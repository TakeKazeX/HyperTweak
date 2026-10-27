package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Insets;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.InsetsFrameProvider;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Owns navigation bars, gesture insets and headless host leases. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public abstract class SystemUiNavigationRuntime extends SystemUiOpenTransitionRuntime {
    protected abstract void ensureBackInputInstalledFromHandler(Object handler, String source);
    protected abstract Context resolveCurrentApplicationContext(ClassLoader loader);
    protected abstract void ensureMiuiOverviewStateReceiver(Context context);
    protected abstract void installBackInputDriver(Object handler, Object animation);
    protected abstract Object onEdgeBackUpdateIsEnabled(XposedInterface.Chain chain) throws Throwable;
    protected abstract Object onEdgeBackNavigationModeChanged(XposedInterface.Chain chain) throws Throwable;
    protected abstract Object onEdgeBackSetBackAnimation(XposedInterface.Chain chain) throws Throwable;

    protected void hookNavigationBarGestureInsets(ClassLoader classLoader) {
        try {
            Class<?> navigationBarClass = Class.forName(NAVIGATION_BAR, false, classLoader);
            Method method = navigationBarClass.getDeclaredMethod(
                    "getBarLayoutParamsForRotation", int.class);
            method.setAccessible(true);
            registerHook(method, "systemui_navigation_bar_gesture_insets", this::restoreNavigationBarGestureInsets);
            moduleLog(Log.INFO, TAG,
                    "Hooked NavigationBar application gesture Insets restoration");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavigationBar application gesture Insets", throwable);
        }
    }

    protected Object restoreNavigationBarGestureInsets(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (!(result instanceof WindowManager.LayoutParams)) {
            return result;
        }

        try {
            Object navigationBar = chain.getThisObject();
            Object edgeBackGestureHandler = readField(
                    navigationBar, "mEdgeBackGestureHandler");
            InsetsFrameProvider.InsetsSizeOverride imeOverride =
                    new InsetsFrameProvider.InsetsSizeOverride(
                            WindowManager.LayoutParams.TYPE_INPUT_METHOD, Insets.NONE);
            InsetsFrameProvider.InsetsSizeOverride[] imeOverrides =
                    new InsetsFrameProvider.InsetsSizeOverride[]{imeOverride};
            Object providers = readField(result, "providedInsets");
            if (providers == null || !providers.getClass().isArray()) {
                return result;
            }
            int systemGestureType = WindowInsets.Type.systemGestures();
            boolean stableOverrideTypes = requireSystemUiPlatformImpl()
                    .requiresStableGestureInsetsOverrideTypes();
            if (stableOverrideTypes) {
                for (int i = 0; i < Array.getLength(providers); i++) {
                    Object provider = Array.get(providers, i);
                    if (!(provider instanceof InsetsFrameProvider)
                            || ((InsetsFrameProvider) provider).getType()
                            != systemGestureType) {
                        continue;
                    }
                    InsetsFrameProvider typedProvider = (InsetsFrameProvider) provider;
                    typedProvider.setInsetsSizeOverrides(imeOverrides);
                    typedProvider.setMinimalInsetsSizeInDisplayCutoutSafe(Insets.NONE);
                }
            }
            if (!Boolean.TRUE.equals(readField(edgeBackGestureHandler, "mInGestureNavMode"))
                    || !Boolean.TRUE.equals(readField(
                    edgeBackGestureHandler, "mIsBackGestureAllowed"))) {
                return result;
            }

            Context context = (Context) readField(navigationBar, "mContext");
            EdgeWidthSnapshot widths = readEdgeWidthSnapshot(edgeBackGestureHandler,
                    context.getResources().getDisplayMetrics().density);
            int restored = 0;
            for (int i = 0; i < Array.getLength(providers); i++) {
                Object provider = Array.get(providers, i);
                if (!(provider instanceof InsetsFrameProvider)
                        || ((InsetsFrameProvider) provider).getType() != systemGestureType) {
                    continue;
                }
                InsetsFrameProvider typedProvider = (InsetsFrameProvider) provider;
                int providerIndex = typedProvider.getIndex();
                Insets size;
                if (providerIndex == 0) {
                    size = Insets.of(widths.leftSensitivity, 0, 0, 0);
                } else if (providerIndex == 1) {
                    size = Insets.of(0, 0, widths.rightSensitivity, 0);
                } else {
                    continue;
                }
                // WMS also applies the cutout-safe minimum to overridden frames, so keep it zero.
                if (!stableOverrideTypes) {
                    typedProvider.setInsetsSizeOverrides(imeOverrides);
                    typedProvider.setMinimalInsetsSizeInDisplayCutoutSafe(Insets.NONE);
                }
                typedProvider.setInsetsSize(size);
                restored++;
            }
            moduleLog(restored == 2 ? Log.INFO : Log.WARN, TAG,
                    "Restored application system-gesture Insets with zero IME override"
                            + ", left=" + widths.leftSensitivity
                            + ", right=" + widths.rightSensitivity
                            + ", providers=" + restored);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to restore IME-safe application gesture Insets", throwable);
        }
        return result;
    }

    protected void hookNavigationBarTransientAutoHide(ClassLoader classLoader) {
        try {
            Class<?> navigationBarClass = Class.forName(NAVIGATION_BAR, false, classLoader);
            Method method = navigationBarClass.getDeclaredMethod(
                    "showTransient", int.class, int.class, boolean.class);
            method.setAccessible(true);
            registerHook(method, "systemui_navigation_bar_show_transient", this::preserveTransientBarAutoHide);
            moduleLog(Log.INFO, TAG, "Hooked NavigationBar.showTransient auto-hide preservation");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavigationBar transient auto-hide", throwable);
        }
    }

    protected Object preserveTransientBarAutoHide(XposedInterface.Chain chain)
            throws Throwable {
        Object navigationBar = chain.getThisObject();
        boolean wasTransient = false;
        Integer modeBefore = null;
        try {
            wasTransient = Boolean.TRUE.equals(readField(navigationBar, "mTransientShown"));
            Object mode = readField(navigationBar, "mTransitionMode");
            if (mode instanceof Number) {
                modeBefore = Integer.valueOf(((Number) mode).intValue());
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Cannot snapshot transient NavigationBar state", throwable);
        }

        Object result = chain.proceed();
        try {
            if (wasTransient || modeBefore == null
                    || !Boolean.TRUE.equals(readField(navigationBar, "mTransientShown"))) {
                return result;
            }
            Object modeAfter = readField(navigationBar, "mTransitionMode");
            if (!(modeAfter instanceof Number)
                    || ((Number) modeAfter).intValue() != modeBefore.intValue()) {
                return result;
            }
            Object autoHideController = readField(navigationBar, "mAutoHideController");
            if (autoHideController == null) {
                moduleLog(Log.WARN, TAG,
                        "Transparent transient NavigationBar has no AutoHideController");
                return result;
            }
            invokeAnyMethod(autoHideController, "touchAutoHide", new Object[0]);
            moduleLog(Log.INFO, TAG,
                    "Preserved native transient-bar auto-hide with unchanged transparent mode");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to preserve transparent transient-bar auto-hide", throwable);
        }
        return result;
    }

    protected void hookNavigationBarTransientAppearance(ClassLoader classLoader) {
        try {
            Class<?> helperClass = Class.forName(NAV_BAR_HELPER, false, classLoader);
            Method method = helperClass.getDeclaredMethod(
                    "transitionMode", int.class, boolean.class);
            method.setAccessible(true);
            registerHook(method, "systemui_navigation_bar_transient_appearance", this::preserveTransientBarAppearance);
            moduleLog(Log.INFO, TAG, "Hooked NavBarHelper.transitionMode transient appearance");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavBarHelper transient appearance", throwable);
        }
    }

    protected void hookStatusBarTransientAppearance(ClassLoader classLoader) {
        try {
            Class<?> appearanceClass;
            try {
                appearanceClass = Class.forName(
                        STATUS_BAR_APPEARANCE_LAMBDA, false, classLoader);
            } catch (ClassNotFoundException absentReducer) {
                // HyperTweak: current Android 17 uses an inlined Flow combine reducer.
                // Preserve that native appearance path rather than assuming its synthetic
                // lambda has the old six-argument ABI or changing transient-bar state globally.
                moduleLog(Log.DEBUG, TAG, "Native status-bar appearance reducer retained");
                return;
            }
            Method method = findAnyMethod(appearanceClass, "invoke", 6);
            if (method == null) {
                throw new NoSuchMethodException(STATUS_BAR_APPEARANCE_LAMBDA + ".invoke/6");
            }
            method.setAccessible(true);
            registerHook(method, "systemui_status_bar_transient_appearance", this::preserveTransientBarAppearance);
            moduleLog(Log.INFO, TAG, "Hooked status-bar transient appearance reducer");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook status-bar transient appearance", throwable);
        }
    }

    protected Object preserveTransientBarAppearance(XposedInterface.Chain chain)
            throws Throwable {
        if (!Boolean.TRUE.equals(chain.getArg(1))) {
            return chain.proceed();
        }
        Object[] args = chain.getArgs().toArray();
        args[1] = Boolean.FALSE;
        return chain.proceed(args);
    }

    protected void hookNavigationBarControllerCreate(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(
                    NAVIGATION_BAR_CONTROLLER_IMPL, false, classLoader);
            Method method = findAnyMethod(controllerClass, "createNavigationBar", 3);
            if (method == null) {
                throw new NoSuchMethodException(
                        NAVIGATION_BAR_CONTROLLER_IMPL + ".createNavigationBar/3");
            }
            method.setAccessible(true);
            registerHook(method, "systemui_navigation_bar_controller_create", this::reconcileAfterNavigationBarCreate);
            moduleLog(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.createNavigationBar"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook NavigationBarControllerImpl.createNavigationBar",
                    throwable);
        }
    }

    protected void hookNavigationBarControllerRemove(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(
                    NAVIGATION_BAR_CONTROLLER_IMPL, false, classLoader);
            Method method = controllerClass.getDeclaredMethod(
                    "removeNavigationBar", int.class);
            method.setAccessible(true);
            registerHook(method, "systemui_navigation_bar_controller_remove", this::reconcileAfterNavigationBarRemove);
            moduleLog(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.removeNavigationBar"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook NavigationBarControllerImpl.removeNavigationBar",
                    throwable);
        }
    }

    protected void hookNavigationBarControllerMode(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(
                    NAVIGATION_BAR_CONTROLLER_IMPL, false, classLoader);
            Method method = controllerClass.getDeclaredMethod(
                    "onNavigationModeChanged", int.class);
            method.setAccessible(true);
            registerHook(method, "systemui_navigation_bar_controller_onNavigationModeChanged", this::reconcileAfterNavigationModeChanged);
            moduleLog(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.onNavigationModeChanged"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavigationBarControllerImpl.onNavigationModeChanged",
                    throwable);
        }
    }

    protected Object reconcileAfterNavigationBarCreate(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object display = chain.getArg(0);
        try {
            if (display instanceof Display && ((Display) display).getDisplayId() == 0) {
                scheduleHeadlessNavBarReconcile(chain.getThisObject(),
                        "createNavigationBar");
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to identify created NavigationBar display",
                    throwable);
        }
        return result;
    }

    protected Object reconcileAfterNavigationBarRemove(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object displayId = chain.getArg(0);
        if (displayId instanceof Number && ((Number) displayId).intValue() == 0) {
            scheduleHeadlessNavBarReconcile(chain.getThisObject(),
                    "removeNavigationBar");
        }
        return result;
    }

    protected Object reconcileAfterNavigationModeChanged(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        scheduleHeadlessNavBarReconcile(chain.getThisObject(),
                "onNavigationModeChanged");
        return result;
    }

    protected void scheduleHeadlessNavBarReconcile(Object controller, String reason) {
        if (controller == null || !acceptingHeadlessNavBarLifecycle) {
            return;
        }
        long generation = headlessNavBarLifecycleGeneration.get();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!acceptingHeadlessNavBarLifecycle
                    || generation != headlessNavBarLifecycleGeneration.get()) {
                return;
            }
            reconcileHeadlessNavBarLifecycle(controller, reason);
        });
    }

    protected void reconcileHeadlessNavBarLifecycle(Object controller, String reason) {
        if (controller == null || !acceptingHeadlessNavBarLifecycle) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scheduleHeadlessNavBarReconcile(controller, reason + ":ownerThread");
            return;
        }
        try {
            Object injector = readField(controller, "mNavigationModeControllerInjector");
            Object navigationBars = readField(controller, "mNavigationBars");
            Object taskbarDelegate = readField(controller, "mTaskbarDelegate");
            Object navBarHelper = readField(controller, "mNavBarHelper");
            Object edgeBackGestureHandler = readField(navBarHelper,
                    "mEdgeBackGestureHandler");
            Object backAnimation = readField(taskbarDelegate, "mBackAnimation");
            Object navModeValue = readField(controller, "mNavMode");
            Object contextValue = readField(controller, "mContext");
            if (!(contextValue instanceof Context)
                    || !(navModeValue instanceof Number)) {
                throw new IllegalStateException("Unexpected NavigationBar controller state"
                        + ", context=" + shortObject(contextValue)
                        + ", navMode=" + shortObject(navModeValue));
            }
            Context context = (Context) contextValue;
            Object displayIdValue = invokeAnyMethod(
                    context, "getDisplayId", new Object[0]);
            if (!(displayIdValue instanceof Number)) {
                throw new IllegalStateException("Unexpected Context displayId="
                        + shortObject(displayIdValue));
            }
            int displayId = ((Number) displayIdValue).intValue();
            int navigationMode = ((Number) navModeValue).intValue();
            Object defaultNavigationBar = invokeAnyMethod(navigationBars, "get",
                    new Object[]{Integer.valueOf(0)});
            boolean taskbarInitialized = Boolean.TRUE.equals(
                    readField(taskbarDelegate, "mInitialized"));
            boolean fsgMode = Boolean.TRUE.equals(readField(injector, "mIsFsgMode"));
            boolean hideGestureLine = Boolean.TRUE.equals(
                    readField(injector, "mHideGestureLine"));
            boolean flipTinyScreen = isMiuiFlipTinyScreen(
                    context, controller.getClass().getClassLoader());
            boolean hasNativeOwner = defaultNavigationBar != null || taskbarInitialized;
            boolean systemHasNavigationBar = false;
            if (displayId == 0) {
                systemHasNavigationBar = requireSystemUiPlatformImpl()
                        .canCreateNavBarOrTaskBar(controller, displayId);
            }
            boolean headlessDesired = displayId == 0
                    && fsgMode
                    && hideGestureLine
                    && !flipTinyScreen
                    && systemHasNavigationBar
                    && !hasNativeOwner
                    && backAnimation != null;

            HeadlessNavBarLease existing;
            synchronized (headlessNavBarLifecycleLock) {
                existing = headlessNavBarLease;
            }
            if (existing != null && existing.controller != controller) {
                if (!detachHeadlessNavBarLease(
                        existing, reason + ":controllerReplaced")) {
                    return;
                }
                existing = null;
            }
            if (existing != null
                    && !containsIdentity(readField(existing.navBarHelper,
                    "mStateListeners"), existing.updaterProxy)) {
                synchronized (headlessNavBarLifecycleLock) {
                    if (headlessNavBarLease == existing) {
                        headlessNavBarLease = null;
                    }
                }
                moduleLog(Log.WARN, TAG, "Headless NavBar updater disappeared"
                        + ", controller=" + shortObject(controller)
                        + ", reason=" + reason);
                existing = null;
            }
            if (existing != null && !existing.ready) {
                if (!detachHeadlessNavBarLease(
                        existing, reason + ":partialAttachCleanup")) {
                    return;
                }
                existing = null;
            }
            if (existing != null && flipTinyScreen) {
                detachHeadlessNavBarLease(existing, reason + ":flipTinyScreen");
                return;
            }
            if (existing != null && hasNativeOwner) {
                detachHeadlessNavBarLease(existing, reason + ":nativeOwnerReady");
                return;
            }
            if (existing != null && !headlessDesired) {
                detachHeadlessNavBarLease(existing, reason + ":noLongerHeadless");
                return;
            }
            if (existing != null) {
                if (existing.navigationMode != navigationMode) {
                    invokeMethod(existing.edgeBackGestureHandler,
                            "onNavigationModeChanged",
                            new Class<?>[]{int.class},
                            new Object[]{Integer.valueOf(navigationMode)});
                    existing.navigationMode = navigationMode;
                    moduleLog(Log.INFO, TAG, "Updated headless EdgeBackGestureHandler mode"
                            + ", mode=" + navigationMode
                            + ", reason=" + reason);
                }
                if (headlessDesired && existing.backAnimation != backAnimation) {
                    if (!detachHeadlessNavBarLease(existing,
                            reason + ":backAnimationReplaced")) {
                        return;
                    }
                    existing = null;
                } else {
                    ensureBackInputInstalledFromHandler(
                            existing.edgeBackGestureHandler,
                            "headlessNavBar:" + reason);
                    return;
                }
            }
            if (!headlessDesired) {
                return;
            }
            attachHeadlessNavBarLease(controller, navBarHelper,
                    edgeBackGestureHandler, backAnimation, navigationMode, reason);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to reconcile headless NavigationBar lifecycle"
                    + ", controller=" + shortObject(controller)
                    + ", reason=" + reason, throwable);
        }
    }

    protected boolean isCurrentHeadlessNavBarLifecycle(Object edgeBackGestureHandler) {
        if (edgeBackGestureHandler == null
                || !acceptingHeadlessNavBarLifecycle
                || Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }
        long generation = headlessNavBarLifecycleGeneration.get();
        HeadlessNavBarLease lease;
        synchronized (headlessNavBarLifecycleLock) {
            lease = headlessNavBarLease;
            if (lease == null || !lease.ready
                    || lease.edgeBackGestureHandler != edgeBackGestureHandler) {
                return false;
            }
        }
        try {
            Object navigationBars = readField(lease.controller, "mNavigationBars");
            Object taskbarDelegate = readField(lease.controller, "mTaskbarDelegate");
            Object injector = readField(
                    lease.controller, "mNavigationModeControllerInjector");
            Object defaultNavigationBar = invokeAnyMethod(
                    navigationBars, "get", new Object[]{Integer.valueOf(0)});
            boolean taskbarInitialized = Boolean.TRUE.equals(
                    readField(taskbarDelegate, "mInitialized"));
            boolean fsgMode = Boolean.TRUE.equals(readField(injector, "mIsFsgMode"));
            boolean hideGestureLine = Boolean.TRUE.equals(
                    readField(injector, "mHideGestureLine"));
            boolean updaterRegistered = containsIdentity(
                    readField(lease.navBarHelper, "mStateListeners"), lease.updaterProxy);
            boolean backAnimationCurrent = readField(
                    taskbarDelegate, "mBackAnimation") == lease.backAnimation;
            if (defaultNavigationBar != null || taskbarInitialized
                    || !fsgMode || !hideGestureLine || !updaterRegistered
                    || !backAnimationCurrent) {
                scheduleHeadlessNavBarReconcile(lease.controller, "inputDown:staleLease");
                return false;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Cannot authenticate live headless NavigationBar lifecycle", throwable);
            return false;
        }
        if (!acceptingHeadlessNavBarLifecycle
                || generation != headlessNavBarLifecycleGeneration.get()) {
            return false;
        }
        synchronized (headlessNavBarLifecycleLock) {
            return headlessNavBarLease == lease
                    && lease.ready
                    && lease.edgeBackGestureHandler == edgeBackGestureHandler;
        }
    }

    protected boolean isMiuiFlipTinyScreen(Context context, ClassLoader classLoader) {
        try {
            Class<?> configsClass = Class.forName(MIUI_CONFIGS, false, classLoader);
            Method method = configsClass.getMethod("isFlipTinyScreen", Context.class);
            return Boolean.TRUE.equals(method.invoke(null, context));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to resolve Xiaomi flip tiny-screen state;"
                    + " headless NavigationBar will fail closed", throwable);
            return true;
        }
    }

    protected void attachHeadlessNavBarLease(Object controller, Object navBarHelper,
                                             Object edgeBackGestureHandler,
                                             Object backAnimation, int navigationMode,
                                             String reason) throws Exception {
        ClassLoader classLoader = controller.getClass().getClassLoader();
        Class<?> updaterInterface = Class.forName(
                NAV_BAR_STATE_UPDATER, false, classLoader);
        Method navigationModeChanged = edgeBackGestureHandler.getClass().getMethod(
                "onNavigationModeChanged", int.class);
        Method registerUpdater = navBarHelper.getClass().getMethod(
                "registerNavTaskStateUpdater", updaterInterface);
        Method removeUpdater = navBarHelper.getClass().getMethod(
                "removeNavTaskStateUpdater", updaterInterface);
        Method setBackAnimation = edgeBackGestureHandler.getClass().getMethod(
                "setBackAnimation", requireSystemUiPlatformImpl()
                        .backAnimationParameterClass(classLoader));
        navigationModeChanged.setAccessible(true);
        registerUpdater.setAccessible(true);
        removeUpdater.setAccessible(true);
        setBackAnimation.setAccessible(true);
        Object updaterProxy = Proxy.newProxyInstance(
                updaterInterface.getClassLoader(),
                new Class<?>[]{updaterInterface},
                (proxy, method, args) -> headlessUpdaterResult(proxy, method, args));
        try {
            navigationModeChanged.invoke(edgeBackGestureHandler,
                    Integer.valueOf(navigationMode));
            registerUpdater.invoke(navBarHelper, updaterProxy);
            Object currentBackAnimation = readField(
                    edgeBackGestureHandler, "mBackAnimation");
            if (currentBackAnimation != backAnimation) {
                setBackAnimation.invoke(edgeBackGestureHandler, backAnimation);
            } else {
                ensureBackInputInstalledFromHandler(edgeBackGestureHandler,
                        "headlessNavBar:existingBackAnimation");
            }
            HeadlessNavBarLease lease = new HeadlessNavBarLease(
                    controller, navBarHelper, edgeBackGestureHandler,
                    updaterProxy, updaterInterface, backAnimation, navigationMode, true);
            synchronized (headlessNavBarLifecycleLock) {
                if (headlessNavBarLease != null) {
                    throw new IllegalStateException("Headless NavBar lease raced with "
                            + shortObject(headlessNavBarLease.controller));
                }
                headlessNavBarLease = lease;
            }
            moduleLog(Log.INFO, TAG, "Attached headless SystemUI NavigationBar lifecycle"
                    + ", controller=" + shortObject(controller)
                    + ", helper=" + shortObject(navBarHelper)
                    + ", handler=" + shortObject(edgeBackGestureHandler)
                    + ", backAnimation=" + shortObject(backAnimation)
                    + ", mode=" + navigationMode
                    + ", reason=" + reason
                    + ", createsWindow=false");
        } catch (Throwable throwable) {
            boolean updaterRemains = false;
            try {
                updaterRemains = containsIdentity(
                        readField(navBarHelper, "mStateListeners"), updaterProxy);
                if (updaterRemains) {
                    removeUpdater.invoke(navBarHelper, updaterProxy);
                }
                updaterRemains = containsIdentity(
                        readField(navBarHelper, "mStateListeners"), updaterProxy);
            } catch (Throwable rollbackFailure) {
                throwable.addSuppressed(rollbackFailure);
                try {
                    updaterRemains = containsIdentity(
                            readField(navBarHelper, "mStateListeners"), updaterProxy);
                } catch (Throwable ignored) {
                    updaterRemains = true;
                }
            }
            if (updaterRemains) {
                HeadlessNavBarLease partialLease = new HeadlessNavBarLease(
                        controller, navBarHelper, edgeBackGestureHandler,
                        updaterProxy, updaterInterface, backAnimation,
                        navigationMode, false);
                synchronized (headlessNavBarLifecycleLock) {
                    if (headlessNavBarLease == null) {
                        headlessNavBarLease = partialLease;
                    }
                }
            }
            if (throwable instanceof Exception) {
                throw (Exception) throwable;
            }
            throw new IllegalStateException("Failed to attach headless NavBar lease",
                    throwable);
        }
    }

    protected Object headlessUpdaterResult(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "equals":
                    return Boolean.valueOf(args != null && args.length == 1
                            && proxy == args[0]);
                case "hashCode":
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString":
                    return "MiuiBackGestureHook.HeadlessNavBarUpdater@"
                            + Integer.toHexString(System.identityHashCode(proxy));
                default:
                    return null;
            }
        }
        return primitiveDefaultValue(method.getReturnType());
    }

    protected static Object primitiveDefaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0f);
        }
        if (type == double.class) {
            return Double.valueOf(0.0d);
        }
        return null;
    }

    protected static boolean containsIdentity(Object collection, Object target) {
        if (!(collection instanceof Iterable)) {
            return false;
        }
        for (Object value : (Iterable<?>) collection) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    protected boolean detachHeadlessNavBarLease(HeadlessNavBarLease lease, String reason) {
        if (lease == null) {
            return true;
        }
        try {
            Object listeners = readField(lease.navBarHelper, "mStateListeners");
            if (containsIdentity(listeners, lease.updaterProxy)) {
                invokeMethod(lease.navBarHelper, "removeNavTaskStateUpdater",
                        new Class<?>[]{lease.updaterInterface},
                        new Object[]{lease.updaterProxy});
            }
            if (containsIdentity(readField(lease.navBarHelper, "mStateListeners"),
                    lease.updaterProxy)) {
                throw new IllegalStateException("Headless NavBar updater remains registered");
            }
            synchronized (headlessNavBarLifecycleLock) {
                if (headlessNavBarLease == lease) {
                    headlessNavBarLease = null;
                }
            }
            moduleLog(Log.INFO, TAG, "Detached headless SystemUI NavigationBar lifecycle"
                    + ", controller=" + shortObject(lease.controller)
                    + ", reason=" + reason);
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to detach headless NavigationBar lifecycle"
                    + ", controller=" + shortObject(lease.controller)
                    + ", reason=" + reason, throwable);
            return false;
        }
    }

    protected Object[][] detachHeadlessNavBarLifecycleForHotReload() {
        HeadlessNavBarLease lease;
        synchronized (headlessNavBarLifecycleLock) {
            lease = headlessNavBarLease;
        }
        if (lease == null) {
            return new Object[0][0];
        }
        Object[][] savedState = new Object[][]{{
                lease.controller, lease.navBarHelper, lease.updaterProxy,
                lease.updaterInterface
        }};
        Runnable detach = () -> detachHeadlessNavBarLease(lease, "hotReload");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detach.run();
            return savedState;
        }
        CountDownLatch completed = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                detach.run();
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(5L, TimeUnit.SECONDS)) {
                moduleLog(Log.ERROR, TAG, "Timed out detaching headless NavBar lease"
                        + " on the SystemUI main Looper");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            moduleLog(Log.ERROR, TAG, "Interrupted detaching headless NavBar lease",
                    exception);
        }
        return savedState;
    }

    protected boolean cleanupOldHeadlessNavBarProxy(Object[] savedLease) {
        if (savedLease.length < 4 || savedLease[1] == null
                || savedLease[2] == null || !(savedLease[3] instanceof Class<?>)) {
            return false;
        }
        Object navBarHelper = savedLease[1];
        Object updaterProxy = savedLease[2];
        Class<?> updaterInterface = (Class<?>) savedLease[3];
        try {
            if (!containsIdentity(readField(navBarHelper, "mStateListeners"),
                    updaterProxy)) {
                return true;
            }
            invokeMethod(navBarHelper, "removeNavTaskStateUpdater",
                    new Class<?>[]{updaterInterface}, new Object[]{updaterProxy});
            if (containsIdentity(readField(navBarHelper, "mStateListeners"),
                    updaterProxy)) {
                throw new IllegalStateException(
                        "Residual headless NavBar updater remains registered");
            }
            moduleLog(Log.WARN, TAG, "Removed residual pre-reload headless NavBar updater"
                    + ", helper=" + shortObject(navBarHelper));
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to remove residual pre-reload headless NavBar updater",
                    throwable);
            return false;
        }
    }

    protected void adoptResidualHeadlessNavBarLease(Object[] savedLease) {
        if (savedLease.length < 4 || savedLease[0] == null
                || savedLease[1] == null || savedLease[2] == null
                || !(savedLease[3] instanceof Class<?>)) {
            return;
        }
        try {
            Object controller = savedLease[0];
            Object navBarHelper = savedLease[1];
            Object edgeBackGestureHandler = readField(
                    navBarHelper, "mEdgeBackGestureHandler");
            Object taskbarDelegate = readField(controller, "mTaskbarDelegate");
            Object backAnimation = readField(taskbarDelegate, "mBackAnimation");
            Object navigationMode = readField(controller, "mNavMode");
            if (!(navigationMode instanceof Number)) {
                throw new IllegalStateException("Residual NavBar mode="
                        + shortObject(navigationMode));
            }
            HeadlessNavBarLease residual = new HeadlessNavBarLease(
                    controller, navBarHelper, edgeBackGestureHandler,
                    savedLease[2], (Class<?>) savedLease[3], backAnimation,
                    ((Number) navigationMode).intValue(), false);
            synchronized (headlessNavBarLifecycleLock) {
                if (headlessNavBarLease == null) {
                    headlessNavBarLease = residual;
                }
            }
            moduleLog(Log.WARN, TAG, "Adopted residual pre-reload headless NavBar updater"
                    + " for deferred exact cleanup");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to adopt residual headless NavBar updater",
                    throwable);
        }
    }

    protected void restoreSystemUiHotReloadLifecycle(ClassLoader classLoader) {
        Object[][] inputState = pendingHotReloadInputState;
        Object[][] savedHeadlessState = pendingHotReloadHeadlessState;
        pendingHotReloadInputState = new Object[0][0];
        pendingHotReloadHeadlessState = new Object[0][0];
        long generation = headlessNavBarLifecycleGeneration.get();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!acceptingHeadlessNavBarLifecycle
                    || generation != headlessNavBarLifecycleGeneration.get()) {
                return;
            }
            Context systemUiContext = resolveCurrentApplicationContext(classLoader);
            if (systemUiContext != null) {
                ensureMiuiOverviewStateReceiver(systemUiContext);
            }
            int headlessRestored = 0;
            for (Object[] savedLease : savedHeadlessState) {
                if (savedLease == null || savedLease.length == 0
                        || savedLease[0] == null) {
                    continue;
                }
                if (!cleanupOldHeadlessNavBarProxy(savedLease)) {
                    adoptResidualHeadlessNavBarLease(savedLease);
                    headlessRestored++;
                    continue;
                }
                Object controller = savedLease[0];
                reconcileHeadlessNavBarLifecycle(controller, "hotReload:savedController");
                headlessRestored++;
            }
            if (headlessRestored == 0 && classLoader != null) {
                Object controller = findNavigationBarControllerFromDependency(classLoader);
                if (controller != null) {
                    reconcileHeadlessNavBarLifecycle(controller,
                            "hotReload:dependencyBackfill");
                    headlessRestored++;
                }
            }
            int inputRestored = 0;
            for (Object[] pair : inputState) {
                if (pair == null || pair.length < 2) {
                    continue;
                }
                installBackInputDriver(pair[0], pair[1]);
                inputRestored++;
            }
            moduleLog(Log.INFO, TAG, "Restored SystemUI hot reload lifecycle on main thread"
                    + ", headlessControllers=" + headlessRestored
                    + ", inputMonitors=" + inputRestored);
        });
    }

    /** Recover an already-created host after package removal or lifting the debug gate. */
    protected void recoverCurrentSystemUiBackInput(ClassLoader classLoader) {
        if (!acceptingBackInputInstalls || !acceptingHeadlessNavBarLifecycle
                || com.takekazex.hypertweak.hook.Preferences.INSTANCE.upstreamBackGestureOwnsInput()) return;
        Object controller = findNavigationBarControllerFromDependency(classLoader);
        if (controller == null) return; // Later native lifecycle callbacks remain retryable.
        reconcileHeadlessNavBarLifecycle(controller, "upstreamGateReleased");
        try {
            // The helper owns the live handler in both ordinary and headless navigation modes.
            Object helper = readField(controller, "mNavBarHelper");
            Object handler = readField(helper, "mEdgeBackGestureHandler");
            ensureBackInputInstalledFromHandler(handler, "upstreamGateReleased:liveHelper");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to recover existing back input after upstream gate", throwable);
        }
    }

    protected Object findNavigationBarControllerFromDependency(ClassLoader classLoader) {
        try {
            Class<?> dependencyClass = Class.forName(
                    SYSTEM_UI_DEPENDENCY, false, classLoader);
            Object dependency = readStaticField(dependencyClass, "sDependency");
            if (dependency == null) {
                moduleLog(Log.INFO, TAG, "SystemUI Dependency is not initialized;"
                        + " NavigationBar hooks will capture the controller later");
                return null;
            }
            Object lazyController = readField(dependency, "mNavigationBarController");
            Object controller = invokeAnyMethod(lazyController, "get", new Object[0]);
            if (controller == null
                    || !NAVIGATION_BAR_CONTROLLER_IMPL.equals(
                    controller.getClass().getName())) {
                moduleLog(Log.WARN, TAG, "Unexpected NavigationBarController dependency="
                        + shortObject(controller));
                return null;
            }
            return controller;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to backfill NavigationBarController from Dependency",
                    throwable);
            return null;
        }
    }

    protected void hookEdgeBackGestureHandler(ClassLoader classLoader,
                                              boolean hookUpdateIsEnabled, boolean hookNavigationModeChanged,
                                              boolean hookSetBackAnimation) {
        Class<?> handlerClass;
        try {
            handlerClass = Class.forName(EDGE_BACK_GESTURE_HANDLER, false, classLoader);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to resolve EdgeBackGestureHandler", throwable);
            return;
        }
        int installed = 0;
        if (hookUpdateIsEnabled) {
            try {
                Method updateIsEnabled = handlerClass.getDeclaredMethod("updateIsEnabled");
                updateIsEnabled.setAccessible(true);
                registerHook(updateIsEnabled, "systemui_edge_back_updateIsEnabled", this::onEdgeBackUpdateIsEnabled);
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler.updateIsEnabled",
                        throwable);
            }
        }
        if (hookNavigationModeChanged) {
            try {
                Method navigationModeChanged = handlerClass.getDeclaredMethod(
                        "onNavigationModeChanged", int.class);
                navigationModeChanged.setAccessible(true);
                registerHook(navigationModeChanged, "systemui_edge_back_onNavigationModeChanged", this::onEdgeBackNavigationModeChanged);
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed to hook EdgeBackGestureHandler.onNavigationModeChanged",
                        throwable);
            }
        }
        if (hookSetBackAnimation) {
            try {
                Method setBackAnimation = handlerClass.getDeclaredMethod("setBackAnimation",
                        requireSystemUiPlatformImpl()
                                .backAnimationParameterClass(classLoader));
                setBackAnimation.setAccessible(true);
                registerHook(setBackAnimation, "systemui_edge_back_setBackAnimation", this::onEdgeBackSetBackAnimation);
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler.setBackAnimation",
                        throwable);
            }
        }
        moduleLog(Log.INFO, TAG, "Hooked EdgeBackGestureHandler AOSP path, installed=" + installed);
    }

    /**
     * Replaces the AOSP back-panel threshold constants. Hooking the shared helper keeps this
     * working when R8 inlines BackPanelController's private threshold methods. The panel still
     * owns all gesture state and visuals; when the optional effect cannot be prepared, the
     * original AOSP method remains the fallback.
     */
}
