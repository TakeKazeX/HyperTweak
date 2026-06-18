package com.takekazex.hypertweak.ui.effect

import android.view.RoundedCorner
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.NavigationEventTransitionState.InProgress

@Composable
fun rememberDeviceCornerRadius(defaultRadius: Dp = 16.dp): Dp {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    return remember(view, density) {
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
        defaultRadius
    }
}

/**
 * A non-Compose-state holder for inPredictiveBackAnimation.
 * Using a plain Ref avoids recomposition racing that LaunchedEffect + mutableStateOf causes
 * when the user navigates back during an entering page transition.
 * This matches InstallerX's approach of using a class-level `private var`.
 */
class PredictiveBackAnimState {
    var inPredictiveBackAnimation: Boolean = false
}

@Composable
fun Modifier.scalePredictiveBackDecorator(
    transitionState: NavigationEventTransitionState?,
    contentPageKey: Any,
    currentPageKey: NavKey?,
    exitFollowGesture: Boolean,
    exitingPageKey: String?,
    exitProgress: Float,
    // Callback to notify the nav container whether a predictive back gesture is active,
    // so it can gate exitingPageKey assignment correctly
    animState: PredictiveBackAnimState
): Modifier {
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val navContent = LocalNavAnimatedContentScope.current

    val containerHeightPx = windowInfo.containerSize.height
    val containerWidthPx = windowInfo.containerSize.width.toFloat()
    val pageKey = contentPageKey.toString()
    val transition = navContent.transition
    val deviceCornerRadius = rememberDeviceCornerRadius()

    return if (pageKey == currentPageKey.toString() || exitingPageKey == pageKey) {
        // Use transition.animateFloat directly — exactly like InstallerX.
        // This is the correct approach: the Compose transition system manages the timing,
        // and there's no LaunchedEffect race condition with the enter transition.
        val animatedScale by transition.animateFloat(
            transitionSpec = { tween(300) },
            label = "PredictiveScale"
        ) { state ->
            when (state) {
                androidx.compose.animation.EnterExitState.PostExit -> 0.85f
                else -> 1f
            }
        }

        // Update the shared ref — NOT mutableStateOf, so no recomposition triggered
        animState.inPredictiveBackAnimation = animatedScale != 1f

        val progressInProgress = (transitionState as? InProgress)
        val edge = progressInProgress?.latestEvent?.swipeEdge ?: 0
        val touchY = progressInProgress?.latestEvent?.touchY

        val currentPivotY = if (touchY != null && containerHeightPx > 0) {
            (touchY / containerHeightPx).coerceIn(0.1f, 0.9f)
        } else 0.5f

        val currentPivotX = if (edge == NavigationEvent.EDGE_LEFT) 0.8f else 0.2f

        val directionMultiplier = if (exitFollowGesture) {
            if (edge == NavigationEvent.EDGE_LEFT) 1f else -1f
        } else {
            1f
        }

        // Match InstallerX: exit translation is containerWidth * exitProgress * direction.
        // During the gesture (not exiting), translationX is 0 — the scale pivot handles the
        // visual "movement". Only during the committed exit animation do we slide off-screen.
        val resolvedExitProgress = if (pageKey != currentPageKey.toString()) 1f else exitProgress
        val translationX = containerWidthPx * resolvedExitProgress * directionMultiplier

        val needsClip = animState.inPredictiveBackAnimation || exitingPageKey != null

        this
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
        // Background (parent) page dim overlay — only when a real gesture is in progress
        val renderModifier = if (transitionState is InProgress) {
            val progress = if (!animState.inPredictiveBackAnimation) 1f else exitProgress
            val dynamicAlpha = 0.5f * (1f - progress)

            this
                .graphicsLayer()
                .drawWithContent {
                    drawContent()
                    drawRect(color = Color.Black.copy(alpha = dynamicAlpha))
                }
        } else Modifier

        this.then(renderModifier)
    }
}
