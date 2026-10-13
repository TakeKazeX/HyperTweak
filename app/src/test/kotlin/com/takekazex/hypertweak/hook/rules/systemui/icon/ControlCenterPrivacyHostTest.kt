package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.FieldReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The target APK must supply the real animation and privacy ABI used by the adapter. */
class ControlCenterPrivacyHostTest {
    private fun classes(): Map<String, ClassDef> {
        val file = System.getenv("CC_PRIVACY_SYSTEMUI_APK")?.let(::File)
        assumeTrue("Set CC_PRIVACY_SYSTEMUI_APK for target bytecode checks", file?.isFile == true)
        val container = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }

    @Test fun `native privacy view preserves callback icons and click ownership`() {
        val classes = classes()
        val view = classes.getValue("Lcom/android/systemui/controlcenter/shade/MiuiPrivacyContainerView;")
        assertTrue(view.fields.map { it.name }.containsAll(listOf("mPrivacyText", "mIconCamera",
            "mIconMic", "mIconLocation", "mIconScreen", "mIconDot", "mPrivacyBackground", "mEndSpace", "mVisibility", "mNeedShowing")))
        val prompt = view.methods.single { it.name == "onPromptInfoChanged" }
        assertEquals(listOf("Lcom/android/systemui/plugins/miui/statusbar/MiuiPrivacyController\$MiuiPromptInfo;",
            "Lcom/android/systemui/plugins/miui/statusbar/MiuiPrivacyController\$AndroidPromptInfo;"),
            prompt.parameterTypes.map(CharSequence::toString))
        val click = classes.getValue("Lcom/android/systemui/controlcenter/shade/MiuiPrivacyContainerView\$\$ExternalSyntheticLambda0;")
            .methods.single { it.name == "onClick" }
        assertTrue(click.implementation!!.instructions.filterIsInstance<ReferenceInstruction>().any {
            val ref = it.reference as? MethodReference
            ref?.definingClass == "Lcom/android/systemui/statusbar/privacy/MiuiPrivacyControllerImpl;" && ref.name == "onClick"
        })
        val tracker = classes.getValue("Lcom/android/systemui/statusbar/privacy/MiuiPrivacyHistoryTracker;")
        val promptInfo = classes.getValue("Lcom/android/systemui/statusbar/privacy/MiuiPrivacyControllerImpl\$MiuiPromptInfoImpl;")
        assertTrue(promptInfo.fields.any { it.name == "mPackageName" && it.type == "Ljava/lang/String;" })
        assertTrue(tracker.fields.any { it.name == "privacyHistory" })
        val state = classes.getValue("Lcom/android/systemui/statusbar/privacy/MiuiPrivacyHistoryTracker\$PrivacyItemWithState;")
        assertTrue(state.fields.any { it.name == "isBeingCalled" && it.type == "Z" })
        assertTrue(state.fields.any { it.name == "inactiveSinceElapsed" && it.type == "J" })
        assertTrue(tracker.methods.any { it.name == "removeExpiredItems" && it.parameterTypes.map(CharSequence::toString) == listOf("J") })
    }

    @Test fun `native progress drives both real and fake privacy translations`() {
        val classes = classes()
        val expansion = classes.getValue("Lcom/android/systemui/controlcenter/shade/ControlCenterHeaderExpandController;")
        assertTrue(expansion.fields.map { it.name }.containsAll(listOf("normalControlPrivacyTranslationX",
            "normalControlPrivacyTranslationY", "headerController", "controlCenterCallback")))
        assertTrue(expansion.methods.any { it.name == "updateLocation" && it.parameterTypes.isEmpty() })
        val callback = classes.getValue("Lcom/android/systemui/controlcenter/shade/ControlCenterHeaderExpandController\$controlCenterCallback\$1;")
        assertTrue(callback.fields.any { it.name == "progress" && it.type == "F" })
        val progress = callback.methods.single { it.name == "onExpansionChanged" }
        assertEquals(listOf("F"), progress.parameterTypes.map(CharSequence::toString))
        val calls = progress.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }.toList()
        val fields = progress.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? FieldReference }.map { it.name }.toSet()
        assertTrue(fields.containsAll(listOf("controlCenterFakePrivacyContainer", "controlCenterPrivacyContainer",
            "normalControlPrivacyTranslationX", "normalControlPrivacyTranslationY")))
        for (property in listOf("setTranslationX", "setTranslationY")) {
            // DEX resolves the inherited setters on View, although JADX prints the concrete receivers.
            assertTrue("both privacy surfaces retain native translation calls", calls.count { it.name == property } >= 2)
        }
        val constraints = classes.getValue("Landroidx/constraintlayout/widget/ConstraintLayout\$LayoutParams;")
        assertTrue(constraints.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) ==
            listOf("Landroid/view/ViewGroup\$LayoutParams;") })
        assertTrue(constraints.fields.map { it.name }.containsAll(listOf("startToStart", "endToEnd",
            "topToTop", "bottomToBottom", "bottomToTop")))
    }
}
