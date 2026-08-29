// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

/** Collection capabilities used by the expressive card browser. */
interface ExpressiveBrowseGateway {
    suspend fun searchCards(
        query: String,
        filter: String,
        limit: Int = 60,
    ): List<UiCard>
}
