package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Keeps island Lottie icons (timer hourglass, stopwatch, alarm…) playing after a collapse.
 *
 * The coordinator's `AnimEventBridge` listener pauses or resets every island-key Lottie scene on
 * `onAnimTrulyStarted` and replays them on the next `onAnimTrulyFinished`/`onAnimCancelled`, but
 * only while its `lottiePausedByAnim` flag is set. A second transition that starts before the
 * first one reports completion, with no pauses of its own pending, clears that flag without
 * resuming. The keys stay in `pausedLottieKeys`, so the collapsed big or small island keeps its
 * icon stopped on frame 0. While enabled, the flag is kept set as long as paused keys are
 * outstanding, so the host's own resume runs when the bridge's last animation ends.
 *
 * No state is kept here: everything lives in the host listener. Hot reload and disable need no
 * restoration, and keys that were orphaned before enabling are resumed after the next transition.
 */
class IslandLottieResumeHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIslandLottieResume by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_LOTTIE_RESUME, false)
    }

    @Volatile private var retiring = false

    override fun onHook() {
        val coordinator = COORDINATOR.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, COORDINATOR, "not found on host")
            return
        }
        val bridgeListener = classLoader.loadClass("miui.systemui.dynamicisland.anim.ui.viewbinder.AnimEventBridge\$Listener")
        val listener = classLoader.loadClass("$COORDINATOR\$initAnimEventBridge\$1")
        check(bridgeListener.isAssignableFrom(listener) && listener.enclosingClass == coordinator)
        val instance = listener.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        // The flag is the listener's only boolean; the pending map and paused keys are its collections.
        val paused = instance.single { it.type == Boolean::class.javaPrimitiveType }
            .apply { check(name == "lottiePausedByAnim") }
            .accessible()
        val keys = HostAccess.requireDeclaredField(listener, "pausedLottieKeys", accessible = true)
            .apply { check(type == Set::class.java) }
            .accessible()
        HostAccess.requireDeclaredField(listener, "pendingLottiePauseMap", accessible = true)
            .apply { check(type == Map::class.java) }
        val started = classLoader.loadClass("miui.systemui.dynamicisland.anim.model.AnimEvent\$Started")
        val onStarted = HostAccess.requireDeclaredMethod(listener, "onAnimTrulyStarted", started, accessible = false)
            .apply { check(returnType == Void.TYPE) }
        // Both resume paths must stay the consumers of the flag this hook preserves.
        listOf("onAnimTrulyFinished" to "Finished", "onAnimCancelled" to "Cancelled").forEach { (name, event) ->
            val type = classLoader.loadClass("miui.systemui.dynamicisland.anim.model.AnimEvent\$$event")
            check(HostAccess.requireDeclaredMethod(listener, name, type, accessible = false).returnType == Void.TYPE)
        }

        deoptimize(onStarted)
        onStarted.hook {
            after { param ->
                if (retiring || !settingIslandLottieResume) return@after
                HookFailurePolicy.open(TAG, "keep island Lottie resume pending", Unit) {
                    val owner = param.thisObject ?: return@open
                    if (paused.getBoolean(owner)) return@open
                    val outstanding = (keys.get(owner) as? Set<*>)?.size ?: 0
                    if (outstanding == 0) return@open
                    paused.setBoolean(owner, true)
                    DebugLog.d(TAG, "resume kept pending for $outstanding island keys")
                }
            }
        }
        DebugLog.hookRegistered(TAG, "island Lottie resume across overlapping transitions")
    }

    override fun onPrepareHotReload() {
        retiring = true
    }

    private fun Field.accessible(): Field = apply { isAccessible = true }

    private companion object {
        const val TAG = "IslandLottieResume"
        const val COORDINATOR = "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator"
    }
}
