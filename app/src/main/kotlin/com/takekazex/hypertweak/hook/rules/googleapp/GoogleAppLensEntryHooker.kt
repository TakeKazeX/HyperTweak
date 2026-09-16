package com.takekazex.hypertweak.hook.rules.googleapp

import android.app.Activity
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens the Google App's exported Lens entry point to the power-button **Google Lens** action.
 *
 * `GoogleLensLauncher` in system_server starts `google://lens`
 * (`activity-alias com.google.android.apps.lens.MainActivity` →
 * `com.google.android.apps.search.lens.LensExportedActivity`, `process=:googleapp`) on the current
 * user, exactly the way a launcher shortcut would. That start is legitimate but comes from a
 * *non-activity* caller, and the Google App's handler refuses two things about it. Both were
 * measured on device before being patched — the first press showed the launch animation and
 * dropped straight back ("转跳动画出来了又立刻退出"):
 *
 * 1. **An empty caller package.** The handler reads `Activity#getCallingPackage()`, which is null
 *    for a start from system_server, a Service or `am`, and answers `3` — logged as
 *    `Caller package cannot be empty. LensExportedActivity must be started for result.` — after
 *    which it finishes at once. Lens is a start-for-result API, so a caller is mandatory; but
 *    `PowerButtonCtsHooker` runs in system_server, which owns no Activity and therefore cannot
 *    *be* one. [HOOK_CALLER] / [HOOK_CALLER_HIDDEN] supply the package the framework could not,
 *    for this activity only and only for this action's own marked launch.
 *
 * 2. **The caller-partner allowlist.** With a caller, the handler answers with a Lens eligibility
 *    code that becomes the activity result, and only `-1` keeps Lens open. From the 17.48.13
 *    decompile (handler class `dlay`; on the device's 17.58.13 the same code logs from the
 *    obfuscated holder `dpqh`):
 *
 *    | condition | result |
 *    | --- | --- |
 *    | caller package empty | `3` |
 *    | `LENS_UNAVAILABLE_LOCALE_NOT_SUPPORTED` / `_DEVICE_INCOMPATIBLE` / `_DEVICE_LOCKED` | `6` / `7` / `8` |
 *    | `LENS_READY` | `-1` |
 *    | caller not on the partner allowlist (`LENS_UNAVAILABLE`) | `1` |
 *
 *    `LENS_READY` is decided by a Google-side allowlist: a master boolean, two package sets, and
 *    an OEM table keyed on the `com.google.lens.feature.CAMERA_INTEGRATION` /
 *    `IMAGE_INTEGRATION` system features plus the `ro.com.google.lens.oem_camera_package` /
 *    `oem_image_package` properties. **This device declares none of those features and none of
 *    those properties** (`pm list features`, `getprop` verified), and `com.takekazex.hypertweak`
 *    is in no set, so the measured start returned `1` and Lens exited immediately again.
 *    [HOOK_ELIGIBILITY] converts **only that caller's** `1` into `-1`. Codes `6` / `7` / `8` are
 *    deliberately left alone: those mean Lens genuinely cannot run (locale, device, locked), and
 *    faking them would only show a broken surface.
 *
 * Both callbacks are gated live on `Preferences.powerButtonAction() ==
 * POWER_BUTTON_ACTION_GOOGLE_LENS` and on this action's own marked launch, so another binding
 * leaves Google's behaviour untouched and a third-party Lens launch keeps Google's own answer. The
 * eligibility override additionally requires the caller to be the module's package.
 *
 * The eligibility target is resolved structurally, never by an obfuscated name: the holder is the
 * class containing the stable log string `"Lens eligibility error: %s"`, and the decision is the
 * single `(String) -> int` method inside it. If either that or the framework method is unavailable
 * or ambiguous, the hook logs and installs nothing — the Google App is then untouched and the
 * power-button action simply fails closed to the platform's own long-press handling.
 */
object GoogleAppLensEntryHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "GoogleLensEntry"

    /** Stable log-string anchor; the class that logs it owns the eligibility decision. */
    private const val ELIGIBILITY_ANCHOR = "Lens eligibility error: %s"

    /** The exported Lens activity; the only activity these hooks apply to. */
    private const val LENS_EXPORTED_ACTIVITY =
        "com.google.android.apps.search.lens.LensExportedActivity"

    /** The module app, i.e. the caller supplied in place of the framework's null. */
    private val CALLER_PACKAGE = BuildConfig.APPLICATION_ID

    /** Eligibility code for "this caller is not allowlisted" (`LENS_UNAVAILABLE`). */
    private const val RESULT_NOT_ALLOWLISTED = 1

    /** Eligibility code for "Lens is ready"; the handler keeps the Lens surface open for this one. */
    private const val RESULT_READY = -1

    internal const val HOOK_CALLER = "google_lens_direct_intent_caller"
    internal const val HOOK_CALLER_HIDDEN = "google_lens_direct_intent_caller_hidden"
    internal const val HOOK_ELIGIBILITY = "google_lens_direct_intent_eligibility"

    /** One-shot guard: the Lens view reads the calling package dozens of times per launch. */
    private val callerLogged = AtomicBoolean(false)

    override fun onHook() {
        // GoogleAppRuntime owns the one shared DexKit session; see GoogleAppLiveTranslateHooker.
    }

    /** Installs the hooks from the coordinator's shared DexKit bridge. */
    internal fun installWithBridge(
        bridge: DexKitBridge,
        existingHookIds: Set<String> = emptySet()
    ) {
        installCallerHook(existingHookIds)
        installEligibilityHook(bridge, existingHookIds)
    }

    /** Replacement callbacks used before a hot-reload generation resolves DexKit again. */
    internal fun replacement(id: String): XposedInterface.Hooker? = when (id) {
        HOOK_CALLER, HOOK_CALLER_HIDDEN -> XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            if (result != null) result else suppliedCaller(chain.thisObject, id)
        }
        HOOK_ELIGIBILITY -> XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val caller = chain.args.firstOrNull() as? String
            if (caller == CALLER_PACKAGE &&
                result == RESULT_NOT_ALLOWLISTED &&
                lensActionSelected()
            ) {
                RESULT_READY
            } else {
                result
            }
        }
        else -> null
    }

    /**
     * Supplies the calling package the framework reports as null for a non-activity start.
     *
     * Both accessors are covered because the framework exposes the same fact twice and a build may
     * read either: the public `getCallingPackage()`, and the hidden `getLaunchedFromPackage()` the
     * handler itself can reach from inside the app. Neither needs DexKit itself; they are installed
     * from the coordinator's bridge pass because gate 2's scan needs that same bridge anyway.
     */
    private fun installCallerHook(existingHookIds: Set<String>) {
        hookCallerAccessor("getCallingPackage", HOOK_CALLER, existingHookIds)
        hookCallerAccessor("getLaunchedFromPackage", HOOK_CALLER_HIDDEN, existingHookIds)
    }

    private fun hookCallerAccessor(name: String, id: String, existingHookIds: Set<String>) {
        if (existingHookIds.contains(id)) {
            DebugLog.i(TAG, "reused carried Lens caller hook ($name)")
            return
        }
        val method = runCatching {
            Activity::class.java.getDeclaredMethod(name)
        }.getOrNull() ?: run {
            DebugLog.i(TAG, "Activity#$name not present on this build")
            return
        }
        method.isAccessible = true
        deoptimize(method)
        method.hook(id) {
            after { param ->
                if (param.result == null) {
                    suppliedCaller(param.thisObject, name)?.let { param.result = it }
                }
            }
        }
        DebugLog.i(TAG, "HOOK_OK lens caller substitution on Activity#$name")
    }

    /**
     * The caller package to report, or null to leave the framework's own answer alone.
     *
     * Two conditions keep this narrow. The launch must carry [GoogleLensLauncher.EXTRA_POWER_LAUNCH],
     * which is how this action's own start is recognised: the framework reports
     * `Activity#getLaunchedFromUid()` as `-1` and the calling package as null for **any** start
     * without a source activity, so the caller's identity is simply not available here (measured on
     * device with an `am start` of the same intent). And the power-button preference must currently
     * be Google Lens, so another binding leaves Google's behaviour untouched.
     *
     * The extra is a marker, not a secret: an app that forges it while this action is bound gets
     * the same Lens launch the user's own long press gets. That is the documented cost of having no
     * framework identity to rely on; it is not a privilege escalation, and it disappears as soon as
     * the power button is bound to something else.
     */
    private fun suppliedCaller(activity: Any?, accessor: String): String? {
        if (activity !is Activity) return null
        if (activity.javaClass.name != LENS_EXPORTED_ACTIVITY) return null
        if (!lensActionSelected()) return null
        if (activity.intent?.getBooleanExtra(GoogleLensLauncher.EXTRA_POWER_LAUNCH, false) != true) {
            return null
        }
        if (callerLogged.compareAndSet(false, true)) {
            DebugLog.i(
                TAG,
                "supplying the power-button caller through Activity#$accessor " +
                    "(launchedFromUid=${activity.launchedFromUid})"
            )
        }
        return CALLER_PACKAGE
    }

    /**
     * The `(String) -> int` decision method inside the class that logs [ELIGIBILITY_ANCHOR].
     *
     * The scan is deliberately strict: exactly one matching method. A build that splits or renames
     * it is not guessed at — the caller logs and leaves the Google App's own behavior in place.
     */
    private fun installEligibilityHook(bridge: DexKitBridge, existingHookIds: Set<String>) {
        if (existingHookIds.contains(HOOK_ELIGIBILITY)) {
            DebugLog.i(TAG, "reused carried Lens eligibility hook")
            return
        }
        val method = resolveEligibilityMethod(bridge) ?: return
        method.isAccessible = true
        // Small and private, so ART may inline it into its single caller; the hook would then
        // never fire.
        deoptimize(method)
        method.hook(HOOK_ELIGIBILITY) {
            after { param ->
                val caller = param.args.firstOrNull() as? String
                if (caller != CALLER_PACKAGE) return@after
                if (param.result != RESULT_NOT_ALLOWLISTED) return@after
                if (!lensActionSelected()) return@after
                DebugLog.i(TAG, "power-button Lens caller=$caller passed the partner allowlist gate")
                param.result = RESULT_READY
            }
        }
        DebugLog.i(TAG, "HOOK_OK lens direct-intent eligibility on ${method.declaringClass.name}")
    }

    private fun resolveEligibilityMethod(bridge: DexKitBridge): Method? {
        val holders = runCatching {
            bridge.findClass { matcher { usingStrings(ELIGIBILITY_ANCHOR) } }
        }.onFailure { failure ->
            DebugLog.w(TAG, "Lens eligibility holder lookup failed", failure)
        }.getOrDefault(emptyList())
        if (holders.isEmpty()) {
            DebugLog.w(TAG, "Lens eligibility holder not found; leaving the Google App untouched")
            return null
        }
        val candidates = holders.flatMap { holder ->
            val clazz = classFor(holder.name) ?: return@flatMap emptyList()
            clazz.declaredMethods.filter { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.returnType == Int::class.javaPrimitiveType
            }
        }
        if (candidates.size != 1) {
            DebugLog.w(
                TAG,
                "Lens eligibility decision ambiguous (holders=${holders.size}, " +
                    "methods=${candidates.size}); leaving the Google App untouched"
            )
            return null
        }
        return candidates[0]
    }

    private fun lensActionSelected(): Boolean =
        Preferences.powerButtonAction() == Preferences.POWER_BUTTON_ACTION_GOOGLE_LENS

    private fun classFor(dexName: String): Class<*>? = runCatching {
        Class.forName(
            dexName.removePrefix("L").removeSuffix(";").replace('/', '.'),
            false,
            classLoader
        )
    }.onFailure { failure ->
        DebugLog.w(TAG, "failed to load $dexName", failure)
    }.getOrNull()
}
