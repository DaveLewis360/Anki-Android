// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.analytics

import com.criticalay.GoogleAnalytics
import com.ichi2.anki.common.analytics.AnalyticsEvent

/** Google Analytics transport compiled only into store flavors. */
internal object UsageAnalyticsTransportFactory {
    fun create(configuration: UsageAnalyticsTransportConfiguration): UsageAnalyticsTransport = GoogleUsageAnalyticsTransport(configuration)
}

private class GoogleUsageAnalyticsTransport(
    configuration: UsageAnalyticsTransportConfiguration,
) : UsageAnalyticsTransport {
    private val analytics =
        GoogleAnalytics.builder {
            measurementId = configuration.measurementId
            apiSecret = configuration.apiSecret
            appName = configuration.appName
            appVersion = configuration.appVersion
            enabled = configuration.enabled
            samplePercentage = configuration.samplePercentage
            debug = configuration.debug
        }

    override suspend fun flush() {
        analytics.flush()
    }

    override fun sendScreenView(
        clientId: String,
        screenName: String,
    ) {
        analytics.screenView(clientId).screenName(screenName).sendAsync()
    }

    override fun sendEvent(
        clientId: String,
        event: AnalyticsEvent,
    ) {
        val builder =
            analytics
                .event(clientId)
                .category(event.category)
                .action(event.action)
        event.label?.let { builder.label(it) }
        event.value?.let { builder.value(it) }
        builder.sendAsync()
    }

    override fun sendException(
        clientId: String,
        description: String,
        fatal: Boolean,
    ) {
        analytics
            .exception(clientId)
            .description(description)
            .fatal(fatal)
            .sendAsync()
    }
}
