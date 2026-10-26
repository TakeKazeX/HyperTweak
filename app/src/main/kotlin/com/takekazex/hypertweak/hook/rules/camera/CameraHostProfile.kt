package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The live host object graph plus independently resolved feature contracts. No version families. */
internal object CameraHostProfile {
    private val bindings = WeakHashMap<ClassLoader, WeakReference<Binding>>()
    private val gateFacades = WeakHashMap<ClassLoader, WeakReference<Pair<Class<*>, Class<*>>>>()
    data class Binding(
        val facade: Class<*>,
        val configField: Field,
        val configType: Class<*>,
        val modelArrayGetter: String?,
        val brandGetter: String?,
        val lccGate: String?,
        val masterLiveGate: String?,
        val modeOrders: List<String>,
        val effectTable: String?,
        val focalStops: String?,
        private val singletonField: Field,
    ) {
        fun descriptors(): Map<String, String> = buildMap {
            put("facade", facade.name)
            put("configField", MethodDescriptorCodec.descriptor(configField))
            put("singleton", MethodDescriptorCodec.descriptor(singletonField))
            mapOf("model" to modelArrayGetter, "brand" to brandGetter, "lcc" to lccGate,
                "master" to masterLiveGate, "effects" to effectTable, "zoom" to focalStops)
                .forEach { (key, value) -> value?.let { put(key, it) } }
            put("orderCount", modeOrders.size.toString())
            modeOrders.forEachIndexed { i, name -> put("order.$i", name) }
        }
        fun configMethod(receiver: Class<*>, name: String?, returnType: Class<*>): Method? =
            name?.let { runCatching {
                HostAccess.requirePublicMethod(receiver, it, accessible = true).takeIf { method ->
                    !Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                        returnType.isAssignableFrom(method.returnType)
                }?.apply { isAccessible = true }
            }.getOrNull() }

        fun facadeInstance(): Any? = runCatching { singletonField.get(null) }.getOrNull()
        fun configInstance(): Any? = runCatching {
            facadeInstance()?.let(configField::get)?.takeIf(configType::isInstance)
        }.getOrNull()

        /** The actual host base type defines the ABI; feature misses never invalidate it. */
        fun acceptsConfig(candidate: Any): Boolean = configType.isInstance(candidate) &&
            configType.methods.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.all { expected ->
                runCatching { HostAccess.requirePublicMethod(candidate.javaClass, expected.name, *expected.parameterTypes, accessible = false) }
                    .getOrNull()?.returnType == expected.returnType
            }
        fun replaceConfigInstance(target: Any): Boolean = runCatching {
            if (!acceptsConfig(target)) return@runCatching false
            val owner = facadeInstance() ?: return@runCatching false
            configField.set(owner, target)
            configField.get(owner) === target
        }.getOrDefault(false)
    }

    @Synchronized fun resolve(ctx: CameraResolver.Ctx, scope: String): Binding? {
        bindings[ctx.classLoader]?.get()?.let { return it }
        val path = ctx.appInfo?.sourceDir ?: return null
        return DexRuntime.resolve(
            feature = object : DexFeature { override val id = "camera.host_profile"; override val schema = 1 },
            apkPath = path, cacheDir = DexRuntime.cacheDirectory(ctx.appInfo), appInfo = ctx.appInfo,
            compute = { resolveUncached(ctx, scope)?.descriptors().orEmpty() },
            decode = { values, _ -> decodeBinding(values, ctx.classLoader) },
        )?.also { bindings[ctx.classLoader] = WeakReference(it) }
    }

    /** Full APK identity preserves graph evidence; replay additionally checks every stored ABI. */
    internal fun decodeBinding(values: Map<String, String>, loader: ClassLoader): Binding? = runCatching {
        val facade = Class.forName(values.getValue("facade"), false, loader)
        val field = requireNotNull(MethodDescriptorCodec.field(values.getValue("configField"), loader))
        val singleton = requireNotNull(MethodDescriptorCodec.field(values.getValue("singleton"), loader))
        require(field.declaringClass == facade && !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive)
        require(singleton.type == facade && Modifier.isStatic(singleton.modifiers))
        val config = field.type
        fun member(key: String, owner: Class<*>, returns: Class<*>, static: Boolean): String? {
            val name = values[key] ?: return null
            val method = runCatching { HostAccess.requireDeclaredMethod(owner, name, accessible = false) }.getOrElse { HostAccess.requirePublicMethod(owner, name, accessible = false) }
            require(method.parameterCount == 0 && returns.isAssignableFrom(method.returnType) &&
                Modifier.isStatic(method.modifiers) == static && !method.isBridge && !method.isSynthetic)
            return name
        }
        val count = values.getValue("orderCount").toInt()
        require(count in 0..256)
        val orders = (0 until count).map { i ->
            requireNotNull(member("order.$i", config, IntArray::class.java, false))
        }
        Binding(facade, field, config,
            member("model", facade, Array<String>::class.java, false),
            member("brand", facade, String::class.java, false),
            member("lcc", facade, java.lang.Boolean.TYPE, true),
            member("master", config, java.lang.Boolean.TYPE, false), orders,
            member("effects", config, Map::class.java, false),
            member("zoom", config, android.util.SparseArray::class.java, false), singleton)
    }.getOrNull()

    private fun resolveUncached(ctx: CameraResolver.Ctx, scope: String): Binding? = runCatching {
        val semantics = CameraSemantics.create(ctx, scope) ?: return null
        val dex = semantics.dex
        val facade = dex.ownerStrings("LCC").mapNotNull {
            runCatching { DexOperandIndex.type(it, ctx.classLoader) }.getOrNull()
        }.filter { type -> configFields(type).size == 1 && singleton(type) != null
        }.singleOrNull() ?: run { DebugLog.w(scope, "camera config object graph is not unique"); return null }
        val field = configFields(facade).single().apply { isAccessible = true }
        val config = field.type
        val owner = DexOperandIndex.descriptor(facade)
        val model = facade.declaredMethods.singleOrNull { it.returnType == Array<String>::class.java &&
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
        val modelReference = model?.let(semantics::reference)
        val brand = if (modelReference == null) null else semantics.unique("watermark brand slot", dex.declared(owner).filter { method ->
            method.returnType == "Ljava/lang/String;" && method.parameterTypes.isEmpty() &&
                dex.code(method).arrays.any { read ->
                    (read.producer as? DexOperandIndex.Value.Call)?.method?.let(DexOperandIndex::descriptor) ==
                        DexOperandIndex.descriptor(modelReference) && read.index == DexOperandIndex.Value.Number(0)
                }
        })
        val lcc = semantics.unique("LCC", dex.strings("LCC").filter {
            it.definingClass == owner && CameraSemantics.booleanGetter(it) && Modifier.isStatic(it.accessFlags)
        })
        val master = semantics.entryConfigGetter("masterlive.MasterLiveModuleEntry", config)
        val orders = (
            dex.strings("pref_camera_sort_modes_key").filter { it.returnType == "[I" }
                .flatMap { dex.localCalls(it) }.filter {
                    DexOperandIndex.isInstanceGetter(it, DexOperandIndex.descriptor(config), "[I")
                }).distinctBy(DexOperandIndex::descriptor).mapNotNull(semantics::reflect)
        val effects = semantics.unique("MasterLive immutable effect definitions",
            dex.ownerStrings("ComponentRunningMasterLive", "pref_master_live_key").flatMap(dex::declared)
                .flatMap { dex.code(it).calls }.filter {
                    it.method.definingClass == "Ljava/util/Collections;" && it.method.name == "unmodifiableMap"
                }.flatMap { it.arguments }.filterIsInstance<DexOperandIndex.Value.Call>()
                .map { it.method }.filter { DexOperandIndex.isInstanceGetter(it, DexOperandIndex.descriptor(config), "Ljava/util/Map;") })
        val zoomOwners = dex.ownerStrings("ZoomUtil")
        val zoom = semantics.unique("per-mode zoom stops", zoomOwners.flatMap(dex::declared)
            .filter { it.returnType == "[Ljava/lang/Float;" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I", "Z", "Z", "[Ljava/lang/Float;") }
            .flatMap { dex.code(it).calls }.map { it.method }.filter {
                DexOperandIndex.isInstanceGetter(it, DexOperandIndex.descriptor(config), "Landroid/util/SparseArray;") &&
                    semantics.reflect(it)?.genericReturnType?.typeName == "android.util.SparseArray<java.lang.Float[]>"
            })
        Binding(facade, field, config, model?.name, brand?.name, lcc?.name, master?.name,
            orders.map { it.name }, effects?.name, zoom?.name, singleton(facade)!!).also {
            DebugLog.i(scope, "camera semantic profile: facade=${facade.name}, config=${config.name}, " +
                "master=${master?.name}, orders=${orders.map { it.name }}, effects=${effects?.name}, zoom=${zoom?.name}")
        }
    }.onFailure { DebugLog.w(scope, "camera config graph resolution failed", it) }.getOrNull()

    /**
     * Resolves only what the device-config gate needs: the config facade and its config type.
     *
     * [resolve] builds the full binding, which requires the dexlib2 operand index
     * (`CameraSemantics` -> `DexOperandIndex`). That index enumerates every method in the camera APK
     * (188k on this build) and measured 577-773 ms on an idle device, but seconds during boot when
     * the 205 MB APK is read cold under IO contention - which is what pushed the process past
     * ActivityManager's provider deadline.
     *
     * The gate itself needs nothing from that index: the facade is the unique class containing
     * the `LCC` marker with exactly one config field and one singleton, and DexKit answers the
     * marker query natively. The full binding is still built when a feature that needs it runs.
     */
    @Synchronized fun resolveGateFacade(ctx: CameraResolver.Ctx, scope: String): Pair<Class<*>, Class<*>>? {
        gateFacades[ctx.classLoader]?.get()?.let { return it }
        val facade = CameraResolver.resolveClassByStrings(
            scope = scope, key = "camera_config_gate_facade", ctx = ctx,
            anchors = listOf(LCC_MARKER),
            validate = { type -> configFields(type).size == 1 && singleton(type) != null },
        ) ?: return null
        val config = configFields(facade).single().type
        return (facade to config).also { gateFacades[ctx.classLoader] = WeakReference(it) }
    }

    private const val LCC_MARKER = "LCC"

    private fun configFields(type: Class<*>): List<Field> = type.declaredFields.filter { field ->
        !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive &&
            field.type.methods.count { it.returnType == java.lang.Boolean.TYPE && it.parameterCount == 0 } > 20 &&
            field.type.methods.any { Map::class.java.isAssignableFrom(it.returnType) } &&
            field.type.methods.any { it.returnType == IntArray::class.java }
    }
    private fun singleton(type: Class<*>): Field? = (type.declaredFields.toList() +
        type.declaredClasses.flatMap { it.declaredFields.toList() }).filter {
        Modifier.isStatic(it.modifiers) && it.type == type
    }.singleOrNull()?.apply { isAccessible = true }
}
