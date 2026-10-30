import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.animation.ValueAnimator;
import java.lang.reflect.*;
import java.util.*;
import java.io.FileOutputStream;

/** Detached production renderer checks; never opens a Window or changes device settings. */
public final class DuoTransportProbe {
    static final String P=DuoNetworkProbe.P,H=DuoNetworkProbe.H;
    static Object state(ClassLoader cl,boolean wifi,int rows,int active)throws Exception{return state(cl,wifi,rows,active,null);}
    static Object state(ClassLoader cl,boolean wifi,int rows,int active,String label)throws Exception{
        Object battery=cl.loadClass(P+"DuoBattery").getConstructor(int.class,boolean.class,boolean.class,Integer.class).newInstance(77,false,false,null);
        Constructor<?> row=cl.loadClass(P+"DuoSignalRow").getConstructor(int.class,int.class,int.class);
        List<Integer> levels=rows==1?Arrays.asList(4):Arrays.asList(4,2);
        List<Object> signals=new ArrayList<>();for(int i=0;i<rows;i++)signals.add(row.newInstance(i+1,i,levels.get(i)));
        return Arrays.stream(cl.loadClass(P+"DuoContent").getConstructors()).filter(c->c.getParameterCount()==10).findFirst().get()
            .newInstance(battery,wifi?Integer.valueOf(4):null,label,active==2?2:4,false,false,false,levels,signals,active);
    }
    static Bitmap frame(Object drawable)throws Exception{
        Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        drawable.getClass().getMethod("draw",Canvas.class).invoke(drawable,new Canvas(b));return b;
    }
    static int[] pixels(Bitmap b){int[] p=new int[128*128];b.getPixels(p,0,128,0,0,128,128);return p;}
    static double center(Bitmap b){double ysum=0,sum=0;for(int y=0;y<128;y++)for(int x=0;x<128;x++){
        int a=Color.alpha(b.getPixel(x,y));sum+=a;ysum+=a*y;}if(sum==0)throw new AssertionError("Transport lost all signal contours");return ysum/sum;}
    static void similar(Bitmap a,Bitmap b,String label){double error=0;int[] aa=pixels(a),bb=pixels(b);
        for(int i=0;i<aa.length;i++)error+=Math.abs(Color.alpha(aa[i])-Color.alpha(bb[i]));
        if(error/aa.length>.8)throw new AssertionError(label+" endpoint alpha error="+error/aa.length);}
    static int run(Context context,ClassLoader cl,String output)throws Exception{
        Class<?> renderer=cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRenderer");Object rendering=renderer.getField("INSTANCE").get(null);
        Method parse=Arrays.stream(renderer.getMethods()).filter(m->m.getName().startsWith("parse-")&&m.getParameterCount()==1).findFirst().get();
        Class<?> doc=cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRenderer$Document");
        Object single=parse.invoke(rendering,new String(context.getAssets().open("svg/Signal-HyperOS3-Single.svg").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        Object stacked=parse.invoke(rendering,new String(context.getAssets().open("svg/Signal-HyperOS3-Stacked.svg").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        Class<?> barsType=cl.loadClass(P+"DuoSignalBars");Object barsCompanion=barsType.getField("Companion").get(null);
        Object bars=barsCompanion.getClass().getMethod("fromDocument",doc).invoke(barsCompanion,single);
        Class<?> morphType=cl.loadClass(P+"SignalBarMorph");Object companion=morphType.getField("Companion").get(null);
        Method create=companion.getClass().getMethod("create",doc,doc,int.class,int.class,float.class,float.class);
        Class<?> drawable=cl.loadClass(P+"DuoDrawable"),layerType=cl.loadClass(P+"DuoRenderLayer"),contentType=cl.loadClass(P+"DuoContent");
        Object composite=Enum.valueOf((Class)layerType,"COMPOSITE");
        Class<?> motionType=cl.loadClass(H+"DuoTransportSignalMotion");
        Method prepare=motionType.getMethod("prepare",drawable,drawable,List.class,barsType),draw=motionType.getMethod("draw",Canvas.class,float.class);
        Method picture=Arrays.stream(renderer.getMethods()).filter(m->m.getName().equals("signalPicture")&&m.getParameterCount()==5).findFirst().get();
        Bitmap compositeSheet=Bitmap.createBitmap(640,512,Bitmap.Config.ARGB_8888);Canvas compositeCanvas=new Canvas(compositeSheet);compositeCanvas.drawColor(Color.BLACK);
        Bitmap sheet=Bitmap.createBitmap(640,24*128,Bitmap.Config.ARGB_8888);Canvas sheetCanvas=new Canvas(sheet);sheetCanvas.drawColor(Color.BLACK);int cases=0,scenario=0;
        for(boolean dots:new boolean[]{false,true})for(int rows:new int[]{1,2})for(boolean dual:new boolean[]{false,true})
        for(int active=1;active<=rows;active++)for(float weight:new float[]{0f,1f}){
            Object cell=drawable.getConstructor(Context.class,layerType).newInstance(context,composite),wifi=drawable.getConstructor(Context.class,layerType).newInstance(context,composite);
            Object cellState=state(cl,false,rows,active),wifiState=state(cl,true,rows,active);
            drawable.getMethod("setContent",contentType).invoke(cell,cellState);drawable.getMethod("setContent",contentType).invoke(wifi,wifiState);
            drawable.getMethod("setCellularDots",boolean.class).invoke(cell,dots);
            drawable.getMethod("setDualSignalDots",boolean.class).invoke(wifi,dual);
            List<Integer> levels=rows==1?Arrays.asList(4):Arrays.asList(4,2);
            Picture source=(Picture)picture.invoke(rendering,rows==1?single:stacked,levels,.28f,.28f,weight);
            drawable.getMethod("setCellularSignalPicture",Picture.class).invoke(cell,source);
            for(Object d:new Object[]{cell,wifi}){drawable.getMethod("setBounds",int.class,int.class,int.class,int.class).invoke(d,0,0,128,128);drawable.getMethod("setHidePowerTrack",boolean.class).invoke(d,true);}
            drawable.getMethod("setHideNetwork",boolean.class).invoke(wifi,true);
            List<Object> geometry=new ArrayList<>();for(int row=0;row<rows;row++)geometry.add(create.invoke(companion,rows==1?single:stacked,single,row,rows,.632f,weight));
            Object motion=motionType.getConstructor().newInstance();
            if(!(Boolean)prepare.invoke(motion,cell,wifi,geometry,bars))throw new AssertionError("Transport geometry not ready");
            int[][] forward=new int[5][];double last=0;
            for(int step:new int[]{0,1,2,3,4,3,2,1,0}){
                float p=step/4f;Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);draw.invoke(motion,new Canvas(b),p);
                if(step>=1&&(!dual||rows==1))DuoRenderProbe.components(b);
                int[] now=pixels(b);if(forward[step]!=null&&!Arrays.equals(now,forward[step]))throw new AssertionError("Transport reverse path differs");
                if(forward[step]==null){double center=center(b);if(center+1<last)throw new AssertionError("Signal jumped upward while joining Wi-Fi");last=center;sheetCanvas.drawBitmap(b,step*128,scenario*128,null);}
                forward[step]=now;if(step==0)similar(b,frame(cell),"cell");if(step==4)similar(b,frame(wifi),"wifi");cases++;
            }
            int preview=scenario==0?0:scenario==8?1:scenario==12?2:scenario==20?3:-1;
            checkView(context,cl,state(cl,false,rows,active,"5G"),wifiState,source,geometry,bars,dots,dual,compositeCanvas,preview);
            scenario++;
        }
        try(FileOutputStream out=new FileOutputStream(output)){sheet.compress(Bitmap.CompressFormat.PNG,100,out);}
        try(FileOutputStream out=new FileOutputStream(output+".composite.png")){compositeSheet.compress(Bitmap.CompressFormat.PNG,100,out);}
        System.out.println("PASS transport_signal_frames="+cases+" interrupted_view_cases="+scenario+" spring_clock_cases="+scenario);
        return cases;
    }
    static void checkView(Context context,ClassLoader cl,Object cell,Object wifi,Picture picture,List<Object> geometry,Object bars,boolean dots,boolean dual,Canvas preview,int previewRow)throws Exception{
        Class<?> type=cl.loadClass(H+"DuoIconView"),contentType=cl.loadClass(P+"DuoContent");
        View view=(View)type.getConstructor(Context.class).newInstance(context);type.getMethod("setSourceIconSidePx",Integer.class).invoke(view,Integer.valueOf(128));
        type.getMethod("setTransportRows",List.class).invoke(view,geometry);type.getMethod("setTransportBars",bars.getClass()).invoke(view,bars);
        Object icon=type.getMethod("getIcon").invoke(view);icon.getClass().getMethod("setCellularDots",boolean.class).invoke(icon,dots);icon.getClass().getMethod("setDualSignalDots",boolean.class).invoke(icon,dual);
        view.measure(View.MeasureSpec.makeMeasureSpec(128,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(128,View.MeasureSpec.EXACTLY));view.layout(0,0,128,128);
        Method submit=type.getMethod("submit",contentType,boolean.class,Picture.class);submit.invoke(view,cell,false,picture);Bitmap initial=frame(view);
        submit.invoke(view,wifi,false,picture);Field animator=type.getDeclaredField("animator");animator.setAccessible(true);((ValueAnimator)animator.get(view)).cancel();
        Field transition=type.getDeclaredField("transition");transition.setAccessible(true);Object t=transition.get(view);Method advance=t.getClass().getMethod("advance",float.class);
        if(previewRow>=0)for(int step=0;step<5;step++){advance.invoke(t,step/4f);preview.drawBitmap(frame(view),step*128,previewRow*128,null);}
        advance.invoke(t,.4f);Bitmap before=frame(view);
        submit.invoke(view,cell,false,picture);((ValueAnimator)animator.get(view)).cancel();advance.invoke(t,0f);
        if(!Arrays.equals(pixels(before),pixels(frame(view))))throw new AssertionError("Reversing transport teleported visible contours");
        advance.invoke(t,1f);if(!Arrays.equals(pixels(initial),pixels(frame(view))))throw new AssertionError("Cellular endpoint did not restore exactly");
        type.getMethod("finishTransition").invoke(view);
        // Exercise the real animator listener/clock, not only direct geometry fractions.
        submit.invoke(view,wifi,false,picture);
        ValueAnimator forward=(ValueAnimator)animator.get(view);forward.setCurrentPlayTime(120);forward.cancel();
        Method getWeights=t.getClass().getMethod("getWeights");
        Object wifiKey=cl.loadClass(H+"DuoRepresentation").getConstructor(boolean.class,String.class,boolean.class).newInstance(true,null,false);
        float moving=(Float)((Map)getWeights.invoke(t)).get(wifiKey);
        if(moving<.5f||moving>.9f)throw new AssertionError("Animator clock skipped smooth curve: "+moving);
        Bitmap movingFrame=frame(view);
        submit.invoke(view,cell,false,picture);ValueAnimator reverse=(ValueAnimator)animator.get(view);
        reverse.setCurrentPlayTime(0);
        if(!Arrays.equals(pixels(movingFrame),pixels(frame(view))))throw new AssertionError("Spring retarget changed current pose");
        reverse.setCurrentPlayTime(1);
        float coast=(Float)((Map)getWeights.invoke(t)).get(wifiKey);
        if(coast<=moving)throw new AssertionError("Spring retarget dropped its incoming velocity");
        reverse.setCurrentPlayTime(900);
        if(!Arrays.equals(pixels(initial),pixels(frame(view))))throw new AssertionError("Spring endpoint did not settle exactly");
        type.getMethod("finishTransition").invoke(view);
    }
}
