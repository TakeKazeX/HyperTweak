package com.takekazex.hypertweak.hook.rules.milink

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Accepts universal clipboard messages from trusted peers whose reported region differs from this
 * device's. PC and Mac peers already skip the check natively; trust, connection and clipboard-sharing
 * switches stay native. Only the receiver's region comparison is answered.
 */
internal object MiLinkClipboardRegionHooker : StaticHooker() {
    private const val TAG = "MiLinkClipboardRegion"
    /** The process that runs `UniversalClipboardService` on the verified build. */
    const val PROCESS = "com.milink.crossdeviceservice"

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_MILINK_IGNORE_CLIPBOARD_REGION, false)
    }

    override val installPolicy = InstallPolicy.WHEN_ENABLED
    override fun isFeatureEnabled() = enabled

    override fun onHook() {
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            DexRuntime.resolve(
                feature = MiLinkClipboardRegionContract,
                apkPath = apk,
                cacheDir = dexCacheDirectory,
                compute = { MiLinkClipboardRegionContract.resolve(DexRuntime.operands(apk)) },
                decode = { values, _ -> DexContractMembers(values, classLoader) },
            )
        }) { members ->
            val receiver = members.method("receiver")
            val region = members.method("check")
            // The comparison is small enough for ART to inline into its only caller.
            check(deoptimize(receiver)) { "Cannot deoptimize clipboard receiver" }
            region.hook("milink_clipboard_region") {
                intercept { chain -> if (enabled && !generationRetired) true else chain.proceed() }
            }
            DebugLog.hookRegistered(TAG, "clipboard region check ready")
        }
    }
}
