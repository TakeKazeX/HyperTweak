@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostFlowCollector
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconPositionHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalState
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * OS4 battery-layout adapter. Original battery objects remain attached and receive host callbacks.
 * Only the parent's View-typed layout reference is borrowed; the icon container never receives a
 * module View (its children must implement the host StatusIconDisplayable interface).
 */
object DuoSignalHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED
    private const val TAG = "DuoSignal"
    private const val AIRPLANE_SLOT = "airplane"
    private const val BATTERY = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val CONTAINER = "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer"
    private const val WIFI_VM = "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"
    private val main = Handler(Looper.getMainLooper())
    private val bindings = IdentityHashMap<View, Binding>()
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val wifiHandles = ArrayList<HostFlowCollector.Handle>()
    @Volatile private var enabled = false
    @Volatile private var epoch = 0
    private var expandedStyle = DuoExpandedStyle.RESTORE_NATIVE
    private var mobile = MobileSignalState()
    private var network = DuoNetwork()
    private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var batteryLayoutField: Field? = null
    private var statusIconsField: Field? = null
    private var visibilityMethod: Method? = null
    private var tintMethod: Method? = null
    private var darkMethod: Method? = null
    private var wifiScope: Any? = null
    private var wifiInteractor: Any? = null
    // Only applicationContext is retained, for restoring the host flow after a reload.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var wifiContext: Context? = null

    private class Binding(val battery: View, val parent: ViewGroup, val icons: View, val surface: DuoSurface) {
        val view = DuoView(battery.context)
        var active = false
        var failed = false
        var reconciling = false
        // Preserve whether the host/user already ignored the native airplane slot before Duo.
        // Duo may add that slot while active, but must never unhide something the host chose to hide.
        var hostIgnoredAirplane: Boolean? = null
    }

    private class DuoView(context: Context) : View(context) {
        val icon = DuoDrawable().also { it.callback = this }
        var drawFailed: (() -> Unit)? = null
        override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean = who === icon || super.verifyDrawable(who)
        /** The carrier takes the retained battery's box in full; the drawable fits its glyph. */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desired = (26f * resources.displayMetrics.density).roundToInt()
            // WRAP_CONTENT must report an intrinsic desired size. Feeding MeasureSpec.getSize()
            // back into resolveSize() makes an AT_MOST spec consume the parent's whole allowance.
            // position() still exact-measures this view to the native battery slot once active.
            setMeasuredDimension(
                resolveSize(maxOf(desired, suggestedMinimumWidth), widthMeasureSpec),
                resolveSize(maxOf(desired, suggestedMinimumHeight), heightMeasureSpec)
            )
        }
        override fun onDraw(canvas: Canvas) {
            icon.setBounds(0, 0, width, height)
            runCatching { icon.draw(canvas) }.onFailure { drawFailed?.invoke() }
        }
    }

    override fun onHook() {
        enabled = PlatformLevel.isOs4 && isMainProcess &&
            Preferences.getBoolean(Preferences.KEY_ICON_DUO_ENABLED, false)
        if (!enabled) return
        epoch++
        expandedStyle = if (Preferences.getInt(Preferences.KEY_ICON_DUO_EXPANDED, 1) == 0)
            DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
        IconTunerFlows.init(classLoader)
        val batteryClass = BATTERY.toClassOrNull() ?: run { enabled = false; return }
        val parentClass = CONTAINER.toClassOrNull() ?: run { enabled = false; return }
        batteryLayoutField = field(parentClass, "mBattery")?.takeIf { it.type == View::class.java }
        statusIconsField = field(parentClass, "mStatusIcon")
        visibilityMethod = batteryClass.declaredMethods.singleOrNull {
            it.name == "updateVisibility\$6" && it.parameterCount == 0
        }?.apply { isAccessible = true }
        if (batteryLayoutField == null || statusIconsField == null || visibilityMethod == null) {
            enabled = false
            DebugLog.hookSkipped(TAG, "battery layout", "unsupported signature")
            return
        }
        tintMethod = "com.android.systemui.plugins.DarkIconDispatcher".toClassOrNull()?.methods?.singleOrNull {
            it.name == "getTint" && it.parameterCount == 3
        }
        darkMethod = "com.android.systemui.statusbar.DarkIconDispatcherExt".toClassOrNull()?.methods?.singleOrNull {
            it.name == "getDarkIntensity" && it.parameterCount == 3
        }
        // No hooks on global View/ImageView draw, visibility or tint methods.
        batteryClass.findMethodOrNull { name("onAttachedToWindow"); paramCount(0) }?.hook {
            after { param -> (param.thisObject as? View)?.let { battery ->
                val token = epoch
                main.post { if (enabled && epoch == token && battery.isAttachedToWindow) guarded { attach(battery) } }
            } }
        }
        batteryClass.findMethodOrNull { name("onDetachedFromWindow"); paramCount(0) }?.hook {
            before { param -> (param.thisObject as? View)?.let { battery -> guarded { detach(battery) } } }
        }
        visibilityMethod?.hook {
            after { param -> (param.thisObject as? View)?.let { battery -> guarded {
                bindings[battery]?.let { reconcile(it) }
            } } }
        }
        for (name in listOf("onBatteryLevelChanged", "onPowerSaveChanged", "onChargeStateChanged", "updateLightDarkTint", "updateAll")) {
            batteryClass.declaredMethods.filter { it.name == name }.forEach { method ->
                method.hook { after { param -> (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let { reconcile(it) }
                } } } }
            }
        }
        hookWifi()
        DebugLog.hookRegistered(TAG, "OS4 battery container, expanded=$expandedStyle")
    }

    val requiresMobileState: Boolean get() = enabled

    /** Reuses the existing mobile reducer; collector-only mode never masks native mobile flows. */
    fun onMobileState(state: MobileSignalState, ready: Boolean) {
        if (!enabled) return
        val next = if (ready) state else MobileSignalState()
        if (mobile == next) return
        mobile = next
        refresh()
    }

    private fun attach(battery: View) {
        if (bindings.containsKey(battery)) return
        val parent = battery.parent as? ViewGroup ?: return
        if (parent.javaClass.name != CONTAINER || batteryLayoutField?.get(parent) !== battery) return
        val surface = surface(battery)
        if (!DuoPolicy.replaces(surface, expandedStyle)) return
        val icons = statusIconsField?.get(parent) as? View ?: return
        ensureConnectivity(battery.context)
        val binding = Binding(battery, parent, icons, surface)
        captureAirplaneMaskBaseline(binding)
        bindings[battery] = binding
        binding.view.drawFailed = {
            binding.failed = true
            main.post { guarded { reconcile(binding) } }
        }
        // Use the parent's own LayoutParams class, required by measureChildWithMargins.
        val original = battery.layoutParams
        // OS4 LayoutParams has no declared constructor (DEX constructor elision). The parent
        // only casts to MarginLayoutParams, so use the platform copy constructor.
        binding.view.layoutParams = (if (original is ViewGroup.MarginLayoutParams)
            ViewGroup.MarginLayoutParams(original) else ViewGroup.MarginLayoutParams(original)).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.view.visibility = View.GONE
        binding.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        try { parent.addView(binding.view, parent.indexOfChild(battery) + 1) } catch (error: Throwable) {
            bindings.remove(battery)
            throw error
        }
        binding.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> guarded { reconcile(binding) } }
        reconcile(binding)
    }

    private fun surface(view: View): DuoSurface {
        var node = view.parent
        repeat(20) {
            val current = node as? View ?: return DuoSurface.UNSUPPORTED
            when (current.javaClass.name) {
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView" -> return DuoSurface.HOME
                "com.android.systemui.qs.MiuiQSHeaderView",
                "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon",
                "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons" -> return DuoSurface.EXPANDED
            }
            node = current.parent
        }
        return DuoSurface.UNSUPPORTED
    }

    /**
     * Gives the carrier the retained battery's measured size and slot. The host container keeps
     * `mBattery` pointing at the carrier, but never lays it out, so without this the carrier stays
     * at zero bounds: invisible, never redrawn, and never able to claim the signal mask.
     */
    private fun position(binding: Binding) {
        val battery = binding.battery
        val view = binding.view
        if (battery.width <= 0 || battery.height <= 0) return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(battery.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(battery.height, View.MeasureSpec.EXACTLY)
        )
        if (view.left == battery.left && view.top == battery.top &&
            view.right == battery.right && view.bottom == battery.bottom) return
        view.layout(battery.left, battery.top, battery.right, battery.bottom)
    }

    private fun reconcile(binding: Binding) {
        if (binding.reconciling) return
        binding.reconciling = true
        try {
            val battery = binding.battery
            if (binding.active && batteryLayoutField?.get(binding.parent) !== binding.view) binding.failed = true
            val percent = (read(battery, "mLevel") as? Int)?.takeIf { read(battery, "mFirstLevel") == false }
            val charging = read(battery, "mCharging") as? Boolean
            val powerSave = read(battery, "mPowerSave") as? Boolean
            val content = if (percent != null && charging != null && powerSave != null)
                DuoPolicy.content(DuoBattery(percent, charging, powerSave), mobile, network) else null
            val visible = read(battery, "mHomeBlock") == false && read(battery, "mMinimalism") == false &&
                read(battery, "mIsAddBatteryIsland") == false && read(battery, "mIsAodAnimate") != true
            if (!enabled || binding.failed || content == null || !visible || battery.parent !== binding.parent ||
                !DuoPolicy.replaces(surface(battery), expandedStyle)) {
                restore(binding)
                return
            }
            binding.view.icon.content = content
            binding.view.icon.foreground = foreground(binding)
            binding.view.contentDescription = buildString {
                append(battery.contentDescription?.toString().orEmpty())
                if (content.airplaneMode) {
                    append(", Airplane mode")
                } else {
                    append(", ")
                    append(content.networkLabel ?: "Wi-Fi ${content.wifiLevel}/4")
                    append(", SIM ${if (content.noService) "0" else content.mobileLevel}/4")
                    if (content.noInternet) append(", !")
                }
            }
            binding.view.alpha = battery.alpha
            binding.view.translationX = battery.translationX
            binding.view.translationY = battery.translationY
            binding.view.scaleX = battery.scaleX
            binding.view.scaleY = battery.scaleY
            if (!binding.active) {
                if (batteryLayoutField?.get(binding.parent) !== battery) return
                // Establish a measured, visible replacement before claiming the signal mask.
                batteryLayoutField?.set(binding.parent, binding.view)
                binding.active = true
                binding.view.visibility = View.VISIBLE
                battery.visibility = View.GONE
                binding.parent.requestLayout()
            }
            // MiuiStatusBatteryContainer only lays out its own known children, so this carrier never
            // receives bounds from the host and must take the retained native battery's slot itself.
            position(binding)
            if (battery.width > 0 && battery.height > 0 && binding.view.isAttachedToWindow) {
                if (!IconPositionHooker.setDuoMask(binding.icons, true)) {
                    binding.failed = true
                    restore(binding)
                } else {
                    // Native SystemUI publishes its own `airplane` status-bar slot.  Duo renders
                    // that state inside the battery ring, so keep the native slot ignored while
                    // the replacement owns this cluster; otherwise two planes appear side by side.
                    setNativeAirplaneMasked(binding, true)
                }
            }
        } catch (error: Throwable) {
            binding.failed = true
            restore(binding)
            DebugLog.w(TAG, "container failed; restored native", error)
        } finally { binding.reconciling = false }
    }

    /** Snapshot the host's own airplane-slot policy before Duo changes anything. */
    private fun captureAirplaneMaskBaseline(binding: Binding) {
        if (binding.hostIgnoredAirplane != null) return
        val ignored = ignoredSlots(binding.icons) ?: return
        binding.hostIgnoredAirplane = ignored.any { it == AIRPLANE_SLOT }
    }

    /**
     * Adds/removes only Duo's native-airplane suppression.  The baseline bit prevents restore from
     * exposing an airplane slot that was already hidden by Icon Tuner or the host configuration.
     */
    private fun setNativeAirplaneMasked(binding: Binding, masked: Boolean) {
        captureAirplaneMaskBaseline(binding)
        val ignored = ignoredSlots(binding.icons) ?: return
        val hostAlreadyIgnored = binding.hostIgnoredAirplane == true
        var changed = false
        if (masked) {
            if (ignored.none { it == AIRPLANE_SLOT }) {
                ignored.add(AIRPLANE_SLOT)
                changed = true
            }
        } else if (!hostAlreadyIgnored) {
            var index = ignored.size - 1
            while (index >= 0) {
                if (ignored[index] == AIRPLANE_SLOT) {
                    ignored.removeAt(index)
                    changed = true
                }
                index--
            }
        }
        if (changed) {
            binding.icons.requestLayout()
            binding.icons.invalidate()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ignoredSlots(view: View): MutableList<Any?>? =
        runCatching { field(view.javaClass, "ignoredSlots")?.get(view) as? MutableList<Any?> }.getOrNull()

    private fun foreground(binding: Binding): Int {
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

    private fun restore(binding: Binding) {
        IconPositionHooker.setDuoMask(binding.icons, false)
        setNativeAirplaneMasked(binding, false)
        if (!binding.active) return
        binding.active = false
        runCatching {
            if (batteryLayoutField?.get(binding.parent) === binding.view) batteryLayoutField?.set(binding.parent, binding.battery)
            binding.view.visibility = View.GONE
            visibilityMethod?.invoke(binding.battery)
            binding.parent.requestLayout()
        }.onFailure { DebugLog.w(TAG, "native battery restore failed", it) }
    }

    private fun detach(battery: View) {
        val binding = bindings.remove(battery) ?: return
        binding.reconciling = true
        restore(binding)
        // Removal during the parent's detach traversal can skip sibling detach callbacks.
        main.post { if (binding.view.parent === binding.parent) binding.parent.removeView(binding.view) }
    }

    private fun hookWifi() {
        val vm = WIFI_VM.toClassOrNull() ?: return
        vm.declaredConstructors.filter { ctor ->
            ctor.parameterCount == 6 && ctor.parameterTypes[3].name.endsWith(".WifiInteractorImpl") &&
                ctor.parameterTypes[4].name == IconTunerFlows.hostClassName("kotlinx.coroutines", "CoroutineScope")
        }.forEach { ctor -> ctor.hook { after { param ->
            val interactor = param.args.getOrNull(3) ?: return@after
            val scope = param.args.getOrNull(4) ?: return@after
            val context = param.args.getOrNull(1) as? Context ?: return@after
            val token = epoch
            main.post { if (enabled && token == epoch) guarded { bindWifi(scope, interactor, context) } }
        } } }
    }

    private fun bindWifi(scope: Any, interactor: Any, context: Context) {
        if (wifiInteractor === interactor && wifiHandles.isNotEmpty()) return
        wifiHandles.forEach { it.cancel() }; wifiHandles.clear()
        wifiScope = scope; wifiInteractor = interactor; wifiContext = context.applicationContext
        network = network.copy(wifiLevel = null)
        ensureConnectivity(context)
        val token = epoch
        val wifiMax = (context.getSystemService(WifiManager::class.java)?.maxSignalLevel ?: 4).coerceAtLeast(1)
        HostFlowCollector.collect(scope, read(interactor, "wifiNetwork"), { value ->
            val level = if (value?.javaClass?.name?.endsWith("WifiNetworkModel\$Active") == true)
                (read(value, "level") as? Int)?.takeIf { it in 0..wifiMax }?.let {
                    (it * 4f / wifiMax).roundToInt().coerceIn(0, 4)
                } else null
            network = network.copy(wifiLevel = level)
            refresh()
        }, { enabled && token == epoch })?.let(wifiHandles::add)
    }

    private fun ensureConnectivity(context: Context) {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val token = epoch
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                if (!enabled || token != epoch) return
                currentNetwork = value
                network = network.copy(transport = DuoTransport.UNKNOWN, validated = false)
                refresh()
            }
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) {
                if (!enabled || token != epoch || currentNetwork != value) return
                network = network.copy(transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DuoTransport.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DuoTransport.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> DuoTransport.VPN
                    else -> DuoTransport.OTHER
                }, validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                refresh()
            }
            override fun onLost(value: Network) {
                if (!enabled || token != epoch || currentNetwork != value) return
                currentNetwork = null
                network = network.copy(transport = DuoTransport.NONE, validated = false)
                refresh()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(listener, main) }.onSuccess {
            connectivity = manager; callback = listener
        }.onFailure { DebugLog.w(TAG, "default network callback unavailable", it) }
    }

    private fun refresh() { bindings.values.toList().forEach { guarded { reconcile(it) } } }
    private fun field(type: Class<*>, name: String): Field? {
        val key = type to name
        if (fields.containsKey(key)) return fields[key]
        return generateSequence(type) { it.superclass }.mapNotNull { clazz ->
            runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull().also { fields[key] = it }
    }
    private fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)
    private inline fun guarded(action: () -> Unit) { runCatching(action).onFailure { DebugLog.w(TAG, "host callback failed", it) } }

    private fun <T> onMainBlocking(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Result<T>? = null
        main.post { try { result = runCatching(action) } finally { latch.countDown() } }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Duo main-thread cleanup timed out" }
        return checkNotNull(result).getOrThrow()
    }
    override fun saveHotReloadState(): Any? = onMainBlocking {
        listOf(wifiScope, wifiInteractor, wifiContext, bindings.keys.toList())
    }
    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        val token = epoch
        main.post { if (enabled && token == epoch) guarded {
            val scope = saved.getOrNull(0); val interactor = saved.getOrNull(1); val context = saved.getOrNull(2) as? Context
            if (scope != null && interactor != null && context != null) bindWifi(scope, interactor, context)
            (saved.getOrNull(3) as? List<*>)?.filterIsInstance<View>()?.filter { it.isAttachedToWindow }?.forEach(::attach)
        } }
    }
    override fun onPrepareHotReload() {
        enabled = false
        epoch++
        // Finish native restoration before the lifecycle removes this generation's hooks.
        onMainBlocking {
            bindings.keys.toList().forEach(::detach)
            callback?.let { listener -> runCatching { connectivity?.unregisterNetworkCallback(listener) } }
            callback = null; connectivity = null; currentNetwork = null
            wifiHandles.forEach { it.cancel() }; wifiHandles.clear()
            wifiScope = null; wifiInteractor = null; wifiContext = null
            mobile = MobileSignalState(); network = DuoNetwork()
        }
    }
}
