package com.takekazex.hypertweak.hook.rules.backgesture.hooks.hotreload;

// Adapted for HyperTweak from wxxsfxyzm/MiuiBackGestureHook (Apache-2.0).
// Vendored through upstream ae2ff31 (v0.8.1 + 5 post-tag commits). Keep structural parity
// so future updates stay mergeable; HyperTweak-local changes are marked.

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemserver.SystemServerHookRuntime;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public abstract class HotReloadHookRuntime extends SystemServerHookRuntime {


    /**
     * HyperTweak: upstream's {@code onHotReloading(param)}. BaseHooker calls this before it tears
     * the old generation down and treats a throw as "not ready", which is how upstream's
     * {@code return false} deferral is expressed here. The saved state is returned instead of
     * being written to the LSPosed param.
     */
    public Object saveHotReloadState() {
        PreparedBackTransitionHold heldTransition = preparedBackTransitionHold.get();
        if (heldTransition != null) {
            log(Log.WARN, TAG,
                    "Deferred hot reload while a prepared-back transition is held"
                            + ", process=" + processName
                            + ", " + describePreparedBackTransitionHold(heldTransition));
            throw new IllegalStateException(
                    "A prepared-back transition is still held");
        }
        for (NativeBackInputMonitor monitor
                : new ArrayList<>(nativeInputMonitors.values())) {
            if (monitor.blocksHotReload()) {
                log(Log.WARN, TAG,
                        "Deferred hot reload while a fixed Shell gesture session is active"
                                + ", process=" + processName
                                + ", state="
                                + monitor.describeActiveShellSession());
                throw new IllegalStateException(
                        "A fixed Shell gesture session is still active");
            }
        }
        MiuiHomeReturnHomeController activeReturnHomeController =
                miuiHomeReturnHomeController;
        if (activeReturnHomeController != null
                && activeReturnHomeController.blocksControllerReplacement()) {
            log(Log.WARN, TAG,
                    "Deferred hot reload while Xiaomi owns predictive return-home"
                            + ", process=" + processName
                            + ", state="
                            + activeReturnHomeController.describeUnifiedOwner());
            throw new IllegalStateException(
                    "Xiaomi still owns predictive return-home");
        }
        log(Log.INFO, TAG, "Hot reloading, build=" + BUILD_MARK
                + ", process=" + processName
                + ", hooks=" + hookHandles.size());
        boolean savedMiuiOverviewVisible = miuiOverviewVisible;
        boolean savedMiuiDrawerVisible = miuiDrawerVisible;
        boolean savedMiuiLauncherEditing = miuiLauncherEditing;
        long savedMiuiOverviewDismissDeadline = miuiOverviewDismissPendingUntilUptime;
        Object savedMiuiHomeOpenBreakController = miuiHomeOpenBreakController;
        Context savedMiuiHomeOpenBreakContext = miuiHomeOpenBreakContext;
        long savedMiuiHomeOpenBreakGeneration = miuiHomeOpenBreakGeneration;
        Object savedMiuiHomeOpenBreakAnimationIdentity =
                miuiHomeOpenBreakAnimationIdentity;
        boolean savedMiuiHomeOpenBreakGenerationPrepared =
                miuiHomeOpenBreakGenerationPrepared;
        boolean savedMiuiHomeOpenBreakAnimationActive =
                miuiHomeOpenBreakAnimationActive;
        boolean savedMiuiHomeOpenBreakCommandPending =
                miuiHomeOpenBreakCommandPending;
        miuiHomeLocalHandoffToken.set(null);
        invalidateMiuiHomeLauncherOpenSnapshot(null, "hotReload");
        IBinder savedMiuiHomeReturnHomeBinder =
                detachMiuiHomeReturnHome("hotReload", true);
        miuiHomePendingNativeGeometry.remove();
        returnHomeFinishTransferCandidate.remove();
        preparedBackTargetArrival.set(null);
        preparedBackTargetArrivalHookReady = false;
        preparedBackTerminalHookReady = false;
        preparedBackStartAnimationInvoker = null;
        freeformColorRootCandidate.set(null);
        freeformColorRootAnimation = null;
        backCommitCompositionHookReady = false;
        backFinishOpenAtomicHookReady = false;
        backFinishOpenCallerDeoptimized = false;
        acceptingOpenSnapshots = false;
        acceptingHeadlessNavBarLifecycle = false;
        synchronized (backInputLifecycleLock) {
            acceptingBackInputInstalls = false;
        }
        headlessNavBarLifecycleGeneration.incrementAndGet();
        miuiHomeOpenBreakCallbackEpoch.incrementAndGet();
        openSnapshotGeneration.incrementAndGet();
        invalidateAllOpenTransitionSnapshots("hotReload");
        clearLegacyBackGuard("hotReload");
        miuiLauncherOpenActive = false;
        miuiLauncherOpenBreakAvailable = false;
        miuiLauncherOpenBreakGeneration = 0L;
        acceptedInputToken.set(null);
        miuiHomeAcceptedInputIdentity.set(null);
        clearSystemUiReturnHomeCommitIdentity(null, 0L, "hotReload");
        unregisterMiuiOverviewStateReceiver();
        unregisterMiuiHomeOpenBreakCommandReceiver();
        unregisterMiuiHomeInputArbiterReceiver();
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
        return new Object[]{
                inputState, Boolean.valueOf(savedMiuiOverviewVisible),
                Long.valueOf(savedMiuiOverviewDismissDeadline),
                savedMiuiHomeOpenBreakController, savedMiuiHomeOpenBreakContext,
                Long.valueOf(savedMiuiHomeOpenBreakGeneration),
                savedMiuiHomeOpenBreakAnimationIdentity,
                Boolean.valueOf(savedMiuiHomeOpenBreakGenerationPrepared),
                Boolean.valueOf(savedMiuiHomeOpenBreakAnimationActive),
                Boolean.valueOf(savedMiuiHomeOpenBreakCommandPending),
                Boolean.valueOf(savedMiuiDrawerVisible),
                savedMiuiHomeReturnHomeBinder,
                savedHeadlessState,
                Boolean.valueOf(savedMiuiLauncherEditing)
        };
    }

    /**
     * HyperTweak: upstream's {@code onHotReloaded(param)} re-attached every old hook by id and
     * then restored process state. BaseHooker re-runs {@code onHook()} against the old handle
     * store first, so hook re-attachment already happened by the time this is called and only
     * upstream's state restoration is kept here.
     */
    public void restoreHotReloadState(Object savedState) {
        // Upstream relies on the replacement generation being a fresh instance for these to fall
        // back to their initial `true`. HyperTweak reaches the runtime through a singleton, so
        // re-open the gates explicitly.
        acceptingOpenSnapshots = true;
        acceptingHeadlessNavBarLifecycle = true;
        synchronized (backInputLifecycleLock) {
            acceptingBackInputInstalls = true;
        }
        restoreHotReloadInput(savedState);
        ClassLoader hotReloadClassLoader = runtimeClassLoader;
        if (SYSTEM_UI.equals(processName)) {
            restoreSystemUiHotReloadLifecycle(hotReloadClassLoader);
        }
        if (MIUI_HOME.equals(processName) && hotReloadClassLoader != null) {
            try {
                restoreMiuiHomeGestureStubsAfterHotReload(hotReloadClassLoader);
                refreshMiuiHomeEditingState(hotReloadClassLoader, "hotReloadBackfill");
                restoreMiuiHomeOpenBreakAfterHotReload();
                restoreMiuiHomeReturnHomeAfterHotReload(hotReloadClassLoader);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to restore MiuiHome state", throwable);
            }
        }
        log(Log.INFO, TAG, "Hot reloaded, build=" + BUILD_MARK
                + ", process=" + processName
                + ", hooks=" + hookHandles.size());
    }

    // HyperTweak: upstream's createHotReloadHooker() re-attached each old hook by id during
    // onHotReloaded(). BaseHooker already replaces handles by hook id when onHook() re-runs
    // against the HotReloadHandleStore, so the mapping table would never be consulted here.

    protected void restoreHotReloadInput(Object savedState) {
        Object inputStateObject = savedState;
        if (savedState instanceof Object[]) {
            Object[] state = (Object[]) savedState;
            if (state.length >= 2 && state[0] instanceof Object[][]
                    && state[1] instanceof Boolean) {
                inputStateObject = state[0];
                miuiOverviewVisible = ((Boolean) state[1]).booleanValue();
                if (state.length >= 3 && state[2] instanceof Long) {
                    miuiOverviewDismissPendingUntilUptime = ((Long) state[2]).longValue();
                }
                if (state.length >= 10) {
                    miuiHomeOpenBreakController = state[3];
                    if (state[4] instanceof Context) {
                        miuiHomeOpenBreakContext = (Context) state[4];
                    }
                    if (state[5] instanceof Long) {
                        miuiHomeOpenBreakGeneration = ((Long) state[5]).longValue();
                        miuiHomeOpenBreakGenerationIds.set(Math.max(
                                miuiHomeOpenBreakGenerationIds.get(),
                                miuiHomeOpenBreakGeneration));
                    }
                    miuiHomeOpenBreakAnimationIdentity = state[6];
                    miuiHomeOpenBreakGenerationPrepared =
                            Boolean.TRUE.equals(state[7]);
                    miuiHomeOpenBreakAnimationActive = Boolean.TRUE.equals(state[8]);
                    miuiHomeOpenBreakCommandPending = Boolean.TRUE.equals(state[9]);
                }
                if (state.length >= 11) {
                    miuiDrawerVisible = Boolean.TRUE.equals(state[10]);
                }
                if (state.length >= 12 && state[11] instanceof IBinder) {
                    miuiHomeReturnHomeBinder = (IBinder) state[11];
                }
                if (state.length >= 13 && state[12] instanceof Object[][]) {
                    pendingHotReloadHeadlessState = (Object[][]) state[12];
                }
                if (state.length >= 14) {
                    miuiLauncherEditing = Boolean.TRUE.equals(state[13]);
                }
            }
        }
        restoreMiuiOverviewDismissTimeoutAfterHotReload();
        Object[][] inputState = inputStateObject instanceof Object[][]
                ? (Object[][]) inputStateObject : new Object[0][0];
        if (SYSTEM_UI.equals(processName)) {
            pendingHotReloadInputState = inputState;
            log(Log.INFO, TAG, "Deferred SystemUI hot reload lifecycle restoration"
                    + ", inputCount=" + inputState.length
                    + ", headlessLeaseCount="
                    + pendingHotReloadHeadlessState.length);
            return;
        }
        if (!(inputStateObject instanceof Object[][])) {
            log(Log.INFO, TAG, "No hot reload back input state to restore");
            return;
        }
        if (inputState.length == 0) {
            log(Log.INFO, TAG, "Hot reload back input state is empty; "
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
            log(Log.INFO, TAG, "Restored hot reload back input on main thread, count="
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
            miuiOverviewVisible = true;
            log(Log.WARN, TAG, "Expired Recents dismiss deadline during hot reload"
                    + ", restoredOverviewVisible=true");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(deadline), remaining);
        log(Log.INFO, TAG, "Restored Recents dismiss timeout after hot reload"
                + ", remainingMs=" + remaining);
    }

    // HyperTweak: upstream dispatches these from the LSPosed onPackageLoaded()/
    // onSystemServerStarting() callbacks. HookEntry owns that dispatch here, so each process
    // gets an explicit entry point that also binds the registrar used by registerHook().

    public void installSystemUiHooks(ClassLoader classLoader, HookRegistrar registrar) {
        processName = SYSTEM_UI;
        hookRegistrar = registrar;
        runtimeClassLoader = classLoader;
        log(Log.INFO, TAG, "Installing SystemUI hooks, build=" + BUILD_MARK
                + ", classLoader=" + classLoader);
        installSystemUiHooks(classLoader);
    }

    public void installMiuiHomeHooks(ClassLoader classLoader, HookRegistrar registrar) {
        processName = MIUI_HOME;
        hookRegistrar = registrar;
        runtimeClassLoader = classLoader;
        log(Log.INFO, TAG, "Installing MiuiHome hooks, build=" + BUILD_MARK
                + ", classLoader=" + classLoader);
        installMiuiHomeHooks(classLoader);
    }

    public void installSystemServerHooks(ClassLoader classLoader, HookRegistrar registrar) {
        processName = "system";
        hookRegistrar = registrar;
        runtimeClassLoader = classLoader;
        log(Log.INFO, TAG, "Installing system_server hooks, build=" + BUILD_MARK
                + ", classLoader=" + classLoader);
        installSystemServerHooks(classLoader);
    }

    protected void installMiuiHomeHooks(ClassLoader classLoader) {
        try {
            Class<?> gestureStubClass = Class.forName(MIUI_HOME_GESTURE_STUB, false,
                    classLoader);
            hookMiuiHomeGestureStubShow(gestureStubClass);
            Class<?> processorClass = Class.forName(
                    MIUI_HOME_GESTURE_PROCESSOR, false, classLoader);
            hookMiuiHomeGestureInputArbiter(processorClass, gestureStubClass);
            Class<?> recentsContainerClass = Class.forName(MIUI_HOME_RECENTS_CONTAINER, false,
                    classLoader);
            hookMiuiHomeRecentsActualState(recentsContainerClass);
            Class<?> taskViewClass = Class.forName(MIUI_HOME_TASK_VIEW, false, classLoader);
            hookMiuiHomeRecentsTaskLaunch(taskViewClass);
            hookMiuiHomeFullscreenState(classLoader);
            Class<?> breakControllerClass = Class.forName(
                    MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER, false, classLoader);
            hookMiuiHomeOpenBreakEnable(breakControllerClass);
            Class<?> windowElementAnimListenerClass = Class.forName(
                    MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER, false, classLoader);
            hookMiuiHomeOpenBreakAnimationStart(windowElementAnimListenerClass);
            hookMiuiHomeOpenBreakAnimationEnd(windowElementAnimListenerClass);
            try {
                hookMiuiHomeLauncherOpenSnapshotTargets(classLoader);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install Xiaomi OPEN target binding",
                        throwable);
            }
            hookMiuiHomeReusedCloseOpen(classLoader);
            try {
                hookMiuiHomeTransitionContinuity(
                        classLoader, true, true, true);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome element continuity",
                        throwable);
            }
            try {
                hookMiuiHomeUnifiedFinishEpoch(
                        classLoader, true, true, true);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome finish-epoch hooks",
                        throwable);
            }
            try {
                hookMiuiHomePermissionMerge(classLoader);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome permission merge",
                        throwable);
            }
            hookMiuiHomeGeometryFrames(classLoader, true, true);
            try {
                hookMiuiHomeTransitionSetupLeash(classLoader);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome transition geometry hook",
                        throwable);
            }
            hookMiuiHomeStartTransactionApply(
                    Collections.emptySet());
            hookMiuiHomeReturnHomeSameIconParallel(classLoader);
            hookMiuiHomeReturnHomeFreshOpen(classLoader);
            hookMiuiHomeReturnHomeDirectCancel(classLoader);
            hookMiuiHomeDrawerState(classLoader);
            hookMiuiHomeFreeformBackTouchability(classLoader);
            hookMiuiHomeEditingState(classLoader);
            hookMiuiHomeReturnHomeInitialize(classLoader);
            hookMiuiHomeReturnHomeLocalHandoff(classLoader);
            hookMiuiHomeReturnHomeWallpaperCommands(classLoader, true, true);
            log(Log.INFO, TAG, "Enabled MiuiHome native side input arbitration"
                    + ", preservedGestureStubInitialization=true"
                    + ", preservesNativeRedirect=true"
                    + ", blocksLegacyGestureProcessor=true"
                    + ", requiresAcceptedInputToken=true"
                    + ", systemUiOwnsCommittedGesture=true"
                    + ", mirrorsActualRecentsState=true"
                    + ", mirrorsTaskLaunchExit=true"
                    + ", mirrorsAuthenticatedFullscreenState=true"
                    + ", mirrorsDrawerState=true"
                    + ", preservesSmallWindowBackTouchability=true"
                    + ", mirrorsLauncherEditingState=true"
                    + ", mirrorsLauncherOpenBreakState=true"
                    + ", repairsNonReusableSameIconOpen=true"
                    + ", usesStandardLauncherBackCallback=true");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install MiuiHome input arbitration", throwable);
        }
    }

}
