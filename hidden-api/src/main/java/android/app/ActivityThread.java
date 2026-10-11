package android.app;

/** Compile-only declaration for resolving the current process Application. */
public final class ActivityThread {
    private ActivityThread() {
        throw new RuntimeException("Stub");
    }

    public static Application currentApplication() {
        throw new RuntimeException("Stub");
    }
}
