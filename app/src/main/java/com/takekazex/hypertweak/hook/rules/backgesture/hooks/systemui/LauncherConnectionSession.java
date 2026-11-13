package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

/** Identity receipts for a deferred notification from the native Launcher connection. */
final class LauncherConnectionSession {
    static final class Receipt {
        final Object binder;
        final long generation;

        Receipt(Object binder, long generation) {
            this.binder = binder;
            this.generation = generation;
        }
    }

    private Receipt current;
    private boolean closed;

    synchronized Receipt observe(Object binder, long generation) {
        if (closed) return null;
        if (binder == null || generation <= 0L) {
            current = null;
            return null;
        }
        if (current != null && current.binder == binder && current.generation == generation)
            return null;
        return current = new Receipt(binder, generation);
    }

    synchronized boolean accepts(Receipt receipt, Object binder, long generation) {
        return !closed && receipt != null && current == receipt
                && receipt.binder == binder && receipt.generation == generation
                && generation > 0L;
    }

    synchronized void close() { closed = true; current = null; }
}
