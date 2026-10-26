package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.ReadyIslandController
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the plugin's temporary-hide event and per-content gate; never deletes island data. */
class ControlCenterSuperIslandHooker : DynamicHooker() {
    private val main = Handler(Looper.getMainLooper())
    private val forcedControllers = WeakHashMap<Any, Boolean>()
    private val knownControllers = WeakHashMap<Any, Boolean>()
    @Volatile private var retiring = false
    private var restore: ((Any) -> Unit)? by generationState { null }

    fun snapshotControllers(): List<Any> = synchronized(knownControllers) { knownControllers.keys.toList() }

    fun restoreControllers(controllers: List<Any>) {
        synchronized(knownControllers) { controllers.forEach { knownControllers[it] = true } }
        refreshSettings()
    }

    private val refreshSettingsAction = Runnable {
        if (!retiring) snapshotControllers().forEach { owner ->
            HookFailurePolicy.open(TAG, "live temporary hide", Unit) { restore?.invoke(owner) }
        }
    }

    fun refreshSettings() {
        main.removeCallbacks(refreshSettingsAction)
        if (!retiring) main.post(refreshSettingsAction)
    }

    override fun onHook() {
        val state = "miui.systemui.dynamicisland.window.DynamicIslandWindowState".toClassOrNull()
            ?: return
        val controller = "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController".toClassOrNull()
            ?: return
        val view = "miui.systemui.dynamicisland.window.DynamicIslandWindowView".toClassOrNull()
            ?: return
        val ext = "miui.systemui.dynamicisland.window.ext.DynamicIslandWindowStateExtKt".toClassOrNull()
            ?: return
        // Resolve every required contract before installing any hooks. The flow implementation
        // is obfuscated, so resolve getValue against each getter's declared return interface.
        fun getter(owner: Class<*>, name: String) = HostAccess.requireDeclaredMethod(owner, name, accessible = true)
        val expanded = getter(state, "getControlCenterExpanded")
        val swiping = getter(state, "getControlCenterPanelSwipeToAppearance")
        val tempHidden = getter(state, "getTempHidden")
        val type = getter(state, "getTempHiddenType")
        val getState = getter(controller, "getWindowState")
        val getController = getter(view, "getWindowViewController")
        val coordinator = "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator".toClassOrNull()
            ?: return
        val readyController = ReadyIslandController.resolve(view, getController,
            getter(view, "getViewComponent").returnType, coordinator) ?: return
        val getView = HostAccess.requireDeclaredMethod(controller, "access\$getView", controller, accessible = true)
        val hiddenGate = HostAccess.requireDeclaredMethod(ext, "isTempHiddenExt", state, Int::class.javaObjectType, accessible = false)
        val hideEvent = HostAccess.requireDeclaredMethod(view, "onIslandTempHide", Boolean::class.javaPrimitiveType!!, type.returnType, accessible = false)
        val expandedEvent = HostAccess.requireDeclaredMethod(controller, "controlCenterExpanded", Boolean::class.javaPrimitiveType!!, accessible = false)
        val swipeEvent = controller.declaredMethods.single {
            it.name == "controlCenterPanelExpandHeightChanged" && it.parameterCount == 1 &&
                it.returnType == Void.TYPE
        }
        val expandedValue = HostAccess.requirePublicMethod(expanded.returnType, "getValue", accessible = false)
        val swipeValue = HostAccess.requirePublicMethod(swiping.returnType, "getValue", accessible = false)
        val hiddenValue = HostAccess.requirePublicMethod(tempHidden.returnType, "getValue", accessible = false)
        fun value(owner: Any, getter: Method, reader: Method): Boolean =
            reader.invoke(getter.invoke(owner)) as? Boolean ?: false
        fun shouldHide(owner: Any): Boolean = !retiring &&
            Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, false) &&
            (value(owner, expanded, expandedValue) || value(owner, swiping, swipeValue))
        fun refresh(owner: Any) {
            synchronized(knownControllers) { knownControllers[owner] = true }
            val windowState = getState.invoke(owner) ?: return
            val forced = shouldHide(windowState)
            val previous = synchronized(forcedControllers) { forcedControllers[owner] == true }
            if (forced == previous) return
            val islandView = getView.invoke(null, owner) ?: return
            // The native event is a no-op before its coordinator exists. Leave the
            // edge unrecorded so a later ready callback can still apply it.
            if (!readyController.isReady(islandView)) return
            // Native combined tempHidden may stay false when only Control Center changes.
            // Explicitly dispatch its event on our edge, preserving all other native hide causes.
            hideEvent.invoke(islandView, forced || value(windowState, tempHidden, hiddenValue), type.invoke(windowState))
            synchronized(forcedControllers) {
                if (forced) forcedControllers[owner] = true else forcedControllers.remove(owner)
            }
        }
        restore = ::refresh
        deoptimize(hiddenGate)
        state.declaredMethods.filter { it.name == "isTempHidden" }.forEach { deoptimize(it) }
        hiddenGate.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "isTempHiddenExt", Unit) {
                    val owner = param.args.firstOrNull() ?: return@open
                    if (shouldHide(owner)) param.result = true
                }
            }
        }
        hideEvent.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "onIslandTempHide", Unit) {
                    val owner = readyController.get(param.thisObject) ?: return@open
                    val windowState = getState.invoke(owner) ?: return@open
                    if (shouldHide(windowState)) {
                        param.args[0] = true
                        synchronized(forcedControllers) { forcedControllers[owner] = true }
                    }
                }
            }
        }
        listOf(expandedEvent, swipeEvent).forEach { event ->
            deoptimize(event)
            event.hook {
                after { param ->
                    val owner = param.thisObject
                    val update = Runnable {
                        HookFailurePolicy.open(TAG, event.name, Unit) { refresh(owner) }
                    }
                    if (Looper.myLooper() == main.looper) update.run() else main.post(update)
                }
            }
        }
        DebugLog.hookRegistered(TAG, "plugin temporary-hide gate and Control Center events")
    }

    override fun saveHotReloadState(): Any = snapshotControllers()
    override fun restoreHotReloadState(state: Any?) = restoreControllers((state as? List<*>)?.filterNotNull().orEmpty())

    override fun onPrepareHotReload() {
        retiring = true
        main.removeCallbacks(refreshSettingsAction)
        main.removeCallbacksAndMessages(null)
        val owners = synchronized(forcedControllers) { forcedControllers.keys.toList() }
        val refresh = restore
        // Restore through native state before retiring this generation. Queued callbacks carry
        // no hook handles and cannot re-enable the old policy.
        val cleanup = Runnable {
            owners.forEach { owner ->
                HookFailurePolicy.open(TAG, "restore native visibility", Unit) { refresh?.invoke(owner) }
            }
            synchronized(forcedControllers) { forcedControllers.clear() }
            synchronized(knownControllers) { knownControllers.clear() }
        }
        if (Looper.myLooper() == main.looper) cleanup.run() else {
            val done = CountDownLatch(1)
            main.post { try { cleanup.run() } finally { done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS)) { "island visibility cleanup timed out" }
        }
    }

    private companion object {
        const val TAG = "ControlCenterSuperIsland"
    }
}
