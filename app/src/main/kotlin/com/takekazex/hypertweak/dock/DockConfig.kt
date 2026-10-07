package com.takekazex.hypertweak.dock

import kotlin.math.min
import kotlin.math.roundToInt

/** One versioned preference is the complete transaction, including disable and geometry. */
data class DockConfig(
    val enabled: Boolean = false,
    val style: Int = GLASS,
    val height: Int = 94,
    val horizontalMargin: Int = 10,
    val bottomMargin: Int = 36,
    val radius: Int = 28,
) {
    fun encode(): String = "3|${if (enabled) 1 else 0}|$style|$height|$horizontalMargin|$bottomMargin|$radius"

    companion object {
        const val KEY = "launcher_dock_background"
        const val GLASS = 0
        const val FROSTED = 1
        fun decode(value: String): DockConfig? {
            if (value.length > 80) return null
            val fields = value.split('|').map { it.toIntOrNull() ?: return null }
            if (fields.size != 7 || fields[0] !in 1..3 || fields[1] !in 0..1 || fields[2] !in 0..1 ||
                fields[3] !in 40..180 || fields[4] !in 0..100 || fields[5] !in 0..160 || fields[6] !in 0..90) return null
            // Only migrate the old untouched geometry. An explicitly adjusted layout remains exact.
            val legacyDefault = (fields[0] == 1 && fields.drop(3) == listOf(88, 16, 12, 28)) ||
                (fields[0] == 2 && fields.drop(3) == listOf(88, 16, 40, 28))
            return if (legacyDefault) DockConfig(enabled = fields[1] == 1, style = fields[2])
                else DockConfig(fields[1] == 1, fields[2], fields[3], fields[4], fields[5], fields[6])
        }
    }
}

/** Measured launcher row: 68 dp icons, 49 dp from screen bottom, 12 dp row inset. */
object DockPreviewGeometry {
    fun icons(width: Int, height: Int, density: Float): List<DockBounds> {
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0) return emptyList()
        val size = (68 * density).roundToInt().coerceIn(1, min(width, height))
        val inset = (12 * density).coerceAtMost(width / 2f)
        val top = (height - 49 * density - size).roundToInt().coerceAtLeast(0)
        return List(4) { index ->
            DockBounds((inset + (index + .5f) * (width - 2 * inset) / 4 - size / 2f).roundToInt(), top, size, size, 0f)
        }
    }
}

data class DockBounds(val x: Int, val y: Int, val width: Int, val height: Int, val radius: Float)

object DockGeometry {
    private val homeTitles = setOf("com.miui.home/com.miui.home.launcher.Launcher", "com.miui.home/.launcher.Launcher",
        "com.miui.home.launcher.Launcher")
    fun resolve(width: Int, height: Int, density: Float, config: DockConfig): DockBounds? {
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0 || DockConfig.decode(config.encode()) == null) return null
        val margin = (config.horizontalMargin * density).roundToInt().coerceAtMost(width / 2)
        val bottom = (config.bottomMargin * density).roundToInt().coerceAtMost(height)
        val panelWidth = width - margin * 2
        val panelHeight = (config.height * density).roundToInt().coerceAtMost(height - bottom)
        if (panelWidth < 1 || panelHeight < 1) return null
        return DockBounds(margin, height - bottom - panelHeight, panelWidth, panelHeight,
            min(config.radius * density, min(panelWidth, panelHeight) / 2f))
    }

    fun isHomeWindow(packageName: String?, title: String, type: Int, display: Int): Boolean =
        packageName == "com.miui.home" && display == 0 && type in 1..2 && title in homeTitles

    fun isHomeOverlay(packageName: String?, title: String, type: Int, display: Int): Boolean =
        packageName == "com.miui.home" && display == 0 && type == 4 && title.startsWith("LauncherOverlayWindow:")
}
