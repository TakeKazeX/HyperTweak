import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.FileOutputStream;

/** Left clone window fade and generic/Duo ownership transfer in detached production Views. */
public final class DuoLeftIconsProbe {
    static Bitmap icon(int side,int color){Bitmap b=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);new Canvas(b).drawColor(color);return b;}
    static Bitmap frame(View root){Bitmap b=Bitmap.createBitmap(192,224,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));return b;}
    static int[] pixels(Bitmap b){int[] a=new int[192*224];b.getPixels(a,0,192,0,0,192,224);return a;}
    static void run(Context context,ClassLoader cl,String output)throws Exception{
        Class<?> motionType=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftPanelMotion"),function=cl.loadClass("kotlin.jvm.functions.Function0");
        Object callback=Proxy.newProxyInstance(cl,new Class[]{function},(proxy,method,args)->cl.loadClass("kotlin.Unit").getField("INSTANCE").get(null));
        Method update=motionType.getMethod("update",ViewGroup.class,View.class,View.class,float.class,Float.class),clear=motionType.getMethod("clear");
        Class<?> genericType=cl.loadClass(DuoNetworkProbe.H+"DuoStatusIconsMotion"),ownersType=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.SignalTargetOwnership");
        Method genericUpdate=genericType.getMethod("update",ViewGroup.class,View.class,View.class,View.class,float.class,ownersType),exclude=genericType.getMethod("setExcludedSlots",Set.class);
        Bitmap sheet=Bitmap.createBitmap(960,8*224,Bitmap.Config.ARGB_8888);Canvas sheetCanvas=new Canvas(sheet);sheetCanvas.drawColor(Color.BLACK);int count=0,scenario=0;
        for(boolean rtl:new boolean[]{false,true})for(boolean gone:new boolean[]{false,true})for(float scale:new float[]{1f,1.15f}){
            DuoNetworkProbe.ARoot root=new DuoNetworkProbe.ARoot(context),left=new DuoNetworkProbe.ARoot(context),right=new DuoNetworkProbe.ARoot(context),fake=new DuoNetworkProbe.ARoot(context),expanded=new DuoNetworkProbe.ARoot(context);
            root.addView(fake);root.addView(expanded);root.layout(0,0,192,224);root.setScaleX(scale);root.setScaleY(scale);left.layout(0,0,192,40);right.layout(0,0,192,40);fake.layout(0,0,192,40);expanded.layout(0,144,192,184);
            DuoNetworkProbe.AImage source=new DuoNetworkProbe.AImage(context);source.setImageDrawable(new BitmapDrawable(context.getResources(),icon(16,Color.GREEN)));left.addView(source);int sx=rtl?144:12;source.layout(sx,4,sx+32,20);
            DuoStatusIconsProbe.Slot duplicate=new DuoStatusIconsProbe.Slot(context,"bluetooth",Color.GREEN),target=new DuoStatusIconsProbe.Slot(context,"bluetooth",Color.WHITE);fake.addView(duplicate);expanded.addView(target);
            target.setImageDrawable(new BitmapDrawable(context.getResources(),icon(20,Color.WHITE)));int tx=rtl?36:120;target.layout(tx,0,tx+20,28);duplicate.layout(120,4,136,20);
            Object generic=genericType.getConstructor().newInstance(),owners=ownersType.getConstructor().newInstance();
            genericUpdate.invoke(generic,root,right,fake,expanded,.4f,owners);
            if(target.getAlpha()!=0f)throw new AssertionError("Test did not establish previous right-side ownership");
            exclude.invoke(generic,Collections.singleton("bluetooth"));
            if(target.getAlpha()!=1f||duplicate.getAlpha()!=1f)throw new AssertionError("Left migration did not release the previous alpha receipt");
            Object motion=motionType.getConstructor(function).newInstance(callback);left.setAlpha(0f);if(gone)left.setVisibility(View.GONE);
            int[][] forward=new int[5][];
            for(int step:new int[]{0,1,2,3,4,3,2,1,0}){
                float p=step/4f;expanded.setTranslationX(-26*(1-p));expanded.setTranslationY(-64*(1-p));expanded.setAlpha(p);fake.setAlpha(1-p);
                genericUpdate.invoke(generic,root,right,fake,expanded,p,owners);
                update.invoke(motion,root,source,target,p,null);frame(root);
                if(!(Boolean)update.invoke(motion,root,source,target,p,null))throw new AssertionError("Hidden HOME interrupted left continuation");
                source.setAlpha(p>0?0f:1f);duplicate.setAlpha(p==1?1f:0f);
                if(target.getAlpha()!=(p==1?1f:0f))throw new AssertionError("Left endpoint appeared before arrival");
                Bitmap b=frame(root);float[] a={source.getWidth()/2f,source.getHeight()/2f},z={target.getWidth()/2f,target.getHeight()/2f};
                Matrix matrix=new Matrix();source.transformMatrixToGlobal(matrix);matrix.mapPoints(a);matrix.reset();target.transformMatrixToGlobal(matrix);matrix.mapPoints(z);
                float[] point={a[0]+(z[0]-a[0])*p,a[1]+(z[1]-a[1])*p};matrix.reset();root.transformMatrixToGlobal(matrix);Matrix inverse=new Matrix();matrix.invert(inverse);inverse.mapPoints(point);
                int color=b.getPixel(Math.round(point[0]),Math.round(point[1]));if(Color.green(color)<220||Color.alpha(color)<240)throw new AssertionError("Left slot missed its measured path p="+p);
                int[] now=pixels(b);if(forward[step]!=null&&!Arrays.equals(now,forward[step]))throw new AssertionError("Left reverse path differs");
                if(forward[step]==null)sheetCanvas.drawBitmap(b,step*192,scenario*224,null);forward[step]=now;count++;
            }
            target.setAlpha(.6f);update.invoke(motion,root,source,target,.5f,null);frame(root);clear.invoke(motion);
            if(Math.abs(target.getAlpha()-.6f)>.001f)throw new AssertionError("Left cleanup discarded the host's newer alpha");
            genericType.getMethod("clear").invoke(generic);scenario++;
        }
        try(FileOutputStream out=new FileOutputStream(output)){sheet.compress(Bitmap.CompressFormat.PNG,100,out);}
        System.out.println("PASS left_continuation_frames="+count+" hidden_home_scale_rtl_cases="+scenario+" right_to_left_ownership_transfers="+scenario);
    }
}
