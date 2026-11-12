import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/** Actual lockscreen controller, detached Canvas only; no windows or device settings. */
public final class KeyguardAirplaneProbe {
    public static final class Root extends FrameLayout {
        public android.view.WindowInsets insets;
        public Root(Context context) { super(context); }
        @Override public android.view.Display getDisplay() { return null; }
        @Override public android.view.WindowInsets getRootWindowInsets() { return insets; }
        @Override public boolean isAttachedToWindow() { return true; }
        @Override public boolean isShown() { return getVisibility() == View.VISIBLE; }
    }
    public static final class Label extends TextView {
        public Label(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    private static java.lang.reflect.Field field(Object owner, String name) throws Exception {
        var result = owner.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static Object get(Object owner, String name) throws Exception { return field(owner, name).get(owner); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int[] pixels(Bitmap bitmap) {
        int[] result = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(result, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return result;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        String icon = "com.takekazex.hypertweak.hook.rules.systemui.icon.";
        String left = icon + "left.";
        Root root = new Root(context), carrier = new Root(context), right = new Root(context);
        root.layout(0, 0, 320, 128);
        android.graphics.Rect hole = new android.graphics.Rect(144, 0, 176, 32);
        root.insets = new android.view.WindowInsets.Builder().setDisplayCutout(new android.view.DisplayCutout(
            android.graphics.Insets.of(0, 32, 0, 0), new android.graphics.Rect(), hole,
            new android.graphics.Rect(), new android.graphics.Rect())).build();
        root.addView(carrier); carrier.layout(0, 0, 40, 32);
        root.addView(right); right.layout(260, 0, 320, 32);
        Label label = new Label(context);
        carrier.addView(label);
        label.setTextColor(Color.WHITE);
        // Keep the synthetic glyph within its measured box, independent of device font scaling.
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, args.length > 1 ? 48f : 16f);
        Bitmap white = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        white.eraseColor(Color.WHITE);
        Class<?> orderType = loader.loadClass("com.takekazex.hypertweak.presentation.icon.AirplaneLabelGeometry$Order");
        Class<?> spanType = loader.loadClass(icon + "AirplaneGlyphSpan");
        Object span = spanType.getConstructor(android.graphics.drawable.Drawable.class, orderType,
            boolean.class, boolean.class, float.class, String.class).newInstance(
                new BitmapDrawable(context.getResources(), white), Enum.valueOf((Class) orderType, "GLYPH_ONLY"),
                false, false, 2f, "airplane");
        SpannableString text = new SpannableString("airplane");
        text.setSpan(span, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setText(text);
        label.measure(View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY));
        label.layout(0, 0, 40, 32);
        label.draw(new Canvas(Bitmap.createBitmap(40, 32, Bitmap.Config.ARGB_8888)));
        Class<?> targetsType = loader.loadClass(icon + "AirplaneGlyphTargets");
        Object targets = targetsType.getField("INSTANCE").get(null);
        Class<?> surfaceType = loader.loadClass(icon + "AirplaneGlyphTargets$Surface");
        targetsType.getMethod("register", TextView.class, surfaceType).invoke(targets, label,
            Enum.valueOf((Class) surfaceType, "KEYGUARD"));
        View duo = new View(context) { @Override public boolean isAttachedToWindow() { return true; } };
        right.addView(duo); duo.layout(20, 8, 36, 24);
        Picture picture = new Picture();
        picture.beginRecording(16, 16).drawColor(Color.WHITE); picture.endRecording();
        float[] duoAlpha = {1f};
        Class<?> endpointType = loader.loadClass(icon + "GlyphEndpoint");
        Object endpoint = Proxy.newProxyInstance(loader, new Class<?>[]{endpointType}, (proxy, method, values) -> {
            switch (method.getName()) {
                case "getView": return duo;
                case "picture": return picture;
                case "getValue": return duoAlpha[0];
                case "setValue": duoAlpha[0] = (Float) values[0]; return null;
                default: return null;
            }
        });
        Class<?> function1 = loader.loadClass("kotlin.jvm.functions.Function1");
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Object release = Proxy.newProxyInstance(loader, new Class<?>[]{function1}, (p, m, v) -> unit);
        Object[] activeEndpoint = {endpoint};
        Object provider = Proxy.newProxyInstance(loader, new Class<?>[]{function1}, (p, m, v) -> activeEndpoint[0]);
        Object reason = Proxy.newProxyInstance(loader, new Class<?>[]{function1}, (p, m, v) -> "probe");
        Class<?> duoPort = loader.loadClass(icon + "IconHostServices$Duo");
        Object port = duoPort.getConstructor(function1, function1, function1).newInstance(release, provider, reason);
        Class<?> portsType = loader.loadClass(icon + "IconHostPorts");
        Object ports = portsType.getField("INSTANCE").get(null);
        Object services = portsType.getMethod("getServices").invoke(ports);
        Object binding = services.getClass().getMethod("getBinding").invoke(services);
        Class<?> bindingType = binding.getClass();
        String[] getters = {"getLeft", "getPositions", "getCarrier", "getDuo", "getHeader", "getKeyguard", "getRepublish", "getMobileReady"};
        Object[] members = new Object[getters.length];
        for (int i = 0; i < members.length; i++) members[i] = bindingType.getMethod(getters[i]).invoke(binding);
        members[3] = port;
        Object replacement = Arrays.stream(bindingType.getConstructors()).filter(c -> c.getParameterCount() == 8)
            .findFirst().orElseThrow().newInstance(members);
        Object owner = new Object();
        services.getClass().getMethod("bind", Object.class, bindingType).invoke(services, owner, replacement);
        Class<?> hostType = loader.loadClass(left + "LeftKeyguardAirplane$Host");
        Object host = Proxy.newProxyInstance(loader, new Class<?>[]{hostType}, (p, m, v) ->
            m.getName().equals("getRetiring") ? false : null);
        Class<?> controllerType = loader.loadClass(left + "LeftKeyguardAirplane");
        Object controller = controllerType.getConstructor(hostType).newInstance(host);
        Class<?> sceneType = loader.loadClass(left + "KeyguardAirplaneScene");
        Object scene = Proxy.newProxyInstance(loader, new Class<?>[]{sceneType}, (p, m, v) -> {
            if (m.getName().equals("getKeyguardCarrier")) return carrier;
            if (m.getName().equals("getAirplaneContainer")) return right;
            return null;
        });
        Class<?> stateType = loader.loadClass(left + "LeftKeyguardAirplaneState");
        Object state = stateType.getConstructor().newInstance();
        Class<?> targetType = loader.loadClass(left + "LeftCarrierSwapPolicy$Target");
        Object target = targetType.getConstructor(float.class, float.class).newInstance(1f, 0f);
        Method update = controllerType.getMethod("update", sceneType, stateType, targetType,
            boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
        Method glyphAlpha = spanType.getMethod("getGlyphAlpha");
        update.invoke(controller, scene, state, target, true, false, true, true, true);
        check((Float) glyphAlpha.invoke(span) == 1f && duoAlpha[0] == 0f,
            "intro owns the label and yields Duo even with no left clone/container");
        update.invoke(controller, scene, state, target, true, false, false, false, true);
        ValueAnimator outward = (ValueAnimator) get(state, "animator");
        check(outward != null, "intro expiry starts the no-left Duo travel");
        outward.setCurrentFraction(.15f);
        Object travel = get(state, "travel");
        var layer = (android.graphics.drawable.Drawable) get(travel, "layer");
        Bitmap bitmap = Bitmap.createBitmap(320, 128, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        layer.draw(canvas);
        outward.setCurrentFraction(.15f);
        bitmap.eraseColor(Color.TRANSPARENT); layer.draw(canvas);
        int[] before = pixels(bitmap);
        android.graphics.RectF from = (android.graphics.RectF) get(travel, "from");
        android.graphics.RectF to = (android.graphics.RectF) get(travel, "to");
        float position = (Float) get(state, "fraction");
        int inkLeft = bitmap.getWidth(), inkTop = bitmap.getHeight(), inkRight = -1, inkBottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            if (Color.alpha(bitmap.getPixel(x, y)) <= 8) continue;
            inkLeft = Math.min(inkLeft, x); inkTop = Math.min(inkTop, y);
            inkRight = Math.max(inkRight, x); inkBottom = Math.max(inkBottom, y);
        }
        float expectedX = from.centerX() + (to.centerX() - from.centerX()) * position;
        float expectedY = from.centerY() + (to.centerY() - from.centerY()) * position;
        System.out.println("Straight geometry: from=" + from + " to=" + to + " progress=" + position
            + " expected=" + expectedX + "," + expectedY + " actual="
            + ((inkLeft + inkRight + 1) / 2f) + "," + ((inkTop + inkBottom + 1) / 2f));
        check(Math.abs((inkLeft + inkRight + 1) / 2f - expectedX) < 1.1f &&
            Math.abs((inkTop + inkBottom + 1) / 2f - expectedY) < 1.1f,
            "the artwork follows the straight endpoint line even when window insets contain a cutout");
        check(Arrays.stream(before).anyMatch(pixel -> Color.alpha(pixel) > 0), "overlay actually draws ink");
        check((Float) glyphAlpha.invoke(span) == 0f && duoAlpha[0] == 0f && label.getAlpha() == 1f,
            "only the travelling glyph is visible, the label view/text stays opaque");
        float fraction = (Float) get(state, "fraction");
        Object spring = get(state, "spring");
        Method speed = spring.getClass().getMethod("getVelocity");
        float previousSpeed = (Float) speed.invoke(spring);
        update.invoke(controller, scene, state, target, true, false, true, false, true);
        check(get(state, "travel") == travel && Math.abs((Float) get(state, "fraction") - fraction) < .00001f,
            "reversal retains the overlay and its exact current position");
        check(Math.abs((Float) speed.invoke(spring) - previousSpeed) < .00001f,
            "reversal preserves the spring's velocity, not just its position");
        bitmap.eraseColor(Color.TRANSPARENT); layer.draw(canvas);
        check(Arrays.equals(before, pixels(bitmap)), "reversal's first Canvas frame is pixel-identical");
        ((ValueAnimator) get(state, "animator")).end();
        check((Float) glyphAlpha.invoke(span) == 1f && duoAlpha[0] == 0f && get(state, "travel") == null,
            "return hands the glyph to the label and removes the overlay");
        update.invoke(controller, scene, state, target, true, false, false, false, true);
        ((ValueAnimator) get(state, "animator")).end();
        check((Float) glyphAlpha.invoke(span) == 0f && duoAlpha[0] == 1f, "outward arrival reveals only Duo");
        update.invoke(controller, scene, state, target, true, false, true, false, true);
        check((Float) glyphAlpha.invoke(span) == 0f && duoAlpha[0] == 1f,
            "before a reverse overlay's first draw Duo stays visible without flashing the label");
        controllerType.getMethod("release", sceneType, stateType).invoke(controller, scene, state);
        check(duoAlpha[0] == 1f && (Float) glyphAlpha.invoke(span) == 1f && get(state, "animator") == null,
            "retirement releases both endpoint opacities and cancels the animator");
        activeEndpoint[0] = null;
        update.invoke(controller, scene, state, target, true, false, false, true, true);
        check((Float) glyphAlpha.invoke(span) == 1f, "a missing Duo/native endpoint keeps the label visible");
        activeEndpoint[0] = endpoint;
        update.invoke(controller, scene, state, target, true, false, false, false, true);
        check(get(state, "animator") != null, "a late Duo endpoint starts travel from the retained label");
        ((ValueAnimator) get(state, "animator")).end();
        update.invoke(controller, scene, state, target, true, false, true, true, true);
        update.invoke(controller, scene, state, target, true, false, false, false, true);
        ValueAnimator disappearing = (ValueAnimator) get(state, "animator");
        targetsType.getMethod("unregister", TextView.class).invoke(targets, label);
        disappearing.setCurrentFraction(.1f);
        check(duoAlpha[0] == 1f && get(state, "animator") == null,
            "losing the departing label mid-flight immediately restores the surviving Duo endpoint");
        update.invoke(controller, scene, state, target, true, false, false, false, true);
        check(duoAlpha[0] == 1f && get(state, "atLabel") == null && get(state, "animator") == null,
            "live removal of the label glyph releases Duo without requiring a container or restart");
        targetsType.getMethod("releaseAll").invoke(targets);
        services.getClass().getMethod("retire", Object.class).invoke(services, owner);
        System.out.println("PASS: no-left Duo intro/travel, straight Canvas travel, position/velocity-continuous reversal, arrival/retirement, missing/late endpoint and live disable");
    }
}
