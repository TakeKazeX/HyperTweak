package com.takekazex.hypertweak.hook.rules.backgesture;

/** Visibility only: upstream generations never enter HyperTweak's input arbiter. */
public final class UpstreamOverviewState {
    private long owner;
    private long generation;
    private Boolean visible;

    public synchronized boolean offer(long nextOwner, long nextGeneration, Boolean overview) {
        if (nextOwner <= 0L || nextGeneration <= 0L || nextOwner < owner
                || (nextOwner == owner && nextGeneration < generation)) return false;
        if (nextOwner != owner || nextGeneration != generation) visible = null;
        owner = nextOwner;
        generation = nextGeneration;
        if (overview != null) visible = overview;
        return true;
    }

    public synchronized Boolean visible() { return visible; }
}
