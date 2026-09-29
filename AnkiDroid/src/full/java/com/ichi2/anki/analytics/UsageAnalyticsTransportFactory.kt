// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.analytics

import com.ichi2.anki.common.analytics.AnalyticsEvent

/** Tracker-free transport used by the GitHub/F-Droid `full` flavor. */
internal object UsageAnalyticsTransportFactory {
    fun create(configuration: UsageAnalyticsTransportConfiguration): UsageAnalyticsTransport = NoOpUsageAnalyticsTransport
}

private object NoOpUsageAnalyticsTransport : UsageAnalyticsTransport {
    override suspend fun flush() = Unit

    override fun sendScreenView(
        clientId: String,
        screenName: String,
    ) = Unit

    override fun sendEvent(
        clientId: String,
        event: AnalyticsEvent,
    ) = Unit

    override fun sendException(
        clientId: String,
        description: String,
        fatal: Boolean,
    ) = Unit
}
