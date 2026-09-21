package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalHooker
import com.takekazex.hypertweak.util.DebugLog

/** Recover already-inflated hosts after all replacement hooks and saved snapshots are installed. */
internal object StatusIconHotReloadRecovery {
    private val main = Handler(Looper.getMainLooper())

    fun schedule(context: Context) {
        main.post { recover(context) }
    }

    private fun recover(context: Context) {
        runCatching {
            val app = context.applicationContext ?: context
            val component = read(app, "mSysUIComponent")
                ?: read(app, "mInitializer")?.let { invoke(it, "getSysUIComponent") }
            val roots = WindowInspector.getGlobalWindowViews()
            // Snapshot before any feature adds/removes its owned children.
            val views = ArrayList<View>()
            fun visit(view: View) {
                views += view
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            roots.forEach(::visit)
            val expansion = component?.let { provider(it, "controlCenterExpandControllerDelegateProvider") }
            val progress = expansion?.let { read(it, "expansionState") }
                ?.let(IconTunerFlows::readFlowValue) as? Float
            val visible = expansion?.let { read(it, "visibleState") }
                ?.let(IconTunerFlows::readFlowValue) as? Boolean
            val stretch = expansion?.let { read(it, "stretchHeightState") }
                ?.let(IconTunerFlows::readFlowValue) as? Float
            fun recoverFeature(name: String, action: () -> Unit) {
                runCatching(action).onFailure { DebugLog.w("IconTuner", "hot reload recovery failed: $name", it) }
            }
            val controller = component?.let { provider(it, "statusBarIconControllerImplProvider") }
            val managers = controller?.let { read(it, "mIconGroups") as? List<*> }.orEmpty().filterNotNull()
            recoverFeature("manager") { IconManagerHooker.recoverManagers(managers) }
            recoverFeature("containers") { IconPositionHooker.recoverExistingContainers(views) }
            recoverFeature("left") { LeftContainerHooker.recoverExistingViews(views, progress, visible) }
            recoverFeature("carrier") { ControlCenterCarrierBlockHooker.recoverExistingViews(views, progress, visible) }
            recoverFeature("header") { ControlCenterHeaderHooker.recoverExistingViews(views) }
            recoverFeature("Duo") { DuoSignalHooker.recoverExistingViews(views, progress, visible, stretch) }
            if (component != null) {
                val scope = provider(component, "bgApplicationScopeProvider")
                val interactor = provider(component, "wifiInteractorImplProvider")
                if (scope != null && interactor != null) {
                    recoverFeature("carrier Wi-Fi") { ControlCenterCarrierBlockHooker.recoverWifi(scope, interactor, app) }
                    recoverFeature("Duo Wi-Fi") { DuoSignalHooker.recoverWifi(scope, interactor, app) }
                }
            }
            DebugLog.i("IconTuner", "hot reload rebound roots=${roots.size} managers=${managers.size} progress=$progress visible=$visible")
        }.onFailure { DebugLog.w("IconTuner", "hot reload host discovery failed", it) }
    }

    private fun provider(component: Any, name: String): Any? = read(component, name)?.let { invoke(it, "get") }
    private fun invoke(owner: Any, name: String): Any? = runCatching {
        owner.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }?.invoke(owner)
    }.getOrNull()
    private fun read(owner: Any, name: String): Any? = generateSequence(owner.javaClass) { it.superclass }
        .mapNotNull { type -> runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
        .firstOrNull()?.let { runCatching { it.get(owner) }.getOrNull() }
}
