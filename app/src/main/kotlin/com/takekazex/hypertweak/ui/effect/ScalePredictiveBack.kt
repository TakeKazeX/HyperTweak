package com.takekazex.hypertweak.ui.effect

import androidx.compose.ui.graphics.TransformOrigin
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

internal data class ScaleBackFrame(val scale: Float, val translationFraction: Float)

internal fun scaleBackFrame(progress: Float, releaseProgress: Float, committed: Boolean): ScaleBackFrame {
    val p = progress.coerceIn(0f, 1f)
    val release = releaseProgress.coerceIn(0f, 0.999f)
    return if (committed) {
        ScaleBackFrame(1f - 0.15f * release, ((p - release) / (1f - release)).coerceIn(0f, 1f))
    } else {
        ScaleBackFrame(1f - 0.15f * p, 0f)
    }
}

/** Uses the native Miuix gesture/settle driver, including cancellation and interrupted entry. */
fun scaleBackTransition(followGesture: Boolean): NavTransition = navGraphicsTransition { scope ->
    val d = scope.relativeDepth
    val gesture = scope.gesture
    val popping = scope.role == NavRole.Outgoing
    val width = scope.layoutSize.width.toFloat()
    when {
        d <= 0f && gesture != null -> {
            val frame = scaleBackFrame(-d, gesture.progress, popping)
            scaleX = frame.scale
            scaleY = frame.scale
            val rightEdge = gesture.swipeEdge == NavSwipeEdge.Right
            translationX = width * frame.translationFraction * if (followGesture && rightEdge) -1f else 1f
            val pivotY = if (scope.layoutSize.height > 0) {
                (gesture.touchY / scope.layoutSize.height).coerceIn(0.1f, 0.9f)
            } else 0.5f
            transformOrigin = TransformOrigin(if (rightEdge) 0.2f else 0.8f, pivotY)
        }
        d <= 0f && popping -> {
            val progress = (-d).coerceIn(0f, 1f)
            scaleX = 1f - 0.1f * progress
            scaleY = scaleX
            alpha = 1f - progress
        }
        d <= 0f -> translationX = -d.coerceIn(-1f, 0f) * width
        gesture == null -> translationX = -d.coerceIn(0f, 1f) * width * 0.25f
    }
}
