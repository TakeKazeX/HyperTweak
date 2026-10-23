package com.takekazex.hypertweak.root;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.List;

/** One-shot root entry point. No daemon, hooks, preference-file edits or binder transaction IDs. */
public final class NotificationBannerBatch implements AutoCloseable {
    private final Object notificationManager;
    private final RootNotificationProvider provider;
    private final Method channels;
    private final Method groups;
    private final Method enabled;

    private NotificationBannerBatch(int user) throws Exception {
        Class<?> services = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) services.getMethod("getService", String.class).invoke(null, "notification");
        notificationManager = Class.forName("android.app.INotificationManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> api = Class.forName("android.app.INotificationManager");
        channels = api.getMethod("getNotificationChannelsForPackage", String.class, int.class, boolean.class);
        groups = api.getMethod("getNotificationChannelGroupsForPackage", String.class, int.class, boolean.class);
        enabled = api.getMethod("areNotificationsEnabledForPackage", String.class, int.class);
        Class.forName("android.content.pm.ParceledListSlice").getMethod("getList");
        provider = new RootNotificationProvider(user);
    }

    private static List<?> list(Object slice) throws Exception {
        if (slice == null) throw new IllegalStateException("Missing package/channel list");
        return (List<?>) Class.forName("android.content.pm.ParceledListSlice").getMethod("getList").invoke(slice);
    }

    private Bundle arguments(String pkg, String channel) {
        Bundle args = new Bundle();
        args.putString("package", pkg);
        if (channel != null) args.putString("channel_id", channel);
        return args;
    }

    private boolean canFloat(String pkg, String channel) throws Exception {
        Bundle result = provider.call("canFloat", arguments(pkg, channel));
        if (result == null || !result.containsKey("canShowFloat")) {
            throw new IllegalStateException("MIUI notification provider unavailable");
        }
        return result.getBoolean("canShowFloat");
    }

    private void allowFloat(String pkg, String channel) throws Exception {
        Bundle args = arguments(pkg, channel);
        args.putBoolean("canShowFloat", true);
        provider.call("setFloat", args);
    }

    private JSONObject run(int user, boolean apply) throws Exception {
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        Object pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        List<?> installed = list(api.getMethod("getInstalledApplications", long.class, int.class).invoke(pm, 0L, user));
        // A provider mismatch must fail before changing any channel, not silently report success.
        if (!installed.isEmpty()) canFloat(((ApplicationInfo) installed.get(0)).packageName, null);
        java.util.ArrayList<BannerBatchProcessor.App> apps = new java.util.ArrayList<>();
        for (Object value : installed) {
            ApplicationInfo app = (ApplicationInfo) value;
            apps.add(new BannerBatchProcessor.App(app.packageName, app.uid));
        }
        BannerBatchProcessor.Result result = BannerBatchProcessor.run(apps, user, apply, new BannerBatchProcessor.Backend() {
            @Override public boolean enabled(BannerBatchProcessor.App app) throws Exception {
                return (Boolean) enabled.invoke(notificationManager, app.pkg, app.uid);
            }
            @Override public List<BannerBatchProcessor.Channel> channels(BannerBatchProcessor.App app) throws Exception {
                java.util.Set<String> blocked = new java.util.HashSet<>();
                for (Object value : list(groups.invoke(notificationManager, app.pkg, app.uid, false))) {
                    NotificationChannelGroup group = (NotificationChannelGroup) value;
                    if (group.isBlocked()) blocked.add(group.getId());
                }
                java.util.ArrayList<BannerBatchProcessor.Channel> result = new java.util.ArrayList<>();
                // includeDeleted=false excludes deleted channels; closed channels have importance 0.
                for (Object value : list(channels.invoke(notificationManager, app.pkg, app.uid, false))) {
                    NotificationChannel channel = (NotificationChannel) value;
                    result.add(new BannerBatchProcessor.Channel(channel.getId(), channel.getImportance(),
                            blocked.contains(channel.getGroup())));
                }
                return result;
            }
            @Override public boolean canFloat(BannerBatchProcessor.App app, BannerBatchProcessor.Channel channel) throws Exception {
                return NotificationBannerBatch.this.canFloat(app.pkg, channel == null ? null : channel.id);
            }
            @Override public void allowFloat(BannerBatchProcessor.App app, BannerBatchProcessor.Channel channel) throws Exception {
                NotificationBannerBatch.this.allowFloat(app.pkg, channel == null ? null : channel.id);
            }
        });
        return new JSONObject().put("apps", result.apps).put("channels", result.channels).put("skipped", result.skipped)
                .put("failed", result.failed).put("changed", result.changed).put("applied", apply)
                .put("failure_notes", new org.json.JSONArray(result.failures));
    }

    @Override public void close() throws Exception { provider.close(); }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root invocation required");
            if (args.length != 2) throw new IllegalArgumentException("Expected user and scan|apply");
            int user = Integer.parseInt(args[0]);
            if (user < 0 || !("scan".equals(args[1]) || "apply".equals(args[1]))) throw new IllegalArgumentException("Invalid scope");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            JSONObject result;
            try (NotificationBannerBatch batch = new NotificationBannerBatch(user)) {
                result = batch.run(user, "apply".equals(args[1]));
            }
            System.out.println("HYPERTWEAK_BANNERS " + result);
            System.exit(0);
        } catch (Throwable failure) {
            android.util.Log.e("HyperTweak-BannerBatch", "Banner batch helper failed", failure);
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            JSONObject error = new JSONObject();
            try { error.put("detail", trace.toString()); } catch (org.json.JSONException ignored) { }
            System.out.println("HYPERTWEAK_BANNERS_ERROR " + error);
            System.exit(1);
        }
    }
}
