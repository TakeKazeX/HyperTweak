package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Hides the control-center Edit button, OS4 miui.systemui.plugin.
 *
 * `MainPanelContentDistributor.distributePanels(Z)` asks every child `MainPanelContent` whether it
 * is `available(Z)` and only lays out the available ones. While enabled, `EditButtonController`
 * answers false, so the host drops the button and its row itself: there is no empty slot and no
 * view is touched. The host already answers false in compact, horizontal, wide-fold-compact,
 * edit mode and super power save.
 *
 * The button is the only visible way into the control-center edit mode, so hiding it also hides
 * the entry to the module's card editor; the settings page says so.
 *
 * Attached for the whole plugin session by `SystemUIPluginHooker`. A live change and a hot-reload
 * restore redistribute every distributor seen by this generation with the host's own `distributePanels$default` and
 * `handleNotifyChanged$default` pair (what the host's entry controllers post). A distributor this
 * generation has not laid out under the current value (first seen by an older build, or retained
 * by a plugin that was not open during the change) catches up in `onStart()`, which the host
 * dispatches on every Control Center open.
 */
class ControlCenterEditButtonHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingHidden by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_EDIT_BUTTON, false)
    }

    private companion object {
        const val TAG = "ControlCenterEditButton"
        const val EDIT_BUTTON = "miui.systemui.controlcenter.panel.main.qs.EditButtonController"
        const val DISTRIBUTOR = "miui.systemui.controlcenter.panel.main.MainPanelContentDistributor"
    }

    /** The host's own redistribution pair, both static Kotlin default-argument bridges. */
    private class Refresh(val distribute: Method, val notifyChanged: Method)

    private var refresh: Refresh? by generationState { null }

    /** Distributors seen by this generation, with the hidden value of their last layout. */
    private val distributors = generationMainResource(WeakHashMap<Any, Boolean>()) { it.clear() }

    private val mainHandler = generationMainHandler()

    @Volatile private var retiring = false

    override fun onHook() {
        retiring = false
        refresh = null
        val editButton = EDIT_BUTTON.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, EDIT_BUTTON, "class not found")
            return
        }
        val distributor = DISTRIBUTOR.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, DISTRIBUTOR, "class not found")
            return
        }
        val boolean = Boolean::class.javaPrimitiveType!!
        // The distributor's constructor takes the edit controller: that ties the two classes.
        val owned = distributor.declaredConstructors.any { ctor -> editButton in ctor.parameterTypes }
        val available = editButton.declaredMethods.filter { method ->
            method.name == "available" && !Modifier.isStatic(method.modifiers) && !method.isSynthetic &&
                method.returnType == boolean
        }
        val distribute = distributor.declaredMethods.filter { method ->
            method.name == "distributePanels" && !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(boolean))
        }
        val start = distributor.declaredMethods.singleOrNull { method ->
            method.name == "onStart" && method.parameterCount == 0 && method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers)
        }
        if (!owned || available.size != 1 || !available[0].parameterTypes.contentEquals(arrayOf(boolean)) ||
            distribute.size != 1) {
            DebugLog.hookSkipped(TAG, "$EDIT_BUTTON contract",
                "owned=$owned available=${available.map { it.parameterTypes.size }} distribute=${distribute.size}")
            return
        }
        refresh = resolveRefresh(distributor)
        if (refresh == null) DebugLog.w(TAG, "redistribution bridges not recognized; live changes wait for the host")

        distribute[0].hook {
            before { param ->
                HookFailurePolicy.open(TAG, "remember distributor", Unit) {
                    if (!retiring) param.thisObjectOrNull?.let { distributors[it] = isHidden() }
                }
            }
        }
        available[0].hook {
            after { param ->
                HookFailurePolicy.open(TAG, "edit button availability", Unit) {
                    if (isHidden() && param.result == true) param.result = false
                }
            }
        }
        start?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "catch up on open", Unit) {
                    val owner = param.thisObjectOrNull ?: return@open
                    if (!retiring && distributors[owner] != isHidden()) redistributeNow(listOf(owner))
                }
            }
        } ?: DebugLog.w(TAG, "distributor onStart not found; a living panel follows on its next layout")
        DebugLog.d(TAG, "hooked ${editButton.simpleName}#available hidden=${isHidden()}")
    }

    /**
     * `distributePanels$default(distributor, Z, I, Object)` and
     * `handleNotifyChanged$default(distributor, Z, Z, I, Object)`; the default mask lets the host
     * choose the orientation argument itself.
     */
    private fun resolveRefresh(distributor: Class<*>): Refresh? {
        val boolean = Boolean::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        fun bridge(name: String, vararg params: Class<*>) = distributor.declaredMethods.singleOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(distributor, *params))
        }?.apply { isAccessible = true }
        val distribute = bridge("distributePanels\$default", boolean, int, Any::class.java) ?: return null
        val notifyChanged = bridge("handleNotifyChanged\$default", boolean, boolean, int, Any::class.java) ?: return null
        return Refresh(distribute, notifyChanged)
    }

    /** Accepted-snapshot refresh: redistribute the living panels so the button follows at once. */
    fun refreshSettings() {
        if (retiring) return
        redistribute()
        DebugLog.i(TAG, "settings applied hidden=${isHidden()} distributors=${distributors.size}")
    }

    private fun redistribute() {
        if (refresh == null) return
        mainHandler.post { redistributeNow(distributors.keys.toList()) }
    }

    /** Main thread only. Mirrors the host's entry-controller refresh: `(false, mask 1)` then `(false, false, mask 2)`. */
    private fun redistributeNow(targets: List<Any>) {
        val bridges = refresh ?: return
        targets.forEach { distributor ->
            runCatching {
                bridges.distribute.invoke(null, distributor, false, 1, null)
                bridges.notifyChanged.invoke(null, distributor, false, false, 2, null)
            }.onFailure { DebugLog.w(TAG, "control-center redistribution failed", it) }
        }
    }

    override fun saveHotReloadState(): Any = HostAccess.onMain { distributors.keys.toList() }

    override fun restoreHotReloadState(state: Any?) {
        val owner = DISTRIBUTOR.toClassOrNull() ?: return
        val saved = (state as? List<*>)?.filterNotNull().orEmpty()
        // Saved states predate this generation's value; the redistribution below records it.
        HostAccess.onMain { saved.filter { owner.isInstance(it) }.forEach { distributors[it] = !isHidden() } }
        if (!retiring) redistribute()
    }

    private fun isHidden(): Boolean = !retiring && settingHidden

    /**
     * The retiring generation answers natively from here on. It does not redistribute: plugin
     * unload also retires this hooker, and a hot-reload successor redistributes on restore.
     */
    override fun onPrepareHotReload() {
        retiring = true
    }
}
