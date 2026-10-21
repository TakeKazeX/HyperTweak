package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import io.github.libxposed.api.XposedInterface;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Tracks native open transitions and guards duplicate back delivery. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public abstract class SystemUiOpenTransitionRuntime extends SystemUiInputRuntime {
    protected volatile SystemUiPlatformImpl systemUiPlatformImpl;
    protected SystemUiPlatformImpl requireSystemUiPlatformImpl() {
        SystemUiPlatformImpl implementation = systemUiPlatformImpl;
        if (implementation == null) {
            throw new IllegalStateException("SystemUI platform implementation not selected");
        }
        return implementation;
    }

    protected void hookDefaultTransitionHandler(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(DEFAULT_TRANSITION_HANDLER, false,
                    classLoader);
            resolveDefaultTransitionSnapshotReflection(handlerClass);
            SystemUiPlatformImpl implementation = requireSystemUiPlatformImpl();
            if (implementation.captureOpenFromTransitionsOwner()) {
                Class<?> playerClass = Class.forName(
                        "com.android.wm.shell.transition.Transitions$TransitionPlayerImpl",
                        false, classLoader);
                Method onTransitionReady = playerClass.getDeclaredMethod(
                        "onTransitionReady", IBinder.class, TransitionInfo.class,
                        SurfaceControl.Transaction.class, SurfaceControl.Transaction.class);
                onTransitionReady.setAccessible(true);
                registerHook(onTransitionReady, implementation.defaultTransitionOpenCaptureHookId(), this::capturePostedDefaultOpenTransition);
                moduleLog(Log.INFO, TAG,
                        "Hooked Android 17 TransitionPlayerImpl.onTransitionReady OPEN capture");
                return;
            }
            Class<?> finishCallbackClass = Class.forName(
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback",
                    false, classLoader);
            Method startAnimation = handlerClass.getDeclaredMethod("startAnimation",
                    IBinder.class, TransitionInfo.class, SurfaceControl.Transaction.class,
                    SurfaceControl.Transaction.class, finishCallbackClass);
            startAnimation.setAccessible(true);
            registerHook(startAnimation, implementation.defaultTransitionOpenCaptureHookId(), this::registerDefaultTransitionHandler);
            moduleLog(Log.INFO, TAG, "Hooked exact DefaultTransitionHandler.startAnimation"
                    + ", impl=" + implementation.name());
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook DefaultTransitionHandler", throwable);
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    protected synchronized void resolveDefaultTransitionSnapshotReflection(
            Class<?> handlerClass) throws ReflectiveOperationException {
        if (defaultTransitionAnimationsField != null
                && defaultTransitionAnimationSizeField != null
                && defaultTransitionAnimExecutorField != null
                && animatorCanReverseMethod != null) {
            return;
        }
        String animatorsFieldName = requireSystemUiPlatformImpl()
                .defaultTransitionAnimatorsFieldName();
        Field animationsField = handlerClass.getDeclaredField(animatorsFieldName);
        Field animationSizeField = handlerClass.getDeclaredField("mAnimationSize");
        Field animExecutorField = handlerClass.getDeclaredField("mAnimExecutor");
        // Animator.canReverse() is a boot-classpath hidden API. LSPosed loads this code inside
        // SystemUI with hidden-API access; the public SDK stub does not expose the method.
        Method canReverseMethod = Animator.class.getDeclaredMethod("canReverse");
        animationsField.setAccessible(true);
        animationSizeField.setAccessible(true);
        animExecutorField.setAccessible(true);
        canReverseMethod.setAccessible(true);
        defaultTransitionAnimationsField = animationsField;
        defaultTransitionAnimationSizeField = animationSizeField;
        defaultTransitionAnimExecutorField = animExecutorField;
        animatorCanReverseMethod = canReverseMethod;
    }

    protected Object registerDefaultTransitionHandler(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (!Boolean.TRUE.equals(result)) {
            return result;
        }
        if (requireSystemUiPlatformImpl().captureOpenFromTransitionsOwner()) {
            // Neutralize an Android 16-style handle left behind by hot reload. Android 17
            // captures from the non-inlined Transitions owner after handler selection.
            return result;
        }
        try {
            captureRunningOpenTransition(chain.getThisObject(), chain.getArg(0),
                    chain.getArg(1));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to capture Xiaomi OPEN transition snapshot",
                    throwable);
        }
        return result;
    }

    protected Object capturePostedDefaultOpenTransition(XposedInterface.Chain chain)
            throws Throwable {
        Object token = chain.getArg(0);
        Object info = chain.getArg(1);
        int transitionType = info instanceof TransitionInfo
                ? ((TransitionInfo) info).getType() : -1;
        boolean open = transitionType == 1;
        if (open) {
            moduleLog(Log.INFO, TAG,
                    "Entered Android 17 TransitionPlayer OPEN ready hook"
                            + ", token=" + shortObject(token)
                            + ", thread=" + Thread.currentThread().getName());
        }
        Object result = chain.proceed();
        if (!open && transitionType != 2) {
            return result;
        }
        try {
            Object transitions = readField(chain.getThisObject(), "this$0");
            Object executorObject = readField(transitions, "mMainExecutor");
            if (!(executorObject instanceof Executor)) {
                throw new IllegalStateException("Unexpected Shell main executor: "
                        + shortObject(executorObject));
            }
            ((Executor) executorObject).execute(() -> {
                if (open) {
                    capturePostedDefaultOpenTransitionOnOwner(
                            transitions, token, info);
                } else {
                    observePostedMiuiOpenCloseMerge(info);
                }
            });
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to enqueue Android 17 OPEN transition capture",
                    throwable);
        }
        return result;
    }

    protected void capturePostedDefaultOpenTransitionOnOwner(
            Object transitions, Object token, Object info) {
        try {
            Object knownObject = readField(transitions, "mKnownTransitions");
            Object activeTransition = knownObject instanceof Map
                    ? ((Map<?, ?>) knownObject).get(token) : null;
            Object handler = activeTransition == null
                    ? null : readField(activeTransition, "mHandler");
            moduleLog(Log.INFO, TAG,
                    "Resolved Android 17 OPEN owner after dispatch"
                            + ", token=" + shortObject(token)
                            + ", active=" + shortObject(activeTransition)
                            + ", handler=" + shortObject(handler)
                            + ", handlerClass="
                            + (handler == null ? "null" : handler.getClass().getName())
                            + ", thread=" + Thread.currentThread().getName());
            if (handler == null) {
                return;
            }
            String handlerClass = handler.getClass().getName();
            if (DEFAULT_TRANSITION_HANDLER.equals(handlerClass)) {
                captureRunningOpenTransition(handler, token, info, transitions);
            } else if ("com.android.wm.shell.common.transition.MiuiTransitionHandler"
                    .equals(handlerClass)) {
                captureRunningMiuiSpringOpenTransition(
                        handler, token, info);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture Android 17 posted OPEN transition snapshot",
                    throwable);
        }
    }

    protected void captureRunningMiuiSpringOpenTransition(
            Object handler, Object token, Object info)
            throws Exception {
        Object transitionMapObject = readField(handler, "mTransitions");
        Object executorObject = readField(handler, "mAnimExecutor");
        if (!(transitionMapObject instanceof Map)
                || ((Map<?, ?>) transitionMapObject).get(token) != info
                || !(executorObject instanceof Executor)
                || !(info instanceof TransitionInfo)) {
            moduleLog(Log.INFO, TAG,
                    "Skipped Android 17 Xiaomi OPEN without exact active owner"
                            + ", token=" + shortObject(token));
            return;
        }
        Class<?> managerClass = Class.forName(
                "com.android.wm.shell.common.transition.animation."
                        + "MiuiSpringAnimationManager",
                false, handler.getClass().getClassLoader());
        Method getInstance = managerClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object manager = getInstance.invoke(null);
        int transitionDebugId = ((TransitionInfo) info).getDebugId();
        long generation = openSnapshotGeneration.get();
        if (!acceptingOpenSnapshots) {
            return;
        }
        openSnapshotLifecycleEpoch.incrementAndGet();
        ((Executor) executorObject).execute(() ->
                captureRunningMiuiSpringOpenTransitionOnAnim(
                        handler, manager, token, info,
                        transitionDebugId, generation,
                        (Executor) executorObject));
    }

    protected void captureRunningMiuiSpringOpenTransitionOnAnim(
            Object handler, Object manager,
            Object token, Object info, int transitionDebugId,
            long generation, Executor animExecutor) {
        try {
            if (!acceptingOpenSnapshots
                    || generation != openSnapshotGeneration.get()) {
                return;
            }
            Object groupsByTransitionObject = readField(
                    manager, "mRunningAnimGroupMapByTransition");
            Object groupMapObject = groupsByTransitionObject instanceof Map
                    ? ((Map<?, ?>) groupsByTransitionObject).get(
                    Integer.valueOf(transitionDebugId)) : null;
            if (!(groupMapObject instanceof Map)
                    || ((Map<?, ?>) groupMapObject).isEmpty()) {
                moduleLog(Log.INFO, TAG,
                        "Skipped Android 17 Xiaomi OPEN without running spring groups"
                                + ", transitionId=" + transitionDebugId);
                return;
            }
            Object[] groups = ((Map<?, ?>) groupMapObject).values().toArray();
            Object[] springAnimations = collectRunningMiuiSpringAnimations(
                    groups, true);
            if (springAnimations.length == 0) {
                return;
            }
            Object shellTransitionInfo = readField(
                    groups[0], "mMiuiShellTransitionInfo");
            OpenTransitionSnapshot snapshot = new OpenTransitionSnapshot(
                    token, info, springAnimations.length, animExecutor, generation,
                    handler, manager, groups, springAnimations,
                    shellTransitionInfo, transitionDebugId);
            OpenTransitionSnapshot previous = runningOpenTransitions.put(token, snapshot);
            if (previous != null) {
                invalidateOpenTransitionSnapshot(previous, "replaced");
            }
            verifyAndActivateMiuiSpringOpenTransition(snapshot);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture Android 17 Xiaomi spring OPEN snapshot",
                    throwable);
        }
    }

    protected Object[] collectRunningMiuiSpringAnimations(
            Object[] groups, boolean requireRunning) throws Exception {
        ArrayList<Object> animations = new ArrayList<>();
        for (Object group : groups) {
            Object wrappersObject = readField(group, "mMiuiSpringAnimations");
            if (!(wrappersObject instanceof List)
                    || ((List<?>) wrappersObject).isEmpty()) {
                return new Object[0];
            }
            for (Object wrapper : (List<?>) wrappersObject) {
                Method getSpringAnimation = wrapper.getClass()
                        .getDeclaredMethod("getMiuiSpringAnimation");
                getSpringAnimation.setAccessible(true);
                Object spring = getSpringAnimation.invoke(wrapper);
                if (spring == null
                        || (requireRunning && !isMiuiSpringRunning(spring))) {
                    return new Object[0];
                }
                animations.add(spring);
            }
        }
        return animations.toArray();
    }

    protected boolean isMiuiSpringRunning(Object spring) throws Exception {
        Method isRunning = spring.getClass().getMethod("isRunning");
        isRunning.setAccessible(true);
        return Boolean.TRUE.equals(isRunning.invoke(spring));
    }

    protected void verifyAndActivateMiuiSpringOpenTransition(
            OpenTransitionSnapshot snapshot) {
        try {
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || snapshot.state.get() != OPEN_SNAPSHOT_PENDING) {
                invalidateOpenTransitionSnapshot(snapshot, "staleValidator");
                return;
            }
            Object transitionMapObject = readField(
                    snapshot.platformHandler, "mTransitions");
            Object groupsByTransitionObject = readField(
                    snapshot.animationManager,
                    "mRunningAnimGroupMapByTransition");
            Object groupMapObject = groupsByTransitionObject instanceof Map
                    ? ((Map<?, ?>) groupsByTransitionObject).get(
                    Integer.valueOf(snapshot.transitionDebugId)) : null;
            if (!(transitionMapObject instanceof Map)
                    || ((Map<?, ?>) transitionMapObject).get(snapshot.token)
                    != snapshot.transitionInfo
                    || !(groupMapObject instanceof Map)
                    || ((Map<?, ?>) groupMapObject).size()
                    != snapshot.animationGroups.length) {
                invalidateOpenTransitionSnapshot(snapshot, "ownerChanged");
                return;
            }
            for (Object group : snapshot.animationGroups) {
                if (!((Map<?, ?>) groupMapObject).containsValue(group)) {
                    invalidateOpenTransitionSnapshot(snapshot, "groupChanged");
                    return;
                }
            }
            Object[] currentSprings = collectRunningMiuiSpringAnimations(
                    snapshot.animationGroups, true);
            if (currentSprings.length != snapshot.springAnimations.length) {
                invalidateOpenTransitionSnapshot(snapshot, "animationSetChanged");
                return;
            }
            for (Object spring : snapshot.springAnimations) {
                if (!containsIdentity(currentSprings, spring)) {
                    invalidateOpenTransitionSnapshot(snapshot, "animationChanged");
                    return;
                }
            }
            attachMiuiOpenEndListener(snapshot);
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || !snapshot.state.compareAndSet(
                    OPEN_SNAPSHOT_PENDING, OPEN_SNAPSHOT_ACTIVE)) {
                removeOpenTransitionListeners(snapshot);
                invalidateOpenTransitionSnapshot(snapshot, "notReversible");
                return;
            }
            moduleLog(Log.INFO, TAG,
                    "Published reversible Android 17 Xiaomi OPEN snapshot"
                            + ", springAnimationCount="
                            + snapshot.originalAnimatorCount
                            + ", transitionId="
                            + snapshot.transitionDebugId);
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "verificationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to verify Android 17 Xiaomi spring OPEN snapshot",
                    throwable);
        }
    }

    protected boolean containsIdentity(Object[] values, Object expected) {
        for (Object value : values) {
            if (value == expected) {
                return true;
            }
        }
        return false;
    }

    protected void attachMiuiOpenEndListener(OpenTransitionSnapshot snapshot)
            throws Exception {
        if (snapshot.springAnimations.length == 0) {
            throw new IllegalStateException("Missing Xiaomi spring animation identity");
        }
        ClassLoader classLoader = snapshot.platformHandler.getClass().getClassLoader();
        Class<?> listenerInterface = Class.forName(
                "com.android.wm.shell.common.transition.animation.spring."
                        + "MiuiDynamicAnimation$OnAnimationEndListener",
                false, classLoader);
        Object listener = Proxy.newProxyInstance(classLoader,
                new Class<?>[]{listenerInterface}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "equals":
                                return args != null && args.length == 1
                                        && proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "MiuiOpenSpringEndListener@"
                                        + Integer.toHexString(
                                        System.identityHashCode(proxy));
                            default:
                                return null;
                        }
                    }
                    if ("onAnimationEnd".equals(method.getName())) {
                        boolean canceled = args != null && args.length > 1
                                && Boolean.TRUE.equals(args[1]);
                        onMiuiOpenSpringEnded(snapshot, canceled);
                    }
                    return null;
                });
        snapshot.springEndListener = listener;
        try {
            for (Object spring : snapshot.springAnimations) {
                Method addEndListener = spring.getClass().getMethod(
                        "addEndListener", listenerInterface);
                addEndListener.setAccessible(true);
                addEndListener.invoke(spring, listener);
            }
        } catch (Throwable throwable) {
            removeOpenTransitionListeners(snapshot);
            if (throwable instanceof Exception) {
                throw (Exception) throwable;
            }
            throw new ReflectiveOperationException(
                    "Failed to attach Xiaomi spring end listener", throwable);
        }
    }

    protected void onMiuiOpenSpringEnded(
            OpenTransitionSnapshot snapshot, boolean canceled) {
        if (snapshot.state.get() != OPEN_SNAPSHOT_ACTIVE
                || runningOpenTransitions.get(snapshot.token) != snapshot) {
            return;
        }
        try {
            if (canceled) {
                invalidateOpenTransitionSnapshot(snapshot, "cancel");
                return;
            }
            if (snapshot.miuiShellTransitionInfo != null
                    && Boolean.TRUE.equals(readField(
                    snapshot.miuiShellTransitionInfo,
                    "mIsMergeOtherTransition"))) {
                invalidateOpenTransitionSnapshot(snapshot, "reverseMerge");
                correlateLegacyBackMerge(snapshot.transitionInfo);
                return;
            }
            for (Object spring : snapshot.springAnimations) {
                if (isMiuiSpringRunning(spring)) {
                    return;
                }
            }
            // The native final spring listener may post transition cleanup to the
            // Shell main executor before this module listener runs. At this point
            // immutable spring identity, cancellation, merge state, snapshot state,
            // and gesture ownership are the authoritative natural-end proof; the
            // handler's token map is allowed to have completed normally already.
            invalidateOpenTransitionSnapshot(snapshot, "end");
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "endVerificationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to verify Android 17 Xiaomi OPEN spring end",
                    throwable);
        }
    }

    protected void observePostedMiuiOpenCloseMerge(Object incomingInfo) {
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            if (!snapshot.miuiSpring
                    || snapshot.state.get() != OPEN_SNAPSHOT_ACTIVE
                    || snapshot.miuiShellTransitionInfo == null) {
                continue;
            }
            try {
                if (!Boolean.TRUE.equals(readField(
                        snapshot.miuiShellTransitionInfo,
                        "mIsMergeOtherTransition"))) {
                    continue;
                }
                invalidateOpenTransitionSnapshot(snapshot, "reverseMerge");
                correlateLegacyBackMerge(snapshot.transitionInfo);
                moduleLog(Log.INFO, TAG,
                        "Observed accepted Android 17 Xiaomi OPEN/CLOSE merge"
                                + ", runningTransitionId="
                                + snapshot.transitionDebugId
                                + ", incomingInfo="
                                + shortObject(incomingInfo));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to verify Android 17 Xiaomi OPEN/CLOSE merge",
                        throwable);
            }
        }
    }

    protected void captureRunningOpenTransition(Object handler, Object token, Object info)
            throws Exception {
        captureRunningOpenTransition(handler, token, info, null);
    }

    protected void captureRunningOpenTransition(
            Object handler, Object token, Object info, Object shellTransitions)
            throws Exception {
        if (handler == null || token == null || info == null) {
            return;
        }
        resolveDefaultTransitionSnapshotReflection(handler.getClass());
        if (!(info instanceof TransitionInfo) || ((TransitionInfo) info).getType() != 1) {
            return;
        }
        openSnapshotLifecycleEpoch.incrementAndGet();
        Object animationsObject = defaultTransitionAnimationsField.get(handler);
        Object animationSizeObject = defaultTransitionAnimationSizeField.get(handler);
        Object executorObject = defaultTransitionAnimExecutorField.get(handler);
        if (!(animationsObject instanceof Map) || !(animationSizeObject instanceof Map)
                || !(executorObject instanceof Executor)) {
            throw new IllegalStateException("Unexpected DefaultTransitionHandler fields"
                    + ", animations=" + shortObject(animationsObject)
                    + ", animationSize=" + shortObject(animationSizeObject)
                    + ", executor=" + shortObject(executorObject));
        }
        Object animatorListObject = ((Map<?, ?>) animationsObject).get(token);
        if (!(animatorListObject instanceof List)) {
            moduleLog(Log.INFO, TAG, "Skipped Xiaomi OPEN snapshot without animator list"
                    + ", token=" + shortObject(token)
                    + ", mapClass=" + animationsObject.getClass().getName()
                    + ", mapSize=" + ((Map<?, ?>) animationsObject).size()
                    + ", containsToken="
                    + ((Map<?, ?>) animationsObject).containsKey(token)
                    + ", value=" + shortObject(animatorListObject));
            return;
        }
        List<?> animatorList = (List<?>) animatorListObject;
        Object originalSizeObject = ((Map<?, ?>) animationSizeObject).get(token);
        int originalSize = originalSizeObject instanceof Number
                ? ((Number) originalSizeObject).intValue() : 0;
        // mAnimationSize counts native wrappers, not the Animator leaves of a multipart
        // wrapper. Reject a partial wrapper set before flattening its immutable children.
        List<Animator> leaves = TransitionAnimatorSet.collect(animatorList, originalSize,
                entry -> requireSystemUiPlatformImpl().unwrapDefaultTransitionAnimators(entry));
        if (leaves == null) {
            moduleLog(Log.INFO, TAG, "Skipped incomplete or unsupported Xiaomi OPEN animation set"
                    + ", wrapperCount=" + animatorList.size()
                    + ", originalWrapperCount=" + originalSize);
            return;
        }
        Animator[] animators = leaves.toArray(new Animator[0]);
        if (animators.length == 0) {
            moduleLog(Log.INFO, TAG, "Skipped Xiaomi OPEN snapshot with empty animator list"
                    + ", token=" + shortObject(token)
                    + ", originalAnimatorCount=" + originalSize);
            return;
        }
        long generation = openSnapshotGeneration.get();
        if (!acceptingOpenSnapshots) {
            return;
        }
        OpenTransitionSnapshot snapshot = new OpenTransitionSnapshot(token, info, animators,
                animators.length, (Executor) executorObject, generation,
                shellTransitions);
        OpenTransitionSnapshot previous = runningOpenTransitions.put(token, snapshot);
        if (previous != null) {
            invalidateOpenTransitionSnapshot(previous, "replaced");
        }
        if (!acceptingOpenSnapshots || generation != openSnapshotGeneration.get()) {
            invalidateOpenTransitionSnapshot(snapshot, "generationChanged");
            return;
        }
        try {
            snapshot.animExecutor.execute(() -> verifyAndActivateOpenTransition(snapshot));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "executorRejected");
            throw new IllegalStateException("Animation executor rejected OPEN snapshot",
                    throwable);
        }
    }

    protected void verifyAndActivateOpenTransition(OpenTransitionSnapshot snapshot) {
        try {
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || snapshot.state.get() != OPEN_SNAPSHOT_PENDING) {
                invalidateOpenTransitionSnapshot(snapshot, "staleValidator");
                return;
            }
            if (snapshot.animators.length != snapshot.originalAnimatorCount) {
                moduleLog(Log.INFO, TAG, "Skipped partial Xiaomi OPEN transition snapshot"
                        + ", currentAnimatorCount=" + snapshot.animators.length
                        + ", originalAnimatorCount=" + snapshot.originalAnimatorCount);
                invalidateOpenTransitionSnapshot(snapshot, "partialAnimationSet");
                return;
            }
            for (Animator animator : snapshot.animators) {
                if (!Boolean.TRUE.equals(animatorCanReverseMethod.invoke(animator))
                        || !animator.isRunning()) {
                    invalidateOpenTransitionSnapshot(snapshot, "notReversible");
                    return;
                }
            }
            AnimatorListenerAdapter invalidationListener =
                    new OpenTransitionInvalidationListener(this, snapshot);
            snapshot.listener = invalidationListener;
            for (Animator animator : snapshot.animators) {
                animator.addListener(invalidationListener);
            }
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || !snapshot.state.compareAndSet(
                    OPEN_SNAPSHOT_PENDING, OPEN_SNAPSHOT_ACTIVE)) {
                removeOpenTransitionListeners(snapshot);
                invalidateOpenTransitionSnapshot(snapshot, "activationRace");
                return;
            }
            moduleLog(Log.INFO, TAG, "Published reversible Xiaomi OPEN transition snapshot"
                    + ", animatorCount=" + snapshot.animators.length
                    + ", info=" + shortObject(snapshot.transitionInfo));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "verificationFailure");
            moduleLog(Log.WARN, TAG, "Failed to verify Xiaomi OPEN transition snapshot",
                    throwable);
        }
    }

    @Override
    protected void onOpenTransitionAnimatorEnded(
            OpenTransitionSnapshot snapshot, boolean isReverse) {
        if (isReverse || snapshot.shellTransitions == null) {
            super.onOpenTransitionAnimatorEnded(snapshot, isReverse);
            return;
        }
        if (!snapshot.endSignalQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            Object executorObject = readField(
                    snapshot.shellTransitions, "mMainExecutor");
            if (!(executorObject instanceof Executor)) {
                throw new IllegalStateException(
                        "Unexpected Transitions main executor="
                                + shortObject(executorObject));
            }
            ((Executor) executorObject).execute(() ->
                    registerDefaultOpenEndAfterShellIdle(snapshot));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot,
                    "idleRegistrationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to enqueue Android 17 default OPEN idle end",
                    throwable);
        }
    }

    protected void registerDefaultOpenEndAfterShellIdle(
            OpenTransitionSnapshot snapshot) {
        if (snapshot.state.get() != OPEN_SNAPSHOT_ACTIVE
                || runningOpenTransitions.get(snapshot.token) != snapshot) {
            return;
        }
        try {
            Method runOnIdle = snapshot.shellTransitions.getClass()
                    .getDeclaredMethod("runOnIdle", Runnable.class);
            runOnIdle.setAccessible(true);
            runOnIdle.invoke(snapshot.shellTransitions, (Runnable) () -> {
                if (snapshot.state.get() == OPEN_SNAPSHOT_ACTIVE
                        && runningOpenTransitions.get(snapshot.token) == snapshot) {
                    invalidateOpenTransitionSnapshot(snapshot, "end");
                }
            });
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot,
                    "idleRegistrationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to register Android 17 default OPEN idle end",
                    throwable);
        }
    }

    protected void invalidateOpenTransitionSnapshot(OpenTransitionSnapshot snapshot,
                                                    String reason) {
        if (snapshot == null) {
            return;
        }
        boolean normalEnd = "end".equals(reason);
        if (!normalEnd) {
            openSnapshotLifecycleEpoch.incrementAndGet();
        }
        int previousState = snapshot.state.getAndSet(OPEN_SNAPSHOT_INVALID);
        if (previousState == OPEN_SNAPSHOT_INVALID) {
            return;
        }
        runningOpenTransitions.remove(snapshot.token, snapshot);
        AnimatorListenerAdapter listener = snapshot.listener;
        if (listener != null || snapshot.springEndListener != null) {
            try {
                snapshot.animExecutor.execute(() -> removeOpenTransitionListeners(snapshot));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to remove Xiaomi OPEN snapshot listeners"
                        + ", reason=" + reason, throwable);
            }
        }
        moduleLog(Log.INFO, TAG, "Invalidated Xiaomi OPEN transition snapshot"
                + ", reason=" + reason
                + ", animatorCount=" + snapshot.animationCount());
        if (previousState == OPEN_SNAPSHOT_ACTIVE && normalEnd) {
            long handoffEpoch = openSnapshotLifecycleEpoch.incrementAndGet();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isOpenEndHandoffCurrent(handoffEpoch)) {
                    return;
                }
                for (NativeBackInputMonitor monitor
                        : new ArrayList<>(nativeInputMonitors.values())) {
                    monitor.driver.onInAppOpenTransitionEnded(snapshot, handoffEpoch);
                }
            });
        }
    }

    protected void removeOpenTransitionListeners(OpenTransitionSnapshot snapshot) {
        AnimatorListenerAdapter listener = snapshot.listener;
        if (listener != null) {
            for (Animator animator : snapshot.animators) {
                animator.removeListener(listener);
            }
            snapshot.listener = null;
        }
        Object springEndListener = snapshot.springEndListener;
        if (springEndListener == null) {
            return;
        }
        try {
            Class<?> listenerInterface = springEndListener.getClass()
                    .getInterfaces()[0];
            for (Object spring : snapshot.springAnimations) {
                Method removeEndListener = spring.getClass().getMethod(
                        "removeEndListener", listenerInterface);
                removeEndListener.setAccessible(true);
                removeEndListener.invoke(spring, springEndListener);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to remove Android 17 Xiaomi OPEN spring listener",
                    throwable);
        } finally {
            snapshot.springEndListener = null;
        }
    }

    protected void invalidateOpenTransitionForInfo(Object info, String reason) {
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            if (snapshot.transitionInfo == info) {
                invalidateOpenTransitionSnapshot(snapshot, reason);
            }
        }
    }

    protected void invalidateAllOpenTransitionSnapshots(String reason) {
        int count = runningOpenTransitions.size();
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            invalidateOpenTransitionSnapshot(snapshot, reason);
        }
        runningOpenTransitions.clear();
        if (count > 0) {
            moduleLog(Log.INFO, TAG, "Cleared Xiaomi OPEN transition snapshots"
                    + ", reason=" + reason
                    + ", count=" + count);
        }
    }

    protected void hookDefaultTransitionImplMerge(ClassLoader classLoader) {
        try {
            Class<?> implementationClass = Class.forName(DEFAULT_TRANSITION_IMPL, false,
                    classLoader);
            Class<?> shellExecutorClass = Class.forName(
                    "com.android.wm.shell.common.ShellExecutor", false, classLoader);
            Class<?> transitionInfoClass = TransitionInfo.class;
            Class<?> finishCallbackClass = Class.forName(
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback",
                    false, classLoader);
            Method mergeAnimation = implementationClass.getDeclaredMethod("mergeAnimation",
                    shellExecutorClass, shellExecutorClass, IBinder.class,
                    transitionInfoClass, ArrayList.class, transitionInfoClass,
                    int.class, finishCallbackClass);
            mergeAnimation.setAccessible(true);
            registerHook(mergeAnimation, "systemui_default_transition_merge", this::trackMiuiOpenCloseMerge);
            moduleLog(Log.INFO, TAG, "Hooked exact DefaultTransitionImpl.mergeAnimation");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook DefaultTransitionImpl.mergeAnimation",
                    throwable);
        }
    }

    protected Object trackMiuiOpenCloseMerge(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (Boolean.TRUE.equals(result)) {
            Object runningInfo = chain.getArg(5);
            invalidateOpenTransitionForInfo(runningInfo, "reverseMerge");
            correlateLegacyBackMerge(runningInfo);
        }
        return result;
    }

    protected void hookBackAnimationSendBackEvent(ClassLoader classLoader) {
        try {
            Method sendBackEvent = requireSystemUiPlatformImpl()
                    .backEventGuardMethod(classLoader);
            registerHook(sendBackEvent, "systemui_back_send_event_guard", this::guardDuplicateBackEvent);
            moduleLog(Log.INFO, TAG, "Hooked BackAnimationController.sendBackEvent guard");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook BackAnimationController.sendBackEvent", throwable);
        }
    }

    protected Object guardDuplicateBackEvent(XposedInterface.Chain chain) throws Throwable {
        int action = ((Number) chain.getArg(0)).intValue();
        if (moduleLegacyBackInjection.get() != null) {
            return chain.proceed();
        }
        if (shouldSuppressDuplicateBack(chain.getThisObject(), action)) {
            return null;
        }
        return chain.proceed();
    }

    protected LegacyBackAttempt armLegacyBackGuard(Object controller, Object runningInfo) {
        long now = SystemClock.uptimeMillis();
        LegacyBackAttempt attempt = new LegacyBackAttempt(
                legacyBackAttemptIds.incrementAndGet(), controller, runningInfo, now);
        synchronized (legacyBackGuardLock) {
            resetLegacyBackGuardLocked();
            legacyBackAttempt = attempt;
            legacyBackGuardPhase = BACK_GUARD_WAIT_MERGE;
            legacyBackGuardDeadlineUptime = now + LEGACY_BACK_MERGE_TIMEOUT_MS;
        }
        moduleLog(Log.INFO, TAG, "Armed Xiaomi interruption BACK correlation"
                + ", attempt=" + attempt.id
                + ", controller=" + shortObject(controller)
                + ", runningInfo=" + shortObject(runningInfo));
        scheduleLegacyBackGuardExpiry(attempt, LEGACY_BACK_MERGE_TIMEOUT_MS);
        return attempt;
    }

    protected void correlateLegacyBackMerge(Object runningInfo) {
        LegacyBackAttempt correlated = null;
        boolean expired = false;
        long now = SystemClock.uptimeMillis();
        synchronized (legacyBackGuardLock) {
            if (legacyBackGuardPhase != BACK_GUARD_WAIT_MERGE
                    || legacyBackAttempt == null) {
                return;
            }
            if (now > legacyBackGuardDeadlineUptime) {
                expired = true;
                correlated = legacyBackAttempt;
                resetLegacyBackGuardLocked();
            } else if (legacyBackAttempt.runningTransitionInfo == runningInfo) {
                correlated = legacyBackAttempt;
                legacyBackGuardPhase = BACK_GUARD_EXPECT_DOWN;
                legacyBackGuardDeadlineUptime = now + DUPLICATE_BACK_PAIR_TIMEOUT_MS;
                suppressedBackDownUptime = 0L;
                suppressedBackDownThread = null;
            }
        }
        if (correlated == null) {
            return;
        }
        if (expired) {
            moduleLog(Log.WARN, TAG, "Expired Xiaomi interruption BACK before merge"
                    + ", attempt=" + correlated.id
                    + ", elapsedMs=" + (now - correlated.startedUptime));
            return;
        }
        moduleLog(Log.INFO, TAG, "Correlated Xiaomi OPEN/CLOSE reverse merge"
                + ", attempt=" + correlated.id
                + ", elapsedMs=" + (now - correlated.startedUptime)
                + ", duplicatePairDeadlineMs=" + DUPLICATE_BACK_PAIR_TIMEOUT_MS);
        scheduleLegacyBackGuardExpiry(correlated, DUPLICATE_BACK_PAIR_TIMEOUT_MS);
    }

    protected boolean shouldSuppressDuplicateBack(Object controller, int action) {
        long now = SystemClock.uptimeMillis();
        LegacyBackAttempt attempt;
        String outcome = null;
        boolean suppress = false;
        synchronized (legacyBackGuardLock) {
            attempt = legacyBackAttempt;
            if (legacyBackGuardPhase == BACK_GUARD_IDLE || attempt == null) {
                return false;
            }
            if (now > legacyBackGuardDeadlineUptime) {
                outcome = "expired";
                resetLegacyBackGuardLocked();
            } else if (legacyBackGuardPhase == BACK_GUARD_WAIT_MERGE) {
                return false;
            } else if (attempt.controller != controller) {
                outcome = "controllerMismatch";
                resetLegacyBackGuardLocked();
            } else if (legacyBackGuardPhase == BACK_GUARD_EXPECT_DOWN) {
                if (action == KEY_ACTION_DOWN) {
                    legacyBackGuardPhase = BACK_GUARD_EXPECT_UP;
                    suppressedBackDownUptime = now;
                    suppressedBackDownThread = Thread.currentThread();
                    legacyBackGuardDeadlineUptime = now + DUPLICATE_BACK_UP_INTERVAL_MS;
                    suppress = true;
                    outcome = "down";
                } else {
                    outcome = "expectedDownGot" + action;
                    resetLegacyBackGuardLocked();
                }
            } else if (legacyBackGuardPhase == BACK_GUARD_EXPECT_UP) {
                long interval = now - suppressedBackDownUptime;
                if (action == KEY_ACTION_UP
                        && suppressedBackDownThread == Thread.currentThread()
                        && interval >= 0L
                        && interval <= DUPLICATE_BACK_UP_INTERVAL_MS) {
                    suppress = true;
                    outcome = "pair";
                    resetLegacyBackGuardLocked();
                } else {
                    outcome = "invalidUp(action=" + action
                            + ", sameThread="
                            + (suppressedBackDownThread == Thread.currentThread())
                            + ", intervalMs=" + interval + ")";
                    resetLegacyBackGuardLocked();
                }
            }
        }
        if ("down".equals(outcome)) {
            scheduleLegacyBackGuardExpiry(attempt, DUPLICATE_BACK_UP_INTERVAL_MS);
        } else if ("pair".equals(outcome)) {
            moduleLog(Log.INFO, TAG, "Consumed one correlated duplicate BACK pair"
                    + ", attempt=" + attempt.id);
        } else if (outcome != null) {
            moduleLog(Log.WARN, TAG, "Released Xiaomi duplicate BACK guard"
                    + ", attempt=" + attempt.id
                    + ", reason=" + outcome);
        }
        return suppress;
    }

    protected void scheduleLegacyBackGuardExpiry(LegacyBackAttempt attempt, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> expireLegacyBackGuard(attempt), Math.max(1L, delayMs));
    }

    protected void expireLegacyBackGuard(LegacyBackAttempt expectedAttempt) {
        int phase;
        synchronized (legacyBackGuardLock) {
            if (legacyBackAttempt != expectedAttempt
                    || SystemClock.uptimeMillis() < legacyBackGuardDeadlineUptime) {
                return;
            }
            phase = legacyBackGuardPhase;
            resetLegacyBackGuardLocked();
        }
        moduleLog(Log.INFO, TAG, "Expired Xiaomi duplicate BACK guard"
                + ", attempt=" + expectedAttempt.id
                + ", phase=" + phase);
    }

    protected void clearLegacyBackGuard(String reason) {
        LegacyBackAttempt attempt;
        int phase;
        synchronized (legacyBackGuardLock) {
            attempt = legacyBackAttempt;
            phase = legacyBackGuardPhase;
            resetLegacyBackGuardLocked();
        }
        if (phase != BACK_GUARD_IDLE && attempt != null) {
            moduleLog(Log.INFO, TAG, "Cleared Xiaomi duplicate BACK guard"
                    + ", attempt=" + attempt.id
                    + ", phase=" + phase
                    + ", reason=" + reason);
        }
    }

    protected void resetLegacyBackGuardLocked() {
        legacyBackAttempt = null;
        legacyBackGuardPhase = BACK_GUARD_IDLE;
        legacyBackGuardDeadlineUptime = 0L;
        suppressedBackDownUptime = 0L;
        suppressedBackDownThread = null;
    }

}
