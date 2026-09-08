package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.util.DebugLog

/**
 * Removes the media Super Island dropdown whitelist in the MIUI SystemUI plugin.
 *
 * The actual media mini-window/dropdown gate is the plugin-side
 * `NotificationSettingsManager.mediaIslandSupportMiniWindow(pkg)`, which checks
 * `config_dynamic_island_miniwindow_media_whitelist`. The base SystemUI
 * `canShowFocusMediaState()` gate is a separate notification preference and does not unlock this
 * path. This hook is attached with the plugin PathClassLoader after the plugin is loaded.
 *
 * The master preference is checked again at callback time so turning the feature off restores the
 * plugin's original whitelist result without requiring another process restart.
 */
class MediaSuperIslandWhitelistHooker : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private companion object {
        const val TAG = "MediaSuperIslandWhitelist"
        const val SETTINGS_MANAGER =
            "miui.systemui.notification.NotificationSettingsManager"
    }

    override fun onHook() {
        val manager = SETTINGS_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, SETTINGS_MANAGER, "class not found")
            return
        }
        val mediaIslandSupportMiniWindow = manager.declaredMethods.singleOrNull {
            it.name == "mediaIslandSupportMiniWindow" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == Boolean::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$SETTINGS_MANAGER#mediaIslandSupportMiniWindow",
                "method not found"
            )
            return
        }

        mediaIslandSupportMiniWindow.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "$SETTINGS_MANAGER#mediaIslandSupportMiniWindow", Unit) {
                    if (Preferences.getBoolean(
                            Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST,
                            false
                        )
                    ) {
                        param.result = true
                    }
                }
            }
        }
        DebugLog.i(
            TAG,
            "HOOK_OK plugin media Super Island dropdown whitelist removed"
        )
    }
}
