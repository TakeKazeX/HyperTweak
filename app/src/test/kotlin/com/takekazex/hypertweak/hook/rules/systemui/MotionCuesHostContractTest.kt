package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.systemui.freeform.MotionCuesResolver
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class MotionCuesHostContractTest {
    @Test fun resolvesTheViewPassedToWindowManagerAndStoredByTheHost() {
        val path = System.getenv("SYSTEMUI_APK")
        assumeTrue("Set SYSTEMUI_APK to verify the Motion Cues overlay", path != null)
        val owner = "Lcom/android/systemui/motioncues/MotionCuesUi;"
        val overlay = "Lcom/android/systemui/motioncues/MotionCuesUi\$OverlayView;"
        val manager = "Lcom/android/systemui/motioncues/MotionCuesManager;"
        assertEquals(
            mapOf(
                "create" to "$owner->makeOverlayView()V",
                "overlay" to "$owner->overlayView:$overlay",
                "start" to "$manager->startMotionCuesSession(Landroid/content/ComponentName;ILandroid/app/motioncues/MotionCuesSettings;)V",
                "reconfigure" to "$owner->onConfigChanged(Landroid/content/res/Configuration;)V",
            ),
            MotionCuesResolver.resolve(DexOperandIndex.fixture(path!!)),
        )
    }
}
