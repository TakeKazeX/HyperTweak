package com.takekazex.hypertweak.hook.rules.securitycenter.sidebar

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference

/** Preference readers -> list membership -> outer capability gates; never use obfuscated names. */
internal class SecurityCenterGameBoosterResolver(private val index: DexOperandIndex) {
    private val string = "Ljava/lang/String;"
    private val context = "Landroid/content/Context;"
    private fun MethodReference.key() = DexOperandIndex.descriptor(this)
    private fun MethodReference.shape(params: List<String>, result: String = "Z") =
        parameterTypes.map(CharSequence::toString) == params && returnType == result
    private fun Method.calls() = index.code(this).calls
    private fun callers(target: MethodReference) = callers(setOf(target.key()))
    private fun callers(targets: Set<String>): List<Method> = index.methods.filter { candidate ->
        candidate.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.key() in targets
        } == true
    }
    private fun membership(key: String): Method {
        val reader = index.strings(key).single { it.shape(emptyList(), "Ljava/util/ArrayList;") }
        return callers(reader).single().also { member ->
            check(member.shape(listOf(string)))
            check(member.calls().any { it.method.definingClass == "Ljava/util/List;" &&
                it.method.name == "contains" && it.method.shape(listOf("Ljava/lang/Object;")) })
        }
    }
    private fun capability(method: MethodReference, anchor: String) =
        method.shape(emptyList()) && index.code(method).hasString(anchor)

    fun gunsight(): Map<String, String> {
        val member = membership("gb_game_gunsight")
        val gate = callers(member).single().also { gate ->
            check(gate.shape(listOf(string)))
            check(gate.calls().any { capability(it.method, "support_game_gunsight") })
            check(gate.calls().any { it.method.shape(listOf(context)) })
        }
        val start = index.strings("startCollimatorService: invalid", "gun_sight").single().also {
            check(it.shape(listOf(string, "I", "Z"), "V"))
            check(it.calls().any { call -> call.method.key() == gate.key() })
            check(it.calls().any { call -> call.method.name == "bindService" && call.method.definingClass == context })
        }
        return result(mapOf("gunsight" to gate), listOf(gate)) + modelBypass(start)
    }

    fun macro(): Map<String, String> {
        val member = membership("pref_gb_unsupport_macro_apps")
        val gate = callers(member).single().also { check(it.shape(listOf(context, string, "Z"))) }
        val owner = index.declared(gate.definingClass)
        val setting = owner.single { candidate -> candidate.shape(listOf("Z")) &&
            candidate.calls().any { capability(it.method, "support_mi_game_macro") } }.also { setting ->
            check(gate.calls().any { it.method.key() == setting.key() })
            check(setting.calls().any { call -> call.method.definingClass == member.definingClass &&
                call.method.shape(emptyList()) && index.code(call.method).hasString("com.xiaomi.macro") })
        }
        fun wrapper(params: List<String>, flag: Long) = owner.single { candidate ->
            candidate.shape(params) && candidate.calls().any { call -> call.method.key() == gate.key() &&
                call.arguments == listOf(DexOperandIndex.Value.Argument(0), DexOperandIndex.Value.Argument(1),
                    DexOperandIndex.Value.Number(flag)) }
        }
        val enabled = wrapper(listOf(context, string), 1)
        val visible = wrapper(listOf(context, string, "Z"), 0)
        val settingWrapper = owner.single { candidate -> candidate.shape(emptyList()) &&
            candidate.calls().any { it.method.key() == setting.key() &&
                it.arguments == listOf(DexOperandIndex.Value.Number(1)) } }
        // This is a NO-USING predicate, not a permission to start. True makes k() SKIP
        // automatic startup. Icon clicks already bypass it. Keep its native result.
        val noUsing = owner.single { it.shape(listOf(context, string)) &&
            index.code(it).hasString("macro_game_using_status") && index.code(it).hasString("com.xiaomi.macro") }
        check(noUsing.calls().any { it.method.definingClass == "Landroid/content/pm/PackageManager;" &&
            it.method.name == "getPackageInfo" })
        val start = owner.single { index.code(it).hasString("startMacroService: no using macro for ") }.also { start ->
            check(start.calls().any { it.method.key() == noUsing.key() })
            check(start.calls().any { it.method.key() == enabled.key() })
            check(start.calls().any { it.method.definingClass == "Landroid/content/Context;" &&
                it.method.name == "startForegroundService" })
        }
        return result(mapOf("macro" to gate, "setting" to setting), listOf(gate, setting, enabled, visible, settingWrapper)) + modelBypass(start) +
            mapOf("e.noUsing" to noUsing.key(), "e.start" to start.key(), "e.enabled" to enabled.key(), "e.visible" to visible.key())
    }

    /** Resolve the model predicate from the start call's actual factory -> receiver flow. */
    private fun modelBypass(start: Method): Map<String, String> {
        val code = index.code(start)
        val factory = code.calls.single { it.method.shape(listOf(string, "I"), it.method.returnType) &&
            it.method.returnType.startsWith("L") && it.arguments ==
                (if (start.parameterTypes.size == 3) listOf(DexOperandIndex.Value.Argument(0), DexOperandIndex.Value.Argument(1))
                 else listOf(DexOperandIndex.Value.Argument(1), DexOperandIndex.Value.Argument(2))) }
        val invalid = code.calls.single { call -> call.method.definingClass == factory.method.returnType &&
            call.method.shape(emptyList()) && call.arguments == listOf(DexOperandIndex.Value.Call(factory.method, factory.arguments)) }.method
        check(index.code(invalid).calls.any { it.method.definingClass == "Ljava/lang/Float;" && it.method.name == "parseFloat" })
        fun list(anchor: String) = index.strings(anchor).single { candidate ->
            candidate.shape(listOf(context, string, "I"), "Ljava/util/List;") &&
                candidate.calls().any { it.method.key() == invalid.key() } }
        val cloud = list("getFunctionDataV2: ")
        val local = list("getLocalFunctionData: ")
        val boundaries = listOf(start, cloud, local)
        return mapOf("model.invalid" to invalid.key(),
            "model.contexts" to boundaries.joinToString("\n") { it.key() },
            "model.callers" to callers(boundaries.map { it.key() }.toSet()).joinToString("\n") { it.key() })
    }

    private fun result(hooks: Map<String, Method>, boundaries: List<Method>): Map<String, String> =
        hooks.mapKeys { "m.${it.key}" }.mapValues { it.value.key() } +
            mapOf("callers" to callers(boundaries.map { it.key() }.toSet()).joinToString("\n") { it.key() })
}
