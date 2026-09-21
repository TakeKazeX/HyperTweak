package com.takekazex.hypertweak.hook.rules.powerkeeper

import android.os.Bundle
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Remove power restrictions on GMS in com.miui.powerkeeper.
 * Based on HyperOS_FCM_Live by howard20181.
 * https://github.com/howard20181/HyperOS_FCM_Live
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
        val netdExecutorClass = "com.miui.powerkeeper.utils.NetdExecutor".toClassOrNull()
        if (netdExecutorClass == null) {
            DebugLog.d(hookerName, "NetdExecutor class unavailable on this PowerKeeper build")
        } else {
            // Legacy PowerKeeper builds routed this through NetdExecutor. Keep the fallback for
            // those builds, but its absence must not prevent installing the current GmsObserver hook.
            hookOptionalTarget("NetdExecutor.initGmsChain(String,int,String)") {
                val method = netdExecutorClass.getDeclaredMethod(
                    "initGmsChain",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                method.hook {
                    before { param ->
                        param.args[2] = "ACCEPT"
                    }
                }
            }
        }

        val gmsObserverClass = "com.miui.powerkeeper.utils.GmsObserver".toClassOrNull()
        if (gmsObserverClass == null) {
            DebugLog.d(hookerName, "GmsObserver class unavailable on this PowerKeeper build")
            return
        }

        // PowerKeeper 4.2.00 reports Google reachability to Greeze through this method. Its
        // argument is the network-limit state, so force false before the Binder update.
        hookOptionalTarget("GmsObserver.updateFrameworkGmsNetStatus(boolean)") {
            val method = gmsObserverClass.getDeclaredMethod(
                "updateFrameworkGmsNetStatus",
                Boolean::class.javaPrimitiveType
            )
            method.hook {
                before { param ->
                    param.args[0] = false
                }
            }
        }

        // Retain older builds' alarm/network/wakelock controls independently. PowerKeeper 4.2.00
        // removed these methods; a missing legacy target must not suppress the current network hook.
        listOf(
            "updateGmsAlarm",
            "updateGmsNetWork",
            "updateGoogleReletivesWakelock"
        ).forEach { methodName ->
            hookOptionalTarget("GmsObserver.$methodName(boolean)") {
                val method = gmsObserverClass.getDeclaredMethod(methodName, Boolean::class.javaPrimitiveType)
                method.hook {
                    before { param ->
                        param.args[0] = false
                    }
                }
            }
        }
    }

    private fun hookOptionalTarget(target: String, install: () -> Unit) {
        try {
            install()
            DebugLog.i(hookerName, "hook registered target=$target")
        } catch (t: NoSuchMethodException) {
            DebugLog.d(hookerName, "target unavailable on this PowerKeeper build: $target")
        } catch (t: Throwable) {
            DebugLog.e(hookerName, "failed to hook target=$target", t)
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
