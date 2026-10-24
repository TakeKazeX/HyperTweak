package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NotificationMiniWindowHostContractTest {
    @Test fun nativeBarAndTouchShareTheWhitelistGateAndKeepLaunchGuards() {
        val path = System.getenv("SYSTEMUI_APK")
        assumeTrue("Set SYSTEMUI_APK to check the actual native notification chain", path != null)
        val dex = DexOperandIndex.fixture(path!!)
        val manager = "Lcom/android/systemui/statusbar/notification/policy/AppMiniWindowManagerImpl;"
        val row = "Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRowInjector;"
        val settings = "Lcom/miui/systemui/notification/NotificationSettingsManager;"
        val gate = "$manager->canNotificationSlide(Ljava/lang/String;Landroid/app/PendingIntent;)Z"
        val code = dex.code(gate)
        listOf("mAllowNotificationSlide", "mAllowNotificationSlideLocal").forEach { field ->
            assertTrue(code.reads.any { it.definingClass == settings && it.name == field && it.type == "Ljava/util/List;" })
        }
        assertTrue(code.calls.any { it.method.definingClass == "Ljava/util/List;" && it.method.name == "contains" })
        assertTrue(code.calls.any { it.method.definingClass == "Landroid/app/PendingIntent;" && it.method.name == "isActivity" })
        assertTrue(code.reads.any { it.name == "mHasMiniWindowFeature" && it.type == "Z" })
        assertEquals(settings, dex.classDefinition(manager)!!.fields.single { it.name == "notificationSettingsManager" }.type)
        listOf("updateMiniWindowBar", "updateFreeformTipView").forEach { name ->
            val consumer = dex.code("$row->$name()V")
            assertTrue(consumer.calls.any { DexOperandIndex.descriptor(it.method) == gate })
            assertTrue(consumer.writes.any { it.first.definingClass == row && it.first.name == "canSlide" })
        }
        val touch = dex.code("Lcom/android/systemui/statusbar/notification/policy/AppMiniWindowRowTouchHelper;->onInterceptTouchEvent(Landroid/view/MotionEvent;)Z")
        assertTrue(touch.reads.any { it.definingClass == row && it.name == "canSlide" })
        val bar = dex.code("$row->updateMiniWindowBar()V")
        assertTrue(bar.calls.any { it.method.name == "getState" && it.method.definingClass.contains("StatusBarStateController") })
        assertEquals("Lcom/android/systemui/statusbar/notification/row/ExpandableViewInjector;",
            dex.classDefinition("Lcom/android/systemui/statusbar/notification/row/ExpandableView;")!!.fields.single { it.name == "mInjector" }.type)
    }
}
