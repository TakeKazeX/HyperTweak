package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeOutput
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeRenderer
import com.takekazex.hypertweak.presentation.icon.MobileSignalState

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt
import com.takekazex.hypertweak.util.platform.ResourceLookup

/** Immutable inputs for one carrier render pass. */
internal data class CarrierRenderInput(
    val mobileState: MobileSignalState,
    val simNames: Map<Int, String>, val showBadge: Boolean,
    val keepTypeOnWifi: Boolean, val typeConfig: MobileTypeConfig,
    val nativeWifi: CarrierNativeGlyph?
)

/** Bitmap/row presentation only; host subscriptions, masks and handover stay outside. */
internal class CarrierRowRenderer {
    private companion object {
        const val TAG = "IconTuner"
    }
    private fun readField(target: Any, name: String): Any? = runCatching { HostAccess.read(target, name) }.getOrNull()
    fun clear() { iconHeights.clear() }
    fun invalidate(resources: Resources) { iconHeights.remove(resources) }
    // Main-thread only. Values do not retain their weak Resources keys.
    private class IconHeight(
        val configuration: Configuration, val density: Float,
        val assets: WeakReference<AssetManager>, val pixels: Int
    )
    private val iconHeights = WeakHashMap<Resources, IconHeight>()

    fun renderRow(parts: CarrierRowParts, rows: List<CarrierRowModel>, input: CarrierRenderInput) {
        val model = rows.firstOrNull { it.slot == parts.slot && it.visible }
        parts.model = model
        // Read the controller's raw custom value, not its merged carrier/SPN output: only the
        // explicit Settings override may take priority over a renamed SIM. Controller and row
        // callbacks schedule renders when custom names change; reflection is cached by class.
        val controller = readField(parts.row, "carrierTextController")
        val customName = controller?.let { readField(it, "mCustomCarrier") as? Array<*> }
            ?.getOrNull(parts.slot) as? CharSequence
        val name = CarrierBlockPolicy.carrierName(
            customName, model?.subId?.let(input.simNames::get), parts.nativeCarrierText
        )
        if (parts.carrierText.text.toString() != name.toString()) parts.carrierText.text = name
        parts.carrierText.visibility = if (input.mobileState.airplaneMode || parts.carrierText.text.isNullOrBlank()) View.GONE else View.VISIBLE
        parts.airplaneText.visibility = if (model?.airplane == true) View.VISIBLE else View.GONE
        parts.badge.visibility = if (input.mobileState.airplaneMode || !input.showBadge || model == null) View.GONE else View.VISIBLE
        val tint = syncTint(parts)
        if (model == null) {
            // Before reducer readiness the host label remains usable. An actually absent
            // slot is hidden only when another subscription establishes a known row set.
            parts.row.visibility = if (input.mobileState.airplaneMode || rows.any { it.visible }) View.GONE else parts.rowState.visibility
            parts.signal.setImageDrawable(null)
            parts.signal.visibility = View.GONE
            parts.networkSlot.visibility = View.GONE
            parts.wifi.visibility = View.GONE
            parts.type.visibility = View.GONE
            parts.networkFade.clear()
            parts.typeSuppressed = false
            parts.nativeWifi = null
            parts.wifi.setImageDrawable(null)
            parts.typeBitmap = null
            parts.rendered = null
            return
        }
        parts.row.visibility = View.VISIBLE
        val context = parts.row.context
        val densityDpi = context.resources.displayMetrics.densityDpi
        val fontScale = context.resources.configuration.fontScale
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val nativeWifi = input.nativeWifi.takeIf { model.hasWifi }
        val signalIconId = model.subId?.let { input.mobileState.subscriptions[it]?.signalIconId }
            ?.takeIf { model.hasSignal }
        val nativeSignal = parts.nativeSignal.takeIf { model.hasSignal }
        val state = CarrierRowRenderState(model, signalIconId,
            nativeSignal, nativeSignal?.intrinsicWidth ?: 0, nativeSignal?.intrinsicHeight ?: 0,
            densityDpi, fontScale, rtl,
            iconHeightPx(context), parts.carrierText.text.toString(), parts.badge.text.toString(),
            input.mobileState.subscriptionOrder.size == 1,
            input.mobileState.subscriptions[model.networkSubId]?.roaming == true, input.keepTypeOnWifi, input.typeConfig, input.showBadge,
            parts.carrierText.typeface, parts.carrierText.textSize, nativeWifi,
            nativeWifi?.intrinsicWidth ?: 0, nativeWifi?.intrinsicHeight ?: 0)
        if (parts.rendered == state) return

        publishSignal(parts.signal, nativeSignal, signalIconId, iconHeightPx(context))

        // Wi-Fi and its type follow the active data SIM (开关 4: types on both rows).
        val inlineType = nativeSignal?.children != null
        val typeBitmap = model.typeText?.takeIf { !inlineType && it.isNotBlank() }?.let { text ->
            runCatching {
                MobileTypeRenderer.render(
                    output = MobileTypeOutput(
                        text = text,
                        isSingle = input.mobileState.subscriptionOrder.size == 1,
                        isRoaming = input.mobileState.subscriptions[model.networkSubId]?.roaming == true
                    ),
                    config = input.typeConfig,
                    iconHeightPx = CarrierBlockPolicy.typeHeight(iconHeightPx(context),
                        context.resources.displayMetrics.density, fontScale),
                    densityDpi = densityDpi,
                    fontScale = fontScale,
                    rtl = rtl,
                    // Inherit the live host typography, including Xiaomi font/weight changes.
                    hostTypeface = parts.carrierText.typeface,
                    hostTextSizePx = parts.carrierText.textSize
                )
            }.onFailure { DebugLog.w(TAG, "carrier type render failed", it) }.getOrNull()
        }
        publishNetwork(parts, typeBitmap, nativeWifi, tint, input.keepTypeOnWifi)
        parts.rendered = state.takeIf {
            (model.signalLevel == null || parts.signal.drawable != null) &&
                (inlineType || model.typeText.isNullOrBlank() || typeBitmap != null)
        }
        parts.row.contentDescription = buildString {
            if (input.showBadge) append(parts.badge.text).append(", ")
            if (model.airplane) append("Airplane mode") else append(parts.carrierText.text)
            model.signalLevel?.let { append(", ").append(it).append("/4") }
            if (inlineType || !parts.typeSuppressed) model.typeText?.let { append(", ").append(it) }
            if (nativeWifi != null) append(", Wi-Fi")
        }
    }

    /** Match the native glyph's foreground opacity while keeping the host's palette and states. */
    fun syncTint(parts: CarrierRowParts): Int {
        val hostColors = parts.carrierText.textColors
        if (hostColors !== parts.appliedCarrierColors) {
            val opaqueColors = hostColors.withAlpha(255)
            parts.nativeCarrierColors = hostColors
            parts.appliedCarrierColors = opaqueColors
            parts.carrierText.setTextColor(opaqueColors)
        }
        val tint = parts.carrierText.currentTextColor
        val density = parts.row.resources.displayMetrics.density
        if (parts.badgeTint == tint && parts.badgeDensity == density) return tint
        parts.badge.setTextColor(tint)
        parts.airplaneText.setTextColor(tint)
        val colors = ColorStateList.valueOf(tint)
        parts.signal.setColorFilter(tint)
        parts.type.imageTintList = colors
        parts.badgeDrawable.cornerRadius = 2 * density
        parts.badgeDrawable.setStroke(density.roundToInt().coerceAtLeast(1), tint)
        parts.badgeTint = tint
        parts.badgeDensity = density
        if (parts.badge.background !== parts.badgeDrawable) parts.badge.background = parts.badgeDrawable
        return tint
    }

    /** One measured trailing position for cellular type and Wi-Fi, including the dual-display option. */
    private fun publishNetwork(parts: CarrierRowParts, type: Bitmap?, wifi: CarrierNativeGlyph?, tint: Int, keepTypeOnWifi: Boolean) {
        // Keep the previous drawable in the same box while its alpha exits. The model bitmap
        // below still becomes null immediately, so a stale Wi-Fi glyph cannot claim the mask.
        if (type != null || parts.type.drawable == null) publish(parts.type, type, tint)
        if (wifi != null) {
            if (parts.wifi.drawable !== wifi) parts.wifi.setImageDrawable(wifi)
            val params = parts.wifi.layoutParams
            if (params.width != wifi.intrinsicWidth || params.height != wifi.intrinsicHeight) {
                params.width = wifi.intrinsicWidth
                params.height = wifi.intrinsicHeight
                parts.wifi.layoutParams = params
            }
            parts.wifi.visibility = View.VISIBLE
        }
        parts.typeBitmap = type
        parts.nativeWifi = wifi
        val mode = CarrierNetworkChoice.select(
            cellular = type != null,
            wifiDefaultAndRendered = wifi != null,
            keepCellularType = keepTypeOnWifi,
            cellularSuppressed = parts.model?.typeSuppressed == true
        )
        parts.typeSuppressed = parts.model?.typeSuppressed == true || mode == CarrierNetworkMode.WIFI
        val icon = iconHeightPx(parts.row.context)
        val gap = (4 * parts.row.resources.displayMetrics.density).roundToInt()
        val width = CarrierNetworkChoice.requiredWidth(
            minimum = icon,
            cellularWidth = type?.width ?: 0,
            wifiWidth = wifi?.intrinsicWidth ?: 0,
            keepBoth = keepTypeOnWifi && type != null,
            gap = gap,
            previousWidth = parts.networkWidth
        )
        if (width != parts.networkWidth) {
            parts.networkWidth = width
            val params = parts.networkSlot.layoutParams
            if (params.width != width) { params.width = width; parts.networkSlot.layoutParams = params }
        }
        val height = maxOf(icon, type?.height ?: 0, wifi?.intrinsicHeight ?: 0)
        val slotParams = parts.networkSlot.layoutParams
        if (slotParams.height != height) {
            slotParams.height = height
            parts.networkSlot.layoutParams = slotParams
        }
        val both = keepTypeOnWifi && type != null
        val typeGravity = Gravity.START or Gravity.CENTER_VERTICAL
        val wifiGravity = if (both) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        listOf(parts.type to typeGravity, parts.wifi to wifiGravity).forEach { (view, gravity) ->
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            if (params.gravity != gravity) { params.gravity = gravity; view.layoutParams = params }
        }
        parts.networkFade.select(mode)
    }

    private fun publish(view: ImageView, bitmap: Bitmap?, tint: Int) {
        if (bitmap == null || bitmap.isRecycled) {
            view.visibility = View.GONE
            return
        }
        view.setImageBitmap(bitmap)
        // Bitmaps are already rendered in physical pixels. Avoid ImageView's intrinsic-density
        // scaling enlarging the Wi-Fi/type glyph and consuming the name's width a second time.
        val params = view.layoutParams
        if (params.width != bitmap.width || params.height != bitmap.height) {
            params.width = bitmap.width; params.height = bitmap.height
            view.layoutParams = params
        }
        view.imageTintList = ColorStateList.valueOf(tint)
        view.visibility = View.VISIBLE
    }

    private fun publishSignal(view: ImageView, native: CarrierNativeGlyph?, resourceId: Int?, fallbackHeight: Int) {
        val drawable = native ?: resourceId?.let {
            runCatching { view.context.getDrawable(it) }
                .onFailure { error -> DebugLog.w(TAG, "carrier native signal resource unavailable", error) }
                .getOrNull()
        }
        if (view.drawable !== drawable) view.setImageDrawable(drawable)
        view.visibility = if (drawable == null) View.GONE else View.VISIBLE
        if (drawable == null) return
        val params = view.layoutParams
        val height = native?.intrinsicHeight ?: fallbackHeight
        val width = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            (height.toFloat() * drawable.intrinsicWidth / drawable.intrinsicHeight).roundToInt()
        } else height
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            view.layoutParams = params
        }
    }

    fun iconHeightPx(context: Context): Int {
        val resources = context.resources
        val configuration = resources.configuration
        val density = resources.displayMetrics.density
        iconHeights[resources]?.let { cached ->
            if (cached.configuration == configuration && cached.density == density &&
                cached.assets.get() === resources.assets) return cached.pixels
        }
        val height = runCatching {
            val id = ResourceLookup.identifier(resources, "status_bar_icon_height", "dimen", "com.android.systemui")
            if (id != 0) resources.getDimensionPixelSize(id) else null
        }.getOrNull()
        val pixels = CarrierBlockPolicy.iconHeight(height, density)
        iconHeights[resources] = IconHeight(Configuration(configuration), density, WeakReference(resources.assets), pixels)
        return pixels
    }

}
