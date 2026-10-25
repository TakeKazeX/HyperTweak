package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys;

import com.takekazex.hypertweak.settings.contract.BackGestureKeys;

import com.takekazex.hypertweak.hook.Preferences;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.util.Log;
import android.view.Display;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.BackTouchTracker;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.takekazex.hypertweak.hook.rules.backgesture.PredictiveBackPreferences;
import com.takekazex.hypertweak.hook.rules.backgesture.hooks.core.HookRuntimeCore;

/** Shared input settings, haptics and contextual-search input policy. */
@SuppressLint({"PrivateApi", "DiscouragedApi"})
public abstract class SystemUiInputPolicyRuntime extends HookRuntimeCore {
    protected abstract void publishSystemUiReturnHomeFinish(
            Object controller, long shellSessionId,
            Object finishCallback, String reason);

    protected abstract Object findNativeEdgeBackPlugin(
            Object edgeBackGestureHandler) throws Exception;

    protected abstract void prepareNativeBackPanel(
            Object edgeBackGestureHandler, Object plugin) throws Exception;

    protected abstract void updateNativeBackPanelDisplaySize(
            Object edgeBackGestureHandler, Object plugin) throws Exception;

    protected abstract boolean isNavigationOverlayExcluded(
            Object edgeBackGestureHandler, int x, int y) throws Exception;

    protected abstract void injectPlatformLegacyBackKey(
            Object controller, int displayId) throws Exception;

    protected static boolean hasXiaomiBackIntent(
            float outwardDistance, float verticalDelta, float outwardThreshold) {
        return outwardDistance > outwardThreshold
                && outwardDistance >= Math.abs(verticalDelta) / 2.0f;
    }

    protected final Map<Object, ContextualSearchInputReceiver> contextualSearchInputReceivers =
            Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * NavigationBar instances remain discoverable while the Android 16 switch is disabled.
     */
    protected final Set<Object> contextualSearchNavigationBars =
            Collections.newSetFromMap(new WeakHashMap<>());
    protected volatile Object[] pendingHotReloadContextualSearchNavigationBars = new Object[0];

    protected volatile boolean hyperOsIndicatorPreferencesFailureLogged;
    protected volatile MiuiHapticFeedbackHelper hyperOsBackHapticHelper;
    protected volatile SharedPreferences gestureTriggerPreferences;
    protected volatile boolean gestureTriggerPreferencesFailureLogged;
    protected volatile SharedPreferences contextualSearchPreferences;
    protected volatile boolean contextualSearchPreferencesFailureLogged;
    protected volatile boolean contextualSearchServiceUnavailableLogged;

    protected boolean isContextualSearchLongPressEnabled() {
        try {
            SharedPreferences preferences = contextualSearchPreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = contextualSearchPreferences;
                    if (preferences == null) {
                        preferences = getRemotePreferences(PredictiveBackPreferences.GROUP);
                        contextualSearchPreferences = preferences;
                    }
                }
            }
            boolean enabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            contextualSearchPreferencesFailureLogged = false;
            return enabled;
        } catch (Throwable throwable) {
            if (!contextualSearchPreferencesFailureLogged) {
                contextualSearchPreferencesFailureLogged = true;
                moduleLog(Log.ERROR, TAG, "Contextual-search preference unavailable"
                        + ", policy=failClosed", throwable);
            }
            return false;
        }
    }

    /**
     * Android 17 delegates the terminal long-press callback to native MiuiHome.  Xiaomi's
     * helper aborts the launcher when the platform contextual-search Binder service is absent,
     * which can happen after an API-102 hot upgrade because the service startup gate has already
     * run.  Never publish the requested preference to that native owner until the service really
     * exists.  Android 16 keeps its existing Java path, whose Binder request already fails closed.
     */
    protected boolean isContextualSearchLongPressRuntimeEnabled() {
        if (!isContextualSearchLongPressEnabled()) {
            contextualSearchServiceUnavailableLogged = false;
            return false;
        }
        if (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL) {
            return true;
        }
        try {
            Class<?> serviceManagerClass = Class.forName(
                    "android.os.ServiceManager");
            Object binderObject = serviceManagerClass
                    .getMethod("getService", String.class)
                    .invoke(null, "contextual_search");
            boolean available = binderObject instanceof IBinder
                    && ((IBinder) binderObject).isBinderAlive();
            if (available) {
                if (contextualSearchServiceUnavailableLogged) {
                    moduleLog(Log.INFO, TAG,
                            "Contextual-search service became available;"
                                    + " native launcher state may be enabled");
                }
                contextualSearchServiceUnavailableLogged = false;
                return true;
            }
            if (!contextualSearchServiceUnavailableLogged) {
                contextualSearchServiceUnavailableLogged = true;
                moduleLog(Log.WARN, TAG,
                        "Suppressed Android 17 native contextual search because the"
                                + " contextual_search service is not registered;"
                                + " policy=failClosed, restartRequiredAfterHotUpgrade=true");
            }
            return false;
        } catch (Throwable throwable) {
            if (!contextualSearchServiceUnavailableLogged) {
                contextualSearchServiceUnavailableLogged = true;
                moduleLog(Log.ERROR, TAG,
                        "Failed to verify Android 17 contextual-search service;"
                                + " policy=failClosed",
                        throwable);
            }
            return false;
        }
    }

    protected boolean isFullscreenDirectBackEnabled() {
        return Preferences.INSTANCE.backGestureBoolean(
                BackGestureKeys.KEY_FULLSCREEN_DIRECT_BACK,
                PredictiveBackPreferences.DEFAULT_FULLSCREEN_DIRECT_BACK);
    }

    /** Immutable ACTION_DOWN decision; preference changes apply to the next stream. */
    protected static final class FullscreenBackPolicy {
        final boolean directBack;
        final boolean allowsBack;
        final boolean yieldToTransientBars;

        FullscreenBackPolicy(Long sysUiStateFlags, boolean navBarHidden,
                boolean navBarShownTransiently, boolean authenticatedHeadlessLifecycle,
                boolean directBackRequested) {
            boolean readable = sysUiStateFlags != null;
            boolean allowIgnoringBarVisibility = readable
                    && (sysUiStateFlags.longValue()
                    & SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY) != 0L;
            directBack = readable && navBarHidden && directBackRequested;
            allowsBack = !navBarHidden || allowIgnoringBarVisibility || directBack
                    || (readable && authenticatedHeadlessLifecycle);
            yieldToTransientBars = navBarHidden && !navBarShownTransiently && !directBack;
        }
    }

    protected boolean isContextualSearchHapticsEnabled() {
        try {
            // HyperTweak: consume the acknowledged live snapshot, including hot reload/reset.
            return Preferences.INSTANCE.backGestureBoolean(
                    PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_HAPTICS);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search haptic preference unavailable, policy=disabled",
                    throwable);
            return false;
        }
    }

    /**
     * MiCTS-compatible click feedback, emitted only after native CTS succeeds.
     */
    @SuppressLint("MissingPermission") // Runs inside SystemUI, which owns VIBRATE.
    protected boolean playContextualSearchHaptic(Context context) {
        if (context == null || !isContextualSearchHapticsEnabled()) {
            return false;
        }
        try {
            Vibrator vibrator = context.getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setFlags(128)
                    .build();
            vibrator.vibrate(VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_CLICK),
                    new VibrationAttributes.Builder(attributes).build());
            moduleLog(Log.INFO, TAG, "Played contextual-search trigger haptic");
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to play contextual-search trigger haptic", throwable);
            return false;
        }
    }

    protected void attachContextualSearchInputReceiver(Object navigationBar) {
        detachContextualSearchInputReceiver(navigationBar);
        synchronized (contextualSearchNavigationBars) {
            contextualSearchNavigationBars.add(navigationBar);
        }
        if (!isContextualSearchLongPressEnabled()) {
            return;
        }
        try {
            Object viewObject;
            try {
                viewObject = invokeAnyMethod(navigationBar, "getView", new Object[0]);
            } catch (Throwable getterFailure) {
                viewObject = readField(navigationBar, "mView");
            }
            if (!(viewObject instanceof View)) {
                throw new IllegalStateException("NavigationBar view is "
                        + shortObject(viewObject));
            }
            View navigationView = (View) viewObject;
            Display display = navigationView.getDisplay();
            int displayId = display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            Context context = navigationView.getContext();
            InputManager inputManager = context.getSystemService(InputManager.class);
            if (inputManager == null) {
                throw new IllegalStateException("InputManager unavailable");
            }
            Object monitorObject = invokeAnyMethod(inputManager, "monitorGestureInput",
                    new Object[]{"miui-contextual-search", Integer.valueOf(displayId)});
            if (!(monitorObject instanceof InputMonitor)) {
                throw new IllegalStateException("monitorGestureInput returned "
                        + shortObject(monitorObject));
            }
            InputMonitor inputMonitor = (InputMonitor) monitorObject;
            ContextualSearchInputReceiver receiver;
            try {
                receiver = new ContextualSearchInputReceiver(
                        context, navigationBar, navigationView, inputMonitor, displayId);
            } catch (Throwable throwable) {
                inputMonitor.dispose();
                throw throwable;
            }
            synchronized (contextualSearchInputReceivers) {
                contextualSearchInputReceivers.put(navigationBar, receiver);
            }
            moduleLog(Log.INFO, TAG, "Contextual-search gesture observer attached"
                    + ", displayId=" + displayId
                    + ", enabled=" + isContextualSearchLongPressEnabled()
                    + ", recognitionTimeoutMs=" + receiver.getRecognitionTimeoutMillis());
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to attach contextual-search gesture observer", throwable);
        }
    }

    protected void detachContextualSearchInputReceiver(Object navigationBar) {
        ContextualSearchInputReceiver receiver;
        synchronized (contextualSearchInputReceivers) {
            receiver = contextualSearchInputReceivers.remove(navigationBar);
        }
        if (receiver != null) {
            receiver.detach();
        }
    }

    protected void forgetContextualSearchNavigationBar(Object navigationBar) {
        synchronized (contextualSearchNavigationBars) {
            contextualSearchNavigationBars.remove(navigationBar);
        }
    }

    /**
     * Reconcile the Android 16 input observer set after the remote switch changes.
     * Android 17's native launcher path does not call this method; it receives the
     * existing arbiter-state update instead.
     */
    protected void refreshContextualSearchInputReceivers() {
        Object[] navigationBars;
        synchronized (contextualSearchNavigationBars) {
            navigationBars = contextualSearchNavigationBars.toArray();
        }
        for (Object navigationBar : navigationBars) {
            if (navigationBar != null) {
                detachContextualSearchInputReceiver(navigationBar);
            }
        }
        if (!isContextualSearchLongPressEnabled()) {
            return;
        }
        for (Object navigationBar : navigationBars) {
            if (navigationBar != null) {
                attachContextualSearchInputReceiver(navigationBar);
            }
        }
        moduleLog(Log.INFO, TAG,
                "Refreshed Android 16 contextual-search gesture observers"
                        + ", count=" + navigationBars.length);
    }

    protected Object[] detachAllContextualSearchInputReceiversForHotReload() {
        Object[] navigationBars;
        ContextualSearchInputReceiver[] receivers;
        synchronized (contextualSearchNavigationBars) {
            navigationBars = contextualSearchNavigationBars.toArray();
        }
        synchronized (contextualSearchInputReceivers) {
            receivers = contextualSearchInputReceivers.values().toArray(
                    new ContextualSearchInputReceiver[0]);
            contextualSearchInputReceivers.clear();
        }
        for (ContextualSearchInputReceiver receiver : receivers) {
            receiver.detach();
        }
        return navigationBars;
    }

    protected void restoreContextualSearchInputReceivers(Object[] navigationBars) {
        if (navigationBars == null || navigationBars.length == 0) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            int restored = 0;
            for (Object navigationBar : navigationBars) {
                if (navigationBar == null) {
                    continue;
                }
                attachContextualSearchInputReceiver(navigationBar);
                restored++;
            }
            moduleLog(Log.INFO, TAG,
                    "Restored contextual-search gesture observers after hot reload"
                            + ", count=" + restored);
        });
    }

    protected boolean invokeContextualSearchService() {
        if (!isContextualSearchLongPressEnabled()) {
            return false;
        }
        try {
            Class<?> serviceManagerClass = Class.forName(
                    "android.os.ServiceManager");
            Object binderObject = serviceManagerClass
                    .getMethod("getService", String.class)
                    .invoke(null, "contextual_search");
            if (!(binderObject instanceof IBinder)) {
                moduleLog(Log.WARN, TAG,
                        "Contextual-search service is not registered; restart may be required");
                return false;
            }
            Class<?> stubClass = Class.forName(
                    "android.app.contextualsearch.IContextualSearchManager$Stub");
            Object service = stubClass.getMethod("asInterface", IBinder.class)
                    .invoke(null, binderObject);
            if (service == null) {
                return false;
            }
            Class<?> interfaceClass = Class.forName(
                    "android.app.contextualsearch.IContextualSearchManager");
            Method startMethod;
            Object[] arguments;
            try {
                startMethod = interfaceClass.getMethod(
                        "startContextualSearch", int.class);
                arguments = new Object[]{Integer.valueOf(1)};
            } catch (NoSuchMethodException qpr0Missing) {
                Class<?> configClass = Class.forName(
                        "android.app.contextualsearch.ContextualSearchConfig");
                startMethod = interfaceClass.getMethod(
                        "startContextualSearch", int.class, configClass);
                arguments = new Object[]{Integer.valueOf(1), null};
            }
            startMethod.invoke(service, arguments);
            moduleLog(Log.INFO, TAG,
                    "Requested contextual search from the navigation handle"
                            + ", signature=" + startMethod.toGenericString());
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search Binder request failed", throwable);
            return false;
        }
    }

    protected final class ContextualSearchInputReceiver extends InputEventReceiver {
        private static final int HANDLE_HORIZONTAL_PADDING_DP = 12;
        private static final int HANDLE_BOTTOM_REGION_HEIGHT_DP = 32;
        private static final int HANDLE_MAX_TOUCH_WIDTH_DP = 192;
        private static final long LONG_PRESS_OWNERSHIP_LEAD_MILLIS = 75L;
        private static final long MIN_LONG_PRESS_TIMEOUT_MILLIS = 200L;

        private final Object navigationBar;
        private final View navigationView;
        private final InputMonitor inputMonitor;
        private final int displayId;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final KeyguardManager keyguardManager;
        private final float touchSlopSquared;
        private final float upwardIntentDistance;
        private final long longPressTimeoutMillis;
        private boolean tracking;
        private boolean pilfered;
        private float downX;
        private float downY;
        private float lastX;
        private float lastY;
        private final Runnable longPress;

        private void onLongPressTimeout() {
            if (!tracking || pilfered || hasUpwardGestureIntent()
                    || shouldRejectLongPress()) {
                cancelTracking(false);
                return;
            }
            try {
                inputMonitor.pilferPointers();
                pilfered = true;
                moduleLog(Log.INFO, TAG,
                        "Claimed navigation-handle long press for contextual search"
                                + ", displayId=" + displayId);
                invokeContextualSearchService();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to claim contextual-search long press", throwable);
                cancelTracking(false);
            }
        }

        ContextualSearchInputReceiver(Context context, Object navigationBar,
                                      View navigationView, InputMonitor inputMonitor,
                                      int displayId) {
            super(inputMonitor.getInputChannel(), Looper.getMainLooper());
            this.navigationBar = navigationBar;
            this.navigationView = navigationView;
            this.inputMonitor = inputMonitor;
            this.displayId = displayId;
            this.keyguardManager = context.getSystemService(KeyguardManager.class);
            float touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            this.touchSlopSquared = touchSlop * touchSlop;
            this.upwardIntentDistance = Math.max(
                    touchSlop * 0.5f,
                    4.0f * context.getResources().getDisplayMetrics().density);
            long systemLongPressTimeoutMillis = ViewConfiguration.getLongPressTimeout();
            // Xiaomi's navigation-handle assistant action uses the platform long-press
            // deadline. Claim slightly before that deadline so the spy monitor can pilfer the
            // same physical stream before the competing callback commits.
            this.longPressTimeoutMillis = Math.min(
                    systemLongPressTimeoutMillis,
                    Math.max(MIN_LONG_PRESS_TIMEOUT_MILLIS,
                            systemLongPressTimeoutMillis
                                    - LONG_PRESS_OWNERSHIP_LEAD_MILLIS));
            this.longPress = this::onLongPressTimeout;
        }

        long getRecognitionTimeoutMillis() {
            return longPressTimeoutMillis;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent) {
                    handled = handleMotionEvent((MotionEvent) event);
                }
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Contextual-search input observer failed", throwable);
                cancelTracking(true);
            } finally {
                finishInputEvent(event, handled);
            }
        }

        void detach() {
            cancelTracking(true);
            try {
                dispose();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to dispose contextual-search input receiver", throwable);
            }
            try {
                inputMonitor.dispose();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to dispose contextual-search input monitor", throwable);
            }
        }

        private boolean handleMotionEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelTracking(true);
                    if (shouldRejectLongPress()
                            || !containsGestureHandle(event.getX(), event.getY())) {
                        return false;
                    }
                    tracking = true;
                    downX = event.getX();
                    downY = event.getY();
                    lastX = downX;
                    lastY = downY;
                    moduleLog(Log.INFO, TAG,
                            "Tracking navigation-handle long press"
                                    + ", displayId=" + displayId
                                    + ", x=" + Math.round(downX)
                                    + ", y=" + Math.round(downY));
                    mainHandler.postDelayed(longPress, longPressTimeoutMillis);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (tracking && !pilfered) {
                        lastX = event.getX();
                        lastY = event.getY();
                        float deltaX = lastX - downX;
                        float deltaY = lastY - downY;
                        if (event.getPointerCount() != 1
                                || hasUpwardGestureIntent()
                                || deltaX * deltaX + deltaY * deltaY > touchSlopSquared) {
                            moduleLog(Log.INFO, TAG,
                                    "Cancelled navigation-handle long press on movement"
                                            + ", displayId=" + displayId
                                            + ", dx=" + Math.round(deltaX)
                                            + ", dy=" + Math.round(deltaY)
                                            + ", pointers=" + event.getPointerCount());
                            cancelTracking(false);
                        }
                    }
                    return pilfered;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    boolean cancelledHandled = pilfered;
                    cancelTracking(true);
                    return cancelledHandled;
                case MotionEvent.ACTION_UP:
                    boolean upHandled = pilfered;
                    cancelTracking(true);
                    return upHandled;
                default:
                    return pilfered;
            }
        }

        private boolean hasUpwardGestureIntent() {
            float deltaX = lastX - downX;
            float deltaY = lastY - downY;
            float upward = -deltaY;
            return upward >= upwardIntentDistance
                    && upward >= Math.abs(deltaX) * 0.5f;
        }

        private boolean shouldRejectLongPress() {
            // HyperOS 3 / Android 16 does not expose
            // NavigationBar.shouldDisableNavbarGestures().  The observer is attached only to
            // the live default-display NavigationBar and the DOWN must still hit its visible
            // home handle, so keep the stable ownership checks here instead of failing every
            // stream on a version-specific helper.
            return !isContextualSearchLongPressEnabled()
                    || (keyguardManager != null && keyguardManager.isKeyguardLocked())
                    || !navigationView.isAttachedToWindow()
                    || !navigationView.isShown();
        }

        private boolean containsGestureHandle(float x, float y) {
            int handleId = navigationView.getResources().getIdentifier(
                    "home_handle", "id", SYSTEM_UI);
            if (handleId == 0) {
                return false;
            }
            View handle = navigationView.findViewById(handleId);
            if (handle == null || !handle.isShown() || handle.getAlpha() <= 0.0f) {
                return false;
            }
            Rect bounds = new Rect();
            if (!handle.getGlobalVisibleRect(bounds) || bounds.isEmpty()) {
                return false;
            }
            float density = navigationView.getResources().getDisplayMetrics().density;
            int screenHeight = navigationView.getResources()
                    .getDisplayMetrics().heightPixels;
            float bottomRegionHeight = HANDLE_BOTTOM_REGION_HEIGHT_DP * density;
            if (y < screenHeight - bottomRegionHeight || y > screenHeight) {
                return false;
            }
            float touchWidth = Math.min(
                    HANDLE_MAX_TOUCH_WIDTH_DP * density,
                    bounds.width() + 2.0f * HANDLE_HORIZONTAL_PADDING_DP * density);
            float centerX = bounds.exactCenterX();
            return x >= centerX - touchWidth / 2.0f
                    && x <= centerX + touchWidth / 2.0f;
        }

        private void cancelTracking(boolean clearPilfered) {
            mainHandler.removeCallbacks(longPress);
            tracking = false;
            if (clearPilfered) {
                pilfered = false;
            }
        }
    }

    protected boolean isHyperOsIndicatorEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
                PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR);
    }

    protected boolean isHyperOsHapticsEnabled() {
        return readGestureHapticEffect() == PredictiveBackPreferences.GESTURE_HAPTIC_HYPEROS;
    }

    protected boolean isGestureHapticsDisabled() {
        return readGestureHapticEffect() == PredictiveBackPreferences.GESTURE_HAPTIC_DISABLED;
    }

    protected int readGestureHapticEffect() {
        try {
            int effect = PredictiveBackPreferences.readGestureHapticEffect(
                    getRemotePreferences(PredictiveBackPreferences.GROUP));
            hyperOsIndicatorPreferencesFailureLogged = false;
            return effect;
        } catch (Throwable throwable) {
            if (!hyperOsIndicatorPreferencesFailureLogged) {
                hyperOsIndicatorPreferencesFailureLogged = true;
                moduleLog(Log.ERROR, TAG, "Gesture haptic preference unavailable"
                        + ", policy=keepNativePanelHaptics", throwable);
            }
            return PredictiveBackPreferences.GESTURE_HAPTIC_AOSP;
        }
    }

    protected boolean isHyperOsIndicatorBlurEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_INDICATOR_BLUR,
                PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR_BLUR);
    }

    protected boolean isHyperOsHapticsEnhancedEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
                PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_ENHANCED);
    }

    @Override
    protected boolean isHyperOsSlideAnimationEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
                PredictiveBackPreferences.DEFAULT_HYPEROS_SLIDE_ANIMATION);
    }

    protected boolean readHyperOsBooleanPreference(String key, boolean defaultValue) {
        try {
            // Preferences may replace the remote channel after service recovery. Never
            // retain the old SharedPreferences instance as the style's authoritative source.
            boolean enabled = com.takekazex.hypertweak.hook.Preferences.INSTANCE.backGestureBoolean(key, defaultValue);
            hyperOsIndicatorPreferencesFailureLogged = false;
            return enabled;
        } catch (Throwable throwable) {
            if (!hyperOsIndicatorPreferencesFailureLogged) {
                hyperOsIndicatorPreferencesFailureLogged = true;
                moduleLog(Log.ERROR, TAG, "HyperOS indicator preference unavailable"
                        + ", policy=failClosedToAospPanel"
                        + ", key=" + key, throwable);
            }
            return false;
        }
    }

    /**
     * Replaces AOSP threshold haptics with HyperOS's default back effect. The
     * caller must keep the original AOSP method when this returns false.
     */
    protected boolean playHyperOsReplacementHaptic(Object panelViewOrController) {
        // HyperOS indicator mode owns its own two-stage feedback path. In AOSP-panel mode,
        // this same global switch replaces the panel's original threshold feedback.
        if (isHyperOsIndicatorEnabled() || !isHyperOsHapticsEnabled()) {
            return false;
        }
        try {
            MiuiHapticFeedbackHelper helper = hyperOsBackHapticHelper;
            if (helper == null) {
                synchronized (this) {
                    helper = hyperOsBackHapticHelper;
                    if (helper == null) {
                        Object panelView = panelViewOrController instanceof View
                                ? panelViewOrController
                                : readField(panelViewOrController, "mView");
                        if (!(panelView instanceof View)) {
                            return false;
                        }
                        Context panelContext = ((View) panelView).getContext();
                        helper = new MiuiHapticFeedbackHelper(panelContext,
                                (priority, message, throwable) -> {
                                    if (throwable == null) {
                                        moduleLog(priority, TAG, message);
                                    } else {
                                        moduleLog(priority, TAG, message, throwable);
                                    }
                                });
                        hyperOsBackHapticHelper = helper;
                    }
                }
            }
            if (!helper.isDefaultBackSupported()) {
                return false;
            }
            return helper.performDefaultBack();
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to replace native threshold haptic; keeping native feedback",
                    throwable);
            return false;
        }
    }

    /**
     * Adds the optional committed-release haptic while the native AOSP indicator remains
     * visible. HyperOS indicator mode already dispatches this stage from its arrow driver.
     */
    protected void playAospIndicatorHandUpHaptic(Object panelViewOrController) {
        if (isHyperOsIndicatorEnabled()
                || !isHyperOsHapticsEnabled()
                || !isHyperOsHapticsEnhancedEnabled()) {
            return;
        }
        try {
            MiuiHapticFeedbackHelper helper = hyperOsBackHapticHelper;
            if (helper == null) {
                Object panelView = panelViewOrController instanceof View
                        ? panelViewOrController
                        : readField(panelViewOrController, "mView");
                if (!(panelView instanceof View)) {
                    return;
                }
                Context panelContext = ((View) panelView).getContext();
                helper = new MiuiHapticFeedbackHelper(panelContext,
                        (priority, message, throwable) -> {
                            if (throwable == null) {
                                moduleLog(priority, TAG, message);
                            } else {
                                moduleLog(priority, TAG, message, throwable);
                            }
                        });
                hyperOsBackHapticHelper = helper;
            }
            if (!helper.isSupported()) {
                return;
            }
            helper.setEnhancedMode(true);
            helper.performHandUp();
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to play AOSP-indicator hand-up haptic", throwable);
        }
    }

    protected void closeHyperOsBackHapticHelper() {
        MiuiHapticFeedbackHelper helper = hyperOsBackHapticHelper;
        hyperOsBackHapticHelper = null;
        if (helper != null) {
            helper.close();
        }
    }

    protected boolean isOpenEndHandoffCurrent(long handoffEpoch) {
        return handoffEpoch != 0L
                && openSnapshotLifecycleEpoch.get() == handoffEpoch
                && runningOpenTransitions.isEmpty();
    }

    protected boolean isShellReadyOnOwner(Object stateController)
            throws Exception {
        if (Boolean.TRUE.equals(readField(stateController,
                "mPostCommitAnimationInProgress"))
                || Boolean.TRUE.equals(readField(
                stateController, "mBackGestureStarted"))
                || Boolean.TRUE.equals(readField(
                stateController, "mReceivedNullNavigationInfo"))
                || readField(stateController,
                "mBackNavigationInfo") != null
                || readField(stateController,
                "mBackAnimationFinishedCallback") != null) {
            return false;
        }
        Object current = readField(stateController, "mCurrentTracker");
        Object queued = readField(stateController, "mQueuedTracker");
        return isTrackerInitial(current) && isTrackerInitial(queued);
    }

    protected boolean isTrackerInitial(Object tracker) throws Exception {
        return tracker == null || ((BackTouchTracker) tracker).isInInitialState();
    }

}
