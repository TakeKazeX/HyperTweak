package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import org.junit.Assert.*
import org.junit.Test

class NotificationSlideWhitelistTest {
    @Test fun liveToggleAndResetRestoreCloudMembershipWithoutEditingData() {
        var enabled = false
        val cloud = mutableListOf("allowed.app")
        val list = NotificationSlideWhitelist(cloud) { enabled }
        assertFalse(list.contains("other.app"))
        enabled = true
        assertTrue(list.contains("other.app"))
        assertFalse(list.contains(""))
        assertEquals(listOf("allowed.app"), list.toList())
        assertEquals(cloud, list)
        cloud.clear()
        cloud.add("updated.app")
        enabled = false
        assertFalse(list.contains("other.app"))
        assertFalse(list.contains("allowed.app"))
        assertTrue(list.contains("updated.app"))
        assertSame(cloud, list.source)
        enabled = true
        assertTrue(list.contains("other.app"))
    }

    @Test fun resetAndUpgradeSnapshotsReachOnlyTheSystemUiParticipant() {
        val key = SystemUiSettingsKeys.KEY_NOTIFICATION_MINI_WINDOW
        val group = RuntimeSettingsGroups.NOTIFICATION_MINI_WINDOW
        assertEquals(setOf(group), RuntimeSettingsGroups.affected(key))
        for (value in listOf(false, true)) {
            val values = mapOf<String, Any>(key to value)
            assertEquals(values, RuntimeSettingsGroups.subset("com.android.systemui", values))
            assertTrue(RuntimeSettingsGroups.subset("com.miui.home", values).isEmpty())
            assertNotNull(RuntimeSettingsSnapshot.read(1, values, setOf(group)))
        }
    }
}
