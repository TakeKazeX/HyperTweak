package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierRecoveryPolicyTest {
    private fun prepared() = CarrierRecoveryEvidence(true, 1, 2, true, true, true)

    @Test
    fun missingPackageReadyEnvironmentCannotReportPreparedDespiteAvailableSources() {
        val missingContext = prepared().copy(environmentReady = false)
        assertEquals(listOf("environment"), missingContext.missing)
        assertEquals(CarrierRecoveryPolicy.Decision.RETRY, CarrierRecoveryPolicy.decide(true, 1, missingContext))
        assertEquals(CarrierRecoveryPolicy.Decision.PREPARED, CarrierRecoveryPolicy.decide(true, 2, prepared()))
    }

    @Test
    fun zeroRecoveredHostsIsAnIncompleteRecoveryNotSuccess() {
        val missingHost = prepared().copy(hosts = 0, rows = 0)
        assertTrue("host" in missingHost.missing)
        for (attempt in 1 until CarrierRecoveryPolicy.MAX_ATTEMPTS) {
            assertEquals(CarrierRecoveryPolicy.Decision.RETRY, CarrierRecoveryPolicy.decide(true, attempt, missingHost))
        }
        assertEquals(CarrierRecoveryPolicy.Decision.EXHAUSTED,
            CarrierRecoveryPolicy.decide(true, CarrierRecoveryPolicy.MAX_ATTEMPTS, missingHost))
    }

    @Test
    fun findingAHostDoesNotFinishBeforeBothRowsAndTheirSourcesAreBound() {
        for (incomplete in listOf(
            prepared().copy(rows = 1), prepared().copy(artworkReady = false),
            prepared().copy(wifiBound = false), prepared().copy(mobileBound = false)
        )) {
            assertEquals(CarrierRecoveryPolicy.Decision.RETRY, CarrierRecoveryPolicy.decide(true, 1, incomplete))
        }
        assertEquals(CarrierRecoveryPolicy.Decision.PREPARED, CarrierRecoveryPolicy.decide(true, 2, prepared()))
    }

    @Test
    fun retiringGenerationCannotResumeEvenIfItsDelayedArtworkCompletes() {
        assertEquals(CarrierRecoveryPolicy.Decision.RETIRED, CarrierRecoveryPolicy.decide(false, 1, prepared()))
        assertEquals(CarrierRecoveryPolicy.Decision.RETIRED,
            CarrierRecoveryPolicy.decide(false, CarrierRecoveryPolicy.MAX_ATTEMPTS, prepared().copy(hosts = 0)))
    }
}
