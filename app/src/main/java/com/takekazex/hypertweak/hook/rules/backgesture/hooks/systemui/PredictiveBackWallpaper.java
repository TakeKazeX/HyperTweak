package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import com.takekazex.hypertweak.util.DebugLog;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Surface;
import android.view.SurfaceControl;

/** Wallpaper pixels only; Shell continues to own the surface, geometry and removal. */
final class PredictiveBackWallpaper implements AutoCloseable {
    /**
     * HyperTweak: {@link Intent#ACTION_WALLPAPER_CHANGED} is deprecated on API 37, but SystemUI
     * still receives and registers this broadcast, and it is the only signal for wallpaper swaps.
     * Spelled out here so the dependency stays explicit without the deprecated constant reference.
     */
    private static final String WALLPAPER_CHANGED = "android.intent.action.WALLPAPER_CHANGED";
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HT-BackWallpaper");
        thread.setDaemon(true);
        return thread;
    });
    private final PreparedWallpaperCache<Bitmap> cache = new PreparedWallpaperCache<>(worker,
            Bitmap::recycle, failure -> DebugLog.INSTANCE.w("BackGesture", "Wallpaper preparation unavailable; preserving native background", failure));
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WALLPAPER_CHANGED.equals(action)) cache.invalidate();
            else cache.retryMissing();
            warm();
        }
    };
    private boolean registered;

    PredictiveBackWallpaper(Context context) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        IntentFilter filter = new IntentFilter(WALLPAPER_CHANGED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            this.context.registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED);
            registered = true;
        } catch (RuntimeException failure) {
            DebugLog.INSTANCE.w("BackGesture", "Wallpaper change observer unavailable", failure);
        }
        warm();
    }

    private void warm() {
        cache.request(() -> prepare(context));
    }

    SurfaceControl create(SurfaceControl.Builder builder, int width, int height) throws Exception {
        // Surface dimensions are rendering inputs, never wallpaper-cache identity.
        SurfaceControl ready = cache.use(bitmap -> render(builder, width, height, bitmap));
        if (ready == null) warm();
        return ready;
    }

    @Override public void close() {
        cache.close();
        worker.shutdownNow();
        if (registered) {
            registered = false;
            try { context.unregisterReceiver(changes); }
            catch (RuntimeException failure) { DebugLog.INSTANCE.w("BackGesture", "Wallpaper observer cleanup failed", failure); }
        }
    }

    // Runs inside privileged SystemUI (READ_WALLPAPER_INTERNAL verified on the host),
    // never in HyperTweak's app process. Denial is caught by the renderer's native fallback.
    @SuppressLint("MissingPermission")
    private static Bitmap prepare(Context context) throws Exception {
        WallpaperManager manager = WallpaperManager.getInstance(context);
        int wallpaperId = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        File directory = context.getCacheDir();
        WallpaperCacheFile disk = directory == null ? null : new WallpaperCacheFile(new File(directory,
                "hypertweak-back-wallpaper/source.htwp"));
        Bitmap bitmap = null;
        try {
            byte[] pixels = disk == null ? null : disk.read(wallpaperId);
            if (pixels != null) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pixels, 0, pixels.length, bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 1024 && bounds.outHeight <= 1024) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    bitmap = BitmapFactory.decodeByteArray(pixels, 0, pixels.length, options);
                }
            }
        } catch (IOException failure) {
            DebugLog.INSTANCE.w("BackGesture", "Wallpaper disk cache unreadable; refreshing source", failure);
        }
        if (bitmap != null) {
            boolean current = false;
            try {
                current = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) == wallpaperId;
                return current ? bitmap : null;
            } finally { if (!current) bitmap.recycle(); }
        }
        manager.forgetLoadedWallpaper();
        Drawable drawable = manager.getDrawable(WallpaperManager.FLAG_SYSTEM);
        if (drawable == null) return null;
        int sourceWidth = drawable.getIntrinsicWidth();
        int sourceHeight = drawable.getIntrinsicHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            sourceWidth = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
            sourceHeight = Math.max(1, context.getResources().getDisplayMetrics().heightPixels);
        }
        // Retain the source aspect ratio. Crop at render time so rotation and
        // freeform backgrounds reuse the same locally prepared wallpaper.
        float scale = Math.min(.25f, 1024f / Math.max(sourceWidth, sourceHeight));
        bitmap = Bitmap.createBitmap(Math.max(1, Math.round(sourceWidth * scale)),
                Math.max(1, Math.round(sourceHeight * scale)), Bitmap.Config.ARGB_8888);
        boolean complete = false;
        try {
            Rect originalBounds = new Rect(drawable.getBounds());
            try {
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(new Canvas(bitmap));
            } finally { drawable.setBounds(originalBounds); }
            blurBitmap(bitmap, 7, 2);
            if (manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) != wallpaperId) return null;
            if (wallpaperId > 0 && disk != null) {
                try {
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) throw new IOException("Wallpaper cache encoding failed");
                    disk.write(wallpaperId, encoded.toByteArray());
                } catch (IOException failure) {
                    DebugLog.INSTANCE.w("BackGesture", "Wallpaper disk cache write failed; retaining memory cache", failure);
                }
            }
            complete = true;
            return bitmap;
        } finally { if (!complete) bitmap.recycle(); }
    }

    private static SurfaceControl render(SurfaceControl.Builder builder, int width, int height,
            Bitmap bitmap) throws Exception {
        SurfaceControl control = null;
        Surface surface = null;
        boolean complete = false;
        try {
            control = builder.setName("HyperTweakPredictiveBackWallpaper")
                    .setBufferSize(width, height).setFormat(PixelFormat.RGBX_8888).setHidden(true).build();
            surface = new Surface(control);
            Canvas canvas = surface.lockCanvas(null);
            try {
                canvas.drawColor(Color.BLACK);
                drawCenterCrop(bitmap, canvas, width, height);
                canvas.drawColor(0x26000000);
            } finally { surface.unlockCanvasAndPost(canvas); }
            complete = true;
            return control;
        } finally {
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

    private static void drawCenterCrop(Bitmap bitmap, Canvas canvas, int width, int height) {
        float scale = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float scaledWidth = bitmap.getWidth() * scale;
        float scaledHeight = bitmap.getHeight() * scale;
        float left = (width - scaledWidth) / 2f;
        float top = (height - scaledHeight) / 2f;
        canvas.drawBitmap(bitmap, null, new android.graphics.RectF(left, top,
                left + scaledWidth, top + scaledHeight), new Paint(Paint.FILTER_BITMAP_FLAG));
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
