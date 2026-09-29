// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import com.ichi2.anki.R
import com.ichi2.anki.common.android.themes.disableXiaomiForceDarkMode
import com.ichi2.anki.pages.AnkiPackageImporterFragment
import com.ichi2.anki.pages.CsvImporter
import com.ichi2.anki.pages.ExpressiveImportPageHost
import com.ichi2.themes.Themes

/**
 * Standalone host for Anki's backend-owned APKG/CSV option pages.
 *
 * This activity deliberately does not inherit from AnkiActivity and does not create a Classic task
 * stack. It returns only a typed success/cancellation result to the Expressive coordinator.
 */
class ExpressiveImportPageActivity :
    FragmentActivity(),
    ExpressiveImportPageHost {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The backend pages are View-based: apply the user's light or dark theme, palette, and
        // dynamic color before the window exists, as AnkiActivity does.
        Themes.setTheme(this, savedInstanceState)
        disableXiaomiForceDarkMode(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_single_fragment)
        if (savedInstanceState != null) return
        val path =
            intent.getStringExtra(EXTRA_OWNED_PATH) ?: run {
                finishCancelled()
                return
            }
        val format =
            intent.getStringExtra(EXTRA_FORMAT)?.let { runCatching { ExpressiveImportFormat.valueOf(it) }.getOrNull() }
                ?: run {
                    finishCancelled()
                    return
                }
        val fragment =
            when (format) {
                ExpressiveImportFormat.APKG -> AnkiPackageImporterFragment.create(path)
                ExpressiveImportFormat.TEXT -> CsvImporter.create(path)
                ExpressiveImportFormat.COLPKG ->
                    run {
                        finishCancelled()
                        return
                    }
            }
        supportFragmentManager.commit { replace(R.id.fragment_container, fragment, TAG) }
    }

    override fun onBackendImportFinished() {
        runOnUiThread {
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_RESULT, RESULT_IMPORTED)
                    .putExtra(EXTRA_REQUEST_ID, requestId()),
            )
            finish()
        }
    }

    override fun onBackendImportCancelled() = finishCancelled()

    private fun finishCancelled() {
        setResult(
            Activity.RESULT_CANCELED,
            Intent()
                .putExtra(EXTRA_RESULT, RESULT_CANCELLED)
                .putExtra(EXTRA_REQUEST_ID, requestId()),
        )
        finish()
    }

    private fun requestId(): Long = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)

    companion object {
        private const val EXTRA_OWNED_PATH = "expressive.import-page.owned-path"
        private const val EXTRA_FORMAT = "expressive.import-page.format"
        internal const val EXTRA_REQUEST_ID = "expressive.import-page.request-id"
        internal const val EXTRA_RESULT = "expressive.import-page.result"
        internal const val RESULT_IMPORTED = "imported"
        internal const val RESULT_CANCELLED = "cancelled"
        private const val TAG = "expressive-import-page"

        fun getIntent(
            context: Context,
            path: String,
            format: ExpressiveImportFormat,
            requestId: Long,
        ): Intent =
            Intent(context, ExpressiveImportPageActivity::class.java)
                .putExtra(EXTRA_OWNED_PATH, path)
                .putExtra(EXTRA_FORMAT, format.name)
                .putExtra(EXTRA_REQUEST_ID, requestId)
    }
}
