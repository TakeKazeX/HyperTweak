import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Debug;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;
import dalvik.system.PathClassLoader;
import java.lang.reflect.*;
import java.util.*;

/** Same detached production rendering/loading paths in both APKs; no windows or device input. */
public final class RenderingPerformanceProbe {
    public static final class NativeIconShape extends ImageView {
        public NativeIconShape(Context context) { super(context); }
    }
    private interface Operation { Object run(int i) throws Exception; }
    private static volatile Object sink;
    private static long bytes() { return Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated")); }
    private static void measure(String name, int n, Operation operation) throws Exception {
        System.gc();
        for (int round = -3; round < 7; round++) {
            long allocated = bytes(), cpu = Debug.threadCpuTimeNanos(), start = System.nanoTime();
            for (int i = 0; i < n; i++) sink = operation.run(i);
            long elapsed = System.nanoTime()-start, cpuUsed = Debug.threadCpuTimeNanos()-cpu;
            long used = bytes()-allocated;
            if (round >= 0) System.out.printf(Locale.US, "%s,%d,%d,%.3f,%.3f,%.3f%n",
                name, round, n, (double)elapsed/n, (double)cpuUsed/n, (double)used/n);
        }
    }
    private static Method method(Class<?> type, String prefix, int parameters) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().startsWith(prefix)
            && m.getParameterCount() == parameters).findFirst().orElseThrow();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        Looper.prepareMainLooper();
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context moduleContext = system.createPackageContext("com.takekazex.hypertweak", Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        String p = "com.takekazex.hypertweak.";
        Class<?> repository = loader.loadClass(p+"presentation.icon.IconSvgRepository");
        Object repo = repository.getConstructor(Context.class).newInstance(moduleContext);
        Class<?> assets = loader.loadClass(p+"presentation.icon.IconSvgAsset");
        Object asset = assets.getEnumConstants()[2];
        Method load = method(repository, "loadBuiltIn", 1);
        Object first = load.invoke(repo, asset), second = load.invoke(repo, asset);
        check(first.getClass().getName().endsWith("IconSvgSnapshot"), "actual bundled SVG must load");
        System.out.println("META svg_snapshot_reused="+(first == second)+" fingerprint="+android.os.Build.FINGERPRINT);
        System.out.println("case,round,iterations,wall_ns_op,cpu_ns_op,allocated_bytes_op");
        measure("svg_repeated_load", 50, i -> load.invoke(repo, asset));
        Object[] all = assets.getEnumConstants();
        measure("svg_style_roundtrip", 100, i -> load.invoke(repo, all[i % 4]));

        Class<?> frameType = loader.loadClass(p+"hook.rules.systemui.icon.PanelGlyphFrame");
        Method capture = frameType.getMethod("captureView", android.view.View.class, RectF.class);
        Method revision = frameType.getMethod("getRevision");
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE);
        NativeIconShape image = new NativeIconShape(system); image.setImageBitmap(bitmap); image.layout(0,0,64,64);
        Object imageFrame = frameType.getConstructor().newInstance();
        check((Boolean)capture.invoke(imageFrame,image,null), "image capture");
        int initial = (Integer)revision.invoke(imageFrame);
        measure("glyph_native_subclass_dirty", 2000, i -> {image.invalidate();return capture.invoke(imageFrame,image,null);});
        check((Integer)revision.invoke(imageFrame)==initial, "unchanged dirty artwork must keep revision");
        image.setImageTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        capture.invoke(imageFrame,image,null);
        check((Integer)revision.invoke(imageFrame)>initial,"in-place tint must invalidate snapshot");
        TextView text = new TextView(system); text.setLayoutParams(new android.view.ViewGroup.LayoutParams(192,64));
        text.setText("Carrier 5G"); text.setTextSize(18); text.setTextColor(Color.WHITE);
        text.layout(0,0,192,64); Object textFrame = frameType.getConstructor().newInstance();
        check((Boolean)capture.invoke(textFrame,text,null), "text capture");
        measure("glyph_text_dirty", 1000, i -> {text.invalidate();return capture.invoke(textFrame,text,null);});
        text.setText("Changed");
        text.measure(android.view.View.MeasureSpec.makeMeasureSpec(192, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(64, android.view.View.MeasureSpec.EXACTLY));
        text.layout(0,0,192,64);
        int old = (Integer)revision.invoke(textFrame); capture.invoke(textFrame,text,null);
        check((Integer)revision.invoke(textFrame)>old,"text content mutation");
        Method parse = method(repository, "parseSnapshot", 3); parse.setAccessible(true);
        Object kind = first.getClass().getMethod("getKind").invoke(first);
        String source = (String)first.getClass().getMethod("getSource").invoke(first);
        Object changed = parse.invoke(repo, kind, source+"\n<!-- changed source identity -->", "changed.svg");
        check(changed.getClass().getName().endsWith("IconSvgSnapshot"), "changed SVG must validate");
        check(!first.getClass().getMethod("getSourceHash").invoke(first).equals(
            changed.getClass().getMethod("getSourceHash").invoke(changed)), "changed bytes invalidate identity");
        Object renamed = parse.invoke(repo, kind, source, "renamed.svg");
        check("renamed.svg".equals(first.getClass().getMethod("getDisplayName").invoke(renamed)), "display name retained");
        if (first == second) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                java.util.List<java.util.concurrent.Future<Object>> pending = new ArrayList<>();
                for(int i=0;i<32;i++) pending.add(pool.submit(()->load.invoke(repo,asset)));
                for(var result:pending) check(result.get()==first,"concurrent cached document");
            } finally {pool.shutdownNow();}
        }
        System.out.println("PASS svg_source_invalidation display_name concurrent_cache_identity");
        System.out.println("PASS unchanged_dirty_revision tint_mutation text_mutation");
    }
}
