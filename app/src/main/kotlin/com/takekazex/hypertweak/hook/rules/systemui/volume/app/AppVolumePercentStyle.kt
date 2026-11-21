package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.takekazex.hypertweak.hook.rules.systemui.slider.PercentLabelStyle
import com.takekazex.hypertweak.hook.rules.systemui.slider.PercentLabels
import com.takekazex.hypertweak.hook.rules.systemui.slider.VolumeBadgeStyler
import com.takekazex.hypertweak.presentation.slider.SliderPercentageColors
import com.takekazex.hypertweak.hook.rules.systemui.slider.VolumeBadgePolicy
import com.takekazex.hypertweak.settings.contract.SliderPercentagePolicy

/**
 * App percentages reuse the native percentage typography and capsule material. An app-specific
 * accent is shaded for the light fill, dark track or themed capsule behind the text.
 */
internal class AppVolumePercentStyle(private val hostLoader: ClassLoader) {
    private val labels = PercentLabels()
    private val text = PercentLabelStyle(labels, hostLoader)
    private val capsule = VolumeBadgeStyler(TAG)

    var policy = SliderPercentagePolicy()

    fun inner(column: AppVolumeColumn, accent: Int?, neutral: Boolean) {
        val label = column.percent
        labels.capture(label, geometry = false)
        capsule.detachTextBackground(label)
        text.popup(label, policy)
        if (neutral) {
            column.setPercentColors(
                track = SliderPercentageColors.CC_ON_TRACK,
                fill = SliderPercentageColors.CC_ON_FILL,
            )
        } else {
            column.setPercentColors(
                track = accent?.let { legible(it, SliderPercentageColors.CC_TRACK) } ?: label.currentTextColor,
                fill = accent?.let { legible(it, Color.WHITE) } ?: label.currentTextColor,
            )
        }
    }

    fun outer(background: View, label: TextView, accent: Int?, neutral: Boolean) {
        labels.capture(label, geometry = false)
        // Same rule as the native capsule: it grows with a larger label, never below native size.
        capsule.restoreNativeSize(background, hostLoader, (policy.typography.effective().sizePercent / 100f).coerceAtLeast(1f))
        capsule.detachTextBackground(label)
        capsule.applyCapsule(background, expanded = false, hostLoader = hostLoader, style = policy.popupStyle)
        text.popup(label, if (neutral) policy.copy(unifiedColor = false) else policy)
        if (accent != null) {
            val surface = SliderPercentageColors.popup(VolumeBadgePolicy.isDark(label.context)).background
            label.setTextColor(legible(accent, ColorUtils.setAlphaComponent(surface, 255)))
        }
    }

    private fun legible(accent: Int, surface: Int): Int {
        if (ColorUtils.calculateContrast(accent, surface) >= MIN_CONTRAST) return accent
        val neutral = if (ColorUtils.calculateLuminance(surface) > 0.179) Color.BLACK else Color.WHITE
        var low = 0f
        var high = 1f
        repeat(7) {
            val mix = (low + high) / 2f
            if (ColorUtils.calculateContrast(ColorUtils.blendARGB(accent, neutral, mix), surface) >= MIN_CONTRAST) {
                high = mix
            } else {
                low = mix
            }
        }
        return ColorUtils.blendARGB(accent, neutral, high)
    }

    /** Style or theme changed: the next pass repaints every label and capsule. */
    fun invalidate() {
        text.clear()
        capsule.clear()
    }

    private companion object {
        const val TAG = "AppVolume"
        const val MIN_CONTRAST = 4.5
    }
}
