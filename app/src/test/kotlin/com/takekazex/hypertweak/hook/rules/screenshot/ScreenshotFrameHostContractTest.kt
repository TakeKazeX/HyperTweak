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

/** Proves the immutable host contract, including bytecode behavior that reflection alone cannot check. */
class ScreenshotFrameHostContractTest {
    private fun classes(): Map<String, ClassDef> {
        val apk = System.getenv("SCREENSHOT_APK")?.let(::File)
        assumeTrue("Set SCREENSHOT_APK to run target bytecode checks", apk?.isFile == true)
        assertTrue("Update the adapter before accepting a different APK", NativeScreenshotFrameAccess.supports(apk!!))
        val dex = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }

    private fun Method.references() = implementation!!.instructions
        .filterIsInstance<ReferenceInstruction>().map { it.reference }.toList()
    private fun ClassDef.field(name: String) = fields.single { it.name == name }.type

    @Test fun `full screen boundary creates exactly the save task used by the worker`() {
        val classes = classes()
        val controller = classes.getValue("Lcom/miui/screenshot/z;")
        val save = controller.methods.single { it.name == "X1" }
        assertEquals(listOf("Lcom/miui/screenshot/interfaces/ISaveCallback;",
            "Lcom/miui/screenshot/interfaces/ITotalFinishCallback;"), save.parameterTypes.map { it.toString() })
        val calls = save.references().filterIsInstance<MethodReference>()
        assertTrue(calls.any { it.definingClass == "Lcom/miui/screenshot/a0;" && it.name == "<init>" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Lcom/miui/screenshot/X;", "Lp1/a;", "Z") })
        assertTrue(calls.any { it.name == "execute" })
        val thumbnail = controller.methods.single { it.name == "g2" }
        assertTrue(thumbnail.references().filterIsInstance<MethodReference>().any {
            it.definingClass == "Lcom/miui/gallery/editor/transition/RoundedCornerDrawable;" && it.name == "setBitmap"
        })
        val caller = controller.methods.single { it.name == "k2" }
        assertTrue(caller.references().filterIsInstance<MethodReference>().any { it.name == "X1" })
        val task = classes.getValue("Lcom/miui/screenshot/a0;")
        val worker = task.methods.single { it.name == "d" && it.returnType == "Lcom/miui/screenshot/X;" }
        assertEquals(listOf("[Lcom/miui/screenshot/X;"), worker.parameterTypes.map { it.toString() })
        assertTrue(worker.references().filterIsInstance<MethodReference>().any {
            it.definingClass == task.type && it.name == "e"
        })
        assertEquals("Landroid/graphics/Bitmap;", classes.getValue("Lcom/miui/screenshot/X;").field("b"))
        assertEquals("Landroid/graphics/Bitmap\$CompressFormat;", classes.getValue("Lcom/miui/screenshot/X;").field("e"))
    }

    @Test fun `file format and live MediaStore dimensions are the audited mutable fields`() {
        val classes = classes()
        val task = classes.getValue("Lcom/miui/screenshot/a0;")
        assertEquals("Ljava/lang/String;", task.field("i"))
        assertEquals("I", task.field("d"))
        assertEquals("I", task.field("e"))
        val strings = task.methods.filter { it.name == "<init>" }
            .flatMap { it.references().filterIsInstance<StringReference>() }.map { it.string }
        assertTrue(strings.contains("Screenshot_%s_%s.png"))
        val info = classes.getValue("Lcom/miui/screenshot/h0;")
        assertEquals("Ljava/lang/Integer;", info.field("n"))
        assertEquals("Ljava/lang/Integer;", info.field("o"))
        assertEquals("Landroid/net/Uri;", info.field("g"))
        assertEquals("Ljava/lang/Boolean;", info.field("h"))
        val mediaUtils = classes.getValue("Lcom/miui/screenshot/util/MediaUtils;")
        val mediaFields = mediaUtils.methods.filter { it.implementation != null }
            .flatMap { it.references().filterIsInstance<FieldReference>() }
            .filter { it.definingClass == info.type }.map { it.name }
        assertTrue(mediaFields.containsAll(listOf("n", "o")))
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
        assertEquals("Lcom/miui/screenshot/h0;", exporter.field("mNotifyMediaStoreData"))
        assertTrue(exporter.methods.any { it.name == "onExport" && it.returnType == "Z" })
        assertTrue(shell.methods.any { it.name == "doShell" && it.parameterTypes.size == 1 })
        val activity = classes.getValue("Lcom/miui/gallery/editor/photo/screen/home/ScreenEditorActivity;")
        assertTrue(activity.methods.any { it.name == "doShellExecutor" })
    }
}
