package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import org.junit.Assert.*
import org.junit.Test

class NotificationIconPolicyTest {
    @Test fun hyperOsHidesHighAndBelowWhileAospHidesMediumAndBelow() {
        val hyper = NotificationIconPolicy.threshold(0)
        val aosp = NotificationIconPolicy.threshold(1)
        for (importance in 0..5) {
            assertEquals(importance <= 3, NotificationIconPolicy.silent(importance, hyper, false))
            assertEquals(importance <= 2, NotificationIconPolicy.silent(importance, aosp, false))
        }
        assertNull(NotificationIconPolicy.silent(-1000, hyper, true))
        assertEquals(0, NotificationIconPolicy.normalize(6))
    }

    @Test fun resettingModeDeliversTheDefaultToSystemUi() {
        val key = Preferences.KEY_NOTIFICATION_ICON_FILTER_MODE
        val group = RuntimeSettingsGroups.NOTIFICATION_ICONS
        assertEquals(setOf(group), RuntimeSettingsGroups.affected(key))
        assertEquals(mapOf(key to 0), RuntimeSettingsGroups.subset("com.android.systemui", mapOf(key to 0)))
        assertTrue(RuntimeSettingsGroups.subset("com.android.settings", mapOf(key to 0)).isEmpty())
    }

    private class Model(val key: String, val rank: Int, val silent: Boolean, val icon: Any)
    @Test fun changedClassificationCopiesThePublishedModelAndRetainsItsNativeData() {
        val original = Model("notification-key", 3, false, Any())
        val field = Model::class.java.getDeclaredField("silent").apply { isAccessible = true }
        val copier = NotificationModelCopy(Model::class.java, field)
        val copy = copier.withFlag(original, true) as Model
        assertFalse(original.silent)
        assertTrue(copy.silent)
        assertNotSame(original, copy)
        assertSame(original.icon, copy.icon)
        assertEquals(original.key, copy.key)
        assertEquals(original.rank, copy.rank)
        assertSame(original, copier.withFlag(original, false))
    }
}
