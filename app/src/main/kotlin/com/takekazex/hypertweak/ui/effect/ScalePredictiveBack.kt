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
    exitingPageKey: String?,
    exitProgress: Float
): Modifier {
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navContent = LocalNavAnimatedContentScope.current

    val containerHeightPx = windowInfo.containerSize.height
    val containerWidthPx = windowInfo.containerSize.width.toFloat()
    val pageKey = contentPageKey.toString()
    val transition = navContent.transition
    val deviceCornerRadius = rememberDeviceCornerRadius()

    var inPredictiveBackAnimation by remember { mutableStateOf(false) }

    if (pageKey == currentPageKey.toString() || exitingPageKey == pageKey) {
        val animatedScale by transition.animateFloat(
            transitionSpec = { tween(300) },
            label = "PredictiveScale"
        ) { state ->
            when (state) {
                androidx.compose.animation.EnterExitState.PostExit -> 0.85f
                else -> 1f
            }
        }

        inPredictiveBackAnimation = animatedScale != 1f

        val progressInProgress = (transitionState as? NavigationEventTransitionState.InProgress)
        val edge = progressInProgress?.latestEvent?.swipeEdge ?: 0
        val touchY = progressInProgress?.latestEvent?.touchY

        val currentPivotY = if (touchY != null && containerHeightPx > 0) {
            (touchY / containerHeightPx).coerceIn(0.1f, 0.9f)
        } else 0.5f

        val currentPivotX = if (edge == androidx.navigationevent.NavigationEvent.EDGE_LEFT) 0.8f else 0.2f

        val directionMultiplier = if (exitFollowGesture) {
            if (edge == androidx.navigationevent.NavigationEvent.EDGE_LEFT) 1f else -1f
        } else {
            1f
        }

        val exitProgressVal = if (pageKey != currentPageKey.toString()) {
            1f
        } else {
            exitProgress
        }

        // Slide slightly during active swipe, and interpolate to screen edge during exit
        val maxSlidePx = remember(density) { with(density) { 32.dp.toPx() } }
        val translationX = if (exitingPageKey != null) {
            // we use the mapped exitProgress to drive the translation out
            val fraction = exitProgressVal.coerceIn(0f, 1f)
            val startTrans = (progressInProgress?.latestEvent?.progress ?: 0f) * maxSlidePx * directionMultiplier
            val endTrans = containerWidthPx * directionMultiplier
            startTrans + (endTrans - startTrans) * fraction
        } else {
            val progress = progressInProgress?.latestEvent?.progress ?: 0f
            progress * maxSlidePx * directionMultiplier
        }

        val needsClip = inPredictiveBackAnimation || exitingPageKey != null

        return this
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                this.translationX = translationX
                transformOrigin = TransformOrigin(currentPivotX, currentPivotY)
            }
            .clip(
                if (needsClip) RoundedCornerShape(deviceCornerRadius)
                else RectangleShape
            )
    } else {
        val renderModifier = if (transitionState is NavigationEventTransitionState.InProgress) {
            val progress = if (!inPredictiveBackAnimation) 1f else {
                val mappedProgress = if (transition.currentState == androidx.compose.animation.EnterExitState.PostExit) 1f else 0f
                mappedProgress
            }
            val dynamicAlpha = 0.5f * (1f - progress)

            this
                .graphicsLayer()
                .drawWithContent {
                    drawContent()
                    drawRect(color = Color.Black.copy(alpha = dynamicAlpha))
                }
        } else Modifier

        return this.then(renderModifier)
    }
}
