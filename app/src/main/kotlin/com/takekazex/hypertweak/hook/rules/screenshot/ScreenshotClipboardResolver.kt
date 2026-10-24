package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference

/** Descriptors only: no live DEX objects survive resolution or enter the disk cache. */
internal data class ScreenshotClipboardContract(val gate: String, val copy: String, val callers: List<String>, val commitWriter: String, val saveWriter: String, val notify: String, val success: String) {
    fun encode(): Map<String, String> = mapOf("gate" to gate, "copy" to copy, "commitWriter" to commitWriter,
        "callers" to callers.joinToString("\n"), "saveWriter" to saveWriter, "notify" to notify, "success" to success)
}

/** Resolve the actual clipboard gate and prove a path from the AsyncTask writer to it. */
internal class ScreenshotClipboardResolver(private val dex: DexOperandIndex) {
    companion object { const val SETTING = "mi_screen_shots_write_clipboard_enable" }
    private fun key(method: MethodReference) = DexOperandIndex.descriptor(method)
    private fun params(method: MethodReference) = method.parameterTypes.map(CharSequence::toString)

    fun resolve(): ScreenshotClipboardContract {
        val gate = dex.strings(SETTING).filter { method ->
            method.accessFlags and 0x8 != 0 && method.returnType == "Z" &&
                params(method) == listOf("Landroid/content/Context;") && dex.code(method).calls.any {
                    it.method.definingClass == "Landroid/provider/Settings\$Secure;" && it.method.name == "getInt"
                }
        }.singleOrNull() ?: error("Screenshot clipboard gate missing or ambiguous")
        val copy = dex.strings("Not support super clipboard.").filter { method ->
            val body = dex.code(method)
            body.calls.any { key(it.method) == key(gate) } && body.calls.any {
                it.method.definingClass == "Landroid/content/ClipboardManager;" && it.method.name == "setPrimaryClip"
            }
        }.singleOrNull() ?: error("Screenshot clipboard publisher missing or ambiguous")
        val publication = dex.code(copy).calls.single { it.method.definingClass == "Landroid/content/ClipboardManager;" &&
            it.method.name == "setPrimaryClip" }
        check(dex.code(copy).branches.any { branch ->
            val producer = branch.value as? DexOperandIndex.Value.Call
            producer != null && key(producer.method) == key(gate) && branch.skipsWhenFalse && branch.end <= publication.address
        }) { "Screenshot clipboard gate polarity changed" }
        // Read references without decoding every method's operand graph. Only the small
        // connected save family needs decoding; unrelated MediaStore writers cannot qualify.
        val incoming = HashMap<String, MutableList<Method>>()
        dex.methods.forEach { method ->
            method.implementation?.instructions?.filterIsInstance<ReferenceInstruction>()
                ?.mapNotNull { it.reference as? MethodReference }?.distinctBy(::key)?.forEach {
                    incoming.getOrPut(key(it)) { ArrayList() }.add(method)
                }
        }
        val paths = ArrayList<List<MethodReference>>()
        fun walk(current: MethodReference, path: List<MethodReference>) {
            if (path.size > 8) return
            if (dex.classDefinition(current.definingClass)?.superclass == "Landroid/os/AsyncTask;" &&
                params(current).let { it.size == 4 && it[0] == "Landroid/content/Context;" &&
                    it[1].startsWith("[L") && it[2] == "Landroid/graphics/Bitmap;" && it[3] == "Ljava/io/OutputStream;" }) {
                paths += path
                return
            }
            incoming[key(current)].orEmpty().filter { next -> path.none { key(it) == key(next) } }
                .forEach { walk(it, path + it) }
        }
        walk(copy, listOf(copy))
        check(paths.isNotEmpty()) { "Screenshot clipboard publisher is disconnected from the save writer" }
        val writers = paths.map { key(it.last()) }.distinct()
        check(writers.size == 1) { "Screenshot clipboard save writer ambiguous" }
        // The same publisher is also used by native export/long-save helpers. Retire
        // their compiled inline copies too, even when they do not pass through this worker.
        val inlineFamily = linkedMapOf(key(copy) to copy)
        var frontier = listOf<MethodReference>(copy)
        repeat(4) {
            frontier = frontier.flatMap { incoming[key(it)].orEmpty() }
                .distinctBy(::key).filter { key(it) !in inlineFamily }
            frontier.forEach { inlineFamily[key(it)] = it }
        }
        val commitWriter = incoming[key(copy)].orEmpty().filter { method ->
            val body = dex.code(method)
            method.returnType == "Z" && params(method).take(5) == listOf("Landroid/content/Context;",
                "Landroid/graphics/Bitmap;", "Landroid/net/Uri;", "Ljava/io/OutputStream;",
                "Landroid/graphics/Bitmap\$CompressFormat;") && body.hasString("is_pending") &&
                body.calls.any { call -> key(call.method) == key(copy) && body.calls.any { update ->
                    update.address > call.address && update.method.definingClass == "Landroid/content/ContentResolver;" &&
                        update.method.name == "update"
                } }
        }.distinctBy(::key).singleOrNull() ?: error("Screenshot clipboard commit writer missing or ambiguous")
        val callers = (paths.flatten() + inlineFamily.values).map(::key).distinct()
        check(callers.size <= 128) { "Screenshot clipboard caller family unexpectedly large" }
        val saveWriter = paths.map { it.last() }.distinctBy(::key).single()
        val success = dex.code(saveWriter).writes.filter { (field, value) ->
            field.type == "Ljava/lang/Boolean;" && (value as? DexOperandIndex.Value.Field)?.field?.let {
                it.definingClass == "Ljava/lang/Boolean;" && it.name == "TRUE"
            } == true
        }.map { it.first }.distinct().single()
        val notify = dex.classDefinition(saveWriter.definingClass)!!.fields.single { it.type == success.definingClass }
        fun field(ref: org.jf.dexlib2.iface.reference.FieldReference) = "${ref.definingClass}->${ref.name}:${ref.type}"
        return ScreenshotClipboardContract(key(gate), key(copy), callers, key(commitWriter),
            key(saveWriter), field(notify), field(success))
    }
}
