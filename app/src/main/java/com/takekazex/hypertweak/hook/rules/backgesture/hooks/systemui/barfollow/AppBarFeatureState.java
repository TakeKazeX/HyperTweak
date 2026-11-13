package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow;

/** Fence asynchronous attachment and preference callbacks across disable/re-enable and reload. */
final class AppBarFeatureState {
    private boolean enabled;
    private boolean suspended;
    private long generation;

    synchronized void setEnabled(boolean requested) {
        if (enabled != requested) { enabled = requested; generation++; }
    }

    synchronized boolean enabled() { return enabled && !suspended; }
    synchronized long generation() { return generation; }
    synchronized boolean accepts(long expected) { return enabled() && generation == expected; }

    synchronized void suspend() {
        suspended = true;
        generation++;
    }

    synchronized void resumeAfterRejectedReload() {
        suspended = false;
        generation++;
    }
}
