// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Study screen shows card templates as Anki does, with only its light or dark theme applied. */
class ExpressiveCardHtmlTest {
    private val canvas = Color(0xFF111418)
    private val onCanvas = Color(0xFFE1E2E8)

    /** The shared renderer's output, as a stale dark Classic theme left it. */
    private val rendered =
        """
        <!doctype html>
        <html class="mobile android linux js">
        <head><title>AnkiDroid Flashcard</title>
        <link rel="stylesheet" type="text/css" href="https://appassets.androidplatform.net/_anki/android_asset/flashcard.css">
        </head>
        <body class="card card1 night_mode nightMode ankidroid_dark_mode">
        <div id="content" dir="auto"><div id="qa"><style>.card { color: black; background-color: white; }</style>Front</div></div>
        </body>
        </html>
        """.trimIndent()

    @Test
    fun `a light theme removes stale night classes and leaves the note type's colors alone`() {
        val html = expressiveCardHtml(rendered, dark = false, canvas = canvas, onCanvas = onCanvas)

        assertEquals("card card1", bodyClass(html))
        assertTrue(":root{color-scheme:light;}" in style(html))
        assertFalse("background-color" in style(html), style(html))
        assertNothingForced(html)
    }

    @Test
    fun `a dark theme adds the night classes once and paints the canvas through Anki's night-mode selector`() {
        val html = expressiveCardHtml(rendered, dark = true, canvas = canvas, onCanvas = onCanvas)

        assertEquals("card card1 night_mode nightMode ankidroid_dark_mode", bodyClass(html))
        assertTrue(":root{color-scheme:dark;}" in style(html))
        assertTrue("body.night_mode,body.ankidroid_dark_mode{background-color:#111418;color:#E1E2E8;}" in style(html))
        assertNothingForced(html)
    }

    @Test
    fun `the theme sits between flashcard css and the note type's css, where Classic's night rules sit`() {
        val html = expressiveCardHtml(rendered, dark = true, canvas = canvas, onCanvas = onCanvas)

        val theme = html.indexOf("<style id=\"expressive-theme\">")
        assertTrue(html.indexOf("flashcard.css") < theme)
        assertTrue(theme < html.indexOf("</head>"))
        assertTrue(theme < html.indexOf(".card { color: black;"))
        assertEquals(1, Regex("expressive-theme").findAll(html).count())
    }

    @Test
    fun `the Classic plain-theme class never applies to the Expressive theme`() {
        val plain = rendered.replace("night_mode nightMode ankidroid_dark_mode", "ankidroid_plain_mode")

        assertEquals("card card1", bodyClass(expressiveCardHtml(plain, dark = false, canvas = canvas, onCanvas = onCanvas)))
        assertEquals(
            "card card1 night_mode nightMode ankidroid_dark_mode",
            bodyClass(expressiveCardHtml(plain, dark = true, canvas = canvas, onCanvas = onCanvas)),
        )
    }

    @Test
    fun `the page's typing input is hidden only while the screen shows its own`() {
        val hidden = expressiveCardHtml(rendered, dark = false, canvas = canvas, onCanvas = onCanvas, hideTypeInput = true)
        val shown = expressiveCardHtml(rendered, dark = false, canvas = canvas, onCanvas = onCanvas)

        assertTrue("input#typeans{display:none!important;}" in style(hidden))
        assertFalse("typeans" in style(shown))
    }

    @Test
    fun `a document without a head or body class keeps its content and gets the theme appended`() {
        val fragment = "<div>Front</div>"

        val html = expressiveCardHtml(fragment, dark = true, canvas = canvas, onCanvas = onCanvas)

        assertTrue(html.startsWith(fragment))
        assertTrue(html.endsWith("</style>"))
    }

    /** No animation, and nothing that overrides the note type, except hiding the duplicate typing input. */
    private fun assertNothingForced(html: String) {
        val style = style(html)
        assertFalse("!important" in style.replace("input#typeans{display:none!important;}", ""), style)
        assertFalse("animation" in html, "no animation in the card")
        assertFalse("@keyframes" in html, "no keyframes in the card")
        assertFalse("<script" in style, "no script in the theme")
    }

    private fun bodyClass(html: String): String = Regex("""<body class="([^"]*)"""").find(html)!!.groupValues[1]

    private fun style(html: String): String =
        Regex("""<style id="expressive-theme">.*?</style>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
}
