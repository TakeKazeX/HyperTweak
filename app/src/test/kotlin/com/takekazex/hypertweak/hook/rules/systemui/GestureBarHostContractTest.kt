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

class GestureBarHostContractTest {
    private fun classes(variable: String): Map<String, ClassDef> {
        val file = System.getenv(variable)?.let(::File)
        assumeTrue("Set $variable for target bytecode validation", file?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }

    private fun Method.calls(): List<MethodReference> = implementation!!.instructions.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? MethodReference
    }

    private fun Method.fields(): List<FieldReference> = implementation!!.instructions.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? FieldReference
    }

    @Test fun `native explicit detach and final finish are separate restore call sites`() {
        val classes = classes("GESTURE_BAR_SERVICES_JAR")
        val transition = classes.getValue("Lcom/android/server/wm/Transition;")
        val controller = classes.getValue("Lcom/android/server/wm/TransitionController;")
        val detach = controller.methods.single { it.name == "legacyDetachNavigationBarFromApp" }
        assertEquals(listOf("Landroid/os/IBinder;"), detach.parameterTypes.map(CharSequence::toString))
        assertTrue(detach.calls().any { it.definingClass == transition.type && it.name == "legacyRestoreNavigationBarFromApp" })
        assertTrue(transition.methods.single { it.name == "finishTransition" }.calls().any {
            it.definingClass == transition.type && it.name == "legacyRestoreNavigationBarFromApp"
        })
        val restore = transition.methods.single { it.name == "legacyRestoreNavigationBarFromApp" }
        val calls = restore.calls()
        assertTrue(calls.any { it.name == "sendLumaSamplingEnabledToStatusBarInternal" })
        assertTrue(calls.any { it.name == "reparent" && it.definingClass == "Landroid/view/SurfaceControl\$Transaction;" })
        assertTrue(calls.any { it.name == "fadeWindowToken" })
        assertTrue(restore.fields().any { it.name == "mNavigationBarAttachedToApp" && it.type == "Z" })
    }

    @Test fun `native sampling notification reaches navigation controller and fade timing is verified`() {
        val ui = classes("GESTURE_BAR_SYSTEMUI_APK")
        val callback = ui.getValue("Lcom/android/systemui/navigationbar/NavigationBarControllerImpl\$1;")
            .methods.single { it.name == "setNavigationBarLumaSamplingEnabled" }
        assertEquals(listOf("I", "Z"), callback.parameterTypes.map(CharSequence::toString))
        assertTrue(callback.calls().any { it.name == "getNavigationBar" })
        assertTrue(callback.calls().any { it.name == "start" && it.definingClass.endsWith("/RegionSamplingHelper;") })
        assertTrue(callback.calls().any { it.name == "stop" && it.definingClass.endsWith("/RegionSamplingHelper;") })
        val services = classes("GESTURE_BAR_SERVICES_JAR")
        val fade = services.getValue("Lcom/android/server/wm/NavBarFadeAnimationController;")
        val ctor = fade.methods.single { it.name == "<init>" }
        assertTrue(ctor.implementation!!.instructions.any {
            (it as? org.jf.dexlib2.iface.instruction.WideLiteralInstruction)?.wideLiteral == 133L
        })
        assertTrue(ctor.fields().any { it.name == "FADE_OUT_INTERPOLATOR" })
        val init = fade.methods.single { it.name == "<clinit>" }
        assertTrue(init.calls().any { it.name == "<init>" && it.definingClass == "Landroid/view/animation/PathInterpolator;" })
    }

    @Test fun `native layout refresh rebuilds rotated insets and can adopt preexisting controllers`() {
        val classes = classes("GESTURE_BAR_SYSTEMUI_APK")
        val bar = classes.getValue("Lcom/android/systemui/navigationbar/views/NavigationBar;")
        val update = bar.methods.single { it.name == "updateViewLayout" && it.parameterTypes.map(CharSequence::toString) == listOf("I") }
        assertEquals("V", update.returnType)
        assertTrue(update.calls().any { it.name == "getBarLayoutParamsForRotation" && it.definingClass == bar.type })
        assertTrue(update.calls().any { it.name == "updateViewLayout" && it.definingClass == "Landroid/view/WindowManager;" })
        val layout = bar.methods.single { it.name == "getBarLayoutParamsForRotation" }
        assertTrue(layout.calls().any { it.definingClass == "Landroid/view/InsetsFrameProvider;" && it.name == "setInsetsSize" })
        val controller = classes.getValue("Lcom/android/systemui/navigationbar/NavigationBarControllerImpl;")
        assertEquals("Landroid/util/SparseArray;", controller.fields.single { it.name == "mNavigationBars" }.type)
        val component = classes.getValue("Lcom/android/systemui/dagger/DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl;")
        assertEquals("Ldagger/internal/Provider;", component.fields.single { it.name == "navigationBarControllerImplProvider" }.type)
    }

    @Test fun `native transition consults live attach gate and retains platform restoration`() {
        val classes = classes("GESTURE_BAR_SERVICES_JAR")
        val policy = classes.getValue("Lcom/android/server/wm/DisplayPolicy;")
        assertEquals("Z", policy.methods.single { it.name == "shouldAttachNavBarToAppDuringTransition" && it.parameterTypes.isEmpty() }.returnType)
        assertEquals("Lcom/android/server/wm/WindowState;", policy.fields.single { it.name == "mNavigationBar" }.type)
        assertEquals("Landroid/content/res/Resources;", policy.methods.single { it.name == "getCurrentUserResources" && it.parameterTypes.isEmpty() }.returnType)
        val config = policy.methods.single { it.name == "onConfigurationChanged" && it.parameterTypes.isEmpty() }
        assertTrue(config.calls().any { it.name == "enableTinyTaskbar" && it.definingClass == "Lcom/android/wm/shell/Flags;" })
        val transition = classes.getValue("Lcom/android/server/wm/Transition;")
        val start = transition.methods.single { it.name == "handleLegacyRecentsStartBehavior" }
        assertEquals(listOf("Lcom/android/server/wm/DisplayContent;", "Landroid/window/TransitionInfo;"), start.parameterTypes.map(CharSequence::toString))
        assertTrue(start.calls().any { it.definingClass == policy.type && it.name == "shouldAttachNavBarToAppDuringTransition" })
        assertTrue(transition.methods.any { it.name == "legacyRestoreNavigationBarFromApp" })
    }

    @Test fun `native handles draw the pill and SystemUI can receive paired overview notifications`() {
        val classes = classes("GESTURE_BAR_SYSTEMUI_APK")
        for (name in listOf("NavigationHandle", "QuickswitchOrientedNavHandle")) {
            val draw = classes.getValue("Lcom/android/systemui/navigationbar/gestural/$name;").methods.single {
                it.name == "onDraw" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/graphics/Canvas;")
            }
            assertEquals("V", draw.returnType)
            assertTrue(draw.calls().any { it.definingClass == "Landroid/graphics/Canvas;" && it.name == "drawRoundRect" })
        }
        val notifications = classes.values.filter { it.type.startsWith("Lcom/android/systemui/LauncherProxyService") }
            .flatMap { it.methods.toList() }.filter { it.implementation != null }.flatMap { it.calls() }
        for (name in listOf("onOverviewShown", "onOverviewHidden")) {
            assertTrue(notifications.any { it.definingClass == "Lcom/android/wm/shell/sysui/ShellInterface;" &&
                it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf("I") })
        }
        val disconnect = classes.getValue("Lcom/android/systemui/LauncherProxyService;").methods.single {
            it.name == "disconnectFromLauncherService" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
        }
        assertEquals("V", disconnect.returnType)
    }

    @Test fun `replacement can recover the existing shell visibility map from the component`() {
        val systemUi = classes("GESTURE_BAR_SYSTEMUI_APK")
        val component = systemUi.getValue("Lcom/android/systemui/dagger/DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl;")
        assertEquals("Lcom/android/wm/shell/sysui/ShellInterface;", component.fields.single { it.name == "setShell" }.type)
        val shell = classes("GESTURE_BAR_SHELL_JAR")
        val wrapper = shell.getValue("Lcom/android/wm/shell/sysui/ShellController\$ShellInterfaceImpl;")
        assertEquals("Lcom/android/wm/shell/sysui/ShellController;", wrapper.fields.single { it.name == "this\$0" }.type)
        val controller = shell.getValue("Lcom/android/wm/shell/sysui/ShellController;")
        val query = controller.methods.single { it.name == "isOverviewVisible" && it.parameterTypes.map(CharSequence::toString) == listOf("I") }
        assertEquals("Z", query.returnType)
        assertTrue(query.calls().any { it.definingClass == "Landroid/util/SparseBooleanArray;" && it.name == "get" })
    }

    @Test fun `transition finished reaches SystemUI on the main thread after the native restore`() {
        val classes = classes("GESTURE_BAR_SYSTEMUI_APK")
        val callback = classes.getValue("Lcom/android/systemui/statusbar/phone/CentralSurfacesCommandQueueCallbacks;")
            .methods.single { it.name == "appTransitionFinished" }
        assertEquals(listOf("I"), callback.parameterTypes.map(CharSequence::toString))
        val dispatch = classes.getValue("Lcom/android/systemui/statusbar/CommandQueue;")
            .methods.single { it.name == "appTransitionFinished" }
        assertTrue(dispatch.calls().any { it.definingClass == "Landroid/os/Handler;" && it.name == "obtainMessage" })
        // The framework posts this notification from the legacy listener, i.e. after
        // legacyRestoreNavigationBarFromApp has already reparented the token back to the display.
        val services = classes("GESTURE_BAR_SERVICES_JAR")
        val listener = services.getValue("Lcom/android/server/wm/DisplayPolicy\$2;")
        val finished = listener.methods.single { it.name == "onAppTransitionFinishedLocked" }
        assertTrue(finished.calls().any { it.definingClass == "Landroid/os/Handler;" && it.name == "post" })
        val transition = services.getValue("Lcom/android/server/wm/Transition;")
        val calls = transition.methods.single { it.name == "finishTransition" }.calls()
        val notify = calls.indexOfFirst { it.name == "dispatchLegacyAppTransitionFinished" }
        val restore = calls.indexOfFirst { it.name == "legacyRestoreNavigationBarFromApp" }
        assertTrue(notify >= 0 && restore > notify)
    }

    @Test fun `shell records both overview directions before notifying its listeners`() {
        val shell = classes("GESTURE_BAR_SHELL_JAR").getValue("Lcom/android/wm/shell/sysui/ShellController;")
        for (name in listOf("onOverviewShown", "onOverviewHidden")) {
            val method = shell.methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf("I") }
            assertEquals("V", method.returnType)
            val calls = method.calls()
            val update = calls.indexOfFirst { it.definingClass == shell.type && it.name == "updateOverviewVisibility" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I", "Z") && it.returnType == "Z" }
            val notify = calls.indexOfFirst { it.definingClass == "Lcom/android/wm/shell/sysui/OverviewVisibilityChangeListener;" && it.name == name }
            assertTrue(update >= 0 && notify > update)
        }
    }
}
