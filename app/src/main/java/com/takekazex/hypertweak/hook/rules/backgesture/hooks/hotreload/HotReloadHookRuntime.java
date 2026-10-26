package com.takekazex.hypertweak.hook.rules.backgesture.hooks.hotreload;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Map;

import com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemserver.SystemServerHookRuntime;
import com.takekazex.hypertweak.hook.base.dex.DexRuntime;

public abstract class HotReloadHookRuntime extends SystemServerHookRuntime {

    public void refreshBackGesturePreferences() {
        Context context = miuiOverviewReceiverContext;
        if (!SYSTEM_UI.equals(processName) || context == null) return;
        new Handler(context.getMainLooper()).post(() -> {
            if (!acceptingBackInputInstalls || miuiOverviewReceiverContext != context) return;
            ensureContextualSearchStatePreferenceListener(context);
            for (NativeBackInputMonitor monitor : nativeInputMonitors.values()) monitor.refreshIndicatorIfIdle();
            publishSystemUiInputArbiterState(context,
                    systemUiInputArbiterMonitorCount.get() > 0, "preferencesRebound");
        });
    }

    /** A preference change applies to the next gesture; an owned session finishes normally. */
    public boolean shouldRunBackGestureHooks() {
        if (com.takekazex.hypertweak.hook.Preferences.INSTANCE.backGestureEnabled()) return true;
        for (NativeBackInputMonitor monitor : nativeInputMonitors.values()) {
            if (monitor.blocksHotReload()) return true;
        }
        return false;
    }

    public void checkHotReloadReady() {
        if (DexRuntime.INSTANCE.isResolving()) {
            moduleLog(Log.WARN, TAG,
                    "Deferred hot reload during SystemUI dex resolution"
                            + ", process=" + processName);
            throw new IllegalStateException("Back gesture runtime is busy");
        }
        if (contextualSearchBridgeCallsInFlight.get() != 0) {
            moduleLog(Log.WARN, TAG,
                    "Deferred hot reload during an authenticated contextual-search call"
                            + ", process=" + processName);
            throw new IllegalStateException("Back gesture runtime is busy");
        }
        PreparedBackTransitionHold heldTransition =
                preparedBackTransitionHold.get();
        if (heldTransition != null) {
            moduleLog(Log.WARN, TAG,
                    "Deferred hot reload while a prepared-back transition is held"
                            + ", process=" + processName
                            + ", "
                            + describePreparedBackTransitionHold(
                            heldTransition));
            throw new IllegalStateException("Back gesture runtime is busy");
        }
        for (NativeBackInputMonitor monitor
                : new ArrayList<>(nativeInputMonitors.values())) {
            if (monitor.blocksHotReload()) {
                moduleLog(Log.WARN, TAG,
                        "Deferred hot reload while a fixed Shell gesture session is active"
                                + ", process=" + processName
                                + ", state="
                                + monitor.describeActiveShellSession());
                throw new IllegalStateException("Back gesture runtime is busy");
            }
        }

    }
    public Object saveHotReloadState() {
        checkHotReloadReady();
        moduleLog(Log.INFO, TAG, "Hot reloading, build=" + BUILD_MARK
                + ", process=" + processName
                + ", hooks=" + hookHandles.size());
        boolean savedMiuiOverviewVisible = miuiOverviewVisible;
        boolean savedMiuiDrawerVisible = miuiDrawerVisible;
        boolean savedMiuiFolderVisible = miuiFolderVisible;
        boolean savedMiuiLauncherEditing = miuiLauncherEditing;
        miuiLauncherHomeSurfaceVisible = false;
        long savedMiuiLauncherDartStateOwnerEpoch =
                miuiLauncherDartStateOwnerEpoch;
        long savedMiuiOverviewDismissDeadline = miuiOverviewDismissPendingUntilUptime;
        returnHomeFinishTransferCandidate.remove();
        preparedBackTargetArrival.set(null);
        preparedBackTargetArrivalHookReady = false;
        preparedBackTerminalHookReady = false;
        preparedBackStartAnimationInvoker = null;
        freeformColorRootCandidate.set(null);
        freeformColorRootAdoption = null;
        backCommitCompositionHookReady = false;
        backFinishOpenAtomicHookReady = false;
        backFinishOpenCallerDeoptimized = false;
        acceptingOpenSnapshots = false;
        acceptingHeadlessNavBarLifecycle = false;
        synchronized (backInputLifecycleLock) {
            acceptingBackInputInstalls = false;
        }
        headlessNavBarLifecycleGeneration.incrementAndGet();
        openSnapshotGeneration.incrementAndGet();
        invalidateAllOpenTransitionSnapshots("hotReload");
        clearLegacyBackGuard("hotReload");
        miuiLauncherOpenActive = false;
        miuiLauncherOpenBreakAvailable = false;
        miuiLauncherOpenBreakGeneration = 0L;
        // The replacement SystemUI generation must obtain a fresh,
        // authenticated native publication before XiaoAi can be claimed.
        miuiLauncherXiaoAiVisible = false;
        acceptedInputToken.set(null);
        Object[] savedContextualSearchNavigationBars =
                detachAllContextualSearchInputReceiversForHotReload();
        closeHyperOsBackHapticHelper();
        clearSystemUiReturnHomeCommitIdentity(null, 0L, "hotReload");
        unregisterMiuiOverviewStateReceiver();
        Object[][] inputState = new Object[nativeInputMonitors.size()][2];
        int index = 0;
        for (Map.Entry<Object, NativeBackInputMonitor> entry
                : new ArrayList<>(nativeInputMonitors.entrySet())) {
            inputState[index][0] = entry.getKey();
            inputState[index][1] = entry.getValue().driver.backAnimationImpl;
            index++;
        }
        Object[][] savedHeadlessState =
                detachHeadlessNavBarLifecycleForHotReload();
        for (NativeBackInputMonitor monitor : new ArrayList<>(nativeInputMonitors.values())) {
            monitor.detach();
        }
        nativeInputMonitors.clear();
        destroySystemUiPlatformImpl();
        return new Object[]{
                inputState, Boolean.valueOf(savedMiuiOverviewVisible),
                Long.valueOf(savedMiuiOverviewDismissDeadline),
                // Reserved slots preserve the previous hot-reload wire layout.
                null, null, 0L, null, false, false, false,
                Boolean.valueOf(savedMiuiDrawerVisible),
                null,
                savedHeadlessState,
                Boolean.valueOf(savedMiuiLauncherEditing),
                Boolean.valueOf(savedMiuiFolderVisible),
                savedContextualSearchNavigationBars,
                Long.valueOf(savedMiuiLauncherDartStateOwnerEpoch)
        };
    }

    protected void restoreHotReloadInput(Object savedState) {
        Object inputStateObject = savedState;
        if (savedState instanceof Object[]) {
            Object[] state = (Object[]) savedState;
            if (state.length >= 2 && state[0] instanceof Object[][]
                    && state[1] instanceof Boolean) {
                inputStateObject = state[0];
                setMiuiOverviewVisible(((Boolean) state[1]).booleanValue());
                if (state.length >= 3 && state[2] instanceof Long) {
                    miuiOverviewDismissPendingUntilUptime = ((Long) state[2]).longValue();
                }
                if (state.length >= 11) {
                    miuiDrawerVisible = Boolean.TRUE.equals(state[10]);
                }
                if (state.length >= 13 && state[12] instanceof Object[][]) {
                    pendingHotReloadHeadlessState = (Object[][]) state[12];
                }
                if (state.length >= 14) {
                    miuiLauncherEditing = false;
                }
                if (state.length >= 15) {
                    miuiFolderVisible = Boolean.TRUE.equals(state[14]);
                }
                if (state.length >= 16 && state[15] instanceof Object[]) {
                    pendingHotReloadContextualSearchNavigationBars = (Object[]) state[15];
                }
                if (state.length >= 17 && state[16] instanceof Long) {
                    miuiLauncherDartStateOwnerEpoch =
                            ((Long) state[16]).longValue();
                }
            }
        }
        restoreMiuiOverviewDismissTimeoutAfterHotReload();
        // Package-ready observers may register before this runtime's saved owner is restored.
        // Replay only after both the visibility/deadline and owner epoch have been adopted.
        notifyOverviewVisibilityListener();
        Object[][] inputState = inputStateObject instanceof Object[][]
                ? (Object[][]) inputStateObject : new Object[0][0];
        if (SYSTEM_UI.equals(processName)) {
            pendingHotReloadInputState = inputState;
            moduleLog(Log.INFO, TAG, "Deferred SystemUI hot reload lifecycle restoration"
                    + ", inputCount=" + inputState.length
                    + ", headlessLeaseCount="
                    + pendingHotReloadHeadlessState.length);
            return;
        }
        if (!(inputStateObject instanceof Object[][])) {
            moduleLog(Log.INFO, TAG, "No hot reload back input state to restore");
            return;
        }
        if (inputState.length == 0) {
            moduleLog(Log.INFO, TAG, "Hot reload back input state is empty; "
                    + "will restore from next EdgeBackGestureHandler callback");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            int restored = 0;
            for (Object[] pair : inputState) {
                if (pair == null || pair.length < 2) {
                    continue;
                }
                installBackInputDriver(pair[0], pair[1]);
                restored++;
            }
            moduleLog(Log.INFO, TAG, "Restored hot reload back input on main thread, count="
                    + restored);
        });
    }

    protected synchronized void restoreMiuiOverviewDismissTimeoutAfterHotReload() {
        long deadline = miuiOverviewDismissPendingUntilUptime;
        if (deadline == 0L) {
            return;
        }
        long remaining = deadline - SystemClock.uptimeMillis();
        if (remaining <= 0L) {
            miuiOverviewDismissPendingUntilUptime = 0L;
            setMiuiOverviewVisible(true);
            moduleLog(Log.WARN, TAG, "Expired Recents dismiss deadline during hot reload"
                    + ", restoredOverviewVisible=true");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(deadline), remaining);
        moduleLog(Log.INFO, TAG, "Restored Recents dismiss timeout after hot reload"
                + ", remainingMs=" + remaining);
    }

    // HyperTweak: BaseHooker reinstalls/replaces every managed hook before restoring hosts.
    public void installSystemUiHooks(ClassLoader loader, HookRegistrar registrar) {
        processName = SYSTEM_UI;
        runtimeClassLoader = loader;
        hookRegistrar = registrar;
        super.installSystemUiHooks(loader);
    }
    public void installSystemServerHooks(ClassLoader loader, HookRegistrar registrar) {
        processName = "system";
        runtimeClassLoader = loader;
        hookRegistrar = registrar;
        installSystemServerHooks(loader);
    }
    public void restoreHotReloadState(Object savedState) {
        if (com.takekazex.hypertweak.util.platform.UpstreamBackGesture.INSTANCE.getInstalled()) {
            moduleLog(Log.INFO, TAG, "Upstream module present; back input hosts remain detached after hot reload");
            return;
        }
        restoreHotReloadInput(savedState);
        if (SYSTEM_UI.equals(processName)) {
            restoreSystemUiHotReloadLifecycle(runtimeClassLoader);
            Object[] bars = pendingHotReloadContextualSearchNavigationBars;
            pendingHotReloadContextualSearchNavigationBars = new Object[0];
            restoreContextualSearchInputReceivers(bars);
        }
    }
}
