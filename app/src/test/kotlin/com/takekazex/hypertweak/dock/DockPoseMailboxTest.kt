package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockPoseMailboxTest {
    @Test fun slowRendererAppliesLatestPoseAndSchedulesAgainAfterDrain() {
        val mailbox = DockPoseMailbox()
        assertTrue(mailbox.offer(DockPose(10f, 20f, .8f)))
        repeat(100) { assertFalse(mailbox.offer(DockPose(it.toFloat(), 30f, .9f))) }
        assertEquals(DockPose(99f, 30f, .9f), mailbox.take())
        assertNull(mailbox.take())
        assertTrue(mailbox.offer(DockPose(0f, 0f, 1f)))
        assertEquals(DockPose(0f, 0f, 1f), mailbox.take())
    }

    @Test fun retiredRendererDropsQueuedAndLateFrames() {
        val mailbox = DockPoseMailbox()
        assertTrue(mailbox.offer(DockPose(10f, 20f, .8f)))
        mailbox.close()
        assertNull(mailbox.take())
        assertFalse(mailbox.offer(DockPose(0f, 0f, 1f)))
        assertNull(mailbox.take())
    }
}
