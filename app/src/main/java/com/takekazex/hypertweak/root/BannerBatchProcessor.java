package com.takekazex.hypertweak.root;

import java.util.ArrayList;
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
        public Channel(String id, int importance, boolean blockedGroup) {
            this.id = id; this.importance = importance; this.blockedGroup = blockedGroup;
        }
    }
    public interface Backend {
        boolean enabled(App app) throws Exception;
        List<Channel> channels(App app) throws Exception;
        boolean canFloat(App app, Channel channel) throws Exception;
        void allowFloat(App app, Channel channel) throws Exception;
    }
    public static final class Result {
        public int apps, channels, skipped, failed, changed;
        /** Bounded diagnostic trail for partial failures. */
        public final List<String> failures = new ArrayList<>();
    }
    private static final int MAX_FAILURE_NOTES = 20;

    private static String scope(App app, Channel channel) {
        return app.pkg + (channel == null ? "" : ":" + channel.id);
    }

    private static void note(Result result, App app, Channel channel, Exception failure) {
        if (result.failures.size() >= MAX_FAILURE_NOTES) return;
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        if (message != null && message.length() > 80) message = message.substring(0, 80);
        result.failures.add(scope(app, channel) + " " + cause.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message));
    }
    private static boolean allow(Backend backend, App app, Channel channel, boolean apply) throws Exception {
        // An effective true may only come from a whitelist or our live bypass. Persist an
        // explicit true even then, so the batch survives turning the bypass off.
        if (!apply) { backend.canFloat(app, channel); return false; }
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
                    // Only DEFAULT (3) and higher; never promote a quiet channel.
                    if (channel.importance < 3 || channel.blockedGroup) { result.skipped++; continue; }
                    if (!countedApp) {
                        countedApp = true;
                        result.apps++;
                        if (allow(backend, app, null, apply)) result.changed++;
                    }
                    result.channels++;
                    try {
                        if (allow(backend, app, channel, apply)) result.changed++;
                    } catch (Exception failure) { result.failed++; note(result, app, channel, failure); }
                }
            } catch (Exception failure) { result.failed++; note(result, app, null, failure); }
        }
        return result;
    }
}
