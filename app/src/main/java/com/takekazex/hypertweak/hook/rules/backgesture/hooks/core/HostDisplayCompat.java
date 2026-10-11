package com.takekazex.hypertweak.hook.rules.backgesture.hooks.core;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** HyperTweak: preserve the context's display, including the non-visual-context fallback. */
public final class HostDisplayCompat {
    private HostDisplayCompat() {}

    public static Display getDisplay(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Display display = context.getDisplay();
            if (display != null) {
                return display;
            }
        } catch (UnsupportedOperationException ignored) {
            // WindowManagerImpl.getDefaultDisplay() also fell back to display 0 here.
        }
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        return manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
    }
}
