package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContextualSearchRequestPolicyTest {
    @Test public void nativeWindowAdmissionIgnoresDrawingAndRetainsImmersiveBoundaries() {
        // A native completion already proves the gesture was detected. Normal window
        // state admits it even when the module makes the pill alpha/height zero.
        assertTrue(ContextualSearchRequestPolicy.allowsNativeCompletion(0, false, true, false));
        assertFalse(ContextualSearchRequestPolicy.allowsNativeCompletion(2, false, true, false));
        assertFalse(ContextualSearchRequestPolicy.allowsNativeCompletion(1, false, true, false));
        // A transient navigation reveal is valid while its window flag remains hidden.
        assertTrue(ContextualSearchRequestPolicy.allowsNativeCompletion(2, true, true, false));
        assertFalse(ContextualSearchRequestPolicy.allowsNativeCompletion(2, false, true, false));
        assertTrue(ContextualSearchRequestPolicy.allowsNativeCompletion(0, false, true, false));
        assertFalse(ContextualSearchRequestPolicy.allowsNativeCompletion(0, false, false, false));
        assertFalse(ContextualSearchRequestPolicy.allowsNativeCompletion(2, true, false, false));
        assertFalse(ContextualSearchRequestPolicy.allowsNativeCompletion(0, false, true, true));
    }

    @Test public void hiddenCompletionCannotReplayAfterBarsAppearButNextPressCanStart() {
        ContextualSearchRequestPolicy policy = new ContextualSearchRequestPolicy();
        assertFalse(policy.admit(1000, 1000, false));
        assertFalse(policy.admit(1100, 1000, true));
        assertFalse(policy.admit(1100, 1100, true));
        assertTrue(policy.admit(1700, 1700, true));
        assertFalse(policy.admit(1800, 1800, true));
        assertTrue(policy.admit(2300, 2300, true));
    }

    @Test public void delayedFutureAndMissingRequestsNeverStartOrPoisonNextGesture() {
        ContextualSearchRequestPolicy policy = new ContextualSearchRequestPolicy();
        assertFalse(policy.admit(5000, -1, true));
        assertFalse(policy.admit(5000, 5001, true));
        assertFalse(policy.admit(5000, 3000, true));
        assertTrue(policy.admit(5000, 4999, true));
        assertFalse(policy.admit(5100, 4500, true));
        assertTrue(policy.admit(5600, 5600, true));
    }

}
