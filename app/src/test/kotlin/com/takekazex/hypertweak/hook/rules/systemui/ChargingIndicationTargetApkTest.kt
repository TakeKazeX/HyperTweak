package com.takekazex.hypertweak.hook.rules.systemui

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.TypeReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChargingIndicationTargetApkTest {
    @Test fun `host owns replacement controller and native wake restores queued indications`() {
        val apk = System.getenv("SYSTEMUI_APK")?.let(::File)
        assumeTrue(apk?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val host = classes.getValue("Lcom/android/systemui/statusbar/KeyguardIndicationController;")
        val rotate = classes.getValue("Lcom/android/systemui/keyguard/KeyguardIndicationRotateTextViewController;")
        fun references(method: org.jf.dexlib2.iface.Method) = method.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference
        }.toList()
        assertEquals(rotate.type, host.fields.single { it.name == "mRotateTextViewController" }.type)
        for (name in listOf("mDozing", "mVisible")) {
            assertEquals("Z", host.fields.single { it.name == name }.type)
        }
        val area = host.methods.single { it.name == "setIndicationArea" }
        assertEquals(listOf("Landroid/view/ViewGroup;"), area.parameterTypes.map(CharSequence::toString))
        val calls = references(area).filterIsInstance<MethodReference>()
        assertTrue(calls.any { it.definingClass == rotate.type && it.name == "destroy" })
        assertTrue(references(area).filterIsInstance<TypeReference>().any { it.type == rotate.type })
        val update = host.methods.single { it.name == "updateDeviceEntryIndication" }
        assertEquals(listOf("Z"), update.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", rotate.fields.single { it.name == "mIsDozing" }.type)
        assertEquals("I", rotate.fields.single { it.name == "mCurrIndicationType" }.type)
        assertEquals("Ljava/lang/CharSequence;", rotate.fields.single { it.name == "mCurrMessage" }.type)
        val listener = classes.getValue(rotate.fields.single { it.name == "mStatusBarStateListener" }.type)
        val wake = listener.methods.single { it.name == "onDozingChanged" }
        assertEquals(listOf("Z"), wake.parameterTypes.map(CharSequence::toString))
        val refs = references(wake)
        assertTrue(refs.filterIsInstance<FieldReference>().any { it.name == "mIsDozing" })
        assertTrue(refs.filterIsInstance<FieldReference>().any { it.name == "mIndicationQueue" })
        assertTrue(refs.filterIsInstance<MethodReference>().any {
            it.definingClass == rotate.type && it.name == "showIndication" && it.parameterTypes == listOf("I")
        })
        val show = rotate.methods.single { it.name == "showIndication" }
        assertEquals(listOf("I"), show.parameterTypes.map(CharSequence::toString))
        assertTrue(references(show).filterIsInstance<MethodReference>().any { it.name == "setText" })
    }
}
