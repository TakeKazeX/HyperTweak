package com.takekazex.hypertweak.hook.rules.sms

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.MethodReference

/** Derive the OTP getter from the text passed to the native copy-action Intent factory. */
internal class SmsCodeResolver(private val dex: DexOperandIndex) {
    private fun key(method: MethodReference) = DexOperandIndex.descriptor(method)
    private fun params(method: MethodReference) = method.parameterTypes.map(CharSequence::toString)
    private fun call(value: DexOperandIndex.Value?) = value as? DexOperandIndex.Value.Call

    fun resolve(): Map<String, String> {
        val factory = dex.strings("extra_text", "is_verification_code", "function_copy").filter { method ->
            method.accessFlags and 8 != 0 && method.returnType == "Landroid/content/Intent;" &&
                params(method) == listOf("I", "Landroid/content/Context;", "Landroid/net/Uri;",
                    "Ljava/lang/String;", "Ljava/lang/String;") && dex.code(method).calls.any { site ->
                    site.method.definingClass == "Landroid/content/Intent;" && site.method.name == "putExtra" &&
                        site.arguments.getOrNull(1) == DexOperandIndex.Value.Text("extra_text") &&
                        site.arguments.getOrNull(2) == DexOperandIndex.Value.Argument(3)
                }
        }.singleOrNull() ?: error("SMS copy-action factory missing or ambiguous")
        val otpRenderer = dex.strings("verify_code", "pref_key_hide_verification_code").filter { method ->
            val body = dex.code(method)
            body.calls.any { key(it.method) == key(factory) } && body.calls.any {
                it.method.definingClass == "Landroid/app/PendingIntent;" && it.method.name == "getBroadcast"
            }
        }.singleOrNull() ?: error("SMS OTP renderer missing or ambiguous")
        val otp = dex.code(otpRenderer).calls.filter { key(it.method) == key(factory) }
            .mapNotNull { call(it.arguments.getOrNull(3))?.method }.distinctBy(::key).singleOrNull()
            ?.takeIf { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            ?: error("SMS OTP operand missing or ambiguous")
        val recognize = dex.strings("handleVerificationCodeReceived: sdk is null").filter { method ->
            method.accessFlags and 8 != 0 && method.returnType == otp.definingClass &&
                params(method) == listOf("Landroid/content/Context;", "Ljava/lang/String;", "I",
                    "Ljava/lang/String;", "Ljava/lang/String;") && dex.code(method).calls.any {
                    it.method.returnType == otp.definingClass &&
                        params(it.method).firstOrNull() == "Landroid/content/Context;" &&
                        it.method.parameterTypes.size == 2
                }
        }.singleOrNull() ?: error("SMS native recognition missing or ambiguous")
        val wrappers = dex.declared(recognize.definingClass).filter { method ->
            method.accessFlags and 8 != 0 && method.returnType == otp.definingClass &&
                params(method).let { it.size == 5 && it[0] == "Landroid/content/Context;" &&
                    it[1].startsWith("L") && it.drop(2) == listOf("I", "I", "Z") } &&
                dex.code(method).calls.any { key(it.method) == key(recognize) }
        }
        val wrapper = wrappers.singleOrNull() ?: error("SMS recognition wrapper missing or ambiguous")
        val recognitionCall = dex.code(wrapper).calls.single { key(it.method) == key(recognize) }
        val idString = call(recognitionCall.arguments.getOrNull(1))
            ?.takeIf { it.method.definingClass == "Ljava/lang/String;" &&
                it.method.name == "valueOf" && params(it.method) == listOf("J") }
            ?: error("SMS message ID string data flow changed")
        // Operand arguments retain register words: the long occupies two slots.
        val id = call(idString.arguments.firstOrNull())?.takeIf {
            it.method.definingClass == params(wrapper)[1] && it.method.parameterTypes.isEmpty() &&
                it.method.returnType == "J" && it.arguments == listOf(DexOperandIndex.Value.Argument(1))
        }?.method ?: error("SMS message ID getter data flow changed")
        val receiver = dex.strings("android.provider.Telephony.SMS_DELIVER").filter { method ->
            method.returnType == "V" && params(method) == listOf("Landroid/os/Message;") &&
                dex.classDefinition(method.definingClass)?.superclass == "Landroid/os/Handler;" &&
                dex.code(method).let { body ->
                    body.calls.any { key(it.method) == key(wrapper) } &&
                        body.reads.any { it.definingClass == "Landroid/os/Message;" &&
                            it.name == "obj" && it.type == "Ljava/lang/Object;" } &&
                        body.calls.any { it.method.definingClass == "Landroid/content/Intent;" &&
                            it.method.name == "getAction" && params(it.method).isEmpty() }
                }
        }.singleOrNull() ?: error("SMS receive Handler missing or ambiguous")
        return mapOf("receive" to key(receiver), "recognize" to key(wrapper), "id" to key(id), "otp" to key(otp))
    }
}
