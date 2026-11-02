import android.content.Context;
import android.graphics.*;
import java.lang.reflect.*;

/** Real variable-font rasterization; no windows or device preference changes. */
public final class MobileTypeTypographyProbe {
    static long ink(Paint paint) {
        Bitmap bitmap=Bitmap.createBitmap(160,80,Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawText("5GA",4,60,paint);
        long alpha=0;for(int y=0;y<80;y++)for(int x=0;x<160;x++)alpha+=Color.alpha(bitmap.getPixel(x,y));
        bitmap.recycle();return alpha;
    }
    static void run(Context context,ClassLoader cl)throws Exception {
        Class<?> config=cl.loadClass("com.takekazex.hypertweak.settings.contract.MobileTypeTypography");
        Constructor<?> create=config.getConstructor(boolean.class,float.class,int.class);
        Method apply=cl.loadClass("com.takekazex.hypertweak.presentation.icon.MobileTypePaintKt").getMethod("applyTo",config,Paint.class);
        Paint original=new Paint(Paint.ANTI_ALIAS_FLAG);original.setColor(Color.WHITE);original.setTextSize(32);
        original.setTypeface(new Typeface.Builder("/system/fonts/MiSansVF.ttf").setFontVariationSettings("'wght' 660").build());
        Paint unchanged=new Paint(original);apply.invoke(null,create.newInstance(false,50f,100),unchanged);
        if(ink(original)!=ink(unchanged)||original.getTextSize()!=unchanged.getTextSize()||original.getTypeface()!=unchanged.getTypeface())throw new AssertionError("Disabled typography changed host metrics");
        Paint thin=new Paint(original),heavy=new Paint(original),small=new Paint(original),large=new Paint(original);
        apply.invoke(null,create.newInstance(true,100f,100),thin);apply.invoke(null,create.newInstance(true,100f,900),heavy);
        long low=ink(thin),high=ink(heavy);
        if(low<=0||high<low*1.3)throw new AssertionError("Variable wght axis defeated weight override: "+low+","+high);
        apply.invoke(null,create.newInstance(true,50f,400),small);apply.invoke(null,create.newInstance(true,150f,400),large);
        if(small.getTextSize()!=16||large.getTextSize()!=48||large.measureText("5GA")<small.measureText("5GA")*2.8)throw new AssertionError("Independent size did not scale");
        System.out.println("PASS mobile_type_typography variable_font_ink="+low+"/"+high+" disabled_baseline=true scale=50..150%");
    }
}
