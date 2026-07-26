package com.takekazex.hypertweak.hook.rules.systemui

import android.content.ComponentName
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Restores the AOSP power menu and volume panel by hiding MIUI's replacements from SystemUI's
 * plugin framework.
 *
 * `PluginActionManager.loadPluginComponent(ComponentName)` is the single funnel both plugin
 * discovery paths go through, and each caller skips the plugin when it returns null. Returning null
 * for the two MIUI components is what disabling them with `pm disable` achieves, without root and
 * without leaving persistent component state behind: turning the switch off and restarting SystemUI
 * restores MIUI's own UI.
 *
 * Matching is by class name so the control-center plugin in the same package — which
 * [SystemUIPluginHooker] depends on — keeps loading.
 */
object AospSystemUiPluginBlockHooker : StaticHooker() {
    private const val TAG = "AospSystemUiPlugin"

    private const val PLUGIN_ACTION_MANAGER = "com.android.systemui.shared.plugins.PluginActionManager"
    private const val GLOBAL_ACTIONS_PLUGIN = "miui.systemui.globalactions.GlobalActionsPlugin"
    private const val VOLUME_DIALOG_PLUGIN = "miui.systemui.volume.VolumeDialogPlugin"

    @Volatile
    private var blockedClasses: Set<String> = emptySet()

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onPrepareHotReload() {
        blockedClasses = emptySet()
    }

    override fun onHook() {
        blockedClasses = buildSet {
            if (Preferences.getBoolean(Preferences.KEY_AOSP_POWER_MENU, false)) add(GLOBAL_ACTIONS_PLUGIN)
            if (Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, false)) add(VOLUME_DIALOG_PLUGIN)
        }
        if (blockedClasses.isEmpty()) {
            DebugLog.hookSkipped(TAG, "MIUI SystemUI plugins", "disabled")
            return
        }

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
