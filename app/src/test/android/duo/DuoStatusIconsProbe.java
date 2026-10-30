import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.FileOutputStream;

/** Detached status slots with independent native parent motion; no actual device UI. */
public final class DuoStatusIconsProbe {
    static class Slot extends DuoNetworkProbe.AImage {public String mSlot;Slot(Context c,String name,int color){super(c);mSlot=name;setImageDrawable(new ColorDrawable(color));}}
    public static class Speed extends DuoNetworkProbe.AText {Speed(Context c){super(c);setText("8K/s");setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,12);setTextColor(Color.WHITE);}public String getSlot(){return "network_speed";}}
    static Bitmap frame(View root){Bitmap b=Bitmap.createBitmap(192,224,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));return b;}
    static int[] pixels(Bitmap b){int[] p=new int[192*224];b.getPixels(p,0,192,0,0,192,224);return p;}
    static void run(Context context,ClassLoader cl,String output)throws Exception{
        Class<?> type=cl.loadClass(DuoNetworkProbe.H+"DuoStatusIconsMotion"),ownersType=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.SignalTargetOwnership");
        Method update=type.getMethod("update",ViewGroup.class,View.class,View.class,View.class,float.class,ownersType),clear=type.getMethod("clear");
        Bitmap sheet=Bitmap.createBitmap(960,448,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sheet);canvas.drawColor(Color.BLACK);int frames=0;
        for(boolean rtl:new boolean[]{false,true}){
            DuoNetworkProbe.ARoot root=new DuoNetworkProbe.ARoot(context),home=new DuoNetworkProbe.ARoot(context),fake=new DuoNetworkProbe.ARoot(context),target=new DuoNetworkProbe.ARoot(context);
            root.addView(fake);root.addView(target);root.layout(0,0,192,224);home.layout(0,0,192,40);fake.layout(0,0,192,40);target.layout(0,144,192,184);
            String[] slots={"bluetooth","mute","alarm_clock","vpn"};Slot[] src=new Slot[4],copies=new Slot[4],dest=new Slot[4];
            for(int i=0;i<4;i++){src[i]=new Slot(context,slots[i],Color.GREEN);home.addView(src[i]);int x=rtl?168-i*32:8+i*32;src[i].layout(x,4,x+16,20);}
            // Deliberately different child order; slot identity must control matching.
            for(int i=3;i>=0;i--){copies[i]=new Slot(context,slots[i],Color.GREEN);fake.addView(copies[i]);int x=rtl?168-i*32:8+i*32;copies[i].layout(x,4,x+16,20);
                dest[i]=new Slot(context,slots[i],Color.WHITE);target.addView(dest[i]);int tx=rtl?156-i*32:16+i*32;dest[i].layout(tx,4,tx+20,24);}
            Slot wifiFake=new Slot(context,"wifi",Color.BLUE),wifiTarget=new Slot(context,"wifi",Color.BLUE);fake.addView(wifiFake);target.addView(wifiTarget);wifiFake.layout(144,4,160,20);wifiTarget.layout(144,4,164,24);
            Object motion=type.getConstructor().newInstance(),owners=ownersType.getConstructor().newInstance(),rival=type.getConstructor().newInstance();
            int[][] forward=new int[5][];
            for(int step:new int[]{0,1,2,3,4,3,2,1,0}){
                float p=step/4f;fake.setTranslationX(-60*p);fake.setTranslationY(52*p);fake.setAlpha(1-p);target.setTranslationX(-32*(1-p));target.setTranslationY(-64*(1-p));target.setAlpha(p);
                update.invoke(motion,root,home,fake,target,p,owners);Bitmap b=frame(root);
                if(step<4)for(int i=0;i<4;i++){
                    if(copies[i].getAlpha()!=0||dest[i].getAlpha()!=0)throw new AssertionError("Stationary status icon duplicate remains");
                    float fromX=src[i].getLeft()+8,toX=target.getTranslationX()+dest[i].getLeft()+10;
                    float fromY=12,toY=144+target.getTranslationY()+14;
                    int x=Math.round(fromX+(toX-fromX)*p),y=Math.round(fromY+(toY-fromY)*p);
                    int color=b.getPixel(x,y);if(Color.alpha(color)<240||Color.green(color)<200)throw new AssertionError("Slot did not follow its target "+slots[i]+" p="+p+" at="+x+","+y);
                }
                else for(int i=0;i<4;i++)if(copies[i].getAlpha()!=1||dest[i].getAlpha()!=1)throw new AssertionError("Expanded endpoint alpha not restored");
                if(wifiFake.getAlpha()!=1||wifiTarget.getAlpha()!=1)throw new AssertionError("Generic motion stole network ownership");
                int[] now=pixels(b);if(forward[step]!=null&&!Arrays.equals(now,forward[step]))throw new AssertionError("Other icons reverse path differs");
                if(forward[step]==null)canvas.drawBitmap(b,step*192,rtl?224:0,null);forward[step]=now;frames++;
            }
            update.invoke(rival,root,home,fake,target,.5f,owners);clear.invoke(rival);
            for(int i=0;i<4;i++)if(copies[i].getAlpha()!=0||dest[i].getAlpha()!=0)throw new AssertionError("Rival proxy changed live masks");
            // Host updates must survive cleanup; dynamic symbols and tint must remain live.
            dest[0].setAlpha(.6f);src[0].getDrawable().setTint(Color.RED);
            update.invoke(motion,root,home,fake,target,.25f,owners);Bitmap recolored=frame(root);
            int rx=Math.round(src[0].getLeft()+8+(target.getTranslationX()+dest[0].getLeft()+10-src[0].getLeft()-8)*.25f);
            int ry=Math.round(12+(144+target.getTranslationY()+14-12)*.25f);int refreshed=recolored.getPixel(rx,ry);
            if(Color.red(refreshed)<200||Color.green(refreshed)>150||Color.alpha(refreshed)<180)throw new AssertionError("Status tint stopped updating during the gesture");
            clear.invoke(motion);
            if(Math.abs(dest[0].getAlpha()-.6f)>.001f)throw new AssertionError("Cleanup lost newer native alpha");
            for(int i=1;i<4;i++)if(dest[i].getAlpha()!=1||copies[i].getAlpha()!=1)throw new AssertionError("Retirement retained icon mask");
            // A symbol disappears from a row; its old lease must be restored immediately.
            update.invoke(motion,root,home,fake,target,.5f,owners);fake.removeView(copies[1]);target.removeView(dest[1]);update.invoke(motion,root,home,fake,target,.5f,owners);
            if(copies[1].getAlpha()!=1||dest[1].getAlpha()!=1)throw new AssertionError("Removed slot retained a stale mask");
            clear.invoke(motion);
            Slot disappearing=new Slot(context,"recording",Color.GREEN),disappearingFake=new Slot(context,"recording",Color.GREEN);
            home.addView(disappearing);fake.addView(disappearingFake);disappearing.layout(140,24,156,40);disappearingFake.layout(140,24,156,40);
            Slot appearing=new Slot(context,"hotspot",Color.WHITE);target.addView(appearing);appearing.layout(140,24,156,40);
            update.invoke(motion,root,home,fake,target,.5f,owners);Bitmap changed=frame(root);
            int sourceOnly=Color.alpha(changed.getPixel(148,32));
            int targetOnly=Color.alpha(changed.getPixel(Math.round(148+target.getTranslationX()),Math.round(176+target.getTranslationY())));
            if(sourceOnly<115||sourceOnly>140||targetOnly<115||targetOnly>140)throw new AssertionError("Missing endpoint did not fade safely "+sourceOnly+","+targetOnly);
            clear.invoke(motion);
            Speed speedHome=new Speed(context),speedFake=new Speed(context),speedTarget=new Speed(context);home.addView(speedHome);fake.addView(speedFake);target.addView(speedTarget);
            for(Speed speed:new Speed[]{speedHome,speedFake,speedTarget}){speed.measure(View.MeasureSpec.makeMeasureSpec(36,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(20,View.MeasureSpec.EXACTLY));speed.layout(4,20,40,40);}
            update.invoke(motion,root,home,fake,target,.5f,owners);frame(root);
            if(speedFake.getAlpha()!=0||speedTarget.getAlpha()!=0)throw new AssertionError("getSlot-only network-speed widget was skipped");
            speedHome.setText("10M/s");update.invoke(motion,root,home,fake,target,.6f,owners);frame(root);clear.invoke(motion);
            if(speedFake.getAlpha()!=1||speedTarget.getAlpha()!=1)throw new AssertionError("Dynamic text widget was not restored");
            if(disappearingFake.getAlpha()!=1||appearing.getAlpha()!=1)throw new AssertionError("One-sided slot retained its mask");
        }
        try(FileOutputStream out=new FileOutputStream(output)){sheet.compress(Bitmap.CompressFormat.PNG,100,out);}
        System.out.println("PASS other_status_icon_frames="+frames+" moved_slots=4 rtl_cases=2 ownership_dynamic_cleanup_cases=6 one_sided_slots=4 live_tint_cases=2 getSlot_text_cases=2");
    }
}
