// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gesture-aware container for the card area.
 *
 * All detection runs on the [PointerEventPass.Initial]/[PointerEventPass.Final]
 * observation passes WITHOUT consuming events, so the WebView inside keeps its own
 * touches: audio play buttons, links and vertical scrolling keep working.
 * Vertical drags are therefore never gestures (they scroll the card), and taps on
 * elements the WebView consumes (e.g. a play button) are ignored.
 */
@Composable
fun CardGestureArea(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current
    var pendingTapJob by remember { mutableStateOf<Job?>(null) }
    var lastUpMillis by remember { mutableLongStateOf(0L) }
    val doubleTapEnabled = onDoubleTap != null

    Box(
        modifier =
            modifier.pointerInput(doubleTapEnabled) {
                val swipeThreshold = 80.dp.toPx()
                val slop = viewConfiguration.touchSlop
                val longPressMillis = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    var longFired = false
                    var last: Offset = down.position
                    if (doubleTapEnabled && pendingTapJob?.isActive == true && down.uptimeMillis - lastUpMillis < 300) {
                        // second tap of a double-tap: cancel the pending single tap
                        pendingTapJob?.cancel()
                        onDoubleTap()
                        moved = true // swallow this gesture entirely
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        last = change.position
                        if (!moved) {
                            val dx = last.x - down.position.x
                            val dy = last.y - down.position.y
                            if (abs(dx) > slop || abs(dy) > slop) moved = true
                        }
                        if (!longFired && !moved && change.uptimeMillis - down.uptimeMillis >= longPressMillis) {
                            longFired = true
                            onLongPress?.invoke()
                        }
                        if (event.changes.all { !it.pressed }) {
                            // The WebView consumes every touch (scroll handling), so
                            // consumption cannot be used to filter taps — same trade-off
                            // as the classic AnkiDroid gesture detector.
                            val dx = last.x - down.position.x
                            val dy = last.y - down.position.y
                            val isTap = !moved && !longFired
                            val isHorizontalSwipe = moved && abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.2f
                            when {
                                isTap -> {
                                    lastUpMillis = change.uptimeMillis
                                    if (doubleTapEnabled) {
                                        pendingTapJob?.cancel()
                                        pendingTapJob =
                                            scope.launch {
                                                delay(280)
                                                onTap()
                                            }
                                    } else {
                                        onTap()
                                    }
                                }
                                isHorizontalSwipe && dx < 0 -> onSwipeLeft?.invoke()
                                isHorizontalSwipe && dx > 0 -> onSwipeRight?.invoke()
                            }
                            break
                        }
                    }
                }
            },
        content = content,
    )
}
