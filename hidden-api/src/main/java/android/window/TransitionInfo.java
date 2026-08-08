package android.window;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;

import java.util.List;

/**
 * Compile-only declaration for the framework transition container.
 *
 * <p>Shell and Xiaomi transition implementations remain reflective; this
 * declaration only supplies the boot-classpath type identity used in hook
 * signatures and class-name comparisons.</p>
 */
public final class TransitionInfo {
    private TransitionInfo() {
        throw new RuntimeException("Stub");
    }

    public int getType() {
        throw new RuntimeException("Stub");
    }

    public int getFlags() {
        throw new RuntimeException("Stub");
    }

    public int getDebugId() {
        throw new RuntimeException("Stub");
    }

    public List<Change> getChanges() {
        throw new RuntimeException("Stub");
    }

    public int getRootCount() {
        throw new RuntimeException("Stub");
    }

    public Root getRoot(int index) {
        throw new RuntimeException("Stub");
    }

    public static final class Change {
        private Change() {
            throw new RuntimeException("Stub");
        }

        public int getMode() {
            throw new RuntimeException("Stub");
        }

        public int getFlags() {
            throw new RuntimeException("Stub");
        }

        public boolean hasFlags(int flags) {
            throw new RuntimeException("Stub");
        }

        public ActivityManager.RunningTaskInfo getTaskInfo() {
            throw new RuntimeException("Stub");
        }

        public ComponentName getActivityComponent() {
            throw new RuntimeException("Stub");
        }

        public SurfaceControl getLeash() {
            throw new RuntimeException("Stub");
        }

        public Rect getStartAbsBounds() {
            throw new RuntimeException("Stub");
        }

        public Rect getEndAbsBounds() {
            throw new RuntimeException("Stub");
        }

        public int getStartDisplayId() {
            throw new RuntimeException("Stub");
        }

        public int getEndDisplayId() {
            throw new RuntimeException("Stub");
        }

        public void setMode(int mode) {
            throw new RuntimeException("Stub");
        }
    }

    public static final class Root {
        private Root() {
            throw new RuntimeException("Stub");
        }

        public int getDisplayId() {
            throw new RuntimeException("Stub");
        }

        public SurfaceControl getLeash() {
            throw new RuntimeException("Stub");
        }

        public Point getOffset() {
            throw new RuntimeException("Stub");
        }
    }
}
