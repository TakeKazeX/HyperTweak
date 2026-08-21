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
}