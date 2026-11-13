package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import java.util.concurrent.Executor;

/** One queued MOVE on the verified serial Shell executor. Terminal tasks remain FIFO after seal. */
public final class ShellMoveQueue {
    public interface Consumer { void apply(float x, float y, float distance, boolean crossed, boolean dispatch); }
    private final Executor executor;
    private final Consumer consumer;
    private final Runnable drain = this::drain;
    private boolean queued, sealed, pending, crossed;
    private float x, y, distance, crossX, crossY, crossDistance;
    private boolean dispatch, crossDispatch;

    public ShellMoveQueue(Executor executor, Consumer consumer) {
        this.executor = executor; this.consumer = consumer;
    }
    public synchronized boolean submit(float x, float y, float distance, boolean crossed, boolean dispatch) {
        if (sealed) return false;
        this.x = x; this.y = y; this.distance = distance; this.dispatch = dispatch;
        if (crossed && !this.crossed) {
            this.crossed = true; crossX = x; crossY = y; crossDistance = distance; crossDispatch = dispatch;
        }
        pending = true;
        if (!queued) {
            queued = true;
            // Enqueue before a racing seal returns, including when the executor runs inline.
            try { executor.execute(drain); }
            catch (RuntimeException failure) { queued = false; pending = false; this.crossed = false; throw failure; }
        }
        return true;
    }
    public synchronized void seal() { sealed = true; }
    private void drain() {
        final float latestX, latestY, latestDistance, thresholdX, thresholdY, thresholdDistance;
        final boolean emitCross, latestDispatch, thresholdDispatch;
        synchronized (this) {
            if (!pending) { queued = false; return; }
            latestX = x; latestY = y; latestDistance = distance; latestDispatch = dispatch;
            emitCross = crossed; thresholdX = crossX; thresholdY = crossY;
            thresholdDistance = crossDistance; thresholdDispatch = crossDispatch;
            pending = false; crossed = false; queued = false;
        }
        // Never erase a crossing followed by a retreat while the owner was busy.
        final boolean separateCross = emitCross && (thresholdX != latestX || thresholdY != latestY
                || thresholdDistance != latestDistance);
        if (separateCross) consumer.apply(thresholdX, thresholdY, thresholdDistance, true, thresholdDispatch);
        consumer.apply(latestX, latestY, latestDistance, emitCross && !separateCross, latestDispatch);
    }
}
