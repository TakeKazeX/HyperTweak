package com.takekazex.hypertweak.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.anim.folmeSpring

private val expandSizeSpring = folmeSpring<IntSize>(0.95f, 0.30f)
private val expandAlphaSpring = folmeSpring<Float>(0.95f, 0.20f)

@Composable
internal fun MiuixExpandableContent(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = expandSizeSpring, expandFrom = Alignment.Top) +
            fadeIn(animationSpec = expandAlphaSpring),
        exit = shrinkVertically(animationSpec = expandSizeSpring, shrinkTowards = Alignment.Top) +
            fadeOut(animationSpec = expandAlphaSpring),
    ) {
        Column { content() }
    }
}
