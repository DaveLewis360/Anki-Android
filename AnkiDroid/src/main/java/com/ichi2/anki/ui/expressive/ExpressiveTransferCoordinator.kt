// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import anki.cards.cardIds
import anki.collection.OpChanges
import anki.generic.Empty
import anki.import_export.CsvMetadataRequest
import anki.import_export.ExportLimit
import anki.import_export.exportLimit
import anki.notes.noteIds
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.CollectionReplacementRecoveryException
import com.ichi2.anki.ExpressiveRestorePendingException
import com.ichi2.anki.libanki.exportAnkiPackage
import com.ichi2.anki.libanki.exportCardsCsv
import com.ichi2.anki.libanki.exportNotesCsv
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.observability.undoableOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

internal const val EXPRESSIVE_IMPORT_MIME = "application/apkg"
internal const val EXPRESSIVE_COLPKG_MIME = "application/colpkg"
internal const val EXPRESSIVE_TEXT_MIME = "text/plain"

/** Stable, opaque external-delivery identity and a bounded durable claim ledger. */
internal object ExpressiveExternalImportRequests {
    private const val PREFS_NAME = "expressive_external_import_requests_v1"
    private const val CLAIMS_KEY = "claimed_request_ids"
    private const val MAX_CLAIMS = 16

    fun identityFor(source: Intent): String? {
        val uri = ExpressiveTransferFiles.extractSingleUri(source) ?: return null
        val fields = listOf("external-import-v1", source.action.orEmpty(), source.type.orEmpty().lowercase(Locale.ROOT), uri.toString())
        val canonical = buildString { fields.forEach { field -> append(field.length).append(':').append(field) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "v1_" + Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    @Synchronized
    fun claim(
        context: Context,
        requestId: String,
    ): Boolean {
        if (!ExpressiveTransferCoordinatorViewModel.isExternalRequestId(requestId)) return false
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val claims =
            preferences
                .getString(CLAIMS_KEY, "")
                .orEmpty()
                .lineSequence()
                .filter(ExpressiveTransferCoordinatorViewModel::isExternalRequestId)
                .toList()
        if (requestId in claims) return false
        return preferences
            .edit()
            .putString(
                CLAIMS_KEY,
                (claims + requestId)
                    .takeLast(MAX_CLAIMS)
                    .joinToString("\n"),
            ).commit()
    }

    @Synchronized
    internal fun clearClaimsForTests(context: Context) {
        check(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
        )
    }
}

@Serializable
enum class ExpressiveImportFormat { APKG, COLPKG, TEXT }

@Serializable
enum class ExpressiveExportFormat { COLPKG, APKG, NOTES_TEXT, CARDS_TEXT }

@Serializable
enum class ExpressiveSelectionKind { NOTES, CARDS }

enum class ExpressiveTransferOperation { IMPORT, EXPORT }

sealed interface ExpressiveExportScope {
    data object WholeCollection : ExpressiveExportScope

    data class Deck(
        val deckId: Long,
        val displayName: String,
    ) : ExpressiveExportScope

    /** Opaque basename of an app-owned selection token containing one validated ID per line. */
    data class Selection(
        val kind: ExpressiveSelectionKind,
        val token: String,
        val count: Int,
    ) : ExpressiveExportScope
}

@Serializable
data class ExpressiveExportOptions(
    val format: ExpressiveExportFormat,
    val withScheduling: Boolean = true,
    val withDeckConfigs: Boolean = false,
    val withMedia: Boolean = true,
    val legacy: Boolean = false,
    val withHtml: Boolean = true,
    val withTags: Boolean = true,
    val withDeck: Boolean = false,
    val withNotetype: Boolean = false,
    val withGuid: Boolean = false,
)

internal object ExpressiveExportPolicy {
    fun defaultFormat(scope: ExpressiveExportScope): ExpressiveExportFormat =
        when (scope) {
            ExpressiveExportScope.WholeCollection -> ExpressiveExportFormat.COLPKG
            is ExpressiveExportScope.Deck,
            is ExpressiveExportScope.Selection,
            -> ExpressiveExportFormat.APKG
        }

    fun allowedFormats(scope: ExpressiveExportScope): List<ExpressiveExportFormat> =
        when (scope) {
            ExpressiveExportScope.WholeCollection -> ExpressiveExportFormat.entries
            is ExpressiveExportScope.Deck ->
                listOf(
                    ExpressiveExportFormat.APKG,
                    ExpressiveExportFormat.NOTES_TEXT,
                    ExpressiveExportFormat.CARDS_TEXT,
                )
            is ExpressiveExportScope.Selection ->
                when (scope.kind) {
                    ExpressiveSelectionKind.NOTES ->
                        listOf(ExpressiveExportFormat.APKG, ExpressiveExportFormat.NOTES_TEXT)
                    ExpressiveSelectionKind.CARDS ->
                        listOf(ExpressiveExportFormat.APKG, ExpressiveExportFormat.CARDS_TEXT)
                }
        }

    fun isValid(
        scope: ExpressiveExportScope,
        format: ExpressiveExportFormat,
    ): Boolean = scope.isStructurallyValid() && format in allowedFormats(scope)
}

@Serializable
enum class ExpressiveTransferError {
    UNSUPPORTED_FILE,
    UNSUPPORTED_DATABASE,
    PROVIDER_UNAVAILABLE,
    FILE_TOO_LARGE,
    COPY_FAILED,
    IMPORT_FAILED,
    COLLECTION_RECOVERY_FAILED,
    EXPORT_FAILED,
    SAVE_FAILED,
    SHARE_UNAVAILABLE,
    INTERRUPTED,

    /** A collection replacement was refused before anything changed: an earlier restore must finish first. */
    PREVIOUS_RESTORE_UNFINISHED,
}

sealed interface ExpressiveTransferUiState {
    val requestId: Long

    data object Idle : ExpressiveTransferUiState {
        override val requestId: Long = 0
    }

    data class PickingImport(
        override val requestId: Long,
        val launchDispatched: Boolean = false,
    ) : ExpressiveTransferUiState

    /** The provider URI remains only in the retained coroutine argument and is never persisted. */
    data class CopyingImport(
        override val requestId: Long,
    ) : ExpressiveTransferUiState

    data class ConfirmImport(
        override val requestId: Long,
        val format: ExpressiveImportFormat,
        val displayName: String,
        val cacheToken: String,
    ) : ExpressiveTransferUiState

    data class ExportOptions(
        override val requestId: Long,
        val scope: ExpressiveExportScope,
        val options: ExpressiveExportOptions,
    ) : ExpressiveTransferUiState

    data class Running(
        override val requestId: Long,
        val operation: ExpressiveTransferOperation,
        val collectionBlocking: Boolean,
        val mutatingImport: Boolean,
        val ownedToken: String? = null,
        val selectionToken: String? = null,
    ) : ExpressiveTransferUiState

    data class Ready(
        override val requestId: Long,
        val artifactToken: String,
        val displayName: String,
        val format: ExpressiveExportFormat,
        val saveLaunchDispatched: Boolean = false,
    ) : ExpressiveTransferUiState {
        val mime: String
            get() = ExpressiveTransferFiles.mimeFor(format)
    }

    /** Destination URI is deliberately absent; restore converts this to retryable [Ready]. */
    data class SavingExport(
        override val requestId: Long,
        val artifactToken: String,
        val displayName: String,
        val format: ExpressiveExportFormat,
    ) : ExpressiveTransferUiState

    data class Terminal(
        override val requestId: Long,
        val succeeded: Boolean,
        val imported: Boolean,
        val error: ExpressiveTransferError? = null,
        val retainedArtifactToken: String? = null,
        val cleanupSelectionToken: String? = null,
    ) : ExpressiveTransferUiState
}

internal val ExpressiveTransferUiState.isCollectionBlocking: Boolean
    get() = (this as? ExpressiveTransferUiState.Running)?.collectionBlocking == true

internal sealed interface ExpressiveTransferLaunch {
    data class OpenImport(
        val requestId: Long,
        val intent: Intent,
    ) : ExpressiveTransferLaunch

    data class BackendImport(
        val requestId: Long,
        val intent: Intent,
    ) : ExpressiveTransferLaunch

    data class SaveExport(
        val requestId: Long,
        val intent: Intent,
    ) : ExpressiveTransferLaunch
}

internal data class StagedImport(
    val format: ExpressiveImportFormat,
    val displayName: String,
    val cacheToken: String,
)

internal object ExpressiveTransferFiles {
    internal const val MAX_IMPORT_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_DISPLAY_NAME = 120
    private const val MAX_TOKEN_LENGTH = 128
    private const val MAX_SELECTION_IDS = 5_000_000
    private const val MAX_STALE_FILES = 24
    private const val STALE_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    private val tokenPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val packageMimes = setOf("application/apkg", "application/vnd.anki", "application/x-apkg", "application/zip")
    private val collectionMimes = setOf("application/colpkg", "application/x-colpkg")
    private val textMimes = setOf("text/plain", "text/csv", "text/tsv", "text/comma-separated-values", "text/tab-separated-values")

    fun openDocumentIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                (packageMimes + collectionMimes + textMimes + "application/octet-stream").toTypedArray(),
            )
        }

    fun createDocumentIntent(ready: ExpressiveTransferUiState.Ready): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = ready.mime
            putExtra(Intent.EXTRA_TITLE, ready.displayName)
        }

    fun createShareIntent(
        context: Context,
        ready: ExpressiveTransferUiState.Ready,
        uri: Uri,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = ready.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, ready.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun extractSingleUri(intent: Intent?): Uri? {
        intent ?: return null
        val candidates =
            buildList {
                intent.data?.let(::add)
                intent.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) } }
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            }.distinct()
        return candidates.singleOrNull()
    }

    suspend fun stageImport(
        context: Context,
        intent: Intent,
        maxBytes: Long = MAX_IMPORT_BYTES,
    ): Result<StagedImport> =
        withContext(Dispatchers.IO) {
            var output: File? = null
            try {
                val uri = extractSingleUri(intent) ?: throw TransferFailure(ExpressiveTransferError.PROVIDER_UNAVAILABLE)
                if (uri.scheme !in setOf("content", "file", "android.resource")) {
                    throw TransferFailure(ExpressiveTransferError.UNSUPPORTED_FILE)
                }
                val (rawName, size) = queryMetadata(context, uri)
                val suppliedMime = context.contentResolver.getType(uri) ?: intent.type
                if (size != null && size > maxBytes) throw TransferFailure(ExpressiveTransferError.FILE_TOO_LARGE)
                val format = classify(rawName, suppliedMime)
                val extension = importExtension(format, rawName)
                val fallback = genericImportName(format, extension)
                val displayName = sanitizeDisplayName(rawName ?: fallback, fallback)
                val root = importRoot(context).apply { mkdirs() }
                cleanupStale(root)
                output = createOpaqueFile(root, extension)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > maxBytes) throw TransferFailure(ExpressiveTransferError.FILE_TOO_LARGE)
                            stream.write(buffer, 0, read)
                        }
                        stream.fd.sync()
                    }
                } ?: throw TransferFailure(ExpressiveTransferError.PROVIDER_UNAVAILABLE)
                Result.success(StagedImport(format, displayName, output.name))
            } catch (failure: Throwable) {
                output?.delete()
                if (failure is CancellationException) throw failure
                Result.failure(failure)
            }
        }

    fun ownedImportFile(
        context: Context,
        token: String,
    ): File? = ownedFile(importRoot(context), token)

    fun ownedExportFile(
        context: Context,
        token: String,
    ): File? = context.externalCacheDir?.let { ownedFile(exportRoot(it), token) }

    fun ownedSelectionFile(
        context: Context,
        token: String,
    ): File? = ownedFile(selectionRoot(context), token)

    fun deleteImport(
        context: Context,
        token: String?,
    ) {
        token?.let { ownedImportFile(context, it)?.delete() }
    }

    fun deleteExport(
        context: Context,
        token: String?,
    ) {
        token?.let { ownedExportFile(context, it)?.delete() }
    }

    fun deleteSelection(
        context: Context,
        token: String?,
    ) {
        token?.let { ownedSelectionFile(context, it)?.delete() }
    }

    fun writeSelectionToken(
        context: Context,
        selection: BrowseSelection,
    ): ExpressiveExportScope.Selection {
        require(selection.isValid() && !selection.isEmpty && selection.rowIds.size <= MAX_SELECTION_IDS)
        val root = selectionRoot(context).apply { mkdirs() }
        cleanupStale(root)
        val token = createOpaqueFile(root, "ids")
        token.bufferedWriter().use { writer -> selection.rowIds.forEach { writer.appendLine(it.value.toString()) } }
        return ExpressiveExportScope.Selection(
            kind = if (selection.mode == ExpressiveBrowseMode.NOTES) ExpressiveSelectionKind.NOTES else ExpressiveSelectionKind.CARDS,
            token = token.name,
            count = selection.rowIds.size,
        )
    }

    fun readSelectionToken(
        context: Context,
        scope: ExpressiveExportScope.Selection,
    ): List<Long> {
        require(scope.count in 1..MAX_SELECTION_IDS)
        val file = ownedSelectionFile(context, scope.token) ?: error("Invalid selection token")
        val values = file.useLines { lines -> lines.take(scope.count + 1).map { it.toLong() }.toList() }
        require(values.size == scope.count && values.all { it > 0 } && values.distinct().size == values.size)
        return values
    }

    fun createExportFile(
        context: Context,
        format: ExpressiveExportFormat,
    ): File {
        val root = requireNotNull(context.externalCacheDir) { "External cache is unavailable" }.let(::exportRoot).apply { mkdirs() }
        cleanupStale(root)
        return createOpaqueFile(root, extensionFor(format))
    }

    fun displayNameForExport(
        format: ExpressiveExportFormat,
        prefix: String,
    ): String {
        val safePrefix = sanitizeDisplayName(prefix, "Anki-export").substringBeforeLast('.').ifBlank { "Anki-export" }
        return "$safePrefix-${System.currentTimeMillis()}.${extensionFor(format)}"
    }

    suspend fun copyReadyToProvider(
        context: Context,
        ready: ExpressiveTransferUiState.Ready,
        destination: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val source = ownedExportFile(context, ready.artifactToken) ?: return@withContext false
            runCatching {
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                } ?: return@withContext false
                true
            }.getOrDefault(false)
        }

    fun cleanupOwnedRoots(
        context: Context,
        activeTokens: Set<String> = emptySet(),
    ) {
        cleanupStale(importRoot(context), activeTokens)
        cleanupStale(selectionRoot(context), activeTokens)
        context.externalCacheDir?.let { cleanupStale(exportRoot(it), activeTokens) }
    }

    fun isOpaqueToken(value: String?): Boolean =
        value != null && value.length <= MAX_TOKEN_LENGTH && tokenPattern.matches(value) && ".." !in value

    fun mimeFor(format: ExpressiveImportFormat): String =
        when (format) {
            ExpressiveImportFormat.APKG -> EXPRESSIVE_IMPORT_MIME
            ExpressiveImportFormat.COLPKG -> EXPRESSIVE_COLPKG_MIME
            ExpressiveImportFormat.TEXT -> EXPRESSIVE_TEXT_MIME
        }

    fun mimeFor(format: ExpressiveExportFormat): String =
        when (format) {
            ExpressiveExportFormat.APKG -> EXPRESSIVE_IMPORT_MIME
            ExpressiveExportFormat.COLPKG -> EXPRESSIVE_COLPKG_MIME
            ExpressiveExportFormat.NOTES_TEXT, ExpressiveExportFormat.CARDS_TEXT -> EXPRESSIVE_TEXT_MIME
        }

    fun genericImportName(format: ExpressiveImportFormat): String =
        genericImportName(format, if (format == ExpressiveImportFormat.TEXT) "txt" else format.name.lowercase(Locale.ROOT))

    fun genericExportName(format: ExpressiveExportFormat): String = "Anki-export.${extensionFor(format)}"

    private fun importExtension(
        format: ExpressiveImportFormat,
        rawName: String?,
    ): String =
        when (format) {
            ExpressiveImportFormat.APKG -> "apkg"
            ExpressiveImportFormat.COLPKG -> "colpkg"
            ExpressiveImportFormat.TEXT ->
                rawName
                    ?.substringAfterLast('.', "txt")
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf { it in setOf("csv", "tsv", "txt") }
                    ?: "txt"
        }

    private fun genericImportName(
        format: ExpressiveImportFormat,
        extension: String,
    ): String =
        when (format) {
            ExpressiveImportFormat.APKG -> "import.apkg"
            ExpressiveImportFormat.COLPKG -> "collection.colpkg"
            ExpressiveImportFormat.TEXT -> "import.$extension"
        }

    private fun extensionFor(format: ExpressiveExportFormat): String =
        when (format) {
            ExpressiveExportFormat.COLPKG -> "colpkg"
            ExpressiveExportFormat.APKG -> "apkg"
            ExpressiveExportFormat.NOTES_TEXT, ExpressiveExportFormat.CARDS_TEXT -> "txt"
        }

    private fun classify(
        name: String?,
        mime: String?,
    ): ExpressiveImportFormat {
        val normalized = name?.trim()?.lowercase(Locale.ROOT)
        if (normalized?.endsWith(".anki2") == true) throw TransferFailure(ExpressiveTransferError.UNSUPPORTED_DATABASE)
        return when {
            normalized == "collection.apkg" || normalized?.endsWith(".colpkg") == true || mime in collectionMimes ->
                ExpressiveImportFormat.COLPKG
            normalized?.endsWith(".apkg") == true || (normalized == null && mime in packageMimes) -> ExpressiveImportFormat.APKG
            normalized?.endsWith(".csv") == true || normalized?.endsWith(".tsv") == true || normalized?.endsWith(".txt") == true ||
                mime in textMimes -> ExpressiveImportFormat.TEXT
            else -> throw TransferFailure(ExpressiveTransferError.UNSUPPORTED_FILE)
        }
    }

    private fun queryMetadata(
        context: Context,
        uri: Uri,
    ): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        val cursor: Cursor? =
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex).takeIf { value -> value >= 0 }
            }
        }
        return name to size
    }

    private fun sanitizeDisplayName(
        value: String,
        fallback: String,
    ): String =
        value
            .replace(Regex("[\\p{Cntrl}/\\\\]+"), "_")
            .trim()
            .take(MAX_DISPLAY_NAME)
            .ifBlank { fallback }

    private fun importRoot(context: Context): File = File(context.cacheDir, "expressive-import").canonicalFile

    private fun selectionRoot(context: Context): File = File(context.cacheDir, "expressive-selection").canonicalFile

    private fun exportRoot(externalCacheDir: File): File = File(externalCacheDir, "export").canonicalFile

    private fun createOpaqueFile(
        root: File,
        extension: String,
    ): File =
        File(root, "${UUID.randomUUID()}.$extension")
            .canonicalFile
            .also {
                require(
                    it.parentFile == root &&
                        it.absoluteFile == it.canonicalFile,
                )
            }

    private fun ownedFile(
        root: File,
        token: String,
    ): File? =
        runCatching {
            if (!isOpaqueToken(token)) return null
            val canonicalRoot = root.canonicalFile
            val unresolved = File(canonicalRoot, token).absoluteFile
            val candidate = unresolved.canonicalFile
            candidate.takeIf { candidate == unresolved && candidate.parentFile == canonicalRoot && candidate.isFile }
        }.getOrNull()

    private fun cleanupStale(
        root: File,
        activeTokens: Set<String> = emptySet(),
    ) {
        val cutoff = System.currentTimeMillis() - STALE_AGE_MILLIS
        val ownedFiles =
            root
                .listFiles()
                .orEmpty()
                .mapNotNull { ownedFile(root, it.name) }
                .filterNot { it.name in activeTokens }
        ownedFiles.filter { it.lastModified() < cutoff }.forEach(File::delete)
        ownedFiles
            .filter(File::exists)
            .sortedByDescending(File::lastModified)
            .drop(MAX_STALE_FILES)
            .forEach(File::delete)
    }
}

internal class TransferFailure(
    val safeError: ExpressiveTransferError,
    cause: Throwable? = null,
) : Exception(cause)

internal interface ExpressiveTransferGateway {
    suspend fun importApkg(path: String): OpChanges

    suspend fun importText(path: String): OpChanges

    suspend fun replaceCollection(path: String)

    suspend fun export(
        path: String,
        scope: ExpressiveExportScope,
        options: ExpressiveExportOptions,
        context: Context,
    )

    fun abort()
}

internal fun expressiveExportLimit(
    scope: ExpressiveExportScope,
    context: Context,
): ExportLimit =
    when (scope) {
        ExpressiveExportScope.WholeCollection -> exportLimit { wholeCollection = Empty.getDefaultInstance() }
        is ExpressiveExportScope.Deck -> exportLimit { deckId = scope.deckId }
        is ExpressiveExportScope.Selection -> {
            val ids = ExpressiveTransferFiles.readSelectionToken(context, scope)
            when (scope.kind) {
                ExpressiveSelectionKind.NOTES -> exportLimit { noteIds = noteIds { noteIds.addAll(ids) } }
                ExpressiveSelectionKind.CARDS -> exportLimit { cardIds = cardIds { cids.addAll(ids) } }
            }
        }
    }

internal suspend fun runCommittedCollectionReplacement(
    replace: suspend () -> Unit,
    invalidate: suspend () -> Unit,
) {
    val recoveryFailure =
        try {
            replace()
            null
        } catch (failure: CollectionReplacementRecoveryException) {
            failure
        }
    withContext(NonCancellable) { invalidate() }
    recoveryFailure?.let { throw TransferFailure(ExpressiveTransferError.COLLECTION_RECOVERY_FAILED, it) }
}

internal object DefaultExpressiveTransferGateway : ExpressiveTransferGateway {
    override suspend fun importApkg(path: String): OpChanges {
        val presets = withCol { backend.getImportAnkiPackagePresets() }
        return undoableOp { backend.importAnkiPackage(path, presets).changes }
    }

    override suspend fun importText(path: String): OpChanges {
        val metadata = withCol { backend.getCsvMetadata(CsvMetadataRequest.newBuilder().setPath(path).build()) }
        return undoableOp { backend.importCsv(path, metadata).changes }
    }

    override suspend fun replaceCollection(path: String) {
        runCommittedCollectionReplacement(
            replace = { CollectionManager.importColpkg(path) },
            invalidate = {
                withContext(Dispatchers.Main) {
                    ChangeManager.notifySubscribers(
                        OpChanges
                            .newBuilder()
                            .setCard(true)
                            .setNote(true)
                            .setDeck(true)
                            .setStudyQueues(true)
                            .setBrowserTable(true)
                            .setNoteText(true)
                            .build(),
                        this@DefaultExpressiveTransferGateway,
                    )
                }
            },
        )
    }

    override suspend fun export(
        path: String,
        scope: ExpressiveExportScope,
        options: ExpressiveExportOptions,
        context: Context,
    ) {
        require(ExpressiveExportPolicy.isValid(scope, options.format))
        val limit = expressiveExportLimit(scope, context)
        when (options.format) {
            ExpressiveExportFormat.COLPKG -> {
                require(scope == ExpressiveExportScope.WholeCollection)
                CollectionManager.exportCollectionPackageGuaranteed(path, options.withMedia, options.legacy)
            }
            ExpressiveExportFormat.APKG ->
                withCol {
                    exportAnkiPackage(path, options.withScheduling, options.withDeckConfigs, options.withMedia, limit, options.legacy)
                }
            ExpressiveExportFormat.NOTES_TEXT ->
                withCol {
                    exportNotesCsv(
                        path,
                        options.withHtml,
                        options.withTags,
                        options.withDeck,
                        options.withNotetype,
                        options.withGuid,
                        limit,
                    )
                }
            ExpressiveExportFormat.CARDS_TEXT -> withCol { exportCardsCsv(path, options.withHtml, limit) }
        }
    }

    override fun abort() = CollectionManager.getBackend().setWantsAbort()
}

internal class ExpressiveTransferCoordinatorViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val gateway: ExpressiveTransferGateway = DefaultExpressiveTransferGateway,
) : ViewModel() {
    private val restored = savedStateHandle.get<String>(STATE_KEY)?.let(TransferStateCodec::decode)
    private val initial = restored.toSafeRestoredState()
    private val _state = MutableStateFlow<ExpressiveTransferUiState>(initial)
    val state: StateFlow<ExpressiveTransferUiState> = _state.asStateFlow()
    private val _launches = Channel<ExpressiveTransferLaunch>(Channel.BUFFERED)
    val launches: Flow<ExpressiveTransferLaunch> = _launches.receiveAsFlow()
    private val pendingImportResults = ArrayDeque<Long>()
    private val pendingSaveResults = ArrayDeque<Long>()
    private var operation: Job? = null

    init {
        savedStateHandle.remove<String>(LEGACY_STATE_KEY)
        persist(initial)
        if (initial is ExpressiveTransferUiState.PickingImport && !initial.launchDispatched) {
            _launches.trySend(ExpressiveTransferLaunch.OpenImport(initial.requestId, ExpressiveTransferFiles.openDocumentIntent()))
        }
    }

    fun startExternalImport(
        context: Context,
        externalRequestId: String,
    ): Long? {
        if (_state.value !is ExpressiveTransferUiState.Idle || !isExternalRequestId(externalRequestId)) return null
        val consumed = consumedExternalRequestIds()
        if (externalRequestId in consumed || !ExpressiveExternalImportRequests.claim(context, externalRequestId)) return null
        savedStateHandle[CONSUMED_EXTERNAL_IDS_KEY] = ArrayList((consumed + externalRequestId).takeLast(MAX_CONSUMED_EXTERNAL_IDS))
        val requestId = nextId()
        set(ExpressiveTransferUiState.PickingImport(requestId, launchDispatched = true))
        return requestId
    }

    fun startImport(): Boolean {
        if (_state.value !is ExpressiveTransferUiState.Idle) return false
        val requestId = nextId()
        val newState = ExpressiveTransferUiState.PickingImport(requestId)
        set(newState)
        _launches.trySend(ExpressiveTransferLaunch.OpenImport(requestId, ExpressiveTransferFiles.openDocumentIntent()))
        return true
    }

    /** Called immediately before ActivityResult launch so a synchronous/late result has an exact owner. */
    fun markImportLaunchDispatched(requestId: Long): Boolean {
        val current = _state.value as? ExpressiveTransferUiState.PickingImport ?: return false
        if (current.requestId != requestId || current.launchDispatched) return false
        pendingImportResults.addLast(requestId)
        set(current.copy(launchDispatched = true))
        return true
    }

    fun importLaunchFailed(requestId: Long) {
        pendingImportResults.remove(requestId)
        val current = _state.value as? ExpressiveTransferUiState.PickingImport ?: return
        if (current.requestId == requestId) {
            set(ExpressiveTransferUiState.Terminal(requestId, false, true, ExpressiveTransferError.PROVIDER_UNAVAILABLE))
        }
    }

    fun consumeImportResultRequestId(): Long? = pendingImportResults.pollFirst()

    fun pickerCancelled(requestId: Long) {
        val current = _state.value as? ExpressiveTransferUiState.PickingImport ?: return
        if (current.requestId == requestId) set(ExpressiveTransferUiState.Terminal(requestId, false, true))
    }

    fun stageImport(
        context: Context,
        requestId: Long,
        source: Intent,
    ) {
        val current = _state.value as? ExpressiveTransferUiState.PickingImport ?: return
        if (current.requestId != requestId) return
        set(ExpressiveTransferUiState.CopyingImport(requestId))
        operation =
            viewModelScope.launch {
                try {
                    val staged = ExpressiveTransferFiles.stageImport(context, source)
                    staged.fold(
                        onSuccess = { stagedImport(context, requestId, it) },
                        onFailure = { failure ->
                            stagingFailed(
                                requestId,
                                (failure as? TransferFailure)?.safeError ?: ExpressiveTransferError.COPY_FAILED,
                            )
                        },
                    )
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        val copying = _state.value as? ExpressiveTransferUiState.CopyingImport
                        if (copying?.requestId == requestId) {
                            set(ExpressiveTransferUiState.Terminal(requestId, false, true, ExpressiveTransferError.INTERRUPTED))
                        }
                    }
                }
            }
    }

    internal fun stagedImport(
        context: Context,
        requestId: Long,
        staged: StagedImport,
    ) {
        val current = _state.value as? ExpressiveTransferUiState.CopyingImport
        if (current?.requestId != requestId) {
            ExpressiveTransferFiles.deleteImport(context, staged.cacheToken)
            return
        }
        set(
            ExpressiveTransferUiState.ConfirmImport(
                requestId,
                staged.format,
                staged.displayName,
                staged.cacheToken,
            ),
        )
    }

    internal fun stagingFailed(
        requestId: Long,
        error: ExpressiveTransferError,
    ) {
        val current = _state.value
        if (current.requestId != requestId || current !is ExpressiveTransferUiState.CopyingImport) return
        set(ExpressiveTransferUiState.Terminal(requestId, false, true, error))
    }

    fun confirmImport(context: Context) {
        val current = _state.value as? ExpressiveTransferUiState.ConfirmImport ?: return
        val source = ExpressiveTransferFiles.ownedImportFile(context, current.cacheToken)
        if (source == null) {
            set(ExpressiveTransferUiState.Terminal(current.requestId, false, true, ExpressiveTransferError.COPY_FAILED))
            return
        }
        val collectionReplacement = current.format == ExpressiveImportFormat.COLPKG
        set(
            ExpressiveTransferUiState.Running(
                current.requestId,
                ExpressiveTransferOperation.IMPORT,
                collectionReplacement,
                mutatingImport = true,
                ownedToken = current.cacheToken,
            ),
        )
        if (!collectionReplacement) {
            _launches.trySend(
                ExpressiveTransferLaunch.BackendImport(
                    current.requestId,
                    ExpressiveImportPageActivity.getIntent(context, source.path, current.format, current.requestId),
                ),
            )
            return
        }
        operation =
            viewModelScope.launch {
                try {
                    // Once admitted, replacement commit, global invalidation (inside the gateway),
                    // source disposition, and successful terminal publication are one boundary.
                    // cancel() first signals backend abort, so a pre-commit backend operation can
                    // still fail without allowing post-commit cancellation to skip invalidation.
                    withContext(NonCancellable) {
                        gateway.replaceCollection(source.path)
                        ExpressiveTransferFiles.deleteImport(context, current.cacheToken)
                        setIfRequestMatches(ExpressiveTransferUiState.Terminal(current.requestId, true, true))
                    }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        val terminal = _state.value as? ExpressiveTransferUiState.Terminal
                        if (terminal?.requestId == current.requestId && terminal.succeeded) return@withContext
                        ExpressiveTransferFiles.deleteImport(context, current.cacheToken)
                        setIfRequestMatches(ExpressiveTransferUiState.Terminal(current.requestId, false, true))
                    }
                } catch (failure: TransferFailure) {
                    withContext(NonCancellable) {
                        ExpressiveTransferFiles.deleteImport(context, current.cacheToken)
                        setIfRequestMatches(
                            ExpressiveTransferUiState.Terminal(
                                current.requestId,
                                false,
                                true,
                                failure.safeError,
                            ),
                        )
                    }
                } catch (_: ExpressiveRestorePendingException) {
                    // Refused before anything changed: an earlier Expressive restore still owns its journal.
                    withContext(NonCancellable) {
                        ExpressiveTransferFiles.deleteImport(context, current.cacheToken)
                        setIfRequestMatches(
                            ExpressiveTransferUiState.Terminal(
                                current.requestId,
                                false,
                                true,
                                ExpressiveTransferError.PREVIOUS_RESTORE_UNFINISHED,
                            ),
                        )
                    }
                } catch (_: Exception) {
                    withContext(NonCancellable) {
                        val terminal = _state.value as? ExpressiveTransferUiState.Terminal
                        if (terminal?.requestId == current.requestId && terminal.succeeded) return@withContext
                        ExpressiveTransferFiles.deleteImport(context, current.cacheToken)
                        setIfRequestMatches(
                            ExpressiveTransferUiState.Terminal(
                                current.requestId,
                                false,
                                true,
                                ExpressiveTransferError.IMPORT_FAILED,
                            ),
                        )
                    }
                }
            }
    }

    fun completeBackendImport(
        context: Context,
        requestId: Long,
        succeeded: Boolean,
    ) {
        val current = _state.value
        if (
            current is ExpressiveTransferUiState.Terminal &&
            current.requestId == requestId &&
            current.imported &&
            current.error == ExpressiveTransferError.INTERRUPTED
        ) {
            ExpressiveTransferFiles.deleteImport(context, current.retainedArtifactToken)
            set(
                ExpressiveTransferUiState.Terminal(
                    requestId,
                    succeeded,
                    imported = true,
                    error = if (succeeded) null else ExpressiveTransferError.IMPORT_FAILED,
                ),
            )
            return
        }
        current as? ExpressiveTransferUiState.Running ?: return
        if (current.requestId != requestId || !current.mutatingImport || current.collectionBlocking) return
        ExpressiveTransferFiles.deleteImport(context, current.ownedToken)
        set(
            ExpressiveTransferUiState.Terminal(
                requestId,
                succeeded,
                imported = true,
                error = if (succeeded) null else ExpressiveTransferError.IMPORT_FAILED,
            ),
        )
    }

    fun failBackendImportLaunch(
        context: Context,
        requestId: Long,
    ) {
        val current = _state.value as? ExpressiveTransferUiState.Running ?: return
        if (current.requestId != requestId || current.collectionBlocking) return
        ExpressiveTransferFiles.deleteImport(context, current.ownedToken)
        set(ExpressiveTransferUiState.Terminal(requestId, false, true, ExpressiveTransferError.IMPORT_FAILED))
    }

    fun startExport(
        scope: ExpressiveExportScope,
        defaultFormat: ExpressiveExportFormat? = null,
    ): Boolean {
        if (_state.value !is ExpressiveTransferUiState.Idle || !scope.isStructurallyValid()) return false
        val format = defaultFormat ?: ExpressiveExportPolicy.defaultFormat(scope)
        if (!ExpressiveExportPolicy.isValid(scope, format)) return false
        set(ExpressiveTransferUiState.ExportOptions(nextId(), scope, ExpressiveExportOptions(format)))
        return true
    }

    fun updateExportOptions(options: ExpressiveExportOptions) {
        val current = _state.value as? ExpressiveTransferUiState.ExportOptions ?: return
        if (!ExpressiveExportPolicy.isValid(current.scope, options.format)) return
        set(current.copy(options = options))
    }

    fun confirmExport(context: Context) {
        val current = _state.value as? ExpressiveTransferUiState.ExportOptions ?: return
        if (!ExpressiveExportPolicy.isValid(current.scope, current.options.format)) {
            (current.scope as? ExpressiveExportScope.Selection)?.let { ExpressiveTransferFiles.deleteSelection(context, it.token) }
            set(ExpressiveTransferUiState.Terminal(current.requestId, false, false, ExpressiveTransferError.EXPORT_FAILED))
            return
        }
        val prefix =
            when (val scope = current.scope) {
                ExpressiveExportScope.WholeCollection -> "Anki-collection"
                is ExpressiveExportScope.Deck -> scope.displayName
                is ExpressiveExportScope.Selection ->
                    if (scope.kind == ExpressiveSelectionKind.NOTES) "Selected-notes" else "Selected-cards"
            }
        val artifact =
            runCatching { ExpressiveTransferFiles.createExportFile(context, current.options.format) }.getOrElse {
                (current.scope as? ExpressiveExportScope.Selection)?.let { ExpressiveTransferFiles.deleteSelection(context, it.token) }
                set(ExpressiveTransferUiState.Terminal(current.requestId, false, false, ExpressiveTransferError.EXPORT_FAILED))
                return
            }
        val selectionToken = (current.scope as? ExpressiveExportScope.Selection)?.token
        set(
            ExpressiveTransferUiState.Running(
                current.requestId,
                ExpressiveTransferOperation.EXPORT,
                current.options.format == ExpressiveExportFormat.COLPKG,
                mutatingImport = false,
                ownedToken = artifact.name,
                selectionToken = selectionToken,
            ),
        )
        operation =
            viewModelScope.launch {
                try {
                    gateway.export(artifact.path, current.scope, current.options, context)
                    ExpressiveTransferFiles.deleteSelection(context, selectionToken)
                    setIfRequestMatches(
                        ExpressiveTransferUiState.Ready(
                            current.requestId,
                            artifact.name,
                            ExpressiveTransferFiles.displayNameForExport(current.options.format, prefix),
                            current.options.format,
                        ),
                    )
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        ExpressiveTransferFiles.deleteExport(context, artifact.name)
                        ExpressiveTransferFiles.deleteSelection(context, selectionToken)
                        setIfRequestMatches(ExpressiveTransferUiState.Terminal(current.requestId, false, false))
                    }
                } catch (_: Exception) {
                    ExpressiveTransferFiles.deleteExport(context, artifact.name)
                    ExpressiveTransferFiles.deleteSelection(context, selectionToken)
                    setIfRequestMatches(
                        ExpressiveTransferUiState.Terminal(
                            current.requestId,
                            false,
                            false,
                            ExpressiveTransferError.EXPORT_FAILED,
                        ),
                    )
                }
            }
    }

    fun requestSave() {
        val current = _state.value as? ExpressiveTransferUiState.Ready ?: return
        if (current.saveLaunchDispatched) return
        set(current.copy(saveLaunchDispatched = true))
        _launches.trySend(ExpressiveTransferLaunch.SaveExport(current.requestId, ExpressiveTransferFiles.createDocumentIntent(current)))
    }

    fun markSaveLaunchDispatched(requestId: Long): Boolean {
        val current = _state.value as? ExpressiveTransferUiState.Ready ?: return false
        if (current.requestId != requestId || !current.saveLaunchDispatched) return false
        if (requestId !in pendingSaveResults) pendingSaveResults.addLast(requestId)
        return true
    }

    fun saveLaunchFailed(requestId: Long) {
        pendingSaveResults.remove(requestId)
        val current = _state.value as? ExpressiveTransferUiState.Ready ?: return
        if (current.requestId == requestId) set(current.copy(saveLaunchDispatched = false))
    }

    fun consumeSaveResultRequestId(): Long? = pendingSaveResults.pollFirst()

    fun saveResult(
        context: Context,
        requestId: Long,
        destination: Uri?,
        succeededResult: Boolean,
    ) {
        val ready = _state.value as? ExpressiveTransferUiState.Ready ?: return
        if (ready.requestId != requestId) return
        if (!succeededResult || destination == null) {
            set(ready.copy(saveLaunchDispatched = false))
            return
        }
        set(ExpressiveTransferUiState.SavingExport(requestId, ready.artifactToken, ready.displayName, ready.format))
        operation =
            viewModelScope.launch {
                val copied =
                    try {
                        ExpressiveTransferFiles.copyReadyToProvider(context, ready, destination)
                    } catch (_: CancellationException) {
                        false
                    }
                withContext(NonCancellable) {
                    val current = _state.value as? ExpressiveTransferUiState.SavingExport
                    if (current?.requestId != requestId) return@withContext
                    if (copied) {
                        set(ExpressiveTransferUiState.Terminal(requestId, true, false))
                        // Persist success before source cleanup. A process death here is recovered by bounded stale cleanup.
                        ExpressiveTransferFiles.deleteExport(context, ready.artifactToken)
                    } else {
                        set(ready.copy(saveLaunchDispatched = false))
                    }
                }
            }
    }

    fun cleanupInterruptedArtifacts(context: Context) {
        val current = _state.value
        val active =
            when (current) {
                is ExpressiveTransferUiState.ConfirmImport -> setOf(current.cacheToken)
                is ExpressiveTransferUiState.Running -> setOfNotNull(current.ownedToken, current.selectionToken)
                is ExpressiveTransferUiState.Ready -> setOf(current.artifactToken)
                is ExpressiveTransferUiState.SavingExport -> setOf(current.artifactToken)
                is ExpressiveTransferUiState.Terminal -> setOfNotNull(current.retainedArtifactToken)
                else -> emptySet()
            }
        if (current is ExpressiveTransferUiState.Terminal && current.error == ExpressiveTransferError.INTERRUPTED) {
            ExpressiveTransferFiles.deleteSelection(context, current.cleanupSelectionToken)
        }
        ExpressiveTransferFiles.cleanupOwnedRoots(context, active)
    }

    fun cancel(context: Context): Boolean =
        when (val current = _state.value) {
            is ExpressiveTransferUiState.CopyingImport,
            is ExpressiveTransferUiState.Running,
            is ExpressiveTransferUiState.SavingExport,
            -> {
                gateway.abort()
                operation?.cancel()
                false
            }
            is ExpressiveTransferUiState.ConfirmImport -> {
                ExpressiveTransferFiles.deleteImport(context, current.cacheToken)
                set(ExpressiveTransferUiState.Idle)
                true
            }
            is ExpressiveTransferUiState.Ready -> {
                ExpressiveTransferFiles.deleteExport(context, current.artifactToken)
                set(ExpressiveTransferUiState.Idle)
                true
            }
            is ExpressiveTransferUiState.ExportOptions -> {
                (current.scope as? ExpressiveExportScope.Selection)?.let { ExpressiveTransferFiles.deleteSelection(context, it.token) }
                set(ExpressiveTransferUiState.Idle)
                true
            }
            is ExpressiveTransferUiState.Terminal -> {
                ExpressiveTransferFiles.deleteImport(context, current.retainedArtifactToken)
                ExpressiveTransferFiles.deleteExport(context, current.retainedArtifactToken)
                ExpressiveTransferFiles.deleteSelection(context, current.cleanupSelectionToken)
                set(ExpressiveTransferUiState.Idle)
                true
            }
            is ExpressiveTransferUiState.PickingImport -> {
                set(ExpressiveTransferUiState.Idle)
                true
            }
            ExpressiveTransferUiState.Idle -> true
        }

    fun dismissTerminal(context: Context? = null) {
        val current = _state.value as? ExpressiveTransferUiState.Terminal ?: return
        if (context != null) {
            ExpressiveTransferFiles.deleteImport(context, current.retainedArtifactToken)
            ExpressiveTransferFiles.deleteExport(context, current.retainedArtifactToken)
            ExpressiveTransferFiles.deleteSelection(context, current.cleanupSelectionToken)
        }
        set(ExpressiveTransferUiState.Idle)
    }

    private fun setIfRequestMatches(value: ExpressiveTransferUiState) {
        if (_state.value.requestId == value.requestId) set(value)
    }

    private fun nextId(): Long =
        (savedStateHandle.get<Long>(NEXT_ID_KEY) ?: 1L)
            .coerceAtLeast(1L)
            .also { savedStateHandle[NEXT_ID_KEY] = if (it == Long.MAX_VALUE) 1L else it + 1L }

    private fun set(value: ExpressiveTransferUiState) {
        _state.value = value
        persist(value)
    }

    private fun persist(value: ExpressiveTransferUiState) {
        savedStateHandle[STATE_KEY] = TransferStateCodec.encode(value)
    }

    private fun consumedExternalRequestIds(): List<String> =
        savedStateHandle.get<ArrayList<String>>(CONSUMED_EXTERNAL_IDS_KEY).orEmpty().filter(::isExternalRequestId)

    companion object {
        internal const val STATE_KEY = "expressive.transfer.state.v2"
        internal const val CONSUMED_EXTERNAL_IDS_KEY = "expressive.transfer.consumed-external-ids.v1"
        private const val LEGACY_STATE_KEY = "expressive.transfer.state.v1"
        private const val NEXT_ID_KEY = "expressive.transfer.next-id.v2"
        private const val MAX_CONSUMED_EXTERNAL_IDS = 16
        private val externalRequestPattern = Regex("[A-Za-z0-9_-]{16,64}")

        internal fun isExternalRequestId(value: String): Boolean = externalRequestPattern.matches(value)

        fun factory(gateway: ExpressiveTransferGateway = DefaultExpressiveTransferGateway): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ExpressiveTransferCoordinatorViewModel(createSavedStateHandle(), gateway) }
            }
    }
}

private fun ExpressiveTransferUiState?.toSafeRestoredState(): ExpressiveTransferUiState =
    when (this) {
        null -> ExpressiveTransferUiState.Idle
        is ExpressiveTransferUiState.PickingImport ->
            if (launchDispatched) {
                ExpressiveTransferUiState.Terminal(requestId, false, true, ExpressiveTransferError.INTERRUPTED)
            } else {
                this
            }
        is ExpressiveTransferUiState.CopyingImport ->
            ExpressiveTransferUiState.Terminal(requestId, false, true, ExpressiveTransferError.INTERRUPTED)
        is ExpressiveTransferUiState.Running ->
            ExpressiveTransferUiState.Terminal(
                requestId,
                false,
                mutatingImport,
                ExpressiveTransferError.INTERRUPTED,
                retainedArtifactToken = ownedToken.takeIf { mutatingImport },
                cleanupSelectionToken = selectionToken,
            )
        is ExpressiveTransferUiState.SavingExport ->
            ExpressiveTransferUiState.Ready(
                requestId,
                artifactToken,
                ExpressiveTransferFiles.genericExportName(format),
                format,
                saveLaunchDispatched = false,
            )
        is ExpressiveTransferUiState.Ready ->
            copy(
                displayName = ExpressiveTransferFiles.genericExportName(format),
                saveLaunchDispatched = false,
            )
        else -> this
    }

private fun ExpressiveExportScope.isStructurallyValid(): Boolean =
    when (this) {
        ExpressiveExportScope.WholeCollection -> true
        is ExpressiveExportScope.Deck -> deckId > 0 && displayName.isNotBlank() && displayName.length <= 120
        is ExpressiveExportScope.Selection ->
            count in 1..5_000_000 && ExpressiveTransferFiles.isOpaqueToken(token)
    }

@Serializable
private data class TransferSnapshot(
    val version: Int = 2,
    val kind: String,
    val requestId: Long,
    val launchDispatched: Boolean = false,
    val importFormat: ExpressiveImportFormat? = null,
    val exportFormat: ExpressiveExportFormat? = null,
    val scopeKind: String? = null,
    val deckId: Long? = null,
    val selectionKind: ExpressiveSelectionKind? = null,
    val count: Int? = null,
    val token: String? = null,
    val secondaryToken: String? = null,
    val options: ExpressiveExportOptions? = null,
    val operation: String? = null,
    val collectionBlocking: Boolean = false,
    val mutatingImport: Boolean = false,
    val succeeded: Boolean = false,
    val imported: Boolean = false,
    val error: ExpressiveTransferError? = null,
)

private object TransferStateCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(state: ExpressiveTransferUiState): String = json.encodeToString(state.toSnapshot())

    fun decode(value: String): ExpressiveTransferUiState? =
        runCatching { json.decodeFromString<TransferSnapshot>(value) }
            .getOrNull()
            ?.takeIf { it.version == 2 && it.requestId >= 0 }
            ?.toState()

    private fun ExpressiveTransferUiState.toSnapshot(): TransferSnapshot =
        when (this) {
            ExpressiveTransferUiState.Idle -> TransferSnapshot(kind = "idle", requestId = 0)
            is ExpressiveTransferUiState.PickingImport ->
                TransferSnapshot(kind = "picking", requestId = requestId, launchDispatched = launchDispatched)
            is ExpressiveTransferUiState.CopyingImport -> TransferSnapshot(kind = "copying-import", requestId = requestId)
            is ExpressiveTransferUiState.ConfirmImport ->
                TransferSnapshot(kind = "confirm-import", requestId = requestId, importFormat = format, token = cacheToken)
            is ExpressiveTransferUiState.ExportOptions ->
                scope.toSnapshot(requestId, options)
            is ExpressiveTransferUiState.Running ->
                TransferSnapshot(
                    kind = "running",
                    requestId = requestId,
                    operation = operation.name,
                    collectionBlocking = collectionBlocking,
                    mutatingImport = mutatingImport,
                    token = ownedToken,
                    secondaryToken = selectionToken,
                )
            is ExpressiveTransferUiState.Ready ->
                TransferSnapshot(kind = "ready", requestId = requestId, exportFormat = format, token = artifactToken)
            is ExpressiveTransferUiState.SavingExport ->
                TransferSnapshot(kind = "saving-export", requestId = requestId, exportFormat = format, token = artifactToken)
            is ExpressiveTransferUiState.Terminal ->
                TransferSnapshot(
                    kind = "terminal",
                    requestId = requestId,
                    succeeded = succeeded,
                    imported = imported,
                    error = error,
                    token = retainedArtifactToken,
                    secondaryToken = cleanupSelectionToken,
                )
        }

    private fun ExpressiveExportScope.toSnapshot(
        requestId: Long,
        options: ExpressiveExportOptions,
    ): TransferSnapshot =
        when (this) {
            ExpressiveExportScope.WholeCollection ->
                TransferSnapshot(kind = "export-options", requestId = requestId, scopeKind = "whole", options = options)
            is ExpressiveExportScope.Deck ->
                TransferSnapshot(kind = "export-options", requestId = requestId, scopeKind = "deck", deckId = deckId, options = options)
            is ExpressiveExportScope.Selection ->
                TransferSnapshot(
                    kind = "export-options",
                    requestId = requestId,
                    scopeKind = "selection",
                    selectionKind = kind,
                    count = count,
                    token = token,
                    options = options,
                )
        }

    private fun TransferSnapshot.toState(): ExpressiveTransferUiState? {
        if (token != null && !ExpressiveTransferFiles.isOpaqueToken(token)) return null
        if (secondaryToken != null && !ExpressiveTransferFiles.isOpaqueToken(secondaryToken)) return null
        return when (kind) {
            "idle" -> ExpressiveTransferUiState.Idle.takeIf { requestId == 0L }
            "picking" ->
                ExpressiveTransferUiState
                    .PickingImport(requestId, launchDispatched)
                    .takeIf { requestId > 0 }
            "copying-import" ->
                ExpressiveTransferUiState
                    .CopyingImport(requestId)
                    .takeIf { requestId > 0 }
            "confirm-import" -> {
                val format = importFormat ?: return null
                val ownedToken = token ?: return null
                ExpressiveTransferUiState
                    .ConfirmImport(
                        requestId,
                        format,
                        ExpressiveTransferFiles.genericImportName(format),
                        ownedToken,
                    ).takeIf { requestId > 0 }
            }
            "export-options" -> {
                val restoredOptions = options ?: return null
                val scope = toScope() ?: return null
                ExpressiveTransferUiState
                    .ExportOptions(requestId, scope, restoredOptions)
                    .takeIf { requestId > 0 && ExpressiveExportPolicy.isValid(scope, restoredOptions.format) }
            }
            "running" -> {
                val restoredOperation =
                    operation
                        ?.let {
                            runCatching { ExpressiveTransferOperation.valueOf(it) }
                                .getOrNull()
                        } ?: return null
                ExpressiveTransferUiState
                    .Running(
                        requestId,
                        restoredOperation,
                        collectionBlocking,
                        mutatingImport,
                        token,
                        secondaryToken,
                    ).takeIf { requestId > 0 }
            }
            "ready" -> {
                val format = exportFormat ?: return null
                val artifact = token ?: return null
                ExpressiveTransferUiState
                    .Ready(
                        requestId,
                        artifact,
                        ExpressiveTransferFiles.genericExportName(format),
                        format,
                    ).takeIf { requestId > 0 }
            }
            "saving-export" -> {
                val format = exportFormat ?: return null
                val artifact = token ?: return null
                ExpressiveTransferUiState
                    .SavingExport(
                        requestId,
                        artifact,
                        ExpressiveTransferFiles.genericExportName(format),
                        format,
                    ).takeIf { requestId > 0 }
            }
            "terminal" ->
                ExpressiveTransferUiState
                    .Terminal(requestId, succeeded, imported, error, token, secondaryToken)
                    .takeIf { requestId > 0 }
            else -> null
        }
    }

    private fun TransferSnapshot.toScope(): ExpressiveExportScope? =
        when (scopeKind) {
            "whole" -> ExpressiveExportScope.WholeCollection
            "deck" -> deckId?.takeIf { it > 0 }?.let { ExpressiveExportScope.Deck(it, "Deck") }
            "selection" -> {
                val restoredKind = selectionKind ?: return null
                val restoredToken = token ?: return null
                val restoredCount = count ?: return null
                ExpressiveExportScope.Selection(restoredKind, restoredToken, restoredCount)
            }
            else -> null
        }?.takeIf(ExpressiveExportScope::isStructurallyValid)
}
