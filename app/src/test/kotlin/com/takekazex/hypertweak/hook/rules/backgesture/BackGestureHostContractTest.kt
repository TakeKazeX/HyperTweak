package com.takekazex.hypertweak.hook.rules.backgesture

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Validate the adapter's actual host ABI, independent of module compilation. */
class BackGestureHostContractTest {
    @Test fun nativeContextualSearchUsesLiveNavigationOwnerAndIndependentTransientState() {
        val host = classes("BACK_GESTURE_SYSTEMUI_APK")
        val bar = host.getValue("Lcom/android/systemui/navigationbar/views/NavigationBar;")
        assertEquals("I", bar.fields.single { it.name == "mNavigationBarWindowState" }.type)
        assertEquals("Z", bar.fields.single { it.name == "mTransientShown" }.type)
        fun fieldWrites(method: String) = bar.methods.single { it.name == method }
            .implementation!!.instructions
            .filterIsInstance<org.jf.dexlib2.iface.instruction.ReferenceInstruction>()
            .filter { it.opcode.toString().startsWith("IPUT") }
            .mapNotNull { (it.reference as? org.jf.dexlib2.iface.reference.FieldReference)?.name }
        assertTrue(fieldWrites("setWindowState").contains("mNavigationBarWindowState"))
        val transientWrites = fieldWrites("showTransient")
        assertTrue(transientWrites.contains("mTransientShown"))
        assertFalse(transientWrites.contains("mNavigationBarWindowState"))
        val dependency = host.getValue("Lcom/android/systemui/Dependency;")
        assertTrue(dependency.fields.any { it.name == "sDependency" })
        assertTrue(dependency.fields.any { it.name == "mNavigationBarController" })
        val controller = host.getValue("Lcom/android/systemui/navigationbar/NavigationBarControllerImpl;")
        assertEquals("Landroid/util/SparseArray;", controller.fields.single { it.name == "mNavigationBars" }.type)
        val ancestors = generateSequence(bar) { it.superclass?.let(host::get) }
        assertTrue(ancestors.any { cls -> cls.fields.any { it.name == "mView" } })
    }

    @Test fun upstreamRemovalCanRecoverAnExistingHandlerWithoutConstructorReplay() {
        val host = classes("BACK_GESTURE_SYSTEMUI_APK")
        val dependency = host.getValue("Lcom/android/systemui/Dependency;")
        assertTrue(dependency.fields.any { it.name == "sDependency" })
        assertTrue(dependency.fields.any { it.name == "mNavigationBarController" })
        val controller = host.getValue("Lcom/android/systemui/navigationbar/NavigationBarControllerImpl;")
        val helper = host.getValue(controller.fields.single { it.name == "mNavBarHelper" }.type)
        val handler = host.getValue(helper.fields.single { it.name == "mEdgeBackGestureHandler" }.type)
        val owners = generateSequence(handler) { it.superclass?.let(host::get) }.toList()
        val animation = owners.flatMap { it.fields }.single { it.name == "mBackAnimation" }
        assertTrue(animation.type.startsWith("L"))
        assertTrue(owners.flatMap { it.methods }.any { it.name == "setBackAnimation" && it.parameterTypes.size == 1 })
        assertTrue(controller.fields.any { it.name == "mNavigationBars" && it.type == "Landroid/util/SparseArray;" })
    }

    private fun classes(variable: String): Map<String, ClassDef> {
        val file = System.getenv(variable)?.let(::File)
        assumeTrue("Set $variable to run target bytecode validation", file?.isFile == true)
        val container = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            .associateBy { it.type }
    }

    @Test fun `Shell has a received-null fallback before dispatching predictive threshold progress`() {
        val shell = classes("BACK_GESTURE_SHELL_JAR")
            .getValue("Lcom/android/wm/shell/back/BackAnimationController;")
        assertEquals("Z", shell.fields.single { it.name == "mReceivedNullNavigationInfo" }.type)
        assertEquals("Landroid/window/BackNavigationInfo;",
            shell.fields.single { it.name == "mBackNavigationInfo" }.type)
        val threshold = shell.methods.single { it.name == "onThresholdCrossed" && it.parameterTypes.isEmpty() }
        val refs = threshold.implementation!!.instructions
            .filterIsInstance<org.jf.dexlib2.iface.instruction.ReferenceInstruction>().map { it.reference }.toList()
        val nullFlag = refs.indexOfFirst {
            (it as? org.jf.dexlib2.iface.reference.FieldReference)?.name == "mReceivedNullNavigationInfo"
        }
        val pilfer = refs.indexOfFirst {
            (it as? org.jf.dexlib2.iface.reference.MethodReference)?.name == "tryPilferPointers"
        }
        val predictive = refs.indexOfFirst {
            (it as? org.jf.dexlib2.iface.reference.MethodReference)?.name == "startPredictiveBackAnimationIfNeeded"
        }
        assertTrue("Native null fallback must precede predictive dispatch", nullFlag >= 0 && pilfer > nullFlag && predictive > pilfer)
    }

    @Test fun `SystemUI supplies per-display native panels and Android 17 Shell back ABI`() {
        val classes = classes("BACK_GESTURE_SYSTEMUI_APK")
        val edge = classes.getValue("Lcom/android/systemui/navigationbar/gestural/EdgeBackGestureHandler;")
        val fields = edge.fields.map { it.name }.toSet()
        assertTrue(fields.containsAll(setOf("mDisplayBackGestureHandlerFactory", "mDisplayBackGestureHandlers", "mBackCallback", "mBackAnimation")))
        assertFalse(fields.contains("mEdgeBackPlugin"))
        val display = classes.getValue("Lcom/android/systemui/navigationbar/gestural/DisplayBackGestureHandlerImpl;")
        assertEquals("Lcom/android/systemui/navigationbar/gestural/BackPanelController;", display.fields.single { it.name == "edgeBackPlugin" }.type)
        assertTrue(edge.methods.any { it.name == "setBackAnimation" && it.parameterTypes.map(CharSequence::toString) == listOf("Lcom/android/wm/shell/back/BackAnimation;") })
        // HyperOS exposes Shell through the manifest's com.miui.wm.shell shared library.
        val shellClasses = classes("BACK_GESTURE_SHELL_JAR")
        val shell = shellClasses.getValue("Lcom/android/wm/shell/back/BackAnimationController;")
        assertTrue(shell.methods.any { it.name == "sendBackEvent" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I") })
        assertTrue(shell.methods.any { it.name == "injectBackKey" && it.parameterTypes.map(CharSequence::toString) == listOf("I") })
    }

    @Test fun `current Shell exposes multipart leaves and the native Recents idle boundary`() {
        val shell = classes("BACK_GESTURE_SHELL_JAR")
        val window = shell.getValue("Lcom/android/wm/shell/transition/WindowAnimation;")
        val multipart = shell.getValue("Lcom/android/wm/shell/transition/MultiPartWindowAnimation;")
        assertEquals(window.type, multipart.superclass)
        assertEquals("Ljava/util/List;", multipart.fields.single { it.name == "mAnimators" }.type)
        assertEquals("Landroid/animation/ValueAnimator;", window.fields.single { it.name == "mAnimator" }.type)
        val transitions = shell.getValue("Lcom/android/wm/shell/transition/Transitions;")
        assertTrue(transitions.methods.any { it.name == "runOnIdle" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Runnable;") })
        assertTrue(transitions.methods.any { it.name == "getRecentsTransitionHandler" && it.parameterTypes.isEmpty() })
        val recents = shell.getValue("Lcom/android/wm/shell/recents/RecentsTransitionHandler;")
        assertEquals("Ljava/util/concurrent/CopyOnWriteArrayList;", recents.fields.single { it.name == "mControllers" }.type)
        val controller = shell.getValue("Lcom/android/wm/shell/recents/RecentsTransitionHandler\$RecentsController;")
        assertEquals("Landroid/window/TransitionInfo;", controller.fields.single { it.name == "mInfo" }.type)
        assertTrue(controller.fields.any { it.name == "mRemoteToRecentType" })
        assertTrue(controller.fields.any { it.name == "mFinishCB" })
        val back = shell.getValue("Lcom/android/wm/shell/back/BackAnimationController;")
        assertTrue(back.methods.any { it.name == "startBackNavigation" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/window/BackTouchTracker;") })
        assertTrue(back.methods.any { it.name == "startPredictiveBackAnimationIfNeeded" && it.parameterTypes.isEmpty() })
    }

    @Test fun `services preserves native predictive preparation and six-argument transition calculation`() {
        val classes = classes("BACK_GESTURE_SERVICES_JAR")
        val transition = classes.getValue("Lcom/android/server/wm/Transition;")
        assertTrue(transition.methods.any {
            it.name == "calculateTransitionInfo" && it.returnType == "Landroid/window/TransitionInfo;" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I", "I", "Ljava/util/ArrayList;", "Landroid/view/SurfaceControl\$Transaction;", "I", "Lcom/android/server/wm/TransitionStub;")
        })
        val builder = classes.getValue("Lcom/android/server/wm/BackNavigationController\$AnimationHandler\$ScheduleAnimationBuilder;")
        for (name in listOf("prepareTransitionIfNeeded", "applyPreviewStrategy")) {
            val method = builder.methods.single { it.name == name }
            assertTrue("$name must retain its native implementation", method.implementation!!.instructions.count() > 10)
        }
        val task = classes.getValue("Lcom/android/server/wm/Task;")
        val root = task.methods.single { it.name == "getRootActivity" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "Z") }
        assertTrue("Task root lookup must retain the native helper call", root.implementation!!.instructions.any { it.opcode.toString().startsWith("INVOKE_") })
    }
    @Test fun `wallpaper hooks the terminal cropped background overload used by fullscreen calls`() {
        val shell = classes("BACK_GESTURE_SHELL_JAR")
        val background = shell.getValue("Lcom/android/wm/shell/back/BackAnimationBackground;")
        val terminal = background.methods.single {
            it.name == "ensureBackground" && it.parameterTypes.size == 8
        }
        assertEquals(listOf("Landroid/graphics/Rect;", "I", "Landroid/view/SurfaceControl\$Transaction;",
            "I", "Landroid/graphics/Rect;", "[F", "I", "Landroid/view/SurfaceControl;"),
            terminal.parameterTypes.map(CharSequence::toString))
        val wrapper = background.methods.single { it.name == "ensureBackground" && it.parameterTypes.size == 5 }
        assertTrue(wrapper.implementation!!.instructions.filterIsInstance<org.jf.dexlib2.iface.instruction.ReferenceInstruction>().any {
            val ref = it.reference as? org.jf.dexlib2.iface.reference.MethodReference
            ref?.definingClass == background.type && ref.name == "ensureBackground" && ref.parameterTypes.size == 8
        })
        assertEquals("Landroid/view/SurfaceControl;", background.fields.single { it.name == "mBackgroundSurface" }.type)
        assertTrue(background.methods.any { it.name == "removeBackground" && it.parameterTypes.size == 1 })
    }

    @Test fun `slide runner can bypass customized activity selection on the verified Shell`() {
        val shell = classes("BACK_GESTURE_SHELL_JAR")
        val registry = shell.getValue("Lcom/android/wm/shell/back/ShellBackAnimationRegistry;")
        assertEquals("Landroid/util/SparseArray;", registry.fields.single { it.name == "mAnimationDefinition" }.type)
        assertTrue(registry.fields.any { it.name == "mDefaultCrossActivityAnimation" })
        assertTrue(registry.fields.any { it.name == "mCustomizeActivityAnimation" })
        assertTrue(registry.methods.any { it.name == "getAnimationRunnerAndInit" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/window/BackNavigationInfo;") })
        val animation = shell.getValue("Lcom/android/wm/shell/back/CrossActivityBackAnimation;")
        val callback = shell.getValue("Lcom/android/wm/shell/back/CrossActivityBackAnimation\$Callback;")
        assertEquals(animation.type, callback.fields.single { it.name == "this\$0" }.type)
        val start = callback.methods.single { it.name == "onBackStarted" }
        assertEquals(listOf("Landroid/window/BackMotionEvent;"), start.parameterTypes.map(CharSequence::toString))
        val calls = start.implementation!!.instructions
            .filterIsInstance<org.jf.dexlib2.iface.instruction.ReferenceInstruction>()
            .mapNotNull { it.reference as? org.jf.dexlib2.iface.reference.MethodReference }.toList()
        val geometryStart = calls.indexOfFirst { it.definingClass == animation.type && it.name == "startBackAnimation" }
        val progressStart = calls.indexOfFirst { it.definingClass == "Landroid/window/BackProgressAnimator;" && it.name == "onBackStarted" }
        assertTrue("Native geometry must initialize before progress registration", geometryStart >= 0 && progressStart > geometryStart)

        for (name in listOf("startBackAnimation", "onPostCommitProgress", "applyTransaction", "finishAnimation")) {
            assertTrue("Slide boundary missing: $name", animation.methods.any { it.name == name })
        }
        for (name in listOf("startClosingRect", "targetClosingRect", "startEnteringRect", "targetEnteringRect", "progressAnimator")) {
            assertTrue("Slide geometry missing: $name", animation.fields.any { it.name == name })
        }
    }

    @Test fun `HyperOS indicator resources remain present in the installed launcher`() {
        val file = System.getenv("BACK_GESTURE_LAUNCHER_APK")?.let(::File)
        assumeTrue("Set BACK_GESTURE_LAUNCHER_APK for native indicator resource validation", file?.isFile == true)
        java.util.zip.ZipFile(file!!).use { apk ->
            for (name in listOf("gesture_back_arrow.webp", "gesture_back_background.webp")) {
                val entry = apk.getEntry("res/drawable/$name")
                assertNotNull("Native HyperOS skin missing: $name", entry)
                val bytes = apk.getInputStream(entry).use { it.readBytes() }
                assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
                assertEquals("WEBP", String(bytes.copyOfRange(8, 12), Charsets.US_ASCII))
            }
        }
    }

    @Test fun `fullscreen side swipe preserves the native DisplayPolicy callback ownership`() {
        val classes = classes("BACK_GESTURE_SERVICES_JAR")
        val policy = classes.getValue("Lcom/android/server/wm/DisplayPolicy;")
        val callbacks = classes.getValue("Lcom/android/server/wm/DisplayPolicy\$1;")
        assertTrue(callbacks.interfaces.contains("Lcom/android/server/wm/SystemGesturesPointerEventListener\$Callbacks;"))
        assertEquals(policy.type, callbacks.fields.single { it.name == "this\$0" }.type)
        for (name in listOf("onSwipeFromLeft", "onSwipeFromRight")) {
            val method = callbacks.methods.single { it.name == name && it.parameterTypes.isEmpty() }
            assertEquals("V", method.returnType)
            val refs = method.implementation!!.instructions
                .filterIsInstance<org.jf.dexlib2.iface.instruction.ReferenceInstruction>().map { it.reference }.toList()
            assertTrue(refs.filterIsInstance<org.jf.dexlib2.iface.reference.MethodReference>()
                .any { it.name == "requestTransientBars" && it.definingClass == policy.type })
        }
        for (name in listOf("mNavigationBar", "mLeftGestureHost", "mRightGestureHost", "mSystemGestures", "mLock")) {
            assertTrue("Missing native policy field $name", policy.fields.any { it.name == name })
        }
        val gestures = classes.getValue("Lcom/android/server/wm/SystemGesturesPointerEventListener;")
        for (name in listOf("mMouseHoveringAtLeft", "mMouseHoveringAtRight")) {
            assertEquals("Z", gestures.fields.single { it.name == name }.type)
        }
    }

}
