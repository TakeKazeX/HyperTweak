package com.takekazex.hypertweak.hook.rules.phrase

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.reference.MethodReference

internal class ClipboardProviderResolver(private val dex: DexOperandIndex) {
    fun resolve(): Map<String, String> {
        val owner = dex.strings("getClipboardList", "com.miui.phrase.input.provider").single {
            it.name == "<clinit>" && dex.code(it).calls.any { call ->
                call.method.definingClass == "Landroid/content/UriMatcher;" && call.method.name == "addURI"
            }
        }.definingClass
        val methods = dex.declared(owner)
        val query = methods.single {
            it.name == "query" && it.returnType == "Landroid/database/Cursor;" &&
                it.parameterTypes.map(CharSequence::toString) ==
                listOf(URI, "[Ljava/lang/String;", STRING, "[Ljava/lang/String;", STRING) &&
                dex.code(it).hasString("clipboard_cipher_list")
        }
        val insert = methods.single {
            it.name == "insert" && it.returnType == URI &&
                it.parameterTypes.map(CharSequence::toString) == listOf(URI, "Landroid/content/ContentValues;") &&
                dex.code(it).hasString("json_chunk") && dex.code(it).hasString("SHA1")
        }
        val insertCalls = dex.code(insert).calls
        val gate = dex.code(query).calls.single { site ->
            site.method.definingClass == owner && site.method.parameterTypes.isEmpty() &&
                site.method.returnType == "Z" && insertCalls.any { id(it.method) == id(site.method) } &&
                dex.code(site.method).calls.any { it.method.definingClass == "Landroid/os/Binder;" && it.method.name == "getCallingUid" }
        }.method
        val signatureList = insertCalls.single { site ->
            val field = (site.arguments.firstOrNull() as? Value.Field)?.field
            val digest = (site.arguments.lastOrNull() as? Value.Call)?.method
            site.method.name == "contains" && field?.definingClass == owner &&
                digest?.definingClass == "Lmiui/util/HashUtils;" && digest.name == "toHexString"
        }.arguments.first() as Value.Field
        val assembly = insertCalls.single { site ->
            site.method.definingClass == "Ljava/util/HashMap;" && site.method.name == "put" &&
                (site.arguments.getOrNull(1) as? Value.Call)?.method?.name == "getAsInteger" &&
                (site.arguments.getOrNull(2) as? Value.Call)?.method?.definingClass == "Landroid/util/Base64;"
        }.arguments.first() as Value.Field
        val receivedBytes = dex.code(insert).writes.map { it.first }.distinctBy(MethodDescriptorCodec::descriptor)
            .single { it.definingClass == owner && it.type == "I" }
        val parser = dex.code(query).calls.single {
            it.method.returnType == "Ljava/util/ArrayList;" &&
                it.method.parameterTypes.map(CharSequence::toString) == listOf(STRING) &&
                dex.code(it.method).calls.any { call ->
                    call.method.definingClass == MODEL && call.method.name == "fromJSONObject"
                }
        }.method
        val getters = dex.declared(MODEL)
        fun getter(name: String, type: String) = getters.single { method ->
            method.name == name && method.returnType == type && method.parameterTypes.isEmpty() &&
                dex.code(method).reads.singleOrNull()?.type == type
        }
        val time = getter("getTime", "J")
        val content = getter("getContent", STRING)
        val type = getter("getType", "I")
        val across = getter("isAcrossDevices", "Z")
        return mapOf(
            "query" to id(query), "insert" to id(insert), "gate" to id(gate),
            "signatureList" to MethodDescriptorCodec.descriptor(signatureList.field),
            "queryParser" to id(parser), "time" to id(time),
            "content" to id(content), "type" to id(type), "across" to id(across),
            "assembly" to MethodDescriptorCodec.descriptor(assembly.field),
            "receivedBytes" to MethodDescriptorCodec.descriptor(receivedBytes),
        )
    }

    private fun id(method: MethodReference) = MethodDescriptorCodec.descriptor(method)

    private companion object {
        const val URI = "Landroid/net/Uri;"
        const val STRING = "Ljava/lang/String;"
        const val MODEL = "Lcom/miui/inputmethod/ClipboardContentModel;"
    }
}
