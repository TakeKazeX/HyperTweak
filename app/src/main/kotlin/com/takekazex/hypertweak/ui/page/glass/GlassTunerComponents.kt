package com.takekazex.hypertweak.ui.page.glass

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppIconButton as IconButton
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun GlassSliderPreference(
    title: String,
    summary: String,
    value: Float,
    rangeStart: Float,
    rangeEnd: Float,
    defaultValue: Float,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit
) {
    val slider = rememberSliderEdit(value)
    val sliderValue = slider.value
    var expanded by remember(title) { mutableStateOf(false) }
    var showValueDialog by remember(title) { mutableStateOf(false) }
    ArrowPreference(
        title = title,
        summary = summary,
        endActions = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(format(sliderValue), color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                IconButton(
                    onClick = {
                        DebugLog.i("GlassTuner", "numeric editor opened: $title")
                        showValueDialog = true
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.glass_slider_edit_value),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        onClick = {
            if (!expanded) {
                expanded = true
                DebugLog.i("GlassTuner", "numeric editor opened: $title")
                showValueDialog = true
            } else {
                expanded = false
            }
        },
        holdDownState = expanded,
        bottomAction = {
            Slider(
                value = sliderValue.coerceIn(rangeStart, rangeEnd),
                onValueChange = { newValue ->
                    slider.update(newValue.coerceIn(rangeStart, rangeEnd))
                    onValueChange(slider.value)
                },
                onValueChangeFinished = { slider.finish()?.let(onValueChangeFinished) },
                valueRange = rangeStart..rangeEnd,
                showKeyPoints = true,
                keyPoints = listOf(rangeStart, defaultValue, rangeEnd).distinct(),
                magnetThreshold = 0.01f,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }
    )
    IntValueDialog(
        show = showValueDialog,
        title = title,
        summary = summary,
        suffix = stringResource(R.string.scale_percent),
        range = (rangeStart * 100).roundToInt()..(rangeEnd * 100).roundToInt(),
        currentValue = { (sliderValue * 100).roundToInt() },
        emptyValue = (sliderValue * 100).roundToInt(),
        onValueConfirmed = { percent ->
            val newValue = percent / 100f
            slider.update(newValue)
            onValueChange(newValue)
            slider.finish()?.let(onValueChangeFinished)
            DebugLog.i("GlassTuner", "numeric editor applied: $title=$percent%")
        },
        onDismissRequest = {
            showValueDialog = false
            // Clear ArrowPreference's pressed/expanded state with the dialog. Otherwise the first
            // tap after closing only collapses the row and a second tap is needed to edit again.
            expanded = false
            DebugLog.i("GlassTuner", "numeric editor dismissed; slider collapsed: $title")
        },
    )
}

@Composable
internal fun PresetIconAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
internal fun PreviewEmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(24.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

internal fun readMaterialStyle(context: Context): Int = runCatching {
    Settings.Secure.getInt(context.contentResolver, "material_style", 1)
}.getOrDefault(1)
