package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Control-center-only date policy and compact carrier/battery constraints. */
object ControlCenterHeaderHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val CONTROLLER_CLASS =
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderController"
    private const val CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock"
    private const val DATE_CLASS = "com.android.systemui.controlcenter.phone.widget.ControlCenterDateView"
    private const val PARENT_ID = 0
    private const val UNSET = -1
    private val main = Handler(Looper.getMainLooper())
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val dates = WeakHashMap<View, DateState>()
    private val layouts = WeakHashMap<View, LayoutState>()
    private val compactHeaders = WeakHashMap<ViewGroup, Pair<View, View?>>()
    private val compactCarrierVisibility = WeakHashMap<ViewGroup, Int>()

    @Volatile private var carrierLeft = false
    @Volatile private var hideDate = false

    /** True when the compact carrier feature owns the two-line control-center header. */
    fun secondRowStatusIconsEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CC_HIDE_DATE, false) &&
            Preferences.getBoolean(Preferences.KEY_CC_CARRIER_TWO_LINE, false)

    private class DateState(var policy: Int, val visibility: Int)
    private class LayoutState(view: View) {
        val params = LinkedHashMap<String, Int>()
        val gravity = (view as? LinearLayout)?.gravity
    }

    override fun onPrepareHotReload() {
        carrierLeft = false
        hideDate = false
        val restore = {
            dates.forEach { (date, state) ->
                runCatching {
                    field(date.javaClass, "mPolicyVisibility")?.setInt(date, state.policy)
                    date.visibility = state.visibility
                }
            }
            dates.clear()
            layouts.keys.toList().forEach(::restoreLayout)
            compactCarrierVisibility.toList().forEach { (carrier, visibility) ->
                runCatching { carrier.visibility = visibility }
            }
            compactCarrierVisibility.clear()
            compactHeaders.clear()
            fields.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) restore() else {
            val latch = CountDownLatch(1)
            main.post { try { restore() } finally { latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS)) { "carrier header cleanup timed out" }
        }
    }

    override fun onHook() {
        carrierLeft = Preferences.getBoolean(Preferences.KEY_CC_CARRIER_LEFT, false)
        hideDate = Preferences.getBoolean(Preferences.KEY_CC_HIDE_DATE, false)
        if (!carrierLeft && !hideDate) return
        val controller = CONTROLLER_CLASS.toClassOrNull() ?: return
        // Lifecycle hooks also cover compiled callers that inline the small visibility helpers.
        listOf("updateConstraint", "updateDateVisibility", "updateCarrierAndPrivacyVisible",
            "onInit", "onConfigChanged", "onScreenLayoutSizeChanged", "updateDimens").forEach { name ->
            controller.declaredMethods.filter { it.name == name }.forEach { method ->
                deoptimize(method)
                method.hook { after { param -> guarded {
                    val owner = param.thisObject
                    if (name == "updateConstraint") {
                        (read(owner, "carrierLayout") as? View)?.let(layouts::remove)
                        (read(owner, "controlCenterStatusBar") as? View)?.let(layouts::remove)
                        (read(owner, "fakeStatusBar") as? View)?.let(layouts::remove)
                    }
                    (read(owner, "dateView") as? View)?.let(::hideDateView)
                    (read(owner, "carrierLayout") as? ViewGroup)?.let { carrier ->
                        if (name == "updateCarrierAndPrivacyVisible") {
                            reapplyCompactCarrierVisibility(carrier)
                        }
                        updateCarrierLayout(carrier)
                    }
                } } }
            }
        }
        val combined = "com.android.systemui.controlcenter.shade.CombinedHeaderController".toClassOrNull()
        combined?.findMethodOrNull { name("updateControlCenterHeaderLayout"); noParams() }?.let { method ->
            deoptimize(method)
            method.hook { after { param -> guarded {
                (read(param.thisObject, "controlCenterCarrierLayout") as? ViewGroup)
                    ?.let(::updateCarrierLayout)
            } } }
        }
        if (hideDate) hookClockPolicy()
        DebugLog.hookRegistered(TAG, "control-center header left=$carrierLeft hideDate=$hideDate")
    }

    internal fun recoverExistingViews(views: List<View>) {
        if (!carrierLeft && !hideDate) return
        views.forEach { view ->
            guarded {
                if (isDate(view)) hideDateView(view)
                if (view.javaClass.name == "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout") {
                    (view as? ViewGroup)?.let(::updateCarrierLayout)
                }
            }
        }
    }

    private fun hookClockPolicy() {
        val clock = CLOCK_CLASS.toClassOrNull() ?: return
        listOf("onAttachedToWindow", "updateClockVisibility").forEach { name ->
            clock.findMethodOrNull { name(name); noParams() }?.let { method ->
                deoptimize(method)
                method.hook { after { param -> guarded {
                    (param.thisObject as? View)?.let(::hideDateView)
                } } }
            }
        }
        clock.findMethodOrNull { name("setPolicyVisibility"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param -> guarded {
                    val date = param.thisObject as? View ?: return@guarded
                    if (!isDate(date)) return@guarded
                    val requested = param.args.getOrNull(0) as? Int ?: return@guarded
                    rememberDate(date)?.policy = requested
                    param.args[0] = View.GONE
                } }
                after { param -> guarded { (param.thisObject as? View)?.let(::hideDateView) } }
            }
        }
    }

    private fun isDate(view: View): Boolean = hideDate && view.javaClass.name == DATE_CLASS &&
        view.id != 0 && view.id == id(view, "normal_control_center_date_view")

    private fun rememberDate(view: View): DateState? {
        if (!isDate(view)) return null
        return dates.getOrPut(view) {
            DateState(field(view.javaClass, "mPolicyVisibility")?.getInt(view) ?: View.VISIBLE,
                view.visibility)
        }
    }

    private fun hideDateView(date: View) {
        rememberDate(date) ?: return
        field(date.javaClass, "mPolicyVisibility")?.setInt(date, View.GONE)
        if (date.visibility != View.GONE) date.visibility = View.GONE
    }

    /** Only the verified normal phone portrait header uses this constraint graph. */
    fun supportsCompactLayout(view: View): Boolean {
        val config = view.resources.configuration
        return config.orientation == Configuration.ORIENTATION_PORTRAIT &&
            config.screenWidthDp in 300..599
    }

    /** Re-shows the two-line carrier block after SystemUI hides it for a privacy prompt. */
    fun ensureCompactCarrierVisible(carrier: ViewGroup) {
        if (!ControlCenterCarrierBlockHooker.ownsLayout(carrier) || !supportsCompactLayout(carrier)) return
        compactCarrierVisibility.putIfAbsent(carrier, carrier.visibility)
        if (carrier.visibility != View.VISIBLE) {
            carrier.visibility = View.VISIBLE
            carrier.requestLayout()
        }
    }

    /** Keep the last host-requested visibility for reversible cleanup across privacy changes. */
    private fun reapplyCompactCarrierVisibility(carrier: ViewGroup) {
        if (!ControlCenterCarrierBlockHooker.ownsLayout(carrier) || !supportsCompactLayout(carrier)) {
            compactCarrierVisibility.remove(carrier)
            return
        }
        compactCarrierVisibility[carrier] = carrier.visibility
        if (carrier.visibility != View.VISIBLE) {
            carrier.visibility = View.VISIBLE
            carrier.requestLayout()
        }
    }

    /** Called after carrier measurement too, so the right cluster tracks the actual first row. */
    fun updateCarrierLayout(carrier: ViewGroup) = guarded {
        val parent = carrier.parent as? ViewGroup ?: return@guarded
        if (ControlCenterCarrierBlockHooker.ownsLayout(carrier) && supportsCompactLayout(carrier)) {
            val statusId = id(carrier, "normal_control_center_status_bar")
            val status = parent.findViewById<View>(statusId) ?: return@guarded
            val fake = parent.findViewById<View>(id(carrier, "normal_fake_control_center_status_bar"))
            compactHeaders[carrier] = status to fake
            // Keep the status/battery root at the first row: the battery belongs to the header's
            // first-row anchor. IconPositionHooker splits only the statusIcons children against
            // the carrier block's two measured row centers, so ordinary icons can return to row 2
            // without dragging the battery or its MiuiStatusBatteryContainer with them.
            change(carrier, mapOf(
                "width" to 0, "height" to ViewGroup.LayoutParams.WRAP_CONTENT,
                "startToStart" to PARENT_ID, "startToEnd" to UNSET,
                "endToStart" to statusId, "endToEnd" to UNSET,
                "topToTop" to UNSET, "topToBottom" to UNSET,
                "bottomToTop" to UNSET, "bottomToBottom" to PARENT_ID,
                "bottomMargin" to 0
            ))
            change(status, mapOf(
                "width" to ViewGroup.LayoutParams.WRAP_CONTENT,
                "startToStart" to UNSET, "startToEnd" to UNSET,
                "endToStart" to UNSET, "endToEnd" to PARENT_ID,
                "topToTop" to carrier.id, "topToBottom" to UNSET,
                "bottomToTop" to UNSET, "bottomToBottom" to UNSET,
                "topMargin" to (ControlCenterCarrierBlockHooker.firstRowCenter(carrier) -
                    status.measuredHeight / 2),
                "bottomMargin" to 0
            ))
            // The host applies the SAME Y translation to real and fake rows. Keeping the fake
            // anchored to the hidden date would leave the closing Duo below its HOME endpoint.
            fake?.let {
                change(it, mapOf(
                    "startToStart" to PARENT_ID, "startToEnd" to UNSET,
                    "topToTop" to statusId, "topToBottom" to UNSET,
                    "bottomToTop" to UNSET, "bottomToBottom" to statusId,
                    "topMargin" to 0, "bottomMargin" to 0
                ))
            }
            (carrier as? LinearLayout)?.let {
                if (it.gravity != (Gravity.START or Gravity.CENTER_VERTICAL))
                    it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        } else {
            compactHeaders.remove(carrier)?.let { (status, fake) ->
                restoreLayout(carrier)
                restoreLayout(status)
                fake?.let(::restoreLayout)
            }
            if (carrierLeft) applyCarrierSide(carrier)
        }
    }

    private fun applyCarrierSide(carrier: ViewGroup) {
        val params = carrier.layoutParams ?: return
        if (field(params.javaClass, "endToEnd")?.getInt(params) != PARENT_ID) return
        change(carrier, mapOf("endToEnd" to UNSET, "endToStart" to UNSET,
            "startToEnd" to UNSET, "startToStart" to PARENT_ID))
        (carrier as? LinearLayout)?.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private fun change(view: View, values: Map<String, Int>) {
        val params = view.layoutParams ?: return
        val resolved = values.keys.associateWith { field(params.javaClass, it) }
        if (resolved.values.any { it == null }) return
        val state = layouts.getOrPut(view) { LayoutState(view) }
        var changed = false
        values.forEach { (name, value) ->
            val f = resolved.getValue(name)!!
            state.params.putIfAbsent(name, f.getInt(params))
            if (f.getInt(params) != value) { f.setInt(params, value); changed = true }
        }
        if (changed) view.layoutParams = params
    }

    private fun restoreLayout(view: View) {
        val state = layouts.remove(view) ?: return
        runCatching {
            val params = view.layoutParams ?: return@runCatching
            state.params.forEach { (name, value) -> field(params.javaClass, name)?.setInt(params, value) }
            view.layoutParams = params
            if (view is LinearLayout) state.gravity?.let { view.gravity = it }
        }.onFailure { DebugLog.w(TAG, "carrier header restore failed", it) }
    }

    private fun id(view: View, name: String): Int =
        view.resources.getIdentifier(name, "id", "com.android.systemui")

    private fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)

    private fun field(type: Class<*>, name: String): Field? = fields.getOrPut(type to name) {
        var current: Class<*>? = type
        var found: Field? = null
        while (current != null && found == null) {
            found = runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            current = current.superclass
        }
        found
    }

    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { DebugLog.w(TAG, "control-center header update failed", it) }
    }
}
