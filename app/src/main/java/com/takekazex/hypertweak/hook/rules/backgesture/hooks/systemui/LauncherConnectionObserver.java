package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.content.ComponentName;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Observes SystemUI's existing connection; never binds or starts a Launcher service. */
public final class LauncherConnectionObserver implements AutoCloseable {
    public interface Sink {
        void connected(LauncherConnectionObserver source, Object receipt);
    }

    private final Object service;
    private final Handler owner;
    private final Field proxyField;
    private final Method add, remove;
    private final Object listener;
    private final LauncherConnectionSession session = new LauncherConnectionSession();
    private final BiConsumer<String, Throwable> log;
    private volatile boolean closed;

    public LauncherConnectionObserver(Object service, LongSupplier generation, Sink sink,
            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.service = service;
        this.log = log;
        Class<?> ownerClass = service.getClass();
        if (!ownerClass.getName().equals("com.android.systemui.LauncherProxyService"))
            throw new IllegalStateException("Unknown Launcher connection owner");
        owner = (Handler) field(ownerClass, "mHandler").get(service);
        if (owner == null || Looper.myLooper() != owner.getLooper())
            throw new IllegalStateException("Launcher connection requires its owner Looper");
        ComponentName component = (ComponentName) field(ownerClass, "mRecentsComponentName").get(service);
        if (component == null || !"com.miui.home".equals(component.getPackageName()))
            throw new IllegalStateException("Launcher connection belongs to another package");
        proxyField = field(ownerClass, "mLauncherProxy");
        Class<?> type = Class.forName(ownerClass.getName() + "$LauncherProxyListener",
                false, ownerClass.getClassLoader());
        Method connection = type.getMethod("onConnectionChanged", boolean.class);
        if (!type.isInterface() || connection.getReturnType() != void.class)
            throw new NoSuchMethodException("Launcher connection callback changed");
        for (Method method : type.getMethods())
            if (method.getReturnType() != void.class)
                throw new NoSuchMethodException("Unexpected Launcher listener result");
        add = ownerClass.getMethod("addCallback", type);
        remove = ownerClass.getMethod("removeCallback", type);
        if (add.getReturnType() != void.class || remove.getReturnType() != void.class)
            throw new NoSuchMethodException("Launcher listener registration changed");
        listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals": return args != null && args.length == 1 && proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "ModuleLauncherConnectionListener";
                    default: return null;
                }
            }
            if (!closed && method.equals(connection)) {
                try {
                    assertOwner();
                    IBinder binder = Boolean.TRUE.equals(args[0]) ? currentBinder() : null;
                    LauncherConnectionSession.Receipt receipt = session.observe(binder, generation.getAsLong());
                    if (receipt != null) sink.connected(this, receipt);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    session.observe(null, 0L);
                    report("Launcher connection receipt unavailable", failure);
                }
            }
            return null; // Other native listener callbacks have void/no-op defaults.
        });
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private void assertOwner() {
        if (Looper.myLooper() != owner.getLooper())
            throw new IllegalStateException("Launcher callback left its owner Looper");
    }

    private IBinder currentBinder() throws IllegalAccessException {
        Object proxy = proxyField.get(service);
        IBinder binder = proxy instanceof IInterface ? ((IInterface) proxy).asBinder() : null;
        return binder != null && binder.isBinderAlive() ? binder : null;
    }

    public boolean ownsService(Object candidate) { return service == candidate; }

    public void start() throws ReflectiveOperationException {
        assertOwner();
        try { add.invoke(service, listener); }
        catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }

    public boolean isCurrent(Object receipt, long generation) {
        if (closed || !(receipt instanceof LauncherConnectionSession.Receipt)) return false;
        try {
            assertOwner();
            return session.accepts((LauncherConnectionSession.Receipt) receipt, currentBinder(), generation);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            report("Launcher reconnection validation failed", failure);
            return false;
        }
    }

    private void report(String message, Throwable failure) {
        try { log.accept(message, failure); }
        catch (RuntimeException ignored) { } // Preserve native callback delivery.
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        session.close();
        Runnable cleanup = () -> {
            try { remove.invoke(service, listener); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                report("Launcher listener removal failed", failure);
            }
        };
        if (Looper.myLooper() == owner.getLooper()) cleanup.run();
        else if (!owner.post(cleanup)) report("Launcher owner rejected listener removal", null);
    }
}
