package com.takekazex.hypertweak.dock

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Check the installed platform's actual sampling and IME ownership contracts after an OTA. */
class DockFrameworkContractTest {
    private fun classes() = run {
        val input = System.getenv("FRAMEWORK_JAR")?.let(::File)
        assumeTrue("Set FRAMEWORK_JAR for platform bytecode checks", input?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(input!!, Opcodes.getDefault())
        dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }
    private fun Method.references() = implementation!!.instructions
        .filterIsInstance<ReferenceInstruction>().map { it.reference }.toList()

    @Test fun `own renderer bridge follows native View sampling and release operations`() {
        val classes = classes()
        val view = classes.getValue("Landroid/view/View;")
        val enable = view.methods.single { it.name == "setPassWindowBlurEnabled" }
        assertEquals(listOf("Z"), enable.parameterTypes.map { it.toString() })
        val calls = enable.references().filterIsInstance<MethodReference>()
        assertTrue(calls.any { it.definingClass == "Landroid/view/ViewRootImpl;" && it.name == "addTextureView" })
        assertTrue(calls.any { it.definingClass == "Landroid/view/ViewRootImpl;" && it.name == "clearTextureView" })
        assertTrue(calls.any { it.definingClass == "Landroid/graphics/RenderNode;" && it.name == "setPassWindowBlurEnabled" })
        assertEquals("Z", view.fields.single { it.name == "mNeedPassWindowBlur" }.type)
        assertEquals("Landroid/graphics/RenderNode;", view.fields.single { it.name == "mRenderNode" }.type)
        val root = classes.getValue("Landroid/view/ViewRootImpl;")
        val add = root.methods.single { it.name == "addTextureView" }
        assertEquals(listOf("Landroid/view/View;"), add.parameterTypes.map { it.toString() })
        assertTrue(add.references().filterIsInstance<MethodReference>().any { it.name == "updateSfState" })
        val clear = root.methods.single { it.name == "clearTextureView" }
        assertTrue(clear.references().filterIsInstance<MethodReference>().any { it.name == "updateSfState" })
        val release = root.methods.single { it.name == "releaseSurTex" }
        assertTrue(release.references().filterIsInstance<MethodReference>().any {
            it.definingClass == "Landroid/graphics/SurfaceTexture;" && it.name == "release"
        })
    }

    @Test fun `IME root comes from service owned layout rather than a numeric resource ID`() {
        val service = classes().getValue("Landroid/inputmethodservice/InputMethodService;")
        assertEquals("Landroid/view/View;", service.fields.single { it.name == "mRootView" }.type)
        val init = service.methods.single { it.name == "initViews" }
        val fields = init.references().filterIsInstance<FieldReference>()
        assertTrue(fields.any { it.definingClass == service.type && it.name == "mRootView" })
        val calls = init.references().filterIsInstance<MethodReference>()
        assertTrue(calls.any { it.name == "inflate" })
        assertTrue(calls.any { it.name == "setContentView" })
    }
}
