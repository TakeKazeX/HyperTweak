package android.view;

import android.graphics.Insets;

/** Compile-only declaration for the hidden framework inset provider. */
public class InsetsFrameProvider {
    private InsetsFrameProvider() {
        throw new RuntimeException("Stub");
    }

    public int getIndex() {
        throw new RuntimeException("Stub");
    }

    public int getType() {
        throw new RuntimeException("Stub");
    }

    public InsetsFrameProvider setInsetsSize(Insets insets) {
        throw new RuntimeException("Stub");
    }

    public InsetsFrameProvider setInsetsSizeOverrides(InsetsSizeOverride[] overrides) {
        throw new RuntimeException("Stub");
    }

    public InsetsFrameProvider setMinimalInsetsSizeInDisplayCutoutSafe(Insets insets) {
        throw new RuntimeException("Stub");
    }

    public static class InsetsSizeOverride {
        public InsetsSizeOverride(int type, Insets insets) {
            throw new RuntimeException("Stub");
        }
    }
}
