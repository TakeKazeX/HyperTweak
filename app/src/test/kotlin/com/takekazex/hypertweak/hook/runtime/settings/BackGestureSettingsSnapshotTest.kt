package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.settings.contract.BackGestureKeys

import org.junit.Assert.*
import org.junit.Test

class BackGestureSettingsSnapshotTest {
    private val indicator = BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_INDICATOR

    @Test fun `malformed settings and negative identities cannot silently become defaults`() {
        for (bad in listOf<Any>("false", 0, 1L)) {
            assertNull(BackGestureSettingsSnapshot.read(mapOf(indicator to bad)))
        }
        for (bad in listOf<Any>(-1L, "1", 1)) {
            assertNull(BackGestureSettingsSnapshot.read(mapOf(BackGestureSettingsSnapshot.REVISION to bad)))
            assertNull(BackGestureSettingsSnapshot.read(mapOf("prefs_epoch" to bad)))
        }
    }
}
