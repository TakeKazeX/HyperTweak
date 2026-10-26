package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.HostAccess
import android.os.Build
import android.util.Size
import android.util.SparseArray
import androidx.core.util.isNotEmpty
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/** Owns MasterLive donors, readiness, compatibility gates and effect-table state. */
internal class CameraMasterLiveHooks(owner: BaseHooker, private val config: CameraConfigAccess, private val legendaryMomentUnlock: () -> Boolean) : HookComponent(owner) {
    private companion object { const val TAG = "CamImpersonate" }
    private val deviceIsNezhaCache = AtomicReference<Boolean?>()
    private fun deviceIsNezha(): Boolean {
        deviceIsNezhaCache.get()?.let { return it }
        synchronized(deviceIsNezhaCache) {
            deviceIsNezhaCache.get()?.let { return it }
            val isNezha = Build.DEVICE.equals("nezha", ignoreCase = true)
            deviceIsNezhaCache.set(isNezha)
            return isNezha
        }
    }

    private val hostProfile get() = config.profile
    private fun activeConfigInstance() = config.activeInstance()
    private fun configDispatchClasses() = config.dispatchClasses()
    @Volatile var masterLiveGateReady = false
        private set
    private fun resolveK100Config(loader: ClassLoader, resolver: Method): Any? {
        // Snapshot once so every source-profile candidate uses the same identity baseline.
        val original = activeConfigInstance()
        for (sourceName in K100_SOURCE_NAME_CANDIDATES) {
            val instance = buildFrom(loader, resolver, sourceName)
            if (instance == null) {
                DebugLog.w(TAG, "MasterLive source profile unavailable: $sourceName")
                continue
            }
            val shaped = isK100Shaped(instance)
            if (shaped && isK100Candidate(instance, original)) {
                DebugLog.i(TAG, "K100 config resolved via resolver source name $sourceName -> ${instance.javaClass.name}")
                return instance
            }
            DebugLog.d(TAG, "K100 source probe $sourceName -> ${instance.javaClass.name} rejected (shaped=$shaped)")
        }
        DebugLog.w(TAG, "K100 config not resolved by semantic source profile; effect table unavailable")
        return null
    }

    private val K100_SOURCE_NAME_CANDIDATES = listOf(
        "com.mi.device.Songyuan",
    )

    private fun isK100Candidate(instance: Any, original: Any?): Boolean = runCatching {
        if (!isK100Shaped(instance) || original == null) return@runCatching false
        val profile = hostProfile ?: return@runCatching false
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG)
            ?: return@runCatching false
        val ddf = semantics.unique("dynamic DDF config", semantics.dex.strings("dynamic_ddfid: ")
            .flatMap { semantics.dex.code(it).calls.map { call -> call.method } }
            .filter { DexOperandIndex.isInstanceGetter(it, DexOperandIndex.descriptor(profile.configType), "I") })
            ?: return@runCatching false
        fun stops(receiver: Any, mode: Int): FloatArray? {
            val getter = profile.configMethod(receiver.javaClass, profile.focalStops, SparseArray::class.java)
                ?: return null
            val values = (getter.invoke(receiver) as? SparseArray<*>)?.get(mode)
            return when (values) {
                is FloatArray -> values.copyOf()
                is Array<*> -> values.map { it as? Float ?: return null }.toFloatArray()
                else -> null
            }
        }
        val compatible = CameraMasterLiveCompatibility.accepts(
            ddf.invoke(original) as? Int, ddf.invoke(instance) as? Int,
            stops(original, 163), stops(instance, 163), stops(instance, CameraIdentity.MASTER_LIVE_MODE_ID),
        )
        if (!compatible) DebugLog.w(TAG, "MasterLive donor rejected: DDF/photo/MasterLive focal contract differs")
        compatible
    }.onFailure { DebugLog.w(TAG, "MasterLive donor contract could not be read", it) }.getOrDefault(false)

    private fun isK100Shaped(instance: Any): Boolean = runCatching {
        val profile = hostProfile ?: return@runCatching false
        val clazz = instance.javaClass
        profile.configType.isAssignableFrom(clazz) &&
            profile.configMethod(clazz, profile.masterLiveGate, java.lang.Boolean.TYPE) != null &&
            profile.configMethod(clazz, profile.effectTable, Map::class.java) != null
    }.getOrDefault(false)

    private fun buildFrom(
        loader: ClassLoader,
        resolver: java.lang.reflect.Method,
        name: String
    ): Any? = runCatching {
        val cls = resolver.invoke(null, name) as? Class<*> ?: return@runCatching null
        cls.getDeclaredConstructor().newInstance()
    }.getOrNull()

    fun hookMasterLiveModePlacement() {
        val profile = hostProfile ?: return
        // CameraIdentity.frontMasterLiveMode is a no-op for arrays that already contain 231,
        // so this is inert on configs with native 231 — it only fronts the base C1143 `M()`
        // the active config inherits (no 231).
        val methods = LinkedHashSet<Method>()
        for (target in configDispatchClasses()) {
            profile.modeOrders.forEach { name ->
                profile.configMethod(target, name, IntArray::class.java)?.let(methods::add)
            }
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "config ${profile.modeOrders}()[I not found; masterlive placement skipped")
            return
        }
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_masterlive_mode_front_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                    val order = param.result as? IntArray ?: return@after
                    if (!order.contains(CameraIdentity.MODE_LIST_MORE_MARKER)) return@after
                    val fronted = CameraIdentity.frontMasterLiveMode(order)
                    if (fronted != null) param.result = fronted
                }
            }
            DebugLog.d(TAG, "masterlive placement hooked on ${method.declaringClass.name}#${method.name}()")
        }
    }

    fun hookMasterLiveSupportGate() {
        val profile = hostProfile ?: return
        val methods = LinkedHashSet<Method>()
        activeConfigInstance()?.javaClass?.let { target ->
            profile.configMethod(target, profile.masterLiveGate, java.lang.Boolean.TYPE)
                ?.let(methods::add)
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "masterlive registry getter ${profile.masterLiveGate}() not found; support gate skipped")
            return
        }
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_masterlive_support_gate_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                    if ((param.result as? Boolean) == true) return@after
                    param.result = true
                    if (mlGateLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive registry gate ${method.name}() forced true on ${method.declaringClass.name}")
                    }
                }
            }
            masterLiveGateReady = true
            DebugLog.i(TAG, "masterlive support gate hooked on ${method.declaringClass.name}#${method.name}()")
        }
    }

    private val mlGateLogged = AtomicReference(false)

    fun hookMasterLiveRealEffectTable() {
        val profile = hostProfile ?: return
        if (configDispatchClasses().isEmpty()) {
            DebugLog.w(TAG, "no config dispatch classes; masterlive effect table hook skipped")
            return
        }
        val seen = HashSet<Method>()
        var hooked = 0
        for (clazz in configDispatchClasses()) {
            val method = profile.configMethod(clazz, profile.effectTable, Map::class.java) ?: continue
            if (!seen.add(method)) continue
            deoptimize(method)
            method.hook("cam_masterlive_effect_table_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled()) return@after
                    var table = param.result
                    if (table == null || (table as? Map<*, *>)?.isEmpty() == true) {
                        table = borrowK100EffectTable() ?: return@after
                        param.result = table
                        if (mlTableBorrowLogged.getAndSet(true) == false) {
                            DebugLog.i(TAG, "masterlive effect table borrowed from K100 config (active config ${method.name}() is null)")
                        }
                    }
                    if (!redCarpetEnabled()) return@after
                    val merged = injectRedCarpetEntry(table) ?: return@after
                    param.result = merged
                    if (mlRedCarpetLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive effect table: injected 红毯运镜 (type 1) entry")
                    }
                }
            }
            hooked++
        }
        if (hooked == 0) {
            DebugLog.w(TAG, "no ${profile.effectTable}() Method resolved; masterlive effect table hook skipped")
        } else {
            DebugLog.i(TAG, "masterlive effect table hook installed on $hooked ${profile.effectTable}() dispatch method(s)")
        }
    }

    fun hookMasterLiveSlowMotionFallback() {
        if (!CameraMasterLiveRedCarpet.needsSlowMotionFallback(Build.DEVICE, Build.HARDWARE)) {
            return
        }
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val reader = masterLiveTypeMethod.get()?.let(semantics::reference) ?: return
        val readers = setOf(DexOperandIndex.descriptor(reader))
        val method = semantics.unique("MasterLive slow-motion type", semantics.dex.strings("1").filter { ref ->
            Modifier.isStatic(ref.accessFlags) && ref.returnType == "Z" && ref.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                semantics.dex.code(ref).calls.any { DexOperandIndex.descriptor(it.method) in readers }
        }) ?: return
        val clazz = method.declaringClass
        deoptimize(method)
        method.hook("cam_masterlive_red_carpet_safe_session") {
            after { param ->
                if (!masterliveEnabled() || !redCarpetEnabled()) return@after
                if ((param.args.firstOrNull() as? Int) != CameraIdentity.MASTER_LIVE_MODE_ID) return@after
                if (currentMasterLiveType() != CameraMasterLiveRedCarpet.RED_CARPET_TYPE) return@after
                if (param.result != true) return@after
                param.result = false
                if (mlRedCarpetFallbackLogged.getAndSet(true) == false) {
                    DebugLog.w(
                        TAG,
                        "red-carpet slow-motion fallback: ${Build.DEVICE}/${Build.HARDWARE} " +
                            "has no HSR path; using the safe normal movement session"
                    )
                }
            }
        }
        redCarpetCaptureReady.set(true)
        DebugLog.i(TAG, "red-carpet slow-motion fallback hooked on ${clazz.name}#${method.name}(I)")
    }

    private val mlRedCarpetFallbackLogged = AtomicReference(false)

    private val mlRedCarpetLogged = AtomicReference(false)

    private data class EffectEntryShape(val fields: List<Field>, val constructor: java.lang.reflect.Constructor<*>)
    private val effectEntryShapes = java.util.concurrent.ConcurrentHashMap<Class<*>, EffectEntryShape>()

    private fun injectRedCarpetEntry(table: Any): Any? {
        if (table !is Map<*, *> || table.containsKey(CameraMasterLiveRedCarpet.RED_CARPET_TYPE)) return null
        val sourceType = CameraMasterLiveRedCarpet.CLONE_SOURCE_TYPES.firstOrNull { table[it] != null } ?: return null
        val source = table[sourceType] ?: return null
        return runCatching {
            val shape = effectEntryShapes.getOrPut(source.javaClass) {
                EffectEntryShape(source.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                    .onEach { it.isAccessible = true },
                    source.javaClass.getDeclaredConstructor().apply { isAccessible = true })
            }
            val fields = shape.fields
            val type = fields.filter { it.type == String::class.java && it.get(source) == sourceType }.singleOrNull()
                ?: return@runCatching null
            val default = fields.singleOrNull { it.type == java.lang.Boolean.TYPE } ?: return@runCatching null
            val lists = fields.filter { List::class.java.isAssignableFrom(it.type) }
                .mapNotNull { it.get(source) as? List<*> }.filter { it.isNotEmpty() }
            val roles = lists.singleOrNull { values -> values.all { it is String && it.matches(Regex("[A-Za-z_]+")) } }
                ?: return@runCatching null
            val ranges = lists.singleOrNull { values -> values.all { it is String && it.contains(":") } }
                ?: return@runCatching null
            val zooms = lists.singleOrNull { values -> values.size == roles.size * 2 && values.all { it is Float } }
                ?: return@runCatching null
            if (!CameraMasterLiveRedCarpet.segmentsConsistent(roles.size, zooms.size, ranges.size)) return@runCatching null
            val copy = shape.constructor.newInstance()
            fields.forEach { field ->
                val value = field.get(source)
                field.set(copy, if (value is List<*>) ArrayList(value) else value)
            }
            type.set(copy, CameraMasterLiveRedCarpet.RED_CARPET_TYPE)
            default.setBoolean(copy, false)
            val order = CameraMasterLiveRedCarpet.orderedKeys(table.keys.filterIsInstance<String>()) ?: return@runCatching null
            LinkedHashMap<Any?, Any?>().apply { order.forEach { key ->
                put(key, if (key == CameraMasterLiveRedCarpet.RED_CARPET_TYPE) copy else table[key])
            } }
        }.onFailure { DebugLog.w(TAG, "red-carpet entry schema rejected", it) }.getOrNull()
    }

    private val k100EffectTableInstance = AtomicReference<Any?>(null)
    private val k100EffectTableMethod = AtomicReference<Method?>(null)
    private val k100EffectTableProbeFinished = AtomicReference(false)

    private val mlTableBorrowLogged = AtomicReference(false)
    private val mlTableUnavailableLogged = AtomicReference(false)

    fun masterLiveHasEffectTable(): Boolean {
        val profile = hostProfile ?: return false
        val config = activeConfigInstance() ?: return false
        val method = profile.configMethod(config.javaClass, profile.effectTable, Map::class.java)
            ?: return false
        val native = runCatching { method.invoke(config) as? Map<*, *> }.getOrNull()
        return native?.isNotEmpty() == true ||
            (borrowK100EffectTable() as? Map<*, *>)?.isNotEmpty() == true
    }

    fun borrowK100EffectTable(): Any? {
        k100EffectTableInstance.get()?.let { instance ->
            k100EffectTableMethod.get()?.let { method ->
                return runCatching { method.invoke(instance) }.getOrNull()
            }
        }
        if (k100EffectTableProbeFinished.get()) return null
        synchronized(k100EffectTableInstance) {
            k100EffectTableInstance.get()?.let { instance ->
                k100EffectTableMethod.get()?.let { method ->
                    return runCatching { method.invoke(instance) }.getOrNull()
                }
            }
            if (k100EffectTableProbeFinished.get()) return null
            k100EffectTableProbeFinished.set(true)
            val loader = classLoader
            val resolver = CameraResolver.resolveSourceNameResolver(
                CameraResolver.Ctx(loader, hookParam.appInfo), TAG,
            ) ?: run {
                logEffectTableUnavailableOnce("source-name resolver unavailable")
                return null
            }
            val k100 = resolveK100Config(loader, resolver) ?: run {
                logEffectTableUnavailableOnce("K100 config not resolved by semantic source profile")
                return null
            }
            val effectName = hostProfile?.effectTable ?: return null
            val q0Method = runCatching {
                HostAccess.requirePublicMethod(k100.javaClass, effectName, accessible = false).takeIf {
                    it.parameterTypes.isEmpty() && Map::class.java.isAssignableFrom(it.returnType)
                }
            }.getOrNull() ?: run {
                logEffectTableUnavailableOnce("${k100.javaClass.name}#$effectName() not found")
                return null
            }
            k100EffectTableInstance.set(k100)
            k100EffectTableMethod.set(q0Method)
            val table = runCatching { q0Method.invoke(k100) }.getOrNull()
            if (table == null) logEffectTableUnavailableOnce("${k100.javaClass.name}#$effectName() returned null")
            return table
        }
    }

    private fun logEffectTableUnavailableOnce(reason: String) {
        if (mlTableUnavailableLogged.getAndSet(true) == false) {
            DebugLog.w(TAG, "masterlive effect table borrow unavailable: $reason")
        }
    }

    fun hookMasterLiveFullFocal() {
        val profile = hostProfile ?: return
        if (configDispatchClasses().isEmpty()) {
            DebugLog.w(TAG, "no config dispatch classes; masterlive full focal hook skipped")
            return
        }
        val seen = HashSet<Method>()
        var hooked = 0
        for (clazz in configDispatchClasses()) {
            val method = profile.configMethod(clazz, profile.focalStops, SparseArray::class.java) ?: continue
            if (!seen.add(method)) continue
            deoptimize(method)
            method.hook("cam_masterlive_full_focal_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled() || !fullFocalEnabled()) return@after
                    val array = param.result as? SparseArray<*> ?: return@after
                    if (array.indexOfKey(CameraIdentity.MASTER_LIVE_MODE_ID) >= 0) return@after
                    // Copy before mutating: v1() results can be shared instances, and the hook
                    // must never widen the native table's visible state.
                    val clone = runCatching { HostAccess.requirePublicMethod(array.javaClass, "clone", accessible = false) }.getOrNull()
                        ?: return@after
                    @Suppress("UNCHECKED_CAST")
                    val copy = runCatching { clone.invoke(array) as SparseArray<Any> }
                        .getOrNull() ?: return@after
                    copy.put(
                        CameraIdentity.MASTER_LIVE_MODE_ID,
                        // Mirror the value type of the existing table (boxed on every verified
                        // build); an empty table gets the boxed default.
                        CameraIdentity.masterLiveFocalStops(
                            if (array.isNotEmpty()) array.valueAt(0) else null
                        )
                    )
                    param.result = copy
                    if (fullFocalLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive full focal: ${method.name}()[231] = ${CameraIdentity.MASTER_LIVE_FOCAL_STOPS.contentToString()}")
                    }
                }
            }
            hooked++
        }
        if (hooked == 0) {
            DebugLog.w(TAG, "no ${profile.focalStops}() Method resolved; masterlive full focal hook skipped")
        } else {
            DebugLog.i(TAG, "masterlive full focal hook installed on $hooked ${profile.focalStops}() dispatch method(s)")
        }
    }

    private val fullFocalLogged = AtomicReference(false)

    private val componentModuleList = AtomicReference<Class<*>?>()

    private fun resolveComponentModuleList(): Class<*>? {
        componentModuleList.get()?.let { return it }
        synchronized(componentModuleList) {
            componentModuleList.get()?.let { return it }
            val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
            val resolved = CameraResolver.resolveClass(
                scope = TAG, key = "component_module_list", ctx = ctx,

                probe = { bridge ->
                    // Both strings are CONTAINS-matched; the pair pins exactly one class.
                    bridge.findClass {
                        matcher { usingStrings("ComponentModuleList", "setAllSupportModeList") }
                    }.singleOrNull()?.name
                },
                validate = { c ->
                    hasDefaultModeListField(c) &&
                        c.declaredMethods.any {
                            !it.isSynthetic && it.returnType == IntArray::class.java && it.parameterCount == 1
                        }
                },
            )
            componentModuleList.set(resolved)
            return resolved
        }
    }

    private fun hasDefaultModeListField(clazz: Class<*>): Boolean =
        clazz.declaredFields.any { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == IntArray::class.java &&
                runCatching {
                    field.isAccessible = true
                    CameraIdentity.defaultModeListShape(field.get(null) as? IntArray)
                }.getOrDefault(false)
        }

    fun hookMasterLiveOrderFunnel() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "ComponentModuleList not resolved; masterlive order funnel skipped")
            return
        }
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val yMethod = semantics.anchored("mode order funnel", "pref_camera_sort_modes_key") {
            it.definingClass == DexOperandIndex.descriptor(clazz) && it.returnType == "[I" && it.parameterTypes.size == 1
        } ?: return
        deoptimize(yMethod)
        yMethod.hook("cam_masterlive_order_funnel") {
            after { param ->
                if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                val placed = CameraIdentity.placeMasterLiveModeBeforeMarker(param.result as? IntArray)
                if (placed != null) param.result = placed
            }
        }
        DebugLog.d(TAG, "masterlive order funnel hooked on ${clazz.name}#${yMethod.name}")
    }

    fun hookMasterLiveSupportEntry() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "ComponentModuleList not resolved; masterlive support entry skipped")
            return
        }
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val eMethod = semantics.anchored("persisted mode support", "all_support_mode_list") {
            it.definingClass == DexOperandIndex.descriptor(clazz) && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I")
        } ?: return
        deoptimize(eMethod)
        eMethod.hook("cam_masterlive_support_entry") {
            after { param ->
                val modeId = param.args.getOrNull(0) as? Int ?: return@after
                // The persisted all_support_mode_list can predate the 256 entry. Keep the
                // newly injected registry entry usable even when that stale list says false.
                if (legendaryMomentUnlock() && modeId == CameraIdentity.LEGENDARY_MOMENT_MODE_ID) {
                    if ((param.result as? Boolean) == false) param.result = true
                    return@after
                }
                if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                if ((param.result as? Boolean) != false) return@after
                if (modeId != CameraIdentity.MASTER_LIVE_MODE_ID) return@after
                param.result = true
            }
        }
        DebugLog.d(TAG, "masterlive support entry hooked on ${clazz.name}#${eMethod.name}(I)")
    }

    fun hookMasterLiveTeleFallback() {
        // This optional path needs two DEX scans. It is installed only when the switch was
        // already enabled at camera attach; after hooking, the callback still reads it live.
        if (!masterliveTeleFallback()) return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val role23 = CameraResolver.resolveMethodByStrings(
            scope = TAG, key = "role_23_camera_id", ctx = ctx,
            anchors = listOf("roleId=23"),
            shape = {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Integer.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "role-23 camera-id getter not uniquely resolved; tele fallback skipped")
            return
        }
        val role20 = CameraResolver.resolveMethodByStrings(
            scope = TAG, key = "role_20_camera_id", ctx = ctx,
            anchors = listOf("roleId=20"),
            shape = {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Integer.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "role-20 camera-id getter not uniquely resolved; tele fallback skipped")
            return
        }
        deoptimize(role23)
        role23.hook("cam_masterlive_tele_fallback") {
            after { param ->
                if (!masterliveTeleFallback() || deviceIsNezha()) return@after
                if ((param.result as? Int) != -1) return@after
                val teleId = runCatching { role20.invoke(param.thisObject) as? Int }
                    .getOrNull()?.takeIf { it != -1 } ?: return@after
                param.result = teleId
            }
        }
        DebugLog.d(TAG, "masterlive tele fallback hooked on semantic role-23 getter ${role23.declaringClass.name}#${role23.name}()")
    }

    fun hookMasterLiveVideoSizeProbe() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)

        // is backup only — "getLivePhotoVideoSize" is NOT plaintext in the dex (runtime
        // concatenation), so candidates are the primary path.
        val semantics = CameraSemantics.create(ctx, TAG) ?: return
        val method = semantics.anchored("live-photo video size", "getLivePhotoVideoSize: fail") {
            it.returnType == "Landroid/util/Size;" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == "Landroid/util/Size;"
        } ?: return
        val ref = semantics.reference(method) ?: return
        val moduleField = semantics.dex.code(ref).reads.filter {
            it.definingClass == ref.parameterTypes[1].toString() && it.type == "I"
        }.distinctBy { it.toString() }.singleOrNull()?.let { semantics.dex.field(it, classLoader) } ?: return
        deoptimize(method)
        method.hook("cam_masterlive_video_size_probe") {
            after { param ->
                if (!videoSizeProbeEnabled()) return@after
                if ((param.result as? Size) == null) return@after
                val receiver = param.args.getOrNull(1) ?: return@after
                val mode = runCatching { moduleField.getInt(receiver) }.getOrNull() ?: return@after
                if (mode != CameraIdentity.MASTER_LIVE_MODE_ID) return@after
                val original = param.result as? Size ?: return@after
                val pinned = CameraMasterLiveSizeBinding.boundSize(
                    currentMasterLiveType(), original.width, original.height
                ) ?: return@after
                param.result = Size(pinned.first, pinned.second)
                if (videoSizeProbeLogged.getAndSet(true) == false) {
                    DebugLog.i(
                        TAG,
                        "masterlive video size probe: getLivePhotoVideoSize $original " +
                            "(type ${currentMasterLiveType() ?: "?"}) -> ${pinned.first}x${pinned.second}"
                    )
                }
            }
        }
        DebugLog.i(TAG, "masterlive video size probe hooked on ${method.declaringClass.name}#${method.name}(Size,..)")
    }

    private val masterLiveTypeMethod = AtomicReference<Method?>(null)
    fun resolveMasterLiveType() {
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val reader = semantics.cachedUnique("MasterLive selected effect", validate = { method ->
            Modifier.isStatic(method.modifiers) && method.returnType == String::class.java &&
                method.parameterTypes.contentEquals(arrayOf(Integer.TYPE))
        }) {
            val owners = semantics.dex.ownerStrings("ComponentRunningMasterLive", "pref_master_live_key")
            semantics.dex.methods.filter { ref ->
                Modifier.isStatic(ref.accessFlags) && ref.returnType == "Ljava/lang/String;" &&
                    ref.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                    semantics.dex.code(ref).calls.any { it.method.definingClass in owners && it.method.name == "getComponentValue" }
            }
        }
        masterLiveTypeMethod.set(reader)
    }

    private fun currentMasterLiveType(): String? = runCatching {
        masterLiveTypeMethod.get()?.invoke(null, CameraIdentity.MASTER_LIVE_MODE_ID) as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private val videoSizeProbeLogged = AtomicReference(false)

    fun hookMasterLiveVideoSurfaceSize() {
        if (!videoSizeProbeEnabled()) return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val method = CameraResolver.resolveMethodByNumbers(
            scope = TAG,
            key = "masterlive_surface_size_fallback",
            ctx = ctx,
            numbers = listOf(2304, 1296),
            shape = {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == Size::class.java
            },
        )
            ?: run {
                DebugLog.w(TAG, "surface-size fallback was not uniquely resolved; size probe skipped")
                return
            }
        deoptimize(method)
        method.hook("cam_masterlive_video_surface_size") {
            after { param ->
                val original = param.result as? Size ?: return@after
                val receiver = param.thisObject
                // Mode gate: only rewrite inside MasterLive (the same consumer also serves the
                // normal live-photo modes' 4:3 geometry). Inspect the bounded object graph by
                // value so owner/field names can move between camera builds.
                if (!receiverContainsUniqueModeId(receiver, CameraIdentity.MASTER_LIVE_MODE_ID)) {
                    return@after
                }
                val pinned = CameraMasterLiveSizeBinding.boundSize(
                    currentMasterLiveType(), original.width, original.height
                ) ?: return@after
                param.result = Size(pinned.first, pinned.second)
                if (videoSurfaceProbeLogged.getAndSet(true) == false) {
                    DebugLog.i(
                        TAG,
                        "masterlive video surface size: ${method.declaringClass.name}#${method.name}() $original " +
                            "(type ${currentMasterLiveType() ?: "?"}) -> ${pinned.first}x${pinned.second}"
                    )
                }
            }
        }
        DebugLog.i(TAG, "masterlive video surface size hooked on ${method.declaringClass.name}#${method.name}()")
    }

    private val videoSurfaceProbeLogged = AtomicReference(false)

    private fun receiverContainsUniqueModeId(receiver: Any, expected: Int): Boolean {
        val pending = ArrayDeque<Pair<Any, Int>>()
        val visited = IdentityHashMap<Any, Boolean>()
        pending.add(receiver to 0)
        var matches = 0
        var visitedCount = 0
        while (pending.isNotEmpty() && visitedCount < 40) {
            val (owner, depth) = pending.removeFirst()
            if (visited.put(owner, true) != null) continue
            visitedCount++
            var type: Class<*>? = owner.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (field.type == Integer.TYPE) {
                        val value = runCatching {
                            field.isAccessible = true
                            field.getInt(owner)
                        }.getOrNull()
                        if (value == expected && ++matches > 1) return false
                    } else if (depth < 4 && !field.type.isPrimitive && !field.type.isArray &&
                        !field.type.name.startsWith("java.") &&
                        !field.type.name.startsWith("android.") &&
                        !field.type.name.startsWith("kotlin.")
                    ) {
                        runCatching {
                            field.isAccessible = true
                            field.get(owner)
                        }.getOrNull()?.let { pending.add(it to depth + 1) }
                    }
                }
                type = type.superclass
            }
        }
        return matches == 1
    }

    fun masterliveEnabled(): Boolean =
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_ENABLE, false)

    private fun masterliveTeleFallback(): Boolean =
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_TELE_FALLBACK, false)

    private fun videoSizeProbeEnabled(): Boolean =
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, false)

    private val redCarpetCaptureReady = java.util.concurrent.atomic.AtomicBoolean(
        !CameraMasterLiveRedCarpet.needsSlowMotionFallback(Build.DEVICE, Build.HARDWARE),
    )

    private fun redCarpetEnabled(): Boolean = redCarpetCaptureReady.get() &&
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_RED_CARPET, false)

    private fun fullFocalEnabled(): Boolean =
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, false)
}
