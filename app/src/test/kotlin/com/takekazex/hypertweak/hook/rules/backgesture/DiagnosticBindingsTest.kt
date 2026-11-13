package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow.DiagnosticBindings
import org.junit.Assert.*
import org.junit.Test

class DiagnosticBindingsTest {
    @Test fun disabledCreatesNothingAndRepeatedEnableDisableDoesNotAccumulateCollectors() {
        var created = 0; var disposed = 0
        val group = DiagnosticBindings { disposed++ }
        val open = DiagnosticBindings.Open { retain -> repeat(3) { retain.accept(++created) } }
        repeat(20) {
            group.setEnabled(false, open); assertEquals(0, group.size())
            group.setEnabled(true, open); group.setEnabled(true, open); assertEquals(3, group.size())
            group.setEnabled(false, open); assertEquals(0, group.size())
        }
        assertEquals(60, created); assertEquals(created, disposed)
    }
    @Test fun partialStartIsDisposedAndFailedDisposalRemainsOwnedForRetry() {
        var fail = true
        val group = DiagnosticBindings { if (fail) throw ReflectiveOperationException("busy") }
        assertThrows(ReflectiveOperationException::class.java) {
            group.setEnabled(true) { retain -> retain.accept("first"); throw ReflectiveOperationException("second") }
        }
        assertEquals(1, group.size()); fail = false; group.close(); assertEquals(0, group.size())
    }
}
