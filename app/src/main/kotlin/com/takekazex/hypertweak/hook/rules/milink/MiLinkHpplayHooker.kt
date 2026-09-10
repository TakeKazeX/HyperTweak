package com.takekazex.hypertweak.hook.rules.milink

import android.content.Context
import android.preference.PreferenceManager
import androidx.core.content.edit
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Keeps MiLink's HPPlay/LeBo integration from creating its external SD-card files.
 *
 * The stock SDK already has an on/off preference. Writing that preference before
 * `ContextPath.initDirs` runs prevents only the optional external directories;
 * the internal service directories and the cast service remain native. The
 * public `enableSDCard` boundary is also forced false so a later SDK call cannot
 * turn the external path back on.
 */
object MiLinkHpplayHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "MiLinkHpplay"
    private const val PACKAGE = "com.milink.service"
    private const val CONTEXT_PATH = "com.hpplay.common.utils.ContextPath"
    private const val LELINK_SOURCE_SDK = "com.hpplay.sdk.source.api.LelinkSourceSDK"
    private const val SD_CARD_PREF = "key_sdcard_dir_enable"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_MILINK_BLOCK_HPPLAY_FILES, false)) {
            DebugLog.hookSkipped(TAG, "MiLink HPPlay external files", "disabled")
            return
        }

        var installed = 0
        val contextPathClass = CONTEXT_PATH.toClassOrNull()
        val initDirs = contextPathClass?.declaredMethods
            ?.filter(::isInitDirs)
            .orEmpty()
        if (initDirs.isEmpty()) {
            DebugLog.hookSkipped(TAG, "$CONTEXT_PATH#initDirs", "expected signature not found")
        }
        initDirs.forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook("milink_hpplay_init_dirs") {
                    before { param ->
                        HookFailurePolicy.open(TAG, "initDirs.before", Unit) {
                            val context = param.args.getOrNull(0) as? Context ?: return@open
                            @Suppress("DEPRECATION")
                            PreferenceManager.getDefaultSharedPreferences(context).edit {
                                putBoolean(SD_CARD_PREF, false)
                            }
                        }
                    }
                }
                installed++
            }.onFailure { t ->
                DebugLog.hookFailed(TAG, method.toGenericString(), t)
            }
        }

        val sdkClass = LELINK_SOURCE_SDK.toClassOrNull()
        val enableSdCard = sdkClass?.declaredMethods
            ?.filter(::isEnableSdCard)
            .orEmpty()
        if (enableSdCard.isEmpty()) {
            DebugLog.hookSkipped(TAG, "$LELINK_SOURCE_SDK#enableSDCard", "expected signature not found")
        }
        enableSdCard.forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook("milink_hpplay_enable_sd_card") {
                    before { param ->
                        HookFailurePolicy.open(TAG, "enableSDCard.before", Unit) {
                            if (param.args.isNotEmpty()) param.args[0] = false
                        }
                    }
                }
                installed++
            }.onFailure { t ->
                DebugLog.hookFailed(TAG, method.toGenericString(), t)
            }
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "MiLink HPPlay external files", "no boundary was hooked")
        } else {
            DebugLog.i(TAG, "HPPlay external-file creation disabled boundaries=$installed")
        }
    }

    private fun isInitDirs(method: Method): Boolean {
        val parameters = method.parameterTypes
        return method.name == "initDirs" &&
            parameters.size == 3 &&
            Context::class.java.isAssignableFrom(parameters[0]) &&
            parameters[1] == Int::class.javaPrimitiveType &&
            parameters[2] == String::class.java &&
            method.returnType == Void.TYPE
    }

    private fun isEnableSdCard(method: Method): Boolean {
        val parameters = method.parameterTypes
        return method.name == "enableSDCard" &&
            parameters.size == 1 &&
            parameters[0] == Boolean::class.javaPrimitiveType
    }
}
