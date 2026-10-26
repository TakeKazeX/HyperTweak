package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import com.takekazex.hypertweak.hook.base.HostAccess
import java.lang.reflect.Modifier

/**
 * Restores the AOSP package installer on HyperOS.
 *
 * `PackageManagerServiceImpl.updateDefaultPkgInstallerLocked()` picks the MIUI installer unless
 * `isCTS()` is true, and `assertValidApkAndInstaller()` / `hookChooseBestActivity()` gate the same
 * way. Forcing `isCTS()` true for the duration of those installer methods hands installs back to the
 * AOSP installer.
 *
 * `isCTS()` is static and also gates MIUI's install verification for every caller, so the override
 * is scoped to the calling thread with a re-entrant depth counter. A process-wide flag would
 * affect concurrent system_server calls.
 *
 * Ported from tehcneko's AOSP Package Installer (GPL-3.0).
 */
object AospPackageInstallerHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingAospPackageInstaller by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_PACKAGE_INSTALLER, false)
    }

    private const val TAG = "AospPackageInstaller"

    private const val PMS_IMPL = "com.android.server.pm.PackageManagerServiceImpl"

    /** Methods whose bodies should observe a CTS build. */
    private val SCOPED_METHODS = setOf(
        "switchPackageInstaller",
        "hookChooseBestActivity",
        "updateDefaultPkgInstallerLocked",
        "assertValidApkAndInstaller"
    )

    private var switchInstaller: Method? by generationState { null }
    private var ctsMethod: Method? by generationState { null }
    private var getService: Method? by generationState { null }

    private val forcedCtsDepth = ThreadLocal<Int>()

    private fun depth(): Int = forcedCtsDepth.get() ?: 0

    private fun isEnabled(): Boolean =
        settingAospPackageInstaller

    fun refreshSettings(): Boolean = HookFailurePolicy.open(TAG, "refresh installer", false) {
        val method = switchInstaller ?: return@open false
        val service = getService?.invoke(null) ?: return@open false
        // Before systemReady the vendor has not attached PMS yet. The original boot path will
        // select the installer; runtime publications retry until initialization has completed.
        if (HostAccess.read(service, "mPms") == null || HostAccess.read(service, "mPkgSettings") == null)
            return@open false
        val enabled = isEnabled()
        if (enabled) {
            val settings = HostAccess.read(service, "mPkgSettings") ?: return@open false
            val packages = HostAccess.read(settings, "mPackages") ?: return@open false
            val get = HostAccess.method(packages, "get", Any::class.java) ?: return@open false
            val pms = HostAccess.read(service, "mPms") ?: return@open false
            val lock = HostAccess.read(pms, "mLock") ?: return@open false
            val available = synchronized(lock) {
                listOf("com.android.packageinstaller", "com.google.android.packageinstaller")
                    .any { get.invoke(packages, it) != null }
            }
            if (!available) return@open false
        }
        method.invoke(service)
        val current = HostAccess.read(service, "mCurrentPackageInstaller") as? String
        val nativeDefault = ctsMethod?.invoke(null) == true
        val expected = if (enabled || nativeDefault) setOf("com.android.packageinstaller", "com.google.android.packageinstaller")
            else setOf("com.miui.packageinstaller")
        current in expected
    }

    override fun recoverHotReloadHosts() { refreshSettings() }

    override fun onPrepareHotReload() {
        forcedCtsDepth.remove()
    }

    override fun onHook() {
        val pmsImpl = PMS_IMPL.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, PMS_IMPL, "class not found")
            return
        }

        val isCts = pmsImpl.findMethodOrNull {
            name("isCTS")
            parameterTypes()
        }
        if (isCts == null) {
            DebugLog.hookSkipped(TAG, "$PMS_IMPL#isCTS()", "method not found")
            return
        }

        val switch = HostAccess.declaredMethod(pmsImpl, "switchPackageInstaller", accessible = true)
            ?.takeIf { it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) }
        val stub = "com.android.server.pm.PackageManagerServiceStub".toClassOrNull()
        val getter = stub?.let { HostAccess.declaredMethod(it, "get", accessible = true) }
            ?.takeIf { Modifier.isStatic(it.modifiers) }
        if (switch == null || getter == null || isCts.returnType != Boolean::class.javaPrimitiveType ||
            !Modifier.isStatic(isCts.modifiers)) {
            DebugLog.hookSkipped(TAG, "installer refresh contract", "missing or changed signature")
            return
        }
        switchInstaller = switch
        getService = getter
        ctsMethod = isCts
        hookForcedResult(isCts)

        val contracts = mapOf(
            "switchPackageInstaller" to ("void" to emptyList()),
            "updateDefaultPkgInstallerLocked" to ("boolean" to emptyList()),
            "assertValidApkAndInstaller" to ("void" to listOf("java.lang.String",
                "android.content.pm.SigningDetails", "int", "java.lang.String", "boolean", "int")),
            "hookChooseBestActivity" to ("android.content.pm.ResolveInfo" to listOf("android.content.Intent",
                "java.lang.String", "long", "java.util.List", "int", "android.content.pm.ResolveInfo")),
        )
        val scoped = pmsImpl.declaredMethods.filter { method ->
            contracts[method.name]?.let { (result, parameters) ->
                method.returnType.name == result && method.parameterTypes.map { it.name } == parameters
            } == true
        }
        if (scoped.size != SCOPED_METHODS.size || scoped.map { it.name }.toSet() != SCOPED_METHODS) {
            DebugLog.hookSkipped(TAG, "$PMS_IMPL installer selection", "incomplete target methods")
            return
        }
        scoped.forEach(::hookScope)
        refreshSettings()
    }

    /** `isCTS()` reports a CTS build while the calling thread sits inside a scoped method. */
    private fun hookForcedResult(method: Method) {
        runCatching {
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "isCTS", Unit) {
                        if (depth() > 0 && isEnabled()) {
                            param.result = true
                        }
                    }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, "$PMS_IMPL#isCTS()", it) }
    }

    private fun hookScope(method: Method) {
        runCatching {
            deoptimize(method)
            method.hook {
                before {
                    HookFailurePolicy.open(TAG, "enter:${method.name}", Unit) {
                        forcedCtsDepth.set(depth() + 1)
                    }
                }
                after {
                    HookFailurePolicy.open(TAG, "exit:${method.name}", Unit) {
                        val current = depth()
                        if (current > 1) {
                            forcedCtsDepth.set(current - 1)
                        } else if (current == 1) {
                            forcedCtsDepth.remove()
                        }
                    }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, "$PMS_IMPL#${method.name}", it) }
    }
}
