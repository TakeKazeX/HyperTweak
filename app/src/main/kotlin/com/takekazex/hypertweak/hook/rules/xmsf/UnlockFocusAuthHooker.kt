package com.takekazex.hypertweak.hook.rules.xmsf

import android.os.Bundle
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Unlocks the focus-notification whitelist signature verification (解锁焦点通知白名单签名验证) in
 * com.xiaomi.xmsf, mirroring HyperCeiler's `UnlockFoucsAuth` (reference copied to
 * reverse/cache/hyperceiler-ref/UnlockFoucsAuth.kt).
 *
 * Mechanism, verified on OS4.0.0.21.XPMCNXM (`reverse/cache/xmsf-os4-device/`): xmsf publishes the
 * "auth" system service (`com.xiaomi.xms.auth.IAuthService`, SERVICE_NAME "auth"; AIDL in base.apk,
 * implementation in the split_auth.apk) whose `AuthManager.innerAuth` flow fetches per-package
 * AuthData (package + scope + appType) from Xiaomi's server and verifies the caller app's
 * signatures (`AuthManager.a` → `PkgUtils.a`, comparing `PackageInfo.signatures` against the
 * server-provided list). Every failure funnels into `AuthSession`'s error dispatch (jadx name
 * `b(AuthError)`: public final, one arg, returns Bundle, sends `result_code = errorCode` through
 * `onAuthResult`); the success path is `h()` (public final, no args, returns the
 * `result_code = 0` / "Auth is successful" Bundle).
 *
 * The hook before-forces the error dispatch to return the success Bundle (with the AuthError's int
 * error-code field zeroed, mirroring upstream), so the signature check passes for every app. The
 * switch is read live in the callback; installing the hook needs an xmsf restart.
 *
 * Resolution is by reflection, not DexKit: on this build the two classes keep their names
 * (`com.xiaomi.xms.auth.AuthSession` / `AuthError` are referenced directly by the binder code and
 * survive in the dex string pool), only the method names are R8-renamed, so matching by the
 * signature shape (public final + parameter count + Bundle return, single match) is deterministic
 * and fail-closed. DexKit-by-apk-path would not work here anyway: the auth library ships in
 * split_auth.apk, which `DexKitBridge.create(sourceDir)` (base.apk only) never scans. The target
 * methods are deoptimized so ART cannot AOT-inline the error dispatch past the hook.
 */
object UnlockFocusAuthHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "UnlockFocusAuth"
    private const val AUTH_SESSION = "com.xiaomi.xms.auth.AuthSession"
    private const val AUTH_ERROR = "com.xiaomi.xms.auth.AuthError"

    private class Targets(
        val errorDispatch: Method,
        val success: Method,
        val errorCode: Field
    )

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)) {
            DebugLog.hookSkipped(TAG, "focus notification whitelist signature auth", "disabled")
            return
        }
        val targets = resolveTargets() ?: run {
            DebugLog.hookSkipped(TAG, AUTH_SESSION, "auth session targets not resolved")
            return
        }

        // Deoptimize so ART cannot AOT-inline the error dispatch past the hook.
        runCatching { deoptimize(targets.errorDispatch) }

        targets.errorDispatch.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "AuthSession error dispatch", Unit) {
                    if (!Preferences.getBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)) {
                        return@open
                    }
                    val error = param.args[0] ?: return@open
                    runCatching { targets.errorCode.set(error, 0) }
                    param.result = targets.success.invoke(param.thisObject)
                }
            }
        }
        DebugLog.i(TAG, "HOOK_OK AuthSession error dispatch forced to success")
    }

    private fun resolveTargets(): Targets? {
        val session = runCatching {
            Class.forName(AUTH_SESSION, false, classLoader)
        }.getOrNull() ?: return null
        val error = runCatching {
            Class.forName(AUTH_ERROR, false, classLoader)
        }.getOrNull() ?: return null

        val errorDispatch = session.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                it.parameterCount == 1 && it.returnType == Bundle::class.java &&
                it.parameterTypes[0] == error
        }.singleOrNull() ?: return null

        val success = session.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                it.parameterCount == 0 && it.returnType == Bundle::class.java
        }.singleOrNull() ?: return null

        val errorCode = error.declaredFields.filter {
            it.type == Int::class.javaPrimitiveType
        }.singleOrNull() ?: return null
        errorCode.isAccessible = true

        // The static helper `c(AuthSession, int, String)` builds an AuthError and calls the error
        // dispatch; if ART inlined the dispatch into it, calls through this path would bypass the
        // hook. Deoptimize it too when present.
        session.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 3 &&
                it.parameterTypes[0] == session &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.returnType == Bundle::class.java
        }.singleOrNull()?.let { helper ->
            runCatching { deoptimize(helper) }
        }

        return Targets(errorDispatch, success, errorCode)
    }
}
