package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import org.junit.Assert.*
import org.junit.Test

class FingerprintAvoidancePolicyTest {
    private val normal = FingerprintAvoidancePolicy.Geometry(
        normalPadding = 8, fingerprintPadding = 20, remoteViewY = 0, indicationTop = 2300,
        hasUnderDisplaySensor = true, fingerprintY = 2000, fingerprintOffset = 10,
        lowPosition = false, numberHeight = 30, numberPadding = 10
    )
    @Test fun defaultAndUnknownModesPreserveOtherModulesInsteadOfRecomputingNativeDefaults() {
        for (mode in listOf(-1, 0, 3)) assertNull(FingerprintAvoidancePolicy.bottom(mode, normal))
        val upstream = FingerprintBoundProjection(1800)
        assertEquals(1800, upstream.effective(FingerprintAvoidancePolicy.bottom(0, normal)))
    }
    @Test fun noAvoidanceUsesTheRemoteViewOnlyWhenPresent() {
        assertEquals(2292, FingerprintAvoidancePolicy.bottom(1, normal))
        assertEquals(2092, FingerprintAvoidancePolicy.bottom(1, normal.copy(remoteViewY = 2100)))
        assertEquals(2292, FingerprintAvoidancePolicy.bottom(1, normal.copy(remoteViewY = -1)))
    }
    @Test fun alwaysAvoidUsesNativeSensorAndOffsetBranchWithoutChangingEnrollment() {
        assertEquals(1990, FingerprintAvoidancePolicy.bottom(2, normal))
        assertEquals(1970, FingerprintAvoidancePolicy.bottom(2, normal.copy(fingerprintOffset = -30)))
        assertEquals(1980, FingerprintAvoidancePolicy.bottom(2, normal.copy(fingerprintOffset = -20)))
        assertEquals(2292, FingerprintAvoidancePolicy.bottom(2, normal.copy(hasUnderDisplaySensor = false)))
    }
    @Test fun lowPositionReservesTheLiveNumberBoundsOnlyForFingerprintAvoidance() {
        assertEquals(1950, FingerprintAvoidancePolicy.bottom(2, normal.copy(lowPosition = true)))
        assertEquals(1930, FingerprintAvoidancePolicy.bottom(2, normal.copy(lowPosition = true, numberHeight = 50)))
        assertEquals(2292, FingerprintAvoidancePolicy.bottom(1, normal.copy(lowPosition = true)))
    }
    @Test fun notReadyAndSmallBoundsKeepTheExactNativeZeroAndSubtractionSemantics() {
        assertEquals(0, FingerprintAvoidancePolicy.bottom(1, normal.copy(indicationTop = 0)))
        assertEquals(-30, FingerprintAvoidancePolicy.bottom(2, normal.copy(lowPosition = true, fingerprintY = 20)))
    }
    @Test fun ownRefreshesAndReentrantUpstreamUpdatesCannotPoisonTheRestoreBaseline() {
        val session = FingerprintBoundProjection(1800)
        assertEquals(2292, session.effective(2292))
        assertEquals(1800, session.upstream)
        // HyperMusicCover publishes a different policy while our explicit mode is active.
        assertEquals(2292, session.observe(1770, 2292))
        assertEquals(1770, session.effective(null))
        assertEquals(1990, session.effective(1990))
        assertEquals(1770, session.upstream)
        assertEquals(1750, session.observe(1750, null))
    }
}
