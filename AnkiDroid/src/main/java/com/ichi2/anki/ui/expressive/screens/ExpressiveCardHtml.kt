// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Anki's night-mode body classes, and the one AnkiDroid adds for its dark theme. */
private val NIGHT_MODE_CLASSES = listOf("night_mode", "nightMode", "ankidroid_dark_mode")

/** Every theme class the shared renderer may add: the Expressive theme decides which ones apply. */
private val THEME_CLASSES = NIGHT_MODE_CLASSES + "ankidroid_plain_mode"

/** The body of card_template.html; note content only follows it, inside `#qa`. */
private val BODY_CLASS = Regex("""<body\s+class="([^"]*)"""", RegexOption.IGNORE_CASE)

private val HEAD_END = Regex("</head>", RegexOption.IGNORE_CASE)

/**
 * The document the Study screen loads for a rendered card side.
 *
 * Card templates render as they do in Anki and in the Classic reviewer: nothing is animated, and no
 * color is forced on the note type. The page only follows the Expressive light or dark theme, the
 * way Anki's night mode does:
 * - the body carries the night-mode classes exactly when the theme is [dark], whichever Classic
 *   theme the shared renderer last saw; the Classic plain-theme class never applies;
 * - in dark mode the page canvas and text take [canvas] and [onCanvas] through the same
 *   `body.night_mode` selectors flashcard.css uses. The rule comes after flashcard.css and before
 *   the note type's CSS, so the note type's rules win or lose exactly as they do in the Classic
 *   reviewer: a more specific rule such as `.nightMode.card` still decides;
 * - form controls and scrollbars follow the theme's color scheme.
 *
 * Like the Classic reviewer, images are only limited to the page width once the page has loaded
 * (flashcard.css's `.chrome img`).
 *
 * When the screen shows its own typing field ([hideTypeInput]), the page's typing input is hidden.
 */
internal fun expressiveCardHtml(
    rendered: String,
    dark: Boolean,
    canvas: Color,
    onCanvas: Color,
    hideTypeInput: Boolean = false,
): String {
    val themed =
        BODY_CLASS.find(rendered)?.let { match ->
            val classes = match.groupValues[1].split(' ').filter { it.isNotEmpty() && it !in THEME_CLASSES }
            val bodyClass = (if (dark) classes + NIGHT_MODE_CLASSES else classes).joinToString(" ")
            rendered.replaceRange(match.groups[1]!!.range, bodyClass)
        } ?: rendered
    val style =
        buildString {
            append("<style id=\"expressive-theme\">")
            append(":root{color-scheme:").append(if (dark) "dark" else "light").append(";}")
            if (dark) {
                append("body.night_mode,body.ankidroid_dark_mode{")
                append("background-color:").append(canvas.cssHex()).append(";")
                append("color:").append(onCanvas.cssHex()).append(";}")
            }
            // A second typing field in the page would take the typed answer away from the screen's own.
            if (hideTypeInput) append("input#typeans{display:none!important;}")
            append("</style>")
        }
    val headEnd = HEAD_END.find(themed) ?: return themed + style
    return themed.replaceRange(headEnd.range.first, headEnd.range.first, style)
}

private fun Color.cssHex(): String = "#%06X".format(0xFFFFFF and toArgb())
