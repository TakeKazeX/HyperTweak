package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class NativeRuleStateSnapshotTest {
    @Test fun unavailableChannelDoesNotDisableAnExistingConfiguration() {
        assertNull(NativeRuleStateSnapshot.read { throw IllegalStateException("daemon unavailable") })
    }
    @Test fun authoritativeResetPublishesDefaults() {
        assertEquals(NativeRuleStateSnapshot(false, 3, false), NativeRuleStateSnapshot.read { emptyMap<String, Any>() })
    }
    @Test fun completeSnapshotKeepsIndependentSwitches() {
        assertEquals(NativeRuleStateSnapshot(true, 5, false), NativeRuleStateSnapshot.read { mapOf(
            Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON to true,
            Preferences.KEY_OPENED_FOLDER_COLUMNS to 5,
            Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS to false
        ) })
    }
    @Test fun malformedChannelDoesNotPartiallyOverwriteConfiguration() {
        assertNull(NativeRuleStateSnapshot.read { mapOf(
            Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON to "false",
            Preferences.KEY_OPENED_FOLDER_COLUMNS to 4
        ) })
    }
}
