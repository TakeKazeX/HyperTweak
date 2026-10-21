// SPDX-License-Identifier: Apache-2.0
package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.annotation.SuppressLint;
import android.graphics.Path;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Boot-framework Xiaomi material APIs, resolved once for an owned background View.
 */
@SuppressLint("PrivateApi")
final class MiuiIndicatorMaterial {
    private MiuiIndicatorMaterial() {
    }

    static Api resolve() throws ReflectiveOperationException {
        return resolve(View.class, Class.forName("android.view.ViewRootImpl", false,
                View.class.getClassLoader()));
    }

    static Api resolve(Class<?> viewType, Class<?> rootType)
            throws ReflectiveOperationException {
        Method supported = method(rootType, "getSupportedMiBlur", boolean.class);
        if (!Modifier.isStatic(supported.getModifiers())) {
            throw new NoSuchMethodException("getSupportedMiBlur must be static");
        }
        return new Api(supported,
                method(viewType, "setMiBackgroundBlurPath", void.class, Path.class),
                method(viewType, "setPassWindowBlurEnabled", boolean.class, boolean.class),
                method(viewType, "setMiBlurWinType", boolean.class, int.class),
                method(viewType, "setMiBackgroundBlurMode", void.class, int.class),
                method(viewType, "setMiViewBlurMode", void.class, int.class),
                method(viewType, "setMiBackgroundBlurRadius", void.class, int.class),
                method(viewType, "addMiBackgroundBlendColor", void.class, int.class, int.class),
                method(viewType, "clearMiBackgroundBlendColor", void.class),
                method(viewType, "getPassWindowBlurEnabled", boolean.class),
                method(viewType, "getMiBackgroundBlurMode", int.class),
                method(viewType, "getMiViewBlurMode", int.class),
                method(viewType, "getMiBackgroundBlurRadius", int.class));
    }

    private static Method method(Class<?> type, String name, Class<?> result,
                                 Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != result) {
            throw new NoSuchMethodException("Unexpected return type for " + name);
        }
        method.setAccessible(true);
        return method;
    }

    static final class Api {
        private final Method supported;
        private final Method path;
        private final Method pass;
        private final Method windowSampling;
        private final Method backgroundMode;
        private final Method viewMode;
        private final Method radius;
        private final Method blend;
        private final Method clearBlend;
        private final Method passEnabled;
        private final Method backgroundModeValue;
        private final Method viewModeValue;
        private final Method radiusValue;

        private Api(Method supported, Method path, Method pass, Method windowSampling,
                    Method backgroundMode,
                    Method viewMode, Method radius, Method blend, Method clearBlend,
                    Method passEnabled, Method backgroundModeValue,
                    Method viewModeValue, Method radiusValue) {
            this.supported = supported;
            this.path = path;
            this.pass = pass;
            this.windowSampling = windowSampling;
            this.backgroundMode = backgroundMode;
            this.viewMode = viewMode;
            this.radius = radius;
            this.blend = blend;
            this.clearBlend = clearBlend;
            this.passEnabled = passEnabled;
            this.backgroundModeValue = backgroundModeValue;
            this.viewModeValue = viewModeValue;
            this.radiusValue = radiusValue;
        }

        boolean enable(Object view, Path shape, int blurRadius, int tint)
                throws ReflectiveOperationException {
            if (!Boolean.TRUE.equals(supported.invoke(null))) {
                return false;
            }
            try {
                // Install the bounded shape before requesting any cross-window sampling.
                path.invoke(view, shape);
                clearBlend.invoke(view);
                blend.invoke(view, tint, 101);
                radius.invoke(view, blurRadius);
                viewMode.invoke(view, 1);
                pass.invoke(view, true);
                // Sample the owned window's extent, which the material overlay sizes
                // to the display; the View path and Outline bound the visible material.
                if (!Boolean.TRUE.equals(windowSampling.invoke(view, 1))) {
                    throw new IllegalStateException("Xiaomi rejected window-bound sampling");
                }
                backgroundMode.invoke(view, 1);
                // The enable call returns false for an unchanged value as well as rejection.
                // Read the accepted state instead of treating its return as proof.
                if (!Boolean.TRUE.equals(passEnabled.invoke(view))
                        || !Integer.valueOf(1).equals(backgroundModeValue.invoke(view))
                        || !Integer.valueOf(1).equals(viewModeValue.invoke(view))
                        || !Integer.valueOf(blurRadius).equals(radiusValue.invoke(view))) {
                    throw new IllegalStateException("Xiaomi rejected indicator material state");
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                try {
                    clear(view);
                } catch (ReflectiveOperationException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        void updatePath(Object view, Path shape) throws ReflectiveOperationException {
            path.invoke(view, shape);
        }

        void clear(Object view) throws ReflectiveOperationException {
            // One failure must not prevent the remaining cleanup calls from running.
            ReflectiveOperationException failure = null;
            Method[] methods = {backgroundMode, viewMode, radius, clearBlend,
                    windowSampling, pass};
            Object[][] values = {{0}, {0}, {0}, {}, {0}, {false}};
            for (int i = 0; i < methods.length; i++) {
                try {
                    methods[i].invoke(view, values[i]);
                } catch (ReflectiveOperationException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
