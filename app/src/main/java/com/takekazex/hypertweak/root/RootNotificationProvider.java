package com.takekazex.hypertweak.root;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.Method;

/** Same external-provider ownership and caller attribution as the platform content command. */
final class RootNotificationProvider implements AutoCloseable {
    private static final String AUTHORITY = "statusbar.notification";
    private final Object activityManager;
    private final IBinder token = new Binder();
    private final int user;
    private final Method release;
    private final Method call;
    private final Object attribution;
    private Object provider;
    private boolean acquired;

    RootNotificationProvider(int user) throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("Root provider access requires uid 0");
        this.user = user;
        activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Class<?> manager = Class.forName("android.app.IActivityManager");
        Method acquire = manager.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class);
        release = manager.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class);
        Class<?> source = Class.forName("android.content.AttributionSource");
        attribution = source.getConstructor(int.class, String.class, String.class).newInstance(Process.myUid(), "root", null);
        call = Class.forName("android.content.IContentProvider").getMethod("call", source, String.class, String.class, String.class, Bundle.class);
        java.lang.reflect.Field field = Class.forName("android.app.ContentProviderHolder").getField("provider");
        Object holder = acquire.invoke(activityManager, AUTHORITY, user, token, "*hypertweak-banner*");
        if (holder == null) throw new IllegalStateException("Notification provider unavailable for user " + user);
        acquired = true;
        try {
            provider = field.get(holder);
            if (provider == null) throw new IllegalStateException("Notification provider has no Binder interface");
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    Bundle call(String method, Bundle arguments) throws Exception {
        if (!acquired) throw new IllegalStateException("Notification provider already released");
        return (Bundle) call.invoke(provider, attribution, AUTHORITY, method, null, arguments);
    }

    @Override public void close() throws Exception {
        if (acquired) {
            acquired = false;
            release.invoke(activityManager, AUTHORITY, token, user);
            provider = null;
        }
    }
}
