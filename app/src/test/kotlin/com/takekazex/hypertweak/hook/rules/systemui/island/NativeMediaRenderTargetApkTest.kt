package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeMediaRenderTargetApkTest {
    @Test fun `native media Big renderer owns return geometry frame dispatch and completion`() {
        val apk = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(apk?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val root = "Lmiui/systemui/dynamicisland/"
        val content = root + "window/content/DynamicIslandContentView;"
        val base = root + "window/content/DynamicIslandBaseContentView;"
        val fake = root + "window/content/DynamicIslandContentFakeView;"
        val delegate = root + "anim/DynamicIslandAnimationDelegate;"
        fun method(owner: String, name: String, vararg args: String): Method =
            generateSequence(classes.getValue(owner)) { classes[it.superclass] }.flatMap { it.methods.asSequence() }
                .first { it.name == name && it.parameterTypes.map(CharSequence::toString) == args.toList() }
        fun calls(method: Method) = method.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }
        val animator = method(delegate, "getFakeViewAnimator").returnType
        val helper = classes.getValue(animator).methods.single { it.name.startsWith("getHelper") && it.parameterTypes.isEmpty() }.returnType
        val bigReturn = method(delegate, "appExpandedToBigIslandAnimation", content, "Z")
        val smallReturn = method(delegate, "appExpandedToSmallIslandAnimation", content, "Z")
        assertEquals("V", bigReturn.returnType)
        assertEquals("V", smallReturn.returnType)
        assertTrue(calls(bigReturn).any { it.definingClass == animator && it.name == "fakeViewToBigIsland" })
        val bigFake = method(animator, "fakeViewToBigIsland", content, "Z")
        assertTrue(calls(bigFake).any { it.name.startsWith("getCurrentBigIslandX") })
        assertTrue(calls(bigFake).any { it.name.startsWith("getCurrentBigIslandWidth") })
        method(animator, "fakeViewToSmallIsland", content, "Z")
        for (owner in listOf(animator, helper))
            method(owner, "applyAppAnimByState", content, "Z", "F", "F", "F")
        val frameCalls = calls(method(helper, "applyAppAnimByState", content, "Z", "F", "F", "F"))
        assertTrue(frameCalls.any { it.name == "getState" })
        assertTrue(frameCalls.any { it.name == "applyAppAnimBigIsland" })
        method(animator, "updateFakeViewStateForAppAnim", "I", "I", "I", "I", content, "Z", "Z")
        method(fake, "onStateChanged", content)
        method(fake, "updateViewStateWhenCloseEnd", "Z", "Ljava/lang/String;")
        method(fake, "setVisibility", "I")
        val sync = calls(method(fake, "syncIslandLottieProgress", "Z"))
        assertTrue(sync.any { it.name == "getState" })
        assertTrue(sync.any { it.name.startsWith("islandShift") })
        val close = calls(method(content, "updateViewStateWhenCloseEnd"))
        assertTrue(close.any { it.name == "getState" })
        assertTrue(close.any { it.name == "getBigIslandView" })
        for (name in listOf("getState", "getLastState")) method(base, name)
        for (name in listOf("getCurrentBigIslandX", "getCurrentBigIslandWidth", "getBigIslandRect")) {
            val bridge = method(base, name + "\$default", base, "Ljava/lang/Boolean;", "I", "Ljava/lang/Object;")
            assertTrue(calls(bridge).any { it.name == name })
        }
        for (owner in listOf(delegate, root + "anim/DynamicIslandAnimationController;"))
            method(owner, "appExpandedToSmallIslandAnimation", content)
        method(delegate, "appExpandedToBigIslandAnimation", content)
        val window = root + "window/DynamicIslandWindowView;"
        val controller = method(window, "getWindowViewController").returnType
        val state = root + "event/DynamicIslandState;"
        assertEquals(1, classes.getValue(controller).methods.count { it.returnType == "Landroid/graphics/Rect;" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(state, content, "Ljava/lang/String;", "Z") })
    }
}
