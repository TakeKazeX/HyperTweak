package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.base.HostAccess

import com.takekazex.hypertweak.settings.contract.AodSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Field

/** Keeps a visible lockscreen's wallpaper scale for the native lockscreen-to-full-AOD animation. */
object AodWallpaperHandoffHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingAodFullscreen by hookSetting(false) { Preferences.getBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, false) }

    private const val TAG = "AodWallpaperHandoff"
    private const val SLEEP_CALLBACK = "com.android.systemui.keyguard.KeyguardService\$2"
    private const val DOZE_HOST = "com.android.keyguard.injector.DozeServiceHostInjector"

    private class SleepCall(var host: Any? = null)
    private val calls = ThreadLocal<MutableList<SleepCall>>()
    private var fullAodField: Field? by generationState { null }
    private var screenOffAnimationField: Field? by generationState { null }
    private var keyguardShowingField: Field? by generationState { null }
    private var keyguardOccludedField: Field? by generationState { null }

    override fun onHook() {
        if (!PlatformLevel.isOs4 || !isMainProcess) return

        val sleep = SLEEP_CALLBACK.toClassOrNull()?.declaredMethods?.singleOrNull {
            it.name == "onStartedGoingToSleep" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        val host = DOZE_HOST.toClassOrNull()
        val update = host?.declaredMethods?.singleOrNull {
            it.name == "updateScreenOffNeedLinkageAnimState" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        fullAodField = host?.field("mFullAodEnable")
        screenOffAnimationField = host?.field("mScreenOffNeedFullAodAnim")
        keyguardShowingField = host?.field("mKeyguardShowing")
        keyguardOccludedField = host?.field("mKeyguardOccluded")
        if (sleep == null || update == null || fullAodField == null ||
            screenOffAnimationField == null || keyguardShowingField == null ||
            keyguardOccludedField == null) {
            DebugLog.hookSkipped(TAG, "KeyguardService/DozeServiceHostInjector", "unsupported signature")
            return
        }

        deoptimize(sleep)
        deoptimize(update)
        sleep.hook {
            before {
                if (!settingAodFullscreen) return@before
                val stack = calls.get() ?: mutableListOf<SleepCall>().also(calls::set)
                stack.add(SleepCall())
            }
            after {
                val stack = calls.get() ?: return@after
                val call = stack.removeLastOrNull() ?: return@after
                call.host?.let { target ->
                    runCatching { screenOffAnimationField?.setBoolean(target, true) }
                        .onFailure { DebugLog.w(TAG, "could not restore full-AOD animation state", it) }
                }
                if (stack.isEmpty()) calls.remove()
            }
        }
        update.hook {
            after { param ->
                val call = calls.get()?.lastOrNull() ?: return@after
                if (call.host != null) return@after
                val target = param.thisObject
                runCatching {
                    if (fullAodField?.getBoolean(target) != true ||
                        screenOffAnimationField?.getBoolean(target) != true ||
                        keyguardShowingField?.getBoolean(target) != true ||
                        keyguardOccludedField?.getBoolean(target) == true) return@runCatching
                    // KeyguardService checks this flag immediately after this callback. The
                    // launcher cancel resets the wallpaper to 1.0 without animation, while
                    // KeyguardPanelViewController then starts its own scale at 1.05.
                    screenOffAnimationField?.setBoolean(target, false)
                    call.host = target
                    DebugLog.i(TAG, "kept lockscreen wallpaper scale for full-AOD handoff")
                }.onFailure { DebugLog.w(TAG, "wallpaper handoff check failed", it) }
            }
        }
        DebugLog.hookRegistered(TAG, "lockscreen-to-full-AOD wallpaper handoff")
    }

    private fun Class<*>.field(name: String): Field? = runCatching {
        HostAccess.requireDeclaredField(this, name, accessible = true)
    }.getOrNull()
}
