package com.takekazex.hypertweak.hook.rules.ime

import com.takekazex.hypertweak.hook.base.HostAccess
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
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

    private var immsContextField: Field? by generationState { null }
    private var imeDrawsNavBarField: Field? by generationState { null }

    /**
     * `getInputMethodNavButtonFlagsLocked` runs on every IME attach, and reading
     * `navigation_mode`/`DEFAULT_INPUT_METHOD` from `Settings.Secure` on every call was two
     * binder round-trips per attach. Snapshot both values and refresh them through a
     * `ContentObserver`, so the hot path only reads fields (the system-server half already
     * requires a reboot, so a settings change arriving via the observer is strictly better
     * than the previous per-call read).
     */
    private var cachedGestureNav by generationState { false }

    private var cachedCurrentIme: String? by generationState { null }
    private var cachedEnabledImePackages: Set<String> by generationState { emptySet() }

    private val imeSettingsLock = Any()
    private var imeSettingsAttached by generationState { false }
    private var imeSettingsObserver: ContentObserver? by generationState { null }
    private var imeSettingsResolver: android.content.ContentResolver? by generationState { null }

    override fun onPrepareHotReload() {
        synchronized(imeSettingsLock) {
            val observer = imeSettingsObserver ?: return@synchronized
            imeSettingsObserver = null
            imeSettingsAttached = false
            // `unregisterSelf()` is @hide; unregister through the resolver instead.
            runCatching { imeSettingsResolver?.unregisterContentObserver(observer) }
            imeSettingsResolver = null
        }
    }

    override fun onHook() {
        hookNavButtonFlags()
        hookIsCallingBetweenCustomIme()
    }

    private var serviceInstance: Any? by generationState { null }

    override fun recoverHotReloadHosts() { refreshSettings() }

    fun refreshSettings(): Boolean = runCatching {
        if (!isHooked) return true
        val type = IMMS.toClassOrNull() ?: return false
        val service = serviceInstance ?: run {
            // OS4 wraps the public binder callback in ZeroJankProxy. The process-local
            // service retains a unique IMMS owner field and avoids binder/proxy unwrapping.
            val localServices = "com.android.server.LocalServices".toClassOrNull() ?: return false
            val internalType = "com.android.server.inputmethod.InputMethodManagerInternal".toClassOrNull() ?: return false
            val local = HostAccess.requireDeclaredMethod(localServices, "getService", Class::class.java,
                accessible = true).invoke(null, internalType) ?: return false
            val owner = HostAccess.cached(local.javaClass, "ime.system.owner", Field::class.java) {
                local.javaClass.declaredFields.singleOrNull { it.type == type }?.apply { isAccessible = true }
            }
            checkNotNull(owner?.get(local)?.takeIf { type.isInstance(it) }) { "Local IME service has no unique IMMS owner" }
        }
        serviceInstance = service
        val lock = "com.android.server.inputmethod.ImfLock".toClassOrNull() ?: return false
        synchronized(lock) {
            val context = immsContextField?.get(service) as? Context ?: return false
            refreshImeSettings(context)
            val userId = HostAccess.read(service, "mCurrentImeUserId") as? Int ?: return false
            val userData = HostAccess.method(service, "getUserData", Int::class.javaPrimitiveType!!)?.invoke(service, userId)
                ?: return false
            // Older builds overwrote this platform flag with the feature's request. Re-evaluate
            // its native resource policy before publishing, including disable/reset migrations.
            val monitor = "com.android.server.inputmethod.InputMethodDrawsNavBarResourceMonitor".toClassOrNull()
                ?: return false
            val users = HostAccess.read(service, "mUserManagerInternal") ?: return false
            val parentId = HostAccess.method(users, "getProfileParentId", Int::class.javaPrimitiveType!!)
                ?.invoke(users, userId) as? Int ?: userId
            val native = HostAccess.requireDeclaredMethod(monitor, "evaluate", Context::class.java,
                Int::class.javaPrimitiveType!!, accessible = true).invoke(null, context, parentId) as? Boolean ?: return false
            (imeDrawsNavBarField?.get(userData) as? AtomicBoolean)?.set(native)
            HostAccess.method(service, "sendOnNavButtonFlagsChangedLocked", userData.javaClass)?.invoke(service, userData)
        }
        true
    }.getOrElse { DebugLog.w(TAG, "IME flags refresh failed", it); false }

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
            HostAccess.requireDeclaredField(imms, "mContext", accessible = true)
        }.getOrNull()
        imeDrawsNavBarField = runCatching {
            HostAccess.requireDeclaredField(userData, "mImeDrawsNavBar", accessible = true)
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
                        serviceInstance = param.thisObject
                        val flags = param.result as? Int ?: return@open
                        if (!AospImeConfig.isEnabled() || AospImeConfig.raiseStyle() !=
                            com.takekazex.hypertweak.settings.contract.ImeLayout.RAISE_STYLE_AOSP) return@open
                        val context = immsContextField?.get(param.thisObject) as? Context ?: return@open
                        val wanted = shouldDrawImeNavBar(context)
                        if (!wanted) return@open
                        if (wanted == ((flags and FLAG_IME_DRAWS_IME_NAV_BAR) != 0)) return@open

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
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer
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
        cachedEnabledImePackages = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
                .split(':').mapNotNull { android.content.ComponentName.unflattenFromString(it.substringBefore(';'))?.packageName }.toSet()
        }.getOrDefault(emptySet())
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
                        if ((!AospImeConfig.isEnabled() && !AospImeConfig.showAllImeList()) || param.result == true) return@open
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        val uid = param.args.getOrNull(1) as? Int ?: return@open
                        val targetPackage = param.args.getOrNull(2) as? String ?: return@open
                        attachImeSettingsObserver(context)
                        // targetPackage is the OTHER keyboard being queried, not the caller.
                        // Otherwise Android package visibility removes it before the MIUI popup
                        // even obtains its list. Authenticate the requesting keyboard by UID.
                        val owned = context.packageManager?.getPackagesForUid(uid)
                        if (ImeSwitcherPolicy.mayAccess(targetPackage, cachedEnabledImePackages,
                                owned?.toSet().orEmpty(), AospImeConfig.selectedPackages())) {
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
