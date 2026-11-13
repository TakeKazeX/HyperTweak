package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class BarFolmeCommandsTest {
    @Test fun statusReplacementPreservesNavigationAndWaitsForBoth() {
        val commands = BarFolmeCommands()
        val hide = Any()
        val showStatus = Any()
        commands.replace(hide, 0, 4)
        commands.replace(showStatus, 0, 2)
        assertFalse(commands.owns(hide, 1))
        assertTrue(commands.owns(hide, 3))
        assertEquals(0, commands.terminal(showStatus, false))
        assertTrue(commands.contains(hide))
        assertEquals(1, commands.terminal(hide, false))
        assertEquals(0, commands.terminal(hide, true))
    }

    @Test fun supersededCancellationCannotCancelReplacement() {
        val commands = BarFolmeCommands()
        val old = Any()
        val status = Any()
        val navigation = Any()
        commands.replace(old, 0, 4)
        commands.replace(status, 0, 2)
        commands.replace(navigation, 2, 2)
        assertEquals(0, commands.terminal(old, true))
        assertEquals(0, commands.terminal(navigation, false))
        assertEquals(1, commands.terminal(status, false))
    }

    @Test fun navigationSetToInvalidatesOnlyNavigationCallbacks() {
        val commands = BarFolmeCommands()
        val status = Any()
        val oldNavigation = Any()
        commands.replace(status, 0, 2)
        commands.replace(oldNavigation, 2, 2)
        commands.reset(2, 2)
        assertEquals(0, commands.terminal(oldNavigation, true))
        assertTrue(commands.owns(status, 0))
        assertEquals(1, commands.terminal(status, false))
    }

    @Test fun currentCancellationIsReportedAfterOtherChannelsFinish() {
        val commands = BarFolmeCommands()
        val status = Any()
        val navigation = Any()
        commands.replace(status, 0, 2)
        commands.replace(navigation, 2, 2)
        assertEquals(0, commands.terminal(status, true))
        assertEquals(2, commands.terminal(navigation, false))
        commands.replace(status, 0, 2)
        assertEquals(1, commands.terminal(status, false))
    }
}
