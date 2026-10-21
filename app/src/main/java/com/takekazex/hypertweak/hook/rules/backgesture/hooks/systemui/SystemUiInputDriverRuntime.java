package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import com.takekazex.hypertweak.hook.Preferences;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackTouchTracker;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Drives an accepted native input stream through the Shell owner session. */
@SuppressLint({"PrivateApi", "DiscouragedApi"})
public abstract class SystemUiInputDriverRuntime extends SystemUiInputPolicyRuntime {
    protected abstract boolean isRecentsRetryInputAllowed(Object handler);
    protected abstract void sendAuthenticatedMiuiHomeOpenBreakCommand(
            Context context, long generation, long attemptId,
            SystemUiBackGestureDriver driver, Object releaseController);

    protected final class SystemUiBackGestureDriver {
        protected final class ShellOwner {
            final Object controller;
            final Executor executor;
            final long inputEpoch;

            ShellOwner(Object controller, Executor executor, long inputEpoch) {
                this.controller = controller;
                this.executor = executor;
                this.inputEpoch = inputEpoch;
            }
        }

        protected final class ShellStartSnapshot {
            final boolean ready;
            final boolean startInvoked;
            final String stateDescription;
            final Object tracker;
            final Object navigation;
            final boolean receivedNullNavigation;
            final Throwable failure;

            ShellStartSnapshot(boolean ready, boolean startInvoked,
                               String stateDescription, Object tracker,
                               Object navigation, boolean receivedNullNavigation,
                               Throwable failure) {
                this.ready = ready;
                this.startInvoked = startInvoked;
                this.stateDescription = stateDescription;
                this.tracker = tracker;
                this.navigation = navigation;
                this.receivedNullNavigation = receivedNullNavigation;
                this.failure = failure;
            }
        }

        protected final class ShellGestureSession {
            final long id = systemUiShellGestureSessionIds.incrementAndGet();
            final Object controller;
            final Executor executor;
            final long inputEpoch;
            final Object tracker;
            final Object navigation;
            final boolean receivedNullNavigation;
            final int edge;
            final float startX;
            final float startY;
            final float linearDistance;
            final float maxDistance;
            final float nonLinearFactor;
            final AtomicReference<MiuiHomeAcceptedInputToken> inputIdentity;
            final AtomicBoolean releaseQueued = new AtomicBoolean();
            final AtomicBoolean moveFailed = new AtomicBoolean();
            final AtomicBoolean awaitingStockCleanup = new AtomicBoolean();
            final AtomicBoolean completionConsumed = new AtomicBoolean();

            ShellGestureSession(ShellOwner owner, ShellStartSnapshot start,
                                int edge, float startX, float startY,
                                float linearDistance,
                                float maxDistance, float nonLinearFactor,
                                MiuiHomeAcceptedInputToken inputIdentity) {
                this.controller = owner.controller;
                this.executor = owner.executor;
                this.inputEpoch = owner.inputEpoch;
                this.tracker = start.tracker;
                this.navigation = start.navigation;
                this.receivedNullNavigation = start.receivedNullNavigation;
                this.edge = edge;
                this.startX = startX;
                this.startY = startY;
                this.linearDistance = linearDistance;
                this.maxDistance = maxDistance;
                this.nonLinearFactor = nonLinearFactor;
                this.inputIdentity = new AtomicReference<>(inputIdentity);
            }
        }

        protected final Context context;
        protected final Object edgeBackGestureHandler;
        protected final int displayId;
        protected volatile Object controller;
        public volatile Object backAnimationImpl;
        protected volatile Executor shellExecutor;
        protected volatile ShellGestureSession activeShellSession;
        protected volatile boolean shellStartInFlight;
        protected volatile boolean shellOwnerUncertain;
        protected boolean gestureActive;
        protected boolean thresholdCrossed;
        protected boolean shellGestureStarted;
        protected boolean shellGestureStartDeferred;
        protected volatile boolean gestureSuppressed;
        protected boolean legacyInterruptGesture;
        protected boolean aospNullNavigationGesture;
        protected Object legacyRunningOpenInfo;
        protected boolean launcherOpenBreakGesture;
        protected long launcherOpenBreakGeneration;
        protected long launcherOpenBreakAttemptId;
        protected long pendingLauncherOpenBreakGeneration;
        protected long pendingLauncherOpenBreakAttemptId;
        protected boolean launcherOverviewGesture;
        protected boolean launcherShadeGesture;
        protected boolean launcherXiaoAiGesture;
        protected boolean launcherDrawerGesture;
        protected boolean launcherEditingGesture;
        protected boolean recentsVisualOnlyGesture;
        protected MiuiHomeAcceptedInputToken acceptedInputIdentity;
        protected ShellOwner gestureOwner;
        protected final AtomicLong inputMonitorEpoch = new AtomicLong();
        protected volatile boolean inputMonitorAttached;
        protected int activeEdge;
        protected float downX;
        protected float downY;
        protected float lastX;
        protected float lastY;
        protected MiuiStyleBackArrowOverlay miuiStyleOverlay;
        protected MiuiHapticFeedbackHelper miuiHapticHelper;
        protected boolean miuiStyleGestureActive;
        protected boolean miuiStyleSwipeStarted;
        protected boolean miuiStyleHapticsActive;
        protected Boolean lastIndicatorRequested;
        protected Boolean lastIndicatorApplied;

        SystemUiBackGestureDriver(Context context, Object edgeBackGestureHandler,
                                  Object controller, Object backAnimationImpl,
                                  int displayId)
                throws Exception {
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.displayId = displayId;
            this.controller = controller;
            this.backAnimationImpl = backAnimationImpl;
            this.shellExecutor = resolveShellExecutor(controller);
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            Object newController = readField(newBackAnimationImpl, "this$0");
            Executor newShellExecutor = resolveShellExecutor(newController);
            synchronized (backInputLifecycleLock) {
                if (controller != newController) {
                    inputMonitorEpoch.incrementAndGet();
                }
                this.backAnimationImpl = newBackAnimationImpl;
                this.controller = newController;
                this.shellExecutor = newShellExecutor;
            }
        }

        protected Executor resolveShellExecutor(Object shellController)
                throws Exception {
            Object executor = readField(shellController, "mShellExecutor");
            if (!(executor instanceof Executor)) {
                throw new IllegalStateException("mShellExecutor is "
                        + shortObject(executor));
            }
            return (Executor) executor;
        }

        boolean blocksHotReload() {
            ShellGestureSession session = activeShellSession;
            return shellStartInFlight || shellOwnerUncertain || session != null;
        }

        String describeActiveShellSession() {
            ShellGestureSession session = activeShellSession;
            return "startInFlight=" + shellStartInFlight
                    + ", uncertain=" + shellOwnerUncertain
                    + ", sessionId=" + (session == null ? 0L : session.id)
                    + ", controller=" + shortObject(
                    session == null ? null : session.controller)
                    + ", tracker=" + shortObject(
                    session == null ? null : session.tracker)
                    + ", navigation=" + shortObject(
                    session == null ? null : session.navigation)
                    + ", releaseQueued=" + (session != null
                    && session.releaseQueued.get())
                    + ", completionConsumed=" + (session != null
                    && session.completionConsumed.get());
        }

        protected ShellOwner captureShellOwner() {
            synchronized (backInputLifecycleLock) {
                if (!inputMonitorAttached || controller == null
                        || shellExecutor == null
                        || inputMonitorEpoch.get() == 0L) {
                    return null;
                }
                return new ShellOwner(controller, shellExecutor,
                        inputMonitorEpoch.get());
            }
        }

        protected boolean isShellOwnerCurrent(ShellOwner owner) {
            synchronized (backInputLifecycleLock) {
                return owner != null && inputMonitorAttached
                        && owner.controller == controller
                        && owner.executor == shellExecutor
                        && owner.inputEpoch == inputMonitorEpoch.get();
            }
        }

        protected boolean isShellSessionOwnerCurrent(
                ShellGestureSession session) {
            synchronized (backInputLifecycleLock) {
                return session != null && inputMonitorAttached
                        && session.controller == controller
                        && session.executor == shellExecutor
                        && session.inputEpoch == inputMonitorEpoch.get();
            }
        }

        protected boolean executeShellBlocking(Executor executor, Runnable task,
                                               String reason) {
            try {
                invokeAnyMethod(executor, "executeBlocking",
                        new Object[]{task});
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed blocking Shell-owner task, reason=" + reason,
                        throwable);
                return false;
            }
        }

        void onInputMonitorAttached() {
            synchronized (backInputLifecycleLock) {
                inputMonitorEpoch.incrementAndGet();
                inputMonitorAttached = true;
            }
        }

        boolean bindAcceptedInput(MiuiHomeAcceptedInputToken token) {
            if (gestureActive && isShellOwnerCurrent(gestureOwner)
                    && isCurrentAcceptedInputIdentity(
                    token, activeEdge, gestureOwner.controller,
                    gestureOwner.inputEpoch)) {
                acceptedInputIdentity = token;
                ShellGestureSession session = activeShellSession;
                if (shellGestureStarted && session != null
                        && session.edge == activeEdge) {
                    session.inputIdentity.compareAndSet(null, token);
                }
                if (launcherOpenBreakGesture
                        && launcherOpenBreakGeneration
                        == miuiLauncherOpenBreakGeneration
                        && !miuiLauncherOpenActive) {
                    onLauncherOpenEnded(launcherOpenBreakGeneration);
                }
                return true;
            }
            return false;
        }

        protected boolean handleTouch(MotionEvent event, int edge,
                                      boolean launcherOpenBreakCandidate,
                                      long launcherOpenBreakGenerationCandidate,
                                      boolean launcherShadeCandidate,
                                      boolean launcherXiaoAiCandidate,
                                      boolean launcherDrawerCandidate,
                                      boolean launcherEditingCandidate) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return onDown(event, edge, launcherOpenBreakCandidate,
                                launcherOpenBreakGenerationCandidate,
                                launcherShadeCandidate,
                                launcherXiaoAiCandidate,
                                launcherDrawerCandidate, launcherEditingCandidate);
                    case MotionEvent.ACTION_MOVE:
                        return onMove(event);
                    case MotionEvent.ACTION_UP:
                        return onUp(event, true);
                    case MotionEvent.ACTION_CANCEL:
                        return onUp(event, false);
                    default:
                        return gestureActive;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "SystemUI back gesture driver failed", throwable);
                clearLocalGestureState();
                return false;
            }
        }

        protected boolean isRecentsVisualOnlyGesture() {
            return gestureActive && launcherOverviewGesture && recentsVisualOnlyGesture;
        }

        protected boolean isGestureSuppressed() {
            return gestureActive && gestureSuppressed;
        }

        protected boolean canPilferAtIntentThreshold() {
            if (!gestureActive || gestureSuppressed) {
                return false;
            }
            if (!shellGestureStartDeferred
                    || findReversibleRunningOpenTransition() != null) {
                return true;
            }
            ShellOwner owner = gestureOwner;
            if (!isShellOwnerCurrent(owner)
                    || !prepareShellSessionSlotForStart()) {
                return false;
            }
            AtomicBoolean ready = new AtomicBoolean();
            AtomicReference<String> state = new AtomicReference<>("not-run");
            boolean completed = executeShellBlocking(owner.executor, () -> {
                try {
                    Object transitionHandler = readField(owner.controller,
                            "mBackTransitionHandler");
                    recoverStaleCloseTransitionRequest(
                            owner.controller,
                            readField(owner.controller, "mCurrentTracker"),
                            readField(owner.controller, "mQueuedTracker"),
                            readField(transitionHandler,
                                    "mOnAnimationFinishCallback"),
                            0L);
                    state.set(describeShellStateOnOwner(owner.controller));
                    ready.set(isShellStartReadyOnOwner(owner.controller));
                } catch (Throwable throwable) {
                    state.set(describeShellStateOnOwner(owner.controller));
                    moduleLog(Log.WARN, TAG,
                            "Failed Shell readiness check before pilfer",
                            throwable);
                }
            }, "prePilferReadiness");
            if (!completed || !ready.get()) {
                moduleLog(Log.INFO, TAG,
                        "Left accepted gesture unpilfered while Shell is busy"
                                + ", state=" + state.get());
                return false;
            }
            return true;
        }

        protected boolean onDown(MotionEvent event, int edge,
                                 boolean launcherOpenBreakCandidate,
                                 long launcherOpenBreakGenerationCandidate,
                                 boolean launcherShadeCandidate,
                                 boolean launcherXiaoAiCandidate,
                                 boolean launcherDrawerCandidate,
                                 boolean launcherEditingCandidate) throws Exception {
            clearLegacyBackGuard("newPhysicalGesture");
            gestureActive = true;
            shellGestureStarted = false;
            shellGestureStartDeferred = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            aospNullNavigationGesture = false;
            legacyRunningOpenInfo = null;
            launcherOpenBreakGesture = launcherOpenBreakCandidate;
            launcherOpenBreakGeneration = launcherOpenBreakCandidate
                    ? launcherOpenBreakGenerationCandidate : 0L;
            launcherOpenBreakAttemptId = launcherOpenBreakCandidate
                    ? launcherOpenBreakAttemptIds.incrementAndGet() : 0L;
            launcherOverviewGesture = miuiOverviewVisible && !launcherShadeCandidate;
            launcherShadeGesture = launcherShadeCandidate;
            launcherXiaoAiGesture = launcherXiaoAiCandidate;
            if (launcherXiaoAiGesture) {
                launcherOverviewGesture = false;
            }
            launcherDrawerGesture = launcherDrawerCandidate;
            launcherEditingGesture = launcherEditingCandidate;
            recentsVisualOnlyGesture = false;
            thresholdCrossed = false;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            lastX = downX;
            lastY = downY;
            gestureOwner = captureShellOwner();
            if (launcherOpenBreakGesture
                    && launcherOpenBreakGeneration
                    != miuiLauncherOpenBreakGeneration) {
                long staleGeneration = launcherOpenBreakGeneration;
                gestureActive = false;
                moduleLog(Log.WARN, TAG,
                        "Rejected stale launcher OPEN candidate"
                                + ", generation=" + staleGeneration
                                + ", currentGeneration="
                                + miuiLauncherOpenBreakGeneration
                                + ", edge=" + activeEdge);
                return false;
            }
            if (launcherOpenBreakGesture && !miuiLauncherOpenActive) {
                long endedGeneration = launcherOpenBreakGeneration;
                launcherOpenBreakGesture = false;
                launcherOpenBreakGeneration = 0L;
                launcherOpenBreakAttemptId = 0L;
                moduleLog(Log.INFO, TAG,
                        "Started expired launcher OPEN candidate on the normal Shell path"
                                + ", generation=" + endedGeneration
                                + ", edge=" + activeEdge);
            }
            if (launcherOpenBreakGesture) {
                dispatchToEdgePlugin(event, activeEdge);
                moduleLog(Log.INFO, TAG, "SystemUI-owned launcher OPEN break candidate"
                        + ", useMiuiHomeNativeController=true"
                        + ", shellGestureStarted=false"
                        + ", generation=" + launcherOpenBreakGeneration
                        + ", attempt=" + launcherOpenBreakAttemptId
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                return true;
            }
            if (launcherOverviewGesture || launcherShadeGesture
                    || launcherXiaoAiGesture
                    || launcherDrawerGesture || launcherEditingGesture) {
                moduleLog(Log.INFO, TAG, (launcherShadeGesture
                        ? "SystemUI-owned NotificationShade back gesture candidate"
                        : launcherXiaoAiGesture
                        ? "SystemUI-owned XiaoAi back gesture candidate"
                        : launcherOverviewGesture
                        ? "SystemUI-owned Recents back gesture candidate"
                        : launcherDrawerGesture
                        ? "SystemUI-owned MiuiHome callback surface back gesture candidate"
                        : "SystemUI-owned MiuiHome editing back gesture candidate")
                        + ", useShellCallback=true"
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                // Launcher Home, Recents, drawer/folder, and editing share one Activity.
                // Resolve the callback on DOWN while the real stream is still unpilfered.
                if (!startShellGesture()) {
                    if (recentsVisualOnlyGesture) {
                        dispatchToEdgePlugin(event, activeEdge);
                        moduleLog(Log.INFO, TAG, "Started visual-only Recents edge gesture"
                                + ", staleShellTargetRejected=true"
                                + ", inputWillRemainUnpilfered=true");
                        return true;
                    }
                    gestureActive = false;
                    gestureSuppressed = false;
                    moduleLog(Log.INFO, TAG, (launcherShadeGesture
                            ? "Ignored NotificationShade gesture without a callback target"
                            : launcherXiaoAiGesture
                            ? "Ignored XiaoAi gesture without a callback target"
                            : launcherDrawerGesture
                            ? "Ignored MiuiHome callback surface gesture without a callback target"
                            : launcherEditingGesture
                            ? "Ignored MiuiHome editing gesture without a callback target"
                            : "Ignored Recents edge gesture without a back navigation target")
                            + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                    return false;
                }
            } else {
                // MiuiHome GestureStub accepted the original DOWN and its legacy processor
                // was neutralized. Pilfering and Shell setup share the fixed 8dp outward
                // boundary and Xiaomi's non-terminal direction gate, with the authenticated
                // token as the ownership proof.
                shellGestureStartDeferred = true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            moduleLog(Log.INFO, TAG, "SystemUI gesture driver candidate"
                    + ", shellStartDeferred=" + shellGestureStartDeferred
                    + ", inputModel=miuihome-accepted-token"
                    + ", launcherShade=" + launcherShadeGesture
                    + ", launcherXiaoAi=" + launcherXiaoAiGesture
                    + ", launcherDrawerOrFolder=" + launcherDrawerGesture
                    + ", launcherEditing=" + launcherEditingGesture
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        protected boolean onMove(MotionEvent event) throws Exception {
            if (!gestureActive) {
                return false;
            }
            if (gestureSuppressed) {
                return true;
            }
            if (recentsVisualOnlyGesture) {
                dispatchToEdgePlugin(event, activeEdge);
                return true;
            }
            lastX = event.getRawX();
            lastY = event.getRawY();
            dispatchToEdgePlugin(event, activeEdge);
            float distance = activeEdge == EDGE_LEFT
                    ? lastX - downX
                    : downX - lastX;
            boolean intentQualified = hasXiaomiBackIntent(
                    distance, lastY - downY, dp(PILFER_THRESHOLD_DP));
            if (shellGestureStartDeferred && intentQualified) {
                shellGestureStartDeferred = false;
                if (!startShellGesture()) {
                    cancelLocalGesture(event,
                            "BackNavigationInfo unavailable at intent threshold");
                    return false;
                }
                moduleLog(Log.INFO, TAG, "Started in-app back path at 8dp intent threshold"
                        + ", shellGestureStarted=" + shellGestureStarted
                        + ", legacyInterrupt=" + legacyInterruptGesture
                        + ", aospNullNavigation=" + aospNullNavigationGesture
                        + ", edge=" + activeEdge
                        + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            }
            boolean crossedNow = false;
            if (!thresholdCrossed && intentQualified) {
                crossedNow = crossIntentThreshold(distance);
            }
            if (!shellGestureStartDeferred
                    && !legacyInterruptGesture && !launcherOpenBreakGesture) {
                ShellGestureSession session = activeShellSession;
                if (session == null || !queueShellMove(session,
                        event.getRawX(), event.getRawY(), distance,
                        crossedNow, thresholdCrossed
                                && !aospNullNavigationGesture)) {
                    cancelLocalGesture(event,
                            "failed to queue Shell-owner MOVE");
                    return false;
                }
            }
            return true;
        }

        void onLauncherOpenEnded(long generation) {
            if (!gestureActive || gestureSuppressed
                    || !launcherOpenBreakGesture
                    || generation == 0L
                    || launcherOpenBreakGeneration != generation
                    || miuiLauncherOpenBreakGeneration != generation
                    || miuiLauncherOpenActive
                    || pendingLauncherOpenBreakAttemptId != 0L
                    || !isShellOwnerCurrent(gestureOwner)
                    || !isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, gestureOwner.controller,
                    gestureOwner.inputEpoch)) {
                return;
            }
            // MiuiHome has authenticated that this exact OPEN ended. Seed the standard Shell
            // path with the coordinates accumulated while Xiaomi owned its native OPEN.
            launcherOpenBreakGesture = false;
            launcherOpenBreakGeneration = 0L;
            launcherOpenBreakAttemptId = 0L;
            handoffEndedOpenToShell("launcher OPEN",
                    ", generation=" + generation, 0L);
        }

        void onInAppOpenTransitionEnded(
                OpenTransitionSnapshot snapshot, long handoffEpoch) {
            if (!gestureActive || gestureSuppressed
                    || !legacyInterruptGesture
                    || snapshot == null
                    || legacyRunningOpenInfo != snapshot.transitionInfo
                    || shellGestureStarted
                    || !thresholdCrossed
                    || !isOpenEndHandoffCurrent(handoffEpoch)
                    || !isShellOwnerCurrent(gestureOwner)
                    || !isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, gestureOwner.controller,
                    gestureOwner.inputEpoch)) {
                return;
            }
            handoffEndedOpenToShell("in-app Activity OPEN",
                    ", info=" + shortObject(snapshot.transitionInfo), handoffEpoch);
        }

        protected void handoffEndedOpenToShell(
                String openDescription, String identityDescription,
                long openHandoffEpoch) {
            if (!thresholdCrossed) {
                shellGestureStartDeferred = true;
                moduleLog(Log.INFO, TAG,
                        "Deferred " + openDescription + "-to-Shell handoff until 8dp"
                                + identityDescription
                                + ", edge=" + activeEdge);
                return;
            }
            if (openHandoffEpoch != 0L
                    && !isOpenEndHandoffCurrent(openHandoffEpoch)) {
                return;
            }
            try {
                float distance = activeEdge == EDGE_LEFT
                        ? lastX - downX : downX - lastX;
                if (openHandoffEpoch != 0L) {
                    legacyInterruptGesture = false;
                    legacyRunningOpenInfo = null;
                }
                if (!startShellGesture(
                        gestureOwner, lastX, lastY, openHandoffEpoch)) {
                    gestureSuppressed = true;
                    moduleLog(Log.WARN, TAG,
                            "Suppressed ended " + openDescription
                                    + " handoff after Shell rejected navigation"
                                    + identityDescription);
                    return;
                }
                ShellGestureSession session = activeShellSession;
                moduleLog(Log.INFO, TAG,
                        (legacyInterruptGesture
                                ? "Handed ended " + openDescription
                                + " gesture to Xiaomi OPEN interruption"
                                : "Handed ended " + openDescription + " gesture to Shell")
                                + identityDescription
                                + ", distance=" + distance
                                + ", legacyInterrupt=" + legacyInterruptGesture
                                + ", aospNullNavigation="
                                + aospNullNavigationGesture
                                + ", trackerSeededBeforeRunner="
                                + !legacyInterruptGesture
                                + ", shellSessionId="
                                + (session == null ? 0L : session.id));
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed " + openDescription + "-to-Shell handoff"
                                + identityDescription,
                        throwable);
            }
        }

        protected boolean crossIntentThreshold(float distance) {
            if (thresholdCrossed) {
                return false;
            }
            thresholdCrossed = true;
            if (legacyInterruptGesture) {
                moduleLog(Log.INFO, TAG, "MIUI in-app interrupt threshold crossed, distance="
                        + distance);
            } else if (launcherOpenBreakGesture) {
                moduleLog(Log.INFO, TAG, "MiuiHome launcher OPEN break threshold crossed, distance="
                        + distance);
            } else {
                moduleLog(Log.INFO, TAG, launcherShadeGesture
                        ? "SystemUI NotificationShade Shell callback threshold crossed, distance="
                        + distance
                        : launcherOverviewGesture
                        ? "SystemUI Recents Shell callback threshold crossed, distance=" + distance
                        : "SystemUI gesture driver intent threshold crossed, distance=" + distance);
            }
            return true;
        }

        protected boolean onUp(MotionEvent event, boolean allowTrigger) throws Exception {
            if (!gestureActive) {
                return false;
            }
            if (gestureSuppressed) {
                dispatchToEdgePlugin(event, activeEdge);
                clearControllerTriggerAfterVisualOnlyGesture();
                clearLocalGestureState();
                moduleLog(Log.INFO, TAG, "Finished suppressed SystemUI back gesture");
                return true;
            }
            if (launcherOpenBreakGesture) {
                return finishLauncherOpenBreakGesture(event, allowTrigger);
            }
            if (legacyInterruptGesture) {
                return finishLegacyInterruptGesture(event, allowTrigger);
            }
            if (recentsVisualOnlyGesture) {
                // BackPanelController may set BackAnimationImpl's trigger bit while completing
                // its local animation. Clear it after UP/CANCEL: cancellation of the rejected
                // Shell navigation was already queued, and this gesture must never commit later.
                dispatchToEdgePlugin(event, activeEdge);
                float releaseDistance = activeEdge == EDGE_LEFT
                        ? event.getRawX() - downX
                        : downX - event.getRawX();
                maybePlayAospIndicatorHandUpHaptic(
                        readNativePanelState(), allowTrigger && thresholdCrossed);
                clearControllerTriggerAfterVisualOnlyGesture();
                moduleLog(Log.INFO, TAG, "Finished visual-only Recents edge gesture"
                        + ", releaseDistance=" + releaseDistance
                        + ", nativeReleaseDelivered=" + allowTrigger
                        + ", inputRemainedUnpilfered=true");
                clearLocalGestureState();
                return false;
            }
            if (!shellGestureStarted) {
                cancelLocalGesture(event, "released before back intent qualified");
                return true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            String panelStateAfterRelease = readNativePanelState();
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean releaseAllowed = allowTrigger && thresholdCrossed;
            ShellGestureSession session = activeShellSession;
            if (session == null || session.edge != activeEdge) {
                cancelLocalGesture(event,
                        "missing fixed Shell session at release");
                return true;
            }
            boolean ownerStillCurrent = isShellSessionOwnerCurrent(session);
            if (!ownerStillCurrent) {
                releaseAllowed = false;
                moduleLog(Log.WARN, TAG,
                        "Forced Shell release cancellation after owner changed"
                                + ", shellSessionId=" + session.id
                                + ", sessionController="
                                + shortObject(session.controller)
                                + ", currentController="
                                + shortObject(controller)
                                + ", sessionInputEpoch="
                                + session.inputEpoch
                                + ", currentInputEpoch="
                                + inputMonitorEpoch.get());
            }
            maybePlayAospIndicatorHandUpHaptic(panelStateAfterRelease, releaseAllowed);
            MiuiHomeAcceptedInputToken releaseInputIdentity =
                    session.inputIdentity.get();
            boolean queued = queueShellReleaseTransaction(
                    session,
                    event.getRawX(), event.getRawY(), releaseDistance,
                    thresholdCrossed, releaseAllowed, activeEdge,
                    launcherOverviewGesture, launcherShadeGesture, launcherDrawerGesture,
                    launcherEditingGesture, aospNullNavigationGesture,
                    session.inputEpoch,
                    releaseInputIdentity);
            moduleLog(queued ? Log.INFO : Log.ERROR, TAG,
                    "SystemUI gesture driver release queued=" + queued
                            + ", releaseAllowed=" + releaseAllowed
                            + ", recentsShellCallback=" + launcherOverviewGesture
                            + ", shadeShellCallback=" + launcherShadeGesture
                            + ", drawerOrFolderShellCallback=" + launcherDrawerGesture
                            + ", editingShellCallback=" + launcherEditingGesture
                            + ", aospNullNavigation=" + aospNullNavigationGesture
                            + ", shellSessionId=" + session.id
                            + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        protected boolean finishLegacyInterruptGesture(MotionEvent event, boolean allowTrigger)
                throws Exception {
            String panelStateBeforeRelease = readNativePanelState();
            boolean panelReleaseDelivered =
                    dispatchToEdgePlugin(event, activeEdge);
            String panelStateAfterRelease = readNativePanelState();
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean releaseAllowed = allowTrigger && thresholdCrossed;
            Boolean nativePanelTrigger = resolveNativePanelReleaseTrigger(
                    panelStateAfterRelease, panelReleaseDelivered);
            Object releaseController = isShellOwnerCurrent(gestureOwner)
                    ? gestureOwner.controller : null;
            boolean exactReleaseOwner = releaseController != null
                    && isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, releaseController,
                    gestureOwner.inputEpoch);
            boolean trigger = releaseAllowed
                    && exactReleaseOwner
                    && Boolean.TRUE.equals(nativePanelTrigger);
            if (trigger) {
                maybePlayAospIndicatorHandUpHaptic(panelStateAfterRelease, true);
            }
            if (trigger) {
                // A normal BACK creates the incoming CLOSE/TO_BACK transition. Xiaomi's
                // TransitionControllerImpl tags a consecutive inverse transition pair and
                // DefaultTransitionImpl.mergeAnimation() reverses the running OPEN animators.
                dispatchLegacyInterruptBack(releaseController);
            }
            moduleLog(Log.INFO, TAG, "Finished MIUI in-app interrupt gesture"
                    + ", releaseAllowed=" + releaseAllowed
                    + ", nativePanelTrigger=" + nativePanelTrigger
                    + ", exactReleaseOwner=" + exactReleaseOwner
                    + ", panelStateBeforeRelease="
                    + panelStateBeforeRelease
                    + ", panelStateAfterRelease="
                    + panelStateAfterRelease
                    + ", trigger=" + trigger
                    + ", releaseDistance=" + releaseDistance
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        void detach() {
            synchronized (backInputLifecycleLock) {
                inputMonitorAttached = false;
                inputMonitorEpoch.incrementAndGet();
            }
            teardownMiuiStyleIndicator();
            if (pendingLauncherOpenBreakAttemptId != 0L) {
                decrementLauncherOpenBreakCommandsInFlight();
            }
            pendingLauncherOpenBreakGeneration = 0L;
            pendingLauncherOpenBreakAttemptId = 0L;
            clearSystemUiReturnHomeCommitIdentity(
                    controller, 0L, "driverDetach");
            clearLocalGestureState();
        }

        protected boolean finishLauncherOpenBreakGesture(MotionEvent event,
                                                         boolean allowTrigger)
                throws Exception {
            String panelStateBeforeRelease = readNativePanelState();
            boolean panelReleaseDelivered =
                    dispatchToEdgePlugin(event, activeEdge);
            String panelStateAfterRelease = readNativePanelState();
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean releaseAllowed = allowTrigger && thresholdCrossed;
            Boolean nativePanelTrigger = resolveNativePanelReleaseTrigger(
                    panelStateAfterRelease, panelReleaseDelivered);
            Object releaseController = isShellOwnerCurrent(gestureOwner)
                    ? gestureOwner.controller : null;
            boolean exactReleaseOwner = releaseController != null
                    && isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, releaseController,
                    gestureOwner.inputEpoch);
            boolean trigger = releaseAllowed
                    && exactReleaseOwner
                    && Boolean.TRUE.equals(nativePanelTrigger);
            if (trigger) {
                maybePlayAospIndicatorHandUpHaptic(panelStateAfterRelease, true);
            }
            // This gesture never starts a Shell tracker. Clear any trigger value posted by
            // BackPanelController after its release event, then hand a committed gesture to
            // MiuiHome's own BackGestureBreakController.
            clearControllerTriggerAfterVisualOnlyGesture();
            if (trigger) {
                long generation = launcherOpenBreakGeneration;
                long attemptId = launcherOpenBreakAttemptId;
                pendingLauncherOpenBreakGeneration = generation;
                pendingLauncherOpenBreakAttemptId = attemptId;
                launcherOpenBreakCommandsInFlight.incrementAndGet();
                try {
                    sendAuthenticatedMiuiHomeOpenBreakCommand(
                            context, generation, attemptId, this,
                            releaseController);
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG, "Failed to send launcher OPEN break command",
                            throwable);
                    onLauncherOpenBreakCommandResult(generation, attemptId,
                            LAUNCHER_OPEN_BREAK_RESULT_REJECTED, "sendException",
                            releaseController);
                }
            }
            moduleLog(Log.INFO, TAG, "Finished MiuiHome launcher OPEN break gesture"
                    + ", trigger=" + trigger
                    + ", generation=" + launcherOpenBreakGeneration
                    + ", attempt=" + launcherOpenBreakAttemptId
                    + ", releaseDistance=" + releaseDistance
                    + ", releaseAllowed=" + releaseAllowed
                    + ", nativePanelTrigger=" + nativePanelTrigger
                    + ", exactReleaseOwner=" + exactReleaseOwner
                    + ", panelStateBeforeRelease="
                    + panelStateBeforeRelease
                    + ", panelStateAfterRelease="
                    + panelStateAfterRelease
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        public void onLauncherOpenBreakCommandResult(long generation, long attemptId,
                                                     int resultCode, String reason,
                                                     Object releaseController) {
            if (pendingLauncherOpenBreakGeneration != generation
                    || pendingLauncherOpenBreakAttemptId != attemptId) {
                moduleLog(Log.WARN, TAG, "Ignored stale launcher OPEN break command result"
                        + ", generation=" + generation
                        + ", attempt=" + attemptId
                        + ", pendingGeneration=" + pendingLauncherOpenBreakGeneration
                        + ", pendingAttempt=" + pendingLauncherOpenBreakAttemptId
                        + ", resultCode=" + resultCode
                        + ", reason=" + reason);
                return;
            }
            decrementLauncherOpenBreakCommandsInFlight();
            pendingLauncherOpenBreakGeneration = 0L;
            pendingLauncherOpenBreakAttemptId = 0L;
            if (resultCode == LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED) {
                moduleLog(Log.INFO, TAG, "MiuiHome accepted launcher OPEN break command"
                        + ", generation=" + generation
                        + ", attempt=" + attemptId);
                return;
            }
            moduleLog(Log.WARN, TAG, "Falling back to one ordinary BACK after launcher OPEN "
                    + "break rejection"
                    + ", generation=" + generation
                    + ", attempt=" + attemptId
                    + ", resultCode=" + resultCode
                    + ", reason=" + reason);
            injectLegacyBackKey(releaseController);
        }

        protected void decrementLauncherOpenBreakCommandsInFlight() {
            int remaining = launcherOpenBreakCommandsInFlight.decrementAndGet();
            if (remaining < 0) {
                launcherOpenBreakCommandsInFlight.set(0);
                moduleLog(Log.WARN, TAG, "Corrected launcher OPEN break in-flight underflow");
            }
        }

        protected boolean startShellGesture() throws Exception {
            return startShellGesture(null, 0.0f, 0.0f, 0L);
        }

        protected boolean startShellGesture(
                ShellOwner progressOwner, float progressX, float progressY,
                long openHandoffEpoch) throws Exception {
            if (openHandoffEpoch != 0L
                    && !isOpenEndHandoffCurrent(openHandoffEpoch)) {
                return false;
            }
            ShellOwner owner = progressOwner == null
                    ? gestureOwner : progressOwner;
            if (!isShellOwnerCurrent(owner)) {
                moduleLog(Log.WARN, TAG, "Rejected gesture without a stable Shell start owner"
                        + ", controller=" + shortObject(controller)
                        + ", inputEpoch=" + inputMonitorEpoch.get()
                        + ", inputAttached=" + inputMonitorAttached);
                return false;
            }
            // A running Xiaomi OPEN transition is the native interruption source. Prefer it
            // even when system_server can already return a valid predictive-back navigation;
            // otherwise Shell starts a new cross-activity animation and misses reverse().
            boolean launcherCallbackOnly = launcherOverviewGesture
                    || launcherShadeGesture || launcherXiaoAiGesture
                    || launcherDrawerGesture
                    || launcherEditingGesture;
            OpenTransitionSnapshot runningOpen = launcherCallbackOnly
                    ? null : findReversibleRunningOpenTransition();
            if (runningOpen != null) {
                legacyInterruptGesture = true;
                legacyRunningOpenInfo = runningOpen.transitionInfo;
                moduleLog(Log.INFO, TAG, "Preferred running Xiaomi OPEN transition before predictive back");
                return true;
            }

            try {
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                updateNativeBackPanelDisplaySize(edgeBackGestureHandler, plugin);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to update display size before Shell start",
                        throwable);
            }
            float maxDistance = Math.max(1.0f,
                    context.getResources().getDisplayMetrics().widthPixels);
            float linearThreshold = readFloatFieldOrDefault(
                    edgeBackGestureHandler, "mBackSwipeLinearThreshold",
                    dp(AOSP_PROGRESS_THRESHOLD_DP));
            float linearDistance = Math.min(maxDistance, linearThreshold);
            float nonLinearFactor = readFloatFieldOrDefault(
                    edgeBackGestureHandler, "mNonLinearFactor", 0.0f);
            float startX = downX;
            float startY = downY;
            int startEdge = activeEdge;
            if (!prepareShellSessionSlotForStart()) {
                return false;
            }

            AtomicReference<ShellStartSnapshot> startResult =
                    new AtomicReference<>();
            AtomicReference<ShellGestureSession> abandonedSession =
                    new AtomicReference<>();
            AtomicBoolean abandoned = new AtomicBoolean();
            AtomicBoolean startTaskEntered = new AtomicBoolean();
            Runnable startTask = () -> {
                boolean startInvoked = false;
                try {
                    startTaskEntered.set(true);
                    if (abandoned.get()) {
                        return;
                    }
                    String state = describeShellStateOnOwner(owner.controller);
                    if (!isShellStartReadyOnOwner(owner.controller)) {
                        startResult.set(new ShellStartSnapshot(
                                false, false, state, null, null,
                                false, null));
                        return;
                    }
                    ensureAospBackAnimations(owner.controller,
                            "gestureStartOwner");
                    if (abandoned.get()) {
                        return;
                    }
                    if (openHandoffEpoch != 0L
                            && !isOpenEndHandoffCurrent(openHandoffEpoch)) {
                        startResult.set(new ShellStartSnapshot(
                                false, false, "stale-ended-open-handoff",
                                null, null, false, null));
                        return;
                    }
                    startInvoked = true;
                    invokeAnyMethod(owner.controller, "onGestureStarted",
                            new Object[]{Float.valueOf(startX),
                                    Float.valueOf(startY),
                                    Integer.valueOf(startEdge)});
                    Object tracker = invokeAnyMethod(owner.controller,
                            "getActiveTracker", new Object[0]);
                    if (tracker != null) {
                        applyProgressThresholds(tracker, linearDistance,
                                maxDistance, nonLinearFactor);
                    }
                    if (progressOwner != null) {
                        if (tracker == null) {
                            throw new IllegalStateException(
                                    "Accumulated Shell start has no active tracker");
                        }
                        ((BackTouchTracker) tracker).update(progressX, progressY);
                        invokeAnyMethod(owner.controller,
                                "onThresholdCrossed", new Object[0]);
                    }
                    Object navigation = readField(owner.controller,
                            "mBackNavigationInfo");
                    boolean receivedNull = Boolean.TRUE.equals(readField(
                            owner.controller, "mReceivedNullNavigationInfo"));
                    startResult.set(new ShellStartSnapshot(
                            true, true,
                            describeShellStateOnOwner(owner.controller),
                            tracker, navigation, receivedNull, null));
                } catch (Throwable throwable) {
                    Object tracker = null;
                    Object navigation = null;
                    boolean receivedNull = false;
                    try {
                        tracker = invokeAnyMethod(owner.controller,
                                "getActiveTracker", new Object[0]);
                        navigation = readField(owner.controller,
                                "mBackNavigationInfo");
                        receivedNull = Boolean.TRUE.equals(readField(
                                owner.controller,
                                "mReceivedNullNavigationInfo"));
                    } catch (Throwable captureFailure) {
                        throwable.addSuppressed(captureFailure);
                    }
                    startResult.set(new ShellStartSnapshot(
                            false, startInvoked,
                            describeShellStateOnOwner(owner.controller),
                            tracker, navigation, receivedNull, throwable));
                } finally {
                    if (abandoned.get()) {
                        handleAbandonedShellStart(owner, startResult.get(),
                                abandonedSession, linearDistance,
                                maxDistance, nonLinearFactor,
                                startX, startY, startEdge);
                    }
                }
            };
            shellStartInFlight = true;
            boolean blockingStartCompleted = executeShellBlocking(
                    owner.executor, startTask, "gestureStart");
            if (!blockingStartCompleted) {
                abandoned.set(true);
                shellOwnerUncertain = true;
                boolean cleanupQueued = false;
                try {
                    owner.executor.execute(() -> handleAbandonedShellStart(
                            owner, startResult.get(), abandonedSession,
                            linearDistance, maxDistance, nonLinearFactor,
                            startX, startY, startEdge));
                    cleanupQueued = true;
                } catch (Throwable cleanupFailure) {
                    moduleLog(Log.ERROR, TAG,
                            "Failed to queue abandoned Shell-start cleanup",
                            cleanupFailure);
                }
                if (!cleanupQueued && !startTaskEntered.get()) {
                    shellStartInFlight = false;
                    shellOwnerUncertain = false;
                }
                moduleLog(Log.ERROR, TAG,
                        "Rejected gesture after Shell blocking start failed"
                                + ", cleanupQueued=" + cleanupQueued
                                + ", taskEntered=" + startTaskEntered.get()
                                + ", controller="
                                + shortObject(owner.controller)
                                + ", inputEpoch=" + owner.inputEpoch);
                return false;
            }
            ShellStartSnapshot start = startResult.get();
            if (start == null) {
                shellOwnerUncertain = true;
                abandoned.set(true);
                start = startResult.get();
                if (start != null) {
                    handleAbandonedShellStart(owner, start,
                            abandonedSession, linearDistance,
                            maxDistance, nonLinearFactor,
                            startX, startY, startEdge);
                }
                moduleLog(Log.ERROR, TAG,
                        "Rejected gesture after Shell blocking start timed out"
                                + ", controller="
                                + shortObject(owner.controller)
                                + ", inputEpoch=" + owner.inputEpoch);
                return false;
            }
            if (start.failure != null) {
                if (start.startInvoked) {
                    handleAbandonedShellStart(owner, start,
                            abandonedSession, linearDistance,
                            maxDistance, nonLinearFactor,
                            startX, startY, startEdge);
                }
                shellStartInFlight = false;
                moduleLog(Log.ERROR, TAG, "Shell-owner gesture start failed",
                        start.failure);
                return false;
            }
            if (!start.ready || !start.startInvoked) {
                shellStartInFlight = false;
                moduleLog(Log.WARN, TAG, "Rejected gesture while Shell is busy"
                        + ", state=" + start.stateDescription);
                return false;
            }
            ShellGestureSession session = new ShellGestureSession(
                    owner, start, startEdge, startX, startY, linearDistance,
                    maxDistance, nonLinearFactor, acceptedInputIdentity);
            if (publishShellGestureSession(session)) {
                shellOwnerUncertain = true;
                shellStartInFlight = false;
                cancelUnpublishedShellSession(session,
                        "activeSessionCollision");
                return false;
            }
            shellStartInFlight = false;
            Object info = start.navigation;
            boolean receivedNull = start.receivedNullNavigation;
            if (!isShellOwnerCurrent(owner)) {
                moduleLog(Log.WARN, TAG, "Rejected Shell gesture after start owner changed"
                        + ", sessionId=" + session.id
                        + ", startController=" + shortObject(owner.controller)
                        + ", currentController=" + shortObject(controller)
                        + ", startInputEpoch=" + owner.inputEpoch
                        + ", currentInputEpoch=" + inputMonitorEpoch.get());
                cleanupRejectedShellGesture(session);
                return false;
            }
            // A received-null result is not a predictive target. Check it before any target
            // handling, and never carry an inconsistent non-null target into the legacy path.
            if (!ShellNavigationIdentity.isConsistent(info, receivedNull)) {
                cleanupRejectedShellGesture(session);
                moduleLog(Log.WARN, TAG, "Rejected inconsistent Shell navigation result"
                        + ", info=" + shortObject(info)
                        + ", receivedNull=" + receivedNull);
                return false;
            }
            if (receivedNull || info == null) {
                if (launcherOverviewGesture) {
                    recentsVisualOnlyGesture = true;
                }
                if (launcherCallbackOnly) {
                    cleanupRejectedShellGesture(session);
                    if (launcherOverviewGesture) {
                        moduleLog(Log.DEBUG, TAG, "Rejected null Recents BackNavigationInfo"
                                + ", mode=visual-only"
                                + ", retry=false");
                    }
                    return false;
                }
                runningOpen = findReversibleRunningOpenTransition();
                if (receivedNull && runningOpen != null) {
                    cleanupRejectedShellGesture(session);
                    legacyInterruptGesture = true;
                    legacyRunningOpenInfo = runningOpen.transitionInfo;
                    moduleLog(Log.INFO, TAG, "Using SystemUI-owned legacy BACK for possible "
                            + "MIUI in-app transition interruption");
                    return true;
                }
                boolean authenticatedNullStart = false;
                if (info == null && receivedNull) {
                    synchronized (backInputLifecycleLock) {
                        if (isCurrentAcceptedInputIdentity(
                                acceptedInputIdentity, activeEdge,
                                session.controller, session.inputEpoch)) {
                            // Match stock BackAnimationController: retain this authenticated
                            // physical stream, let the native panel drive the tracker's terminal
                            // trigger, and inject one legacy BACK only if release commits.
                            // Launcher callback probes and Shell-busy rejection never reach this
                            // branch.
                            aospNullNavigationGesture = true;
                            shellGestureStarted = true;
                            authenticatedNullStart = true;
                        }
                    }
                }
                if (authenticatedNullStart) {
                    scheduleRecentsIdleNavigationRetry(session);
                    moduleLog(Log.DEBUG, TAG, "Continuing authenticated in-app gesture with AOSP "
                            + "null-navigation fallback"
                            + ", input=" + shortObject(acceptedInputIdentity)
                            + ", edge=" + activeEdge);
                    return true;
                }
                moduleLog(Log.WARN, TAG, "Shell rejected back navigation"
                        + ", info=" + shortObject(info)
                        + ", receivedNull=" + receivedNull
                        + ", state=" + start.stateDescription);
                cleanupRejectedShellGesture(session);
                return false;
            }
            if (launcherCallbackOnly) {
                int navigationType = info instanceof BackNavigationInfo
                        ? ((BackNavigationInfo) info).getType() : -1;
                if (navigationType != TYPE_CALLBACK) {
                    moduleLog(Log.WARN, TAG, (launcherShadeGesture
                            ? "Rejected non-callback NotificationShade Shell target"
                            : launcherXiaoAiGesture
                            ? "Rejected non-callback XiaoAi Shell target"
                            : launcherOverviewGesture
                            ? "Rejected stale Recents Shell target"
                            : launcherDrawerGesture
                            ? "Rejected non-callback MiuiHome callback surface Shell target"
                            : "Rejected non-callback MiuiHome editing Shell target")
                            + ", type=" + navigationType
                            + ", info=" + shortObject(info));
                    if (launcherOverviewGesture) {
                        recentsVisualOnlyGesture = true;
                    }
                    cleanupRejectedShellGesture(session);
                    return false;
                }
                moduleLog(Log.INFO, TAG, (launcherShadeGesture
                        ? "Resolved NotificationShade Shell callback, type="
                        : launcherXiaoAiGesture
                        ? "Resolved XiaoAi Shell callback, type="
                        : launcherOverviewGesture
                        ? "Resolved Launcher Recents Shell callback, type="
                        : launcherDrawerGesture
                        ? "Resolved MiuiHome callback surface Shell callback, type="
                        : "Resolved MiuiHome editing Shell callback, type=")
                        + navigationType);
            }
            shellGestureStarted = true;
            moduleLog(Log.INFO, TAG, "SystemUI gesture driver onGestureStarted"
                    + ", shellSessionId=" + session.id
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        /**
         * Read on the input owner; both task and activity must survive the idle wait.
         * HyperTweak: {@code getRunningTasks} is deprecated for third-party apps, but this runs in
         * privileged SystemUI, which is the only component that can observe the live focus task.
         * UsageStats is not an equivalent source of current RunningTaskInfo/topActivity.
         */
        @SuppressWarnings("deprecation")
        protected android.app.ActivityManager.RunningTaskInfo focusedAppTask() {
            try {
                android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
                List<android.app.ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(20);
                for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                    Object taskDisplay = readField(task, "displayId");
                    if (!(taskDisplay instanceof Number)
                            || ((Number) taskDisplay).intValue() != displayId) continue;
                    if (task.topActivity == null || MIUI_HOME.equals(task.topActivity.getPackageName())) return null;
                    return task;
                }
                return null;
            } catch (Throwable failure) { return null; }
        }

        protected String focusIdentity(android.app.ActivityManager.RunningTaskInfo task) {
            return task == null ? null : task.taskId + ":" + task.topActivity.flattenToString();
        }

        /** All Recents container/list reads run on Shell's owner, never the input Looper. */
        protected List<?> recentsControllersOnOwner(Object transitions) throws Exception {
            Object recents = invokeAnyMethod(transitions, "getRecentsTransitionHandler", new Object[0]);
            Object controllers = readField(recents, "mControllers");
            return controllers instanceof List ? (List<?>) controllers : null;
        }

        protected void scheduleRecentsIdleNavigationRetry(ShellGestureSession session) {
            android.app.ActivityManager.RunningTaskInfo focus = focusedAppTask();
            if (focus == null || session.inputIdentity.get() == null) return;
            RecentsIdleRetry retry = new RecentsIdleRetry(session, session.inputIdentity.get(),
                    session.inputEpoch, focusIdentity(focus));
            executeShellBlocking(session.executor, () -> {
                try {
                    Object transitions = readField(session.controller, "mTransitions");
                    List<?> controllers = recentsControllersOnOwner(transitions);
                    if (controllers == null) return;
                    Object captured = null;
                    for (Object candidate : controllers) {
                        Object type = readField(candidate, "mRemoteToRecentType");
                        Object token = readField(candidate, "mTransition");
                        Object info = readField(candidate, "mInfo");
                        if (!"LAUNCH_FROM_HOME".equals(String.valueOf(type))
                                || readField(candidate, "mFinishCB") == null
                                || !Boolean.TRUE.equals(invokeAnyMethod(transitions, "isKnownTransition", new Object[]{token}))
                                || !(info instanceof android.window.TransitionInfo)) continue;
                        for (android.window.TransitionInfo.Change change : ((android.window.TransitionInfo) info).getChanges()) {
                            android.app.ActivityManager.RunningTaskInfo task = change.getTaskInfo();
                            if (task != null && task.taskId == focus.taskId
                                    && Integer.valueOf(displayId).equals(readField(task, "displayId"))) {
                                captured = candidate;
                                break;
                            }
                        }
                        if (captured != null) break;
                    }
                    if (captured == null) return;
                    moduleLog(Log.INFO, TAG, "Queued same-stream navigation retry after native Recents idle"
                            + ", sessionId=" + session.id + ", focus=" + focusIdentity(focus));
                    // Stock runOnIdle runs after native transition finish, including the WM finish IPC.
                    invokeAnyMethod(transitions, "runOnIdle", new Object[]{(Runnable) () ->
                            new Handler(Looper.getMainLooper()).post(() ->
                                    retryNavigationAfterRecentsIdle(session, retry, transitions))});
                } catch (Throwable failure) {
                    moduleLog(Log.WARN, TAG, "Recents idle retry unavailable; preserving ordinary BACK", failure);
                }
            }, "recentsIdleRegistration");
        }

        protected void retryNavigationAfterRecentsIdle(ShellGestureSession session,
                                                     RecentsIdleRetry retry, Object transitions) {
            if (!gestureActive || gestureSuppressed || !aospNullNavigationGesture
                    || !Preferences.INSTANCE.backGestureEnabled() || !isShellSessionOwnerCurrent(session)) return;
            if (!isRecentsRetryInputAllowed(edgeBackGestureHandler)) return;
            android.app.ActivityManager.RunningTaskInfo focus = focusedAppTask();
            if (focus == null) return;
            String currentFocus = focusIdentity(focus);
            AtomicReference<ShellStartSnapshot> result = new AtomicReference<>();
            AtomicBoolean abandoned = new AtomicBoolean();
            boolean completed = executeShellBlocking(session.executor, () -> {
                boolean invoked = false;
                try {
                    if (abandoned.get()) return;
                    List<?> remaining = recentsControllersOnOwner(transitions);
                    if (!retry.claim(activeShellSession, acceptedInputIdentity, inputMonitorEpoch.get(),
                            focusIdentity(focusedAppTask()), gestureActive, session.releaseQueued.get(),
                            remaining != null && remaining.isEmpty())
                            || session.completionConsumed.get() || session.moveFailed.get()
                            || !isCurrentAcceptedInputIdentity(session.inputIdentity.get(), session.edge,
                                    session.controller, session.inputEpoch)
                            || readField(session.controller, "mCurrentTracker") != session.tracker
                            || readField(session.controller, "mBackNavigationInfo") != null
                            || !Boolean.TRUE.equals(readField(session.controller, "mReceivedNullNavigationInfo"))
                            || !Boolean.TRUE.equals(invokeAnyMethod(session.tracker, "isActive", new Object[0]))) return;
                    // Retry native navigation on the existing tracker. Do not synthesize another DOWN,
                    // clear a running navigation, or bypass system_server's Recents guard.
                    if (abandoned.get()) return;
                    invoked = true;
                    writeField(session.controller, "mReceivedNullNavigationInfo", Boolean.FALSE);
                    invokeAnyMethod(session.controller, "startBackNavigation", new Object[]{session.tracker});
                    Object navigation = readField(session.controller, "mBackNavigationInfo");
                    if (navigation == null) {
                        writeField(session.controller, "mReceivedNullNavigationInfo", Boolean.TRUE);
                        return;
                    }
                    if (abandoned.get() || activeShellSession != session || session.releaseQueued.get()
                            || !currentFocus.equals(focusIdentity(focusedAppTask()))) {
                        invokeAnyMethod(session.controller, "finishBackNavigation", new Object[]{Boolean.FALSE});
                        writeField(session.controller, "mReceivedNullNavigationInfo", Boolean.TRUE);
                        return;
                    }
                    ((BackTouchTracker) session.tracker).update(lastX, lastY);
                    invokeAnyMethod(session.controller, "startPredictiveBackAnimationIfNeeded", new Object[0]);
                    navigation = readField(session.controller, "mBackNavigationInfo");
                    if (navigation == null) {
                        writeField(session.controller, "mReceivedNullNavigationInfo", Boolean.TRUE);
                        return;
                    }
                    result.set(new ShellStartSnapshot(true, true, "recents-idle-retry",
                            session.tracker, navigation, false, null));
                } catch (Throwable failure) {
                    try {
                        // A partially started retry must not leave the old null receipt pointing
                        // at a new navigation. Cancel only this still-owned tracker and restore fallback.
                        if (invoked && activeShellSession == session
                                && readField(session.controller, "mCurrentTracker") == session.tracker) {
                            if (readField(session.controller, "mBackNavigationInfo") != null) {
                                invokeAnyMethod(session.controller, "finishBackNavigation", new Object[]{Boolean.FALSE});
                            }
                            writeField(session.controller, "mReceivedNullNavigationInfo", Boolean.TRUE);
                        }
                    } catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                    moduleLog(Log.WARN, TAG, "Same-stream Recents navigation retry failed", failure);
                }
            }, "recentsIdleNavigationRetry");
            if (!completed) {
                abandoned.set(true);
                // The owner task checks abandonment before and after its native call.
                // A completed late result is canceled on the captured owner, never a new stream.
                try { session.executor.execute(() -> {
                    try {
                        if (result.get() != null && readField(session.controller, "mCurrentTracker") == session.tracker
                                && readField(session.controller, "mBackNavigationInfo") == result.get().navigation) {
                            invokeAnyMethod(session.controller, "finishBackNavigation", new Object[]{Boolean.FALSE});
                            writeField(session.controller, "mReceivedNullNavigationInfo", Boolean.TRUE);
                        }
                    } catch (Throwable failure) {
                        moduleLog(Log.WARN, TAG, "Failed to retire abandoned Recents retry", failure);
                    }
                }); } catch (Throwable failure) {
                    moduleLog(Log.WARN, TAG, "Could not enqueue abandoned Recents retry cleanup", failure);
                }
                return;
            }
            ShellStartSnapshot start = result.get();
            if (start == null) return;
            ShellGestureSession replacement = new ShellGestureSession(gestureOwner, start,
                    session.edge, session.startX, session.startY, session.linearDistance,
                    session.maxDistance, session.nonLinearFactor, session.inputIdentity.get());
            synchronized (backInputLifecycleLock) {
                if (activeShellSession != session || session.releaseQueued.get()) return;
                // Retire only the local null-start receipt; its same native tracker now has navigation.
                session.completionConsumed.set(true);
                activeShellSession = replacement;
                aospNullNavigationGesture = false;
            }
            moduleLog(Log.INFO, TAG, "Upgraded same-stream back navigation after Recents idle"
                    + ", oldSessionId=" + session.id + ", sessionId=" + replacement.id
                    + ", focus=" + currentFocus);
        }

        protected boolean prepareShellSessionSlotForStart() {
            ShellGestureSession completedSession;
            synchronized (backInputLifecycleLock) {
                if (shellStartInFlight || shellOwnerUncertain) {
                    moduleLog(Log.WARN, TAG,
                            "Rejected Shell start while ownership is unsettled"
                                    + ", startInFlight=" + shellStartInFlight
                                    + ", ownerUncertain=" + shellOwnerUncertain);
                    return false;
                }
                completedSession = activeShellSession;
                if (completedSession == null) return true;
            }
            if (!completedSession.completionConsumed.get()
                    && completedSession.releaseQueued.get()
                    && completedSession.awaitingStockCleanup.get()) {
                // A cold/absent preview may finish through a vendor path that skips
                // our completion hook. Inspect the captured Shell on its executor;
                // only a fully idle controller can retire this released local slot.
                executeShellBlocking(completedSession.executor, () -> {
                    try {
                        if (activeShellSession == completedSession
                                && completedSession.controller == controller
                                && completedSession.inputEpoch == inputMonitorEpoch.get()
                                && isShellStartReadyOnOwner(completedSession.controller)) {
                            completeShellSessionOnOwner(completedSession, "stock-idle-before-next-start");
                        }
                    } catch (Throwable failure) {
                        moduleLog(Log.WARN, TAG, "Could not verify released Shell session is idle", failure);
                    }
                }, "releasedSessionReadiness");
            }
            if (!completedSession.completionConsumed.get()) {
                moduleLog(Log.WARN, TAG,
                        "Rejected Shell start while another session owns the slot"
                                + ", shellSessionId=" + completedSession.id
                                + ", completionConsumed=false");
                return false;
            }
            completeShellSessionOnMain(completedSession, "retired-before-next-start");
            synchronized (backInputLifecycleLock) {
                return activeShellSession == null && !shellStartInFlight && !shellOwnerUncertain;
            }
        }

        protected boolean publishShellGestureSession(
                ShellGestureSession session) {
            synchronized (backInputLifecycleLock) {
                if (activeShellSession != null) {
                    return true;
                }
                activeShellSession = session;
                shellOwnerUncertain = false;
                return false;
            }
        }

        protected void handleAbandonedShellStart(
                ShellOwner owner, ShellStartSnapshot start,
                AtomicReference<ShellGestureSession> abandonedSession,
                float linearDistance, float maxDistance,
                float nonLinearFactor, float startX, float startY,
                int startEdge) {
            if (start == null) {
                shellStartInFlight = false;
                shellOwnerUncertain = false;
                return;
            }
            if (!start.startInvoked) {
                shellStartInFlight = false;
                shellOwnerUncertain = false;
                return;
            }
            ShellGestureSession session = abandonedSession.get();
            if (session == null) {
                ShellGestureSession candidate = new ShellGestureSession(
                        owner, start, startEdge, startX, startY,
                        linearDistance, maxDistance, nonLinearFactor,
                        acceptedInputIdentity);
                if (abandonedSession.compareAndSet(null, candidate)) {
                    session = candidate;
                } else {
                    session = abandonedSession.get();
                }
            }
            if (session == null || session.releaseQueued.get()) {
                return;
            }
            if (activeShellSession != session
                    && publishShellGestureSession(session)) {
                shellStartInFlight = false;
                cancelUnpublishedShellSession(session,
                        "abandonedStartCollision");
                return;
            }
            shellStartInFlight = false;
            cleanupRejectedShellGesture(session);
        }

        protected void cancelUnpublishedShellSession(
                ShellGestureSession session, String reason) {
            shellOwnerUncertain = true;
            try {
                session.executor.execute(() -> cancelFailedShellRelease(
                        session, session.tracker));
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Could not cancel untracked Shell session"
                                + ", sessionId=" + session.id
                                + ", reason=" + reason,
                        throwable);
            }
        }

        protected OpenTransitionSnapshot findReversibleRunningOpenTransition() {
            OpenTransitionSnapshot active = null;
            for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
                if (snapshot.state.get() == OPEN_SNAPSHOT_ACTIVE) {
                    if (active != null) {
                        moduleLog(Log.WARN, TAG,
                                "Rejected ambiguous reversible OPEN transitions"
                                        + ", firstInfo="
                                        + shortObject(active.transitionInfo)
                                        + ", secondInfo="
                                        + shortObject(snapshot.transitionInfo));
                        return null;
                    }
                    active = snapshot;
                }
            }
            if (active != null) {
                moduleLog(Log.INFO, TAG, "Detected reversible running OPEN transition"
                        + ", animatorCount=" + active.animationCount()
                        + ", info=" + shortObject(active.transitionInfo));
            }
            return active;
        }

        protected boolean isShellStartReadyOnOwner(Object stateController)
                throws Exception {
            if (!isShellReadyOnOwner(stateController)) {
                return false;
            }
            Object transitionHandler = readField(stateController,
                    "mBackTransitionHandler");
            return !Boolean.TRUE.equals(readField(transitionHandler,
                    "mCloseTransitionRequested"))
                    && readField(transitionHandler,
                    "mPrepareOpenTransition") == null
                    && readField(transitionHandler,
                    "mClosePrepareTransition") == null;
        }

        protected String describeShellStateOnOwner(Object stateController) {
            try {
                Object transitionHandler = readField(stateController,
                        "mBackTransitionHandler");
                return "postCommit=" + readField(
                        stateController, "mPostCommitAnimationInProgress")
                        + ", backStarted=" + readField(stateController, "mBackGestureStarted")
                        + ", info=" + shortObject(readField(
                        stateController, "mBackNavigationInfo"))
                        + ", finishedCallback=" + shortObject(
                        readField(stateController, "mBackAnimationFinishedCallback"))
                        + ", current=" + shortObject(readField(
                        stateController, "mCurrentTracker"))
                        + ", queued=" + shortObject(readField(
                        stateController, "mQueuedTracker"))
                        + ", closeRequested=" + readField(
                        transitionHandler, "mCloseTransitionRequested")
                        + ", prepareOpen=" + shortObject(readField(
                        transitionHandler, "mPrepareOpenTransition"))
                        + ", prepareClose=" + shortObject(readField(
                        transitionHandler, "mClosePrepareTransition"));
            } catch (Throwable throwable) {
                return "unavailable:" + throwable.getClass().getSimpleName();
            }
        }

        protected void cleanupRejectedShellGesture(ShellGestureSession session) {
            // A prepared adapter may deliver onAnimationStart after onGestureStarted() returns.
            // Finishing navigation here would clear mBackNavigationInfo first; the late adapter
            // callback would then be retained while startSystemAnimation() exits early, leaving
            // every later gesture blocked by mBackAnimationFinishedCallback. Reuse the complete
            // Shell-owner release transaction so the tracker is finished with trigger=false and
            // a waiting runner receives cancellation before normal navigation cleanup.
            boolean queued = queueShellReleaseTransaction(
                    session,
                    session.startX, session.startY, 0.0f,
                    false, false, session.edge,
                    launcherOverviewGesture, launcherShadeGesture, launcherDrawerGesture,
                    launcherEditingGesture, false, 0L, null);
            moduleLog(queued ? Log.INFO : Log.ERROR, TAG,
                    "Rejected Shell navigation cancellation queued=" + queued
                            + ", releaseAllowed=false"
                            + ", recentsProbe=" + launcherOverviewGesture
                            + ", shadeProbe=" + launcherShadeGesture
                            + ", xiaoAiProbe=" + launcherXiaoAiGesture
                            + ", drawerOrFolderProbe=" + launcherDrawerGesture
                            + ", editingProbe=" + launcherEditingGesture
                            + ", shellSessionId=" + session.id
                            + ", edge=" + session.edge);
        }

        protected void clearControllerTriggerAfterVisualOnlyGesture() {
            try {
                // Queue the clear through the same BackAnimationImpl path used by the panel's
                // BackCallback so it is ordered after any trigger=true posted by ACTION_UP.
                invokeAnyMethod(backAnimationImpl, "setTriggerBack",
                        new Object[]{Boolean.FALSE});
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to queue visual-only trigger clear through BackAnimationImpl",
                        throwable);
            }
        }

        protected boolean isCurrentAcceptedInputIdentity(
                MiuiHomeAcceptedInputToken inputIdentity, int edge,
                Object expectedController, long expectedInputMonitorEpoch) {
            return inputIdentity != null
                    && acceptingBackInputInstalls
                    && systemUiInputArbiterMonitorCount.get() > 0
                    && inputMonitorAttached
                    && expectedInputMonitorEpoch != 0L
                    && expectedInputMonitorEpoch == inputMonitorEpoch.get()
                    && expectedController == controller
                    && inputIdentity.generation == systemUiInputArbiterGeneration
                    && (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL
                    || (inputIdentity.launcherStateOwnerEpoch > 0L
                    && inputIdentity.launcherStateOwnerEpoch
                    == miuiLauncherDartStateOwnerEpoch))
                    && inputIdentity.edge == edge;
        }

        protected void clearLocalGestureState() {
            gestureActive = false;
            shellGestureStarted = false;
            shellGestureStartDeferred = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            aospNullNavigationGesture = false;
            legacyRunningOpenInfo = null;
            launcherOpenBreakGesture = false;
            launcherOpenBreakGeneration = 0L;
            launcherOpenBreakAttemptId = 0L;
            launcherOverviewGesture = false;
            launcherShadeGesture = false;
            launcherXiaoAiGesture = false;
            launcherDrawerGesture = false;
            launcherEditingGesture = false;
            recentsVisualOnlyGesture = false;
            acceptedInputIdentity = null;
            gestureOwner = null;
            thresholdCrossed = false;
        }

        protected void cancelLocalGesture(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                dispatchToEdgePlugin(cancel, activeEdge);
                cancel.recycle();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to cancel local edge panel", throwable);
            }
            clearLocalGestureState();
            moduleLog(Log.INFO, TAG, "Cancelled local SystemUI back gesture, reason=" + reason);
        }

        protected Runnable captureShellAnimationCompletion(
                Object finishedController, Object currentTracker,
                Object queuedTracker, Object navigation,
                Object finishCallback, String reason) {
            ShellGestureSession session = activeShellSession;
            boolean exactIdentity = session != null
                    && session.navigation == navigation
                    && (session.tracker == currentTracker
                    || session.tracker == queuedTracker);
            if (session == null || session.completionConsumed.get()
                    || !session.releaseQueued.get()
                    || session.controller != finishedController
                    || (!exactIdentity
                    && !session.awaitingStockCleanup.get())) {
                return null;
            }
            return () -> {
                if (exactIdentity) {
                    recoverStaleCloseTransitionRequest(
                            finishedController, currentTracker, queuedTracker,
                            finishCallback, session.id);
                }
                if (!exactIdentity) {
                    boolean quiescent = false;
                    try {
                        quiescent = activeShellSession == session
                                && isShellReadyOnOwner(finishedController);
                        if (!quiescent
                                && activeShellSession == session
                                && navigation == null
                                && session.navigation != null
                                && currentTracker != session.tracker
                                && queuedTracker == session.tracker) {
                            Object currentAfterFinish = readField(
                                    finishedController, "mCurrentTracker");
                            Object queuedAfterFinish = readField(
                                    finishedController, "mQueuedTracker");
                            Object animationFinishedCallback = readField(
                                    finishedController,
                                    "mBackAnimationFinishedCallback");
                            boolean resetNavigationLost =
                                    currentAfterFinish == session.tracker
                                            && queuedAfterFinish == currentTracker
                                            && readField(finishedController,
                                            "mBackNavigationInfo") == null
                                            && isTrackerInitial(currentAfterFinish)
                                            && isTrackerInitial(queuedAfterFinish)
                                            && !Boolean.TRUE.equals(readField(
                                            finishedController,
                                            "mPostCommitAnimationInProgress"))
                                            && !Boolean.TRUE.equals(readField(
                                            finishedController,
                                            "mBackGestureStarted"))
                                            && !Boolean.TRUE.equals(readField(
                                            finishedController,
                                            "mReceivedNullNavigationInfo"))
                                            && animationFinishedCallback != null
                                            && session.tracker instanceof BackTouchTracker
                                            && !((BackTouchTracker) session.tracker)
                                            .getTriggerBack();
                            if (resetNavigationLost) {
                                // A focus-taking window can make AOSP reset an unfinished
                                // tracker before UP. Its timeout then skips invokeOrCancelBack()
                                // because both trackers are initial, leaving the animation
                                // callback behind forever. Reuse the native cancellation path
                                // only after that exact stock finish boundary is fully neutral.
                                invokeAnyMethod(finishedController,
                                        "invokeOrCancelBack",
                                        new Object[]{session.tracker});
                                quiescent = isShellReadyOnOwner(
                                        finishedController);
                                if (quiescent) {
                                    moduleLog(Log.WARN, TAG,
                                            "Cancelled orphaned Shell animation callback"
                                                    + " after stock timeout"
                                                    + ", shellSessionId="
                                                    + session.id);
                                }
                            }
                        }
                    } catch (Throwable throwable) {
                        moduleLog(Log.WARN, TAG,
                                "Failed to verify orphaned Shell cleanup",
                                throwable);
                    }
                    if (!quiescent) {
                        moduleLog(Log.WARN, TAG,
                                "Retained orphaned Shell session after non-quiescent finish"
                                        + ", shellSessionId=" + session.id
                                        + ", tracker="
                                        + shortObject(session.tracker)
                                        + ", currentTracker="
                                        + shortObject(currentTracker)
                                        + ", queuedTracker="
                                        + shortObject(queuedTracker)
                                        + ", navigation="
                                        + shortObject(session.navigation)
                                        + ", currentNavigation="
                                        + shortObject(navigation));
                        return;
                    }
                    moduleLog(Log.WARN, TAG,
                            "Accepted definitive stock cleanup for orphaned Shell session"
                                    + ", shellSessionId=" + session.id);
                }
                publishSystemUiReturnHomeFinish(
                        session.controller, session.id,
                        finishCallback, reason);
                completeShellSessionOnOwner(
                        session, "stock-finish:" + reason);
            };
        }

        protected void completeShellSessionOnOwner(
                ShellGestureSession session, String reason) {
            if (session == null
                    || !session.completionConsumed.compareAndSet(
                    false, true)) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(
                    () -> completeShellSessionOnMain(session, reason));
        }

        protected void completeShellSessionOnMain(
                ShellGestureSession session, String reason) {
            synchronized (backInputLifecycleLock) {
                if (activeShellSession != session) {
                    moduleLog(Log.WARN, TAG,
                            "Ignored stale Shell session completion"
                                    + ", shellSessionId=" + session.id
                                    + ", activeSessionId="
                                    + (activeShellSession == null ? 0L
                                    : activeShellSession.id)
                                    + ", reason=" + reason);
                    return;
                }
                activeShellSession = null;
                shellOwnerUncertain = false;
            }
            clearSystemUiReturnHomeCommitIdentity(
                    session.controller, session.id,
                    "shellFinished:" + reason);
        }

        /**
         * Xiaomi's Android 17 Shell can finish the predictive animation without ever entering
         * BackTransitionHandler.handleCloseTransition(). In that case the normal finish callback
         * is already gone, both trackers are reset, and only mCloseTransitionRequested remains
         * set. That flag blocks every subsequent startBackNavigation(). Clear it only after the
         * exact stock-finish quiescence boundary has been observed on the Shell owner executor.
         */
        protected void recoverStaleCloseTransitionRequest(
                Object stateController, Object capturedCurrentTracker,
                Object capturedQueuedTracker, Object capturedFinishCallback,
                long shellSessionId) {
            try {
                Object transitionHandler = readField(stateController,
                        "mBackTransitionHandler");
                boolean stale = Boolean.TRUE.equals(readField(transitionHandler,
                        "mCloseTransitionRequested"))
                        && !Boolean.TRUE.equals(readField(stateController,
                        "mPostCommitAnimationInProgress"))
                        && !Boolean.TRUE.equals(readField(stateController,
                        "mBackGestureStarted"))
                        && !Boolean.TRUE.equals(readField(stateController,
                        "mReceivedNullNavigationInfo"))
                        && readField(stateController, "mBackNavigationInfo") == null
                        && readField(stateController,
                        "mBackAnimationFinishedCallback") == null
                        && readField(transitionHandler,
                        "mOnAnimationFinishCallback") == null
                        && readField(transitionHandler,
                        "mPrepareOpenTransition") == null
                        && readField(transitionHandler,
                        "mClosePrepareTransition") == null
                        && readField(transitionHandler,
                        "mOpenTransitionInfo") == null
                        && readField(transitionHandler,
                        "mFinishOpenTransaction") == null
                        && readField(transitionHandler,
                        "mFinishOpenTransitionCallback") == null
                        && readField(transitionHandler,
                        "mTakeoverHandler") == null
                        && isTrackerInitial(readField(stateController,
                        "mCurrentTracker"))
                        && isTrackerInitial(readField(stateController,
                        "mQueuedTracker"))
                        && capturedFinishCallback == null;
                if (!stale) {
                    return;
                }
                Object currentAfter = readField(stateController, "mCurrentTracker");
                Object queuedAfter = readField(stateController, "mQueuedTracker");
                if (currentAfter != capturedCurrentTracker
                        || queuedAfter != capturedQueuedTracker) {
                    moduleLog(Log.WARN, TAG,
                            "Skipped stale Shell close-request recovery after tracker identity changed"
                                    + ", shellSessionId=" + shellSessionId);
                    return;
                }
                writeField(transitionHandler, "mCloseTransitionRequested",
                        Boolean.FALSE);
                if (!isShellStartReadyOnOwner(stateController)) {
                    moduleLog(Log.WARN, TAG,
                            "Shell close-request recovery did not reach ready state"
                                    + ", shellSessionId=" + shellSessionId
                                    + ", state=" + describeShellStateOnOwner(stateController));
                    return;
                }
                moduleLog(Log.WARN, TAG,
                        "Recovered orphaned Shell close-transition request"
                                + ", shellSessionId=" + shellSessionId);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to recover orphaned Shell close-transition request",
                        throwable);
            }
        }

        protected boolean queueShellReleaseTransaction(ShellGestureSession session,
                                                       float rawX, float rawY,
                                                       float releaseDistance,
                                                       boolean dispatchFinalProgress,
                                                       boolean releaseAllowed,
                                                       int releaseEdge,
                                                       boolean recentsCallback,
                                                       boolean shadeCallback,
                                                       boolean drawerCallback,
                                                       boolean editingCallback,
                                                       boolean aospNullFallback,
                                                       long aospNullInputEpoch,
                                                       MiuiHomeAcceptedInputToken
                                                               inputIdentity) {
            if (session == null || session.completionConsumed.get()
                    || !session.releaseQueued.compareAndSet(false, true)) {
                return false;
            }
            try {
                session.executor.execute(() -> finishGestureOnShellExecutor(
                        session, rawX, rawY, releaseDistance,
                        dispatchFinalProgress, releaseAllowed, releaseEdge,
                        recentsCallback, shadeCallback, drawerCallback, editingCallback,
                        aospNullFallback, aospNullInputEpoch, inputIdentity));
                return true;
            } catch (Throwable throwable) {
                // A release must never fall back to mutating controller/tracker state from
                // the input Looper. Fail closed if the owner executor cannot be reached.
                moduleLog(Log.ERROR, TAG, "Failed to queue complete Shell release transaction"
                                + ", shellSessionId=" + session.id,
                        throwable);
                session.awaitingStockCleanup.set(true);
                shellOwnerUncertain = true;
                return false;
            }
        }

        protected void finishGestureOnShellExecutor(ShellGestureSession session,
                                                    float rawX, float rawY,
                                                    float releaseDistance,
                                                    boolean dispatchFinalProgress,
                                                    boolean releaseAllowed,
                                                    int releaseEdge,
                                                    boolean recentsCallback,
                                                    boolean shadeCallback,
                                                    boolean drawerCallback,
                                                    boolean editingCallback,
                                                    boolean aospNullFallback,
                                                    long aospNullInputEpoch,
                                                    MiuiHomeAcceptedInputToken
                                                            inputIdentity) {
            Object releaseController = session.controller;
            Object tracker = null;
            try {
                if (releaseAllowed && !isCurrentAcceptedInputIdentity(
                        inputIdentity, releaseEdge, releaseController,
                        session.inputEpoch)) {
                    releaseAllowed = false;
                    moduleLog(Log.WARN, TAG,
                            "Forced Shell release cancellation after launcher "
                                    + "state owner changed"
                                    + ", shellSessionId=" + session.id
                                    + ", inputOwnerEpoch="
                                    + (inputIdentity == null ? 0L
                                    : inputIdentity.launcherStateOwnerEpoch)
                                    + ", currentOwnerEpoch="
                                    + miuiLauncherDartStateOwnerEpoch);
                }
                if (session.moveFailed.get()) {
                    releaseAllowed = false;
                    moduleLog(Log.WARN, TAG,
                            "Forced Shell release cancellation after MOVE failure"
                                    + ", shellSessionId=" + session.id);
                }
                if (!isShellSessionOwnerCurrent(session)) {
                    if (releaseAllowed) {
                        moduleLog(Log.WARN, TAG,
                                "Forced Shell release cancellation after owner changed on executor"
                                        + ", shellSessionId=" + session.id
                                        + ", sessionController="
                                        + shortObject(session.controller)
                                        + ", currentController="
                                        + shortObject(controller)
                                        + ", sessionInputEpoch="
                                        + session.inputEpoch
                                        + ", currentInputEpoch="
                                        + inputMonitorEpoch.get());
                    }
                    releaseAllowed = false;
                }
                tracker = invokeAnyMethod(releaseController,
                        "getActiveTracker", new Object[0]);
                if (tracker == null || tracker != session.tracker) {
                    failShellReleaseWithoutTracker(session,
                            "activeTrackerIdentityMismatch");
                    return;
                }
                Object currentNavigation = readField(releaseController,
                        "mBackNavigationInfo");
                if (currentNavigation != session.navigation) {
                    failShellReleaseWithoutTracker(session,
                            "navigationIdentityMismatch");
                    return;
                }
                applyProgressThresholds(tracker, session.linearDistance,
                        session.maxDistance, session.nonLinearFactor);
                ((BackTouchTracker) tracker).update(rawX, rawY);
                // BackPanelController posts its terminal ACTIVE/INACTIVE/FLUNG decision through
                // BackAnimationImpl before this release transaction. Preserve that ordered
                // native decision. Only an invalid release (ACTION_CANCEL, owner replacement, or
                // MOVE failure) may veto it; pointer distance does not redefine native commit.
                if (!(tracker instanceof BackTouchTracker)) {
                    throw new IllegalStateException("active tracker is not BackTouchTracker: "
                            + shortObject(tracker));
                }
                boolean nativeTriggerBeforeSafetyVeto =
                        ((BackTouchTracker) tracker).getTriggerBack();
                if (!releaseAllowed && nativeTriggerBeforeSafetyVeto) {
                    invokeAnyMethod(releaseController, "setTriggerBack",
                            new Object[]{Boolean.FALSE});
                }
                tracker = invokeAnyMethod(releaseController,
                        "getActiveTracker", new Object[0]);
                if (tracker == null || tracker != session.tracker) {
                    failShellReleaseWithoutTracker(session,
                            "postTriggerTrackerIdentityMismatch");
                    return;
                }
                if (!(tracker instanceof BackTouchTracker)) {
                    throw new IllegalStateException("post-trigger tracker is not BackTouchTracker: "
                            + shortObject(tracker));
                }
                boolean actualTrigger = ((BackTouchTracker) tracker).getTriggerBack();
                moduleLog(Log.INFO, TAG, "Resolved Shell release trigger"
                        + ", releaseAllowed=" + releaseAllowed
                        + ", nativeTriggerBeforeSafetyVeto="
                        + nativeTriggerBeforeSafetyVeto
                        + ", actualTrigger=" + actualTrigger);
                if (dispatchFinalProgress && !aospNullFallback) {
                    dispatchExplicitProgressOnShell(session, tracker,
                            releaseDistance);
                }
                Object infoObject = readField(releaseController,
                        "mBackNavigationInfo");
                if (infoObject != session.navigation) {
                    failShellReleaseWithoutTracker(session,
                            "releaseNavigationIdentityMismatch");
                    return;
                }
                BackNavigationInfo info = infoObject instanceof BackNavigationInfo
                        ? (BackNavigationInfo) infoObject : null;
                if (info == null) {
                    finishNullNavigationOnShellExecutor(
                            session, tracker, releaseAllowed,
                            actualTrigger, releaseEdge, aospNullFallback,
                            aospNullInputEpoch, inputIdentity);
                    return;
                }
                int focusedTaskId = -1;
                if (actualTrigger) {
                    Object observer = readField(releaseController,
                            "mBackTransitionObserver");
                    int focusedTaskIdObject = info.getFocusedTaskId();
                    writeField(observer, "mFocusedTaskId",
                            focusedTaskIdObject);
                    focusedTaskId = focusedTaskIdObject;
                }
                writeField(releaseController, "mThresholdCrossed", Boolean.FALSE);
                writeField(releaseController, "mPointersPilfered", Boolean.FALSE);
                writeField(releaseController, "mBackGestureStarted", Boolean.FALSE);
                setTrackerState(tracker, "FINISHED");

                if (Boolean.TRUE.equals(readField(releaseController,
                        "mPostCommitAnimationInProgress"))) {
                    session.awaitingStockCleanup.set(true);
                    moduleLog(Log.WARN, TAG, "Shell release found an existing post-commit animation"
                            + ", actualTrigger=" + actualTrigger
                            + ", edge=" + releaseEdge);
                    return;
                }
                if (actualTrigger && info.getType() == TYPE_RETURN_TO_HOME) {
                    if (focusedTaskId < 0 || inputIdentity == null
                            || inputIdentity.generation
                            != systemUiInputArbiterGeneration) {
                        moduleLog(Log.ERROR, TAG,
                                "Could not bind committed return-home to accepted DOWN"
                                        + ", taskId=" + focusedTaskId
                                        + ", input="
                                        + shortObject(inputIdentity)
                                        + ", inputGeneration="
                                        + (inputIdentity == null ? 0L
                                        : inputIdentity.generation)
                                        + ", arbiterGeneration="
                                        + systemUiInputArbiterGeneration);
                    } else {
                        SystemUiReturnHomeCommitIdentity identity =
                                new SystemUiReturnHomeCommitIdentity(
                                        releaseController, session.id,
                                        focusedTaskId, inputIdentity);
                        SystemUiReturnHomeCommitIdentity replaced =
                                systemUiReturnHomeCommitIdentity
                                        .getAndSet(identity);
                        moduleLog(Log.INFO, TAG,
                                "Bound committed return-home to accepted DOWN"
                                        + ", taskId=" + focusedTaskId
                                        + ", eventId="
                                        + inputIdentity.eventId
                                        + ", downTime="
                                        + inputIdentity.downTime
                                        + ", replacedTaskId="
                                        + (replaced == null ? -1
                                        : replaced.taskId));
                    }
                }
                if (!info.isPrepareRemoteAnimation()) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    logShellReleaseResult(info, releaseAllowed, actualTrigger,
                            "direct-callback", releaseEdge,
                            recentsCallback, shadeCallback, drawerCallback, editingCallback);
                    completeShellSessionOnOwner(session,
                            "direct-callback");
                    return;
                }

                session.awaitingStockCleanup.set(true);
                int runnerState = inspectRemoteRunnerState(releaseController, info);
                if (runnerState == REMOTE_RUNNER_MISSING
                        || runnerState == REMOTE_RUNNER_CANCELLED) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    logShellReleaseResult(info, releaseAllowed, actualTrigger,
                            runnerState == REMOTE_RUNNER_MISSING
                                    ? "runner-missing" : "runner-cancelled",
                            releaseEdge, recentsCallback, shadeCallback,
                            drawerCallback, editingCallback);
                    completeShellSessionOnOwner(session,
                            runnerState == REMOTE_RUNNER_MISSING
                                    ? "runner-missing" : "runner-cancelled");
                    return;
                }
                if (runnerState == REMOTE_RUNNER_WAITING
                        || runnerState == REMOTE_RUNNER_UNKNOWN) {
                    scheduleShellAnimationTimeout(releaseController);
                    logShellReleaseResult(info, releaseAllowed, actualTrigger,
                            runnerState == REMOTE_RUNNER_WAITING
                                    ? "runner-waiting" : "runner-unknown",
                            releaseEdge, recentsCallback, shadeCallback,
                            drawerCallback, editingCallback);
                    return;
                }
                if (!actualTrigger && info.getType() == TYPE_RETURN_TO_HOME) {
                    prepareReturnHomeCancelTransitionCleanup(releaseController);
                }
                invokeAnyMethod(releaseController,
                        "startPostCommitAnimation", new Object[0]);
                logShellReleaseResult(info, releaseAllowed, actualTrigger,
                        "post-commit", releaseEdge,
                        recentsCallback, shadeCallback, drawerCallback, editingCallback);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Complete Shell release transaction failed; cancelling",
                        throwable);
                cancelFailedShellRelease(session, tracker);
            }
        }

        protected void finishNullNavigationOnShellExecutor(
                ShellGestureSession session, Object tracker,
                boolean releaseAllowed, boolean actualTrigger,
                int releaseEdge, boolean aospNullFallback,
                long aospNullInputEpoch,
                MiuiHomeAcceptedInputToken inputIdentity) throws Exception {
            Object releaseController = session.controller;
            boolean authenticatedFallback;
            boolean commitLegacyBack;
            synchronized (backInputLifecycleLock) {
                authenticatedFallback = aospNullFallback
                        && isCurrentAcceptedInputIdentity(
                        inputIdentity, releaseEdge, releaseController,
                        aospNullInputEpoch);
                commitLegacyBack = authenticatedFallback
                        && releaseAllowed && actualTrigger;
                if (commitLegacyBack) {
                    Object observer = readField(releaseController,
                            "mBackTransitionObserver");
                    writeField(observer, "mFocusedTaskId", Integer.valueOf(-1));
                }
                writeField(releaseController, "mThresholdCrossed", Boolean.FALSE);
                writeField(releaseController, "mPointersPilfered", Boolean.FALSE);
                writeField(releaseController, "mBackGestureStarted", Boolean.FALSE);
                setTrackerState(tracker, "FINISHED");
                if (Boolean.TRUE.equals(readField(releaseController,
                        "mPostCommitAnimationInProgress"))) {
                    session.awaitingStockCleanup.set(true);
                    moduleLog(Log.WARN, TAG,
                            "Null-navigation release found an existing post-commit animation"
                                    + ", authenticatedFallback="
                                    + authenticatedFallback
                                    + ", actualTrigger=" + actualTrigger
                                    + ", edge=" + releaseEdge);
                    return;
                }
                ((BackTouchTracker) tracker).reset();
                if (commitLegacyBack) {
                    injectLegacyBackKey(releaseController);
                }
                invokeAnyMethod(releaseController, "finishBackNavigation",
                        new Object[]{Boolean.valueOf(commitLegacyBack)});
            }
            completeShellSessionOnOwner(session,
                    commitLegacyBack ? "null-navigation-commit"
                            : "null-navigation-cancel");
            moduleLog(commitLegacyBack ? Log.INFO : Log.WARN, TAG,
                    "Finished released gesture with null navigation"
                            + ", releaseAllowed=" + releaseAllowed
                            + ", actualTrigger=" + actualTrigger
                            + ", aospFallbackRequested=" + aospNullFallback
                            + ", authenticatedFallback=" + authenticatedFallback
                            + ", focusedTaskId="
                            + (commitLegacyBack ? "-1" : "unchanged")
                            + ", legacyBackCommitted=" + commitLegacyBack
                            + ", edge=" + releaseEdge);
        }

        protected void prepareReturnHomeCancelTransitionCleanup(
                Object releaseController) {
            try {
                Object transitionHandler = readField(releaseController,
                        "mBackTransitionHandler");
                Object prepareOpen = readField(transitionHandler,
                        "mPrepareOpenTransition");
                Object prepareClose = readField(transitionHandler,
                        "mClosePrepareTransition");
                Object closeRequested = readField(transitionHandler,
                        "mCloseTransitionRequested");
                if (prepareOpen == null) {
                    return;
                }
                boolean staleCloseRequested = prepareClose == null
                        && Boolean.TRUE.equals(closeRequested);
                if (staleCloseRequested) {
                    // A preceding committed Xiaomi close can finish without entering Shell's
                    // handleCloseTransition() callback, leaving this flag true after both
                    // prepared-transition tokens are gone. If it survives into a later cancel,
                    // stock finishBackAnimation() skips createClosePrepareTransition() and WM
                    // keeps the previous composed navigation indefinitely. Clear only that
                    // stale gate for this exact prepared return-home cancellation; stock
                    // startPostCommitAnimation()/finishBackAnimation() still own restoreBackNavi.
                    writeField(transitionHandler, "mCloseTransitionRequested",
                            Boolean.FALSE);
                }
                moduleLog(Log.INFO, TAG,
                        "Prepared stock return-to-home cancel transition cleanup"
                                + ", staleCloseRequested="
                                + staleCloseRequested
                                + ", prepareOpen="
                                + shortObject(prepareOpen)
                                + ", prepareClose="
                                + shortObject(prepareClose));
            } catch (Throwable throwable) {
                // Keep stock cancellation moving even when a vendor field changes. Its normal
                // timeout remains safer than finishing a prepared transition from this driver.
                moduleLog(Log.WARN, TAG,
                        "Failed to prepare return-to-home cancel transition cleanup",
                        throwable);
            }
        }

        protected void dispatchExplicitProgressOnShell(ShellGestureSession session,
                                                       Object tracker,
                                                       float distance) {
            try {
                Object callback = readField(session.controller,
                        "mActiveCallback");
                float progress = Math.max(0.0f,
                        Math.min(1.0f, distance
                                / Math.max(1.0f, session.maxDistance)));
                BackMotionEvent progressEvent =
                        ((BackTouchTracker) tracker).createProgressEvent(progress);
                invokeAnyMethod(session.controller, "dispatchOnBackProgressed",
                        new Object[]{callback, progressEvent});
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to dispatch final progress on Shell executor",
                        throwable);
            }
        }

        protected int inspectRemoteRunnerState(Object releaseController,
                                               BackNavigationInfo info) {
            try {
                Object registry = readField(releaseController,
                        "mShellBackAnimationRegistry");
                Object definitions = readField(registry, "mAnimationDefinition");
                Object runner = invokeAnyMethod(definitions, "get",
                        new Object[]{Integer.valueOf(info.getType())});
                if (runner == null) {
                    return REMOTE_RUNNER_MISSING;
                }
                Object cancelled = readField(runner, "mAnimationCancelled");
                if (!(cancelled instanceof Boolean)) {
                    throw new IllegalStateException("mAnimationCancelled is "
                            + shortObject(cancelled));
                }
                if (Boolean.TRUE.equals(cancelled)) {
                    return REMOTE_RUNNER_CANCELLED;
                }
                Object waiting = readField(runner, "mWaitingAnimation");
                if (!(waiting instanceof Boolean)) {
                    throw new IllegalStateException("mWaitingAnimation is "
                            + shortObject(waiting));
                }
                return Boolean.TRUE.equals(waiting)
                        ? REMOTE_RUNNER_WAITING : REMOTE_RUNNER_READY;
            } catch (Throwable throwable) {
                // Unknown is deliberately not treated as missing/cancelled. The tracker stays
                // FINISHED and Shell's own timeout is allowed to resolve the navigation.
                moduleLog(Log.WARN, TAG, "Remote runner state is unknown; waiting for Shell timeout",
                        throwable);
                return REMOTE_RUNNER_UNKNOWN;
            }
        }

        protected void scheduleShellAnimationTimeout(Object releaseController) {
            try {
                Object executor = readField(releaseController, "mShellExecutor");
                Object timeout = readField(releaseController,
                        "mAnimationTimeoutRunnable");
                invokeAnyMethod(executor, "executeDelayed",
                        new Object[]{timeout, Long.valueOf(2000L)});
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to schedule required Shell animation timeout",
                        throwable);
            }
        }

        protected void failShellReleaseWithoutTracker(
                ShellGestureSession session, String reason) {
            session.awaitingStockCleanup.set(true);
            scheduleShellAnimationTimeout(session.controller);
            moduleLog(Log.ERROR, TAG,
                    "Kept failed Shell session for stock timeout"
                            + ", shellSessionId=" + session.id
                            + ", reason=" + reason
                            + ", tracker=" + shortObject(session.tracker)
                            + ", navigation="
                            + shortObject(session.navigation));
        }

        protected void cancelFailedShellRelease(ShellGestureSession session,
                                                Object tracker) {
            Object releaseController = session.controller;
            try {
                Object activeTracker = invokeAnyMethod(releaseController,
                        "getActiveTracker", new Object[0]);
                Object infoObject = readField(releaseController,
                        "mBackNavigationInfo");
                if (tracker == null || tracker != session.tracker
                        || activeTracker != session.tracker
                        || infoObject != session.navigation) {
                    failShellReleaseWithoutTracker(session,
                            "failedReleaseIdentityMismatch");
                    return;
                }
                writeField(releaseController, "mThresholdCrossed", Boolean.FALSE);
                writeField(releaseController, "mPointersPilfered", Boolean.FALSE);
                writeField(releaseController, "mBackGestureStarted", Boolean.FALSE);
                invokeAnyMethod(tracker, "setTriggerBack",
                        new Object[]{Boolean.FALSE});
                setTrackerState(tracker, "FINISHED");
                if (Boolean.TRUE.equals(readField(releaseController,
                        "mPostCommitAnimationInProgress"))) {
                    session.awaitingStockCleanup.set(true);
                    scheduleShellAnimationTimeout(releaseController);
                    return;
                }
                BackNavigationInfo info = infoObject instanceof BackNavigationInfo
                        ? (BackNavigationInfo) infoObject : null;
                if (info == null) {
                    ((BackTouchTracker) tracker).reset();
                    invokeAnyMethod(releaseController, "finishBackNavigation",
                            new Object[]{Boolean.FALSE});
                    completeShellSessionOnOwner(session,
                            "failed-null-navigation-cancel");
                    return;
                }
                if (!info.isPrepareRemoteAnimation()) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    completeShellSessionOnOwner(session,
                            "failed-direct-cancel");
                    return;
                }
                session.awaitingStockCleanup.set(true);
                int runnerState = inspectRemoteRunnerState(
                        releaseController, info);
                if (runnerState == REMOTE_RUNNER_MISSING
                        || runnerState == REMOTE_RUNNER_CANCELLED) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    completeShellSessionOnOwner(session,
                            runnerState == REMOTE_RUNNER_MISSING
                                    ? "failed-runner-missing"
                                    : "failed-runner-cancelled");
                    return;
                }
                if (runnerState == REMOTE_RUNNER_WAITING
                        || runnerState == REMOTE_RUNNER_UNKNOWN) {
                    scheduleShellAnimationTimeout(releaseController);
                    return;
                }
                if (info.getType() == TYPE_RETURN_TO_HOME) {
                    prepareReturnHomeCancelTransitionCleanup(
                            releaseController);
                }
                invokeAnyMethod(releaseController,
                        "startPostCommitAnimation", new Object[0]);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to cancel broken Shell release transaction",
                        throwable);
                failShellReleaseWithoutTracker(session,
                        "failedReleaseCancellationException");
            }
        }

        protected void logShellReleaseResult(BackNavigationInfo info,
                                             boolean releaseAllowed,
                                             boolean actualTrigger,
                                             String outcome, int releaseEdge,
                                             boolean recentsCallback,
                                             boolean shadeCallback,
                                             boolean drawerCallback,
                                             boolean editingCallback) {
            moduleLog(Log.INFO, TAG, "Completed Shell release transaction"
                    + ", type=" + info.getType()
                    + ", releaseAllowed=" + releaseAllowed
                    + ", actualTrigger=" + actualTrigger
                    + ", outcome=" + outcome
                    + ", recentsShellCallback=" + recentsCallback
                    + ", shadeShellCallback=" + shadeCallback
                    + ", drawerOrFolderShellCallback=" + drawerCallback
                    + ", editingShellCallback=" + editingCallback
                    + ", edge=" + releaseEdge);
        }

        protected void setTrackerState(Object tracker, String stateName) throws Exception {
            BackTouchTracker.TouchTrackerState state =
                    BackTouchTracker.TouchTrackerState.valueOf(stateName);
            ((BackTouchTracker) tracker).setState(state);
        }

        protected float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }

        protected boolean queueShellMove(
                ShellGestureSession session, float rawX, float rawY,
                float distance, boolean crossedNow,
                boolean dispatchProgress) {
            if (session == null || session.completionConsumed.get()
                    || session.releaseQueued.get()
                    || session.moveFailed.get()) {
                return false;
            }
            try {
                session.executor.execute(() -> {
                    Object tracker = null;
                    try {
                        if (session.completionConsumed.get()
                                || session.moveFailed.get()) {
                            return;
                        }
                        tracker = invokeAnyMethod(session.controller,
                                "getActiveTracker", new Object[0]);
                        Object navigation = readField(session.controller,
                                "mBackNavigationInfo");
                        boolean receivedNull = Boolean.TRUE.equals(readField(
                                session.controller,
                                "mReceivedNullNavigationInfo"));
                        if (tracker != session.tracker
                                || !ShellNavigationIdentity.matches(
                                        session.navigation, session.receivedNullNavigation,
                                        navigation, receivedNull)) {
                            throw new IllegalStateException(
                                    "Shell MOVE identity changed"
                                            + ", tracker="
                                            + shortObject(tracker)
                                            + ", navigation="
                                            + shortObject(navigation)
                                            + ", receivedNull="
                                            + receivedNull);
                        }
                        applyProgressThresholds(tracker,
                                session.linearDistance,
                                session.maxDistance,
                                session.nonLinearFactor);
                        ((BackTouchTracker) tracker).update(rawX, rawY);
                        if (crossedNow) {
                            invokeAnyMethod(session.controller,
                                    "onThresholdCrossed", new Object[0]);
                        }
                        if (dispatchProgress) {
                            dispatchExplicitProgressOnShell(
                                    session, tracker, distance);
                        }
                    } catch (Throwable throwable) {
                        session.moveFailed.set(true);
                        moduleLog(Log.ERROR, TAG,
                                "Shell-owner MOVE failed; cancelling"
                                        + ", shellSessionId=" + session.id,
                                throwable);
                        if (session.releaseQueued.compareAndSet(
                                false, true)) {
                            cancelFailedShellRelease(session, tracker);
                        }
                    }
                });
                return true;
            } catch (Throwable throwable) {
                session.moveFailed.set(true);
                moduleLog(Log.ERROR, TAG,
                        "Failed to queue Shell-owner MOVE"
                                + ", shellSessionId=" + session.id,
                        throwable);
                cleanupRejectedShellGesture(session);
                return false;
            }
        }

        protected void applyProgressThresholds(
                Object tracker, float linearDistance,
                float maxDistance, float nonLinearFactor) {
            ((BackTouchTracker) tracker).setProgressThresholds(
                    linearDistance, maxDistance, nonLinearFactor);
        }

        protected void dispatchLegacyInterruptBack(Object interruptionController) {
            if (legacyRunningOpenInfo == null) {
                moduleLog(Log.WARN, TAG, "Missing correlated OPEN info for legacy interruption; "
                        + "using ordinary BACK without duplicate guard");
                injectLegacyBackKey(interruptionController);
                return;
            }
            LegacyBackAttempt attempt = armLegacyBackGuard(interruptionController,
                    legacyRunningOpenInfo);
            Object previousMarker = moduleLegacyBackInjection.get();
            moduleLegacyBackInjection.set(attempt);
            try {
                injectLegacyBackKey(interruptionController);
            } finally {
                if (previousMarker == null) {
                    moduleLegacyBackInjection.remove();
                } else {
                    moduleLegacyBackInjection.set(previousMarker);
                }
            }
        }

        protected void injectLegacyBackKey(Object injectionController) {
            if (injectionController == null) {
                moduleLog(Log.ERROR, TAG, "Cannot inject legacy BACK without a controller");
                return;
            }
            Object previousMarker = moduleLegacyBackInjection.get();
            if (previousMarker == null) {
                moduleLegacyBackInjection.set(this);
            }
            try {
                try {
                    injectPlatformLegacyBackKey(injectionController, displayId);
                    moduleLog(Log.INFO, TAG,
                            "Injected one legacy BACK pair through stock Shell helper"
                                    + ", displayId=" + displayId);
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG,
                            "Failed to inject legacy BACK pair through stock Shell helper",
                            throwable);
                }
            } finally {
                if (previousMarker == null) {
                    moduleLegacyBackInjection.remove();
                } else {
                    moduleLegacyBackInjection.set(previousMarker);
                }
            }
        }

        protected boolean dispatchToEdgePlugin(MotionEvent event, int edge) {
            MotionEvent screenEvent = null;
            try {
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                if (plugin == null) {
                    moduleLog(Log.WARN, TAG, "NavigationEdgeBackPlugin is null; native panel unavailable");
                    if (miuiStyleGestureActive) {
                        miuiStyleGestureActive = false;
                        MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
                        if (overlay != null && miuiStyleSwipeStarted) {
                            overlay.onGestureEnd(-1.0f);
                        }
                        miuiStyleSwipeStarted = false;
                        miuiStyleHapticsActive = false;
                    }
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    prepareNativeBackPanel(plugin);
                    boolean requestedHyperOs = isHyperOsIndicatorEnabled();
                    miuiStyleGestureActive = prepareMiuiStyleIndicator(edge, requestedHyperOs)
                            && applyNativePanelSkinVisibility(plugin, true);
                    if (lastIndicatorRequested == null || lastIndicatorRequested != requestedHyperOs
                            || lastIndicatorApplied == null || lastIndicatorApplied != miuiStyleGestureActive) {
                        lastIndicatorRequested = requestedHyperOs;
                        lastIndicatorApplied = miuiStyleGestureActive;
                        moduleLog(Log.INFO, TAG, "Back indicator style applied"
                                + ", requested=" + (requestedHyperOs ? "HyperOS" : "AOSP")
                                + ", selected=" + (miuiStyleGestureActive ? "HyperOS" : "AOSP")
                                + ", mainLooper=" + (Looper.myLooper() == Looper.getMainLooper()));
                    }
                    if (!miuiStyleGestureActive) {
                        applyNativePanelSkinVisibility(plugin, false);
                    }
                    miuiStyleHapticsActive = miuiStyleGestureActive
                            && prepareMiuiStyleHaptics();
                    // May clear miuiStyleHapticsActive on failure, so the overlay must
                    // learn the final value afterwards or both sources would vibrate.
                    applyNativePanelHapticSuppression(plugin,
                            isGestureHapticsDisabled() || miuiStyleHapticsActive);
                    if (miuiStyleOverlay != null) {
                        miuiStyleOverlay.setHapticsForGesture(miuiStyleHapticsActive);
                    }
                }
                invokeMethod(plugin, "setIsLeftPanel",
                        new Class<?>[]{boolean.class},
                        new Object[]{Boolean.valueOf(edge == EDGE_LEFT)});
                screenEvent = MotionEvent.obtain(event);
                screenEvent.setLocation(event.getRawX(), event.getRawY());
                invokeMethod(plugin, "onMotionEvent",
                        new Class<?>[]{MotionEvent.class}, new Object[]{screenEvent});
                if (miuiStyleGestureActive) {
                    driveMiuiStyleIndicator(event, edge);
                }
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to dispatch event to NavigationEdgeBackPlugin",
                        throwable);
                return false;
            } finally {
                if (screenEvent != null) {
                    screenEvent.recycle();
                }
            }
        }

        protected String readNativePanelState() {
            try {
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                Object state = plugin == null ? null
                        : readField(plugin, "currentState");
                return state instanceof Enum<?>
                        ? ((Enum<?>) state).name() : null;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to read native BackPanelController state",
                        throwable);
                return null;
            }
        }

        protected void maybePlayAospIndicatorHandUpHaptic(
                String panelStateAfterRelease, boolean releaseAllowed) {
            if (!releaseAllowed || !("FLUNG".equals(panelStateAfterRelease)
                    || "COMMITTED".equals(panelStateAfterRelease))) {
                return;
            }
            try {
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                playAospIndicatorHandUpHaptic(plugin);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to prepare AOSP-indicator hand-up haptic", throwable);
            }
        }

        protected Boolean resolveNativePanelReleaseTrigger(
                String stateAfterRelease, boolean releaseDelivered) {
            if (!releaseDelivered || stateAfterRelease == null) {
                return null;
            }
            switch (stateAfterRelease) {
                case "CANCELLED":
                case "GONE":
                    return Boolean.FALSE;
                case "FLUNG":
                case "COMMITTED":
                    return Boolean.TRUE;
                case "ENTRY":
                case "ACTIVE":
                case "INACTIVE":
                    // These can still be waiting on Xiaomi's delayed release branch.
                    // Only FLUNG/COMMITTED prove the native panel committed this attempt.
                    return null;
                default:
                    return null;
            }
        }

        protected void prepareNativeBackPanel(Object plugin) {
            try {
                SystemUiInputDriverRuntime.this.prepareNativeBackPanel(
                        edgeBackGestureHandler, plugin);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to prepare native AOSP back panel", throwable);
            }
        }

        protected boolean prepareMiuiStyleIndicator(int edge, boolean requestedHyperOs) {
            if (!requestedHyperOs) {
                return false;
            }
            try {
                if (miuiStyleOverlay == null) {
                    miuiStyleOverlay = new MiuiStyleBackArrowOverlay(context,
                            (priority, message, throwable) -> {
                                if (throwable == null) {
                                    moduleLog(priority, TAG, message);
                                } else {
                                    moduleLog(priority, TAG, message, throwable);
                                }
                            });
                }
                return miuiStyleOverlay.prepare(edge, isHyperOsIndicatorBlurEnabled());
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to prepare HyperOS-style indicator",
                        throwable);
                return false;
            }
        }

        /**
         * The hidden native BackPanelController keeps processing every event for state,
         * commit thresholds, and haptics; only its View pixels are suppressed while the
         * HyperOS-style overlay draws. Restoring is best-effort and re-evaluated on the
         * next ACTION_DOWN, so a failure here can never strand an invisible panel.
         */
        protected boolean applyNativePanelSkinVisibility(Object plugin, boolean hide) {
            try {
                Object panelView = readField(plugin, "mView");
                if (!(panelView instanceof View)) {
                    return false;
                }
                float alpha = hide ? 0.0f : 1.0f;
                View target = (View) panelView;
                if (target.getAlpha() != alpha) {
                    target.setAlpha(alpha);
                    moduleLog(Log.INFO, TAG, "Native BackPanel visuals "
                            + (hide ? "hidden behind HyperOS-style indicator"
                            : "restored"));
                }
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to change native BackPanel visibility"
                        + ", hide=" + hide, throwable);
                return false;
            }
        }

        protected void driveMiuiStyleIndicator(MotionEvent event, int edge) {
            MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
            if (overlay == null) {
                miuiStyleGestureActive = false;
                miuiStyleSwipeStarted = false;
                return;
            }
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // Nothing is drawn yet: Xiaomi's processor waits for the 20px
                        // horizontal intent threshold below before onSwipeStart().
                        miuiStyleSwipeStarted = false;
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float offset = miuiStyleGestureOffset(event, edge);
                        if (!miuiStyleSwipeStarted) {
                            // Mirrors GesturesBackTouchProcessor: horizontal delta must
                            // reach 20px and dominate half the vertical delta first.
                            float verticalDelta =
                                    Math.abs(event.getRawY() - downY);
                            if (offset < MIUI_STYLE_SWIPE_START_PX
                                    || offset < verticalDelta / 2.0f) {
                                break;
                            }
                            miuiStyleSwipeStarted = true;
                            overlay.onGestureStart(downY, edge);
                        }
                        overlay.onGestureProgress(Math.max(0.0f, offset),
                                offset > MIUI_STYLE_ARROW_SHOW_PX
                                        && isNativePanelStateLit());
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        float releaseOffset = Math.max(0.0f,
                                miuiStyleGestureOffset(event, edge));
                        // Snapshot before onGestureEnd() cancels the arrow animator,
                        // mirroring Xiaomi reading isArrowFeedBackDone() pre-stop.
                        boolean handUpEligible = miuiStyleHapticsActive
                                && miuiStyleSwipeStarted;
                        boolean arrowFeedbackDone = overlay.isArrowFeedbackDone();
                        if (miuiStyleSwipeStarted) {
                            overlay.onGestureEnd(releaseOffset);
                        }
                        if (handUpEligible) {
                            maybePerformMiuiHandUpHaptic(event, releaseOffset,
                                    arrowFeedbackDone);
                        }
                        miuiStyleGestureActive = false;
                        miuiStyleSwipeStarted = false;
                        miuiStyleHapticsActive = false;
                        break;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        if (miuiStyleSwipeStarted) {
                            overlay.onGestureEnd(-1.0f);
                        }
                        overlay.markArrowFeedbackDone();
                        miuiStyleGestureActive = false;
                        miuiStyleSwipeStarted = false;
                        miuiStyleHapticsActive = false;
                        break;
                    default:
                        break;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to drive HyperOS-style indicator",
                        throwable);
                miuiStyleGestureActive = false;
                miuiStyleSwipeStarted = false;
            }
        }

        protected float miuiStyleGestureOffset(MotionEvent event, int edge) {
            return edge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
        }

        // The arrow lights exactly when the hidden native panel would commit on release,
        // so the HyperOS visuals stay truthful to the module's AOSP trigger semantics.
        protected boolean isNativePanelStateLit() {
            String state = readNativePanelState();
            return "ACTIVE".equals(state) || "FLUNG".equals(state)
                    || "COMMITTED".equals(state);
        }

        protected boolean prepareMiuiStyleHaptics() {
            if (!isHyperOsHapticsEnabled()) {
                return false;
            }
            try {
                if (miuiHapticHelper == null) {
                    miuiHapticHelper = new MiuiHapticFeedbackHelper(context,
                            (priority, message, throwable) -> {
                                if (throwable == null) {
                                    moduleLog(priority, TAG, message);
                                } else {
                                    moduleLog(priority, TAG, message, throwable);
                                }
                            });
                }
                if (!miuiHapticHelper.isSupported()) {
                    return false;
                }
                miuiHapticHelper.setEnhancedMode(isHyperOsHapticsEnhancedEnabled());
                MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
                if (overlay != null) {
                    overlay.setHapticListener(() -> {
                        MiuiHapticFeedbackHelper helper = miuiHapticHelper;
                        if (helper != null) {
                            helper.performReadyBack();
                        }
                    });
                }
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to prepare MIUI two-stage haptics",
                        throwable);
                return false;
            }
        }

        /**
         * Mirrors GestureStubView.onSwipeStop: hand-up plays only for a committed
         * release under the native doFeedBack rule — a slow release on non-V2 devices
         * or a ready-back haptic that never completed.
         */
        protected void maybePerformMiuiHandUpHaptic(MotionEvent event,
                                                    float releaseOffset,
                                                    boolean arrowFeedbackDone) {
            MiuiHapticFeedbackHelper helper = miuiHapticHelper;
            MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
            if (helper == null || overlay == null) {
                return;
            }
            try {
                String panelState = readNativePanelState();
                boolean panelCommittedForHaptics = "ENTRY".equals(panelState)
                        || "ACTIVE".equals(panelState)
                        || "INACTIVE".equals(panelState)
                        || "FLUNG".equals(panelState)
                        || "COMMITTED".equals(panelState);
                if (panelCommittedForHaptics) {
                    long gestureDuration =
                            event.getEventTime() - event.getDownTime();
                    float pxPerMs = gestureDuration > 0
                            ? releaseOffset / gestureDuration : 0.0f;
                    // Enhanced mode plays hand-up on every committed release; the
                    // helper's 140ms blocker still prevents doubles near ready-back.
                    boolean doFeedback = helper.isEnhancedMode()
                            || (!helper.isHapticV2() && pxPerMs < 2.0f)
                            || !arrowFeedbackDone;
                    if (doFeedback) {
                        helper.performHandUp();
                    }
                }
                overlay.markArrowFeedbackDone();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to play MIUI hand-up haptic", throwable);
            }
        }

        /**
         * BackPanel plays its threshold haptics through View.performHapticFeedback on
         * its own view, so isHapticFeedbackEnabled() suppresses exactly that feedback.
         * On failure the two-stage haptics are disabled and the native feedback stays.
         */
        protected void applyNativePanelHapticSuppression(Object plugin,
                                                         boolean suppress) {
            try {
                Object panelView = readField(plugin, "mView");
                if (!(panelView instanceof View)) {
                    if (suppress) {
                        miuiStyleHapticsActive = false;
                    }
                    return;
                }
                View target = (View) panelView;
                boolean enabled = !suppress;
                if (target.isHapticFeedbackEnabled() != enabled) {
                    target.setHapticFeedbackEnabled(enabled);
                    moduleLog(Log.INFO, TAG, "Native BackPanel haptics "
                            + (suppress ? "suppressed by gesture haptic preference"
                            : "restored"));
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to change native BackPanel haptics"
                        + ", suppress=" + suppress, throwable);
                if (suppress) {
                    miuiStyleHapticsActive = false;
                }
            }
        }

        protected void teardownMiuiStyleIndicator() {
            miuiStyleGestureActive = false;
            miuiStyleSwipeStarted = false;
            miuiStyleHapticsActive = false;
            MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
            miuiStyleOverlay = null;
            if (overlay != null) {
                overlay.detach();
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                    if (plugin != null) {
                        applyNativePanelSkinVisibility(plugin, false);
                        applyNativePanelHapticSuppression(plugin, false);
                    }
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG, "Failed to restore native BackPanel visuals",
                            throwable);
                }
            });
        }
    }
}
