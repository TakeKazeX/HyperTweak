package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Keeps the control-center Device Center (融合设备中心) card to one row, OS4 miui.systemui.plugin.
 *
 * The host card controller rebuilds its items in a private `(Z)V` builder: one item per entry of
 * its pending `List<DeviceInfoWrapper>`, then the native "more devices" detail item (three dots,
 * opens the Smart Hub list). Its row mode comes from the item count alone: more than four items
 * means two rows. While the builder runs, this hook shows it only the first
 * [MAX_DEVICE_ICONS] devices and restores the full list afterwards, so the host's own detail
 * item lands in the fourth slot and its own mode logic picks one row. The device controller's
 * list, discovery tracking and every other listener keep the full list.
 *
 * The third-party device-control branch replaces the whole list with a single item, so it is
 * unaffected. A card narrower than four columns (Control Center resize) can still wrap; the
 * count is fixed for the stock full-width card.
 *
 * Attached ONLY from `SystemUIPluginHooker` with the plugin PathClassLoader. Live changes and
 * hot reload rebuild the controllers this generation has seen; a controller first seen by an
 * older build rebuilds on its next `onResume` (every Control Center open).
 */
class DeviceCenterSingleRowHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingSingleRow by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_DEVICE_CENTER_SINGLE_ROW, false)
    }

    private companion object {
        const val TAG = "DeviceCenterSingleRow"
        const val CONTROLLER_CLASS =
            "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController"
        const val MAX_DEVICE_ICONS = 3
    }

    private class Contract(val pending: Field, val build: Method)

    private var contract: Contract? by generationState { null }

    /** Controllers whose builder ran in this generation; values are unused. */
    private val controllers = generationMainResource(WeakHashMap<Any, Boolean>()) { it.clear() }

    private val mainHandler = generationMainHandler()

    @Volatile private var retiring = false

    override fun onHook() {
        retiring = false
        contract = null
        val controller = CONTROLLER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CONTROLLER_CLASS, "class not found")
            return
        }
        val resolved = resolveContract(controller) ?: return
        contract = resolved
        resolved.build.hook {
            intercept { chain ->
                val self = chain.thisObject
                val full = HookFailurePolicy.open(TAG, "device list read", null) {
                    controllers[self] = true
                    if (isEnabled()) resolved.pending.get(self) as? List<*> else null
                }
                if (full == null || full.size <= MAX_DEVICE_ICONS) return@intercept chain.proceed()
                val swapped = HookFailurePolicy.open(TAG, "device list trim", false) {
                    resolved.pending.set(self, ArrayList(full.subList(0, MAX_DEVICE_ICONS)))
                    true
                }
                if (!swapped) return@intercept chain.proceed()
                try {
                    chain.proceed()
                } finally {
                    HookFailurePolicy.open(TAG, "device list restore", Unit) {
                        // A nested onDeviceListChanged cannot run inside the builder (both are on
                        // main), so the field still holds our trimmed copy here.
                        resolved.pending.set(self, full)
                    }
                    DebugLog.d(TAG, "trimmed device row ${full.size} -> $MAX_DEVICE_ICONS")
                }
            }
        }
        DebugLog.d(TAG, "hooked ${controller.simpleName}#${resolved.build.name} pending=${resolved.pending.name}")
    }

    /**
     * Structural contract: the controller implements a single-method `(List)V` listener, owns
     * exactly one instance `List` field (the pending device list) and one `ArrayList` field (the
     * built items), exposes a no-arg method returning a three-constant enum (the row mode), and
     * has exactly one private instance `(Z)V` method (the item builder). Anything else rejects
     * the contract and leaves the card native.
     */
    private fun resolveContract(controller: Class<*>): Contract? {
        val listener = controller.interfaces.any { iface ->
            val methods = iface.declaredMethods.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            methods.size == 1 && methods[0].returnType == Void.TYPE &&
                methods[0].parameterTypes.contentEquals(arrayOf(List::class.java))
        }
        val instanceFields = controller.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val pending = instanceFields.filter { it.type == List::class.java && !Modifier.isFinal(it.modifiers) }
        val items = instanceFields.filter { it.type == java.util.ArrayList::class.java }
        val modeGetter = controller.declaredMethods.any { method ->
            method.parameterCount == 0 && method.returnType.isEnum &&
                method.returnType.enumConstants?.size == 3
        }
        val builders = controller.declaredMethods.filter { method ->
            Modifier.isPrivate(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                !method.isSynthetic && method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }
        if (!listener || pending.size != 1 || items.size != 1 || !modeGetter || builders.size != 1) {
            DebugLog.hookSkipped(
                TAG, "${controller.name} contract",
                "listener=$listener pending=${pending.size} items=${items.size} mode=$modeGetter builders=${builders.size}"
            )
            return null
        }
        return Contract(
            pending = pending[0].apply { isAccessible = true },
            build = builders[0].apply { isAccessible = true }
        )
    }

    /**
     * Accepted-snapshot refresh. Rebuilds every living controller with the host's own builder
     * (`false` = no animation, the host's own refresh variant). The builder returns early when
     * the card is not listening, so a closed Control Center simply picks the value up on open.
     */
    fun refreshSettings() {
        if (retiring) return
        val build = contract?.build ?: return
        val enabled = isEnabled()
        mainHandler.post {
            if (retiring) return@post
            controllers.keys.toList().forEach { controller ->
                runCatching { build.invoke(controller, false) }
                    .onFailure { DebugLog.w(TAG, "device row rebuild failed", it) }
            }
        }
        DebugLog.i(TAG, "settings applied enabled=$enabled controllers=${controllers.size}")
    }

    override fun saveHotReloadState(): Any = HostAccess.onMain { controllers.keys.toList() }

    override fun restoreHotReloadState(state: Any?) {
        val saved = (state as? List<*>)?.filterNotNull().orEmpty()
        val owner = CONTROLLER_CLASS.toClassOrNull() ?: return
        HostAccess.onMain {
            saved.filter { owner.isInstance(it) }.forEach { controllers[it] = true }
        }
        refreshSettings()
    }

    private fun isEnabled(): Boolean = !retiring && settingSingleRow

    override fun onPrepareHotReload() {
        retiring = true
    }
}
