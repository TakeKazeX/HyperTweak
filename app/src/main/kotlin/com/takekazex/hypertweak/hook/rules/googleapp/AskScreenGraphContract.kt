package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.dex.DexControlFlow
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
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

    data class ModelFields(val attachment: String, val suppressHint: String?)

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
        val calls = renderingMethods(index, reader, model).flatMap { index.code(it).calls }
        return calls.any { isSetHint(it.method) } && calls.any {
            it.method.definingClass == model && it.method.parameterTypes.isEmpty() &&
                (it.method.returnType.startsWith("L") || it.method.returnType.startsWith("["))
        }
    }

    fun modelFields(index: DexOperandIndex, graph: Match, hintReaders: List<MethodReference>): ModelFields? {
        val constructor = index.definition(graph.constructor) ?: return null
        val model = constructor.definingClass
        val attachment = hintReaders.flatMap { reader ->
            val code = index.code(reader)
            code.branches.mapNotNull { branch ->
                val decision = branch.value as? Value.Call ?: return@mapNotNull null
                if (!branch.skipsWhenFalse || !DexOperandIndex.isInstanceGetter(decision.method, model, "Z")) {
                    return@mapNotNull null
                }
                if (code.calls.none { it.method.definingClass == "Landroid/widget/ImageView;" &&
                        it.method.name == "setImageBitmap" && branch.controls(code, it.address, onTrue = true)
                    }) return@mapNotNull null
                attachmentField(index, graph, decision.method)
            }
        }.distinctBy(Codec::descriptor).singleOrNull() ?: return null

        val suppression = hintReaders.flatMap { renderingMethods(index, it, model) }
            .distinctBy(Codec::descriptor).flatMap { method ->
                val code = index.code(method)
                val hintCalls = code.calls.filter { isSetHint(it.method) }
                code.branches.mapNotNull { branch ->
                    val field = (branch.value as? Value.Field)?.field ?: return@mapNotNull null
                    if (field.definingClass != model || field.type != "Z" || method.definingClass != model) {
                        return@mapNotNull null
                    }
                    val blank = hintCalls.singleOrNull {
                        it.arguments.getOrNull(1) == Value.Text("") && branch.controls(code, it.address, onTrue = true)
                    } ?: return@mapNotNull null
                    field.takeIf {
                        hintCalls.any { call ->
                            val source = call.arguments.getOrNull(1) as? Value.Call
                            source?.method?.let { it.name == "getString" && it.returnType == "Ljava/lang/String;" } == true &&
                                source.arguments.getOrNull(1) == Value.Argument(0) &&
                                blank.arguments.firstOrNull() == call.arguments.firstOrNull() &&
                                branch.controls(code, call.address, onTrue = false)
                        }
                    }
                }
            }.distinctBy(Codec::descriptor)
        if (suppression.size > 1) return null

        val attachmentDescriptor = Codec.descriptor(attachment)
        val suppressionDescriptor = suppression.singleOrNull()?.let(Codec::descriptor)
        val fields = listOfNotNull(graph.hint, attachmentDescriptor, suppressionDescriptor)
        val arguments = fields.map { field ->
            val write = index.code(constructor).writes.singleOrNull { Codec.descriptor(it.first) == field }
            (write?.second as? Value.Argument)?.index ?: return null
        }.toSet()
        if (AskScreenModelContract.fields(index, graph.constructor, arguments)?.toSet() != fields.toSet()) return null
        return ModelFields(attachmentDescriptor, suppressionDescriptor)
    }

    private fun attachmentField(index: DexOperandIndex, graph: Match, decision: MethodReference): FieldReference? {
        val code = index.code(decision)
        val eligibility = code.branches.singleOrNull { branch ->
            val call = branch.value as? Value.Call
            branch.skipsWhenFalse && call != null && Codec.descriptor(call.method) == graph.eligibility &&
                (call.arguments.singleOrNull() as? Value.Field)?.field?.let(Codec::descriptor) == graph.modelCoordinator
        } ?: return null
        return code.branches.mapNotNull { branch ->
            val field = (branch.value as? Value.Field)?.field ?: return@mapNotNull null
            field.takeIf { branch.skipsWhenFalse && branch.end == eligibility.end &&
                branch.controls(code, eligibility.start, onTrue = true) &&
                it.definingClass == decision.definingClass && it.type == "Z" }
        }.singleOrNull()
    }

    private fun DexOperandIndex.Branch.controls(code: DexOperandIndex.Code, target: Int, onTrue: Boolean): Boolean {
        val edge = code.edges[start]?.getOrNull(if (skipsWhenFalse == onTrue) 0 else 1) ?: return false
        return DexControlFlow.edgeDominates(code.edges, code.roots, start, edge, target)
    }

    private fun isSetHint(method: MethodReference): Boolean =
        method.definingClass == "Landroid/widget/TextView;" && method.name == "setHint" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;")

    private fun renderingMethods(index: DexOperandIndex, reader: MethodReference, model: String): List<MethodReference> {
        val allowedOwners = setOf(model, reader.definingClass)
        val pending = java.util.ArrayDeque<MethodReference>().apply { add(reader) }
        val visited = HashSet<String>()
        val methods = ArrayList<MethodReference>()
        while (pending.isNotEmpty()) {
            val method = pending.removeFirst()
            if (!visited.add(Codec.descriptor(method))) continue
            methods.add(method)
            index.code(method).calls.map { it.method }.filter { it.definingClass in allowedOwners }.forEach(pending::add)
        }
        return methods
    }
}
