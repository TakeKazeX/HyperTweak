package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex.Value
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference

/** Resolve the save graph and operand roles, without APK hashes, versions or obfuscated names. */
internal class ScreenshotFrameResolver(path: String) {
    private val dex = CameraDexIndex.open(path)
    data class Contract(val methods: Map<String, MethodReference>, val fields: Map<String, FieldReference>)
    private val methods = linkedMapOf<String, MethodReference>()
    private val fields = linkedMapOf<String, FieldReference>()
    private fun <T> unique(role: String, values: List<T>): T = values.distinct().singleOrNull()
        ?: error("Screenshot contract $role missing or ambiguous (${values.distinct().size})")
    private fun method(role: String, values: List<MethodReference>): MethodReference =
        unique(role, values.distinctBy(CameraDexIndex::descriptor)).also { methods[role] = ImmutableMethodReference.of(it) }
    private fun field(role: String, values: List<FieldReference>): FieldReference =
        unique(role, values.distinctBy { "${it.definingClass}->${it.name}:${it.type}" }).also { fields[role] = ImmutableFieldReference.of(it) }
    private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
    private fun fields(owner: String, type: String) = dex.classDefinition(owner)?.fields?.toList().orEmpty().filter { it.type == type }
    private fun calls(method: MethodReference) = dex.code(method).calls.map { it.method }
    private fun same(a: MethodReference, b: MethodReference) = CameraDexIndex.descriptor(a) == CameraDexIndex.descriptor(b)
    private fun calls(method: MethodReference, target: MethodReference) = calls(method).any { same(it, target) }

    fun resolve(): Contract {
        val screenshot = "Lcom/miui/screenshot/core/domain/ScreenshotData;"
        val request = method("request", dex.methods.filter {
            it.implementation != null && dex.declared(it.definingClass).any { candidate ->
                candidate.params() == listOf("Lcom/miui/screenshot/interfaces/ISaveCallback;", "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;") &&
                    calls(candidate).any { call -> call.name == "execute" }
            } && it.params().size == 3 && it.params().take(2) == listOf(screenshot, "Ljava/util/function/Consumer;") && it.returnType == "V"
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
