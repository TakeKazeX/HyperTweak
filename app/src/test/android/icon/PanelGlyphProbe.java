import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
    public static final class MobileHolder extends FrameLayout {
        public final int subId = 12;
        public MobileHolder(Context context) { super(context); }
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
        wifi.setImageBitmap(bitmap);
        signal.layout(0, 0, 16, 16);
        wifi.layout(0, 0, 16, 16);
        Object parts = partsType.getConstructors()[0].newInstance(0, new android.widget.LinearLayout(context),
            label, signal, new android.widget.TextView(context), new android.widget.FrameLayout(context), wifi, mobileType);
        Class<?> rendererType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierRowRenderer");
        Object renderer = rendererType.getConstructor().newInstance();
        Method sync = rendererType.getMethod("syncTint", partsType);
        label.setTextColor(Color.WHITE);
        sync.invoke(renderer, parts);
        check((Boolean) capture.invoke(frame, signal, null), "fallback signal capture");
        check((Integer) colorAt.invoke(frame, 8f, 8f) == Color.WHITE, "fallback signal follows carrier palette");

        FrameLayout mirror = new FrameLayout(context);
        mirror.layout(0, 0, 48, 16);
        MobileHolder holder = new MobileHolder(context);
        mirror.addView(holder);
        AttachedImage bars = new AttachedImage(context);
        bars.setId(1234);
        ImageView inlineType = new AttachedImage(context);
        inlineType.setId(1235);
        inlineType.setImageBitmap(bitmap);
        holder.addView(bars);
        holder.addView(inlineType);
        ImageView otherBadge = new ImageView(context);
        otherBadge.setImageBitmap(bitmap);
        holder.addView(otherBadge);
        Bitmap barsBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Canvas barsCanvas = new Canvas(barsBitmap);
        Paint barsPaint = new Paint();
        barsPaint.setColor(Color.BLACK);
        barsCanvas.drawRect(0, 0, 8, 16, barsPaint);
        barsPaint.setAlpha(96);
        barsCanvas.drawRect(8, 8, 16, 16, barsPaint);
        bars.setImageBitmap(barsBitmap);
        holder.layout(0, 0, 48, 16);
        bars.layout(0, 0, 16, 16);
        inlineType.layout(16, 0, 32, 16);
        otherBadge.layout(32, 0, 48, 16);

        String handoverName = "com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierHandoverController";
        Class<?> handoverType = loader.loadClass(handoverName);
        Class<?> hostType = loader.loadClass(handoverName + "$Host");
        Object carrierHost = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{hostType}, (proxy, method, values) ->
            switch (method.getName()) {
                case "getMobileSignalId" -> bars.getId();
                case "getMobileTypeId" -> inlineType.getId();
                case "findStatusContainer" -> mirror;
                default -> throw new AssertionError(method.getName());
            });
        Object handover = handoverType.getConstructor(hostType).newInstance(carrierHost);
        View selected = (View) handoverType.getMethod("nativeSignal", ViewGroup.class, Integer.class)
            .invoke(handover, mirror, holder.subId);
        check(selected == bars, "carrier signal uses the native bars as the base glyph");
        Method selectBadge = handoverType.getMethod("inlineBadge", View.class);
        check(selectBadge.invoke(handover, bars) == inlineType, "visible native badge is eligible for handover");
        inlineType.setVisibility(View.GONE);
        check(selectBadge.invoke(handover, bars) == null, "hidden native badge is ineligible");
        inlineType.setVisibility(View.VISIBLE);
        Class<?> nativeType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierNativeGlyph");
        var glyphConstructor = nativeType.getConstructor(View.class, java.util.List.class);
        android.graphics.drawable.Drawable nativeBars = (android.graphics.drawable.Drawable)
            glyphConstructor.newInstance(selected, null);
        check((Boolean) nativeType.getMethod("update").invoke(nativeBars), "record native signal");
        signal.setImageDrawable(nativeBars);
        for (int color : new int[]{Color.WHITE, Color.BLACK, Color.GRAY, Color.WHITE}) {
            label.setTextColor(color);
            sync.invoke(renderer, parts);
            check((Boolean) capture.invoke(frame, signal, null), "native signal capture");
            check((Integer) colorAt.invoke(frame, 4f, 8f) == color, "native bars follow the carrier palette");
            int faintBar = (Integer) colorAt.invoke(frame, 12f, 12f);
            check(Color.alpha(faintBar) == 96, "recoloring must preserve bar opacity");
            check((Integer) colorAt.invoke(frame, 12f, 4f) == Color.TRANSPARENT, "recoloring must preserve transparent pixels");
            check(signal.getDrawable() == nativeBars, "palette updates must retain artwork");
            check(mobileType.getImageTintList().getDefaultColor() == color, "trailing type follows the carrier palette");
            check((Boolean) capture.invoke(frame, wifi, null) && (Integer) colorAt.invoke(frame, 8f, 8f) == Color.BLUE,
                "Wi-Fi retains its own palette");
        }
        android.graphics.drawable.Drawable combined = (android.graphics.drawable.Drawable)
            glyphConstructor.newInstance(holder, java.util.List.of(bars, inlineType));
        check((Boolean) nativeType.getMethod("update").invoke(combined), "record native signal and type together");
        signal.setImageDrawable(combined);
        signal.layout(0, 0, 48, 16);
        check((Boolean) capture.invoke(frame, signal, null), "combined carrier glyph capture");
        check((Integer) colorAt.invoke(frame, 4f, 8f) == Color.WHITE &&
            (Integer) colorAt.invoke(frame, 24f, 8f) == Color.WHITE, "both bars and inline type take the carrier color");
        check((Integer) colorAt.invoke(frame, 40f, 8f) == Color.TRANSPARENT, "unrelated native badge stays out of the capture");
        android.graphics.Picture nativePicture = (android.graphics.Picture) nativeType.getMethod("getPicture").invoke(combined);
        Bitmap originalPalette = Bitmap.createBitmap(48, 16, Bitmap.Config.ARGB_8888);
        new Canvas(originalPalette).drawPicture(nativePicture);
        check(originalPalette.getPixel(4, 8) == Color.BLACK && originalPalette.getPixel(24, 8) == Color.BLUE,
            "the moving overlay starts with the source palette");
        signal.setImageDrawable(nativeBars);
        signal.layout(0, 0, 16, 16);
        check((Boolean) capture.invoke(frame, signal, null) &&
            ((android.graphics.RectF) ink.invoke(frame)).right <= 16f, "bars-only replacement sheds the native type badge");

        AttachedRoot carrierRoot = new AttachedRoot(context);
        carrierRoot.layout(0, 0, 120, 16);
        carrierRoot.addView(mirror);
        AttachedImage carrierTarget = new AttachedImage(context);
        carrierRoot.addView(carrierTarget);
        carrierTarget.layout(64, 0, 112, 16);
        carrierTarget.setImageDrawable(combined);
        carrierTarget.setColorFilter(Color.WHITE);
        Class<?> leftMotionType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftPanelMotion");
        Class<?> function0 = loader.loadClass("kotlin.jvm.functions.Function0");
        Object onFrame = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function0},
            (proxy, method, values) -> loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null));
        Object leftMotion = leftMotionType.getConstructor(function0).newInstance(onFrame);
        Method move = leftMotionType.getMethod("update", ViewGroup.class, View.class, View.class,
            float.class, Float.class, boolean.class, android.graphics.Picture.class);
        java.lang.reflect.Field carrierLayerField = leftMotionType.getDeclaredField("layer");
        carrierLayerField.setAccessible(true);
        var carrierLayer = (android.graphics.drawable.Drawable) carrierLayerField.get(leftMotion);
        Bitmap travelBitmap = Bitmap.createBitmap(120, 16, Bitmap.Config.ARGB_8888);
        Canvas travelCanvas = new Canvas(travelBitmap);
        for (float progress : new float[]{.5f, .99f, .5f}) {
            move.invoke(leftMotion, carrierRoot, holder, carrierTarget, progress, null, false, nativePicture);
            carrierLayer.draw(travelCanvas);
            travelBitmap.eraseColor(Color.TRANSPARENT);
            carrierLayer.draw(travelCanvas);
            int offset = Math.round(64 * progress);
            int badgePixel = travelBitmap.getPixel(offset + 24, 8);
            check(progress == .5f ? badgePixel == Color.BLUE : Color.red(badgePixel) > 240,
                "inline type moves to the target and returns with the source color: progress=" + progress
                    + ", pixel=" + Integer.toHexString(badgePixel));
            check(travelBitmap.getPixel(offset + 40, 8) == Color.TRANSPARENT,
                "the native sibling outside the composite does not travel");
        }
        inlineType.setAlpha(0f);
        inlineType.setColorFilter(Color.GREEN);
        check((Boolean) nativeType.getMethod("update").invoke(combined), "record a changed badge while its source is masked");
        var refreshedPicture = (android.graphics.Picture) nativeType.getMethod("getPicture").invoke(combined);
        check(refreshedPicture != nativePicture, "an appearance update publishes a new source snapshot");
        move.invoke(leftMotion, carrierRoot, holder, carrierTarget, .5f, null, false, refreshedPicture);
        travelBitmap.eraseColor(Color.TRANSPARENT);
        carrierLayer.draw(travelCanvas);
        check(travelBitmap.getPixel(56, 8) == Color.GREEN, "the moving badge updates during a held gesture");
        leftMotionType.getMethod("clear").invoke(leftMotion);
        check(carrierTarget.getAlpha() == 1f, "ending a carrier gesture restores the destination alpha");

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
        System.out.println("PASS: idle recaptures, live tint, bitmap mutation, carrier badge selection and travel, "
            + "masked badge updates, moving-dot tint interpolation and reversal, held updates and alpha restoration");
    }
}
