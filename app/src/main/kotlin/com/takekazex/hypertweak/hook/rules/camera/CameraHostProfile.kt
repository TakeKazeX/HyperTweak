package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The live host object graph plus independently resolved feature contracts. No version families. */
internal object CameraHostProfile {
    private val bindings = WeakHashMap<ClassLoader, WeakReference<Binding>>()
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
        fun configMethod(receiver: Class<*>, name: String?, returnType: Class<*>): Method? =
            name?.let { runCatching {
                receiver.getMethod(it).takeIf { method ->
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
                runCatching { candidate.javaClass.getMethod(expected.name, *expected.parameterTypes) }
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
        return resolveUncached(ctx, scope)?.also { bindings[ctx.classLoader] = WeakReference(it) }
    }

    private fun resolveUncached(ctx: CameraResolver.Ctx, scope: String): Binding? = runCatching {
        val semantics = CameraSemantics.create(ctx, scope) ?: return null
        val dex = semantics.dex
        val facade = dex.ownerStrings("LCC").mapNotNull {
            runCatching { CameraDexIndex.type(it, ctx.classLoader) }.getOrNull()
        }.filter { type -> configFields(type).size == 1 && singleton(type) != null
        }.singleOrNull() ?: run { DebugLog.w(scope, "camera config object graph is not unique"); return null }
        val field = configFields(facade).single().apply { isAccessible = true }
        val config = field.type
        val owner = CameraDexIndex.descriptor(facade)
        val model = facade.declaredMethods.singleOrNull { it.returnType == Array<String>::class.java &&
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
        val modelReference = model?.let(semantics::reference)
        val brand = if (modelReference == null) null else semantics.unique("watermark brand slot", dex.declared(owner).filter { method ->
            method.returnType == "Ljava/lang/String;" && method.parameterTypes.isEmpty() &&
                dex.code(method).arrays.any { read ->
                    (read.producer as? CameraDexIndex.Value.Call)?.method?.let(CameraDexIndex::descriptor) ==
                        CameraDexIndex.descriptor(modelReference) && read.index == CameraDexIndex.Value.Number(0)
                }
        })
        val lcc = semantics.unique("LCC", dex.strings("LCC").filter {
            it.definingClass == owner && CameraSemantics.booleanGetter(it) && Modifier.isStatic(it.accessFlags)
        })
        val master = semantics.entryConfigGetter("masterlive.MasterLiveModuleEntry", config)
        val orders = (
            dex.strings("pref_camera_sort_modes_key").filter { it.returnType == "[I" }
                .flatMap { dex.localCalls(it) }.filter {
                    CameraDexIndex.isInstanceGetter(it, CameraDexIndex.descriptor(config), "[I")
                }).distinctBy(CameraDexIndex::descriptor).mapNotNull(semantics::reflect)
        val effects = semantics.unique("MasterLive immutable effect definitions",
            dex.ownerStrings("ComponentRunningMasterLive", "pref_master_live_key").flatMap(dex::declared)
                .flatMap { dex.code(it).calls }.filter {
                    it.method.definingClass == "Ljava/util/Collections;" && it.method.name == "unmodifiableMap"
                }.flatMap { it.arguments }.filterIsInstance<CameraDexIndex.Value.Call>()
                .map { it.method }.filter { CameraDexIndex.isInstanceGetter(it, CameraDexIndex.descriptor(config), "Ljava/util/Map;") })
        val zoomOwners = dex.ownerStrings("ZoomUtil")
        val zoom = semantics.unique("per-mode zoom stops", zoomOwners.flatMap(dex::declared)
            .filter { it.returnType == "[Ljava/lang/Float;" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I", "Z", "Z", "[Ljava/lang/Float;") }
            .flatMap { dex.code(it).calls }.map { it.method }.filter {
                CameraDexIndex.isInstanceGetter(it, CameraDexIndex.descriptor(config), "Landroid/util/SparseArray;") &&
                    semantics.reflect(it)?.genericReturnType?.typeName == "android.util.SparseArray<java.lang.Float[]>"
            })
        Binding(facade, field, config, model?.name, brand?.name, lcc?.name, master?.name,
            orders.map { it.name }, effects?.name, zoom?.name, singleton(facade)!!).also {
            DebugLog.i(scope, "camera semantic profile: facade=${facade.name}, config=${config.name}, " +
                "master=${master?.name}, orders=${orders.map { it.name }}, effects=${effects?.name}, zoom=${zoom?.name}")
        }
    }.onFailure { DebugLog.w(scope, "camera config graph resolution failed", it) }.getOrNull()

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
