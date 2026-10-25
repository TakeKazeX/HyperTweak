package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import com.takekazex.hypertweak.settings.contract.BackGestureKeys;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.window.BackProgressAnimator;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import com.takekazex.hypertweak.hook.rules.backgesture.PredictiveBackPreferences;
import io.github.libxposed.api.XposedInterface;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public abstract class SystemUiHookRuntime extends SystemUiAnimationRuntime {

    private final SystemUiBridgeLease launcherBridgeLease = new SystemUiBridgeLease();

    protected volatile SharedPreferences contextualSearchStatePreferences;
    protected volatile Context contextualSearchStateContext;
    protected final SharedPreferences.OnSharedPreferenceChangeListener
            contextualSearchStatePreferenceListener = (preferences, key) -> {
                if (key != null && !PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS.equals(key)
                        && !BackGestureKeys.KEY_BACK_GESTURE_ENABLED.equals(key)
                        && !PredictiveBackPreferences.KEY_HYPEROS_INDICATOR.equals(key)
                        && !PredictiveBackPreferences.KEY_HYPEROS_INDICATOR_BLUR.equals(key)
                        && !PredictiveBackPreferences.KEY_GESTURE_HAPTICS_DISABLED.equals(key)) {
                    return;
                }
                com.takekazex.hypertweak.hook.Preferences.INSTANCE.invalidateRuntimeReadCache();
                contextualSearchPreferences = preferences;
                Context context = contextualSearchStateContext;
                SystemUiPlatformImpl implementation = systemUiPlatformImpl;
                if (context == null || implementation == null) {
                    return;
                }
                Handler mainHandler = new Handler(context.getMainLooper());
                mainHandler.post(() -> {
                    if (contextualSearchStateContext != context) {
                        return;
                    }
                    if (!implementation.nativeLauncherOwnsContextualSearchLongPress()) {
                        refreshContextualSearchInputReceivers();
                        return;
                    }
                    for (NativeBackInputMonitor monitor : nativeInputMonitors.values()) {
                        monitor.refreshIndicatorIfIdle();
                    }
                    publishSystemUiInputArbiterState(context,
                            systemUiInputArbiterMonitorCount.get() > 0,
                            "backGesturePreference:" + key);
                });
            };

    protected void installSystemUiHooks(ClassLoader classLoader) {
        try {
            selectSystemUiPlatformImpl(classLoader);
            hookContextualSearchNavigationBar(classLoader, true, true);
            Context systemUiContext = resolveCurrentApplicationContext(classLoader);
            if (systemUiContext != null) {
                ensureMiuiOverviewStateReceiver(systemUiContext);
            } else {
                moduleLog(Log.WARN, TAG,
                        "SystemUI application context is not available yet; "
                                + "status receiver will retry from the input owner");
            }
            hookMiuiOverviewProxy(classLoader);
            hookNavigationBarTransientAutoHide(classLoader);
            hookNavigationBarTransientAppearance(classLoader);
            hookStatusBarTransientAppearance(classLoader);
            hookNavigationBarGestureInsets(classLoader);
            hookPlatformBackAnimationStatusBarReset(classLoader);
            hookEdgeBackGestureHandler(classLoader, true, true, true);
            hookAospBackPanelHaptic(classLoader);
            hookAospBackPanelViewHaptic(classLoader);
            hookNavigationBarControllerCreate(classLoader);
            hookNavigationBarControllerRemove(classLoader);
            hookNavigationBarControllerMode(classLoader);
            hookShellBackAnimation(classLoader);
            hookBackAnimationSendBackEvent(classLoader);
            hookDefaultTransitionHandler(classLoader);
            hookDefaultTransitionImplMerge(classLoader);
            moduleLog(Log.INFO, TAG, "Installed SystemUI AOSP back restoration hooks, build="
                    + BUILD_MARK + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install SystemUI hooks", throwable);
        }
    }

    protected void hookContextualSearchNavigationBar(
            ClassLoader classLoader, boolean hookAttach, boolean hookDetach) {
        try {
            Class<?> navigationBarClass = Class.forName(
                    NAVIGATION_BAR, false, classLoader);
            if (hookDetach) {
                Method detached = navigationBarClass.getDeclaredMethod("onViewDetached");
                detached.setAccessible(true);
                registerHook(detached, "systemui_contextual_search_nav_detach", this::detachContextualSearchBeforeNavigationBarDetached);
            }
            if (hookAttach) {
                Method attached = navigationBarClass.getDeclaredMethod("onViewAttached");
                attached.setAccessible(true);
                registerHook(attached, "systemui_contextual_search_nav_attach", this::attachContextualSearchAfterNavigationBarAttached);
            }
            moduleLog(Log.INFO, TAG,
                    "Installed contextual-search NavigationBar lifecycle hooks"
                            + ", attach=" + hookAttach + ", detach=" + hookDetach);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search NavigationBar lifecycle unavailable", throwable);
        }
    }

    protected Object attachContextualSearchAfterNavigationBarAttached(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!requireSystemUiPlatformImpl()
                .nativeLauncherOwnsContextualSearchLongPress()) {
            attachContextualSearchInputReceiver(chain.getThisObject());
        }
        return result;
    }

    private final ContextualSearchRequestPolicy contextualSearchRequests =
            new ContextualSearchRequestPolicy();

    private void handleNativeContextualSearchRequest(Context context, long requestedAt) {
        if (!isContextualSearchLongPressEnabled()) return;
        boolean eligible = false;
        try {
            // Resolve the live controller on every request. This also recovers existing
            // NavigationBars when upgrading from an APK that never saved CTS host state.
            Object controller = findNavigationBarControllerFromDependency(context.getClassLoader());
            Object bars = controller == null ? null : readField(controller, "mNavigationBars");
            Object bar = bars == null ? null : invokeAnyMethod(bars, "get",
                    new Object[]{Integer.valueOf(0)});
            if (bar != null) {
                Object viewObject = readField(bar, "mView");
                if (viewObject instanceof android.view.View) {
                    android.view.View view = (android.view.View) viewObject;
                    android.app.KeyguardManager keyguard = context.getSystemService(
                            android.app.KeyguardManager.class);
                    Object state = readField(bar, "mNavigationBarWindowState");
                    // Native MiuiHome already detected the long press. Do not hit-test
                    // the drawn pill again: our hide/no-raise feature makes it transparent
                    // or zero-height while leaving native input ownership intact.
                    eligible = state instanceof Integer && keyguard != null
                            && ContextualSearchRequestPolicy.allowsNativeCompletion((Integer) state,
                            Boolean.TRUE.equals(readField(bar, "mTransientShown")),
                            view.isAttachedToWindow(), keyguard.isKeyguardLocked());
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Native contextual-search navigation owner unavailable", throwable);
        }
        if (!contextualSearchRequests.admit(android.os.SystemClock.elapsedRealtime(), requestedAt, eligible)) {
            moduleLog(Log.INFO, TAG, "Rejected native contextual-search request; ownerEligible=" + eligible);
            return;
        }
        if (launcherBridgeLease.isActive() && invokeContextualSearchService()
                && launcherBridgeLease.isActive() && acceptingBackInputInstalls) {
            playContextualSearchHaptic(context);
        }
    }

    protected Object detachContextualSearchBeforeNavigationBarDetached(
            XposedInterface.Chain chain) throws Throwable {
        forgetContextualSearchNavigationBar(chain.getThisObject());
        detachContextualSearchInputReceiver(chain.getThisObject());
        return chain.proceed();
    }

    @Override
    protected void restoreContextualSearchInputReceivers(Object[] navigationBars) {
        if (requireSystemUiPlatformImpl()
                .nativeLauncherOwnsContextualSearchLongPress()) {
            return;
        }
        super.restoreContextualSearchInputReceivers(navigationBars);
    }

    protected synchronized void ensureContextualSearchStatePreferenceListener(
            Context context) {
        if (context == null) return;
        try {
            SharedPreferences preferences = getRemotePreferences(PredictiveBackPreferences.GROUP);
            if (contextualSearchStatePreferences == preferences) return;
            releaseContextualSearchStatePreferenceListener();
            preferences.registerOnSharedPreferenceChangeListener(
                    contextualSearchStatePreferenceListener);
            contextualSearchPreferences = preferences;
            contextualSearchStatePreferences = preferences;
            contextualSearchStateContext = context.getApplicationContext();
            moduleLog(Log.INFO, TAG,
                    "Registered live contextual-search preference listener");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Live contextual-search preference listener unavailable",
                    throwable);
        }
    }

    protected synchronized void releaseContextualSearchStatePreferenceListener() {
        SharedPreferences preferences = contextualSearchStatePreferences;
        contextualSearchStatePreferences = null;
        contextualSearchStateContext = null;
        contextualSearchPreferences = null;
        if (preferences == null) {
            return;
        }
        try {
            preferences.unregisterOnSharedPreferenceChangeListener(
                    contextualSearchStatePreferenceListener);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to unregister live contextual-search preference listener",
                    throwable);
        }
    }

    protected void selectSystemUiPlatformImpl(ClassLoader classLoader) throws Exception {
        Class<?> edgeHandlerClass = Class.forName(EDGE_BACK_GESTURE_HANDLER,
                false, classLoader);
        if (!SystemUiAndroid17Impl.matches(edgeHandlerClass, classLoader)) {
            throw new NoSuchMethodException("Unsupported Android 17 SystemUI back handler");
        }
        SystemUiPlatformImpl selected = new SystemUiAndroid17Impl();
        SystemUiPlatformImpl previous = systemUiPlatformImpl;
        systemUiPlatformImpl = selected;
        if (previous != null && previous != selected) {
            previous.destroy();
        }
        moduleLog(Log.INFO, TAG, "Selected SystemUI platform implementation: "
                + selected.name());
    }

    protected String defaultTransitionOpenCaptureHookId() {
        return requireSystemUiPlatformImpl().defaultTransitionOpenCaptureHookId();
    }

    protected String systemUiInputArbiterStateAction() {
        SystemUiPlatformImpl implementation = systemUiPlatformImpl;
        return implementation == null
                ? MODULE_SYSTEMUI_INPUT_ARBITER_STATE
                : implementation.systemUiInputArbiterStateAction(
                MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
    }

    protected void hookPlatformBackAnimationStatusBarReset(ClassLoader classLoader) {
        try {
            Method reset = requireSystemUiPlatformImpl()
                    .brokenBackAnimationStatusBarResetMethod(classLoader);
            if (reset == null) {
                return;
            }
            registerHook(reset, "systemui_a17_back_background_status_reset", this::neutralizeBrokenBackAnimationStatusBarReset);
            moduleLog(Log.INFO, TAG,
                    "Neutralized stripped Android 17 BackAnimationBackground status reset");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Android 17 BackAnimationBackground status reset",
                    throwable);
        }
    }

    protected Object neutralizeBrokenBackAnimationStatusBarReset(
            XposedInterface.Chain chain) {
        // Xiaomi's companion customize/set methods are no-ops, so there is no active
        // customization to reset. Preserve the app-requested status-bar appearance.
        return null;
    }

    @Override
    protected Object findNativeEdgeBackPlugin(Object edgeBackGestureHandler) throws Exception {
        return requireSystemUiPlatformImpl().findNativeEdgeBackPlugin(edgeBackGestureHandler);
    }

    @Override
    protected void prepareNativeBackPanel(Object edgeBackGestureHandler,
                                          Object plugin) throws Exception {
        requireSystemUiPlatformImpl().prepareNativeBackPanel(
                edgeBackGestureHandler, plugin);
    }

    @Override
    protected void updateNativeBackPanelDisplaySize(Object edgeBackGestureHandler,
                                                    Object plugin) throws Exception {
        requireSystemUiPlatformImpl().updateDisplaySize(
                edgeBackGestureHandler, plugin);
    }

    @Override
    protected boolean isNavigationOverlayExcluded(Object edgeBackGestureHandler,
                                                  int x, int y) throws Exception {
        return requireSystemUiPlatformImpl().isNavigationOverlayExcluded(
                edgeBackGestureHandler, x, y);
    }

    @Override
    protected void injectPlatformLegacyBackKey(
            Object controller, int displayId) throws Exception {
        requireSystemUiPlatformImpl().injectLegacyBackKey(controller, displayId);
    }

    protected void destroySystemUiPlatformImpl() {
        SystemUiPlatformImpl implementation = systemUiPlatformImpl;
        if (implementation != null) {
            implementation.destroy();
        }
    }

    protected void hookShellBackAnimation(ClassLoader classLoader) {
        try {
            Class<?> controllerClass =
                    Class.forName(BACK_ANIMATION_CONTROLLER, false, classLoader);
            hookShellAnimationFinished(controllerClass, "onBackAnimationFinished",
                    "shell_back_onBackAnimationFinished", false);
            hookShellAnimationFinished(controllerClass, "finishBackAnimation",
                    "shell_back_finishBackAnimation", true);
            hookBackNavigationInfoReceived(controllerClass);
            hookPreparedBackTargetArrival(classLoader);
            hookPreparedBackTerminal(controllerClass);
            hookPreparedBackTransitionDecision(classLoader);
            hookBackPrepareTransitionReparent(classLoader);
            hookBackCommitComposition(classLoader);
            hookBackFinishOpenAtomicTransfer(classLoader);
            hookFreeformCrossActivityScrimCreation();
            hookCrossActivitySlideAnimation(classLoader,
                    true, true, true, true, true, true);
            hookCrossTaskBackground(classLoader);
            hookCrossActivityRunnerSelection(classLoader);
            moduleLog(Log.INFO, TAG, "Hooked Shell BackAnimationController AOSP path");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook Shell back animation", throwable);
        }
    }

    protected void hookMiuiOverviewProxy(ClassLoader classLoader) {
        try {
            Class<?> proxyClass = Class.forName(MIUI_OVERVIEW_PROXY, false, classLoader);
            Method method = proxyClass.getDeclaredMethod("onTransact",
                    int.class, Parcel.class, Parcel.class, int.class);
            method.setAccessible(true);
            registerHook(method, "systemui_block_miui_gesture_line_progress", this::interceptMiuiOverviewProxyTransact);
            moduleLog(Log.INFO, TAG, "Hooked MiuiOverviewProxy.onTransact");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook MiuiOverviewProxy", throwable);
        }
    }

    protected Object interceptMiuiOverviewProxyTransact(XposedInterface.Chain chain)
            throws Throwable {
        int code = (Integer) chain.getArg(0);
        if (code != TRANSACTION_MIUI_ON_GESTURE_LINE_PROGRESS) {
            return chain.proceed();
        }

        Parcel reply = (Parcel) chain.getArg(2);
        if (reply != null) {
            reply.writeNoException();
        }
        return Boolean.TRUE;
    }

    protected void hookAospBackPanelHaptic(ClassLoader classLoader) {
        try {
            Class<?> vibratorHelperClass = Class.forName(VIBRATOR_HELPER,
                    false, classLoader);
            Method performHapticFeedback;
            try {
                performHapticFeedback = vibratorHelperClass.getDeclaredMethod(
                        "performHapticFeedback", View.class, int.class);
            } catch (NoSuchMethodException absentHelper) {
                // HyperTweak: BackPanel calls View.performHapticFeedback on this host.
                // The following installer owns both View overloads and isolates BackPanel.
                moduleLog(Log.DEBUG, TAG, "Back-panel haptics use the native View boundary");
                return;
            }
            performHapticFeedback.setAccessible(true);
            registerHook(performHapticFeedback, "systemui_back_panel_aosp_haptic", this::replaceAospBackPanelHaptic);
            moduleLog(Log.INFO, TAG,
                    "Hooked AOSP back-panel threshold haptic replacement");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to hook AOSP back-panel activation haptic; native effect remains",
                    throwable);
        }
    }

    protected Object replaceAospBackPanelHaptic(XposedInterface.Chain chain)
            throws Throwable {
        Object feedbackConstant = chain.getArg(1);
        if (isAospBackThresholdHaptic(feedbackConstant)
                && ((isAospBackPanelView(chain.getArg(0)) && isGestureHapticsDisabled())
                || playHyperOsReplacementHaptic(chain.getArg(0)))) {
            return null;
        }
        return chain.proceed();
    }

    /**
     * HyperOS removes VibratorHelper.performHapticFeedback(View, int) on some builds and
     * emits the same AOSP threshold call directly from BackPanel. Hook the actual View
     * boundary as the compatibility path. Only the BackPanel instance and the two original
     * AOSP threshold constants are intercepted; both use the same single HyperOS default
     * effect, without adding another feedback stage.
     */
    protected void hookAospBackPanelViewHaptic(ClassLoader classLoader) {
        hookAospBackPanelViewHaptic(classLoader, true, true);
    }

    protected void hookAospBackPanelViewHaptic(ClassLoader classLoader,
                                               boolean hookSingleArgument,
                                               boolean hookFlagsArgument) {
        int installed = 0;
        if (hookSingleArgument) {
            try {
                Class<?> viewClass = Class.forName("android.view.View", false, classLoader);
                Method performHapticFeedback = viewClass.getDeclaredMethod(
                        "performHapticFeedback", int.class);
                performHapticFeedback.setAccessible(true);
                registerHook(performHapticFeedback, "systemui_back_panel_aosp_view_haptic", this::replaceAospBackPanelViewHaptic);
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to hook View.performHapticFeedback(int) for AOSP back panel",
                        throwable);
            }
        }
        if (hookFlagsArgument) {
            try {
                Class<?> viewClass = Class.forName("android.view.View", false, classLoader);
                Method performHapticFeedback = viewClass.getDeclaredMethod(
                        "performHapticFeedback", int.class, int.class);
                performHapticFeedback.setAccessible(true);
                registerHook(performHapticFeedback, "systemui_back_panel_aosp_view_haptic_flags", this::replaceAospBackPanelViewHaptic);
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to hook View.performHapticFeedback(int, int) for AOSP back panel",
                        throwable);
            }
        }
        if (installed > 0) {
            moduleLog(Log.INFO, TAG,
                    "Hooked AOSP BackPanel View threshold haptic replacement"
                            + ", methods=" + installed);
        }
    }

    protected Object replaceAospBackPanelViewHaptic(XposedInterface.Chain chain)
            throws Throwable {
        Object view = chain.getThisObject();
        Object feedbackConstant = chain.getArg(0);
        if (isAospBackPanelView(view)
                && isAospBackThresholdHaptic(feedbackConstant)
                && (isGestureHapticsDisabled() || playHyperOsReplacementHaptic(view))) {
            // View.performHapticFeedback returns boolean. Report that the original
            // threshold feedback was handled after replacing or disabling its effect.
            return Boolean.TRUE;
        }
        return chain.proceed();
    }

    protected boolean isAospBackThresholdHaptic(Object feedbackConstant) {
        if (!(feedbackConstant instanceof Number)) {
            return false;
        }
        int constant = ((Number) feedbackConstant).intValue();
        return constant == HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                || constant == HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE;
    }

    protected boolean isAospBackPanelView(Object value) {
        return value instanceof View
                && BACK_PANEL_VIEW.equals(value.getClass().getName());
    }

    protected Object onEdgeBackUpdateIsEnabled(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        ensureBackInputInstalledFromHandler(chain.getThisObject(), "updateIsEnabled");
        return result;
    }

    protected Object onEdgeBackNavigationModeChanged(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        ensureBackInputInstalledFromHandler(chain.getThisObject(), "onNavigationModeChanged");
        return result;
    }

    protected Object onEdgeBackSetBackAnimation(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        installBackInputDriver(chain.getThisObject(), chain.getArg(0));
        return result;
    }

    // Shares the already authenticated native launcher state with other SystemUI features.
    // A null value means no native owner has been observed; Shell remains the fallback.
    protected java.util.function.Consumer<Boolean> overviewVisibilityListener;
    protected static final String UPSTREAM_OVERVIEW_ACTION =
            "dev.codex.miuibackgesturehook.action.MIUI_OVERVIEW_STATE_CHANGE";
    protected final com.takekazex.hypertweak.hook.rules.backgesture.UpstreamOverviewState
            upstreamOverviewState = new com.takekazex.hypertweak.hook.rules.backgesture.UpstreamOverviewState();

    public final synchronized void setOverviewVisibilityListener(
            java.util.function.Consumer<Boolean> listener) {
        overviewVisibilityListener = listener;
        notifyOverviewVisibilityListener();
    }

    protected final synchronized void notifyOverviewVisibilityListener() {
        java.util.function.Consumer<Boolean> listener = overviewVisibilityListener;
        if (listener == null) return;
        try {
            listener.accept(com.takekazex.hypertweak.util.UpstreamBackGesture.INSTANCE.getInstalled()
                    ? upstreamOverviewState.visible() : miuiLauncherDartStateOwnerEpoch > 0L
                    ? Boolean.valueOf(miuiOverviewVisible) : null);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to notify launcher overview observer", throwable);
        }
    }

    protected final synchronized void setMiuiOverviewVisible(boolean visible) {
        miuiOverviewVisible = visible;
        notifyOverviewVisibilityListener();
    }

    public final synchronized void clearLauncherOverviewVisibility() {
        // Preserve the owner epoch fence so delayed retired broadcasts stay rejected.
        miuiOverviewDismissPendingUntilUptime = 0L;
        setMiuiOverviewVisible(false);
    }

    /**
     * Registers the MiuiHome launcher-state receiver as soon as a real SystemUI application
     * context exists.
     *
     * Package load resolves no application yet, so the install path logs "will retry from the
     * input owner" and depends on the edge-back hook for that retry. Device logs on 2026-10-20 show
     * the retry never happened on any SystemUI start: the registration line is absent from every
     * session, so launcher-driven Overview changes never reached the module and recent-task hiding
     * had to fall back to a polling watchdog. Package ready is the first reliable point that has a
     * context, and this call is idempotent.
     */
    public final void onApplicationContextReady(Context context) {
        if (context == null) {
            moduleLog(Log.WARN, TAG, "Launcher overview bridge skipped: null context");
            return;
        }
        Context appContext = context.getApplicationContext();
        ensureMiuiOverviewStateReceiver(appContext != null ? appContext : context);
        moduleLog(Log.INFO, TAG, "Launcher overview bridge requested, registered="
                + (miuiOverviewReceiver != null));
    }

    protected synchronized void ensureMiuiOverviewStateReceiver(Context context) {
        if (!acceptingBackInputInstalls || miuiOverviewReceiver != null || context == null) {
            return;
        }
        // getApplicationContext() can return null for a context that is itself the application
        // (or for a partially initialised one). Falling back to the supplied context is what keeps
        // the registration below from dereferencing null.
        Context resolved = context.getApplicationContext();
        Context appContext = resolved != null ? resolved : context;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                // Unregister can fail during APK replacement. Such an old dispatcher
                // must stay inert, including across separate module class loaders.
                if (!acceptingBackInputInstalls || miuiOverviewReceiver != this
                        || !launcherBridgeLease.isActive()) return;
                String action = intent == null ? null : intent.getAction();
                if (UPSTREAM_OVERVIEW_ACTION.equals(action)) {
                    observeUpstreamOverview(receiverContext, getSentFromUid(), getSentFromPackage(), intent);
                    return;
                }
                if (MODULE_RUNTIME_STATUS_QUERY.equals(action)) {
                    handleModuleRuntimeStatusQuery(receiverContext,
                            getSentFromUid(), getSentFromPackage(), intent);
                    return;
                }
                if (MODULE_RUNTIME_STATUS_REPLY.equals(action)) {
                    handleNativeRuntimeStatusReply(receiverContext,
                            getSentFromUid(), getSentFromPackage(), intent);
                    return;
                }
                if (MODULE_CONTEXTUAL_SEARCH_REQUEST.equals(action)) {
                    if (getSentFromUid() != android.os.Process.SYSTEM_UID) {
                        moduleLog(Log.WARN, TAG, "Rejected foreign contextual-search request");
                        return;
                    }
                    handleNativeContextualSearchRequest(receiverContext,
                            intent.getLongExtra("request_elapsed", -1));
                    return;
                }
                if (MODULE_CONTEXTUAL_SEARCH_TRIGGERED.equals(action)) {
                    // Old/upstream native payloads report Binder transport success before
                    // SystemUI admission. Feedback now belongs to the admitted request.
                    if (requireSystemUiPlatformImpl().nativeLauncherOwnsContextualSearchLongPress()) {
                        return;
                    }
                    int senderUid = getSentFromUid();
                    String senderPackage = getSentFromPackage();
                    if (!isTrustedMiuiHomeBroadcastSender(
                            receiverContext, senderUid, senderPackage)) {
                        moduleLog(Log.WARN, TAG,
                                "Rejected untrusted contextual-search trigger"
                                        + ", uid=" + senderUid
                                        + ", package=" + senderPackage);
                        return;
                    }
                    playContextualSearchHaptic(receiverContext);
                    return;
                }
                if (!MODULE_MIUI_OVERVIEW_STATE_CHANGE.equals(action)
                        && !MODULE_MIUI_HOME_INPUT_ARBITER_QUERY.equals(action)) {
                    return;
                }
                int senderUid = getSentFromUid();
                String senderPackage = getSentFromPackage();
                if (!isTrustedMiuiHomeBroadcastSender(
                        receiverContext, senderUid, senderPackage)) {
                    moduleLog(Log.WARN, TAG, "Rejected untrusted Miui launcher-state broadcast"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                    return;
                }
                if (MODULE_MIUI_HOME_INPUT_ARBITER_QUERY.equals(action)) {
                    publishSystemUiInputArbiterState(receiverContext,
                            systemUiInputArbiterMonitorCount.get() > 0,
                            "miuiHomeQuery");
                    return;
                }
                boolean carriesDartState = intent.hasExtra("drawer_visible")
                        || intent.hasExtra("overview_visible")
                        || intent.hasExtra(EXTRA_LAUNCHER_EDITING)
                        || intent.hasExtra(EXTRA_LAUNCHER_HOME_SURFACE_VISIBLE);
                if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                        && (carriesDartState
                        || intent.hasExtra(EXTRA_LAUNCHER_STATE_OWNER_EPOCH))) {
                    long ownerEpoch = intent.getLongExtra(
                            EXTRA_LAUNCHER_STATE_OWNER_EPOCH, 0L);
                    long ownerGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (ownerEpoch <= 0L
                            || ownerGeneration != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Rejected invalid native launcher-state owner"
                                        + ", ownerEpoch=" + ownerEpoch
                                        + ", generation=" + ownerGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                        return;
                    }
                    if (ownerEpoch < miuiLauncherDartStateOwnerEpoch) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored retired native launcher-state owner"
                                        + ", ownerEpoch=" + ownerEpoch
                                        + ", currentOwnerEpoch="
                                        + miuiLauncherDartStateOwnerEpoch);
                        return;
                    }
                    if (ownerEpoch > miuiLauncherDartStateOwnerEpoch) {
                        miuiLauncherDartStateOwnerEpoch = ownerEpoch;
                        acceptedInputToken.set(null);
                        miuiDrawerVisible = false;
                        setMiuiOverviewVisible(false);
                        miuiLauncherEditing = false;
                        miuiLauncherHomeSurfaceVisible = false;
                        miuiOverviewDismissPendingUntilUptime = 0L;
                        moduleLog(Log.INFO, TAG,
                                "Adopted native launcher-state owner"
                                        + ", ownerEpoch=" + ownerEpoch
                                        + ", generation=" + ownerGeneration);
                    }
                }
                if (intent.getBooleanExtra(EXTRA_INPUT_ACCEPTED, false)) {
                    receiveMiuiHomeAcceptedInput(intent);
                }
                if (intent.hasExtra("drawer_visible")) {
                    long drawerGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            && drawerGeneration != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored stale native MiuiHome drawer state"
                                        + ", generation=" + drawerGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                    } else {
                        miuiDrawerVisible = intent.getBooleanExtra(
                                "drawer_visible", false);
                        moduleLog(Log.INFO, TAG, "MiuiHome drawer state changed"
                                + ", drawerVisible=" + miuiDrawerVisible
                                + ", generation=" + drawerGeneration
                                + ", uid=" + senderUid
                                + ", package=" + senderPackage);
                    }
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_XIAOAI_VISIBLE)) {
                    long xiaoAiGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            && xiaoAiGeneration
                            != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored stale native MiuiHome XiaoAi state"
                                        + ", generation=" + xiaoAiGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                    } else {
                        miuiLauncherXiaoAiVisible = intent.getBooleanExtra(
                                EXTRA_LAUNCHER_XIAOAI_VISIBLE, false);
                        moduleLog(Log.INFO, TAG,
                                "MiuiHome XiaoAi overlay state changed"
                                        + ", visible="
                                        + miuiLauncherXiaoAiVisible
                                        + ", generation=" + xiaoAiGeneration
                                        + ", uid=" + senderUid
                                        + ", package=" + senderPackage);
                    }
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_FOLDER_VISIBLE)) {
                    miuiFolderVisible = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_FOLDER_VISIBLE, false);
                    moduleLog(Log.INFO, TAG, "MiuiHome folder state changed"
                            + ", visible=" + miuiFolderVisible
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                }
                if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                        && intent.hasExtra(EXTRA_LAUNCHER_HOME_SURFACE_VISIBLE)) {
                    // Caller, arbiter generation and Dart owner were validated above.
                    // A distinct key prevents old native editing hooks from reviving the
                    // stale-true gate during Java hot reload before native activation.
                    miuiLauncherHomeSurfaceVisible = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_HOME_SURFACE_VISIBLE, false);
                    moduleLog(Log.INFO, TAG, "Native MiuiHome callback surface changed"
                            + ", visible=" + miuiLauncherHomeSurfaceVisible
                            + ", generation=" + systemUiInputArbiterGeneration
                            + ", ownerEpoch=" + miuiLauncherDartStateOwnerEpoch);
                }
                if (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL
                        && intent.hasExtra(EXTRA_LAUNCHER_EDITING)) {
                    miuiLauncherEditing = intent.getBooleanExtra(EXTRA_LAUNCHER_EDITING, false);
                    moduleLog(Log.INFO, TAG, "MiuiHome editing state changed"
                            + ", editing=" + miuiLauncherEditing
                            + ", uid=" + senderUid + ", package=" + senderPackage);
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE)
                        && intent.hasExtra(EXTRA_LAUNCHER_OPEN_ACTIVE)) {
                    long generation = intent.getLongExtra(
                            EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, 0L);
                    boolean active = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_OPEN_ACTIVE, false);
                    boolean available = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE, false);
                    if (generation == 0L
                            || generation < miuiLauncherOpenBreakGeneration) {
                        moduleLog(Log.WARN, TAG, "Ignored stale MiuiHome launcher OPEN break state"
                                + ", active=" + active
                                + ", available=" + available
                                + ", generation=" + generation
                                + ", currentGeneration="
                                + miuiLauncherOpenBreakGeneration);
                    } else {
                        long previousGeneration = miuiLauncherOpenBreakGeneration;
                        boolean previousActive = miuiLauncherOpenActive;
                        miuiLauncherOpenBreakGeneration = generation;
                        miuiLauncherOpenActive = active;
                        miuiLauncherOpenBreakAvailable = available;
                        moduleLog(Log.INFO, TAG, "MiuiHome launcher OPEN break state changed"
                                + ", active=" + active
                                + ", available=" + available
                                + ", generation=" + generation
                                + ", uid=" + senderUid
                                + ", package=" + senderPackage);
                        if (!active && previousActive
                                && previousGeneration == generation) {
                            for (NativeBackInputMonitor monitor
                                    : new ArrayList<>(nativeInputMonitors.values())) {
                                monitor.driver.onLauncherOpenEnded(generation);
                            }
                        }
                    }
                } else if (intent.hasExtra(
                        EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE)) {
                    moduleLog(Log.WARN, TAG,
                            "Ignored launcher OPEN state without active lifecycle");
                }
                String state = intent == null ? null : intent.getStringExtra("state");
                boolean overviewVisible;
                String source;
                if (intent.hasExtra("overview_visible")) {
                    long overviewGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            && overviewGeneration
                            != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored stale native MiuiHome Overview state"
                                        + ", generation=" + overviewGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                        return;
                    }
                    overviewVisible = intent.getBooleanExtra("overview_visible", false);
                    if (!overviewVisible
                            && intent.getBooleanExtra("task_launch_started", false)) {
                        beginMiuiOverviewDismiss("taskLaunch");
                        return;
                    }
                    state = overviewVisible ? "actualRecentsEnter" : "actualRecentsExit";
                    source = Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            ? "nativeOverview" : "RecentsContainer";
                } else if ("toRecents".equals(state)) {
                    overviewVisible = true;
                    source = "fullscreenState";
                } else if ("toHome".equals(state)
                        || "toAnotherApp".equals(state)
                        || "toCurrentApp".equals(state)
                        || "finishRecentDirectly".equals(state)) {
                    overviewVisible = false;
                    source = "fullscreenState";
                } else {
                    return;
                }
                updateMiuiOverviewState(overviewVisible, state, source);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            filter.addAction(UPSTREAM_OVERVIEW_ACTION);
            filter.addAction(MODULE_MIUI_HOME_INPUT_ARBITER_QUERY);
            filter.addAction(MODULE_CONTEXTUAL_SEARCH_TRIGGERED);
            filter.addAction(MODULE_CONTEXTUAL_SEARCH_REQUEST);
            filter.addAction(MODULE_RUNTIME_STATUS_QUERY);
            filter.addAction(MODULE_RUNTIME_STATUS_REPLY);
            miuiOverviewReceiverContext = appContext;
            miuiOverviewReceiver = receiver;
            launcherBridgeLease.claim();
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            predictiveBackWallpaper = new PredictiveBackWallpaper(appContext);
            ensureContextualSearchStatePreferenceListener(appContext);
            moduleLog(Log.INFO, TAG, "Registered Miui launcher overview-state receiver"
                    + ", currentOverviewVisible=" + miuiOverviewVisible);
        } catch (Throwable throwable) {
            launcherBridgeLease.retire();
            miuiOverviewReceiver = null;
            miuiOverviewReceiverContext = null;
            moduleLog(Log.ERROR, TAG, "Failed to register Miui overview-state receiver",
                    throwable);
        }
    }

    /**
     * Ports Samsung's Android 16 CrossTask geometry onto Xiaomi's existing Shell runner.
     * The callback and finish hooks are deliberately separate from the global
     * BackProgressAnimator hook: the latter only proxies the exact animator owned by the
     * current registry animation, while Xiaomi still owns target arrival, cancellation,
     * remote completion, and cleanup on both Android 16 and Android 17.
     */

    protected Context resolveCurrentApplicationContext(ClassLoader classLoader) {
        try {
            if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL) {
                return ActivityThread.currentApplication();
            }
            Class<?> activityThread = Class.forName(
                    "android.app.ActivityThread", false, classLoader);
            Method currentApplication = activityThread.getDeclaredMethod(
                    "currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to resolve SystemUI application context for status receiver",
                    throwable);
            return null;
        }
    }

    protected void handleModuleRuntimeStatusQuery(Context context, int senderUid,
                                                  String senderPackage,
                                                  Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!isTrustedModuleStatusSender(context, senderUid, senderPackage)) {
            moduleLog(Log.WARN, TAG, "Rejected untrusted runtime status query"
                    + ", uid=" + senderUid + ", package=" + senderPackage);
            return;
        }
        long nonce = intent.getLongExtra(EXTRA_STATUS_NONCE, 0L);
        if (nonce <= 0L) {
            moduleLog(Log.WARN, TAG, "Rejected runtime status query without nonce");
            return;
        }
        pendingModuleStatusNonce.set(nonce);
        boolean ready = systemUiInputArbiterMonitorCount.get() > 0
                && com.takekazex.hypertweak.hook.Preferences.INSTANCE.backGestureEnabled();
        sendModuleRuntimeStatusReply(context, nonce, false, ready,
                "systemUiResponse", null);
        try {
            Intent nativeQuery = new Intent(systemUiInputArbiterStateAction())
                    .setPackage(MIUI_HOME)
                    .putExtra(EXTRA_STATUS_QUERY, true)
                    .putExtra(EXTRA_STATUS_NONCE, nonce)
                    .putExtra(EXTRA_INPUT_ARBITER_READY, ready)
                    .putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                            systemUiInputArbiterGeneration)
                    // The native launcher receiver consumes this marked query as
                    // an arbiter-state update too. Keep the effective runtime bit
                    // on the query so a missing boot-created service remains
                    // fail-closed after an API-102 hot upgrade.
                    .putExtra(EXTRA_CONTEXTUAL_SEARCH_ENABLED,
                            isContextualSearchLongPressRuntimeEnabled())
                    .putExtra("sender_uid", Process.myUid());
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.getApplicationContext().sendBroadcast(nativeQuery, null, options);
            moduleLog(Log.INFO, TAG, "Sent native runtime status query"
                    + ", nonce=" + nonce + ", ready=" + ready);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to send native runtime status query",
                    throwable);
        }
    }

    protected void handleNativeRuntimeStatusReply(Context context, int senderUid,
                                                  String senderPackage,
                                                  Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!isTrustedMiuiHomeBroadcastSender(context, senderUid, senderPackage)) {
            moduleLog(Log.WARN, TAG, "Rejected untrusted native runtime status reply"
                    + ", uid=" + senderUid + ", package=" + senderPackage);
            return;
        }
        long nonce = intent.getLongExtra(EXTRA_STATUS_NONCE, 0L);
        if (nonce <= 0L || pendingModuleStatusNonce.get() != nonce) {
            moduleLog(Log.WARN, TAG, "Ignored stale native runtime status reply"
                    + ", nonce=" + nonce
                    + ", pending=" + pendingModuleStatusNonce.get());
            return;
        }
        pendingModuleStatusNonce.compareAndSet(nonce, 0L);
        sendModuleRuntimeStatusReply(context, nonce, true,
                systemUiInputArbiterMonitorCount.get() > 0
                        && com.takekazex.hypertweak.hook.Preferences.INSTANCE.backGestureEnabled(),
                "nativeResponse", intent);
    }

    protected void sendModuleRuntimeStatusReply(Context context, long nonce,
                                                boolean nativeResponse,
                                                boolean systemUiReady,
                                                String reason,
                                                Intent nativeReply) {
        if (context == null || nonce <= 0L) {
            return;
        }
        try {
            boolean legacyMode = Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL;
            boolean legacyReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_LEGACY_READY, false);
            boolean profileResolved = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_PROFILE_RESOLVED, false);
            boolean nativeReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_READY, false);
            int businessState = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_BUSINESS_STATE, 0);
            int bridgeState = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_BRIDGE_STATE, 0);
            int profileStage = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_RUNTIME_PROFILE_STAGE, 0);
            int dartResolverStage = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_DART_RESOLVER_STAGE, 0);
            boolean drawerStateReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_DRAWER_STATE_READY, false);
            boolean overviewStateReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_OVERVIEW_STATE_READY, false);
            boolean editingStateReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_EDITING_STATE_READY, false);
            boolean profileRejected = (profileStage >= 101 && profileStage <= 105)
                    || (dartResolverStage >= 101 && dartResolverStage <= 105);
            boolean statusReady = nativeResponse && systemUiReady
                    && (legacyMode ? legacyReady
                    : !profileRejected && nativeReady && profileResolved
                    && businessState == 3 && bridgeState == 3
                    && drawerStateReady && overviewStateReady
                    && editingStateReady);
            Intent reply = new Intent(MODULE_RUNTIME_STATUS_REPLY)
                    .setPackage(MODULE_PACKAGE)
                    .putExtra(EXTRA_STATUS_NONCE, nonce)
                    .putExtra(EXTRA_STATUS_NATIVE_RESPONSE, nativeResponse)
                    .putExtra(EXTRA_STATUS_LEGACY_MODE, legacyMode)
                    .putExtra(EXTRA_STATUS_SYSTEMUI_READY, systemUiReady)
                    .putExtra(EXTRA_STATUS_SYSTEMUI_GENERATION,
                            systemUiInputArbiterGeneration)
                    .putExtra(EXTRA_STATUS_SYSTEMUI_MONITORS,
                            systemUiInputArbiterMonitorCount.get())
                    .putExtra(EXTRA_STATUS_REASON, reason);
            if (nativeReply != null) {
                reply.putExtra(EXTRA_STATUS_LEGACY_READY, legacyReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_PROFILE_RESOLVED, profileResolved);
                reply.putExtra(EXTRA_STATUS_NATIVE_READY, nativeReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_PROFILE_DYNAMIC,
                        nativeReply.getBooleanExtra(
                                EXTRA_STATUS_NATIVE_PROFILE_DYNAMIC, false));
                reply.putExtra(EXTRA_STATUS_NATIVE_PROFILE_ENTRY_OFFSET,
                        nativeReply.getLongExtra(
                                EXTRA_STATUS_NATIVE_PROFILE_ENTRY_OFFSET, 0L));
                reply.putExtra(EXTRA_STATUS_NATIVE_SIDE_OFFSET,
                        nativeReply.getLongExtra(EXTRA_STATUS_NATIVE_SIDE_OFFSET, 0L));
                reply.putExtra(EXTRA_STATUS_NATIVE_RUNTIME_PROFILE_STAGE, profileStage);
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_RESOLVER_STAGE,
                        dartResolverStage);
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_DRAWER_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_DRAWER_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_TRANSITION_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_TRANSITION_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_EDITING_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_EDITING_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DRAWER_STATE_READY,
                        drawerStateReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_OVERVIEW_STATE_READY,
                        overviewStateReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_EDITING_STATE_READY,
                        editingStateReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_BUSINESS_STATE, businessState);
                reply.putExtra(EXTRA_STATUS_NATIVE_BRIDGE_STATE, bridgeState);
                reply.putExtra(EXTRA_STATUS_NATIVE_RECEIVER_STATE,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_RECEIVER_STATE, 0));
            }
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.getApplicationContext().sendBroadcast(reply, null, options);
            moduleLog(Log.INFO, TAG, "Published module runtime status reply"
                    + ", nonce=" + nonce + ", nativeResponse=" + nativeResponse
                    + ", systemUiReady=" + systemUiReady
                    + ", statusReady=" + statusReady
                    + ", profileResolved=" + profileResolved
                    + ", nativeReady=" + nativeReady
                    + ", businessState=" + businessState
                    + ", bridgeState=" + bridgeState
                    + ", profileStage=" + profileStage
                    + ", dartResolverStage=" + dartResolverStage
                    + ", drawerStateReady=" + drawerStateReady
                    + ", overviewStateReady=" + overviewStateReady
                    + ", editingStateReady=" + editingStateReady
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to publish module runtime status reply",
                    throwable);
        }
    }

    protected void receiveMiuiHomeAcceptedInput(Intent intent) {
        long generation = intent.getLongExtra(EXTRA_INPUT_ARBITER_GENERATION, 0L);
        if (generation != systemUiInputArbiterGeneration
                || systemUiInputArbiterMonitorCount.get() <= 0) {
            moduleLog(Log.WARN, TAG, "Ignored stale MiuiHome accepted input token"
                    + ", tokenGeneration=" + generation
                    + ", currentGeneration=" + systemUiInputArbiterGeneration
                    + ", monitors=" + systemUiInputArbiterMonitorCount.get());
            return;
        }
        if (!intent.hasExtra(EXTRA_INPUT_EVENT_ID)) {
            moduleLog(Log.WARN, TAG, "Rejected MiuiHome accepted token without event id");
            return;
        }
        MiuiHomeAcceptedInputToken token = new MiuiHomeAcceptedInputToken(
                intent.getIntExtra(EXTRA_INPUT_EVENT_ID, 0),
                intent.getLongExtra(EXTRA_INPUT_DOWN_TIME, Long.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_DEVICE_ID, Integer.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_SOURCE, 0),
                intent.getIntExtra(EXTRA_INPUT_DISPLAY_ID, Integer.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_EDGE, -1), generation,
                intent.getLongExtra(EXTRA_LAUNCHER_STATE_OWNER_EPOCH, 0L));
        if (token.downTime == Long.MIN_VALUE
                || token.deviceId == Integer.MIN_VALUE
                || token.displayId == Integer.MIN_VALUE
                || (token.edge != EDGE_LEFT && token.edge != EDGE_RIGHT)) {
            moduleLog(Log.WARN, TAG, "Rejected malformed MiuiHome accepted input token"
                    + ", eventId=" + token.eventId
                    + ", downTime=" + token.downTime
                    + ", deviceId=" + token.deviceId
                    + ", displayId=" + token.displayId
                    + ", edge=" + token.edge);
            return;
        }
        acceptedInputToken.set(token);
        boolean consumed = new ArrayList<>(nativeInputMonitors.values()).stream()
                .anyMatch(monitor -> monitor.acceptMiuiHomeInput(token));
        if (consumed) {
            acceptedInputToken.compareAndSet(token, null);
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> acceptedInputToken.compareAndSet(token, null),
                    INPUT_ACCEPTED_TOKEN_TIMEOUT_MS);
        }
        moduleLog(Log.INFO, TAG, "Received MiuiHome accepted input token"
                + ", eventId=" + token.eventId
                + ", downTime=" + token.downTime
                + ", displayId=" + token.displayId
                + ", edge=" + token.edge
                + ", matchedPendingDown=" + consumed
                + ", generation=" + generation);
    }

    protected boolean isTrustedMiuiHomeBroadcastSender(Context context, int uid,
                                                       String senderPackage) {
        if (context == null || uid == Process.INVALID_UID
                || !MIUI_HOME.equals(senderPackage)) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(MIUI_HOME);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to validate launcher-state sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    protected boolean isTrustedModuleStatusSender(Context context, int uid,
                                                  String senderPackage) {
        if (context == null || uid == Process.INVALID_UID
                || !MODULE_PACKAGE.equals(senderPackage)) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(MODULE_PACKAGE);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to validate module status sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    @SuppressWarnings("deprecation") // Bundle.get preserves strict wire types before primitive reads.
    protected synchronized void observeUpstreamOverview(Context context, int uid,
                                                       String sender, Intent intent) {
        if (!com.takekazex.hypertweak.util.UpstreamBackGesture.INSTANCE.getInstalled()
                || !isTrustedMiuiHomeBroadcastSender(context, uid, sender)) return;
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        Object owner = extras.get(EXTRA_LAUNCHER_STATE_OWNER_EPOCH);
        Object generation = extras.get("input_arbiter_generation");
        Object overview = extras.get("overview_visible");
        if (!(owner instanceof Long) || !(generation instanceof Long)
                || (extras.containsKey("overview_visible") && !(overview instanceof Boolean))) return;
        if (upstreamOverviewState.offer((Long) owner, (Long) generation, (Boolean) overview)) {
            // Visibility is shared with gesture-bar hiding only. Neither accepted
            // input, HyperTweak's arbiter generation nor native settings are adopted.
            notifyOverviewVisibilityListener();
        }
    }

    protected synchronized void unregisterMiuiOverviewStateReceiver() {
        // Revoke before touching framework registration or other teardown work.
        launcherBridgeLease.retire();
        PredictiveBackWallpaper wallpaper = predictiveBackWallpaper;
        predictiveBackWallpaper = null;
        if (wallpaper != null) wallpaper.close();
        releaseContextualSearchStatePreferenceListener();
        BroadcastReceiver receiver = miuiOverviewReceiver;
        Context receiverContext = miuiOverviewReceiverContext;
        miuiOverviewReceiver = null;
        acceptedInputToken.set(null);
        miuiOverviewReceiverContext = null;
        miuiLauncherOpenBreakAvailable = false;
        miuiLauncherOpenBreakGeneration = 0L;
        if (receiver == null || receiverContext == null) {
            return;
        }
        try {
            receiverContext.unregisterReceiver(receiver);
            moduleLog(Log.INFO, TAG, "Unregistered Miui launcher overview-state receiver");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to unregister Miui overview-state receiver",
                    throwable);
        }
    }

    protected synchronized void updateMiuiOverviewState(boolean overviewVisible,
                                                        String state, String source) {
        long now = SystemClock.uptimeMillis();
        long pendingUntil = miuiOverviewDismissPendingUntilUptime;
        if (overviewVisible && pendingUntil > now) {
            if ("toRecents".equals(state) && "fullscreenState".equals(source)) {
                // A lone container enter can be a late lifecycle notification from the
                // dismissal that just finished. The launcher's explicit toRecents signal,
                // however, is emitted by a new overview gesture. Accept it even inside the
                // short post-exit guard so quickly reopening Recents cannot leave SystemUI
                // permanently believing that launcher Home is visible.
                miuiOverviewDismissPendingUntilUptime = 0L;
                setMiuiOverviewVisible(true);
                moduleLog(Log.INFO, TAG, "Confirmed new Miui Recents entry during dismiss pending"
                        + ", state=" + state
                        + ", source=" + source
                        + ", clearedPendingForMs=" + (pendingUntil - now)
                        + ", overviewVisible=true");
                return;
            }
            moduleLog(Log.INFO, TAG, "Ignored Miui Recents enter while dismiss is pending"
                    + ", state=" + state
                    + ", source=" + source
                    + ", pendingForMs=" + (pendingUntil - now)
                    + ", overviewVisible=" + miuiOverviewVisible);
            return;
        }
        if (!overviewVisible && pendingUntil > now) {
            long guardUntil = now + MIUI_OVERVIEW_EXIT_GUARD_MS;
            miuiOverviewDismissPendingUntilUptime = guardUntil;
            setMiuiOverviewVisible(false);
            moduleLog(Log.INFO, TAG, "Confirmed Miui Recents dismiss"
                    + ", state=" + state
                    + ", source=" + source
                    + ", lateEnterGuardMs=" + MIUI_OVERVIEW_EXIT_GUARD_MS
                    + ", overviewVisible=false");
            return;
        }
        if (!overviewVisible || pendingUntil != 0L) {
            miuiOverviewDismissPendingUntilUptime = 0L;
        }
        setMiuiOverviewVisible(overviewVisible);
        moduleLog(Log.INFO, TAG, "Miui launcher state changed"
                + ", state=" + state
                + ", source=" + source
                + ", overviewVisible=" + overviewVisible);
    }

    protected synchronized void beginMiuiOverviewDismiss(String reason) {
        long pendingUntil = SystemClock.uptimeMillis() + MIUI_OVERVIEW_DISMISS_TIMEOUT_MS;
        miuiOverviewDismissPendingUntilUptime = pendingUntil;
        setMiuiOverviewVisible(false);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(pendingUntil),
                MIUI_OVERVIEW_DISMISS_TIMEOUT_MS);
        moduleLog(Log.INFO, TAG, "Started Miui Recents dismiss pending"
                + ", reason=" + reason
                + ", timeoutMs=" + MIUI_OVERVIEW_DISMISS_TIMEOUT_MS
                + ", overviewVisible=false");
    }

    protected synchronized void restoreMiuiOverviewAfterDismissTimeout(long pendingUntil) {
        if (miuiOverviewDismissPendingUntilUptime != pendingUntil) {
            return;
        }
        miuiOverviewDismissPendingUntilUptime = 0L;
        setMiuiOverviewVisible(true);
        moduleLog(Log.WARN, TAG, "Miui Recents dismiss confirmation timed out"
                + ", restoredOverviewVisible=true");
    }

    protected void installBackInputDriver(Object edgeBackGestureHandler, Object backAnimationImpl) {
        if (!acceptingBackInputInstalls) {
            return;
        }
        try {
            if (edgeBackGestureHandler == null || backAnimationImpl == null) {
                return;
            }
            Object controller = readField(backAnimationImpl, "this$0");
            Context context = (Context) readField(edgeBackGestureHandler, "mContext");
            ensureMiuiOverviewStateReceiver(context);
            ensureNativeEdgeBackPlugin(edgeBackGestureHandler, context);
            NativeBackInputMonitor existing = nativeInputMonitors.get(edgeBackGestureHandler);
            if (existing != null) {
                existing.updateBackAnimation(backAnimationImpl);
                moduleLog(Log.INFO, TAG, "Updated native SystemUI back input monitor"
                        + ", controller=" + shortObject(controller));
                return;
            }
            NativeBackInputMonitor monitor = createNativeBackInputMonitor(context,
                    edgeBackGestureHandler, controller, backAnimationImpl);
            boolean published;
            synchronized (backInputLifecycleLock) {
                published = acceptingBackInputInstalls;
                if (published) {
                    nativeInputMonitors.put(edgeBackGestureHandler, monitor);
                    try {
                        monitor.attach();
                    } catch (Throwable throwable) {
                        if (nativeInputMonitors.get(edgeBackGestureHandler) == monitor) {
                            nativeInputMonitors.remove(edgeBackGestureHandler);
                        }
                        try {
                            monitor.detach();
                        } catch (Throwable cleanupFailure) {
                            throwable.addSuppressed(cleanupFailure);
                        }
                        throw throwable;
                    }
                }
            }
            if (!published) {
                monitor.detach();
                // Keep the status/overview receiver alive even when the input monitor could
                // not be published.  It must be able to report SystemUI-not-ready and allow
                // a later status query to observe a repaired monitor without depending on a
                // second receiver registration.
                return;
            }
            moduleLog(Log.INFO, TAG, "Installed native SystemUI back input monitor"
                    + ", controller=" + shortObject(controller));
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install SystemUI back input driver", throwable);
        }
    }

    protected void ensureBackInputInstalledFromHandler(Object edgeBackGestureHandler,
                                                       String reason) {
        if (!acceptingBackInputInstalls || edgeBackGestureHandler == null) {
            return;
        }
        try {
            if (nativeInputMonitors.containsKey(edgeBackGestureHandler)) {
                return;
            }
            Object backAnimation = readField(edgeBackGestureHandler, "mBackAnimation");
            if (backAnimation == null) {
                moduleLog(Log.INFO, TAG, "Cannot restore back input from handler yet"
                        + ", reason=" + reason + ", mBackAnimation=null");
                return;
            }
            installBackInputDriver(edgeBackGestureHandler, backAnimation);
            moduleLog(Log.INFO, TAG, "Restored back input from existing EdgeBackGestureHandler"
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to restore back input from handler"
                    + ", reason=" + reason, throwable);
        }
    }

    protected void ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler, Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(
                    () -> ensureNativeEdgeBackPlugin(edgeBackGestureHandler, context));
            return;
        }
        try {
            Object plugin = requireSystemUiPlatformImpl().ensureNativeEdgeBackPlugin(
                    edgeBackGestureHandler, context);
            if (plugin != null) {
                moduleLog(Log.INFO, TAG, "Installed native AOSP NavigationEdgeBackPlugin: "
                        + shortObject(plugin) + ", impl="
                        + requireSystemUiPlatformImpl().name());
                return;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to create native AOSP edge back plugin", throwable);
        }
        logNativePluginDiagnostics(edgeBackGestureHandler);
    }

    protected void ensureAospBackAnimations(Object controller, String source) {
        if (controller == null) {
            return;
        }
        try {
            Object registry = readField(controller, "mShellBackAnimationRegistry");
            ensureAospRegistryDefinitions(registry, source);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to ensure AOSP back animations from " + source,
                    throwable);
        }
    }
}
