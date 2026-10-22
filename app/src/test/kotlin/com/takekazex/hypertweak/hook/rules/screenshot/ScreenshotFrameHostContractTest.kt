package com.takekazex.hypertweak.hook.rules.screenshot

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.dexlib2.writer.io.MemoryDataStore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

/** Proves the immutable host contract, including bytecode behavior that reflection alone cannot check. */
class ScreenshotFrameHostContractTest {
    private lateinit var contract: ScreenshotFrameResolver.Contract
    private fun classes(): Map<String, ClassDef> {
        val apk = System.getenv("SCREENSHOT_APK")?.let(::File)
        assumeTrue("Set SCREENSHOT_APK to run target bytecode checks", apk?.isFile == true)
        contract = ScreenshotFrameResolver(apk!!.path).resolve()
        val dex = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }

    private fun Method.references() = implementation!!.instructions
        .filterIsInstance<ReferenceInstruction>().map { it.reference }.toList()
    private fun ClassDef.field(name: String) = fields.single { it.name == name }.type

    @Test fun `save worker preview and bridge resolve as one graph`() {
        val classes = classes()
        fun definition(role: String): Method {
            val ref = contract.methods.getValue(role)
            return classes.getValue(ref.definingClass).methods.single {
                it.name == ref.name && it.parameterTypes == ref.parameterTypes && it.returnType == ref.returnType
            }
        }
        val save = definition("save")
        assertTrue(save.references().contains(contract.methods.getValue("taskConstructor")))
        assertTrue(definition("worker").references().contains(contract.methods.getValue("write")))
        assertTrue(definition("workerBridge").references().contains(contract.methods.getValue("worker")))
        assertTrue(definition("saveCaller").references().contains(contract.methods.getValue("save")))
        assertTrue(definition("thumbnailReady").references().contains(contract.fields.getValue("captureBitmap")))
        assertTrue(save.references().contains(contract.fields.getValue("captureBitmap")))
    }

    @Test fun `mutable dimensions and format belong to the actual data records`() {
        val classes = classes()
        val expected = mapOf("bitmap" to "Landroid/graphics/Bitmap;", "format" to "Landroid/graphics/Bitmap\$CompressFormat;",
            "taskWidth" to "I", "taskHeight" to "I", "fileTemplate" to "Ljava/lang/String;",
            "width" to "Ljava/lang/Integer;", "height" to "Ljava/lang/Integer;", "uri" to "Landroid/net/Uri;", "success" to "Ljava/lang/Boolean;")
        expected.forEach { (role, type) ->
            val ref = contract.fields.getValue(role)
            val field = classes.getValue(ref.definingClass).fields.single { it.name == ref.name }
            assertEquals(type, field.type)
            assertEquals(0, field.accessFlags and 16)
        }
    }

    private fun fieldKey(ref: FieldReference) = "${ref.definingClass}->${ref.name}:${ref.type}"

    /** Repackage real DEX after independently changing every resolved obfuscated type/member. */
    private fun fixture(duplicate: Boolean = false, removeWriter: Boolean = false, check: (File) -> Unit) {
        classes()
        val apk = File(requireNotNull(System.getenv("SCREENSHOT_APK")))
        val source = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        val clipboard = ScreenshotClipboardResolver(CameraDexIndex.open(apk.path)).resolve()
        val types = (contract.methods.values.map { it.definingClass } + contract.fields.values.flatMap { listOf(it.definingClass) } +
            contract.methods.getValue("taskConstructor").parameterTypes.map(CharSequence::toString) +
            (clipboard.callers + clipboard.gate).map { it.substringBefore("->") })
            .filter { it.startsWith("L") && !it.startsWith("Landroid/") && it != "Lcom/miui/gallery/editor/photo/app/ExportTask;" && !it.contains("/interfaces/") && !it.contains("/core/") }
            .distinct().mapIndexed { index, type -> type to "Lfixture/adaptive/Type$index;" }.toMap()
        fun type(value: String): String = if (value.startsWith("[")) "[" + type(value.substring(1)) else types[value] ?: value
        val methodNames = (contract.methods.values.filter { it.name != "<init>" && it.name != "doInBackground" }
            .map(CameraDexIndex::descriptor) + clipboard.callers + clipboard.gate)
            .distinct().filter { it.substringAfter("->").substringBefore("(") != "doInBackground" }
            .mapIndexed { index, ref -> ref to "operation$index" }.toMap()
        val fieldNames = contract.fields.values.mapIndexed { index, ref -> fieldKey(ref) to "slot$index" }.toMap()
        val rewrite = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters) = Rewriter<String>(::type)
            override fun getMethodReferenceRewriter(rewriters: Rewriters) = Rewriter<MethodReference> { ref ->
                ImmutableMethodReference(type(ref.definingClass), methodNames[CameraDexIndex.descriptor(ref)] ?: ref.name,
                    ref.parameterTypes.map { type(it.toString()) }, type(ref.returnType))
            }
            override fun getFieldReferenceRewriter(rewriters: Rewriters) = Rewriter<FieldReference> { ref ->
                ImmutableFieldReference(type(ref.definingClass), fieldNames[fieldKey(ref)] ?: ref.name, type(ref.type))
            }
        })
        val directory = createTempDirectory("screenshot-adaptive-").toFile()
        try {
            val fixture = File(directory, "fixture.apk")
            ZipOutputStream(fixture.outputStream()).use { output ->
                source.dexEntryNames.forEach { name ->
                    val original = source.getEntry(name)!!.dexFile
                    val rewritten = rewrite.dexFileRewriter.rewrite(original)
                    val selected = rewritten.classes.filter { owner ->
                        !removeWriter || owner.type != type(contract.methods.getValue("write").definingClass)
                    }.toMutableSet()
                    if (duplicate) selected.addAll(original.classes.filter { it.type in types.keys })
                    val dex = object : DexFile {
                        override fun getOpcodes() = original.opcodes
                        override fun getClasses() = selected
                    }
                    val store = MemoryDataStore()
                    DexPool.writeTo(store, dex)
                    output.putNextEntry(ZipEntry(name.substringAfterLast('/')))
                    output.write(store.buffer, 0, store.size)
                    output.closeEntry()
                    store.close()
                }
            }
            check(fixture)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `renaming classes methods fields and changing APK identity preserves resolution`() = fixture { apk ->
        val resolved = ScreenshotFrameResolver(apk.path).resolve()
        assertTrue(resolved.methods.getValue("save").definingClass.startsWith("Lfixture/adaptive/"))
        assertTrue(resolved.methods.getValue("save").name.startsWith("operation"))
        assertTrue(resolved.fields.getValue("bitmap").name.startsWith("slot"))
    }

    @Test fun `duplicate save graph is rejected rather than selecting the first candidate`() = fixture(duplicate = true) { apk ->
        assertThrows(IllegalStateException::class.java) { ScreenshotFrameResolver(apk.path).resolve() }
    }

    @Test fun `missing worker is rejected before any save can enroll`() = fixture(removeWriter = true) { apk ->
        assertThrows(IllegalStateException::class.java) { ScreenshotFrameResolver(apk.path).resolve() }
    }

    @Test fun `clipboard gate is consumed before publication and task flag only controls the later tip`() {
        classes()
        val dex = CameraDexIndex.open(requireNotNull(System.getenv("SCREENSHOT_APK")))
        val resolved = ScreenshotClipboardResolver(dex).resolve()
        val copy = dex.code(resolved.copy)
        val gate = copy.calls.single { CameraDexIndex.descriptor(it.method) == resolved.gate }
        val publish = copy.calls.single { it.method.definingClass == "Landroid/content/ClipboardManager;" && it.method.name == "setPrimaryClip" }
        assertTrue(gate.address < publish.address)
        assertTrue(copy.branches.any { branch ->
            (branch.value as? CameraDexIndex.Value.Call)?.method?.let(CameraDexIndex::descriptor) == resolved.gate &&
                branch.skipsWhenFalse && branch.end <= publish.address
        })
        // The boolean cannot be the copy gate: its only guarded call is after publication.
        val tip = copy.calls.single { it.method.definingClass == resolved.gate.substringBefore("->") &&
            it.method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Z") }
        assertTrue(publish.address < tip.address)
        val write = CameraDexIndex.descriptor(contract.methods.getValue("write"))
        assertTrue(resolved.callers.contains(write))
        // Prove actual edges, rather than adding a desired target to a set before asserting it.
        var reachable = setOf(write)
        repeat(8) { reachable = reachable + reachable.flatMap { dex.code(it).calls.map { c -> CameraDexIndex.descriptor(c.method) } } }
        val directPublishers = dex.methods.filter { method ->
            method.implementation != null && method.references().filterIsInstance<MethodReference>()
                .any { CameraDexIndex.descriptor(it) == resolved.copy }
        }.map(CameraDexIndex::descriptor)
        assertTrue("Export and long-save callers must not retain inlined publishers", resolved.callers.containsAll(directPublishers))
        assertTrue(resolved.copy in reachable)
        assertTrue(resolved.gate in reachable)
    }

    @Test fun `renamed clipboard gate and publisher follow the save graph`() = fixture { apk ->
        val resolved = ScreenshotClipboardResolver(CameraDexIndex.open(apk.path)).resolve()
        assertTrue(resolved.gate.startsWith("Lfixture/adaptive/"))
        assertTrue(resolved.copy.contains("->operation"))
    }

    @Test fun `disconnected clipboard publisher rejects the contract`() = fixture(removeWriter = true) { apk ->
        assertThrows(IllegalStateException::class.java) { ScreenshotClipboardResolver(CameraDexIndex.open(apk.path)).resolve() }
    }

    @Test fun `duplicate clipboard graph rejects the contract`() = fixture(duplicate = true) { apk ->
        assertThrows(IllegalStateException::class.java) { ScreenshotClipboardResolver(CameraDexIndex.open(apk.path)).resolve() }
    }

    @Test fun `native frame uses model resources and creates an independent output without recycling input`() {
        val classes = classes()
        val entry = classes.getValue("Lcom/miui/gallery/editor/photo/screen/shell/ScreenShellEntry;")
        val generate = entry.methods.single { it.name == "generateBitmap" }
        assertEquals(listOf("shell.json", "shell.png", "shell.svg"),
            generate.references().filterIsInstance<StringReference>().map { it.string }.filter { it.startsWith("shell.") }.sorted())
        val apply = entry.methods.single { it.name == "apply" && it.returnType == "Landroid/graphics/Bitmap;" }
        val calls = apply.references().filterIsInstance<MethodReference>()
        assertTrue(calls.any { it.name == "createBitmap" })
        assertTrue(calls.any { it.name == "clipPath" })
        assertTrue(calls.any { it.name == "getColorSpace" })
        assertFalse(calls.any { it.name == "recycle" })
    }

    @Test fun `editor shell gate png export and export receipt interfaces exist in both host processes`() {
        val classes = classes()
        val shell = classes.getValue("Lcom/miui/gallery/editor/photo/scribbles/ScreenShellManager;")
        assertEquals("Z", shell.field("mIsRightRatio"))
        val gate = shell.methods.single { it.name == "checkShellFuncEnabled" }
        assertTrue(gate.references().filterIsInstance<FieldReference>().any { it.name == "mIsRightRatio" })
        val manager = classes.getValue("Lcom/miui/gallery/editor/photo/scribbles/ScreenImageManager;")
        assertEquals("Landroid/net/Uri;", manager.field("mSource"))
        assertTrue(manager.methods.any { it.name == "isSavedAsPng" && it.returnType == "Z" })
        assertTrue(manager.methods.any { it.name == "openInputStream" && it.returnType == "Ljava/io/InputStream;" })
        val exporter = classes.getValue("Lcom/miui/gallery/editor/photo/app/ExportTask;")
        assertEquals(contract.fields.getValue("notify").type, exporter.field("mNotifyMediaStoreData"))
        assertTrue(exporter.methods.any { it.name == "onExport" && it.returnType == "Z" })
        assertTrue(shell.methods.any { it.name == "doShell" && it.parameterTypes.size == 1 })
        val activity = classes.getValue("Lcom/miui/gallery/editor/photo/screen/home/ScreenEditorActivity;")
        assertTrue(activity.methods.any { it.name == "doShellExecutor" })
    }
    @Test fun `flattened editor source uses native crop path without creating a shell layer`() {
        val classes = classes()
        val activity = classes.getValue("Lcom/miui/gallery/editor/photo/screen/home/ScreenEditorActivity;")
        assertEquals("Lcom/miui/gallery/editor/photo/scribbles/ScreenImageManager;", activity.field("mScreenImageManager"))
        val gateCalls = activity.methods.single { it.name == "initShellView" }.references()
            .filterIsInstance<MethodReference>()
        assertTrue(gateCalls.any { it.name == "checkShellFuncEnabled" && it.parameterTypes == listOf("Landroid/content/Context;") })
        val shell = classes.getValue("Lcom/miui/gallery/editor/photo/scribbles/ScreenShellManager;")
        val gate = shell.methods.single { it.name == "checkShellFuncEnabled" }
        assertTrue(gate.references().filterIsInstance<FieldReference>().any { it.name == "mIsShellEnabled" })
        // Cropping is hidden only when a separate native shell layer is active.
        val editor = classes.getValue("Lcom/miui/gallery/editor/photo/screen/home/ScreenEditorView;")
        val cropCalls = editor.methods.single { it.name == "updateScreenCrop" }.references()
            .filterIsInstance<MethodReference>()
        assertTrue(cropCalls.any { it.name == "isWithShell" })
        assertTrue(cropCalls.any { it.name == "setVisible" && it.parameterTypes == listOf("Z") })
        val imageManager = classes.getValue("Lcom/miui/gallery/editor/photo/scribbles/ScreenImageManager;")
        assertEquals("Landroid/content/Context;", imageManager.field("mContext"))
        assertTrue(imageManager.methods.any { it.name == "setNeedSaveAsPng" && it.parameterTypes == listOf("Z") })
    }

}
