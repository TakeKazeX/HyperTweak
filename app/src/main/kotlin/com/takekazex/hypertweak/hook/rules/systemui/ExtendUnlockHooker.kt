package com.takekazex.hypertweak.hook.rules.systemui

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Repairs Extend Unlock (formerly Smart Lock) on HyperOS.
 *
 * Xiaomi does not modify `KeyguardUpdateMonitor.getUserHasTrust(int)` — on the current baseline it
 * is AOSP's formula verbatim, `!isSimPinSecure() && mUserHasTrust.get(id) && isUnlockingWithBiometricAllowed(true)`.
 * What breaks is the `mUserHasTrust` cache itself, which goes stale so a trust grant never reaches
 * the keyguard.
 *
 * This patches only that failure: when the original returns false and neither guard explains it,
 * the trust state is re-derived from `TrustManagerService` through `KeyguardManager`. The other two
 * causes of a false result are left alone, so a pending SIM PIN can never be overridden into a
 * trusted state.
 *
 * Unlike HyperTrust, which this is ported from (GPL-3.0), the result is not written back into
 * `mUserHasTrust`. The only other reader of that field is one assistant-visibility check, and a
 * `SparseBooleanArray` write from a getter reachable off the main thread is a race AOSP explicitly
 * asserts against.
 *
 * `getUserHasTrust` is recomputed in bursts from the fingerprint listening state, so the two binder
 * round-trips are cached per user for [TRUST_CACHE_TTL_MS] and invalidated eagerly whenever
 * `onTrustChanged` fires.
 */
object ExtendUnlockHooker : StaticHooker() {
    private const val TAG = "ExtendUnlock"

    private const val KEYGUARD_UPDATE_MONITOR = "com.android.keyguard.KeyguardUpdateMonitor"
    private const val TRUST_CACHE_TTL_MS = 200L

    private const val GMS_PACKAGE = "com.google.android.gms"

    /** The Extend Unlock agent. Not the unrelated `personalsafety` locking agent. */
    private const val GMS_TRUST_AGENT = "com.google.android.gms.auth.trustagent.GoogleTrustAgent"

    private class CachedTrust(val uptimeMs: Long, val trusted: Boolean)

    @Volatile
    private var enabled = false

    private val trustCache = ConcurrentHashMap<Int, CachedTrust>()

    private var contextField: Field? = null
    private var isSimPinSecureMethod: Method? = null
    private var isUnlockingWithBiometricAllowedMethod: Method? = null
    private var isDeviceSecureMethod: Method? = null
    private var isDeviceLockedMethod: Method? = null

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    /**
     * Puts GMS's Extend Unlock agent into the enabled trust agent list, which is what actually makes
     * the feature available.
     *
     * HyperOS ships no Trust agents settings screen at all, so there is no way for the user to
     * enable it — `dumpsys trust` lists only Play Services' unrelated locking agent, and GMS reports
     * Extend Unlock as unavailable. The keyguard-side fix above is useless until this is done.
     *
     * Called from SystemUI, which runs as uid system and may write lock settings. The list is
     * persistent system state, so the entry is removed again when the setting is turned off.
     */
    fun syncTrustAgent(context: Context) {
        HookFailurePolicy.open(TAG, "syncTrustAgent", Unit) {
            val wanted = Preferences.getBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, false)
            val lockPatternUtils = "com.android.internal.widget.LockPatternUtils".toClassOrNull()
                ?: return@open
            val instance = lockPatternUtils.getConstructor(Context::class.java).newInstance(context)

            val userId = runCatching {
                android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as? Int
            }.getOrNull() ?: 0

            @Suppress("UNCHECKED_CAST")
            val enabled = lockPatternUtils
                .getMethod("getEnabledTrustAgents", Int::class.javaPrimitiveType)
                .invoke(instance, userId) as? List<ComponentName> ?: emptyList()

            val agent = ComponentName(GMS_PACKAGE, GMS_TRUST_AGENT)
            val present = enabled.any { it == agent }
            if (present == wanted) return@open

            val next = if (wanted) enabled + agent else enabled.filterNot { it == agent }
            lockPatternUtils
                .getMethod("setEnabledTrustAgents", Collection::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, next, userId)
            DebugLog.i(TAG, "${if (wanted) "enabled" else "disabled"} trust agent $agent for user $userId")
        }
    }

    override fun onPrepareHotReload() {
        enabled = false
        trustCache.clear()
        contextField = null
        isSimPinSecureMethod = null
        isUnlockingWithBiometricAllowedMethod = null
        isDeviceSecureMethod = null
        isDeviceLockedMethod = null
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, false)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "keyguard trust state", "disabled")
            return
        }

        val monitor = KEYGUARD_UPDATE_MONITOR.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, KEYGUARD_UPDATE_MONITOR, "class not found")
            return
        }

        contextField = runCatching {
            monitor.getDeclaredField("mContext").apply { isAccessible = true }
        }.getOrNull()
        isSimPinSecureMethod = CompatibleMethodResolver.find(
            monitor, "isSimPinSecure", returnType = Boolean::class.javaPrimitiveType
        )
        isUnlockingWithBiometricAllowedMethod = CompatibleMethodResolver.find(
            monitor,
            "isUnlockingWithBiometricAllowed",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(Boolean::class.javaPrimitiveType!!)
        )
        if (contextField == null || isSimPinSecureMethod == null ||
            isUnlockingWithBiometricAllowedMethod == null
        ) {
            DebugLog.hookSkipped(
                TAG,
                "$KEYGUARD_UPDATE_MONITOR guards",
                "mContext/isSimPinSecure/isUnlockingWithBiometricAllowed not resolved"
            )
            return
        }

        hookGetUserHasTrust(monitor)
        hookOnTrustChanged(monitor)
    }

    private fun hookGetUserHasTrust(monitor: Class<*>) {
        val method = CompatibleMethodResolver.find(
            monitor,
            "getUserHasTrust",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(Int::class.javaPrimitiveType!!)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "$KEYGUARD_UPDATE_MONITOR#getUserHasTrust(int)", "method not found")
            return
        }

        runCatching {
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "getUserHasTrust", Unit) {
                        if (!enabled || param.result == true) return@open
                        val monitorInstance = param.thisObject
                        val userId = param.args.getOrNull(0) as? Int ?: return@open
                        if (!guardsAllowTrust(monitorInstance)) return@open
                        if (resolveTrusted(monitorInstance, userId)) {
                            param.result = true
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$KEYGUARD_UPDATE_MONITOR#getUserHasTrust(int)", it)
        }
    }

    /** Drop the cached trust state as soon as the platform reports a real change. */
    private fun hookOnTrustChanged(monitor: Class<*>) {
        val method = monitor.declaredMethods.firstOrNull {
            it.name == "onTrustChanged" && it.parameterTypes.size == 5
        } ?: run {
            DebugLog.hookSkipped(TAG, "$KEYGUARD_UPDATE_MONITOR#onTrustChanged", "method not found")
            return
        }

        runCatching {
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "onTrustChanged", Unit) {
                        when (val userId = param.args.getOrNull(2)) {
                            is Int -> trustCache.remove(userId)
                            else -> trustCache.clear()
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$KEYGUARD_UPDATE_MONITOR#onTrustChanged", it)
        }
    }

    /**
     * True when the original false result can only have come from the stale cache. A SIM PIN or a
     * biometric policy that forbids trust must never be overridden.
     */
    private fun guardsAllowTrust(monitorInstance: Any): Boolean {
        val simPinSecure = isSimPinSecureMethod?.invoke(monitorInstance) as? Boolean ?: return false
        if (simPinSecure) return false
        return isUnlockingWithBiometricAllowedMethod?.invoke(monitorInstance, true) as? Boolean ?: false
    }

    private fun resolveTrusted(monitorInstance: Any, userId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        trustCache[userId]?.let { cached ->
            if (now - cached.uptimeMs < TRUST_CACHE_TTL_MS) return cached.trusted
        }

        val context = contextField?.get(monitorInstance) as? Context ?: return false
        val keyguardManager = context.getSystemService(KeyguardManager::class.java) ?: return false

        val isDeviceSecure = isDeviceSecureMethod ?: runCatching {
            KeyguardManager::class.java
                .getMethod("isDeviceSecure", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()?.also { isDeviceSecureMethod = it } ?: return false
        val isDeviceLocked = isDeviceLockedMethod ?: runCatching {
            KeyguardManager::class.java
                .getMethod("isDeviceLocked", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()?.also { isDeviceLockedMethod = it } ?: return false

        val secure = isDeviceSecure.invoke(keyguardManager, userId) as? Boolean ?: return false
        val locked = isDeviceLocked.invoke(keyguardManager, userId) as? Boolean ?: return false
        val trusted = secure && !locked
        trustCache[userId] = CachedTrust(now, trusted)
        return trusted
    }
}
