package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.BackNavigationInfo;
import android.window.TransitionInfo;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;
import com.takekazex.hypertweak.hook.base.dex.DexRuntime;
import com.takekazex.hypertweak.hook.base.dex.DexFeature;
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec;
import java.util.Collections;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Holds prepared transitions until their exact native targets arrive. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public abstract class SystemUiPreparedTransitionRuntime extends SystemUiNavigationRuntime {
    protected abstract String describeRemoteAnimationTargets(Object targets);
    protected abstract void logCrossTaskTargetDiagnostics();
    protected static final class PreparedBackTargetArrival {
        final Object controller;
        final Object transitionToken;
        final Object apps;
        final Object finishedCallback;

        PreparedBackTargetArrival(Object controller, Object transitionToken,
                                  Object apps, Object finishedCallback) {
            this.controller = controller;
            this.transitionToken = transitionToken;
            this.apps = apps;
            this.finishedCallback = finishedCallback;
        }
    }

    protected final class PreparedBackTransitionHold {
        final NativeBackInputMonitor monitor;
        final SystemUiBackGestureDriver.ShellGestureSession session;
        final Handler shellHandler;
        final XposedInterface.Invoker<?, Method> startAnimationInvoker;
        final Object handler;
        final Object controller;
        final Object transitionToken;
        final Object transitionInfo;
        final SurfaceControl.Transaction startTransaction;
        final SurfaceControl.Transaction finishTransaction;
        final Object finishCallback;
        final int transitionDebugId;
        final AtomicBoolean stockResumeAttempted = new AtomicBoolean();

        PreparedBackTransitionHold(
                NativeBackInputMonitor monitor,
                SystemUiBackGestureDriver.ShellGestureSession session,
                Handler shellHandler,
                XposedInterface.Invoker<?, Method> startAnimationInvoker,
                Object handler, Object controller,
                Object transitionToken, Object transitionInfo,
                SurfaceControl.Transaction startTransaction,
                SurfaceControl.Transaction finishTransaction,
                Object finishCallback) {
            this.monitor = monitor;
            this.session = session;
            this.shellHandler = shellHandler;
            this.startAnimationInvoker = startAnimationInvoker;
            this.handler = handler;
            this.controller = controller;
            this.transitionToken = transitionToken;
            this.transitionInfo = transitionInfo;
            this.startTransaction = startTransaction;
            this.finishTransaction = finishTransaction;
            this.finishCallback = finishCallback;
            this.transitionDebugId = readTransitionDebugId(transitionInfo);
        }
    }

    protected final AtomicReference<PreparedBackTransitionHold>
            preparedBackTransitionHold = new AtomicReference<>();
    protected final AtomicReference<PreparedBackTargetArrival>
            preparedBackTargetArrival = new AtomicReference<>();
    protected volatile XposedInterface.Invoker<?, Method>
            preparedBackStartAnimationInvoker;
    protected volatile boolean preparedBackTargetArrivalHookReady;
    protected volatile boolean preparedBackTerminalHookReady;
    protected volatile String preparedBackRunnerDexContainer;

    protected void hookPreparedBackTargetArrival(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(
                    BACK_ANIMATION_CONTROLLER, false, classLoader);
            Class<?> runnerStub = Class.forName(
                    "android.window.IBackAnimationRunner$Stub", false, classLoader);
            Method onAnimationStart = Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                    ? resolvePreparedBackTargetArrivalWithDexKit(
                    classLoader, controllerClass, runnerStub)
                    : requireExactDeclaredMethod(
                    Class.forName(BACK_ANIMATION_CONTROLLER + "$3", false, classLoader),
                    "onAnimationStart", "void",
                    "[Landroid.view.RemoteAnimationTarget;", IBinder.class.getName(),
                    "android.window.IBackAnimationFinishedCallback");
            Class<?> adapterClass = onAnimationStart.getDeclaringClass();
            validatePreparedBackTargetArrivalAdapter(
                    adapterClass, controllerClass, runnerStub);
            registerHook(onAnimationStart, "systemui_back_prepared_target_arrival", this::onPreparedBackTargetArrival);
            preparedBackTargetArrivalHookReady = true;
            moduleLog(Log.INFO, TAG,
                    "Hooked prepared-back remote-target arrival handoff"
                            + ", adapter=" + adapterClass.getName());
        } catch (Throwable throwable) {
            preparedBackTargetArrivalHookReady = false;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook prepared-back remote-target arrival handoff",
                    throwable);
        }
    }

    protected Method resolvePreparedBackTargetArrivalWithDexKit(
            ClassLoader classLoader, Class<?> controllerClass, Class<?> runnerStub)
            throws Throwable {
        // BackAnimationController lives in a shared Shell jar on Android 17. Resolve the
        // actual loaded container, while the common runtime owns native loading and lifetime.
        String dexContainer = resolveDexContainerForLoadedClass(controllerClass);
        android.content.Context context = resolveCurrentApplicationContext(classLoader);
        Method resolved = DexRuntime.INSTANCE.resolve(
                new DexFeature() {
                    @Override public String getId() { return "backgesture.prepared_target_arrival"; }
                    @Override public int getSchema() { return 1; }
                }, dexContainer,
                DexRuntime.INSTANCE.cacheDirectory(context == null ? null : context.getApplicationInfo()),
                false, false, null, session -> session.dexKit(bridge -> {
            Method match = null;
            FindMethod query = FindMethod.create().matcher(
                    MethodMatcher.create()
                            .name("onAnimationStart")
                            .paramCount(3));
            for (MethodData candidate : bridge.findMethod(query)) {
                Method method;
                try {
                    method = candidate.getMethodInstance(classLoader);
                    validatePreparedBackTargetArrivalSignature(method);
                    validatePreparedBackTargetArrivalAdapter(
                            method.getDeclaringClass(), controllerClass, runnerStub);
                } catch (Throwable ignored) {
                    // Name and arity are only a search key; require the full owner contract.
                    continue;
                }
                if (match != null && !match.equals(method)) {
                    throw new IllegalStateException(
                            "Ambiguous back animation runner adapters: "
                                    + match + " and " + method);
                }
                match = method;
            }
            return match == null ? Collections.<String, String>emptyMap()
                    : Collections.singletonMap("arrival", MethodDescriptorCodec.INSTANCE.descriptor(match));
        }), (values, cached) -> {
            try {
                String descriptor = values.get("arrival");
                if (descriptor == null || values.size() != 1) return null;
                Method method = MethodDescriptorCodec.INSTANCE.method(descriptor, classLoader);
                if (method == null) return null;
                validatePreparedBackTargetArrivalSignature(method);
                validatePreparedBackTargetArrivalAdapter(method.getDeclaringClass(), controllerClass, runnerStub);
                return method;
            } catch (Throwable rejected) {
                return null;
            }
        });
        if (resolved == null) {
            throw new NoSuchMethodException(
                    "No exact BackAnimationController runner adapter found");
        }
        return resolved;
    }

    protected String resolveDexContainerForLoadedClass(Class<?> targetClass)
            throws Throwable {
        String cached = preparedBackRunnerDexContainer;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        String targetName = targetClass.getName();
        List<String> matches = new ArrayList<>();
        ClassLoader loader = targetClass.getClassLoader();
        while (loader != null) {
            Object pathList;
            Object dexElements;
            try {
                pathList = readField(loader, "pathList");
                dexElements = readField(pathList, "dexElements");
            } catch (Throwable ignored) {
                loader = loader.getParent();
                continue;
            }
            int elementCount = Array.getLength(dexElements);
            for (int index = 0; index < elementCount; index++) {
                Object element = Array.get(dexElements, index);
                Object dexFile;
                try {
                    dexFile = readField(element, "dexFile");
                } catch (Throwable ignored) {
                    continue;
                }
                if (dexFile == null || !dexFileDeclaresClass(dexFile, targetName)) {
                    continue;
                }
                Method getName = dexFile.getClass().getMethod("getName");
                String path = String.valueOf(getName.invoke(dexFile));
                if (!path.isEmpty() && !matches.contains(path)) {
                    matches.add(path);
                }
            }
            loader = loader.getParent();
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected one loaded dex container for " + targetName
                            + ", found=" + matches);
        }
        cached = matches.get(0);
        preparedBackRunnerDexContainer = cached;
        moduleLog(Log.INFO, TAG,
                "Resolved prepared-back runner dex container=" + cached);
        return cached;
    }

    protected static boolean dexFileDeclaresClass(Object dexFile, String targetName)
            throws ReflectiveOperationException {
        Method entriesMethod = dexFile.getClass().getMethod("entries");
        Object entriesObject = entriesMethod.invoke(dexFile);
        if (!(entriesObject instanceof Enumeration<?>)) {
            return false;
        }
        Enumeration<?> entries = (Enumeration<?>) entriesObject;
        while (entries.hasMoreElements()) {
            if (targetName.equals(entries.nextElement())) {
                return true;
            }
        }
        return false;
    }

    protected static void validatePreparedBackTargetArrivalSignature(Method method)
            throws NoSuchMethodException {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (!"onAnimationStart".equals(method.getName())
                || method.getReturnType() != void.class
                || parameterTypes.length != 3
                || !"[Landroid.view.RemoteAnimationTarget;".equals(
                parameterTypes[0].getName())
                || !IBinder.class.getName().equals(parameterTypes[1].getName())
                || !"android.window.IBackAnimationFinishedCallback".equals(
                parameterTypes[2].getName())) {
            throw new NoSuchMethodException(
                    "Unexpected back animation arrival signature: " + method);
        }
    }

    protected static void validatePreparedBackTargetArrivalAdapter(
            Class<?> adapterClass, Class<?> controllerClass, Class<?> runnerStub)
            throws ReflectiveOperationException {
        if (adapterClass.getSuperclass() != runnerStub
                || adapterClass.getDeclaredField("this$0").getType() != controllerClass) {
            throw new NoSuchMethodException(
                    "Unexpected back animation adapter owner: " + adapterClass.getName());
        }
    }

    protected void hookPreparedBackTerminal(Class<?> controllerClass) {
        try {
            Method finishBackNavigation = controllerClass.getDeclaredMethod(
                    "finishBackNavigation", boolean.class);
            finishBackNavigation.setAccessible(true);
            registerHook(finishBackNavigation, "systemui_back_prepared_terminal", this::onPreparedBackTerminal);
            preparedBackTerminalHookReady = true;
            moduleLog(Log.INFO, TAG,
                    "Hooked prepared-back terminal handoff");
        } catch (Throwable throwable) {
            preparedBackTerminalHookReady = false;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook prepared-back terminal handoff",
                    throwable);
        }
    }

    protected Object onPreparedBackTargetArrival(XposedInterface.Chain chain)
            throws Throwable {
        Object controller = null;
        Object apps = null;
        Object token = null;
        Object finishedCallback = null;
        int navigationType = -1;
        try {
            controller = readField(chain.getThisObject(), "this$0");
            apps = chain.getArg(0);
            token = chain.getArg(1);
            finishedCallback = chain.getArg(2);
            Object navigation = readFieldOrNull(controller, "mBackNavigationInfo");
            if (navigation instanceof BackNavigationInfo) {
                navigationType = ((BackNavigationInfo) navigation).getType();
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture prepared-back target arrival",
                    throwable);
        }
        Object result = chain.proceed();
        if (navigationType == TYPE_CROSS_TASK) {
            moduleLog(Log.INFO, TAG, "Cross-task remote targets arrived"
                    + ", token=" + shortObject(token)
                    + ", apps=" + describeRemoteAnimationTargets(apps)
                    + ", finishedCallback=" + shortObject(finishedCallback));
            logCrossTaskTargetDiagnostics();
        }
        if (controller != null && token != null) {
            PreparedBackTargetArrival arrival = new PreparedBackTargetArrival(
                    controller, token, apps, finishedCallback);
            preparedBackTargetArrival.set(arrival);
            schedulePreparedBackTransitionResume(
                    preparedBackTransitionHold.get(), arrival, false);
        }
        return result;
    }

    protected Object onPreparedBackTerminal(
            XposedInterface.Chain chain) throws Throwable {
        PreparedBackTransitionHold hold = preparedBackTransitionHold.get();
        boolean exactTerminal = false;
        try {
            exactTerminal = hold != null
                    && hold.controller == chain.getThisObject()
                    && isExactPreparedBackSession(hold)
                    && isHeldPreparedBackTransitionUntouched(hold);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to authenticate prepared-back terminal",
                    throwable);
        }
        Object result = chain.proceed();
        if (exactTerminal) {
            schedulePreparedBackTransitionResume(hold, null, true);
        }
        return result;
    }

    protected boolean isExactPreparedBackTransition(
            Object handler, Object navigation, Object info) throws Exception {
        if (!(navigation instanceof BackNavigationInfo)) {
            return false;
        }
        int focusedTaskId = ((BackNavigationInfo) navigation).getFocusedTaskId();
        if (focusedTaskId < 0) {
            return false;
        }
        Object transitions = readField(handler, "mTransitions");
        Object organizer = readField(transitions, "mOrganizer");
        Object taskInfo = invokeAnyMethod(organizer, "getRunningTaskInfo",
                new Object[]{Integer.valueOf(focusedTaskId)});
        if (taskInfo == null
                || readIntFieldOrDefault(taskInfo, "taskId", -1)
                != focusedTaskId
                || resolveTaskInfoActivityType(taskInfo)
                != ACTIVITY_TYPE_STANDARD) {
            return false;
        }
        int windowingMode = resolveTaskInfoWindowingMode(taskInfo);
        if (windowingMode != WINDOWING_MODE_FREEFORM
                && windowingMode != WINDOWING_MODE_FULLSCREEN) {
            return false;
        }
        int displayId = readIntFieldOrDefault(taskInfo, "displayId", -1);
        Object configuration = readField(taskInfo, "configuration");
        Object windowConfiguration = readField(
                configuration, "windowConfiguration");
        Object taskBoundsObject = invokeAnyMethod(
                windowConfiguration, "getBounds", new Object[0]);
        Object changesObject = readTransitionInfoChanges(info);
        Object rootCountObject = readTransitionInfoRootCount(info);
        if (displayId < 0 || !(taskBoundsObject instanceof Rect)
                || ((Rect) taskBoundsObject).isEmpty()
                || !(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 2
                || !(rootCountObject instanceof Number)
                || ((Number) rootCountObject).intValue() != 1) {
            throw new IllegalStateException(
                    "prepared task geometry unavailable"
                            + ", taskId=" + focusedTaskId
                            + ", displayId=" + displayId
                            + ", bounds=" + shortObject(taskBoundsObject)
                            + ", changes=" + shortObject(changesObject)
                            + ", roots=" + shortObject(rootCountObject));
        }
        Rect taskBounds = (Rect) taskBoundsObject;
        Object root = readTransitionInfoRoot(info, 0);
        Object rootLeashObject = readTransitionRootLeash(root);
        Object rootOffsetObject = readTransitionRootOffset(root);
        if (!(rootLeashObject instanceof SurfaceControl)
                || !((SurfaceControl) rootLeashObject).isValid()
                || !(rootOffsetObject instanceof Point)
                || ((Point) rootOffsetObject).x != taskBounds.left
                || ((Point) rootOffsetObject).y != taskBounds.top) {
            throw new IllegalStateException(
                    "prepared root mismatch"
                            + ", taskId=" + focusedTaskId
                            + ", bounds=" + taskBounds
                            + ", root=" + shortObject(rootLeashObject)
                            + ", offset=" + shortObject(rootOffsetObject));
        }
        SurfaceControl rootLeash = (SurfaceControl) rootLeashObject;
        final int closingFlags = 0x08000000
                | FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK;
        final int openingFlags = FLAG_BACK_GESTURE_ANIMATED
                | FLAG_FILLS_TASK | FLAG_IS_OCCLUDED;
        Object closingComponent = null;
        Object openingComponent = null;
        SurfaceControl closingLeash = null;
        SurfaceControl openingLeash = null;
        Object closingChange = null;
        Object openingChange = null;
        boolean fixedRotationOpening = false;
        int changeIndex = 0;
        for (Object change : (List<?>) changesObject) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object changeTaskInfo = readTransitionChangeTaskInfo(change);
            Object component = readTransitionChangeActivityComponent(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : -1;
            if (changeTaskInfo != null || component == null
                    || !(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || surfacesAreSame(
                    (SurfaceControl) leashObject, rootLeash)
                    || !taskBounds.equals(startBoundsObject)
                    || !taskBounds.equals(endBoundsObject)
                    || !(startDisplayObject instanceof Number)
                    || !(endDisplayObject instanceof Number)
                    || ((Number) startDisplayObject).intValue() != displayId
                    || ((Number) endDisplayObject).intValue() != displayId) {
                throw new IllegalStateException(
                        "prepared Activity change mismatch"
                                + ", taskId=" + focusedTaskId
                                + ", changeIndex=" + changeIndex
                                + ", mode=" + mode
                                + ", flags=0x" + Integer.toHexString(flags)
                                + ", taskInfo=" + shortObject(changeTaskInfo)
                                + ", component=" + shortObject(component)
                                + ", leash=" + shortObject(leashObject)
                                + ", startBounds="
                                + shortObject(startBoundsObject)
                                + ", endBounds=" + shortObject(endBoundsObject)
                                + ", startDisplay="
                                + shortObject(startDisplayObject)
                                + ", endDisplay="
                                + shortObject(endDisplayObject));
            }
            if (mode == TRANSIT_CHANGE && flags == closingFlags
                    && closingComponent == null) {
                closingComponent = component;
                closingLeash = (SurfaceControl) leashObject;
                closingChange = change;
            } else if (mode == TRANSIT_TO_FRONT && openingComponent == null
                    && (flags == openingFlags
                    || (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                    && windowingMode == WINDOWING_MODE_FULLSCREEN
                    && flags == (openingFlags & ~FLAG_FILLS_TASK)))) {
                openingComponent = component;
                openingLeash = (SurfaceControl) leashObject;
                openingChange = change;
                fixedRotationOpening = flags != openingFlags;
            } else {
                throw new IllegalStateException(
                        "prepared Activity role mismatch"
                                + ", taskId=" + focusedTaskId
                                + ", changeIndex=" + changeIndex
                                + ", mode=" + mode
                                + ", flags=0x" + Integer.toHexString(flags));
            }
            changeIndex++;
        }
        if (fixedRotationOpening) {
            // The server's exact fixed-rotation role correction retains the
            // opening Activity's missing FILLS_TASK bit. Wait for the same
            // adapter here too; a prepared-before-targets race must not strand it.
            if (displayId != 0 || taskBounds.left != 0 || taskBounds.top != 0) {
                return false;
            }
            if (!isExactAndroid17FixedRotationPair(
                    closingChange, openingChange, closingComponent, openingComponent,
                    closingLeash, openingLeash, focusedTaskId, taskBounds)) {
                return false;
            }
        }
        return closingComponent != null && openingComponent != null
                && !surfacesAreSame(closingLeash, openingLeash);
    }

    protected boolean isExactAndroid17FixedRotationPair(
            Object closingChange, Object openingChange,
            Object closingComponent, Object openingComponent,
            SurfaceControl closingLeash, SurfaceControl openingLeash,
            int focusedTaskId, Rect taskBounds) throws Exception {
        if (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL
                || closingChange == null || openingChange == null
                || closingLeash == null || openingLeash == null
                || surfacesAreSame(closingLeash, openingLeash)) {
            return false;
        }
        for (Object change : new Object[]{closingChange, openingChange}) {
            if (invokeAnyMethod(change, "getParent", new Object[0]) != null
                    || invokeAnyMethod(change, "getLastParent", new Object[0]) != null
                    || !taskBounds.equals(readTransitionChangeStartAbsBounds(change))
                    || !taskBounds.equals(readTransitionChangeEndAbsBounds(change))
                    || !Integer.valueOf(0).equals(readTransitionChangeStartDisplayId(change))
                    || !Integer.valueOf(0).equals(readTransitionChangeEndDisplayId(change))) {
                return false;
            }
            Object activityInfo = invokeAnyMethod(
                    change, "getActivityTransitionInfo", new Object[0]);
            if (activityInfo == null
                    || !Integer.valueOf(focusedTaskId).equals(
                    invokeAnyMethod(activityInfo, "getTaskId", new Object[0]))) {
                return false;
            }
            Object activityComponent = invokeAnyMethod(
                    activityInfo, "getComponent", new Object[0]);
            if (!Objects.equals(activityComponent,
                    change == closingChange ? closingComponent : openingComponent)) {
                return false;
            }
        }
        int closingStartRotation = readRequiredTransitionInt(
                closingChange, "getStartRotation");
        int closingEndRotation = readRequiredTransitionInt(
                closingChange, "getEndRotation");
        int closingFixedRotation = readRequiredTransitionInt(
                closingChange, "getEndFixedRotation");
        int openingStartRotation = readRequiredTransitionInt(
                openingChange, "getStartRotation");
        int openingEndRotation = readRequiredTransitionInt(
                openingChange, "getEndRotation");
        int openingFixedRotation = readRequiredTransitionInt(
                openingChange, "getEndFixedRotation");
        return closingStartRotation == closingEndRotation
                && closingFixedRotation == -1
                && openingFixedRotation >= 0
                && isQuarterTurnRotation(openingStartRotation, openingEndRotation)
                && isQuarterTurnRotation(closingEndRotation, openingFixedRotation);
    }

    protected int readRequiredTransitionInt(Object change, String method)
            throws Exception {
        Object value = invokeAnyMethod(change, method, new Object[0]);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("TransitionInfo " + method + " unavailable");
        }
        return ((Number) value).intValue();
    }

    protected static boolean isQuarterTurnRotation(int from, int to) {
        int delta = Math.floorMod(to - from, 4);
        return delta == 1 || delta == 3;
    }

    protected void hookPreparedBackTransitionDecision(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method startAnimation = requireExactDeclaredMethod(handlerClass,
                    "startAnimation", "boolean", IBinder.class.getName(),
                    TransitionInfo.class.getName(),
                    SurfaceControl.Transaction.class.getName(),
                    SurfaceControl.Transaction.class.getName(),
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback");
            preparePreparedBackStartAnimationInvoker(startAnimation);
            registerHook(startAnimation, "systemui_back_prepared_transition_decision", this::holdPreparedBackTransitionUntilTargets);
            moduleLog(Log.INFO, TAG,
                    "Hooked prepared-back transition target ordering");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook prepared-back transition target ordering",
                    throwable);
        }
    }

    protected void preparePreparedBackStartAnimationInvoker(Method startAnimation) {
        XposedInterface.Invoker<?, Method> invoker = getInvoker(startAnimation);
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
        preparedBackStartAnimationInvoker = invoker;
    }

    protected Object holdPreparedBackTransitionUntilTargets(
            XposedInterface.Chain chain) throws Throwable {
        if (!preparedBackTargetArrivalHookReady
                || !preparedBackTerminalHookReady
                || preparedBackStartAnimationInvoker == null) {
            return chain.proceed();
        }
        PreparedBackTransitionHold hold;
        try {
            Object info = chain.getArg(1);
            Object type = readTransitionInfoType(info);
            if (!(type instanceof Number)
                    || ((Number) type).intValue() != TRANSIT_PREDICTIVE_BACK) {
                return chain.proceed();
            }
            if (!(chain.getArg(2) instanceof SurfaceControl.Transaction)
                    || !(chain.getArg(3) instanceof SurfaceControl.Transaction)
                    || chain.getArg(0) == null || chain.getArg(4) == null) {
                return chain.proceed();
            }
            Object handler = chain.getThisObject();
            Object controller = readField(handler, "this$0");
            Object transitionToken = chain.getArg(0);
            if (readField(controller, "mBackTransitionHandler") != handler
                    || readField(controller, "mApps") != null
                    || readField(handler, "mPrepareOpenTransition")
                    != transitionToken
                    || readField(handler, "mClosePrepareTransition") != null
                    || readField(handler, "mOpenTransitionInfo") != null
                    || readField(handler, "mFinishOpenTransaction") != null
                    || readField(handler, "mFinishOpenTransitionCallback") != null
                    || readField(handler, "mOnAnimationFinishCallback") != null
                    || Boolean.TRUE.equals(readField(
                    handler, "mCloseTransitionRequested"))) {
                return chain.proceed();
            }
            Object navigation = readField(controller, "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigation);
            if (!(navigationType instanceof Number)
                    || ((Number) navigationType).intValue()
                    != TYPE_CROSS_ACTIVITY) {
                return chain.proceed();
            }
            if (!isExactPreparedBackTransition(
                    handler, navigation, info)) {
                return chain.proceed();
            }
            NativeBackInputMonitor exactMonitor = null;
            SystemUiBackGestureDriver.ShellGestureSession exactSession = null;
            Object currentTracker = readField(controller, "mCurrentTracker");
            for (NativeBackInputMonitor monitor
                    : new ArrayList<>(nativeInputMonitors.values())) {
                SystemUiBackGestureDriver.ShellGestureSession session =
                        monitor.driver.activeShellSession;
                if (session == null || session.controller != controller
                        || session.navigation != navigation
                        || session.tracker != currentTracker
                        || session.completionConsumed.get()
                        || !monitor.driver.isShellSessionOwnerCurrent(session)) {
                    continue;
                }
                if (exactSession != null) {
                    return chain.proceed();
                }
                exactMonitor = monitor;
                exactSession = session;
            }
            if (exactSession == null) {
                return chain.proceed();
            }
            Object shellExecutor = readField(controller, "mShellExecutor");
            Object shellHandler = readField(shellExecutor, "mHandler");
            if (shellExecutor != exactSession.executor
                    || !(shellHandler instanceof Handler)
                    || !((Handler) shellHandler).getLooper().isCurrentThread()) {
                return chain.proceed();
            }
            hold = new PreparedBackTransitionHold(
                    exactMonitor, exactSession, (Handler) shellHandler,
                    preparedBackStartAnimationInvoker,
                    handler, controller,
                    transitionToken, info,
                    (SurfaceControl.Transaction) chain.getArg(2),
                    (SurfaceControl.Transaction) chain.getArg(3),
                    chain.getArg(4));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to qualify prepared-back transition hold",
                    throwable);
            return chain.proceed();
        }
        if (!preparedBackTransitionHold.compareAndSet(null, hold)) {
            return chain.proceed();
        }
        moduleLog(Log.INFO, TAG,
                "Held prepared-back transition until remote targets"
                        + ", transitionId=" + hold.transitionDebugId
                        + ", shellSessionId=" + hold.session.id
                        + ", token=" + shortObject(hold.transitionToken));
        schedulePreparedBackTransitionResume(
                hold, preparedBackTargetArrival.get(), false);
        return Boolean.TRUE;
    }

    protected void schedulePreparedBackTransitionResume(
            PreparedBackTransitionHold hold,
            PreparedBackTargetArrival arrival, boolean terminal) {
        if (hold == null || preparedBackTransitionHold.get() != hold
                || (!terminal && (arrival == null
                || arrival.controller != hold.controller
                || arrival.transitionToken != hold.transitionToken))) {
            return;
        }
        try {
            if (!hold.shellHandler.post(() -> resumePreparedBackTransition(
                    hold, arrival, terminal))) {
                throw new IllegalStateException("Shell Handler rejected resume");
            }
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to queue held prepared-back transition resume"
                            + ", transitionId=" + hold.transitionDebugId
                            + ", event=" + (terminal ? "terminal" : "targets"),
                    throwable);
        }
    }

    protected void resumePreparedBackTransition(
            PreparedBackTransitionHold hold,
            PreparedBackTargetArrival arrival, boolean terminal) {
        String event = terminal ? "terminal" : "targets";
        try {
            if (preparedBackTransitionHold.get() != hold
                    || (terminal
                    ? !isHeldPreparedBackTransitionTerminalReady(hold)
                    : !isHeldPreparedBackTransitionUntouched(hold))) {
                return;
            }
            Object controllerApps = readField(hold.controller, "mApps");
            if (!terminal) {
                PreparedBackTargetArrival latest =
                        preparedBackTargetArrival.get();
                if (latest != arrival) {
                    schedulePreparedBackTransitionResume(
                            hold, latest, false);
                    return;
                }
                if (controllerApps != arrival.apps
                        || readField(hold.controller,
                        "mBackAnimationFinishedCallback")
                        != arrival.finishedCallback
                        || !isExactPreparedBackSession(hold)) {
                    return;
                }
            } else if (controllerApps != null
                    || readField(hold.controller, "mBackNavigationInfo") != null) {
                return;
            }
            if (!hold.stockResumeAttempted.compareAndSet(false, true)) {
                return;
            }
            Object result;
            try {
                result = hold.startAnimationInvoker.invoke(
                        hold.handler, hold.transitionToken,
                        hold.transitionInfo, hold.startTransaction,
                        hold.finishTransaction, hold.finishCallback);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                throw cause == null ? exception : cause;
            }
            if (!Boolean.TRUE.equals(result)) {
                moduleLog(Log.ERROR, TAG,
                        "Stock handler declined held prepared-back transition"
                                + ", transitionId=" + hold.transitionDebugId
                                + ", shellSessionId=" + hold.session.id
                                + ", event=" + event);
                return;
            }
            if (!preparedBackTransitionHold.compareAndSet(hold, null)) {
                moduleLog(Log.ERROR, TAG,
                        "Lost held prepared-back ownership after stock resume"
                                + ", transitionId=" + hold.transitionDebugId
                                + ", shellSessionId=" + hold.session.id);
                return;
            }
            PreparedBackTargetArrival consumedArrival = arrival != null
                    ? arrival : preparedBackTargetArrival.get();
            if (consumedArrival != null
                    && consumedArrival.controller == hold.controller
                    && consumedArrival.transitionToken == hold.transitionToken) {
                preparedBackTargetArrival.compareAndSet(
                        consumedArrival, null);
            }
            moduleLog(Log.INFO, TAG,
                    "Resumed held prepared-back transition through stock handler"
                            + ", transitionId=" + hold.transitionDebugId
                            + ", shellSessionId=" + hold.session.id
                            + ", event=" + event
                            + ", apps=" + shortObject(controllerApps));
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to resume held prepared-back transition"
                            + ", transitionId=" + hold.transitionDebugId
                            + ", shellSessionId=" + hold.session.id
                            + ", event=" + event,
                    throwable);
        }
    }

    protected boolean isExactPreparedBackSession(
            PreparedBackTransitionHold hold) throws Exception {
        return hold.monitor.driver.activeShellSession == hold.session
                && !hold.session.completionConsumed.get()
                && hold.monitor.driver.isShellSessionOwnerCurrent(hold.session)
                && readField(hold.controller, "mBackNavigationInfo")
                == hold.session.navigation
                && (readField(hold.controller, "mCurrentTracker")
                == hold.session.tracker
                || readField(hold.controller, "mQueuedTracker")
                == hold.session.tracker);
    }

    protected boolean isHeldPreparedBackTransitionUntouched(
            PreparedBackTransitionHold hold) throws Exception {
        return isHeldPreparedBackTransitionBase(hold)
                && readField(hold.handler, "mClosePrepareTransition") == null
                && !Boolean.TRUE.equals(readField(
                hold.handler, "mCloseTransitionRequested"));
    }

    protected boolean isHeldPreparedBackTransitionTerminalReady(
            PreparedBackTransitionHold hold) throws Exception {
        return isHeldPreparedBackTransitionBase(hold);
    }

    protected boolean isHeldPreparedBackTransitionBase(
            PreparedBackTransitionHold hold) throws Exception {
        Object type = readTransitionInfoType(hold.transitionInfo);
        return readField(hold.handler, "this$0") == hold.controller
                && readField(hold.controller, "mBackTransitionHandler")
                == hold.handler
                && type instanceof Number
                && ((Number) type).intValue() == TRANSIT_PREDICTIVE_BACK
                && readField(hold.handler, "mPrepareOpenTransition")
                == hold.transitionToken
                && readField(hold.handler, "mOpenTransitionInfo") == null
                && readField(hold.handler, "mFinishOpenTransaction") == null
                && readField(hold.handler,
                "mFinishOpenTransitionCallback") == null
                && readField(hold.handler, "mOnAnimationFinishCallback") == null;
    }

    protected String describePreparedBackTransitionHold(
            PreparedBackTransitionHold hold) {
        return "transitionId=" + hold.transitionDebugId
                + ", shellSessionId=" + hold.session.id
                + ", stockResumeAttempted="
                + hold.stockResumeAttempted.get();
    }

    /**
     * Restyles the native cross-activity predictive-back animation into the miuix slide
     * when the preference is on: the closing surface follows the finger full-width with
     * no scale and no fade, the entering surface parallaxes in from a quarter width
     * behind at alpha 0.9 -> 1 with its dim scrim tracking the drag, and the commit
     * settles on a cubic ease-out. Exact freeform puts Xiaomi's task-local radius on
     * the prepared root and both Activity targets; the default and slide geometries
     * also inverse-map a fixed task crop into each moving target. Fullscreen still
     * clears only the revealed lower page. Targets, letterboxes, and the finish
     * lifecycle stay native.
     * Cross-task and return-to-home are untouched. The independent apply hook adopts
     * exact freeform ColorLayers and normalizes those corners whether or not the slide
     * preference is enabled.
     */
}
