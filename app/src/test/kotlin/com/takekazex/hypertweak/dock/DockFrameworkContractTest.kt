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

    @Test fun `surface replacement can discard sampling while View still requests blur`() {
        val root = classes().getValue("Landroid/view/ViewRootImpl;")
        val has = root.methods.single { it.name == "hasTextureView" }
        assertEquals(listOf("Landroid/view/View;"), has.parameterTypes.map { it.toString() })
        assertEquals("Z", has.returnType)
        val clear = root.methods.single { it.name == "clearPassBlurTextureStateIfNeed" }.references()
        assertTrue(clear.filterIsInstance<MethodReference>().any {
            it.definingClass == "Ljava/util/HashMap;" && it.name == "clear"
        })
        assertFalse(clear.filterIsInstance<FieldReference>().any {
            it.definingClass == "Landroid/view/View;" && it.name == "mNeedPassWindowBlur"
        })
        assertTrue(root.methods.single { it.name == "updateSurfaceControl" }.references()
            .filterIsInstance<MethodReference>().any { it.name == "clearPassBlurTextureStateIfNeed" })
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

    @Test fun `relayout can reset capture rotation after initial readiness`() {
        val classes = classes()
        val root = classes.getValue("Landroid/view/ViewRootImpl;")
        fun calls(name: String) = root.methods.single { it.name == name }.references()
            .filterIsInstance<MethodReference>()
        assertTrue(calls("relayoutWindow").any { it.name == "checkSurTexSize" })
        assertTrue(calls("checkSurTexSize").any { it.name == "checkConfigRot" })
        assertTrue(calls("checkConfigRot").any {
            it.definingClass == "Landroid/app/WindowConfiguration;" && it.name == "getRotation"
        })
        mapOf("mConfigRot" to "I", "mSurfaceSize" to "Landroid/graphics/Point;",
            "mDispRect" to "Landroid/graphics/Rect;", "mTexScale" to "F",
            "mSurTex" to "Landroid/graphics/SurfaceTexture;", "mLastSfState" to "I",
            "mTextureVis" to "Z").forEach { (name, type) ->
            assertEquals(type, root.fields.single { it.name == name }.type)
        }
        val available = classes.getValue("Landroid/view/View;").methods.single { it.name == "setTextureAvailable" }
        assertEquals(listOf("Z", "I", "F"), available.parameterTypes.map { it.toString() })
    }

    @Test fun `windowless host retains its supplied configuration for native relayout`() {
        val classes = classes()
        val host = classes.getValue("Landroid/view/SurfaceControlViewHost;")
        assertEquals("Landroid/view/WindowlessWindowManager;", host.methods.single { it.name == "getWindowlessWM" }.returnType)
        val wm = classes.getValue("Landroid/view/WindowlessWindowManager;")
        val setter = wm.methods.single { it.name == "setConfiguration" }
        assertEquals(listOf("Landroid/content/res/Configuration;"), setter.parameterTypes.map { it.toString() })
        assertTrue(setter.references().filterIsInstance<MethodReference>().any {
            it.definingClass == "Landroid/content/res/Configuration;" && it.name == "setTo"
        })
        assertTrue(setter.references().filterIsInstance<MethodReference>().any { it.name == "dispatchStateToClients" })
        val relayout = wm.methods.single { it.name == "relayoutInner" }.references()
        assertTrue(relayout.filterIsInstance<FieldReference>().any { it.name == "mConfiguration" })
        assertTrue(relayout.filterIsInstance<MethodReference>().any {
            it.definingClass == "Landroid/util/MergedConfiguration;" && it.name == "setConfiguration" &&
                it.parameterTypes.map { type -> type.toString() } == List(2) { "Landroid/content/res/Configuration;" }
        })
        val context = classes.getValue("Landroid/content/Context;")
        assertEquals(listOf("Landroid/content/res/Configuration;"), context.methods.single {
            it.name == "createConfigurationContext"
        }.parameterTypes.map { it.toString() })
    }

    @Test fun `capture can start on the worker after the surface attachment commits`() {
        val classes = classes()
        val transaction = classes.getValue("Landroid/view/SurfaceControl\$Transaction;")
        val listener = "Landroid/view/SurfaceControl\$TransactionCommittedListener;"
        val add = transaction.methods.single { it.name == "addTransactionCommittedListener" }
        assertEquals(listOf("Ljava/util/concurrent/Executor;", listener), add.parameterTypes.map { it.toString() })
        val callback = classes.getValue(listener).methods.single { it.name == "onTransactionCommitted" }
        assertTrue(callback.parameterTypes.isEmpty())
        assertEquals("V", callback.returnType)
    }
}
