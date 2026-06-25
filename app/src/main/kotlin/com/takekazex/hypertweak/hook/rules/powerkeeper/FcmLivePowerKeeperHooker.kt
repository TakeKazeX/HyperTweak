package com.takekazex.hypertweak.hook.rules.powerkeeper

import android.os.Bundle
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Remove power restrictions on GMS in com.miui.powerkeeper.
 * Based on HyperOS_FCM_Live by howard20181.
 */
object FcmLivePowerKeeperHooker : StaticHooker() {
    override val hookerName = "FcmLivePowerKeeper"

    private const val GMS_PACKAGE_NAME = "com.google.android.gms"

    override fun onInit() {
        if (!Preferences.getBoolean(Preferences.KEY_FCM_LIVE_ENABLED, false)) {
            DebugLog.d(hookerName, "FCM Live disabled by user preference")
            return
        }

        hookGmsObserver()
        hookGlobalFeatureConfigureHelper()
    }

    private fun hookGmsObserver() {
        runCatching {
            val netdExecutorClass = "com.miui.powerkeeper.utils.NetdExecutor".toClassOrNull() ?: return@runCatching
            val initGmsChainMethod = netdExecutorClass.getDeclaredMethod(
                "initGmsChain",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )

            initGmsChainMethod.hook {
                before { param ->
                    param.args[2] = "ACCEPT"
                }
            }

            val gmsObserverClass = "com.miui.powerkeeper.utils.GmsObserver".toClassOrNull() ?: return@runCatching

            // Hook updateGmsAlarm, updateGmsNetWork, updateGoogleReletivesWakelock
            listOf(
                "updateGmsAlarm",
                "updateGmsNetWork",
                "updateGoogleReletivesWakelock"
            ).forEach { methodName ->
                runCatching {
                    val method = gmsObserverClass.getDeclaredMethod(methodName, Boolean::class.javaPrimitiveType)
                    method.hook {
                        before { param ->
                            param.args[0] = false
                        }
                    }
                }
            }

            DebugLog.i(hookerName, "GmsObserver hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook GmsObserver", t)
        }
    }

    private fun hookGlobalFeatureConfigureHelper() {
        runCatching {
            val clazz = "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper".toClassOrNull() ?: return@runCatching
            val getDozeWhiteListAppsMethod = clazz.getDeclaredMethod("getDozeWhiteListApps", Bundle::class.java)

            getDozeWhiteListAppsMethod.hook {
                after { param ->
                    @Suppress("UNCHECKED_CAST")
                    val whiteList = param.result as? MutableList<String>
                    if (whiteList != null && !whiteList.contains(GMS_PACKAGE_NAME)) {
                        whiteList.add(GMS_PACKAGE_NAME)
                    }
                }
            }

            DebugLog.i(hookerName, "GlobalFeatureConfigureHelper hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook GlobalFeatureConfigureHelper", t)
        }
    }
}
