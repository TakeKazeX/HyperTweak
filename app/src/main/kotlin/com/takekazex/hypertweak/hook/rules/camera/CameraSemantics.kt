package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.reference.MethodReference
import java.lang.reflect.Method

/** Only semantic entry points and platform API names belong here, never version/name tables. */
internal class CameraSemantics(val ctx: CameraResolver.Ctx, private val scope: String) {
    val dex by lazy { DexRuntime.operands(requireNotNull(ctx.appInfo?.sourceDir), ctx.appInfo) }
    fun reflect(reference: MethodReference?): Method? = reference?.let { dex.method(it, ctx.classLoader) }
    fun unique(key: String, references: Iterable<MethodReference>): Method? {
        val candidates = references.distinctBy(DexOperandIndex::descriptor)
        val result = candidates.singleOrNull()?.let(::reflect)
        if (result == null) DebugLog.w(scope, "$key: semantic matches=${candidates.size}; feature skipped")
        else DebugLog.d(scope, "$key -> ${result.declaringClass.name}#${result.name}")
        return result
    }
    fun entry(name: String): Class<*>? = ctx.loadOrNull("com.android.camera.features.mode.$name")
        ?.takeIf { type -> type.methods.any { it.name == "getModuleId" && it.returnType == Integer.TYPE } }
    fun entryMethod(name: String, api: String): Method? = entry(name)?.methods?.singleOrNull {
        it.name == api && it.parameterCount == 0 && !it.isSynthetic
    }
    fun reference(method: Method): MethodReference? = dex.declared(DexOperandIndex.descriptor(method.declaringClass))
        .singleOrNull { it.name == method.name && it.returnType == DexOperandIndex.descriptor(method.returnType) &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(DexOperandIndex::descriptor) }
    fun calls(method: Method): List<MethodReference> = reference(method)?.let { dex.code(it).calls.map { it.method } }.orEmpty()
    /** Replay a fully hashed, exact method descriptor before constructing any operand index. */
    fun cachedUnique(key: String, validate: (Method) -> Boolean, references: () -> Iterable<MethodReference>): Method? {
        val path = ctx.appInfo?.sourceDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$scope:$key".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return DexRuntime.resolveMethodMap(
            id = "camera.semantic.$digest", apkPath = path,
            cacheDir = DexRuntime.cacheDirectory(ctx.appInfo), appInfo = ctx.appInfo,
            classLoader = ctx.classLoader,
            // The complete APK identity and MethodDescriptorCodec preserve the exact owner,
            // parameter and return contract established by the operand resolver on a miss.
            validate = { _, method -> validate(method) },
            compute = { unique(key, references())?.let { mapOf("method" to it) }.orEmpty() },
        )?.get("method")
    }
    /**
     * [shape] selects the scan candidates and may read bytecode. A cache hit validates with
     * [replay] instead, which must only compare the signature: the cached descriptor is bound to the
     * full APK hash, and a [shape] that reads code would build the whole operand index on the
     * install thread (0.8 s on every camera start for the watermark debug flag).
     */
    fun anchored(
        key: String,
        vararg anchors: String,
        replay: ((MethodReference) -> Boolean)? = null,
        shape: (MethodReference) -> Boolean,
    ): Method? =
        cachedUnique(key + anchors.joinToString("\u0000"), validate = { method ->
            (replay ?: shape)(org.jf.dexlib2.immutable.reference.ImmutableMethodReference(
                DexOperandIndex.descriptor(method.declaringClass), method.name,
                method.parameterTypes.map(DexOperandIndex::descriptor), DexOperandIndex.descriptor(method.returnType),
            ))
        }) { dex.strings(*anchors).filter(shape) }
    fun preferenceGate(key: String, owner: Class<*>? = null): Method? =
        cachedUnique("preference gate:$key:${owner?.name}", validate = {
            it.parameterCount == 0 && (owner == null || it.declaringClass == owner)
        }) {
            dex.preferenceGates(key).filter { it.parameterTypes.isEmpty() &&
                (owner == null || it.definingClass == DexOperandIndex.descriptor(owner)) }
        }
    fun entryConfigGetter(entryName: String, config: Class<*>, api: String = "support"): Method? =
        cachedUnique("entry config getter:$entryName:$api:${config.name}", validate = {
            it.declaringClass == config && !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
        }) {
            val entry = entryMethod(entryName, api) ?: return@cachedUnique emptyList()
            calls(entry).filter { DexOperandIndex.isInstanceGetter(it, DexOperandIndex.descriptor(config), "Z") }
        }

    /** Every distinct method of [references], replayed like [cachedUnique]; an empty result is cached too. */
    fun cachedMethods(key: String, validate: (Method) -> Boolean, references: () -> Iterable<MethodReference>): List<Method>? {
        val path = ctx.appInfo?.sourceDir ?: return null
        return DexRuntime.resolveMethodMap(
            id = "camera.semantic." + digest("methods:$key"), apkPath = path,
            cacheDir = DexRuntime.cacheDirectory(ctx.appInfo), appInfo = ctx.appInfo,
            classLoader = ctx.classLoader,
            validate = { _, method -> validate(method) },
            compute = {
                references().distinctBy(DexOperandIndex::descriptor).mapNotNull(::reflect).distinct()
                    .withIndex().associate { it.index.toString() to it.value }
            },
        )?.entries?.sortedBy { it.key.toInt() }?.map { it.value }
    }

    /** Plain values such as resource ids, bound to the APK identity like every other entry here. */
    fun cachedValues(key: String, validate: (List<String>) -> Boolean, compute: () -> List<String>): List<String>? {
        val path = ctx.appInfo?.sourceDir ?: return null
        return DexRuntime.resolve(
            feature = object : com.takekazex.hypertweak.hook.base.dex.DexFeature {
                override val id = "camera.semantic." + digest("values:$key")
                override val schema = 1
            },
            apkPath = path, cacheDir = DexRuntime.cacheDirectory(ctx.appInfo), appInfo = ctx.appInfo, cacheEmpty = true,
            compute = { compute().withIndex().associate { it.index.toString() to it.value } },
            decode = { values, _ ->
                values.entries.sortedBy { it.key.toInt() }.map { it.value }.takeIf(validate)
            },
        )
    }

    private fun digest(key: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$scope:$key".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /**
     * The field a cached method reads, replayed like [cachedUnique]: [reference] reads bytecode and
     * runs only on a miss, and a hit is validated by reflection alone.
     */
    fun cachedField(key: String, validate: (java.lang.reflect.Field) -> Boolean,
        reference: () -> org.jf.dexlib2.iface.reference.FieldReference?): java.lang.reflect.Field? {
        val path = ctx.appInfo?.sourceDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$scope:field:$key".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return DexRuntime.resolve(
            feature = object : com.takekazex.hypertweak.hook.base.dex.DexFeature {
                override val id = "camera.semantic.$digest"
                override val schema = 1
            },
            apkPath = path, cacheDir = DexRuntime.cacheDirectory(ctx.appInfo), appInfo = ctx.appInfo, cacheEmpty = true,
            compute = { reference()?.let { mapOf("field" to MethodDescriptorCodec.descriptor(it)) }.orEmpty() },
            decode = { values, _ ->
                val fields = values.mapValues { MethodDescriptorCodec.field(it.value, ctx.classLoader)?.takeIf(validate) }
                if (fields.values.any { it == null }) null else fields
            },
        )?.get("field")
    }
    fun methodByShape(type: Class<*>, key: String, shape: (Method) -> Boolean): Method? =
        type.declaredMethods.filter { !it.isSynthetic && shape(it) }.singleOrNull()?.apply { isAccessible = true }
            ?: run { DebugLog.w(scope, "$key: non-unique method shape on ${type.name}"); null }

    companion object {
        fun create(ctx: CameraResolver.Ctx, scope: String): CameraSemantics? = runCatching {
            CameraSemantics(ctx, scope)
        }.onFailure { DebugLog.w(scope, "camera semantic index unavailable", it) }.getOrNull()
        fun booleanGetter(method: MethodReference) = method.returnType == "Z" && method.parameterTypes.isEmpty()
    }
}
