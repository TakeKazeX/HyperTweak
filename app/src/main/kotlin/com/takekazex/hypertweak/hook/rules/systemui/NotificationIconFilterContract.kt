package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.reference.FieldReference

internal object NotificationIconFilterContract {
    data class Target(val owner: String, val method: String, val ambientFlag: String, val silentFlag: String, val callers: List<Pair<String, String>> = emptyList())
    private const val MODEL = "Lcom/android/systemui/statusbar/notification/shared/ActiveNotificationModel;"

    fun resolve(dex: CameraDexIndex): Target? = dex.methods.asSequence().filter {
        it.returnType == "Ljava/lang/Object;" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
            it.definingClass.startsWith("Lcom/android/systemui/statusbar/notification/icon/")
    }.mapNotNull { method ->
        val code = dex.code(method)
        if (code.calls.none { it.method.name == "isBubbleExpanded" } || code.reads.none { it.definingClass == MODEL && it.name == "isSilent" }) return@mapNotNull null
        val ambient = guardingFlag(method, "isAmbient") ?: return@mapNotNull null
        val silent = guardingFlag(method, "isSilent") ?: return@mapNotNull null
        if (ambient == silent) return@mapNotNull null
        val descriptor = CameraDexIndex.descriptor(method)
        val callers = dex.methods.filter { candidate ->
            candidate.definingClass.startsWith("Lcom/android/systemui/statusbar/notification/icon/") &&
                candidate.returnType == "Ljava/lang/Object;" && candidate.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
                dex.code(candidate).calls.any { CameraDexIndex.descriptor(it.method) == descriptor }
        }.map { it.definingClass.removePrefix("L").removeSuffix(";").replace('/', '.') to it.name }
        Target(method.definingClass.removePrefix("L").removeSuffix(";").replace('/', '.'), method.name, ambient, silent, callers)
    }.toList().singleOrNull()

    /** Decode the captured boolean whose if-nez skips this specific model-field rejection. */
    private fun guardingFlag(method: Method, property: String): String? {
        val instructions = method.implementation?.instructions?.toList() ?: return null
        val matches = instructions.indices.mapNotNull { index ->
            val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
            if (field?.definingClass != MODEL || field.name != property || index == 0) return@mapNotNull null
            val branch = instructions[index - 1]
            if (branch.opcode != org.jf.dexlib2.Opcode.IF_NEZ) return@mapNotNull null
            val register = (branch as? OneRegisterInstruction)?.registerA ?: return@mapNotNull null
            val producer = instructions.take(index - 1).lastOrNull { (it as? OneRegisterInstruction)?.registerA == register ||
                (it as? TwoRegisterInstruction)?.registerA == register } ?: return@mapNotNull null
            val captured = (producer as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
            captured.takeIf { it.definingClass == method.definingClass && it.type == "Z" }?.name
        }
        return matches.singleOrNull()
    }
}
