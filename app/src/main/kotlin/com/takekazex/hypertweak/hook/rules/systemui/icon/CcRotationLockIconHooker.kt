package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy.SLOT_ROTATION_LOCK
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Publishes a native status icon from the same lock state as the SystemUI tile. */
internal object CcRotationLockIconHooker : StaticHooker() {
    private const val TAG = "CcRotationLockIcon"
    private const val INITIALIZER = "com.android.systemui.SystemUIInitializer"
    private const val ROTATION_CONTROLLER = "com.android.systemui.statusbar.policy.RotationLockControllerImpl"
    private const val CALLBACK = "com.android.systemui.statusbar.policy.RotationLockController\$RotationLockControllerCallback"

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, false)
    }
    private val main = Handler(Looper.getMainLooper())
    private var active by generationState { false }
    private var binding: Binding? by generationState { null }

    private class Binding(
        val source: Any,
        val callback: Any,
        val removeCallback: Method,
        val bridge: HostIconBridge,
        val description: String
    )

    init {
        hostRecovery(HostRecoveryPhase.SOURCES, icons = true) { scope ->
            if (active) guarded { bind(scope.component, scope.controller) }
        }
    }

    override fun onHook() {
        if (!isMainProcess || !enabled) return
        if (!IconManagerHooker.rotationSlotRoutingReady) {
            DebugLog.hookSkipped(TAG, "control-center rotation lock icon", "icon group routing unavailable")
            return
        }
        guarded {
            val initializer = requireNotNull(INITIALIZER.toClassOrNull())
            val init = HostAccess.requireDeclaredMethod(initializer, "init", Boolean::class.javaPrimitiveType, accessible = false)
            val component = requireNotNull(HostAccess.publicMethod(initializer, "getSysUIComponent", accessible = true))
            init.hook {
                after { param ->
                    if (param.throwable != null) return@after
                    val host = param.thisObject
                    // Let SystemUI finish initializing before resolving singleton providers.
                    main.post {
                        if (active) guarded {
                            val graph = component.invoke(host)
                            bind(graph, provider(graph, "statusBarIconControllerImplProvider"))
                        }
                    }
                }
            }
            active = true
            DebugLog.hookRegistered(TAG, "SystemUIInitializer#init(boolean)")
        }
    }

    private fun bind(component: Any?, controller: Any?) {
        if (binding != null || controller == null || !IconManagerHooker.rotationSlotRoutingReady) return
        val source = provider(component, "rotationLockControllerImplProvider") ?: return
        if (source.javaClass.name != ROTATION_CONTROLLER) return
        val callbackType = requireNotNull(CALLBACK.toClassOrNull())
        HostAccess.requireDeclaredMethod(callbackType, "onRotationLockStateChanged", Boolean::class.javaPrimitiveType, accessible = false)
        val add = HostAccess.requireDeclaredMethod(source.javaClass, "addCallback", Any::class.java, accessible = true)
        val remove = HostAccess.requireDeclaredMethod(source.javaClass, "removeCallback", Any::class.java, accessible = true)
        val context = HostAccess.read(controller, "mContext") as? Context ?: return
        val module = context.createPackageContext(HostIconBridge.MODULE_PACKAGE, 0)
        val artwork = Icon.createWithResource(module, R.drawable.ic_stat_sys_rotation_lock_ios)
        val callback = Proxy.newProxyInstance(classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> TAG
                "onRotationLockStateChanged" -> {
                    val locked = args?.firstOrNull() as? Boolean
                    if (locked != null) main.post {
                        val current = binding
                        if (current?.callback === proxy) guarded {
                            if (!locked) current.bridge.removeOwned(SLOT_ROTATION_LOCK)
                            else if (!current.bridge.owns(SLOT_ROTATION_LOCK)) {
                                if (current.bridge.publish(SLOT_ROTATION_LOCK, artwork, current.description)) {
                                    DebugLog.d(TAG, "rotation lock icon published")
                                }
                            }
                        }
                    }
                    null
                }
                else -> null
            }
        }
        binding = Binding(
            source, callback, remove, HostIconBridge(controller, classLoader),
            module.getString(R.string.icon_cc_rotation_lock_description)
        )
        try {
            add.invoke(source, callback)
        } catch (failure: Throwable) {
            retire()
            throw failure
        }
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            active = false
            main.removeCallbacksAndMessages(null)
            retire()
        }
    }

    private fun retire() {
        val current = binding ?: return
        binding = null
        guarded { current.removeCallback.invoke(current.source, current.callback) }
        guarded { current.bridge.removeOwned(SLOT_ROTATION_LOCK) }
    }

    private fun provider(component: Any?, name: String): Any? {
        val provider = component?.let { HostAccess.read(it, name) } ?: return null
        return HostAccess.publicMethod(provider.javaClass, "get", accessible = true)?.invoke(provider)
    }

    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { DebugLog.w(TAG, "rotation icon update failed", it) }
    }
}
