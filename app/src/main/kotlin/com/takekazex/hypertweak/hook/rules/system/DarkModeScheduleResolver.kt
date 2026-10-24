package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/** Names are output descriptors only. Every role is derived from the current services.jar. */
internal class DarkModeScheduleResolver(private val dex: DexOperandIndex) {
    private fun key(method: MethodReference) = DexOperandIndex.descriptor(method)
    private fun key(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"
    private fun Method.calls() = implementation?.instructions?.filterIsInstance<ReferenceInstruction>().orEmpty()
        .mapNotNull { it.reference as? MethodReference }
    private fun privateMethod(method: Method, result: String) =
        method.accessFlags and 0xa == 2 && method.parameterTypes.isEmpty() && method.returnType == result
    private fun calls(method: MethodReference, owner: String, name: String) = dex.code(method).calls.any {
        it.method.definingClass == owner && it.method.name == name
    }
    private fun reaches(from: MethodReference, target: MethodReference, depth: Int = 2): Boolean =
        dex.code(from).calls.any { key(it.method) == key(target) ||
            (depth > 0 && it.method.definingClass == target.definingClass && reaches(it.method, target, depth - 1)) }
    private fun fields(from: MethodReference, owner: String, depth: Int = 1): List<FieldReference> =
        dex.code(from).reads + if (depth == 0) emptyList() else dex.code(from).calls
            .filter { it.method.definingClass == owner }.flatMap { fields(it.method, owner, depth - 1) }

    fun resolve(): Map<String, String> {
        val predicate = dex.methods.filter { privateMethod(it, "Z") }.filter { method ->
            val refs = method.calls()
            refs.any { it.definingClass == "Landroid/os/PowerManager;" && it.name == "isInteractive" } &&
                refs.any { it.definingClass == "Landroid/service/dreams/DreamManagerInternal;" && it.name == "isDreaming" }
        }.singleOrNull() ?: error("Automatic mode predicate missing or ambiguous")
        val owner = predicate.definingClass
        val declared = dex.declared(owner)
        val types = dex.classDefinition(owner)!!.fields.map { it.type }.toSet()
        check(types.containsAll(setOf("Landroid/content/res/Configuration;", "Landroid/content/BroadcastReceiver;", "Z", "I")))
        check(types.any { type ->
            val members = dex.declared(type)
            members.size == 2 && members.any { it.parameterTypes.isEmpty() && it.returnType == "I" } &&
                members.any { it.parameterTypes.map { p -> p.toString() } == listOf("I") && it.returnType == "V" }
        }) { "Night-mode property holder missing" }
        val predicateReads = dex.code(predicate).reads.filter { it.definingClass == owner }
        check(predicateReads.count { it.type == "Z" } == 1 && predicateReads.count { it.type == "I" } == 1)
        val configuration = declared.filter { privateMethod(it, "V") }.single { method ->
            dex.code(method).writes.any { (field, _) ->
                field.definingClass == "Landroid/content/res/Configuration;" && field.name == "uiMode" && field.type == "I"
            }
        }
        val publish = declared.filter { privateMethod(it, "V") }.single { method ->
            calls(method, "Landroid/app/IActivityTaskManager;", "updateConfiguration") &&
                dex.code(method).reads.any { it.definingClass == "Landroid/content/res/Configuration;" && it.name == "uiMode" }
        }
        val configField = dex.code(configuration).reads.filter { it.definingClass == owner && it.type == "Landroid/content/res/Configuration;" }
            .distinctBy(::key).single()
        check(dex.code(publish).reads.any { key(it) == key(configField) })
        check(dex.code(publish).writes.any { (field, _) -> field.definingClass == owner && field.type == "I" &&
            dex.code(publish).reads.any { key(it) == key(field) } }) { "Published uiMode comparison missing" }

        val register = declared.single { method -> privateMethod(method, "V") &&
            dex.code(method).hasString("android.intent.action.SCREEN_OFF") &&
            dex.code(method).hasString("android.intent.action.DREAMING_STARTED") &&
            calls(method, "Landroid/content/Context;", "registerReceiver") }
        val wait = dex.code(register).writes.filter { (field, value) ->
            field.definingClass == owner && field.type == "Z" && value == DexOperandIndex.Value.Number(1)
        }.map { it.first }.single()
        val unregister = declared.filter { method -> privateMethod(method, "V") &&
            calls(method, "Landroid/content/Context;", "unregisterReceiver") &&
            dex.code(method).writes.any { (field, value) -> key(field) == key(wait) && value == DexOperandIndex.Value.Number(0) }
        }.singleOrNull() ?: error("Inactive receiver cleanup missing or ambiguous")
        check(dex.code(configuration).branches.any { (it.value as? DexOperandIndex.Value.Field)?.field?.let(::key) == key(wait) })
        check(dex.code(configuration).reads.filter { it.definingClass == owner && it.type == "Z" }.distinctBy(::key).size >= 3)
        val receiverField = dex.code(register).reads.single { it.definingClass == owner && it.type == "Landroid/content/BroadcastReceiver;" }
        check(dex.code(unregister).reads.any { key(it) == key(receiverField) })

        // Limit expensive operand decoding to the service's callers, including compiler nest bridges.
        val related = dex.methods.filter { method -> method.definingClass == owner || method.calls().any { it.definingClass == owner } }
        val receiver = related.single { method ->
            dex.classDefinition(method.definingClass)?.superclass == "Landroid/content/BroadcastReceiver;" &&
                method.parameterTypes.map { it.toString() } == listOf("Landroid/content/Context;", "Landroid/content/Intent;") &&
                reaches(method, unregister)
        }
        val update = dex.code(receiver).calls.map { it.method }.single { it.definingClass == owner &&
            it.parameterTypes.map { type -> type.toString() } == listOf("I", "I") && it.returnType == "V" }
        check(reaches(update, configuration) && reaches(update, publish)) { "Native publication path disconnected" }
        val lock = fields(receiver, owner).filter { it.definingClass == owner && it.type == "Ljava/lang/Object;" }.distinctBy(::key).single()
        val decisionCallers = related.filter { reaches(it, predicate) }
        check(decisionCallers.any {
            dex.classDefinition(it.definingClass)?.interfaces?.contains("Lcom/android/server/twilight/TwilightListener;") == true
        }) { "Twilight decision path missing" }
        check(decisionCallers.any { it.definingClass == owner &&
            dex.localCalls(it, 2).any { call -> call.definingClass == "Landroid/app/AlarmManager;" && call.name == "setExact" } && reaches(it, register)
        }) { "Custom-time decision path missing" }
        val deopt = (decisionCallers + related.filter { reaches(it, configuration, 0) } + listOf(predicate, configuration, update))
            .map(::key).distinct()
        return mapOf("predicate" to key(predicate), "configuration" to key(configuration), "wait" to key(wait),
            "unregister" to key(unregister), "update" to key(update), "lock" to key(lock), "callers" to deopt.joinToString("\n"))
    }
}
