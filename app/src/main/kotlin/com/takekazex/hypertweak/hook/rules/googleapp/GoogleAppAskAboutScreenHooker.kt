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
 * delivers. Mirrors upstream MiuiBackGestureHook commit `0f603b1d`, verified against 17.48.13
 * (dex string literals survive R8 renaming each build):
 *
 * 1. **Navigation anchor** — the unique 0-arg non-void method referencing all three literals
 *    `com.google.android.apps.search.lens.user` + `45781832` (AIM searchbox) + `45765529`
 *    (AIM screen context) is `wry.iX()`, the Dagger factory that builds the Lensient model
 *    `doqf` (wry.java:9590; both flags registered default-false at wvx.java:3499-3500).
 * 2. **Model constructor** — `wry.iX()`'s only invoked constructor matching its return type is
 *    `doqf.<init>` (41 params); its 7th parameter type (index 6) is the **coordinator** `djyp`
 *    (wri.java:430 builds it).
 * 3. **Capability** — `djyp.<init>` (djyp.java:27) computes `this.d = ((bydc) gpgxVar.hS()).c()`;
 *    `bydc.c()` (0-arg boolean, invoked only through that virtual call) is the unique
 *    screen-thumbnail-retention gate, lazily server-fetched (`bycz` coroutine) and false on
 *    stock. It is the single decision point `djyp.b()/c()/d()` feed (`dnrk.java:92` bottom bar,
 *    `dopu.java:681` / `dopx.java:77` searchbox, `dnpr.java:31`, `dnpk.java:4469`).
 *
 * The hook after-forces the capability's successful `false` result to `true` while the feature
 * is on, exactly like upstream's `overrideGoogleLensScreenCapability`. It does **not** forge a
 * thumbnail, spoof Build identity, bypass consent, or manufacture a capture/token path — the
 * OMNI overlay itself is the Circle-to-Search capture session, so no screenshot injection is
 * needed on this surface (unlike the Robin floaty attachment sheet, see
 * GOOGLE_APP_ASK_ABOUT_SCREEN_PLAN.md §5).
 *
 * Resolution is fail-closed: ambiguous, missing, or unreadable matches install nothing. The
 * coordinator constructor and every caller of the capability are deoptimized so ART cannot
 * AOT-inline the read past the hooked method. The Google app is a declared required Xposed
 * scope (see `scope.list` and `ScopeManager`), so the switch flips the preference and restarts
 * the app; disabled (default) installs nothing.
 */
object GoogleAppAskAboutScreenHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    const val PACKAGE = "com.google.android.googlequicksearchbox"

    private const val TAG = "AskAboutScreen"

    /** Navigation anchors: the Lensient AIM model factory references all three literals. */
    private const val LENS_USER_NAMESPACE = "com.google.android.apps.search.lens.user"
    private const val FLAG_AIM_SEARCHBOX = "45781832"
    private const val FLAG_AIM_SCREEN_CONTEXT = "45765529"

    /** Expected coordinator slot in the model constructor (index 6 on 17.48.13). */
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
                ", deoptimized=$deoptimized/${target.callers.size + 1}"
        )
    }

    /**
     * Resolves `bydc.c()` through the upstream 0f603b1d chain. Every step must be unique;
     * any ambiguity, gap, or unreadable dex entry fails closed (returns null).
     */
    private fun resolveTarget(bridge: DexKitBridge): Target? {
        // 1. Unique 0-arg non-void consumer referencing the three navigation anchors.
        val consumer = bridge.findMethod {
            matcher {
                paramCount(0)
                usingEqStrings(LENS_USER_NAMESPACE, FLAG_AIM_SEARCHBOX, FLAG_AIM_SCREEN_CONTEXT)
            }
        }.filter { it.returnTypeName != "void" }.singleOrNull() ?: return null

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
        return Target(capability, coordinatorConstructor, callers)
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
        val callers: List<Method>
    )
}