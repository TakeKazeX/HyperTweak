@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.rules.systemui.aod.AodIconSettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.NetworkHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.SlotMaskApplier
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.presentation.icon.duo.DuoBattery
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoExpandedStyle
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoPolicy
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes
import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface
import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable
import com.takekazex.hypertweak.presentation.icon.toDuoNetwork
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.CcBatteryPercentStyle
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal fun DuoRenderer.nativeWifiGlyph(binding: DuoBinding): NativeWifiDrawable? {
        val id = binding.networkIds["wifi_signal"]?.takeIf { it != 0 } ?: return null
        val source = binding.icons.findViewById<View>(id) as? ImageView ?: return null
        val drawable = binding.wifiGlyph?.takeIf { it.source === source }
            ?: wifiTintResourceMethod?.let { transform ->
                NativeWifiDrawable(source) { resource ->
                    transform.invoke(null, resource, true, false) as? Int
                        ?: error("native Wi-Fi tint resource unavailable")
                }.also { binding.wifiGlyph = it }
            } ?: return null
        val ready = drawable.isReady()
        if (binding.wifiArtworkRevision != drawable.revision) {
            binding.wifiArtworkRevision = drawable.revision
            binding.view.invalidate()
        }
        return drawable.takeIf { ready }
    }

    /** Follow host Folme transforms without creating another animator or cancelling frames. */
internal fun DuoRenderer.privacyOffset(binding: DuoBinding, state: String?): Float {
        if (binding.surface != DuoSurface.HOME || !DuoPrivacyGeometry.isTransition(state)) return 0f
        var chip = binding.privacyView
        if (chip == null || !chip.isAttachedToWindow) {
            chip = if (binding.privacyId != 0) binding.parent.findViewById(binding.privacyId) else null
            binding.privacyView = chip
        }
        chip ?: return 0f
        if (chip.width <= 0 || chip.height <= 0) return 0f
        val rect = binding.privacyRect
        rect.set(0f, 0f, chip.width.toFloat(), chip.height.toFloat())
        var node: View = chip
        repeat(16) {
            if (node.visibility != View.VISIBLE || node.alpha <= 0.01f) return 0f
            node.matrix.mapRect(rect)
            rect.offset(node.left.toFloat(), node.top.toFloat())
            val ancestor = node.parent as? View ?: return 0f
            rect.offset(-ancestor.scrollX.toFloat(), -ancestor.scrollY.toFloat())
            if (ancestor === binding.parent) {
                return DuoPrivacyGeometry.offset(binding.view.left.toFloat(), binding.view.right.toFloat(),
                    rect.left, rect.right, 4f * chip.resources.displayMetrics.density,
                    binding.parent.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            }
            node = ancestor
        }
        return 0f
    }

internal fun DuoRenderer.setPrivacyInset(binding: DuoBinding, inset: Int) {
        if (binding.privacyInset == inset) return
        val icons = binding.icons
        val base = (icons.paddingEnd - binding.privacyInset).coerceAtLeast(0)
        binding.privacyInset = inset
        icons.setPaddingRelative(icons.paddingStart, icons.paddingTop, base + inset, icons.paddingBottom)
    }

    /** Snapshot the host's own airplane-slot policy before Duo changes anything. */
internal fun DuoRenderer.captureAirplaneMaskBaseline(binding: DuoBinding) {
        if (binding.hostIgnoredAirplane != null) return
        val ignored = ignoredSlots(binding.icons) ?: return
        binding.hostIgnoredAirplane = ignored.any { it == AIRPLANE_SLOT }
    }

    /**
     * Adds/removes only Duo's native-airplane suppression.  The baseline bit prevents restore from
     * exposing an airplane slot that was already hidden by Icon Tuner or the host configuration.
     */

internal fun DuoRenderer.setNativeAirplaneMasked(binding: DuoBinding, masked: Boolean) {
        // IconPosition owns the merged airplane mask. Never mutate ignoredSlots independently:
        // its next pass would restore that list and trigger another layout every frame.
        if (binding.airplaneMasked == masked) return
        binding.airplaneMasked = masked
        IconHostPorts.left.duoOwnershipChanged()
    }

    @Suppress("UNCHECKED_CAST")
internal fun DuoRenderer.ignoredSlots(view: View): MutableList<Any?>? =
        runCatching { field(view.javaClass, "ignoredSlots")?.get(view) as? MutableList<Any?> }.getOrNull()

internal fun DuoRenderer.foreground(binding: DuoBinding): Int {
        val battery = binding.battery
        val areas = read(battery, "mTintAreas")
        if (read(battery, "mUseTint") == true) {
            return (tintMethod?.invoke(null, areas, binding.view, read(battery, "mTintColor")) as? Int)
                ?: error("local tint unavailable")
        }
        val intensity = darkMethod?.invoke(null, areas, binding.view, read(battery, "mDarkIntensity")) as? Float
            ?: error("local dark intensity unavailable")
        return (read(battery, if (intensity > 0f) "mDarkColor" else "mLightColor") as? Int) ?: Color.WHITE
    }

internal fun DuoRenderer.restore(binding: DuoBinding) {
        aodController.restoreNativeFullAod(binding)
        panelHandover.clearSignalMotions(binding)
        panelHandover.clearPanelMotion(binding)
        panelHandover.restoreProxyPosition(binding)
        binding.expiry?.let(main::removeCallbacks)
        binding.expiry = null
        binding.missingSince = -1L
        binding.view.setClickTarget(null)
        binding.view.sourceIconSidePx = null
        binding.view.finishTransition()
        setPrivacyInset(binding, 0)
        binding.view.translationX = 0f
        binding.view.setBatteryOnly(false, null)
        binding.view.setPercent(false, false, null, null, null)
        binding.view.icon.cellularSignalPicture = null
        SlotMaskApplier.setDuoMask(binding.icons, false)
        setNativeAirplaneMasked(binding, false)
        if (!binding.active) {
            // Only a suppression the module made may be released by replaying the host's own
            // visibility; otherwise the host result is already the truth.
            if (binding.aodNativeSuppressed) visibilityMethod?.invoke(binding.battery)
            binding.aodNativeSuppressed = false
            return
        }
        binding.active = false
        binding.aodNativeSuppressed = false
        runCatching {
            if (batteryLayoutField?.get(binding.parent) === binding.view) batteryLayoutField?.set(binding.parent, binding.battery)
            field(binding.parent.javaClass, "mIsHideBattery")?.setBoolean(binding.parent, binding.hostHideBattery)
            binding.view.visibility = View.GONE
            visibilityMethod?.invoke(binding.battery)
            binding.parent.requestLayout()
        }.onFailure { DebugLog.w(TAG, "native battery restore failed", it) }
    }

internal fun DuoRenderer.detach(battery: View) {
        val binding = bindings.remove(battery) ?: return
        binding.reconciling = true
        restore(binding)
        binding.rowPhase = AodHostPhase.UNKNOWN
        binding.preDraw?.let { listener -> binding.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        // Removal during the parent's detach traversal can skip sibling detach callbacks.
        if (!enabled && Looper.myLooper() == Looper.getMainLooper()) {
            if (binding.view.parent === binding.parent) binding.parent.removeView(binding.view)
        } else main.post { if (binding.view.parent === binding.parent) binding.parent.removeView(binding.view) }
    }
