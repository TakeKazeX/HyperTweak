package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemserver;

/** Identifies synchronous status-alpha writes from a proven native gesture owner. */
final class ServerBarGestureScope {
    private static final class Request {
        final Object controller;
        final boolean snapshot;
        Request(Object controller, boolean snapshot) { this.controller = controller; this.snapshot = snapshot; }
    }
    private final ThreadLocal<Request> active = new ThreadLocal<>();

    static boolean isPendingStartState(int state) {
        return state == -1 || state == 3 || state == 6 || state == 12 || state == 14;
    }

    Scope enter(Object controller, int state, boolean ownsLiveWindow) {
        Request previous = active.get();
        if (controller != null && state == 6 && ownsLiveWindow) active.set(new Request(controller, true));
        else active.remove();
        return new Scope(previous);
    }

    Scope enterRecentsStart(Object controller, boolean appToRecents) {
        Request previous = active.get();
        if (controller != null && appToRecents) active.set(new Request(controller, false));
        else active.remove();
        return new Scope(previous);
    }

    boolean keepsLiveStatus(Object controller) {
        Request request = active.get();
        return request != null && request.snapshot && request.controller == controller;
    }

    boolean keepsAppDuringStart(Object controller, float homeAlpha, float appAlpha) {
        Request request = active.get();
        return request != null && !request.snapshot && request.controller == controller
                && homeAlpha == 1f && appAlpha == 0f;
    }

    final class Scope implements AutoCloseable {
        private final Request previous;
        private boolean closed;

        private Scope(Request previous) { this.previous = previous; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) active.remove();
            else active.set(previous);
        }
    }
}
