package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.app.WallpaperManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Surface;
import android.view.SurfaceControl;

/** Wallpaper pixels only; Shell continues to own the surface, geometry and removal. */
final class PredictiveBackWallpaper {
    // Runs inside privileged SystemUI (READ_WALLPAPER_INTERNAL verified on the host),
    // never in HyperTweak's app process. Denial is caught by the renderer's native fallback.
    @SuppressLint("MissingPermission")
    static SurfaceControl create(Context context, SurfaceControl.Builder builder,
            int width, int height) throws Exception {
        Drawable drawable = WallpaperManager.getInstance(context).getDrawable(WallpaperManager.FLAG_SYSTEM);
        if (drawable == null) return null;
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width / 4), Math.max(1, height / 4), Bitmap.Config.ARGB_8888);
        SurfaceControl control = null;
        Surface surface = null;
        boolean complete = false;
        try {
            Rect originalBounds = new Rect(drawable.getBounds());
            try {
                drawCenterCrop(drawable, new Canvas(bitmap), bitmap.getWidth(), bitmap.getHeight());
            } finally {
                drawable.setBounds(originalBounds);
            }
            blurBitmap(bitmap, 7, 2);
            control = builder.setName("HyperTweakPredictiveBackWallpaper")
                    .setBufferSize(width, height).setFormat(PixelFormat.RGBX_8888).setHidden(true).build();
            surface = new Surface(control);
            Canvas canvas = surface.lockCanvas(null);
            try {
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(bitmap, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
                canvas.drawColor(0x26000000);
            } finally {
                surface.unlockCanvasAndPost(canvas);
            }
            complete = true;
            return control;
        } finally {
            bitmap.recycle();
            if (surface != null) surface.release();
            if (!complete && control != null) discard(control);
        }
    }

    private static final class SurfaceMethods {
        static final java.lang.reflect.Method REMOVE = resolveRemove();
        private static java.lang.reflect.Method resolveRemove() {
            try { return SurfaceControl.Transaction.class.getMethod("remove", SurfaceControl.class); }
            catch (NoSuchMethodException failure) { throw new IllegalStateException(failure); }
        }
    }

    static void discard(SurfaceControl surface) {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            SurfaceMethods.REMOVE.invoke(transaction, surface);
            transaction.apply();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Wallpaper surface cleanup failed", failure);
        } finally {
            surface.release();
        }
    }

    private static void drawCenterCrop(Drawable drawable, Canvas canvas, int width, int height) {
        int sourceWidth = drawable.getIntrinsicWidth();
        int sourceHeight = drawable.getIntrinsicHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            drawable.setBounds(0, 0, width, height);
        } else {
            float scale = Math.max(width / (float) sourceWidth,
                    height / (float) sourceHeight);
            int scaledWidth = Math.round(sourceWidth * scale);
            int scaledHeight = Math.round(sourceHeight * scale);
            int left = (width - scaledWidth) / 2;
            int top = (height - scaledHeight) / 2;
            drawable.setBounds(left, top, left + scaledWidth, top + scaledHeight);
        }
        drawable.draw(canvas);
    }

    private static void blurBitmap(Bitmap bitmap, int radius, int iterations) {
        if (radius <= 0 || iterations <= 0
                || bitmap.getWidth() <= 1 || bitmap.getHeight() <= 1) {
            return;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        int[] scratch = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int iteration = 0; iteration < iterations; iteration++) {
            boxBlurHorizontal(pixels, scratch, width, height, radius);
            boxBlurVertical(scratch, pixels, width, height, radius);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static void boxBlurHorizontal(int[] source, int[] target,
            int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int alpha = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int x = -radius; x <= radius; x++) {
                int color = source[row + wallpaperClamp(x, 0, width - 1)];
                alpha += Color.alpha(color);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
            }
            for (int x = 0; x < width; x++) {
                target[row + x] = Color.argb(alpha / window, red / window,
                        green / window, blue / window);
                int left = source[row + wallpaperClamp(x - radius, 0, width - 1)];
                int right = source[row + wallpaperClamp(x + radius + 1, 0, width - 1)];
                alpha += Color.alpha(right) - Color.alpha(left);
                red += Color.red(right) - Color.red(left);
                green += Color.green(right) - Color.green(left);
                blue += Color.blue(right) - Color.blue(left);
            }
        }
    }

    private static void boxBlurVertical(int[] source, int[] target,
            int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            int alpha = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int y = -radius; y <= radius; y++) {
                int color = source[wallpaperClamp(y, 0, height - 1) * width + x];
                alpha += Color.alpha(color);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
            }
            for (int y = 0; y < height; y++) {
                target[y * width + x] = Color.argb(alpha / window, red / window,
                        green / window, blue / window);
                int top = source[wallpaperClamp(y - radius, 0, height - 1) * width + x];
                int bottom = source[wallpaperClamp(y + radius + 1, 0, height - 1)
                        * width + x];
                alpha += Color.alpha(bottom) - Color.alpha(top);
                red += Color.red(bottom) - Color.red(top);
                green += Color.green(bottom) - Color.green(top);
                blue += Color.blue(bottom) - Color.blue(top);
            }
        }
    }

    private static int wallpaperClamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

}
