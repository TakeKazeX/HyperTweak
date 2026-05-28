package com.takekazex.hypertweak.ui.effect

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEventTransitionState

@Composable
fun rememberDeviceCornerRadius(defaultRadius: Dp = 16.dp): Dp {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    return remember(view, density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            if (insets != null) {
                val corner = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                    ?: insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                    ?: insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                    ?: insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)

                if (corner != null) {
                    with(density) {
                        return@remember corner.radius.toDp()
                    }
                }
            }
        }
        defaultRadius
    }
}

@Composable
fun Modifier.scalePredictiveBackDecorator(
    transitionState: NavigationEventTransitionState?,
    contentPageKey: Any,
    currentPageKey: NavKey?,
    exitFollowGesture: Boolean,
    exitAnimatableValue: Float,
    exitingPageKey: String?
): Modifier {
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val containerHeightPx = windowInfo.containerSize.height
    val containerWidthPx = windowInfo.containerSize.width.toFloat()
    val pageKey = contentPageKey.toString()
    val deviceCornerRadius = rememberDeviceCornerRadius()

    // Remember last gesture states to avoid sudden jumps when transitionState is cleared to Idle
    var lastGestureProgress by remember { mutableStateOf(0f) }
    var lastSwipeEdge by remember { mutableStateOf(0) }
    var lastTouchY by remember { mutableStateOf<Float?>(null) }

    if (transitionState is NavigationEventTransitionState.InProgress) {
        val event = transitionState.latestEvent
        lastGestureProgress = event.progress
        lastSwipeEdge = event.swipeEdge
        lastTouchY = event.touchY
    }

    val isExiting = exitingPageKey == pageKey

    if (pageKey == currentPageKey.toString() || isExiting) {
        // Foreground page (being swiped or exiting)
        val progress = if (isExiting) {
            exitAnimatableValue
        } else if (transitionState is NavigationEventTransitionState.InProgress) {
            transitionState.latestEvent.progress
        } else {
            0f
        }

        val edge = if (transitionState is NavigationEventTransitionState.InProgress) {
            transitionState.latestEvent.swipeEdge
        } else {
            lastSwipeEdge
        }

        val touchY = if (transitionState is NavigationEventTransitionState.InProgress) {
            transitionState.latestEvent.touchY
        } else {
            lastTouchY
        }

        val currentPivotY = if (touchY != null && containerHeightPx > 0) {
            (touchY / containerHeightPx).coerceIn(0.1f, 0.9f)
        } else 0.5f

        val currentPivotX = if (edge == androidx.navigationevent.NavigationEvent.EDGE_LEFT) 0.8f else 0.2f

        val directionMultiplier = if (exitFollowGesture) {
            if (edge == androidx.navigationevent.NavigationEvent.EDGE_LEFT) 1f else -1f
        } else {
            1f
        }

        // Slide slightly during active swipe, and interpolate to screen edge during exit
        val maxSlidePx = remember(density) { with(density) { 32.dp.toPx() } }
        val translationX = if (isExiting) {
            val releaseProgress = lastGestureProgress
            val fraction = if (releaseProgress < 1f) {
                ((progress - releaseProgress) / (1f - releaseProgress)).coerceIn(0f, 1f)
            } else {
                1f
            }
            val startTrans = releaseProgress * maxSlidePx * directionMultiplier
            val endTrans = containerWidthPx * directionMultiplier
            startTrans + (endTrans - startTrans) * fraction
        } else {
            progress * maxSlidePx * directionMultiplier
        }

        val scale = 1.0f - (0.1f * progress)
        val needsClip = progress > 0f || isExiting

        return this
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.translationX = translationX
                transformOrigin = TransformOrigin(currentPivotX, currentPivotY)
            }
            .clip(
                if (needsClip) RoundedCornerShape(deviceCornerRadius)
                else RectangleShape
            )
    } else {
        // Background page (revealed underneath)
        val progress = if (exitingPageKey != null) {
            exitAnimatableValue
        } else if (transitionState is NavigationEventTransitionState.InProgress) {
            transitionState.latestEvent.progress
        } else {
            null
        }

        if (progress != null) {
            val dynamicAlpha = 0.5f * (1f - progress)

            return this
                .graphicsLayer()
                .drawWithContent {
                    drawContent()
                    drawRect(color = Color.Black.copy(alpha = dynamicAlpha))
                }
        }
        return this
    }
}
