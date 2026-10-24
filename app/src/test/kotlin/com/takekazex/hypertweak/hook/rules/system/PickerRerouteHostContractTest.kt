package com.takekazex.hypertweak.hook.rules.system

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Run with the three PICKER_* inputs to verify the actual vendor execution chain. */
class PickerRerouteHostContractTest {
    private fun classes(env: String): List<org.jf.dexlib2.iface.ClassDef> {
        val file = System.getenv(env)?.let(::File)
        assumeTrue("Set $env to the verified host artifact", file?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
    }
    private fun calls(method: Method) = method.implementation!!.instructions.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? MethodReference
    }
    private fun strings(method: Method) = method.implementation!!.instructions.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
    }.toSet()
    private fun params(method: Method) = method.parameterTypes.map(CharSequence::toString)

    @Test fun `vendor private helper is unique and public wrapper retains cloud dispatch`() {
        val impl = classes("PICKER_MIUI_SERVICES").single { it.type == "Lcom/android/server/wm/ActivityTaskManagerServiceImpl;" }
        assertEquals("Lcom/android/server/wm/ActivityTaskManagerServiceStub;", impl.superclass)
        val shape = listOf("Landroid/content/Intent;", "Ljava/lang/String;")
        val helper = impl.methods.single { params(it) == shape && it.returnType == "Landroid/content/Intent;" && it.accessFlags and 2 != 0 }
        assertTrue(strings(helper).containsAll(listOf("android.intent.action.OPEN_DOCUMENT", "hyper.intent.action.OPEN_DOCUMENT", "com.android.fileexplorer")))
        assertTrue(calls(helper).any { it.name == "setAction" })
        assertTrue(calls(helper).any { it.name == "setPackage" })
        val wrapper = impl.methods.single { it.name == "hookStartActivity" && params(it) == shape }
        val chain = calls(wrapper)
        val redirect = chain.indexOfFirst { it.name == "redirectStartActivity" }
        val vendor = chain.indexOfFirst { it.name == helper.name && it.definingClass == impl.type }
        assertTrue(redirect >= 0 && vendor > redirect)
    }

    @Test fun `PM nine argument overload is unique and reached by query entrypoints`() {
        val classes = classes("PICKER_SERVICES")
        val engine = classes.single { it.type == "Lcom/android/server/pm/ComputerEngine;" }
        val shape = listOf("Landroid/content/Intent;", "Ljava/lang/String;", "J", "J", "I", "I", "I", "Z", "Z")
        val query = engine.methods.single { it.returnType == "Ljava/util/List;" && params(it) == shape }
        assertEquals("queryIntentActivitiesInternal", query.name)
        assertTrue(engine.methods.filter { it.name == query.name && params(it) != shape }.any { method ->
            calls(method).any { it.name == query.name && it.parameterTypes.map(CharSequence::toString) == shape }
        })
        val locked = classes.single { it.type == "Lcom/android/server/pm/ComputerLocked;" }
        assertEquals(engine.type, locked.superclass)
        assertFalse(locked.methods.any { it.name == query.name && params(it) == shape })
    }

    @Test fun `Hyper browse uses the nullable component helper and retains DocumentsUI fallback`() {
        val owner = classes("PICKER_APK").single { it.type == "Lcom/android/photopicker/hyper/HyperMainActivity;" }
        val helper = owner.methods.single { it.name == "getHyperFilePickerName" && params(it).isEmpty() && it.returnType == "Landroid/content/ComponentName;" }
        assertTrue(strings(helper).containsAll(listOf("securitycenter", "hyper_refer_file_picker")))
        val browse = owner.methods.single { it.name == "referToDocumentsUi" }
        assertTrue(calls(browse).any { it.name == helper.name })
        assertTrue(calls(helper).any { it.name == "getDocumentssUiComponentName" })
        assertTrue(calls(browse).any { it.name == "setComponent" })
    }
}
