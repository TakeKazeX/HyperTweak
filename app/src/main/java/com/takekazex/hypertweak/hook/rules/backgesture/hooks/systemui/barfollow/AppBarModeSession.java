package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow;

import java.util.Objects;
import java.util.function.Consumer;

/** Main-owner inputs and failure boundary for the private bar's Flow collectors. */
final class AppBarModeSession implements AutoCloseable {
    interface ModeConsumer {
        void accept(Object mode, boolean awake);
    }

    private final ModeConsumer apply;
    private final Consumer<RuntimeException> onFailure;
    private Object mode;
    private Boolean awake;
    private boolean stopped;

    AppBarModeSession(ModeConsumer apply, Consumer<RuntimeException> onFailure) {
        this.apply = Objects.requireNonNull(apply);
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    Consumer<Object> guard(Consumer<Object> consumer) {
        return value -> {
            if (stopped) return;
            try {
                consumer.accept(value);
            } catch (RuntimeException failure) {
                close();
                try {
                    onFailure.accept(failure);
                } catch (RuntimeException reportingFailure) {
                    if (reportingFailure != failure) failure.addSuppressed(reportingFailure);
                }
            }
        };
    }

    void mode(Object value) {
        if (stopped) return;
        mode = Objects.requireNonNull(value, "Missing native status-bar mode");
        applyIfReady();
    }

    void awake(Object value) {
        if (stopped) return;
        awake = (Boolean) Objects.requireNonNull(value, "Missing native wakefulness value");
        applyIfReady();
    }

    private void applyIfReady() {
        if (mode != null && awake != null) apply.accept(mode, awake);
    }

    @Override public void close() {
        stopped = true;
        mode = null;
        awake = null;
    }
}
