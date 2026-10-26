package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import android.content.Context
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.BooleanFieldOverrides
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
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
 * Resolution starts at the semantic screen-thumbnail retention contract (`vidcip` + prototype).
 * It follows that host's coordinator fields, a cached-boolean/OMNI eligibility decision, and the
 * native model's field readers/writers. A candidate hint flag must be constructor-owned, read by
 * the coordinator consumer, and connected to a native TextView hint renderer and model accessor.
 * Helper extraction does not change this graph: traversal stays within each reader/model's owners
 * and terminates at native rendering. Diagnostic log strings, versions, obfuscated names and a
 * fixed helper depth never select a target. Missing or ambiguous graphs fail closed.
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
 * Native model/coordinator field assignments are proven with the shared operand index. Original
 * values are captured before reversible OMNI overrides, and field readers are deoptimized. The
 * shared committed-settings dispatcher refreshes living native peers; disable and retirement
 * restore native values. Google owns rendering on its next bind/overlay invocation.
 *
 * The hook deliberately does **not** forge a thumbnail, spoof Build identity, bypass consent, or
 * manufacture a capture/token path, and it never writes Google's hint text — it only unblocks the
 * native path and lets Google's own model and callback choose the text.
 */
object GoogleAppAskAboutScreenHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingAskAboutScreen by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, false) }


    const val PACKAGE = "com.google.android.googlequicksearchbox"

    private const val TAG = "AskAboutScreen"

    // ── Markers (obfuscation-independent) ───────────────────────────────────────

    /** Screen-thumbnail retention gate; anchors the whole chain. */
    private const val THUMBNAIL_MARKER = "vidcip"

    private const val HOOK_THUMBNAIL = "google_lens_screen_thumbnail_retention"
    private const val HOOK_ELIGIBILITY = "google_lens_aim_eligibility_bridge"
    private const val HOOK_HINT = "google_lens_aim_hint_bridge"
    private const val HOOK_COORDINATOR = "google_lens_aim_coordinator_state"
    private const val HOOK_CAPABILITY = "google_lens_aim_screen_capability_bridge"
    private val HOOK_IDS = setOf(
        HOOK_THUMBNAIL,
        HOOK_ELIGIBILITY,
        HOOK_HINT,
        HOOK_COORDINATOR
    )

    private val models = BooleanFieldOverrides()
    private val coordinators = BooleanFieldOverrides()
    @Volatile private var retired = false
    init { generationCleanup { retired = true; models.restore(); coordinators.restore(); models.clear(); coordinators.clear() } }

    private var enabledCache by generationState { false }

    private var targets: Targets? by generationState { null }

    private val eligibilityOverrideLogged = generationResource(AtomicBoolean()) { it.set(false) }
    private val thumbnailOverrideLogged = generationResource(AtomicBoolean()) { it.set(false) }

    private fun featureEnabled(): Boolean {
        enabledCache = !retired && settingAskAboutScreen
        return enabledCache
    }

    override fun onHook() {
        // GoogleAppRuntime owns the one shared DexKit session. Keeping this child hooker inert
        // prevents a second scan when BaseHooker attaches the feature runtimes below it.
    }

    // ─── Installation ───────────────────────────────────────────────────────────

    private data class Prepared(val thumbnail: Method, val deoptimizations: List<Executable>, val full: Targets?)
    internal fun prepareInstall(apk: String, existingHookIds: Set<String>): () -> Unit {
        if (existingHookIds.containsAll(HOOK_IDS)) return {}
        val prepared = resolveDexContract("googleapp.ask_screen", apk, schema = 4,
            compute = compute@ { bridge ->
                val thumbnail = resolveThumbnail(bridge) ?: return@compute null
                val full = resolveTargets(bridge, thumbnail)
                Prepared(thumbnail.method, full?.deoptimizations ?: executablesWithCallers(thumbnail.data), full)
            },
            encode = { prepared ->
                val full = prepared.full
                DexContractMembers.encode("thumbnail" to prepared.thumbnail, "deoptimizations" to prepared.deoptimizations,
                    "eligibility" to full?.eligibility, "model" to full?.modelConstructor,
                    "hint" to full?.hint, "coordinator" to full?.modelCoordinator, "entryOwner" to full?.entryOwner,
                    "entry" to full?.entry, "capability" to full?.capability,
                    "indices" to full?.aimBooleanParameterIndices?.joinToString(","),
                    "aimFields" to full?.aimFields, "peer" to full?.peer,
                    "cached" to full?.cached, "coordinatorConstructor" to full?.coordinatorConstructor,
                    "viewFragment" to full?.viewFragment)
            },
            decode = { members ->
                val thumbnail = members.method("thumbnail")
                require(!Modifier.isStatic(thumbnail.modifiers) && thumbnail.returnType == Boolean::class.javaPrimitiveType && thumbnail.parameterCount == 1 && !thumbnail.parameterTypes[0].isPrimitive)
                val deoptimizations = members.executables("deoptimizations")
                require(thumbnail in deoptimizations)
                val full = members.optionalMethod("eligibility")?.let { eligibility ->
                    val constructor = members.constructor("model")
                    val hint = members.field("hint"); val coordinator = members.field("coordinator"); val entryOwner = members.field("entryOwner")
                    val entry = members.method("entry"); val capability = members.optionalMethod("capability")
                    require(listOf(eligibility, entry).all { !Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType })
                    require(listOf(hint, coordinator, entryOwner).all { !Modifier.isStatic(it.modifiers) })
                    require(coordinator.declaringClass.isAssignableFrom(constructor.declaringClass) && entryOwner.declaringClass.isAssignableFrom(coordinator.type))
                    require(entry.declaringClass.isAssignableFrom(entryOwner.type) && entry.parameterCount == 0)
                    capability?.also { require(!Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType) }
                    val indices = findAimBooleanParameterIndices(constructor.parameterTypes)
                    require(indices.joinToString(",") == members.text("indices"))
                    val fields = members.text("aimFields").lineSequence().filter(String::isNotEmpty).map {
                        requireNotNull(MethodDescriptorCodec.field(it, classLoader))
                    }.toList()
                    require(fields.size == indices.size && fields.all { it.declaringClass == constructor.declaringClass && it.type == Boolean::class.javaPrimitiveType })
                    val peer = members.field("peer")
                    require(peer.type == constructor.declaringClass)
                    val cached = members.field("cached")
                    val coordinatorConstructor = members.constructor("coordinatorConstructor")
                    require(cached.type == Boolean::class.javaPrimitiveType && cached.declaringClass == coordinator.type)
                    require(coordinatorConstructor.declaringClass == coordinator.type)
                    Targets(thumbnail, eligibility, constructor, hint, coordinator, entryOwner, entry, capability, indices, deoptimizations,
                        fields, peer, cached, coordinatorConstructor, members.method("viewFragment"))
                }
                Prepared(thumbnail, deoptimizations, full)
            },
        ) ?: return { DebugLog.w(TAG, "Lensient thumbnail bridge unresolved; failing closed") }
        val resolved = prepared.full
        return if (resolved == null) ({
            prepared.deoptimizations.forEach(::deoptimize)
            installThumbnail(prepared.thumbnail, existingHookIds)
            DebugLog.w(TAG, "Lensient AIM bridge unresolved; retained thumbnail only")
        }) else ({ retired = false; targets = resolved; install(resolved, existingHookIds); refreshSettings() })
    }

    /** Replacement callbacks used before the new generation starts resolving DexKit. */
    internal fun replacement(id: String): XposedInterface.Hooker? {
        // Old source-level overrides populated permanent host caches; retain only an inert handle.
        if (id == HOOK_CAPABILITY) return XposedInterface.Hooker { chain -> chain.proceed() }
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
            HOOK_COORDINATOR -> XposedInterface.Hooker { chain -> captureCoordinator(chain, previousTargets) }
            else -> null
        }
    }

    private fun install(t: Targets, existingHookIds: Set<String>) {
        t.deoptimizations.forEach(::deoptimize)
        if (!existingHookIds.contains(HOOK_COORDINATOR)) {
            t.coordinatorConstructor.hook(HOOK_COORDINATOR) {
                intercept { chain -> captureCoordinator(chain, t) }
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
        target: Method,
        existingHookIds: Set<String>
    ) {
        if (existingHookIds.contains(HOOK_THUMBNAIL)) return
        target.isAccessible = true
        target.hook(HOOK_THUMBNAIL) {
            intercept { chain -> retainThumbnail(chain) }
        }
        DebugLog.i(TAG, "HOOK_OK Lensient thumbnail retention=$target")
    }

    private fun captureCoordinator(chain: XposedInterface.Chain, target: Targets): Any? {
        val result = chain.proceed()
        HookFailurePolicy.open(TAG, "capture native capability", Unit) {
            coordinators.capture(chain.thisObject, listOf(target.cached))
            coordinators.apply { featureEnabled() && target.isOmni(it) }
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
        // Run native construction unchanged, then overlay only proven boolean assignments.
        val result = chain.proceed()
        if (target == null || target.modelConstructor != chain.executable) return result
        HookFailurePolicy.open(TAG, "capture AIM model", Unit) {
            models.capture(chain.thisObject, target.aimFields)
            target.modelCoordinator.get(chain.thisObject)?.let { coordinators.capture(it, listOf(target.cached)) }
            applyModels(target)
        }
        return result
    }

    private fun applyModels(target: Targets) {
        coordinators.apply { featureEnabled() && target.isOmni(it) }
        models.apply { model -> featureEnabled() && target.isOmni(target.modelCoordinator.get(model)) }
    }

    internal fun hasResolvedTargets(): Boolean = targets != null

    fun refreshSettings(): Boolean = HostAccess.onMain {
        val target = targets ?: return@onMain false
        HookFailurePolicy.open(TAG, "refresh AIM models", false) {
            // Native view→fragment tags survive module replacement and require no unobfuscated
            // FragmentManager accessor. Recover attached peers even without saved feature state.
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requirePublicMethod(activityThread, "currentActivityThread", accessible = false).invoke(null)
            val records = thread?.let { HostAccess.read(it, "mActivities") } as? Map<*, *>
            records?.values?.filterNotNull()?.forEach { record ->
                val activity = HostAccess.read(record, "activity") as? android.app.Activity ?: return@forEach
                if (activity.isDestroyed) return@forEach
                val root = activity.window?.peekDecorView() ?: return@forEach
                val views = java.util.ArrayDeque<android.view.View>().apply { add(root) }
                while (views.isNotEmpty()) {
                    val view = views.removeFirst()
                    val fragment = target.viewFragment.invoke(null, view)
                    if (fragment != null && target.peer.declaringClass.isInstance(fragment)) {
                        target.peer.get(fragment)?.let { model ->
                            models.capture(model, target.aimFields)
                            target.modelCoordinator.get(model)?.let { coordinators.capture(it, listOf(target.cached)) }
                        }
                    }
                    if (view is android.view.ViewGroup) {
                        for (child in 0 until view.childCount) views.add(view.getChildAt(child))
                    }
                }
            }
            applyModels(target)
            // Existing pixels are owned by Google's view/flow lifecycle: the next native bind or
            // reopening the overlay renders the new model; never replay its search-submission API.
            true
        }
    }

    override fun recoverHotReloadHosts() { refreshSettings() }
    override fun onPrepareHotReload() = HostAccess.onMain {
        retired = true
        models.restore()
        coordinators.restore()
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
    private fun executablesWithCallers(data: MethodData): List<Executable> =
        (listOf(data) + data.callers.filter { it.isMethod || it.isConstructor }).mapNotNull { target ->
            runCatching<Executable> {
                if (target.isConstructor) target.getConstructorInstance(classLoader)
                else target.getMethodInstance(classLoader)
            }.getOrNull()
        }.distinct()

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

    /** Recover the unique native ownership graph from the thumbnail host; diagnostics are not anchors. */
    private fun resolveTargets(
        bridge: DexKitBridge,
        thumbnailTarget: ThumbnailTarget
    ): Targets? {
        val thumbnail = thumbnailTarget.method
        val thumbnailData = thumbnailTarget.data

        val apk = hookParam.appInfo?.sourceDir ?: return null
        val index = DexRuntime.operands(apk, hookParam.appInfo)
        fun method(descriptor: String) = index.definition(descriptor)
        val graphs = AskScreenGraphContract.resolve(index, thumbnailData.descriptor,
            callers = { reference ->
                bridge.getMethodData(MethodDescriptorCodec.descriptor(reference))?.callers.orEmpty()
                    .mapNotNull { method(it.descriptor) }
            },
            uses = { reference ->
                val data = bridge.getFieldData(MethodDescriptorCodec.descriptor(reference))
                AskScreenGraphContract.FieldUses(
                    data?.readers.orEmpty().mapNotNull { method(it.descriptor) },
                    data?.writers.orEmpty().mapNotNull { method(it.descriptor) },
                )
            },
            isOmniEntry = { reference ->
                bridge.getMethodData(MethodDescriptorCodec.descriptor(reference))?.let(::isOmniEntryTest) == true
            },
        )
        // Ambiguity is rejected before materializing any alternative. No diagnostic-string or
        // version-dependent chain is allowed to rescue a missing/changed ownership graph.
        if (graphs.size != 1) {
            DebugLog.w(TAG, "Ask Screen ownership graph matches=${graphs.size}")
            return null
        }
        val graph = graphs.single()
        val gate = bridge.getMethodData(graph.eligibility) ?: return null
        val constructor = bridge.getMethodData(graph.constructor) ?: return null
        val hint = bridge.getFieldData(graph.hint) ?: return null
        val cached = bridge.getFieldData(graph.cached) ?: return null
        val modelOwner = bridge.getFieldData(graph.modelCoordinator) ?: return null
        val entryOwner = bridge.getFieldData(graph.entryOwner) ?: return null
        val entry = bridge.getMethodData(graph.entry) ?: return null
        val target = buildTargets(bridge, thumbnail, thumbnailData, gate, constructor, hint, cached, modelOwner, entryOwner, entry)
        DebugLog.i(TAG, "Ask Screen ownership graph unique, materialized=${target != null}")
        return target
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
        bridge: DexKitBridge,
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
        val coordinatorConstructor = coordinatorConstructorData?.getConstructorInstance(classLoader) ?: return null
        val cachedField = cached.getFieldInstance(classLoader).apply { isAccessible = true }
        val capabilityData = coordinatorConstructorData.invokes.filter {
            it.isMethod && it.paramCount == 0 && it.returnTypeName == "boolean" &&
                it.declaredClassName != gate.declaredClassName
        }.singleOrNull()
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
        val apk = hookParam.appInfo?.sourceDir ?: return null
        val descriptors = AskScreenModelContract.fields(DexRuntime.operands(apk, hookParam.appInfo),
            MethodDescriptorCodec.descriptor(modelConstructor), aimBooleanParameterIndices.toSet()) ?: return null
        val aimFields = descriptors.map { MethodDescriptorCodec.field(it, classLoader) ?: return null }
        if (hintField !in aimFields) return null
        val peers = modelConstructor.parameterTypes.filterNot { it.isPrimitive }.flatMap { type ->
            type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == modelConstructor.declaringClass }
        }.distinct()
        val peer = peers.singleOrNull()?.apply { isAccessible = true } ?: return null
        val childManager = HostAccess.requirePublicMethod(peer.declaringClass, "getChildFragmentManager", accessible = false)
        val viewFragmentDescriptor = AskScreenGraphContract.fragmentLookup(DexRuntime.operands(apk, hookParam.appInfo),
            MethodDescriptorCodec.typeDescriptor(childManager.returnType), MethodDescriptorCodec.typeDescriptor(childManager.declaringClass)) ?: return null
        val viewFragment = MethodDescriptorCodec.method(viewFragmentDescriptor, classLoader) ?: return null
        return Targets(
            thumbnail, eligibility, modelConstructor,
            hintField, modelCoordinator, entryOwnerField, entryMethod,
            capability, aimBooleanParameterIndices,
            listOfNotNull(thumbnailData, gate, constructorData, coordinatorConstructorData, capabilityData)
                .flatMap(::executablesWithCallers).plus(aimFields.flatMap { field ->
                    bridge.getFieldData(field)?.readers.orEmpty().flatMap(::executablesWithCallers)
                }).plus(cached.readers.flatMap(::executablesWithCallers)).distinct(), aimFields, peer, cachedField, coordinatorConstructor, viewFragment
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
                HostAccess.requireDeclaredMethod(activityThread, "currentApplication", accessible = false).invoke(null) as? Context
            }.getOrNull()
            ?: runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val thread = HostAccess.requirePublicMethod(activityThread, "currentActivityThread", accessible = false).invoke(null)
                HostAccess.requirePublicMethod(activityThread, "getSystemContext", accessible = false).invoke(thread) as? Context
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
        val eligibility: Method,
        val modelConstructor: Constructor<*>,
        val hint: Field,
        val modelCoordinator: Field,
        val entryOwner: Field,
        val entry: Method,
        val capability: Method?,
        val aimBooleanParameterIndices: IntArray,
        val deoptimizations: List<Executable>,
        val aimFields: List<Field>,
        val peer: Field,
        val cached: Field,
        val coordinatorConstructor: Constructor<*>,
        val viewFragment: Method,
    ) {
        /** True only when Google's own entrypoint check reports the OMNI overlay. */
        fun isOmni(coordinator: Any?): Boolean {
            if (coordinator == null) return false
            return entry.invoke(entryOwner.get(coordinator)) == true
        }
    }
}
