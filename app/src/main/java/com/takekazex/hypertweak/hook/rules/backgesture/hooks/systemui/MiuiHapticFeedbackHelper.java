package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

// Adapted for HyperTweak from wxxsfxyzm/MiuiBackGestureHook (Apache-2.0).
// Vendored through upstream a5f1ae5 (v0.8.5). Keep structural parity so future updates stay
// mergeable; HyperTweak-local changes are marked.

import android.content.Context;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Reproduces MiuiHome's two-stage back gesture haptics inside SystemUI through the
 * framework {@code miui.util.HapticFeedbackUtil} (boot classpath on MIUI/HyperOS).
 *
 * <p>Mirrors {@code HapticFeedbackCompatLinear}/{@code HapticFeedbackCompatV2}:
 * <ul>
 *   <li>ready-back (arrow fade-in complete): ext effect 162 on linear-motor devices,
 *       effect 0 on {@code sys.haptic.version=2.0} devices; arms a 140ms blocker on
 *       linear devices only.</li>
 *   <li>hand-up (committed release): ext effect 163 / 0, skipped on linear devices
 *       within 140ms of ready-back.</li>
 * </ul>
 * The optional two-stage mode requires {@code isSupportExtHapticFeedback(162) && (163)}
 * exactly like Xiaomi's {@code isSupportEffectGestureBackLinear()}; the single AOSP
 * replacement checks only its actual default effect (162 or V2 effect 0).
 */
final class MiuiHapticFeedbackHelper {

    private static final int EFFECT_GESTURE_READY_BACK_LINEAR = 162;
    private static final int EFFECT_GESTURE_BACK_HAND_UP_LINEAR = 163;
    private static final int EFFECT_GESTURE_BACK_V2 = 0;
    // Stock V2 plays effect 0 for both stages; enhanced mode uses a distinct hand-up.
    private static final int EFFECT_GESTURE_BACK_HAND_UP_V2_ENHANCED = 1;
    private static final long LINEAR_HAND_UP_BLOCK_MS = 140L;

    interface Logger {
        void log(int priority, String message, Throwable throwable);
    }

    private final Logger logger;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Object hapticFeedbackUtil;
    private final Method performExtHapticFeedback;
    private final boolean defaultBackSupported;
    private final boolean supported;
    private final boolean hapticV2;
    private volatile boolean enhancedMode;
    private volatile long lastReadyBackUptime;

    MiuiHapticFeedbackHelper(Context context, Logger logger) {
        this.logger = logger;
        Object util = null;
        Method perform = null;
        boolean resolvedDefaultSupported = false;
        boolean resolvedSupported = false;
        boolean resolvedV2 = false;
        try {
            String version = SystemProperties.get("sys.haptic.version", "1.0");
            resolvedV2 = "2.0".equals(version);

            Class<?> utilClass = Class.forName("miui.util.HapticFeedbackUtil");
            boolean linearMotor = Boolean.TRUE.equals(utilClass
                    .getMethod("isSupportLinearMotorVibrate").invoke(null));
            if (linearMotor) {
                util = utilClass.getConstructor(Context.class, boolean.class)
                        .newInstance(context, Boolean.TRUE);
                Method support = utilClass.getMethod(
                        "isSupportExtHapticFeedback", int.class);
                perform = utilClass.getMethod("performExtHapticFeedback", int.class);
                resolvedDefaultSupported = Boolean.TRUE.equals(support.invoke(util,
                        Integer.valueOf(resolvedV2
                                ? EFFECT_GESTURE_BACK_V2
                                : EFFECT_GESTURE_READY_BACK_LINEAR)));
                resolvedSupported = resolvedDefaultSupported
                        && Boolean.TRUE.equals(support.invoke(util,
                        Integer.valueOf(EFFECT_GESTURE_BACK_HAND_UP_LINEAR)));
            }
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "MIUI haptic feedback unavailable"
                    + ", policy=keepNativePanelHaptics", throwable);
            util = null;
            perform = null;
            resolvedDefaultSupported = false;
            resolvedSupported = false;
        }
        this.hapticFeedbackUtil = util;
        this.performExtHapticFeedback = perform;
        this.defaultBackSupported = resolvedDefaultSupported
                && util != null && perform != null;
        this.supported = resolvedSupported && util != null && perform != null;
        this.hapticV2 = resolvedV2;
        if (this.supported) {
            logger.log(Log.INFO, "MIUI two-stage back haptics ready"
                    + ", hapticV2=" + resolvedV2, null);
        } else if (this.defaultBackSupported) {
            logger.log(Log.INFO, "MIUI default AOSP replacement haptic ready"
                    + ", effect=" + (resolvedV2
                    ? EFFECT_GESTURE_BACK_V2 : EFFECT_GESTURE_READY_BACK_LINEAR)
                    + ", hapticV2=" + resolvedV2, null);
        }
    }

    boolean isSupported() {
        return supported;
    }

    boolean isDefaultBackSupported() {
        return defaultBackSupported;
    }

    boolean isHapticV2() {
        return hapticV2;
    }

    /**
     * Enhanced release feedback: every committed release plays hand-up, V2 devices use
     * effect 1 instead of 0, and the 140ms blocker also applies to V2. Disabled keeps
     * the stock behavior.
     */
    void setEnhancedMode(boolean enabled) {
        enhancedMode = enabled;
    }

    boolean isEnhancedMode() {
        return enhancedMode;
    }

    /** Plays only HyperOS's default back effect, without its second hand-up stage. */
    boolean performDefaultBack() {
        if (!defaultBackSupported) {
            return false;
        }
        int effectId = hapticV2 ? EFFECT_GESTURE_BACK_V2
                : EFFECT_GESTURE_READY_BACK_LINEAR;
        try {
            // The original View feedback call is synchronous. Keep this single replacement
            // synchronous as well so the threshold does not acquire an executor delay and a
            // runtime failure can still fall back to the native AOSP effect.
            performExtHapticFeedback.invoke(hapticFeedbackUtil,
                    Integer.valueOf(effectId));
            return true;
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "Failed to play MIUI default AOSP replacement effect "
                    + effectId, throwable);
            return false;
        }
    }

    void close() {
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    /** Arrow fade-in completed; mirrors performGestureReadyBack(). */
    void performReadyBack() {
        if (!supported) {
            return;
        }
        if (!hapticV2 || enhancedMode) {
            lastReadyBackUptime = SystemClock.uptimeMillis();
        }
        play(hapticV2 ? EFFECT_GESTURE_BACK_V2 : EFFECT_GESTURE_READY_BACK_LINEAR);
    }

    /** Committed release; mirrors performGestureBackHandUp() with the 140ms blocker. */
    void performHandUp() {
        if (!supported) {
            return;
        }
        if ((!hapticV2 || enhancedMode) && SystemClock.uptimeMillis() - lastReadyBackUptime
                < LINEAR_HAND_UP_BLOCK_MS) {
            return;
        }
        int effect;
        if (hapticV2) {
            effect = enhancedMode
                    ? EFFECT_GESTURE_BACK_HAND_UP_V2_ENHANCED : EFFECT_GESTURE_BACK_V2;
        } else {
            effect = EFFECT_GESTURE_BACK_HAND_UP_LINEAR;
        }
        play(effect);
    }

    private void play(int effectId) {
        executor.execute(() -> {
            try {
                performExtHapticFeedback.invoke(hapticFeedbackUtil,
                        Integer.valueOf(effectId));
            } catch (Throwable throwable) {
                logger.log(Log.WARN, "Failed to play MIUI ext haptic effect "
                        + effectId, throwable);
            }
        });
    }
}
