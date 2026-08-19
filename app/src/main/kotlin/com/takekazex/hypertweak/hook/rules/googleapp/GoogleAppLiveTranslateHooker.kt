package com.takekazex.hypertweak.hook.rules.googleapp

import android.content.pm.PackageManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * Shows the full-screen live-translate (屏幕实时翻译 / 滚动并翻译) button inside Circle to Search
 * (即圈即搜) — the Google app's Lens OMNI overlay, whose bottom action bar is built by the
 * `OmniBoxView` constructor `dnrk.<init>`.
 *
 * The button is hidden by default through Google-side gates that only a server flag push or an
 * OMNI entry carrying a MediaProjection token can open. Verified against 17.48.13 (dex anchors are
 * real dex string/number literals, so they survive R8 renaming each build):
 *
 * 1. **Master flag** — `wtz.lu()` reads `com.google.android.apps.search.lens.user / 45785436`
 *    (default false), absorbed into `dmkp.T = dmjh.e() && lu()`.
 * 2. **System feature** — `dmkp.f = dmjh.f() /* OMNI */ && 45716363 && (45724135 || system feature
 *    CONTEXTUAL_SEARCH_LIVE_TRANSLATE)`. HyperOS never declares the feature, so we make
 *    `PackageManager.hasSystemFeature` answer true for exactly that name.
 * 3. **Media-projection display gate** — `dmwn.a()` refuses to show the button unless the
 *    invocation intent carries `android.media.projection.extra.EXTRA_MEDIA_PROJECTION` (`dmwm`
 *    predicate).
 *
 * The button's visibility decision is `dmwl.i() = dmkp.T && dmwn.a()`, consumed by `dnrk` for the
 * View path (inflate `lens_omnibox_live_translate_button`) and by `dmhc.j.a()` (= `dmwn.a()`) for
 * the Compose path (`dmit`'s `"live_translate_button"`).
 *
 * **Why gate 3 alone can never work on this device (root cause):** the invocation intent carries
 * no EXTRA_MEDIA_PROJECTION (HyperOS does not inject a projection token), and `cnov` only copies
 * the inbound intent into `"invocation-intent"` when that extra is present, so `dkne.p()`
 * (the `omnientInvocationIntent` Optional) is **empty**. `dmwn.a()` evaluates
 * `Optional<Intent>.map(dmwm).orElse(false)` — an empty Optional short-circuits `dmwm.apply`, it
 * is never invoked, and the gate settles on `false`. The `dmwm` hook is therefore dead code on a
 * stock device; only hooking the decision point itself can open the button.
 *
 * The primary hooks are therefore the two 0-arg boolean decision methods — `dmwl.i()` (View path)
 * and `dmwn.a()` (Compose path, plus it backs `dmwl.i()`). Both are reached through non-inlinable
 * virtual calls (`invoke-interface Ldjxk;->i()` / `invoke-virtual Ldmwn;->a()Z`), so they cannot
 * be sunk by R8 or ART. The bean `dmwl` is resolved via its unique action-id method
 * `int a() { return 271520; }` (the same id its View/Compose consumers reference), and `dmwn` is
 * then taken from the bean's constructor parameter type, so nothing hardcodes an obfuscated name.
 *
 * On tap (`dmwj.startLiveTranslate`) the app still works normally: when the intent has a token it
 * reuses it (direct `enaz` path), otherwise it launches
 * `MediaProjectionPermissionCheckerActivity` and asks the user once for screen-recording — that
 * legitimate system authorization is intentionally left untouched.
 *
 * The Google app is a declared required Xposed scope (see `scope.list` and `ScopeManager`), so
 * the switch only flips the preference and restarts the app; the hooks then read the preference
 * live, so turning the feature off is a no-op restart away from stock behaviour.
 */
object GoogleAppLiveTranslateHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    const val PACKAGE = "com.google.android.googlequicksearchbox"

    private const val TAG = "FullScreenTranslate"

    /** Master iris-user flag; default false; single reader on 17.48.13 is `wtz.lu()`. */
    private const val FLAG_LIVE_TRANSLATE = "45785436"

    /** System feature that would otherwise keep the capability gate shut on HyperOS. */
    private const val FEATURE_LIVE_TRANSLATE =
        "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE"

    /** Display gate: the omnibox only inflates the button when the intent carries this token. */
    private const val EXTRA_MEDIA_PROJECTION = "android.media.projection.extra.EXTRA_MEDIA_PROJECTION"

    /**
     * Live-translate action id, hardcoded by the app (not a resource id, so it is stable across
     * builds): `dmwl.a()` returns it, and the View consumer `dljh` / Compose consumer `dmit` map
     * the same id back to the bean. The bean's class carries a 0-arg `int a()` and the 0-arg
     * `boolean i()` that gates the button.
     */
    private const val ACTION_LIVE_TRANSLATE = 271520

    @Volatile
    private var enabledCache = false

    private fun featureEnabled(): Boolean {
        if (Preferences.isInitialized) {
            enabledCache = Preferences.getBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)
        }
        return enabledCache
    }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        enabledCache = Preferences.getBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)
        if (!enabledCache) {
            DebugLog.i(TAG, "feature disabled; not installing hooks")
            return
        }
        installGateHooks()
    }

    private fun installGateHooks() {
        // Reliable: framework method, cannot be R8-inlined at the dmkp call site.
        hookHasSystemFeature()
        // Display gate: invoked through a Guava Function interface, cannot be inlined.
        hookMediaProjectionGate()
        // Best-effort master flag leaf (may be sunk into its single call site by R8).
        hookFlagLeaf(FLAG_LIVE_TRANSLATE)
        // The on-device decision points the flags feed into. These are the ones that actually
        // win: `dmwl.i()` (View path) and `dmwn.a()` (Compose path + backs dmwl.i()), both
        // reached through non-inlinable virtual calls.
        hookActionBeanVisibility()

        DebugLog.i(TAG, "gate hooks installed")
    }

    // ─── Gate 2: system feature ───────────────────────────────────────────────────

    private fun hookHasSystemFeature() {
        val impl = runCatching {
            Class.forName("android.app.ApplicationPackageManager", false, classLoader)
        }.getOrNull()
        val base = runCatching {
            Class.forName("android.content.pm.PackageManager", false, classLoader)
        }.getOrNull()

        for (clazz in listOfNotNull(base, impl)) {
            runCatching {
                val method = clazz.getDeclaredMethod("hasSystemFeature", String::class.java)
                deoptimize(method)
                method.hook {
                    after { param ->
                        // Check the feature name first so the preference read only happens for
                        // the rare matching query, not on every process-wide hasSystemFeature call.
                        if (param.args[0] == FEATURE_LIVE_TRANSLATE && featureEnabled()) {
                            param.result = true
                        }
                    }
                }
                DebugLog.d(TAG, "hasSystemFeature hook installed on ${clazz.name}")
            }.onFailure { t ->
                DebugLog.w(TAG, "hasSystemFeature hook failed on ${clazz.name}", t)
            }
        }
    }

    // ─── Gate 3: EXTRA_MEDIA_PROJECTION display predicate ─────────────────────────

    private fun hookMediaProjectionGate() {
        methodsUsingString(EXTRA_MEDIA_PROJECTION).forEach { md ->
            val method = methodFor(md) ?: return@forEach
            // The display predicate is a Guava `Function<InvocationIntent, Boolean>` whose JVM
            // signature erases to `Object apply(Object)` (the boxed Boolean returns through the
            // erased `Object` slot — `dmwm.apply`, verified in 17.48.13), so it is matched for the
            // plain `boolean`/`Boolean` forms and the erased `Object` form alike. Methods that
            // merely move the binder around (the deeplink gateway) are excluded by the
            // single-`Object`-parameter shape, which none of them has.
            if (method.parameterTypes.size != 1 ||
                method.parameterTypes[0] != Any::class.java ||
                (method.returnType != Boolean::class.java &&
                    method.returnType != java.lang.Boolean.TYPE &&
                    method.returnType != Any::class.java)
            ) {
                return@forEach
            }
            deoptimize(method)
            method.hook {
                after { param ->
                    if (featureEnabled()) param.result = true
                }
            }
            DebugLog.d(TAG, "media-projection display predicate hooked on $method")
        }
    }

    // ─── Gate 1: master flag leaf ─────────────────────────────────────────────────

    private fun hookFlagLeaf(anchor: String) {
        methodsUsingString(anchor).forEach { md ->
            val method = methodFor(md) ?: return@forEach
            if (method.parameterCount != 0 || method.returnType != java.lang.Boolean.TYPE) {
                return@forEach
            }
            deoptimize(method)
            method.hook {
                after { param ->
                    if (featureEnabled()) param.result = true
                }
            }
            DebugLog.d(TAG, "master flag leaf hooked on $method")
        }
    }

    // ─── Primary: action-bean visibility (dmwl.i) + capability gate (dmwn.a) ─────

    /**
     * Hooks the two on-device decision points for the live-translate button:
     *
     * - `dmwl.i()` (0-arg boolean) — the View path (`dnrk` inflates
     *   `lens_omnibox_live_translate_button` only when `djxk.i()` is true). This is the single
     *   verdict `dmkp.T && dmwn.a()`; forcing it true bypasses the master flag, the caller-type
     *   gate, and the (on this device dead) media-projection Optional.
     * - `dmwn.a()` (0-arg boolean), derived from the bean's constructor parameter type — the
     *   Compose path (`dmit`'s "live_translate_button" is gated on `dmhc.j.a()` = `dmwn.a()`).
     *
     * Both are reached through virtual interface calls (`invoke-interface Ldjxk;->i()` /
     * `invoke-virtual Ldmwn;->a()Z`), so R8/ART cannot inline or sink them.
     */
    private fun hookActionBeanVisibility() {
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.w(TAG, "no sourceDir; cannot resolve action bean")
            return
        }
        val beanClass = DexKitManager.withBridge(apkPath) { bridge ->
            bridge.findMethod { matcher { usingNumbers(ACTION_LIVE_TRANSLATE) } }
                .firstOrNull {
                    // `dmwl.a()` is the only 0-arg method that returns the live-translate action
                    // id; its consumers (`dljh`'s render path, `dmit`'s compose lambda) reference
                    // the same id from multi-arg methods, so the shape is already unique.
                    it.paramCount == 0 && it.methodName == "a"
                }
                ?.let { materializeClass(it.className) }
        } ?: run {
            DebugLog.w(TAG, "live-translate action bean (a()==$ACTION_LIVE_TRANSLATE) not resolved")
            return
        }

        val visibility = beanClass.declaredMethods.firstOrNull {
            it.name == "i" && it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
        }
        if (visibility != null) {
            visibility.isAccessible = true
            deoptimize(visibility)
            visibility.hook {
                after { param -> if (featureEnabled()) param.result = true }
            }
            DebugLog.i(TAG, "action bean i() hooked on ${beanClass.name}")
        } else {
            DebugLog.w(TAG, "action bean i() not found on ${beanClass.name}")
        }

        // dmwn is the second constructor parameter of the bean (dmwl(dmkp, dmwn, dmwk)).
        val capabilityClass = runCatching {
            beanClass.declaredConstructors.firstOrNull { it.parameterCount == 3 }
                ?.parameterTypes?.getOrNull(1)
        }.getOrNull()
        if (capabilityClass != null) {
            val capability = capabilityClass.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
            }
            if (capability != null) {
                capability.isAccessible = true
                deoptimize(capability)
                capability.hook {
                    after { param -> if (featureEnabled()) param.result = true }
                }
                DebugLog.i(TAG, "capability a() hooked on ${capabilityClass.name}")
            } else {
                DebugLog.w(TAG, "capability a() not found on ${capabilityClass.name}")
            }
        } else {
            DebugLog.i(TAG, "action bean ${beanClass.name} has no 3-arg constructor; skimming dmwn")
        }
    }

    // ─── DexKit method resolution ─────────────────────────────────────────────────

    private fun methodsUsingString(anchor: String): List<MethodData> {
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.w(TAG, "no sourceDir; cannot resolve '$anchor'")
            return emptyList()
        }
        return DexKitManager.withBridge(apkPath) { bridge ->
            bridge.findMethod { matcher { usingStrings(anchor) } }
        } ?: emptyList()
    }

    private fun materializeClass(dexName: String): Class<*>? = runCatching {
        val normalized = dexName.removePrefix("L").removeSuffix(";").replace('/', '.')
        Class.forName(normalized, false, classLoader)
    }.onFailure { t ->
        DebugLog.w(TAG, "failed to load class $dexName", t)
    }.getOrNull()

    private fun methodFor(md: MethodData): Method? = runCatching {
        val dexName = md.className.removePrefix("L").removeSuffix(";").replace('/', '.')
        val clazz = Class.forName(dexName, false, classLoader)
        val method = if (md.paramCount == 0) {
            clazz.declaredMethods.firstOrNull {
                it.name == md.methodName && it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
            }
        } else {
            clazz.declaredMethods.firstOrNull {
                it.name == md.methodName && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Any::class.java
            }
        }
        method?.apply { isAccessible = true }
    }.onFailure { t ->
        DebugLog.w(TAG, "failed to materialize method for ${md.className}#${md.methodName}", t)
    }.getOrNull()
}
