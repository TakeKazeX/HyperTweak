package com.takekazex.hypertweak.hook.rules.backgesture

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.SwitchPayload
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** OTA drift checks for the actual connection, exclusion and native completion boundaries. */
class BackGesture0130HostContractTest {
    private fun classes(key: String): Map<String, ClassDef> {
        val file = System.getenv(key)?.let(::File)
        assumeTrue("Set $key for host bytecode checks", file?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }

    @Test fun connectionNotificationsUseTheSameLiveProxyAsInitialRegistration() {
        val host = classes("BACK_GESTURE_SYSTEMUI_APK")
        val proxy = host.getValue("Lcom/android/systemui/LauncherProxyService;")
        assertEquals("Landroid/os/Handler;", proxy.fields.single { it.name == "mHandler" }.type)
        assertEquals("Landroid/content/ComponentName;", proxy.fields.single { it.name == "mRecentsComponentName" }.type)
        assertTrue(proxy.fields.single { it.name == "mLauncherProxy" }.type.endsWith("ILauncherProxy;"))
        for (name in listOf("notifyConnectionChanged", "addCallback")) {
            val method = proxy.methods.single { it.name == name &&
                (name != "addCallback" || it.parameterTypes.single().toString().endsWith("LauncherProxyListener;")) }
            val invokes = method.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
                .mapNotNull { it.reference as? MethodReference }
            assertTrue(invokes.any { it.name == "onConnectionChanged" && it.parameterTypes.map(CharSequence::toString) == listOf("Z") })
        }
    }

    @Test fun exclusionPublicationUsesTheTwoFreshlyCalculatedRegions() {
        val display = classes("BACK_GESTURE_SERVICES_JAR").getValue("Lcom/android/server/wm/DisplayContent;")
        for (name in listOf("mSystemGestureFrameLeft", "mSystemGestureFrameRight"))
            assertEquals("Landroid/graphics/Rect;", display.fields.single { it.name == name }.type)
        val update = display.methods.single { it.name == "updateSystemGestureExclusion" }
        val invokes = update.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }.toList()
        val calculate = invokes.indexOfFirst { it.name == "calculateSystemGestureExclusion" }
        val publish = invokes.indexOfFirst { it.name == "onSystemGestureExclusionChanged" }
        assertTrue(calculate >= 0 && publish > calculate)
        assertEquals(listOf("Landroid/graphics/Region;", "Landroid/graphics/Region;"), invokes[calculate].parameterTypes.map(CharSequence::toString))
    }

    @Test fun recentsNativeFinishStillOwnsCleanupAndTheFinishReceipt() {
        val host = classes("BACK_GESTURE_SHELL_JAR")
        val controller = host.getValue("Lcom/android/wm/shell/recents/RecentsTransitionHandler\$RecentsController;")
        val finish = controller.methods.single { it.name == "onFinishInner" }
        assertEquals(listOf("Landroid/window/WindowContainerTransaction;"), finish.parameterTypes.map(CharSequence::toString))
        val calls = finish.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }.toList()
        assertTrue(calls.any { it.name == "cleanUp" && it.definingClass == controller.type })
        assertTrue(calls.any { it.name == "onTransitionFinished" && it.definingClass.endsWith("Transitions\$TransitionFinishCallback;") })
        val animation = host.getValue("Lcom/android/wm/shell/back/CrossTaskBackAnimation;")
        for (name in listOf("startBackAnimation", "finishAnimation", "onGestureCommitted"))
            assertTrue(animation.methods.any { it.name == name && it.parameterTypes.isEmpty() })
        assertTrue(animation.methods.any { it.name == "updateGestureBackProgress" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("F", "Landroid/window/BackEvent;") })
    }

    @Test fun nativeSnapshotStateHasAnIsolatedStatusAlphaWriteAndIndependentNavigationWork() {
        val host = classes("BACK_GESTURE_MIUI_SERVICES_JAR")
        val runnable = host.getValue("Lcom/android/server/wm/BarFollowAnimationImpl\$8;")
        val run = runnable.methods.single { it.name == "run" }
        var address = 0
        val instructions = run.implementation!!.instructions.associateBy { instruction ->
            address.also { address += instruction.codeUnits }
        }
        val dispatch = instructions.entries.single { it.value.opcode == Opcode.PACKED_SWITCH }
        val payloadAddress = dispatch.key + (dispatch.value as OffsetInstruction).codeOffset
        val payload = instructions.getValue(payloadAddress) as SwitchPayload
        val destinations = payload.switchElements.associate { it.key to dispatch.key + it.offset }
        fun calls(state: Int): Set<String> {
            val start = destinations.getValue(state)
            val end = destinations.values.filter { it > start }.minOrNull() ?: payloadAddress
            return instructions.filterKeys { it in start until end }.values
                .filterIsInstance<ReferenceInstruction>()
                .mapNotNull { it.reference as? MethodReference }.map { it.name }.toSet()
        }
        assertTrue(calls(6).containsAll(setOf("updateStatusBarTaskSnapshot", "updateNavBarTaskSnapshot")))
        assertTrue(calls(10).containsAll(setOf("updateStatusBarTaskSnapshot", "updateNavBarFinishRecents")))
        val snapshot = host.getValue("Lcom/android/server/wm/MiuiStatusBarController;")
            .methods.single { it.name == "updateStatusBarTaskSnapshot" }
        val body = snapshot.implementation!!.instructions.toList()
        assertFalse(body.any { it is ReferenceInstruction && it.reference is FieldReference &&
            (it.opcode.toString().startsWith("IPUT") || it.opcode.toString().startsWith("SPUT")) })
        assertEquals(1, body.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }.count { it.name == "animateStatusBarTo" })
    }

    @Test fun recentsInitializationUsesTheNativeClassifierAndItsSwipeStateEarlyExit() {
        val host = classes("BACK_GESTURE_MIUI_SERVICES_JAR")
        val classifier = host.getValue("Lcom/android/server/wm/BarFollowAnimationUtils;")
            .methods.single { it.name == "isRecentTransition" }
        assertEquals("Z", classifier.returnType)
        assertEquals(listOf("Landroid/window/TransitionInfo;"), classifier.parameterTypes.map(CharSequence::toString))
        assertTrue(classifier.implementation!!.instructions.any {
            it.opcode == Opcode.AND_INT_LIT16 && (it as NarrowLiteralInstruction).narrowLiteral == 0x80
        })
        val start = host.getValue("Lcom/android/server/wm/MiuiStatusBarController;")
            .methods.single { it.name == "startStatusBarAnimation" }
        assertEquals(listOf("Landroid/window/TransitionInfo;", "Landroid/view/IBarFollowExecuteCallBack;"),
            start.parameterTypes.map(CharSequence::toString))
        val calls = start.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }.map { it.name }.toSet()
        assertTrue(calls.containsAll(setOf("isRecentTransition", "isOnSwipeUping", "animateStatusBarTo")))
    }

    @Test fun recentsTaskParentsAreQueuedUntilTheNativeFolmeFrameCommits() {
        val host = classes("BACK_GESTURE_MIUI_SERVICES_JAR")
        val controller = host.getValue("Lcom/android/server/wm/MiuiStatusBarController;")
        assertEquals("Landroid/view/SurfaceControl\$Transaction;", controller.fields.single { it.name == "mStatusBarT" }.type)
        val start = controller.methods.single { it.name == "startStatusBarAnimation" }
        val calls = start.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }.toList()
        assertTrue(calls.any { it.definingClass == "Landroid/view/SurfaceControl\$Transaction;" && it.name == "reparent" })
        assertFalse(calls.any { it.definingClass == "Landroid/view/SurfaceControl\$Transaction;" && it.name == "apply" })
        val frame = host.getValue("Lcom/android/server/wm/MiuiStatusBarController\$1;")
            .methods.single { it.name == "onUpdate" }
        assertTrue(frame.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }
            .any { it.definingClass == "Landroid/view/SurfaceControl\$Transaction;" && it.name == "apply" })
    }
}
