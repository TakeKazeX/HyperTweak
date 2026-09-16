package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Applies the selected Security Center permission-warning intercept mode
 * (危险权限确认倒计时) for the `InterceptBaseActivity` / `InterceptBaseFragment`
 * confirm pages (USB debugging, device manager, accessibility, OAID, MIUI
 * optimization, …).
 *
 * Modes (see [Preferences.KEY_SECURITY_CENTER_PERMISSION_WARNING_MODE]):
 * - 0 follow system: no hook.
 * - 1 skip countdown: rewrites the Activity input Bundle (`KET_STEP_COUNT=0`,
 *   `KEY_ALLOW_ENABLE=true`) before `InterceptBaseActivity.onCreate`, the single
 *   source the fragments actually read on the current baseline (both
 *   `InterceptBaseFragment.onCreate` and `InterceptPermissionFragment.onCreate`
 *   read `InterceptBaseActivity.x0()`, not their own arguments).
 * - 2 direct approve: resolves the native confirm method (public non-static
 *   `(Z)V` whose body carries the `setResult(-1/0)` constants) inside
 *   `com.miui.permcenter.privacymanager.InterceptBaseFragment` and calls it with
 *   `true` after `onInflateView`.
 *
 * Hard exclusion: `miui_close_optimization` (关闭 MIUI 优化) writes
 * `persist.sys.miui_optimization=false` in its native confirm path. That
 * permission is checked at BOTH entry points and never touched — the native
 * warning page, countdown and manual Allow remain intact in every mode. The
 * duplicate check is deliberate: if a future build moves the data between the
 * Activity and its fragments, a single-layer recognition failure must not turn
 * into an automatic MIUI-optimization confirmation.
 *
 * Obfuscated method names (c0/d0/e0 changed between 13.2.7 and 13.5.8) are never
 * hardcoded; the confirm method is resolved behaviorally by DexKit and rejected
 * unless exactly one candidate matches (fail closed).
 *
 * Second, independent flow: 设置 → 开发者选项 →「USB 调试（安全设置）」does **not**
 * use the intercept activity above. It opens
 * `com.miui.permcenter.install.AdbInputApplyActivity`, a three-step page whose own
 * `Handler` ticks a 5-second countdown per step before enabling 「下一步」/
 * 「允许」, then hands off to `AdbInstallVerifyActivity`, whose Mi-account risk check
 * (`srv.sec.miui.com/data/adb`) is what finally writes `persist.security.adbinput`.
 * That page extends `BaseActivity` and is therefore invisible to the intercept hooks.
 * It is handled here too: the native countdown is driven straight to its completion so
 * the button is immediately tappable with the ROM's own label, and the network
 * verification is bypassed in both modes by landing that same native result —
 * written through the host's own property helper and verified by reading it back.
 * The countdown counter is resolved structurally (the int field the host's tick
 * handler decrements), never by obfuscated name, and the path fails closed when that
 * resolution is ambiguous.
 */
object WarningCountdownHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "WarningCountdown"
    private const val PACKAGE = "com.miui.securitycenter"

    private const val BASE_FRAGMENT_CLASS =
        "com.miui.permcenter.privacymanager.InterceptBaseFragment"
    private const val BASE_ACTIVITY_CLASS =
        "com.miui.permcenter.privacymanager.model.InterceptBaseActivity"

    /** Bundle keys and permission name as shipped by the 13.5.8 baseline. */
    private const val KEY_STEP_COUNT = "KET_STEP_COUNT"
    private const val KEY_ALLOW_ENABLE = "KEY_ALLOW_ENABLE"
    private const val KEY_PERM_NAME = "permName"

    /** MIUI 优化关闭: system-property side effect, excluded from every mode. */
    private const val EXCLUDED_PERMISSION = "miui_close_optimization"

    /** USB 调试（安全设置）3-step page and its Mi-account verification hand-off. */
    private const val ADB_INPUT_APPLY_CLASS =
        "com.miui.permcenter.install.AdbInputApplyActivity"
    private const val ADB_VERIFY_CLASS =
        "com.miui.permcenter.install.AdbInstallVerifyActivity"
    private const val EXTRA_IS_INPUT = "is_input"

    /**
     * The property the native success path writes for this flow
     * (`AdbInputApplyActivity.G0(true)` → `SystemPropertiesCompat.set`), reached after the
     * Mi-account check. Writing it directly is what bypasses that check.
     */
    private const val ADB_INPUT_PROPERTY = "persist.security.adbinput"
    private const val PROPERTY_COMPAT_CLASS = "com.miui.permcenter.compact.SystemPropertiesCompat"

    /** Per-activity-class countdown field resolved by the decrement probe below. */
    private val countdownFields = java.util.concurrent.ConcurrentHashMap<Class<*>, Field>()

    /** Host property accessors (`set`/`get`), resolved once per process. */
    private val propertyAccessors by lazy { resolvePropertyAccessors() }

    private class PropertyAccessors(val write: Method, val read: Method)

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        val mode = selectedMode()
        if (mode == Preferences.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM) {
            DebugLog.hookSkippedDebug(TAG, "permission warning intercept", "follow system")
            return
        }

        hookCountdownPath()
        hookAdbInputApplyPath()
        hookAdbVerifyPath()

        if (mode == Preferences.SECURITY_CENTER_WARNING_DIRECT_APPROVE) {
            hookDirectApprovePath()
        }
    }

    private fun selectedMode(): Int = Preferences.getInt(
        Preferences.KEY_SECURITY_CENTER_PERMISSION_WARNING_MODE,
        Preferences.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM
    ).coerceIn(
        Preferences.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM,
        Preferences.SECURITY_CENTER_WARNING_DIRECT_APPROVE
    )

    /** True when the current call must keep native behavior (hard exclusion). */
    private fun isExcludedPermission(permName: String?): Boolean =
        permName == EXCLUDED_PERMISSION

    /**
     * The permission about to be intercepted.
     *
     * The launch Intent is the authoritative source: `SpecialPermissionInterceptActivity`
     * dispatches on `getIntent().getStringExtra("permName")`, and
     * `DeviceManagerApplyActivity.A0()` writes its own `permName` into that Intent during
     * `onCreate`. The `onCreate` Bundle argument is the framework's **saved-instance-state**
     * slot — null on a fresh launch and, per `onSaveInstanceState`, only ever holding
     * `KET_STEP_COUNT` / `KEY_ALLOW_ENABLE` — so it never carries `permName` on this
     * baseline and is kept only as a defensive fallback.
     */
    private fun interceptedPermission(param: HookParam): String? {
        val activity = param.thisObjectOrNull as? Activity
        val fromIntent = runCatching {
            activity?.intent?.getStringExtra(KEY_PERM_NAME)
        }.getOrNull()
        if (fromIntent != null) return fromIntent
        return (param.args.getOrNull(0) as? Bundle)?.getString(KEY_PERM_NAME)
    }

    // ─── Mode 1 (+ Activity-side exclusion for mode 2): input Bundle rewrite ───

    private fun hookCountdownPath() {
        val activityClass = BASE_ACTIVITY_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "countdown path", "$BASE_ACTIVITY_CLASS not found")
            return
        }
        val onCreate = activityClass.declaredMethods.firstOrNull { method ->
            method.name == "onCreate" &&
                method.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
        } ?: run {
            DebugLog.hookSkipped(TAG, "countdown path", "onCreate(Bundle) not found")
            return
        }

        onCreate.isAccessible = true
        deoptimize(onCreate)
        onCreate.hook("warning_countdown_base_activity_on_create") {
            before { param ->
                HookFailurePolicy.open(TAG, "onCreate.before", Unit) {
                    rewriteInputBundle(param)
                }
            }
        }
        DebugLog.i(TAG, "countdown path hooked on $BASE_ACTIVITY_CLASS#onCreate")
    }

    private fun rewriteInputBundle(param: HookParam) {
        val mode = selectedMode()
        if (mode != Preferences.SECURITY_CENTER_WARNING_SKIP_COUNTDOWN &&
            mode != Preferences.SECURITY_CENTER_WARNING_DIRECT_APPROVE
        ) {
            return
        }

        if (isExcludedPermission(interceptedPermission(param))) {
            // 1st exclusion layer: leave the countdown Bundle untouched (still null on a
            // fresh launch), so 关闭 MIUI 优化 keeps its native countdown and manual Allow.
            DebugLog.i(TAG, "excluded permission; native countdown preserved")
            return
        }

        // The fragments read this through InterceptBaseActivity.x0(), so it is the
        // authoritative countdown / allow-enable source. It must be replaced in the
        // `before` stage: the host assigns its own field from this argument later on.
        val bundle = param.argAs<Bundle?>(0) ?: Bundle().also { param.args[0] = it }
        bundle.putInt(KEY_STEP_COUNT, 0)
        bundle.putBoolean(KEY_ALLOW_ENABLE, true)
    }

    // ─── Mode 2: behavioral confirm-method resolution + after-hook call ───

    private fun hookDirectApprovePath() {
        val fragmentClass = BASE_FRAGMENT_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "direct approve path", "$BASE_FRAGMENT_CLASS not found")
            return
        }
        val onInflateView = fragmentClass.declaredMethods.firstOrNull { method ->
            method.name == "onInflateView" &&
                method.parameterTypes.contentEquals(
                    arrayOf(LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
                )
        } ?: run {
            DebugLog.hookSkipped(TAG, "direct approve path", "onInflateView not found")
            return
        }

        val confirmMethod = resolveConfirmMethod() ?: run {
            DebugLog.hookSkipped(
                TAG,
                "direct approve path",
                "confirm method not uniquely resolved; fail closed"
            )
            return
        }

        onInflateView.isAccessible = true
        deoptimize(onInflateView)
        onInflateView.hook("warning_countdown_base_fragment_on_inflate_view") {
            after { param ->
                HookFailurePolicy.open(TAG, "onInflateView.after", Unit) {
                    callConfirmIfAllowed(param, confirmMethod)
                }
            }
        }
        DebugLog.i(
            TAG,
            "direct approve path hooked on $BASE_FRAGMENT_CLASS#onInflateView " +
                "confirm=${confirmMethod.toGenericString()}"
        )
    }

    /**
     * Resolves the native confirm method inside [BASE_FRAGMENT_CLASS] by behavior:
     * public non-static `(Z)V` whose body uses both -1 and 0 (the
     * `setResult(z ? -1 : 0)` pair). `InterceptPermissionFragment.e0` overrides it
     * for ADB but is not in this class, so the match here is the shared base path.
     * Returns null unless exactly one candidate matches (fail closed).
     */
    private fun resolveConfirmMethod(): Method? {
        val apkPath = hookParam.appInfo?.sourceDir
        if (apkPath != null) {
            val resolved = DexKitManager.withBridge(apkPath) { bridge ->
                bridge.findMethod {
                    matcher {
                        declaredClass(BASE_FRAGMENT_CLASS, StringMatchType.Equals)
                        returnType("void")
                        addParamType("boolean")
                        usingNumbers(-1, 0)
                        modifiers(Modifier.PUBLIC)
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }
            if (resolved != null) {
                if (resolved.parameterCount == 1 &&
                    resolved.parameterTypes[0] == java.lang.Boolean.TYPE &&
                    !Modifier.isStatic(resolved.modifiers)
                ) {
                    return resolved
                }
                DebugLog.w(TAG, "resolved confirm method shape mismatch: ${resolved.toGenericString()}")
                return null
            }
        }

        // Fallback without DexKit: same behavioral signature via reflection only.
        val candidates = fragmentCandidates()
        return candidates.singleOrNull()
    }

    private fun fragmentCandidates(): List<Method> {
        val fragmentClass = BASE_FRAGMENT_CLASS.toClassOrNull() ?: return emptyList()
        return fragmentClass.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == java.lang.Boolean.TYPE
        }
    }

    private fun callConfirmIfAllowed(param: HookParam, confirmMethod: Method) {
        val fragment = param.thisObject
        // 2nd exclusion layer: the fragment arguments carry `permName` (both
        // `InterceptPermissionFragment.v0` and `InterceptMIUIFragment.m0` copy it from the
        // launch Intent). No reward is worth guessing here: an unreadable permission means
        // this callback cannot tell whether it is about to confirm 关闭 MIUI 优化, so it
        // backs off and leaves the page to the user instead of auto-approving it. Layer 1
        // has already skipped the countdown, so the page is still immediately usable.
        val permName = runCatching {
            fragment.javaClass
                .getMethod("getArguments")
                .invoke(fragment) as? Bundle
        }.getOrNull()?.getString(KEY_PERM_NAME)

        if (permName == null) {
            DebugLog.w(TAG, "permission unreadable on the fragment; confirm not called")
            return
        }
        if (isExcludedPermission(permName)) {
            DebugLog.i(TAG, "excluded permission; confirm not called")
            return
        }
        runCatching {
            confirmMethod.invoke(fragment, true)
        }.onFailure { t ->
            DebugLog.w(TAG, "confirm call failed on ${confirmMethod.name}", t)
        }
    }

    // ─── USB 调试（安全设置）: the AdbInputApplyActivity 3-step countdown ───

    /**
     * Hooks the independent `AdbInputApplyActivity` flow used by 设置 → 开发者选项 →
     * 「USB 调试（安全设置）」. Its own `Handler` decrements a per-step counter and only
     * enables 「下一步」/「允许」 when it reaches zero, so neither the intercept hooks above
     * nor the countdown Bundle carry over. This path drives the native tick to completion
     * instead: `handleMessage` returns without re-arming once the counter hits zero, which
     * stops the tick chain and leaves the ROM's own button label and enable state in place.
     */
    private fun hookAdbInputApplyPath() {
        val activityClass = ADB_INPUT_APPLY_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "adb input path", "$ADB_INPUT_APPLY_CLASS not found")
            return
        }
        val onCreate = activityClass.declaredMethods.firstOrNull { method ->
            method.name == "onCreate" &&
                method.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
        } ?: run {
            DebugLog.hookSkipped(TAG, "adb input path", "onCreate(Bundle) not found")
            return
        }
        val handlerField = adbHandlerField(activityClass) ?: run {
            DebugLog.hookSkipped(TAG, "adb input path", "tick handler field not resolvable")
            return
        }
        DebugLog.d(TAG, "adb input tick handler field resolved: ${handlerField.name}")

        onCreate.isAccessible = true
        deoptimize(onCreate)
        onCreate.hook("warning_countdown_adb_input_on_create") {
            after { param ->
                HookFailurePolicy.open(TAG, "AdbInputApply.onCreate.after", Unit) {
                    applyToAdbInputPage(param.thisObject)
                }
            }
        }

        val onClick = activityClass.declaredMethods.firstOrNull { method ->
            method.name == "onClick" &&
                method.parameterTypes.contentEquals(arrayOf(View::class.java))
        } ?: run {
            DebugLog.hookSkipped(TAG, "adb input path", "onClick(View) not found")
            return
        }
        onClick.isAccessible = true
        deoptimize(onClick)
        onClick.hook("warning_countdown_adb_input_on_click") {
            after { param ->
                HookFailurePolicy.open(TAG, "AdbInputApply.onClick.after", Unit) {
                    if (selectedMode() == Preferences.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM) {
                        return@open
                    }
                    // 拒绝 finishes the page; any other click advanced a step and
                    // re-armed the countdown, so drive it to completion again.
                    val activity = param.thisObject as? Activity ?: return@open
                    if (activity.isFinishing) return@open
                    adbHandlerOf(activity)?.let { instantEnableAdbAccept(activity, it) }
                }
            }
        }
        DebugLog.i(TAG, "adb input path hooked on $ADB_INPUT_APPLY_CLASS")
    }

    private fun applyToAdbInputPage(activity: Any) {
        if (selectedMode() == Preferences.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM) return
        // The no-Mi-account branch returns before setContentView, so the page has no views
        // and has already handed off to the verification step by itself. Touching it here
        // would NPE inside the probe and would start that verification a second time.
        if (!adbWarningPageShown(activity)) return

        // 直接跳过警告: land the native result immediately instead of walking the 3 steps
        // and then waiting on the Mi-account risk check. When the write does not take,
        // fall through so the page is at least immediately tappable.
        if (selectedMode() == Preferences.SECURITY_CENTER_WARNING_DIRECT_APPROVE &&
            grantAdbInput(activity)
        ) {
            DebugLog.i(TAG, "adb input page granted directly; verification bypassed")
            return
        }

        val handler = adbHandlerOf(activity) ?: return
        val enabled = instantEnableAdbAccept(activity, handler)
        if (!enabled) {
            DebugLog.w(TAG, "adb input countdown not driven; native page kept")
        }
    }

    private fun adbHandlerField(activityClass: Class<*>): Field? =
        activityClass.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) &&
                Handler::class.java.isAssignableFrom(field.type)
        }.singleOrNull()

    /** False on the early-return branch, whose view fields are still null. */
    private fun adbWarningPageShown(activity: Any): Boolean {
        val buttonFields = activity.javaClass.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) && Button::class.java.isAssignableFrom(field.type)
        }
        if (buttonFields.isEmpty()) return false
        return buttonFields.any { field ->
            runCatching {
                field.isAccessible = true
                field.get(activity) != null
            }.getOrDefault(false)
        }
    }

    private fun adbHandlerOf(activity: Any): Handler? =
        adbHandlerField(activity.javaClass)?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(activity) as? Handler
            }.getOrNull()
        }

    /**
     * Finishes the native countdown immediately: clear the pending tick, set the counter
     * to 1 and run the host's own `handleMessage` once. The host decrements it to 0, applies
     * its own 「下一步」/「允许」 label plus enable state by its current step, and returns
     * without re-arming, so no further tick is scheduled.
     */
    private fun instantEnableAdbAccept(activity: Any, handler: Handler): Boolean {
        val countdown = resolveAdbCountdownField(activity, handler) ?: return false
        val prepared = runCatching {
            handler.removeCallbacksAndMessages(null)
            countdown.setInt(activity, 1)
        }.isSuccess
        if (!prepared) return false
        return try {
            handler.handleMessage(Message.obtain())
            runCatching { handler.removeCallbacksAndMessages(null) }
            true
        } catch (t: Throwable) {
            DebugLog.w(TAG, "adb input countdown drive failed", t)
            false
        }
    }

    /**
     * Identifies the countdown counter without relying on its obfuscated name: the tick
     * handler decrements its own int field, so one native tick reveals it as the single int
     * field whose value changed. Only successful resolutions are cached; a failed probe is
     * retried on the next page instead of disabling the path for the whole process.
     */
    private fun resolveAdbCountdownField(activity: Any, handler: Handler): Field? {
        val activityClass = activity.javaClass
        countdownFields[activityClass]?.let { return it }

        val intFields = activityClass.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) && field.type == java.lang.Integer.TYPE
        }
        if (intFields.isEmpty()) return null
        intFields.forEach { it.isAccessible = true }
        val before = intFields.map { runCatching { it.getInt(activity) }.getOrNull() }

        // One probe tick: the host decrements its own counter, and re-arms a tick we drop.
        val ticked = runCatching { handler.handleMessage(Message.obtain()) }.isSuccess
        runCatching { handler.removeCallbacksAndMessages(null) }
        if (!ticked) {
            DebugLog.w(TAG, "adb input probe tick failed; native countdown kept")
            return null
        }

        val changed = intFields.filterIndexed { index, field ->
            val previous = before[index] ?: return@filterIndexed false
            val current = runCatching { field.getInt(activity) }.getOrNull() ?: return@filterIndexed false
            previous != current
        }
        val resolved = changed.singleOrNull()
        if (resolved == null) {
            DebugLog.w(TAG, "adb input countdown field ambiguous; native countdown kept")
            return null
        }
        countdownFields[activityClass] = resolved
        return resolved
    }

    /**
     * Mode 1's own final step: once the user taps 「允许」 the native page starts
     * `AdbInstallVerifyActivity` (`is_input=true`), which shows a progress dialog and asks
     * `srv.sec.miui.com` for a risk verdict before granting. That check gates nothing the
     * user asked to keep, so it is bypassed here by landing the same result the native
     * success path would (`AdbInputApplyActivity.G0(true)` → `persist.security.adbinput=1`)
     * and letting the page finish. The install-debug variant (`is_input=false`, a different
     * feature) is deliberately left native.
     */
    private fun hookAdbVerifyPath() {
        val verifyClass = ADB_VERIFY_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "adb verify path", "$ADB_VERIFY_CLASS not found")
            return
        }
        val onCreate = verifyClass.declaredMethods.firstOrNull { method ->
            method.name == "onCreate" &&
                method.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
        } ?: run {
            DebugLog.hookSkipped(TAG, "adb verify path", "onCreate(Bundle) not found")
            return
        }

        onCreate.isAccessible = true
        deoptimize(onCreate)
        onCreate.hook("warning_countdown_adb_verify_on_create") {
            after { param ->
                HookFailurePolicy.open(TAG, "AdbInstallVerify.onCreate.after", Unit) {
                    if (selectedMode() == Preferences.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM) {
                        return@open
                    }
                    val activity = param.thisObjectOrNull as? Activity ?: return@open
                    val isInputFlow = runCatching {
                        activity.intent?.getBooleanExtra(EXTRA_IS_INPUT, false) == true
                    }.getOrDefault(false)
                    if (!isInputFlow) return@open
                    if (grantAdbInput(activity)) {
                        DebugLog.i(TAG, "adb input verification bypassed; native result applied")
                    }
                }
            }
        }
        DebugLog.i(TAG, "adb verify path hooked on $ADB_VERIFY_CLASS")
    }

    /**
     * Lands the native success result of the 安全设置 USB-debugging flow: write
     * `persist.security.adbinput=1` through the host's own property helper and finish the
     * page (its `finish()` override sets `RESULT_OK`, which is what the Settings caller
     * reads). Nothing else is touched — no account check, no extra permission, no other
     * property.
     */
    private fun grantAdbInput(activity: Any): Boolean {
        if (!writeAdbInputProperty()) return false
        // `finish()` is overridden by the host to publish RESULT_OK before finishing, so it
        // must be dispatched virtually rather than replaced by a bare Activity#finish call.
        runCatching { (activity as? Activity)?.finish() }
            .onFailure { t -> DebugLog.w(TAG, "adb input page finish failed", t) }
        return true
    }

    private fun writeAdbInputProperty(): Boolean {
        val accessors = propertyAccessors ?: run {
            DebugLog.w(TAG, "property accessors unavailable; adb input result not applied")
            return false
        }
        return runCatching {
            // The host's compat wrapper declares an `int` return for the void native setter,
            // so its value is meaningless (it always reports 0); the write still happens and
            // is therefore verified by reading the property back.
            accessors.write.invoke(null, ADB_INPUT_PROPERTY, "1")
            accessors.read.invoke(null, ADB_INPUT_PROPERTY, "") as? String == "1"
        }.getOrElse { t ->
            DebugLog.w(TAG, "adb input property write failed", t)
            false
        }
    }

    /** The host's own property helper, falling back to the platform class directly. */
    private fun resolvePropertyAccessors(): PropertyAccessors? {
        PROPERTY_COMPAT_CLASS.toClassOrNull()?.let { compat ->
            val write = runCatching {
                compat.getMethod("set", String::class.java, String::class.java)
            }.getOrNull()
            val read = runCatching {
                compat.getMethod("getString", String::class.java, String::class.java)
            }.getOrNull()
            if (write != null && read != null) return PropertyAccessors(write, read)
        }
        val platform = runCatching {
            Class.forName("android.os.SystemProperties", false, classLoader)
        }.getOrNull() ?: return null
        val write = runCatching {
            platform.getMethod("set", String::class.java, String::class.java)
        }.getOrNull()
        val read = runCatching {
            platform.getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
        return if (write != null && read != null) PropertyAccessors(write, read) else null
    }
}
