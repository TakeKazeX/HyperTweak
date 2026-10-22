package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.hook.rules.aod.AodIconSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit's P0 regression: an AOD battery option must never hide the ordinary lock-screen
 * battery. These cases pin the decision table with the host's real state source
 * (`MiuiFullAodManager.isDozing`), not a hand-picked phase.
 */
class AodKeyguardPolicyTest {
    private fun decide(
        phase: AodHostPhase,
        standardDuo: Boolean = false,
        aodCustomized: Boolean = true,
        aodDuo: Boolean = false,
        fullAodRendering: Boolean = true
    ) = AodKeyguardPolicy.decide(phase, standardDuo, aodCustomized, aodDuo, fullAodRendering)

    @Test fun litLockScreenKeepsNativeBatteryForEveryAodOption() {
        listOf(
            Triple(false, false, true),   // battery icon off
            Triple(true, true, false),    // AOD Duo on
            Triple(true, false, true)     // battery icon and percent hidden
        ).forEach { (icon, duo, percent) ->
            val settings = AodIconSettings(icon, duo, percent, false, 2)
            assertTrue(settings.customized)
            val decision = AodKeyguardPolicy.decide(
                phase = AodHostPhase.LIT,
                standardDuo = false,
                aodCustomized = settings.customized,
                aodDuo = settings.duo,
                fullAodRendering = true
            )
            assertEquals(KeyguardRowOwner.NONE, decision.owner)
            assertTrue(decision.keepsNativeRow)
            assertFalse(decision.aodMarksNativeRow)
            assertFalse(decision.holdsCluster)
        }
    }

    @Test fun litLockScreenOnlyLetsTheAcceptedDuoSwitchReplaceTheCluster() {
        val duo = decide(AodHostPhase.LIT, standardDuo = true)
        assertEquals(KeyguardRowOwner.DUO, duo.owner)
        assertTrue(duo.keepsNativeRow)
        assertTrue(duo.holdsCluster)
        assertFalse(duo.aodMarksNativeRow)
    }

    @Test fun unknownHostStateNeverChangesTheNativeResult() {
        val decision = decide(AodHostPhase.UNKNOWN, standardDuo = true, aodDuo = true)
        assertEquals(KeyguardRowOwner.NONE, decision.owner)
        assertTrue(decision.keepsNativeRow)
    }

    @Test fun dozingFlagDecidesBetweenAodAndLitLockScreen() {
        // The real classifier: only a dozing host plus an attached keyguard may own an AOD row.
        assertEquals(AodHostPhase.DOZING, AodKeyguardPolicy.phaseFromDozing(true, keyguardVisible = true))
        assertEquals(AodHostPhase.LIT, AodKeyguardPolicy.phaseFromDozing(false, keyguardVisible = true))
        // Unknown evidence may only resolve to "a lock screen exists", never to "AOD is showing".
        assertEquals(AodHostPhase.LIT, AodKeyguardPolicy.phaseFromDozing(null, keyguardVisible = true))
        // Without the keyguard host nothing may be borrowed.
        assertEquals(AodHostPhase.UNKNOWN, AodKeyguardPolicy.phaseFromDozing(true, keyguardVisible = false))
        assertEquals(AodHostPhase.UNKNOWN, AodKeyguardPolicy.phaseFromDozing(false, keyguardVisible = false))
        assertEquals(AodHostPhase.UNKNOWN, AodKeyguardPolicy.phaseFromDozing(null, keyguardVisible = false))
    }

    @Test fun fullAodExitIsLitEvenThoughTheHostKeepsToLockScreenFalse() {
        // Enter: the handshake reports false, the dozing flag is authoritative.
        assertEquals(
            AodHostPhase.DOZING,
            AodKeyguardPolicy.phaseAfterHandshake(toLockScreen = false, keyguardVisible = true, dozing = true)
        )
        // Exit: true with a lit host.
        assertEquals(
            AodHostPhase.LIT,
            AodKeyguardPolicy.phaseAfterHandshake(toLockScreen = true, keyguardVisible = true, dozing = false)
        )
        // Exit where the host has not published the flag yet: the target is a lit lock screen.
        assertEquals(
            AodHostPhase.LIT,
            AodKeyguardPolicy.phaseAfterHandshake(toLockScreen = true, keyguardVisible = true, dozing = null)
        )
        // A detached keyguard host cannot be classified at all.
        assertEquals(
            AodHostPhase.UNKNOWN,
            AodKeyguardPolicy.phaseAfterHandshake(toLockScreen = true, keyguardVisible = false, dozing = true)
        )
    }

    @Test fun keyguardCallbackAloneNeverClaimsAod() {
        // Without the dozing flag the field can only report a lock screen, never a settled AOD,
        // because it stays false after a full-AOD exit.
        assertEquals(AodHostPhase.LIT, AodKeyguardPolicy.phaseAfterKeyguardCallback(true))
        assertEquals(AodHostPhase.TRANSITIONING, AodKeyguardPolicy.phaseAfterKeyguardCallback(false))
    }

    @Test fun transitionSettlesOnlyOnTheDozingFlag() {
        assertEquals(AodHostPhase.DOZING, AodKeyguardPolicy.phaseAfterTransitionFrame(true, true))
        assertEquals(AodHostPhase.TRANSITIONING, AodKeyguardPolicy.phaseAfterTransitionFrame(null, true))
        assertEquals(AodHostPhase.TRANSITIONING, AodKeyguardPolicy.phaseAfterTransitionFrame(false, true))
    }

    @Test fun settledAodUsesFullAodRenderingDuoOrTheLegacyLease() {
        assertEquals(KeyguardRowOwner.AOD_DUO, decide(AodHostPhase.DOZING, aodDuo = true).owner)
        val native = decide(AodHostPhase.DOZING)
        assertEquals(KeyguardRowOwner.AOD_NATIVE, native.owner)
        assertTrue(native.aodMarksNativeRow)
        assertFalse(native.keepsNativeRow)
        // A ROM without full-AOD rendering keeps the lease so the fade never exposes stock icons.
        val legacy = decide(AodHostPhase.DOZING, fullAodRendering = false)
        assertEquals(KeyguardRowOwner.AOD_HOLD, legacy.owner)
        assertFalse(legacy.keepsNativeRow)
        // Without an AOD renderer or options the row stays the host's.
        val stock = decide(AodHostPhase.DOZING, aodCustomized = false, fullAodRendering = false)
        assertEquals(KeyguardRowOwner.NONE, stock.owner)
        assertTrue(stock.keepsNativeRow)
    }

    @Test fun leavingAodReleasesOwnershipInTheSamePass() {
        // The decision for the lit lock screen keeps the row, which is what releases a stale AOD
        // suppression after wake or unlock instead of replaying it.
        val afterExit = decide(AodHostPhase.LIT, aodCustomized = true)
        assertTrue(afterExit.keepsNativeRow)
        assertEquals(KeyguardRowOwner.NONE, afterExit.owner)
        val duringAod = decide(AodHostPhase.DOZING, aodCustomized = true)
        assertFalse(duringAod.keepsNativeRow)
    }
}
