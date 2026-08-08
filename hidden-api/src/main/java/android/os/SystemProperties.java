package android.os;

/**
 * Compile-only declaration for the boot-classpath system property helper.
 *
 * <p>The framework supplies the implementation at runtime.  This source is
 * deliberately a stub and is never packaged into the module APK.</p>
 */
public final class SystemProperties {
    private SystemProperties() {
        throw new RuntimeException("Stub");
    }

    public static String get(String key, String def) {
        throw new RuntimeException("Stub");
    }
}
