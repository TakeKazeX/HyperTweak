package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference

/** Resolve the save graph and operand roles, without APK hashes, versions or obfuscated names. */
internal class ScreenshotFrameResolver(
    private val dex: DexOperandIndex,
    private val requestDescriptors: List<String>? = null,
) {
    data class Contract(val methods: Map<String, MethodReference>, val fields: Map<String, FieldReference>) {
        fun encode(): Map<String, String> = methods.mapKeys { "method." + it.key }.mapValues { MethodDescriptorCodec.descriptor(it.value) } +
            fields.mapKeys { "field." + it.key }.mapValues { MethodDescriptorCodec.descriptor(it.value) }
    }
    companion object {
        private val methodRoles = setOf("request", "save", "taskConstructor", "worker", "workerBridge", "write", "saveCaller", "thumbnailReady")
        private val fieldRoles = setOf("captureBitmap", "thumbnailDrawable", "thumbnail", "bitmap", "context", "format", "notify", "taskWidth", "taskHeight", "fileTemplate", "width", "height", "path", "uri", "success")
        fun decode(values: Map<String, String>, loader: ClassLoader): Contract? = runCatching {
            require(values.keys == methodRoles.map { "method.$it" }.toSet() + fieldRoles.map { "field.$it" })
            val methods = methodRoles.associateWith { role ->
                val descriptor = values.getValue("method.$role")
                val executable = if (role == "taskConstructor") requireNotNull(MethodDescriptorCodec.constructor(descriptor, loader))
                    else requireNotNull(MethodDescriptorCodec.method(descriptor, loader))
                val returnType = (executable as? java.lang.reflect.Method)?.returnType ?: Void.TYPE
                ImmutableMethodReference(MethodDescriptorCodec.typeDescriptor(executable.declaringClass),
                    if (role == "taskConstructor") "<init>" else executable.name,
                    executable.parameterTypes.map(MethodDescriptorCodec::typeDescriptor), MethodDescriptorCodec.typeDescriptor(returnType))
            }
            val fields = fieldRoles.associateWith { role ->
                val field = requireNotNull(MethodDescriptorCodec.field(values.getValue("field.$role"), loader))
                ImmutableFieldReference(MethodDescriptorCodec.typeDescriptor(field.declaringClass), field.name,
                    MethodDescriptorCodec.typeDescriptor(field.type))
            }
            Contract(methods, fields).also(::validate)
        }.getOrNull()
        private fun validate(contract: Contract) {
            val methods = contract.methods
            fun params(role: String) = methods.getValue(role).parameterTypes.map(CharSequence::toString)
            val controller = methods.getValue("request").definingClass
            val task = methods.getValue("taskConstructor").definingClass
            val taskParams = params("taskConstructor")
            require(taskParams.size == 3 && taskParams.last() == "Z" && methods.getValue("taskConstructor").returnType == "V")
            val data = taskParams.first()
            require(params("request").size == 3 && params("request").take(2) == listOf("Lcom/miui/screenshot/core/domain/ScreenshotData;", "Ljava/util/function/Consumer;") && methods.getValue("request").returnType == "V")
            require(listOf("save", "saveCaller", "thumbnailReady").all { methods.getValue(it).definingClass == controller && methods.getValue(it).returnType == "V" })
            require(params("save") == listOf("Lcom/miui/screenshot/interfaces/ISaveCallback;", "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;"))
            require(params("saveCaller") == listOf("Ljava/lang/Runnable;", "Lcom/miui/screenshot/interfaces/ISaveCallback;", "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;", "Z"))
            require(params("thumbnailReady") == listOf("Ljava/lang/Runnable;"))
            require(listOf("worker", "workerBridge", "write").all { methods.getValue(it).definingClass == task })
            require(params("worker") == listOf("[$data") && methods.getValue("worker").returnType == data)
            require(params("workerBridge") == listOf("[Ljava/lang/Object;") && methods.getValue("workerBridge").returnType == "Ljava/lang/Object;")
            require(params("write") == listOf("Landroid/content/Context;", "[$data", "Landroid/graphics/Bitmap;", "Ljava/io/OutputStream;") && methods.getValue("write").returnType == "V")
            val expected = mapOf("captureBitmap" to "Landroid/graphics/Bitmap;", "thumbnailDrawable" to "Lcom/miui/gallery/editor/transition/RoundedCornerDrawable;", "thumbnail" to "Landroid/widget/ImageView;",
                "bitmap" to "Landroid/graphics/Bitmap;", "context" to "Landroid/content/Context;", "format" to "Landroid/graphics/Bitmap\$CompressFormat;",
                "taskWidth" to "I", "taskHeight" to "I", "fileTemplate" to "Ljava/lang/String;", "width" to "Ljava/lang/Integer;", "height" to "Ljava/lang/Integer;",
                "path" to "Ljava/lang/String;", "uri" to "Landroid/net/Uri;", "success" to "Ljava/lang/Boolean;")
            require(expected.all { (role, type) -> contract.fields.getValue(role).type == type })
            val owners = mapOf("captureBitmap" to controller, "thumbnailDrawable" to controller, "thumbnail" to controller,
                "bitmap" to data, "context" to data, "format" to data, "taskWidth" to task, "taskHeight" to task, "fileTemplate" to task, "notify" to task)
            require(owners.all { (role, owner) -> contract.fields.getValue(role).definingClass == owner })
            val info = contract.fields.getValue("notify").type
            require(listOf("width", "height", "path", "uri", "success").all { contract.fields.getValue(it).definingClass == info })
        }
    }
    private val methods = linkedMapOf<String, MethodReference>()
    private val fields = linkedMapOf<String, FieldReference>()
    private fun <T> unique(role: String, values: List<T>): T = values.distinct().singleOrNull()
        ?: error("Screenshot contract $role missing or ambiguous (${values.distinct().size})")
    private fun method(role: String, values: List<MethodReference>): MethodReference =
        unique(role, values.distinctBy(DexOperandIndex::descriptor)).also { methods[role] = ImmutableMethodReference.of(it) }
    private fun field(role: String, values: List<FieldReference>): FieldReference =
        unique(role, values.distinctBy { "${it.definingClass}->${it.name}:${it.type}" }).also { fields[role] = ImmutableFieldReference.of(it) }
    private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
    private fun fields(owner: String, type: String) = dex.classDefinition(owner)?.fields?.toList().orEmpty().filter { it.type == type }
    private fun calls(method: MethodReference) = dex.code(method).calls.map { it.method }
    private fun same(a: MethodReference, b: MethodReference) = DexOperandIndex.descriptor(a) == DexOperandIndex.descriptor(b)
    private fun calls(method: MethodReference, target: MethodReference) = calls(method).any { same(it, target) }

    fun resolve(): Contract {
        val screenshot = "Lcom/miui/screenshot/core/domain/ScreenshotData;"
        // Production receives all signature candidates from the shared native session. Keep the
        // full operand/call-graph proof below; offline fixtures can enumerate their own methods.
        val requests = requestDescriptors?.distinct()?.map { descriptor ->
            requireNotNull(dex.definition(descriptor)) { "Missing screenshot request candidate: $descriptor" }
        } ?: dex.methods
        val request = method("request", requests.filter {
            // Reject unrelated methods before scanning an owner's save call graph. Most DEX
            // methods cannot accept ScreenshotData, and many share the same owner.
            it.implementation != null && it.params().size == 3 && it.params().take(2) == listOf(screenshot, "Ljava/util/function/Consumer;") && it.returnType == "V" &&
                dex.declared(it.definingClass).any { candidate ->
                candidate.params() == listOf("Lcom/miui/screenshot/interfaces/ISaveCallback;", "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;") &&
                    calls(candidate).any { call -> call.name == "execute" }
            }
        })
        val controller = request.definingClass
        val save = method("save", dex.declared(controller).filter { candidate ->
            candidate.params() == listOf("Lcom/miui/screenshot/interfaces/ISaveCallback;", "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;") &&
                calls(candidate).any { it.name == "execute" } && candidate.returnType == "V"
        })
        val taskConstructor = method("taskConstructor", calls(save).filter {
            it.name == "<init>" && it.params().size == 3 && it.params().last() == "Z" &&
                dex.classDefinition(it.definingClass)?.superclass == "Landroid/os/AsyncTask;"
        })
        val task = taskConstructor.definingClass
        val data = taskConstructor.params().first()
        check(calls(save).any { it.name == "<init>" && it.definingClass == data }) { "Save does not construct worker data" }
        val worker = method("worker", dex.declared(task).filter {
            it.returnType == data && it.params() == listOf("[$data")
        })
        method("workerBridge", dex.declared(task).filter {
            it.name == "doInBackground" && it.params() == listOf("[Ljava/lang/Object;") && calls(it, worker)
        })
        val write = method("write", calls(worker).filter {
            it.definingClass == task && it.params() == listOf("Landroid/content/Context;", "[$data", "Landroid/graphics/Bitmap;", "Ljava/io/OutputStream;") && it.returnType == "V"
        })
        method("saveCaller", dex.declared(controller).filter {
            it.params() == listOf("Ljava/lang/Runnable;", "Lcom/miui/screenshot/interfaces/ISaveCallback;", "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;", "Z") && calls(it, save)
        })
        val drawable = "Lcom/miui/gallery/editor/transition/RoundedCornerDrawable;"
        val thumbnailReady = method("thumbnailReady", dex.declared(controller).filter {
            it.params() == listOf("Ljava/lang/Runnable;") && it.returnType == "V" && calls(it).any { call ->
                call.definingClass == drawable && call.name == "setBitmap" && call.params() == listOf("Landroid/graphics/Bitmap;")
            }
        })
        val preview = unique("preview operands", dex.code(thumbnailReady).calls.filter { it.method.definingClass == drawable && it.method.name == "setBitmap" })
        val previewFields = preview.arguments.mapNotNull { (it as? Value.Field)?.field }
        val capture = field("captureBitmap", previewFields.filter { it.definingClass == controller && it.type == "Landroid/graphics/Bitmap;" })
        field("thumbnailDrawable", previewFields.filter { it.definingClass == controller && it.type == drawable })
        check(dex.code(save).reads.any { it == capture }) { "Preview bitmap is not the saved capture" }
        val image = unique("preview ImageView", dex.code(thumbnailReady).calls.filter { it.method.name == "setImageDrawable" })
        field("thumbnail", image.arguments.mapNotNull { (it as? Value.Field)?.field }.filter { it.definingClass == controller && it.type == "Landroid/widget/ImageView;" })
        field("bitmap", fields(data, "Landroid/graphics/Bitmap;"))
        field("context", fields(data, "Landroid/content/Context;"))
        val format = field("format", fields(data, "Landroid/graphics/Bitmap\$CompressFormat;"))
        check(dex.code(write).reads.any { it == format }) { "Writer does not consume data format" }

        // The exporter shares exactly the MediaStore record type used by the save task.
        val exporter = dex.classDefinition("Lcom/miui/gallery/editor/photo/app/ExportTask;") ?: error("Missing editor exporter")
        val info = unique("editor MediaStore record", exporter.fields.filter { it.name == "mNotifyMediaStoreData" }).type
        field("notify", fields(task, info))
        val constructors = dex.declared(task).filter { it.name == "<init>" }
        fun dimensions(role: String, getter: String) = field(role, constructors.flatMap { dex.code(it).writes }.filter { (target, value) ->
            target.definingClass == task && target.type == "I" && value is Value.Call &&
                value.method.definingClass == "Landroid/graphics/Bitmap;" && value.method.name == getter
        }.map { it.first })
        dimensions("taskWidth", "getWidth")
        dimensions("taskHeight", "getHeight")
        field("fileTemplate", constructors.flatMap { dex.code(it).writes }.filter { (target, value) ->
            target.definingClass == task && target.type == "Ljava/lang/String;" && value == Value.Text("Screenshot_%s_%s.png")
        }.map { it.first })
        val infoConstructor = unique("MediaStore constructor", dex.declared(info).filter { it.name == "<init>" &&
            it.params() == listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Integer;", "Ljava/lang/Integer;", "Ljava/lang/Long;", "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;") })
        fun argumentField(role: String, argument: Int, type: String) = field(role, dex.code(infoConstructor).writes.filter { (target, value) ->
            target.definingClass == info && target.type == type && value == Value.Argument(argument) &&
                dex.classDefinition(info)!!.fields.any { it.name == target.name && it.accessFlags and 16 == 0 }
        }.map { it.first })
        argumentField("width", 2, "Ljava/lang/Integer;")
        argumentField("height", 3, "Ljava/lang/Integer;")
        argumentField("path", 0, "Ljava/lang/String;")
        val uri = field("uri", fields(info, "Landroid/net/Uri;"))
        val success = field("success", fields(info, "Ljava/lang/Boolean;"))
        check(dex.code(write).writes.any { it.first == uri && it.second is Value.Call }) { "Writer does not publish MediaStore URI" }
        check(dex.code(write).writes.any { it.first == success && (it.second as? Value.Field)?.field?.let { ref ->
            ref.definingClass == "Ljava/lang/Boolean;" && ref.name == "TRUE"
        } == true }) { "Writer does not acknowledge successful export" }
        return Contract(methods.toMap(), fields.toMap())
    }
}
