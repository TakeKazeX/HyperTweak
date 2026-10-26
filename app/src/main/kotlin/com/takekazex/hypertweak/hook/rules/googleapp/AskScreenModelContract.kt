package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec

/** Post-construction overrides are valid only for uniquely assigned, otherwise unused arguments. */
internal object AskScreenModelContract {
    fun fields(index: DexOperandIndex, constructor: String, indices: Set<Int>): List<String>? {
        if (indices.isEmpty()) return null
        val code = index.code(constructor)
        fun affected(value: DexOperandIndex.Value?) = value is DexOperandIndex.Value.Argument && value.index in indices
        if (code.calls.any { it.arguments.any(::affected) } || code.branches.any { affected(it.value) }) return null
        val owner = constructor.substringBefore("->")
        val fields = indices.sorted().map { argument ->
            val field = code.writes.filter { (_, value) -> value == DexOperandIndex.Value.Argument(argument) }
                .map { it.first }.singleOrNull() ?: return null
            if (field.type != "Z" || field.definingClass != owner) return null
            MethodDescriptorCodec.descriptor(field)
        }
        return fields.takeIf { it.distinct().size == it.size }
    }
}
