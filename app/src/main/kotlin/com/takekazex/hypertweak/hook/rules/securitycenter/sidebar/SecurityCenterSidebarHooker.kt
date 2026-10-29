package com.takekazex.hypertweak.hook.rules.securitycenter.sidebar

import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap

private object SidebarFeature : DexFeature {
    override val id = "securitycenter.sidebar_handle"
    override val schema = 1
}

/** No drawable, layout, window flags or sampler state is overwritten. */
object SecurityCenterSidebarHooker : DynamicHooker() {
    private const val TAG = "SecurityCenterSidebar"
    private val mode by hookSetting(0) {
        Preferences.getInt(PlatformSettingsKeys.KEY_SECURITY_CENTER_SIDEBAR_MODE, 0).takeIf { it in 0..2 } ?: 0
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var retired = true
    private var viewType: Class<*>? by generationState { null }
    private var insets: InsetsContract? by generationState { null }
    private val hosts = WeakHashMap<View, Registration>()
    private data class Registration(val attach: View.OnAttachStateChangeListener, var observer: ViewTreeObserver? = null, var proxy: Any? = null)

    private class InsetsContract {
        val listener = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        private val info = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
        val add = HostAccess.requirePublicMethod(ViewTreeObserver::class.java, "addOnComputeInternalInsetsListener", listener, accessible = true)
        val remove = HostAccess.requirePublicMethod(ViewTreeObserver::class.java, "removeOnComputeInternalInsetsListener", listener, accessible = true)
        private val setMode = HostAccess.requirePublicMethod(info, "setTouchableInsets", Int::class.javaPrimitiveType, accessible = true)
        private val region = HostAccess.requirePublicField(info, "touchableRegion", accessible = true)
        fun empty(value: Any) {
            val touchable = region.get(value) as? Region ?: return
            setMode.invoke(value, 3) // TOUCHABLE_INSETS_REGION, local to this traversal.
            touchable.setEmpty()
        }
    }

    override fun onHook() {
        if (hookParam.processName != SecurityCenterDockHost.PROCESS) return
        retired = false
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = SidebarFeature.id, resolve = {
            DexRuntime.resolve(feature = SidebarFeature, apkPath = apk, cacheDir = dexCacheDirectory,
                compute = { SecurityCenterSidebarResolver(DexRuntime.operands(apk)).resolve() },
                decode = { values, _ ->
                    Triple(DexOperandIndex.type(values.getValue("view"), classLoader),
                        requireNotNull(MethodDescriptorCodec.method(values.getValue("touch"), classLoader)),
                        requireNotNull(MethodDescriptorCodec.method(values.getValue("draw"), classLoader)))
                })
        }, install = { (type, touch, draw) ->
            // Validate the platform contract before installing either behavior.
            insets = InsetsContract()
            viewType = type
            deoptimize(touch)
            deoptimize(draw)
            draw.hook { before { param -> if (!retired && mode != 0) param.result = null } }
            touch.hook { before { param -> if (!retired && mode == 1) param.result = false } }
            type.hookAllConstructors { after { param ->
                val view = param.thisObject as? View
                if (view != null && !retired) main.post {
                    if (!retired) runCatching { track(view) }.onFailure { DebugLog.w(TAG, "handle capture failed", it) }
                }
            } }
            refreshSettings()
            DebugLog.hookRegistered(TAG, "three-state sidebar handle")
        })
    }

    private fun track(view: View) {
        if (viewType?.isInstance(view) != true || hosts.containsKey(view)) return
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!retired) runCatching { bind(v) }.onFailure { DebugLog.w(TAG, "input region attach failed", it) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching { unbind(v) }.onFailure { DebugLog.w(TAG, "input region detach failed", it) }
            }
        }
        hosts[view] = Registration(attach)
        view.addOnAttachStateChangeListener(attach)
        if (view.isAttachedToWindow) bind(view)
    }

    private fun bind(view: View) {
        val registration = hosts[view] ?: return
        unbind(view)
        if (mode != 1) return
        val root = view.rootView as? ViewGroup ?: return
        // The verified host puts the handle and panel directly in one FrameLayout.
        if (view.parent !== root) return
        val contract = insets ?: return
        val weak = WeakReference(view)
        val proxy = Proxy.newProxyInstance(contract.listener.classLoader, arrayOf(contract.listener)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> TAG
                "onComputeInternalInsets" -> {
                    if (!retired && mode == 1) runCatching {
                        val handle = weak.get()
                        val container = handle?.rootView as? ViewGroup
                        if (handle != null && container != null && handle.parent === container) {
                            // An open panel/shortcut menu keeps the host's complete input region.
                            val panelVisible = (0 until container.childCount).any {
                                val child = container.getChildAt(it)
                                child !== handle && child.visibility == View.VISIBLE && child.alpha > 0f && child.width > 0 && child.height > 0
                            }
                            if (!panelVisible) args?.firstOrNull()?.let(contract::empty)
                        }
                    }.onFailure { DebugLog.w(TAG, "input region update failed", it) }
                    null
                }
                else -> null
            }
        }
        val observer = root.viewTreeObserver
        contract.add.invoke(observer, proxy)
        registration.observer = observer
        registration.proxy = proxy
        root.requestLayout()
        view.invalidate()
    }

    private fun unbind(view: View) {
        val registration = hosts[view] ?: return
        val observer = registration.observer
        val proxy = registration.proxy
        if (observer?.isAlive == true && proxy != null) insets?.remove?.invoke(observer, proxy)
        registration.observer = null
        registration.proxy = null
        view.rootView.requestLayout()
        view.invalidate()
    }

    fun refreshSettings(): Boolean {
        Preferences.invalidateRuntimeReadCache()
        if (retired) return true
        if (viewType == null) return mode == 0
        return runCatching {
            HostAccess.onMain {
                if (retired) return@onMain
                // Recover live handles even when upgrading from an APK without this feature.
                val global = Class.forName("android.view.WindowManagerGlobal")
                val instance = HostAccess.requireDeclaredMethod(global, "getInstance", accessible = true).invoke(null)
                val roots = HostAccess.requireDeclaredField(global, "mViews", accessible = true).get(instance) as? List<*>
                fun visit(view: View) {
                    if (viewType?.isInstance(view) == true) track(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                roots?.toList()?.filterIsInstance<View>()?.forEach(::visit)
                hosts.keys.toList().forEach { view -> if (view.isAttachedToWindow) bind(view) }
            }
            true
        }.onFailure { DebugLog.w(TAG, "existing handle recovery failed", it) }.getOrDefault(false)
    }

    override fun recoverHotReloadHosts() { refreshSettings() }
    override fun onPrepareHotReload() {
        retired = true
        main.removeCallbacksAndMessages(null)
        HostAccess.onMain {
            hosts.keys.toList().forEach { view ->
                runCatching { unbind(view) }.onFailure { DebugLog.w(TAG, "input region cleanup failed", it) }
                hosts[view]?.let { view.removeOnAttachStateChangeListener(it.attach) }
            }
            hosts.clear()
        }
    }
}
