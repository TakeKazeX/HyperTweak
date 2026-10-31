import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;

/** Same network content at every battery value, using the actual Android font and Canvas. */
public final class DuoHeaderAlignmentProbe {
    static void run(Context context, ClassLoader cl, String output) throws Exception {
        String p = "com.takekazex.hypertweak.presentation.icon.duo.";
        Class<?> drawableType = cl.loadClass(p + "DuoDrawable");
        Class<?> layerType = cl.loadClass(p + "DuoRenderLayer");
        Class<?> contentType = cl.loadClass(p + "DuoContent");
        Class<?> sizesType = cl.loadClass(p + "DuoSizes");
        Constructor<?> battery = cl.loadClass(p + "DuoBattery").getConstructor(int.class, boolean.class, boolean.class, Integer.class);
        Constructor<?> content = Arrays.stream(contentType.getConstructors()).filter(c -> c.getParameterCount() == 10).findFirst().get();
        Constructor<?> row = cl.loadClass(p + "DuoSignalRow").getConstructor(int.class, int.class, int.class);
        Class<?> rendererType = cl.loadClass(p + "DuoTopRenderer");
        Object renderer = rendererType.getConstructor().newInstance();
        java.lang.reflect.Field textPaintField = rendererType.getDeclaredField("textPaint");
        textPaintField.setAccessible(true);
        Paint numberPaint = (Paint) textPaintField.get(renderer);
        float[] digitAdvances = new float[10];
        int[] digitInkWidths = new int[10];
        android.graphics.Rect digitInk = new android.graphics.Rect();
        for (int digit = 0; digit < 10; digit++) {
            String text = Integer.toString(digit);
            digitAdvances[digit] = numberPaint.measureText(text);
            numberPaint.getTextBounds(text, 0, 1, digitInk);
            digitInkWidths[digit] = digitInk.width();
        }
        System.out.println("FONT digit_advances=" + Arrays.toString(digitAdvances) +
            " digit_ink_widths=" + Arrays.toString(digitInkWidths) + " features=" + numberPaint.getFontFeatureSettings());
        for (float advance : digitAdvances) if (Math.abs(advance - digitAdvances[0]) > .01f)
            throw new AssertionError("Battery digits are not tabular");
        Object layer = Enum.valueOf((Class) layerType, "COMPOSITE");
        Drawable icon = (Drawable) drawableType.getConstructor(Context.class, layerType).newInstance(context, layer);
        Object sizes = drawableType.getMethod("getSizes").invoke(icon);
        Float firstAngle = null, lastAngle = null;
        Float[] twoDigitAngles = new Float[2];
        for (int percent = 0; percent <= 100; percent++) for (boolean charging : new boolean[]{false, true}) {
            Object layout = rendererType.getMethod("measure", int.class, boolean.class, boolean.class, sizesType)
                .invoke(renderer, percent, true, charging, sizes);
            Object geometry = layout.getClass().getMethod("getGeometry").invoke(layout);
            float a = (Float) geometry.getClass().getMethod("getHalfGapDegrees").invoke(geometry);
            float y = (Float) geometry.getClass().getMethod("getCenterY").invoke(geometry);
            float height = (Float) geometry.getClass().getMethod("getHeight").invoke(geometry);
            float capY = 16f - 14.6f * (float) Math.cos(Math.toRadians(a));
            if (Math.abs(y - Math.max(capY - .6f, -2.2f + height / 2f)) > .001f)
                throw new AssertionError("Number lost cap alignment: " + percent + " charging=" + charging);
            if (y + height / 2f > 9.351f) throw new AssertionError("Header crowds network glyph: " + percent + " charging=" + charging);
            if (percent >= 10 && percent <= 99) {
                int slot = charging ? 1 : 0;
                if (twoDigitAngles[slot] != null && Math.abs(a - twoDigitAngles[slot]) > .001f)
                    throw new AssertionError("Same digit count changed the opening: " + percent);
                twoDigitAngles[slot] = a;
            }
            if (firstAngle == null) firstAngle = a;
            lastAngle = a;
        }
        if (Math.abs(firstAngle - lastAngle) < 1f) throw new AssertionError("Opening must follow actual width");
        int[] values = {0, 7, 23, 60, 85, 96, 99, 100};
        Bitmap sheet = Bitmap.createBitmap(144 * values.length, 176 * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet); canvas.drawColor(Color.rgb(18, 18, 20));
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG); label.setColor(Color.WHITE);
        label.setTextSize(12); label.setTextAlign(Paint.Align.CENTER);
        drawableType.getMethod("setShowTopPercent", boolean.class).invoke(icon, true);
        drawableType.getMethod("setDualSignalDots", boolean.class).invoke(icon, true);
        drawableType.getMethod("setForeground", int.class).invoke(icon, Color.WHITE);
        for (int charging = 0; charging < 2; charging++) for (int i = 0; i < values.length; i++) {
            Object state = content.newInstance(battery.newInstance(values[i], charging == 1, false, null),
                4, null, 4, false, false, false, Arrays.asList(4, 2),
                Arrays.asList(row.newInstance(1, 0, 4), row.newInstance(2, 1, 2)), 1);
            drawableType.getMethod("setContent", contentType).invoke(icon, state);
            int x = i * 144, y = charging * 176;
            icon.setBounds(x + 8, y + 8, x + 136, y + 136); icon.draw(canvas);
            canvas.drawText(values[i] + (charging == 1 ? " charging" : " normal"), x + 72, y + 160, label);
        }
        try (FileOutputStream out = new FileOutputStream(output)) { sheet.compress(Bitmap.CompressFormat.PNG, 100, out); }
        System.out.println("PASS actual_font_header_alignment_cases=202 same_content_render_cases=16");
    }
}
