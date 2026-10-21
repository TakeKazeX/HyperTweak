package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import com.takekazex.hypertweak.hook.Preferences;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.takekazex.hypertweak.hook.rules.backgesture.PredictiveBackPreferences;

/** Owns per-display input monitors and admission on the input thread. */
@SuppressLint({"PrivateApi", "DiscouragedApi"})
public abstract class SystemUiInputRuntime extends SystemUiInputDriverRuntime {
    protected final Map<Object, NativeBackInputMonitor> nativeInputMonitors =
            Collections.synchronizedMap(new WeakHashMap<>());
    @Override
    protected boolean isRecentsRetryInputAllowed(Object handler) {
        NativeBackInputMonitor monitor = nativeInputMonitors.get(handler);
        if (monitor == null || !monitor.isBackGestureAllowedBySystemUiState()) return false;
        Long flags = monitor.readSystemUiStateFlags();
        return flags != null && (flags.longValue() & SYSUI_STATE_LOCKED_OR_PINNED_MASK) == 0L;
    }
    protected NativeBackInputMonitor createNativeBackInputMonitor(Context context,
                                                                  Object edgeBackGestureHandler, Object controller, Object backAnimationImpl)
            throws Exception {
        InputManager inputManager = context.getSystemService(InputManager.class);
        int displayId = readIntFieldOrDefault(
                edgeBackGestureHandler, "mDisplayId", 0);
        Object monitor = invokeAnyMethod(inputManager, "monitorGestureInput",
                new Object[]{"miui-aosp-back", Integer.valueOf(displayId)});
        if (!(monitor instanceof InputMonitor)) {
            throw new IllegalStateException("monitorGestureInput returned "
                    + shortObject(monitor));
        }
        Object inputChannel = ((InputMonitor) monitor).getInputChannel();
        if (!(inputChannel instanceof InputChannel)) {
            throw new IllegalStateException("InputMonitor channel is "
                    + shortObject(inputChannel));
        }
        return new NativeBackInputMonitor(context, edgeBackGestureHandler, controller,
                backAnimationImpl, (InputMonitor) monitor, (InputChannel) inputChannel,
                displayId);
    }

    protected final class NativeBackInputMonitor extends InputEventReceiver {
        protected final Context context;
        protected final Object edgeBackGestureHandler;
        protected final InputMonitor inputMonitor;
        public final SystemUiBackGestureDriver driver;
        protected final int displayId;
        protected boolean gestureCandidate;
        protected boolean launcherOpenBreakCandidate;
        protected long launcherOpenBreakGenerationCandidate;
        protected boolean launcherShadeCandidate;
        protected boolean launcherXiaoAiCandidate;
        protected boolean launcherDrawerCandidate;
        protected boolean launcherEditingCandidate;
        protected boolean miuiHomeInputAccepted;
        protected boolean pilfered;
        protected boolean waitingForTransientBarsAtDown;
        protected boolean arbiterAttached;
        protected final AtomicBoolean detachStarted = new AtomicBoolean();
        protected int activeEdge;
        protected int downEventId;
        protected int downDeviceId = Integer.MIN_VALUE;
        protected int downSource;
        protected int downDisplayId = Integer.MIN_VALUE;
        protected float downX;
        protected float downY;
        protected long downTime = Long.MIN_VALUE;
        protected long downLauncherStateOwnerEpoch;
        protected MotionEvent pendingDownEvent;
        protected MotionEvent pendingMotionEvent;

        protected NativeBackInputMonitor(Context context, Object edgeBackGestureHandler,
                                         Object controller, Object backAnimationImpl, InputMonitor inputMonitor,
                                         InputChannel inputChannel, int displayId)
                throws Exception {
            super(inputChannel, Looper.getMainLooper());
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.inputMonitor = inputMonitor;
            this.displayId = displayId;
            this.driver = new SystemUiBackGestureDriver(context, edgeBackGestureHandler,
                    controller, backAnimationImpl, displayId);
        }

        void attach() {
            if (!arbiterAttached) {
                arbiterAttached = true;
                driver.onInputMonitorAttached();
                onSystemUiInputMonitorAttached(context);
            }
            moduleLog(Log.INFO, TAG, "Native SystemUI back input receiver attached"
                    + ", inputModel=miuihome-accepted-token");
        }

        public void detach() {
            if (!detachStarted.compareAndSet(false, true)) {
                return;
            }
            if (arbiterAttached) {
                arbiterAttached = false;
                onSystemUiInputMonitorDetached(context);
            }
            Runnable disposeOnOwner = () -> {
                resetCandidate();
                driver.detach();
                try {
                    dispose();
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Failed to dispose native back input receiver", throwable);
                }
                try {
                    inputMonitor.dispose();
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Failed to dispose native back input monitor", throwable);
                }
                moduleLog(Log.INFO, TAG,
                        "Native SystemUI back input receiver detached on owner Looper");
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                disposeOnOwner.run();
            } else {
                // InputEventReceiver enforces owner-Looper disposal on Android 17.
                // Hot reload runs from an LSPosed Binder thread, and its later
                // restoration is also queued to main, so FIFO ordering disposes
                // this old receiver before a replacement monitor is attached.
                new Handler(Looper.getMainLooper()).post(disposeOnOwner);
            }
        }

        public void refreshIndicatorIfIdle() {
            if (!gestureCandidate && !driver.blocksHotReload()) driver.teardownMiuiStyleIndicator();
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            driver.updateBackAnimation(newBackAnimationImpl);
        }

        public boolean blocksHotReload() {
            return driver.blocksHotReload();
        }

        public String describeActiveShellSession() {
            return driver.describeActiveShellSession();
        }

        Runnable captureShellAnimationCompletion(
                Object finishedController, Object currentTracker,
                Object queuedTracker, Object navigation,
                Object finishCallback, String reason) {
            return driver.captureShellAnimationCompletion(
                    finishedController, currentTracker, queuedTracker,
                    navigation, finishCallback, reason);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent) {
                    handled = handleMotionEvent((MotionEvent) event);
                }
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Native SystemUI back input failed", throwable);
                resetCandidate();
            } finally {
                finishInputEvent(event, handled);
            }
        }

        protected boolean handleMotionEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && gestureCandidate
                    && Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                    && downLauncherStateOwnerEpoch > 0L
                    && downLauncherStateOwnerEpoch
                    != miuiLauncherDartStateOwnerEpoch) {
                boolean handled = pilfered;
                cancelNativeCandidate(event,
                        "launcher Dart state owner changed");
                return handled;
            }
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    return onNativeDown(event);
                case MotionEvent.ACTION_MOVE:
                    return onNativeMove(event);
                case MotionEvent.ACTION_UP:
                    return onNativeUp(event, true);
                case MotionEvent.ACTION_CANCEL:
                    return onNativeUp(event, false);
                default:
                    return gestureCandidate;
            }
        }

        protected boolean onNativeDown(MotionEvent event) {
            resetCandidate();
            if (!com.takekazex.hypertweak.hook.Preferences.INSTANCE.backGestureEnabled()) return false;
            int edge = edgeForDown(event);
            if (edge < 0 || !canStartBackGesture(event, edge)) {
                return false;
            }
            gestureCandidate = true;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            downTime = event.getDownTime();
            downDeviceId = event.getDeviceId();
            downSource = event.getSource();
            downLauncherStateOwnerEpoch = miuiLauncherDartStateOwnerEpoch;
            try {
                downEventId = readMotionEventId(event);
                downDisplayId = readMotionEventDisplayId(event);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Cannot identify pending MiuiHome DOWN", throwable);
                resetCandidate();
                return false;
            }
            pendingDownEvent = MotionEvent.obtain(event);
            moduleLog(Log.INFO, TAG, "Native SystemUI back candidate awaiting MiuiHome acceptance"
                    + ", eventId=" + downEventId
                    + ", downTime=" + downTime
                    + ", launcherOpenBreak=" + launcherOpenBreakCandidate
                    + ", launcherOpenBreakGeneration="
                    + launcherOpenBreakGenerationCandidate
                    + ", launcherShade=" + launcherShadeCandidate
                    + ", launcherXiaoAi=" + launcherXiaoAiCandidate
                    + ", launcherDrawerOrFolder=" + launcherDrawerCandidate
                    + ", launcherEditing=" + launcherEditingCandidate
                    + ", inputModel=miuihome-accepted-token"
                    + ", displayId=" + displayId
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            MiuiHomeAcceptedInputToken token = acceptedInputToken.get();
            if (token != null && matchesMiuiHomeInput(token)
                    && acceptedInputToken.compareAndSet(token, null)) {
                acceptMiuiHomeInput(token);
            }
            return false;
        }

        protected boolean onNativeMove(MotionEvent event) {
            if (!gestureCandidate) {
                return false;
            }
            if (!miuiHomeInputAccepted) {
                replacePendingMotionEvent(event);
                MiuiHomeAcceptedInputToken token = acceptedInputToken.get();
                if (token != null && matchesMiuiHomeInput(token)
                        && acceptedInputToken.compareAndSet(token, null)) {
                    acceptMiuiHomeInput(token);
                }
                return pilfered;
            }
            if (nativeTransientBarsClaimedGesture()) {
                return yieldToNativeTransientBars(event, "move");
            }
            float distance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            if (!pilfered && driver.isRecentsVisualOnlyGesture()) {
                // MiuiHome can report Overview before WMS exposes Launcher as the back target.
                // Keep the native panel responsive, but leave the real input stream untouched
                // and never retry this gesture against a later navigation target.
                if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate)) {
                    resetCandidate();
                    return false;
                }
                return false;
            }
            if (!pilfered && driver.isGestureSuppressed()) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
                return false;
            }
            if (!pilfered && hasXiaomiBackIntent(
                    distance, event.getRawY() - downY, dp(PILFER_THRESHOLD_DP))) {
                if (!driver.canPilferAtIntentThreshold()) {
                    cancelNativeCandidate(event,
                            "Shell unavailable before pilfer");
                    return false;
                }
                pilferPointers(distance);
                if (!pilfered) {
                    cancelNativeCandidate(event, "failed to pilfer outward gesture");
                    return false;
                }
            }
            if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                    launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                    launcherXiaoAiCandidate, launcherDrawerCandidate,
                    launcherEditingCandidate)) {
                resetCandidate();
                return false;
            }
            return pilfered;
        }

        boolean acceptMiuiHomeInput(MiuiHomeAcceptedInputToken token) {
            if (!matchesMiuiHomeInput(token)) {
                return false;
            }
            miuiHomeInputAccepted = true;
            MotionEvent down = pendingDownEvent;
            pendingDownEvent = null;
            try {
                if (down == null || !driver.handleTouch(down, activeEdge,
                        launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate,
                        launcherShadeCandidate,
                        launcherXiaoAiCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate)) {
                    moduleLog(Log.INFO, TAG, "MiuiHome accepted DOWN but SystemUI path declined"
                            + ", eventId=" + token.eventId
                            + ", edge=" + token.edge);
                    resetCandidate();
                    return true;
                }
                if (!driver.bindAcceptedInput(token)) {
                    cancelNativeCandidate(down,
                            "driver owner changed before accepted-DOWN bind");
                    return true;
                }
                moduleLog(Log.INFO, TAG, "Matched MiuiHome accepted input token"
                        + ", eventId=" + token.eventId
                        + ", downTime=" + token.downTime
                        + ", displayId=" + token.displayId
                        + ", edge=" + token.edge
                        + ", generation=" + token.generation);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to start accepted MiuiHome input", throwable);
                resetCandidate();
                return true;
            } finally {
                if (down != null) {
                    down.recycle();
                }
            }
            MotionEvent pending = pendingMotionEvent;
            pendingMotionEvent = null;
            if (pending != null) {
                try {
                    onNativeMove(pending);
                } finally {
                    pending.recycle();
                }
            }
            return true;
        }

        protected boolean matchesMiuiHomeInput(MiuiHomeAcceptedInputToken token) {
            return token != null
                    && !token.isExpired()
                    && gestureCandidate
                    && !miuiHomeInputAccepted
                    && token.generation == systemUiInputArbiterGeneration
                    && (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL
                    || (token.launcherStateOwnerEpoch > 0L
                    && token.launcherStateOwnerEpoch
                    == downLauncherStateOwnerEpoch))
                    && token.eventId == downEventId
                    && token.downTime == downTime
                    && token.deviceId == downDeviceId
                    && token.source == downSource
                    && token.displayId == downDisplayId
                    && token.displayId == displayId
                    && token.edge == activeEdge;
        }

        protected void replacePendingMotionEvent(MotionEvent event) {
            MotionEvent old = pendingMotionEvent;
            pendingMotionEvent = MotionEvent.obtain(event);
            if (old != null) {
                old.recycle();
            }
        }

        protected void pilferPointers(float distance) {
            try {
                inputMonitor.pilferPointers();
                pilfered = true;
                moduleLog(Log.INFO, TAG, "Native SystemUI back pilfered pointers"
                        + ", distance=" + distance + ", edge=" + activeEdge);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to pilfer native back pointers", throwable);
            }
        }

        protected void cancelNativeCandidate(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to cancel native back candidate", throwable);
            }
            moduleLog(Log.INFO, TAG, "Cancelled native SystemUI back candidate before pilfer"
                    + ", reason=" + reason
                    + ", edge=" + activeEdge
                    + ", x=" + event.getRawX()
                    + ", y=" + event.getRawY());
            resetCandidate();
        }

        protected boolean onNativeUp(MotionEvent event, boolean allowTrigger) {
            if (!gestureCandidate) {
                return false;
            }
            if (!miuiHomeInputAccepted) {
                resetCandidate();
                return false;
            }
            if (nativeTransientBarsClaimedGesture()) {
                return yieldToNativeTransientBars(event, "release");
            }
            if (driver.isRecentsVisualOnlyGesture()) {
                // Let BackPanelController finish its local animation. No Shell navigation is
                // active, and the monitor must not claim this input stream.
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
            } else if (allowTrigger && pilfered) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
            } else {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            }
            boolean handled = pilfered;
            resetCandidate();
            return handled;
        }

        protected boolean nativeTransientBarsClaimedGesture() {
            return waitingForTransientBarsAtDown && isNavBarShownTransiently();
        }

        protected boolean yieldToNativeTransientBars(MotionEvent event, String phase) {
            boolean handled = pilfered;
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to cancel Shell back after native transient-bars claim",
                        throwable);
            }
            moduleLog(Log.INFO, TAG, "Yielded immersive side gesture to native transient bars"
                    + ", phase=" + phase
                    + ", inputPilfered=" + handled
                    + ", downTime=" + downTime
                    + ", edge=" + activeEdge
                    + ", x=" + event.getRawX()
                    + ", y=" + event.getRawY());
            resetCandidate();
            return handled;
        }

        protected int edgeForDown(MotionEvent event) {
            float x = event.getRawX();
            float displayWidth = currentDisplayWidth();
            EdgeWidthSnapshot widths = readEdgeWidthSnapshot(
                    edgeBackGestureHandler,
                    context.getResources().getDisplayMetrics().density);
            if (x < widths.leftTouchWidth) {
                return EDGE_LEFT;
            }
            if (x >= displayWidth - widths.rightTouchWidth) {
                return EDGE_RIGHT;
            }
            return -1;
        }

        protected float currentDisplayWidth() {
            try {
                return Math.max(1.0f, context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds().width());
            } catch (Throwable ignored) {
                return Math.max(1.0f,
                        context.getResources().getDisplayMetrics().widthPixels);
            }
        }

        protected float currentDisplayHeight() {
            try {
                return Math.max(1.0f, context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds().height());
            } catch (Throwable ignored) {
                return Math.max(1.0f,
                        context.getResources().getDisplayMetrics().heightPixels);
            }
        }

        /**
         * MiuiHome owns the physical side window. Keep the SystemUI spy on the same vertical
         * bounds so shrinking that native window cannot leave a second, larger SystemUI-owned
         * trigger area behind it.
         */
        protected boolean isWithinConfiguredGestureTriggerArea(MotionEvent event) {
            try {
                SharedPreferences preferences = gestureTriggerPreferences;
                if (preferences == null) {
                    synchronized (SystemUiInputRuntime.this) {
                        preferences = gestureTriggerPreferences;
                        if (preferences == null) {
                            preferences = getRemotePreferences(
                                    PredictiveBackPreferences.GROUP);
                            gestureTriggerPreferences = preferences;
                        }
                    }
                }
                int heightPercent = preferences.getInt(
                        PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT,
                        PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT);
                int positionPercent = preferences.getInt(
                        PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT,
                        PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT);
                if (heightPercent < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_HEIGHT_PERCENT
                        || heightPercent
                        > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_HEIGHT_PERCENT
                        || positionPercent
                        < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_POSITION_PERCENT
                        || positionPercent
                        > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_POSITION_PERCENT) {
                    return true;
                }
                if (heightPercent
                        == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT
                        && positionPercent
                        == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT) {
                    gestureTriggerPreferencesFailureLogged = false;
                    return true;
                }
                float displayHeight = currentDisplayHeight();
                float configuredHeight = displayHeight * heightPercent / 100.0f;
                float availableTravel = Math.max(0.0f, displayHeight - configuredHeight);
                float top = availableTravel * positionPercent / 100.0f;
                float y = event.getRawY();
                boolean inside = y >= top && y < top + configuredHeight;
                gestureTriggerPreferencesFailureLogged = false;
                return inside;
            } catch (Throwable throwable) {
                if (!gestureTriggerPreferencesFailureLogged) {
                    gestureTriggerPreferencesFailureLogged = true;
                    moduleLog(Log.WARN, TAG,
                            "Gesture trigger configuration unavailable; preserving native area",
                            throwable);
                }
                return true;
            }
        }

        protected boolean canStartBackGesture(MotionEvent event, int edge) {
            if (event.getPointerCount() != 1) {
                return false;
            }
            if (!isWithinConfiguredGestureTriggerArea(event)) {
                moduleLog(Log.INFO, TAG, "Ignored native back outside configured trigger area"
                        + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY()
                        + ", edge=" + edge);
                return false;
            }
            if (!isNativeBackInputActive()) {
                return false;
            }
            ComponentName topActivity = findTopActivity();
            if (topActivity == null) {
                moduleLog(Log.WARN, TAG, "Rejected native back because top activity is unknown"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
                return false;
            }
            boolean launcherPackage = MIUI_HOME.equals(topActivity.getPackageName());
            boolean launcherHome = launcherPackage
                    && isLauncherHomeComponent(topActivity);
            boolean launcherShade = launcherHome && displayId == 0
                    && isMiuiShadeExpanded();
            boolean launcherXiaoAi = launcherHome && displayId == 0
                    && !launcherShade
                    && miuiLauncherXiaoAiVisible;
            boolean launcherHomeSurface = launcherHome && displayId == 0
                    && Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                    && !launcherShade && !launcherXiaoAi && !miuiOverviewVisible
                    && miuiLauncherHomeSurfaceVisible;
            boolean launcherOpenBreak = displayId == 0
                    && !launcherShade
                    && !launcherXiaoAi
                    && !launcherHomeSurface
                    && !miuiOverviewVisible
                    && miuiLauncherOpenActive
                    && miuiLauncherOpenBreakGeneration != 0L
                    && launcherOpenBreakCommandsInFlight.get() == 0;
            boolean launcherDrawer = launcherHome
                    && !launcherShade
                    && !launcherXiaoAi
                    && miuiDrawerVisible
                    && !miuiOverviewVisible
                    && !launcherOpenBreak;
            boolean launcherFolder = launcherHome
                    && !launcherShade
                    && !launcherXiaoAi
                    && miuiFolderVisible
                    && !miuiOverviewVisible
                    && !launcherOpenBreak
                    && !launcherDrawer;
            boolean launcherEditing = launcherHome
                    && Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL
                    && !launcherShade
                    && !launcherXiaoAi
                    && miuiLauncherEditing
                    && !miuiOverviewVisible
                    && !launcherOpenBreak
                    && !launcherDrawer
                    && !launcherFolder;
            if (launcherHome && !miuiOverviewVisible
                    && !launcherOpenBreak && !launcherShade
                    && !launcherXiaoAi
                    && !launcherHomeSurface
                    && !launcherDrawer && !launcherFolder
                    && !launcherEditing) {
                moduleLog(Log.INFO, TAG, "Ignored native back on launcher Home"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", overviewVisible=false"
                        + ", launcherShade=false"
                        + ", launcherXiaoAi=false"
                        + ", launcherHomeSurface=false"
                        + ", launcherDrawer=false"
                        + ", launcherFolder=false"
                        + ", launcherEditing=false"
                        + ", launcherOpenActive="
                        + miuiLauncherOpenActive
                        + ", launcherOpenBreakAvailable="
                        + miuiLauncherOpenBreakAvailable
                        + ", commandsInFlight="
                        + launcherOpenBreakCommandsInFlight.get()
                        + ", generation=" + miuiLauncherOpenBreakGeneration
                        + ", displayId=" + displayId);
                return false;
            }
            if (launcherPackage && !launcherHome) {
                moduleLog(Log.INFO, TAG, "Accepted non-Home MiuiHome activity as a normal back target"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherShade) {
                moduleLog(Log.INFO, TAG,
                        "Accepted native back for NotificationShade over MiuiHome Home"
                                + ", requireShellCallback=true"
                                + ", displayId=" + displayId
                                + ", edge=" + edge);
            }
            if (launcherXiaoAi) {
                moduleLog(Log.INFO, TAG,
                        "Accepted native back for XiaoAi over MiuiHome Home"
                                + ", requireShellCallback=true"
                                + ", displayId=" + displayId
                                + ", edge=" + edge);
            }
            if (launcherDrawer) {
                moduleLog(Log.INFO, TAG, "Accepted native back in MiuiHome app drawer"
                        + ", drawerVisible=true"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherFolder) {
                moduleLog(Log.INFO, TAG, "Accepted native back in MiuiHome folder"
                        + ", folderVisible=true"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherEditing) {
                moduleLog(Log.INFO, TAG, "Accepted native back in MiuiHome editing surface"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherHomeSurface) {
                moduleLog(Log.INFO, TAG, "Accepted native MiuiHome callback surface"
                        + ", requireShellCallback=true"
                        + ", ownerEpoch=" + miuiLauncherDartStateOwnerEpoch
                        + ", displayId=" + displayId + ", edge=" + edge);
            }
            if (launcherOpenBreak) {
                moduleLog(Log.INFO, TAG, "Accepted native back during launcher OPEN animation"
                        + ", launcherOpenBreakAvailable="
                        + miuiLauncherOpenBreakAvailable
                        + ", launcherHome=" + launcherHome
                        + ", generation=" + miuiLauncherOpenBreakGeneration
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            Long sysUiStateFlags = readSystemUiStateFlags();
            boolean navBarHidden = sysUiStateFlags != null
                    ? (sysUiStateFlags.longValue() & SYSUI_STATE_NAV_BAR_HIDDEN) != 0L
                    : isNavigationBarHidden();
            boolean navBarShownTransiently = isNavBarShownTransiently();
            boolean allowGestureIgnoringBarVisibility = sysUiStateFlags != null
                    && (sysUiStateFlags.longValue()
                    & SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY) != 0L;
            boolean authenticatedHeadlessLifecycle = navBarHidden
                    && !allowGestureIgnoringBarVisibility
                    && sysUiStateFlags != null
                    && isCurrentHeadlessNavBarLifecycle(edgeBackGestureHandler);
            FullscreenBackPolicy fullscreenPolicy = new FullscreenBackPolicy(
                    sysUiStateFlags, navBarHidden, navBarShownTransiently,
                    authenticatedHeadlessLifecycle,
                    navBarHidden && isFullscreenDirectBackEnabled());
            if (!fullscreenPolicy.allowsBack) {
                moduleLog(Log.INFO, TAG, "Ignored native back by AOSP bar-visibility policy"
                        + ", sysUiStateFlags=" + sysUiStateFlags
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
                return false;
            }
            waitingForTransientBarsAtDown = fullscreenPolicy.yieldToTransientBars;
            if (fullscreenPolicy.directBack) {
                moduleLog(Log.INFO, TAG, "Accepted immersive back with direct-back preference"
                        + ", sysUiStateFlags=" + sysUiStateFlags
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            } else if (navBarHidden && authenticatedHeadlessLifecycle
                    && !allowGestureIgnoringBarVisibility) {
                moduleLog(Log.INFO, TAG,
                        "Accepted native back through authenticated headless lifecycle"
                                + ", sysUiStateFlags=" + sysUiStateFlags
                                + ", edge=" + edge + ", x=" + event.getRawX()
                                + ", y=" + event.getRawY());
            } else if (navBarHidden) {
                moduleLog(Log.INFO, TAG, "AOSP bar-visibility policy allows immersive back"
                        + ", sysUiStateFlags=" + sysUiStateFlags
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            }
            if (!isBackGestureAllowedBySystemUiState()) {
                moduleLog(Log.INFO, TAG, "Ignored native back while SystemUI state disallows back");
                return false;
            }
            if (isInBottomGestureRegion(event)) {
                moduleLog(Log.INFO, TAG, "Ignored native back inside bottom gesture region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInMiuiSidebarRegion(event)) {
                moduleLog(Log.INFO, TAG, "Ignored native back inside MIUI sidebar bounds"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInExcludedRegion(event, edge)) {
                moduleLog(Log.INFO, TAG, "Ignored native back inside exclusion region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            launcherOpenBreakCandidate = launcherOpenBreak;
            launcherOpenBreakGenerationCandidate = launcherOpenBreak
                    ? miuiLauncherOpenBreakGeneration : 0L;
            launcherShadeCandidate = launcherShade;
            launcherXiaoAiCandidate = launcherXiaoAi;
            // Native Home children share the existing callback-only probe. The
            // complete native state, rather than the old editing query, owns A17 admission.
            launcherDrawerCandidate = launcherDrawer || launcherFolder || launcherHomeSurface;
            launcherEditingCandidate = launcherEditing;
            // Geometry, attachment, touchability, and redirect acceptance are proved later
            // by the matching token emitted only from MiuiHome's accepted processor boundary.
            return true;
        }

        protected boolean isMiuiShadeExpanded() {
            try {
                Object sysUiState = readField(edgeBackGestureHandler, "mSysUiState");
                Object stateDisplayId = invokeAnyMethod(
                        sysUiState, "getDisplayId", new Object[0]);
                Object flagsObject = invokeAnyMethod(sysUiState, "getFlags", new Object[0]);
                if (!(stateDisplayId instanceof Number)
                        || ((Number) stateDisplayId).intValue() != displayId
                        || !(flagsObject instanceof Number)) {
                    moduleLog(Log.WARN, TAG, "Rejected launcher shade state with invalid SysUiState"
                            + ", stateDisplayId=" + stateDisplayId
                            + ", monitorDisplayId=" + displayId
                            + ", flags=" + flagsObject);
                    return false;
                }
                long flags = ((Number) flagsObject).longValue();
                return (flags & SYSUI_STATE_MIUI_SHADE_EXPANDED_MASK) != 0L
                        && (flags & SYSUI_STATE_LOCKED_OR_PINNED_MASK) == 0L;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to inspect MIUI notification shade state; preserving Home ignore",
                        throwable);
                return false;
            }
        }

        protected boolean isNativeBackInputActive() {
            try {
                if (!Boolean.TRUE.equals(readField(edgeBackGestureHandler, "mIsAttached"))
                        || !Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mInGestureNavMode"))
                        || !Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mIsBackGestureAllowed"))
                        || Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mUsingThreeButtonNav"))
                        || Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mDisabledForQuickstep"))) {
                    return false;
                }
                try {
                    // Some SystemUI branches expose this derived lifecycle state. Xiaomi's
                    // current build does not, so absence is compatible while a present false
                    // value must disable the driver.
                    Field enabledField = findCachedField(
                            edgeBackGestureHandler.getClass(), "mIsEnabled");
                    if (!Boolean.TRUE.equals(enabledField.get(edgeBackGestureHandler))) {
                        return false;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        protected boolean isNavBarShownTransiently() {
            try {
                return Boolean.TRUE.equals(readField(edgeBackGestureHandler,
                        "mIsNavBarShownTransiently"));
            } catch (Throwable ignored) {
                return false;
            }
        }

        protected Long readSystemUiStateFlags() {
            try {
                Object sysUiState = readField(edgeBackGestureHandler, "mSysUiState");
                Object stateDisplayId = invokeAnyMethod(
                        sysUiState, "getDisplayId", new Object[0]);
                Object flagsObject = invokeAnyMethod(
                        sysUiState, "getFlags", new Object[0]);
                if (!(stateDisplayId instanceof Number)
                        || ((Number) stateDisplayId).intValue() != displayId
                        || !(flagsObject instanceof Number)) {
                    moduleLog(Log.WARN, TAG,
                            "Cannot bind AOSP gesture policy to current SysUiState"
                                    + ", stateDisplayId=" + stateDisplayId
                                    + ", monitorDisplayId=" + displayId
                                    + ", flags=" + flagsObject);
                    return null;
                }
                long flags = ((Number) flagsObject).longValue();
                boolean logHeadlessState = false;
                synchronized (headlessNavBarLifecycleLock) {
                    HeadlessNavBarLease lease = headlessNavBarLease;
                    if (lease != null
                            && lease.edgeBackGestureHandler == edgeBackGestureHandler
                            && !headlessSysUiStateLogged) {
                        headlessSysUiStateLogged = true;
                        logHeadlessState = true;
                    }
                }
                if (logHeadlessState) {
                    moduleLog(Log.INFO, TAG,
                            "Authenticated native SysUiState for headless NavigationBar"
                                    + ", displayId=" + displayId
                                    + ", flags=0x" + Long.toHexString(flags));
                }
                return Long.valueOf(flags);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to inspect AOSP immersive gesture visibility policy",
                        throwable);
                return null;
            }
        }

        protected boolean isBackGestureAllowedBySystemUiState() {
            try {
                Object allowed = readField(edgeBackGestureHandler, "mIsBackGestureAllowed");
                if (allowed instanceof Boolean && !((Boolean) allowed).booleanValue()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object running = readField(edgeBackGestureHandler,
                        "mGestureBlockingActivityRunning");
                Object value = invokeAnyMethod(running, "get", new Object[0]);
                if (!(value instanceof Boolean) || Boolean.TRUE.equals(value)) {
                    return false;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect gesture-blocking activity state",
                        throwable);
                return false;
            }
            return true;
        }

        // HyperTweak: this runs in privileged SystemUI and queries tasks on the owning display.
        // UsageStats is not an equivalent source of current RunningTaskInfo/topActivity.
        @SuppressWarnings("deprecation")
        protected ComponentName findTopActivity() {
            try {
                ActivityManager activityManager = context.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(20);
                if (tasks == null || tasks.isEmpty()) {
                    return null;
                }
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    int taskDisplayId = readRunningTaskDisplayId(task);
                    if (taskDisplayId != displayId || task.topActivity == null) {
                        continue;
                    }
                    return task.topActivity;
                }
                return null;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect top activity for native back", throwable);
                return null;
            }
        }

        protected boolean isLauncherHomeComponent(ComponentName topActivity) {
            try {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(displayId == 0
                                ? Intent.CATEGORY_HOME : Intent.CATEGORY_SECONDARY_HOME)
                        .setPackage(MIUI_HOME);
                ResolveInfo resolved = context.getPackageManager().resolveActivity(
                        homeIntent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
                ActivityInfo activityInfo = resolved == null ? null : resolved.activityInfo;
                if (activityInfo == null || !MIUI_HOME.equals(activityInfo.packageName)) {
                    // Preserve the old fail-closed Home behavior when package resolution is
                    // unexpectedly unavailable. Pilfering a real launcher Home stream is worse
                    // than declining a sibling Activity until the resolution fault is known.
                    moduleLog(Log.WARN, TAG, "Could not resolve a MiuiHome HOME component"
                            + ", topActivity=" + topActivity.flattenToShortString()
                            + ", displayId=" + displayId);
                    return true;
                }
                ComponentName declared = new ComponentName(
                        activityInfo.packageName, activityInfo.name);
                return topActivity.equals(declared)
                        || (activityInfo.targetActivity != null
                        && topActivity.equals(new ComponentName(
                        activityInfo.packageName, activityInfo.targetActivity)));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to distinguish MiuiHome Activity from launcher Home"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", displayId=" + displayId, throwable);
                return true;
            }
        }

        protected int readRunningTaskDisplayId(ActivityManager.RunningTaskInfo task) {
            try {
                Object value = readField(task, "displayId");
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            } catch (Throwable throwable) {
                if (displayId != 0) {
                    moduleLog(Log.WARN, TAG, "Failed to inspect running-task display"
                            + ", monitorDisplayId=" + displayId, throwable);
                }
            }
            // The SDK stub hides TaskInfo.displayId. Default-display SystemUI can safely use
            // the historical default when reflection is unavailable; secondary displays fail
            // closed instead of borrowing another display's launcher state.
            return displayId == 0 ? 0 : Integer.MIN_VALUE;
        }

        protected boolean isInExcludedRegion(MotionEvent event, int edge) {
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            try {
                Object region = readField(edgeBackGestureHandler, "mExcludeRegion");
                if (!(region instanceof Region) || ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect application exclusion region",
                        throwable);
                return true;
            }
            try {
                Object region = readField(edgeBackGestureHandler, "mDesktopModeExcludeRegion");
                if (!(region instanceof Region) || ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect desktop exclusion region", throwable);
                return true;
            }
            try {
                if (isNavigationOverlayExcluded(edgeBackGestureHandler, x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect navigation-overlay exclusion bounds",
                        throwable);
                return true;
            }
            Boolean inPip = readPipState();
            if (inPip == null) {
                return true;
            }
            if (inPip.booleanValue()) {
                try {
                    Object bounds = readField(edgeBackGestureHandler, "mPipExcludedBounds");
                    if (!(bounds instanceof Rect) || ((Rect) bounds).contains(x, y)) {
                        return true;
                    }
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG, "Failed to inspect PiP exclusion bounds", throwable);
                    return true;
                }
            }
            return false;
        }

        protected Boolean readPipState() {
            try {
                Object value = readField(edgeBackGestureHandler, "mIsInPip");
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect PiP state", throwable);
                return null;
            }
        }

        protected boolean isInMiuiSidebarRegion(MotionEvent event) {
            String encoded;
            try {
                encoded = Settings.Secure.getString(context.getContentResolver(),
                        MIUI_SIDEBAR_BOUNDS);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to read MIUI sidebar bounds", throwable);
                return true;
            }
            if (encoded == null || encoded.trim().isEmpty()) {
                return false;
            }
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            int padding = Math.max(0, Math.round(dp(MIUI_SIDEBAR_EXCLUSION_PADDING_DP)));
            try {
                JSONArray bounds = new JSONArray(encoded);
                for (int i = 0; i < bounds.length(); i++) {
                    JSONObject item = bounds.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    Rect rect = new Rect(item.optInt("l", -1), item.optInt("t", -1),
                            item.optInt("r", -1), item.optInt("b", -1));
                    if (rect.isEmpty()) {
                        continue;
                    }
                    rect.inset(-padding, -padding);
                    if (rect.contains(x, y)) {
                        return true;
                    }
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to parse MIUI sidebar bounds: " + encoded,
                        throwable);
                return true;
            }
            return false;
        }

        protected void resetCandidate() {
            gestureCandidate = false;
            MotionEvent oldDown = pendingDownEvent;
            MotionEvent oldMotion = pendingMotionEvent;
            pendingDownEvent = null;
            pendingMotionEvent = null;
            if (oldDown != null) {
                oldDown.recycle();
            }
            if (oldMotion != null) {
                oldMotion.recycle();
            }
            launcherOpenBreakCandidate = false;
            launcherOpenBreakGenerationCandidate = 0L;
            launcherShadeCandidate = false;
            launcherXiaoAiCandidate = false;
            launcherDrawerCandidate = false;
            launcherEditingCandidate = false;
            miuiHomeInputAccepted = false;
            pilfered = false;
            waitingForTransientBarsAtDown = false;
            activeEdge = EDGE_LEFT;
            downEventId = 0;
            downDeviceId = Integer.MIN_VALUE;
            downSource = 0;
            downDisplayId = Integer.MIN_VALUE;
            downX = 0.0f;
            downY = 0.0f;
            downTime = Long.MIN_VALUE;
            downLauncherStateOwnerEpoch = 0L;
        }

        protected boolean isNavigationBarHidden() {
            try {
                WindowInsets insets = context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getWindowInsets();
                return insets == null
                        || !insets.isVisible(WindowInsets.Type.navigationBars());
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect navigation-bar visibility", throwable);
                return true;
            }
        }

        protected float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }

        protected float bottomGestureHeight() {
            float height = readFloatFieldOrDefault(edgeBackGestureHandler,
                    "mBottomGestureHeight", Float.NaN);
            return Float.isNaN(height) || Float.isInfinite(height)
                    ? Float.POSITIVE_INFINITY : Math.max(0.0f, height);
        }

        protected boolean isInBottomGestureRegion(MotionEvent event) {
            float bottomGestureHeight = bottomGestureHeight();
            if (bottomGestureHeight <= 0.0f) {
                return false;
            }
            try {
                Rect bounds = context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds();
                return event.getRawY() >= bounds.bottom - bottomGestureHeight;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect bottom gesture region", throwable);
                return true;
            }
        }
    }

}
