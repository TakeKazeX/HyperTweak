import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;
import android.widget.ImageView;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;

/** Detached ART regression: no windows, UI interaction or screenshots. Pass a debug APK. */
public final class PanelGlyphProbe {
    public static final class AttachedRoot extends android.widget.FrameLayout {
        public AttachedRoot(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    public static final class AttachedImage extends ImageView {
        public AttachedImage(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    public static final class InvalidationCounter implements android.graphics.drawable.Drawable.Callback {
        public int frames;
        public void invalidateDrawable(android.graphics.drawable.Drawable drawable) { frames++; }
        public void scheduleDrawable(android.graphics.drawable.Drawable drawable, Runnable task, long when) { }
        public void unscheduleDrawable(android.graphics.drawable.Drawable drawable, Runnable task) { }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        Class<?> type = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame");
        Object frame = type.getConstructor().newInstance();
        Method capture = type.getMethod("captureView", android.view.View.class, android.graphics.RectF.class);
        Method revision = type.getMethod("getRevision");
        Method colorAt = type.getMethod("colorAt", float.class, float.class);
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        ImageView image = new ImageView(context);
        image.setImageBitmap(bitmap);
        image.layout(0, 0, 16, 16);
        image.setAlpha(0f);
        check((Boolean) capture.invoke(frame, image, null), "initial masked capture");
        int first = (Integer) revision.invoke(frame);
        for (int i = 0; i < 200; i++) {
            image.invalidate();
            check((Boolean) capture.invoke(frame, image, null), "dirty masked capture");
        }
        check((Integer) revision.invoke(frame) == first, "idle capture must not advance revision");
        image.setImageTintList(ColorStateList.valueOf(Color.BLACK));
        capture.invoke(frame, image, null);
        check((Integer) revision.invoke(frame) > first, "tint must advance revision");
        check((Integer) colorAt.invoke(frame, 8f, 8f) == Color.BLACK, "tint must reach sampled ink immediately");
        image.setImageTintList(null);
        bitmap.eraseColor(Color.RED);
        capture.invoke(frame, image, null);
        check((Integer) colorAt.invoke(frame, 8f, 8f) == Color.RED, "in-place bitmap mutation");
        capture.invoke(frame, image, new android.graphics.RectF(0, 0, 16, 8));
        Method ink = type.getMethod("getInk");
        check(((android.graphics.RectF) ink.invoke(frame)).height() == 8f, "cropped recording");
        capture.invoke(frame, image, null);
        check(((android.graphics.RectF) ink.invoke(frame)).height() == 16f,
            "removing the crop must not reuse a partial recording");
        bitmap.eraseColor(Color.BLUE);
        type.getMethod("invalidateView").invoke(frame);
        capture.invoke(frame, image, null);
        check((Integer) colorAt.invoke(frame, 8f, 8f) == Color.BLUE,
            "explicit invalidation must bypass the clean bitmap shortcut");
        // Arbitrary draw callbacks can be dirty even when their output is unchanged.
        Object recorded = type.getConstructor().newInstance();
        Class<?> function = loader.loadClass("kotlin.jvm.functions.Function1");
        Method record = type.getMethod("capture", int.class, int.class, android.graphics.RectF.class, function);
        Paint paint = new Paint(); paint.setColor(Color.WHITE);
        Object draw = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, values) -> {
            if (method.getName().equals("invoke")) {
                ((Canvas) values[0]).drawRect(0, 0, 16, 16, paint);
                return loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
            }
            return null;
        });
        record.invoke(recorded, 16, 16, null, draw);
        int stable = (Integer) revision.invoke(recorded);
        for (int i = 0; i < 200; i++) record.invoke(recorded, 16, 16, null, draw);
        check((Integer) revision.invoke(recorded) == stable, "identical recordings must not feed another frame");
        paint.setColor(Color.BLACK);
        record.invoke(recorded, 16, 16, null, draw);
        check((Integer) revision.invoke(recorded) > stable, "changed recording must advance revision");
        Class<?> partsType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierRowParts");
        android.widget.TextView label = new android.widget.TextView(context);
        ImageView signal = new ImageView(context);
        ImageView wifi = new ImageView(context);
        ImageView mobileType = new ImageView(context);
        signal.setImageBitmap(bitmap);
        Object original = signal.getDrawable();
        Object parts = partsType.getConstructors()[0].newInstance(0, new android.widget.LinearLayout(context),
            label, signal, new android.widget.TextView(context), new android.widget.FrameLayout(context), wifi, mobileType);
        Class<?> rendererType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierRowRenderer");
        Object renderer = rendererType.getConstructor().newInstance();
        Method sync = rendererType.getMethod("syncTint", partsType);
        for (int color : new int[]{Color.WHITE, Color.BLACK, Color.GRAY, Color.WHITE}) {
            label.setTextColor(color);
            sync.invoke(renderer, parts);
            for (ImageView glyph : new ImageView[]{signal, wifi, mobileType})
                check(glyph.getImageTintList().getDefaultColor() == color, "carrier tint follows each host color");
            check(signal.getDrawable() == original, "palette updates must retain artwork");
        }
        // Exercise the actual round-dot/fallback outer filter, including interrupted reversals.
        AttachedRoot root = new AttachedRoot(context); root.layout(0, 0, 64, 32);
        AttachedImage source = new AttachedImage(context), target = new AttachedImage(context);
        root.addView(source); root.addView(target);
        source.layout(0, 0, 16, 16); target.layout(32, 0, 48, 16);
        Bitmap white = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); white.eraseColor(Color.WHITE);
        target.setImageBitmap(white); target.setAlpha(.6f);
        android.graphics.Picture picture = new android.graphics.Picture();
        Canvas recording = picture.beginRecording(16, 16); recording.drawColor(Color.WHITE); picture.endRecording();
        Class<?> motionType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.duo.SignalRowMotion");
        Object motion = motionType.getConstructor().newInstance();
        Method update = java.util.Arrays.stream(motionType.getMethods()).filter(m -> m.getName().equals("update")).findFirst().get();
        java.lang.reflect.Field layerField = motionType.getDeclaredField("layer"); layerField.setAccessible(true);
        android.graphics.drawable.Drawable layer = (android.graphics.drawable.Drawable) layerField.get(motion);
        android.graphics.RectF box = new android.graphics.RectF(0, 0, 16, 16);
        Bitmap rendered = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rendered);
        for (float progress : new float[]{.25f, .5f, .75f, .5f, .25f}) {
            for (int warm = 0; warm < 2; warm++) {
                update.invoke(motion, root, source, box, target, picture, box, Color.BLACK, progress,
                    null, null, null, true, null, null);
                rendered.eraseColor(Color.TRANSPARENT); layer.draw(canvas);
            }
            int pixel = rendered.getPixel(Math.round(8 + 32 * progress), 8);
            check(Color.alpha(pixel) > 0, "moving glyph must be visible");
            check(Math.abs(Color.red(pixel) - Math.round(255 * progress)) <= 2,
                "moving dots must blend to white before the endpoint: p=" + progress + " rgba=" + Integer.toHexString(pixel));
        }
        InvalidationCounter counter = new InvalidationCounter(); layer.setCallback(counter);
        for (int i = 0; i < 200; i++)
            update.invoke(motion, root, source, box, target, picture, box, Color.BLACK, .25f,
                null, null, null, true, null, null);
        check(counter.frames == 0, "held gesture must not keep invalidating an unchanged overlay");
        motionType.getMethod("clear").invoke(motion);
        check(Math.abs(target.getAlpha() - .6f) < .001f, "clear restores native endpoint alpha");
        System.out.println("PASS: idle recaptures, live tint, bitmap mutation, carrier palette reversals, moving-dot black-to-white interpolation at .25/.5/.75 and reverse, 200 held updates without extra invalidation, alpha restoration");
    }
}
