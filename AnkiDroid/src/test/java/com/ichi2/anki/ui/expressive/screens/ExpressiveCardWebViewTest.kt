// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.ui.expressive.EXPRESSIVE_ANDROID_ASSET_ORIGIN
import com.ichi2.anki.ui.expressive.EXPRESSIVE_CARD_ORIGIN
import com.ichi2.anki.ui.expressive.mediaDirectoryBaseUrl
import com.ichi2.anki.ui.expressive.rewriteCardMediaOrigin
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.sameInstance
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExpressiveCardWebViewTest {
    @Test
    fun `render key includes card side and renderer identity`() {
        val question = renderKey(cardId = 1, side = ExpressiveCardSide.QUESTION)

        assertThat(question, not(equalTo(question.copy(cardId = 2))))
        assertThat(question, not(equalTo(question.copy(side = ExpressiveCardSide.ANSWER))))
        assertThat(question, not(equalTo(question.copy(rendererGeneration = 1))))
        assertThat(question, equalTo(question.copy()))
    }

    @Test
    fun `holder reloads identical HTML for a different card or side with unique document tokens`() {
        val webView = mock(WebView::class.java)
        val holder = ExpressiveCardWebViewHolder(webView, destroyWebView = {})
        val firstQuestion = renderKey(cardId = 1, side = ExpressiveCardSide.QUESTION)

        holder.render(firstQuestion)
        holder.render(firstQuestion)
        holder.render(firstQuestion.copy(cardId = 2))
        holder.render(firstQuestion.copy(cardId = 2, side = ExpressiveCardSide.ANSWER))

        verify(webView, times(3)).stopLoading()
        verify(webView, times(3)).scrollTo(0, 0)
        verify(webView).loadDataWithBaseURL(
            expressiveDocumentUrl(firstQuestion.baseUrl, 1),
            htmlWithExpressiveRenderToken(firstQuestion.html, 1),
            "text/html",
            "utf-8",
            null,
        )
        verify(webView).loadDataWithBaseURL(
            expressiveDocumentUrl(firstQuestion.baseUrl, 2),
            htmlWithExpressiveRenderToken(firstQuestion.html, 2),
            "text/html",
            "utf-8",
            null,
        )
        verify(webView).loadDataWithBaseURL(
            expressiveDocumentUrl(firstQuestion.baseUrl, 3),
            htmlWithExpressiveRenderToken(firstQuestion.html, 3),
            "text/html",
            "utf-8",
            null,
        )
    }

    @Test
    fun `holder clears stale content and ignores lifecycle calls after one destroy`() {
        val webView = mock(WebView::class.java)
        var destroyCalls = 0
        val holder = ExpressiveCardWebViewHolder(webView, destroyWebView = { destroyCalls++ })

        holder.render(renderKey())
        holder.clear()
        holder.clear()
        holder.onResume()
        holder.onPause()
        holder.destroy()
        holder.destroy()
        holder.onPause()
        holder.onResume()
        holder.render(renderKey(cardId = 2))

        verify(webView, times(1)).loadUrl("about:blank")
        verify(webView, times(1)).onResume()
        verify(webView, times(1)).onPause()
        verify(webView, times(1)).loadDataWithBaseURL(
            expressiveDocumentUrl(EXPRESSIVE_CARD_ORIGIN, 1),
            htmlWithExpressiveRenderToken("<html>same</html>", 1),
            "text/html",
            "utf-8",
            null,
        )
        assertThat(destroyCalls, equalTo(1))
        assertThat(holder.renderKey, equalTo(null))
    }

    @Test
    fun `navigation policy opens only allowlisted external schemes`() {
        val cases =
            mapOf(
                "https://example.com" to CardNavigationAction.OPEN_EXTERNALLY,
                "http://example.com" to CardNavigationAction.OPEN_EXTERNALLY,
                "mailto:user@example.com" to CardNavigationAction.OPEN_EXTERNALLY,
                "playsound:q:0" to CardNavigationAction.HANDLE_INTERNALLY,
                "videoended:" to CardNavigationAction.HANDLE_INTERNALLY,
                "videopause:" to CardNavigationAction.HANDLE_INTERNALLY,
                "tts-voices:" to CardNavigationAction.HANDLE_INTERNALLY,
                "missing-user-action:1" to CardNavigationAction.HANDLE_INTERNALLY,
                "file:///data/user/0/private" to CardNavigationAction.BLOCK,
                "content://contacts/1" to CardNavigationAction.BLOCK,
                "data:text/html,<script>alert(1)</script>" to CardNavigationAction.BLOCK,
                "javascript:alert(1)" to CardNavigationAction.BLOCK,
                "intent://example.com#Intent;scheme=https;end" to CardNavigationAction.BLOCK,
                "android-app://com.example" to CardNavigationAction.BLOCK,
                "custom-scheme:value" to CardNavigationAction.BLOCK,
            )

        for ((url, expected) in cases) {
            assertThat(url, cardNavigationAction(Uri.parse(url)), equalTo(expected))
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `created WebView uses restricted card-origin settings`() {
        val holder =
            createExpressiveCardWebViewHolder(
                context = ApplicationProvider.getApplicationContext(),
                onOpenExternal = {},
                onRendererGone = {},
                onMainFrameError = {},
            )
        val webView = holder.webView

        assertThat(webView.settings.javaScriptEnabled, equalTo(true))
        assertThat(webView.settings.domStorageEnabled, equalTo(true))
        assertThat(webView.settings.allowFileAccess, equalTo(false))
        assertThat(webView.settings.allowContentAccess, equalTo(false))
        assertThat(webView.settings.allowFileAccessFromFileURLs, equalTo(false))
        assertThat(webView.settings.allowUniversalAccessFromFileURLs, equalTo(false))
        assertThat(webView.settings.javaScriptCanOpenWindowsAutomatically, equalTo(false))
        assertThat(webView.settings.mixedContentMode, equalTo(WebSettings.MIXED_CONTENT_NEVER_ALLOW))
        assertThat(CookieManager.getInstance().acceptThirdPartyCookies(webView), equalTo(false))

        holder.destroy()
    }

    @Test
    fun `safe origin delegates local resources and missing resources return 404`() {
        val webView = mock(WebView::class.java)
        val existingRequest = request("${EXPRESSIVE_CARD_ORIGIN}sound.mp3")
        val missingRequest = request("${EXPRESSIVE_CARD_ORIGIN}missing.mp3")
        val externalRequest = request("https://example.com/image.png")
        val existingResponse = WebResourceResponse("audio/mpeg", null, ByteArrayInputStream(byteArrayOf(1)))
        var intercepted = 0
        val client =
            ExpressiveCardWebViewClient(
                onOpenExternal = {},
                onInternalNavigation = { _, _ -> },
                onMainFrameError = {},
                interceptLocalResource = { request ->
                    intercepted++
                    if (request === existingRequest) existingResponse else null
                },
            )

        assertThat(client.shouldInterceptRequest(webView, existingRequest), sameInstance(existingResponse))
        assertThat(client.shouldInterceptRequest(webView, missingRequest)?.statusCode, equalTo(404))
        assertThat(client.shouldInterceptRequest(webView, externalRequest), nullValue())
        assertThat(intercepted, equalTo(2))
    }

    @Test
    fun `Android assets are served only through the safe asset path`() {
        val holder =
            createExpressiveCardWebViewHolder(
                context = ApplicationProvider.getApplicationContext(),
                onOpenExternal = {},
                onRendererGone = {},
                onMainFrameError = {},
            )
        val client = holder.webView.webViewClient as ExpressiveCardWebViewClient

        val asset =
            client.shouldInterceptRequest(
                holder.webView,
                request("${EXPRESSIVE_ANDROID_ASSET_ORIGIN}backend/js/mathjax.js", method = "GET"),
            )
        val traversal =
            client.shouldInterceptRequest(
                holder.webView,
                request("${EXPRESSIVE_ANDROID_ASSET_ORIGIN}%2E%2E%2Fankidroid.css", method = "GET"),
            )

        assertThat(asset, notNullValue())
        assertThat(traversal?.statusCode, equalTo(404))
        holder.destroy()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `stale page completion and navigation cannot control the current render`() {
        val webView = mock(WebView::class.java)
        val first = renderKey(cardId = 1)
        val second = renderKey(cardId = 2)
        val firstUrl = expressiveDocumentUrl(first.baseUrl, 1)
        val secondUrl = expressiveDocumentUrl(second.baseUrl, 2)
        val finished = mutableListOf<ExpressiveCardRenderKey>()
        val navigated = mutableListOf<ExpressiveCardRenderKey>()
        val client =
            ExpressiveCardWebViewClient(
                onOpenExternal = {},
                onInternalNavigation = { _, key -> navigated += key },
                onMainFrameError = {},
                onPageFinished = { finished += it },
            )

        client.beginRender(1, first, firstUrl)
        client.onPageStarted(webView, firstUrl, null)
        client.beginRender(2, second, secondUrl)
        client.onPageFinished(webView, firstUrl)
        client.shouldOverrideUrlLoading(webView, "playsound:q:0?$EXPRESSIVE_RENDER_QUERY=1")
        client.onPageStarted(webView, secondUrl, null)
        client.onPageFinished(webView, secondUrl)
        client.onPageFinished(webView, secondUrl)
        // A delayed command from the already-finished first DOM must still be rejected.
        client.shouldOverrideUrlLoading(webView, "playsound:q:0?$EXPRESSIVE_RENDER_QUERY=1")
        client.shouldOverrideUrlLoading(webView, "playsound:q:0?$EXPRESSIVE_RENDER_QUERY=2")

        assertThat(finished, equalTo(listOf(second)))
        assertThat(navigated, equalTo(listOf(second)))
    }

    @Test
    fun `only the current render is reported drawable, once`() {
        val webView = mock(WebView::class.java)
        val first = renderKey(cardId = 1)
        val second = renderKey(cardId = 2)
        val firstUrl = expressiveDocumentUrl(first.baseUrl, 1)
        val secondUrl = expressiveDocumentUrl(second.baseUrl, 2)
        val visible = mutableListOf<ExpressiveCardRenderKey>()
        val client =
            ExpressiveCardWebViewClient(
                onOpenExternal = {},
                onInternalNavigation = { _, _ -> },
                onMainFrameError = {},
                onPageVisible = { visible += it },
            )

        client.beginRender(1, first, firstUrl)
        client.onPageStarted(webView, firstUrl, null)
        client.beginRender(2, second, secondUrl)
        // The first document commits after the second render began: it must not reveal the pane.
        client.onPageCommitVisible(webView, firstUrl)
        // A commit before the second document started loading belongs to no current document.
        client.onPageCommitVisible(webView, secondUrl)
        client.onPageStarted(webView, secondUrl, null)
        client.onPageCommitVisible(webView, secondUrl)
        client.onPageCommitVisible(webView, secondUrl)
        client.onPageFinished(webView, secondUrl)

        assertThat(visible, equalTo(listOf(second)))
    }

    @Test
    fun `render HTML binds every static internal command to its render token`() {
        val html =
            "<html><head></head><body>" +
                "<a href=\"playsound:q:0\">play</a>" +
                "<a href=\"tts-voices:\">voices</a>" +
                "<video onended='location.href=\"videoended:a:1\"'></video>" +
                "</body></html>"
        val tagged = htmlWithExpressiveRenderToken(html, 12)

        assertThat(tagged, containsString("playsound:q:0?$EXPRESSIVE_RENDER_QUERY=12"))
        assertThat(tagged, containsString("tts-voices:?$EXPRESSIVE_RENDER_QUERY=12"))
        assertThat(tagged, containsString("videoended:a:1?$EXPRESSIVE_RENDER_QUERY=12"))
        assertThat(
            tagged,
            containsString("globalThis.__ankidroidInternalNavigationSuffix='?$EXPRESSIVE_RENDER_QUERY=12'"),
        )
    }

    @Test
    fun `media and Android asset file URLs are rewritten to the safe origin`() {
        val mediaDirectory = File("/tmp/Anki Media/collection")
        val fileBaseUrl = mediaDirectoryBaseUrl(mediaDirectory)
        val html =
            "<video src=\"${fileBaseUrl}folder/video.mp4\"></video>" +
                "<script src=\"file:///android_asset/backend/js/mathjax.js\"></script>"

        assertThat(fileBaseUrl, containsString("Anki%20Media"))
        assertThat(fileBaseUrl, not(containsString(" ")))
        assertThat(fileBaseUrl.endsWith('/'), equalTo(true))
        assertThat(
            rewriteCardMediaOrigin(html, mediaDirectory),
            equalTo(
                "<video src=\"${EXPRESSIVE_CARD_ORIGIN}folder/video.mp4\"></video>" +
                    "<script src=\"${EXPRESSIVE_ANDROID_ASSET_ORIGIN}backend/js/mathjax.js\"></script>",
            ),
        )
    }

    @Test
    fun `interactive WebView hit targets block card gestures`() {
        assertThat(cardGestureBlockedForHitTest(WebView.HitTestResult.SRC_ANCHOR_TYPE), equalTo(true))
        assertThat(cardGestureBlockedForHitTest(WebView.HitTestResult.EDIT_TEXT_TYPE), equalTo(true))
        assertThat(cardGestureBlockedForHitTest(WebView.HitTestResult.UNKNOWN_TYPE), equalTo(false))
    }

    private fun request(
        url: String,
        method: String? = null,
    ): WebResourceRequest =
        mock(WebResourceRequest::class.java).also {
            `when`(it.url).thenReturn(Uri.parse(url))
            method?.let { value -> `when`(it.method).thenReturn(value) }
        }

    private fun renderKey(
        cardId: Long = 1,
        side: ExpressiveCardSide = ExpressiveCardSide.QUESTION,
        rendererGeneration: Int = 0,
    ) = ExpressiveCardRenderKey(
        cardId = cardId,
        side = side,
        rendererGeneration = rendererGeneration,
        baseUrl = EXPRESSIVE_CARD_ORIGIN,
        html = "<html>same</html>",
    )
}
