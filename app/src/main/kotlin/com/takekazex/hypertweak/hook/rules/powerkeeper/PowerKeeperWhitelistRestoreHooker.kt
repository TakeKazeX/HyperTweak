package com.takekazex.hypertweak.hook.rules.powerkeeper

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

/**
 * Stops PowerKeeper from putting apps back on the battery-optimization (deviceidle user) allowlist.
 *
 * PowerKeeper 4.2.00 re-adds its built-in `doze_always_white_apps` (WeChat, QQ) and the desk clock
 * on every rule pass, and adds apps whose cloud or user rule allows them. Every add and remove it
 * makes goes through the framework `IDeviceIdleController` Binder proxy, so both are observed there,
 * inside the PowerKeeper process, independent of PowerKeeper obfuscation. Settings writes from its
 * own process and is unaffected.
 *
 * PowerKeeper may only restore what it removed itself: a removal of a package that is on the user
 * allowlist at that moment is recorded, and one later add of that package is let through. Every
 * other add is dropped, except GMS while FCM Live asks PowerKeeper to allowlist it. Removals are
 * recorded while the switch is off as well, so enabling it mid-cycle keeps pending restores.
 */
object PowerKeeperWhitelistRestoreHooker : StaticHooker() {
    private val settingBlockRestore by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_POWERKEEPER_BLOCK_WHITELIST_RESTORE, false)
    }
    private val settingFcmLiveEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, false)
    }

    override val hookerName = "PowerKeeperWhitelistRestore"

    private const val PROXY_CLASS = "android.os.IDeviceIdleController\$Stub\$Proxy"
    private const val GMS_PACKAGE_NAME = "com.google.android.gms"

    @Volatile private var retired = false
    init { generationCleanup { retired = true } }

    private val ledger = AllowlistRestoreLedger()
    private var userWhitelistGetter: Method? by generationState { null }

    override fun onPrepareHotReload() {
        retired = true
    }

    // Only boot-classpath types cross generations.
    override fun saveHotReloadState(): Any? =
        ledger.snapshot().takeIf { it.isNotEmpty() }

    // A build that saved no state forgets pending restores: PowerKeeper's next restore of an app it
    // removed before the reload is then dropped while the switch is on.
    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? Set<*> ?: return
        ledger.restore(saved)
    }

    override fun onHook() {
        retired = false
        val proxy = PROXY_CLASS.toClassOrNull()
        if (proxy == null) {
            DebugLog.hookSkipped(hookerName, PROXY_CLASS, "class not found")
            return
        }
        val getter = try {
            HostAccess.requireDeclaredMethod(proxy, "getUserPowerWhitelist", accessible = false)
                .also { require(it.returnType == Array<String>::class.java) { "unexpected return type ${it.returnType}" } }
        } catch (t: Throwable) {
            // Without the allowlist query a removal cannot be told apart from a no-op, so the
            // restore whitelist would be guesswork; leave PowerKeeper untouched.
            DebugLog.hookSkipped(hookerName, "$PROXY_CLASS.getUserPowerWhitelist()", t.toString())
            return
        }
        userWhitelistGetter = getter

        hookTarget("$PROXY_CLASS.removePowerSaveWhitelistApp(String)") {
            val method = HostAccess.requireDeclaredMethod(proxy, "removePowerSaveWhitelistApp", String::class.java, accessible = false)
            require(method.returnType == Void.TYPE) { "unexpected return type ${method.returnType}" }
            method.hook {
                before { param ->
                    if (retired) return@before
                    val pkg = param.args.firstOrNull() as? String ?: return@before
                    recordRemoval(param.thisObject, pkg)
                }
            }
        }
        // int addPowerSaveWhitelistApps(List<String>): returns the number of packages added.
        hookTarget("$PROXY_CLASS.addPowerSaveWhitelistApps(List)") {
            val method = HostAccess.requireDeclaredMethod(proxy, "addPowerSaveWhitelistApps", List::class.java, accessible = false)
            require(method.returnType == Int::class.javaPrimitiveType) { "unexpected return type ${method.returnType}" }
            method.hook {
                before { param ->
                    if (retired) return@before
                    val requested = (param.args.firstOrNull() as? List<*>)?.filterIsInstance<String>() ?: return@before
                    val allowed = filterAdds(requested)
                    when {
                        allowed == null -> Unit
                        allowed.isEmpty() -> param.result = 0
                        allowed.size != requested.size -> param.args[0] = ArrayList(allowed)
                    }
                }
            }
        }
        hookTarget("$PROXY_CLASS.addPowerSaveWhitelistApp(String)") {
            val method = HostAccess.requireDeclaredMethod(proxy, "addPowerSaveWhitelistApp", String::class.java, accessible = false)
            require(method.returnType == Void.TYPE) { "unexpected return type ${method.returnType}" }
            method.hook {
                before { param ->
                    if (retired) return@before
                    val pkg = param.args.firstOrNull() as? String ?: return@before
                    if (filterAdds(listOf(pkg))?.isEmpty() == true) param.result = null
                }
            }
        }
    }

    private fun recordRemoval(proxy: Any?, pkg: String) {
        val allowlisted = try {
            @Suppress("UNCHECKED_CAST")
            (userWhitelistGetter?.invoke(proxy) as? Array<String>)?.contains(pkg)
        } catch (t: Throwable) {
            DebugLog.w(hookerName, "user allowlist query failed; $pkg will not be restorable", t)
            null
        }
        if (allowlisted != true) return
        ledger.recordRemoval(pkg)
        DebugLog.d(hookerName) { "PowerKeeper removed allowlisted $pkg; one restore allowed" }
    }

    /**
     * Returns the packages PowerKeeper may add, or null when nothing is filtered. Every requested
     * package consumes its restore record, whether or not the switch is on.
     */
    private fun filterAdds(requested: List<String>): List<String>? {
        val blocking = settingBlockRestore
        val fcmLive = settingFcmLiveEnabled
        val allowed = ledger.filterAdds(requested, blocking) { pkg -> fcmLive && pkg == GMS_PACKAGE_NAME }
        if (!blocking) return null
        if (allowed.size != requested.size) {
            DebugLog.d(hookerName) { "blocked PowerKeeper allowlist add ${requested - allowed.toSet()} allowed=$allowed" }
        }
        return allowed
    }

    private fun hookTarget(target: String, install: () -> Unit) {
        try {
            install()
            DebugLog.d(hookerName, "hook registered target=$target")
        } catch (t: NoSuchMethodException) {
            DebugLog.hookSkipped(hookerName, target, "method not found")
        } catch (t: Throwable) {
            DebugLog.e(hookerName, "failed to hook target=$target", t)
        }
    }
}
