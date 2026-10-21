package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackProgressAnimator;
import android.window.BackTouchTracker;
import android.window.TransitionInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.takekazex.hypertweak.hook.rules.backgesture.PredictiveBackPreferences;
import io.github.libxposed.api.XposedInterface;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Owns Shell predictive animation preparation, rendering and commit receipts. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public abstract class SystemUiAnimationRuntime extends SystemUiPreparedTransitionRuntime {
    protected volatile PredictiveBackWallpaper predictiveBackWallpaper;
    protected void hookFreeformCrossActivityScrimCreation() {
        try {
            Method setHidden = requireExactDeclaredMethod(SurfaceControl.Builder.class,
                    "setHidden", SurfaceControl.Builder.class.getName(), "boolean");
            registerHook(setHidden, "systemui_back_color_root_scrim_creation", this::keepFreeformScrimHiddenUntilFirstApply);
            moduleLog(Log.INFO, TAG,
                    "Hooked freeform cross-activity scrim creation visibility");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook freeform cross-activity scrim creation",
                    throwable);
        }
    }

    protected void hookCrossActivitySlideAnimation(ClassLoader classLoader,
                                                   boolean installStart,
                                                   boolean installProgress,
                                                   boolean installPostCommit,
                                                   boolean installDuration,
                                                   boolean installFinish,
                                                   boolean installColorRootApply) {
        Class<?> baseClass;
        Class<?> defaultClass;
        Class<?> backMotionEventClass;
        try {
            baseClass = Class.forName(
                    CROSS_ACTIVITY_BACK_ANIMATION, false, classLoader);
            defaultClass = Class.forName(
                    DEFAULT_CROSS_ACTIVITY_BACK_ANIMATION, false, classLoader);
            backMotionEventClass = BackMotionEvent.class;
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Cross-activity animation classes unavailable",
                    throwable);
            return;
        }
        // Each hook installs independently: R8 may rename individual members in
        // Xiaomi's build, and a missing one must only degrade its own stage.
        if (installColorRootApply) {
            try {
                Method apply = resolveSlideMethod(defaultClass, baseClass,
                        "applyTransaction", void.class);
                registerHook(apply, "systemui_back_color_root_apply", this::onCrossActivityColorRootApply);
                moduleLog(Log.INFO, TAG, "Hooked freeform color-layer root adoption");
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed to hook freeform color-layer root adoption", throwable);
            }
        }
        if (installStart) {
            try {
                Method start = resolveSlideMethod(defaultClass, baseClass,
                        "startBackAnimation", void.class, backMotionEventClass);
                registerHook(start, "systemui_back_slide_start", this::onCrossActivitySlideStart);
                moduleLog(Log.INFO, TAG, "Hooked slide start as " + start.getName());
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide start", throwable);
            }
        }
        if (installProgress) {
            try {
                Class<?> callback = Class.forName(CROSS_ACTIVITY_BACK_ANIMATION + "$Callback", false, classLoader);
                Method callbackStart = callback.getDeclaredMethod("onBackStarted", BackMotionEvent.class);
                registerHook(callbackStart, "systemui_back_slide_callback_start", chain -> {
                    Object owner = readFieldOrNull(chain.getThisObject(), "this$0");
                    Object previousOwner = crossActivityStartOwner.get();
                    if (owner != null) crossActivityStartOwner.set(owner);
                    try { return chain.proceed(); }
                    finally {
                        if (previousOwner == null) crossActivityStartOwner.remove();
                        else crossActivityStartOwner.set(previousOwner);
                    }
                });
                // Interpreting the real callback prevents ART from bypassing its hooked
                // start/BackProgressAnimator calls through previously compiled inline code.
                deoptimize(callbackStart);
            } catch (Throwable failure) {
                moduleLog(Log.WARN, TAG, "Cross-activity callback ownership unavailable", failure);
            }
            try {
                // R8 inlines the private per-frame method into its registration
                // lambda, so the stable interception point is the framework
                // BackProgressAnimator: swap the registered ProgressCallback for the
                // armed animation's own animator and drive the frames ourselves.
                Method register = BackProgressAnimator.class.getDeclaredMethod(
                        "onBackStarted", BackMotionEvent.class,
                        BackProgressAnimator.ProgressCallback.class);
                register.setAccessible(true);
                registerHook(register, "systemui_back_slide_progress", this::onCrossActivitySlideProgressRegistration);
                moduleLog(Log.INFO, TAG,
                        "Hooked slide progress via BackProgressAnimator registration");
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide progress", throwable);
            }
        }
        if (installPostCommit) {
            try {
                Method postCommit = resolveSlideMethod(defaultClass, baseClass,
                        "onPostCommitProgress", void.class, float.class);
                // Hooked on the base class means this is the super-call position:
                // the subclass override keeps writing its native geometry after our
                // interceptor returns, so our frame must be applied afterwards.
                miuixSlidePostCommitOnBase =
                        postCommit.getDeclaringClass() == baseClass;
                registerHook(postCommit, "systemui_back_slide_post_commit", this::onCrossActivitySlidePostCommit);
                moduleLog(Log.INFO, TAG, "Hooked slide post-commit as "
                        + postCommit.getDeclaringClass().getSimpleName()
                        + "." + postCommit.getName()
                        + ", superPosition=" + miuixSlidePostCommitOnBase);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide post-commit", throwable);
            }
        }
        if (installDuration) {
            try {
                Method duration = resolveSlideMethod(defaultClass, baseClass,
                        "getPostCommitAnimationDuration", long.class);
                registerHook(duration, "systemui_back_slide_duration", this::onCrossActivitySlideDuration);
                moduleLog(Log.INFO, TAG, "Hooked slide duration as " + duration.getName());
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide duration", throwable);
            }
        }
        if (installFinish) {
            try {
                Method finish = resolveSlideMethod(defaultClass, baseClass,
                        "finishAnimation", void.class);
                registerHook(finish, "systemui_back_slide_finish", this::onCrossActivitySlideFinish);
                moduleLog(Log.INFO, TAG, "Hooked slide finish as "
                        + finish.getDeclaringClass().getSimpleName()
                        + "." + finish.getName());
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide finish", throwable);
            }
        }
    }

    protected void hookCrossActivityRunnerSelection(ClassLoader loader) {
        try {
            Class<?> registry = Class.forName("com.android.wm.shell.back.ShellBackAnimationRegistry", false, loader);
            Method select = registry.getDeclaredMethod("getAnimationRunnerAndInit", BackNavigationInfo.class);
            registerHook(select, "systemui_back_slide_runner", chain -> {
                Object selected = null;
                try {
                    Object navigation = chain.getArg(0);
                    if (isHyperOsSlideAnimationEnabled() && navigation instanceof BackNavigationInfo
                            && ((BackNavigationInfo) navigation).getType() == TYPE_CROSS_ACTIVITY) {
                        Object registryOwner = chain.getThisObject();
                        Object animation = readFieldOrNull(registryOwner, "mDefaultCrossActivityAnimation");
                        if (animation != null) {
                            selected = invokeAnyMethod(animation, "getRunner", new Object[0]);
                            if (selected != null) {
                                Object color = invokeAnyMethod(navigation, "getLetterboxColor", new Object[0]);
                                invokeAnyMethod(animation, "prepareNextAnimation", new Object[]{null, color});
                                invokeAnyMethod(readField(registryOwner, "mAnimationDefinition"), "set",
                                        new Object[]{TYPE_CROSS_ACTIVITY, selected});
                            }
                        }
                    }
                } catch (Throwable failure) {
                    selected = null;
                    moduleLog(Log.WARN, TAG, "Slide runner unavailable; preserving native selection", failure);
                }
                return selected == null ? chain.proceed() : selected;
            });
            deoptimize(select);
        } catch (Throwable failure) {
            moduleLog(Log.ERROR, TAG, "Failed to bind live cross-activity slide runner", failure);
        }
    }

    protected void hookCrossTaskBackground(ClassLoader classLoader) {
        try {
            Class<?> backgroundClass = Class.forName(BACK_ANIMATION_BACKGROUND, false, classLoader);
            // The five-argument overload delegates here. Hook only the terminal overload,
            // so freeform crops are retained and a fullscreen background is rendered once.
            Method method = backgroundClass.getDeclaredMethod("ensureBackground", Rect.class,
                    int.class, SurfaceControl.Transaction.class, int.class, Rect.class,
                    float[].class, int.class, SurfaceControl.class);
            registerHook(method, "systemui_cross_task_background", this::renderPredictiveBackWallpaper);
        } catch (Throwable failure) {
            moduleLog(Log.ERROR, TAG, "Failed to hook predictive-back wallpaper background", failure);
        }
    }

    protected Object renderPredictiveBackWallpaper(XposedInterface.Chain chain) throws Throwable {
        Object previous = readFieldOrNull(chain.getThisObject(), "mBackgroundSurface");
        Object result = chain.proceed();
        if (previous != null) return result;
        SurfaceControl replacement = null;
        boolean installed = false;
        try {
            boolean crossAppSession = false;
            int backgroundDisplayId = ((Number) chain.getArg(6)).intValue();
            for (NativeBackInputMonitor monitor : nativeInputMonitors.values()) {
                if (monitor.driver.displayId != backgroundDisplayId) continue;
                SystemUiBackGestureDriver.ShellGestureSession session = monitor.driver.activeShellSession;
                if (session != null && session.completionConsumed.get()) continue;
                Object navigation = session == null && monitor.driver.shellStartInFlight
                        ? readFieldOrNull(monitor.driver.controller, "mBackNavigationInfo")
                        : session == null ? null : session.navigation;
                if (navigation == null) continue;
                Object type = readBackNavigationType(navigation);
                if (!(type instanceof Number)) continue;
                int navigationType = ((Number) type).intValue();
                String key;
                boolean defaultValue;
                if (navigationType == TYPE_CROSS_ACTIVITY) {
                    key = PredictiveBackPreferences.KEY_CROSS_ACTIVITY_WALLPAPER;
                    defaultValue = PredictiveBackPreferences.DEFAULT_CROSS_ACTIVITY_WALLPAPER;
                } else if (navigationType == TYPE_CROSS_TASK) {
                    key = PredictiveBackPreferences.KEY_CROSS_TASK_WALLPAPER;
                    defaultValue = PredictiveBackPreferences.DEFAULT_CROSS_TASK_WALLPAPER;
                } else continue;
                crossAppSession = readHyperOsBooleanPreference(key, defaultValue);
                break;
            }
            if (!crossAppSession) return result;
            Object background = chain.getThisObject();
            Object old = readFieldOrNull(background, "mBackgroundSurface");
            if (!(old instanceof SurfaceControl) || !((SurfaceControl) old).isValid()) return result;
            Rect bounds = (Rect) chain.getArg(0);
            Rect crop = (Rect) chain.getArg(4);
            float[] corners = (float[]) chain.getArg(5);
            int displayId = ((Number) chain.getArg(6)).intValue();
            SurfaceControl relative = (SurfaceControl) chain.getArg(7);
            SurfaceControl.Transaction transaction = (SurfaceControl.Transaction) chain.getArg(2);
            Object organizer = readField(background, "mRootTaskDisplayAreaOrganizer");
            Object contextValue = readFieldOrNull(organizer, "mContext");
            Context context = contextValue instanceof Context ? (Context) contextValue
                    : miuiOverviewReceiverContext;
            if (context == null || bounds == null || bounds.isEmpty()) return result;
            int width = Math.max(bounds.width(), crop == null ? 0 : crop.right);
            int height = Math.max(bounds.height(), crop == null ? 0 : crop.bottom);
            SurfaceControl.Builder builder = new SurfaceControl.Builder();
            invokeAnyMethod(organizer, "attachToDisplayArea", new Object[]{displayId, builder});
            PredictiveBackWallpaper wallpaper = predictiveBackWallpaper;
            if (wallpaper == null) return result;
            replacement = wallpaper.create(builder, width, height);
            if (replacement == null) return result;
            if (relative != null && relative.isValid()) invokeAnyMethod(transaction, "setRelativeLayer", new Object[]{replacement, relative, -1});
            else transaction.setLayer(replacement, -1);
            if (crop != null && !crop.isEmpty()) {
                transaction.setCrop(replacement, crop);
                if (corners != null && corners.length == 4) invokeAnyMethod(transaction, "setCornerRadius", new Object[]{replacement, corners[0]});
            }
            invokeAnyMethod(transaction, "show", new Object[]{replacement});
            // Write the field only after rendering/geometry succeeds. Shell's existing
            // removeBackground owns this replacement on commit, cancellation and reload.
            writeField(background, "mBackgroundSurface", replacement);
            invokeAnyMethod(transaction, "remove", new Object[]{old});
            ((SurfaceControl) old).release();
            installed = true;
        } catch (Throwable failure) {
            moduleLog(Log.WARN, TAG, "Predictive-back wallpaper unavailable; keeping native background", failure);
        } finally {
            if (!installed && replacement != null) {
                try { PredictiveBackWallpaper.discard(replacement); }
                catch (Throwable cleanupFailure) {
                    moduleLog(Log.WARN, TAG, "Failed to retire unused wallpaper surface", cleanupFailure);
                }
            }
        }
        return result;
    }

    protected String describeRemoteAnimationTargets(Object targets) {
        if (targets == null || !targets.getClass().isArray()) {
            return shortObject(targets);
        }
        int count = java.lang.reflect.Array.getLength(targets);
        StringBuilder description = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                description.append(", ");
            }
            description.append(describeRemoteAnimationTarget(
                    java.lang.reflect.Array.get(targets, index)));
        }
        return description.append(']').toString();
    }

    /**
     * Records the exact targets owned by Shell's native cross-task animation at the point
     * where it creates its background. This is deliberately read-only: compositor alpha
     * must not be corrected until the faulty leash and frame are proven on-device.
     */
    protected void logCrossTaskTargetDiagnostics() {
        Object animation = aospCrossTaskAnimation;
        if (animation == null) {
            moduleLog(Log.WARN, TAG, "Cross-task diagnostic missing registry animation");
            return;
        }
        Object closingTarget = readFieldOrNull(animation, "mClosingTarget");
        Object enteringTarget = readFieldOrNull(animation, "mEnteringTarget");
        moduleLog(Log.INFO, TAG, "Cross-task exact targets"
                + ", animation=" + shortObject(animation)
                + ", closing=" + describeRemoteAnimationTarget(closingTarget)
                + ", entering=" + describeRemoteAnimationTarget(enteringTarget)
                + ", closingRect="
                + shortObject(readFieldOrNull(animation, "mClosingCurrentRect"))
                + ", enteringRect="
                + shortObject(readFieldOrNull(animation, "mEnteringCurrentRect")));
    }

    protected String describeRemoteAnimationTarget(Object target) {
        if (target == null || target instanceof String) {
            return shortObject(target);
        }
        return "{identity=" + shortObject(target)
                + ", taskId=" + readIntFieldOrDefault(target, "taskId", -1)
                + ", mode=" + readIntFieldOrDefault(target, "mode", -1)
                + ", leash=" + shortObject(readFieldOrNull(target, "leash"))
                + ", localBounds=" + shortObject(readFieldOrNull(target, "localBounds"))
                + ", screenBounds="
                + shortObject(readFieldOrNull(target, "screenSpaceBounds"))
                + ", translucent=" + shortObject(readFieldOrNull(target, "isTranslucent"))
                + "}";
    }

    // finishAnimation() is the animation's natural end; clear the session flag so a
    // later gesture re-arms cleanly. The original always runs.
    protected Object onCrossActivitySlideFinish(XposedInterface.Chain chain)
            throws Throwable {
        miuixSlideAnimActive = false;
        freeformColorRootCandidate.set(null);
        FreeformColorRootAdoption adoption = freeformColorRootAdoption;
        if (adoption != null && adoption.animation == chain.getThisObject()) {
            freeformColorRootAdoption = null;
        }
        return chain.proceed();
    }

    /**
     * Resolves an animation-class member by name first, then falls back to a unique
     * signature match so an R8-renamed member is still found. Same-name declarations
     * across the hierarchy are one virtual method — the most-derived one wins;
     * different-name candidates at the same level are ambiguous and fail.
     */
    protected Method resolveSlideMethod(Class<?> leaf, Class<?> stop, String name,
                                        Class<?> returnType, Class<?>... parameters)
            throws NoSuchMethodException {
        try {
            return findDeclaredMethodInHierarchy(leaf, stop, name, parameters);
        } catch (NoSuchMethodException ignored) {
        }
        Class<?> current = leaf;
        while (current != null) {
            Method match = null;
            for (Method candidate : current.getDeclaredMethods()) {
                if (candidate.isSynthetic()
                        || candidate.getReturnType() != returnType
                        || !Arrays.equals(candidate.getParameterTypes(), parameters)) {
                    continue;
                }
                if (match != null) {
                    throw new NoSuchMethodException(name
                            + ": ambiguous signature fallback in " + current.getName()
                            + " (" + match.getName() + " vs " + candidate.getName()
                            + ")");
                }
                match = candidate;
            }
            if (match != null) {
                match.setAccessible(true);
                moduleLog(Log.INFO, TAG, "Resolved " + name + " by signature as "
                        + current.getName() + "." + match.getName());
                return match;
            }
            if (current == stop) {
                break;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(leaf.getName() + "." + name);
    }

    protected Method findDeclaredMethodInHierarchy(Class<?> leaf, Class<?> stop,
                                                   String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> current = leaf;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            if (current == stop) {
                break;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(leaf.getName() + "." + name);
    }

    protected volatile boolean miuixSlideAnimActive;

    protected final RectF miuixSlideCommitClosing = new RectF();
    protected final RectF miuixSlideCommitEntering = new RectF();
    protected boolean miuixSlideCommitPoseCaptured;
    protected float miuixSlideCommitEnteringAlpha = 1.0f;
    protected float miuixSlideCommitScrimAlpha;
    protected float miuixSlideCommitScrimVelocity;
    protected float miuixSlideProgressVelocity;
    protected float miuixSlideLastProgressSample;
    protected long miuixSlideLastProgressSampleMs;
    protected boolean miuixSlidePostCommitOnBase;
    protected volatile WeakReference<Object> miuixSlideArmedAnimation;
    protected boolean miuixSlideRegistrationReentry;
    protected volatile Method crossActivityApplyTransform;
    protected volatile Object crossActivityNoFling;
    protected volatile Method multiTaskingControllerGetInstance;
    protected final AtomicReference<FreeformColorRootCandidate>
            freeformColorRootCandidate = new AtomicReference<>();
    protected volatile FreeformColorRootAdoption freeformColorRootAdoption;

    protected static final long MIUIX_SLIDE_SETTLE_DURATION_MS = 400L;
    protected static final float MIUIX_SLIDE_ENTERING_MIN_ALPHA = 0.9f;
    protected static final float MIUIX_SLIDE_PARALLAX_FRACTION = 0.25f;
    protected static final float MIUIX_SLIDE_SCRIM_OMEGA = 12.083f;
    protected static final float MIUIX_SLIDE_SCRIM_MAX_ALPHA = 0.5f;

    protected static final class FreeformColorRootCandidate {
        final Object handler;
        final Object transitionToken;
        final Object transitionInfo;
        final Object appsIdentity;
        final Object closingTarget;
        final Object enteringTarget;
        final SurfaceControl rootLeash;
        final SurfaceControl closingLeash;
        final SurfaceControl enteringLeash;
        final float rootCornerRadius;

        FreeformColorRootCandidate(Object handler, Object transitionToken,
                                   Object transitionInfo,
                                   Object appsIdentity, Object closingTarget,
                                   Object enteringTarget, SurfaceControl rootLeash,
                                   SurfaceControl closingLeash,
                                   SurfaceControl enteringLeash,
                                   float rootCornerRadius) {
            this.handler = handler;
            this.transitionToken = transitionToken;
            this.transitionInfo = transitionInfo;
            this.appsIdentity = appsIdentity;
            this.closingTarget = closingTarget;
            this.enteringTarget = enteringTarget;
            this.rootLeash = rootLeash;
            this.closingLeash = closingLeash;
            this.enteringLeash = enteringLeash;
            this.rootCornerRadius = rootCornerRadius;
        }
    }

    protected static final class FreeformColorRootAdoption {
        final Object animation;
        final FreeformColorRootCandidate candidate;
        final Rect closingCrop = new Rect();
        final Rect enteringCrop = new Rect();

        FreeformColorRootAdoption(Object animation,
                                  FreeformColorRootCandidate candidate) {
            this.animation = animation;
            this.candidate = candidate;
        }
    }

    protected Object keepFreeformScrimHiddenUntilFirstApply(
            XposedInterface.Chain chain) throws Throwable {
        FreeformColorRootCandidate candidate = freeformColorRootCandidate.get();
        Object builder = chain.getThisObject();
        if (candidate == null
                || !Boolean.FALSE.equals(chain.getArg(0))
                || !"Cross-Activity back animation scrim".equals(
                readFieldOrNull(builder, "mName"))
                || !"CrossActivityBackAnimation".equals(
                readFieldOrNull(builder, "mCallsite"))) {
            return chain.proceed();
        }
        moduleLog(Log.INFO, TAG,
                "Kept freeform cross-activity scrim hidden until atomic first apply"
                        + ", taskId=" + readIntFieldOrDefault(
                        candidate.closingTarget, "taskId", -1));
        return chain.proceed(new Object[]{Boolean.TRUE});
    }

    protected boolean isExactFreeformCrossActivityPair(Object closingTarget,
                                                       Object enteringTarget)
            throws Exception {
        int taskId = readIntFieldOrDefault(closingTarget, "taskId", -1);
        Object closingBounds = readFieldOrNull(closingTarget, "localBounds");
        Object enteringBounds = readFieldOrNull(enteringTarget, "localBounds");
        return closingTarget != null && enteringTarget != null
                && closingTarget != enteringTarget && taskId >= 0
                && taskId == readIntFieldOrDefault(enteringTarget, "taskId", -1)
                && resolveRemoteTargetWindowingMode(closingTarget)
                == WINDOWING_MODE_FREEFORM
                && resolveRemoteTargetWindowingMode(enteringTarget)
                == WINDOWING_MODE_FREEFORM
                && closingBounds instanceof Rect
                && !((Rect) closingBounds).isEmpty()
                && closingBounds.equals(enteringBounds);
    }

    protected SurfaceControl resolveSingleTransitionRoot(Object info) throws Exception {
        Object rootCount = readTransitionInfoRootCount(info);
        if (!(rootCount instanceof Number)
                || ((Number) rootCount).intValue() != 1) {
            return null;
        }
        Object root = readTransitionInfoRoot(info, 0);
        Object leash = readTransitionRootLeash(root);
        return leash instanceof SurfaceControl ? (SurfaceControl) leash : null;
    }

    protected float resolveFreeformRootCornerRadius(Object handler, int taskId)
            throws Exception {
        ClassLoader classLoader = handler.getClass().getClassLoader();
        Class<?> controllerClass = Class.forName(
                "com.android.wm.shell.dagger.MultiTaskingControllerImpl",
                false, classLoader);
        Method getInstance = multiTaskingControllerGetInstance;
        if (getInstance == null
                || getInstance.getDeclaringClass() != controllerClass) {
            getInstance = controllerClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            multiTaskingControllerGetInstance = getInstance;
        }
        Object controller = getInstance.invoke(null);
        Object repository = invokeAnyMethod(controller,
                "getMultiTaskingTaskRepository", new Object[0]);
        Object taskInfo = invokeAnyMethod(repository,
                "getMiuiFreeformTaskInfo", new Object[]{Integer.valueOf(taskId)});
        Object radiusValue = invokeAnyMethod(taskInfo,
                "getCornerRadius", new Object[0]);
        Object scaleValue = invokeAnyMethod(taskInfo,
                "getFreeformScale", new Object[0]);
        if (!(radiusValue instanceof Number) || !(scaleValue instanceof Number)) {
            throw new IllegalStateException("freeform radius or scale unavailable");
        }
        float radius = ((Number) radiusValue).floatValue();
        float scale = ((Number) scaleValue).floatValue();
        float rootRadius = radius / scale;
        if (!(radius > 0.0f) || !(scale > 0.0f) || !Float.isFinite(rootRadius)) {
            throw new IllegalStateException("invalid freeform radius geometry"
                    + ", radius=" + radius + ", scale=" + scale);
        }
        return rootRadius;
    }

    protected Object onCrossActivityColorRootApply(XposedInterface.Chain chain)
            throws Throwable {
        FreeformColorRootCandidate candidate = freeformColorRootCandidate.get();
        if (candidate == null) {
            FreeformColorRootAdoption adoption = freeformColorRootAdoption;
            if (adoption != null && adoption.animation == chain.getThisObject()) {
                try {
                    applyAdoptedFreeformTargetGeometry(
                            chain.getThisObject(), adoption);
                } catch (Throwable throwable) {
                    if (freeformColorRootAdoption == adoption) {
                        freeformColorRootAdoption = null;
                    }
                    moduleLog(Log.WARN, TAG,
                            "Failed freeform Activity geometry normalization;"
                                    + " preserving native target geometry",
                            throwable);
                }
            }
            return chain.proceed();
        }
        FreeformColorRootAdoption adopted = null;
        try {
            Object animation = chain.getThisObject();
            if (!matchesFreeformColorRootCandidate(candidate, animation)) {
                freeformColorRootCandidate.compareAndSet(candidate, null);
                moduleLog(Log.WARN, TAG,
                        "Rejected stale freeform color-layer root candidate");
            } else {
                Object scrim = readFieldOrNull(animation, "scrimLayer");
                Object backgroundOwner = readFieldOrNull(animation, "background");
                Object background = readFieldOrNull(
                        backgroundOwner, "mBackgroundSurface");
                Object transaction = readFieldOrNull(animation, "transaction");
                if (!(scrim instanceof SurfaceControl)
                        || !((SurfaceControl) scrim).isValid()
                        || !(background instanceof SurfaceControl)
                        || !((SurfaceControl) background).isValid()
                        || !(transaction instanceof SurfaceControl.Transaction)) {
                    throw new IllegalStateException(
                            "freeform color layers unavailable at first apply");
                }
                Object root = readTransitionInfoRoot(candidate.transitionInfo, 0);
                Object rootOffset = readTransitionRootOffset(root);
                Object colorBounds = readFieldOrNull(
                        candidate.closingTarget, "localBounds");
                Object targetCrop = readFieldOrNull(animation, "cropRect");
                if (!(rootOffset instanceof Point)
                        || !(colorBounds instanceof Rect)
                        || !(targetCrop instanceof Rect)
                        || !Boolean.FALSE.equals(
                        readFieldOrNull(animation, "isLetterboxed"))) {
                    throw new IllegalStateException(
                            "freeform color-layer crop geometry unavailable");
                }
                Rect rootLocalColorCrop = new Rect((Rect) colorBounds);
                Point offset = (Point) rootOffset;
                rootLocalColorCrop.offset(-offset.x, -offset.y);
                if (!rootLocalColorCrop.equals(targetCrop)) {
                    throw new IllegalStateException(
                            "freeform color-layer crop does not match prepared root");
                }
                if (freeformColorRootCandidate.compareAndSet(candidate, null)) {
                    SurfaceControl.Transaction surfaceTransaction =
                            (SurfaceControl.Transaction) transaction;
                    try (SurfaceControl.Transaction donor =
                                 new SurfaceControl.Transaction()) {
                        donor.setCrop(candidate.rootLeash, rootLocalColorCrop);
                        invokeMethod(donor, "setCornerRadius",
                                new Class<?>[]{SurfaceControl.class, float.class},
                                new Object[]{candidate.rootLeash,
                                        Float.valueOf(candidate.rootCornerRadius)});
                        donor.reparent((SurfaceControl) background,
                                        candidate.rootLeash)
                                .setCrop((SurfaceControl) background,
                                        rootLocalColorCrop)
                                .setAlpha((SurfaceControl) background, 0.0f)
                                .setLayer((SurfaceControl) background, -1)
                                .reparent((SurfaceControl) scrim,
                                        candidate.rootLeash)
                                .setCrop((SurfaceControl) scrim,
                                        rootLocalColorCrop);
                        invokeMethod(donor, "setRelativeLayer",
                                new Class<?>[]{SurfaceControl.class,
                                        SurfaceControl.class, int.class},
                                new Object[]{scrim, candidate.closingLeash,
                                        Integer.valueOf(-1)});
                        invokeMethod(donor, "setCornerRadius",
                                new Class<?>[]{SurfaceControl.class, float.class},
                                new Object[]{candidate.closingLeash,
                                        Float.valueOf(candidate.rootCornerRadius)});
                        invokeMethod(donor, "setCornerRadius",
                                new Class<?>[]{SurfaceControl.class, float.class},
                                new Object[]{candidate.enteringLeash,
                                        Float.valueOf(candidate.rootCornerRadius)});
                        surfaceTransaction.merge(donor);
                    }
                    adopted = new FreeformColorRootAdoption(animation, candidate);
                }
            }
        } catch (Throwable throwable) {
            freeformColorRootCandidate.compareAndSet(candidate, null);
            moduleLog(Log.WARN, TAG,
                    "Failed freeform color-layer root adoption; leaving native layers",
                    throwable);
        }
        Object result = chain.proceed();
        if (adopted != null) {
            freeformColorRootAdoption = adopted;
            moduleLog(Log.INFO, TAG,
                    "Adopted freeform cross-activity color layers into prepared root"
                            + ", backgroundAlpha=0.0"
                            + ", rootCornerRadius=" + candidate.rootCornerRadius
                            + ", taskId=" + readIntFieldOrDefault(
                            candidate.closingTarget, "taskId", -1));
        }
        return result;
    }

    protected void applyAdoptedFreeformTargetGeometry(
            Object animation, FreeformColorRootAdoption adoption) throws Exception {
        FreeformColorRootCandidate candidate = adoption.candidate;
        Object transaction = readFieldOrNull(animation, "transaction");
        if (readFieldOrNull(animation, "closingTarget") != candidate.closingTarget
                || readFieldOrNull(animation, "enteringTarget")
                != candidate.enteringTarget
                || !(transaction instanceof SurfaceControl.Transaction)
                || !candidate.closingLeash.isValid()
                || !candidate.enteringLeash.isValid()) {
            throw new IllegalStateException(
                    "freeform Activity target ownership changed");
        }
        SurfaceControl.Transaction surfaceTransaction =
                (SurfaceControl.Transaction) transaction;
        boolean defaultGeometry = DEFAULT_CROSS_ACTIVITY_BACK_ANIMATION.equals(
                animation.getClass().getName());
        WeakReference<Object> slideReference = miuixSlideArmedAnimation;
        boolean slideGeometry = defaultGeometry && miuixSlideAnimActive
                && slideReference != null
                && slideReference.get() == animation;
        if (!defaultGeometry) {
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.closingLeash, candidate.rootCornerRadius);
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.enteringLeash, candidate.rootCornerRadius);
            return;
        }
        Object frame = readFieldOrNull(animation, "backAnimRect");
        Object nativeCrop = readFieldOrNull(animation, "cropRect");
        Object closingRect = readFieldOrNull(animation, "currentClosingRect");
        Object enteringRect = readFieldOrNull(animation, "currentEnteringRect");
        if (!(frame instanceof Rect) || !(nativeCrop instanceof Rect)
                || !(closingRect instanceof RectF)
                || !(enteringRect instanceof RectF)
                || ((RectF) closingRect).isEmpty()
                || ((RectF) enteringRect).isEmpty()) {
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.closingLeash, candidate.rootCornerRadius);
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.enteringLeash, candidate.rootCornerRadius);
            return;
        }
        float postCommitScale = 1.0f;
        if (!slideGeometry) {
            Object lastFlingScale = readFieldOrNull(
                    animation, "lastPostCommitFlingScale");
            if (lastFlingScale instanceof Number) {
                float value = ((Number) lastFlingScale).floatValue();
                if (value > 0.0f && value <= 1.0f) {
                    postCommitScale = value;
                }
            }
        }
        float closingRadius = resolveFixedFreeformTargetClip(
                (Rect) frame, (Rect) nativeCrop,
                (RectF) closingRect, postCommitScale,
                candidate.rootCornerRadius, adoption.closingCrop);
        float enteringRadius = resolveFixedFreeformTargetClip(
                (Rect) frame, (Rect) nativeCrop,
                (RectF) enteringRect, postCommitScale,
                candidate.rootCornerRadius, adoption.enteringCrop);
        surfaceTransaction.setCrop(candidate.closingLeash, adoption.closingCrop);
        setFreeformTargetCornerRadius(surfaceTransaction,
                candidate.closingLeash, closingRadius);
        surfaceTransaction.setCrop(candidate.enteringLeash, adoption.enteringCrop);
        setFreeformTargetCornerRadius(surfaceTransaction,
                candidate.enteringLeash, enteringRadius);
    }

    protected void setFreeformTargetCornerRadius(
            SurfaceControl.Transaction transaction, SurfaceControl leash,
            float cornerRadius) throws Exception {
        invokeMethod(transaction, "setCornerRadius",
                new Class<?>[]{SurfaceControl.class, float.class},
                new Object[]{leash, Float.valueOf(cornerRadius)});
    }

    protected float resolveFixedFreeformTargetClip(
            Rect frame, Rect nativeCrop, RectF currentRect,
            float additionalScale, float rootCornerRadius, Rect outCrop) {
        if (frame.isEmpty() || frame.left != 0 || frame.top != 0
                || !nativeCrop.equals(frame)
                || !(additionalScale > 0.0f)
                || !Float.isFinite(additionalScale)) {
            throw new IllegalStateException("unsupported freeform target crop geometry");
        }
        float visualWidth = currentRect.width() * additionalScale;
        float visualHeight = currentRect.height() * additionalScale;
        float scaleX = visualWidth / frame.width();
        float scaleY = visualHeight / frame.height();
        if (!(scaleX > 0.0f) || !(scaleY > 0.0f)
                || !Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                || Math.abs(scaleX - scaleY) > 0.01f) {
            throw new IllegalStateException("non-uniform freeform target transform");
        }
        float visualLeft = currentRect.centerX() - (visualWidth / 2.0f);
        float visualTop = currentRect.centerY() - (visualHeight / 2.0f);
        int left = Math.max(nativeCrop.left, Math.min(nativeCrop.right,
                (int) Math.ceil((frame.left - visualLeft) / scaleX)));
        int top = Math.max(nativeCrop.top, Math.min(nativeCrop.bottom,
                (int) Math.ceil((frame.top - visualTop) / scaleY)));
        int right = Math.max(nativeCrop.left, Math.min(nativeCrop.right,
                (int) Math.floor((frame.right - visualLeft) / scaleX)));
        int bottom = Math.max(nativeCrop.top, Math.min(nativeCrop.bottom,
                (int) Math.floor((frame.bottom - visualTop) / scaleY)));
        if (right < left) {
            right = left;
        }
        if (bottom < top) {
            bottom = top;
        }
        outCrop.set(left, top, right, bottom);
        return outCrop.isEmpty() ? 0.0f
                : Math.min(rootCornerRadius / scaleX,
                Math.min(outCrop.width(), outCrop.height()) / 2.0f);
    }

    protected boolean matchesFreeformColorRootCandidate(
            FreeformColorRootCandidate candidate, Object animation) throws Exception {
        Object controller = readField(candidate.handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        Object transitionType = readTransitionInfoType(candidate.transitionInfo);
        if (readField(candidate.handler, "mPrepareOpenTransition")
                != candidate.transitionToken
                || readField(candidate.handler, "mOpenTransitionInfo")
                != candidate.transitionInfo
                || readField(controller, "mApps")
                != candidate.appsIdentity
                || !(navigationType instanceof Number)
                || ((Number) navigationType).intValue() != TYPE_CROSS_ACTIVITY
                || !(transitionType instanceof Number)
                || ((Number) transitionType).intValue() != TRANSIT_PREDICTIVE_BACK) {
            return false;
        }
        SurfaceControl rootLeash = resolveSingleTransitionRoot(
                candidate.transitionInfo);
        Object closingTarget = readFieldOrNull(animation, "closingTarget");
        Object enteringTarget = readFieldOrNull(animation, "enteringTarget");
        Object closingLeash = readFieldOrNull(closingTarget, "leash");
        Object enteringLeash = readFieldOrNull(enteringTarget, "leash");
        if (rootLeash != candidate.rootLeash
                || closingTarget != candidate.closingTarget
                || enteringTarget != candidate.enteringTarget
                || closingLeash != candidate.closingLeash
                || enteringLeash != candidate.enteringLeash
                || !isExactFreeformCrossActivityPair(
                closingTarget, enteringTarget)) {
            return false;
        }
        return candidate.rootLeash.isValid()
                && candidate.closingLeash.isValid()
                && candidate.enteringLeash.isValid();
    }

    protected final ThreadLocal<Object> crossActivityStartOwner = new ThreadLocal<>();
    protected final AtomicBoolean slideRouteReported = new AtomicBoolean();

    protected Object onCrossActivitySlideStart(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        armCrossActivitySlide(chain.getThisObject());
        return result;
    }

    protected void armCrossActivitySlide(Object animation) {
        try {
            miuixSlideCommitPoseCaptured = false;
            boolean slideEnabled = isHyperOsSlideAnimationEnabled();
            if (!slideEnabled) {
                miuixSlideAnimActive = false;
                return;
            }
            if (readField(animation, "closingTarget") == null
                    || readField(animation, "enteringTarget") == null) {
                miuixSlideAnimActive = false;
                return;
            }
            Rect backAnimRect = (Rect) readField(animation, "backAnimRect");
            float width = backAnimRect.width();
            if (width <= 0f) {
                miuixSlideAnimActive = false;
                return;
            }
            Context animationContext = (Context) readField(animation, "context");
            boolean rtl = animationContext.getResources().getConfiguration()
                    .getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float slide = rtl ? -width : width;
            RectF startClosing = (RectF) readField(animation, "startClosingRect");
            RectF targetClosing = (RectF) readField(animation, "targetClosingRect");
            RectF startEntering = (RectF) readField(animation, "startEnteringRect");
            RectF targetEntering = (RectF) readField(animation, "targetEnteringRect");
            startClosing.set(backAnimRect);
            targetClosing.set(backAnimRect);
            targetClosing.offset(slide, 0f);
            targetEntering.set(backAnimRect);
            startEntering.set(backAnimRect);
            startEntering.offset(-slide * MIUIX_SLIDE_PARALLAX_FRACTION, 0f);
            miuixSlideProgressVelocity = 0.0f;
            miuixSlideLastProgressSample = 0.0f;
            miuixSlideLastProgressSampleMs = 0L;
            miuixSlideArmedAnimation = new WeakReference<>(animation);
            miuixSlideAnimActive = true;
            moduleLog(Log.INFO, TAG, "Armed miuix slide back animation"
                    + ", width=" + width + ", rtl=" + rtl);
        } catch (Throwable throwable) {
            miuixSlideAnimActive = false;
            moduleLog(Log.WARN, TAG, "Failed to arm miuix slide geometry", throwable);
        }
    }

    /**
     * Fires when any BackProgressAnimator registers its per-gesture ProgressCallback.
     * For the armed cross-activity animation's own animator, the native callback (the
     * inlined geometry) is replaced with the miuix frame driver; every other animator
     * registers untouched.
     */
    protected Object onCrossActivitySlideProgressRegistration(
            XposedInterface.Chain chain) throws Throwable {
        Object callbackOwner = crossActivityStartOwner.get();
        if (!miuixSlideRegistrationReentry && callbackOwner != null
                && readFieldOrNull(callbackOwner, "progressAnimator") == chain.getThisObject()) {
            // Native start has completed inside the callback even when its separate
            // method hook was bypassed. Arm before the first progress callback is registered.
            armCrossActivitySlide(callbackOwner);
        }
        if (miuixSlideRegistrationReentry || !miuixSlideAnimActive) {
            return chain.proceed();
        }
        WeakReference<Object> armedReference = miuixSlideArmedAnimation;
        Object animation = armedReference == null ? null : armedReference.get();
        if (animation == null) {
            return chain.proceed();
        }
        Object progressAnimator;
        try {
            progressAnimator = readField(animation, "progressAnimator");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to read progressAnimator", throwable);
            return chain.proceed();
        }
        if (progressAnimator != chain.getThisObject()) {
            return chain.proceed();
        }
        Object originalCallback = chain.getArg(1);
        BackProgressAnimator.ProgressCallback replacement = event -> {
            try {
                onMiuixSlideFrame(animation, event);
            } catch (Throwable throwable) {
                miuixSlideAnimActive = false;
                moduleLog(Log.WARN, TAG, "miuix slide frame failed"
                        + ", fallingBackToNativeCallback=true", throwable);
                if (originalCallback instanceof BackProgressAnimator.ProgressCallback) {
                    ((BackProgressAnimator.ProgressCallback) originalCallback)
                            .onProgressUpdate(event);
                }
            }
        };
        miuixSlideRegistrationReentry = true;
        try {
            ((BackProgressAnimator) chain.getThisObject()).onBackStarted(
                    (BackMotionEvent) chain.getArg(0), replacement);
        } finally {
            miuixSlideRegistrationReentry = false;
        }
        if (slideRouteReported.compareAndSet(false, true)) {
            moduleLog(Log.INFO, TAG, "Cross-activity slide route active"
                    + ", animation=" + animation.getClass().getName());
        }
        moduleLog(Log.DEBUG, TAG, "miuix slide progress callback proxied");
        return null;
    }

    protected void onMiuixSlideFrame(Object animation, BackEvent backEvent)
            throws Exception {
        if (!miuixSlideAnimActive) {
            return;
        }
        // The delivered progress already tracks the finger through
        // BackProgressAnimator's smoothing spring (cancel rides it back to zero); mapping
        // it linearly, without the native gesture interpolator, is the miuix slide.
        float progress = Math.max(0.0f, Math.min(1.0f, backEvent.getProgress()));
        trackMiuixSlideProgressVelocity(progress);
        writeField(animation, "gestureProgress", Float.valueOf(progress));
        applyMiuixSlideFrame(animation, progress,
                MIUIX_SLIDE_ENTERING_MIN_ALPHA
                        + (1.0f - MIUIX_SLIDE_ENTERING_MIN_ALPHA) * progress);
    }

    // Smoothed progress-per-second, so the commit can seed the scrim fade with the
    // finger's release speed instead of starting from rest.
    protected void trackMiuixSlideProgressVelocity(float progress) {
        long now = SystemClock.uptimeMillis();
        if (miuixSlideLastProgressSampleMs != 0L && now > miuixSlideLastProgressSampleMs) {
            float instant = (progress - miuixSlideLastProgressSample)
                    / ((now - miuixSlideLastProgressSampleMs) / 1000.0f);
            miuixSlideProgressVelocity =
                    0.5f * miuixSlideProgressVelocity + 0.5f * instant;
        }
        miuixSlideLastProgressSample = progress;
        miuixSlideLastProgressSampleMs = now;
    }

    protected Object onCrossActivitySlidePostCommit(XposedInterface.Chain chain)
            throws Throwable {
        if (!miuixSlideAnimActive) {
            return chain.proceed();
        }
        try {
            Object animation = chain.getThisObject();
            float linear = ((Number) chain.getArg(0)).floatValue();
            if (!miuixSlideCommitPoseCaptured) {
                miuixSlideCommitClosing.set((RectF) readField(
                        animation, "currentClosingRect"));
                miuixSlideCommitEntering.set((RectF) readField(
                        animation, "currentEnteringRect"));
                float commitProgress = readFloatFieldOrDefault(
                        animation, "gestureProgress", 0.0f);
                miuixSlideCommitEnteringAlpha = MIUIX_SLIDE_ENTERING_MIN_ALPHA
                        + (1.0f - MIUIX_SLIDE_ENTERING_MIN_ALPHA) * commitProgress;
                // Anchor the settle's dim fade to the exact alpha the drag ended on, and
                // seed it with the matching release speed (scrim falls as progress rises).
                miuixSlideCommitScrimAlpha =
                        MIUIX_SLIDE_SCRIM_MAX_ALPHA * (1.0f - commitProgress);
                miuixSlideCommitScrimVelocity =
                        -MIUIX_SLIDE_SCRIM_MAX_ALPHA * miuixSlideProgressVelocity;
                // The subclass onGestureCommitted (not hooked) rewrites the target
                // rects to its own 0.9 card pose, so the slide would settle short of
                // the edge. Restore the full slide-out destination for the settle.
                Rect backAnimRect = (Rect) readField(animation, "backAnimRect");
                Context animationContext = (Context) readField(animation, "context");
                boolean rtl = animationContext.getResources().getConfiguration()
                        .getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                float slide = rtl ? -backAnimRect.width() : backAnimRect.width();
                RectF targetClosing = (RectF) readField(
                        animation, "targetClosingRect");
                RectF targetEntering = (RectF) readField(
                        animation, "targetEnteringRect");
                targetClosing.set(backAnimRect);
                targetClosing.offset(slide, 0f);
                targetEntering.set(backAnimRect);
                miuixSlideCommitPoseCaptured = true;
            }
            if (miuixSlidePostCommitOnBase) {
                // Super-call position: the subclass override writes its native
                // geometry after this hook returns. Reapply ours behind it on the
                // same looper turn so the miuix frame is what reaches the compositor.
                Handler frameHandler = new Handler(Objects.requireNonNull(Looper.myLooper()));
                frameHandler.post(() -> {
                    try {
                        if (miuixSlideAnimActive) {
                            applyMiuixSlidePostCommitFrame(animation, linear);
                        }
                    } catch (Throwable throwable) {
                        miuixSlideAnimActive = false;
                        moduleLog(Log.WARN, TAG, "miuix slide deferred post-commit failed",
                                throwable);
                    }
                });
                return null;
            }
            applyMiuixSlidePostCommitFrame(animation, linear);
            return null;
        } catch (Throwable throwable) {
            miuixSlideAnimActive = false;
            moduleLog(Log.WARN, TAG, "miuix slide post-commit frame failed", throwable);
            return chain.proceed();
        }
    }

    protected Object onCrossActivitySlideDuration(XposedInterface.Chain chain)
            throws Throwable {
        if (miuixSlideAnimActive) {
            return Long.valueOf(MIUIX_SLIDE_SETTLE_DURATION_MS);
        }
        return chain.proceed();
    }

    protected void applyMiuixSlidePostCommitFrame(Object animation, float linear)
            throws Exception {
        float eased = miuixSlideSettleEase(linear);
        RectF targetClosing = (RectF) readField(animation, "targetClosingRect");
        RectF targetEntering = (RectF) readField(animation, "targetEnteringRect");
        RectF currentClosing = (RectF) readField(animation, "currentClosingRect");
        RectF currentEntering = (RectF) readField(animation, "currentEnteringRect");
        lerpRectF(miuixSlideCommitClosing, targetClosing, eased, currentClosing);
        lerpRectF(miuixSlideCommitEntering, targetEntering, eased, currentEntering);
        float enteringAlpha = miuixSlideCommitEnteringAlpha
                + (1.0f - miuixSlideCommitEnteringAlpha) * eased;
        // Analytic critically damped decay to zero from the committed alpha, seeded with
        // the release speed: leaves the commit value at the finger's dimming rate and
        // eases to rest. Decoupled from the geometry's ease-out on purpose.
        float t = linear * (MIUIX_SLIDE_SETTLE_DURATION_MS / 1000.0f);
        float decay = (float) Math.exp(-MIUIX_SLIDE_SCRIM_OMEGA * t);
        float scrimAlpha = (miuixSlideCommitScrimAlpha
                + (miuixSlideCommitScrimVelocity
                + MIUIX_SLIDE_SCRIM_OMEGA * miuixSlideCommitScrimAlpha) * t) * decay;
        applyMiuixSlideTransforms(animation, currentClosing, currentEntering,
                enteringAlpha, Math.max(0.0f, scrimAlpha));
    }

    protected void applyMiuixSlideFrame(Object animation, float progress,
                                        float enteringAlpha) throws Exception {
        RectF startClosing = (RectF) readField(animation, "startClosingRect");
        RectF targetClosing = (RectF) readField(animation, "targetClosingRect");
        RectF startEntering = (RectF) readField(animation, "startEnteringRect");
        RectF targetEntering = (RectF) readField(animation, "targetEnteringRect");
        RectF currentClosing = (RectF) readField(animation, "currentClosingRect");
        RectF currentEntering = (RectF) readField(animation, "currentEnteringRect");
        lerpRectF(startClosing, targetClosing, progress, currentClosing);
        lerpRectF(startEntering, targetEntering, progress, currentEntering);
        // Dim tracks the finger: fully dimmed at rest, gone once the top has pulled a
        // full width away.
        float scrimAlpha = MIUIX_SLIDE_SCRIM_MAX_ALPHA * (1.0f - progress);
        applyMiuixSlideTransforms(animation, currentClosing, currentEntering,
                enteringAlpha, scrimAlpha);
    }

    protected void applyMiuixSlideTransforms(Object animation, RectF closingRect,
                                             RectF enteringRect, float enteringAlpha,
                                             float scrimAlpha)
            throws Exception {
        Object closingLeash = readFieldOrNull(
                readField(animation, "closingTarget"), "leash");
        Object enteringLeash = readFieldOrNull(
                readField(animation, "enteringTarget"), "leash");
        applyCrossActivityTransform(animation, closingLeash, closingRect, 1.0f);
        applyCrossActivityTransform(animation, enteringLeash, enteringRect,
                enteringAlpha);
        Object scrim = readFieldOrNull(animation, "scrimLayer");
        if (scrim instanceof SurfaceControl && ((SurfaceControl) scrim).isValid()) {
            ((SurfaceControl.Transaction) readField(animation, "transaction"))
                    .setAlpha((SurfaceControl) scrim,
                            Math.max(0.0f, Math.min(1.0f, scrimAlpha)));
        }
        Object transaction = readField(animation, "transaction");
        FreeformColorRootAdoption adoption = freeformColorRootAdoption;
        // Fullscreen keeps only the moving top card rounded. Exact freeform is
        // normalized for both targets by the common applyTransaction hook.
        if ((adoption == null || adoption.animation != animation)
                && enteringLeash instanceof SurfaceControl
                && ((SurfaceControl) enteringLeash).isValid()) {
            invokeMethod(transaction, "setCornerRadius",
                    new Class<?>[]{SurfaceControl.class, float.class},
                    new Object[]{enteringLeash, Float.valueOf(0.0f)});
        }
        invokeAnyMethod(animation, "applyTransaction", new Object[0]);
        Object background = readField(animation, "background");
        if (background != null) {
            invokeAnyMethod(background, "customizeStatusBarAppearance",
                    new Object[]{Integer.valueOf((int) closingRect.top)});
        }
    }

    protected void applyCrossActivityTransform(Object animation, Object leash,
                                               RectF rect, float alpha)
            throws Exception {
        Method method = crossActivityApplyTransform;
        if (method == null || !method.getDeclaringClass().isInstance(animation)) {
            method = null;
            for (Class<?> current = animation.getClass(); current != null;
                 current = current.getSuperclass()) {
                for (Method candidate : current.getDeclaredMethods()) {
                    if ("applyTransform".equals(candidate.getName())
                            && candidate.getParameterCount() == 5) {
                        candidate.setAccessible(true);
                        method = candidate;
                        break;
                    }
                }
                if (method != null) {
                    break;
                }
            }
            if (method == null) {
                throw new NoSuchMethodException("applyTransform");
            }
            crossActivityApplyTransform = method;
            crossActivityNoFling = method.getParameterTypes()[4].getEnumConstants()[0];
        }
        method.invoke(animation, leash, rect, Float.valueOf(alpha), null,
                crossActivityNoFling);
    }

    protected void lerpRectF(RectF start, RectF target, float progress, RectF out) {
        out.left = start.left + (target.left - start.left) * progress;
        out.top = start.top + (target.top - start.top) * progress;
        out.right = start.right + (target.right - start.right) * progress;
        out.bottom = start.bottom + (target.bottom - start.bottom) * progress;
    }

    // Cubic ease-out: full speed at release for a continuous handoff from the finger,
    // then a decisive settle — a critically damped closed form starts at zero velocity
    // (a visible hitch at release) and crawls sub-pixel through its final stretch.
    protected float miuixSlideSettleEase(float linearProgress) {
        float remaining = 1.0f - Math.max(0.0f, Math.min(1.0f, linearProgress));
        return 1.0f - remaining * remaining * remaining;
    }

    protected void hookBackPrepareTransitionReparent(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            for (Method method : handlerClass.getDeclaredMethods()) {
                if ("handlePrepareTransition".equals(method.getName())
                        && method.getParameterCount() == 5) {
                    method.setAccessible(true);
                    registerHook(method, "systemui_back_prepare_reparent", this::correctPredictiveBackPrepareReparent);
                    moduleLog(Log.INFO, TAG,
                            "Hooked Shell predictive prepare ownership correction");
                    return;
                }
            }
            moduleLog(Log.WARN, TAG,
                    "BackTransitionHandler.handlePrepareTransition not found");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Shell predictive-back prepare reparent",
                    throwable);
        }
    }

    protected void hookBackCommitComposition(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method method = requireBackMergeAnimation(handlerClass);
            registerHook(method, "systemui_back_commit_composition", this::correctPredictiveBackCommitComposition);
            backCommitCompositionHookReady = true;
            moduleLog(Log.INFO, TAG,
                    "Hooked Shell predictive return-home commit composition");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Shell predictive return-home commit composition",
                    throwable);
        }
    }

    protected Method requireBackMergeAnimation(Class<?> handlerClass)
            throws NoSuchMethodException {
        return requireExactDeclaredMethod(handlerClass, "mergeAnimation", "void",
                IBinder.class.getName(), TransitionInfo.class.getName(),
                SurfaceControl.Transaction.class.getName(),
                SurfaceControl.Transaction.class.getName(), IBinder.class.getName(),
                "com.android.wm.shell.transition.Transitions$TransitionFinishCallback");
    }

    protected void captureFreeformColorRootCandidate(Object handler,
                                                     Object transitionToken,
                                                     Object info) throws Exception {
        Object type = readTransitionInfoType(info);
        if (!(type instanceof Number)
                || ((Number) type).intValue() != TRANSIT_PREDICTIVE_BACK
                || readField(handler, "mPrepareOpenTransition") != transitionToken
                || readField(handler, "mOpenTransitionInfo") != info) {
            return;
        }
        Object controller = readField(handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        Object apps = readField(controller, "mApps");
        if (!(navigationType instanceof Number)
                || ((Number) navigationType).intValue() != TYPE_CROSS_ACTIVITY
                || apps == null || !apps.getClass().isArray()
                || Array.getLength(apps) != 2) {
            return;
        }
        Object closingTarget = null;
        Object enteringTarget = null;
        for (int index = 0; index < 2; index++) {
            Object target = Array.get(apps, index);
            int mode = readIntFieldOrDefault(target, "mode", -1);
            if (mode == 0 && enteringTarget == null) {
                enteringTarget = target;
            } else if (mode == 1 && closingTarget == null) {
                closingTarget = target;
            } else {
                return;
            }
        }
        int taskId = readIntFieldOrDefault(closingTarget, "taskId", -1);
        if (!isExactFreeformCrossActivityPair(
                closingTarget, enteringTarget)) {
            return;
        }
        Object closingLeash = readFieldOrNull(closingTarget, "leash");
        Object enteringLeash = readFieldOrNull(enteringTarget, "leash");
        if (!(closingLeash instanceof SurfaceControl)
                || !(enteringLeash instanceof SurfaceControl)
                || closingLeash == enteringLeash
                || !((SurfaceControl) closingLeash).isValid()
                || !((SurfaceControl) enteringLeash).isValid()) {
            return;
        }
        SurfaceControl rootLeash = resolveSingleTransitionRoot(info);
        if (rootLeash == null
                || rootLeash == closingLeash || rootLeash == enteringLeash
                || !rootLeash.isValid()) {
            return;
        }
        float rootCornerRadius = resolveFreeformRootCornerRadius(handler, taskId);
        freeformColorRootCandidate.set(new FreeformColorRootCandidate(
                handler, transitionToken, info, apps,
                closingTarget, enteringTarget, rootLeash,
                (SurfaceControl) closingLeash, (SurfaceControl) enteringLeash,
                rootCornerRadius));
        moduleLog(Log.INFO, TAG,
                "Armed freeform cross-activity color-layer root adoption"
                        + ", taskId=" + taskId
                        + ", rootCornerRadius=" + rootCornerRadius);
    }

    protected Object correctPredictiveBackPrepareReparent(
            XposedInterface.Chain chain) throws Throwable {
        freeformColorRootCandidate.set(null);
        freeformColorRootAdoption = null;
        Object result = chain.proceed();
        if (!Boolean.TRUE.equals(result)) {
            return result;
        }
        try {
            captureFreeformColorRootCandidate(
                    chain.getThisObject(), chain.getArg(0), chain.getArg(1));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture freeform color-layer root candidate",
                    throwable);
        }
        try {
            Object handler = chain.getThisObject();
            Object info = chain.getArg(1);
            Object type = readTransitionInfoType(info);
            if (!(type instanceof Number)
                    || ((Number) type).intValue() != TRANSIT_PREDICTIVE_BACK) {
                return result;
            }
            if (readField(handler, "mPrepareOpenTransition") != chain.getArg(0)
                    || readField(handler, "mOpenTransitionInfo") != info) {
                return result;
            }
            Object controller = readField(handler, "this$0");
            Object navigationInfo = readField(controller, "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigationInfo);
            if (!(navigationType instanceof Number)
                    || ((Number) navigationType).intValue()
                    != TYPE_RETURN_TO_HOME) {
                return result;
            }
            Object apps = readField(controller, "mApps");
            ReturnHomeComposition composition =
                    resolveReturnHomeComposition(apps);
            if (composition == null) {
                return result;
            }
            PreparedReturnHomeShape preparedShape =
                    resolvePreparedReturnHomeShape(
                            info, composition, TRANSIT_TO_FRONT);
            Object startTransaction = chain.getArg(2);
            if (preparedShape == null
                    || !(startTransaction instanceof SurfaceControl.Transaction)) {
                return result;
            }
            SurfaceControl changeLeash = preparedShape.appLeash;
            // The stock body already accepted and retained this prepare info, but Xiaomi's
            // TO_FRONT role made it treat the departing task as another opening surface. Repair
            // the physical parent first, then normalize only the retained semantic role to the
            // AOSP CHANGE shape used by handlePrepareTransition and mergePendingTransitions.
            ((SurfaceControl.Transaction) startTransaction)
                    .reparent(changeLeash, composition.closingLeash)
                    .apply();
            if (readField(handler, "mOpenTransitionInfo") != info
                    || readField(handler, "mPrepareOpenTransition")
                    != chain.getArg(0)) {
                throw new IllegalStateException(
                        "prepared return-home ownership changed after reparent");
            }
            if (!setTransitionChangeMode(preparedShape.appChange, TRANSIT_CHANGE)) {
                throw new IllegalStateException("prepared return-home change is not framework Change");
            }
            Object normalizedModeObject = readTransitionChangeMode(
                    preparedShape.appChange);
            int normalizedMode = normalizedModeObject instanceof Number
                    ? ((Number) normalizedModeObject).intValue() : -1;
            if (normalizedMode != TRANSIT_CHANGE) {
                throw new IllegalStateException(
                        "prepared return-home role normalization was not retained"
                                + ", mode=" + normalizedMode);
            }
            moduleLog(Log.INFO, TAG,
                    "Corrected Xiaomi predictive return-home prepare role"
                            + ", taskId=" + composition.closingTaskId
                            + ", mode=" + TRANSIT_TO_FRONT + "->" + normalizedMode
                            + ", wallpaperPresent="
                            + (preparedShape.wallpaperLeash != null));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed Xiaomi predictive return-home prepare role correction",
                    throwable);
        }
        return result;
    }

    protected Object correctPredictiveBackCommitComposition(
            XposedInterface.Chain chain) throws Throwable {
        OrphanedCloseRequestCandidate orphanedCloseRequest = null;
        try {
            orphanedCloseRequest = captureOrphanedCloseRequestCandidate(chain);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to inspect predictive-back close-request ownership",
                    throwable);
        }
        ReturnHomeCommitComposition candidate = null;
        try {
            candidate = captureReturnHomeCommitComposition(chain);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to inspect predictive return-home commit composition",
                    throwable);
        }
        ReturnHomeFinishTransferCandidate finishTransfer = null;
        if (isReturnHomeFinishTransferReady()) {
            try {
                finishTransfer = captureReturnHomeFinishTransferCandidate(chain);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to inspect rejected return-home CLOSE boundary",
                        throwable);
            }
        }
        boolean finishTransferArmed = false;
        if (finishTransfer != null) {
            ReturnHomeFinishTransferCandidate nested =
                    returnHomeFinishTransferCandidate.get();
            if (nested == null) {
                returnHomeFinishTransferCandidate.set(finishTransfer);
                finishTransferArmed = true;
                moduleLog(Log.INFO, TAG,
                        "Armed atomic prepared-finish transfer"
                                + ", transitionDebugId="
                                + finishTransfer.transitionDebugId
                                + ", preparedDebugId="
                                + finishTransfer.preparedDebugId
                                + ", taskId="
                                + finishTransfer.composition.closingTaskId);
            } else {
                moduleLog(Log.WARN, TAG,
                        "Rejected nested atomic prepared-finish transfer"
                                + ", transitionDebugId="
                                + finishTransfer.transitionDebugId
                                + ", activeTransitionDebugId="
                                + nested.transitionDebugId);
            }
        }
        Object[] routedArgs = null;
        if (candidate != null) {
            try {
                Object wrappedFinishCallback =
                        wrapAcceptedReturnHomeFinishCallback(candidate);
                routedArgs = chain.getArgs().toArray();
                routedArgs[5] = wrappedFinishCallback;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Could not arm accepted return-home commit composition",
                        throwable);
            }
        }
        Object result;
        try {
            result = routedArgs == null
                    ? chain.proceed() : chain.proceed(routedArgs);
        } finally {
            if (finishTransferArmed
                    && returnHomeFinishTransferCandidate.get()
                    == finishTransfer) {
                returnHomeFinishTransferCandidate.remove();
            }
        }
        if (finishTransferArmed
                && finishTransfer.transferAttempted.get() == 2) {
            publishStandardReturnHomeCommit(
                    finishTransfer.composition.closingTaskId,
                    finishTransfer.transitionDebugId,
                    finishTransfer.controller,
                    finishTransfer.preparedFinishCallback, true);
        }
        clearOrphanedCloseRequest(orphanedCloseRequest, chain);
        if (candidate == null) {
            return result;
        }
        ReturnHomeComposition composition = candidate.composition;
        try {
            Object currentApps = readField(candidate.controller, "mApps");
            Object navigationInfo = readField(candidate.controller,
                    "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigationInfo);
            Object animationFinishCallback = readField(candidate.handler,
                    "mOnAnimationFinishCallback");
            Object currentPrepareOpen = readField(candidate.handler,
                    "mPrepareOpenTransition");
            Object currentOpenInfo = readField(candidate.handler,
                    "mOpenTransitionInfo");
            Object currentCloseRequested = readField(candidate.handler,
                    "mCloseTransitionRequested");
            boolean appsSame = currentApps == composition.appsIdentity;
            boolean callIdentitySame = chain.getArg(0) == candidate.transitionToken
                    && chain.getArg(1) == candidate.transitionInfo
                    && chain.getArg(2) == candidate.startTransaction
                    && chain.getArg(3) == candidate.finishTransaction
                    && chain.getArg(4) == candidate.mergeTarget
                    && chain.getArg(5) == candidate.finishCallback;
            boolean returnHomeStillCurrent = navigationType instanceof Number
                    && ((Number) navigationType).intValue() == TYPE_RETURN_TO_HOME;
            boolean closeStillRequested = Boolean.TRUE.equals(
                    currentCloseRequested);
            boolean prepareOpenSame = currentPrepareOpen == candidate.mergeTarget;
            boolean preparedInfoConsumed = currentOpenInfo == null;
            boolean freshFinishCallback = animationFinishCallback != null
                    && animationFinishCallback
                    != candidate.previousAnimationFinishCallback;
            boolean changeLeashValid = candidate.changeLeash.isValid();
            boolean closingLeashValid = composition.closingLeash.isValid();
            boolean openingLeashValid = composition.openingLeash.isValid();
            boolean accepted = appsSame && callIdentitySame
                    && returnHomeStillCurrent
                    && closeStillRequested && prepareOpenSame
                    && preparedInfoConsumed && freshFinishCallback
                    && changeLeashValid && closingLeashValid
                    && openingLeashValid;
            if (accepted) {
                boolean composedInStartTransaction =
                        candidate.acceptedBoundaryComposition.get() == 2;
                if (!composedInStartTransaction) {
                    moduleLog(Log.ERROR, TAG,
                            "Rejected non-atomic return-home commit composition"
                                    + ", taskId=" + composition.closingTaskId
                                    + ", boundaryPhase="
                                    + candidate.acceptedBoundaryComposition.get());
                    return result;
                }
                moduleLog(Log.INFO, TAG,
                        "Corrected accepted predictive return-home commit composition"
                                + ", taskId=" + composition.closingTaskId
                                + ", homeTaskId=" + composition.openingTaskId
                                + ", transitionType="
                                + candidate.transitionType
                                + ", changeLeash=" + candidate.changeLeash
                                + ", closingLeash="
                                + composition.closingLeash
                                + ", atomicStartTransaction=true");
                publishStandardReturnHomeCommit(
                        composition.closingTaskId,
                        readTransitionDebugId(candidate.transitionInfo),
                        candidate.controller, null, false);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed predictive return-home merge exit verification",
                    throwable);
        }
        return result;
    }

    protected static final class OrphanedCloseRequestCandidate {
        final Thread ownerThread;
        final Object handler;
        final Object controller;
        final Object preparedOpen;
        final Object preparedInfo;

        OrphanedCloseRequestCandidate(Thread ownerThread, Object handler,
                                      Object controller, Object preparedOpen,
                                      Object preparedInfo) {
            this.ownerThread = ownerThread;
            this.handler = handler;
            this.controller = controller;
            this.preparedOpen = preparedOpen;
            this.preparedInfo = preparedInfo;
        }
    }

    protected OrphanedCloseRequestCandidate
    captureOrphanedCloseRequestCandidate(XposedInterface.Chain chain)
            throws Exception {
        Thread ownerThread = Thread.currentThread();
        if (!"wmshell.main".equals(ownerThread.getName())) {
            return null;
        }
        Object handler = chain.getThisObject();
        Object controller = readField(handler, "this$0");
        Object preparedOpen = readField(handler, "mPrepareOpenTransition");
        Object preparedInfo = readField(handler, "mOpenTransitionInfo");
        Object preparedType = readTransitionInfoType(preparedInfo);
        if (controller == null
                || readField(controller, "mBackTransitionHandler") != handler
                || !Boolean.TRUE.equals(readField(
                handler, "mCloseTransitionRequested"))
                || preparedOpen == null || preparedOpen != chain.getArg(4)
                || preparedOpen == chain.getArg(0)
                || preparedInfo == null || preparedInfo == chain.getArg(1)
                || !(preparedType instanceof Number)
                || ((Number) preparedType).intValue()
                != TRANSIT_PREDICTIVE_BACK
                || readField(handler, "mFinishOpenTransaction") == null
                || readField(handler, "mFinishOpenTransitionCallback") == null
                || readField(handler, "mClosePrepareTransition") != null
                || readField(handler, "mOnAnimationFinishCallback") != null
                || readField(controller, "mApps") != null
                || readField(controller, "mActiveCallback") != null
                || !isShellReadyOnOwner(controller)) {
            return null;
        }
        return new OrphanedCloseRequestCandidate(ownerThread, handler,
                controller, preparedOpen, preparedInfo);
    }

    protected void clearOrphanedCloseRequest(
            OrphanedCloseRequestCandidate candidate,
            XposedInterface.Chain chain) {
        if (candidate == null) {
            return;
        }
        try {
            Object handler = chain.getThisObject();
            Object controller = readField(handler, "this$0");
            boolean orphaned = Thread.currentThread() == candidate.ownerThread
                    && "wmshell.main".equals(
                    candidate.ownerThread.getName())
                    && handler == candidate.handler
                    && controller == candidate.controller
                    && readField(controller, "mBackTransitionHandler") == handler
                    && chain.getArg(4) == candidate.preparedOpen
                    && Boolean.TRUE.equals(readField(
                    handler, "mCloseTransitionRequested"))
                    && readField(handler, "mPrepareOpenTransition") == null
                    && readField(handler, "mOpenTransitionInfo") == null
                    && readField(handler, "mFinishOpenTransaction") == null
                    && readField(handler, "mFinishOpenTransitionCallback") == null
                    && readField(handler, "mClosePrepareTransition") == null
                    && readField(handler, "mOnAnimationFinishCallback") == null
                    && readField(handler, "mTakeoverHandler") == null
                    && readField(controller, "mApps") == null
                    && readField(controller, "mActiveCallback") == null
                    && isShellReadyOnOwner(controller);
            if (!orphaned) {
                return;
            }
            writeField(handler, "mCloseTransitionRequested", Boolean.FALSE);
            if (!Boolean.FALSE.equals(readField(
                    handler, "mCloseTransitionRequested"))) {
                throw new IllegalStateException(
                        "predictive-back close request remained set");
            }
            moduleLog(Log.INFO, TAG,
                    "Cleared orphaned predictive-back close request"
                            + ", preparedTransitionDebugId="
                            + readTransitionDebugId(candidate.preparedInfo)
                            + ", mergeTransitionDebugId="
                            + readTransitionDebugId(chain.getArg(1)));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to clear orphaned predictive-back close request",
                    throwable);
        }
    }

    protected Object wrapAcceptedReturnHomeFinishCallback(
            ReturnHomeCommitComposition candidate) throws Exception {
        ClassLoader classLoader = candidate.handler.getClass().getClassLoader();
        Class<?> callbackClass = Class.forName(
                "com.android.wm.shell.transition.Transitions$TransitionFinishCallback",
                false, classLoader);
        if (!callbackClass.isInstance(candidate.finishCallback)) {
            throw new IllegalStateException("Unexpected transition finish callback: "
                    + shortObject(candidate.finishCallback));
        }
        return Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass},
                (proxy, method, invocationArgs) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return headlessUpdaterResult(
                                proxy, method, invocationArgs);
                    }
                    if ("onTransitionFinished".equals(method.getName())
                            && method.getParameterCount() == 1
                            && candidate.acceptedBoundaryComposition
                            .compareAndSet(0, 1)) {
                        try {
                            composeAcceptedReturnHomeCommit(candidate);
                            candidate.acceptedBoundaryComposition.set(2);
                        } catch (Throwable throwable) {
                            candidate.acceptedBoundaryComposition.set(3);
                            moduleLog(Log.WARN, TAG,
                                    "Failed accepted return-home start-transaction composition"
                                            + ", taskId="
                                            + candidate.composition.closingTaskId
                                            + ", transitionDebugId="
                                            + readTransitionDebugId(
                                            candidate.transitionInfo),
                                    throwable);
                        }
                    }
                    try {
                        return method.invoke(candidate.finishCallback,
                                invocationArgs);
                    } catch (InvocationTargetException exception) {
                        Throwable cause = exception.getCause();
                        throw cause == null ? exception : cause;
                    }
                });
    }

    protected void composeAcceptedReturnHomeCommit(
            ReturnHomeCommitComposition candidate) throws Exception {
        ReturnHomeComposition composition = candidate.composition;
        Object currentApps = readField(candidate.controller, "mApps");
        Object navigationInfo = readField(candidate.controller,
                "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        boolean exact = "wmshell.main".equals(Thread.currentThread().getName())
                && currentApps == composition.appsIdentity
                && navigationType instanceof Number
                && ((Number) navigationType).intValue()
                == TYPE_RETURN_TO_HOME
                && Boolean.TRUE.equals(readField(candidate.handler,
                "mCloseTransitionRequested"))
                && readField(candidate.handler, "mPrepareOpenTransition")
                == candidate.mergeTarget
                && readField(candidate.handler, "mOpenTransitionInfo") == null
                && readField(candidate.handler, "mOnAnimationFinishCallback")
                == candidate.previousAnimationFinishCallback
                && candidate.startTransaction
                instanceof SurfaceControl.Transaction
                && candidate.changeLeash.isValid()
                && composition.closingLeash.isValid()
                && composition.openingLeash.isValid();
        if (!exact) {
            throw new IllegalStateException(
                    "return-home ownership changed at accepted callback");
        }
        // Xiaomi's current BackTransitionHandler calls this finish callback only after its
        // commit predicates have accepted the merge and immediately before applying the same
        // start Transaction. Append the AOSP closing-parent correction at that boundary so
        // SurfaceFlinger can never present the unparented fullscreen change in between.
        ((SurfaceControl.Transaction) candidate.startTransaction).reparent(
                candidate.changeLeash, composition.closingLeash);
        moduleLog(Log.INFO, TAG,
                "Composed accepted predictive return-home commit in original start transaction"
                        + ", taskId=" + composition.closingTaskId
                        + ", homeTaskId=" + composition.openingTaskId
                        + ", transitionDebugId="
                        + readTransitionDebugId(candidate.transitionInfo)
                        + ", changeLeash=" + candidate.changeLeash
                        + ", closingLeash=" + composition.closingLeash);
    }

    protected ReturnHomeCommitComposition captureReturnHomeCommitComposition(
            XposedInterface.Chain chain) throws Exception {
        Object handler = chain.getThisObject();
        if (!Boolean.TRUE.equals(readField(handler, "mCloseTransitionRequested"))) {
            return null;
        }
        Object controller = readField(handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        if (navigationInfo == null) {
            return null;
        }
        Object navigationType = readBackNavigationType(navigationInfo);
        if (!(navigationType instanceof Number)
                || ((Number) navigationType).intValue() != TYPE_RETURN_TO_HOME) {
            return null;
        }
        Object transitionToken = chain.getArg(0);
        Object info = chain.getArg(1);
        Object startTransaction = chain.getArg(2);
        Object finishTransaction = chain.getArg(3);
        Object mergeTarget = chain.getArg(4);
        Object finishCallback = chain.getArg(5);
        Object preparedOpenInfo = readField(handler, "mOpenTransitionInfo");
        Object previousAnimationFinishCallback = readField(
                handler, "mOnAnimationFinishCallback");
        Object preparedOpenToken = readField(handler, "mPrepareOpenTransition");
        if (transitionToken == null || info == null
                || !(startTransaction instanceof SurfaceControl.Transaction)
                || !(finishTransaction instanceof SurfaceControl.Transaction)
                || mergeTarget == null || finishCallback == null
                || transitionToken == mergeTarget
                || preparedOpenToken != mergeTarget
                || preparedOpenInfo == null
                || previousAnimationFinishCallback != null) {
            return null;
        }
        Object preparedTypeObject = readTransitionInfoType(preparedOpenInfo);
        if (!(preparedTypeObject instanceof Number)
                || ((Number) preparedTypeObject).intValue()
                != TRANSIT_PREDICTIVE_BACK) {
            return null;
        }
        ReturnHomeComposition composition = resolveReturnHomeComposition(
                readField(controller, "mApps"));
        if (composition == null) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "non-standard targets");
            return null;
        }
        PreparedReturnHomeShape preparedShape =
                resolvePreparedReturnHomeShape(
                        preparedOpenInfo, composition, TRANSIT_CHANGE);
        if (preparedShape == null) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "non-standard prepared transition");
            return null;
        }
        Object transitionTypeObject = readTransitionInfoType(info);
        int transitionType = transitionTypeObject instanceof Number
                ? ((Number) transitionTypeObject).intValue() : -1;
        boolean supportedClosingType = transitionType == TRANSIT_CLOSE
                || transitionType == TRANSIT_TO_BACK;
        if (!supportedClosingType) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "unexpected transition type=" + transitionType);
            return null;
        }
        Object changesObject = readTransitionInfoChanges(info);
        if (!(changesObject instanceof List<?>)) {
            return null;
        }
        Object matchingChange = null;
        int matchingMode = -1;
        boolean backGestureAnimated = false;
        boolean elementChangePresent = false;
        int matchCount = 0;
        for (Object change : (List<?>) changesObject) {
            Object flagsObject = readTransitionChangeFlags(change);
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            if (flags == FLAG_IS_ELEMENT) {
                elementChangePresent = true;
            }
            Object taskInfo = readTransitionChangeTaskInfo(change);
            if (readIntFieldOrDefault(taskInfo, "taskId", -1)
                    != composition.closingTaskId) {
                continue;
            }
            matchCount++;
            matchingChange = change;
            Object modeObject = readTransitionChangeMode(change);
            matchingMode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            backGestureAnimated = Boolean.TRUE.equals(hasTransitionChangeFlags(
                    change, FLAG_BACK_GESTURE_ANIMATED));
        }
        if (matchCount != 1 || matchingChange == null
                || matchingMode != transitionType
                || !backGestureAnimated || elementChangePresent) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "closing change mismatch"
                            + ", taskId=" + composition.closingTaskId
                            + ", transitionType=" + transitionType
                            + ", matches=" + matchCount
                            + ", mode=" + matchingMode
                            + ", backGestureAnimated=" + backGestureAnimated
                            + ", elementChangePresent="
                            + elementChangePresent);
            return null;
        }
        Object changeLeashObject = readTransitionChangeLeash(matchingChange);
        if (!(changeLeashObject instanceof SurfaceControl)
                || !((SurfaceControl) changeLeashObject).isValid()
                || surfacesAreSame((SurfaceControl) changeLeashObject,
                composition.closingLeash)
                || surfacesAreSame((SurfaceControl) changeLeashObject,
                composition.openingLeash)
                || !surfacesAreSame((SurfaceControl) changeLeashObject,
                preparedShape.appLeash)) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "invalid or aliased change leash"
                            + ", taskId=" + composition.closingTaskId
                            + ", transitionType=" + transitionType
                            + ", mode=" + matchingMode
                            + ", changeLeash=" + shortObject(changeLeashObject));
            return null;
        }
        return new ReturnHomeCommitComposition(handler, controller, composition,
                (SurfaceControl) changeLeashObject, transitionToken, info,
                startTransaction, finishTransaction, mergeTarget,
                finishCallback, previousAnimationFinishCallback,
                transitionType);
    }

    protected ReturnHomeFinishTransferCandidate
    captureReturnHomeFinishTransferCandidate(
            XposedInterface.Chain chain) throws Exception {
        Thread ownerThread = Thread.currentThread();
        if (!isReturnHomeFinishTransferReady()
                || !"wmshell.main".equals(ownerThread.getName())) {
            return null;
        }
        Object handler = chain.getThisObject();
        if (!Boolean.TRUE.equals(readField(
                handler, "mCloseTransitionRequested"))) {
            return null;
        }
        Object controller = readField(handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        if (!(navigationType instanceof Number)
                || ((Number) navigationType).intValue()
                != TYPE_RETURN_TO_HOME) {
            return null;
        }

        Object transitionToken = chain.getArg(0);
        Object info = chain.getArg(1);
        Object startTransactionObject = chain.getArg(2);
        Object incomingFinishTransactionObject = chain.getArg(3);
        Object mergeTarget = chain.getArg(4);
        Object incomingFinishCallback = chain.getArg(5);
        Object preparedOpenInfo = readField(handler, "mOpenTransitionInfo");
        Object preparedOpenToken = readField(
                handler, "mPrepareOpenTransition");
        Object preparedFinishTransactionObject = readField(
                handler, "mFinishOpenTransaction");
        Object preparedFinishCallback = readField(
                handler, "mFinishOpenTransitionCallback");
        Object animationFinishCallback = readField(
                handler, "mOnAnimationFinishCallback");
        Object closePrepareTransition = readField(
                handler, "mClosePrepareTransition");
        Object takeoverHandler = readField(handler, "mTakeoverHandler");
        if (transitionToken == null || info == null
                || !(startTransactionObject
                instanceof SurfaceControl.Transaction)
                || !(incomingFinishTransactionObject
                instanceof SurfaceControl.Transaction)
                || mergeTarget == null || incomingFinishCallback == null
                || preparedOpenInfo == null
                || preparedOpenToken != mergeTarget
                || !(preparedFinishTransactionObject
                instanceof SurfaceControl.Transaction)
                || preparedFinishCallback == null
                || animationFinishCallback != null
                || closePrepareTransition != null
                || takeoverHandler != null
                || transitionToken == mergeTarget
                || startTransactionObject == incomingFinishTransactionObject
                || startTransactionObject == preparedFinishTransactionObject
                || incomingFinishTransactionObject
                == preparedFinishTransactionObject
                || incomingFinishCallback == preparedFinishCallback) {
            return null;
        }

        Object incomingTypeObject = readTransitionInfoType(info);
        Object preparedTypeObject = readTransitionInfoType(preparedOpenInfo);
        int incomingType = incomingTypeObject instanceof Number
                ? ((Number) incomingTypeObject).intValue() : -1;
        int preparedType = preparedTypeObject instanceof Number
                ? ((Number) preparedTypeObject).intValue() : -1;
        int transitionDebugId = readTransitionDebugId(info);
        int preparedDebugId = readTransitionDebugId(preparedOpenInfo);
        boolean supportedIncomingType = incomingType == TRANSIT_CLOSE
                || incomingType == TRANSIT_TO_BACK;
        if (!supportedIncomingType
                || preparedType != TRANSIT_PREDICTIVE_BACK
                || transitionDebugId < 0 || preparedDebugId < 0
                || transitionDebugId == preparedDebugId) {
            return null;
        }
        Object transitions = readField(handler, "mTransitions");
        Object remoteTransitionHandler = invokeAnyMethod(
                transitions, "getRemoteTransitionHandler", new Object[0]);
        Object remoteHandlerType = invokeAnyMethod(
                remoteTransitionHandler, "getTransitionType", new Object[0]);
        Object miuiTransitionInfo = invokeAnyMethod(
                info, "getMiuiTransitionInfo", new Object[0]);
        Object expectedHandlerType = invokeAnyMethod(
                miuiTransitionInfo, "getExpectHandlerType", new Object[0]);
        Object remoteCanHandle = invokeAnyMethod(
                remoteTransitionHandler, "canHandleTransition",
                new Object[]{transitionToken, info});
        if (!"com.android.wm.shell.transition.RemoteTransitionHandler".equals(
                remoteTransitionHandler.getClass().getName())
                || !(remoteHandlerType instanceof Number)
                || ((Number) remoteHandlerType).intValue() != 11
                || !(expectedHandlerType instanceof Number)
                || ((Number) expectedHandlerType).intValue() != 11
                || !Boolean.TRUE.equals(remoteCanHandle)) {
            return null;
        }

        ReturnHomeComposition composition = resolveReturnHomeComposition(
                readField(controller, "mApps"));
        if (composition == null) {
            return null;
        }
        if (!(navigationInfo instanceof BackNavigationInfo)
                || ((BackNavigationInfo) navigationInfo).getFocusedTaskId()
                != composition.closingTaskId) {
            return null;
        }
        PreparedReturnHomeShape preparedShape =
                resolvePreparedReturnHomeShape(
                        preparedOpenInfo, composition, TRANSIT_CHANGE);
        if (preparedShape == null) {
            return null;
        }
        Rect closingBounds = preparedShape.closingBounds;
        Rect openingBounds = preparedShape.openingBounds;

        Object changesObject = readTransitionInfoChanges(info);
        if (!(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 3) {
            return null;
        }
        Object elementChange = null;
        Object appChange = null;
        SurfaceControl homeLeash = null;
        SurfaceControl elementLeash = null;
        SurfaceControl appLeash = null;
        Rect elementEndBounds = null;
        int capturedAppFlags = Integer.MIN_VALUE;
        int elementStartDisplayId = Integer.MIN_VALUE;
        int elementEndDisplayId = Integer.MIN_VALUE;
        for (Object change : (List<?>) changesObject) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object taskInfo = readTransitionChangeTaskInfo(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            int taskId = readIntFieldOrDefault(taskInfo, "taskId", -1);
            int startDisplayId = startDisplayObject instanceof Number
                    ? ((Number) startDisplayObject).intValue() : -2;
            int endDisplayId = endDisplayObject instanceof Number
                    ? ((Number) endDisplayObject).intValue() : -2;
            if (!(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || !(startBoundsObject instanceof Rect)
                    || !(endBoundsObject instanceof Rect)) {
                return null;
            }
            Rect startBounds = (Rect) startBoundsObject;
            Rect endBounds = (Rect) endBoundsObject;
            if (taskId == composition.openingTaskId
                    && homeLeash == null) {
                if (mode != TRANSIT_TO_FRONT
                        || flags != XIAOMI_ELEMENT_HOME_CHANGE_FLAGS
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_HOME
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(openingBounds)
                        || !endBounds.equals(openingBounds)) {
                    return null;
                }
                homeLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskId == composition.closingTaskId
                    && appChange == null) {
                boolean appFlags = flags
                        == FLAG_BACK_GESTURE_ANIMATED
                        || flags == (FLAG_BACK_GESTURE_ANIMATED
                        | FLAG_DISPLAY_CHANGE);
                if (mode != incomingType || !appFlags
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_STANDARD
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(closingBounds)
                        || !endBounds.equals(closingBounds)) {
                    return null;
                }
                appChange = change;
                appLeash = (SurfaceControl) leashObject;
                capturedAppFlags = flags;
                continue;
            }
            if (taskInfo == null && elementChange == null
                    && mode == incomingType
                    && flags == FLAG_IS_ELEMENT
                    && startBounds.equals(closingBounds)
                    && !endBounds.isEmpty()
                    && !endBounds.equals(closingBounds)
                    && startDisplayId == endDisplayId
                    && (startDisplayId == -1
                    || startDisplayId == composition.displayId)
                    && closingBounds.contains(endBounds)) {
                elementChange = change;
                elementLeash = (SurfaceControl) leashObject;
                elementEndBounds = new Rect(endBounds);
                elementStartDisplayId = startDisplayId;
                elementEndDisplayId = endDisplayId;
                continue;
            }
            return null;
        }
        if (elementChange == null || appChange == null || homeLeash == null
                || elementLeash == null || appLeash == null
                || elementEndBounds == null
                || capturedAppFlags == Integer.MIN_VALUE
                || elementStartDisplayId == Integer.MIN_VALUE
                || elementEndDisplayId == Integer.MIN_VALUE
                || surfacesAreSame(homeLeash, elementLeash)
                || surfacesAreSame(homeLeash, appLeash)
                || surfacesAreSame(elementLeash, appLeash)
                || surfacesAreSame(appLeash,
                composition.closingLeash)
                || surfacesAreSame(homeLeash,
                composition.openingLeash)
                || surfacesAreSame(elementLeash,
                composition.closingLeash)
                || surfacesAreSame(elementLeash,
                composition.openingLeash)) {
            return null;
        }

        if (!surfacesAreSame(preparedShape.appLeash, appLeash)
                || !surfacesAreSame(preparedShape.homeLeash, homeLeash)
                || (preparedShape.wallpaperLeash != null
                && surfacesAreSame(
                preparedShape.wallpaperLeash, elementLeash))) {
            return null;
        }

        return new ReturnHomeFinishTransferCandidate(
                handler, controller, ownerThread, transitions,
                remoteTransitionHandler, composition,
                transitionToken, info,
                mergeTarget,
                (SurfaceControl.Transaction) startTransactionObject,
                preparedOpenInfo,
                (SurfaceControl.Transaction) preparedFinishTransactionObject,
                preparedFinishCallback, elementChange, appChange,
                homeLeash, elementLeash, appLeash, closingBounds,
                elementEndBounds, incomingType, capturedAppFlags,
                elementStartDisplayId,
                elementEndDisplayId, transitionDebugId, preparedDebugId);
    }

    protected static final class PreparedReturnHomeShape {
        public final Object appChange;
        public final SurfaceControl appLeash;
        public final SurfaceControl homeLeash;
        public final SurfaceControl wallpaperLeash;
        public final Rect closingBounds;
        public final Rect openingBounds;

        PreparedReturnHomeShape(
                Object appChange, SurfaceControl appLeash,
                SurfaceControl homeLeash, SurfaceControl wallpaperLeash,
                Rect closingBounds, Rect openingBounds) {
            this.appChange = appChange;
            this.appLeash = appLeash;
            this.homeLeash = homeLeash;
            this.wallpaperLeash = wallpaperLeash;
            this.closingBounds = closingBounds;
            this.openingBounds = openingBounds;
        }
    }

    protected PreparedReturnHomeShape resolvePreparedReturnHomeShape(
            Object info, ReturnHomeComposition composition,
            int expectedAppMode) throws Exception {
        Object typeObject = readTransitionInfoType(info);
        if (!(typeObject instanceof Number)
                || ((Number) typeObject).intValue()
                != TRANSIT_PREDICTIVE_BACK
                || (expectedAppMode != TRANSIT_TO_FRONT
                && expectedAppMode != TRANSIT_CHANGE)) {
            return null;
        }
        Rect closingBounds = resolveExactRemoteTargetTransitionBounds(
                composition.closingTarget);
        Rect openingBounds = resolveExactRemoteTargetTransitionBounds(
                composition.openingTarget);
        if (!closingBounds.equals(openingBounds)) {
            return null;
        }
        Object changesObject = readTransitionInfoChanges(info);
        if (!(changesObject instanceof List<?>)) {
            return null;
        }
        List<?> changes = (List<?>) changesObject;
        int changeCount = changes.size();
        if (changeCount != 2 && changeCount != 3) {
            return null;
        }
        boolean wallpaperExpected = changeCount == 3;
        // The exact two-change prepared shape omits SHOW_WALLPAPER on Home.
        int expectedHomeFlags = wallpaperExpected
                ? XIAOMI_PREPARED_HOME_CHANGE_FLAGS
                : XIAOMI_PREPARED_HOME_NO_WALLPAPER_CHANGE_FLAGS;
        Object appChange = null;
        SurfaceControl appLeash = null;
        SurfaceControl homeLeash = null;
        SurfaceControl wallpaperLeash = null;
        for (Object change : changes) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object taskInfo = readTransitionChangeTaskInfo(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            int taskId = readIntFieldOrDefault(taskInfo, "taskId", -1);
            int startDisplayId = startDisplayObject instanceof Number
                    ? ((Number) startDisplayObject).intValue() : -2;
            int endDisplayId = endDisplayObject instanceof Number
                    ? ((Number) endDisplayObject).intValue() : -2;
            if (!(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || !(startBoundsObject instanceof Rect)
                    || !(endBoundsObject instanceof Rect)) {
                return null;
            }
            Rect startBounds = (Rect) startBoundsObject;
            Rect endBounds = (Rect) endBoundsObject;
            if (taskId == composition.closingTaskId
                    && appLeash == null) {
                boolean appFlags = flags
                        == FLAG_BACK_GESTURE_ANIMATED
                        || flags == (FLAG_BACK_GESTURE_ANIMATED
                        | FLAG_DISPLAY_CHANGE);
                if (mode != expectedAppMode || !appFlags
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_STANDARD
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(closingBounds)
                        || !endBounds.equals(closingBounds)) {
                    return null;
                }
                appChange = change;
                appLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskId == composition.openingTaskId
                    && homeLeash == null) {
                if (mode != TRANSIT_TO_FRONT
                        || flags != expectedHomeFlags
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_HOME
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(openingBounds)
                        || !endBounds.equals(openingBounds)) {
                    return null;
                }
                homeLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskInfo == null && wallpaperLeash == null
                    && mode == TRANSIT_TO_FRONT
                    && flags == FLAG_IS_WALLPAPER
                    && startBounds.equals(closingBounds)
                    && endBounds.equals(closingBounds)) {
                wallpaperLeash = (SurfaceControl) leashObject;
                continue;
            }
            return null;
        }
        boolean wallpaperMatches = wallpaperExpected
                ? wallpaperLeash != null
                && !surfacesAreSame(wallpaperLeash, appLeash)
                && !surfacesAreSame(wallpaperLeash, homeLeash)
                : wallpaperLeash == null;
        if (appChange == null || appLeash == null || homeLeash == null
                || !wallpaperMatches
                || surfacesAreSame(appLeash, homeLeash)
                || surfacesAreSame(appLeash, composition.closingLeash)
                || surfacesAreSame(appLeash, composition.openingLeash)
                || surfacesAreSame(homeLeash, composition.closingLeash)
                || surfacesAreSame(homeLeash, composition.openingLeash)
                || (wallpaperLeash != null
                && (surfacesAreSame(
                wallpaperLeash, composition.closingLeash)
                || surfacesAreSame(
                wallpaperLeash, composition.openingLeash)))) {
            return null;
        }
        return new PreparedReturnHomeShape(
                appChange, appLeash, homeLeash, wallpaperLeash,
                new Rect(closingBounds), new Rect(openingBounds));
    }

    protected Rect resolveExactRemoteTargetTransitionBounds(Object target)
            throws Exception {
        Object startBoundsObject = readField(target, "startBounds");
        Object sourceBoundsObject = readField(
                target, "sourceContainerBounds");
        if (!(startBoundsObject instanceof Rect)
                || !(sourceBoundsObject instanceof Rect)) {
            throw new IllegalStateException(
                    "RemoteAnimationTarget transition bounds unavailable");
        }
        Rect startBounds = (Rect) startBoundsObject;
        Rect sourceBounds = (Rect) sourceBoundsObject;
        if (startBounds.isEmpty() || !startBounds.equals(sourceBounds)) {
            throw new IllegalStateException(
                    "RemoteAnimationTarget transition bounds mismatch"
                            + ", start=" + startBounds
                            + ", source=" + sourceBounds);
        }
        return new Rect(startBounds);
    }

    protected boolean isExactReturnHomeFinishTransferPostShape(
            ReturnHomeFinishTransferCandidate candidate) throws Exception {
        Object typeObject = readTransitionInfoType(candidate.transitionInfo);
        Object changesObject = readTransitionInfoChanges(candidate.transitionInfo);
        if (!(typeObject instanceof Number)
                || ((Number) typeObject).intValue()
                != candidate.transitionType
                || !(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 2) {
            return false;
        }
        boolean elementMatched = false;
        boolean appMatched = false;
        for (Object change : (List<?>) changesObject) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object taskInfo = readTransitionChangeTaskInfo(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            int startDisplayId = startDisplayObject instanceof Number
                    ? ((Number) startDisplayObject).intValue() : -2;
            int endDisplayId = endDisplayObject instanceof Number
                    ? ((Number) endDisplayObject).intValue() : -2;
            if (!(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || !(startBoundsObject instanceof Rect)
                    || !(endBoundsObject instanceof Rect)) {
                return false;
            }
            if (change == candidate.elementChange) {
                if (elementMatched || taskInfo != null
                        || mode != candidate.transitionType
                        || flags != FLAG_IS_ELEMENT
                        || startDisplayId
                        != candidate.elementStartDisplayId
                        || endDisplayId != candidate.elementEndDisplayId
                        || !candidate.fullscreenBounds.equals(
                        startBoundsObject)
                        || !candidate.elementEndBounds.equals(
                        endBoundsObject)
                        || !surfacesAreSame(
                        (SurfaceControl) leashObject,
                        candidate.elementLeash)) {
                    return false;
                }
                elementMatched = true;
                continue;
            }
            if (change == candidate.appChange) {
                if (appMatched || taskInfo == null
                        || mode != candidate.transitionType
                        || flags != candidate.appFlags
                        || readIntFieldOrDefault(
                        taskInfo, "taskId", -1)
                        != candidate.composition.closingTaskId
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != candidate.composition.displayId
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_STANDARD
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || startDisplayId
                        != candidate.composition.displayId
                        || endDisplayId
                        != candidate.composition.displayId
                        || !candidate.fullscreenBounds.equals(
                        startBoundsObject)
                        || !candidate.fullscreenBounds.equals(
                        endBoundsObject)
                        || !surfacesAreSame(
                        (SurfaceControl) leashObject,
                        candidate.appLeash)) {
                    return false;
                }
                appMatched = true;
                continue;
            }
            return false;
        }
        return elementMatched && appMatched
                && candidate.homeLeash.isValid()
                && candidate.elementLeash.isValid()
                && candidate.appLeash.isValid()
                && candidate.composition.closingLeash.isValid()
                && candidate.composition.openingLeash.isValid();
    }

    protected Object transferReturnHomeFinishIntoCloseStart(
            XposedInterface.Chain chain) throws Throwable {
        ReturnHomeFinishTransferCandidate candidate =
                returnHomeFinishTransferCandidate.get();
        if (candidate == null) {
            return chain.proceed();
        }
        boolean exact = false;
        boolean transferred = false;
        try {
            Object handler = chain.getThisObject();
            Object navigationInfo = readField(
                    candidate.controller, "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigationInfo);
            Object focusedTaskId = navigationInfo instanceof BackNavigationInfo
                    ? ((BackNavigationInfo) navigationInfo).getFocusedTaskId() : null;
            Object transitions = readField(handler, "mTransitions");
            Object remoteTransitionHandler = invokeAnyMethod(
                    transitions, "getRemoteTransitionHandler", new Object[0]);
            Object remoteHandlerType = invokeAnyMethod(
                    remoteTransitionHandler, "getTransitionType", new Object[0]);
            Object miuiTransitionInfo = invokeAnyMethod(
                    candidate.transitionInfo,
                    "getMiuiTransitionInfo", new Object[0]);
            Object expectedHandlerType = invokeAnyMethod(
                    miuiTransitionInfo,
                    "getExpectHandlerType", new Object[0]);
            Object remoteCanHandle = invokeAnyMethod(
                    remoteTransitionHandler, "canHandleTransition",
                    new Object[]{candidate.transitionToken,
                            candidate.transitionInfo});
            exact = isReturnHomeFinishTransferReady()
                    && Thread.currentThread() == candidate.ownerThread
                    && "wmshell.main".equals(
                    Thread.currentThread().getName())
                    && handler == candidate.handler
                    && chain.getExecutable().getParameterCount() == 0
                    && Boolean.TRUE.equals(readField(
                    handler, "mCloseTransitionRequested"))
                    && readField(handler, "mOpenTransitionInfo") == null
                    && readField(handler, "mPrepareOpenTransition")
                    == candidate.mergeTarget
                    && readField(handler, "mFinishOpenTransaction")
                    == candidate.preparedFinishTransaction
                    && readField(handler, "mFinishOpenTransitionCallback")
                    == candidate.preparedFinishCallback
                    && readField(handler, "mOnAnimationFinishCallback") == null
                    && readField(handler, "mClosePrepareTransition") == null
                    && readField(handler, "mTakeoverHandler") == null
                    && transitions == candidate.transitions
                    && remoteTransitionHandler
                    == candidate.remoteTransitionHandler
                    && remoteHandlerType instanceof Number
                    && ((Number) remoteHandlerType).intValue() == 11
                    && expectedHandlerType instanceof Number
                    && ((Number) expectedHandlerType).intValue() == 11
                    && Boolean.TRUE.equals(remoteCanHandle)
                    && readField(candidate.controller, "mApps")
                    == candidate.composition.appsIdentity
                    && navigationType instanceof Number
                    && ((Number) navigationType).intValue()
                    == TYPE_RETURN_TO_HOME
                    && focusedTaskId instanceof Number
                    && ((Number) focusedTaskId).intValue()
                    == candidate.composition.closingTaskId
                    && readTransitionDebugId(candidate.transitionInfo)
                    == candidate.transitionDebugId
                    && readTransitionDebugId(candidate.preparedOpenInfo)
                    == candidate.preparedDebugId
                    && isExactReturnHomeFinishTransferPostShape(candidate);
            boolean firstAttempt = candidate.transferAttempted.compareAndSet(0, 1);
            if (!exact || !firstAttempt) {
                moduleLog(Log.WARN, TAG,
                        "Rejected prepared-finish atomic transfer at apply boundary"
                                + ", exact=" + exact
                                + ", transitionDebugId="
                                + candidate.transitionDebugId
                                + ", preparedDebugId="
                                + candidate.preparedDebugId);
                return chain.proceed();
            }

            // BackTransitionHandler is about to apply the prepared transition's finish
            // transaction and then release it. Move those operations into the exact incoming
            // native start transaction first. The original method applies the now-empty donor,
            // while MiuiHome appends its task reparent/geometry to the same incoming transaction
            // before the transaction is finally applied. This removes the compositor-visible
            // gap without changing either native animation's surfaces or geometry.
            candidate.startTransaction.merge(
                    candidate.preparedFinishTransaction);
            transferred = true;
            moduleLog(Log.INFO, TAG,
                    "Transferred prepared finish into Xiaomi native start transaction"
                            + ", transitionDebugId="
                            + candidate.transitionDebugId
                            + ", preparedDebugId="
                            + candidate.preparedDebugId
                            + ", transitionType="
                            + candidate.transitionType
                            + ", taskId="
                            + candidate.composition.closingTaskId);
        } catch (Throwable throwable) {
            candidate.transferAttempted.set(1);
            moduleLog(Log.WARN, TAG,
                    "Failed prepared-finish atomic transfer"
                            + ", exact=" + exact
                            + ", transitionDebugId="
                            + candidate.transitionDebugId
                            + ", preparedDebugId="
                            + candidate.preparedDebugId,
                    throwable);
        }
        Object result = chain.proceed();
        if (transferred) {
            candidate.transferAttempted.compareAndSet(1, 2);
        }
        return result;
    }

    protected void hookBackFinishOpenAtomicTransfer(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method applyFinishOpen = handlerClass.getDeclaredMethod(
                    "applyFinishOpenTransition");
            applyFinishOpen.setAccessible(true);
            registerHook(applyFinishOpen, "systemui_back_finish_open_atomic", this::transferReturnHomeFinishIntoCloseStart);
            backFinishOpenAtomicHookReady = true;
            boolean callerDeoptimized =
                    deoptimizeBackFinishOpenCaller(classLoader);
            moduleLog(isReturnHomeFinishTransferReady() ? Log.INFO : Log.WARN, TAG,
                    "Hooked Shell prepared-finish atomic transfer"
                            + ", outerHook="
                            + backCommitCompositionHookReady
                            + ", nestedHook="
                            + backFinishOpenAtomicHookReady
                            + ", mergeCallerDeoptimized="
                            + callerDeoptimized
                            + ", ready="
                            + isReturnHomeFinishTransferReady());
        } catch (Throwable throwable) {
            backFinishOpenAtomicHookReady = false;
            backFinishOpenCallerDeoptimized = false;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Shell prepared-finish atomic transfer",
                    throwable);
        }
    }

    protected boolean deoptimizeBackFinishOpenCaller(ClassLoader classLoader) {
        backFinishOpenCallerDeoptimized = false;
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method mergeAnimation = requireBackMergeAnimation(handlerClass);
            backFinishOpenCallerDeoptimized = deoptimize(mergeAnimation);
            moduleLog(backFinishOpenCallerDeoptimized ? Log.INFO : Log.WARN,
                    TAG, "Deoptimized exact BackTransitionHandler.mergeAnimation"
                            + ", success="
                            + backFinishOpenCallerDeoptimized);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to deoptimize BackTransitionHandler.mergeAnimation"
                            + " for finish/start atomicity",
                    throwable);
        }
        return backFinishOpenCallerDeoptimized;
    }

    protected boolean isReturnHomeFinishTransferReady() {
        return backCommitCompositionHookReady
                && backFinishOpenAtomicHookReady
                && backFinishOpenCallerDeoptimized;
    }

    protected ReturnHomeComposition resolveReturnHomeComposition(Object apps)
            throws Exception {
        if (apps == null || !apps.getClass().isArray()
                || Array.getLength(apps) != 2) {
            return null;
        }
        Object closingTarget = null;
        Object openingTarget = null;
        for (int i = 0; i < 2; i++) {
            Object target = Array.get(apps, i);
            int mode = readIntFieldOrDefault(target, "mode", -1);
            if (mode == 1 && closingTarget == null) {
                closingTarget = target;
            } else if (mode == 0 && openingTarget == null) {
                openingTarget = target;
            } else {
                return null;
            }
        }
        if (closingTarget == null || openingTarget == null
                || Boolean.TRUE.equals(readField(closingTarget, "isElement"))
                || Boolean.TRUE.equals(readField(openingTarget, "isElement"))
                || resolveRemoteTargetActivityType(closingTarget)
                != ACTIVITY_TYPE_STANDARD
                || resolveRemoteTargetActivityType(openingTarget)
                != ACTIVITY_TYPE_HOME
                || resolveRemoteTargetWindowingMode(closingTarget)
                != WINDOWING_MODE_FULLSCREEN
                || resolveRemoteTargetWindowingMode(openingTarget)
                != WINDOWING_MODE_FULLSCREEN) {
            return null;
        }
        int closingTaskId = readIntFieldOrDefault(
                closingTarget, "taskId", -1);
        int openingTaskId = readIntFieldOrDefault(
                openingTarget, "taskId", -1);
        Object closingTaskInfo = readField(closingTarget, "taskInfo");
        Object openingTaskInfo = readField(openingTarget, "taskInfo");
        int closingDisplayId = readIntFieldOrDefault(
                closingTaskInfo, "displayId", -1);
        int openingDisplayId = readIntFieldOrDefault(
                openingTaskInfo, "displayId", -1);
        Object closingLeashObject = readField(closingTarget, "leash");
        Object openingLeashObject = readField(openingTarget, "leash");
        if (closingTaskId < 0 || openingTaskId < 0
                || closingTaskId == openingTaskId
                || closingDisplayId < 0 || closingDisplayId != openingDisplayId
                || readIntFieldOrDefault(closingTaskInfo, "taskId", -1)
                != closingTaskId
                || readIntFieldOrDefault(openingTaskInfo, "taskId", -1)
                != openingTaskId
                || !(closingLeashObject instanceof SurfaceControl)
                || !(openingLeashObject instanceof SurfaceControl)) {
            return null;
        }
        SurfaceControl closingLeash = (SurfaceControl) closingLeashObject;
        SurfaceControl openingLeash = (SurfaceControl) openingLeashObject;
        if (!closingLeash.isValid() || !openingLeash.isValid()
                || surfacesAreSame(closingLeash, openingLeash)) {
            return null;
        }
        return new ReturnHomeComposition(apps, closingTarget, openingTarget,
                closingLeash, openingLeash, closingTaskId, openingTaskId,
                closingDisplayId);
    }

    protected int resolveRemoteTargetActivityType(Object target) throws Exception {
        Object windowConfiguration = readField(target, "windowConfiguration");
        Object activityType = invokeAnyMethod(
                windowConfiguration, "getActivityType", new Object[0]);
        return activityType instanceof Number
                ? ((Number) activityType).intValue() : -1;
    }

    protected int resolveRemoteTargetWindowingMode(Object target) throws Exception {
        Object windowConfiguration = readField(target, "windowConfiguration");
        Object windowingMode = invokeAnyMethod(
                windowConfiguration, "getWindowingMode", new Object[0]);
        return windowingMode instanceof Number
                ? ((Number) windowingMode).intValue() : -1;
    }

    protected void hookShellAnimationFinished(Class<?> controllerClass, String methodName,
                                              String hookId, boolean optional)
            throws NoSuchMethodException {
        try {
            Method method = controllerClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            registerHook(method, hookId, "finishBackAnimation".equals(methodName)
                            ? this::onShellAnimationFinished
                            : this::proceedShellAnimationLifecycle);
        } catch (NoSuchMethodException exception) {
            if (!optional) {
                throw exception;
            }
            moduleLog(Log.INFO, TAG, "Optional Shell method unavailable: " + methodName);
        }
    }

    protected void hookBackNavigationInfoReceived(Class<?> controllerClass)
            throws NoSuchMethodException {
        Method method = controllerClass.getDeclaredMethod(
                "onBackNavigationInfoReceived",
                BackNavigationInfo.class, BackTouchTracker.class);
        method.setAccessible(true);
        registerHook(method, "shell_back_onBackNavigationInfoReceived", this::onBackNavigationInfoReceived);
    }

    protected Object onBackNavigationInfoReceived(XposedInterface.Chain chain)
            throws Throwable {
        ensureAospBackAnimations(chain.getThisObject(), "beforeNavigationInfo");
        forceSystemUiCallbackProgress(chain.getArg(0));
        Object result = chain.proceed();
        logBackNavigationInfo(chain.getArg(0));
        return result;
    }

    protected Object proceedShellAnimationLifecycle(
            XposedInterface.Chain chain) throws Throwable {
        return chain.proceed();
    }

    protected Object onShellAnimationFinished(XposedInterface.Chain chain) throws Throwable {
        Object controller = chain.getThisObject();
        if (preparedBackTransitionHold.get() == null) {
            preparedBackTargetArrival.set(null);
        }
        List<Runnable> completions = new ArrayList<>();
        try {
            Object currentTracker = readField(controller, "mCurrentTracker");
            Object queuedTracker = readField(controller, "mQueuedTracker");
            Object navigation = readField(controller, "mBackNavigationInfo");
            Object transitionHandler = readField(controller,
                    "mBackTransitionHandler");
            Object finishCallback = readField(transitionHandler,
                    "mOnAnimationFinishCallback");
            for (NativeBackInputMonitor monitor
                    : new ArrayList<>(nativeInputMonitors.values())) {
                Runnable completion = monitor.captureShellAnimationCompletion(
                        controller, currentTracker, queuedTracker,
                        navigation, finishCallback,
                        chain.getExecutable().getName());
                if (completion != null) {
                    completions.add(completion);
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture fixed Shell completion identity",
                    throwable);
        }
        Object result = chain.proceed();
        if ("finishBackAnimation".equals(chain.getExecutable().getName())) {
            try {
                Object transitionHandler = readField(controller,
                        "mBackTransitionHandler");
                moduleLog(Log.INFO, TAG, "Completed stock Shell back-animation cleanup"
                        + ", postCommit=" + readField(controller,
                        "mPostCommitAnimationInProgress")
                        + ", navigation=" + shortObject(readField(controller,
                        "mBackNavigationInfo"))
                        + ", finishedCallback=" + shortObject(readField(controller,
                        "mBackAnimationFinishedCallback"))
                        + ", currentTracker=" + shortObject(readField(controller,
                        "mCurrentTracker"))
                        + ", queuedTracker=" + shortObject(readField(controller,
                        "mQueuedTracker"))
                        + ", closeRequested=" + readField(
                        transitionHandler, "mCloseTransitionRequested")
                        + ", prepareOpen=" + shortObject(readField(
                        transitionHandler, "mPrepareOpenTransition"))
                        + ", prepareClose=" + shortObject(readField(
                        transitionHandler, "mClosePrepareTransition")));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to inspect completed Shell back-animation cleanup",
                        throwable);
            }
        }
        for (Runnable completion : completions) {
            try {
                completion.run();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to publish fixed Shell completion",
                        throwable);
            }
        }
        return result;
    }

}
