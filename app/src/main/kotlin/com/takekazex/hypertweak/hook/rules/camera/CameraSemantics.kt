package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.util.DebugLog
import org.jf.dexlib2.iface.reference.MethodReference
import java.lang.reflect.Method

/** Only semantic entry points and platform API names belong here, never version/name tables. */
internal class CameraSemantics(val ctx: CameraResolver.Ctx, private val scope: String) {
    val dex = CameraDexIndex.open(requireNotNull(ctx.appInfo?.sourceDir))
    fun reflect(reference: MethodReference?): Method? = reference?.let { dex.method(it, ctx.classLoader) }
    fun unique(key: String, references: Iterable<MethodReference>): Method? {
        val candidates = references.distinctBy(CameraDexIndex::descriptor)
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
    fun reference(method: Method): MethodReference? = dex.declared(CameraDexIndex.descriptor(method.declaringClass))
        .singleOrNull { it.name == method.name && it.returnType == CameraDexIndex.descriptor(method.returnType) &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CameraDexIndex::descriptor) }
    fun calls(method: Method): List<MethodReference> = reference(method)?.let { dex.code(it).calls.map { it.method } }.orEmpty()
    fun anchored(key: String, vararg anchors: String, shape: (MethodReference) -> Boolean): Method? =
        unique(key, dex.strings(*anchors).filter(shape))
    fun preferenceGate(key: String, owner: Class<*>? = null): Method? = unique(key,
        dex.preferenceGates(key).filter { it.parameterTypes.isEmpty() &&
            (owner == null || it.definingClass == CameraDexIndex.descriptor(owner)) })
    fun entryConfigGetter(entryName: String, config: Class<*>, api: String = "support"): Method? {
        val entry = entryMethod(entryName, api) ?: return null
        return unique(entryName, calls(entry).filter {
            CameraDexIndex.isInstanceGetter(it, CameraDexIndex.descriptor(config), "Z")
        })
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
