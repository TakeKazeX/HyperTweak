package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real bytecode evidence for the publication boundary; no screenshot or device UI automation. */
class FingerprintAvoidanceHostContractTest {
    private val sync = "Lcom/miui/coroutines/syncflow/"
    private val repo = "Lcom/miui/systemui/keyguard/data/repository/MiuiKeyguardRepositoryImpl;"
    private fun classes() = run {
        val apk = System.getenv("FINGERPRINT_SYSTEMUI_APK")?.let(::File)
        assumeTrue("Set FINGERPRINT_SYSTEMUI_APK to the verified target", apk?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }
    private fun Method.calls() = implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
        .mapNotNull { it.reference as? MethodReference }.toList()
    private fun Method.fields() = implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
        .mapNotNull { it.reference as? FieldReference }.toList()

    @Test fun existingSubscribersUseTheSameMutableFlowAndItsAffinityCheckedNotifyingSetter() {
        val c = classes()
        assertEquals("${sync}SynchronousStateFlowImpl;", c.getValue("${sync}ReadonlySynchronousStateFlow;").fields.single { it.name == "flow" }.type)
        val setter = c.getValue("${sync}SynchronousStateFlowImpl;").methods.single { it.name == "setValue" }
        assertEquals(listOf("Ljava/lang/Object;"), setter.parameterTypes.map(CharSequence::toString))
        assertTrue(setter.calls().any { it.name == "assert" && it.definingClass == "${sync}ThreadAffinity;" })
        assertTrue(setter.calls().any { it.name == "updateState" })
        val update = c.getValue("${sync}SynchronousStateFlowImpl;").methods.single { it.name == "updateState" }
        assertTrue(update.calls().any { it.name == "invoke" })
        assertTrue(update.fields().any { it.name == "_state" })
        val writer = c.getValue("${sync}BridgeKt\$asSynchronousStateFlow\$1;").methods.single { it.name == "invokeSuspend" }
        assertTrue(writer.calls().any { it.name == "setValue" && it.definingClass == "${sync}SynchronousStateFlowImpl;" })
    }
    @Test fun nativeGeometryComesFromUntouchedSourceFlowsAndNumberBounds() {
        val c = classes()
        val panel = c.getValue("Lcom/android/keyguard/panel/KeyguardPanelViewController;")
        assertTrue(panel.fields.any { it.name == "_remoteViewY" && it.type.endsWith("/StateFlowImpl;") })
        assertTrue(panel.fields.any { it.name == "keyguardBottomAreaInjector" })
        val repository = c.getValue(repo)
        for (name in listOf("keyguardPanelViewController", "miuiConfigurationRepository", "notificationNumStateViewModel", "scope")) {
            assertTrue("Missing $name", repository.fields.any { it.name == name })
        }
        val number = c.getValue("Lcom/miui/systemui/notification/view/viewmodel/NotificationNumStateViewModel;")
        assertTrue(number.fields.any { it.name == "bound" && it.type.endsWith("/ReadonlyStateFlow;") })
        val transform = c.getValue("Lcom/miui/systemui/keyguard/data/repository/MiuiKeyguardRepositoryImpl\$notificationBottomOnKeyguard\$2\$1;")
            .methods.single { it.name == "invokeSuspend" }
        for (method in listOf("getFirst", "getSecond", "height")) assertTrue(transform.calls().any { it.name == method })
    }
    @Test fun nativeLayoutConsumersAndRecoveryProviderShareThePublishedNotificationBound() {
        val c = classes()
        val vm = c.getValue("Lcom/miui/systemui/notification/view/viewmodel/NotificationContainerViewModel;")
        val info = vm.methods.single { it.name == "getKeyguardStackStatusInfo" }
        assertTrue(info.calls().any { it.definingClass == repo && it.name == "getNotificationBottomOnKeyguard" })
        assertTrue(info.calls().any { it.definingClass == "${sync}SynchronousStateFlow;" && it.name == "getValue" })
        val inputs = c.getValue("Lcom/miui/systemui/notification/view/viewmodel/NotificationContainerViewModel\$\$ExternalSyntheticLambda1;")
            .methods.single { it.name == "invoke" }
        assertTrue(inputs.calls().count { it.definingClass == repo && it.name == "getNotificationBottomOnKeyguard" } >= 2)
        assertTrue(c.values.any { type -> type.fields.any { it.name == "miuiKeyguardRepositoryImplProvider" } })
    }
}
