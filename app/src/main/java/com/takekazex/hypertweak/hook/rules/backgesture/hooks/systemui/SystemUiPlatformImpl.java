package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.animation.Animator;
import android.annotation.SuppressLint;
import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@SuppressLint("PrivateApi")
abstract class SystemUiPlatformImpl {
    abstract String name();

    abstract Class<?> backAnimationParameterClass(ClassLoader classLoader) throws Exception;

    abstract boolean isNavigationOverlayExcluded(Object edgeBackGestureHandler,
                                                 int x, int y) throws Exception;

    abstract Object findNativeEdgeBackPlugin(Object edgeBackGestureHandler) throws Exception;

    abstract Object ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler,
                                               Context context) throws Exception;

    abstract void prepareNativeBackPanel(Object edgeBackGestureHandler,
                                         Object plugin) throws Exception;

    abstract void updateDisplaySize(Object edgeBackGestureHandler,
                                    Object plugin) throws Exception;

    abstract boolean canCreateNavBarOrTaskBar(Object controller, int displayId)
            throws Exception;

    boolean requiresStableGestureInsetsOverrideTypes() {
        return false;
    }

    int backAnimationBackgroundEnsureParameterCount() {
        return 6;
    }

    String defaultTransitionAnimatorsFieldName() {
        return "mAnimations";
    }

    String defaultTransitionOpenCaptureHookId() {
        return "systemui_default_transition_start";
    }

    boolean captureOpenFromTransitionsOwner() {
        return false;
    }

    Animator[] unwrapDefaultTransitionAnimators(Object entry) throws Exception {
        return entry instanceof Animator ? new Animator[]{(Animator) entry} : null;
    }

    Method brokenBackAnimationStatusBarResetMethod(ClassLoader classLoader)
            throws Exception {
        return null;
    }

    Method backEventGuardMethod(ClassLoader classLoader) throws Exception {
        Class<?> controllerClass = Class.forName(
                "com.android.wm.shell.back.BackAnimationController",
                false, classLoader);
        Method method = controllerClass.getDeclaredMethod(
                "sendBackEvent", int.class);
        method.setAccessible(true);
        return method;
    }

    void injectLegacyBackKey(Object controller, int displayId) throws Exception {
        invokeCompatible(controller, "sendBackEvent", Integer.valueOf(0));
        invokeCompatible(controller, "sendBackEvent", Integer.valueOf(1));
    }

    String systemUiInputArbiterStateAction(String defaultAction) {
        return defaultAction;
    }

    boolean nativeLauncherOwnsContextualSearchLongPress() {
        return false;
    }

    void destroy() {
    }

    static Object readField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        return field.get(target);
    }

    static void writeField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.set(target, value);
    }

    static Field findField(Class<?> type, String name) throws Exception {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object invokeCompatible(Object target, String name, Object... args) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterTypes().length != args.length) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (args[i] != null && !box(parameters[i]).isInstance(args[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(target, args);
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }
}
