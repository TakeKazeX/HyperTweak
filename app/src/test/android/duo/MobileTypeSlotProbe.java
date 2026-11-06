import android.content.Context;
import android.graphics.*;
import android.widget.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual host resources, standalone type rasterization and Duo endpoints; no Window. */
public final class MobileTypeSlotProbe {
    static Rect ink(Bitmap bitmap) {
        Rect box = new Rect(bitmap.getWidth(), bitmap.getHeight(), -1, -1);
        for (int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++) {
            if(Color.alpha(bitmap.getPixel(x,y))<=24) continue;
            box.left=Math.min(box.left,x);box.top=Math.min(box.top,y);
            box.right=Math.max(box.right,x+1);box.bottom=Math.max(box.bottom,y+1);
        }
        if(box.right<0)box.setEmpty();
        return box;
    }
    static Bitmap draw(View view) {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));return bitmap;
    }
    static Bitmap render(ClassLoader cl, Context host, String label, boolean enabled, float size, Float baseline) throws Exception {
        String p="com.takekazex.hypertweak.hook.rules.systemui.icon.";
        Class<?> config=cl.loadClass(p+"MobileTypeConfig"),output=cl.loadClass(p+"MobileTypeOutput");
        Class<?> typography=cl.loadClass("com.takekazex.hypertweak.settings.contract.MobileTypeTypography");
        Object font=typography.getConstructor(boolean.class,float.class,int.class).newInstance(enabled,size,400);
        Object options=Arrays.stream(config.getConstructors()).filter(c->c.getParameterCount()==13).findFirst().get()
            .newInstance(false,false,false,14f,630,400,80,.5f,.5f,0f,0,font,true);
        Object text=output.getConstructor(String.class,boolean.class,boolean.class,boolean.class)
            .newInstance(label,false,false,false);
        Class<?> renderer=cl.loadClass(p+"MobileTypeRenderer");
        Class<?> metrics=cl.loadClass(p+"MobileTypeHostMetrics");
        Object nativeFont=metrics.getMethod("font",Context.class).invoke(metrics.getField("INSTANCE").get(null),host);
        Typeface face=(Typeface)nativeFont.getClass().getMethod("getTypeface").invoke(nativeFont);
        String axes=(String)nativeFont.getClass().getMethod("getVariationSettings").invoke(nativeFont);
        int height=host.getResources().getDimensionPixelSize(host.getResources().getIdentifier("status_bar_icon_height","dimen","com.android.systemui"));
        return (Bitmap)renderer.getMethod("render",output,config,int.class,int.class,float.class,boolean.class,Typeface.class,Float.class,String.class)
            .invoke(renderer.getField("INSTANCE").get(null),text,options,height,host.getResources().getDisplayMetrics().densityDpi,
                host.getResources().getConfiguration().fontScale,false,face,baseline,axes);
    }
    static void run(Context context,ClassLoader cl)throws Exception {
        Context host=context.createPackageContext("com.android.systemui",Context.CONTEXT_IGNORE_SECURITY);
        Class<?> metrics=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeHostMetrics");
        Float size=(Float)metrics.getMethod("textSizePx",Context.class).invoke(metrics.getField("INSTANCE").get(null),host);
        if(size==null)throw new AssertionError("Full-height host type appearance missing");
        TextView nativeText=new TextView(host);
        nativeText.setTextAppearance(host.getResources().getIdentifier("TextAppearance.StatusBar.Clock","style","com.android.systemui"));
        if(Math.abs(size-nativeText.getTextSize())>.01f)throw new AssertionError("Standalone slot lost native full-height typography");
        Object nativeFont=metrics.getMethod("font",Context.class).invoke(metrics.getField("INSTANCE").get(null),host);
        Typeface face=(Typeface)nativeFont.getClass().getMethod("getTypeface").invoke(nativeFont);
        String axes=(String)nativeFont.getClass().getMethod("getVariationSettings").invoke(nativeFont);
        if(!Objects.equals(face,nativeText.getPaint().getTypeface())||!Objects.equals(axes,nativeText.getPaint().getFontVariationSettings()))
            throw new AssertionError("Default font/variation axes do not match native full-height label");
        float small=host.getResources().getDimensionPixelSize(host.getResources().getIdentifier("statusbar_signal_size","dimen","com.android.systemui"));
        Bitmap old=render(cl,host,"5G",false,100f,small),normal=render(cl,host,"5G",false,100f,size);
        if(ink(normal).height()<ink(old).height()*1.7f)throw new AssertionError("Standalone type still uses the half-height SIM label");
        Bitmap disabled=render(cl,host,"5G",false,150f,size);
        if(!normal.sameAs(disabled))throw new AssertionError("Disabled typography retained custom size");
        Bitmap nativeRaster=Bitmap.createBitmap(normal.getWidth(),normal.getHeight(),Bitmap.Config.ARGB_8888);
        Paint nativePaint=new Paint(nativeText.getPaint());nativePaint.setColor(Color.WHITE);nativePaint.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm=nativePaint.getFontMetrics();
        new Canvas(nativeRaster).drawText("5G",.5f*host.getResources().getDisplayMetrics().density,normal.getHeight()/2f-(fm.ascent+fm.descent)/2f,nativePaint);
        double ratio=mass(normal)/(double)mass(nativeRaster);
        if(ratio<.95||ratio>1.05)throw new AssertionError("Default raster weight differs from native paint: "+ratio);
        checkDuoFont(context,cl,face,axes);
        checkConfigurationFont(host,metrics);
        int frames=0;
        for(float percent:new float[]{50f,100f,150f}) {
            Bitmap bitmap=render(cl,host,"5G",true,percent,size);
            frames+=handover(context,cl,bitmap);
        }
        Bitmap suffixSmall=render(cl,host,"5GA",true,50f,size),suffixLarge=render(cl,host,"5GA",true,150f,size);
        int low=suffixHeight(cl,size,50f,suffixSmall),high=suffixHeight(cl,size,150f,suffixLarge);
        if(low<=0||high<low*2.4f)throw new AssertionError("5GA suffix ignored typography scale: "+low+".."+high);
        System.out.println("PASS mobile_type_slot host_px="+size+" native_weight="+weight(face)+" native_raster_mass_ratio="+ratio+" per_sim_px="+small+" ink_height="+ink(old).height()+"->"+ink(normal).height()+" suffix_height="+low+".."+high+" endpoint_frames="+frames);
    }
    static long mass(Bitmap bitmap) {
        long total=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)total+=Color.alpha(bitmap.getPixel(x,y));return total;
    }
    static int weight(Typeface face) { return (face==null?Typeface.DEFAULT:face).getWeight(); }
    static void checkDuoFont(Context context,ClassLoader cl,Typeface face,String axes)throws Exception {
        Class<?> type=cl.loadClass(DuoNetworkProbe.P+"DuoDrawable"),layer=cl.loadClass(DuoNetworkProbe.P+"DuoRenderLayer");
        DrawableAccess icon=new DrawableAccess(type,layer,context);
        type.getMethod("setNetworkOnly",boolean.class).invoke(icon.drawable,true);
        Class<?> contentType=cl.loadClass(DuoNetworkProbe.P+"DuoContent");
        Object state=DuoNetworkProbe.state(cl,false,"5G");
        Method setContent=type.getMethod("setContent",contentType);
        setContent.invoke(icon.drawable,state);
        type.getMethod("setNetworkTypeface",Typeface.class).invoke(icon.drawable,face);
        type.getMethod("setNetworkFontVariationSettings",String.class).invoke(icon.drawable,axes);
        Bitmap regular=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        icon.drawable.setBounds(0,0,128,128);icon.drawable.draw(new Canvas(regular));
        Field label=type.getDeclaredField("networkLabelPaint");label.setAccessible(true);Paint paint=(Paint)label.get(icon.drawable);
        if(weight(paint.getTypeface())!=weight(face))throw new AssertionError("Duo inherited header/stroke weight for network label");
        Class<?> typography=cl.loadClass("com.takekazex.hypertweak.settings.contract.MobileTypeTypography");
        Object heavy=typography.getConstructor(boolean.class,float.class,int.class).newInstance(true,100f,900);
        Object[] values={contentType.getMethod("getBattery").invoke(state),null,"5G",4,false,false,false,
            contentType.getMethod("getCellularSignalLevels").invoke(state),contentType.getMethod("getCellularSignalRows").invoke(state),1,heavy};
        Object custom=Arrays.stream(contentType.getConstructors()).filter(c->c.getParameterCount()==11).findFirst().get().newInstance(values);
        Bitmap bold=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        setContent.invoke(icon.drawable,custom);icon.drawable.draw(new Canvas(bold));
        if(paint.getFontVariationSettings()==null||!paint.getFontVariationSettings().contains("900")||mass(bold)<mass(regular)*1.15)
            throw new AssertionError("Duo ignored explicit custom weight: "+mass(regular)+".."+mass(bold)+" axes="+paint.getFontVariationSettings());
        Bitmap restored=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        setContent.invoke(icon.drawable,state);icon.drawable.draw(new Canvas(restored));
        if(!restored.sameAs(regular))throw new AssertionError("Disabling Duo typography retained custom font/axes");
    }
    static class DrawableAccess {
        final android.graphics.drawable.Drawable drawable;
        DrawableAccess(Class<?> type,Class<?> layer,Context context)throws Exception {
            drawable=(android.graphics.drawable.Drawable)type.getConstructor(Context.class,layer).newInstance(context,Enum.valueOf((Class)layer,"COMPOSITE"));
        }
    }
    static void checkConfigurationFont(Context host,Class<?> metrics)throws Exception {
        android.content.res.Configuration changed=new android.content.res.Configuration(host.getResources().getConfiguration());
        changed.fontWeightAdjustment=200;
        Context scoped=host.createConfigurationContext(changed);
        Object first=metrics.getMethod("font",Context.class).invoke(metrics.getField("INSTANCE").get(null),scoped);
        changed.fontWeightAdjustment=0;
        scoped.getResources().updateConfiguration(changed,scoped.getResources().getDisplayMetrics());
        Object second=metrics.getMethod("font",Context.class).invoke(metrics.getField("INSTANCE").get(null),scoped);
        if(first==second)throw new AssertionError("System font configuration change reused stale font snapshot");
        TextView expected=new TextView(scoped);expected.setTextAppearance(scoped.getResources().getIdentifier("TextAppearance.StatusBar.Clock","style","com.android.systemui"));
        if(!Objects.equals(second.getClass().getMethod("getTypeface").invoke(second),expected.getPaint().getTypeface()))throw new AssertionError("System font change did not recover native weight");
    }
    static int suffixHeight(ClassLoader cl,float size,float percent,Bitmap bitmap)throws Exception {
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(size);
        Class<?> typography=cl.loadClass("com.takekazex.hypertweak.settings.contract.MobileTypeTypography");
        Object font=typography.getConstructor(boolean.class,float.class,int.class).newInstance(true,percent,400);
        cl.loadClass("com.takekazex.hypertweak.presentation.icon.MobileTypePaintKt").getMethod("applyTo",typography,Paint.class).invoke(null,font,paint);
        int x=(int)Math.ceil(paint.measureText("5G"))+3;
        if(x>=bitmap.getWidth())return 0;
        return ink(Bitmap.createBitmap(bitmap,x,0,bitmap.getWidth()-x,bitmap.getHeight())).height();
    }
    static int handover(Context context,ClassLoader cl,Bitmap bitmap)throws Exception {
        DuoNetworkProbe.ARoot root=new DuoNetworkProbe.ARoot(context);
        DuoNetworkProbe.AView source=new DuoNetworkProbe.AView(context);
        DuoNetworkProbe.AImage target=new DuoNetworkProbe.AImage(context);
        target.setImageBitmap(bitmap);target.setScaleType(ImageView.ScaleType.CENTER);
        root.addView(source);root.addView(target);root.layout(0,0,360,200);
        source.layout(8,8,136,72);target.layout(224,104,224+bitmap.getWidth(),104+bitmap.getHeight());
        Bitmap endpoint=draw(root);
        Class<?> motionType=cl.loadClass(DuoNetworkProbe.H+"DuoPanelMotion"),content=cl.loadClass(DuoNetworkProbe.P+"DuoContent"),sizes=cl.loadClass(DuoNetworkProbe.P+"DuoSizes"),wifi=cl.loadClass(DuoNetworkProbe.P+"NativeWifiDrawable");
        Object motion=motionType.getConstructor(Context.class,boolean.class).newInstance(context,false);
        Method update=motionType.getMethod("update",ViewGroup.class,View.class,View.class,content,int.class,float.class,boolean.class,sizes,wifi);
        Object state=DuoNetworkProbe.state(cl,false,"5G"),scale=sizes.getConstructor().newInstance();
        Map<Float,Bitmap> forward=new HashMap<>();int frames=0;
        for(float p:new float[]{0f,.5f,.99f,1f,.99f,.5f,0f}) {
            update.invoke(motion,root,source,target,state,Color.WHITE,p,false,scale,null);draw(root);
            update.invoke(motion,root,source,target,state,Color.WHITE,p,false,scale,null);
            Bitmap actual=draw(root);Bitmap previous=forward.putIfAbsent(p,actual);
            if(previous!=null&&!previous.sameAs(actual))throw new AssertionError("5G endpoint size changes on reverse");
            if(p==1f&&!endpoint.sameAs(actual))throw new AssertionError("5G jumps to a different size at native arrival");
            if(p==.99f&&Math.abs(ink(actual).height()-ink(endpoint).height())>2)throw new AssertionError("5G shrinks immediately before arrival");
            frames++;
        }
        motionType.getMethod("clear").invoke(motion);
        if(target.getAlpha()!=1f)throw new AssertionError("5G native alpha leaked on retirement");
        return frames;
    }
}
