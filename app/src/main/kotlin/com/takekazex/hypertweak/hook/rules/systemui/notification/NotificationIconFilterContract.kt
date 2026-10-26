package com.takekazex.hypertweak.hook.rules.systemui.notification

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.reference.FieldReference

internal object NotificationIconFilterContract {
    data class Target(val owner: String, val method: String, val ambientFlag: String, val silentFlag: String, val callers: List<Pair<String, String>> = emptyList())
    fun encode(target: Target): Map<String, String> = mapOf(
        "owner" to target.owner, "method" to target.method, "ambient" to target.ambientFlag,
        "silent" to target.silentFlag, "callers" to target.callers.joinToString("\n") { it.first + "#" + it.second },
    )
    fun decode(values: Map<String, String>, loader: ClassLoader): Target? = runCatching {
        require(values.keys == setOf("owner", "method", "ambient", "silent", "callers"))
        val owner = Class.forName(values.getValue("owner"), false, loader)
        fun predicate(type: Class<*>, name: String) {
            val method = type.getDeclaredMethod(name, Any::class.java)
            require(method.returnType == Any::class.java && !java.lang.reflect.Modifier.isStatic(method.modifiers))
        }
        predicate(owner, values.getValue("method"))
        val ambient = owner.getDeclaredField(values.getValue("ambient"))
        val silent = owner.getDeclaredField(values.getValue("silent"))
        require(ambient.type == Boolean::class.javaPrimitiveType && silent.type == Boolean::class.javaPrimitiveType && ambient != silent)
        require(!java.lang.reflect.Modifier.isStatic(ambient.modifiers) && !java.lang.reflect.Modifier.isStatic(silent.modifiers))
        val callers = values.getValue("callers").split("\n").filter(String::isNotEmpty).map { value ->
            val parts = value.split('#'); require(parts.size == 2)
            predicate(Class.forName(parts[0], false, loader), parts[1]); parts[0] to parts[1]
        }
        Target(owner.name, values.getValue("method"), ambient.name, silent.name, callers)
    }.getOrNull()
    private const val MODEL = "Lcom/android/systemui/statusbar/notification/shared/ActiveNotificationModel;"

    fun resolve(dex: DexOperandIndex): Target? = dex.methods.asSequence().filter {
        it.returnType == "Ljava/lang/Object;" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
            it.definingClass.startsWith("Lcom/android/systemui/statusbar/notification/icon/")
    }.mapNotNull { method ->
        val code = dex.code(method)
        if (code.calls.none { it.method.name == "isBubbleExpanded" } || code.reads.none { it.definingClass == MODEL && it.name == "isSilent" }) return@mapNotNull null
        val ambient = guardingFlag(method, "isAmbient") ?: return@mapNotNull null
        val silent = guardingFlag(method, "isSilent") ?: return@mapNotNull null
        if (ambient == silent) return@mapNotNull null
        val descriptor = DexOperandIndex.descriptor(method)
        val callers = dex.methods.filter { candidate ->
            candidate.definingClass.startsWith("Lcom/android/systemui/statusbar/notification/icon/") &&
                candidate.returnType == "Ljava/lang/Object;" && candidate.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
                dex.code(candidate).calls.any { DexOperandIndex.descriptor(it.method) == descriptor }
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
