package com.takekazex.hypertweak.hook.rules.powerkeeper

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.os.Bundle
import java.lang.reflect.Method
import java.util.WeakHashMap
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Remove power restrictions on GMS in com.miui.powerkeeper.
 * Based on HyperOS_FCM_Live by howard20181.
 * https://github.com/howard20181/HyperOS_FCM_Live
 */
object FcmLivePowerKeeperHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingFcmLiveEnabled by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, false) }

    override val hookerName = "FcmLivePowerKeeper"

    private const val GMS_PACKAGE_NAME = "com.google.android.gms"

    @Volatile private var retired = false
    init { generationCleanup { retired = true; refreshSettings() } }
    private val networkRequests = generationMainResource(WeakHashMap<Any, Boolean>()) { it.clear() }
    private var networkUpdate: Method? by generationState { null }

    override fun onPrepareHotReload() {
        retired = true
        refreshSettings()
    }

    fun refreshSettings(): Boolean = HostAccess.onMain {
        val method = networkUpdate ?: return@onMain true
        var applied = true
        synchronized(networkRequests) { networkRequests.entries.map { it.key to it.value } }.forEach { (observer, requested) ->
            runCatching { method.invoke(observer, requested) }.onFailure {
                applied = false
                DebugLog.w(hookerName, "network policy refresh failed", it)
            }
        }
        applied
    }

    override fun onHook() {
        retired = false
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
                val method = HostAccess.requireDeclaredMethod(netdExecutorClass,
                    "initGmsChain",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java, accessible = false)
                method.hook {
                    before { param ->
                        if (retired || !settingFcmLiveEnabled) return@before
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
            val method = HostAccess.requireDeclaredMethod(gmsObserverClass,
                "updateFrameworkGmsNetStatus",
                Boolean::class.javaPrimitiveType, accessible = false)
            networkUpdate = method
            method.hook {
                before { param ->
                    val requested = param.args.firstOrNull() as? Boolean ?: return@before
                    synchronized(networkRequests) { networkRequests[param.thisObject] = requested }
                    if (!retired && settingFcmLiveEnabled) param.args[0] = false
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
                val method = HostAccess.requireDeclaredMethod(gmsObserverClass, methodName, Boolean::class.javaPrimitiveType, accessible = false)
                method.hook {
                    before { param ->
                        if (retired || !settingFcmLiveEnabled) return@before
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
            val getDozeWhiteListAppsMethod = HostAccess.requireDeclaredMethod(clazz, "getDozeWhiteListApps", Bundle::class.java, accessible = false)

            getDozeWhiteListAppsMethod.hook {
                after { param ->
                    if (retired || !settingFcmLiveEnabled) return@after
                    param.result = com.takekazex.hypertweak.hook.base.CollectionOverrides.stringList(
                        param.result as? Collection<*>, GMS_PACKAGE_NAME
                    )
                }
            }

            DebugLog.i(hookerName, "GlobalFeatureConfigureHelper hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook GlobalFeatureConfigureHelper", t)
        }
    }
}
