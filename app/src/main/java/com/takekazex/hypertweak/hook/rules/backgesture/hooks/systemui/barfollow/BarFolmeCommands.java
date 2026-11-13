package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow;

/** Owner-Looper bookkeeping for independently superseded Folme properties. */
final class BarFolmeCommands {
    private final Object[] owners = new Object[4];
    private int running;
    private boolean cancelled;

    void replace(Object listener, int offset, int count) {
        if (running == 0) cancelled = false;
        for (int i = offset; i < offset + count; i++) {
            owners[i] = listener;
            running |= 1 << i;
        }
    }

    boolean owns(Object listener, int channel) {
        return listener != null && owners[channel] == listener;
    }

    boolean contains(Object listener) {
        for (int i = 0; i < 4; i++) if (owns(listener, i)) return true;
        return false;
    }

    // 0=stale or another command still running; 1=all complete; 2=cancelled.
    int terminal(Object listener, boolean wasCancelled) {
        int finished = 0;
        for (int i = 0; i < 4; i++) {
            if (owns(listener, i)) {
                finished |= running & (1 << i);
                owners[i] = null;
            }
        }
        if (finished == 0) return 0;
        running &= ~finished;
        cancelled |= wasCancelled;
        return running == 0 ? (cancelled ? 2 : 1) : 0;
    }

    void reset(int offset, int count) {
        for (int i = offset; i < offset + count; i++) {
            owners[i] = null;
            running &= ~(1 << i);
        }
        if (running == 0) cancelled = false;
    }
}
