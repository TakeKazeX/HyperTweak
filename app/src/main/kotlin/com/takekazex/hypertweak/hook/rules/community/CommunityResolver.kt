package com.takekazex.hypertweak.hook.rules.community

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.reference.MethodReference

/** Follow feedback submission -> JSON producer -> payload builder; no obfuscated names. */
internal class CommunityResolver(private val dex: DexOperandIndex) {
    private fun params(m: MethodReference) = m.parameterTypes.map(CharSequence::toString)
    private fun key(m: MethodReference) = MethodDescriptorCodec.descriptor(m)
    fun resolve(): Map<String, String> {
        val submit = dex.strings("5577218", "extraB").filter {
            params(it) == listOf("Ljava/lang/String;") && it.returnType == "V"
        }.single()
        val producers = dex.code(submit).calls.map { it.method }.filter {
            params(it) == listOf("Ljava/lang/String;", "J", "I") && it.returnType == "Ljava/lang/String;"
        }.distinctBy(::key)
        val producer = producers.filter { m -> dex.code(m).calls.any {
            it.method.definingClass == "Lcom/alibaba/fastjson/JSON;" && it.method.name == "toJSONString"
        } }.single()
        val build = dex.code(producer).calls.map { it.method }.filter {
            params(it) == listOf("Ljava/lang/String;", "J", "I") && it.returnType == "V" &&
                dex.definition(it)?.accessFlags?.and(8) == 0
        }.distinctBy(::key).single()
        val code = dex.code(build)
        val list = code.reads.filter { it.definingClass == build.definingClass && it.type == "Ljava/util/ArrayList;" }
            .distinctBy(MethodDescriptorCodec::descriptor).single()
        require(code.calls.any { it.method.definingClass == "Ljava/util/ArrayList;" && it.method.name == "add" &&
            it.arguments.firstOrNull() == DexOperandIndex.Value.Field(list) }) { "Disconnected abnormal-app list" }
        val constructors = code.calls.map { it.method }.filter {
            it.name == "<init>" && params(it) == listOf("Ljava/lang/String;")
        }.distinctBy(::key)
        val ctor = constructors.filter { m -> dex.code(m).writes.any { (_, value) ->
            val call = value as? DexOperandIndex.Value.Call
            call != null && dex.code(call.method).hasString("ro.product.model") &&
                params(call.method).isEmpty() && call.method.returnType == "Ljava/lang/String;"
        } }.single()
        val model = dex.code(ctor).writes.filter { (field, value) ->
            field.definingClass == ctor.definingClass && field.type == "Ljava/lang/String;" &&
                (value as? DexOperandIndex.Value.Call)?.let { dex.code(it.method).hasString("ro.product.model") } == true
        }.single().first
        val client = code.writes.map { it.first }.filter { it.type == ctor.definingClass &&
            it.definingClass == build.definingClass }.distinctBy(MethodDescriptorCodec::descriptor).single()
        // The setter must receive the original package argument, then store it to its own field.
        val appFields = code.calls.filter { call ->
            params(call.method) == listOf("Ljava/lang/String;") && call.method.returnType == "V" &&
                call.method.name != "<init>" && call.arguments.lastOrNull() == DexOperandIndex.Value.Argument(0)
        }.flatMap { call -> dex.code(call.method).writes.filter { (field, value) ->
            field.definingClass == call.method.definingClass && field.type == "Ljava/lang/String;" &&
                value == DexOperandIndex.Value.Argument(0)
        }.map { it.first } }.distinctBy(MethodDescriptorCodec::descriptor)
        val app = appFields.single()
        return mapOf("build" to key(build), "producer" to key(producer), "submit" to key(submit),
            "list" to MethodDescriptorCodec.descriptor(list), "client" to MethodDescriptorCodec.descriptor(client),
            "model" to MethodDescriptorCodec.descriptor(model), "app" to MethodDescriptorCodec.descriptor(app))
    }
}
