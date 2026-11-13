package android.view;

public final class RemoteAnimationTarget {
    public int taskId;
    public android.app.ActivityManager.RunningTaskInfo taskInfo;
    public SurfaceControl leash;
    public android.app.WindowConfiguration windowConfiguration;
    private RemoteAnimationTarget() {
        throw new RuntimeException("Stub");
    }
}
