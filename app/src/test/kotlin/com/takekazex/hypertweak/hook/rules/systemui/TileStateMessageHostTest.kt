package com.takekazex.hypertweak.hook.rules.systemui

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TileStateMessageHostTest {
    @Test fun `native show config owns the timer and hide retains slot cleanup`() {
        val apk = System.getenv("CC_MESSAGE_PLUGIN_APK")?.let(::File)
        assumeTrue("Set CC_MESSAGE_PLUGIN_APK for native message contract", apk?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val controller = classes.getValue("Lmiui/systemui/controlcenter/panel/main/header/MessageHeaderController;")
        val slot = controller.fields.mapNotNull { classes[it.type] }.distinct().single { type ->
            type.fields.any { it.type == controller.type } && type.fields.any { it.type == "Ljava/lang/Runnable;" }
        }
        assertEquals(1, controller.fields.count { it.type == "Landroid/os/Handler;" })
        assertEquals("Lmiuix/animation/base/AnimConfig;", slot.fields.single { it.name == "showConfig" }.type)
        val listener = classes.values.single { type ->
            type.fields.any { it.type == slot.type } && type.fields.any { it.type == controller.type } &&
                type.methods.any { method -> method.name == "onComplete" && method.implementation?.instructions
                    ?.filterIsInstance<ReferenceInstruction>()?.any {
                        (it.reference as? MethodReference)?.let { call ->
                            call.definingClass == "Landroid/os/Handler;" && call.name == "postDelayed"
                        } == true
                    } == true }
        }
        val complete = listener.methods.single { it.name == "onComplete" }
        assertEquals(listOf("Ljava/lang/Object;"), complete.parameterTypes.map { it.toString() })
        assertEquals("V", complete.returnType)
        assertTrue(complete.implementation!!.instructions.filterIsInstance<WideLiteralInstruction>().any { it.wideLiteral == 3000L })
        val config = classes.getValue("Lmiuix/animation/base/AnimConfig;")
        assertEquals("Ljava/util/HashSet;", config.fields.single { it.name == "listeners" }.type)
        fun animationInterface(type: String): Boolean = type == "Lmiuix/animation/IStateStyle;" ||
            classes.getValue("Lmiuix/animation/IStateStyle;").interfaces.contains(type)
        for (name in listOf("show", "hide")) {
            val method = slot.methods.single { it.name == name }
            assertEquals("V", method.returnType)
            assertTrue(method.parameterTypes.isEmpty())
            val calls = method.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
                .mapNotNull { it.reference as? MethodReference }
            assertTrue(calls.any { it.name == "removeCallbacks" && it.definingClass == "Landroid/os/Handler;" })
            assertTrue(calls.any { it.name == "to" && animationInterface(it.definingClass) })
        }
    }
}
