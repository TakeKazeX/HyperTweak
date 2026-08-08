package com.takekazex.hypertweak.hook.rules.backgesture.hooks.miuihome;

// Adapted for HyperTweak from wxxsfxyzm/MiuiBackGestureHook (Apache-2.0).
// Vendored through upstream a5f1ae5 (v0.8.5). Keep structural parity so future updates stay
// mergeable; HyperTweak-local changes are marked.

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

public abstract class MiuiHomeReturnHomeRuntime
        extends MiuiHomeReturnHomeLifecycleRuntime {

    /**
     * HyperTweak: {@code performAppToHome()} only runs the real app-to-icon animation when
     * {@code isNeedStopBecauseRecentsRemoteAnimStartFailed()} is false, which requires the
     * launcher's own {@code RecentsAnimationListenerImpl} to have started. In a module-driven
     * return home SystemUI owns the remote animation, so the launcher's listener never starts and
     * {@code performAppToHome()} falls into its failure branch — {@code finishAppToHome(false)}
     * plus a {@code ForceStopTransitionEvent}, which snaps home without animating the app into its
     * icon and leaves the app window on top until it is disposed.
     *
     * Forcing the precondition false for exactly our own call lets the launcher run its own
     * unmodified sequence (setWillFinishToHome → commonAnimStartAppToHome → startAppToHomeAnim →
     * finalization) instead of reimplementing that branch here. The listener is non-null in this
     * state, and the one unguarded dereference inside startAppToHomeAnim sits behind
     * isCloseToElement(), which validates it.
     */
    protected void hookMiuiHomeAppToHomeGate(ClassLoader classLoader) {
        try {
            Class<?> navStubClass = Class.forName(MIUI_HOME_NAV_STUB_VIEW, false, classLoader);
            Method gate = navStubClass.getDeclaredMethod(
                    "isNeedStopBecauseRecentsRemoteAnimStartFailed");
            gate.setAccessible(true);
            registerHook(gate, "miui_home_app_to_home_gate", chain -> {
                if (Boolean.TRUE.equals(drivingMiuiHomeAppToHome.get())) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
            moduleLog(Log.INFO, TAG, "Hooked MiuiHome app-to-home start gate");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to hook MiuiHome app-to-home start gate", throwable);
        }
    }

    protected abstract void handleMiuiHomeReturnHomeBinderDeath(
            MiuiHomeReturnHomeController controller);
    protected abstract void finishDeferredMiuiHomeReturnHomeController(
            MiuiHomeReturnHomeController controller, String reason);

    protected volatile MiuiHomeReturnHomeController miuiHomeReturnHomeController;

    /** Public owner type retained for hook and hot-reload integration. */
    protected final class MiuiHomeReturnHomeController
            extends MiuiHomeReturnHomeLifecycleRuntime.ReturnHomeLifecycleController {
        MiuiHomeReturnHomeController(IBinder shellBackAnimation,
                                    ClassLoader classLoader, Context context) {
            super(shellBackAnimation, classLoader, context);
        }

        @Override
        protected void dispatchShellBinderDeath() {
            handleMiuiHomeReturnHomeBinderDeath(this);
        }

        @Override
        protected void dispatchDeferredControllerFinish(String reason) {
            finishDeferredMiuiHomeReturnHomeController(this, reason);
        }
    }
}

