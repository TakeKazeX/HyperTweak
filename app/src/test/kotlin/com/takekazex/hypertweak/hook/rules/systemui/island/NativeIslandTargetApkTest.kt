package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Validate against both APKs: the plugin API also depends on classes supplied by SystemUI. */
class NativeIslandTargetApkTest {
    @Test fun `native measurement owns timer premeasure and real fake camera publication`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val root = "Lmiui/systemui/dynamicisland/"
        val content = classes.getValue(root + "window/content/DynamicIslandContentView;")
        val base = classes.getValue(content.superclass!!)
        val fake = classes.getValue(root + "window/content/DynamicIslandContentFakeView;")
        fun calls(method: org.jf.dexlib2.iface.Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        val measurement = content.methods.single { it.name == "calculateBigIslandWidth" }
        val premeasure = content.methods.single { it.name == "applyPreMeasureMode" }
        assertEquals(listOf("Landroid/view/ViewGroup;", "Z"), premeasure.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", premeasure.returnType)
        assertTrue(calls(premeasure).any { it.name == "enablePreMeasureMode" && it.parameterTypes == listOf("Z") })
        val measurementCalls = calls(measurement)
        val modeCalls = measurementCalls.withIndex().filter { it.value.name == premeasure.name }
        assertEquals("native measurement enters and exits premeasure", 2, modeCalls.size)
        assertTrue(measurementCalls.withIndex().any { it.value.name == "measure" &&
            it.index > modeCalls.first().index && it.index < modeCalls.last().index })
        assertTrue(measurementCalls.withIndex().any { it.value.name == "calculateBigIslandWidth" &&
            it.value.definingClass != content.type && it.index > modeCalls.last().index })
        // Hooking this inherited getter must cover the fake callback owner as well.
        assertEquals(base.type, fake.superclass)
        assertTrue(base.methods.any { it.name == "getFixedCutoutWidth" && it.returnType == "I" })
        assertFalse(fake.methods.any { it.name == "getFixedCutoutWidth" })
        val callback = classes.values.flatMap { it.methods.toList() }.filter { it.name == "onBegin" }
            .single { method -> calls(method).any { it.name == "updateCutoutWidth" } &&
                calls(method).any { it.name == "getFixedCutoutWidth" } }
        val callbackCalls = calls(callback)
        assertTrue(callbackCalls.indexOfFirst { it.name == "getFixedCutoutWidth" } <
            callbackCalls.indexOfFirst { it.name == "updateCutoutWidth" })
    }

    @Test fun `live whitelist gates preserve verified host signatures`() {
        val paths = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK", "XMSF_AUTH_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        val classes = paths.filterNotNull().flatMap { path ->
            val container = DexFileFactory.loadDexContainer(path, Opcodes.getDefault())
            container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        val manager = classes.getValue("Lcom/miui/systemui/notification/NotificationSettingsManager;")
        for (name in listOf("canShowFocusState", "canShowFocusStateApp")) {
            val method = manager.methods.single { it.name == name && it.parameterTypes.size == 2 }
            assertEquals(listOf("Landroid/content/Context;", "Ljava/lang/String;"), method.parameterTypes.map(CharSequence::toString))
            assertEquals("I", method.returnType)
        }
        val media = classes.getValue("Lmiui/systemui/notification/NotificationSettingsManager;")
            .methods.single { it.name == "mediaIslandSupportMiniWindow" }
        assertEquals(listOf("Ljava/lang/String;"), media.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", media.returnType)
        val session = classes.getValue("Lcom/xiaomi/xms/auth/AuthSession;")
        val publicFinal = session.methods.filter { it.accessFlags and 0x11 == 0x11 && it.returnType == "Landroid/os/Bundle;" }
        val error = publicFinal.single { it.parameterTypes.map(CharSequence::toString) == listOf("Lcom/xiaomi/xms/auth/AuthError;") }
        val success = publicFinal.single { it.parameterTypes.isEmpty() }
        fun strings(method: org.jf.dexlib2.iface.Method) = method.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.toSet()
        assertTrue("result_code" in strings(error))
        assertTrue("Auth is successful" in strings(success))
        assertEquals(1, classes.getValue("Lcom/xiaomi/xms/auth/AuthError;").fields.count { it.type == "I" })
    }

    @Test fun `semantic discovery has unique entries connected to the native render and scheduling graph`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val methods = classes.values.flatMap { it.methods.toList() }
        fun javaType(type: String): String = when (type) {
            "V" -> "void"; "Z" -> "boolean"; "F" -> "float"; "I" -> "int"
            else -> if (type.startsWith("[")) type.replace('/', '.') else type.removePrefix("L").removeSuffix(";").replace('/', '.')
        }
        val entries = NativeIslandAnchors.strings.mapValues { (role, anchors) ->
            val candidates = methods.filter { method ->
                val strings = (method.implementation?.instructions ?: emptyList()).mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }.toList()
                anchors.all { anchor -> strings.any { anchor in it } }
            }
            NativeIslandAnchors.select(role, candidates,
                { it.parameterTypes.map { type -> javaType(type.toString()) } }, { javaType(it.returnType) })
        }
        val tween = entries.getValue("widthTween")
        assertTrue(classes.getValue(tween.definingClass).methods.any { it.name == "setTo" &&
            it.parameterTypes == tween.parameterTypes && it.returnType == tween.returnType })
        val transaction = entries.getValue("transaction")
        val animation = entries.getValue("animation")
        val content = transaction.parameterTypes[1].toString()
        assertEquals(content, entries.getValue("contentMeasure").definingClass)
        assertEquals(listOf(content), animation.parameterTypes.map(CharSequence::toString))
        val transitions = entries.getValue("transitions")
        val calls = animation.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        assertTrue(calls.any { it.definingClass == transitions.definingClass && it.name == transitions.name &&
            it.parameterTypes == transitions.parameterTypes && it.returnType == transitions.returnType })
        val coordinator = classes.getValue(transaction.definingClass)
        assertEquals(1, coordinator.fields.count { it.type == "Ljava/util/ArrayList;" })
        val gesture = classes.getValue("Lmiui/systemui/dynamicisland/anim/ui/animator/IslandGestureAnimator;")
        assertEquals(1, gesture.fields.count { it.type == classes.getValue(content).superclass })
        val press = gesture.methods.single { it.name == "onPressBigIsland" }
        val reset = gesture.methods.single { it.name == "resetPressBigIsland" }
        assertEquals(listOf(content), press.parameterTypes.map(CharSequence::toString))
        assertEquals(listOf(content), reset.parameterTypes.map(CharSequence::toString))
        val full = classes.getValue("Lmiui/systemui/dynamicisland/anim/ui/animator/IslandAnimStateFactory;")
            .methods.single { it.name == "createBigIslandProperties" }
        assertTrue(full.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.any { it.name == "getBigIslandStart" })
    }

    @Test fun `verified host exposes the complete scheduling measurement and touch contract`() {
        val paths = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        val classes = paths.filterNotNull().flatMap { path ->
            val container = DexFileFactory.loadDexContainer(path, Opcodes.getDefault())
            container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        fun type(name: String) = "L${name.replace('.', '/')};"
        fun hierarchy(name: String): Sequence<ClassDef> = generateSequence(classes.getValue(type(name))) {
            classes[it.superclass]
        }
        NativeIslandContract.methods.forEach { (name, members) ->
            members.forEach { member ->
                val targets = hierarchy(name).flatMap { it.methods.asSequence() }.filter { it.name == member }
                    .distinctBy { it.parameterTypes.map(CharSequence::toString) }.toList()
                assertEquals("$name.$member must resolve unambiguously", 1, targets.size)
            }
        }
        val root = "miui.systemui.dynamicisland."
        listOf("BigIsland", "SmallIsland", "Hidden").forEach { state ->
            assertTrue(classes.getValue(type(root + "event.DynamicIslandState\$$state")).methods.any {
                it.name == "<init>" && it.parameterTypes.isEmpty()
            })
        }
        listOf("SwipeLeft", "SwipeRight").forEach { event ->
            assertTrue(type(root + "event.DynamicIslandEvent\$$event") in classes)
        }
        val swipe = classes.getValue(type(root + "event.SwipeEventCoordinator"))
        mapOf(
            "handleAppEvent" to listOf(type(root + "event.DynamicIslandEvent"), type(root + "window.content.DynamicIslandContentView"), "Ljava/util/ArrayList;"),
            "handleSwipe" to listOf("F", "F", "Z", "Ljava/util/ArrayList;"),
            "resetSwipe" to listOf("F", "F", "Ljava/util/ArrayList;")
        ).forEach { (name, parameters) ->
            assertEquals(parameters, swipe.methods.single { it.name == name }.parameterTypes.map(CharSequence::toString))
        }
        val window = classes.getValue(type(root + "window.DynamicIslandWindowView"))
        assertEquals(listOf("F"), window.methods.single { it.name == "isSwipeTowardsSmallIsland" }.parameterTypes.map(CharSequence::toString))
        val hidden = classes.getValue(type(root + "event.handler.HiddenStateHandler"))
        assertEquals(listOf(type(root + "window.content.DynamicIslandContentView"), "Ljava/lang/Boolean;"),
            hidden.methods.single { it.name == "enqueueHiddenStateCallback" }.parameterTypes.map(CharSequence::toString))
        mapOf(
            "event.DynamicIslandEventCoordinator" to listOf("hiddenList"),
            "event.handler.StateHandler" to listOf("newList"),
            "anim.ui.animator.IslandCoordinateCalculator" to listOf("view"),
            "touch.domain.interactor.DynamicIslandTouchInteractor" to listOf("windowView")
        ).forEach { (name, fields) ->
            val declared = classes.getValue(type(root + name)).fields.map { it.name }
            fields.forEach { assertTrue("$name.$it", it in declared) }
        }
        val handler = classes.getValue(type(root + "event.handler.StateHandler"))
        val stop = handler.methods.single { it.name == "stop" }
        val calls = stop.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        assertTrue(calls.any { it.name == "updateState" && it.parameterTypes == listOf(type(root + "window.content.DynamicIslandContentView")) })
        val coordinator = classes.getValue(type(root + "event.DynamicIslandEventCoordinator"))
        val handle = coordinator.methods.single { it.name == "handleEvent" }
        assertEquals(listOf(type(root + "event.DynamicIslandEvent"), type(root + "window.content.DynamicIslandContentView")),
            handle.parameterTypes.map(CharSequence::toString))
        val result = classes.getValue(type(root + "model.IslandContentViewCalculationResult"))
        assertTrue(result.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == List(9) { "I" } })
        // stop() publishes the state synchronously; a changed asynchronous protocol needs rework.
        assertEquals("V", stop.returnType)
    }
}
