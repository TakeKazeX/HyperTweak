package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.presentation.icon.PanelState
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** The only producer of panel expansion, visibility, stretch and shade-switch state. */
internal object PanelMotionAdapter : StaticHooker() {
    private var callbackType: Class<*>? by generationState { null }
    private var recoveryContext: Context? by generationState { null }

    init {
        hostRecovery(HostRecoveryPhase.HANDOVER, icons = true) {
            recoveryContext = it.context
            recoverMotionHost(it.component)
            PanelHub.recover(it.progress, it.visible, it.stretch)
        }
    }
    override fun onHook() {
        if (!isMainProcess) return
        val info = hookParam.appInfo ?: return
        val path = info.sourceDir ?: return
        prepareDexTargets("icon panel motion", resolve = { resolveMethods(path, info) }, install = { methods ->
            installMethods(methods)
            // Recovery may already have run while the worker was resolving. Re-discover the
            // current delegate state and live callback without rebuilding any icon group.
            (recoveryContext ?: hookParam.appContext)?.let {
                StatusIconHotReloadRecovery.scheduleScoped(it, listOf(this))
            }
        })
    }

    private fun resolveMethods(path: String, info: android.content.pm.ApplicationInfo): Map<String, Method>? {
        return DexRuntime.resolveMethodMap(
            "systemui.icon_panel_adapter.v1", path, dexCacheDirectory, classLoader, info,
            validate = { key, method -> valid(key, method) }
        ) { bridge ->
            val expansion = bridge.findMethod { matcher {
                name("onExpansionChanged"); paramTypes(Float::class.javaPrimitiveType!!); returnType(Void.TYPE)
            } }.toList().filter { data ->
                // Prove the callback drives both the expanded row and its collapsed mirror.
                val fields = data.usingFields.map { it.field.typeName }.toSet()
                fields.containsAll(setOf(
                    "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon",
                    "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons"
                )) && data.invokes.any { it.methodName == "setTranslationY" }
            }.singleOrNull()?.getMethodInstance(classLoader) ?: return@resolveMethodMap emptyMap()
            val type = expansion.declaringClass
            val result = linkedMapOf("expansion" to expansion)
            mapOf("visibility" to "onVisibleChanged", "stretch" to "onStretchHeightChanged",
                "appearance" to "onAppearanceChanged").forEach { (key, name) ->
                type.declaredMethods.singleOrNull { it.name == name && valid(key, it) }?.let { result[key] = it }
            }
            // The switching writer belongs to the same controller read by expansion.
            val header = bridge.findMethod { matcher {
                name("onSwitchingChanged"); paramTypes(Boolean::class.javaPrimitiveType!!); returnType(Void.TYPE)
            } }.toList().filter { data -> data.usingFields.any {
                it.field.className == "com.android.systemui.controlcenter.shade.CombinedHeaderController" &&
                    it.field.typeName == "boolean"
            } }.singleOrNull()?.getMethodInstance(classLoader)
            header?.let { method ->
                result["switching"] = method
                method.declaringClass.declaredMethods.singleOrNull {
                    it.name == "onSwitchProgressChanged" && valid("switchProgress", it)
                }?.let { result["switchProgress"] = it }
            }
            if (result.keys.containsAll(listOf("visibility", "appearance", "stretch", "switching", "switchProgress"))) result
            else emptyMap()
        }?.takeIf { it.isNotEmpty() }
    }

    private fun installMethods(methods: Map<String, Method>) {
        callbackType = methods.getValue("expansion").declaringClass
        methods.forEach { (key, method) ->
            deoptimize(method)
            method.hook {
                before { param ->
                    if (key != "stretch") PanelHub.prepare(next(key, param.args[0], PanelHub.state) ?: PanelHub.state)
                }
                after { param ->
                    if (key == "expansion") PanelHub.observeHost(param.thisObject)
                    PanelHub.publish(next(key, param.args[0], PanelHub.state) ?: return@after)
                }
            }
        }
    }
    private fun recoverMotionHost(component: Any?) {
        val type = callbackType ?: return
        runCatching {
            val delegate = component?.let { HostAccess.provider(it, "controlCenterExpandControllerDelegateProvider") }
                ?: return
            val callbacks = HostAccess.read(delegate, "callbacks") as? List<*> ?: return
            callbacks.filterNotNull().filter(type::isInstance).singleOrNull()?.let(PanelHub::observeHost)
        }.onFailure { DebugLog.w("IconPanel", "living panel callback recovery failed", it) }
    }

    /** The state one host motion step publishes; null when the argument does not fit the contract. */
    private fun next(key: String, arg: Any?, state: PanelState): PanelState? = when (key) {
        "expansion" -> (arg as? Float)?.let(state::expansion)
        "visibility" -> (arg as? Boolean)?.let(state::visibility)
        "appearance" -> if (arg == true) state.visibility(true) else state
        "stretch" -> (arg as? Float)?.takeIf(Float::isFinite)?.let { state.copy(stretch = it) } ?: state
        "switching" -> (arg as? Boolean)?.let { switch(state, it, state.shadeSwitchProgress) }
        "switchProgress" -> (arg as? Float)?.takeIf(Float::isFinite)
            ?.let { switch(state, state.hostSwitching, it.coerceIn(0f, 1f)) }
        else -> state
    }

    private fun switch(state: PanelState, hostSwitching: Boolean, progress: Float): PanelState {
        val active = ShadeSwitchMotionPolicy.isActive(hostSwitching, progress)
        val center = ShadeSwitchMotionPolicy.controlCenterAtRest(progress)
        return state.copy(shadeSwitching = active, hostSwitching = hostSwitching, shadeSwitchProgress = progress,
            progress = if (active) 0f else if (state.shadeSwitching) { if (center) 1f else 0f } else state.progress,
            visible = if (active) false else if (state.shadeSwitching) center else state.visible,
            settledToControlCenter = !active && center && (state.shadeSwitching || state.settledToControlCenter))
    }
    private fun valid(key: String, method: Method): Boolean {
        if (method.returnType != Void.TYPE || Modifier.isStatic(method.modifiers)) return false
        val params = when (key) {
            "visibility", "switching" -> arrayOf(Boolean::class.javaPrimitiveType)
            "appearance" -> arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            else -> arrayOf(Float::class.javaPrimitiveType)
        }
        return method.parameterTypes.contentEquals(params)
    }
}
