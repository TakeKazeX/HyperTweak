package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RecentsBarReuseSessionTest {
    @Test fun retainedCallbackAcknowledgesANewControllerOnlyAtStart() {
        val callback = Any()
        val original = Any()
        val endpoint = Any()
        repeat(3) {
            val s = RecentsBarReuseSession(Any(), Any(), Any(), Any(), null, null, null, Any(), 42)
            assertFalse(s.adopt(s.owner, callback, original, endpoint, true, 1))
            assertFalse(s.adopt(s.owner, callback, original, endpoint, false, 12))
            assertFalse(s.adopt(Any(), callback, original, endpoint, true, 12))
            assertTrue(s.adopt(s.owner, callback, original, endpoint, true, 12))
            s.delivered(callback, true, 1)
            assertTrue(consume(s))
            assertFalse(s.adopt(s.owner, Any(), original, endpoint, true, 12))
            s.close()
            assertFalse(s.adopt(s.owner, callback, original, endpoint, true, 12))
        }
    }
    private fun session() = RecentsBarReuseSession(Any(), Any(), Any(), Any(), Any(), Any(), Any(), Any(), 42)
    private fun consume(s: RecentsBarReuseSession, live: Boolean = true, toHome: Boolean = false) =
        s.consume(s.controller, s.transition, s.info, s.callback, s.task, toHome, live)

    @Test fun repeatedReuseDoesNotWaitForTransitionEnd() {
        val s = session()
        repeat(3) {
            s.delivered(s.callback, true, 1)
            s.delivered(s.callback, true, 6)
            assertTrue(consume(s))
            assertFalse(consume(s))
        }
    }

    @Test fun normalOpenAndExistingAppShowNeverGetAnotherHandoff() {
        for (state in listOf(0, 2, 5, 7, 9, 10, 12)) {
            val s = session()
            assertFalse(consume(s))
            s.delivered(s.callback, true, 1)
            s.delivered(s.callback, true, state)
            assertFalse(consume(s))
        }
    }

    @Test fun lateGestureBookkeepingDoesNotEraseHomeAlphaTarget() {
        val s = session()
        s.delivered(s.callback, true, 3)
        assertFalse(consume(s))
        s.delivered(s.callback, true, 1)
        s.delivered(s.callback, true, 3)
        assertTrue(consume(s))
        s.delivered(s.callback, true, 3)
        assertFalse(consume(s))
    }

    @Test fun foreignCallbacksCannotArmOrDisarmCurrentHomeTarget() {
        val s = session()
        s.delivered(Any(), true, 1)
        s.delivered(s.callback, false, 1)
        assertFalse(consume(s))
        s.delivered(s.callback, true, 1)
        s.delivered(Any(), true, 7)
        s.delivered(s.callback, false, 9)
        s.delivered(s.callback, true, 14)
        assertTrue(consume(s))
    }

    @Test fun replacementAndRetirementRejectQueuedDirection() {
        val s = session()
        s.delivered(s.callback, true, 1)
        val identities = arrayOf(s.controller, s.transition, s.info, s.callback, s.task)
        for (index in identities.indices) {
            val changed = identities.copyOf().also { it[index] = Any() }
            assertFalse(s.consume(changed[0], changed[1], changed[2], changed[3], changed[4], false, true))
        }
        assertFalse(consume(s, live = false))
        assertFalse(consume(s, toHome = true))
        s.close()
        s.delivered(s.callback, true, 1)
        assertFalse(consume(s))
    }

    @Test fun anotherPublishedCallbackCannotReplaceTheDeliveredSession() {
        val s = session()
        val next = session()
        next.delivered(next.callback, true, 12)
        s.delivered(s.callback, true, 12)
        s.delivered(s.callback, true, 1)
        s.delivered(next.callback, true, 9)
        assertTrue(consume(s))
        assertFalse(consume(next))
        s.close()
        s.delivered(s.callback, true, 1)
        assertFalse(consume(s))
    }

    private fun target(task: Any = Any(), id: Int = 84) = RecentsBarReuseSession.Target(task, id, Any())
    private fun publish(s: RecentsBarReuseSession, next: RecentsBarReuseSession.Target): Any {
        val update = s.beginTargetUpdate()
        assertTrue(s.acceptTarget(update, next))
        return update
    }
    private fun show(s: RecentsBarReuseSession, next: RecentsBarReuseSession.Target,
        task: Any = next.task, live: Boolean = true, toHome: Boolean = false) =
        s.consumeTarget(s.controller, s.transition, s.info, s.callback, next, task, toHome, live)

    @Test fun nativeAppearedTargetReplacesTheOriginalAppWithoutReplacingTheController() {
        val s = session()
        val b = target()
        s.delivered(s.callback, true, 1)
        publish(s, b)
        assertFalse(consume(s))
        assertTrue(show(s, b))
        assertFalse(show(s, b))
    }

    @Test fun acceptedTargetsAdvanceEvenAfterTheFirstApplicationLeavesTheNativeLists() {
        val s = session()
        for (next in listOf(target(), target(id = 90), target(s.task, s.taskId))) {
            publish(s, next)
            s.delivered(s.callback, true, 1)
            assertTrue(show(s, next))
            assertFalse(show(s, next))
        }
    }

    @Test fun homeStateArrivingDuringNativeTargetDeliveryIsRetained() {
        val s = session()
        val b = target()
        val update = s.beginTargetUpdate()
        s.delivered(s.callback, true, 1)
        assertFalse(show(s, b))
        assertFalse(consume(s))
        assertTrue(s.acceptTarget(update, b))
        assertTrue(show(s, b))
    }

    @Test fun anABARetargetCannotAcceptAnOldDeliveryOrOldProof() {
        val s = session()
        val a = target(s.task, s.taskId)
        val b = target()
        val old = s.beginTargetUpdate()
        publish(s, b)
        publish(s, a)
        assertFalse(s.acceptTarget(old, b))
        s.delivered(s.callback, true, 1)
        assertFalse(show(s, b))
        assertTrue(show(s, a))
    }

    @Test fun replacedNativeTaskOrUncertainOwnerCannotConsumeTheHomeTarget() {
        val s = session()
        val b = target()
        publish(s, b)
        s.delivered(s.callback, true, 1)
        assertFalse(show(s, b, task = Any()))
        assertFalse(show(s, b, live = false))
        assertFalse(show(s, b, toHome = true))
        assertTrue(show(s, b))
    }

    @Test fun nativeTargetDoesNotRelaxTheOriginalControllerTransitionOrCallback() {
        val s = session()
        val b = target()
        publish(s, b)
        s.delivered(s.callback, true, 1)
        val identities = arrayOf(s.controller, s.transition, s.info, s.callback)
        for (index in identities.indices) {
            val changed = identities.copyOf().also { it[index] = Any() }
            assertFalse(s.consumeTarget(changed[0], changed[1], changed[2], changed[3], b, b.task, false, true))
        }
        assertTrue(show(s, b))
    }

    @Test fun lateTargetDeliveryCannotReviveHotReloadOrCleanup() {
        val s = session()
        val b = target()
        val update = s.beginTargetUpdate()
        s.delivered(s.callback, true, 1)
        s.close()
        assertFalse(s.acceptTarget(update, b))
        assertFalse(show(s, b))
        assertFalse(consume(s))
        assertTrue(s.beginTargetUpdate() == null)
    }

    @Test fun malformedTargetCannotEnableAnUnrelatedApp() {
        val s = session()
        val b = target(id = -1)
        s.delivered(s.callback, true, 1)
        assertFalse(s.acceptTarget(s.beginTargetUpdate(), b))
        assertFalse(show(s, b))
        assertTrue(consume(s))
    }

    @Test fun aRepeatedTargetDeliveryDoesNotRearmAnAlreadyConsumedHomeState() {
        val s = session()
        val b = target()
        publish(s, b)
        s.delivered(s.callback, true, 1)
        assertTrue(show(s, b))
        val nextReceipt = target(b.task, b.taskId)
        publish(s, nextReceipt)
        assertFalse(show(s, nextReceipt))
        s.delivered(s.callback, true, 1)
        s.delivered(s.callback, true, 7)
        assertFalse(show(s, nextReceipt))
    }
}
