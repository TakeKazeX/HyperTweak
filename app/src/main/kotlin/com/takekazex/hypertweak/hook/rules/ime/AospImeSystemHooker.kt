package com.takekazex.hypertweak.hook.rules.ime

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The system-server half of the AOSP IME navigation bar.
 *
 * `InputMethodDrawsNavBarResourceMonitor` derives `UserData.mImeDrawsNavBar` from
 * `config_isDesktopModeSupported`, which is false on phones, and only re-evaluates it at user start
 * and on overlay changes — never on an IME switch. So the flag has to be recomputed where it is
 * read, not where it is stored.
 *
 * Ported from Howard20181's Mi_AOSP_IME (GPL-3.0).
 */
object AospImeSystemHooker : StaticHooker() {
    private const val TAG = "AospImeSystem"

    private const val IMMS = "com.android.server.inputmethod.InputMethodManagerService"
    private const val IMMS_IMPL = "com.android.server.inputmethod.InputMethodManagerServiceImpl"
    private const val USER_DATA = "com.android.server.inputmethod.UserData"

    /** `InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR`. */
    private const val FLAG_IME_DRAWS_IME_NAV_BAR = 1

    /** `WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL`. */
    private const val NAV_BAR_MODE_GESTURAL = 2

    /** Deoptimized because the flags method is private and its callers may inline it. */
    private val NAV_BUTTON_FLAG_CALLERS = setOf(
        "attachNewInputLocked",
        "initializeImeLocked",
        "sendOnNavButtonFlagsChangedLocked"
    )

    private var immsContextField: Field? = null
    private var imeDrawsNavBarField: Field? = null

    /**
     * `getInputMethodNavButtonFlagsLocked` runs on every IME attach, and reading
     * `navigation_mode`/`DEFAULT_INPUT_METHOD` from `Settings.Secure` on every call was two
     * binder round-trips per attach. Snapshot both values and refresh them through a
     * `ContentObserver`, so the hot path only reads fields (the system-server half already
     * requires a reboot, so a settings change arriving via the observer is strictly better
     * than the previous per-call read).
     */
    @Volatile
    private var cachedGestureNav = false
    @Volatile
    private var cachedCurrentIme: String? = null

    private val imeSettingsLock = Any()
    private var imeSettingsAttached = false
    private var imeSettingsObserver: ContentObserver? = null
    private var imeSettingsResolver: android.content.ContentResolver? = null

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onPrepareHotReload() {
        immsContextField = null
        imeDrawsNavBarField = null
        synchronized(imeSettingsLock) {
            val observer = imeSettingsObserver ?: return@synchronized
            imeSettingsObserver = null
            imeSettingsAttached = false
            // `unregisterSelf()` is @hide; unregister through the resolver instead.
            runCatching { imeSettingsResolver?.unregisterContentObserver(observer) }
            imeSettingsResolver = null
        }
        cachedGestureNav = false
        cachedCurrentIme = null
    }

    override fun onHook() {
        if (!AospImeConfig.isEnabled()) {
            DebugLog.hookSkipped(TAG, "InputMethodManagerService", "disabled")
            return
        }
        hookNavButtonFlags()
        hookIsCallingBetweenCustomIme()
    }

    private fun hookNavButtonFlags() {
        val imms = IMMS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, IMMS, "class not found")
            return
        }
        val userData = USER_DATA.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, USER_DATA, "class not found")
            return
        }

        // Resolve by exact signature: older platforms take (int userId) here, and a blind write to
        // args[0] on that shape would silently do nothing.
        val method = CompatibleMethodResolver.find(
            imms,
            "getInputMethodNavButtonFlagsLocked",
            returnType = Int::class.javaPrimitiveType,
            parameterTypes = listOf(userData)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$IMMS#getInputMethodNavButtonFlagsLocked(UserData)",
                "method not found"
            )
            return
        }

        immsContextField = runCatching {
            imms.getDeclaredField("mContext").apply { isAccessible = true }
        }.getOrNull()
        imeDrawsNavBarField = runCatching {
            userData.getDeclaredField("mImeDrawsNavBar").apply { isAccessible = true }
        }.getOrNull()
        if (immsContextField == null || imeDrawsNavBarField == null) {
            DebugLog.hookSkipped(
                TAG,
                "$IMMS#getInputMethodNavButtonFlagsLocked(UserData)",
                "mContext/mImeDrawsNavBar not resolved"
            )
            return
        }

        runCatching {
            deoptimize(method)
            imms.declaredMethods.filter { it.name in NAV_BUTTON_FLAG_CALLERS }.forEach(::deoptimize)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "navButtonFlags", Unit) {
                        val flags = param.result as? Int ?: return@open
                        val context = immsContextField?.get(param.thisObject) as? Context ?: return@open
                        val wanted = shouldDrawImeNavBar(context)
                        if (wanted == ((flags and FLAG_IME_DRAWS_IME_NAV_BAR) != 0)) return@open

                        // Keep UserData in step, or the next onNavButtonFlagsChanged disagrees.
                        (imeDrawsNavBarField?.get(param.args.getOrNull(0)) as? AtomicBoolean)?.set(wanted)
                        param.result = if (wanted) {
                            flags or FLAG_IME_DRAWS_IME_NAV_BAR
                        } else {
                            flags and FLAG_IME_DRAWS_IME_NAV_BAR.inv()
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$IMMS#getInputMethodNavButtonFlagsLocked(UserData)", it)
        }
    }

    private fun shouldDrawImeNavBar(context: Context): Boolean {
        attachImeSettingsObserver(context)
        if (!cachedGestureNav) return false
        val currentIme = cachedCurrentIme
        return currentIme != null && AospImeConfig.isSelectedIme(currentIme)
    }

    private fun attachImeSettingsObserver(context: Context) {
        if (imeSettingsAttached) return
        synchronized(imeSettingsLock) {
            if (imeSettingsAttached) return
            imeSettingsAttached = true
            refreshImeSettings(context)
            val resolver = context.contentResolver
            imeSettingsResolver = resolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    refreshImeSettings(context)
                }
            }
            imeSettingsObserver = observer
            runCatching {
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor("navigation_mode"), false, observer
                )
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer
                )
            }.onFailure {
                DebugLog.w(TAG, "failed to register IME settings observer", it)
            }
        }
    }

    private fun refreshImeSettings(context: Context) {
        val resolver = context.contentResolver
        cachedGestureNav = runCatching {
            Settings.Secure.getInt(resolver, "navigation_mode", NAV_BAR_MODE_GESTURAL) ==
                NAV_BAR_MODE_GESTURAL
        }.getOrDefault(false)
        cachedCurrentIme = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
    }

    /**
     * MIUI only lets its own customised input methods talk to the IME stack; a selected keyboard
     * has to pass the same check.
     */
    private fun hookIsCallingBetweenCustomIme() {
        val impl = IMMS_IMPL.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, IMMS_IMPL, "class not found")
            return
        }
        val method = CompatibleMethodResolver.find(
            impl,
            "isCallingBetweenCustomIME",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(Context::class.java, Int::class.javaPrimitiveType!!, String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$IMMS_IMPL#isCallingBetweenCustomIME(Context,int,String)",
                "method not found"
            )
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "isCallingBetweenCustomIME", Unit) {
                        if (param.result == true) return@open
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        val uid = param.args.getOrNull(1) as? Int ?: return@open
                        val targetPackage = param.args.getOrNull(2) as? String ?: return@open
                        if (!AospImeConfig.isSelectedIme(targetPackage)) return@open

                        val owned = context.packageManager?.getPackagesForUid(uid)
                        if (owned?.contains(targetPackage) == true) {
                            param.result = true
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$IMMS_IMPL#isCallingBetweenCustomIME(Context,int,String)", it)
        }
    }
}
