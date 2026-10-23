package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.TypeReference
import org.jf.dexlib2.iface.reference.StringReference

/** All obfuscated members are recovered from one connected, current-image contract. */
internal class SecurityCenterDockResolver(private val index: CameraDexIndex) {
    private val list = "Ljava/util/List;"
    private val context = "Landroid/content/Context;"
    private val string = "Ljava/lang/String;"
    private val pm = "Landroid/content/pm/PackageManager;"
    private val resolveInfo = "Landroid/content/pm/ResolveInfo;"
    private val packageInfo = "Landroid/content/pm/PackageInfo;"
    private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
    private fun Method.refs() = implementation?.instructions?.toList().orEmpty().filterIsInstance<ReferenceInstruction>().map { it.reference }.toList()
    private fun Method.ops() = implementation?.instructions?.toList().orEmpty().map { it.opcode.name }.toSet()
    private fun Method.calls() = refs().filterIsInstance<MethodReference>()
    private fun Method.number(n: Long) = implementation?.instructions?.any { it is WideLiteralInstruction && it.wideLiteral == n } == true
    private fun MethodReference.key() = CameraDexIndex.descriptor(this)
    private fun FieldReference.key() = "$definingClass->$name:$type"

    fun resolve(): Map<String, String> = buildMap {
        fun method(role: String, ref: MethodReference) { put("m.$role", ref.key()) }
        fun field(role: String, ref: FieldReference) { put("f.$role", ref.key()) }
        val append = index.methods.filter {
            it.params() == listOf(context, list, list, list, list, "I") && it.returnType == "V" &&
                it.accessFlags and (0x20 or 0x20000) != 0 && it.number(8) &&
                it.calls().count { call -> call.returnType == resolveInfo } == 3
        }.single()
        method("append", append)
        val code = index.code(append)
        val sizeFields = code.calls.filter { it.method.name == "size" && it.method.definingClass == list }
            .mapNotNull { (it.arguments.singleOrNull() as? CameraDexIndex.Value.Field)?.field }
        check(sizeFields.size == 2 && sizeFields.distinctBy { it.key() }.size == 2)
        val instructions = append.implementation!!.instructions.toList()
        val quota = instructions.withIndex().single { (_, instruction) ->
            instruction.opcode.name == "add-int/lit8" && (instruction as? WideLiteralInstruction)?.wideLiteral == 8L
        }
        val add = quota.value as TwoRegisterInstruction
        val prior = instructions[quota.index - 1] as OneRegisterInstruction
        val compare = instructions[quota.index + 1] as TwoRegisterInstruction
        check(instructions[quota.index - 1].opcode.name == "move-result" && add.registerB == prior.registerA)
        check(instructions[quota.index + 1].opcode.name in setOf("if-ge", "if-lt") && compare.registerB == add.registerA) { "quota branch=${instructions[quota.index + 1].opcode.name} a=${compare.registerA} b=${compare.registerB} sum=${add.registerA}" }
        field("apps", sizeFields[0]); field("pinned", sizeFields[1])
        fun appendField(role: String, type: String) = field(role, code.reads.filter { it.type == type }.distinctBy { it.key() }.single())
        appendField("packageService", "Ljava/lang/Object;"); appendField("pm", pm)
        // 'packages' is the only List read outside the two quota operands.
        field("packages", code.reads.filter { it.type == list && it.key() !in sizeFields.map { f -> f.key() } }.distinctBy { it.key() }.single())
        val calls = append.calls().distinctBy { it.key() }
        fun helper(role: String, args: List<String>, result: String) = method(role, calls.filter {
            it.params() == args && it.returnType == result
        }.single())
        helper("resolve", listOf(context, string, pm), resolveInfo)
        helper("resolveUser", listOf(context, string, pm, "I"), resolveInfo)
        helper("cloned", listOf("Ljava/lang/Object;", string), "Z")
        helper("alias", listOf(string), "Z")
        helper("canonical", listOf(string), string)
        helper("packageInfo", listOf(string, list), packageInfo)
        helper("user", listOf("I"), "I")
        val constructor = calls.single { it.name == "<init>" && it.params() == listOf(string, string, "I", "I") }
        put("model", constructor.definingClass)
        val owner = append.definingClass
        val members = index.declared(owner)
        val load = members.single { candidate -> candidate.calls().count { it.key() == append.key() } == 2 && candidate.params().isEmpty() }
        method("load", load)
        val reload = members.single { candidate -> candidate.params().isEmpty() && candidate.returnType == "V" &&
            candidate.calls().any { it.key() == load.key() } &&
            candidate.calls().count { it.definingClass == list && it.name == "clear" } == 1 && candidate.calls().size == 2 }
        method("reload", reload)
        method("instance", members.single { it.accessFlags and 8 != 0 && it.params().isEmpty() && it.returnType == owner })
        val data = members.single { it.returnType == list && it.params().isEmpty() && index.code(it).hasString("cache.dock_app_list") && it.accessFlags and (0x20 or 0x20000) != 0 }
        method("data", data)
        val fill = index.methods.filter { candidate -> candidate.params() == listOf(list, "I") && candidate.returnType == list &&
            candidate.ops().containsAll(setOf("instance-of", "new-instance")) &&
            candidate.calls().any { it.definingClass == "Ljava/util/Iterator;" && it.name == "next" } &&
            candidate.calls().count { it.definingClass == list && it.name == "size" } == 2
        }.single()
        val limitRegister = fill.implementation!!.registerCount - 1
        check(fill.implementation!!.instructions.any { instruction ->
            instruction.opcode.name in setOf("if-ge", "if-lt") &&
                (instruction as? TwoRegisterInstruction)?.registerB == limitRegister
        })
        method("fill", fill)
        put("placeholder", fill.calls().single { it.name == "<init>" && it.params().isEmpty() && it.definingClass != "Ljava/util/ArrayList;" }.definingClass)
        method("editCopy", fill.calls().single { it.params() == listOf("Z") && it.returnType != "V" })
        val itemModel = fill.calls().single { it.returnType == constructor.definingClass && it.params().isEmpty() }
        method("itemModel", itemModel)
        field("modelKind", index.code(constructor).writes.single {
            it.first.type == "I" && it.second == CameraDexIndex.Value.Argument(3)
        }.first)
        method("heightData", members.single { candidate -> candidate.returnType == list && candidate.params().isEmpty() &&
            candidate.key() != data.key() && index.code(candidate).reads.any { it.key() == sizeFields[0].key() } })
        val itemParent = index.classDefinition(itemModel.definingClass)!!.superclass
        val markers = data.refs().filterIsInstance<TypeReference>().map { it.type }.distinct().filter {
            index.classDefinition(it)?.superclass == itemParent && it != itemModel.definingClass
        }
        val divider = markers.single { type -> index.declared(type).any { method ->
            method.params().size == 1 && method.returnType == "V" &&
                method.implementation?.instructions?.toList()?.map { it.opcode.name } == listOf("return-void")
        } }
        put("divider", divider)
        val drag = index.declared(fill.definingClass).single { candidate -> candidate.params().size == 4 &&
            candidate.params().first() == list && candidate.params().last() == "I" && candidate.returnType == "V" &&
            "instance-of" in candidate.ops() && candidate.calls().any { it.definingClass == "Ljava/util/Iterator;" && it.name == "next" } }
        method("drag", drag)
        val count = drag.calls().single { it.definingClass == drag.params()[1] && it.params().isEmpty() && it.returnType == "I" }
        method("dragCount", count)
        val addItem = drag.calls().single { it.definingClass == drag.params()[1] && it.params().size == 2 && it.returnType == "V" }
        val replaceItem = index.code(addItem).calls.map { it.method }.distinctBy { it.key() }.single {
            it.params() == listOf(drag.params()[2], "I") && it.returnType == "V"
        }
        method("dragAdd", addItem)
        method("replaceSlot", replaceItem)
        val adapter = replaceItem.definingClass
        field("dragAdapter", index.code(addItem).reads.filter { it.type == adapter }.distinctBy { it.key() }.single())
        val items = index.code(replaceItem).calls.single { it.method.definingClass == list && it.method.name == "set" }
            .arguments.first() as CameraDexIndex.Value.Field
        method("adapterData", index.declared(adapter).single { it.params().isEmpty() && it.returnType == list &&
            it.ops() == setOf("iget-object", "return-object") && index.code(it).reads.any { f -> f.key() == items.field.key() } })
        method("appendSlot", index.declared(adapter).single { candidate -> candidate.params() == listOf(drag.params()[2]) &&
            candidate.returnType == "V" && candidate.calls().any { it.definingClass == list && it.name == "add" } &&
            candidate.calls().any { it.name == "notifyItemInserted" && it.params() == listOf("I") } })
        val layoutOwners = index.methods.filter { candidate -> candidate.calls().any { it.key() == fill.key() } }
            .map { it.definingClass }.distinct()
        val layout = layoutOwners.single()
        val layoutMethods = index.declared(layout)
        val bind = layoutMethods.single { it.params() == listOf(list) && it.returnType == "V" &&
            it.calls().any { ref -> ref.name == "post" } }
        method("bind", bind)
        val bindFields = index.code(bind).calls.filter { it.method.definingClass == list && it.method.name == "clear" }
            .mapNotNull { (it.arguments.firstOrNull() as? CameraDexIndex.Value.Field)?.field }
        field("layoutData", bindFields.single())
        val update = layoutMethods.single { it.params().isEmpty() && it.returnType == "V" &&
            it.calls().any { ref -> ref.key() == fill.key() } }
        method("update", update)
        field("layoutEditing", index.code(update).branches.mapNotNull { (it.value as? CameraDexIndex.Value.Field)?.field }
            .filter { it.type == "Z" }.distinctBy { it.key() }.single())
        field("layoutAdapter", index.code(update).reads.filter { it.type == adapter }.distinctBy { it.key() }.single())
        field("layoutController", index.classDefinition(layout)!!.fields.single { it.type == count.definingClass })
        // The cache publisher and its executor wrapper are linked from load -> notifier -> publish.
        val publisher = layoutMethods.single { candidate -> candidate.accessFlags and 8 != 0 && candidate.params().isEmpty() &&
            candidate.calls().any { it.key() == data.key() } }
        method("publish", publisher)
        val notify = members.single { candidate -> candidate.params().isEmpty() && candidate.returnType == "V" &&
            candidate.calls().any { it.definingClass == layout } }
        method("notify", notify)
        val refresh = notify.calls().single { it.definingClass == layout }
        method("refresh", refresh)
        val callers = index.methods.filter { candidate -> candidate.calls().any { it.key() in setOf(append.key(), fill.key(), drag.key()) } }
        val editor = resolveEditor()
        putAll(editor)
        val editorGetters = editor.filterKeys { it.startsWith("m.editState") }.values.toSet() + editor.getValue("m.editStateBase")
        val editorCallers = index.methods.filter { candidate -> candidate.calls().any { it.key() in editorGetters } }
        put("callers", (callers + editorCallers).distinctBy { it.key() }.joinToString("\n") { it.key() })
        // Structural evidence includes the actual placeholder construction and int-controlled loops.
        check(fill.number(1) && drag.number(1))
        check(fill.refs().filterIsInstance<TypeReference>().isNotEmpty())
    }
    /** The capacity state is consumed by both the grey plus renderer and its click dispatch. */
    private fun resolveEditor(): Map<String, String> = buildMap {
        fun method(role: String, ref: MethodReference) { put("m.$role", ref.key()) }
        fun field(role: String, ref: FieldReference) { put("f.$role", ref.key()) }
        val enumInit = index.strings("PINNABLE", "UNPINNABLE").single { it.name == "<clinit>" }
        val enumType = enumInit.definingClass
        check(index.classDefinition(enumType)!!.superclass == "Ljava/lang/Enum;")
        val labels = linkedMapOf<String, FieldReference>()
        var label: String? = null
        var constructed = false
        enumInit.implementation!!.instructions.forEach { instruction ->
            when (val ref = (instruction as? ReferenceInstruction)?.reference) {
                is StringReference -> { label = ref.string; constructed = false }
                is MethodReference -> if (ref.definingClass == enumType && ref.name == "<init>") constructed = true
                is FieldReference -> if (constructed && instruction.opcode.name == "sput-object" && ref.type == enumType) {
                    label?.let { labels[it] = ref }; constructed = false
                }
            }
        }
        check(labels.keys.containsAll(listOf("NONE", "PINNABLE", "PINNED", "UNPINNABLE")))
        field("pinnable", labels.getValue("PINNABLE")); field("unpinnable", labels.getValue("UNPINNABLE"))
        val getters = index.methods.filter { it.params().isEmpty() && it.returnType == enumType &&
            it.ops() == setOf("iget-object", "return-object") }
        check(getters.size == 2)
        val base = getters.map { index.classDefinition(it.definingClass)!!.superclass }.distinct().single()!!
        val baseGetter = index.declared(base).single { it.params().isEmpty() && it.returnType == enumType }
        method("editStateBase", baseGetter)
        getters.sortedBy { it.key() }.forEachIndexed { i, getter -> method("editState$i", getter) }
        val mappers = index.methods.filter { it.params() == listOf(list) && it.returnType == list && it.number(16) &&
            index.code(it).reads.any { f -> f.key() == labels.getValue("UNPINNABLE").key() } }
        check(mappers.size == 3 && mappers.map { it.definingClass }.distinct().size == 1)
        // All three initial/search paths count pinned models; the update coroutine also writes the same state.
        val count = mappers.map { mapper -> mapper.calls().filter {
            it.definingClass == mapper.definingClass && it.params().isEmpty() && it.returnType == list
        }.single().key() }.distinct().single()
        put("m.editorPinned", count)
        mappers.forEachIndexed { i, mapper -> method("editorMap$i", mapper) }
        val transition = index.methods.filter { it.number(16) &&
            index.code(it).reads.any { f -> f.key() == labels.getValue("UNPINNABLE").key() } &&
            it.params() == listOf("Ljava/lang/Object;") && it.returnType == "Ljava/lang/Object;" }.single()
        method("editorTransition", transition)
        val holderUpdate = index.methods.single { candidate -> candidate.params() == listOf(base) && candidate.returnType == "V" &&
            candidate.calls().any { it.key() == baseGetter.key() } &&
            candidate.calls().any { it.name == "setHapticFeedbackEnabled" && it.params() == listOf("Z") } }
        method("holderUpdate", holderUpdate)
        val holder = holderUpdate.definingClass
        val readScopes = index.declared(holder).filter { candidate -> candidate.calls().any { it.key() == baseGetter.key() } }
        check(readScopes.size >= 4 && readScopes.any { it.params() == listOf("Landroid/view/View;") && it.returnType == "V" })
        readScopes.forEachIndexed { i, scope -> method("editorReadScope$i", scope) }
        put("editorReadScopeCount", readScopes.size.toString())
        field("holderModel", index.code(holderUpdate).writes.single { it.first.type == base }.first)
        method("holderBind", index.declared(holder).single { candidate -> candidate.params() == listOf(base) &&
            candidate.returnType == "V" && candidate.calls().any { it.name == "setOnClickListener" } })
        val parent = index.classDefinition(holder)!!.superclass!!
        val holderBase = index.classDefinition(parent)!!.superclass!!
        val params = index.methods.asSequence().map { it.definingClass }.distinct().mapNotNull(index::classDefinition)
            .filter { it.superclass == "Landroid/view/ViewGroup\$MarginLayoutParams;" && it.fields.any { f -> f.type == holderBase } }.single()
        field("childHolder", params.fields.single { it.type == holderBase })
        put("editorOwners", listOf(enumType, base, holder, parent, holderBase, params.type).joinToString("\n"))
    }

}
