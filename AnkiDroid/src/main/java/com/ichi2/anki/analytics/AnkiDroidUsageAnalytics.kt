// SPDX-FileCopyrightText: 2026 Ashish Yadav <mailtoashish693@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.analytics

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.R
import com.ichi2.anki.common.analytics.Analytics
import com.ichi2.anki.common.analytics.AnalyticsEvent
import com.ichi2.anki.common.analytics.UsageAnalytics
import com.ichi2.anki.common.android.appContext
import com.ichi2.anki.common.annotations.NeedsTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.utils.ext.getRootCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/** Configuration passed to the flavor-specific analytics transport. */
internal data class UsageAnalyticsTransportConfiguration(
    val measurementId: String,
    val apiSecret: String,
    val appName: String,
    val appVersion: String,
    val enabled: Boolean,
    val samplePercentage: Int,
    val debug: Boolean,
)

/**
 * Network transport boundary for usage analytics.
 *
 * Store flavors provide the GA implementation. The GitHub/F-Droid `full` flavor
 * provides a no-op implementation and does not resolve the GA dependency.
 */
internal interface UsageAnalyticsTransport {
    suspend fun flush()

    fun sendScreenView(
        clientId: String,
        screenName: String,
    )

    fun sendEvent(
        clientId: String,
        event: AnalyticsEvent,
    )

    fun sendException(
        clientId: String,
        description: String,
        fatal: Boolean,
    )
}

/**
 * Facade over the flavor-specific analytics transport. Every send function is
 * a no-op unless analytics exists in this build and the user has opted in.
 */
@NeedsTest("Add coverage for client id persistence and event/exception sending")
internal object AnkiDroidUsageAnalytics : UsageAnalytics {
    const val ANALYTICS_OPTIN_KEY = UsageAnalytics.ANALYTICS_OPTIN_KEY
    private const val ANALYTICS_CLIENT_ID = "googleAnalyticsClientId"
    private const val MAX_EXCEPTION_DESCRIPTION_LENGTH = 150
    private const val ANALYTICS_PREFS = "analyticsPrefs"

    @Volatile private var analytics: UsageAnalyticsTransport? = null

    @Volatile private var optIn = false

    private lateinit var analyticsContext: Application
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientId: String by lazy { getOrCreateClientId(analyticsContext) }

    private val sharedPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (BuildConfig.ENABLE_USAGE_ANALYTICS && key == ANALYTICS_OPTIN_KEY) {
                val newOptIn = prefs.getBoolean(key, false)
                if (newOptIn != optIn) {
                    optIn = newOptIn
                    Timber.i("Setting analytics opt-in to: %b", optIn)
                    if (::analyticsContext.isInitialized) {
                        reinitialize(analyticsContext)
                    }
                }
            }
        }

    private var samplePercentage: AnalyticsSamplePercentage = AnalyticsSamplePercentage.Uninitialized

    lateinit var reportablePreferences: Set<String>
        private set

    fun initialize(context: Context) {
        Timber.i("AnkiDroidUsageAnalytics:: initialize()")
        configure(context)
        if (BuildConfig.ENABLE_USAGE_ANALYTICS) {
            AnalyticsExceptionHandler.install(this::sendAnalyticsException)
        }
    }

    private fun configure(context: Context) {
        analyticsContext = context.applicationContext as Application
        if (!BuildConfig.ENABLE_USAGE_ANALYTICS) {
            optIn = false
            analytics = null
            reportablePreferences = emptySet()
            Timber.i("Usage analytics is not included in this build")
            return
        }

        handlePreferences(analyticsContext)
        if (analytics == null) {
            analytics =
                UsageAnalyticsTransportFactory.create(
                    UsageAnalyticsTransportConfiguration(
                        measurementId = analyticsContext.getString(R.string.ga_trackingId),
                        apiSecret = BuildConfig.ANALYTICS_API_KEY,
                        appName = analyticsContext.getString(R.string.app_name),
                        appVersion = BuildConfig.VERSION_NAME,
                        enabled = optIn,
                        samplePercentage = getAnalyticsSamplePercentage(analyticsContext),
                        debug = BuildConfig.DEBUG,
                    ),
                )
        }
        initializePrefKeys(analyticsContext)
    }

    private fun handlePreferences(context: Context) {
        val userPrefs = context.sharedPrefs()
        optIn = userPrefs.getBoolean(ANALYTICS_OPTIN_KEY, false)
        userPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    fun reinitialize(context: Context) {
        if (!BuildConfig.ENABLE_USAGE_ANALYTICS) return
        Timber.i("reInitialize()")
        serviceScope.launch { rebuild(context) }
    }

    @VisibleForTesting
    internal suspend fun rebuild(context: Context) {
        if (!BuildConfig.ENABLE_USAGE_ANALYTICS) return
        runCatching { analytics?.flush() }.onFailure { e ->
            Timber.w(e, "Failed to flush analytics")
        }
        analytics = null
        configure(context)
    }

    override fun sendAnalyticsScreenView(screen: Any) = sendAnalyticsScreenView(screen.javaClass.simpleName)

    override fun sendAnalyticsScreenView(screenName: String) {
        Timber.d("AnkiDroidUsageAnalytics: screenView($screenName)")
        if (!optIn) return
        analytics?.sendScreenView(clientId, screenName)
    }

    override fun send(event: AnalyticsEvent) {
        Timber.d("AnkiDroidUsageAnalytics: event(category=${event.category} action=${event.action})")
        if (!optIn) return
        analytics?.sendEvent(clientId, event)
    }

    override fun sendAnalyticsException(
        t: Throwable,
        fatal: Boolean,
    ) {
        val cause = t.getRootCause()
        sendAnalyticsException("${cause::class.simpleName}: ${cause.message}", fatal)
    }

    fun sendAnalyticsException(
        description: String,
        fatal: Boolean,
    ) {
        if (!optIn) return
        Timber.d("AnkiDroidUsageAnalytics: exception(fatal=$fatal)")
        analytics?.sendException(clientId, description.take(MAX_EXCEPTION_DESCRIPTION_LENGTH), fatal)
    }

    private fun getOrCreateClientId(context: Context): String {
        Timber.d("AnkiDroidUsageAnalytics:: getting client Id")
        val prefs = context.getSharedPreferences(ANALYTICS_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(ANALYTICS_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(ANALYTICS_CLIENT_ID, it) }
        }
    }

    private fun getAnalyticsSamplePercentage(context: Context): Int {
        if (!samplePercentage.isInitialized) {
            samplePercentage =
                AnalyticsSamplePercentage(context.resources.getInteger(R.integer.ga_sampleFrequency))
        }
        return samplePercentage.value
    }

    private fun initializePrefKeys(context: Context) {
        reportablePreferences =
            AnalyticsConstants.reportablePrefKeys.mapTo(
                HashSet(AnalyticsConstants.reportablePrefKeys.size),
            ) { context.getString(it) }
    }

    var isEnabled: Boolean
        get() = BuildConfig.ENABLE_USAGE_ANALYTICS && optIn
        set(value) {
            if (!BuildConfig.ENABLE_USAGE_ANALYTICS) {
                optIn = false
                return
            }
            optIn = value
            appContext.sharedPrefs().edit {
                putBoolean(ANALYTICS_OPTIN_KEY, value)
            }
            if (::analyticsContext.isInitialized) {
                reinitialize(analyticsContext)
            }
        }

    fun setDevMode(context: Context) {
        if (!BuildConfig.ENABLE_USAGE_ANALYTICS) return
        Timber.d("setDevMode() re-configuring for development analytics tagging")
        samplePercentage = AnalyticsSamplePercentage.Full
        reinitialize(context)
    }
}

/** Initializes the build-appropriate analytics implementation. */
context(application: Application)
fun initializeAnalytics() {
    AnkiDroidUsageAnalytics.initialize(application)
    Analytics.setAnalytics(AnkiDroidUsageAnalytics)
}
