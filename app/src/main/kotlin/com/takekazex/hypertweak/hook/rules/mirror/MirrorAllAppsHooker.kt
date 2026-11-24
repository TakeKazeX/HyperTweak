package com.takekazex.hypertweak.hook.rules.mirror

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Mirror half of "send all apps": turns on Mirror's own "all apps" relay switch.
 *
 * With the switch on, Mirror's allow-list predicates accept every package, so the provider answers
 * `isSupportSendApp` for any app and outgoing relay messages carry `supportSubScreen` without hiding
 * the icon. Device capability, connection, handoff and per-app handoff support stay native. The
 * switch is a static field read through a trivial getter, so it is set directly instead of hooking
 * an inlinable read; disabling or retiring re-runs the host's initializer to restore its own value.
 */
internal object MirrorAllAppsHooker : StaticHooker() {
    private const val TAG = "MirrorAllApps"
    const val PACKAGE = "com.xiaomi.mirror"

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_MILINK_UNLOCK_SEND_APP, false)
    }

    private class Switch {
        var flag: Field? = null
        var init: Method? = null
        var forced = false

        fun force() {
            flag?.setBoolean(null, true)
            forced = true
        }

        /** The host initializer re-reads every Mirror config value from its own preferences. */
        fun restore() {
            if (!forced) return
            forced = false
            init?.invoke(null)
        }
    }

    private val switch = generationMainResource(Switch()) { state ->
        HookFailurePolicy.open(TAG, "restore all-apps switch", Unit) { state.restore() }
    }

    override fun onHook() {
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            DexRuntime.resolve(
                feature = MirrorAllAppsContract,
                apkPath = apk,
                cacheDir = dexCacheDirectory,
                compute = { MirrorAllAppsContract.resolve(DexRuntime.operands(apk)) },
                decode = { values, _ -> DexContractMembers(values, classLoader) },
            )
        }) { members ->
            val flag = members.field("flag").apply { isAccessible = true }
            val init = members.method("init").apply { isAccessible = true }
            check(flag.type == Boolean::class.javaPrimitiveType) { "all-apps switch is not a boolean" }
            init.hook("mirror_all_apps_init") {
                after {
                    if (!enabled || generationRetired) return@after
                    HookFailurePolicy.open(TAG, "keep all-apps switch", Unit) { switch.force() }
                }
            }
            switch.flag = flag
            switch.init = init
            DebugLog.hookRegistered(TAG, "all-apps relay switch ready")
            refreshSettings()
        }
    }

    fun refreshSettings(): Boolean = HostAccess.onMain {
        if (generationRetired || switch.flag == null) return@onMain true
        HookFailurePolicy.open(TAG, "apply all-apps switch", false) {
            if (enabled) switch.force() else switch.restore()
            DebugLog.d(TAG) { "all-apps switch enabled=$enabled value=${switch.flag?.getBoolean(null)}" }
            true
        }
    }
}
