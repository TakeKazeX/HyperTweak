import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import dalvik.system.PathClassLoader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

/** Detached producer lifecycle, native appearance and competing production motion controllers. */
public final class AirplaneEndpointProbe {
    private static final String ICON = "com.takekazex.hypertweak.hook.rules.systemui.icon.";
    public static final class Root extends FrameLayout {
        public Root(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    public static final class Label extends TextView {
        public boolean attached = true;
        public Label(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return attached; }
    }
    public static final class NativeImage extends ImageView {
        public float mIconAppearAmount = 1f;
        public NativeImage(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Object get(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static int pixel(Picture picture) {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawPicture(picture);
        return bitmap.getPixel(8, 8);
    }
    private static Bitmap white(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE); return bitmap;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Label label(Context context, ClassLoader loader) throws Exception {
        Label label = new Label(context);
        label.setTextColor(Color.WHITE);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f);
        Class<?> order = loader.loadClass("com.takekazex.hypertweak.presentation.icon.AirplaneLabelGeometry$Order");
        Object span = loader.loadClass(ICON + "AirplaneGlyphSpan").getConstructor(
            android.graphics.drawable.Drawable.class, order, boolean.class, boolean.class, float.class, String.class)
            .newInstance(new BitmapDrawable(context.getResources(), white(16)),
                Enum.valueOf((Class) order, "GLYPH_ONLY"), false, false, 2f, "airplane");
        SpannableString text = new SpannableString("airplane");
        text.setSpan(span, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setText(text);
        label.measure(View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY));
        label.layout(0, 0, 40, 32);
        label.draw(new Canvas(Bitmap.createBitmap(40, 32, Bitmap.Config.ARGB_8888)));
        return label;
    }
    private static Object[] weakRegistration(Context context, ClassLoader loader, Object registry,
                                             Method register, Object surface, Object owner) throws Exception {
        // No TextView drawing/accessibility work here: those platform queues can temporarily
        // retain a detached View independently of the registry under test.
        Label label = new Label(context);
        register.invoke(registry, label, surface, owner);
        Class<?> endpointType = loader.loadClass(ICON + "AirplaneGlyphTargets$Endpoint");
        var constructor = endpointType.getDeclaredConstructor(TextView.class); constructor.setAccessible(true);
        Object endpoint = constructor.newInstance(label);
        @SuppressWarnings("unchecked") var endpoints = (java.util.Map<TextView, Object>) get(registry, "endpoints");
        endpoints.put(label, endpoint);
        return new Object[]{new WeakReference<>(label), endpoint};
    }
    private static void nativeTint(Context context, ClassLoader loader) throws Exception {
        NativeImage image = new NativeImage(context);
        image.setImageBitmap(white(16)); image.layout(0, 0, 16, 16);
        Class<?> type = loader.loadClass(ICON + "left.ViewEndpoint");
        var constructor = type.getDeclaredConstructor(View.class, boolean.class); constructor.setAccessible(true);
        Object endpoint = constructor.newInstance(image, true);
        Method capture = type.getDeclaredMethod("picture"); capture.setAccessible(true);
        Picture before = (Picture) capture.invoke(endpoint);
        image.setColorFilter(Color.RED);
        Picture red = (Picture) capture.invoke(endpoint);
        check(red != before && pixel(red) == Color.RED && pixel(before) == Color.WHITE,
            "in-place color filter publishes a new immutable snapshot");
        check(capture.invoke(endpoint) == red, "unchanged output reuses the published snapshot");
        image.clearColorFilter(); image.setImageTintList(ColorStateList.valueOf(Color.BLUE));
        Picture blue = (Picture) capture.invoke(endpoint);
        check(blue != red && pixel(blue) == Color.BLUE, "native tint change invalidates the capture too");
        System.out.println("PASS native appearance: filter/tint updates, immutable snapshots, stable idle picture");
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registryAndMotion(Context context, ClassLoader loader) throws Exception {
        Class<?> type = loader.loadClass(ICON + "AirplaneGlyphTargets");
        Object registry = type.getField("INSTANCE").get(null);
        Class<?> surfaceType = loader.loadClass(ICON + "AirplaneGlyphTargets$Surface");
        Class<?> ownerType = loader.loadClass(ICON + "AirplaneGlyphTargets$Owner");
        Object cc = Enum.valueOf((Class) surfaceType, "CONTROL_CENTER");
        Object kg = Enum.valueOf((Class) surfaceType, "KEYGUARD");
        Object host = Enum.valueOf((Class) ownerType, "HOST_LABEL");
        Object compact = Enum.valueOf((Class) ownerType, "COMPACT_CARRIER");
        Method register = type.getMethod("register", TextView.class, surfaceType, ownerType);
        Method release = type.getMethod("release", ownerType);
        Method unregister = type.getMethod("unregister", TextView.class, ownerType);
        Method find = type.getMethod("controlCenter");
        type.getMethod("releaseAll").invoke(registry);
        Root root = new Root(context); root.layout(0, 0, 320, 128);
        Label nativeLabel = label(context, loader), compactLabel = label(context, loader);
        root.addView(nativeLabel); nativeLabel.layout(0, 0, 40, 32);
        root.addView(compactLabel); compactLabel.layout(280, 0, 320, 32);
        register.invoke(registry, nativeLabel, kg, host); register.invoke(registry, compactLabel, cc, compact);
        Object nativeEndpoint = type.getMethod("keyguard", View.class, boolean.class).invoke(registry, root, true);
        Object endpoint = find.invoke(registry);
        Method alpha = endpoint.getClass().getMethod("getValue");
        Method setAlpha = endpoint.getClass().getMethod("setValue", float.class);
        Method picture = endpoint.getClass().getMethod("picture");
        setAlpha.invoke(nativeEndpoint, 0f); setAlpha.invoke(endpoint, 0f);
        release.invoke(registry, host);
        check((Float) alpha.invoke(nativeEndpoint) == 1f && (Float) alpha.invoke(endpoint) == 0f && find.invoke(registry) == endpoint,
            "host retirement restores only native labels and preserves the compact endpoint identity/alpha");
        unregister.invoke(registry, compactLabel, host);
        check(find.invoke(registry) == endpoint && (Float) alpha.invoke(endpoint) == 0f, "foreign unregister has no effect");
        setAlpha.invoke(endpoint, 1f);
        competingMotion(context, loader, root, compactLabel, endpoint, (Picture) picture.invoke(endpoint));
        setAlpha.invoke(endpoint, 0f);
        compactLabel.attached = false;
        var listener = (View.OnAttachStateChangeListener) get(registry, "attachment");
        listener.onViewDetachedFromWindow(compactLabel);
        check(find.invoke(registry) == null && (Float) alpha.invoke(endpoint) == 1f, "detach retires motion and opacity");
        compactLabel.attached = true; listener.onViewAttachedToWindow(compactLabel);
        Object reattached = find.invoke(registry);
        check(reattached != null && reattached != endpoint && (Float) alpha.invoke(reattached) == 1f,
            "reattachment recovers the producer registration without stale opacity");
        unregister.invoke(registry, compactLabel, compact);
        check(find.invoke(registry) == null, "producer teardown unregisters the removed compact label");
        Object[] weak = weakRegistration(context, loader, registry, register, cc, compact);
        Object weakEndpoint = weak[1];
        // GC timing and Android's constructor queues are outside this contract. Expire the
        // production weak reference explicitly and check that active callers stay null-safe.
        var endpointReference = (WeakReference<?>) get(weakEndpoint, "reference");
        endpointReference.clear();
        check(weakEndpoint.getClass().getMethod("getView").invoke(weakEndpoint) == null &&
            weakEndpoint.getClass().getMethod("picture").invoke(weakEndpoint) == null,
            "expired weak-view endpoint has neither a view nor a stale picture");
        weakEndpoint.getClass().getMethod("setValue", float.class).invoke(weakEndpoint, .4f);
        check((Float) weakEndpoint.getClass().getMethod("getValue").invoke(weakEndpoint) == .4f,
            "opacity updates safely tolerate expired view");
        type.getMethod("releaseAll").invoke(registry);
        System.out.println("PASS registry: isolated producer retirement, foreign unregister, detach/reattach, expired weak-view safety");
    }
    private static void competingMotion(Context context, ClassLoader loader, Root root, Label target,
                                         Object endpoint, Picture glyph) throws Exception {
        Class<?> function0 = loader.loadClass("kotlin.jvm.functions.Function0");
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{function0}, (p, m, v) -> unit);
        Class<?> leftType = loader.loadClass(ICON + "left.LeftPanelMotion");
        Object left = leftType.getConstructor(function0).newInstance(callback);
        Class<?> endpointAlpha = loader.loadClass(ICON + "EndpointAlpha");
        Method move = leftType.getMethod("update", ViewGroup.class, View.class, View.class, float.class,
            Float.class, boolean.class, Picture.class, Picture.class, endpointAlpha, boolean.class);
        NativeImage source = new NativeImage(context); root.addView(source); source.layout(0, 0, 32, 32);
        source.setImageBitmap(white(32));
        move.invoke(left, root, source, target, .5f, null, false, null, glyph, endpoint, false);
        Class<?> duoType = loader.loadClass(ICON + "duo.DuoPanelMotion");
        Object duo = duoType.getConstructor(Context.class, boolean.class).newInstance(context, true);
        Class<?> batteryType = loader.loadClass("com.takekazex.hypertweak.presentation.icon.duo.DuoBattery");
        Object battery = batteryType.getConstructor(int.class, boolean.class, boolean.class, Integer.class)
            .newInstance(50, false, false, null);
        Class<?> contentType = loader.loadClass("com.takekazex.hypertweak.presentation.icon.duo.DuoContent");
        Class<?> typography = loader.loadClass("com.takekazex.hypertweak.settings.contract.MobileTypeTypography");
        Object content = Arrays.stream(contentType.getConstructors()).filter(c -> c.getParameterCount() == 12)
            .findFirst().orElseThrow().newInstance(battery, null, null, 4, false, false, true,
                List.of(4), List.of(), null, true, typography.getConstructor().newInstance());
        Class<?> sizesType = loader.loadClass("com.takekazex.hypertweak.presentation.icon.duo.DuoSizes");
        Object sizes = sizesType.getConstructor().newInstance();
        Method duoMove = duoType.getMethod("update", ViewGroup.class, View.class, View.class, contentType,
            int.class, float.class, boolean.class, sizesType,
            loader.loadClass("com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable"),
            loader.loadClass(ICON + "GlyphEndpoint"));
        duoMove.invoke(duo, root, source, target, content, Color.WHITE, .5f, false, sizes, null, endpoint);
        check(get(left, "target") == null && (Float) get(duo, "savedAlpha") == 1f,
            "Duo preempts/restores the old production motion before capturing endpoint alpha");
        check(!(Boolean) move.invoke(left, root, source, target, .5f, null, false, null, glyph, endpoint, false),
            "a fallback production motion cannot acquire a Duo-owned glyph");
        var layer = (android.graphics.drawable.Drawable) get(duo, "layer");
        Bitmap bitmap = Bitmap.createBitmap(320, 128, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); layer.draw(canvas);
        int[] pixels = new int[320 * 128]; bitmap.getPixels(pixels, 0, 320, 0, 0, 320, 128);
        check(Arrays.stream(pixels).allMatch(p -> Color.alpha(p) == 0), "Duo's pending first overlay is invisible");
        check((Boolean) duoMove.invoke(duo, root, source, target, content, Color.WHITE, .5f, false, sizes, null, endpoint),
            "Duo becomes the sole ready overlay");
        layer.draw(canvas); bitmap.getPixels(pixels, 0, 320, 0, 0, 320, 128);
        check(Arrays.stream(pixels).anyMatch(p -> Color.alpha(p) > 0), "the ready Duo layer draws actual artwork");
        duoMove.invoke(duo, root, source, target, content, Color.WHITE, 1f, false, sizes, null, endpoint);
        check((Float) endpoint.getClass().getMethod("getValue").invoke(endpoint) == 1f, "arrival reveals the label");
        duoType.getMethod("clear").invoke(duo);
        move.invoke(left, root, source, target, .5f, null, false, null, glyph, endpoint, false);
        check(get(left, "target") == target, "fallback reacquires only after Duo releases");
        leftType.getMethod("clear").invoke(left);
        System.out.println("PASS concurrent motion: preemption/alpha restore, exclusive writer, first-frame retention, arrival/release");
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        nativeTint(context, loader); registryAndMotion(context, loader);
    }
}
