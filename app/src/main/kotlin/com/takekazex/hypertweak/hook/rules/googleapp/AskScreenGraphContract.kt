package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec as Codec
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/** Recover the feature's connected ownership graph, without diagnostic text or obfuscated names. */
internal object AskScreenGraphContract {
    data class FieldUses(val readers: List<MethodReference>, val writers: List<MethodReference>)
    data class Match(
        val eligibility: String, val constructor: String, val hint: String, val cached: String,
        val modelCoordinator: String, val entryOwner: String, val entry: String,
    )

    fun resolve(
        index: DexOperandIndex,
        thumbnail: String,
        callers: (MethodReference) -> List<MethodReference>,
        uses: (FieldReference) -> FieldUses,
        isOmniEntry: (MethodReference) -> Boolean,
    ): List<Match> {
        val root = index.definition(thumbnail) ?: return emptyList()
        val coordinatorTypes = index.classDefinition(root.definingClass)?.instanceFields?.toList().orEmpty().map { it.type }.distinct()
        val matches = LinkedHashSet<Match>()
        coordinatorTypes.forEach { owner ->
            index.declared(owner).filter { it.accessFlags and 8 == 0 && it.parameterTypes.isEmpty() && it.returnType == "Z" }.forEach gate@ { gate ->
                val code = index.code(gate)
                val entry = code.calls.map { it.method }.distinctBy(Codec::descriptor).singleOrNull() ?: return@gate
                if (entry.parameterTypes.isNotEmpty() || entry.returnType != "Z" || !isOmniEntry(entry)) return@gate
                val fields = code.reads.distinctBy(Codec::descriptor)
                if (fields.size != 2 || fields.any { it.definingClass != owner }) return@gate
                val cached = fields.singleOrNull { it.type == "Z" } ?: return@gate
                val entryOwner = fields.singleOrNull { it.type == entry.definingClass } ?: return@gate
                callers(gate).filter { it.parameterTypes.isEmpty() && it.returnType == "V" }.forEach reader@ { reader ->
                    val readerFields = index.code(reader).reads.distinctBy(Codec::descriptor)
                        .filter { it.definingClass == reader.definingClass }
                    val coordinator = readerFields.singleOrNull { it.type == owner } ?: return@reader
                    readerFields.filter { it.type == "Z" }.forEach hint@ { hint ->
                        val accesses = uses(hint)
                        val constructor = accesses.writers.filter { it.name == "<init>" && it.definingClass == reader.definingClass }
                            .distinctBy(Codec::descriptor).singleOrNull() ?: return@hint
                        if (accesses.readers.none { rendersHint(index, it, reader.definingClass) }) return@hint
                        matches += Match(Codec.descriptor(gate), Codec.descriptor(constructor), Codec.descriptor(hint),
                            Codec.descriptor(cached), Codec.descriptor(coordinator), Codec.descriptor(entryOwner), Codec.descriptor(entry))
                    }
                }
            }
        }
        return matches.toList()
    }

    /** Native fragment tag lookup; no FragmentManager field names or getFragments API assumption. */
    fun fragmentLookup(index: DexOperandIndex, manager: String, fragment: String): String? =
        index.declared(manager).filter { it.accessFlags and 8 != 0 &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") && it.returnType == fragment
        }.filter { method ->
            val call = index.code(method).calls.singleOrNull()?.method
            call?.definingClass == "Landroid/view/View;" && call.name == "getTag" &&
                call.parameterTypes.map(CharSequence::toString) == listOf("I")
        }.singleOrNull()?.let(Codec::descriptor)

    private fun rendersHint(index: DexOperandIndex, reader: MethodReference, model: String): Boolean {
        val allowedOwners = setOf(model, reader.definingClass)
        val pending = java.util.ArrayDeque<MethodReference>().apply { add(reader) }
        val visited = HashSet<String>()
        var nativeHint = false
        var modelAccessor = false
        while (pending.isNotEmpty()) {
            val method = pending.removeFirst()
            if (!visited.add(Codec.descriptor(method))) continue
            index.code(method).calls.forEach { call ->
                val target = call.method
                if (target.definingClass == "Landroid/widget/TextView;" && target.name == "setHint" &&
                    target.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;")) nativeHint = true
                if (target.definingClass == model && target.parameterTypes.isEmpty() &&
                    (target.returnType.startsWith("L") || target.returnType.startsWith("["))) modelAccessor = true
                if (target.definingClass in allowedOwners) pending.add(target)
            }
        }
        return nativeHint && modelAccessor
    }
}
