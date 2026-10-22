package com.takekazex.hypertweak.hook.rules.aod

import org.junit.Assert.*
import org.junit.Test

class AodRowPolicyTest {
    @Test fun nativePreviewCannotAcquireABatteryStubThroughModuleDefaults() {
        // Current native update(size=2/4/6) explicitly asks for false: those layouts have no
        // battery/step container. Every default or customised module row must preserve that veto.
        listOf(
            AodIconSettings(true, false, true, false, 1),
            AodIconSettings(false, true, true, true, 0),
            AodIconSettings(false, false, false, false, 1)
        ).forEach { settings -> assertFalse(AodRowPolicy.showNativeRow(false, settings.showRow)) }
    }

    @Test fun nativeRuntimeRowCanBeHiddenThenRestoredWithoutChangingEligibility() {
        assertTrue(AodRowPolicy.showNativeRow(true, true))
        assertFalse(AodRowPolicy.showNativeRow(true, false))
        assertTrue(AodRowPolicy.showNativeRow(true, true))
        assertFalse(AodRowPolicy.showNativeRow(false, true))
    }
}
