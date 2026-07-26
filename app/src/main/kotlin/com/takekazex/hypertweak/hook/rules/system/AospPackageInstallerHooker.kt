package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Restores the AOSP package installer on HyperOS.
 *
 * `PackageManagerServiceImpl.updateDefaultPkgInstallerLocked()` picks the MIUI installer unless
 * `isCTS()` is true, and `assertValidApkAndInstaller()` / `hookChooseBestActivity()` gate the same
 * way. Forcing `isCTS()` true for the duration of those three methods hands installs back to the
 * AOSP installer.
 *
 * `isCTS()` is static and also gates MIUI's install verification for every caller, so the override
 * is scoped to the calling thread with a re-entrant depth counter. A process-wide flag — what the
 * upstream implementations use — would let a concurrent install on another system_server thread
 * skip signature and installer validation.
 *
 * Ported from tehcneko's AOSP Package Installer (GPL-3.0).
 */
object AospPackageInstallerHooker : StaticHooker() {
    private const val TAG = "AospPackageInstaller"

    private const val PMS_IMPL = "com.android.server.pm.PackageManagerServiceImpl"

    /** Methods whose bodies should observe a CTS build. */
    private val SCOPED_METHODS = setOf(
        "hookChooseBestActivity",
        "updateDefaultPkgInstallerLocked",
        "assertValidApkAndInstaller"
    )

    private val forcedCtsDepth = ThreadLocal<Int>()

    private fun depth(): Int = forcedCtsDepth.get() ?: 0

    private fun isEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, false)

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

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

        hookForcedResult(isCts)

        val scoped = pmsImpl.declaredMethods.filter { it.name in SCOPED_METHODS }
        if (scoped.isEmpty()) {
            DebugLog.hookSkipped(TAG, "$PMS_IMPL installer selection", "no target methods found")
            return
        }
        scoped.forEach(::hookScope)
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
                        // Only enter the scope when the feature is on, but always leave it below, so
                        // toggling the switch mid-call cannot strand the counter above zero.
                        if (isEnabled()) {
                            forcedCtsDepth.set(depth() + 1)
                        }
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
