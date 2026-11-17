package com.takekazex.hypertweak.ui.page.systemui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.presentation.slider.SliderPercentageColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Host baselines from the current plugin: both percentage labels are 12dip (not font-scaled). */
private val HOST_LABEL_SIZE = 12.dp
private val POPUP_CAPSULE_WIDTH = 45.dp
private val POPUP_CAPSULE_HEIGHT = 20.dp
private const val PREVIEW_PERCENT = 60

/**
 * Control-center slider and outer volume popup at the host's dimensions, so the label's growth
 * and the outer capsule following it are visible before applying.
 */
@Composable
internal fun SliderPercentagePreview(sizePercent: Float, weight: Float) {
    val scale = sizePercent / 100f
    val capsuleScale = scale.coerceAtLeast(1f)
    val fontWeight = FontWeight(weight.toInt().coerceIn(1, 1000))
    val popupWeight = fontWeight
    val colors = MiuixTheme.colorScheme
    val popup = SliderPercentageColors.popup(dark = colors.surface.luminance() < 0.5f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        // Control-center slider: label pinned 16dip below the tile top, above the fill here.
        PreviewTile(width = 72.dp, label = {
            PercentLabel(scale, fontWeight, Color(SliderPercentageColors.CC_ON_TRACK), Modifier.padding(top = 16.dp))
        })
        // Collapsed volume popup: label inside the native fixed-size capsule beside the bar.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                // Same rule as the hook: the capsule grows with the text, never below native.
                Modifier.padding(top = 8.dp).size(POPUP_CAPSULE_WIDTH * capsuleScale, POPUP_CAPSULE_HEIGHT * capsuleScale)
                    .clip(RoundedCornerShape(POPUP_CAPSULE_HEIGHT * capsuleScale / 2)).background(Color(popup.background))
                    .border(1.dp, Color(popup.stroke), RoundedCornerShape(POPUP_CAPSULE_HEIGHT * capsuleScale / 2)),
                contentAlignment = Alignment.Center
            ) { PercentLabel(scale, popupWeight, Color(popup.text)) }
            PreviewTile(width = 52.dp, label = {})
        }
    }
}

@Composable
private fun PreviewTile(width: Dp, label: @Composable () -> Unit) {
    Box(
        Modifier.size(width, 132.dp).clip(RoundedCornerShape(22.dp)).background(Color(SliderPercentageColors.CC_TRACK)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.width(width).height(132.dp * PREVIEW_PERCENT / 100).background(Color(SliderPercentageColors.CC_FILL)))
        }
        label()
    }
}

@Composable
private fun PercentLabel(scale: Float, weight: FontWeight, color: Color, modifier: Modifier = Modifier) {
    val size = with(LocalDensity.current) { (HOST_LABEL_SIZE * scale).toSp() }
    Text(
        text = "$PREVIEW_PERCENT%",
        modifier = modifier,
        color = color,
        fontSize = size,
        lineHeight = size,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false
    )
}
