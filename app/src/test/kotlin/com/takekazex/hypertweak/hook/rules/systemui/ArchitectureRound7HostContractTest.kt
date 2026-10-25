package com.takekazex.hypertweak.hook.rules.systemui

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Checks the actual hosts whose access was moved in round 7; never infers compatibility from compilation. */
class ArchitectureRound7HostContractTest {
    private fun classes(variable: String): Map<String, ClassDef> {
        val file = System.getenv(variable)?.let(::File)
        assumeTrue("Set $variable to the verified target APK", file?.isFile == true)
        val container = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }

    @Test fun aodCallbackReleaseHasTheExpectedTypedAndBridgeContracts() {
        val controller = classes("GESTURE_BAR_SYSTEMUI_APK")
            .getValue("Lcom/android/systemui/statusbar/phone/ConfigurationControllerImpl;")
        val releases = controller.methods.filter { it.name == "removeCallback" }
        val typed = releases.single { it.parameterTypes.map { p -> p.toString() } ==
            listOf("Lcom/android/systemui/statusbar/policy/ConfigurationController\$ConfigurationListener;") }
        assertEquals("V", typed.returnType)
        assertEquals(0, typed.accessFlags and (8 or 64 or 4096))
        val bridge = releases.single { it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/Object;") }
        assertEquals(64 or 4096, bridge.accessFlags and (64 or 4096))
    }

    @Test fun headerBaselineAndOptionalCallbacksKeepTheirExecutingContracts() {
        val classes = classes("GESTURE_BAR_SYSTEMUI_APK")
        val controller = classes.getValue("Lcom/android/systemui/controlcenter/shade/NotificationHeaderExpandController;")
        for (name in listOf("bigTimeSize", "shadeHeaderNotificationClockTextSize", "qsControlHeaderClockFlipSize")) {
            assertEquals(name, "I", controller.fields.single { it.name == name }.type)
        }
        assertEquals("Landroid/content/Context;", controller.fields.single { it.name == "context" }.type)
        val contracts = mapOf(
            "NotificationHeaderExpandController\$notificationCallback" to "onExpansionChanged",
            "CombinedHeaderExpandController\$notificationCallback" to "onStretchHeightChanged",
            "CombinedHeaderController\$configurationListener" to "onConfigChanged",
            "NotificationHeaderExpandController\$configurationControllerCallback" to "onConfigChanged",
        )
        for ((owner, name) in contracts) {
            val methods = classes.values.filter { it.type.contains("/controlcenter/shade/$owner") }
                .flatMap { it.methods }.filter { it.name == name && it.parameterTypes.size == 1 && it.returnType == "V" && it.accessFlags and 8 == 0 }
            assertEquals("Unique callback $owner#$name", 1, methods.size)
        }
    }

    @Test fun editorReflowAndDragAccessRemainOnThePluginAdapter() {
        val classes = classes("CC_ISLAND_PLUGIN_APK")
        val adapter = classes.getValue("Lmiui/systemui/controlcenter/panel/main/recyclerview/MainPanelAdapter;")
        val notify = adapter.methods.single { it.name == "notifyChanged" }
        assertEquals(listOf("Z", "Z"), notify.parameterTypes.map { it.toString() })
        assertEquals("V", notify.returnType)
        assertTrue(adapter.fields.any { it.name == "contentMap" && it.type.contains("LinkedHashMap") })
        assertTrue(adapter.fields.any { it.name == "attachedHolders" && it.type.startsWith("[") })
        assertTrue(adapter.fields.any { it.name == "mode" })
        assertTrue(adapter.methods.any { it.name == "distributeContent" && it.parameterTypes.map { p -> p.toString() } == listOf("Z") })
        val animator = classes.getValue("Lmiui/systemui/controlcenter/panel/main/recyclerview/ControlCenterItemAnimator;")
        assertTrue(animator.methods.any { it.name == "setSuppressAnimation" && it.parameterTypes.map { p -> p.toString() } == listOf("Z") })
    }
}
