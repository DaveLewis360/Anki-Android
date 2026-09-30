// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import com.ichi2.anki.ViewerResourceHandler
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.ui.expressive.EXPRESSIVE_CARD_ORIGIN_HOST
import com.ichi2.anki.ui.expressive.ExpressiveSchedulingMutationCommand
import com.ichi2.anki.workarounds.SafeWebViewClient
import com.ichi2.anki.workarounds.SafeWebViewLayout
import com.ichi2.utils.AssetHelper.guessMimeType
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.nio.file.Paths
import kotlin.io.path.pathString

private const val EXPRESSIVE_ASSET_PATH_PREFIX = "/_anki/android_asset/"
internal const val EXPRESSIVE_RENDER_QUERY = "expressive-render"
private val INTERNAL_NAVIGATION_PATTERN =
    Regex(
        "(playsound:[qa]:\\d+|videoended:[qa]:\\d+|videopause:[qa]:\\d+|tts-voices:|" +
            "ankidroid://show-answer|typeentertext:[^\\\"' <]*|typechangetext:[^\\\"' <]*|" +
            "signal:[^\\\"' <]*)",
        RegexOption.IGNORE_CASE,
    )
private val STRICT_RENDER_TOKEN_SCHEMES =
    setOf(
        "playsound",
        "videoended",
        "videopause",
        "tts-voices",
        "missing-user-action",
        "state-mutation-result",
        "state-mutation-error",
    )
private val LEGACY_REVIEWER_SCHEMES = setOf("ankidroid", "typeentertext", "typechangetext", "signal")

internal enum class ExpressiveCardSide {
    QUESTION,
    ANSWER,
}

/** Everything that identifies one DOM render, including renderer recreation and card identity. */
internal data class ExpressiveCardRenderKey(
    val cardId: CardId,
    val side: ExpressiveCardSide,
    val rendererGeneration: Int,
    val baseUrl: String,
    val html: String,
    val renderRevision: Long = 0,
)

internal enum class CardNavigationAction {
    OPEN_EXTERNALLY,
    HANDLE_INTERNALLY,
    BLOCK,
}

/** Card-origin navigation policy. Unknown and privileged schemes fail closed. */
internal fun cardNavigationAction(uri: Uri): CardNavigationAction =
    when (uri.scheme?.lowercase()) {
        "http", "https", "mailto" -> CardNavigationAction.OPEN_EXTERNALLY
        "playsound", "videoended", "videopause", "tts-voices", "missing-user-action",
        "state-mutation-result", "state-mutation-error",
        "ankidroid", "typeentertext", "typechangetext", "signal",
        -> CardNavigationAction.HANDLE_INTERNALLY
        else -> CardNavigationAction.BLOCK
    }

internal fun expressiveDocumentUrl(
    baseUrl: String,
    renderToken: Long,
): String =
    Uri
        .parse(baseUrl)
        .buildUpon()
        .appendQueryParameter(EXPRESSIVE_RENDER_QUERY, renderToken.toString())
        .build()
        .toString()

internal fun htmlWithExpressiveRenderToken(
    html: String,
    renderToken: Long,
): String {
    val suffix = "?$EXPRESSIVE_RENDER_QUERY=$renderToken"
    val taggedCommands = INTERNAL_NAVIGATION_PATTERN.replace(html) { "${it.value}$suffix" }
    val bootstrap =
        "<script>(function(){" +
            "globalThis.__ankidroidInternalNavigationSuffix='$suffix';" +
            "document.addEventListener('focusin',function(){location.href='ankidroid://input-focus$suffix';},true);" +
            "document.addEventListener('focusout',function(){location.href='ankidroid://input-blur$suffix';},true);" +
            "})();</script>"
    return if (taggedCommands.contains("<head>", ignoreCase = true)) {
        taggedCommands.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$bootstrap")
    } else {
        bootstrap + taggedCommands
    }
}

internal fun schedulingMutationJavascript(command: ExpressiveSchedulingMutationCommand): String {
    val key = JSONObject.quote(command.key)
    val states = JSONObject.quote(command.statesWithContextBase64)
    val userSource = JSONObject.quote(command.javascript)
    val script =
        """
        (function() {
          const suffix = globalThis.__ankidroidInternalNavigationSuffix || '';
          const originalFetch = globalThis.fetch.bind(globalThis);
          const fromBase64 = (value) => {
            const binary = atob(value);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            return bytes;
          };
          const toBase64 = (bytes) => {
            let binary = '';
            for (let i = 0; i < bytes.length; i += 0x8000) {
              binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
            }
            return btoa(binary);
          };
          const signal = (scheme, payload) => {
            globalThis.fetch = originalFetch;
            location.href = scheme + ':' + encodeURIComponent(payload) + suffix;
          };
          const requestBytes = async (input, init) => {
            let body = init && init.body;
            if (body == null && input instanceof Request) body = await input.clone().arrayBuffer();
            if (body instanceof ArrayBuffer) return new Uint8Array(body);
            if (ArrayBuffer.isView(body)) return new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
            return new Uint8Array(await new Response(body || new Uint8Array()).arrayBuffer());
          };
          globalThis.fetch = async (input, init) => {
            const rawUrl = input instanceof Request ? input.url : String(input);
            const path = new URL(rawUrl, location.href).pathname;
            if (path === '/_anki/getSchedulingStatesWithContext') {
              return new Response(fromBase64($states), {
                status: 200,
                headers: {'Content-Type': 'application/binary'},
              });
            }
            if (path === '/_anki/setSchedulingStates') {
              const bytes = await requestBytes(input, init);
              signal('state-mutation-result', toBase64(bytes));
              return new Response(new Uint8Array(), {
                status: 200,
                headers: {'Content-Type': 'application/binary'},
              });
            }
            return originalFetch(input, init);
          };
          try {
            if (!globalThis.anki || typeof globalThis.anki.mutateNextCardStates !== 'function') {
              // The app's own runtime is missing: nothing the deck's author could fix, so no error to show them.
              signal('state-mutation-error', '');
              return;
            }
            const userMutation = new Function(
              'states',
              'customData',
              'ctx',
              '"use strict"; return (async () => {' + $userSource + '\n})();'
            );
            Promise.resolve(
              globalThis.anki.mutateNextCardStates(
                $key,
                async (states, customData, ctx) => userMutation(states, customData, ctx)
              )
            ).catch((error) => signal('state-mutation-error', String(error && error.message || error)));
          } catch (error) {
            signal('state-mutation-error', String(error && error.message || error));
          }
        })();
        """.trimIndent()
    return script
}

internal fun internalNavigationPayload(uri: Uri): String =
    Uri.decode(uri.toString().substringAfter(':').substringBefore("?$EXPRESSIVE_RENDER_QUERY="))

internal fun internalNavigationRenderToken(uri: Uri): Long? =
    uri
        .toString()
        .substringAfter("?$EXPRESSIVE_RENDER_QUERY=", missingDelimiterValue = "")
        .substringBefore('&')
        .toLongOrNull()

internal fun Uri.withoutExpressiveRenderToken(): Uri = Uri.parse(toString().substringBefore("?$EXPRESSIVE_RENDER_QUERY="))

@Suppress("DEPRECATION")
internal fun cardGestureBlockedForHitTest(type: Int): Boolean =
    when (type) {
        WebView.HitTestResult.ANCHOR_TYPE,
        WebView.HitTestResult.EDIT_TEXT_TYPE,
        WebView.HitTestResult.EMAIL_TYPE,
        WebView.HitTestResult.GEO_TYPE,
        WebView.HitTestResult.IMAGE_ANCHOR_TYPE,
        WebView.HitTestResult.PHONE_TYPE,
        WebView.HitTestResult.SRC_ANCHOR_TYPE,
        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
        -> true
        else -> false
    }

/** Owns one WebView instance and makes rendering and destruction idempotent. */
internal class ExpressiveCardWebViewHolder(
    val webView: WebView,
    private val destroyWebView: (WebView) -> Unit = { SafeWebViewLayout.destroyWebView(it) },
    private val onRenderStarted: (Long, ExpressiveCardRenderKey, String) -> Unit = { _, _, _ -> },
    private val onRenderCleared: () -> Unit = {},
) {
    internal var renderKey: ExpressiveCardRenderKey? = null
        private set

    private var destroyed = false
    private var nextRenderToken = 0L
    private var touchGeneration = 0L

    @Volatile
    private var cardGestureBlocked = true

    private var touchHitTestBlocked = true

    fun render(key: ExpressiveCardRenderKey) {
        if (destroyed || renderKey == key) return

        val renderToken = ++nextRenderToken
        val documentUrl = expressiveDocumentUrl(key.baseUrl, renderToken)
        onRenderStarted(renderToken, key, documentUrl)
        webView.stopLoading()
        webView.scrollTo(0, 0)
        webView.loadDataWithBaseURL(
            documentUrl,
            htmlWithExpressiveRenderToken(key.html, renderToken),
            "text/html",
            "utf-8",
            null,
        )
        renderKey = key
    }

    fun clear() {
        if (destroyed || renderKey == null) return

        renderKey = null
        onRenderCleared()
        webView.stopLoading()
        webView.loadUrl("about:blank")
    }

    fun evaluateJavascript(script: String) {
        if (destroyed) return
        webView.post {
            if (!destroyed) webView.evaluateJavascript(script, null)
        }
    }

    fun pageUp() {
        evaluateJavascript("window.scrollBy({top:-Math.max(1,window.innerHeight*0.8),behavior:'smooth'});")
    }

    fun pageDown() {
        evaluateJavascript("window.scrollBy({top:Math.max(1,window.innerHeight*0.8),behavior:'smooth'});")
    }

    fun setMediaPlaybackRequiresUserGesture(required: Boolean) {
        if (!destroyed) webView.settings.mediaPlaybackRequiresUserGesture = required
    }

    fun onResume() {
        if (!destroyed) webView.onResume()
    }

    fun onPause() {
        if (!destroyed) webView.onPause()
    }

    fun shouldHandleCardGesture(): Boolean = !cardGestureBlocked

    internal fun beginCardTouch(initiallyBlocked: Boolean): Long {
        touchHitTestBlocked = initiallyBlocked
        // elementFromPoint() completes asynchronously. Until it resolves, fail closed so a fast
        // tap on a button or script-owned control can never leak into a reviewer gesture.
        cardGestureBlocked = true
        return ++touchGeneration
    }

    internal fun updateCardTouchTarget(
        generation: Long,
        blocked: Boolean,
    ) {
        if (!destroyed && generation == touchGeneration) {
            cardGestureBlocked = touchHitTestBlocked || blocked
        }
    }

    fun destroy() {
        if (destroyed) return

        destroyed = true
        renderKey = null
        onRenderCleared()
        destroyWebView(webView)
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun createExpressiveCardWebViewHolder(
    context: Context,
    onOpenExternal: (Uri) -> Unit,
    onRendererGone: () -> Unit,
    onMainFrameError: (String) -> Unit,
    onLocalResourceError: (Uri) -> Unit = {},
    onInternalNavigation: (Uri, ExpressiveCardRenderKey) -> Unit = { _, _ -> },
    onPageFinished: (ExpressiveCardRenderKey) -> Unit = {},
    onPageVisible: (ExpressiveCardRenderKey) -> Unit = {},
): ExpressiveCardWebViewHolder {
    val webView = WebView(context)
    lateinit var client: ExpressiveCardWebViewClient
    val holder =
        ExpressiveCardWebViewHolder(
            webView = webView,
            onRenderStarted = { token, key, documentUrl -> client.beginRender(token, key, documentUrl) },
            onRenderCleared = { client.clearRender() },
        )
    val resourceHandler = ExpressiveCardResourceHandler(context)

    client =
        ExpressiveCardWebViewClient(
            onOpenExternal = onOpenExternal,
            onInternalNavigation = onInternalNavigation,
            onMainFrameError = onMainFrameError,
            onLocalResourceError = onLocalResourceError,
            onPageFinished = { key ->
                webView.evaluateJavascript(
                    "if (typeof onPageFinished === 'function') { onPageFinished(); }",
                    null,
                )
                onPageFinished(key)
            },
            onPageVisible = onPageVisible,
            interceptLocalResource = resourceHandler::shouldInterceptRequest,
        )
    client.setOnRenderProcessGoneListener { crashedWebView ->
        if (crashedWebView === webView) {
            holder.destroy()
            onRendererGone()
        }
    }
    webView.webViewClient = client

    configureExpressiveCardWebView(webView)
    webView.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val generation = holder.beginCardTouch(cardGestureBlockedForHitTest(webView.hitTestResult.type))
            val x = event.x
            val y = event.y
            webView.evaluateJavascript(
                "(function(){var d=window.devicePixelRatio||1;var e=document.elementFromPoint($x/d,$y/d);" +
                    "return !!(e&&e.closest('a,button,input,textarea,select,video,audio,[onclick],[role=button]'));})()",
            ) { result -> holder.updateCardTouchTarget(generation, result == "true") }
        }
        false
    }
    return holder
}

@Suppress("DEPRECATION")
private fun configureExpressiveCardWebView(webView: WebView) {
    with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        loadWithOverviewMode = true
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    webView.isVerticalScrollBarEnabled = false
}

internal class ExpressiveCardWebViewClient(
    private val onOpenExternal: (Uri) -> Unit,
    private val onInternalNavigation: (Uri, ExpressiveCardRenderKey) -> Unit,
    private val onMainFrameError: (String) -> Unit,
    private val onLocalResourceError: (Uri) -> Unit = {},
    private val onPageFinished: (ExpressiveCardRenderKey) -> Unit = {},
    private val onPageVisible: (ExpressiveCardRenderKey) -> Unit = {},
    private val interceptLocalResource: (WebResourceRequest) -> WebResourceResponse? = { null },
) : SafeWebViewClient() {
    private data class PendingRender(
        val token: Long,
        val key: ExpressiveCardRenderKey,
        val documentUrl: String,
        val started: Boolean = false,
        val visible: Boolean = false,
        val finished: Boolean = false,
    )

    private var pendingRender: PendingRender? = null
    private var readyRender: PendingRender? = null

    fun beginRender(
        token: Long,
        key: ExpressiveCardRenderKey,
        documentUrl: String,
    ) {
        pendingRender = PendingRender(token, key, documentUrl)
        readyRender = null
    }

    fun clearRender() {
        pendingRender = null
        readyRender = null
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        handleNavigation(request.url, request.isForMainFrame)
        return true
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(
        view: WebView,
        url: String,
    ): Boolean {
        handleNavigation(Uri.parse(url), isForMainFrame = true)
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme == "https" && uri.host == EXPRESSIVE_CARD_ORIGIN_HOST) {
            return interceptLocalResource(request) ?: missingLocalResourceResponse()
        }
        return null
    }

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        val render = pendingRender ?: return
        if (url == render.documentUrl) pendingRender = render.copy(started = true)
    }

    /**
     * The current render can now be drawn without any content of the previous one, which is when
     * the card pane may show it.
     */
    override fun onPageCommitVisible(
        view: WebView?,
        url: String?,
    ) {
        val render = pendingRender ?: return
        if (render.started && !render.visible && url == render.documentUrl) {
            pendingRender = render.copy(visible = true)
            onPageVisible(render.key)
        }
    }

    override fun onPageFinished(
        view: WebView?,
        url: String?,
    ) {
        val render = pendingRender ?: return
        if (render.started && !render.finished && url == render.documentUrl) {
            val finishedRender = render.copy(finished = true)
            pendingRender = finishedRender
            readyRender = finishedRender
            onPageFinished(render.key)
        }
    }

    private fun handleNavigation(
        uri: Uri,
        isForMainFrame: Boolean,
    ) {
        if (!isForMainFrame) {
            Timber.w("Blocked card subframe navigation: %s", uri.scheme)
            return
        }
        val render = readyRender
        if (render == null) {
            Timber.w("Blocked card navigation before the current render was ready: %s", uri.scheme)
            return
        }

        when (cardNavigationAction(uri)) {
            CardNavigationAction.OPEN_EXTERNALLY -> onOpenExternal(uri)
            CardNavigationAction.HANDLE_INTERNALLY -> {
                val scheme = uri.scheme?.lowercase()
                val token = internalNavigationRenderToken(uri)
                val validToken = token == render.token
                val tokenlessLegacy = token == null && scheme in LEGACY_REVIEWER_SCHEMES
                if (!validToken && !tokenlessLegacy) {
                    val reason = if (scheme in STRICT_RENDER_TOKEN_SCHEMES) "missing or stale" else "stale"
                    Timber.w("Blocked internal navigation with %s render token: %s", reason, uri.scheme)
                    return
                }
                onInternalNavigation(uri.withoutExpressiveRenderToken(), render.key)
            }
            CardNavigationAction.BLOCK -> Timber.w("Blocked card navigation: %s", uri.scheme)
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) onMainFrameError(error.description.toString())
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (
            !request.isForMainFrame &&
            request.url.scheme == "https" &&
            request.url.host == EXPRESSIVE_CARD_ORIGIN_HOST &&
            errorResponse.statusCode == 404
        ) {
            onLocalResourceError(request.url)
        }
    }
}

private class ExpressiveCardResourceHandler(
    context: Context,
) {
    private val assetManager = context.assets
    private val viewerResourceHandler = ViewerResourceHandler(context)

    fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        val encodedPath = request.url.encodedPath ?: return null
        if (!encodedPath.startsWith(EXPRESSIVE_ASSET_PATH_PREFIX)) {
            return viewerResourceHandler.shouldInterceptRequest(request)
        }

        return try {
            val relativePath = Uri.decode(encodedPath.removePrefix(EXPRESSIVE_ASSET_PATH_PREFIX))
            val normalizedPath = Paths.get(relativePath).normalize()
            if (normalizedPath.isAbsolute || normalizedPath.startsWith("..")) {
                Timber.w("Expressive card asset path traversal attempt blocked")
                return null
            }
            WebResourceResponse(
                guessMimeType(relativePath),
                null,
                assetManager.open(normalizedPath.pathString),
            )
        } catch (exc: Exception) {
            Timber.d(exc, "Expressive card asset not found")
            null
        }
    }
}

private fun missingLocalResourceResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
