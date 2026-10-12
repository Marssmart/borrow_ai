package org.deer.borrowai

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberHorizontalPageSwipe(
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
): Modifier {
    val latestOnSwipeLeft = rememberUpdatedState(onSwipeLeft)
    val latestOnSwipeRight = rememberUpdatedState(onSwipeRight)
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }

    return Modifier.pointerInput(threshold) {
        var horizontalDistance = 0f
        detectHorizontalDragGestures(
            onDragStart = { horizontalDistance = 0f },
            onHorizontalDrag = { change, dragAmount ->
                horizontalDistance += dragAmount
                change.consume()
            },
            onDragEnd = {
                when {
                    horizontalDistance <= -threshold -> latestOnSwipeLeft.value?.invoke()
                    horizontalDistance >= threshold -> latestOnSwipeRight.value?.invoke()
                }
                horizontalDistance = 0f
            },
            onDragCancel = { horizontalDistance = 0f },
        )
    }
}
