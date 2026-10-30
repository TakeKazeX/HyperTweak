import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import dalvik.system.PathClassLoader;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Actual production Canvas drawing in an isolated ART process: no windows, hooks or UI input. */
public final class DuoRenderProbe {
    private static final String P = "com.takekazex.hypertweak.presentation.icon.duo.";
    public static void main(String[] args) {
        try { run(args); System.exit(0); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        Looper.prepareMainLooper();
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object main = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(main);
        Context context = system.createPackageContext("com.takekazex.hypertweak", Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader cl = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader().getParent());
        Class<?> drawableType = cl.loadClass(P + "DuoDrawable");
        Class<?> layerType = cl.loadClass(P + "DuoRenderLayer");
        Object layer = Enum.valueOf((Class) layerType, "COMPOSITE");
        Constructor<?> battery = cl.loadClass(P + "DuoBattery").getConstructor(int.class, boolean.class, boolean.class, Integer.class);
        Class<?> contentType = cl.loadClass(P + "DuoContent");
        Constructor<?> content = Arrays.stream(contentType.getConstructors()).filter(c -> c.getParameterCount() == 10).findFirst().get();
        Constructor<?> row = cl.loadClass(P + "DuoSignalRow").getConstructor(int.class, int.class, int.class);
        List<?> rows = Arrays.asList(row.newInstance(1, 0, 4), row.newInstance(2, 1, 2));
        String[] labels = {"charging / bolt only", "charging / 60 + bolt", "23 / dual SIM", "96 / LTE", "normal / no number", "100 + bolt / dual SIM", "airplane / offline", "23 / single SIM"};
        int[] percentages = {60, 60, 23, 96, 80, 100, 75, 23};
        int cellW = 180, cellH = 176;
        Bitmap sheet = Bitmap.createBitmap(cellW * 4, cellH * 2, Bitmap.Config.ARGB_8888);
        Canvas sheetCanvas = new Canvas(sheet);
        sheetCanvas.drawColor(Color.rgb(18, 18, 20));
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.WHITE); label.setTextSize(12f); label.setTextAlign(Paint.Align.CENTER);
        int passed = 0, layerCases = 0, labelCases = 0, typographyCases = 0;
        Object trackLayer = Enum.valueOf((Class) layerType, "POWER_TRACK");
        for (int i = 0; i < labels.length; i++) {
            Drawable icon = (Drawable) drawableType.getConstructor(Context.class, layerType).newInstance(context, layer);
            boolean charge = i == 0 || i == 1 || i == 5;
            boolean number = i != 0 && i != 4;
            boolean dual = i == 2 || i == 5;
            boolean flight = i == 6;
            Object state = content.newInstance(battery.newInstance(percentages[i], charge, false, null),
                i == 3 || flight ? null : Integer.valueOf(4), i == 3 ? "LTE" : null, 4, false, false,
                flight, Collections.singletonList(4), rows, Integer.valueOf(1));
            drawableType.getMethod("setContent", contentType).invoke(icon, state);
            drawableType.getMethod("setShowTopPercent", boolean.class).invoke(icon, number);
            drawableType.getMethod("setDualSignalDots", boolean.class).invoke(icon, dual);
            drawableType.getMethod("setForeground", int.class).invoke(icon, Color.WHITE);
            Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 128, 128); icon.draw(new Canvas(bitmap));
            // Compare the settings preview's composite path with the live view's two layers.
            Bitmap split = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Canvas splitCanvas = new Canvas(split);
            for (Object renderLayer : new Object[]{trackLayer, layer}) {
                Drawable part = (Drawable) drawableType.getConstructor(Context.class, layerType).newInstance(context, renderLayer);
                drawableType.getMethod("setContent", contentType).invoke(part, state);
                drawableType.getMethod("setShowTopPercent", boolean.class).invoke(part, number);
                drawableType.getMethod("setDualSignalDots", boolean.class).invoke(part, dual);
                drawableType.getMethod("setForeground", int.class).invoke(part, Color.WHITE);
                if (renderLayer == layer) drawableType.getMethod("setHidePowerTrack", boolean.class).invoke(part, true);
                part.setBounds(0, 0, 128, 128); part.draw(splitCanvas);
            }
            int[] compositePixels = new int[128 * 128], splitPixels = new int[128 * 128];
            bitmap.getPixels(compositePixels, 0, 128, 0, 0, 128, 128);
            split.getPixels(splitPixels, 0, 128, 0, 0, 128, 128);
            if (!Arrays.equals(compositePixels, splitPixels)) throw new AssertionError("Composite/live-layer drawing mismatch: " + labels[i]);
            layerCases++;
            if (i == 3) {
                int left = 128, right = -1;
                for (int y = 100; y < 120; y++) for (int x = 0; x < 128; x++) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 128) { left = Math.min(left, x); right = Math.max(right, x); }
                }
                if (right < left || Math.abs((left + right) / 2f - 64f) > 2f) throw new AssertionError("LTE ink is off-center: " + left + ".." + right);
                labelCases++;
            }
            if (number) {
                Class<?> headerType = cl.loadClass(P + "DuoTopRenderer");
                Object header = headerType.getConstructor().newInstance();
                Class<?> sizesType = cl.loadClass(P + "DuoSizes");
                Object sizes = drawableType.getMethod("getSizes").invoke(icon);
                Object headerLayout = headerType.getMethod("measure", int.class, boolean.class, boolean.class, sizesType)
                    .invoke(header, percentages[i], true, charge, sizes);
                Object geometry = headerLayout.getClass().getMethod("getGeometry").invoke(headerLayout);
                Class<?> geometryType = geometry.getClass();
                float width = (Float) geometryType.getMethod("getWidth").invoke(geometry);
                float height = (Float) geometryType.getMethod("getHeight").invoke(geometry);
                float scale = (Float) geometryType.getMethod("getScale").invoke(geometry);
                float numberWidth = (Float) geometryType.getMethod("getNumberWidth").invoke(geometry) * scale;
                float centerY = (Float) geometryType.getMethod("getCenterY").invoke(geometry);
                float left = (16f - width / 2f) * .9f + 1.6f;
                float top = (centerY - height / 2f - 16f) * .9f + 14.8f;
                int minX = 128, minY = 128, maxX = -1, maxY = -1;
                int x0 = Math.max(0, (int) Math.floor(left * 4f));
                int x1 = Math.min(128, (int) Math.ceil((left + numberWidth * .9f) * 4f));
                int y0 = Math.max(0, (int) Math.floor(top * 4f));
                int y1 = Math.min(128, (int) Math.ceil((top + height * .9f) * 4f));
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                }
                int minimumInkHeight = (int) Math.floor(height * .9f * 4f) - 1;
                if (i == 2 && numberWidth / height < 1.2f) throw new AssertionError("Digits are unnaturally narrow");
                if (maxY - minY + 1 < minimumInkHeight) throw new AssertionError("Number ink is too small: " + labels[i] + " bounds=" + minX + "," + minY + ".." + maxX + "," + maxY + " crop=" + x0 + "," + y0 + ".." + x1 + "," + y1);
                float expectedX = (left + numberWidth * .9f / 2f) * 4f;
                float expectedY = (14.8f + (centerY - 16f) * .9f) * 4f;
                if (Math.abs((minX + maxX) / 2f + .5f - expectedX) > 2f ||
                    Math.abs((minY + maxY) / 2f + .5f - expectedY) > 2f) throw new AssertionError("Header ink is off-center: " + labels[i]);
                typographyCases++;
            }
            int pixels = 0;
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) if (Color.alpha(bitmap.getPixel(x, y)) > 0) pixels++;
            if (pixels < 100) throw new AssertionError("Empty draw " + labels[i]);
            if (dual) {
                Picture dots = (Picture) drawableType.getMethod("signalDotsPicture").invoke(icon);
                RectF previous = null;
                Bitmap recorded = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                Canvas recordedCanvas = new Canvas(recorded); recordedCanvas.scale(4f, 4f); recordedCanvas.drawPicture(dots);
                for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
                    RectF crop = new RectF(), bounds = new RectF();
                    boolean has = (Boolean) drawableType.getMethod("signalDotsBounds", RectF.class, RectF.class, int.class).invoke(icon, crop, bounds, rowIndex);
                    if (!has || (previous != null && RectF.intersects(previous, bounds))) throw new AssertionError("Overlapping row crops");
                    for (int y = (int) bounds.top; y < Math.ceil(bounds.bottom); y++) {
                        for (int x = (int) bounds.left; x < Math.ceil(bounds.right); x++) {
                            if (bitmap.getPixel(x, y) != recorded.getPixel(x, y)) throw new AssertionError("Handoff differs from actual row drawing at " + x + "," + y);
                        }
                    }
                    previous = bounds;
                }
                passed++;
            }
            int cx = i % 4 * cellW, cy = i / 4 * cellH;
            sheetCanvas.drawBitmap(bitmap, cx + (cellW - 128) / 2f, cy + 12f, null);
            sheetCanvas.drawText(labels[i], cx + cellW / 2f, cy + 160f, label);
        }
        // Expanded power-track mode must never inherit the compact header opening or bolt.
        Object powerLayer = Enum.valueOf((Class) layerType, "POWER_TRACK");
        int[] previousPixels = null;
        for (boolean showNumber : new boolean[]{false, true}) {
            Drawable ring = (Drawable) drawableType.getConstructor(Context.class, layerType).newInstance(context, powerLayer);
            Object state = content.newInstance(battery.newInstance(77, true, false, null), null, null, 4,
                false, false, false, Collections.singletonList(4), rows, Integer.valueOf(1));
            drawableType.getMethod("setContent", contentType).invoke(ring, state);
            drawableType.getMethod("setBatteryOnly", boolean.class).invoke(ring, true);
            drawableType.getMethod("setShowTopPercent", boolean.class).invoke(ring, showNumber);
            Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            ring.setBounds(0, 0, 128, 128); ring.draw(new Canvas(bitmap));
            if (Color.alpha(bitmap.getPixel(64, 7)) == 0) throw new AssertionError("Expanded top ring has a gap");
            int[] pixels = new int[128 * 128]; bitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128);
            if (previousPixels != null && !Arrays.equals(previousPixels, pixels)) throw new AssertionError("Expanded ring depends on compact number setting");
            previousPixels = pixels;
        }
        // Exercise the production View at mismatched fake-row heights, offscreen only.
        Class<?> viewType = cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoIconView");
        android.view.View view = (android.view.View) viewType.getConstructor(Context.class).newInstance(context);
        Drawable viewIcon = (Drawable) viewType.getMethod("getIcon").invoke(view);
        Object viewState = content.newInstance(battery.newInstance(77, true, false, null), Integer.valueOf(4), null, 4,
            false, false, false, Collections.singletonList(4), rows, Integer.valueOf(1));
        drawableType.getMethod("setContent", contentType).invoke(viewIcon, viewState);
        viewType.getMethod("setSourceIconSidePx", Integer.class).invoke(view, Integer.valueOf(48));
        int[] sourcePixels = null;
        for (int rowHeight : new int[]{48, 32, 96}) {
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(128, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(rowHeight, android.view.View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 128, rowHeight);
            Bitmap bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.translate(0, 80 - rowHeight / 2);
            view.draw(canvas);
            if (viewIcon.getBounds().height() != 48) throw new AssertionError("Proxy glyph changed size");
            int[] pixels = new int[160 * 160]; bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 160);
            if (Arrays.stream(pixels).allMatch(pixel -> pixel == 0)) throw new AssertionError("View drew no ring");
            if (sourcePixels != null && !Arrays.equals(sourcePixels, pixels)) throw new AssertionError("Proxy ring changed with row height");
            sourcePixels = pixels;
        }
        viewType.getMethod("setSourceIconSidePx", Integer.class).invoke(view, new Object[]{null});
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(128, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(32, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 128, 32);
        view.draw(new Canvas(Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)));
        if (viewIcon.getBounds().height() != 32) throw new AssertionError("Source size override leaked after restore");
        // Drive the actual power renderer forward and backward without attached UI.
        Class<?> motionType = cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoPowerMotion");
        Object motion = motionType.getConstructor(Context.class).newInstance(context);
        android.view.View home = (android.view.View) viewType.getConstructor(Context.class).newInstance(context);
        android.view.View copy = (android.view.View) viewType.getConstructor(Context.class).newInstance(context);
        android.view.View expanded = (android.view.View) viewType.getConstructor(Context.class).newInstance(context);
        Drawable homeIcon = (Drawable) viewType.getMethod("getIcon").invoke(home);
        drawableType.getMethod("setContent", contentType).invoke(homeIcon, viewState);
        drawableType.getMethod("setShowTopPercent", boolean.class).invoke(homeIcon, true);
        viewType.getMethod("submit", contentType, boolean.class, Picture.class).invoke(expanded, viewState, false, null);
        viewType.getMethod("setBatteryOnly", boolean.class, Drawable.class).invoke(expanded, true,
            new android.graphics.drawable.ColorDrawable(Color.WHITE));
        viewType.getMethod("setPercent", boolean.class, boolean.class, android.view.View.class,
            android.widget.TextView.class, android.widget.TextView.class, String.class, boolean.class)
            .invoke(expanded, true, true, null, null, null, "77", true);
        for (android.view.View v : new android.view.View[]{home, copy, expanded}) {
            int size = v == expanded ? 80 : 64;
            viewType.getMethod("setSourceIconSidePx", Integer.class).invoke(v, Integer.valueOf(size));
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY));
            v.layout(0, 0, size, size);
        }
        java.lang.reflect.Method prepare = motionType.getMethod("prepareFrame", viewType, viewType, viewType,
            RectF.class, RectF.class, RectF.class, float.class);
        Bitmap motionSheet = Bitmap.createBitmap(192 * 5, 220, Bitmap.Config.ARGB_8888);
        Canvas motionCanvas = new Canvas(motionSheet); motionCanvas.drawColor(Color.rgb(18, 18, 20));
        int[][] forwardFrames = new int[5][];
        int motionCases = 0;
        for (int index : new int[]{0, 1, 2, 3, 4, 3, 2, 1, 0}) {
            float fraction = index / 4f;
            float right = 80 + (144 - 80) * fraction, cy = 48 + (136 - 48) * fraction;
            prepare.invoke(motion, home, copy, expanded, new RectF(16, 16, 80, 80),
                new RectF(right - 64, cy - 32, right, cy + 32), new RectF(64, 96, 144, 176), fraction);
            Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            motionType.getMethod("drawFrame", Canvas.class).invoke(motion, new Canvas(bitmap));
            int[] pixels = new int[192 * 192]; bitmap.getPixels(pixels, 0, 192, 0, 0, 192, 192);
            if (forwardFrames[index] != null && !Arrays.equals(forwardFrames[index], pixels))
                throw new AssertionError("Power reverse drag differs at " + fraction);
            forwardFrames[index] = pixels;
            if (motionCases < 5) {
                motionCanvas.drawBitmap(bitmap, index * 192, 0, null);
                motionCanvas.drawText("progress " + fraction, index * 192 + 96, 210, label);
            }
            if (index == 0 || index == 4) {
                motionType.getMethod("clear").invoke(motion);
                Bitmap expected = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
                Canvas endpoint = new Canvas(expected); endpoint.translate(index == 0 ? 16 : 64, index == 0 ? 16 : 96);
                (index == 0 ? home : expanded).draw(endpoint);
                int[] expectedPixels = new int[192 * 192]; expected.getPixels(expectedPixels, 0, 192, 0, 0, 192, 192);
                int bad = 0, maxDelta = 0;
                for (int pixel = 0; pixel < pixels.length; pixel++) {
                    if (pixels[pixel] != expectedPixels[pixel]) bad++;
                    maxDelta = Math.max(maxDelta, Math.abs(Color.alpha(pixels[pixel]) - Color.alpha(expectedPixels[pixel])));
                }
                if (bad > 8 || maxDelta > 3) throw new AssertionError("Power endpoint differs: " + index + " pixels=" + bad + " alpha=" + maxDelta);
                for (android.view.View v : new android.view.View[]{home, copy, expanded})
                    if ((Boolean) viewType.getMethod("getPowerInMotion").invoke(v)) throw new AssertionError("Power mask leaked on clear");
            }
            motionCases++;
        }
        // Charging arcs are green, so opaque white ink isolates the number/bolt here.
        // Catch line-height interpolation that makes intermediate numerals larger than both endpoints.
        int[] textHeights = new int[5];
        for (int frame = 0; frame < 5; frame++) {
            int minY = 192, maxY = -1;
            for (int y = frame == 4 ? 148 : 0; y < 192; y++) for (int x = 0; x < 192; x++) {
                int pixel = forwardFrames[frame][y * 192 + x];
                if (Color.alpha(pixel) > 180 && Color.red(pixel) > 235 && Color.green(pixel) > 235 && Color.blue(pixel) > 235) {
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            textHeights[frame] = maxY - minY + 1;
        }
        int maxEndpointInk = Math.max(textHeights[0], textHeights[4]);
        for (int height : textHeights) if (height <= 0 || height > maxEndpointInk + 2)
            throw new AssertionError("Moving percentage overshoots endpoint ink: " + Arrays.toString(textHeights));
        motionType.getMethod("clear").invoke(motion);
        try (FileOutputStream out = new FileOutputStream(args[1] + ".motion.png")) { motionSheet.compress(Bitmap.CompressFormat.PNG, 100, out); }
        Class<?> dotsRendererType = cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoDotHandoverRenderer");
        Object dotsRenderer = dotsRendererType.getConstructor().newInstance();
        Class<?> morphDataType = cl.loadClass(P + "DuoDotMorph");
        Class<?> signalBarsType = cl.loadClass(P + "DuoSignalBars");
        java.lang.reflect.Method drawDots = dotsRendererType.getMethod("drawMorph", Canvas.class, morphDataType,
            RectF.class, RectF.class, RectF.class, float.class);
        java.lang.reflect.Method fallbackDots = dotsRendererType.getMethod("drawFallback", Canvas.class, Picture.class, RectF.class, RectF.class);
        Drawable dotsIcon = (Drawable) drawableType.getConstructor(Context.class, layerType).newInstance(context, layer);
        drawableType.getMethod("setContent", contentType).invoke(dotsIcon, viewState);
        drawableType.getMethod("setDualSignalDots", boolean.class).invoke(dotsIcon, true);
        dotsIcon.setBounds(0, 0, 128, 128);
        Picture dotsSource = (Picture) drawableType.getMethod("signalDotsPicture").invoke(dotsIcon);
        Class<?> artworkType = cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalArtwork");
        Object artwork = artworkType.getConstructor().newInstance();
        Object assets = artworkType.getMethod("loadCellularSignalAssets", Context.class).invoke(artwork, context);
        if (assets == null) throw new AssertionError("Signal SVG assets unavailable");
        Object single = assets.getClass().getMethod("getSingle").invoke(assets);
        Object signalGeometry = artworkType.getMethod("signalBars", Context.class).invoke(artwork, context);
        if (signalGeometry == null) throw new AssertionError("Independent signal path geometry unavailable");
        Class<?> svgRendererType = cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRenderer");
        Object svgRenderer = svgRendererType.getField("INSTANCE").get(null);
        java.lang.reflect.Method makeSignal = Arrays.stream(svgRendererType.getMethods())
            .filter(m -> m.getName().equals("signalPicture") && m.getParameterCount() == 5).findFirst().get();
        Bitmap dotSheet = Bitmap.createBitmap(144 * 5, 160, Bitmap.Config.ARGB_8888);
        Canvas dotCanvas = new Canvas(dotSheet); dotCanvas.drawColor(Color.rgb(18, 18, 20));
        int dotRoundCases = 0, dotTravelCases = 0;
        for (int sim = 0; sim < 2; sim++) {
            RectF dotCrop = new RectF(), dotLocal = new RectF();
            drawableType.getMethod("signalDotsBounds", RectF.class, RectF.class, int.class).invoke(dotsIcon, dotCrop, dotLocal, sim);
            Picture bars = (Picture) makeSignal.invoke(svgRenderer, single, Collections.singletonList(sim == 0 ? 4 : 2), .28f, .28f, 0f);
            Object morphData = drawableType.getMethod("signalDotMorph", int.class, signalBarsType).invoke(dotsIcon, sim, signalGeometry);
            int[][] dotFrames = new int[5][];
            for (int index : new int[]{0, 1, 2, 3, 4, 3, 2, 1, 0}) {
                float fraction = index / 4f, w = 36 + 28 * fraction, h = 6 + 34 * fraction;
                RectF travel = new RectF(72 - w / 2, 40 - h / 2, 72 + w / 2, 40 + h / 2);
                Bitmap bitmap = Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888);
                RectF from = new RectF(54, 37, 90, 43), to = new RectF(40, 20, 104, 60);
                drawDots.invoke(dotsRenderer, new Canvas(bitmap), morphData, dotCrop, from, to, fraction);
                assertMorphComponents(bitmap, fraction);
                if (index == 4) {
                    Bitmap expected = Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888);
                    new Canvas(expected).drawPicture(bars, to);
                    assertNearPixels(bitmap, expected, "Independent bars differ from actual SVG endpoint");
                }
                int[] pixels = new int[144 * 80]; bitmap.getPixels(pixels, 0, 144, 0, 0, 144, 80);
                if (dotFrames[index] != null && !Arrays.equals(dotFrames[index], pixels)) throw new AssertionError("Dot reverse drag differs");
                if (dotFrames[index] == null) dotCanvas.drawBitmap(bitmap, index * 144, sim * 56, null);
                dotFrames[index] = pixels;
                if (dotTravelCases % 9 < 5) {
                    // No destination artwork: circles must remain circular even in the tallest box,
                    // and the neighbouring SIM's commands must remain outside this crop.
                    Bitmap round = Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888);
                    fallbackDots.invoke(dotsRenderer, new Canvas(round), dotsSource, dotCrop, travel);
                    assertFourRoundComponents(round);
                    dotRoundCases++;
                }
                dotTravelCases++;
            }
        }
        // Changing the eight-dot option rebuilds the cached source geometry, including the
        // curved single-SIM row. It must still yield four individually growing contours.
        drawableType.getMethod("setDualSignalDots", boolean.class).invoke(dotsIcon, false);
        RectF singleCrop = new RectF(), singleLocal = new RectF();
        drawableType.getMethod("signalDotsBounds", RectF.class, RectF.class, int.class).invoke(dotsIcon, singleCrop, singleLocal, 0);
        Object singleMorph = drawableType.getMethod("signalDotMorph", int.class, signalBarsType).invoke(dotsIcon, 0, signalGeometry);
        int[][] singleFrames = new int[5][];
        for (int index : new int[]{0, 1, 2, 3, 4, 3, 2, 1, 0}) {
            Bitmap bitmap = Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888);
            drawDots.invoke(dotsRenderer, new Canvas(bitmap), singleMorph, singleCrop,
                new RectF(54, 30, 90, 50), new RectF(40, 20, 104, 60), index / 4f);
            assertMorphComponents(bitmap, index / 4f);
            int[] pixels = new int[144 * 80]; bitmap.getPixels(pixels, 0, 144, 0, 0, 144, 80);
            if (singleFrames[index] != null && !Arrays.equals(singleFrames[index], pixels)) throw new AssertionError("Single-SIM reverse morph differs");
            singleFrames[index] = pixels;
            dotTravelCases++;
        }
        // Execute the actual overlay/alpha owner on detached test Views. These override only
        // attachment reporting; no Window is created and no host UI is touched.
        drawableType.getMethod("setDualSignalDots", boolean.class).invoke(dotsIcon, true);
        RectF ownerCrop = new RectF(), ownerLocal = new RectF();
        drawableType.getMethod("signalDotsBounds", RectF.class, RectF.class, int.class).invoke(dotsIcon, ownerCrop, ownerLocal, 0);
        Object ownerMorph = drawableType.getMethod("signalDotMorph", int.class, signalBarsType).invoke(dotsIcon, 0, signalGeometry);
        Picture ownerBars = (Picture) makeSignal.invoke(svgRenderer, single, Collections.singletonList(4), .28f, .28f, 0f);
        ProbeAttachedRoot testRoot = new ProbeAttachedRoot(context);
        ProbeAttachedView testSource = new ProbeAttachedView(context);
        ProbeAttachedSignal testTarget = new ProbeAttachedSignal(context, ownerBars);
        testRoot.addView(testSource); testRoot.addView(testTarget);
        testRoot.layout(0, 0, 160, 160); testSource.layout(0, 0, 128, 128); testTarget.layout(40, 20, 104, 60);
        Class<?> rowMotionType = cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.duo.SignalRowMotion");
        Object rowMotion = rowMotionType.getConstructor().newInstance();
        java.lang.reflect.Method updateRow = rowMotionType.getMethod("update", android.view.ViewGroup.class,
            android.view.View.class, RectF.class, android.view.View.class, Picture.class, RectF.class,
            int.class, float.class, Picture.class, android.widget.ImageView.class, Float.class, boolean.class, morphDataType, cl.loadClass(P + "SignalBarMorph"));
        int ownershipCases = 0;
        for (float fraction : new float[]{0f, .25f, .5f, .75f, .9f, .99f, 1f, .99f, .5f, 0f}) {
            updateRow.invoke(rowMotion, testRoot, testSource, ownerLocal, testTarget, dotsSource, ownerCrop,
                Color.WHITE, fraction, ownerBars, null, null, true, ownerMorph, null);
            testRoot.draw(new Canvas(Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)));
            boolean owns = (Boolean) updateRow.invoke(rowMotion, testRoot, testSource, ownerLocal, testTarget,
                dotsSource, ownerCrop, Color.WHITE, fraction, ownerBars, null, null, true, ownerMorph, null);
            if (!owns) throw new AssertionError("Signal overlay never became ready");
            float expectedAlpha = fraction == 1f ? 1f : 0f;
            if (testTarget.getAlpha() != expectedAlpha) throw new AssertionError("Native bars appeared before morph endpoint at " + fraction);
            Bitmap composite = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            testRoot.draw(new Canvas(composite));
            components(composite); // Four contours, including during the native endpoint handoff.
            ownershipCases++;
        }
        rowMotionType.getMethod("clear").invoke(rowMotion);
        if (testTarget.getAlpha() != 1f) throw new AssertionError("Signal target alpha leaked after cancellation");
        for (int index = 0; index < 5; index++) dotCanvas.drawText("progress " + index / 4f, index * 144 + 72, 152, label);
        try (FileOutputStream out = new FileOutputStream(args[1] + ".dots.png")) { dotSheet.compress(Bitmap.CompressFormat.PNG, 100, out); }
        try (FileOutputStream out = new FileOutputStream(args[1])) { sheet.compress(Bitmap.CompressFormat.PNG, 100, out); }
        DuoNetworkProbe.run(context, cl, args[1] + ".network.png");
        System.out.println("PASS dot_ownership_cases=" + ownershipCases + " dot_round_cases=" + dotRoundCases + " dot_travel_cases=" + dotTravelCases + " power_motion_cases=" + motionCases + " proxy_height_cases=3 proxy_restore_cases=1 actual_renderer_cases=8 expanded_ring_cases=2 per_sim_picture_crop_cases=" + passed + " composite_live_layer_cases=" + layerCases + " label_centering_cases=" + labelCases + " number_ink_cases=" + typographyCases);
    }
    private static void assertFourRoundComponents(Bitmap bitmap) {
        for (android.graphics.Rect bounds : components(bitmap)) {
            if (Math.abs(bounds.width() - bounds.height()) > 1) throw new AssertionError("Signal fallback dot became an ellipse");
        }
    }
    static java.util.List<android.graphics.Rect> components(Bitmap bitmap) {
        java.util.List<android.graphics.Rect> result = new java.util.ArrayList<>();
        int w = bitmap.getWidth(), h = bitmap.getHeight(), count = 0;
        boolean[] seen = new boolean[w * h]; int[] queue = new int[w * h];
        for (int start = 0; start < seen.length; start++) {
            if (seen[start] || Color.alpha(bitmap.getPixel(start % w, start / w)) <= 10) continue;
            int head = 0, tail = 0, left = w, right = -1, top = h, bottom = -1;
            queue[tail++] = start; seen[start] = true;
            while (head < tail) {
                int pixel = queue[head++], x = pixel % w, y = pixel / w;
                left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
                for (int next : new int[]{pixel - 1, pixel + 1, pixel - w, pixel + w}) {
                    if (next < 0 || next >= seen.length || seen[next] || Math.abs(next % w - x) + Math.abs(next / w - y) != 1) continue;
                    if (Color.alpha(bitmap.getPixel(next % w, next / w)) <= 10) continue;
                    seen[next] = true; queue[tail++] = next;
                }
            }
            // Compare each dot's half-opacity core, independent of its reserve alpha and
            // subpixel edge coverage. Low-alpha fringe bounds can differ by two device pixels.
            int peak = 0;
            for (int i = 0; i < tail; i++) peak = Math.max(peak, Color.alpha(bitmap.getPixel(queue[i] % w, queue[i] / w)));
            left = w; right = -1; top = h; bottom = -1;
            for (int i = 0; i < tail; i++) {
                int x = queue[i] % w, y = queue[i] / w;
                if (Color.alpha(bitmap.getPixel(x, y)) * 2 < peak) continue;
                left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
            result.add(new android.graphics.Rect(left, top, right + 1, bottom + 1));
            count++;
        }
        if (count != 4) throw new AssertionError("SIM morph contains " + count + " contours, expected 4");
        result.sort(java.util.Comparator.comparingInt(bounds -> bounds.left));
        return result;
    }
    private static void assertMorphComponents(Bitmap bitmap, float fraction) {
        java.util.List<android.graphics.Rect> shapes = components(bitmap);
        if (fraction == 0f) for (android.graphics.Rect bounds : shapes) {
            if (Math.abs(bounds.width() - bounds.height()) > 1) throw new AssertionError("Morph must start as four circles");
        }
        if (fraction >= .25f) {
            for (int i = 1; i < 4; i++) if (shapes.get(i).height() < shapes.get(i-1).height())
                throw new AssertionError("Columns do not grow to independent ascending heights");
            if (shapes.get(3).height() <= shapes.get(0).height()) throw new AssertionError("All dots stretched to the same height");
        }
    }
    private static void assertNearPixels(Bitmap actual, Bitmap expected, String message) {
        int changed = 0, severe = 0;
        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++) {
            int a = Color.alpha(actual.getPixel(x, y)), b = Color.alpha(expected.getPixel(x, y));
            if (a != b) changed++;
            if (Math.abs(a-b) > 5) severe++;
        }
        if (severe > 4 || changed > 160) throw new AssertionError(message + " changed=" + changed + " severe=" + severe);
    }

    private static class ProbeAttachedRoot extends android.widget.FrameLayout {
        ProbeAttachedRoot(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    private static class ProbeAttachedView extends android.view.View {
        ProbeAttachedView(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    private static final class ProbeAttachedSignal extends ProbeAttachedView {
        private final Picture bars;
        ProbeAttachedSignal(Context context, Picture bars) { super(context); this.bars = bars; }
        @Override protected void onDraw(Canvas canvas) { canvas.drawPicture(bars, new RectF(0, 0, getWidth(), getHeight())); }
    }

}
