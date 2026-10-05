package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import com.takekazex.hypertweak.util.DebugLog
import org.jf.dexlib2.iface.reference.MethodReference
import java.lang.reflect.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction

/** Reuses the operand/call-graph index: descriptors come from the host, never an obfuscation table. */
internal class SecurityCenterPrivacyResolver(path: String, private val loader: ClassLoader) {
    private val dex = CameraDexIndex.open(path)
    private val bool = "Z"
    private val resolutionEvidence = linkedMapOf<String, List<String>>()
    fun evidence(): Map<String, List<String>> = resolutionEvidence.toMap()
    private val callersByTarget by lazy {
        val index = mutableMapOf<String, MutableList<MethodReference>>()
        for (method in dex.methods) {
            val references = (method.implementation?.instructions ?: emptyList<org.jf.dexlib2.iface.instruction.Instruction>()).mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)
            }.distinctBy(CameraDexIndex::descriptor)
            references.forEach { target -> index.getOrPut(CameraDexIndex.descriptor(target)) { mutableListOf() }.add(method) }
        }
        index
    }
    private fun zeroBoolean(it: MethodReference) = it.returnType == bool && it.parameterTypes.isEmpty()
    private fun static(reference: MethodReference) = dex.definition(reference)?.accessFlags?.let { it and 8 != 0 } == true
    private fun unique(role: String, candidates: Collection<MethodReference>, optional: Boolean = false): Method? {
        val distinct = candidates.distinctBy(CameraDexIndex::descriptor)
        resolutionEvidence[role] = distinct.map(CameraDexIndex::descriptor)
        val reflected = distinct.singleOrNull()?.let { dex.method(it, loader) }
        if (reflected == null) {
            val message = "$role semantic candidates=${distinct.size}; skipping this capability"
            if (optional && distinct.isEmpty()) DebugLog.d("SecurityCenterPrivacyEntries", message)
            else DebugLog.w("SecurityCenterPrivacyEntries", message)
        }
        return reflected
    }
    fun antiPeepingV1(): Method? {
        val roots = dex.strings("supportAntiPeeping: device supports pixel privacy, use V2 instead").filter(::zeroBoolean)
        val gates = roots.flatMap { root ->
            if (static(root)) listOf(root) else dex.declared(root.definingClass).filter { caller ->
                static(caller) && zeroBoolean(caller) && dex.code(caller).calls.any {
                    CameraDexIndex.descriptor(it.method) == CameraDexIndex.descriptor(root)
                }
            }
        }
        return unique("anti-peeping V1", gates, optional = true)
    }
    fun antiPeepingV2(): Method? = unique("anti-peeping V2", dex.strings(
        "supportAntiPeepingV2: not support pixel privacy").filter { zeroBoolean(it) && static(it) }, optional = true)

    fun frontAction(): Method? = unique("front assistant action", dex.strings(
        "#Intent;action=com.miui.gamebooster.action.ACCESS_FRONT_ASSISTANT;end").filter {
        static(it) && it.returnType == bool && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
    })
    fun commonFunctions(): Method? = unique("common function list", dex.strings(
        "filter commonly after userset: ", "filter commonly after server: ").filter {
        static(it) && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Ljava/util/List;") &&
            it.returnType in setOf("Ljava/util/List;", "Ljava/util/ArrayList;")
    })
    fun legacyCommonCard(): Method? = unique("common function card", dex.strings(
        "filter commonly after userset: ").filter {
        static(it) && it.returnType == "Lcom/miui/common/card/models/CommonlyUsedFunctionCardModel;" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
            dex.code(it).calls.any { call -> call.method.name == "setCommonlyUsedFuncDataList" }
    })
    private fun beautyInitializers() = dex.strings("Device not support beauty!!!", "preference_key_beauty_switch").filter {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) ==
            listOf("Landroid/os/Bundle;", "Ljava/lang/String;")
    }
    fun beautyEntry(): Method? = unique("beauty settings initializer", beautyInitializers())
    fun beautySupport(): Method? {
        val entry = beautyInitializers().singleOrNull() ?: return null
        return unique("beauty UI support", dex.code(entry).calls.map { it.method }.filter {
            zeroBoolean(it) && static(it) && dex.code(it).hasString("persist.vendor.vcb.ability")
        })
    }
    /** Deoptimize the actual callers, including renamed receivers/providers in secondary processes. */
    fun callers(target: Method): List<Method> {
        val root = reference(target) ?: return emptyList()
        val visited = linkedSetOf(CameraDexIndex.descriptor(root))
        var frontier = visited.toSet()
        repeat(4) {
            val next = frontier.flatMap { callersByTarget[it].orEmpty() }
                .filter { visited.add(CameraDexIndex.descriptor(it)) }
            frontier = next.map(CameraDexIndex::descriptor).toSet()
            if (frontier.isEmpty()) return@repeat
        }
        return dex.methods.filter { CameraDexIndex.descriptor(it) in visited }
            .mapNotNull { dex.method(it, loader) }.distinct()
    }
    private fun reference(method: Method) = dex.declared(CameraDexIndex.descriptor(method.declaringClass)).singleOrNull {
        it.name == method.name && it.returnType == CameraDexIndex.descriptor(method.returnType) &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CameraDexIndex::descriptor)
    }
}
