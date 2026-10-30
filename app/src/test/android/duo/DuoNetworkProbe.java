import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.io.FileOutputStream;
import java.util.*;

/** Production handover paths in isolated software Views; no Window or device UI interaction. */
public final class DuoNetworkProbe {
    static final String P = "com.takekazex.hypertweak.presentation.icon.duo.";
    static final String H = "com.takekazex.hypertweak.hook.rules.systemui.icon.duo.";
    static Object state(ClassLoader cl, boolean wifi, String label) throws Exception {
        Object battery = cl.loadClass(P+"DuoBattery").getConstructor(int.class,boolean.class,boolean.class,Integer.class)
            .newInstance(77,true,false,null);
        Constructor<?> row = cl.loadClass(P+"DuoSignalRow").getConstructor(int.class,int.class,int.class);
        Constructor<?> content = Arrays.stream(cl.loadClass(P+"DuoContent").getConstructors())
            .filter(c -> c.getParameterCount()==10).findFirst().get();
        return content.newInstance(battery,wifi?Integer.valueOf(4):null,label,4,false,false,false,
            Arrays.asList(4,2),Arrays.asList(row.newInstance(1,0,4),row.newInstance(2,1,2)),Integer.valueOf(1));
    }
    public static void run(Context context, ClassLoader cl, String output) throws Exception {
        Class<?> contentType=cl.loadClass(P+"DuoContent"), sizesType=cl.loadClass(P+"DuoSizes");
        Object sizes=sizesType.getConstructor().newInstance();
        Class<?> motionType=cl.loadClass(H+"DuoPanelMotion"), wifiType=cl.loadClass(P+"NativeWifiDrawable");
        Method update=motionType.getMethod("update",ViewGroup.class,View.class,View.class,contentType,
            int.class,float.class,boolean.class,sizesType,wifiType);
        Context systemUi=context.createPackageContext("com.android.systemui",Context.CONTEXT_IGNORE_SECURITY);
        int wifiId=systemUi.getResources().getIdentifier("stat_sys_wifi_signal_3_tint","drawable","com.android.systemui");
        if(wifiId==0) throw new AssertionError("Verified SystemUI Wi-Fi artwork missing");
        AImage wifiSource=new AImage(systemUi); wifiSource.setTag(wifiId); wifiSource.setImageDrawable(systemUi.getDrawable(wifiId));
        Class<?> function=cl.loadClass("kotlin.jvm.functions.Function1");
        Object identity=Proxy.newProxyInstance(cl,new Class[]{function},(proxy,m,args)->args[0]);
        Object nativeWifi=wifiType.getConstructor(ImageView.class,function).newInstance(wifiSource,identity);
        Bitmap sheet=Bitmap.createBitmap(160*5,150*4,Bitmap.Config.ARGB_8888);
        Canvas sheetCanvas=new Canvas(sheet);sheetCanvas.drawColor(Color.rgb(18,18,20));
        Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.WHITE);text.setTextSize(12);
        int cases=0;
        String[] labels={"Wi-Fi","LTE","5G","5GA"};
        for(int item=0;item<labels.length;item++) {
            boolean wifi=item==0;
            ARoot root=new ARoot(context); AView source=new AView(context); View target;
            if(wifi){AImage image=new AImage(systemUi);image.setImageDrawable(systemUi.getDrawable(wifiId).mutate());
                image.setImageTintList(ColorStateList.valueOf(Color.WHITE));target=image;}
            else {AText label=new AText(context);label.setText(labels[item]);label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,18);
                label.setTextColor(Color.WHITE);label.setGravity(Gravity.CENTER);label.setIncludeFontPadding(false);target=label;}
            root.addView(source);root.addView(target);root.layout(0,0,160,140);source.layout(0,0,128,64);
            target.measure(View.MeasureSpec.makeMeasureSpec(60,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(32,View.MeasureSpec.EXACTLY));
            target.layout(40,90,100,122);
            Object motion=motionType.getConstructor(Context.class,boolean.class).newInstance(context,false);
            Object content=state(cl,wifi,wifi?null:labels[item]);
            int[][] forward=new int[5][];
            for(int index:new int[]{0,1,2,3,4,3,2,1,0}) {
                float fraction=index/4f;
                update.invoke(motion,root,source,target,content,Color.WHITE,fraction,item==3,sizes,wifi?nativeWifi:null);
                root.draw(new Canvas(Bitmap.createBitmap(160,140,Bitmap.Config.ARGB_8888)));
                boolean ready=(Boolean)update.invoke(motion,root,source,target,content,Color.WHITE,fraction,item==3,sizes,wifi?nativeWifi:null);
                if(!ready) throw new AssertionError("Network overlay not ready: "+labels[item]);
                if(target.getAlpha()!=(fraction==1?1f:0f)) throw new AssertionError("Early network endpoint duplication");
                Bitmap frame=Bitmap.createBitmap(160,140,Bitmap.Config.ARGB_8888);root.draw(new Canvas(frame));
                int[] pixels=new int[160*140];frame.getPixels(pixels,0,160,0,0,160,140);
                if(forward[index]!=null&&!Arrays.equals(forward[index],pixels)) throw new AssertionError("Network reverse differs: "+labels[item]);
                if(forward[index]==null)sheetCanvas.drawBitmap(frame,index*160,item*150,null);
                forward[index]=pixels;cases++;
            }
            motionType.getMethod("clear").invoke(motion);
            if(target.getAlpha()!=1f)throw new AssertionError("Network alpha leaked on clear");
            sheetCanvas.drawText(labels[item],4,item*150+145,text);
        }
        checkCenterDots(context,cl);
        checkTintRefresh(context,cl);
        int bars=checkBars(context,cl);
        DuoTransportProbe.run(context,cl,output+".transport.png");
        try(FileOutputStream out=new FileOutputStream(output)){sheet.compress(Bitmap.CompressFormat.PNG,100,out);}
        System.out.println("PASS network_travel_cases="+cases+" normal_stacked_morph_cases="+bars+" center_style_cases=3 component_tint_refresh_cases=1");
    }
    static void checkTintRefresh(Context context,ClassLoader cl)throws Exception{
        Class<?> frameType=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame");
        Object frame=frameType.getConstructor().newInstance();
        AImage image=new AImage(context);ColorDrawable drawable=new ColorDrawable(Color.WHITE);
        image.setImageDrawable(drawable);image.measure(View.MeasureSpec.makeMeasureSpec(32,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(32,View.MeasureSpec.EXACTLY));image.layout(0,0,32,32);
        Method capture=frameType.getMethod("captureView",View.class,RectF.class),color=frameType.getMethod("colorAt",float.class,float.class);
        drawable.setTint(Color.RED);capture.invoke(frame,image,null);
        if(Color.red((Integer)color.invoke(frame,16f,16f))<240)throw new AssertionError("Initial component tint missing");
        // Same Drawable identity, same ImageView tint-list property, changed native tint state.
        drawable.setTint(Color.BLUE);capture.invoke(frame,image,null);
        int blue=(Integer)color.invoke(frame,16f,16f);
        if(Color.blue(blue)<240||Color.red(blue)>20)throw new AssertionError("Component tint cache stayed stale");
        drawable.setAlpha(128);capture.invoke(frame,image,null);
        if(Math.abs(Color.alpha((Integer)color.invoke(frame,16f,16f))-128)>2)throw new AssertionError("Drawable alpha cache stayed stale");
    }
    static void checkCenterDots(Context context,ClassLoader cl)throws Exception{
        Class<?> drawType=cl.loadClass(P+"DuoDrawable"),layerType=cl.loadClass(P+"DuoRenderLayer");
        Object layer=Enum.valueOf((Class)layerType,"COMPOSITE");
        Drawable icon=(Drawable)drawType.getConstructor(Context.class,layerType).newInstance(context,layer);
        drawType.getMethod("setContent",cl.loadClass(P+"DuoContent")).invoke(icon,state(cl,false,"5G"));
        icon.setBounds(0,0,128,128);
        int[] original=null;
        for(boolean dots:new boolean[]{false,true,false}){
            drawType.getMethod("setCellularDots",boolean.class).invoke(icon,dots);
            Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);icon.draw(new Canvas(bitmap));
            if(!dots){
                int[] pixels=new int[128*128];bitmap.getPixels(pixels,0,128,0,0,128,128);
                if(original==null)original=pixels;else if(!Arrays.equals(original,pixels))throw new AssertionError("Center style reset changed original rendering");
            }
            if(dots){
                java.util.List<?> rows=(java.util.List<?>)drawType.getMethod("signalDotRows").invoke(icon);
                if(rows.size()!=2)throw new AssertionError("Center dots lost second SIM");
                Picture picture=(Picture)drawType.getMethod("signalDotsPicture").invoke(icon);
                Bitmap expected=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(expected);c.scale(4,4);c.drawPicture(picture);
                for(int row=0;row<2;row++){
                    RectF crop=new RectF(),bounds=new RectF();
                    drawType.getMethod("signalDotsBounds",RectF.class,RectF.class,int.class).invoke(icon,crop,bounds,row);
                    if(bounds.centerY()>80)throw new AssertionError("Cellular dots stayed in bottom notch");
                    for(int y=(int)bounds.top;y<Math.ceil(bounds.bottom);y++)for(int x=(int)bounds.left;x<Math.ceil(bounds.right);x++)
                        if(bitmap.getPixel(x,y)!=expected.getPixel(x,y))throw new AssertionError("Center dot picture differs from live geometry");
                }
            }
        }
    }
    static int checkBars(Context context,ClassLoader cl)throws Exception{
        Class<?> repoType=cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRepository");
        Object repo=repoType.getConstructor(Context.class).newInstance(context);
        Class<?> styleType=cl.loadClass("com.takekazex.hypertweak.settings.contract.SignalSvgStyle");
        // Resolve repository Result through its already successful Duo artwork holder for the target;
        // source documents are parsed directly from the same bundled SVGs via IconSvgRenderer.
        Class<?> rendererType=cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRenderer");
        Object renderer=rendererType.getField("INSTANCE").get(null);
        Method parse=Arrays.stream(rendererType.getMethods()).filter(m->m.getName().startsWith("parse-")&&m.getParameterCount()==1).findFirst().get();
        Class<?> document=cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRenderer$Document");
        Class<?> morphType=cl.loadClass(P+"SignalBarMorph");Object companion=morphType.getField("Companion").get(null);
        Method create=companion.getClass().getMethod("create",document,document,int.class,int.class,float.class,float.class);
        String targetXml=new String(context.getAssets().open("svg/Signal-HyperOS3-Single.svg").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        Object target=parse.invoke(renderer,targetXml);
        Method signalPicture=Arrays.stream(rendererType.getMethods()).filter(m->m.getName().equals("signalPicture")&&m.getParameterCount()==5).findFirst().get();
        Class<?> motionType=cl.loadClass(H+"SignalRowMotion"), dotsType=cl.loadClass(P+"DuoDotMorph");
        Method update=motionType.getMethod("update",ViewGroup.class,View.class,RectF.class,View.class,Picture.class,
            RectF.class,int.class,float.class,Picture.class,ImageView.class,Float.class,boolean.class,dotsType,morphType);
        int count=0;
        for(String family:new String[]{"HyperOS3","iOS27"})for(float weight:new float[]{0f,1f})for(int row=0;row<2;row++){
            String xml=new String(context.getAssets().open("svg/Signal-"+family+"-Stacked.svg").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            Object source=parse.invoke(renderer,xml);
            Object morph=create.invoke(companion,source,target,row,2,family.equals("HyperOS3")?.632f:.610455f,weight);
            java.util.List<?> from=(java.util.List<?>)morphType.getMethod("getSource").invoke(morph);
            java.util.List<?> to=(java.util.List<?>)morphType.getMethod("getTarget").invoke(morph);
            if(from.size()!=4||to.size()!=4)throw new AssertionError("Stacked bar topology missing");
            for(Object contour:from){
                RectF box=(RectF)contour.getClass().getMethod("getBounds").invoke(contour);
                float[] points=(float[])contour.getClass().getMethod("getPoints").invoke(contour);
                if(box.isEmpty()||points.length!=256)throw new AssertionError("Invalid bar contour");
                for(float value:points)if(!Float.isFinite(value))throw new AssertionError("Non-finite bar interpolation");
            }
            Picture sourcePicture=(Picture)signalPicture.invoke(renderer,source,Arrays.asList(4,2),.4f,.2f,weight);
            Picture targetPicture=(Picture)signalPicture.invoke(renderer,target,Collections.singletonList(row==0?4:2),.28f,.28f,0f);
            Bitmap sourceBitmap=Bitmap.createBitmap(sourcePicture.getWidth(),sourcePicture.getHeight(),Bitmap.Config.ARGB_8888);
            new Canvas(sourceBitmap).drawPicture(sourcePicture);
            ARoot root=new ARoot(context);AImage sourceView=new AImage(context),targetView=new AImage(context);
            sourceView.setImageDrawable(new BitmapDrawable(context.getResources(),sourceBitmap));sourceView.setImageTintList(ColorStateList.valueOf(Color.GREEN));
            targetView.setImageDrawable(new PictureDrawable(targetPicture));
            root.addView(targetView);root.layout(0,0,340,220);
            sourceView.measure(View.MeasureSpec.makeMeasureSpec(sourcePicture.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
            sourceView.layout(0,0,sourcePicture.getWidth(),100);
            targetView.measure(View.MeasureSpec.makeMeasureSpec(64,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(64,View.MeasureSpec.EXACTLY));targetView.layout(200,120,264,184);
            RectF sourceBox=new RectF(sourceView.getDrawable().getBounds());sourceView.getImageMatrix().mapRect(sourceBox);
            float split=family.equals("HyperOS3")?.632f:.610455f;
            float top=row==0?0:split,bottom=row==0?split:1;
            RectF local=new RectF(sourceBox.left,sourceBox.top+sourceBox.height()*top,sourceBox.right,sourceBox.top+sourceBox.height()*bottom);
            RectF crop=new RectF(0,sourcePicture.getHeight()*top,sourcePicture.getWidth(),sourcePicture.getHeight()*bottom);
            Object motion=motionType.getConstructor().newInstance();int[][] frames=new int[5][];
            for(int index:new int[]{0,1,2,3,4,3,2,1,0}){
                float fraction=index/4f;
                update.invoke(motion,root,sourceView,local,targetView,sourcePicture,crop,0,fraction,null,sourceView,null,false,null,morph);
                root.draw(new Canvas(Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888)));
                boolean ready=(Boolean)update.invoke(motion,root,sourceView,local,targetView,sourcePicture,crop,0,fraction,null,sourceView,null,false,null,morph);
                if(!ready||targetView.getAlpha()!=(index==4?1f:0f))throw new AssertionError("Stacked ownership failed");
                Bitmap frame=Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888);root.draw(new Canvas(frame));
                DuoRenderProbe.components(frame);
                int[] pixels=new int[340*220];frame.getPixels(pixels,0,340,0,0,340,220);
                if(frames[index]!=null&&!Arrays.equals(frames[index],pixels))throw new AssertionError("Stacked reverse differs "+family+" weight="+weight);
                frames[index]=pixels;
                if(index==0){int green=0;for(int pixel:pixels)if(Color.alpha(pixel)>100&&Color.green(pixel)>200&&Color.red(pixel)<20)green++;
                    if(green<4)throw new AssertionError("Native source tint was lost");}
                count++;
            }
            motionType.getMethod("clear").invoke(motion);
            if(targetView.getAlpha()!=1f)throw new AssertionError("Stacked cancellation leaked alpha");
        }
        return count;
    }
    static class ARoot extends FrameLayout{ARoot(Context c){super(c);}@Override public boolean isAttachedToWindow(){return true;}}
    static class AView extends View{AView(Context c){super(c);}@Override public boolean isAttachedToWindow(){return true;}}
    static class AImage extends ImageView{AImage(Context c){super(c);}@Override public boolean isAttachedToWindow(){return true;}}
    static class AText extends TextView{AText(Context c){super(c);}@Override public boolean isAttachedToWindow(){return true;}}
}
