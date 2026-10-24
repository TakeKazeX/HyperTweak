package com.takekazex.hypertweak.hook.rules.settings

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the Settings contracts HyperOsAiEntriesHooker depends on against a real APK.
 *
 * The hook resolves its targets by class and method name, so a ROM rename would silently disable
 * both switches. The assertions also pin the semantics: the model row is gated by the
 * `ProviderData` capability answer, and Gemini by the international-build flag, which is exactly
 * what the switches override.
 */
class HyperOsAiEntriesHostContractTest {
    private fun classes(path: String): Map<String, ClassDef> {
        val container = DexFileFactory.loadDexContainer(File(path), Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    private fun ClassDef.availabilityStatus(): Method =
        methods.single {
            it.name == "getAvailabilityStatus" && it.parameterTypes.isEmpty() && it.returnType == "I"
        }

    private fun Method.strings(): List<String> = implementation?.instructions
        ?.filterIsInstance<ReferenceInstruction>()
        ?.mapNotNull { (it.reference as? org.jf.dexlib2.iface.reference.StringReference)?.string }
        .orEmpty()

    private fun Method.calls(): List<MethodReference> = implementation?.instructions
        ?.filterIsInstance<ReferenceInstruction>()
        ?.mapNotNull { it.reference as? MethodReference }
        .orEmpty()

    private fun Method.fields(): List<FieldReference> = implementation?.instructions
        ?.filterIsInstance<ReferenceInstruction>()
        ?.mapNotNull { it.reference as? FieldReference }
        .orEmpty()

    @Test fun modelRowIsGatedByTheProviderCapabilityAnswer() {
        val path = System.getenv("SETTINGS_APK")
        assumeTrue("Set SETTINGS_APK to verify the installed Settings host", path != null)
        val classes = classes(path!!)
        val controller = classes.getValue("Lcom/android/settings/hyperosai/ModelManagementController;")
        val availability = controller.availabilityStatus()
        assertEquals(1, controller.methods.count { it.name == "getAvailabilityStatus" })
        assertTrue(
            "The model row must be gated by TileFetcherUtils.ModelManageisSupport()",
            availability.calls().any {
                it.definingClass == "Lcom/android/settings/hyperosai/enhanced/TileFetcherUtils;" &&
                    it.name == "ModelManageisSupport"
            },
        )
        // The row only opens the AIService model page, which is what the module's switch reveals.
        val click = controller.methods.single { it.name == "handlePreferenceTreeClick" }
        assertTrue(click.strings().contains("com.xiaomi.aiservice.MODEL_MANAGER"))
    }

    @Test fun geminiRowIsGatedByTheInternationalBuildFlagAndTheBardPackage() {
        val path = System.getenv("SETTINGS_APK")
        assumeTrue("Set SETTINGS_APK to verify the installed Settings host", path != null)
        val classes = classes(path!!)
        val controller = classes.getValue("Lcom/android/settings/hyperosai/GeminiController;")
        val availability = controller.availabilityStatus()
        assertEquals(1, controller.methods.count { it.name == "getAvailabilityStatus" })
        assertTrue(
            "The Gemini row must be gated by miui.os.Build.IS_INTERNATIONAL_BUILD",
            availability.fields().any {
                it.definingClass == "Lmiuix/os/Build;" && it.name == "IS_INTERNATIONAL_BUILD"
            },
        )
        assertTrue(availability.strings().contains("com.google.android.apps.bard"))
    }

    @Test fun nativeControllersKeepHiddenNodesAndCanRefreshExistingPages() {
        val path = System.getenv("SETTINGS_APK")
        assumeTrue("Set SETTINGS_APK to verify the installed Settings host", path != null)
        val classes = classes(path!!)
        val abstract = classes.getValue("Lcom/android/settingslib/core/AbstractPreferenceController;")
        val visible = abstract.methods.single {
            it.name == "setVisible" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("Landroidx/preference/Preference;", "Z")
        }
        assertTrue(visible.calls().any { it.name == "setVisible" })
        assertTrue(abstract.methods.single { it.name == "displayPreference" }.calls().none {
            it.name == "removePreference" || it.name == "removePreferenceRecursively"
        })
        val dashboard = classes.getValue("Lcom/android/settings/dashboard/DashboardFragment;")
        assertEquals("Ljava/util/Collection;", dashboard.methods.single {
            it.name == "getPreferenceControllers"
        }.returnType)
        assertTrue(dashboard.methods.single { it.name == "getPreferenceControllers" }.calls().any {
            it.definingClass == "Ljava/util/Map;" && it.name == "values"
        })
        val fragment = classes.getValue("Lcom/android/settings/XiaomiHyperOSAiFragment;")
        val build = fragment.methods.single { it.name == "buildPreferenceControllers" }
        for (controller in listOf("ModelManagementController", "GeminiController")) {
            assertTrue(build.calls().any {
                it.definingClass == "Lcom/android/settings/hyperosai/$controller;" && it.name == "<init>"
            })
        }
        assertEquals(1, fragment.methods.count { it.name == "onResume" && it.parameterTypes.isEmpty() })
    }
}
