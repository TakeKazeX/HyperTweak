package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One opportunity to upgrade the same physical null-navigation stream after Recents ends. */
final class RecentsIdleRetry {
    private final Object session;
    private final Object input;
    private final long epoch;
    private final String focus;
    private final AtomicBoolean consumed = new AtomicBoolean();

    RecentsIdleRetry(Object session, Object input, long epoch, String focus) {
        this.session = session;
        this.input = input;
        this.epoch = epoch;
        this.focus = focus;
    }

    boolean claim(Object currentSession, Object currentInput, long currentEpoch,
                  String currentFocus, boolean fingerDown, boolean released,
                  boolean recentsIdle) {
        // Even a rejected callback is retired; restoring an old identity must not revive it.
        return !consumed.getAndSet(true) && session != null && input != null && focus != null
                && session == currentSession && input == currentInput && epoch == currentEpoch
                && Objects.equals(focus, currentFocus) && fingerDown && !released && recentsIdle;
    }
}
