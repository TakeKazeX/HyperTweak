package com.takekazex.hypertweak.hook.rules.backgesture;

// Adapted for HyperTweak from wxxsfxyzm/MiuiBackGestureHook (Apache-2.0).
//
// Upstream's leaf is `MiuiBackGestureHook extends HotReloadHookRuntime`, an empty class whose
// only job is to be the LSPosed entry point. HyperTweak is driven by BaseHooker instead, so this
// leaf carries the process entry points, the registrar binding and the launcher-route gate.

import android.util.Log;

import com.takekazex.hypertweak.hook.Preferences;
import com.takekazex.hypertweak.util.LauncherVersion;

public final class AospBackGestureRuntime extends CrossTaskWallpaperRuntime {

    /**
     * SystemUI entry point. Installs upstream's SystemUI hooks, then HyperTweak's cross-task
     * wallpaper hooks on top. Both are independent of the launcher version.
     */
    @Override
    public void installSystemUiHooks(ClassLoader classLoader, HookRegistrar registrar) {
        super.installSystemUiHooks(classLoader, registrar);
        installCrossTaskWallpaperHooks(classLoader);
        // Warm before any gesture; retries until SystemUI's application context exists.
        ensureWallpaperCacheReady("systemUiInstall");
    }

    /**
     * Launcher entry point, split into two independently gated halves.
     *
     * <p><b>Input arbitration</b> is not optional. MIUI's {@code GestureStubView} owns the screen
     * edges for every app, so without
     * {@code GesturesBackTouchProcessor.onPointerEvent} publishing an accepted-input token
     * SystemUI's {@code onNativeDown} never starts a gesture — in any app. Installing only this
     * half keeps the whole AOSP back gesture alive with no launcher animation hooks attached.
     *
     * <p><b>Predictive return-home</b> is the experimental half: ~35 more `com.miui.home` classes
     * driving the launcher's own animation, and the part that is gated by the setting and by
     * {@link LauncherVersion} (Launcher 8 ships `hasCode="false"`, so none of them resolve).
     */
    @Override
    public void installMiuiHomeHooks(ClassLoader classLoader, HookRegistrar registrar) {
        boolean supported = LauncherVersion.INSTANCE.isRouteSupported(classLoader);
        if (!isMiuiHomeRouteEnabled(supported)) {
            moduleLog(Log.INFO, TAG, "Installing MiuiHome input arbitration only"
                    + ", reason=returnHomeRouteDisabled"
                    + ", launcherMajor=" + LauncherVersion.INSTANCE.getMajor()
                    + ", launcherVersion=" + LauncherVersion.INSTANCE.getVersionName()
                    + ", supported=" + supported);
            installMiuiHomeInputArbitrationOnly(classLoader, registrar);
            return;
        }
        super.installMiuiHomeHooks(classLoader, registrar);
        // HyperTweak-only: lets a module-driven performAppToHome() reach the launcher's real
        // app-to-icon animation instead of its remote-anim-failed branch.
        hookMiuiHomeAppToHomeGate(classLoader);
    }

    /**
     * The subset of upstream's launcher hooks that hands gestures to SystemUI and mirrors the
     * launcher state SystemUI needs to classify them. No return-home animation hooks.
     */
    private void installMiuiHomeInputArbitrationOnly(
            ClassLoader classLoader, HookRegistrar registrar) {
        processName = MIUI_HOME;
        hookRegistrar = registrar;
        runtimeClassLoader = classLoader;
        try {
            Class<?> gestureStubClass = Class.forName(
                    MIUI_HOME_GESTURE_STUB, false, classLoader);
            hookMiuiHomeGestureStubShow(gestureStubClass);
            Class<?> processorClass = Class.forName(
                    MIUI_HOME_GESTURE_PROCESSOR, false, classLoader);
            hookMiuiHomeGestureInputArbiter(processorClass, gestureStubClass);
            Class<?> recentsContainerClass = Class.forName(
                    MIUI_HOME_RECENTS_CONTAINER, false, classLoader);
            hookMiuiHomeRecentsActualState(recentsContainerClass);
            Class<?> taskViewClass = Class.forName(
                    MIUI_HOME_TASK_VIEW, false, classLoader);
            hookMiuiHomeRecentsTaskLaunch(taskViewClass);
            hookMiuiHomeFullscreenState(classLoader);
            hookMiuiHomeFreeformBackTouchability(classLoader);
            moduleLog(Log.INFO, TAG, "Enabled MiuiHome input arbitration"
                    + ", predictiveReturnHome=false"
                    + ", blocksLegacyGestureProcessor=true"
                    + ", requiresAcceptedInputToken=true");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install MiuiHome input arbitration", throwable);
        }
    }

    /**
     * Gates only the predictive return-home animation. Off by default on Launcher 8 and newer; a
     * user who explicitly flipped the switch keeps their choice.
     */
    private boolean isMiuiHomeRouteEnabled(boolean supported) {
        if (Preferences.INSTANCE.getBoolean(
                Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS_USER_SET, false)) {
            return Preferences.INSTANCE.getBoolean(
                    Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS, supported);
        }
        return supported;
    }

    @Override
    public void restoreHotReloadState(Object savedState) {
        super.restoreHotReloadState(savedState);
        ensureWallpaperCacheReady("hotReload");
    }

    /** BaseHooker tears the process down through this; release the wallpaper cache with it. */
    public void prepareHotReload() {
        shutdownWallpaperCache("hotReload");
    }
}
