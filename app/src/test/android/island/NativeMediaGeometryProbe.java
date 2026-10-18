import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandCameraSeat;
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandTemplateWidthLease;

/** Real Android native-area geometry; no window, media playback or device UI interaction. */
public class NativeMediaGeometryProbe {
    static void sideMeasurement(Context ctx) {
        Object owner=new Object(),main=new Object(),media=new Object();
        LinearLayout mainRoot=new LinearLayout(ctx),mediaRoot=new LinearLayout(ctx);
        FrameLayout ml=new FrameLayout(ctx),mc=new FrameLayout(ctx),mr=new FrameLayout(ctx);
        FrameLayout sl=new FrameLayout(ctx),sc=new FrameLayout(ctx),sr=new FrameLayout(ctx);
        mainRoot.addView(ml,new LinearLayout.LayoutParams(93,102,1));
        mainRoot.addView(mc,new LinearLayout.LayoutParams(72,102));
        mainRoot.addView(mr,new LinearLayout.LayoutParams(162,102,1));
        mediaRoot.addView(sl,new LinearLayout.LayoutParams(90,102,1));
        mediaRoot.addView(sc,new LinearLayout.LayoutParams(72,102));
        mediaRoot.addView(sr,new LinearLayout.LayoutParams(90,102,1));
        var settings=(com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings)
            java.lang.reflect.Proxy.newProxyInstance(NativeMediaGeometryProbe.class.getClassLoader(),
                new Class[]{com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings.class},
                (proxy,method,args)->method.getName().equals("getThreeIslands") ||
                    method.getName().equals("getRemovePlaceholder"));
        var host=(com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts)
            java.lang.reflect.Proxy.newProxyInstance(NativeMediaGeometryProbe.class.getClassLoader(),
                new Class[]{com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts.class},
                (proxy,method,args)->{
                    switch(method.getName()) {
                        case "isContent": return args[0]==main || args[0]==media;
                        case "ownerOf": return owner;
                        case "handler": return owner;
                        case "current": return args[1]==com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot.BIG?main:
                            args[1]==com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot.SMALL?media:null;
                        case "orientationOf": return com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation.PORTRAIT;
                        case "smallestScreenWidthDp": return 400;
                        case "isTinyScreen": case "inConfigRebuild": return false;
                        case "showOnce": case "tempShow": return null;
                        case "fixedCutoutWidth": return 72;
                        case "templateAreas": return java.util.List.of(
                            new com.takekazex.hypertweak.hook.rules.systemui.island.host.TemplateAreaPair(
                                args[0]==main?ml:sl,args[0]==main?mr:sr));
                        case "measuredContentSize": return new com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize(90,90);
                        case "sceneOf": return new com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene(
                            1200,2600,170,170,12,0,722,
                            new com.takekazex.hypertweak.hook.rules.systemui.island.model.SafeInsets(0,0),
                            com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation.PORTRAIT);
                        case "nativeResult": return new com.takekazex.hypertweak.hook.rules.systemui.island.model.NativeMeasureResult(180,180,0,0);
                    }
                    throw new AssertionError("unexpected host call "+method.getName());
                });
        var runtime=new com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime(host,settings,()->false,null,null);
        var session=runtime.getSessions().getOrCreate(owner);
        session.getScene().setLayoutIntent(true);
        session.getScene().setLayout(new com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout(471,327,279,810,false,1200));
        session.getScene().setPortraitContent(new com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize(93,162));
        session.getScene().setMusicSmall(media);
        session.getScene().getFullFrames().put(media,new com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame(
            279,new com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize(90,90),true));
        for(int i=0;i<3;i++) {
            runtime.getMeasurement().afterMeasure(media,new Object(),new Object(),new Object());
            if(mc.getLayoutParams().width!=72 || sc.getLayoutParams().width!=0)
                throw new AssertionError("side measurement overwrote main camera seat");
        }
        session.getTemplate().getTemplateWidths().releaseAll(view->72);
        System.out.println("SIDE_MEASUREMENT_CAMERA_OK main=72 media=0 repeated=3");
    }
    static void transientSummary(Context ctx) {
        LinearLayout parent=new LinearLayout(ctx);
        FrameLayout left=new FrameLayout(ctx),camera=new FrameLayout(ctx),right=new FrameLayout(ctx);
        parent.addView(left,new LinearLayout.LayoutParams(90,102,1));
        parent.addView(camera,new LinearLayout.LayoutParams(72,102));
        parent.addView(right,new LinearLayout.LayoutParams(90,102,1));
        Object owner=new Object(),view=new Object();
        com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime[] holder=
            new com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime[1];
        com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings settings=
            (com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings) java.lang.reflect.Proxy.newProxyInstance(
                NativeMediaGeometryProbe.class.getClassLoader(),
                new Class[]{com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings.class},
                (proxy,method,args)->method.getName().equals("getThreeIslands"));
        com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts host=
            (com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts) java.lang.reflect.Proxy.newProxyInstance(
                NativeMediaGeometryProbe.class.getClassLoader(),
                new Class[]{com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts.class},
                (proxy,method,args)->{
                    if(method.getName().equals("orientationOf")) return
                        com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation.PORTRAIT;
                    if(method.getName().equals("templateAreas")) return java.util.List.of(
                        new com.takekazex.hypertweak.hook.rules.systemui.island.host.TemplateAreaPair(left,right));
                    if(method.getName().equals("fixedCutoutWidth")) {
                        Integer projected=holder[0].getPresentation().fixedCutoutWidth(
                            holder[0].getSessions().getOrCreate(owner),view);
                        return projected==null?72:projected;
                    }
                    return null;
                });
        holder[0]=new com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime(
            host,settings,()->false,null,null);
        var runtime=holder[0];var session=runtime.getSessions().getOrCreate(owner);
        session.getTemplate().getTemplateWidths().acquireMedia(view,left,right);
        // No current summary handler: reproduce a native Folme onBegin between states.
        Integer projected=runtime.getPresentation().fixedCutoutWidth(session,view);
        if(projected==null||projected!=0) throw new AssertionError("transient summary restored camera cutout");
        runtime.getTemplates().releaseFullPosition(session,view);
        if(camera.getLayoutParams().width!=72||IslandCameraSeat.INSTANCE.holds(parent))
            throw new AssertionError("native camera width lost during template release");
        if(runtime.getPresentation().fixedCutoutWidth(session,view)!=null)
            throw new AssertionError("released summary kept projected camera cutout");
        System.out.println("TRANSIENT_SUMMARY_CAMERA_OK leased=0 restored=72");
    }
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
        IslandCameraSeat.INSTANCE.clear();
        transientSummary(ctx);
        sideMeasurement(ctx);
        for(int rotation=0;rotation<4;rotation++) {
            LinearLayout parent=new LinearLayout(ctx);parent.setOrientation(LinearLayout.HORIZONTAL);
            FrameLayout left=new FrameLayout(ctx),camera=new FrameLayout(ctx),right=new FrameLayout(ctx);
            left.setPaddingRelative(24,0,3,0);right.setPaddingRelative(3,0,24,0);
            parent.addView(left,new LinearLayout.LayoutParams(90,102,1));
            parent.addView(camera,new LinearLayout.LayoutParams(72,102));
            parent.addView(right,new LinearLayout.LayoutParams(90,102,1));
            Object owner=new Object();IslandTemplateWidthLease lease=new IslandTemplateWidthLease();
            lease.acquireMedia(owner,left,right);lease.acquireMedia(owner,left,right);layout(parent);
            if(!IslandCameraSeat.INSTANCE.holds(parent))
                throw new AssertionError("media lease did not release camera seat");
            if(left.getWidth()!=90||right.getWidth()!=90||camera.getWidth()!=0||
                left.getPaddingEnd()!=0||right.getPaddingStart()!=0||
                left.getPaddingStart()!=24||right.getPaddingEnd()!=24)throw new AssertionError("native pair clipped");
            // While the media lease holds the camera seat, the getFixedCutoutWidth hook
            // answers zero, so the native template factory must receive a zero cutout.
            // There is deliberately no module pre-draw repair listener.
            camera.getLayoutParams().width=0;layout(parent);
            if(camera.getWidth()!=0||left.getPaddingEnd()!=0||right.getPaddingStart()!=0||
                left.getWidth()!=90||right.getWidth()!=90)
                throw new AssertionError("native zero-cutout pass clipped the media pair");
            lease.acquire(owner,left,right,72);layout(parent);
            if(IslandCameraSeat.INSTANCE.holds(parent))
                throw new AssertionError("main template kept camera seat");
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
            if(IslandCameraSeat.INSTANCE.holds(parent)||IslandCameraSeat.INSTANCE.tracked()!=0)
                throw new AssertionError("camera seat leaked after media release");
        }
        System.out.println("NATIVE_MEDIA_GEOMETRY_OK rotations=4 paired=180 camera=0 outer=24 nativeRestored=true");
        System.exit(0);
    }
}
