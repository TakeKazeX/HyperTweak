package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HostScope
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.util.logging.DebugLog

/** Discovers host surfaces once; installed owners supply recovery at dependency barriers. */
internal object StatusIconHotReloadRecovery {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = true
    fun cancel() { active = false; main.removeCallbacksAndMessages(null) }

    fun schedule(context: Context, hooks: Collection<BaseHooker>) {
        active = true
        enqueue(context, hooks, false)
    }

    fun scheduleIcons(context: Context, hooks: Collection<BaseHooker>) = enqueue(context, hooks, true)

    private fun enqueue(context: Context, hooks: Collection<BaseHooker>, iconsOnly: Boolean) {
        val owners = hooks.toList()
        main.post { if (active) recover(context, owners, iconsOnly) }
    }

    private fun recover(context: Context, hooks: List<BaseHooker>, iconsOnly: Boolean) {
        runCatching {
            val app = context.applicationContext ?: context
            val component = read(app, "mSysUIComponent")
                ?: read(app, "mInitializer")?.let { invoke(it, "getSysUIComponent") }
            val scope = HostScope(app, component, windowViews())
            fun dispatch(phase: HostRecoveryPhase) {
                scope.phase = phase
                hooks.forEach { it.recoverHostSurfaces(scope, iconsOnly) }
            }
            dispatch(HostRecoveryPhase.WINDOWS)
            dispatch(HostRecoveryPhase.CONTENT)
            dispatch(HostRecoveryPhase.HEADERS)
            val headerViews = ArrayList<View>()
            scope.headers.forEach { collectViews(it, headerViews) }
            scope.views = (scope.views + headerViews).distinct()
            dispatch(HostRecoveryPhase.HEADER_DECORATIONS)
            scope.controller = component?.let { provider(it, "statusBarIconControllerImplProvider") }
            scope.managers = scope.controller?.let { (read(it, "mIconGroups") as? List<*>).orEmpty().filterNotNull().toList() }.orEmpty()
            dispatch(HostRecoveryPhase.SOURCES)
            val services = read(app, "mServices")?.let { invoke(it, "get") ?: it }
            val entries = when (services) {
                is Array<*> -> services.asList()
                is Iterable<*> -> services.toList()
                else -> emptyList()
            }
            val adapterClass = "com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter"
            scope.adapter = entries.firstOrNull { it?.javaClass?.name == adapterClass }
                ?: component?.let { host ->
                    sequenceOf("getStartables", "getPerUserStartables").mapNotNull { name ->
                        val startables = invoke(host, name) as? Map<*, *> ?: return@mapNotNull null
                        val provider = startables.entries.firstOrNull { (key, _) -> (key as? Class<*>)?.name == adapterClass }?.value
                            ?: return@mapNotNull null
                        invoke(provider, "get")?.takeIf { it.javaClass.name == adapterClass }
                    }.firstOrNull()
                }
            dispatch(HostRecoveryPhase.ADAPTER_POLICY)
            dispatch(HostRecoveryPhase.ADAPTER)
            val controller = scope.controller ?: error("Missing existing SystemUI icon controller")
            check(scope.managers.isNotEmpty()) { "No existing icon managers" }
            rebuildIconGroups(controller, scope.managers) { dispatch(HostRecoveryPhase.SLOT_ORDER) }
            scope.views = windowViews()
            val expansion = component?.let { provider(it, "controlCenterExpandControllerDelegateProvider") }
            scope.progress = expansion?.let { read(it, "expansionState") }?.let(IconTunerFlows::readFlowValue) as? Float
            scope.visible = expansion?.let { read(it, "visibleState") }?.let(IconTunerFlows::readFlowValue) as? Boolean
            scope.stretch = expansion?.let { read(it, "stretchHeightState") }?.let(IconTunerFlows::readFlowValue) as? Float
            dispatch(HostRecoveryPhase.MANAGERS)
            dispatch(HostRecoveryPhase.CONTAINERS)
            dispatch(HostRecoveryPhase.PRESENTATION)
            dispatch(HostRecoveryPhase.ICONS)
            dispatch(HostRecoveryPhase.HANDOVER)
            scope.coroutineScope = component?.let { provider(it, "bgApplicationScopeProvider") }
            scope.wifiInteractor = component?.let { provider(it, "wifiInteractorImplProvider") }
            dispatch(HostRecoveryPhase.NETWORK)
            DebugLog.i("HostRecovery", "hot reload rebound owners=${hooks.size} views=${scope.views.size} managers=${scope.managers.size} progress=${scope.progress} visible=${scope.visible}")
        }.onFailure { DebugLog.w("HostRecovery", "hot reload host discovery failed", it) }
    }

    private fun windowViews(): List<View> = ArrayList<View>().also { views ->
        WindowInspector.getGlobalWindowViews().forEach { collectViews(it, views) }
    }

    private fun collectViews(view: View, views: MutableList<View>) {
        views += view
        if (view is ViewGroup) for (index in 0 until view.childCount) collectViews(view.getChildAt(index), views)
    }

    private fun rebuildIconGroups(controller: Any, managers: List<Any>, applyOrder: () -> Unit) {
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
            applyOrder = applyOrder,
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
