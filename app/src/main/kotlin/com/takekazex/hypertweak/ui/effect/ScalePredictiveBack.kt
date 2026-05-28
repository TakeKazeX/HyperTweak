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
    val navContent = LocalNavAnimatedContentScope.current

    val containerHeightPx = windowInfo.containerSize.height
    val containerWidthPx = windowInfo.containerSize.width.toFloat()
    val pageKey = contentPageKey.toString()
    val transition = navContent.transition
    val deviceCornerRadius = rememberDeviceCornerRadius()

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

        val exitProgress = if (pageKey != currentPageKey.toString()) 1f else exitAnimatableValue
        val animatedTranslationX = containerWidthPx * exitProgress * directionMultiplier
        val needsClip = (animatedScale != 1f) || exitingPageKey != null

        return this
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                translationX = animatedTranslationX
                transformOrigin = TransformOrigin(currentPivotX, currentPivotY)
            }
            .clip(
                if (needsClip) RoundedCornerShape(deviceCornerRadius)
                else RectangleShape
            )
    } else {
        val progressInProgress = (transitionState as? NavigationEventTransitionState.InProgress)
        val progress = if (exitingPageKey != null) {
            exitAnimatableValue
        } else if (progressInProgress != null) {
            progressInProgress.latestEvent.progress
        } else {
            null
        }

        if (progress != null) {
            val dynamicScale = 0.85f + 0.15f * progress
            val dynamicAlpha = 0.5f * (1f - progress)

            return this
                .graphicsLayer {
                    scaleX = dynamicScale
                    scaleY = dynamicScale
                }
                .drawWithContent {
                    drawContent()
                    drawRect(color = Color.Black.copy(alpha = dynamicAlpha))
                }
        }
        return this
    }
}
