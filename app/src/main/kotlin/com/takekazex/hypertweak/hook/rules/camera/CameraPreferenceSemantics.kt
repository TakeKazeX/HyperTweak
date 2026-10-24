package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.FieldReference

internal fun DexOperandIndex.preferenceGates(key: String): List<MethodReference> = strings(key)
    .flatMap { code(it).guards(key) }.filterIsInstance<DexOperandIndex.Value.Call>()
    .map { it.method }.filter { it.returnType == "Z" }
    .distinctBy(DexOperandIndex::descriptor)

internal fun DexOperandIndex.preferenceFields(key: String): List<FieldReference> = strings(key)
    .flatMap { code(it).guards(key) }.filterIsInstance<DexOperandIndex.Value.Field>()
    .map { it.field }.filter { it.type == "Z" }.distinctBy { it.toString() }

/** Read the real title/summary operands passed alongside the preference key. */
internal fun DexOperandIndex.preferenceResources(key: String): List<Int> = strings(key).flatMap { method ->
    code(method).calls.filter { call ->
        (call.method.name.startsWith("add") || call.method.name == "<init>") &&
            call.arguments.any { it == DexOperandIndex.Value.Text(key) }
    }.flatMap { call ->
        val values = call.arguments + call.arguments.filterIsInstance<DexOperandIndex.Value.Call>().flatMap { it.arguments }
        values.filterIsInstance<DexOperandIndex.Value.Number>().map { it.number.toInt() }
            .filter { it ushr 24 == 0x7f }
    }
}.distinct()
