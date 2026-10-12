package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One prepared asset; a miss never runs the loader on the gesture thread. */
final class PreparedWallpaperCache<T> implements AutoCloseable {
    interface Loader<T> { T load() throws Exception; }
    interface Reader<T, R> { R read(T value) throws Exception; }
    private final Executor worker;
    private final Consumer<T> dispose;
    private final Consumer<Throwable> failure;
    private long generation;
    private boolean requested, loading, closed;
    private T value;

    PreparedWallpaperCache(Executor worker, Consumer<T> dispose, Consumer<Throwable> failure) {
        this.worker = worker;
        this.dispose = dispose;
        this.failure = failure;
    }

    synchronized <R> R use(Reader<T, R> reader) throws Exception {
        return !closed && value != null
                ? reader.read(value) : null;
    }

    void request(Loader<T> loader) {
        final long ticket;
        synchronized (this) {
            if (closed || requested) return;
            requested = true;
            loading = true;
            ticket = ++generation;
        }
        try {
            worker.execute(() -> {
                T prepared = null;
                try {
                    prepared = loader.load();
                } catch (Throwable error) {
                    failure.accept(error);
                }
                synchronized (PreparedWallpaperCache.this) {
                    if (!closed && generation == ticket) { value = prepared; loading = false; }
                    else if (prepared != null) dispose.accept(prepared);
                }
            });
        } catch (RuntimeException error) {
            synchronized (this) { if (generation == ticket) loading = false; }
            failure.accept(error);
        }
    }

    synchronized void invalidate() {
        if (closed) return;
        ++generation;
        requested = false;
        loading = false;
        clear();
    }

    synchronized void retryMissing() {
        if (!closed && value == null && !loading) {
            ++generation;
            requested = false;
        }
    }

    private void clear() {
        if (value != null) dispose.accept(value);
        value = null;
    }

    @Override public synchronized void close() {
        closed = true;
        ++generation;
        clear();
    }
}
