package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class MiuiIndicatorMaterialTest {
    @Before
    public void resetCapability() {
        FakeRoot.supported = true;
    }

    @Test
    public void unsupportedPlatformDoesNotMutateTheView() throws Exception {
        FakeRoot.supported = false;
        FakeView view = new FakeView();

        assertFalse(api().enable(view, null, 100, 0x66000000));
        assertTrue(view.calls.isEmpty());
    }

    @Test
    public void provesAcceptedStateAndInstallsTheShapeBeforeCrossWindowSampling() throws Exception {
        FakeView view = new FakeView();
        // Xiaomi also returns false when the requested pass state is unchanged.
        view.pass = true;
        assertTrue(api().enable(view, null, 150, 0x66FFFFFF));

        assertTrue(view.calls.indexOf("shape") < view.calls.indexOf("pass:true"));
        assertTrue(view.calls.indexOf("shape") < view.calls.indexOf("background:1"));
        assertEquals(1, view.backgroundMode);
        assertEquals(1, view.viewMode);
        assertEquals(150, view.radius);
        assertEquals(1, view.blendCount);
        assertEquals(1, view.samplingMode);
    }

    @Test
    public void rejectedWindowSamplingRollsBackThePassOwner() throws Exception {
        FakeView view = new FakeView();
        view.rejectWindowSampling = true;
        try {
            api().enable(view, null, 150, 0x66000000);
            fail("A missing sampling window must not be reported as enabled");
        } catch (IllegalStateException expected) {
            assertCleared(view);
        }
    }

    @Test
    public void rejectedPassRequestRollsBackEveryMaterialProperty() throws Exception {
        FakeView view = new FakeView();
        view.rejectPass = true;
        try {
            api().enable(view, null, 100, 0x66000000);
            fail("Rejected pass state must not be reported as enabled");
        } catch (IllegalStateException expected) {
            assertCleared(view);
        }
    }

    @Test
    public void anExceptionPartwayThroughSetupAlsoRollsBack() throws Exception {
        FakeView view = new FakeView();
        view.rejectRadius = true;
        try {
            api().enable(view, null, 100, 0x66000000);
            fail("Native setter failure must not leave partial material state");
        } catch (ReflectiveOperationException expected) {
            assertCleared(view);
        }
    }

    @Test
    public void cleanupStillDisablesCrossWindowSamplingAfterOneSetterFails() throws Exception {
        FakeView view = new FakeView();
        MiuiIndicatorMaterial.Api api = api();
        assertTrue(api.enable(view, null, 100, 0x66000000));
        view.rejectBackgroundClear = true;

        try {
            api.clear(view);
            fail("Cleanup must report its failed setter");
        } catch (ReflectiveOperationException expected) {
            assertFalse(view.pass);
            assertEquals(0, view.viewMode);
            assertEquals(0, view.radius);
            assertEquals(0, view.blendCount);
            assertEquals(0, view.samplingMode);
        }
    }

    @Test
    public void repeatedCleanupLeavesNoPassOwnerOrBlendState() throws Exception {
        FakeView view = new FakeView();
        MiuiIndicatorMaterial.Api api = api();
        assertTrue(api.enable(view, null, 100, 0x66000000));
        api.clear(view);
        api.clear(view);

        assertCleared(view);
    }

    @Test
    public void incompatibleCapabilityShapeFailsBeforeAnySetup() throws Exception {
        try {
            MiuiIndicatorMaterial.resolve(FakeView.class, NonStaticRoot.class);
            fail("An instance support method is not the boot-framework contract");
        } catch (NoSuchMethodException expected) {
            assertTrue(Objects.requireNonNull(expected.getMessage()).contains("static"));
        }
    }

    private static MiuiIndicatorMaterial.Api api() throws Exception {
        return MiuiIndicatorMaterial.resolve(FakeView.class, FakeRoot.class);
    }

    private static void assertCleared(FakeView view) {
        assertFalse(view.pass);
        assertEquals(0, view.backgroundMode);
        assertEquals(0, view.viewMode);
        assertEquals(0, view.radius);
        assertEquals(0, view.blendCount);
        assertEquals(0, view.samplingMode);
    }

    public static final class FakeRoot {
        static boolean supported;

        public static boolean getSupportedMiBlur() {
            return supported;
        }
    }

    public static final class NonStaticRoot {
        public boolean getSupportedMiBlur() {
            return true;
        }
    }

    /** Models the observed View API contract without invoking Android rendering stubs. */
    public static final class FakeView {
        final List<String> calls = new ArrayList<>();
        int backgroundMode;
        int viewMode;
        int radius;
        int blendCount;
        int samplingMode;
        boolean pass;
        boolean rejectPass;
        boolean rejectRadius;
        boolean rejectBackgroundClear;
        boolean rejectWindowSampling;

        public void setMiBackgroundBlurPath(Path shape) {
            calls.add("shape");
        }

        public boolean setPassWindowBlurEnabled(boolean enabled) {
            calls.add("pass:" + enabled);
            boolean changed = pass != enabled;
            pass = enabled && !rejectPass;
            return changed;
        }

        public boolean setMiBlurWinType(int mode) {
            if (mode > 0 && rejectWindowSampling) {
                return false;
            }
            samplingMode = mode;
            return true;
        }

        public void setMiBackgroundBlurMode(int mode) {
            calls.add("background:" + mode);
            if (mode == 0 && rejectBackgroundClear) {
                throw new IllegalStateException("Rejected background cleanup");
            }
            backgroundMode = mode;
        }

        public void setMiViewBlurMode(int mode) {
            viewMode = mode;
        }

        public void setMiBackgroundBlurRadius(int value) {
            if (value > 0 && rejectRadius) {
                throw new IllegalStateException("Rejected radius");
            }
            radius = value;
        }

        public void addMiBackgroundBlendColor(int color, int mode) {
            blendCount++;
        }

        public void clearMiBackgroundBlendColor() {
            blendCount = 0;
        }

        public boolean getPassWindowBlurEnabled() {
            return pass;
        }

        public int getMiBackgroundBlurMode() {
            return backgroundMode;
        }

        public int getMiViewBlurMode() {
            return viewMode;
        }

        public int getMiBackgroundBlurRadius() {
            return radius;
        }
    }
}
