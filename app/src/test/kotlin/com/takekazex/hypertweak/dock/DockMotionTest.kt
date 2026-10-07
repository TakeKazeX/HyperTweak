package com.takekazex.hypertweak.dock

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DockMotionTest {
    private fun packet(sequence: Long = 1, alpha: Double = 1.0, scale: Double = 1.0, kind: Int = 0, epoch: Long = 0L) =
        ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0x444bL + (kind.toLong() shl 16) + (epoch shl 32)).putLong(101).putLong(-202).putLong(sequence)
            .putDouble(-125.5).putDouble(31.25).putDouble(scale).putDouble(9.5).putDouble(alpha).array()
    @Test fun nativeSlidePacketPreservesFractionalMotionAndSignedSessionIdentity() {
        val motion = requireNotNull(DockMotionCodec.decode(packet(), 72, 101, -202))
        assertEquals(-125.5, motion.x, 0.0)
        assertEquals(31.25, motion.y, 0.0)
        assertEquals(9.5, motion.originY, 0.0)
        assertEquals(1.0, motion.alpha, 0.0)
    }
    @Test fun editEmptyRowAndGestureFadeAreNativeVisibilityRatherThanOverlayPresence() {
        val editing = requireNotNull(DockMotionCodec.decode(packet(2, alpha = 0.0), 72, 101, -202))
        val slide = requireNotNull(DockMotionCodec.decode(packet(3, alpha = .6, scale = .92), 72, 101, -202))
        assertFalse(DockPresentationPolicy.resolve(true, true, true, false, editing.alpha <= 0, true).showLayer)
        assertTrue(DockPresentationPolicy.resolve(true, true, true, false, slide.alpha <= 0, true).showLayer)
    }
    @Test fun oldOrForeignEndpointAndMalformedFloatsCannotMoveOwnedSurface() {
        assertNull(DockMotionCodec.decode(packet(), 72, 999, -202))
        assertNull(DockMotionCodec.decode(packet(), 72, 101, 999))
        assertNull(DockMotionCodec.decode(packet(), 71, 101, -202))
        assertNull(DockMotionCodec.decode(packet(0), 72, 101, -202))
        assertNull(DockMotionCodec.decode(packet(alpha = Double.NaN), 72, 101, -202))
        assertNull(DockMotionCodec.decode(packet(scale = Double.POSITIVE_INFINITY), 72, 101, -202))
        assertNull(DockMotionCodec.decode(packet(scale = 99.0), 72, 101, -202))
    }
    @Test fun interruptedGestureCannotBeReplacedByLateFramesOrClosedSession() {
        val ledger = DockMotionLedger()
        val during = requireNotNull(DockMotionCodec.decode(packet(2, scale = .7), 72, 101, -202))
        val cancelled = requireNotNull(DockMotionCodec.decode(packet(3), 72, 101, -202))
        assertTrue(ledger.offer(during))
        assertTrue(ledger.offer(cancelled))
        assertFalse(ledger.offer(during))
        assertEquals(cancelled.copy(sequence = 2), ledger.latest)
        ledger.close()
        assertFalse(ledger.offer(cancelled.copy(sequence = 4)))
        assertNull(ledger.latest)
    }
    @Test fun cachedInnerRowStillShrinksWithRootAndRestoresAfterCancelledGesture() {
        val ledger = DockMotionLedger()
        assertTrue(ledger.offer(requireNotNull(DockMotionCodec.decode(packet(), 72, 101, -202))))
        assertTrue(ledger.offer(requireNotNull(DockMotionCodec.decode(packet(2, alpha = .8, scale = .65, kind = 1), 72, 101, -202))))
        assertEquals(.65, requireNotNull(ledger.latest).screenScale, 0.0)
        assertEquals(.8, requireNotNull(ledger.latest).alpha, 0.0)
        assertTrue(ledger.offer(requireNotNull(DockMotionCodec.decode(packet(3, kind = 1), 72, 101, -202))))
        assertEquals(1.0, requireNotNull(ledger.latest).screenScale, 0.0)
    }
    @Test fun editingStaysHiddenAcrossIndependentRootAndInnerUpdatesUntilExplicitExit() {
        val ledger = DockMotionLedger()
        fun offer(sequence: Long, kind: Int, alpha: Double = 1.0) = ledger.offer(
            requireNotNull(DockMotionCodec.decode(packet(sequence, alpha = alpha, kind = kind), 72, 101, -202)))
        assertTrue(offer(1, 2, 0.0))
        assertTrue(offer(2, 1))
        assertTrue(offer(3, 0))
        assertEquals(0.0, requireNotNull(ledger.latest).alpha, 0.0)
        assertFalse(offer(1, 2))
        assertEquals(0.0, requireNotNull(ledger.latest).alpha, 0.0)
        assertTrue(offer(4, 2))
        assertEquals(1.0, requireNotNull(ledger.latest).alpha, 0.0)
        assertNull(DockMotionCodec.decode(packet(5, kind = 8), 72, 101, -202))
    }
    @Test fun nativeControllerEventsDrivePoseWithoutAnyWidgetBuild() {
        val ledger = DockMotionLedger()
        assertTrue(ledger.offer(DockMotion(10, 0.0, 0.0, .65, 0.0, 1.0, 3)))
        assertTrue(ledger.offer(DockMotion(11, 0.0, 0.0, 1.0, 0.0, .8, 4)))
        assertTrue(ledger.offer(DockMotion(9, 0.0, 0.0, 1.0, 0.0, 1.0, 5)))
        assertEquals(.65, requireNotNull(ledger.latest).screenScale, 0.0)
        assertEquals(.8, requireNotNull(ledger.latest).alpha, 0.0)
        assertTrue(ledger.offer(DockMotion(12, 0.0, 0.0, 1.0, 0.0, 1.0, 5)))
        assertEquals(1.0, requireNotNull(ledger.latest).screenScale, 0.0)
    }
    @Test fun replacementRestoresEditingAndPoseAndAcceptsOlderIndependentReplay() {
        val old = DockMotionLedger()
        old.offer(DockMotion(5, 12.0, 3.0, 1.0, 0.0, 1.0))
        old.offer(DockMotion(8, 0.0, 0.0, 1.0, 0.0, 0.0, 2))
        old.offer(DockMotion(12, 0.0, 0.0, .7, 0.0, 1.0, 3))
        val replacement = DockMotionLedger().apply { restore(old.snapshot()) }
        old.close()
        assertEquals(.7, requireNotNull(replacement.latest).screenScale, 0.0)
        assertEquals(0.0, requireNotNull(replacement.latest).alpha, 0.0)
        assertFalse(replacement.offer(DockMotion(8, 0.0, 0.0, 1.0, 0.0, 1.0, 2)))
        assertTrue(replacement.offer(DockMotion(10, 0.0, 0.0, 1.0, 0.0, 1.0, 2)))
        assertEquals(1.0, requireNotNull(replacement.latest).alpha, 0.0)
    }
    @Test fun nativeSpringAlphaUsesTheSameClampAsTheRenderingConsumer() {
        assertEquals(1.0, requireNotNull(DockMotionCodec.decode(packet(alpha = 1.02, kind = 4), 72, 101, -202)).alpha, 0.0)
        assertEquals(0.0, requireNotNull(DockMotionCodec.decode(packet(alpha = -.02, kind = 4), 72, 101, -202)).alpha, 0.0)
        assertNull(DockMotionCodec.decode(packet(alpha = 99.0, kind = 4), 72, 101, -202))
    }
    @Test fun ordinaryDesktopDragRetainsDockThroughNativeEmptyRowAndReload() {
        val ledger = DockMotionLedger()
        ledger.offer(DockMotion(1, 0.0, 0.0, 1.0, 0.0, .9))
        ledger.offer(DockMotion(2, 0.0, 0.0, 1.0, 1.0, 1.0, 2))
        ledger.offer(DockMotion(3, 0.0, 0.0, 1.0, 0.0, 0.0))
        assertEquals(.9, requireNotNull(ledger.latest).alpha, 0.0)
        val replacement = DockMotionLedger().apply { restore(ledger.snapshot()) }
        assertEquals(.9, requireNotNull(replacement.latest).alpha, 0.0)
        replacement.offer(DockMotion(4, 0.0, 0.0, 1.0, 0.0, 1.0, 2))
        assertEquals(.9, requireNotNull(replacement.latest).alpha, 0.0)
        replacement.offer(DockMotion(5, 0.0, 0.0, 1.0, 0.0, 0.0, 2))
        assertEquals(0.0, requireNotNull(replacement.latest).alpha, 0.0)
    }
    @Test fun newProducerResetsWatermarksAndBothTransformLayersAfterNativeRestart() {
        val old = DockMotionLedger()
        old.offer(DockMotion(9000, 0.0, 0.0, 1.0, 0.0, 1.0, 3, sourceEpoch = 100))
        old.offer(DockMotion(9001, 0.0, 0.0, 1.0, 0.0, 1.0, 6, sourceEpoch = 100))
        val replacement = DockMotionLedger().apply { restore(old.snapshot()) }
        assertTrue(replacement.offer(DockMotion(1, 0.0, 0.0, .85, 0.0, 1.0, 3, sourceEpoch = 200)))
        assertTrue(replacement.offer(DockMotion(2, 0.0, -12.0, .86, 0.0, 1.0, 6, sourceEpoch = 200)))
        assertEquals(.85, requireNotNull(replacement.latest).screenScale, 0.0)
        assertEquals(.86, requireNotNull(replacement.latest).scale, 0.0)
        assertEquals(-12.0, requireNotNull(replacement.latest).y, 0.0)
        assertFalse(replacement.offer(DockMotion(9999, 0.0, 0.0, 1.0, 0.0, 1.0, 3, sourceEpoch = 100)))
        val again = DockMotionLedger().apply { restore(replacement.snapshot()) }
        assertFalse(again.offer(DockMotion(9999, 0.0, 0.0, 1.0, 0.0, 1.0, 6, sourceEpoch = 100)))
    }
    @Test fun draggingAnEmptyRowRetainsVisibilityButStillConsumesEveryScaleTick() {
        val ledger = DockMotionLedger()
        fun frame(rev: Long, kind: Int, scale: Double = 1.0, y: Double = 0.0, origin: Double = 0.0) =
            DockMotion(rev, 0.0, y, scale, origin, 1.0, kind, sourceEpoch = 10)
        ledger.offer(frame(1, 0, origin = 118.0))
        ledger.offer(frame(2, 2, origin = 1.0))
        ledger.offer(frame(3, 7))
        ledger.offer(frame(4, 6, .9, -10.0))
        ledger.offer(frame(5, 6, .86, -18.0))
        assertEquals(1.0, requireNotNull(ledger.latest).alpha, 0.0)
        assertEquals(.86, requireNotNull(ledger.latest).scale, 0.0)
        assertEquals(118.0, requireNotNull(ledger.latest).originY, 0.0)
        assertEquals(-18.0, requireNotNull(ledger.latest).y, 0.0)
        // A delayed empty sample cannot erase newer visible content or its pose.
        ledger.offer(frame(8, 0, .95, -5.0, 118.0))
        ledger.offer(frame(7, 7))
        ledger.offer(frame(9, 2))
        assertEquals(1.0, requireNotNull(ledger.latest).alpha, 0.0)
        assertEquals(.95, requireNotNull(ledger.latest).scale, 0.0)
    }
    @Test fun wireEpochSurvivesUnsignedHeaderAndLegacyEpochsRemainDecodable() {
        val decoded = requireNotNull(DockMotionCodec.decode(packet(epoch = 0xf1234567L), 72, 101, -202))
        assertEquals(0xf1234567L, decoded.sourceEpoch)
        assertEquals(0, decoded.kind)
        assertEquals(0L, requireNotNull(DockMotionCodec.decode(packet(), 72, 101, -202)).sourceEpoch)
    }
}
