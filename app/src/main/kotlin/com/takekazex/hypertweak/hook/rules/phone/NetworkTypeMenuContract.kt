package com.takekazex.hypertweak.hook.rules.phone

import com.takekazex.hypertweak.hook.base.dex.DexControlFlow
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

internal class NetworkTypeMenuContract private constructor(
    val bindPage: MethodReference,
    val search: MethodReference,
    val gate: MethodReference,
    val enterpriseActive: FieldReference,
) {
    companion object {
        private const val KEY = "button_preferred_network_type_key"
        private const val PHONE = "Lcom/android/internal/telephony/Phone;"
        private const val OPTIONS = "Lcom/android/phone/settings/CommonOptions;"
        private const val TELEPHONY = "Lcom/android/phone/module/ITelephonyModule;"
        private const val DELEGATE = "Lcom/android/phone/TelephonyDelegateImpl;"
        private const val MANAGER = "Lcom/android/phone/NetworkModeManager;"
        private const val ENTERPRISE = "Lmiui/enterprise/EnterpriseManagerStub;"

        fun resolve(index: DexOperandIndex): NetworkTypeMenuContract? {
            val page = index.declared(OPTIONS).singleOrNull { method ->
                method.parameterTypes.isEmpty() && method.returnType == "V" && index.code(method).hasString(KEY)
            } ?: return null
            val body = index.code(page)
            val find = body.calls.singleOrNull {
                it.method.name == "findPreference" && it.arguments.lastOrNull() == Value.Text(KEY)
            } ?: return null
            val preference = body.writes.singleOrNull { (_, value) ->
                value == Value.Call(find.method, find.arguments)
            }?.first ?: return null
            val removal = body.calls.singleOrNull {
                it.method.name == "removePreference" && it.arguments.lastOrNull() == Value.Field(preference)
            } ?: return null
            val guard = body.branches.singleOrNull {
                val call = it.value as? Value.Call ?: return@singleOrNull false
                val fallthrough = body.edges[it.start]?.firstOrNull() ?: return@singleOrNull false
                it.skipsWhenFalse && call.method.definingClass == TELEPHONY &&
                    call.method.parameterTypes == listOf(PHONE) && call.method.returnType == "Z" &&
                    (call.arguments.lastOrNull() as? Value.Field)?.field?.type == PHONE &&
                    DexControlFlow.edgeDominates(body.edges, body.roots, it.start, fallthrough, removal.address)
            }?.value as? Value.Call ?: return null

            val delegate = index.definition(DELEGATE + "->" + guard.method.name + "(" + PHONE + ")Z")
                ?: return null
            val policy = index.code(delegate).calls.singleOrNull {
                it.method.definingClass == MANAGER && it.method.parameterTypes == listOf(PHONE) &&
                    it.method.returnType == "Z" && it.arguments == listOf(Value.Argument(0))
            }?.method ?: return null
            val policyBody = index.code(policy)
            val enterprise = policyBody.reads.singleOrNull {
                it.definingClass == ENTERPRISE && it.name == "ENTERPRISE_ACTIVATED" && it.type == "Z"
            } ?: return null
            if (policyBody.calls.none {
                DexOperandIndex.descriptor(it.method) == "Lmiui/enterprise/IPhoneHelper;->isNetworkModesRestriction()Z"
            }) return null

            val search = index.declared("Lcom/android/phone/settings/search/provider/PhoneStatusSearchProvider;")
                .singleOrNull { method ->
                    method.parameterTypes.isEmpty() && method.returnType == "Ljava/util/List;" &&
                        index.code(method).let { code ->
                            code.calls.any { it.method == guard.method } &&
                                code.reads.any { it.name == "preferred_network_type_title" && it.type == "I" }
                        }
                } ?: return null
            return NetworkTypeMenuContract(page, search, delegate, enterprise)
        }
    }
}
