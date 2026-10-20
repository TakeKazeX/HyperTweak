package com.takekazex.hypertweak.hook.rules.systemui

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.FieldReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Check the real host ABI and event consumers, independently of JADX reconstruction. */
class ControlCenterSuperIslandHostTest {
    private val prefix = "Lmiui/systemui/dynamicisland/window/"
    private fun classes(): Map<String, ClassDef> {
        val apk = System.getenv("CC_ISLAND_PLUGIN_APK")?.let(::File)
        assumeTrue("Set CC_ISLAND_PLUGIN_APK for host contract checks", apk?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }
    private fun Method.calls() = implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
        .mapNotNull { it.reference as? MethodReference }.toList()

    @Test fun `state exposes readable flows and per content native hide gate`() {
        val classes = classes()
        val state = classes.getValue("${prefix}DynamicIslandWindowState;")
        fun hasValue(type: String): Boolean {
            val owner = classes.getValue(type)
            return owner.methods.any { it.name == "getValue" && it.parameterTypes.isEmpty() } ||
                owner.interfaces.any(::hasValue)
        }
        for (name in listOf("getControlCenterExpanded", "getControlCenterPanelSwipeToAppearance", "getTempHidden")) {
            val getter = state.methods.single { it.name == name && it.parameterTypes.isEmpty() }
            assertTrue("$name exposes getValue through its declared interface", hasValue(getter.returnType))
        }
        val gate = classes.getValue("${prefix}ext/DynamicIslandWindowStateExtKt;").methods.single {
            it.name == "isTempHiddenExt" && it.parameterTypes.map(CharSequence::toString) ==
                listOf(state.type, "Ljava/lang/Integer;") && it.returnType == "Z"
        }
        assertTrue(gate.calls().any { it.name == "getTempHidden" })
        val contentGate = state.methods.single {
            it.name == "isTempHidden" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Integer;")
        }
        assertTrue(contentGate.calls().any { it.name == "isTempHiddenExt" })
    }

    @Test fun `control center writes native state and exposes its current view`() {
        val classes = classes()
        val controller = classes.getValue("${prefix}DynamicIslandWindowViewController;")
        val expanded = controller.methods.single {
            it.name == "controlCenterExpanded" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
        }
        assertTrue(expanded.calls().any { it.name == "getControlCenterExpanded" })
        val swipe = controller.methods.single { it.name == "controlCenterPanelExpandHeightChanged" }
        assertTrue(swipe.calls().any { it.name == "getControlCenterPanelSwipeToAppearance" })
        val access = controller.methods.single { it.name == "access\$getView" }
        assertEquals(listOf(controller.type), access.parameterTypes.map(CharSequence::toString))
        assertEquals("${prefix}DynamicIslandWindowView;", access.returnType)
        val view = classes.getValue(access.returnType)
        assertEquals(controller.type, view.methods.single { it.name == "getWindowViewController" }.returnType)
    }

    @Test fun `temporary hide event reaches native content coordinator`() {
        val classes = classes()
        val state = classes.getValue("${prefix}DynamicIslandWindowState;")
        val type = state.methods.single { it.name == "getTempHiddenType" }.returnType
        val view = classes.getValue("${prefix}DynamicIslandWindowView;")
        val event = view.methods.single {
            it.name == "onIslandTempHide" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", type)
        }
        assertTrue(event.calls().any { it.name == "setHide" })
        assertTrue(event.calls().any { it.name.startsWith("dispatchEvent") })
        val consumer = classes.getValue("Lmiui/systemui/dynamicisland/event/IslandTempHiddenEventCoordinator;")
            .methods.single { it.name == "handleAppEvent" }
        assertTrue(consumer.calls().any { it.name == "setTempHide" })
    }

    @Test fun `nullable readiness fields cover the throwing getter and native event no-op`() {
        val view = classes().getValue("${prefix}DynamicIslandWindowView;")
        val componentGetter = view.methods.single { it.name == "getViewComponent" }
        val component = view.fields.single { it.type == componentGetter.returnType && it.accessFlags and 8 == 0 }
        assertTrue("component publication remains volatile", component.accessFlags and 0x40 != 0)
        val event = view.methods.single { it.name == "onIslandTempHide" }
        val coordinator = view.fields.single {
            it.type == "Lmiui/systemui/dynamicisland/event/DynamicIslandEventCoordinator;" && it.accessFlags and 8 == 0
        }
        fun Method.reads(field: String): Boolean = implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().mapNotNull { it.reference as? FieldReference }
            .any { it.definingClass == view.type && it.name == field }
        assertTrue(componentGetter.reads(component.name))
        assertTrue(event.reads(coordinator.name))
        assertFalse("original event is safe before component initialization",
            event.calls().any { it.name == "getWindowViewController" || it.name == "getViewComponent" })
    }
}
