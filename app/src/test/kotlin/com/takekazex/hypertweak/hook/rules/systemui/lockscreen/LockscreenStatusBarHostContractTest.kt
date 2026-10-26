package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercise the actual target bytecode; neither build success nor a guessed host name suffices. */
class LockscreenStatusBarHostContractTest {
    private fun classes(variable: String): Map<String, ClassDef> {
        val file = System.getenv(variable)?.let(::File)
        assumeTrue("Set $variable to a verified target", file?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }
    private fun Method.fields() = implementation!!.instructions.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? FieldReference
    }
    private fun Method.calls() = implementation!!.instructions.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? MethodReference
    }

    @Test fun actualShowingAndOcclusionArePublishedAtTheHookedBoundary() {
        val classes = classes("LOCKSCREEN_SYSTEMUI_APK")
        val owner = "Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;"
        val controller = classes.getValue(owner)
        val notify = controller.methods.single { it.name == "notifyKeyguardState" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Z", "Z") && it.returnType == "V" }
        for (name in listOf("mShowing", "mOccluded")) {
            assertEquals("Z", controller.fields.single { it.name == name }.type)
            assertTrue(notify.fields().any { it.definingClass == owner && it.name == name && it.type == "Z" })
        }
        val barController = classes.getValue("Lcom/android/systemui/statusbar/phone/KeyguardStatusBarViewController;")
        assertEquals("Lcom/android/systemui/statusbar/policy/KeyguardStateController;",
            barController.fields.single { it.name == "mKeyguardStateController" }.type)
        assertTrue(barController.methods.any { it.name == "onViewAttached" && it.parameterTypes.isEmpty() })
        val component = classes.getValue("Lcom/android/systemui/dagger/DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl;")
        assertEquals("Ldagger/internal/Provider;", component.fields.single { it.name == "keyguardStateControllerImplProvider" }.type)
    }

    @Test fun keyguardHasIndependentAttachAndInflateBoundariesAndNativeIconManager() {
        val bar = classes("LOCKSCREEN_SYSTEMUI_APK").getValue("Lcom/android/systemui/statusbar/phone/MiuiKeyguardStatusBarView;")
        for (name in listOf("onAttachedToWindow", "onFinishInflate")) {
            assertEquals("V", bar.methods.single { it.name == name && it.parameterTypes.isEmpty() }.returnType)
        }
        assertEquals("Lcom/android/systemui/statusbar/phone/MiuiLightDarkIconManager;",
            bar.fields.single { it.name == "mTintedIconManager" }.type)
        assertTrue(bar.methods.single { it.name == "onAttachedToWindow" }.calls().any { it.name == "initCallback" })
    }

    @Test fun windowInspectorReturnsTheWindowViewsSnapshotRatherThanViewRoots() {
        val classes = classes("LOCKSCREEN_FRAMEWORK_JAR")
        val inspector = classes.getValue("Landroid/view/inspector/WindowInspector;")
            .methods.single { it.name == "getGlobalWindowViews" }
        assertTrue(inspector.calls().any { it.definingClass == "Landroid/view/WindowManagerGlobal;" && it.name == "getWindowViews" })
        val global = classes.getValue("Landroid/view/WindowManagerGlobal;")
        val snapshot = global.methods.single { it.name == "getWindowViews" }
        assertTrue(snapshot.fields().any { it.name == "mViews" })
        assertFalse(snapshot.fields().any { it.name == "mRoots" })
        assertTrue(snapshot.calls().any { it.definingClass == "Ljava/util/ArrayList;" && it.name == "<init>" })
    }
}
