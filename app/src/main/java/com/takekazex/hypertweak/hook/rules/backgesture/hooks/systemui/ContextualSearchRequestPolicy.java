package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

/** Main-thread admission for native completions; contains no saved visibility snapshot. */
final class ContextualSearchRequestPolicy {
    private long lastRequest = -1;

    boolean admit(long now, long requestedAt, boolean visible) {
        if (requestedAt < 0 || requestedAt > now || now - requestedAt > 1500
                || (lastRequest >= 0 && requestedAt - lastRequest < 500)) {
            return false;
        }
        // Remember rejected hidden requests too: a duplicate from an old native callback
        // must not launch when the first request itself has just revealed the bars.
        lastRequest = requestedAt;
        return visible;
    }

    static boolean allowsNativeCompletion(int windowState, boolean transientShown,
            boolean attached, boolean keyguardLocked) {
        // Drawing suppression and zero-height layouts do not disable the native
        // input owner. Only the app's actual immersive window state gates CTS.
        return (windowState == 0 || transientShown) && attached && !keyguardLocked;
    }
}
