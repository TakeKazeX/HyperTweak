package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.rules.systemui.NotificationHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationHeaderWeatherHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationHeaderClockSecondsHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideAppIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideDeviceSwitchHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenDateHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationContentHotReloadRecovery
import com.takekazex.hypertweak.hook.rules.systemui.HideLockscreenStatusBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenBottomTextHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenChargingDetailHooker
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

    @Volatile private var active = true
    fun cancel() { active = false; main.removeCallbacksAndMessages(null) }

    fun schedule(context: Context) {
        main.post { if (active) recover(context) }
    }

    private fun recover(context: Context) {
        runCatching {
            val app = context.applicationContext ?: context
            val component = read(app, "mSysUIComponent")
                ?: read(app, "mInitializer")?.let { invoke(it, "getSysUIComponent") }
            fun recoverFeature(name: String, action: () -> Unit) {
                runCatching(action).onFailure { DebugLog.w("IconTuner", "hot reload recovery failed: $name", it) }
            }
            // Header restoration must not depend on native status-icon group availability.
            val existingViews = windowViews()
            recoverFeature("notification header") {
                val headers = NotificationHeaderHooker.recoverExistingHosts(component, existingViews)
                val headerViews = ArrayList<View>()
                headers.forEach { collectViews(it, headerViews) }
                NotificationHeaderClockSecondsHooker.recoverExistingViews((existingViews + headerViews).distinct())
                NotificationHeaderWeatherHooker.recoverExistingHeaders(headers)
            }
            recoverFeature("notification content") { NotificationContentHotReloadRecovery.recover(existingViews) }
            recoverFeature("media app icon") { MediaCardHideAppIconHooker.recoverExistingViews(existingViews) }
            recoverFeature("media output switch") { MediaCardHideDeviceSwitchHooker.recoverExistingViews(existingViews) }
            recoverFeature("lockscreen date") { LockscreenDateHooker.recoverExistingViews(existingViews) }
            recoverFeature("lockscreen status bar") { HideLockscreenStatusBarHooker.recoverExistingViews(existingViews) }
            recoverFeature("lockscreen compact labels") { LockscreenBottomTextHooker.recoverExistingViews(existingViews) }
            recoverFeature("lockscreen indications") {
                component?.let { provider(it, "keyguardIndicationControllerProvider") }?.let { indication ->
                    // Rebuild native messages from current battery, biometric and transient state.
                    val refresh = StatusIconHostAccess.method(indication, "updateDeviceEntryIndication", Boolean::class.javaPrimitiveType!!)
                        ?: error("Missing native keyguard indication refresh")
                    refresh.invoke(indication, false)
                    read(indication, "mRotateTextViewController")?.let(LockscreenChargingDetailHooker::recoverController)
                }
            }
            val controller = component?.let { provider(it, "statusBarIconControllerImplProvider") }
                ?: error("Missing existing SystemUI icon controller")
            val managers = (read(controller, "mIconGroups") as? List<*>).orEmpty().filterNotNull().toList()
            check(managers.isNotEmpty()) { "No existing icon managers" }
            recoverFeature("observer") { IgnoreSysIconSettingsHooker.recoverController(controller) }
            if (component != null) {
                recoverFeature("operator config") { CellularTypeIconHooker.recoverOperatorConfig(component) }
                recoverFeature("Wi-Fi source") { WifiIconHooker.recoverViewModel(component) }
                recoverFeature("network speed") { IgnoreSysIconSettingsHooker.recoverNetworkSpeed(component) }
                recoverFeature("region sampling") { RegionSamplingHooker.recover(component) }
            }
            // Getters must expose current per-SIM flows BEFORE fresh binders collect them.
            recoverFeature("SIM visibility") {
                val services = read(app, "mServices")?.let { invoke(it, "get") ?: it }
                val entries = when (services) {
                    is Array<*> -> services.asList()
                    is Iterable<*> -> services.toList()
                    else -> emptyList()
                }
                val adapterClass = "com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter"
                val adapter = entries.firstOrNull { it?.javaClass?.name == adapterClass }
                    ?: component?.let { host ->
                        sequenceOf("getStartables", "getPerUserStartables").mapNotNull { name ->
                            val startables = invoke(host, name) as? Map<*, *> ?: return@mapNotNull null
                            val provider = startables.entries.firstOrNull { (key, _) ->
                                (key as? Class<*>)?.name == adapterClass
                            }?.value ?: return@mapNotNull null
                            invoke(provider, "get")?.takeIf { it.javaClass.name == adapterClass }
                        }.firstOrNull()
                    }
                if (adapter != null) {
                    HideCellularIconHooker.recoverAdapter(adapter)
                    StackedSignalHooker.recoverAdapter(adapter)
                }
            }
            // The native lifecycle disposes old binders/dark receivers and creates fresh views
            // against the new slot indices. Holders, subscription VMs and manager identity survive.
            rebuildIconGroups(controller, managers, app)
            val roots = WindowInspector.getGlobalWindowViews()
            val views = windowViews()
            val expansion = component?.let { provider(it, "controlCenterExpandControllerDelegateProvider") }
            val progress = expansion?.let { read(it, "expansionState") }
                ?.let(IconTunerFlows::readFlowValue) as? Float
            val visible = expansion?.let { read(it, "visibleState") }
                ?.let(IconTunerFlows::readFlowValue) as? Boolean
            val stretch = expansion?.let { read(it, "stretchHeightState") }
                ?.let(IconTunerFlows::readFlowValue) as? Float
            recoverFeature("manager") { IconManagerHooker.recoverManagers(managers) }
            recoverFeature("containers") { IconPositionHooker.recoverExistingContainers(views) }
            recoverFeature("left") { LeftContainerHooker.recoverExistingViews(views, progress, visible) }
            recoverFeature("carrier") { ControlCenterCarrierBlockHooker.recoverExistingViews(app, views, progress, visible) }
            recoverFeature("carrier visibility") { HideCarrierLabelHooker.recoverExistingViews(views) }
            recoverFeature("battery style") { CcBatteryStyleHooker.recoverExistingViews(views) }
            recoverFeature("notification count") { NotificationMaxNumberHooker.recoverExistingViews(views) }
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

    private fun windowViews(): List<View> = ArrayList<View>().also { views ->
        WindowInspector.getGlobalWindowViews().forEach { collectViews(it, views) }
    }

    private fun collectViews(view: View, views: MutableList<View>) {
        views += view
        if (view is ViewGroup) for (index in 0 until view.childCount) collectViews(view.getChildAt(index), views)
    }

    private fun rebuildIconGroups(controller: Any, managers: List<Any>, context: Context) {
        val remove = controller.javaClass.methods.singleOrNull {
            it.name == "removeIconGroup" && it.parameterCount == 1
        }?.apply { isAccessible = true } ?: error("Missing native removeIconGroup")
        val add = controller.javaClass.methods.singleOrNull {
            it.name == "addIconGroup" && it.parameterCount == 1
        }?.apply { isAccessible = true } ?: error("Missing native addIconGroup")
        check(managers.all { add.parameterTypes[0].isInstance(it) && remove.parameterTypes[0].isInstance(it) })
        val list = read(controller, "mStatusBarIconList") ?: error("Missing native icon list")
        @Suppress("UNCHECKED_CAST")
        val slots = read(list, "mSlots") as? MutableList<Any?> ?: error("Missing native slots")
        val original = slots.toList()
        IconGroupReloadTransaction.run(
            groups = managers,
            attached = { it in (read(controller, "mIconGroups") as? List<*>).orEmpty() },
            detach = { remove.invoke(controller, it) },
            applyOrder = { IconPositionHooker.recoverSlotOrder(controller, context) },
            restoreOrder = { slots.clear(); slots.addAll(original) },
            attach = { add.invoke(controller, it) }
        )
        DebugLog.i("IconTuner", "hot reload native icon groups rebuilt=${managers.size}")
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
