package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveCapabilityHostContractTest {
    private fun dex(name: String): DexOperandIndex {
        val path = System.getenv(name)
        assumeTrue("Set $name to the verified host artifact", path != null)
        return DexOperandIndex.fixture(path!!)
    }

    @Test fun existingAonHostsCanBeRecoveredAndRetiredWithoutReconstructingInitializedControllers() {
        val dex = dex("LIVE_MIUI_SERVICES")
        for (owner in listOf("power/PowerManagerServiceImpl", "sensors/SensorServiceImpl")) {
            val type = "Lcom/android/server/$owner;"
            assertTrue(dex.classDefinition(type.dropLast(1) + "\$Provider\$SINGLETON;")!!.fields.any {
                it.name == "INSTANCE" && it.type == type && it.accessFlags and 8 != 0
            })
        }
        val attention = "Lcom/android/server/power/MiuiAttentionDetector;"
        assertTrue(dex.classDefinition(attention)!!.fields.any { it.name == "mAttentionHandler" })
        for (field in listOf("mAonScreenOnSupported", "mAonScreenOffSupported")) {
            assertEquals("Z", dex.classDefinition(attention)!!.fields.single { it.name == field }.type)
        }
        assertTrue(dex.code("$attention->updateConfigState()V").calls.any { it.method.name == "updateAonScreenOffConfig" })
        val anti = "Lcom/android/server/sensors/AntiburnScreenController;"
        val stop = dex.code("$anti->stopAntiBurnForAppExit()V").calls.map { it.method.name }.toSet()
        assertTrue(stop.containsAll(setOf("cancelAndUnbind", "recoveryProcess", "shutdownScheduler", "unregisterSensorManager", "unregisterMiuiMotionEventListener")))
        val constructor = dex.code("$anti-><init>(Landroid/content/Context;)V")
        assertTrue(constructor.calls.any { it.method.name == "getBoolean" && DexOperandIndex.Value.Number(285540512L) in it.arguments })
        assertTrue(constructor.calls.any { it.method.name == "getBoolean" && it.method.definingClass == "Landroid/content/res/Resources;" })
        val gesture = "Lcom/android/server/tof/AONGestureController;"
        assertTrue(dex.code("$gesture->notifyAonEventChangeIfNeeded()V").calls.any { it.method.name == "post" })
    }

    @Test fun pwmFlagReadersAreAllDeoptimizedAndNativeEffectPathRemainsTheOwner() {
        val vendor = dex("LIVE_MIUI_SERVICES")
        val display = "Lcom/android/server/display/DisplayFeatureManagerService;"
        val apply = vendor.code("$display->setDimmingMode()V")
        assertTrue(apply.reads.any { it.name == "MIMOTION_PWM_SUPPORTED" && it.type == "Z" })
        assertTrue(apply.calls.any { it.method.name == "getMimotionDimmingMode" })
        assertTrue(apply.calls.any { it.method.name == "setScreenEffect" && it.method.parameterTypes.map(CharSequence::toString) == listOf("I", "I") })
        val settings = dex("LIVE_SETTINGS_APK")
        val page = "Lcom/android/settings/MiuiDisplaySettings;"
        assertEquals("Z", settings.classDefinition(page)!!.fields.single { it.name == "MIMOTION_PWM_SUPPORTED" }.type)
        assertTrue(settings.code("$page->onCreate(Landroid/os/Bundle;)V").reads.any { it.name == "MIMOTION_PWM_SUPPORTED" })
        assertTrue(settings.code("$page->onPreferenceChange(Landroidx/preference/Preference;Ljava/lang/Object;)Z").reads.any { it.name == "MIMOTION_PWM_SUPPORTED" })
    }

    @Test fun recordingSafetyReaderIsUniqueAndReadsNativeSessionState() {
        val camera = dex("LIVE_CAMERA_APK")
        val readers = camera.strings("pref_video_record_state").filter {
            it.returnType == "Z" && it.parameterTypes.isEmpty() && it.accessFlags and 8 != 0
        }
        assertEquals(1, readers.size)
        assertTrue(camera.code(readers.single()).calls.any { it.method.returnType == "Z" })
    }
}
