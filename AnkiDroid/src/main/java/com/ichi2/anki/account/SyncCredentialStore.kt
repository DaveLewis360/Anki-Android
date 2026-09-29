// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.annotation.VisibleForTesting
import com.ichi2.anki.settings.Prefs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One atomically owned AnkiWeb account record. Only [generation] is safe to persist in WorkManager. */
data class SyncCredentialSnapshot(
    val hkey: String,
    val generation: String,
    val username: String? = null,
    val endpoint: String? = null,
)

/** Storage seam whose non-encrypted implementations live exclusively in test sources. */
internal interface SyncCredentialStorage {
    fun read(context: Context): SyncCredentialSnapshot?

    fun replace(
        context: Context,
        hkey: String,
        username: String?,
    ): SyncCredentialSnapshot

    fun updateEndpoint(
        context: Context,
        expectedGeneration: String,
        endpoint: String,
    ): Boolean

    fun clear(
        context: Context,
        expectedGeneration: String?,
    ): Boolean
}

private class CredentialMigrationDeferredException(
    cause: Throwable,
) : Exception(cause)

/**
 * Stores account identity, redirect ownership and the reusable hkey as one encrypted transaction.
 *
 * The production payload is encrypted with an app-private Android Keystore key and written through
 * [AtomicFile] under [Context.getNoBackupFilesDir]. Preference mirrors remain for Classic callers,
 * but are never authoritative and are changed under the same generation lock.
 */
object SyncCredentialStore {
    @Volatile
    private var testStorage: SyncCredentialStorage? = null

    private val storage: SyncCredentialStorage
        get() = testStorage ?: EncryptedSyncCredentialStorage

    @Synchronized
    fun read(context: Context): SyncCredentialSnapshot? {
        val credential =
            try {
                storage.read(context)
            } catch (_: CredentialMigrationDeferredException) {
                // The legacy hkey remains available for a later migration attempt, but is not used now.
                return null
            }
        if (credential == null) {
            clearPreferenceMirrors()
        } else {
            mirror(credential)
        }
        Prefs.hkey = null
        return credential
    }

    @Synchronized
    fun replace(
        context: Context,
        hkey: String,
        username: String? = null,
    ): SyncCredentialSnapshot {
        val credential = storage.replace(context, hkey, username?.takeIf(String::isNotBlank))
        mirror(credential)
        Prefs.hkey = null
        return credential
    }

    @Synchronized
    fun updateEndpoint(
        context: Context,
        expectedGeneration: String,
        endpoint: String,
    ): Boolean {
        val updated = storage.updateEndpoint(context, expectedGeneration, endpoint)
        if (updated) Prefs.currentSyncUri = endpoint
        return updated
    }

    /** Clears only the account generation the caller observed, or any account when omitted. */
    @Synchronized
    fun clear(
        context: Context,
        expectedGeneration: String? = null,
    ): Boolean {
        val cleared = storage.clear(context, expectedGeneration)
        if (cleared || expectedGeneration == null) clearPreferenceMirrors()
        Prefs.hkey = null
        return cleared
    }

    @VisibleForTesting
    internal fun installStorageForTesting(storage: SyncCredentialStorage?) {
        testStorage = storage
    }

    private fun mirror(credential: SyncCredentialSnapshot) {
        Prefs.username = credential.username
        Prefs.currentSyncUri = credential.endpoint
    }

    private fun clearPreferenceMirrors() {
        Prefs.username = null
        Prefs.currentSyncUri = null
    }
}

private object EncryptedSyncCredentialStorage : SyncCredentialStorage {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ankidroid.sync.credential.v1"
    private const val FILE_NAME = "ankiweb-sync-credential-v1"
    private const val LEGACY_FORMAT_VERSION = 1
    private const val FORMAT_VERSION = 2
    private const val GCM_IV_BYTES = 12
    private const val MAX_RECORD_BYTES = 128 * 1024
    private const val MAX_GENERATION_BYTES = 64
    private const val MAX_HKEY_BYTES = 16 * 1024
    private const val MAX_USERNAME_BYTES = 4 * 1024
    private const val MAX_ENDPOINT_BYTES = 16 * 1024

    override fun read(context: Context): SyncCredentialSnapshot? {
        val file = credentialFile(context)
        val payload =
            try {
                readBounded(file)
            } catch (_: FileNotFoundException) {
                return migrateLegacyCredential(context)
            } catch (_: Exception) {
                quarantineUnreadable(context)
                return null
            }
        val decoded =
            try {
                decrypt(payload)
            } catch (_: Exception) {
                quarantineUnreadable(context)
                return null
            }
        if (decoded.legacyFormat) write(context, decoded.credential)
        return decoded.credential
    }

    override fun replace(
        context: Context,
        hkey: String,
        username: String?,
    ): SyncCredentialSnapshot {
        requireValidUtf8(hkey, MAX_HKEY_BYTES)
        require(hkey.isNotBlank())
        username?.let { requireValidUtf8(it, MAX_USERNAME_BYTES) }
        val credential =
            SyncCredentialSnapshot(
                hkey = hkey,
                generation = UUID.randomUUID().toString(),
                username = username,
                endpoint = null,
            )
        write(context, credential)
        return credential
    }

    override fun updateEndpoint(
        context: Context,
        expectedGeneration: String,
        endpoint: String,
    ): Boolean {
        require(endpoint.isNotBlank())
        requireValidUtf8(endpoint, MAX_ENDPOINT_BYTES)
        val current = read(context) ?: return false
        if (current.generation != expectedGeneration) return false
        write(context, current.copy(endpoint = endpoint))
        return true
    }

    override fun clear(
        context: Context,
        expectedGeneration: String?,
    ): Boolean {
        val current = read(context)
        if (expectedGeneration != null && current?.generation != expectedGeneration) return false
        if (current == null) return false
        deleteCredential(context)
        return true
    }

    private fun migrateLegacyCredential(context: Context): SyncCredentialSnapshot? {
        val legacyHkey = Prefs.hkey?.takeIf(String::isNotBlank) ?: return null
        return try {
            val credential =
                SyncCredentialSnapshot(
                    hkey = legacyHkey,
                    generation = UUID.randomUUID().toString(),
                    username = null,
                    endpoint = null,
                )
            validate(credential)
            write(context, credential)
            credential
        } catch (exception: Exception) {
            throw CredentialMigrationDeferredException(exception)
        }
    }

    private fun write(
        context: Context,
        credential: SyncCredentialSnapshot,
    ) {
        validate(credential)
        val file = credentialFile(context)
        val output = file.startWrite()
        try {
            output.write(encrypt(credential))
            output.flush()
            file.finishWrite(output)
        } catch (throwable: Throwable) {
            file.failWrite(output)
            throw throwable
        }
    }

    private fun encrypt(credential: SyncCredentialSnapshot): ByteArray {
        val plain =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeUtf8(credential.generation)
                    output.writeUtf8(credential.hkey)
                    output.writeNullableUtf8(credential.username)
                    output.writeNullableUtf8(credential.endpoint)
                }
                bytes.toByteArray()
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        check(cipher.iv.size == GCM_IV_BYTES)
        val encrypted = cipher.doFinal(plain)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
        }
    }

    private fun decrypt(payload: ByteArray): DecodedCredential {
        require(payload.size <= MAX_RECORD_BYTES)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val version = input.readInt()
            require(version == LEGACY_FORMAT_VERSION || version == FORMAT_VERSION)
            val iv = input.readBoundedBytes(GCM_IV_BYTES)
            require(iv.size == GCM_IV_BYTES)
            val encrypted = input.readBoundedBytes(MAX_RECORD_BYTES)
            require(input.available() == 0)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(encrypted)
            val credential =
                if (version == LEGACY_FORMAT_VERSION) {
                    decodeLegacyPlaintext(plain)
                } else {
                    decodePlaintext(plain)
                }
            validate(credential)
            DecodedCredential(credential, legacyFormat = version == LEGACY_FORMAT_VERSION)
        }
    }

    private fun decodeLegacyPlaintext(plain: ByteArray): SyncCredentialSnapshot =
        DataInputStream(ByteArrayInputStream(plain)).use { input ->
            SyncCredentialSnapshot(
                generation = input.readUTF(),
                hkey = input.readUTF(),
                username = null,
                endpoint = null,
            ).also { require(input.available() == 0) }
        }

    private fun decodePlaintext(plain: ByteArray): SyncCredentialSnapshot =
        DataInputStream(ByteArrayInputStream(plain)).use { input ->
            SyncCredentialSnapshot(
                generation = input.readUtf8(MAX_GENERATION_BYTES),
                hkey = input.readUtf8(MAX_HKEY_BYTES),
                username = input.readNullableUtf8(MAX_USERNAME_BYTES),
                endpoint = input.readNullableUtf8(MAX_ENDPOINT_BYTES),
            ).also { require(input.available() == 0) }
        }

    private fun validate(credential: SyncCredentialSnapshot) {
        UUID.fromString(credential.generation)
        require(credential.hkey.isNotBlank())
        requireValidUtf8(credential.generation, MAX_GENERATION_BYTES)
        requireValidUtf8(credential.hkey, MAX_HKEY_BYTES)
        credential.username?.let { requireValidUtf8(it, MAX_USERNAME_BYTES) }
        credential.endpoint?.let {
            require(it.isNotBlank())
            requireValidUtf8(it, MAX_ENDPOINT_BYTES)
        }
    }

    private fun readBounded(file: AtomicFile): ByteArray =
        file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= MAX_RECORD_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun DataInputStream.readBoundedBytes(maxBytes: Int): ByteArray {
        val size = readInt()
        require(size in 1..maxBytes)
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableUtf8(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            writeUtf8(value)
        }
    }

    private fun DataInputStream.readUtf8(maxBytes: Int): String {
        val size = readInt()
        require(size in 1..maxBytes)
        return String(ByteArray(size).also(::readFully), StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableUtf8(maxBytes: Int): String? {
        val size = readInt()
        if (size == -1) return null
        require(size in 1..maxBytes)
        return String(ByteArray(size).also(::readFully), StandardCharsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun quarantineUnreadable(context: Context) {
        deleteCredential(context)
        Prefs.hkey = null
        Prefs.username = null
        Prefs.currentSyncUri = null
    }

    private fun deleteCredential(context: Context) {
        credentialFile(context).delete()
        runCatching {
            keyStore().apply {
                if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun credentialFile(context: Context): AtomicFile = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    private data class DecodedCredential(
        val credential: SyncCredentialSnapshot,
        val legacyFormat: Boolean,
    )
}

private fun requireValidUtf8(
    value: String,
    maxBytes: Int,
) {
    require(value.toByteArray(StandardCharsets.UTF_8).size in 1..maxBytes)
}
