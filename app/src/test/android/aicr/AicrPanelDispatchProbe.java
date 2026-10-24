import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ContextThemeWrapper;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated ART probe: loads the installed host's real request/JSON runtime, never starts an activity. */
public final class AicrPanelDispatchProbe {
    private static Object callback(Class<?> type, Function<Object, Object> operation) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, values) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return "AicrProbeCallback";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return proxy == values[0];
            }
            if (values == null || values.length != 1) throw new AssertionError("unexpected callback contract");
            return operation.apply(values[0]);
        });
    }

    private static void nativeDrawing(Context system, ClassLoader module, String browser, String label) throws Exception {
        System.out.println("AICR_DRAW_STAGE context");
        Context resources = system.createPackageContext("com.xiaomi.aicr", Context.CONTEXT_IGNORE_SECURITY);
        Context context = new ContextThemeWrapper(resources, android.R.style.Theme_Material_Light_NoActionBar);
        System.out.println("AICR_DRAW_STAGE native_types");
        Class<?> cueType = Class.forName("com.xiaomi.ai.bubble.core.model.CueData");
        Class<?> viewType = Class.forName("com.xiaomi.ai.bubble.core.bubbleview.view.BubbleView");
        Method bind = java.util.Arrays.stream(viewType.getDeclaredMethods())
                .filter(m -> m.getReturnType() == void.class && java.util.Arrays.equals(m.getParameterTypes(), new Class<?>[]{cueType}))
                .findFirst().get();
        Class<?> gsonType = Class.forName("com.google.gson.Gson");
        Object gson = gsonType.getConstructor().newInstance();
        Method fromJson = gsonType.getMethod("fromJson", String.class, Class.class);
        JSONObject title = new JSONObject().put("text", "在" + label + "中打开")
                .put("highlights", new JSONArray().put(new JSONObject().put("start", 1).put("end", 1 + label.length())));
        JSONObject data = new JSONObject().put("cueId", "cue.copy_text_jump_app#draw-probe").put("category", "copy_jump")
                .put("display", new JSONObject().put("targetPackage", browser).put("briefCard", new JSONObject().put("title", title)));
        System.out.println("AICR_DRAW_STAGE gson");
        Object cue = fromJson.invoke(gson, data.toString(), cueType);
        System.out.println("AICR_DRAW_STAGE construct_view");
        View root = (View) viewType.getConstructor(Context.class).newInstance(context);
        System.out.println("AICR_DRAW_STAGE bind_view");
        bind.invoke(root, cue);
        int id = resources.getResources().getIdentifier("bubble_title", "id", "com.xiaomi.aicr");
        TextView text = root.findViewById(id);
        if (text == null || !text.getText().toString().equals(title.getString("text"))) throw new AssertionError("native binding missing");
        if (text.getText() instanceof Spanned && ((Spanned) text.getText()).getSpans(0, text.length(), CharacterStyle.class).length != 0)
            throw new AssertionError("native renderer changed: baseline already styles highlights");
        Class<?> rendererType = module.loadClass("com.takekazex.hypertweak.hook.rules.aicr.AicrBubbleTitleRenderer");
        Object companion = rendererType.getField("Companion").get(null);
        Object renderer = companion.getClass().getMethod("resolve", Method.class).invoke(companion, bind);
        if (renderer == null) throw new AssertionError("native renderer contract missing");
        Method style = rendererType.getMethod("bind", View.class, Object.class, String.class, String.class);
        Method clear = rendererType.getMethod("clear");
        for (int size : new int[]{15, 24, 32}) {
            text.setTextSize(size);
            text.measure(View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST));
            int width = text.getMeasuredWidth();
            if (!Boolean.TRUE.equals(style.invoke(renderer, root, cue, browser, label))) throw new AssertionError("highlight rejected");
            // Repeated recovery must never stack duplicate spans.
            style.invoke(renderer, root, cue, browser, label);
            text.measure(View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST));
            text.layout(0, 0, text.getMeasuredWidth(), text.getMeasuredHeight());
            if (text.getMeasuredWidth() != width) throw new AssertionError("highlight changed glyph metrics");
            Spanned styled = (Spanned) text.getText();
            CharacterStyle[] spans = styled.getSpans(0, styled.length(), CharacterStyle.class);
            if (spans.length != 1 || styled.getSpanStart(spans[0]) != 1 || styled.getSpanEnd(spans[0]) != label.length() + 1)
                throw new AssertionError("wrong native span range");
            TextPaint paint = new TextPaint(text.getPaint());
            spans[0].updateDrawState(paint);
            if (!(paint.getShader() instanceof LinearGradient) || text.getPaint().getShader() != null)
                throw new AssertionError("draw shader=" + paint.getShader() + " base=" + text.getPaint().getShader()
                        + " width=" + text.getWidth() + " start=" + text.getLayout().getPrimaryHorizontal(1)
                        + " end=" + text.getLayout().getPrimaryHorizontal(label.length() + 1)
                        + " ellipsis=" + text.getLayout().getEllipsisStart(0) + ":" + text.getLayout().getEllipsisCount(0));
            Bitmap bitmap = Bitmap.createBitmap(text.getWidth(), text.getHeight(), Bitmap.Config.ARGB_8888);
            text.draw(new Canvas(bitmap));
            if (text.getPaint().getShader() != null) throw new AssertionError("native base paint changed after drawing");
            int colors = 0;
            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
                int color = bitmap.getPixel(x, y);
                if ((color >>> 24) > 40 && Math.abs(((color >>> 16) & 255) - (color & 255)) > 20) colors++;
            }
            bitmap.recycle();
            if (colors < 10) throw new AssertionError("offscreen native draw produced no colored text");
            if (((Number) clear.invoke(renderer)).intValue() != 1) throw new AssertionError("disable did not clear highlight");
            if (((Spanned) text.getText()).getSpans(0, text.length(), CharacterStyle.class).length != 0)
                throw new AssertionError("old-generation span retained");
            System.out.println("AICR_NATIVE_DRAW_OK browser=" + browser + " sp=" + size + " colored_pixels=" + colors + " metrics_preserved=true disable_restored=true");
        }
        style.invoke(renderer, root, cue, browser, label);
        text.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST));
        text.layout(0, 0, text.getMeasuredWidth(), text.getMeasuredHeight());
        Bitmap narrow = Bitmap.createBitmap(text.getWidth(), text.getHeight(), Bitmap.Config.ARGB_8888);
        text.draw(new Canvas(narrow));
        narrow.recycle();
        if (text.getLayout().getEllipsisCount(0) == 0 || text.getPaint().getShader() != null)
            throw new AssertionError("native narrow title ellipsis or base paint changed");
        clear.invoke(renderer);
        System.out.println("AICR_NATIVE_DRAW_NARROW_OK browser=" + browser + " ellipsis_preserved=true");
        // Upgrade recovery reuses the already bound native object without invoking its binder again.
        if (((Number) rendererType.getMethod("recover", View.class, String.class, String.class)
                .invoke(renderer, root, browser, label)).intValue() != 1) throw new AssertionError("existing native view recovery failed");
        clear.invoke(renderer);
        data.put("category", "contact_create");
        Object unrelated = fromJson.invoke(gson, data.toString(), cueType);
        if (Boolean.TRUE.equals(style.invoke(renderer, root, unrelated, browser, label))) throw new AssertionError("unrelated cue styled");
        System.out.println("AICR_NATIVE_DRAW_RECOVERY_OK browser=" + browser + " unrelated_cue_untouched=true windows_created=0");
    }

    public static void main(String[] args) {
        try { run(args); System.exit(0); } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        System.out.println("AICR_PROBE_STAGE framework");
        Looper.prepareMainLooper();
        // app_process does not run ActivityThread's normal application/font bootstrap.
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        Object thread = threadType.getMethod("systemMain").invoke(null);
        Context context = (Context) threadType.getMethod("getSystemContext").invoke(thread);
        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"));
        probe.addCategory(Intent.CATEGORY_BROWSABLE);
        String current = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;
        if (!current.equals(args[1])) throw new AssertionError("default mismatch: " + current);
        Class<?> requestType = Class.forName("com.xiaomi.aicr.decisionframework.model.tool.ToolRequest");
        Class<?> elementType = Class.forName("kotlinx.serialization.json.JsonElement");
        Class<?> jsonType = Class.forName("kotlinx.serialization.json.Json");
        Object json = jsonType.getField("Default").get(null);
        Method parser = jsonType.getMethod("parseToJsonElement", String.class);
        Constructor<?> constructor = requestType.getConstructor(String.class, String.class, String.class, String.class, elementType);
        System.out.println("AICR_PROBE_STAGE release_loader");
        ClassLoader module = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader().getParent());
        Class<?> accessType = module.loadClass("com.takekazex.hypertweak.hook.rules.aicr.AicrToolRequestAccess");
        Object companion = accessType.getField("Companion").get(null);
        System.out.println("AICR_PROBE_STAGE resolve_host");
        Object access = companion.getClass().getMethod("resolveHost", Class.class).invoke(companion, requestType);
        if (access == null) throw new AssertionError("native ToolRequest contract missing");
        Method requestRewrite = java.util.Arrays.stream(accessType.getDeclaredMethods())
                .filter(m -> m.getName().equals("rewrite") && m.getParameterCount() == 2).findFirst().get();
        Class<?> batchType = module.loadClass("com.takekazex.hypertweak.hook.rules.aicr.AicrCopyDirectCueBatch");
        Object batch = batchType.getField("INSTANCE").get(null);
        Method batchRewrite = java.util.Arrays.stream(batchType.getDeclaredMethods())
                .filter(m -> m.getName().equals("rewrite") && m.getParameterCount() == 5).findFirst().get();
        System.out.println("AICR_PROBE_STAGE rewrite");
        for (int browserIndex = 1; browserIndex < args.length; browserIndex++) {
            String browser = args[browserIndex];
            String label = pm.getApplicationLabel(pm.getApplicationInfo(browser, 0)).toString();
            Intent old = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/page?q=probe"));
            old.setComponent(new ComponentName("com.android.browser", "vendor.BrowserActivity"));
            old.setPackage("com.android.browser");
            JSONObject title = new JSONObject().put("text", "在小米浏览器中打开")
                    .put("highlights", new JSONArray().put(new JSONObject().put("start", 1).put("end", 6)));
            JSONObject payload = new JSONObject().put("version", 1).put("cues", new JSONArray().put(new JSONObject()
                    .put("category", "copy_jump").put("cueId", "cue.copy_text_jump_app#probe")
                    .put("display", new JSONObject().put("targetPackage", "com.android.browser")
                            .put("briefCard", new JSONObject().put("title", title)))
                    .put("actions", new JSONArray().put(new JSONObject().put("toolId", "action")
                            .put("input", new JSONObject().put("intentUri", old.toUri(Intent.URI_INTENT_SCHEME)))))));
            Object original = constructor.newInstance("probe", "aipanel", "aipanel", "DecisionFramework",
                    parser.invoke(json, payload.toString()));
            Object rewritten = requestRewrite.invoke(access, original,
                    callback(requestRewrite.getParameterTypes()[1], raw -> {
                        try {
                            return batchRewrite.invoke(batch, raw.toString(), browser, label,
                                    callback(batchRewrite.getParameterTypes()[3], ignored -> "浏览器"),
                                    callback(batchRewrite.getParameterTypes()[4], uri -> {
                                        try {
                                            Intent web = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                                            web.setComponent(null);
                                            web.setPackage(browser);
                                            return web.toUri(Intent.URI_INTENT_SCHEME);
                                        } catch (Exception failure) { throw new RuntimeException(failure); }
                                    }));
                        } catch (Exception failure) { throw new RuntimeException(failure); }
                    }));
            if (rewritten == null || rewritten == original) throw new AssertionError("request was not replaced");
            JSONObject output = new JSONObject(requestType.getMethod("getInput").invoke(rewritten).toString());
            JSONObject cue = output.getJSONArray("cues").getJSONObject(0);
            JSONObject display = cue.getJSONObject("display");
            JSONObject card = display.getJSONObject("briefCard");
            JSONObject text = card.getJSONObject("title");
            JSONObject range = text.getJSONArray("highlights").getJSONObject(0);
            String colored = text.getString("text").substring(range.getInt("start"), range.getInt("end"));
            Intent target = Intent.parseUri(cue.getJSONArray("actions").getJSONObject(0).getJSONObject("input")
                    .getString("intentUri"), Intent.URI_INTENT_SCHEME);
            if (!colored.equals(label) || !browser.equals(display.getString("targetPackage"))
                    || !browser.equals(card.getJSONObject("startIcon").getString("value"))
                    || !browser.equals(target.getPackage()) || target.getComponent() != null
                    || pm.resolveActivity(target, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                throw new AssertionError("native request presentation/action mismatch for " + browser);
            }
            if (!requestType.getMethod("getInput").invoke(original).toString().contains("vendor.BrowserActivity")) {
                throw new AssertionError("original request was mutated");
            }
            nativeDrawing(context, module, browser, label);
            System.out.println("AICR_NATIVE_REQUEST_OK browser=" + browser + " label=" + label + " colored=" + colored);
        }
        Class<?> catalogType = module.loadClass("com.takekazex.hypertweak.hook.rules.aicr.AicrBrowserCatalog");
        Object catalog = catalogType.getField("INSTANCE").get(null);
        java.util.List<?> browsers = (java.util.List<?>) catalogType.getMethod("installed", Context.class).invoke(catalog, context);
        if (browsers.isEmpty()) throw new AssertionError("native browser catalog is empty");
        for (Object browser : browsers) {
            System.out.println("AICR_BROWSER_CATALOG package=" + browser.getClass().getMethod("getPackageName").invoke(browser)
                    + " label=" + browser.getClass().getMethod("getLabel").invoke(browser));
        }
        System.out.println("AICR_PANEL_PROBE_OK default=" + current + " activities_started=0");
    }
}
