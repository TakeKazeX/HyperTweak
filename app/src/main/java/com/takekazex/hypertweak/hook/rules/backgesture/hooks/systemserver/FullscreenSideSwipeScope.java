package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemserver;

import java.util.List;

import io.github.libxposed.api.XposedInterface;

/** Identifies requests made synchronously by one native side-swipe callback. */
final class FullscreenSideSwipeScope {
    private final ThreadLocal<Request> activeRequest = new ThreadLocal<>();

    Object intercept(XposedInterface.Chain chain, Object displayPolicy, Object navigationBar)
            throws Throwable {
        Request previous = activeRequest.get();
        activeRequest.set(new Request(displayPolicy, navigationBar));
        try {
            return chain.proceed();
        } finally {
            if (previous == null) {
                activeRequest.remove();
            } else {
                activeRequest.set(previous);
            }
        }
    }

    Object[] requestArguments(Object displayPolicy, List<Object> arguments) {
        Request request = activeRequest.get();
        if (request == null || request.displayPolicy != displayPolicy
                || request.navigationBar == null || arguments.size() != 2
                || arguments.get(0) != request.navigationBar
                || !Boolean.FALSE.equals(arguments.get(1))) {
            return null;
        }
        // Keep Xiaomi's null-target path even when our gesture Insets made the
        // NavigationBar the native left/right gesture host. The native callback
        // still owns exclusions, locking and its remaining cleanup.
        return new Object[]{null, Boolean.FALSE};
    }

    private static final class Request {
        final Object displayPolicy;
        final Object navigationBar;

        Request(Object displayPolicy, Object navigationBar) {
            this.displayPolicy = displayPolicy;
            this.navigationBar = navigationBar;
        }
    }
}
