package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Shows "Ask about this screen" (针对屏幕内容提问) inside the Circle to Search (即圈即搜)
 * **Lensient searchbox** — the OMNI overlay's AI search box (`:googleapp` process), where the
 * zero-state hint `lens_lensient_searchbox_aim_text` advertises the feature. This is a different
 * surface from the live-translate bottom-bar button (`GoogleAppLiveTranslateHooker`): the ask
 * flows through the Lensient screen-capability gate rather than a `djxk` action bean.
 *
 * The feature is hidden by default through a server-driven capability that HyperOS never
 * delivers. Mirrors upstream MiuiBackGestureHook commit `0f603b1d` (dex string literals survive
 * R8 renaming each build). Verified chains — class/method names are per-build, only the shape
 * and the anchor literals carry over:
 *
 * | build | factory | model (coordinator idx 6) | capability |
 * |---|---|---|---|
 * | 17.48.13 | `wry.iX()` | `doqf` (41 params, `djyp`) | `bydc.c()` |
 * | 17.57.11 | `wzb.jb()` | `dsnc` (39 params, `dntz`) | `cbof.c()` |
 *
 * 1. **Navigation anchor** — the unique 0-arg non-void method referencing
 *    `com.google.android.apps.search.lens.user` plus the AIM screen-context flag `45765529`
 *    (registered default-false with its siblings) is the Dagger factory that builds the
 *    Lensient model. 17.48.13 also read the AIM searchbox flag `45781832` there; 17.57.11
 *    dropped that read site (the flag is still registered, never read), which is why the
 *    anchor is a list of literal sets rather than one hard-coded triple.
 * 2. **Model constructor** — the factory's only invoked constructor matching its return type;
 *    its 7th parameter (index 6) is the **coordinator**.
 * 3. **Capability** — the coordinator's constructor computes
 *    `this.d = ((capability) provider.hS()).c()` (17.48.13 `djyp.java:27`, 17.57.11
 *    `dntz.java:27`); the 0-arg boolean `c()` (invoked only through that virtual call) is the
 *    unique screen-thumbnail-retention gate, lazily server-fetched and false on stock. It is
 *    the single decision point the coordinator's `b()/c()/d()` feed (`dnrk.java:92` bottom bar,
 *    `dopu.java:681` / `dopx.java:77` searchbox, `dnpr.java:31`, `dnpk.java:4469` on 17.48.13).
 *
 * The hook after-forces the capability's successful `false` result to `true` while the feature
 * is on, exactly like upstream's `overrideGoogleLensScreenCapability`. It does **not** forge a
 * thumbnail, spoof Build identity, bypass consent, or manufacture a capture/token path — the
 * OMNI overlay itself is the Circle-to-Search capture session, so no screenshot injection is
 * needed on this surface (unlike the Robin floaty attachment sheet, see
 * docs/GOOGLE_APP_ASK_ABOUT_SCREEN_PLAN.md §5).
 *
 * Resolution is fail-closed: ambiguous, missing, or unreadable matches install nothing. The
 * coordinator constructor and every caller of the capability are deoptimized so ART cannot
 * AOT-inline the read past the hooked method. The Google app is a declared required Xposed
 * scope (see `scope.list` and `ScopeManager`), so the switch flips the preference and queues
 * the app in the Home restart dialog; disabled (default) installs nothing.
 */
object GoogleAppAskAboutScreenHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    const val PACKAGE = "com.google.android.googlequicksearchbox"

    private const val TAG = "AskAboutScreen"

    /** Navigation anchors: literal sets the Lensient AIM model factory is known to use. */
    private const val LENS_USER_NAMESPACE = "com.google.android.apps.search.lens.user"

    /** AIM screen-context flag; the factory's one anchor literal that survived both builds. */
    private const val FLAG_AIM_SCREEN_CONTEXT = "45765529"

    /** AIM searchbox flag: read by the factory on 17.48.13, register-only since 17.57.11. */
    private const val FLAG_AIM_SEARCHBOX = "45781832"

    /** Sibling flag the factory reads on every verified build, pinning the anchor to it. */
    private const val FLAG_AIM_MODEL_SIBLING = "45710957"

    /**
     * Anchor sets tried in order, strictest first. Each must match exactly one 0-arg non-void
     * method; an ambiguous set is skipped and no unique match anywhere fails closed. The list
     * exists because Google moves flag reads in and out of the factory between builds — the
     * final set only needs the screen-context flag that names the feature.
     */
    private val FACTORY_ANCHORS = listOf(
        listOf(LENS_USER_NAMESPACE, FLAG_AIM_SCREEN_CONTEXT, FLAG_AIM_MODEL_SIBLING),
        listOf(LENS_USER_NAMESPACE, FLAG_AIM_SCREEN_CONTEXT, FLAG_AIM_SEARCHBOX),
        listOf(LENS_USER_NAMESPACE, FLAG_AIM_SCREEN_CONTEXT)
    )

    /** Expected coordinator slot in the model constructor (index 6 on both verified builds). */
    private const val COORDINATOR_PARAM_INDEX = 6

    @Volatile
    private var enabledCache = false

    private fun featureEnabled(): Boolean {
        if (Preferences.isInitialized) {
            enabledCache = Preferences.getBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)
        }
        return enabledCache
    }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        enabledCache = Preferences.getBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)
        if (!enabledCache) {
            DebugLog.i(TAG, "feature disabled; not installing hooks")
            return
        }
        installCapabilityHook()
    }

    private fun installCapabilityHook() {
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.w(TAG, "no sourceDir; cannot resolve the Lensient screen capability")
            return
        }
        val target = DexKitManager.withBridge(apkPath) { bridge ->
            resolveTarget(bridge)
        } ?: run {
            DebugLog.w(TAG, "Lensient screen capability not resolved; failing closed")
            return
        }

        // Deoptimize the coordinator constructor and every capability caller so ART cannot
        // AOT-inline the gate read past the hooked method (same defense as upstream).
        var deoptimized = 0
        try {
            deoptimize(target.coordinatorConstructor)
            deoptimized++
        } catch (_: Throwable) {
        }
        for (caller in target.callers) {
            try {
                deoptimize(caller)
                deoptimized++
            } catch (_: Throwable) {
            }
        }
        try {
            deoptimize(target.capability)
        } catch (_: Throwable) {
        }

        target.capability.isAccessible = true
        target.capability.hook {
            after { param ->
                if (featureEnabled()) param.result = true
            }
        }
        DebugLog.i(
            TAG,
            "HOOK_OK Lensient screen capability on ${target.capability}" +
                " via ${target.factorySign}" +
                ", deoptimized=$deoptimized/${target.callers.size + 1}"
        )
    }

    /**
     * Locates the Lensient AIM model factory (17.48.13 `wry.iX()`, 17.57.11 `wzb.jb()`) with the
     * first anchor set that matches exactly one 0-arg non-void method.
     */
    private fun findModelFactory(bridge: DexKitBridge): MethodData? {
        for (anchors in FACTORY_ANCHORS) {
            val matches = bridge.findMethod {
                matcher {
                    paramCount(0)
                    usingEqStrings(anchors)
                }
            }.filter { it.returnTypeName != "void" }
            if (matches.size == 1) return matches.single()
        }
        return null
    }

    /**
     * Resolves the capability gate (17.48.13 `bydc.c()`, 17.57.11 `cbof.c()`) through the
     * upstream 0f603b1d chain. Every step must be unique; any ambiguity, gap, or unreadable dex
     * entry fails closed (returns null).
     */
    private fun resolveTarget(bridge: DexKitBridge): Target? {
        // 1. Unique 0-arg non-void consumer referencing the navigation anchors. This is the step
        //    an OTA breaks first (the flag literals it reads move between builds), so name it.
        val consumer = findModelFactory(bridge) ?: run {
            DebugLog.w(TAG, "no unique Lensient AIM model factory for anchors $FACTORY_ANCHORS")
            return null
        }

        // 2. The model constructor is the invoked constructor matching the consumer return type.
        val modelConstructor = consumer.invokes.filter {
            it.isConstructor && it.declaredClassName == consumer.returnTypeName
        }.singleOrNull() ?: return null

        // 3. The coordinator is the expected parameter type of the model constructor.
        val coordinatorName = modelConstructor.paramTypeNames.getOrNull(COORDINATOR_PARAM_INDEX)
            ?: return null
        if (materializeClass(coordinatorName) == null) return null

        // 4. The capability is the unique 0-arg boolean invoke inside the coordinator's
        //    constructor(s), not declared by the coordinator itself.
        var coordinatorConstructorData: MethodData? = null
        var capabilityData: MethodData? = null
        for (candidate in bridge.findMethod {
            matcher {
                declaredClass(coordinatorName, StringMatchType.Equals)
                name("<init>")
            }
        }) {
            var constructorCapability: MethodData? = null
            for (invoke in candidate.invokes) {
                if (!invoke.isMethod ||
                    invoke.paramCount != 0 ||
                    invoke.returnTypeName != "boolean" ||
                    invoke.declaredClassName == coordinatorName
                ) {
                    continue
                }
                if (constructorCapability != null && constructorCapability != invoke) {
                    constructorCapability = null
                    break
                }
                constructorCapability = invoke
            }
            if (constructorCapability == null) continue
            if (coordinatorConstructorData != null &&
                (coordinatorConstructorData != candidate || capabilityData != constructorCapability)
            ) {
                return null
            }
            coordinatorConstructorData = candidate
            capabilityData = constructorCapability
        }
        if (coordinatorConstructorData == null || capabilityData == null) return null

        // 5. Materialize the hook targets and the capability's callers.
        val coordinatorConstructor = runCatching {
            coordinatorConstructorData.getConstructorInstance(classLoader)
        }.getOrNull() ?: return null
        val capability = runCatching {
            capabilityData.getMethodInstance(classLoader)
        }.getOrNull() ?: return null

        val callers = mutableListOf<Method>()
        for (caller in capabilityData.callers) {
            if (!caller.isMethod) continue
            runCatching { caller.getMethodInstance(classLoader) }
                .getOrNull()
                ?.let { if (it !in callers) callers.add(it) }
        }
        return Target(
            capability,
            coordinatorConstructor,
            callers,
            "${consumer.declaredClassName}#${consumer.name}"
        )
    }

    private fun materializeClass(dexName: String): Class<*>? = runCatching {
        val normalized = dexName.removePrefix("L").removeSuffix(";").replace('/', '.')
        Class.forName(normalized, false, classLoader)
    }.onFailure { t ->
        DebugLog.w(TAG, "failed to load class $dexName", t)
    }.getOrNull()

    private class Target(
        val capability: Method,
        val coordinatorConstructor: Constructor<*>,
        val callers: List<Method>,
        val factorySign: String
    )
}
