package com.takekazex.hypertweak.hook.rules.googleapp

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows "Ask about this screen" (针对屏幕内容提问) inside the Circle to Search (即圈即搜)
 * **Lensient searchbox** — the OMNI overlay's AI search box (`:googleapp` process). This is a
 * different surface from the live-translate bottom-bar button ([GoogleAppLiveTranslateHooker]).
 *
 * Resolution is marker-driven (ported from upstream `382c75e`). Nothing is bound by version number,
 * obfuscated member name, constructor parameter count or argument index. Instead the whole bridge
 * is recovered from field ownership and call relationships hanging off two string markers that R8
 * cannot rename:
 *
 * 1. **Thumbnail owner** — the unique 1-arg `boolean` method using `vidcip` is Google's screen
 *    thumbnail retention gate. It also *owns* the coordinator class, which is what makes step 5
 *    below unambiguous.
 * 2. **Hint marker** — the unique 1-arg method using `updateAimCsbHintText should not be called
 *    when enableAimCsbHintText is false.` is the callback that drives the native AIM hint.
 * 3. **Model + hint field** — the marker invokes a 0-arg accessor returning a reference type;
 *    the marker also reads a `boolean` field declared by that accessor's owner.
 * 4. **Gate** — that field's 0-arg `void` reader invokes a 0-arg `boolean` method whose body is a
 *    single call. That single call is the path's eligibility test.
 * 5. **OMNI entrypoint** — the eligibility test must resolve to Google's own entrypoint check:
 *    a 0-arg `boolean` method that reads exactly one enum-constant field and invokes exactly one
 *    method returning that same enum type, where the constant is literally named `OMNI`. This
 *    binds us to Google's own notion of "this is the OMNI overlay" instead of guessing.
 * 6. **Ownership** — the gate must use exactly two fields (a cached `boolean` and the entrypoint
 *    owner), the gate's class must be reachable from the thumbnail method's declaring class, the
 *    reader must hold the model reference, and the hint field must have exactly one constructor
 *    writer (the model constructor).
 *
 * Each installed decision only ever flips **a successful `false`** to `true`, and the eligibility
 * flip additionally requires Google's own entrypoint check to report OMNI.
 *
 * This is the only chain: there is no fallback to version/flag/index heuristics, so a build this
 * cannot prove itself on installs nothing. That is deliberate — the removed fallback depended on
 * Phenotype flag literals and a fixed constructor parameter index, which is exactly the brittleness
 * this chain exists to eliminate.
 *
 * The feature is fail-closed: ambiguous, missing or unreadable AIM matches install no eligibility
 * or hint bridge; the independent thumbnail retention hook follows upstream's safe fallback.
 * The coordinator constructor and every capability caller are deoptimized so ART cannot AOT-inline
 * the read past the hooked method. The Google app is a declared required Xposed scope, so the
 * switch flips the preference and queues the app in the Home restart dialog; when disabled, every
 * callback preserves Google's original result.
 *
 * The hook deliberately does **not** forge a thumbnail, spoof Build identity, bypass consent, or
 * manufacture a capture/token path, and it never writes Google's hint text — it only unblocks the
 * native path and lets Google's own model and callback choose the text.
 */
object GoogleAppAskAboutScreenHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    const val PACKAGE = "com.google.android.googlequicksearchbox"

    private const val TAG = "AskAboutScreen"

    // ── Markers (obfuscation-independent) ───────────────────────────────────────

    /** Screen-thumbnail retention gate; anchors the whole chain. */
    private const val THUMBNAIL_MARKER = "vidcip"

    /** Native AIM hint callback; the commit message guard survives R8 renaming. */
    private const val HINT_MARKER =
        "updateAimCsbHintText should not be called when enableAimCsbHintText is false."

    private const val HOOK_THUMBNAIL = "google_lens_screen_thumbnail_retention"
    private const val HOOK_ELIGIBILITY = "google_lens_aim_eligibility_bridge"
    private const val HOOK_HINT = "google_lens_aim_hint_bridge"
    private const val HOOK_CAPABILITY = "google_lens_aim_screen_capability_bridge"
    private val HOOK_IDS = setOf(
        HOOK_THUMBNAIL,
        HOOK_ELIGIBILITY,
        HOOK_HINT,
        HOOK_CAPABILITY
    )

    @Volatile
    private var enabledCache = false

    @Volatile
    private var targets: Targets? = null

    private val capabilityOverrideLogged = AtomicBoolean()
    private val eligibilityOverrideLogged = AtomicBoolean()
    private val hintOverrideLogged = AtomicBoolean()
    private val thumbnailOverrideLogged = AtomicBoolean()

    private fun featureEnabled(): Boolean {
        enabledCache = Preferences.getBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)
        return enabledCache
    }

    override fun onHook() {
        // GoogleAppRuntime owns the one shared DexKit session. Keeping this child hooker inert
        // prevents a second scan when BaseHooker attaches the feature runtimes below it.
    }

    // ─── Installation ───────────────────────────────────────────────────────────

    /**
     * Installs the Ask Screen hooks from the shared Google App DexKit bridge. This mirrors the
     * upstream runtime boundary: feature preferences are checked in the callbacks, not here, so
     * a temporarily unavailable preference backend cannot prevent the native bridge from being
     * prepared for this process.
     */
    internal fun installWithBridge(
        bridge: DexKitBridge,
        existingHookIds: Set<String> = emptySet()
    ) {
        if (existingHookIds.containsAll(HOOK_IDS)) {
            DebugLog.i(TAG, "reused carried Lensient bridge hooks=$HOOK_IDS")
            return
        }
        val thumbnailTarget = resolveThumbnail(bridge)
        if (thumbnailTarget == null) {
            DebugLog.w(TAG, "Lensient thumbnail bridge unresolved; failing closed")
            return
        }
        val targets = resolveTargets(bridge, thumbnailTarget)
        if (targets == null) {
            // This is deliberately the same independent fallback as upstream: retaining the
            // thumbnail is safe on its own, while eligibility/hint remain native when their full
            // semantic graph is missing or ambiguous.
            installThumbnail(thumbnailTarget, existingHookIds)
            DebugLog.w(TAG, "Lensient AIM bridge unresolved; retained thumbnail only")
            return
        }
        this.targets = targets
        install(targets, existingHookIds)
    }

    /** Replacement callbacks used before the new generation starts resolving DexKit. */
    internal fun replacement(id: String): XposedInterface.Hooker? {
        if (id == HOOK_THUMBNAIL) {
            return XposedInterface.Hooker { chain -> retainThumbnail(chain) }
        }
        // These callbacks must retain the resolved field/method graph from the old generation.
        // If that graph is unavailable, let the new generation resolve and install a fresh hook
        // instead of replacing the old handle with a no-op callback.
        val previousTargets = targets ?: return null
        return when (id) {
            HOOK_ELIGIBILITY -> XposedInterface.Hooker { chain ->
                allowOmniEligibility(chain, previousTargets)
            }
            HOOK_HINT -> XposedInterface.Hooker { chain ->
                enableNativeHint(chain, previousTargets)
            }
            HOOK_CAPABILITY -> XposedInterface.Hooker { chain -> overrideCapability(chain) }
            else -> null
        }
    }

    private fun install(t: Targets, existingHookIds: Set<String>) {
        // The coordinator caches the server capability while its constructor runs. Hook the
        // original source as well as the later bridge: otherwise a model created after the hook
        // still snapshots Google's false result into a final field before the bridge is reached.
        t.coordinatorConstructorData?.let(::deoptimizeWithCallers)
        t.capabilityData?.let(::deoptimizeWithCallers)
        deoptimizeWithCallers(t.eligibilityData)
        deoptimizeWithCallers(t.constructorData)
        deoptimizeWithCallers(t.thumbnailData)

        t.capability?.let { capability ->
            if (existingHookIds.contains(HOOK_CAPABILITY)) return@let
            capability.isAccessible = true
            capability.hook(HOOK_CAPABILITY) {
                intercept { chain -> overrideCapability(chain) }
            }
        }

        // Eligibility: only ever unblock a confirmed-false answer, and only when Google's own
        // entrypoint check says this is the OMNI overlay. Any reflective failure preserves the
        // native result.
        if (!existingHookIds.contains(HOOK_ELIGIBILITY)) {
            t.eligibility.isAccessible = true
            t.eligibility.hook(HOOK_ELIGIBILITY) {
                intercept { chain -> allowOmniEligibility(chain, t) }
            }
        }

        // Native hint: unblock Google's own boolean and let its model own the text.
        if (!existingHookIds.contains(HOOK_HINT)) {
            t.modelConstructor.isAccessible = true
            t.modelConstructor.hook(HOOK_HINT) {
                intercept { chain -> enableNativeHint(chain, t) }
            }
        }

        // Thumbnail retention is additive: the OMNI overlay already owns the capture session, so
        // this only stops Google from discarding the frame we are already allowed to use.
        if (!existingHookIds.contains(HOOK_THUMBNAIL)) {
            t.thumbnail.isAccessible = true
            t.thumbnail.hook(HOOK_THUMBNAIL) {
                intercept { chain -> retainThumbnail(chain) }
            }
        }

        DebugLog.i(
            TAG,
            "HOOK_OK Lensient bridge, eligibility=${t.eligibility}" +
                ", hint=${t.modelConstructor}, thumbnail=${t.thumbnail}" +
                ", capability=${t.capability ?: "unresolved"}" +
                ", aimBooleanArgs=${t.aimBooleanParameterIndices.contentToString()}" +
                ", reused=${existingHookIds.intersect(HOOK_IDS)}"
        )
    }

    private fun installThumbnail(
        target: ThumbnailTarget,
        existingHookIds: Set<String>
    ) {
        if (existingHookIds.contains(HOOK_THUMBNAIL)) return
        deoptimizeWithCallers(target.data)
        target.method.isAccessible = true
        target.method.hook(HOOK_THUMBNAIL) {
            intercept { chain -> retainThumbnail(chain) }
        }
        DebugLog.i(TAG, "HOOK_OK Lensient thumbnail retention=${target.method}")
    }

    private fun overrideCapability(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (featureEnabled() && result == false) {
            if (capabilityOverrideLogged.compareAndSet(false, true)) {
                DebugLog.i(TAG, "runtime opened cached Lensient screen capability")
            }
            return true
        }
        return result
    }

    private fun allowOmniEligibility(chain: XposedInterface.Chain): Any? =
        allowOmniEligibility(chain, targets)

    private fun allowOmniEligibility(chain: XposedInterface.Chain, target: Targets?): Any? {
        val result = chain.proceed()
        if (target == null || target.eligibility != chain.executable ||
            result != false || !featureEnabled()
        ) {
            return result
        }
        return HookFailurePolicy.open(TAG, "eligibility", result) {
            if (target.isOmni(chain.thisObject)) {
                if (eligibilityOverrideLogged.compareAndSet(false, true)) {
                    DebugLog.i(TAG, "runtime opened OMNI Ask Screen eligibility")
                }
                true
            } else {
                result
            }
        }
    }

    private fun enableNativeHint(chain: XposedInterface.Chain): Any? =
        enableNativeHint(chain, targets)

    private fun enableNativeHint(chain: XposedInterface.Chain, target: Targets?): Any? {
        if (target != null && featureEnabled()) {
            // dsnc/doqf keep the AIM switches as the boolean tail immediately before their
            // Executor dependency. Set the arguments before final fields are assigned; mutating a
            // final field after construction is not reliable on ART.
            HookFailurePolicy.open(TAG, "constructor boolean arguments", Unit) {
                for (index in target.aimBooleanParameterIndices) {
                    if (index in chain.args.indices) chain.args[index] = true
                }
            }
        }
        val result = chain.proceed()
        if (target == null || target.modelConstructor != chain.executable || !featureEnabled()) {
            return result
        }
        HookFailurePolicy.open(TAG, "hint", Unit) {
            val model = chain.thisObject
            if (target.isOmni(target.modelCoordinator.get(model)) &&
                target.hint.getBoolean(model) == false
            ) {
                target.hint.setBoolean(model, true)
                if (hintOverrideLogged.compareAndSet(false, true)) {
                    DebugLog.i(TAG, "runtime enabled native OMNI Ask Screen hint")
                }
            }
        }
        return result
    }

    private fun retainThumbnail(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (featureEnabled() && result == false) {
            if (thumbnailOverrideLogged.compareAndSet(false, true)) {
                DebugLog.i(TAG, "runtime retained Lensient screen thumbnail")
            }
            return true
        }
        return result
    }

    /**
     * Undo AOT inlining for a resolved target and every caller. Resolution is marker-driven, so a
     * renamed caller must not silently keep an inlined copy of the decision we just hooked.
     */
    private fun deoptimizeWithCallers(data: MethodData) {
        val executable = runCatching<Executable> {
            if (data.isConstructor) {
                data.getConstructorInstance(classLoader)
            } else {
                data.getMethodInstance(classLoader)
            }
        }.getOrNull() ?: return
        var attempted = 0
        deoptimize(executable)
        attempted++
        for (caller in data.callers) {
            if (!caller.isMethod && !caller.isConstructor) continue
            val callerExecutable = runCatching<Executable> {
                if (caller.isConstructor) {
                    caller.getConstructorInstance(classLoader)
                } else {
                    caller.getMethodInstance(classLoader)
                }
            }.getOrNull() ?: continue
            deoptimize(callerExecutable)
            attempted++
        }
        DebugLog.d(TAG, "deoptimized target plus callers, attempted=$attempted")
    }

    // ─── Resolution (upstream 382c75e) ──────────────────────────────────────────

    /** Resolves the independently usable thumbnail retention target. */
    private fun resolveThumbnail(bridge: DexKitBridge): ThumbnailTarget? {
        val thumbnails = bridge.findMethod {
            matcher {
                paramCount(1)
                returnType("boolean")
                usingEqStrings(THUMBNAIL_MARKER)
            }
        }
        if (thumbnails.size != 1) {
            DebugLog.w(TAG, "thumbnail marker '$THUMBNAIL_MARKER' matches=${thumbnails.size}")
            return null
        }
        val thumbnailData = thumbnails.single()
        val thumbnail = runCatching { thumbnailData.getMethodInstance(classLoader) }.getOrNull()
            ?: return null
        if (Modifier.isStatic(thumbnail.modifiers) ||
            thumbnail.parameterTypes.size != 1 ||
            thumbnail.parameterTypes[0].isPrimitive
        ) {
            DebugLog.w(TAG, "thumbnail marker resolved to an unusable receiver: $thumbnail")
            return null
        }
        return ThumbnailTarget(thumbnail, thumbnailData)
    }

    /**
     * Recovers the whole Ask Screen bridge from the `vidcip` thumbnail owner and the native hint
     * marker. Every step must be unique; any ambiguity returns null while the independent
     * thumbnail hook remains eligible for installation.
     */
    private fun resolveTargets(
        bridge: DexKitBridge,
        thumbnailTarget: ThumbnailTarget
    ): Targets? {
        val thumbnail = thumbnailTarget.method
        val thumbnailData = thumbnailTarget.data

        val markers = bridge.findMethod {
            matcher {
                paramCount(1)
                usingEqStrings(HINT_MARKER)
            }
        }
        if (markers.size != 1) {
            DebugLog.w(TAG, "hint marker matches=${markers.size}")
            return null
        }
        val marker = markers.single()

        var accessors = 0
        var hints = 0
        var readers = 0
        var gates = 0
        var omniTests = 0
        val matches = LinkedHashMap<String, Targets>()

        for (accessor in marker.invokes) {
            if (accessor.paramCount != 0) continue
            val accessorReturnType = runCatching {
                accessor.getReturnTypeInstance(classLoader)
            }.getOrNull() ?: continue
            if (accessorReturnType.isPrimitive || accessorReturnType == Void.TYPE) continue
            accessors++
            for (use in marker.usingFields) {
                val hint = use.field
                if (hint.declaredClassName != accessor.declaredClassName ||
                    hint.typeName != "boolean"
                ) {
                    continue
                }
                hints++
                for (reader in hint.readers) {
                    if (reader.declaredClassName != hint.declaredClassName ||
                        reader.paramCount != 0 || reader.returnTypeName != "void"
                    ) {
                        continue
                    }
                    readers++
                    for (gate in reader.invokes) {
                        if (!gate.isMethod || gate.paramCount != 0 ||
                            gate.returnTypeName != "boolean" || gate.invokes.size != 1
                        ) {
                            continue
                        }
                        gates++
                        val entry = gate.invokes[0]
                        if (!isOmniEntryTest(entry)) continue
                        omniTests++

                        // The gate must use exactly two fields: a cached boolean and the
                        // entrypoint owner. More than one of either is ambiguous.
                        var cached: FieldData? = null
                        var entryOwner: FieldData? = null
                        var gateAmbiguous = false
                        for (gateUse in gate.usingFields) {
                            val field = gateUse.field
                            if (field.declaredClassName != gate.declaredClassName) continue
                            if (field.typeName == "boolean") {
                                if (cached != null) {
                                    gateAmbiguous = true
                                    break
                                }
                                cached = field
                            } else if (field.typeName == entry.declaredClassName) {
                                if (entryOwner != null) {
                                    gateAmbiguous = true
                                    break
                                }
                                entryOwner = field
                            }
                        }
                        if (gateAmbiguous || cached == null || entryOwner == null ||
                            gate.usingFields.size != 2
                        ) {
                            continue
                        }

                        // The coordinator is whatever the thumbnail owner holds a field of.
                        val coordinator = runCatching { gate.getClassInstance(classLoader) }
                            .getOrNull() ?: continue
                        var sharedWithThumbnail = false
                        for (declared in thumbnail.declaringClass.declaredFields) {
                            if (!Modifier.isStatic(declared.modifiers) &&
                                declared.type == coordinator
                            ) {
                                sharedWithThumbnail = true
                            }
                        }
                        if (!sharedWithThumbnail) continue

                        // The reader holds the model reference, binding the hint to that model.
                        var modelOwner: FieldData? = null
                        var modelOwnerAmbiguous = false
                        for (readerUse in reader.usingFields) {
                            val field = readerUse.field
                            if (field.declaredClassName == hint.declaredClassName &&
                                field.typeName == gate.declaredClassName
                            ) {
                                if (modelOwner != null) {
                                    modelOwnerAmbiguous = true
                                    break
                                }
                                modelOwner = field
                            }
                        }
                        if (modelOwnerAmbiguous || modelOwner == null) continue

                        val constructors = hint.writers.filter {
                            it.isConstructor && it.declaredClassName == hint.declaredClassName
                        }
                        if (constructors.size != 1) continue

                        val targets = buildTargets(
                            thumbnail, thumbnailData, gate, constructors.single(),
                            hint, cached, modelOwner, entryOwner, entry
                        ) ?: continue
                        matches[gate.descriptor + hint.descriptor + modelOwner.descriptor] = targets
                    }
                }
            }
        }

        DebugLog.i(
            TAG,
            "resolver: accessors=$accessors, hints=$hints, readers=$readers" +
                ", gates=$gates, omniTests=$omniTests, targets=${matches.size}"
        )
        return matches.values.singleOrNull()
    }

    /**
     * Google's own entrypoint test: a 0-arg `boolean` method reading exactly one enum-constant
     * field and invoking exactly one method that returns that same enum type, where the constant
     * is named `OMNI`. This is the only place the chain accepts a name, and it is a host-side
     * product enum rather than an obfuscated symbol.
     */
    private fun isOmniEntryTest(entry: MethodData): Boolean {
        if (!entry.isMethod || entry.paramCount != 0 || entry.returnTypeName != "boolean") {
            return false
        }
        if (entry.usingFields.size != 1 || entry.invokes.size != 1) return false
        val field = runCatching {
            entry.usingFields[0].field.getFieldInstance(classLoader)
        }.getOrNull() ?: return false
        if (!field.isEnumConstant) return false
        val invokedReturn = runCatching {
            entry.invokes[0].getReturnTypeInstance(classLoader)
        }.getOrNull() ?: return false
        if (invokedReturn != field.type) return false
        field.isAccessible = true
        val value = runCatching { field.get(null) }.getOrNull() ?: return false
        return value is Enum<*> && value.name == "OMNI"
    }

    /** Materializes the resolved bridge, rejecting any static member (see upstream's guards). */
    private fun buildTargets(
        thumbnail: Method,
        thumbnailData: MethodData,
        gate: MethodData,
        constructorData: MethodData,
        hint: FieldData,
        cached: FieldData,
        modelOwner: FieldData,
        entryOwner: FieldData,
        entry: MethodData
    ): Targets? {
        val eligibility = runCatching { gate.getMethodInstance(classLoader) }.getOrNull()
            ?: return null
        val modelConstructor = runCatching { constructorData.getConstructorInstance(classLoader) }
            .getOrNull() ?: return null
        val hintField = runCatching { hint.getFieldInstance(classLoader) }.getOrNull() ?: return null
        val modelCoordinator = runCatching { modelOwner.getFieldInstance(classLoader) }
            .getOrNull() ?: return null
        val entryOwnerField = runCatching { entryOwner.getFieldInstance(classLoader) }
            .getOrNull() ?: return null
        val entryMethod = runCatching { entry.getMethodInstance(classLoader) }.getOrNull()
            ?: return null
        val coordinatorConstructorData = cached.writers.singleOrNull {
            it.isConstructor && it.declaredClassName == gate.declaredClassName
        }
        val capabilityData = coordinatorConstructorData?.invokes?.filter {
            it.isMethod && it.paramCount == 0 && it.returnTypeName == "boolean" &&
                it.declaredClassName != gate.declaredClassName
        }?.singleOrNull()
        val capability = capabilityData?.let {
            runCatching { it.getMethodInstance(classLoader) }.getOrNull()
        }
        if (capabilityData != null && capability == null) {
            DebugLog.w(TAG, "capability source resolved in dex but could not be materialized")
        }
        val aimBooleanParameterIndices =
            findAimBooleanParameterIndices(modelConstructor.parameterTypes)
        if (aimBooleanParameterIndices.isEmpty()) {
            val exactExecutorCount = modelConstructor.parameterTypes.count {
                it.name == Executor::class.java.name
            }
            DebugLog.w(
                TAG,
                "model constructor has no boolean AIM tail before unique Executor " +
                    "exactExecutorCount=$exactExecutorCount " +
                    "version=${googleAppVersion()} constructor=${modelConstructor.toGenericString()}"
            )
        }
        if (Modifier.isStatic(eligibility.modifiers) ||
            Modifier.isStatic(hintField.modifiers) ||
            Modifier.isStatic(modelCoordinator.modifiers) ||
            Modifier.isStatic(entryOwnerField.modifiers) ||
            capability?.let { Modifier.isStatic(it.modifiers) } == true
        ) {
            return null
        }
        hintField.isAccessible = true
        modelCoordinator.isAccessible = true
        entryOwnerField.isAccessible = true
        entryMethod.isAccessible = true
        return Targets(
            thumbnail, thumbnailData, eligibility, gate,
            modelConstructor, constructorData,
            hintField, modelCoordinator, entryOwnerField, entryMethod,
            coordinatorConstructorData, capabilityData, capability, aimBooleanParameterIndices
        )
    }

    /**
     * The AIM booleans are kept at the end of the generated constructor immediately before its
     * exact `java.util.concurrent.Executor` parameter. Other dependencies may implement Executor;
     * they are not boundary markers. The boolean count can change between host builds, so recover
     * the contiguous tail instead of binding to fixed argument indices.
     */
    internal fun findAimBooleanParameterIndices(parameterTypes: Array<Class<*>>): IntArray {
        val executorIndices = parameterTypes.mapIndexedNotNull { index, type ->
            if (type.name == Executor::class.java.name) index else null
        }
        val executorIndex = executorIndices.singleOrNull() ?: return IntArray(0)
        val booleanType = Boolean::class.javaPrimitiveType
        if (booleanType == null) return IntArray(0)
        val indices = ArrayList<Int>()
        var index = executorIndex - 1
        while (index >= 0 && parameterTypes[index] == booleanType) {
            indices += index
            index--
        }
        indices.reverse()
        return indices.toIntArray()
    }

    /** Best-effort host version for diagnosing a resolver change after a Google App update. */
    private fun googleAppVersion(): String = runCatching {
        val context = hookParam.appContext
            ?: runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context
            }.getOrNull()
            ?: runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val thread = activityThread.getMethod("currentActivityThread").invoke(null)
                activityThread.getMethod("getSystemContext").invoke(thread) as? Context
            }.getOrNull()
            ?: return "unknown"
        val info = context.packageManager.getPackageInfo(PACKAGE, 0)
        "${info.versionName ?: "unknown"}(${info.longVersionCode})"
    }.getOrDefault("unknown")

    private class ThumbnailTarget(
        val method: Method,
        val data: MethodData
    )

    private class Targets(
        val thumbnail: Method,
        val thumbnailData: MethodData,
        val eligibility: Method,
        val eligibilityData: MethodData,
        val modelConstructor: Constructor<*>,
        val constructorData: MethodData,
        val hint: Field,
        val modelCoordinator: Field,
        val entryOwner: Field,
        val entry: Method,
        val coordinatorConstructorData: MethodData?,
        val capabilityData: MethodData?,
        val capability: Method?,
        val aimBooleanParameterIndices: IntArray
    ) {
        /** True only when Google's own entrypoint check reports the OMNI overlay. */
        fun isOmni(coordinator: Any?): Boolean {
            if (coordinator == null) return false
            return entry.invoke(entryOwner.get(coordinator)) == true
        }
    }
}
