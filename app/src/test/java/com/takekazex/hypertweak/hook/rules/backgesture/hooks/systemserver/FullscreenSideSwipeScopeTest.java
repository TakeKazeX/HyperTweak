package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemserver;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import io.github.libxposed.api.XposedInterface;

public class FullscreenSideSwipeScopeTest {
    @FunctionalInterface
    private interface Callback {
        Object call() throws Throwable;
    }

    private static XposedInterface.Chain callbackChain(Callback callback) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("proceed".equals(method.getName())) {
                        return callback.call();
                    }
                    throw new AssertionError("Unexpected callback access: " + method);
                });
    }

    @Test
    public void nonNullNavigationBarIsRewrittenOnlyWithinTheSideCallback() throws Throwable {
        FullscreenSideSwipeScope scope = new FullscreenSideSwipeScope();
        Object policy = new Object();
        Object navigationBar = new Object();
        List<Object> request = Arrays.asList(navigationBar, false);
        Object originalResult = new Object();

        // The ordinary/AOSP request stays untouched until direct mode scopes a side callback.
        assertNull(scope.requestArguments(policy, request));
        assertSame(originalResult, scope.intercept(callbackChain(() -> {
            assertArrayEquals(new Object[]{null, false}, scope.requestArguments(policy, request));
            assertSame(navigationBar, request.get(0));
            return originalResult;
        }), policy, navigationBar));
        assertNull(scope.requestArguments(policy, request));
    }

    @Test
    public void otherDisplaysWindowsAndSystemBarRequestsStayNative() throws Throwable {
        FullscreenSideSwipeScope scope = new FullscreenSideSwipeScope();
        Object policy = new Object();
        Object navigationBar = new Object();
        scope.intercept(callbackChain(() -> {
            assertNull(scope.requestArguments(new Object(), Arrays.asList(navigationBar, false)));
            assertNull(scope.requestArguments(policy, Arrays.asList(new Object(), false)));
            assertNull(scope.requestArguments(policy, Arrays.asList(navigationBar, true)));
            assertNull(scope.requestArguments(policy, Arrays.asList(navigationBar, null)));
            assertNull(scope.requestArguments(policy, Arrays.asList(null, false)));
            assertNull(scope.requestArguments(policy, Collections.singletonList(navigationBar)));
            return null;
        }), policy, navigationBar);
    }

    @Test
    public void nestedCallbackFailureRestoresTheOuterOwnerAndThenClearsIt() throws Throwable {
        FullscreenSideSwipeScope scope = new FullscreenSideSwipeScope();
        Object outerPolicy = new Object();
        Object outerBar = new Object();
        Object innerPolicy = new Object();
        Object innerBar = new Object();
        RuntimeException originalFailure = new RuntimeException("native callback failed");
        scope.intercept(callbackChain(() -> {
            try {
                scope.intercept(callbackChain(() -> {
                    assertNull(scope.requestArguments(outerPolicy, Arrays.asList(outerBar, false)));
                    assertNotNull(scope.requestArguments(innerPolicy, Arrays.asList(innerBar, false)));
                    throw originalFailure;
                }), innerPolicy, innerBar);
                fail("Original exception must propagate");
            } catch (RuntimeException observed) {
                assertSame(originalFailure, observed);
            }
            assertNotNull(scope.requestArguments(outerPolicy, Arrays.asList(outerBar, false)));
            assertNull(scope.requestArguments(innerPolicy, Arrays.asList(innerBar, false)));
            return null;
        }), outerPolicy, outerBar);
        assertNull(scope.requestArguments(outerPolicy, Arrays.asList(outerBar, false)));
        assertNull(scope.requestArguments(innerPolicy, Arrays.asList(innerBar, false)));
    }

    @Test
    public void sideCallbackOwnershipDoesNotLeakToAnotherThread() throws Throwable {
        FullscreenSideSwipeScope scope = new FullscreenSideSwipeScope();
        Object policy = new Object();
        Object navigationBar = new Object();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scope.intercept(callbackChain(() -> {
            Thread other = new Thread(() -> {
                try {
                    assertNull(scope.requestArguments(policy, Arrays.asList(navigationBar, false)));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            other.start();
            other.join(5000L);
            assertFalse("Request check must finish", other.isAlive());
            if (failure.get() != null) {
                throw failure.get();
            }
            assertNotNull(scope.requestArguments(policy, Arrays.asList(navigationBar, false)));
            return null;
        }), policy, navigationBar);
    }
}
