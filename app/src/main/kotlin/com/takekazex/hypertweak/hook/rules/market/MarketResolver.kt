package com.takekazex.hypertweak.hook.rules.market

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference

/** Validate named APIs and their full signatures against this APK, then collect direct callers.
 * No version table, historical fallback, or constructor-field edits. Each policy fails independently.
 */
internal class MarketResolver(private val dex: DexOperandIndex) {
    fun resolve(): Map<String, String> {
        val targets = MarketTargets.all.map { it.descriptor }.toSet()
        val found = targets.filter { descriptor ->
            val method = dex.definition(descriptor)
            method?.implementation != null && (dex.code(method).calls.any {
                it.method.definingClass == "Lcom/xiaomi/gamecenter/sdk/robust/PatchProxy;" && it.method.name == "proxy"
            } || (method.name == "getShowOpenScreenAd" && dex.code(method).reads.any {
                it.definingClass == method.definingClass && it.name == "showOpenScreenAd" && it.type == "Z"
            }) || dex.code(method).calls.any { it.method.name == method.name &&
                it.method.definingClass == method.definingClass.dropLast(1) + "\$Companion;" }) && (!descriptor.endsWith("getRequestParams()Ljava/util/Map;") || dex.code(method).hasString("adFlag"))
        }.toSet()
        val callers = found.associateWith { linkedSetOf<String>() }
        // Virtual dispatch names the base declaration, not necessarily the overriding method.
        val dispatch = buildMap<String, MutableSet<String>> {
            found.forEach { descriptor ->
                val method = requireNotNull(dex.definition(descriptor))
                var owner: String? = method.definingClass
                val seen = mutableSetOf<String>()
                while (owner != null && seen.add(owner)) {
                    getOrPut(owner + descriptor.substringAfter(method.definingClass)) { linkedSetOf() }.add(descriptor)
                    owner = if (method.accessFlags and (8 or 2) == 0) dex.classDefinition(owner)?.superclass else null
                }
            }
        }
        // Read references only, avoiding a full register-flow decode of every method in the APK.
        dex.methods.forEach { method ->
            method.implementation?.instructions?.forEach { insn ->
                val reference = (insn as? ReferenceInstruction)?.reference as? MethodReference
                if (reference != null) dispatch[MethodDescriptorCodec.descriptor(reference)]?.forEach { target ->
                    callers.getValue(target).add(MethodDescriptorCodec.descriptor(method))
                }
            }
        }
        return buildMap {
            found.sorted().forEach { descriptor ->
                put("target:$descriptor", descriptor)
                callers.getValue(descriptor).sorted().forEachIndexed { index, caller -> put("caller:$descriptor:$index", caller) }
            }
        }
    }
}
