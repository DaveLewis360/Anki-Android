// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
 * Detection observes pointer events without consuming them, so links, controls, media and vertical
 * WebView scrolling keep working. Long press fires at the configured timeout rather than on release;
 * double tap is accepted only after two completed nearby taps.
 */
@Composable
fun CardGestureArea(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    shouldHandleGesture: () -> Boolean = { true },
    gestureKey: Any? = Unit,
    onDoubleTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current
    var pendingTapJob by remember { mutableStateOf<Job?>(null) }
    var firstTapUpMillis by remember { mutableLongStateOf(0L) }
    var firstTapPosition by remember { mutableStateOf(Offset.Unspecified) }
    val doubleTapEnabled = onDoubleTap != null
    val currentShouldHandleGesture by rememberUpdatedState(shouldHandleGesture)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)

    LaunchedEffect(gestureKey) {
        pendingTapJob?.cancel()
        pendingTapJob = null
        firstTapUpMillis = 0L
        firstTapPosition = Offset.Unspecified
    }

    Box(
        modifier =
            modifier.pointerInput(doubleTapEnabled, gestureKey, viewConfiguration) {
                val swipeThreshold = 80.dp.toPx()
                val slop = viewConfiguration.touchSlop
                val longPressMillis = viewConfiguration.longPressTimeoutMillis
                val doubleTapMillis = 300L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    var longFired = false
                    var last = down.position
                    val longPressJob =
                        currentOnLongPress?.let { action ->
                            scope.launch {
                                delay(longPressMillis)
                                val handleThisTouch = currentShouldHandleGesture()
                                if (!moved && handleThisTouch) {
                                    longFired = true
                                    action()
                                }
                            }
                        }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        last = change.position
                        if (!moved) {
                            val dx = last.x - down.position.x
                            val dy = last.y - down.position.y
                            if (abs(dx) > slop || abs(dy) > slop) {
                                moved = true
                                longPressJob?.cancel()
                            }
                        }
                        if (event.changes.all { !it.pressed }) {
                            longPressJob?.cancel()
                            val dx = last.x - down.position.x
                            val dy = last.y - down.position.y
                            val isTap = !moved && !longFired
                            val handleThisTouch = currentShouldHandleGesture()
                            val isHorizontalSwipe = moved && abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.2f
                            when {
                                isTap && handleThisTouch -> {
                                    val withinTime = change.uptimeMillis - firstTapUpMillis in 1..doubleTapMillis
                                    val withinDistance =
                                        firstTapUpMillis > 0L &&
                                            (down.position - firstTapPosition).getDistance() <= slop * 2
                                    if (doubleTapEnabled && pendingTapJob?.isActive == true && withinTime && withinDistance) {
                                        pendingTapJob?.cancel()
                                        pendingTapJob = null
                                        firstTapUpMillis = 0L
                                        firstTapPosition = Offset.Unspecified
                                        if (handleThisTouch) currentOnDoubleTap?.invoke()
                                    } else {
                                        firstTapUpMillis = change.uptimeMillis
                                        firstTapPosition = down.position
                                        pendingTapJob?.cancel()
                                        if (doubleTapEnabled) {
                                            pendingTapJob =
                                                scope.launch {
                                                    delay(doubleTapMillis)
                                                    if (handleThisTouch) currentOnTap()
                                                }
                                        } else if (handleThisTouch) {
                                            currentOnTap()
                                        }
                                    }
                                }
                                isHorizontalSwipe && dx < 0 && handleThisTouch -> currentOnSwipeLeft?.invoke()
                                isHorizontalSwipe && dx > 0 && handleThisTouch -> currentOnSwipeRight?.invoke()
                            }
                            break
                        }
                    }
                }
            },
        content = content,
    )
}
