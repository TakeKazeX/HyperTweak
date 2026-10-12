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
    /** Actual native radius in pixels, not a percentage of an undocumented token. */
    val blur: Int = DEFAULT_BLUR,
    /** Frosted color blend only. Bionic glass owns its color through its shader. */
    val tint: Int = 100,
    val opacity: Int = 100,
) {
    fun encode(): String = "5|${if (enabled) 1 else 0}|$style|$height|$horizontalMargin|$bottomMargin|$radius|$blur|$tint|$opacity"

    fun blurPixels(scale: Float = 1f): Int {
        require(scale.isFinite() && scale > 0f)
        return if (blur == 0) 0 else (blur * scale).roundToInt().coerceAtLeast(1)
    }

    /** Native output alpha affects the entire glass, including highlights and refraction. */
    fun glassShader(native: FloatArray, scale: Float = 1f): FloatArray {
        require(native.size == 42 && native.all { it.isFinite() })
        require(scale.isFinite() && scale > 0f)
        return native.copyOf().apply {
            this[18] *= opacity / 100f
            // Pixel lengths must follow the preview's reduced geometry; ratios/directions stay native.
            if (scale != 1f) {
                this[19] *= scale // edge width
                this[21] *= scale // refraction thickness
                this[22] *= scale // reflection offset
            }
        }
    }

    companion object {
        const val KEY = "launcher_dock_background"
        const val GLASS = 0
        const val FROSTED = 1
        // Verified Glass_Bionic_Medium_Normal uses 110 px for both blur textures.
        const val DEFAULT_BLUR = 110
        val BLUR_RANGE = 0..160
        val PERCENT_RANGE = 0..100

        fun decode(value: String): DockConfig? {
            if (value.length > 120) return null
            val fields = value.split('|').map { it.toIntOrNull() ?: return null }
            if (fields.size < 7) return null
            val expectedSize = when (fields[0]) { in 1..3 -> 7; 4 -> 11; 5 -> 10; else -> return null }
            if (fields.size != expectedSize || fields[1] !in 0..1 || fields[2] !in 0..1 ||
                fields[3] !in 40..180 || fields[4] !in 0..100 || fields[5] !in 0..160 || fields[6] !in 0..90) return null
            if (fields[0] == 4 && (fields[7] !in 0..200 || fields[8] !in PERCENT_RANGE ||
                fields[9] !in 0..200 || fields[10] !in 0..200)) return null
            if (fields[0] == 5 && (fields[7] !in BLUR_RANGE || fields[8] !in PERCENT_RANGE ||
                fields[9] !in PERCENT_RANGE)) return null
            val legacyDefault = (fields[0] == 1 && fields.drop(3) == listOf(88, 16, 12, 28)) ||
                (fields[0] == 2 && fields.drop(3) == listOf(88, 16, 40, 28))
            val geometry = if (legacyDefault) DockConfig(enabled = fields[1] == 1, style = fields[2])
                else DockConfig(fields[1] == 1, fields[2], fields[3], fields[4], fields[5], fields[6])
            // v4's coupled blur and optical multipliers do not describe the new native controls.
            // Restore native material defaults once, preserving every customized layout value.
            return if (fields[0] == 5) geometry.copy(blur = fields[7], tint = fields[8], opacity = fields[9]) else geometry
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
