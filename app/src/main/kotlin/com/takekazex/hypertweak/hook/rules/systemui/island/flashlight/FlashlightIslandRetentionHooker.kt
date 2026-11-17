package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.os.UserHandle
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Modifier

/**
 * SystemUI half of the flashlight island: while [IslandFlashlightHooker]'s panel is on screen, a
 * torch-off close of the focus notification becomes a status-only update, so the island stays
 * and shows the off state. The panel replays the close when it is released (see
 * [FlashlightIslandHold]). Nothing is held across generations; retirement of the plugin hooker
 * replays any pending close.
 */
object FlashlightIslandRetentionHooker : StaticHooker() {
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL, false)
    }

    private const val TAG = "IslandFlashlightHold"
    private const val CONTROLLER = "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl"

    override fun onHook() {
        val controller = CONTROLLER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CONTROLLER, "not found on host")
            return
        }
        val handle = HostAccess.requireDeclaredMethod(controller, "handleFocusNotificationOrStatus",
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, UserHandle::class.java, accessible = false)
        check(handle.returnType == Void.TYPE && !Modifier.isStatic(handle.modifiers))
        // The off path reaches it through the torch callback and the synthetic nest accessor.
        listOfNotNull(
            HostAccess.declaredMethod(controller, "-\$\$Nest\$mhandleFocusNotificationOrStatus", controller,
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, accessible = false),
            "$CONTROLLER\$9".toClassOrNull()?.let {
                HostAccess.declaredMethod(it, "onTorchModeChanged", String::class.java, Boolean::class.javaPrimitiveType!!, accessible = false)
            },
        ).forEach { deoptimize(it) }
        deoptimize(handle)
        handle.hook {
            before { param ->
                if (!settingEnabled) return@before
                HookFailurePolicy.open(TAG, "hold flashlight island", Unit) {
                    val on = param.args[0] as? Boolean ?: return@open
                    val op = param.args[1] as? Int ?: return@open
                    val next = FlashlightIslandHold.onNativeStatus(on, op)
                    if (next != op) {
                        param.args[1] = next
                        DebugLog.i(TAG, if (on) "torch on while the island panel is open; repost skipped"
                            else "torch off while the island panel is open; close deferred")
                    }
                }
            }
        }
        DebugLog.hookRegistered(TAG, "flashlight island off-state retention")
    }
}
