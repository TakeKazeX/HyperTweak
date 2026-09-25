@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.aod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoBattery
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoContent
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.NumberFormat
import java.util.IdentityHashMap
import java.util.Locale

/** Adapter for the AOD plugin's battery row, usually hosted inside SystemUI. */
class AodStatusIconHooker : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private companion object {
        const val TAG = "AodStatusIcon"
        const val BATTERY_METER = "com.miui.aod.components.view.AODBatteryMeterView"
        const val AOD_CONTAINER = "com.miui.aod.components.view.AodContainerView"
        const val LINKAGE_CLOCK = "com.miui.aod.linkagestyle.LinkageStyleClockView"
        const val STYLE_INFO = "com.miui.aod.common.StyleInfo"
        const val BATTERY_STATUS = "com.miui.aod.util.BatteryStatus"
        const val MAML_CONTAINER_ID = "maml_view_container"
        const val BATTERY_CONTAINER_ID = "battery_container"
        const val BATTERY_METER_ID = "aod_battery_layout"
    }

    private val main = Handler(Looper.getMainLooper())
    private val bindings = IdentityHashMap<View, Binding>()
    private var signalObserver: AodSignalObserver? = null

    private data class NativeChild(
        val view: View,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams,
        val visibility: Int,
        val alpha: Float
    )

    private class Binding(
        val root: ViewGroup,
        val icon: View,
        val percent: TextView,
        var battery: DuoBattery?
    ) {
        var nativeChildren: List<NativeChild> = emptyList()
        var wrapper: FrameLayout? = null
        var duoView: View? = null
        var proxyPercent: TextView? = null
        var batteryGlyph: AodBatteryGlyphDrawable? = null
        var lastContent: DuoContent? = null
        var lastCenterSignal: Boolean? = null
        var composed = false
        var refreshing = false
    }

    override fun onPrepareHotReload() {
        bindings.values.toList().forEach { binding -> runCatching { restore(binding) } }
        bindings.clear()
        signalObserver?.stop()
        signalObserver = null
    }

    override fun onHook() {
        if (!PlatformLevel.isOs4 || !isMainProcess || !AodIconSettings.current().customized) return
        hookBatteryMeter()
        hookAodContainer()
        hookLinkageBattery()
    }

    private fun hookBatteryMeter() {
        val type = loadClass(BATTERY_METER) ?: run {
            DebugLog.w(TAG, "AODBatteryMeterView missing in target APK")
            return
        }
        // Prepare the replacement during inflation, before the first AOD frame can be drawn.
        type.findMethodOrNull { name("onFinishInflate"); parameterTypes() }?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                val root = param.thisObject as? View ?: return@after
                onMain { bind(root) }
            } }
        }
        type.findMethodOrNull { name("onAttachedToWindow"); parameterTypes() }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    val root = param.thisObject as? View ?: return@after
                    onMain { bind(root) }
                }
            }
        }
        type.findMethodOrNull { name("onDetachedFromWindow"); parameterTypes() }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    val root = param.thisObject as? View ?: return@after
                    onMain { unbind(root) }
                }
            }
        }
        val statusType = loadClass(BATTERY_STATUS)
        type.findMethodOrNull {
            name("onBatteryLevelChanged")
            if (statusType != null) parameterTypes(statusType)
        }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    val root = param.thisObject as? View ?: return@before
                    val battery = batteryFrom(root, param.args.firstOrNull()) ?: return@before
                    bindings[root]?.battery = battery
                }
                after { param ->
                    val root = param.thisObject as? View ?: return@after
                    onMain { bindings[root]?.let(::refresh) }
                }
            }
        }
        DebugLog.hookRegistered(TAG, "AOD battery view lifecycle and battery state")
    }

    private fun hookAodContainer() {
        val type = loadClass(AOD_CONTAINER) ?: run {
            DebugLog.w(TAG, "AodContainerView missing in target APK")
            return
        }
        val styleType = loadClass(STYLE_INFO)
        type.findMethodOrNull {
            name("update")
            if (styleType != null) parameterTypes(styleType, Int::class.javaPrimitiveType!!)
        }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    val container = param.thisObject as? View ?: return@after
                    onMain { applyContainer(container) }
                }
            }
        }
        type.findMethodOrNull {
            name("showBatteryIcon")
            parameterTypes(Boolean::class.javaPrimitiveType!!)
        }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    param.args[0] = AodIconSettings.current().showRow
                }
                after { param ->
                    val container = param.thisObject as? View ?: return@after
                    onMain { applyContainer(container) }
                }
            }
        }
        DebugLog.hookRegistered(TAG, "AOD battery container rebuild and visibility")
    }

    private fun hookLinkageBattery() {
        val type = loadClass(LINKAGE_CLOCK) ?: return
        type.findMethodOrNull { name("updateBattery"); parameterTypes() }?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                val clock = param.thisObject as? View ?: return@after
                onMain {
                    val widget = read(clock, "mBattery") as? View
                        ?: findNamedView(clock, BATTERY_CONTAINER_ID) ?: return@onMain
                    widget.visibility = if (AodIconSettings.current().showRow) View.VISIBLE else View.GONE
                    if (widget.visibility == View.VISIBLE) findViews(widget, BATTERY_METER).forEach(::bind)
                }
            } }
        }
    }

    private fun bind(rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val icon = read(root, "mBatteryIconView") as? View ?: findNamedView(root, "aod_battery_icon") ?: return
        val percent = read(root, "mBatteryTextDigitView") as? TextView
            ?: findNamedView(root, "aod_battery_digital") as? TextView ?: return
        val battery = currentBattery(root)
        val binding = bindings[root] ?: Binding(root, icon, percent, battery).also {
            bindings[root] = it
            DebugLog.i(TAG, "bound AOD battery row; duo=${AodIconSettings.current().duo}")
        }
        if (binding.battery == null) binding.battery = battery
        refresh(binding)
        updateObserver()
    }

    private fun unbind(root: View) {
        val binding = bindings.remove(root) ?: return
        restore(binding)
        updateObserver()
    }

    private fun refresh(binding: Binding) {
        if (binding.refreshing) return
        binding.refreshing = true
        try {
            val settings = AodIconSettings.current()
            if (!settings.showRow) {
                restoreComposition(binding)
                binding.root.visibility = View.GONE
                return
            }
            binding.root.visibility = View.VISIBLE
            if (!settings.duo) {
                restoreComposition(binding)
                binding.lastContent = null
                binding.lastCenterSignal = null
                applyNativePreferences(binding, settings)
                return
            }
            compose(binding)
            if (binding.lastCenterSignal != settings.centerSignal) {
                binding.lastContent = null
                binding.lastCenterSignal = settings.centerSignal
            }
            val content = (if (settings.centerSignal) signalContent(binding)
                else batteryOnlyContent(binding.battery)) ?: binding.lastContent
            if (content != null) {
                binding.lastContent = content
                renderDuo(binding, content, settings)
                binding.duoView?.visibility = View.VISIBLE
            } else {
                // Incomplete battery/telephony callbacks must not reveal the native row.
                binding.duoView?.visibility = View.INVISIBLE
            }
        } catch (error: Throwable) {
            DebugLog.w(TAG, "AOD battery view update failed", error)
            binding.duoView?.visibility = View.INVISIBLE
            binding.root.visibility = View.GONE
        } finally {
            binding.refreshing = false
            updateObserver()
        }
    }

    private fun compose(binding: Binding) {
        if (binding.composed) return
        val root = binding.root
        val children = (0 until root.childCount).mapNotNull { index ->
            val child = root.getChildAt(index)
            val oldParams = child.layoutParams
            if (oldParams == null) return@mapNotNull null
            NativeChild(child, index, copyLayoutParams(oldParams), child.visibility, child.alpha)
        }
        if (children.isEmpty()) return
        val frame = FrameLayout(root.context).apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.removeAllViews()
        children.forEach { child ->
            (child.view.parent as? ViewGroup)?.removeView(child.view)
            frame.addView(
                child.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            // The original percentage remains available to host callbacks and as a text
            // source, but must not contribute an invisible width beside Duo.
            child.view.visibility = if (child.view === binding.percent) View.GONE else View.INVISIBLE
        }
        val duo = DuoSignalHooker.createAodDuoView(root.context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(
            duo,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            frame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        )
        binding.nativeChildren = children
        binding.wrapper = frame
        binding.duoView = duo
        binding.composed = true
        DebugLog.i(TAG, "composed AOD Duo row")
    }

    private fun renderDuo(binding: Binding, content: DuoContent, settings: AodIconSettings) {
        val duo = binding.duoView ?: return
        val wrapper = binding.wrapper ?: return
        val batteryGlyph = if (settings.centerBattery) {
            binding.batteryGlyph ?: AodBatteryGlyphDrawable(binding.icon).also {
                binding.batteryGlyph = it
            }
        } else null
        val proxy = percentProxy(binding)
        if (settings.percentPosition == Preferences.AOD_PERCENT_POSITION_DUO_CENTER) {
            proxy.text = binding.battery?.percent?.let {
                NumberFormat.getIntegerInstance(Locale.getDefault()).format(it)
            }.orEmpty()
        } else {
            proxy.text = binding.percent.text.toString().ifBlank {
                binding.battery?.percent?.let { String.format(Locale.getDefault(), "%d%%", it) }.orEmpty()
            }
        }
        proxy.setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.percent.textSize)
        proxy.setTextColor(binding.percent.currentTextColor)
        proxy.typeface = binding.percent.typeface
        proxy.letterSpacing = binding.percent.letterSpacing
        proxy.visibility = View.VISIBLE
        val positionCenter = settings.percentPosition == Preferences.AOD_PERCENT_POSITION_DUO_CENTER
        val foreground = binding.percent.currentTextColor.takeIf { it != Color.TRANSPARENT } ?: Color.WHITE
        DuoSignalHooker.updateAodDuoView(
            context = binding.root.context,
            target = duo,
            content = content,
            centerBattery = settings.centerBattery,
            batteryDrawable = batteryGlyph,
            percentVisible = settings.percent,
            percentBelow = positionCenter,
            percentOnRight = settings.percentPosition == Preferences.AOD_PERCENT_POSITION_RIGHT,
            percentContainer = wrapper,
            percentValue = proxy,
            percentFallback = binding.battery?.percent?.toString(),
            foreground = foreground
        )
        binding.root.requestLayout()
    }

    private fun percentProxy(binding: Binding): TextView = binding.proxyPercent ?: TextView(binding.root.context).also {
        it.includeFontPadding = false
        binding.proxyPercent = it
    }

    private fun applyNativePreferences(binding: Binding, settings: AodIconSettings) {
        val position = if (settings.percentPosition == Preferences.AOD_PERCENT_POSITION_LEFT)
            Preferences.AOD_PERCENT_POSITION_LEFT else Preferences.AOD_PERCENT_POSITION_RIGHT
        positionNativePercent(binding, position)
        binding.icon.visibility = if (settings.batteryIcon) View.VISIBLE else View.GONE
        binding.percent.visibility = if (settings.percent) View.VISIBLE else View.GONE
        binding.root.requestLayout()
    }

    private fun positionNativePercent(binding: Binding, position: Int) {
        val root = binding.root
        if (root.indexOfChild(binding.icon) < 0 || root.indexOfChild(binding.percent) < 0) return
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val desiredIndex = if (position == Preferences.AOD_PERCENT_POSITION_LEFT) 0 else root.childCount - 1
        if (root.indexOfChild(binding.percent) != desiredIndex) {
            root.removeView(binding.percent)
            root.addView(binding.percent, desiredIndex.coerceIn(0, root.childCount),
                binding.nativeChildren.firstOrNull { it.view === binding.percent }?.layoutParams
                    ?: binding.percent.layoutParams)
        }
    }

    private fun restoreComposition(binding: Binding) {
        if (!binding.composed) return
        val root = binding.root
        val frame = binding.wrapper
        runCatching {
            frame?.let { if (it.parent === root) root.removeView(it) }
            root.removeAllViews()
            binding.nativeChildren.sortedBy(NativeChild::index).forEach { child ->
                (child.view.parent as? ViewGroup)?.removeView(child.view)
                child.view.layoutParams = copyLayoutParams(child.layoutParams)
                child.view.visibility = child.visibility
                child.view.alpha = child.alpha
                root.addView(child.view, child.index.coerceIn(0, root.childCount), child.view.layoutParams)
            }
        }.onFailure { DebugLog.w(TAG, "failed to restore native AOD children", it) }
        binding.wrapper = null
        binding.duoView = null
        binding.nativeChildren = emptyList()
        binding.composed = false
    }

    private fun restore(binding: Binding) {
        restoreComposition(binding)
    }

    private fun signalContent(binding: Binding): DuoContent? {
        val battery = binding.battery ?: return null
        val snapshot = signalObserver?.snapshot() ?: return null
        if (!snapshot.mobile.airplaneMode && !snapshot.mobile.canRenderReplacement()) return null
        return DuoPolicy.content(battery, snapshot.mobile, snapshot.network) { subId ->
            runCatching { android.telephony.SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
        }
    }

    private fun batteryOnlyContent(battery: DuoBattery?): DuoContent? = battery?.let {
        DuoContent(
            battery = it,
            wifiLevel = null,
            networkLabel = null,
            mobileLevel = 0,
            noService = false,
            noInternet = false
        )
    }

    private fun updateObserver() {
        val needsSignal = AodIconSettings.current().let { it.duo && it.centerSignal } && bindings.values.any { binding ->
            binding.root.isAttachedToWindow &&
                binding.root.visibility == View.VISIBLE
        }
        if (!needsSignal) {
            signalObserver?.stop()
            signalObserver = null
            return
        }
        if (signalObserver == null) {
            val context = bindings.values.firstOrNull { it.root.isAttachedToWindow }?.root?.context ?: return
            signalObserver = AodSignalObserver(context) {
                bindings.values.toList().forEach { binding ->
                    if (binding.root.isAttachedToWindow) refresh(binding)
                }
            }
        }
        signalObserver?.start()
    }

    private fun batteryFrom(root: View, status: Any?): DuoBattery? {
        val level = invokeInt(status, "getLevel")
            ?: (read(root, "mLevel") as? Int)
            ?: return null
        val charging = invokeBoolean(status, "isCharging") ?: false
        val powerSave = runCatching {
            root.context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }.getOrDefault(false)
        return DuoBattery(level.coerceIn(0, 100), charging, powerSave)
    }

    private fun currentBattery(root: View): DuoBattery? {
        val status = read(root, "mBatteryController")?.let { read(it, "mBatteryStatus") }
        if (status != null) batteryFrom(root, status)?.let { return it }
        val sticky = runCatching {
            root.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return batteryFrom(root, null)
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return batteryFrom(root, null)
        val statusCode = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 &&
            (statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusCode == BatteryManager.BATTERY_STATUS_FULL)
        val powerSave = runCatching {
            root.context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }.getOrDefault(false)
        return DuoBattery((level * 100 / scale).coerceIn(0, 100), charging, powerSave)
    }

    private fun applyContainer(container: View) {
        val keep = AodIconSettings.current().showRow
        invokeMethod(container, "updateBatteryWidget")
        val batteryId = resourceId(container.context, BATTERY_CONTAINER_ID)
        var widget = (read(container, "mBatteryWidget") as? View)
            ?: batteryId.takeIf { it != 0 }?.let(container::findViewById)
        // Linkage clocks already include their own battery_container. Inflating the separate
        // battery-step stub first creates a competing native row and can select the wrong one.
        if (widget == null && keep) {
            read(container, "mStyleInfo")?.let { style ->
                invokeMethod(container, "inflateBatteryAndStepContainerIfNeeded", style)
                invokeMethod(container, "updateBatteryWidget")
                widget = (read(container, "mBatteryWidget") as? View)
                    ?: batteryId.takeIf { it != 0 }?.let(container::findViewById)
            }
        }
        if (widget != null) {
            val currentWidget = widget
            write(container, "mBatteryWidget", currentWidget)
            val standardMeter = findViews(currentWidget, BATTERY_METER).isNotEmpty() ||
                findViewByName(currentWidget, BATTERY_METER_ID) != null
            if (!keep) {
                currentWidget.visibility = View.GONE
                disableMamlBattery(container)
            } else if (standardMeter) {
                currentWidget.visibility = View.VISIBLE
                disableMamlBattery(container)
                findViews(currentWidget, BATTERY_METER).forEach(::bind)
            } else {
                DebugLog.w(TAG, "AOD battery widget has no standard meter: ${currentWidget.javaClass.name}")
            }
        } else if (!keep) {
            disableMamlBattery(container)
        }
    }

    private fun disableMamlBattery(container: View) {
        val id = resourceId(container.context, MAML_CONTAINER_ID)
        if (id == 0) return
        val content = findNamedView(container, "content") as? ViewGroup ?: container as? ViewGroup ?: return
        val maml = content.findViewById<View>(id) ?: return
        runCatching {
            val method = findMethod(maml.javaClass, "setBatteryEnable", Boolean::class.javaPrimitiveType!!)
                ?: return
            method.invoke(maml, false)
        }.onFailure { DebugLog.w(TAG, "could not suppress duplicate MAML battery", it) }
    }

    private fun findViews(root: View, className: String): List<View> = buildList {
        if (root.javaClass.name == className) add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(findViews(root.getChildAt(index), className))
    }

    private fun findViewByName(root: View, name: String): View? {
        val id = resourceId(root.context, name)
        return if (id == 0) null else root.findViewById(id)
    }

    private fun findNamedView(root: View, name: String): View? = findViewByName(root, name)

    private fun resourceId(context: Context, name: String): Int = runCatching {
        context.resources.getIdentifier(name, "id", context.packageName)
    }.getOrDefault(0)

    private fun loadClass(name: String): Class<*>? = runCatching { classLoader.loadClass(name) }.getOrNull()

    private fun field(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun read(target: Any, name: String): Any? = runCatching {
        field(target.javaClass, name)?.get(target)
    }.getOrNull()

    private fun write(target: Any, name: String, value: Any) {
        runCatching { field(target.javaClass, name)?.set(target, value) }
    }

    private fun findMethod(type: Class<*>, name: String, vararg params: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredMethod(name, *params) }.getOrNull()?.let {
                return it.apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeMethod(target: Any, name: String, argument: Any? = null) {
        val method = if (argument == null) findMethod(target.javaClass, name) else {
            findCompatibleMethod(target.javaClass, name, argument)
        }
        runCatching {
            if (method != null) method.invoke(target, *if (argument == null) emptyArray() else arrayOf(argument))
        }.onFailure { DebugLog.w(TAG, "AOD method $name failed", it) }
    }

    private fun findCompatibleMethod(type: Class<*>, name: String, argument: Any): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { candidate ->
                candidate.name == name && candidate.parameterCount == 1 &&
                    candidate.parameterTypes[0].isAssignableFrom(argument.javaClass)
            }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun invokeInt(target: Any?, methodName: String): Int? = runCatching {
        target?.let { findMethod(it.javaClass, methodName)?.invoke(it) as? Int }
    }.getOrNull()

    private fun invokeBoolean(target: Any?, methodName: String): Boolean? = runCatching {
        target?.let { findMethod(it.javaClass, methodName)?.invoke(it) as? Boolean }
    }.getOrNull()

    private fun copyLayoutParams(params: ViewGroup.LayoutParams): ViewGroup.LayoutParams = when (params) {
        is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params)
        is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params)
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
        else -> ViewGroup.LayoutParams(params)
    }

    private fun guarded(block: () -> Unit) {
        runCatching(block).onFailure { DebugLog.w(TAG, "AOD callback failed", it) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) guarded(block)
        else main.post { guarded(block) }
    }
}
