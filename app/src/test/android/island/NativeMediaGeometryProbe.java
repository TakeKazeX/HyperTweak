import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.takekazex.hypertweak.hook.rules.systemui.island.IslandTemplateWidthLease;

/** Real Android native-area geometry; no window, media playback or device UI interaction. */
public class NativeMediaGeometryProbe {
    static void layout(LinearLayout root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(102, View.MeasureSpec.EXACTLY));
        root.layout(0,0,180,102);
    }
    public static void main(String[] args) throws Exception {
        if(Looper.myLooper()==null) Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        java.lang.reflect.Constructor<?> ctor=at.getDeclaredConstructor();ctor.setAccessible(true);
        Object thread=ctor.newInstance();
        java.lang.reflect.Method create=Class.forName("android.app.ContextImpl").getDeclaredMethod("createSystemContext",at);
        create.setAccessible(true);Context ctx=(Context)create.invoke(null,thread);
        for(int rotation=0;rotation<4;rotation++) {
            LinearLayout parent=new LinearLayout(ctx);parent.setOrientation(LinearLayout.HORIZONTAL);
            FrameLayout left=new FrameLayout(ctx),camera=new FrameLayout(ctx),right=new FrameLayout(ctx);
            left.setPaddingRelative(24,0,3,0);right.setPaddingRelative(3,0,24,0);
            parent.addView(left,new LinearLayout.LayoutParams(90,102,1));
            parent.addView(camera,new LinearLayout.LayoutParams(72,102));
            parent.addView(right,new LinearLayout.LayoutParams(90,102,1));
            Object owner=new Object();IslandTemplateWidthLease lease=new IslandTemplateWidthLease();
            lease.acquireMedia(owner,left,right);lease.acquireMedia(owner,left,right);layout(parent);
            if(left.getWidth()!=90||right.getWidth()!=90||camera.getWidth()!=0||
                left.getPaddingEnd()!=0||right.getPaddingStart()!=0||
                left.getPaddingStart()!=24||right.getPaddingEnd()!=24)throw new AssertionError("native pair clipped");
            // Native template callbacks may restore the camera placeholder and inward pads.
            camera.getLayoutParams().width=72;
            left.setPaddingRelative(24,0,3,0);right.setPaddingRelative(3,0,24,0);
            parent.getViewTreeObserver().dispatchOnPreDraw();layout(parent);
            if(camera.getWidth()!=0||left.getPaddingEnd()!=0||right.getPaddingStart()!=0)
                throw new AssertionError("native binding overwrote side geometry");
            lease.acquire(owner,left,right,72);layout(parent);
            if(camera.getWidth()!=72||left.getPaddingEnd()!=3||right.getPaddingStart()!=3)
                throw new AssertionError("native main geometry not restored");
            lease.acquireMedia(owner,left,right);lease.release(owner,72);layout(parent);
            if(camera.getWidth()!=72||left.getPaddingEnd()!=3||right.getPaddingStart()!=3||
                ((LinearLayout.LayoutParams)left.getLayoutParams()).weight!=1)
                throw new AssertionError("retired native geometry leaked");
            lease.acquire(owner,left,right,72);
            left.setPaddingRelative(24,0,5,0);right.setPaddingRelative(5,0,24,0);
            parent.getViewTreeObserver().dispatchOnPreDraw();layout(parent);
            if(left.getPaddingEnd()!=5||right.getPaddingStart()!=5)
                throw new AssertionError("ordinary native padding was overwritten");
            lease.acquireMedia(owner,left,right);lease.release(owner,72);layout(parent);
            if(left.getPaddingEnd()!=5||right.getPaddingStart()!=5)
                throw new AssertionError("media readmission restored stale native padding");
        }
        System.out.println("NATIVE_MEDIA_GEOMETRY_OK rotations=4 paired=180 camera=0 outer=24 nativeRestored=true");
        System.exit(0);
    }
}
