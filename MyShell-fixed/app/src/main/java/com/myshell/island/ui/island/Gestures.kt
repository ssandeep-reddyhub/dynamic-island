package com.myshell.island.ui.island

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * One swipe detector for the whole island (flings and slow drags both count).
 *
 * - [claim] = true: the swipe is claimed as soon as it passes touch-slop, so children (e.g. the tap on a
 *   pill) are cancelled. Use it where nothing inside scrolls (compact pill, alert banner).
 * - [claim] = false: the detector only *watches* (final pass). If a child scrolled or swiped on its own
 *   (notification list, swipe-to-delete card, app row) the gesture is ignored, so it never fights them.
 *   Use it on the expanded panel.
 */
fun Modifier.islandSwipe(
    sensitivity: Float,
    claim: Boolean,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
): Modifier = pointerInput(sensitivity, claim, onLeft != null, onRight != null, onUp != null, onDown != null) {
    val slop = viewConfiguration.touchSlop
    val distance = (density * 44f) / sensitivity.coerceIn(0.4f, 2.5f) // finger travel for a slow swipe
    val minFling = density * 12f                                       // minimum travel for a fast flick
    val flingSpeed = 700f * density / sensitivity.coerceIn(0.4f, 2.5f) // px/s

    awaitEachGesture {
        val pass = if (claim) PointerEventPass.Initial else PointerEventPass.Final
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var blocked = false
        var locked = false

        while (true) {
            val event = awaitPointerEvent(pass)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta: Offset = change.positionChange()
            dx += delta.x
            dy += delta.y
            tracker.addPosition(change.uptimeMillis, change.position)

            if (!change.pressed) {
                if (locked) change.consume()
                if (!blocked) {
                    val v = tracker.calculateVelocity()
                    val horizontal = abs(dx) > abs(dy)
                    if (horizontal) {
                        val left = dx < -distance || (dx < -minFling && v.x < -flingSpeed)
                        val right = dx > distance || (dx > minFling && v.x > flingSpeed)
                        if (left) onLeft?.invoke() else if (right) onRight?.invoke()
                    } else {
                        val up = dy < -distance || (dy < -minFling && v.y < -flingSpeed)
                        val downSwipe = dy > distance || (dy > minFling && v.y > flingSpeed)
                        if (up) onUp?.invoke() else if (downSwipe) onDown?.invoke()
                    }
                }
                break
            }

            if (!claim) {
                // A child already used this movement (scrolling / its own swipe): leave the gesture to it.
                if (change.isConsumed && delta != Offset.Zero) blocked = true
            } else {
                if (!locked && maxOf(abs(dx), abs(dy)) > slop) locked = true
                if (locked) change.consume()
            }
        }
    }
}
