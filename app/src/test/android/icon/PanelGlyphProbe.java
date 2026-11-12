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
    public static final class StackedCopy extends ImageView {
        public final String mSlot = "stacked_mobile_icon";
        public StackedCopy(Context context) { super(context); }
    }
    private static java.lang.reflect.Field field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    @SuppressWarnings("unchecked") private static java.util.Map<Object, Object> entries(Object owner, String name) throws Exception {
        return (java.util.Map<Object, Object>) field(owner, name).get(owner);
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
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
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
        checkCarrierRowHeight(loader, context, renderer, rendererType, partsType);
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
        boolean[] cellularSourceAllowed = {true};
        Object carrierHost = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{hostType}, (proxy, method, values) ->
            switch (method.getName()) {
                case "getMobileSignalId" -> bars.getId();
                case "getMobileTypeId" -> inlineType.getId();
                case "findStatusContainer" -> mirror;
                case "allowsCellularSource" -> cellularSourceAllowed[0];
                default -> throw new AssertionError(method.getName());
            });
        Object handover = handoverType.getConstructor(hostType).newInstance(carrierHost);
        View selected = (View) handoverType.getMethod("nativeSignal", ViewGroup.class, Integer.class)
            .invoke(handover, mirror, holder.subId);
        check(selected == bars, "carrier signal uses the native bars as the base glyph");
        Method motionSignal = handoverType.getDeclaredMethod("motionSignal", ViewGroup.class, Integer.class);
        motionSignal.setAccessible(true);
        Method carrierTypeSource = handoverType.getDeclaredMethod("typeGlyph", ViewGroup.class, Integer.class);
        carrierTypeSource.setAccessible(true);
        check(motionSignal.invoke(handover, mirror, holder.subId) == bars, "visible SIM can supply an animated source");
        holder.setVisibility(View.GONE);
        cellularSourceAllowed[0] = false;
        check(handoverType.getMethod("nativeSignal", ViewGroup.class, Integer.class)
            .invoke(handover, mirror, holder.subId) == bars, "hidden holder still supplies expanded glyph artwork");
        check(motionSignal.invoke(handover, mirror, holder.subId) == null,
            "status-bar-hidden SIM cannot animate from stale mirror geometry");
        check(carrierTypeSource.invoke(handover, mirror, holder.subId) == null,
            "status-bar-hidden type cannot animate from stale mirror geometry");
        cellularSourceAllowed[0] = true;
        holder.setVisibility(View.VISIBLE);
        check(motionSignal.invoke(handover, mirror, holder.subId) == bars,
            "restoring visibility re-enables the source without replacing its view");
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
            float.class, Float.class, boolean.class, android.graphics.Picture.class,
            android.graphics.Picture.class,
            loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.EndpointAlpha"), boolean.class);
        java.lang.reflect.Field carrierLayerField = leftMotionType.getDeclaredField("layer");
        carrierLayerField.setAccessible(true);
        var carrierLayer = (android.graphics.drawable.Drawable) carrierLayerField.get(leftMotion);
        Bitmap travelBitmap = Bitmap.createBitmap(120, 16, Bitmap.Config.ARGB_8888);
        Canvas travelCanvas = new Canvas(travelBitmap);
        for (float progress : new float[]{.5f, .99f, .5f}) {
            move.invoke(leftMotion, carrierRoot, holder, carrierTarget, progress, null, false, nativePicture, null, null, false);
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
        move.invoke(leftMotion, carrierRoot, holder, carrierTarget, .5f, null, false, refreshedPicture, null, null, false);
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
        // Stacked orchestration must retain the submitted SignalRowMotion through its first
        // zero-alpha draw. This is a real Canvas/overlay lifecycle check, not a window capture.
        Class<?> stackedType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.StackedPanelMotion");
        Object stacked = stackedType.getConstructor(Object.class).newInstance(new Object());
        Method complete = stackedType.getDeclaredMethod("completeFrame", boolean.class, boolean.class);
        complete.setAccessible(true);
        Object stackedRow = motionType.getConstructor().newInstance();
        var stackedLayer = (android.graphics.drawable.Drawable) layerField.get(stackedRow);
        StackedCopy expandedCopy = new StackedCopy(context), fakeCopy = new StackedCopy(context);
        field(stacked, "expandedRow").set(stacked, expandedCopy); field(stacked, "fakeRow").set(stacked, fakeCopy);
        entries(stacked, "motions").put(12, stackedRow); entries(stacked, "targets").put(12, target);
        boolean ready = (Boolean) update.invoke(stackedRow, root, source, box, target, picture, box, Color.BLACK, .25f,
            null, null, null, true, null, null);
        check(!ready && (Boolean) motionType.getMethod("getHasFrame").invoke(stackedRow), "first frame is submitted but not drawn");
        complete.invoke(stacked, true, false);
        check(entries(stacked, "motions").get(12) == stackedRow &&
            (Boolean) motionType.getMethod("getHasFrame").invoke(stackedRow), "pending overlay must survive the aggregate pass");
        check(fakeCopy.getAlpha() == 1f && expandedCopy.getAlpha() == 1f, "native copies stay visible before first draw");
        stackedLayer.draw(canvas);
        for (float progress : new float[]{.25f, .6f, .9f, .6f, .25f}) {
            ready = (Boolean) update.invoke(stackedRow, root, source, box, target, picture, box, Color.BLACK, progress,
                null, null, null, true, null, null);
            check(ready, "submitted overlay becomes ready and survives reverse drag");
            complete.invoke(stacked, true, true);
            check(fakeCopy.getAlpha() == 0f && expandedCopy.getAlpha() == 0f, "ready overlay owns both native copies");
            rendered.eraseColor(Color.TRANSPARENT); stackedLayer.draw(canvas);
            check(Color.alpha(rendered.getPixel(Math.round(8 + 32 * progress), 8)) > 0, "stacked overlay draws during drag and reversal");
        }
        // A rejected geometry must still release all layers and copies, unlike a pending draw.
        target.layout(0, 0, 0, 0);
        check(!(Boolean) update.invoke(stackedRow, root, source, box, target, picture, box, Color.BLACK, .5f,
            null, null, null, true, null, null), "invalid endpoint is rejected");
        check(!(Boolean) motionType.getMethod("getHasFrame").invoke(stackedRow), "rejection has no pending frame");
        complete.invoke(stacked, false, false);
        check(entries(stacked, "motions").isEmpty() && entries(stacked, "targets").isEmpty(), "rejection releases submitted resources");
        check(fakeCopy.getAlpha() == 1f && expandedCopy.getAlpha() == 1f && Math.abs(target.getAlpha() - .6f) < .001f,
            "rejection restores copies and original destination alpha");
        WifiActivityReloadProbe.run(context, loader);
        System.out.println("PASS: idle recaptures, live tint, bitmap mutation, carrier badge selection and travel, "
            + "masked badge updates, moving-dot tint interpolation and reversal, held updates and alpha restoration, "
            + "stacked pending first draw, ready copies, reverse drag and invalid-endpoint restoration");
    }

    private static void checkCarrierRowHeight(ClassLoader loader, Context context, Object renderer,
                                             Class<?> rendererType, Class<?> partsType) throws Exception {
        android.widget.LinearLayout carrier = new android.widget.LinearLayout(context);
        carrier.setOrientation(android.widget.LinearLayout.VERTICAL);
        carrier.setMinimumHeight(100); // A previous generation/native minimum must not keep two rows alive.
        java.util.List<Object> rows = new java.util.ArrayList<>();
        java.util.List<android.widget.LinearLayout> views = new java.util.ArrayList<>();
        for (int slot = 0; slot < 2; slot++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(context);
            carrier.addView(row, new android.widget.LinearLayout.LayoutParams(200, 24));
            android.widget.TextView text = new android.widget.TextView(context);
            row.addView(text);
            rows.add(partsType.getConstructors()[0].newInstance(slot, row, text, new ImageView(context),
                new android.widget.TextView(context), new FrameLayout(context), new ImageView(context), new ImageView(context)));
            views.add(row);
        }
        Class<?> blockType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierBlock");
        Object block = blockType.getConstructors()[0].newInstance(carrier, rows, null, null);
        blockType.getMethod("setCompact", boolean.class).invoke(block, true);
        Class<?> geometryType = loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.TwoRowIconLayout$Geometry");
        Object[] geometry = {null};
        String name = "com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierPresentation";
        Class<?> presentationType = loader.loadClass(name);
        Class<?> hostType = loader.loadClass(name + "$Host");
        Object host = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{hostType}, (proxy, method, values) ->
            switch (method.getName()) {
                case "rowGeometry" -> geometry[0];
                case "getRowOrder" -> java.util.List.of(0, 1);
                case "hiddenCarrierSlots" -> java.util.Set.of();
                default -> throw new AssertionError(method.getName());
            });
        Object presentation = presentationType.getConstructor(hostType, rendererType).newInstance(host, renderer);
        Method update = presentationType.getMethod("updateRowGeometry", blockType);
        int rowGap = Math.round(3 * context.getResources().getDisplayMetrics().density);
        for (int hidden : new int[]{0, 1, 2, 3, 0}) {
            int count = Math.max(1, 2 - Integer.bitCount(hidden));
            int height = count == 2 ? 48 + rowGap : 24;
            geometry[0] = geometryType.getConstructor(float.class, float.class, int.class, int.class)
                .newInstance(12f, count == 2 ? 36f + rowGap : 12f, height, count);
            blockType.getMethod("setGeometry", geometryType).invoke(block, geometry[0]);
            for (int slot = 0; slot < 2; slot++) views.get(slot).setVisibility((hidden & (1 << slot)) != 0 ? View.GONE : View.VISIBLE);
            update.invoke(presentation, block);
            carrier.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            carrier.layout(0, 0, 200, carrier.getMeasuredHeight());
            update.invoke(presentation, block);
            check(carrier.getMeasuredHeight() == height, "hidden carrier must release its measured row, mask=" + hidden + " expected=" + height + " actual=" + carrier.getMeasuredHeight());
            check(carrier.getMinimumHeight() == height, "old native minimum cannot reserve a hidden carrier row");
            if (hidden == 1) check(views.get(1).getTop() == 0, "remaining SIM 2 moves to the first anchor");
            if (hidden == 2) check(views.get(0).getTop() == 0, "remaining SIM 1 stays at the first anchor");
        }
        presentationType.getMethod("restoreBlockStyle", blockType).invoke(presentation, block);
        check(carrier.getMinimumHeight() == 100, "release restores the captured native minimum");
        System.out.println("PASS carrier_hidden_rows measured=" + (48 + rowGap) + "/24/24/24/" + (48 + rowGap) + " native_minimum_restored=100");
    }
}
