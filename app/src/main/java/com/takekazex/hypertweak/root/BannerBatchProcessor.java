package com.takekazex.hypertweak.root;

import java.util.List;

/** Batch execution policy independent of Binder/ContentResolver, shared by scan and apply. */
public final class BannerBatchProcessor {
    public static final class App {
        public final String pkg;
        public final int uid;
        public App(String pkg, int uid) { this.pkg = pkg; this.uid = uid; }
    }
    public static final class Channel {
        public final String id;
        public final int importance;
        public final boolean blockedGroup;
        public final Object nativeChannel;
        public Channel(String id, int importance, boolean blockedGroup, Object nativeChannel) {
            this.id = id; this.importance = importance; this.blockedGroup = blockedGroup; this.nativeChannel = nativeChannel;
        }
    }
    public interface Backend {
        boolean enabled(App app) throws Exception;
        List<Channel> channels(App app) throws Exception;
        boolean canFloat(App app, Channel channel) throws Exception;
        void allowFloat(App app, Channel channel) throws Exception;
        void raiseImportance(App app, Channel channel) throws Exception;
    }
    public static final class Result {
        public int apps, channels, skipped, failed, changed;
    }
    private static boolean allow(Backend backend, App app, Channel channel, boolean apply) throws Exception {
        boolean allowed = backend.canFloat(app, channel);
        if (!apply || allowed) return false;
        backend.allowFloat(app, channel);
        if (!backend.canFloat(app, channel)) throw new IllegalStateException("Float setting readback failed");
        return true;
    }
    public static Result run(List<App> apps, int user, boolean apply, Backend backend) {
        Result result = new Result();
        // Validate the complete user scope before any mutation, including mixed-user input.
        for (App app : apps) if (app.uid < 0 || app.uid / 100000 != user) throw new IllegalArgumentException("Package user mismatch");
        for (App app : apps) {
            try {
                if (!backend.enabled(app)) { result.skipped++; continue; }
                List<Channel> channels = backend.channels(app);
                if (channels.isEmpty()) { result.skipped++; continue; }
                boolean countedApp = false;
                for (Channel channel : channels) {
                    if (channel.importance <= 0 || channel.blockedGroup) { result.skipped++; continue; }
                    if (!countedApp) {
                        countedApp = true;
                        result.apps++;
                        if (allow(backend, app, null, apply)) result.changed++;
                    }
                    result.channels++;
                    try {
                        if (apply && channel.importance < 4) { backend.raiseImportance(app, channel); result.changed++; }
                        if (allow(backend, app, channel, apply)) result.changed++;
                    } catch (Exception failure) { result.failed++; }
                }
            } catch (Exception failure) { result.failed++; }
        }
        return result;
    }
}
