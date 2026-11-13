package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Main-owner-only optional resources. Failed starts/cleanup retain exact disposal ownership. */
public final class DiagnosticBindings implements AutoCloseable {
    public interface Open { void open(Consumer<Object> retain) throws ReflectiveOperationException; }
    public interface Dispose { void dispose(Object handle) throws ReflectiveOperationException; }
    private final List<Object> handles = new ArrayList<>();
    private final Dispose dispose;
    public DiagnosticBindings(Dispose dispose) { this.dispose = dispose; }
    public int size() { return handles.size(); }
    public void setEnabled(boolean enabled, Open open) throws ReflectiveOperationException {
        if (!enabled) { close(); return; }
        if (!handles.isEmpty()) return;
        try { open.open(handles::add); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            try { close(); } catch (ReflectiveOperationException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @Override public void close() throws ReflectiveOperationException {
        ReflectiveOperationException failure = null;
        for (int i = handles.size()-1; i >= 0; i--) {
            try { dispose.dispose(handles.get(i)); handles.remove(i); }
            catch (ReflectiveOperationException cause) {
                if (failure == null) failure = cause; else failure.addSuppressed(cause);
            }
        }
        if (failure != null) throw failure;
    }
}
