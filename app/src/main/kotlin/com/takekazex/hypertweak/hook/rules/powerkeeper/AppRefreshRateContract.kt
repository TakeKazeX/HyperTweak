package com.takekazex.hypertweak.hook.rules.powerkeeper

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * PowerKeeper's per-app refresh-rate decision.
 *
 * The custom-mode updater takes MiSettings' `"pkg,state"` message: it puts 60 into the custom
 * map or removes the package, then points the active per-app map at the custom map. The
 * foreground handler looks the foreground package up in that active map and hands the Integer
 * to the screen-effect setter; packages missing from it get the user's global rate.
 */
internal class AppRefreshRateContract private constructor(
    val foreground: MethodReference,
    /** The active per-app map consulted for the foreground package. */
    val active: FieldReference,
    /** The user's custom map; the active map is this one only in custom mode. */
    val custom: FieldReference,
    val foregroundPackage: FieldReference,
) {
    companion object {
        private const val ARRAY_MAP = "Landroid/util/ArrayMap;"
        private const val PUT = "Landroid/util/ArrayMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        private const val REMOVE = "Landroid/util/ArrayMap;->remove(Ljava/lang/Object;)Ljava/lang/Object;"
        private const val GET = "Landroid/util/ArrayMap;->get(Ljava/lang/Object;)Ljava/lang/Object;"
        private const val SPLIT = "Ljava/lang/String;->split(Ljava/lang/String;)[Ljava/lang/String;"
        private const val FOREGROUND_INFO = "Lmiui/process/ForegroundInfo;"

        fun resolve(index: DexOperandIndex): AppRefreshRateContract? {
            data class Updater(val owner: String, val active: FieldReference, val custom: FieldReference)
            val updaters = index.methods.filter { method ->
                method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                    index.hasNumber(method, 60L)
            }.mapNotNull { method ->
                val code = index.code(method)
                val descriptors = code.calls.map { DexOperandIndex.descriptor(it.method) }
                if (SPLIT !in descriptors) return@mapNotNull null
                val mutated = code.calls.filter { DexOperandIndex.descriptor(it.method) in setOf(PUT, REMOVE) }
                    .map { (it.arguments.firstOrNull() as? DexOperandIndex.Value.Field)?.field ?: return@mapNotNull null }
                val custom = mutated.distinctBy(::key).singleOrNull()?.takeIf { it.definingClass == method.definingClass && it.type == ARRAY_MAP }
                    ?: return@mapNotNull null
                if (descriptors.count { it == PUT } != 1 || descriptors.count { it == REMOVE } != 1) return@mapNotNull null
                val active = code.writes.filter { (field, value) ->
                    field.definingClass == method.definingClass && field.type == ARRAY_MAP &&
                        (value as? DexOperandIndex.Value.Field)?.field?.let(::key) == key(custom)
                }.map { it.first }.distinctBy(::key).singleOrNull()?.takeIf { key(it) != key(custom) } ?: return@mapNotNull null
                Updater(method.definingClass, active, custom)
            }
            val updater = updaters.singleOrNull() ?: return null
            val handlers = index.declared(updater.owner).filter { method ->
                method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf(FOREGROUND_INFO)
            }.mapNotNull { method ->
                val lookups = index.code(method).calls.filter { call ->
                    DexOperandIndex.descriptor(call.method) == GET &&
                        (call.arguments.getOrNull(0) as? DexOperandIndex.Value.Field)?.field?.let(::key) == key(updater.active)
                }
                val packages = lookups.mapNotNull { (it.arguments.getOrNull(1) as? DexOperandIndex.Value.Field)?.field }
                    .filter { it.definingClass == FOREGROUND_INFO && it.type == "Ljava/lang/String;" }
                    .distinctBy(::key)
                packages.singleOrNull()?.let { method to it }
            }
            val (foreground, foregroundPackage) = handlers.singleOrNull() ?: return null
            return AppRefreshRateContract(foreground, updater.active, updater.custom, foregroundPackage)
        }

        private fun key(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"
    }
}
