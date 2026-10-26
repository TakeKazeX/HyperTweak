package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.content.ComponentName
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Restores the AOSP power menu and volume panel by hiding MIUI's replacements from SystemUI's
 * plugin framework.
 *
 * `PluginActionManager.loadPluginComponent(ComponentName)` is the single funnel both plugin
 * discovery paths go through, and each caller skips the plugin when it returns null. Returning null
 * for the two MIUI components is what disabling them with `pm disable` achieves, without root and
 * without leaving persistent component state behind. Reloading the two action managers restores
 * MIUI's own UI when the switch is turned off.
 *
 * Matching is by class name so the control-center plugin in the same package — which
 * [SystemUIPluginHooker] depends on — keeps loading.
 */
object AospSystemUiPluginBlockHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingAospPowerMenu by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_POWER_MENU, false) }
    private val settingAospVolumePanel by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL, false) }

    private const val TAG = "AospSystemUiPlugin"

    private const val PLUGIN_ACTION_MANAGER = "com.android.systemui.shared.plugins.PluginActionManager"
    private const val GLOBAL_ACTIONS_PLUGIN = "miui.systemui.globalactions.GlobalActionsPlugin"
    private const val VOLUME_DIALOG_PLUGIN = "miui.systemui.volume.VolumeDialogPlugin"

    private val blockedClasses: Set<String> get() = buildSet {
        if (settingAospPowerMenu) add(GLOBAL_ACTIONS_PLUGIN)
        if (settingAospVolumePanel) add(VOLUME_DIALOG_PLUGIN)
    }
    private var component: Any? by generationState { null }
    private var applied: Set<String>? by generationState { null }

    init {
        hostRecovery(HostRecoveryPhase.CONTENT) { scope ->
            component = scope.component
            refreshSettings()
        }
    }

    fun refreshSettings(): Boolean = HookFailurePolicy.open(TAG, "reload plugins", false) {
        val wanted = blockedClasses
        if (applied == wanted) return@open true
        val host = component ?: return@open false
        val root = HostAccess.read(host, "referenceGlobalRootComponentImpl") ?: return@open false
        val manager = HostAccess.provider(root, "providesPluginManagerImplProvider") ?: return@open false
        // Snapshot under the same monitor as PluginManagerImpl's add/remove listener operations.
        val managers = synchronized(manager) {
            (HostAccess.read(manager, "pluginMap") as? Map<*, *>)?.values?.filterNotNull()?.toList()
        } ?: return@open false
        managers.filter {
            HostAccess.read(it, "action") in setOf("com.android.systemui.action.PLUGIN_GLOBAL_ACTIONS",
                "com.android.systemui.action.PLUGIN_VOLUME")
        }.forEach { checkNotNull(HostAccess.method(it, "loadAll")).invoke(it) }
        applied = wanted
        true
    }

    override fun onHook() {
        val actionManager = PLUGIN_ACTION_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, PLUGIN_ACTION_MANAGER, "class not found")
            return
        }

        val loadPluginComponent = CompatibleMethodResolver.find(
            actionManager,
            "loadPluginComponent",
            parameterTypes = listOf(ComponentName::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$PLUGIN_ACTION_MANAGER#loadPluginComponent(ComponentName)",
                "method not found"
            )
            return
        }

        runCatching {
            loadPluginComponent.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "loadPluginComponent", Unit) {
                        val component = param.args.getOrNull(0) as? ComponentName ?: return@open
                        if (component.className in blockedClasses) {
                            param.result = null
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$PLUGIN_ACTION_MANAGER#loadPluginComponent(ComponentName)", it)
        }
    }
}
