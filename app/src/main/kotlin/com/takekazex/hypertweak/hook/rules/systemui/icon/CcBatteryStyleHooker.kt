package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.provider.Settings
import android.view.View
import java.util.WeakHashMap
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field

/**
 * Control-center battery percentage style (OS4 `MiuiBatteryMeterView`).
 *
 * The stock view renders the system `battery_indicator_style` and, for the CN default (`1`), draws
 * the number inside a hollow glyph. Two switches retarget only the control center's own instances:
 *
 * - 开关 1 (`KEY_CC_BATTERY_PERCENT_OUTSIDE`) forces style `3` — solid icon plus the percentage in
 *   the sibling container outside it — by rewriting the argument of `onBatteryStyleChanged(int)`,
 *   so the host itself performs the icon/percentage visibility swap and measurement.
 * - 开关 2 (`KEY_CC_BATTERY_PERCENT_LEFT`) re-lays that outside percentage on the leading edge
 *   after `onLayout(boolean,int,int,int,int)`, which the host positions on the trailing edge.
 *
 * Only the expanded QS battery (`layoutFromTag` 6) uses the chosen style. The collapsed
 * QS_FAKE mirror (`5`) keeps the HOME battery style; SystemUI crossfades those two prepared
 * views with its own Folme alpha animation during expansion and collapse.
 *
 * When Duo owns a control-center battery slot, `DuoSignalHooker` mirrors the host's leading
 * percentage TextViews beside the three-in-one glyph; the retained battery remains `GONE` behind it.
 */
object CcBatteryStyleHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "CcBatteryStyle"
    private const val BATTERY = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"

    /** The real control-center row; QS_FAKE follows the native HOME battery. */
    private const val TAG_CC_STATUS_BAR = 6

    /** `MiuiBatteryMeterView.mBatteryStyle`: battery icon plus the outside percentage container. */
    private const val STYLE_ICON_AND_PERCENT = 3

    @Volatile private var leadingPercent = false
    private var retiring = false
    private val hostStyles = WeakHashMap<View, Int>()
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private var tagField: Field? = null
    private var iconField: Field? = null
    private var percentField: Field? = null

    override fun onHook() {
        if (!isMainProcess) return
        val outside = Preferences.getBoolean(Preferences.KEY_CC_BATTERY_PERCENT_OUTSIDE, false)
        leadingPercent = Preferences.getBoolean(Preferences.KEY_CC_BATTERY_PERCENT_LEFT, false)
        if (!outside && !leadingPercent) return
        val battery = BATTERY.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "control-center battery style", "host class not found")
            return
        }
        tagField = field(battery, "mLayoutFromTag")
        iconField = field(battery, "mBatteryDigitalView")
        percentField = field(battery, "mBatteryPercentContainer")
        if (tagField == null || iconField == null || percentField == null) {
            DebugLog.hookSkipped(TAG, "control-center battery style", "unsupported signature")
            return
        }
        // The style setter decides the icon/percentage visibility and the measured width, so the
        // forced style enters through the host's own parameter rather than an after-the-fact
        // visibility patch. `onBatteryStyleChanged` also runs again on island/AOD/state changes,
        // and these hooks only exist while at least one switch is on.
        battery.findMethodOrNull { name("onBatteryStyleChanged"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    val batteryView = param.thisObject as? View ?: return@before
                    if (retiring || !isControlCenter(batteryView)) return@before
                    val requested = param.args[0] as? Int ?: return@before
                    hostStyles[batteryView] = requested
                    param.args[0] = STYLE_ICON_AND_PERCENT
                }
                after { param ->
                    val view = param.thisObject as? View ?: return@after
                    if (!retiring) hostStyles[view]?.let { style -> field(view.javaClass, "mStoreRealStyle")?.setInt(view, style) }
                }
            }
        }
        // The host's onLayout always restores the trailing-edge order first, so the pair is
        // re-placed unconditionally after it; no state has to be remembered per instance.
        battery.findMethodOrNull { name("onLayout"); paramCount(5) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    val batteryView = param.thisObject as? View ?: return@after
                    guarded { placeLeading(batteryView) }
                }
            }
        }
        DebugLog.hookRegistered(TAG, "control-center battery outside=$outside leading=$leadingPercent")
    }

    override fun onPrepareHotReload() {
        StatusIconHostAccess.onMain {
            retiring = true
            leadingPercent = false
            hostStyles.entries.toList().forEach { (view, style) ->
                StatusIconHostAccess.method(view, "onBatteryStyleChanged", Int::class.javaPrimitiveType!!)?.invoke(view, style)
                view.requestLayout()
            }
            hostStyles.clear()
        }
    }

    internal fun recoverExistingViews(views: List<View>) {
        views.filter { it.javaClass.name == BATTERY }.forEach { view ->
            // Recover even when both switches are disabled: a previous generation forced style 3.
            val style = Settings.System.getInt(view.context.contentResolver, "battery_indicator_style", 1)
            StatusIconHostAccess.method(view, "onBatteryStyleChanged", Int::class.javaPrimitiveType!!)?.invoke(view, style)
            view.requestLayout()
        }
    }

    /** Apply the expanded style only to the real QS row. */
    private fun isControlCenter(view: View): Boolean {
        val tag = tagField?.getInt(view) ?: return false
        return tag == TAG_CC_STATUS_BAR
    }

    private fun placeLeading(view: View) {
        if (!leadingPercent || !isControlCenter(view)) return
        val icon = iconField?.get(view) as? View ?: return
        val percent = percentField?.get(view) as? View ?: return
        // Only style 3 shows both; the in-glyph number has no side to move.
        if (icon.visibility != View.VISIBLE || percent.visibility != View.VISIBLE) return
        // `View.isLayoutRtl()` is hidden from the app SDK; the resolved public direction is the
        // same flag the host's onLayout reads.
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val leading = CcBatteryLayout.leadingEdge(view.width, view.paddingStart, rtl)
        val (percentSpan, iconSpan) = CcBatteryLayout.percentLeading(
            leading = leading,
            rtl = rtl,
            iconWidth = icon.width,
            percentWidth = percent.width
        )
        // Vertical placement stays the host's; only the horizontal order changes.
        if (percent.left != percentSpan.left || percent.right != percentSpan.right) {
            percent.layout(percentSpan.left, percent.top, percentSpan.right, percent.bottom)
        }
        if (icon.left != iconSpan.left || icon.right != iconSpan.right) {
            icon.layout(iconSpan.left, icon.top, iconSpan.right, icon.bottom)
        }
    }

    private fun field(type: Class<*>, name: String): Field? = fields.getOrPut(type to name) {
        var current: Class<*>? = type
        var found: Field? = null
        while (current != null && found == null) {
            found = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            current = current.superclass
        }
        found
    }

    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure {
            DebugLog.w(TAG, "control-center battery layout failed", it)
        }
    }
}
