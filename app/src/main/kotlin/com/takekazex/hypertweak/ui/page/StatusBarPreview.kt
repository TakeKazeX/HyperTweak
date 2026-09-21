package com.takekazex.hypertweak.ui.page

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeLabelStyle
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoBattery
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoContent
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoDrawable
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSizes
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Height of the previewed bar.
 *
 * The ROM's base `status_bar_height` is 24dp, which leaves only 2dp around a 20dp slot box and read
 * as the bar pressing down on its own icons. The mock keeps the ROM's 20dp slot and draws the pill
 * at the height a cutout-era phone's bar actually has, so the icon row sits in the bar the way it
 * does on the device.
 */
private const val BAR_HEIGHT_DP = 32f

/** ROM clock size (`status_bar_clock_size_new`), so the mock reads at the bar's own scale. */
private const val CLOCK_SP = 14f

/** ROM mobile type size; the preview shows the `5G` label the host draws beside the bars. */
private const val NETWORK_TYPE_SP = 12f

/** How often the preview clock re-reads the system time. */
private const val CLOCK_REFRESH_MS = 30_000L

/**
 * Live mock of the home status bar shown above the tab row.
 *
 * Layout mirrors the host: the clock sits at the leading edge with the left-placed icons
 * immediately after it (SystemUI inserts the left container right after the clock), the indicator
 * icons are right-aligned against the pinned connectivity cluster, and the cluster itself never
 * scrolls off the trailing edge. Both icon regions scroll horizontally on their own, so a narrow
 * screen or a large font can never hide the clock or the battery.
 *
 * The pill is painted rather than clipped, so a Duo glyph larger than the bar keeps drawing outside
 * it - exactly what the module's own view does in the host row.
 */
@Composable
internal fun StatusBarPreview(model: StatusBarPreviewModel, duoSizes: DuoSizes = DuoSizes()) {
    val clock = rememberClockText()
    val leftScroll = rememberScrollState()
    val indicatorScroll = rememberScrollState()
    // Keep the indicators next to the pinned cluster visible; the user scrolls towards the clock
    // to reveal the rest, exactly like reading a real status bar.
    LaunchedEffect(indicatorScroll.maxValue) {
        indicatorScroll.scrollTo(indicatorScroll.maxValue)
    }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(percent = 50))
            .heightIn(min = BAR_HEIGHT_DP.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.align(Alignment.CenterStart).horizontalScroll(leftScroll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = clock,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = CLOCK_SP.sp,
                    modifier = Modifier.padding(end = 6.dp)
                )
                model.leftSlots.forEach { slot -> PreviewGlyph(slot, model.duoSizeDp, duoSizes) }
            }
        }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.align(Alignment.CenterEnd).horizontalScroll(indicatorScroll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                model.indicatorSlots.forEach { slot -> PreviewGlyph(slot, model.duoSizeDp, duoSizes) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (model.showNetworkType) {
                val networkLabel = if (model.small5GaEnabled) {
                    buildAnnotatedString {
                        append("5G")
                        withStyle(
                            SpanStyle(
                                fontSize = (NETWORK_TYPE_SP * MobileTypeLabelStyle.SMALL_5GA_SUFFIX_SCALE).sp
                            )
                        ) { append("A") }
                    }
                } else {
                    buildAnnotatedString { append(stringResource(R.string.icon_preview_network_type)) }
                }
                Text(
                    text = networkLabel,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = NETWORK_TYPE_SP.sp,
                    modifier = Modifier.padding(end = 2.dp)
                )
            }
            model.coreSlots.forEach { slot -> PreviewGlyph(slot, model.duoSizeDp, duoSizes) }
        }
    }
}

@Composable
private fun PreviewGlyph(slot: String, duoSizeDp: Float, duoSizes: DuoSizes) {
    if (slot == PREVIEW_DUO_SLOT) {
        DuoPreviewGlyph(duoSizeDp, duoSizes)
        return
    }
    StatusBarSlotGlyph(
        iconRes = IconSlotCatalog.of(slot)?.iconRes ?: IconSlotCatalog.fallbackIconRes(),
        tint = MiuixTheme.colorScheme.onSurface
    )
}

/** Uses the production drawable, so the preview cannot drift from the real Duo artwork. */
@Composable
private fun DuoPreviewGlyph(duoSizeDp: Float, sizes: DuoSizes) {
    val drawable = remember {
        DuoDrawable().apply {
            content = DuoContent(DuoBattery(80, false, false), 4, null, 4, false, false)
        }
    }
    drawable.sizes = sizes
    drawable.foreground = MiuixTheme.colorScheme.onSurface.toArgb()
    // The hook lays the glyph out at exactly the configured dp, so the mock must too.
    Canvas(Modifier.size(duoSizeDp.dp)) {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

@Composable
private fun rememberClockText(): String {
    val context = LocalContext.current
    var text by remember { mutableStateOf(readClock(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_REFRESH_MS)
            text = readClock(context)
        }
    }
    return text
}

/** Respects the device's 12/24-hour setting, matching the host status bar. */
private fun readClock(context: Context): String =
    DateFormat.getTimeFormat(context).format(java.util.Date())
