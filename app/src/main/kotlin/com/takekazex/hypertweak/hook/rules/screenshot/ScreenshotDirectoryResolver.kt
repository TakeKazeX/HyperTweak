package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference

internal data class ScreenshotDirectoryContract(val predicate: String, val callers: List<String>, val workers: List<String>) {
    fun encode() = mapOf("predicate" to predicate, "callers" to callers.joinToString("\n"), "workers" to workers.joinToString("\n"))
}

/** Both the filesystem and MediaStore must consume the same native directory decision. */
internal class ScreenshotDirectoryResolver(private val dex: CameraDexIndex) {
    private fun key(method: MethodReference) = CameraDexIndex.descriptor(method)
    fun resolve(): ScreenshotDirectoryContract {
        val rows = dex.strings("relative_path", "Screenshots").filter { method ->
            dex.code(method).calls.any { it.method.definingClass == "Landroid/content/ContentResolver;" && it.method.name == "insert" }
        }
        val candidates = rows.flatMap { dex.code(it).calls.map { call -> call.method } }.filter {
            it.parameterTypes.isEmpty() && it.returnType == "Z" &&
                dex.definition(it)?.accessFlags?.and(0x8) == 0x8 &&
                dex.code(it).hasString("ro.product.mod_device")
        }.distinctBy(::key)
        val predicate = candidates.singleOrNull() ?: error("Screenshot directory predicate missing or ambiguous")
        val callers = dex.methods.filter { method ->
            method.implementation?.instructions?.filterIsInstance<ReferenceInstruction>()?.any {
                val target = it.reference as? MethodReference
                target != null && key(target) == key(predicate)
            } == true
        }
        val files = callers.filter { method ->
            val code = dex.code(method)
            code.calls.any { it.method.definingClass == "Landroid/os/Environment;" && it.method.name == "getExternalStoragePublicDirectory" } &&
                listOf("DIRECTORY_PICTURES", "DIRECTORY_DCIM").all { name ->
                    code.reads.any { it.definingClass == "Landroid/os/Environment;" && it.name == name }
                } && dex.classDefinition(method.definingClass)?.superclass == "Landroid/os/AsyncTask;"
        }
        check(files.isNotEmpty()) { "Screenshot filesystem directory callers missing" }
        check(rows.size == 1 && rows.single() in callers) { "Screenshot MediaStore directory caller ambiguous" }
        val workers = files.map { file -> dex.declared(file.definingClass).filter { method ->
            method.parameterTypes.size == 1 && method.parameterTypes[0].toString().startsWith("[L") &&
                method.returnType == method.parameterTypes[0].toString().removePrefix("[") &&
                dex.code(method).calls.any { key(it.method) == key(file) }
        }.singleOrNull() ?: error("Screenshot directory save scope missing or ambiguous") }
        val bridges = workers.flatMap { worker -> dex.declared(worker.definingClass).filter { method ->
            dex.code(method).calls.any { key(it.method) == key(worker) }
        } }
        return ScreenshotDirectoryContract(key(predicate), (callers + bridges).map(::key).distinct(), workers.map(::key))
    }
}
