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

class DockFreezeHostContractTest {
    private fun classes() = run {
        val input = System.getenv("MIUI_SERVICES_JAR")?.let(::File)
        assumeTrue("Set MIUI_SERVICES_JAR for vendor freezer bytecode checks", input?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(input!!, Opcodes.getDefault())
        dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }
    private fun Method.references() = implementation!!.instructions.filterIsInstance<ReferenceInstruction>().map { it.reference }.toList()

    @Test fun nativePolicyRejectsBeforeFreezingAndUsesItsOwnDenyConstant() {
        val classes = classes()
        val policy = classes.getValue("Lcom/miui/server/greeze/power/PolicyMaker;")
        val allow = policy.methods.single { it.name == "isAllowFreeze" }
        assertEquals(listOf("I"), allow.parameterTypes.map { it.toString() })
        assertEquals("I", allow.returnType)
        assertEquals("I", policy.fields.single { it.name == "CANNOT_FREEZE" }.type)
        assertTrue(allow.references().filterIsInstance<FieldReference>().any { it.name == "CANNOT_FREEZE" })
        val action = classes.getValue("Lcom/miui/server/greeze/power/ActionExecute;")
            .methods.single { it.name == "handleDelayedFreeze" }
        val calls = action.references().filterIsInstance<MethodReference>()
        assertTrue(calls.any { it.definingClass == policy.type && it.name == "isAllowFreeze" })
        assertTrue(calls.any { it.name == "freezeUid" })
        assertTrue(action.references().filterIsInstance<FieldReference>().any { it.name == "FREEZE_ABLE" })
    }

    @Test fun vendorWindowVisibilityReadsWmsAndGuardsNativeFreezeAction() {
        val classes = classes()
        val service = classes.getValue("Lcom/miui/server/greeze/GreezeManagerService;")
        val visible = service.methods.single { it.name == "isAppShowOnWindows" }
        assertEquals(listOf("I"), visible.parameterTypes.map { it.toString() })
        assertEquals("Z", visible.returnType)
        assertTrue(visible.references().filterIsInstance<MethodReference>().any {
            it.definingClass == "Landroid/view/IWindowManager;" && it.name == "checkAppOnWindowsStatus"
        })
        val freeze = service.methods.single { it.name == "freezeAction" }
        assertTrue(freeze.references().filterIsInstance<MethodReference>().any { it.name == visible.name })
    }
}
