package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RecentsBarCallbackSessionTest {
    @Test fun retainedCallbackWorksAcrossTwoSessions() {
        val owner = Any()
        val retained = Any()
        for (terminal in listOf(0, 9)) {
            val session = RecentsBarCallbackSession(owner)
            assertFalse(session.accept(owner, retained, 10146, true, 6))
            assertFalse(session.accept(owner, retained, 10146, true, 14))
            for (state in listOf(12, 3, terminal))
                assertTrue(session.accept(owner, retained, 10146, true, state))
            assertFalse(session.accept(owner, retained, 10146, true, 13))
        }
    }
    @Test fun wallpaperCannotAdoptCallbackAndSnapshotCannotTerminateReturn() {
        val owner = Any()
        val wallpaperCallback = Any()
        val gestureCallback = Any()
        val session = RecentsBarCallbackSession(owner)
        assertFalse(session.accept(owner, wallpaperCallback, 10146, true, 14))
        assertFalse(session.accept(owner, wallpaperCallback, 10146, true, 3))
        assertTrue(session.accept(owner, gestureCallback, 10146, true, 12))
        assertFalse(session.accept(owner, gestureCallback, 10146, true, 6))
        assertFalse(session.accept(owner, gestureCallback, 10146, true, 14))
        assertTrue(session.accept(owner, gestureCallback, 10146, true, 3))
        assertTrue(session.accept(owner, gestureCallback, 10146, true, 9))
        assertFalse(session.accept(owner, gestureCallback, 10146, true, 12))
    }
    @Test fun rejectsUntrustedReplacedAndClosedOwners() {
        val owner = Any()
        val callback = Any()
        val session = RecentsBarCallbackSession(owner)
        assertFalse(session.accept(owner, callback, 42, false, 12))
        assertFalse(session.accept(Any(), callback, 10146, true, 12))
        assertTrue(session.accept(owner, callback, 10146, true, 12))
        assertFalse(session.accept(owner, Any(), 10146, true, 3))
        assertFalse(session.accept(owner, callback, 42, true, 3))
        session.close()
        assertFalse(session.accept(owner, callback, 10146, true, 0))
        assertFalse(session.accept(owner, callback, 10146, true, 12))
    }
}
