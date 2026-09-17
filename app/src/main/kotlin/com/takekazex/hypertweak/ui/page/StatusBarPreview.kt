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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoBattery
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoContent
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoDrawable
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoLayout
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Glyph height of an ordinary preview icon. */
private const val PREVIEW_ICON_DP = 17f

/** Duo glyph edge at the 24dp default; the user's dp scales it relative to this. */
private const val PREVIEW_DUO_BASE_DP = 20f

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
 */
@Composable
internal fun StatusBarPreview(model: StatusBarPreviewModel, showCellularType: Boolean) {
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
            .clip(RoundedCornerShape(percent = 50))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .heightIn(min = 22.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
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
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
                model.leftSlots.forEach { slot -> PreviewGlyph(slot, model.duoSizeDp) }
            }
        }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.align(Alignment.CenterEnd).horizontalScroll(indicatorScroll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                model.indicatorSlots.forEach { slot -> PreviewGlyph(slot, model.duoSizeDp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            model.coreSlots.forEach { slot ->
                PreviewGlyph(slot, model.duoSizeDp)
                if (slot == "mobile" && showCellularType) {
                    Text(
                        text = stringResource(R.string.icon_preview_network_type),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewGlyph(slot: String, duoSizeDp: Float) {
    if (slot == PREVIEW_DUO_SLOT) {
        DuoPreviewGlyph(duoSizeDp)
        return
    }
    Icon(
        painter = painterResource(
            id = IconSlotCatalog.of(slot)?.iconRes ?: IconSlotCatalog.fallbackIconRes()
        ),
        contentDescription = null,
        modifier = Modifier.padding(horizontal = 2.dp).size(PREVIEW_ICON_DP.dp),
        tint = MiuixTheme.colorScheme.onSurface
    )
}

/** Uses the production drawable, so the preview cannot drift from the real Duo artwork. */
@Composable
private fun DuoPreviewGlyph(duoSizeDp: Float) {
    val drawable = remember {
        DuoDrawable().apply {
            content = DuoContent(DuoBattery(80, false, false), 4, null, 4, false, false)
        }
    }
    drawable.foreground = MiuixTheme.colorScheme.onSurface.toArgb()
    // Mirror the user's dp relative to the default, so the preview keeps the bar's own scale.
    val relative = duoSizeDp / DuoLayout.DEFAULT_ICON_SIZE_DP
    val side = (PREVIEW_DUO_BASE_DP * relative).dp
    Canvas(Modifier.padding(horizontal = 2.dp).size(side)) {
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
