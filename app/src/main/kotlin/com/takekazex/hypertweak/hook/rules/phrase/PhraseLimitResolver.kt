package com.takekazex.hypertweak.hook.rules.phrase

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.reference.MethodReference

internal class PhraseLimitResolver(private val dex: DexOperandIndex) {
    fun resolve(): Map<String, String> {
        val writer = dex.strings("get savedList size : ", "no remain time delete : ").single { method ->
            method.returnType == STRING && params(method) == listOf(CONTEXT, DATABASE, MODEL, STRING) &&
                dex.hasNumber(method, 20L) && dex.hasNumber(method, 259_200_000L)
        }
        val writerCalls = dex.code(writer).calls
        val parse = writerCalls.single {
            it.method.returnType == "Ljava/util/ArrayList;" && params(it.method) == listOf(STRING) &&
                it.arguments == listOf(Value.Argument(3))
        }.method
        val serialize = writerCalls.single {
            it.method.definingClass == MODEL && params(it.method).isEmpty() && it.method.returnType == JSON_OBJECT
        }.method
        val cleanupTask = writerCalls.single {
            it.method.name == "<init>" &&
                params(it.method) == listOf(writer.definingClass, CONTEXT, DATABASE, "Ljava/util/List;")
        }
        val postCleanup = writerCalls.single {
            params(it.method) == listOf("Ljava/lang/Runnable;") &&
                it.method.returnType == "V" && it.arguments.singleOrNull() == cleanupTask.arguments.firstOrNull()
        }
        val provider = dex.declared("Lcom/miui/provider/InputProvider;")
        val save = dex.declared(writer.definingClass).single { method ->
            dex.code(method).calls.any { descriptor(it.method) == descriptor(writer) } &&
                dex.code(method).calls.any {
                    it.method.name == "convertAndSaveClipboardList" &&
                        (it.arguments.getOrNull(1) as? Value.Call)?.method?.let(::descriptor) == descriptor(writer) &&
                        it.arguments.getOrNull(2) == Value.Text("clipboard_cipher_list")
                }
        }
        check(provider.any { method -> dex.code(method).calls.any { descriptor(it.method) == descriptor(save) } })

        val click = dex.strings(ACTION_ADD).single { method ->
            method.name == "onClick" && params(method) == listOf(VIEW) && method.returnType == "V" &&
                dex.hasNumber(method, 20L)
        }
        val clickCalls = dex.code(click).calls
        check(clickCalls.any { it.method.name == "getId" && it.arguments == listOf(Value.Argument(0)) })
        val launch = clickCalls.single {
            it.method.name == "startActivityForResult" && params(it.method) == listOf(INTENT, "I") &&
                it.arguments.firstOrNull() == Value.Argument(-1) && it.arguments.lastOrNull() == Value.Number(0)
        }
        val count = clickCalls.single {
            it.method.returnType == "I" && params(it.method).isEmpty() &&
                (it.arguments.singleOrNull() as? Value.Field)?.field?.definingClass == click.definingClass
        }
        val adapter = (count.arguments.single() as Value.Field).field
        val listCreate = dex.declared(click.definingClass).single { it.name == "onCreate" && params(it) == listOf(BUNDLE) }
        val addButton = dex.code(listCreate).calls.single {
            it.method.name == "setOnClickListener" &&
                params(it.method) == listOf("Landroid/view/View\$OnClickListener;") &&
                it.arguments.lastOrNull() == Value.Argument(-1) &&
                (it.arguments.firstOrNull() as? Value.Call)?.method?.name == "findViewById"
        }
        val buttonId = ((addButton.arguments.first() as Value.Call).arguments.last() as Value.Number).number
        check(dex.hasNumber(click, buttonId))

        val editor = dex.strings("phrase_list", "layout_inflater", ACTION_EDIT).single {
            it.name == "onCreate" && params(it) == listOf(BUNDLE) && it.returnType == "V"
        }
        val editorCode = dex.code(editor)
        val input = editorCode.writes.single { (field, value) ->
            val findView = value as? Value.Call
            field.definingClass == editor.definingClass && field.type == "Landroid/widget/EditText;" &&
                findView?.method?.name == "findViewById" &&
                (findView.arguments.firstOrNull() as? Value.Call)?.method?.name == "inflate"
        }.first
        check(editorCode.calls.any {
            it.method.name == "setText" &&
                (it.arguments.firstOrNull() as? Value.Field)?.field?.let(MethodDescriptorCodec::descriptor) ==
                MethodDescriptorCodec.descriptor(input)
        })
        check(clickCalls.any {
            it.address < launch.address && it.method.definingClass == INTENT && it.method.name == "<init>" &&
                params(it.method) == listOf(CONTEXT, "Ljava/lang/Class;")
        })
        check(clickCalls.any {
            it.address < launch.address && it.method.name == "setAction" &&
                it.arguments.lastOrNull() == Value.Text(ACTION_ADD)
        })

        return mapOf(
            "writer" to descriptor(writer), "save" to descriptor(save), "parse" to descriptor(parse),
            "serialize" to descriptor(serialize),
            "cleanupTask" to descriptor(cleanupTask.method), "postCleanup" to descriptor(postCleanup.method),
            "click" to descriptor(click), "count" to descriptor(count.method),
            "adapter" to MethodDescriptorCodec.descriptor(adapter), "button" to buttonId.toString(),
            "editor" to descriptor(editor), "input" to MethodDescriptorCodec.descriptor(input),
        )
    }

    private fun params(method: MethodReference) = method.parameterTypes.map(CharSequence::toString)
    private fun descriptor(method: MethodReference) = MethodDescriptorCodec.descriptor(method)

    companion object {
        const val ACTION_ADD = "com.miui.intent.action.PHRASE_ADD"
        const val ACTION_EDIT = "com.miui.intent.action.PHRASE_EDIT"
        private const val MODEL = "Lcom/miui/inputmethod/ClipboardContentModel;"
        private const val CONTEXT = "Landroid/content/Context;"
        private const val DATABASE = "Landroid/database/sqlite/SQLiteDatabase;"
        private const val STRING = "Ljava/lang/String;"
        private const val JSON_OBJECT = "Lorg/json/JSONObject;"
        private const val BUNDLE = "Landroid/os/Bundle;"
        private const val VIEW = "Landroid/view/View;"
        private const val INTENT = "Landroid/content/Intent;"
    }
}
