// SPDX-License-Identifier: Apache-2.0
package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackEvent;
import android.window.TransitionInfo;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Method;
import com.takekazex.hypertweak.hook.rules.backgesture.PredictiveBackPreferences;
import io.github.libxposed.api.XposedInterface;

/** Optional Android 17 preview; native Shell owns navigation and the final CLOSE. */
public abstract class SystemUiReturnHomePreviewRuntime extends SystemUiAnimationRuntime {
    protected abstract String systemUiInputArbiterStateAction();
    protected static final int ACTIVITY_TYPE_HOME_VALUE = 2;

    protected void hookReturnHomeCloseRejection(ClassLoader classLoader) {
        if (!isReturnHomePreviewSupported()) {
            return;
        }
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method shouldCancel = handlerClass.getDeclaredMethod(
                    "shouldCancelAnimation", TransitionInfo.class);
            shouldCancel.setAccessible(true);
            registerHook(shouldCancel, "systemui_return_home_close_rejection", this::forceReturnHomeCloseRejection);
            moduleLog(Log.INFO, TAG, "Hooked RETURN_TO_HOME CLOSE rejection");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to hook RETURN_TO_HOME CLOSE rejection", throwable);
        }
    }

    protected void hookReturnHomeHandoff(ClassLoader classLoader,
            boolean installCapture, boolean installTransfer, boolean installCloseLeash,
            boolean installWillFinish) {
        if (!isReturnHomePreviewSupported()) {
            return;
        }
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            if (installCapture) {
                Method merge = requireBackMergeAnimation(handlerClass);
                merge.setAccessible(true);
                registerHook(merge, "systemui_return_home_merge_capture", this::captureReturnHomeMerge);
            }
            if (installTransfer) {
                Method finishOpen = handlerClass.getDeclaredMethod("applyFinishOpenTransition");
                finishOpen.setAccessible(true);
                registerHook(finishOpen, "systemui_return_home_finish_transfer", this::transferReturnHomePreviewFinish);
            }
            if (installWillFinish) {
                Class<?> recentsController = Class.forName(
                        "com.android.wm.shell.recents.RecentsTransitionHandler$RecentsController",
                        false, classLoader);
                Method willFinish = recentsController.getDeclaredMethod(
                        "setWillFinishToHome", boolean.class);
                willFinish.setAccessible(true);
                registerHook(willFinish, "systemui_return_home_will_finish", this::onReturnHomeWillFinishToHome);
            }
            if (installCloseLeash) {
                Class<?> transitionUtil = Class.forName(
                        "com.android.wm.shell.shared.TransitionUtil", false, classLoader);
                Method createLeash = transitionUtil.getDeclaredMethod("createLeash",
                        TransitionInfo.class, TransitionInfo.Change.class, int.class,
                        SurfaceControl.Transaction.class);
                registerHook(createLeash, "systemui_return_home_close_leash", this::placeReturnHomeCloseLeash);
            }
            moduleLog(Log.INFO, TAG, "Hooked RETURN_TO_HOME handoff"
                    + ", capture=" + installCapture + ", transfer=" + installTransfer
                    + ", closeLeash=" + installCloseLeash
                    + ", willFinish=" + installWillFinish);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to hook RETURN_TO_HOME handoff", throwable);
        }
    }

    protected Object forceReturnHomeCloseRejection(
            XposedInterface.Chain chain) throws Throwable {
        try {
            Object handler = chain.getThisObject();
            Object info = chain.getArg(0);
            Object[] merge = returnHomeMergeArgs.get();
            Object controller = readField(handler, "this$0");
            Object navigationType = readBackNavigationType(
                    readField(controller, "mBackNavigationInfo"));
            Object type = readTransitionInfoType(info);
            // Only the CLOSE of the exact committed preview task leaves Shell's merge.
            if (merge != null && merge[0] == info
                    && navigationType instanceof Number
                    && ((Number) navigationType).intValue() == TYPE_RETURN_TO_HOME
                    && Boolean.TRUE.equals(readField(handler, "mCloseTransitionRequested"))
                    && type instanceof Number
                    && (((Number) type).intValue() == TRANSIT_TO_BACK
                    || ((Number) type).intValue() == TRANSIT_CLOSE)
                    && findReturnHomeClosingChange((TransitionInfo) info,
                            ((ReturnHomeHandoff) merge[2]).taskId) != null) {
                moduleLog(Log.INFO, TAG, "Rejected RETURN_TO_HOME CLOSE merge for Xiaomi"
                        + ", transitionDebugId=" + readTransitionDebugId(info)
                        + ", type=" + type);
                return Boolean.TRUE;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "RETURN_TO_HOME CLOSE rejection check failed", throwable);
        }
        return chain.proceed();
    }

    protected static final float RETURN_HOME_PREVIEW_MIN_SCALE = 0.85f;

    protected static final float RETURN_HOME_PREVIEW_EDGE_MARGIN_DP = 8f;

    protected volatile Object returnHomePreviewAnimation;

    protected final RectF returnHomePreviewRect = new RectF();

    protected static final String EXTRA_RETURN_HOME_HANDOFF = "hypertweak_return_home_handoff";

    protected static final long RETURN_HOME_HANDOFF_TIMEOUT_MS = 1500L;

    protected static final class ReturnHomeHandoff {
        final Object animation;
        final int taskId;
        final RectF rect;
        final Rect startTaskRect;
        final float cornerRadius;
        final long sequence;
        boolean transferred;
        TransitionInfo closeInfo;
        boolean placed;
        SurfaceControl homeWrapper;
        boolean backgroundReleased;
        final Looper looper = Looper.myLooper();

        ReturnHomeHandoff(Object animation, int taskId, RectF rect, Rect startTaskRect,
                float cornerRadius, long sequence) {
            this.animation = animation;
            this.taskId = taskId;
            this.rect = rect;
            this.startTaskRect = startTaskRect;
            this.cornerRadius = cornerRadius;
            this.sequence = sequence;
        }
    }

    protected volatile ReturnHomeHandoff returnHomeHandoff;

    protected final java.util.concurrent.atomic.AtomicLong returnHomeHandoffSequence =
            new java.util.concurrent.atomic.AtomicLong();

    protected final ThreadLocal<Object[]> returnHomeMergeArgs = new ThreadLocal<>();

    protected boolean isReturnHomePreviewSupported() {
        return Build.VERSION.SDK_INT == 37;
    }

    @Override
    protected boolean isReturnHomePreviewEnabled() {
        return com.takekazex.hypertweak.hook.Preferences.INSTANCE.backGestureEnabled()
                && isReturnHomePreviewSupported() && readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_RETURN_HOME_ICON_PREVIEW,
                PredictiveBackPreferences.DEFAULT_RETURN_HOME_ICON_PREVIEW);
    }

    @Override
    protected boolean ensureReturnHomePreviewRunner(Object definitions, Object crossTask) {
        if (!(definitions instanceof android.util.SparseArray<?>) || crossTask == null
                || !isReturnHomePreviewSupported()) {
            // Android 16: type 1 belongs to the Java launcher's own remote runner.
            return false;
        }
        if (!isReturnHomePreviewEnabled()) {
            // Withdraw only a preview runner this module installed in any generation.
            try {
                Object existing = ((android.util.SparseArray<?>) definitions)
                        .get(TYPE_RETURN_TO_HOME);
                Object owner = existing == null ? null
                        : readField(readField(existing, "mCallback"), "this$0");
                if (owner != null && owner != crossTask
                        && owner.getClass() == crossTask.getClass()) {
                    invokeAnyMethod(definitions, "remove",
                            new Object[]{Integer.valueOf(TYPE_RETURN_TO_HOME)});
                    returnHomePreviewAnimation = null;
                    moduleLog(Log.INFO, TAG, "Removed RETURN_TO_HOME preview runner");
                    return true;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to remove RETURN_TO_HOME preview runner",
                        throwable);
            }
            return false;
        }
        android.util.SparseArray<?> registered = (android.util.SparseArray<?>) definitions;
        try {
            Object existing = registered.get(TYPE_RETURN_TO_HOME);
            if (existing != null) {
                Object owner = readField(readField(existing, "mCallback"), "this$0");
                if (owner != crossTask && owner.getClass() == crossTask.getClass()) {
                    // Adopt the instance a previous module generation installed.
                    if (returnHomePreviewAnimation == null) {
                        returnHomePreviewAnimation = owner;
                    }
                    return false;
                }
                // The shared cross-task runner must never own RETURN_TO_HOME.
            }
            Object crossTaskRunner = invokeAnyMethod(crossTask, "getRunner", new Object[0]);
            Object background = readField(crossTask, "mBackground");
            Context context = (Context) readField(crossTask, "mContext");
            Handler handler = (Handler) readField(crossTaskRunner, "mHandler");
            java.lang.reflect.Constructor<?> constructor = crossTask.getClass()
                    .getDeclaredConstructor(Context.class, background.getClass(), Handler.class);
            constructor.setAccessible(true);
            Object animation = constructor.newInstance(context, background, handler);
            Object runner = invokeAnyMethod(animation, "getRunner", new Object[0]);
            invokeAnyMethod(definitions, "set",
                    new Object[]{Integer.valueOf(TYPE_RETURN_TO_HOME), runner});
            returnHomePreviewAnimation = animation;
            moduleLog(Log.INFO, TAG, "Installed RETURN_TO_HOME preview runner"
                    + ", animation=" + shortObject(animation)
                    + ", runner=" + shortObject(runner));
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to install RETURN_TO_HOME preview runner",
                    throwable);
            return false;
        }
    }

    protected void hookReturnHomePreviewAnimation(ClassLoader classLoader,
            boolean installStart, boolean installProgress, boolean installCommit,
            boolean installFinish) {
        if (!isReturnHomePreviewSupported()) {
            return;
        }
        try {
            Class<?> animationClass = Class.forName(
                    CROSS_TASK_BACK_ANIMATION, false, classLoader);
            if (installStart) {
                Method start = animationClass.getDeclaredMethod("startBackAnimation");
                start.setAccessible(true);
                registerHook(start, "systemui_return_home_preview_start", this::startReturnHomePreview);
            }
            if (installProgress) {
                Method progress = animationClass.getDeclaredMethod(
                        "updateGestureBackProgress", float.class, BackEvent.class);
                progress.setAccessible(true);
                registerHook(progress, "systemui_return_home_preview_progress", this::updateReturnHomePreview);
            }
            if (installFinish) {
                Method finish = animationClass.getDeclaredMethod("finishAnimation");
                finish.setAccessible(true);
                registerHook(finish, "systemui_return_home_preview_finish", this::finishReturnHomePreview);
            }
            if (installCommit) {
                Method commit = animationClass.getDeclaredMethod("onGestureCommitted");
                commit.setAccessible(true);
                registerHook(commit, "systemui_return_home_preview_commit", this::commitReturnHomePreview);
            }
            // The hooked methods are private; keep their direct callers interpreted
            // so ART cannot inline the original bodies past the hooks.
            boolean deoptimized = deoptimize(animationClass.getDeclaredMethod(
                    "onGestureProgress", BackEvent.class));
            for (String nested : new String[]{
                    "-$$Nest$mstartBackAnimation", "-$$Nest$monGestureCommitted",
                    "-$$Nest$mfinishAnimation"}) {
                deoptimized &= deoptimize(animationClass.getDeclaredMethod(
                        nested, animationClass));
            }
            moduleLog(Log.INFO, TAG, "Hooked RETURN_TO_HOME preview geometry"
                    + ", start=" + installStart + ", progress=" + installProgress
                    + ", commit=" + installCommit + ", finish=" + installFinish
                    + ", callersDeoptimized=" + deoptimized);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to hook RETURN_TO_HOME preview geometry",
                    throwable);
        }
    }

    protected static final int RETURN_HOME_SCRIM_BLUR_PX = 150;

    protected static final float RETURN_HOME_SCRIM_DIM_ALPHA = 0.36f;

    protected static final float RETURN_HOME_SCRIM_FULL_PROGRESS = 0.25f;

    protected static final float RETURN_HOME_APP_STATE_SCALE = 0.96f;

    protected static final float RETURN_HOME_APP_STATE_WALLPAPER_ZOOM = 1.157f;

    protected volatile Object returnHomeTaskOrganizer;

    protected SurfaceControl returnHomeTaskLeash;

    protected final Matrix returnHomeTaskMatrix = new Matrix();

    protected static final long RETURN_HOME_HOME_REVEAL_MS = 16L;

    protected static final long RETURN_HOME_SCRIM_DIM_RELEASE_MS = 24L;

    protected static final long RETURN_HOME_SCRIM_BLUR_RELEASE_MS = 200L;

    protected static final long RETURN_HOME_BACKGROUND_FALLBACK_MS = 400L;

    protected static final class ReturnHomeScrim {
        final SurfaceControl root;
        final SurfaceControl blur;
        final SurfaceControl dim;
        float amount;

        ReturnHomeScrim(SurfaceControl root, SurfaceControl blur, SurfaceControl dim) {
            this.root = root;
            this.blur = blur;
            this.dim = dim;
        }
    }

    protected volatile ReturnHomeScrim returnHomeScrim;

    protected ReturnHomeScrim createReturnHomeScrim(Object animation,
            RemoteAnimationTarget closing, Rect bounds, SurfaceControl.Transaction t)
            throws Throwable {
        Object organizer = readField(readField(animation, "mBackground"),
                "mRootTaskDisplayAreaOrganizer");
        int displayId = (Integer) invokeMethod(closing.taskInfo, "getDisplayId",
                new Class<?>[0], new Object[0]);
        SurfaceControl.Builder rootBuilder = new SurfaceControl.Builder()
                .setName("return-home-preview-scrim");
        invokeMethod(rootBuilder, "setContainerLayer", new Class<?>[0], new Object[0]);
        invokeMethod(organizer, "attachToDisplayArea",
                new Class<?>[]{int.class, SurfaceControl.Builder.class},
                new Object[]{Integer.valueOf(displayId), rootBuilder});
        SurfaceControl root = rootBuilder.build();
        SurfaceControl.Builder blurBuilder = new SurfaceControl.Builder()
                .setName("return-home-preview-scrim-blur").setParent(root);
        invokeMethod(blurBuilder, "setEffectLayer", new Class<?>[0], new Object[0]);
        SurfaceControl blur = blurBuilder.build();
        SurfaceControl.Builder dimBuilder = new SurfaceControl.Builder()
                .setName("return-home-preview-scrim-dim").setParent(root);
        invokeMethod(dimBuilder, "setColorLayer", new Class<?>[0], new Object[0]);
        SurfaceControl dim = dimBuilder.build();
        t.setLayer(blur, 0).setLayer(dim, 1)
                .setCrop(root, bounds).setCrop(blur, bounds).setCrop(dim, bounds)
                .setAlpha(dim, 0f);
        for (SurfaceControl layer : new SurfaceControl[]{root, blur, dim}) {
            invokeMethod(t, "show", new Class<?>[]{SurfaceControl.class},
                    new Object[]{layer});
        }
        invokeMethod(t, "setColor", new Class<?>[]{SurfaceControl.class, float[].class},
                new Object[]{dim, new float[]{0f, 0f, 0f}});
        invokeMethod(t, "setBackgroundBlurRadius",
                new Class<?>[]{SurfaceControl.class, int.class},
                new Object[]{blur, Integer.valueOf(0)});
        setReturnHomeRelativeLayer(t, root, closing.leash);
        return new ReturnHomeScrim(root, blur, dim);
    }

    protected void setReturnHomeRelativeLayer(SurfaceControl.Transaction t,
            SurfaceControl layer, SurfaceControl above) throws Exception {
        invokeMethod(t, "setRelativeLayer",
                new Class<?>[]{SurfaceControl.class, SurfaceControl.class, int.class},
                new Object[]{layer, above, Integer.valueOf(-1)});
    }

    protected void applyReturnHomeScrim(ReturnHomeScrim scrim, float amount,
            SurfaceControl.Transaction t) throws Throwable {
        scrim.amount = amount;
        t.setAlpha(scrim.dim, RETURN_HOME_SCRIM_DIM_ALPHA * amount);
        invokeMethod(t, "setBackgroundBlurRadius",
                new Class<?>[]{SurfaceControl.class, int.class},
                new Object[]{scrim.blur,
                        Integer.valueOf(Math.round(RETURN_HOME_SCRIM_BLUR_PX * amount))});
    }

    protected void removeReturnHomeScrim(ReturnHomeScrim scrim) throws Exception {
        if (scrim == null) {
            return;
        }
        if (returnHomeScrim == scrim) {
            returnHomeScrim = null;
        }
        if (!scrim.root.isValid()) {
            return;
        }
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        invokeMethod(t, "remove", new Class<?>[]{SurfaceControl.class},
                new Object[]{scrim.root});
        t.apply();
    }

    protected Object finishReturnHomePreview(XposedInterface.Chain chain) throws Throwable {
        Object animation = chain.getThisObject();
        if (animation == returnHomePreviewAnimation) {
            ReturnHomeScrim scrim = returnHomeScrim;
            ReturnHomeHandoff handoff = returnHomeHandoff;
            if (handoff == null || !handoff.placed) {
                if (scrim != null) {
                    removeReturnHomeScrim(scrim);
                }
                resetReturnHomeTaskLeash(null);
            }
        }
        return chain.proceed();
    }

    protected void resetReturnHomeTaskLeash(SurfaceControl.Transaction t) {
        SurfaceControl homeTask = returnHomeTaskLeash;
        returnHomeTaskLeash = null;
        if (homeTask == null || !homeTask.isValid()) {
            return;
        }
        try {
            SurfaceControl.Transaction apply = t != null ? t : new SurfaceControl.Transaction();
            invokeMethod(apply, "setMatrix",
                    new Class<?>[]{SurfaceControl.class, Matrix.class, float[].class},
                    new Object[]{homeTask, new Matrix(), new float[9]});
            if (t == null) {
                apply.apply();
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to reset RETURN_TO_HOME Home task", throwable);
        }
    }

    protected Object startReturnHomePreview(XposedInterface.Chain chain) throws Throwable {
        Object animation = chain.getThisObject();
        if (animation != returnHomePreviewAnimation) {
            return chain.proceed();
        }
        RemoteAnimationTarget closing =
                (RemoteAnimationTarget) readField(animation, "mClosingTarget");
        if (closing == null || readField(animation, "mEnteringTarget") == null) {
            return chain.proceed();
        }
        // Home is the live entering target behind the card; no cross-task background.
        Rect startTaskRect = (Rect) readField(animation, "mStartTaskRect");
        startTaskRect.set(closing.windowConfiguration.getBounds());
        startTaskRect.offsetTo(0, 0);
        returnHomePreviewRect.set(startTaskRect);
        removeReturnHomeScrim(returnHomeScrim);
        resetReturnHomeTaskLeash(null);
        try {
            RemoteAnimationTarget entering =
                    (RemoteAnimationTarget) readField(animation, "mEnteringTarget");
            Object organizer = returnHomeTaskOrganizer;
            returnHomeTaskLeash = organizer == null ? null : (SurfaceControl) invokeMethod(
                    organizer, "getTaskLeash", new Class<?>[]{int.class},
                    new Object[]{Integer.valueOf(entering.taskId)});
        } catch (Throwable throwable) {
            returnHomeTaskLeash = null;
            moduleLog(Log.WARN, TAG, "RETURN_TO_HOME Home task leash unavailable", throwable);
        }
        try {
            SurfaceControl.Transaction t =
                    (SurfaceControl.Transaction) readField(animation, "mTransaction");
            // Home's icons stay visible beneath the blur during the preview.
            returnHomeScrim = createReturnHomeScrim(animation, closing, startTaskRect, t);
        } catch (Throwable throwable) {
            returnHomeScrim = null;
            moduleLog(Log.WARN, TAG, "Failed to create RETURN_TO_HOME scrim", throwable);
        }
        moduleLog(Log.INFO, TAG, "Started RETURN_TO_HOME preview"
                + ", taskId=" + closing.taskId + ", bounds=" + startTaskRect
                + ", homeTask=" + (returnHomeTaskLeash != null));
        return null;
    }

    protected Object updateReturnHomePreview(XposedInterface.Chain chain) throws Throwable {
        Object animation = chain.getThisObject();
        if (animation != returnHomePreviewAnimation) {
            return chain.proceed();
        }
        RemoteAnimationTarget closing =
                (RemoteAnimationTarget) readField(animation, "mClosingTarget");
        RemoteAnimationTarget entering =
                (RemoteAnimationTarget) readField(animation, "mEnteringTarget");
        if (closing == null || entering == null) {
            return null;
        }
        float progress = Math.max(0f, Math.min(1f, (Float) chain.getArg(0)));
        BackEvent event = (BackEvent) chain.getArg(1);
        Rect start = (Rect) readField(animation, "mStartTaskRect");
        android.graphics.PointF initialTouch =
                (android.graphics.PointF) readField(animation, "mInitialTouchPos");
        boolean rightEdge = Boolean.TRUE.equals(readField(animation, "mIsRightEdge"));
        float cornerRadius = (Float) readField(animation, "mCornerRadius");
        float density = ((Context) readField(animation, "mContext"))
                .getResources().getDisplayMetrics().density;

        float width = start.width();
        float height = start.height();
        float scale = 1f - (1f - RETURN_HOME_PREVIEW_MIN_SCALE) * progress;
        float scaledWidth = width * scale;
        float scaledHeight = height * scale;
        // Pin the card toward the side opposite the swiped edge, AOSP style.
        float margin = RETURN_HOME_PREVIEW_EDGE_MARGIN_DP * density * progress;
        float left = rightEdge
                ? margin
                : width - scaledWidth - margin;
        float maxShift = Math.max(0f, (height - scaledHeight) / 2f);
        float rawShift = event.getTouchY() - initialTouch.y;
        float shift = Math.signum(rawShift)
                * Math.min(maxShift, Math.abs(rawShift) * 0.25f);
        float top = (height - scaledHeight) / 2f + shift;
        returnHomePreviewRect.set(left, top, left + scaledWidth, top + scaledHeight);

        // Ease Home into Xiaomi's app state so nothing jumps when Xiaomi takes over.
        float amount = Math.min(1f, progress / RETURN_HOME_SCRIM_FULL_PROGRESS);
        SurfaceControl homeTask = returnHomeTaskLeash;
        float zoom = homeTask == null ? 1f
                : 1f + (RETURN_HOME_APP_STATE_WALLPAPER_ZOOM - 1f) * amount;
        RectF home = new RectF(start);
        home.inset(width * (1f - zoom) / 2f, height * (1f - zoom) / 2f);
        invokeAnyMethod(animation, "applyTransform", new Object[]{
                entering.leash, home, Float.valueOf(0f)});
        if (homeTask != null && homeTask.isValid()) {
            float homeScale = (1f - (1f - RETURN_HOME_APP_STATE_SCALE) * amount) / zoom;
            returnHomeTaskMatrix.setScale(homeScale, homeScale, width / 2f, height / 2f);
            invokeMethod(readField(animation, "mTransaction"), "setMatrix",
                    new Class<?>[]{SurfaceControl.class, Matrix.class, float[].class},
                    new Object[]{homeTask, returnHomeTaskMatrix, new float[9]});
        }
        invokeAnyMethod(animation, "applyTransform", new Object[]{
                closing.leash, returnHomePreviewRect, Float.valueOf(cornerRadius)});
        ReturnHomeScrim scrim = returnHomeScrim;
        if (scrim != null) {
            applyReturnHomeScrim(scrim, amount,
                    (SurfaceControl.Transaction) readField(animation, "mTransaction"));
        }
        invokeAnyMethod(animation, "applyTransaction", new Object[0]);
        ((RectF) readField(animation, "mClosingCurrentRect")).set(returnHomePreviewRect);
        writeField(animation, "mGestureProgress", Float.valueOf(progress));
        return null;
    }

    protected Object commitReturnHomePreview(XposedInterface.Chain chain) throws Throwable {
        Object animation = chain.getThisObject();
        if (animation != returnHomePreviewAnimation) {
            return chain.proceed();
        }
        RemoteAnimationTarget closing =
                (RemoteAnimationTarget) readField(animation, "mClosingTarget");
        if (closing == null || readField(animation, "mEnteringTarget") == null) {
            return chain.proceed();
        }
        // Freeze in place. The runner stays alive so the incoming CLOSE still meets
        // the prepared transition and can be rejected into Xiaomi's own route.
        ReturnHomeHandoff handoff = new ReturnHomeHandoff(animation, closing.taskId,
                new RectF(returnHomePreviewRect),
                new Rect((Rect) readField(animation, "mStartTaskRect")),
                (Float) readField(animation, "mCornerRadius"),
                returnHomeHandoffSequence.incrementAndGet());
        returnHomeHandoff = handoff;
        moduleLog(Log.INFO, TAG, "Committed RETURN_TO_HOME preview"
                + ", taskId=" + handoff.taskId
                + ", rect=" + handoff.rect.toShortString()
                + ", sequence=" + handoff.sequence);
        sendReturnHomeHandoff((Context) readField(animation, "mContext"), handoff);
        // A CLOSE that never reaches the merge must not leave the card frozen.
        new Handler(Looper.myLooper()).postDelayed(() -> {
            if (returnHomeHandoff != handoff || handoff.transferred) {
                return;
            }
            returnHomeHandoff = null;
            moduleLog(Log.WARN, TAG, "RETURN_TO_HOME handoff timed out"
                    + ", taskId=" + handoff.taskId + ", sequence=" + handoff.sequence);
            try {
                invokeAnyMethod(animation, "finishAnimation", new Object[0]);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to finish timed-out RETURN_TO_HOME preview", throwable);
            }
        }, RETURN_HOME_HANDOFF_TIMEOUT_MS);
        return null;
    }

    protected void sendReturnHomeHandoff(Context context, ReturnHomeHandoff handoff) {
        try {
            Context application = context.getApplicationContext();
            Intent intent = new Intent(systemUiInputArbiterStateAction())
                    .setPackage(MIUI_HOME)
                    // The native receiver also consumes marked intents as arbiter
                    // state updates; carry the current state unchanged.
                    .putExtra(EXTRA_INPUT_ARBITER_READY,
                            systemUiInputArbiterMonitorCount.get() > 0)
                    .putExtra(EXTRA_INPUT_ARBITER_GENERATION, systemUiInputArbiterGeneration)
                    .putExtra(EXTRA_CONTEXTUAL_SEARCH_ENABLED,
                            isContextualSearchLongPressRuntimeEnabled())
                    .putExtra("sender_uid", Process.myUid())
                    .putExtra(EXTRA_RETURN_HOME_HANDOFF, true)
                    .putExtra("hypertweak_return_home_sequence", handoff.sequence)
                    .putExtra("hypertweak_return_home_task_id", handoff.taskId)
                    .putExtra("hypertweak_return_home_left_x100", Math.round(handoff.rect.left * 100f))
                    .putExtra("hypertweak_return_home_top_x100", Math.round(handoff.rect.top * 100f))
                    .putExtra("hypertweak_return_home_right_x100", Math.round(handoff.rect.right * 100f))
                    .putExtra("hypertweak_return_home_bottom_x100",
                            Math.round(handoff.rect.bottom * 100f));
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            application.sendBroadcast(intent, null, options);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to send RETURN_TO_HOME handoff", throwable);
        }
    }

    protected Object captureReturnHomeMerge(XposedInterface.Chain chain) throws Throwable {
        ReturnHomeHandoff handoff = returnHomeHandoff;
        if (handoff == null || handoff.transferred) {
            return chain.proceed();
        }
        returnHomeMergeArgs.set(new Object[]{chain.getArg(1), chain.getArg(2), handoff});
        try {
            return chain.proceed();
        } finally {
            returnHomeMergeArgs.remove();
            if (handoff.transferred) {
                // Xiaomi now owns the task; end the frozen preview without moving it.
                Object animation = handoff.animation;
                new Handler(Looper.myLooper()).post(() -> {
                    try {
                        invokeAnyMethod(animation, "finishAnimation", new Object[0]);
                    } catch (Throwable throwable) {
                        moduleLog(Log.WARN, TAG,
                                "Failed to finish RETURN_TO_HOME preview", throwable);
                    }
                    if (returnHomeHandoff == handoff) {
                        returnHomeHandoff = null;
                    }
                });
            }
        }
    }

    protected TransitionInfo.Change findReturnHomeClosingChange(TransitionInfo info, int taskId) {
        for (TransitionInfo.Change change : info.getChanges()) {
            android.app.ActivityManager.RunningTaskInfo task = change.getTaskInfo();
            if (task != null && task.taskId == taskId) {
                return change;
            }
        }
        return null;
    }

    protected Object transferReturnHomePreviewFinish(XposedInterface.Chain chain)
            throws Throwable {
        Object[] merge = returnHomeMergeArgs.get();
        if (merge == null) {
            return chain.proceed();
        }
        ReturnHomeHandoff handoff = (ReturnHomeHandoff) merge[2];
        TransitionInfo info = (TransitionInfo) merge[0];
        SurfaceControl.Transaction startTransaction = (SurfaceControl.Transaction) merge[1];
        TransitionInfo.Change change = findReturnHomeClosingChange(info, handoff.taskId);
        Object finishOpen = readField(chain.getThisObject(), "mFinishOpenTransaction");
        if (change == null || change.getLeash() == null
                || !(finishOpen instanceof SurfaceControl.Transaction)) {
            return chain.proceed();
        }
        // The prepared cleanup joins the CLOSE start; the frozen card is placed on the
        // CLOSE wrapper leash when Shell creates it (placeReturnHomeCloseLeash).
        startTransaction.merge((SurfaceControl.Transaction) finishOpen);
        handoff.closeInfo = info;
        handoff.transferred = true;
        moduleLog(Log.INFO, TAG, "Transferred RETURN_TO_HOME preview into CLOSE start"
                + ", taskId=" + handoff.taskId
                + ", transitionDebugId=" + readTransitionDebugId(info)
                + ", rect=" + handoff.rect.toShortString());
        return chain.proceed();
    }

    protected Object placeReturnHomeCloseLeash(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        ReturnHomeHandoff handoff = returnHomeHandoff;
        if (handoff == null || !handoff.transferred
                || chain.getArg(0) != handoff.closeInfo
                || !(result instanceof SurfaceControl)) {
            return result;
        }
        TransitionInfo.Change change = (TransitionInfo.Change) chain.getArg(1);
        android.app.ActivityManager.RunningTaskInfo task = change.getTaskInfo();
        if (task == null) {
            return result;
        }
        SurfaceControl wrapper = (SurfaceControl) result;
        SurfaceControl.Transaction t = (SurfaceControl.Transaction) chain.getArg(3);
        ReturnHomeScrim scrim = returnHomeScrim;
        if (task.taskId == handoff.taskId && !handoff.placed) {
            float scale = handoff.rect.width() / handoff.startTaskRect.width();
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(handoff.rect.left, handoff.rect.top);
            invokeMethod(t, "setMatrix",
                    new Class<?>[]{SurfaceControl.class, Matrix.class, float[].class},
                    new Object[]{wrapper, matrix, new float[9]});
            invokeMethod(t, "setWindowCrop",
                    new Class<?>[]{SurfaceControl.class, Rect.class},
                    new Object[]{wrapper, handoff.startTaskRect});
            invokeMethod(t, "setCornerRadius",
                    new Class<?>[]{SurfaceControl.class, float.class},
                    new Object[]{wrapper, Float.valueOf(handoff.cornerRadius)});
            if (scrim != null) {
                // Directly beneath the card, above Home, as during the preview. Xiaomi's
                // app-state Home is translucent, so beneath it the dims would still stack.
                setReturnHomeRelativeLayer(t, scrim.root, wrapper);
            }
            handoff.placed = true;
            scheduleReturnHomeBackgroundFallback(handoff);
            moduleLog(Log.INFO, TAG, "Placed RETURN_TO_HOME card on CLOSE wrapper leash"
                    + ", taskId=" + handoff.taskId + ", wrapper=" + wrapper
                    + ", rect=" + handoff.rect.toShortString());
        } else if (handoff.homeWrapper == null
                && ((Integer) invokeMethod(task, "getActivityType", new Class<?>[0],
                        new Object[0])) == ACTIVITY_TYPE_HOME_VALUE) {
            // The opening Home wrapper starts at alpha 0 until Xiaomi's flight reveals it
            // a few frames later; those frames dropped Home's glow from beneath the
            // scrim (layer trace + screen record). Keep Home visible under the scrim
            // through the CLOSE start; the scrim leaves once Home is in its app state.
            t.setAlpha(wrapper, 1f);
            resetReturnHomeTaskLeash(t);
            handoff.homeWrapper = wrapper;
            new Handler(handoff.looper).postDelayed(
                    () -> releaseReturnHomeBackground(handoff), RETURN_HOME_HOME_REVEAL_MS);
            moduleLog(Log.INFO, TAG, "Kept RETURN_TO_HOME Home visible beneath the scrim"
                    + ", homeTaskId=" + task.taskId + ", homeWrapper=" + wrapper);
        }
        return result;
    }

    protected Object onReturnHomeWillFinishToHome(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        ReturnHomeHandoff handoff = returnHomeHandoff;
        if (handoff != null && Boolean.TRUE.equals(chain.getArg(0))) {
            new Handler(handoff.looper).post(() -> releaseReturnHomeBackground(handoff));
        }
        return result;
    }

    protected void releaseReturnHomeBackground(ReturnHomeHandoff handoff) {
        if (!handoff.placed || handoff.backgroundReleased) {
            return;
        }
        handoff.backgroundReleased = true;
        ReturnHomeScrim scrim = returnHomeScrim;
        moduleLog(Log.INFO, TAG, "Releasing RETURN_TO_HOME background to Xiaomi Home"
                + ", taskId=" + handoff.taskId);
        try {
            if (scrim != null) {
                releaseReturnHomeScrim(scrim);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to release RETURN_TO_HOME background",
                    throwable);
            try {
                removeReturnHomeScrim(scrim);
            } catch (Exception exception) {
                moduleLog(Log.WARN, TAG, "Failed to remove RETURN_TO_HOME scrim",
                        exception);
            }
        }
    }

    protected void releaseReturnHomeScrim(ReturnHomeScrim scrim) {
        if (returnHomeScrim == scrim) {
            returnHomeScrim = null;
        }
        float dimStart = RETURN_HOME_SCRIM_DIM_ALPHA * scrim.amount;
        float blurStart = RETURN_HOME_SCRIM_BLUR_PX * scrim.amount;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(RETURN_HOME_SCRIM_BLUR_RELEASE_MS);
        animator.setInterpolator(null);
        animator.addUpdateListener(value -> {
            if (!scrim.root.isValid()) {
                value.cancel();
                return;
            }
            float elapsed = value.getAnimatedFraction() * RETURN_HOME_SCRIM_BLUR_RELEASE_MS;
            float dim = 1f - Math.min(1f, elapsed / RETURN_HOME_SCRIM_DIM_RELEASE_MS);
            float blur = 1f - value.getAnimatedFraction();
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.setAlpha(scrim.dim, dimStart * dim);
                invokeMethod(t, "setBackgroundBlurRadius",
                        new Class<?>[]{SurfaceControl.class, int.class},
                        new Object[]{scrim.blur, Integer.valueOf(Math.round(blurStart * blur))});
                t.apply();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "RETURN_TO_HOME scrim release failed", throwable);
                value.cancel();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator ended) {
                try {
                    removeReturnHomeScrim(scrim);
                } catch (Exception exception) {
                    moduleLog(Log.WARN, TAG, "Failed to remove RETURN_TO_HOME scrim",
                            exception);
                }
            }
        });
        animator.start();
    }

    protected void scheduleReturnHomeBackgroundFallback(ReturnHomeHandoff handoff) {
        new Handler(Looper.myLooper()).postDelayed(() -> {
            if (handoff.backgroundReleased) {
                return;
            }
            handoff.backgroundReleased = true;
            moduleLog(Log.WARN, TAG, "RETURN_TO_HOME background fallback"
                    + ", taskId=" + handoff.taskId);
            try {
                removeReturnHomeScrim(returnHomeScrim);
            } catch (Exception exception) {
                moduleLog(Log.WARN, TAG, "Failed to remove RETURN_TO_HOME scrim",
                        exception);
            }
        }, RETURN_HOME_BACKGROUND_FALLBACK_MS);
    }
}
