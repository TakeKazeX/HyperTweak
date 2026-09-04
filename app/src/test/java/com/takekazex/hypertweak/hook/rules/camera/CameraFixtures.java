package com.takekazex.hypertweak.hook.rules.camera;

/**
 * Plain-JVM fixtures mirroring the camera's device-config factory shape:
 * a static cache field `b` typed as the config class + a static zero-arg factory method.
 * The old build named the factory method `q`, the 6.6.000510.0 build renamed it `G0` —
 * mirroring `Je.e` across both verified camera versions.
 */
public final class CameraFixtures {

    /** Old-build shape: factory method `q`. */
    public static final class Factory460 {
        public static Factory460 b;
        public static Factory460 q() { return null; }
    }

    /** New-build shape: factory method renamed to `G0`, field unchanged. */
    public static final class Factory510 {
        public static Factory510 b;
        public static Factory510 G0() { return null; }
    }

    /** Same shape but the factory method is gone (renamed to an unknown name). */
    public static final class FactoryBroken {
        public static FactoryBroken b;
    }

    /** A class that exists under a known name but was repurposed (the `Ox.g` / `i5.d` trap). */
    public static final class Repurposed {
        public static String helper() { return ""; }
    }

    /** New camera build shape: adaptive-lens gates were renamed to h5/j5. */
    public static final class AdaptiveLensNew {
        public static boolean h5(Capabilities capabilities) { return false; }
        public static boolean j5(Capabilities capabilities) { return false; }
    }

    /** Old camera build shape: adaptive-lens gates were named g5/i5. */
    public static final class AdaptiveLensOld {
        public static boolean g5(Capabilities capabilities) { return false; }
        public static boolean i5(Capabilities capabilities) { return false; }
    }

    /** Pair is rejected when its two gates accept different capability argument types. */
    public static final class AdaptiveLensMismatched {
        public static boolean h5(Capabilities capabilities) { return false; }
        public static boolean j5(OtherCapabilities capabilities) { return false; }
    }

    /** Pair is rejected when a gate name is overloaded with another one-argument boolean method. */
    public static final class AdaptiveLensOverloaded {
        public static boolean h5(Capabilities capabilities) { return false; }
        public static boolean h5(OtherCapabilities capabilities) { return false; }
        public static boolean j5(Capabilities capabilities) { return false; }
    }

    public static final class Capabilities {}
    public static final class OtherCapabilities {}
}