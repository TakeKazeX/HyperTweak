package com.takekazex.hypertweak.hook.rules.backgesture;

// HyperTweak-local layer: cross-task wallpaper background.
//
// Not part of wxxsfxyzm/MiuiBackGestureHook. Upstream deliberately keeps cross-task on its
// native animation and only repaints its colour layer black (upstream 03df087). This layer
// instead draws the current wallpaper behind the cross-task back animation when
// KEY_CROSS_TASK_WALLPAPER_BACKGROUND is on; SystemUiHookRuntime#tintCrossTaskBackground yields
// to it so the two never fight over the same surface.

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.SurfaceControl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import com.takekazex.hypertweak.hook.Preferences;
import com.takekazex.hypertweak.hook.rules.backgesture.core.BackGestureHookRuntime;

public abstract class CrossTaskWallpaperRuntime extends BackGestureHookRuntime {

    /** Upstream names the animation class; this layer hooks its inner remote-animation runner. */
    protected static final String CROSS_TASK_BACK_ANIMATION_RUNNER =
            "com.android.wm.shell.back.CrossTaskBackAnimation$Runner";

    protected final ThreadLocal<CrossTaskScope> crossTaskBackgroundScope = new ThreadLocal<>();
    protected final AtomicLong crossTaskGeneration = new AtomicLong();
    protected final AtomicLong wallpaperGeneration = new AtomicLong();
    protected final Object wallpaperCacheLock = new Object();
    protected final ConcurrentHashMap<SurfaceControl, Handler> pendingWallpaperSurfaces =
            new ConcurrentHashMap<>();
    protected volatile WallpaperCache wallpaperCache;
    protected volatile ExecutorService wallpaperExecutor;
    protected volatile Context wallpaperContext;
    protected volatile WallpaperManager.OnColorsChangedListener wallpaperColorsListener;
    protected volatile ComponentCallbacks2 wallpaperConfigurationCallbacks;
    protected volatile long wallpaperPrewarmGeneration;
    protected volatile Object activeCrossTaskRunner;

    /** Installs the HyperTweak-only cross-task hooks on top of upstream's SystemUI hooks. */
    protected void installCrossTaskWallpaperHooks(ClassLoader classLoader) {
        hookCrossTaskWallpaperScope(classLoader);
        hookBackAnimationBackground(classLoader);
    }

    protected static final class CrossTaskScope {
        final Object runner;
        final Handler handler;
        final long generation;
        final long startedUptime;
        CrossTaskScope(Object runner, Handler handler, long generation) {
            this.runner = runner;
            this.handler = handler;
            this.generation = generation;
            this.startedUptime = SystemClock.uptimeMillis();
        }
    }

    protected static final class WallpaperCache {
        final Bitmap bitmap;
        final int displayWidth;
        final int displayHeight;
        final int rotation;
        final int wallpaperId;
        final long generation;
        final boolean dark;
        protected int leases;
        protected boolean retired;

        WallpaperCache(Bitmap bitmap, int displayWidth, int displayHeight, int rotation,
                       int wallpaperId, long generation, boolean dark) {
            this.bitmap = bitmap;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.rotation = rotation;
            this.wallpaperId = wallpaperId;
            this.generation = generation;
            this.dark = dark;
        }

        synchronized boolean acquire() {
            if (retired || bitmap.isRecycled()) return false;
            leases++;
            return true;
        }

        synchronized void release() {
            leases--;
            recycleIfUnused();
        }

        synchronized void retire() {
            retired = true;
            recycleIfUnused();
        }

        protected void recycleIfUnused() {
            if (retired && leases == 0 && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    protected void hookCrossTaskWallpaperScope(ClassLoader classLoader) {
        try {
            Class<?> runnerClass = Class.forName(CROSS_TASK_BACK_ANIMATION_RUNNER, false,
                    classLoader);
            Method start = null;
            for (Method method : runnerClass.getDeclaredMethods()) {
                if ("onAnimationStart".equals(method.getName())
                        && hasParameter(method, RemoteAnimationTarget[].class)) {
                    start = method;
                    break;
                }
            }
            if (start == null) {
                log(Log.WARN, TAG, "Cross-task runner animation-start method not found");
                return;
            }
            start.setAccessible(true);
            registerHook(start, "systemui_cross_task_background_scope", chain -> {
                Object runner = chain.getThisObject();
                CrossTaskScope previous = crossTaskBackgroundScope.get();
                Handler animationHandler = resolveCrossTaskAnimationHandler(runner);
                CrossTaskScope scope = new CrossTaskScope(runner, animationHandler,
                        crossTaskGeneration.incrementAndGet());
                activeCrossTaskRunner = runner;
                crossTaskBackgroundScope.set(scope);
                log(Log.INFO, TAG, "CrossTask start, generation=" + scope.generation
                        + ", thread=" + Thread.currentThread().getName());
                try {
                    return chain.proceed();
                } finally {
                    crossTaskBackgroundScope.set(previous);
                }
            });
            for (Method method : runnerClass.getDeclaredMethods()) {
                String name = method.getName();
                if ((name.contains("AnimationFinished") || name.contains("AnimationCancelled")
                        || name.equals("onAnimationEnd")) && method != start) {
                    method.setAccessible(true);
                    registerHook(method, "systemui_cross_task_wallpaper_finish_" + name,
                            chain -> {
                                Object runner = chain.getThisObject();
                                if (activeCrossTaskRunner == runner) activeCrossTaskRunner = null;
                                long generation = crossTaskGeneration.incrementAndGet();
                                log(Log.INFO, TAG, "CrossTask finish, generation=" + generation
                                        + ", thread=" + Thread.currentThread().getName());
                                return chain.proceed();
                            });
                }
            }
            log(Log.INFO, TAG, "Hooked cross-task background scope");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook cross-task background scope", throwable);
        }
    }

    protected void hookBackAnimationBackground(ClassLoader classLoader) {
        try {
            Class<?> backgroundClass = Class.forName(BACK_ANIMATION_BACKGROUND, false,
                    classLoader);
            Method ensureBackground = null;
            for (Method method : backgroundClass.getDeclaredMethods()) {
                if ("ensureBackground".equals(method.getName())
                        && method.getParameterCount() >= 6
                        && hasParameter(method, SurfaceControl.Transaction.class)) {
                    ensureBackground = method;
                    break;
                }
            }
            if (ensureBackground == null) {
                log(Log.WARN, TAG, "BackAnimationBackground.ensureBackground not found");
                return;
            }
            ensureBackground.setAccessible(true);
            registerHook(ensureBackground, "systemui_cross_task_wallpaper_background", chain -> {
                Object result = chain.proceed();
                CrossTaskScope scope = crossTaskBackgroundScope.get();
                if (scope == null
                        || !isAospBackGestureActive()
                        || !Preferences.INSTANCE.getBoolean(
                        Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND, false)) {
                    return result;
                }
                try {
                    List<Object> args = chain.getArgs();
                    scheduleWallpaperBackgroundInstall(scope, chain.getThisObject(),
                            args.size() > 0 && args.get(0) instanceof Rect
                                    ? (Rect) args.get(0) : null,
                            args.size() > 4 && args.get(4) instanceof Rect
                                    ? (Rect) args.get(4) : null,
                            args.size() > 5 && args.get(5) instanceof Number
                                    ? ((Number) args.get(5)).floatValue() : 0.0f);
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to install cross-task wallpaper background",
                            throwable);
                }
                return result;
            });
            log(Log.INFO, TAG, "Hooked BackAnimationBackground.ensureBackground");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook BackAnimationBackground", throwable);
        }
    }

    protected boolean hasParameter(Method method, Class<?> parameterType) {
        for (Class<?> candidate : method.getParameterTypes()) {
            if (candidate == parameterType) {
                return true;
            }
        }
        return false;
    }

    protected boolean isAospBackGestureActive() {
        synchronized (nativeInputMonitors) {
            for (NativeBackInputMonitor monitor : nativeInputMonitors.values()) {
                if (monitor != null && monitor.driver.isGestureActive()) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void scheduleWallpaperBackgroundInstall(CrossTaskScope scope, Object background,
            Rect bounds, Rect crop, float cornerRadius) throws Exception {
        Object colorSurfaceObject = readField(background, "mBackgroundSurface");
        if (!(colorSurfaceObject instanceof SurfaceControl)) {
            return;
        }
        SurfaceControl colorSurface = (SurfaceControl) colorSurfaceObject;
        if (!colorSurface.isValid()) {
            return;
        }
        startWallpaperCacheIfEnabled();
        WallpaperCache cache = wallpaperCache;
        if (scope.handler == null || cache == null
                || cache.generation != wallpaperGeneration.get()) {
            log(Log.INFO, TAG, "CrossTask wallpaper cache miss, generation=" + scope.generation);
            return;
        }
        if (bounds != null && !bounds.isEmpty()
                && (bounds.width() != cache.displayWidth || bounds.height() != cache.displayHeight)) {
            log(Log.INFO, TAG, "CrossTask wallpaper cache miss, reason=displaySizeMismatch"
                    + ", cached=" + cache.displayWidth + "x" + cache.displayHeight
                    + ", requested=" + bounds.width() + "x" + bounds.height());
            return;
        }
        Rect capturedBounds = bounds == null ? null : new Rect(bounds);
        Rect capturedCrop = crop == null ? null : new Rect(crop);
        scope.handler.post(() -> installWallpaperBackground(scope, background, colorSurface,
                capturedBounds, capturedCrop, cornerRadius, cache));
    }

    protected Handler resolveCrossTaskAnimationHandler(Object runner) {
        try {
            Object owner = readField(runner, "this$0");
            Object backAnimationRunner = readField(owner, "mBackAnimationRunner");
            Object handler = readField(backAnimationRunner, "mHandler");
            if (handler instanceof Handler) return (Handler) handler;
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Unable to resolve CrossTask animation Handler", throwable);
        }
        return null;
    }

    protected void installWallpaperBackground(CrossTaskScope scope, Object background,
            SurfaceControl colorSurface, Rect bounds, Rect crop, float cornerRadius,
            WallpaperCache cache) {
        String abandoned = wallpaperInstallAbandonReason(scope, background, colorSurface, cache);
        if (abandoned != null) {
            logWallpaperInstallAbandoned(scope, abandoned);
            return;
        }
        if (!cache.acquire()) {
            logWallpaperInstallAbandoned(scope, "cacheRetired");
            return;
        }
        SurfaceControl candidate = null;
        try {
            candidate = createWallpaperBackgroundSurface(background,
                    cache.bitmap.getWidth(), cache.bitmap.getHeight());
            pendingWallpaperSurfaces.put(candidate, scope.handler);
            if (!drawCachedWallpaperIntoSurface(cache.bitmap, candidate)) {
                removeCandidateSurface(candidate);
                return;
            }
            abandoned = wallpaperInstallAbandonReason(scope, background, colorSurface, cache);
            if (abandoned != null) {
                removeCandidateSurface(candidate);
                logWallpaperInstallAbandoned(scope, abandoned);
                return;
            }
            float scaleX = cache.displayWidth / (float) cache.bitmap.getWidth();
            float scaleY = cache.displayHeight / (float) cache.bitmap.getHeight();
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                invokeAnyMethod(transaction, "setMatrix", new Object[]{candidate,
                        Float.valueOf(scaleX), Float.valueOf(0.0f), Float.valueOf(0.0f),
                        Float.valueOf(scaleY)});
                transaction.setLayer(candidate, -1);
                if (crop != null && !crop.isEmpty()) {
                    Rect scaledCrop = new Rect(Math.round(crop.left / scaleX),
                            Math.round(crop.top / scaleY), Math.round(crop.right / scaleX),
                            Math.round(crop.bottom / scaleY));
                    transaction.setCrop(candidate, scaledCrop);
                    invokeAnyMethod(transaction, "setCornerRadius", new Object[]{candidate,
                            Float.valueOf(cornerRadius / Math.max(scaleX, scaleY))});
                }
                invokeAnyMethod(transaction, "show", new Object[]{candidate});
                invokeAnyMethod(transaction, "remove", new Object[]{colorSurface});
                transaction.apply();
            }
            writeField(background, "mBackgroundSurface", candidate);
            writeField(background, "mBackgroundIsDark", Boolean.valueOf(cache.dark));
            pendingWallpaperSurfaces.remove(candidate);
            log(Log.INFO, TAG, "CrossTask wallpaper installed, generation=" + scope.generation
                    + ", buffer=" + cache.bitmap.getWidth() + "x" + cache.bitmap.getHeight()
                    + ", thread=" + Thread.currentThread().getName()
                    + ", elapsedMs=" + (SystemClock.uptimeMillis() - scope.startedUptime));
        } catch (Throwable throwable) {
            if (candidate != null) removeCandidateSurface(candidate);
            log(Log.WARN, TAG, "Failed to install cached CrossTask wallpaper", throwable);
        } finally {
            cache.release();
        }
    }

    protected String wallpaperInstallAbandonReason(CrossTaskScope scope, Object background,
            SurfaceControl colorSurface, WallpaperCache cache) {
        if (scope.generation != crossTaskGeneration.get()) return "staleGeneration";
        if (scope.runner != activeCrossTaskRunner) return "runnerReplaced";
        if (!isAospBackGestureActive()) return "gestureFinished";
        if (!colorSurface.isValid()) return "invalidColorSurface";
        if (cache.generation != wallpaperGeneration.get()) return "staleCache";
        try {
            if (readField(background, "mBackgroundSurface") != colorSurface) {
                return "surfaceReplaced";
            }
        } catch (Throwable throwable) {
            return "surfaceLookupFailed";
        }
        return null;
    }

    protected void logWallpaperInstallAbandoned(CrossTaskScope scope, String reason) {
        log(Log.INFO, TAG, "CrossTask wallpaper install abandoned, reason=" + reason
                + ", generation=" + scope.generation + ", thread="
                + Thread.currentThread().getName());
    }

    protected void removeCandidateSurface(SurfaceControl candidate) {
        pendingWallpaperSurfaces.remove(candidate);
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            if (candidate.isValid()) {
                invokeAnyMethod(transaction, "remove", new Object[]{candidate});
                transaction.apply();
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to remove wallpaper candidate", throwable);
        }
    }

    protected Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    protected synchronized void startWallpaperCacheIfEnabled() {
        if (!Preferences.INSTANCE.getBoolean(
                Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND, false)) return;
        try {
            Context context = wallpaperContext;
            if (context == null) {
                Context current = currentApplicationContext();
                if (current == null) return;
                context = current.getApplicationContext();
                wallpaperContext = context;
            }
            if (wallpaperExecutor == null || wallpaperExecutor.isShutdown()) {
                wallpaperExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "HyperTweak-wallpaper-cache");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
            }
            if (wallpaperColorsListener == null) {
                // ACTION_WALLPAPER_CHANGED is deprecated and is no longer broadcast to
                // manifest receivers; WallpaperManager's colour callback is the supported
                // signal and fires for the same events this cache needs to drop on.
                WallpaperManager wallpaperManager =
                        context.getSystemService(WallpaperManager.class);
                if (wallpaperManager != null) {
                    WallpaperManager.OnColorsChangedListener listener =
                            (colors, which) -> {
                                if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
                                    invalidateWallpaperCache("wallpaperChanged");
                                }
                            };
                    wallpaperManager.addOnColorsChangedListener(
                            listener, new Handler(Looper.getMainLooper()));
                    wallpaperColorsListener = listener;
                }
            }
            if (wallpaperConfigurationCallbacks == null) {
                // ComponentCallbacks2 replaces the deprecated onLowMemory() with onTrimMemory().
                ComponentCallbacks2 callbacks = new ComponentCallbacks2() {
                    @Override
                    public void onConfigurationChanged(Configuration newConfig) {
                        invalidateWallpaperCache("displayConfigurationChanged");
                    }

                    // Abstract on ComponentCallbacks and deprecated there. Carrying the
                    // annotation states that rather than hiding it; onTrimMemory is the
                    // supported signal and already covers this case.
                    @Deprecated
                    @Override
                    public void onLowMemory() {
                    }

                    @Override
                    public void onTrimMemory(int level) {
                        // The RUNNING_*/MODERATE/COMPLETE levels are themselves deprecated.
                        // These two are not, and both mean the cached bitmap is no longer
                        // worth holding onto.
                        if (level == TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_UI_HIDDEN) {
                            invalidateWallpaperCache("trimMemory:" + level);
                        }
                    }
                };
                context.registerComponentCallbacks(callbacks);
                wallpaperConfigurationCallbacks = callbacks;
            }
            if (wallpaperCache == null && wallpaperPrewarmGeneration == 0L) {
                invalidateWallpaperCache("initial");
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to start CrossTask wallpaper cache", throwable);
            shutdownWallpaperCache("startFailed");
        }
    }

    protected synchronized void invalidateWallpaperCache(String reason) {
        long generation = wallpaperGeneration.incrementAndGet();
        wallpaperPrewarmGeneration = generation;
        WallpaperCache old;
        synchronized (wallpaperCacheLock) {
            old = wallpaperCache;
            wallpaperCache = null;
        }
        if (old != null) old.retire();
        ExecutorService executor = wallpaperExecutor;
        Context context = wallpaperContext;
        log(Log.INFO, TAG, "CrossTask wallpaper cache invalidated, reason=" + reason
                + ", generation=" + generation);
        if (executor != null && context != null && !executor.isShutdown()) {
            try {
                executor.execute(() -> prewarmWallpaperCache(context, generation));
            } catch (Throwable throwable) {
                if (wallpaperPrewarmGeneration == generation) wallpaperPrewarmGeneration = 0L;
                log(Log.WARN, TAG, "Failed to schedule wallpaper prewarm", throwable);
            }
        }
    }

    // Runs inside SystemUI, which holds READ_WALLPAPER_INTERNAL; the module itself does not.
    @SuppressLint("MissingPermission")
    protected void prewarmWallpaperCache(Context context, long generation) {
        long started = SystemClock.uptimeMillis();
        Bitmap bitmap = null;
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int displayWidth = Math.max(1, metrics.widthPixels);
            int displayHeight = Math.max(1, metrics.heightPixels);
            int rotation = context.getDisplay() == null ? 0 : context.getDisplay().getRotation();
            WallpaperManager manager = WallpaperManager.getInstance(context);
            int wallpaperId = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            Drawable drawable = manager.peekDrawable();
            if (drawable == null) drawable = manager.getDrawable();
            if (drawable == null) throw new IllegalStateException("wallpaper drawable unavailable");
            int width = Math.max(1, displayWidth / 4);
            int height = Math.max(1, displayHeight / 4);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            drawCenterCrop(drawable, new Canvas(bitmap), width, height);
            blurBitmap(bitmap, 8, 2);
            Canvas overlay = new Canvas(bitmap);
            overlay.drawColor(0x26000000);
            boolean dark = wallpaperAppearsDark(context);
            WallpaperCache prepared = new WallpaperCache(bitmap, displayWidth, displayHeight,
                    rotation, wallpaperId, generation, dark);
            if (generation != wallpaperGeneration.get()) {
                prepared.retire();
                log(Log.INFO, TAG, "Discarded stale wallpaper prewarm, generation=" + generation);
                return;
            }
            synchronized (wallpaperCacheLock) {
                if (generation != wallpaperGeneration.get()) {
                    prepared.retire();
                    log(Log.INFO, TAG, "Discarded wallpaper prewarm during publish"
                            + ", generation=" + generation);
                    return;
                }
                WallpaperCache previous = wallpaperCache;
                wallpaperCache = prepared;
                if (previous != null) previous.retire();
            }
            bitmap = null;
            log(Log.INFO, TAG, "CrossTask wallpaper cache ready, generation=" + generation
                    + ", wallpaperId=" + wallpaperId + ", display=" + displayWidth + "x"
                    + displayHeight + ", rotation=" + rotation + ", buffer=" + width + "x"
                    + height + ", elapsedMs=" + (SystemClock.uptimeMillis() - started));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "CrossTask wallpaper prewarm failed, generation=" + generation
                    + ", elapsedMs=" + (SystemClock.uptimeMillis() - started), throwable);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (wallpaperPrewarmGeneration == generation) wallpaperPrewarmGeneration = 0L;
        }
    }

    protected synchronized void shutdownWallpaperCache(String reason) {
        wallpaperGeneration.incrementAndGet();
        Context context = wallpaperContext;
        WallpaperManager.OnColorsChangedListener colorsListener = wallpaperColorsListener;
        if (context != null && colorsListener != null) {
            try {
                WallpaperManager manager = context.getSystemService(WallpaperManager.class);
                if (manager != null) manager.removeOnColorsChangedListener(colorsListener);
            } catch (Throwable ignored) { }
        }
        if (context != null && wallpaperConfigurationCallbacks != null) {
            try { context.unregisterComponentCallbacks(wallpaperConfigurationCallbacks); }
            catch (Throwable ignored) { }
        }
        wallpaperColorsListener = null;
        wallpaperConfigurationCallbacks = null;
        wallpaperPrewarmGeneration = 0L;
        wallpaperContext = null;
        ExecutorService executor = wallpaperExecutor;
        wallpaperExecutor = null;
        if (executor != null) executor.shutdownNow();
        WallpaperCache old;
        synchronized (wallpaperCacheLock) {
            old = wallpaperCache;
            wallpaperCache = null;
        }
        if (old != null) old.retire();
        for (Map.Entry<SurfaceControl, Handler> entry :
                new ArrayList<>(pendingWallpaperSurfaces.entrySet())) {
            SurfaceControl surface = entry.getKey();
            Handler handler = entry.getValue();
            handler.post(() -> removeCandidateSurface(surface));
        }
        crossTaskGeneration.incrementAndGet();
        activeCrossTaskRunner = null;
        log(Log.INFO, TAG, "Stopped CrossTask wallpaper cache, reason=" + reason);
    }

    protected SurfaceControl createWallpaperBackgroundSurface(Object background, int width,
            int height) throws Exception {
        SurfaceControl.Builder builder = new SurfaceControl.Builder()
                .setName("AOSP back wallpaper background")
                .setBufferSize(width, height)
                .setFormat(PixelFormat.RGBX_8888)
                .setHidden(true);
        Object organizer = readField(background, "mRootTaskDisplayAreaOrganizer");
        invokeAnyMethod(organizer, "attachToDisplayArea",
                new Object[]{Integer.valueOf(0), builder});
        return builder.build();
    }

    protected boolean drawCachedWallpaperIntoSurface(Bitmap bitmap, SurfaceControl control) {
        Surface surface = new Surface(control);
        try {
            Canvas canvas = surface.lockCanvas(null);
            try {
                canvas.drawBitmap(bitmap, 0.0f, 0.0f,
                        new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            } finally {
                surface.unlockCanvasAndPost(canvas);
            }
            return true;
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to draw cached wallpaper buffer", throwable);
            return false;
        } finally {
            surface.release();
        }
    }

    protected void drawCenterCrop(Drawable drawable, Canvas canvas, int width, int height) {
        canvas.drawColor(Color.BLACK);
        int intrinsicWidth = drawable.getIntrinsicWidth();
        int intrinsicHeight = drawable.getIntrinsicHeight();
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            drawable.setBounds(0, 0, width, height);
        } else {
            float scale = Math.max(width / (float) intrinsicWidth,
                    height / (float) intrinsicHeight);
            int scaledWidth = Math.round(intrinsicWidth * scale);
            int scaledHeight = Math.round(intrinsicHeight * scale);
            int left = (width - scaledWidth) / 2;
            int top = (height - scaledHeight) / 2;
            drawable.setBounds(left, top, left + scaledWidth, top + scaledHeight);
        }
        drawable.draw(canvas);
    }

    protected void blurBitmap(Bitmap bitmap, int radius, int iterations) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        int[] scratch = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < iterations; i++) {
            boxBlurHorizontal(pixels, scratch, width, height, radius);
            boxBlurVertical(scratch, pixels, width, height, radius);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    protected boolean wallpaperAppearsDark(Context context) {
        try {
            int hints = WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM).getColorHints();
            return (hints & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    protected void boxBlurHorizontal(int[] source, int[] target, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int a = 0, r = 0, g = 0, b = 0;
            for (int x = -radius; x <= radius; x++) {
                int color = source[row + clamp(x, 0, width - 1)];
                a += Color.alpha(color); r += Color.red(color);
                g += Color.green(color); b += Color.blue(color);
            }
            for (int x = 0; x < width; x++) {
                target[row + x] = Color.argb(a / window, r / window, g / window, b / window);
                int left = source[row + clamp(x - radius, 0, width - 1)];
                int right = source[row + clamp(x + radius + 1, 0, width - 1)];
                a += Color.alpha(right) - Color.alpha(left);
                r += Color.red(right) - Color.red(left);
                g += Color.green(right) - Color.green(left);
                b += Color.blue(right) - Color.blue(left);
            }
        }
    }

    protected void boxBlurVertical(int[] source, int[] target, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            int a = 0, r = 0, g = 0, b = 0;
            for (int y = -radius; y <= radius; y++) {
                int color = source[clamp(y, 0, height - 1) * width + x];
                a += Color.alpha(color); r += Color.red(color);
                g += Color.green(color); b += Color.blue(color);
            }
            for (int y = 0; y < height; y++) {
                target[y * width + x] = Color.argb(a / window, r / window, g / window, b / window);
                int top = source[clamp(y - radius, 0, height - 1) * width + x];
                int bottom = source[clamp(y + radius + 1, 0, height - 1) * width + x];
                a += Color.alpha(bottom) - Color.alpha(top);
                r += Color.red(bottom) - Color.red(top);
                g += Color.green(bottom) - Color.green(top);
                b += Color.blue(bottom) - Color.blue(top);
            }
        }
    }

    protected int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
