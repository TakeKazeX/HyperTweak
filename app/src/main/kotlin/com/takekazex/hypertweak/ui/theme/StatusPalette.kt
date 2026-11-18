package com.takekazex.hypertweak.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.takekazex.hypertweak.ui.chrome.isAppInDarkTheme

/** Semantic status tones shared by status cards, result cards, log levels and destructive actions. */
enum class StatusTone { Success, Warning, Error, Info }

/**
 * @property container Tinted card background.
 * @property onContainer Text on [container].
 * @property accent Icons, badges and level markers.
 */
data class StatusColors(val container: Color, val onContainer: Color, val accent: Color)

@Composable
fun statusColors(tone: StatusTone): StatusColors = statusColors(tone, isAppInDarkTheme())

fun statusColors(tone: StatusTone, dark: Boolean): StatusColors = when (tone) {
    StatusTone.Success -> if (dark) StatusColors(Color(0xFF1A3825), Color(0xFF9BE6B3), Color(0xFF36D167))
    else StatusColors(Color(0xFFDFFAE4), Color(0xFF12622D), Color(0xFF36D167))
    StatusTone.Warning -> if (dark) StatusColors(Color(0xFF3D300F), Color(0xFFFFD166), Color(0xFFFFB300))
    else StatusColors(Color(0xFFFFF3C4), Color(0xFF7A5200), Color(0xFFE6A700))
    StatusTone.Error -> if (dark) StatusColors(Color(0xFF3A1F1F), Color(0xFFFFB4AB), Color(0xFFE5484D))
    else StatusColors(Color(0xFFFFECEC), Color(0xFF8C1D18), Color(0xFFD32F2F))
    StatusTone.Info -> if (dark) StatusColors(Color(0xFF14283A), Color(0xFF9CCBFF), Color(0xFF42A5F5))
    else StatusColors(Color(0xFFE3F1FD), Color(0xFF0B4A82), Color(0xFF0091EA))
}
