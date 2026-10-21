package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.window.TransitionInfo;

import java.lang.reflect.Method;

/** SystemUI side of the authenticated native launcher protocol. */
public abstract class NativeLauncherBridgeRuntime extends SystemUiHookRuntime {

    protected Method requireExactDeclaredMethod(
            Class<?> owner, String methodName, String returnTypeName,
            String... parameterTypeNames) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())
                    || !returnTypeName.equals(method.getReturnType().getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != parameterTypeNames.length) {
                continue;
            }
            boolean exact = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!parameterTypeNames[index].equals(
                        parameterTypes[index].getName())) {
                    exact = false;
                    break;
                }
            }
            if (exact) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + methodName
                + "(" + String.join(",", parameterTypeNames) + "):"
                + returnTypeName);
    }

    protected int resolveTaskInfoActivityType(Object taskInfo) {
        try {
            Object directValue = invokeAnyMethod(
                    taskInfo, "getActivityType", new Object[0]);
            if (directValue instanceof Number) {
                return ((Number) directValue).intValue();
            }
            Object configuration = readField(taskInfo, "configuration");
            Object windowConfiguration = readField(
                    configuration, "windowConfiguration");
            Object value = invokeAnyMethod(windowConfiguration,
                    "getActivityType", new Object[0]);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return readIntFieldOrDefault(taskInfo, "topActivityType", -1);
    }

    protected int resolveTaskInfoWindowingMode(Object taskInfo) {
        try {
            Object directValue = invokeAnyMethod(
                    taskInfo, "getWindowingMode", new Object[0]);
            if (directValue instanceof Number) {
                return ((Number) directValue).intValue();
            }
            Object configuration = readField(taskInfo, "configuration");
            Object windowConfiguration = readField(
                    configuration, "windowConfiguration");
            Object value = invokeAnyMethod(windowConfiguration,
                    "getWindowingMode", new Object[0]);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    protected boolean isMiuiHomeLauncherOpenType(String typeName) {
        return "OPEN_FROM_ELEMENT".equals(typeName)
                || "OPEN_FROM_HOME".equals(typeName)
                || "OPEN_FROM_RECENTS".equals(typeName);
    }

    protected int readTransitionDebugId(Object infoOrExpose) {
        if (infoOrExpose == null) {
            return -1;
        }
        try {
            Object infoObject = infoOrExpose;
            if (!(infoObject instanceof TransitionInfo)) {
                infoObject = invokeAnyMethod(infoObject, "unbox", new Object[0]);
            }
            return infoObject instanceof TransitionInfo
                    ? ((TransitionInfo) infoObject).getDebugId() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    protected void sendAuthenticatedMiuiHomeOpenBreakCommand(
            Context context, long generation, long attemptId,
            SystemUiBackGestureDriver driver, Object releaseController) {
        // Close the local admission gate as soon as one committed command is emitted. The
        // MiuiHome receiver independently revalidates its native controller before acting.
        if (miuiLauncherOpenBreakGeneration == generation) {
            miuiLauncherOpenBreakAvailable = false;
        }
        Context appContext = context.getApplicationContext();
        Intent commandIntent = new Intent(MODULE_MIUI_HOME_OPEN_BREAK_COMMAND);
        commandIntent.setPackage(MIUI_HOME);
        commandIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, generation);
        commandIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT, attemptId);
        Bundle options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle();
        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                driver.onLauncherOpenBreakCommandResult(
                        generation, attemptId, getResultCode(), getResultData(),
                        releaseController);
            }
        };
        appContext.sendOrderedBroadcast(commandIntent, null, options,
                resultReceiver, new Handler(Looper.getMainLooper()),
                LAUNCHER_OPEN_BREAK_RESULT_NO_RECEIVER, "noReceiver", null);
        moduleLog(Log.INFO, TAG, "Sent authenticated MiuiHome launcher OPEN break command"
                + ", generation=" + generation
                + ", attempt=" + attemptId
                + ", ordered=true");
    }

    protected int readMotionEventId(MotionEvent event) throws Exception {
        Object value = invokeAnyMethod(event, "getId", new Object[0]);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("MotionEvent.getId returned "
                    + shortObject(value));
        }
        return ((Number) value).intValue();
    }

    protected int readMotionEventDisplayId(MotionEvent event) throws Exception {
        Object value = invokeAnyMethod(event, "getDisplayId", new Object[0]);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    protected void onSystemUiInputMonitorAttached(Context context) {
        int count = systemUiInputArbiterMonitorCount.incrementAndGet();
        publishSystemUiInputArbiterState(context, count > 0, "monitorAttached");
    }

    protected void onSystemUiInputMonitorDetached(Context context) {
        int count = systemUiInputArbiterMonitorCount.decrementAndGet();
        if (count < 0) {
            systemUiInputArbiterMonitorCount.set(0);
            count = 0;
        }
        publishSystemUiInputArbiterState(context, count > 0, "monitorDetached");
    }

    protected void publishSystemUiInputArbiterState(Context context, boolean ready,
                                                    String reason) {
        if (context == null) {
            return;
        }
        try {
            ready = ready && com.takekazex.hypertweak.hook.Preferences.INSTANCE.backGestureEnabled();
            boolean contextualSearchEnabled =
                    isContextualSearchLongPressRuntimeEnabled();
            Intent stateIntent = new Intent(systemUiInputArbiterStateAction());
            stateIntent.setPackage(MIUI_HOME);
            stateIntent.putExtra(EXTRA_INPUT_ARBITER_READY, ready);
            stateIntent.putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                    systemUiInputArbiterGeneration);
            stateIntent.putExtra(EXTRA_CONTEXTUAL_SEARCH_ENABLED,
                    contextualSearchEnabled);
            stateIntent.putExtra("sender_uid", Process.myUid());
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.getApplicationContext().sendBroadcast(stateIntent, null, options);
            moduleLog(Log.INFO, TAG, "Published SystemUI input-arbiter state"
                    + ", ready=" + ready
                    + ", contextualSearch=" + contextualSearchEnabled
                    + ", generation=" + systemUiInputArbiterGeneration
                    + ", monitors=" + systemUiInputArbiterMonitorCount.get()
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to publish SystemUI input-arbiter state"
                    + ", reason=" + reason, throwable);
        }
    }

    protected void publishStandardReturnHomeCommit(
            int taskId, int transitionDebugId,
            Object compositionController, Object exactFinishCallback,
            boolean elementBoundaryOnly) {
        Context context = miuiOverviewReceiverContext;
        long attempt = systemUiReturnHomeCommitAttemptIds.incrementAndGet();
        SystemUiReturnHomeCommitIdentity identity =
                systemUiReturnHomeCommitIdentity.get();
        MiuiHomeAcceptedInputToken input = identity == null
                ? null : identity.input;
        if (context == null || taskId < 0 || transitionDebugId < 0
                || systemUiInputArbiterMonitorCount.get() <= 0
                || identity == null || input == null
                || identity.taskId != taskId
                || identity.controller != compositionController
                || input.generation
                != systemUiInputArbiterGeneration) {
            moduleLog(Log.ERROR, TAG,
                    "Could not publish standard return-home commit"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId
                            + ", context=" + shortObject(context)
                            + ", monitors="
                            + systemUiInputArbiterMonitorCount.get()
                            + ", identityTaskId="
                            + (identity == null ? -1 : identity.taskId)
                            + ", sameController="
                            + (identity != null
                            && identity.controller
                            == compositionController)
                            + ", eventId="
                            + (input == null ? 0 : input.eventId)
                            + ", inputGeneration="
                            + (input == null ? 0L : input.generation));
            return;
        }
        IBinder runnerSession;
        try {
            runnerSession = captureReturnHomeRunnerSession(
                    compositionController);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Could not capture exact Shell return-home runner session"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId,
                    throwable);
            return;
        }
        StandardReturnHomeCommitSignal signal =
                new StandardReturnHomeCommitSignal(
                        attempt, systemUiInputArbiterGeneration,
                        taskId, transitionDebugId,
                        input.eventId, input.downTime,
                        input.deviceId, input.source,
                        input.displayId, input.edge, runnerSession,
                        elementBoundaryOnly);
        Object finishCallback = exactFinishCallback;
        if (!elementBoundaryOnly) {
            try {
                Object handler = readField(compositionController,
                        "mBackTransitionHandler");
                finishCallback = readField(handler,
                        "mOnAnimationFinishCallback");
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Could not capture Shell return-home finish callback",
                        throwable);
            }
        }
        boolean finishCallbackBound = finishCallback != null
                && identity.finishCallback.compareAndSet(
                null, finishCallback);
        boolean finishReceiptBound = finishCallbackBound
                && identity.finishSignal.compareAndSet(null, signal);
        boolean identityCurrent = finishReceiptBound
                && systemUiReturnHomeCommitIdentity.get() == identity;
        if (!identityCurrent) {
            if (finishReceiptBound) {
                identity.finishSignal.compareAndSet(signal, null);
            }
            if (finishCallbackBound) {
                identity.finishCallback.compareAndSet(
                        finishCallback, null);
            }
            moduleLog(Log.ERROR, TAG,
                    "Could not bind Shell return-home finish receipt"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId
                            + ", shellSessionId="
                            + identity.shellSessionId
                            + ", eventId=" + input.eventId
                            + ", finishCallback="
                            + shortObject(finishCallback)
                            + ", identityCurrent="
                            + (systemUiReturnHomeCommitIdentity.get()
                            == identity));
            return;
        }
        try {
            Intent intent = new Intent(MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
            intent.setPackage(MIUI_HOME);
            intent.putExtra(EXTRA_RETURN_HOME_COMMIT_ATTEMPT, attempt);
            putStandardReturnHomeSignal(intent, signal);
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.sendBroadcast(intent, null, options);
            moduleLog(Log.INFO, TAG,
                    "Published standard predictive return-home commit"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId
                            + ", eventId=" + input.eventId
                            + ", downTime=" + input.downTime
                            + ", arbiterGeneration="
                            + systemUiInputArbiterGeneration
                            + ", elementBoundaryOnly="
                            + elementBoundaryOnly
                            + ", runnerSession="
                            + shortObject(runnerSession));
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to publish standard return-home commit"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId,
                    throwable);
        }
    }

    protected void putStandardReturnHomeSignal(
            Intent intent, StandardReturnHomeCommitSignal signal) {
        intent.putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                signal.arbiterGeneration);
        intent.putExtra(EXTRA_RETURN_HOME_COMMIT_TASK_ID, signal.taskId);
        intent.putExtra(EXTRA_RETURN_HOME_COMMIT_DEBUG_ID,
                signal.transitionDebugId);
        intent.putExtra(EXTRA_RETURN_HOME_ELEMENT_BOUNDARY,
                signal.elementBoundaryOnly);
        intent.putExtra(EXTRA_INPUT_EVENT_ID, signal.eventId);
        intent.putExtra(EXTRA_INPUT_DOWN_TIME, signal.downTime);
        intent.putExtra(EXTRA_INPUT_DEVICE_ID, signal.deviceId);
        intent.putExtra(EXTRA_INPUT_SOURCE, signal.source);
        intent.putExtra(EXTRA_INPUT_DISPLAY_ID, signal.displayId);
        intent.putExtra(EXTRA_INPUT_EDGE, signal.edge);
        Bundle binderExtras = new Bundle();
        binderExtras.putBinder(EXTRA_RETURN_HOME_RUNNER_SESSION,
                signal.runnerSession);
        intent.putExtras(binderExtras);
    }

    protected IBinder captureReturnHomeRunnerSession(Object controller)
            throws Throwable {
        Object registry = readField(controller, "mShellBackAnimationRegistry");
        Object definitions = readField(registry, "mAnimationDefinition");
        Object runner = invokeAnyMethod(definitions, "get",
                new Object[]{Integer.valueOf(TYPE_RETURN_TO_HOME)});
        Object remoteCallback = runner == null ? null
                : readField(runner, "mRemoteCallback");
        Object runnerApps = runner == null ? null : readField(runner, "mApps");
        Object controllerApps = readField(controller, "mApps");
        Object waiting = runner == null ? null
                : readField(runner, "mWaitingAnimation");
        Object cancelled = runner == null ? null
                : readField(runner, "mAnimationCancelled");
        IBinder session = remoteCallback instanceof IInterface
                ? ((IInterface) remoteCallback).asBinder() : null;
        if (runner == null || session == null || runnerApps != controllerApps
                || !Boolean.FALSE.equals(waiting)
                || !Boolean.FALSE.equals(cancelled)) {
            throw new IllegalStateException(
                    "return-home runner is not the exact active session"
                            + ", runner=" + shortObject(runner)
                            + ", remoteCallback="
                            + shortObject(remoteCallback)
                            + ", sameApps="
                            + (runnerApps == controllerApps)
                            + ", waiting=" + waiting
                            + ", cancelled=" + cancelled);
        }
        return session;
    }

    @Override

    protected void publishSystemUiReturnHomeFinish(
            Object controller, long shellSessionId,
            Object finishCallback, String reason) {
        SystemUiReturnHomeCommitIdentity identity =
                systemUiReturnHomeCommitIdentity.get();
        StandardReturnHomeCommitSignal signal = identity == null
                ? null : identity.finishSignal.get();
        Object expectedFinishCallback = identity == null
                ? null : identity.finishCallback.get();
        boolean callbackIdentityMatches = signal != null
                && expectedFinishCallback != null
                && (signal.elementBoundaryOnly
                ? finishCallback == null
                : finishCallback == expectedFinishCallback);
        if (identity == null || signal == null
                || identity.controller != controller
                || identity.shellSessionId != shellSessionId
                || !callbackIdentityMatches) {
            return;
        }
        Context context = miuiOverviewReceiverContext;
        boolean cleanupComplete = false;
        try {
            Object handler = readField(controller,
                    "mBackTransitionHandler");
            boolean preparedStateClear = readField(handler,
                    "mOnAnimationFinishCallback") == null
                    && readField(handler, "mFinishOpenTransaction") == null
                    && readField(handler,
                    "mFinishOpenTransitionCallback") == null
                    && readField(handler, "mPrepareOpenTransition") == null
                    && readField(handler, "mClosePrepareTransition") == null
                    && readField(handler, "mOpenTransitionInfo") == null;
            boolean closeRequested = Boolean.TRUE.equals(readField(
                    handler, "mCloseTransitionRequested"));
            if (signal.elementBoundaryOnly
                    && preparedStateClear && closeRequested) {
                writeField(handler, "mCloseTransitionRequested",
                        Boolean.FALSE);
                closeRequested = false;
            }
            cleanupComplete = preparedStateClear && !closeRequested;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Could not prove completed Shell return-home cleanup",
                    throwable);
        }
        if (identity.taskId != signal.taskId
                || signal.arbiterGeneration
                != systemUiInputArbiterGeneration
                || !signal.matchesInput(identity.input)
                || context == null
                || !cleanupComplete
                || !acceptingBackInputInstalls
                || systemUiInputArbiterMonitorCount.get() <= 0) {
            if (signal != null && !cleanupComplete) {
                moduleLog(Log.WARN, TAG,
                        "Withheld premature Shell return-home finish receipt"
                                + ", attempt=" + signal.attempt
                                + ", taskId=" + signal.taskId
                                + ", transitionDebugId="
                                + signal.transitionDebugId
                                + ", shellSessionId=" + shellSessionId);
            }
            return;
        }
        try {
            Intent intent = new Intent(MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
            intent.setPackage(MIUI_HOME);
            intent.putExtra(EXTRA_RETURN_HOME_FINISH_ATTEMPT,
                    signal.attempt);
            putStandardReturnHomeSignal(intent, signal);
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.sendBroadcast(intent, null, options);
            clearSystemUiReturnHomeCommitIdentity(
                    controller, shellSessionId,
                    "finishReceipt:" + reason);
            moduleLog(Log.INFO, TAG,
                    "Published completed Shell return-home receipt"
                            + ", attempt=" + signal.attempt
                            + ", taskId=" + signal.taskId
                            + ", transitionDebugId="
                            + signal.transitionDebugId
                            + ", shellSessionId=" + shellSessionId
                            + ", eventId=" + signal.eventId
                            + ", reason=" + reason);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to publish completed Shell return-home receipt"
                            + ", attempt=" + signal.attempt
                            + ", taskId=" + signal.taskId
                            + ", transitionDebugId="
                            + signal.transitionDebugId,
                    throwable);
        }
    }

    protected boolean clearSystemUiReturnHomeCommitIdentity(
            Object controller, long shellSessionId, String reason) {
        while (true) {
            SystemUiReturnHomeCommitIdentity identity =
                    systemUiReturnHomeCommitIdentity.get();
            if (identity == null
                    || (controller != null
                    && identity.controller != controller)
                    || (shellSessionId != 0L
                    && identity.shellSessionId != shellSessionId)) {
                return false;
            }
            if (systemUiReturnHomeCommitIdentity.compareAndSet(
                    identity, null)) {
                moduleLog(Log.INFO, TAG,
                        "Cleared committed return-home input identity"
                                + ", taskId=" + identity.taskId
                                + ", eventId="
                                + identity.input.eventId
                                + ", shellSessionId="
                                + identity.shellSessionId
                                + ", reason=" + reason);
                return true;
            }
        }
    }
}
