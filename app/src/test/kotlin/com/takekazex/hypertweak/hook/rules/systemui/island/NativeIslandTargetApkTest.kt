package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Validate against both APKs: the plugin API also depends on classes supplied by SystemUI. */
class NativeIslandTargetApkTest {
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
