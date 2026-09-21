package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Shows the full-screen live-translate button inside Google App's Circle to Search overlay.
 *
 * The runtime resolver has no obfuscated class or method names and uses no constructor arity or
 * parameter index. It starts from Google's semantic live-translate action id, then requires one
 * action class with a cached boolean visibility method, the unique constructor that writes that
 * field, and a zero-argument boolean capability call on a uniquely stored constructor dependency.
 * Missing or ambiguous links fail closed. The action id is a product identifier, not a code path.
 *
 * The remaining compatibility hooks are anchored by stable contracts: Google's named system
 * feature, the Android media-projection extra, and the Google flag id. String-anchored hooks also
 * require a unique target with the expected runtime signature. All callbacks read the preference
 * live, so disabling the feature restores Google's original decisions.
 */
object GoogleAppLiveTranslateHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    const val PACKAGE = "com.google.android.googlequicksearchbox"

    private const val TAG = "FullScreenTranslate"

    /** Google server-side flag identifier used as an optional, obfuscation-independent anchor. */
    private const val FLAG_LIVE_TRANSLATE = "45785436"

    /** System feature that would otherwise keep the capability gate shut on HyperOS. */
    private const val FEATURE_LIVE_TRANSLATE =
        "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE"

    /** Display gate: the omnibox only inflates the button when the intent carries this token. */
    private const val EXTRA_MEDIA_PROJECTION = "android.media.projection.extra.EXTRA_MEDIA_PROJECTION"

    /** Stable Google product action identifier; used only as a semantic DEX anchor. */
    private const val ACTION_LIVE_TRANSLATE = 271520

    private const val HOOK_SYSTEM_FEATURE = "google_live_translate_system_feature"
    private const val HOOK_SYSTEM_FEATURE_BASE = "google_live_translate_system_feature_base"
    private const val HOOK_ACTION_VISIBILITY = "google_live_translate_action_visibility"
    private const val HOOK_CAPABILITY = "google_live_translate_capability"

    private data class ActionTargets(
        val visibility: Method,
        val capability: Method
    )

    @Volatile
    private var enabledCache = false

    private fun featureEnabled(): Boolean {
        enabledCache = Preferences.getBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)
        return enabledCache
    }

    override fun onHook() {
        // GoogleAppRuntime owns the one shared DexKit session. Keeping this child hooker inert
        // prevents an independent scan when the feature runtimes are attached below it.
    }

    /** Installs the Google feature hooks from the coordinator's shared DexKit bridge. */
    internal fun installSystemFeature(existingHookIds: Set<String> = emptySet()) {
        hookHasSystemFeature(existingHookIds)
    }

    /** Installs the DexKit-backed Google feature hooks from the coordinator's shared bridge. */
    internal fun installWithBridge(
        bridge: DexKitBridge,
        existingHookIds: Set<String> = emptySet()
    ) {
        // The host strings identify semantic leaves; each child resolver fails closed on ambiguity.
        hookMediaProjectionGate(bridge)
        // Best-effort master flag leaf (may be sunk into its single call site by R8).
        hookFlagLeaf(bridge, FLAG_LIVE_TRANSLATE)
        hookActionBeanVisibility(bridge, existingHookIds)

        DebugLog.i(TAG, "gate hooks installed")
    }

    /** Replacement callbacks used before a hot-reload generation resolves DexKit again. */
    internal fun replacement(id: String): XposedInterface.Hooker? = when (id) {
        HOOK_SYSTEM_FEATURE,
        HOOK_SYSTEM_FEATURE_BASE -> XposedInterface.Hooker { chain ->
            overrideLiveTranslateSystemFeature(chain)
        }
        HOOK_ACTION_VISIBILITY -> XposedInterface.Hooker { chain ->
            overrideLiveTranslateActionVisibility(chain)
        }
        HOOK_CAPABILITY -> XposedInterface.Hooker { chain ->
            overrideLiveTranslateBooleanGate(chain)
        }
        else -> null
    }

    // ─── Gate 2: system feature ───────────────────────────────────────────────────

    private fun hookHasSystemFeature(existingHookIds: Set<String>) {
        val impl = runCatching {
            Class.forName("android.app.ApplicationPackageManager", false, classLoader)
        }.getOrNull()
        val base = runCatching {
            Class.forName("android.content.pm.PackageManager", false, classLoader)
        }.getOrNull()

        for (clazz in listOfNotNull(base, impl)) {
            val hookId = if (clazz == impl) HOOK_SYSTEM_FEATURE else HOOK_SYSTEM_FEATURE_BASE
            if (existingHookIds.contains(hookId)) continue
            runCatching {
                val method = clazz.getDeclaredMethod("hasSystemFeature", String::class.java)
                if (Modifier.isAbstract(method.modifiers)) {
                    DebugLog.d(TAG, "skipping abstract hasSystemFeature on ${clazz.name}")
                    return@runCatching
                }
                deoptimize(method)
                method.hook(hookId) {
                    intercept { chain -> overrideLiveTranslateSystemFeature(chain) }
                }
                DebugLog.i(TAG, "hasSystemFeature hook installed on ${clazz.name} id=$hookId")
            }.onFailure { t ->
                DebugLog.w(TAG, "hasSystemFeature hook failed on ${clazz.name}", t)
            }
        }
    }

    // ─── Gate 3: EXTRA_MEDIA_PROJECTION display predicate ─────────────────────────

    private fun hookMediaProjectionGate(bridge: DexKitBridge) {
        val candidates = methodsUsingString(bridge, EXTRA_MEDIA_PROJECTION).mapNotNull { md ->
            val method = methodFor(md) ?: return@mapNotNull null
            // The host predicate can have an erased Object return type. Match the Android extra,
            // then validate the reflected Function-like shape without using a host method name.
            if (method.parameterTypes.size != 1 ||
                method.parameterTypes[0] != Any::class.java ||
                (method.returnType != Boolean::class.java &&
                    method.returnType != java.lang.Boolean.TYPE &&
                    method.returnType != Any::class.java)
            ) {
                return@mapNotNull null
            }
            method
        }.distinctBy { it.toGenericString() }

        val method = candidates.singleOrNull()
        if (method == null) {
            DebugLog.d(TAG, "media-projection predicate matches=${candidates.size}; skipping")
            return
        }
        deoptimize(method)
        method.hook {
            after { param ->
                if (featureEnabled()) param.result = true
            }
        }
        DebugLog.d(TAG, "media-projection display predicate hooked on $method")
    }

    // ─── Gate 1: master flag leaf ─────────────────────────────────────────────────

    private fun hookFlagLeaf(bridge: DexKitBridge, anchor: String) {
        val candidates = methodsUsingString(bridge, anchor).mapNotNull { md ->
            val method = methodFor(md) ?: return@mapNotNull null
            if (method.parameterCount != 0 || method.returnType != java.lang.Boolean.TYPE) {
                return@mapNotNull null
            }
            method
        }.distinctBy { it.toGenericString() }

        val method = candidates.singleOrNull()
        if (method == null) {
            DebugLog.d(TAG, "master flag leaf matches=${candidates.size}; skipping")
            return
        }
        deoptimize(method)
        method.hook {
            after { param ->
                if (featureEnabled()) param.result = true
            }
        }
        DebugLog.d(TAG, "master flag leaf hooked on $method")
    }

    // ─── Primary: semantic action visibility + constructor-linked capability ─────

    /**
     * Hooks the action's cached visibility decision and the capability call that initializes it.
     * Both targets are recovered from the current DEX field, writer, constructor-parameter and
     * invoke relationships.
     */
    private fun hookActionBeanVisibility(
        bridge: DexKitBridge,
        existingHookIds: Set<String>
    ) {
        val targets = resolveActionTargets(bridge)
        if (targets == null) {
            DebugLog.w(TAG, "live-translate action graph unresolved or ambiguous; failing closed")
            return
        }

        if (!existingHookIds.contains(HOOK_ACTION_VISIBILITY)) {
            targets.visibility.isAccessible = true
            deoptimize(targets.visibility)
            targets.visibility.hook(HOOK_ACTION_VISIBILITY) {
                intercept { chain -> overrideLiveTranslateActionVisibility(chain) }
            }
            DebugLog.i(TAG, "action visibility hooked on ${targets.visibility}")
        }

        if (!existingHookIds.contains(HOOK_CAPABILITY)) {
            targets.capability.isAccessible = true
            deoptimize(targets.capability)
            targets.capability.hook(HOOK_CAPABILITY) {
                intercept { chain -> overrideLiveTranslateBooleanGate(chain) }
            }
            DebugLog.i(TAG, "action capability hooked on ${targets.capability}")
        }
    }

    /**
     * Recovers the live-translate decision pair without relying on obfuscated symbols or a
     * constructor layout. The action id identifies the action class; field reads and constructor
     * writes identify its cached visibility method; the constructor's call/parameter/field graph
     * identifies the capability method.
     */
    private fun resolveActionTargets(bridge: DexKitBridge): ActionTargets? {
        val actionAnchors = bridge.findMethod {
            matcher {
                usingNumbers(ACTION_LIVE_TRANSLATE)
                paramCount(0)
                returnType("int")
            }
        }.filter { it.isMethod && it.returnTypeName == "int" }
            .distinctBy { it.descriptor }
        if (actionAnchors.size != 1) {
            DebugLog.w(TAG, "live-translate action id matches=${actionAnchors.size}")
            return null
        }

        val actionData = actionAnchors.single()
        val actionClass = runCatching { actionData.getClassInstance(classLoader) }.getOrNull()
            ?: return null
        val beanMembers = bridge.findMethod {
            matcher { declaredClass(actionClass) }
        }.filter { it.declaredClassName == actionData.declaredClassName }
        val constructors = beanMembers.filter { it.isConstructor }
            .distinctBy { it.descriptor }

        val visibilityCandidates = beanMembers.mapNotNull { data ->
            if (!data.isMethod || data.paramCount != 0 || data.returnTypeName != "boolean" ||
                data.usingFields.size != 1
            ) {
                return@mapNotNull null
            }
            val field = data.usingFields.single().field
            if (field.typeName != "boolean" ||
                field.declaredClassName != actionData.declaredClassName
            ) {
                return@mapNotNull null
            }
            val writers = field.writers.filter {
                it.isConstructor && it.declaredClassName == actionData.declaredClassName
            }.distinctBy { it.descriptor }
            val writer = writers.singleOrNull() ?: return@mapNotNull null
            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                ?: return@mapNotNull null
            if (Modifier.isStatic(method.modifiers) ||
                method.parameterCount != 0 || method.returnType != java.lang.Boolean.TYPE
            ) {
                return@mapNotNull null
            }
            Triple(data, method, writer)
        }

        val matches = LinkedHashMap<String, ActionTargets>()
        var linkedCapabilities = 0
        for (constructorData in constructors) {
            val constructor = runCatching {
                constructorData.getConstructorInstance(classLoader)
            }.getOrNull() ?: continue
            for (call in constructorData.invokes) {
                if (!call.isMethod || call.paramCount != 0 || call.returnTypeName != "boolean") {
                    continue
                }
                val dependencyClass = runCatching {
                    call.getClassInstance(classLoader)
                }.getOrNull() ?: continue
                val dependencyParameter = constructor.parameterTypes.singleOrNull {
                    dependencyClass.isAssignableFrom(it)
                } ?: continue
                val storedDependencies = actionClass.declaredFields.filter {
                    !Modifier.isStatic(it.modifiers) && it.type == dependencyParameter
                }
                if (storedDependencies.size != 1) continue
                val capability = runCatching { call.getMethodInstance(classLoader) }.getOrNull()
                    ?: continue
                if (Modifier.isStatic(capability.modifiers) || capability.parameterCount != 0 ||
                    capability.returnType != java.lang.Boolean.TYPE
                ) {
                    continue
                }
                linkedCapabilities++
                for ((visibilityData, visibility, writer) in visibilityCandidates) {
                    if (writer.descriptor != constructorData.descriptor) continue
                    val key = "${visibilityData.descriptor}|${call.descriptor}"
                    matches[key] = ActionTargets(visibility, capability)
                }
            }
        }

        DebugLog.i(
            TAG,
            "action resolver: action=${actionAnchors.size}, visibility=${visibilityCandidates.size}, " +
                "constructors=${constructors.size}, capabilityLinks=$linkedCapabilities, targets=${matches.size}"
        )
        return matches.values.singleOrNull()
    }

    // ─── DexKit method resolution ─────────────────────────────────────────────────

    private fun methodsUsingString(bridge: DexKitBridge, anchor: String): List<MethodData> =
        bridge.findMethod { matcher { usingEqStrings(anchor) } }

    private fun overrideLiveTranslateSystemFeature(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        return if (chain.args.size == 1 &&
            chain.args[0] == FEATURE_LIVE_TRANSLATE &&
            featureEnabled()
        ) {
            true
        } else {
            result
        }
    }

    private fun overrideLiveTranslateBooleanGate(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        return if (featureEnabled()) true else result
    }

    private fun overrideLiveTranslateActionVisibility(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        // HyperTweak intentionally exposes the native action on the initial OMNI page. The
        // upstream runtime keeps this callback pass-through, but that would regress this
        // project's existing full-screen-translate surface on HyperOS.
        return if (featureEnabled()) true else result
    }

    private fun methodFor(md: MethodData): Method? = runCatching {
        md.getMethodInstance(classLoader).apply { isAccessible = true }
    }.onFailure { t ->
        DebugLog.w(TAG, "failed to materialize method ${md.descriptor}", t)
    }.getOrNull()
}
