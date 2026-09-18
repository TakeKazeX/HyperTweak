package com.takekazex.hypertweak.ui.page

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.Icon

/**
 * Height of one status-bar slot box: the ROM's own `status_bar_icon_height` (20dp on OS4), which is
 * also the height every `@drawable/ic_stat_sys_*` artwork is authored at.
 */
internal const val SLOT_HEIGHT_DP = 20f

/**
 * Icon column of a settings row.
 *
 * Every `ic_stat_sys_*` vector is the ROM status-bar drawable centred in a 28dp box, and the ROM
 * artwork inside it is only 20dp tall with its own padding. A row that drew that vector at its
 * intrinsic size (or, as it did before, at 22dp) therefore showed a glyph far lighter than the 24dp
 * icons the rest of the app uses. Rows scale it to this box instead, so the slot glyphs read at
 * list-icon weight; [StatusBarSlotGlyph] keeps the 1:1 size for the bar mock.
 */
internal val SlotRowGlyphBox = DpSize(32.dp, 32.dp)

/**
 * One slot glyph as a settings-row icon, at [SlotRowGlyphBox].
 *
 * The vector is scaled up from its 28dp canvas, so the artwork stays crisp and keeps its shape.
 */
@Composable
internal fun SlotRowGlyph(
    @DrawableRes iconRes: Int,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = modifier.size(SlotRowGlyphBox),
        tint = tint
    )
}

/**
 * The same glyph in the status-bar mock: the drawable at its intrinsic size, centred in the slot the
 * ROM gives that artwork, instead of being scaled down into a smaller square.
 *
 * The overflow is the vector's transparent padding, never artwork, so it needs no clipping.
 */
@Composable
internal fun StatusBarSlotGlyph(
    @DrawableRes iconRes: Int,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val painter = painterResource(iconRes)
    val density = LocalDensity.current
    val intrinsic = with(density) {
        val size = painter.intrinsicSize
        DpSize(size.width.toDp(), size.height.toDp())
    }
    val padding = (GLYPH_PADDING_DP * 2).dp
    // A drawable that reports no size at all still gets a square slot and a square glyph.
    val glyph = if (intrinsic.isSpecified) {
        intrinsic
    } else {
        DpSize(SLOT_HEIGHT_DP.dp + padding, SLOT_HEIGHT_DP.dp + padding)
    }
    val box = romSlotArtworkBox(iconRes) ?: glyph.artworkBox(padding)
    Box(modifier.size(box), contentAlignment = Alignment.Center) {
        // `size` would shrink the painter to the slot and bring back the small-glyph look.
        Icon(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.requiredSize(glyph.width, glyph.height),
            tint = tint
        )
    }
}

/**
 * The slot the ROM gives one glyph: its own drawable size when that is not the 20dp square the
 * intrinsic-minus-padding rule assumes.
 *
 * Values are the `android:width`/`android:height` of the matching `stat_sys_*` drawable in the OS4
 * SystemUI resources; the bundled vectors agree with them unit for unit (each vector is that
 * drawable centred in 28dp, e.g. `stat_sys_handle_battery_charging_1` is 30x20 at -1/+4 and
 * `stat_sys_vpn_darkmode` is 25x20 at +1.5/+4). The battery matters most: it is a 30dp-wide glyph
 * whose ink is 25dp, so the square fallback would let it overlap WiFi in the status-bar preview.
 */
private fun romSlotArtworkBox(@DrawableRes iconRes: Int): DpSize? = ROM_ARTWORK_DP[iconRes]

private val ROM_ARTWORK_DP: Map<Int, DpSize> = mapOf(
    R.drawable.ic_stat_sys_handle_battery to DpSize(30.dp, 20.dp),
    R.drawable.ic_stat_sys_vpn to DpSize(25.dp, 20.dp),
    R.drawable.ic_stat_sys_hotspot to DpSize(22.dp, 20.dp),
    R.drawable.ic_stat_sys_wireless_headset to DpSize(21.dp, 20.dp),
    R.drawable.ic_stat_sys_alarm_clock to DpSize(18.dp, 20.dp),
    R.drawable.ic_stat_sys_nfc to DpSize(18.dp, 20.dp),
    R.drawable.ic_stat_sys_headset to DpSize(18.dp, 20.dp),
    R.drawable.ic_stat_sys_airplane to DpSize(18.dp, 16.dp),
    R.drawable.ic_stat_sys_volume to DpSize(17.dp, 20.dp),
    R.drawable.ic_stat_sys_bluetooth to DpSize(16.dp, 20.dp),
    R.drawable.ic_stat_sys_location to DpSize(16.dp, 20.dp),
    R.drawable.ic_stat_sys_zen to DpSize(16.dp, 20.dp)
)

/**
 * Transparent padding each `ic_stat_sys_*` vector carries around the ROM artwork.
 *
 * Painting a vector at its intrinsic size reproduces the host exactly, and the artwork inside that
 * box is the intrinsic size minus this padding. That holds for the 20dp square artwork the signal
 * and WiFi glyphs use; [ROM_ARTWORK_DP] carries the measured size for the rest.
 */
private const val GLYPH_PADDING_DP = 4f

/**
 * The artwork box one `ic_stat_sys_*` vector describes: its intrinsic size without the transparent
 * padding the vector carries around the ROM drawable.
 */
private fun DpSize.artworkBox(padding: Dp): DpSize {
    val edge = { value: Dp ->
        (value - padding).takeIf { it.isSpecified && it > 1.dp } ?: SLOT_HEIGHT_DP.dp
    }
    return DpSize(width = edge(width), height = edge(height))
}
