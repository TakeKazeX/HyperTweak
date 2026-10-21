package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

/**
 * Visual-only port of MiuiHome's {@code GestureBackArrowView} water-drop back indicator,
 * rendered inside SystemUI while the hidden native {@code BackPanelController} keeps
 * owning gesture state, commit thresholds, and haptics.
 *
 * <p>The two bitmaps come from the live {@code com.miui.home} package resources so the
 * appearance stays pixel-identical to the stock HyperOS indicator. All entry points must
 * run on the main Looper except {@link #detach()}, which reposts itself.
 */
@SuppressLint("DiscouragedApi")
final class MiuiStyleBackArrowOverlay {

    interface Logger {
        void log(int priority, String message, Throwable throwable);
    }

    /**
     * Fired when the arrow finishes its 100ms fade-in, matching Xiaomi's
     * ready-back haptic timing in GestureBackArrowView.
     */
    interface HapticListener {
        void onArrowReady();
    }

    static final int EDGE_LEFT = 0;
    static final int EDGE_RIGHT = 1;

    private static final String MIUI_HOME_PACKAGE = "com.miui.home";
    private static final String RESOURCE_BACKGROUND = "gesture_back_background";
    private static final String RESOURCE_ARROW = "gesture_back_arrow";
    // Maximum laterFriction(x, 0.8, 2.0, 0.5) value is 0.8 + (2.0 / 3.0) * 0.5.
    private static final float MAX_BACKGROUND_SCALE = 1.14f;
    // WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL (@hide),
    // FIRST_SYSTEM_WINDOW + 24 — the same window type the native BackPanel uses.
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;

    private final Context context;
    private final Logger logger;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ArrowView view;
    private int attachedEdge = -1;
    private boolean windowAdded;
    private boolean attachedMaterialRequested;

    private Bitmap leftBackground;
    private Bitmap rightBackground;
    private Bitmap arrow;
    private Bitmap leftMaterialMask;
    private Bitmap rightMaterialMask;
    private Bitmap leftMaterialStroke;
    private Bitmap rightMaterialStroke;
    private boolean materialRequested;
    private boolean materialMaskFailureLogged;
    private int loadedDensityDpi = -1;
    private int loadedUiNightMode = -1;
    private boolean resourceFailureLogged;
    private HapticListener hapticListener;
    private boolean hapticsEnabledForGesture;

    MiuiStyleBackArrowOverlay(Context context, Logger logger) {
        this.context = context;
        this.logger = logger;
    }

    void setHapticListener(HapticListener listener) {
        hapticListener = listener;
    }

    /**
     * Decided per gesture at ACTION_DOWN; off keeps this overlay fully silent.
     */
    void setHapticsForGesture(boolean enabled) {
        hapticsEnabledForGesture = enabled;
    }

    /**
     * Whether the ready-back haptic already played for the current arrow show.
     */
    boolean isArrowFeedbackDone() {
        ArrowView current = view;
        return current == null || current.arrowFeedbackDone;
    }

    /**
     * Blocks a late fade-in completion from vibrating after the gesture ended.
     */
    void markArrowFeedbackDone() {
        ArrowView current = view;
        if (current != null) {
            current.arrowFeedbackDone = true;
        }
    }

    private void dispatchArrowReady() {
        HapticListener listener = hapticListener;
        if (hapticsEnabledForGesture && listener != null) {
            listener.onArrowReady();
        }
    }

    /**
     * Loads MiuiHome resources and (re)attaches the overlay window for the given edge.
     * Returns false when the HyperOS skin cannot be shown; callers must then leave the
     * native AOSP panel visible.
     */
    boolean prepare(int edge, boolean blurEnabled) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }
        if (!loadResources()) {
            return false;
        }
        materialRequested = blurEnabled && loadMaterialMasks();
        try {
            ensureWindow(edge);
            return view != null && windowAdded;
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "Failed to attach HyperOS-style back indicator window",
                    throwable);
            return false;
        }
    }

    void onGestureStart(float rawY, int edge) {
        ArrowView current = view;
        if (current == null) {
            return;
        }
        int[] location = new int[2];
        try {
            current.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            location[1] = 0;
        }
        current.onSwipeStart(rawY - location[1], edge == EDGE_LEFT
                ? ArrowView.POSITION_LEFT : ArrowView.POSITION_RIGHT);
    }

    void onGestureProgress(float offsetPx, boolean arrowLit) {
        ArrowView current = view;
        if (current != null) {
            current.onSwipeProgress(offsetPx, arrowLit);
        }
    }

    /**
     * Pass a negative offset to retract from the last drawn scale (cancellation).
     */
    void onGestureEnd(float offsetPx) {
        ArrowView current = view;
        if (current != null) {
            current.onSwipeStop(offsetPx);
        }
    }

    /**
     * Safe from any thread; used by driver detach and hot reload teardown.
     */
    void detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::detach);
            return;
        }
        ArrowView current = view;
        view = null;
        attachedEdge = -1;
        attachedMaterialRequested = false;
        boolean added = windowAdded;
        windowAdded = false;
        if (current != null) {
            current.cancelAnimations();
            if (added) {
                try {
                    WindowManager windowManager =
                            context.getSystemService(WindowManager.class);
                    windowManager.removeViewImmediate(current);
                } catch (Throwable throwable) {
                    logger.log(Log.WARN,
                            "Failed to remove HyperOS-style back indicator window",
                            throwable);
                }
            }
        }
    }

    private void ensureWindow(int edge) {
        if (view != null && windowAdded && attachedEdge == edge
                && attachedMaterialRequested == materialRequested) {
            // prepare() is called for every ACTION_DOWN.  A cancelled gesture may
            // still be running its 100ms retract animation, so clear the reused
            // view before the next stream gets a chance to draw it.
            view.resetForNewGesture(edge == EDGE_LEFT
                    ? ArrowView.POSITION_LEFT : ArrowView.POSITION_RIGHT);
            return;
        }
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        if (view == null || !windowAdded) {
            detachDanglingView(windowManager);
            attachWindow(windowManager, edge);
            return;
        }
        // Recreate the ViewRoot when the edge or material sampling width changes.
        // Relayout is asynchronous and can display an old buffer for one frame;
        // a fresh window starts with a transparent view and the required extent.
        ArrowView oldView = view;
        oldView.resetForNewGesture(edge == EDGE_LEFT
                ? ArrowView.POSITION_LEFT : ArrowView.POSITION_RIGHT);
        oldView.setVisibility(View.INVISIBLE);
        try {
            windowManager.removeViewImmediate(oldView);
        } catch (Throwable throwable) {
            try {
                oldView.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {
            }
            logger.log(Log.WARN,
                    "Failed to replace HyperOS-style back indicator window",
                    throwable);
            throw new IllegalStateException(
                    "Unable to remove the previous HyperOS indicator window", throwable);
        }
        view = null;
        windowAdded = false;
        attachedEdge = -1;
        attachedMaterialRequested = false;
        attachWindow(windowManager, edge);
    }

    private void attachWindow(WindowManager windowManager, int edge) {
        ArrowView created = new ArrowView(context, this);
        created.position = edge == EDGE_LEFT
                ? ArrowView.POSITION_LEFT : ArrowView.POSITION_RIGHT;
        WindowManager.LayoutParams params = buildLayoutParams(edge);
        windowManager.addView(created, params);
        view = created;
        windowAdded = true;
        attachedEdge = edge;
        attachedMaterialRequested = materialRequested;
        logger.log(Log.INFO, "Attached HyperOS-style back indicator window"
                + ", edge=" + edge
                + ", width=" + params.width, null);
    }

    private void detachDanglingView(WindowManager windowManager) {
        ArrowView dangling = view;
        view = null;
        if (dangling != null && windowAdded) {
            windowAdded = false;
            try {
                windowManager.removeViewImmediate(dangling);
            } catch (Throwable ignored) {
            }
        }
    }

    private WindowManager.LayoutParams buildLayoutParams(int edge) {
        // PassBlur's source coordinates and consumer extent belong to the window.
        // Use a display-wide sampler for material, while its owned child remains
        // clipped to the existing water-drop contour. The window receives no input.
        int width = materialRequested ? WindowManager.LayoutParams.MATCH_PARENT
                : Math.max(1, Math.round(
                        leftBackground.getWidth() * MAX_BACKGROUND_SCALE) + 1);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.setTitle("MiuiBackGestureHookIndicator");
        params.gravity = gravityForEdge(edge);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return params;
    }

    @SuppressLint("RtlHardcoded")
    private static int gravityForEdge(int edge) {
        return Gravity.TOP | (edge == EDGE_LEFT ? Gravity.LEFT : Gravity.RIGHT);
    }

    private boolean loadResources() {
        Configuration configuration = context.getResources().getConfiguration();
        int densityDpi = configuration.densityDpi;
        int uiNightMode = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (leftBackground != null && arrow != null
                && densityDpi == loadedDensityDpi && uiNightMode == loadedUiNightMode) {
            return true;
        }
        try {
            Context homeContext = context.createPackageContext(MIUI_HOME_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY);
            Resources homeResources = homeContext.getResources();
            int backgroundId = homeResources.getIdentifier(
                    RESOURCE_BACKGROUND, "drawable", MIUI_HOME_PACKAGE);
            int arrowId = homeResources.getIdentifier(
                    RESOURCE_ARROW, "drawable", MIUI_HOME_PACKAGE);
            if (backgroundId == 0 || arrowId == 0) {
                throw new Resources.NotFoundException(
                        "backgroundId=" + backgroundId + ", arrowId=" + arrowId);
            }
            Bitmap loadedBackground =
                    BitmapFactory.decodeResource(homeResources, backgroundId);
            Bitmap loadedArrow = BitmapFactory.decodeResource(homeResources, arrowId);
            if (loadedBackground == null || loadedArrow == null) {
                throw new IllegalStateException("decoded background="
                        + loadedBackground + ", arrow=" + loadedArrow);
            }
            Matrix rotate = new Matrix();
            rotate.postRotate(180.0f);
            leftBackground = loadedBackground;
            rightBackground = Bitmap.createBitmap(loadedBackground, 0, 0,
                    loadedBackground.getWidth(), loadedBackground.getHeight(),
                    rotate, true);
            arrow = loadedArrow;
            leftMaterialMask = null;
            rightMaterialMask = null;
            leftMaterialStroke = null;
            rightMaterialStroke = null;
            loadedDensityDpi = densityDpi;
            loadedUiNightMode = uiNightMode;
            resourceFailureLogged = false;
            logger.log(Log.INFO, "Loaded HyperOS back indicator resources from MiuiHome"
                    + ", background=" + loadedBackground.getWidth()
                    + "x" + loadedBackground.getHeight()
                    + ", arrow=" + loadedArrow.getWidth()
                    + "x" + loadedArrow.getHeight()
                    + ", densityDpi=" + densityDpi, null);
            return true;
        } catch (Throwable throwable) {
            if (!resourceFailureLogged) {
                resourceFailureLogged = true;
                logger.log(Log.WARN, "HyperOS back indicator resources unavailable"
                        + ", policy=keepNativeAospPanel", throwable);
            }
            return false;
        }
    }

    private boolean loadMaterialMasks() {
        if (leftMaterialMask != null && rightMaterialMask != null
                && leftMaterialStroke != null && rightMaterialStroke != null) {
            return true;
        }
        try {
            // Keep the resource's fractional edge coverage. Thresholding it into a
            // Region loses antialiasing and leaves a staircase around the material.
            int width = leftBackground.getWidth();
            int height = leftBackground.getHeight();
            if ((long) width * height > 2_000_000L) {
                throw new IllegalStateException("Indicator mask exceeds bounded bitmap size");
            }
            int[] pixels = new int[width * height];
            leftBackground.getPixels(pixels, 0, width, 0, 0, width, height);
            int[] strokePixels = pixels.clone();
            MiuiIndicatorMask.isolateWhiteStroke(strokePixels);
            Bitmap leftStroke = Bitmap.createBitmap(
                    strokePixels, width, height, Bitmap.Config.ARGB_8888);
            MiuiIndicatorMask.normalizeAlpha(pixels);
            Bitmap left = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            Matrix mirror = new Matrix();
            mirror.setScale(-1.0f, -1.0f);
            mirror.postTranslate(width, height);
            Bitmap right = Bitmap.createBitmap(left, 0, 0, width, height, mirror, true);
            Bitmap rightStroke = Bitmap.createBitmap(
                    leftStroke, 0, 0, width, height, mirror, true);
            leftMaterialMask = left;
            rightMaterialMask = right;
            leftMaterialStroke = leftStroke;
            rightMaterialStroke = rightStroke;
            materialMaskFailureLogged = false;
            return true;
        } catch (Throwable throwable) {
            if (!materialMaskFailureLogged) {
                materialMaskFailureLogged = true;
                logger.log(Log.WARN,
                        "HyperOS indicator material mask unavailable; keeping bitmap",
                        throwable);
            }
            return false;
        }
    }

    /**
     * Drawing port of {@code com.miui.home.recents.GestureBackArrowView}. The background
     * water-drop scales with {@code laterFriction} damping and the arrow fades in/out with
     * the original 100ms/50ms cubic-ease animations. Haptics stay with the hidden native
     * BackPanelController, so this view never vibrates.
     */
    private static final class ArrowView extends FrameLayout {
        static final int POSITION_LEFT = 0;
        static final int POSITION_RIGHT = 1;

        private static final Interpolator CUBIC_EASE_OUT_INTERPOLATOR =
                new DecelerateInterpolator(1.5f);
        private static final Interpolator QUAD_EASE_OUT_INTERPOLATOR =
                new DecelerateInterpolator();

        private final MiuiStyleBackArrowOverlay overlay;
        private final Paint backgroundPaint;
        private final Paint arrowPaint;
        private final Rect backgroundDstRect = new Rect();
        private final Rect arrowDstRect = new Rect();
        private final MaterialView materialView;

        private ValueAnimator arrowAnimator;
        private ValueAnimator finishAnimator;
        private int position = POSITION_LEFT;
        private float backgroundScale;
        private float startY;
        private float expectBackHeight;
        private boolean arrowLit;
        private boolean arrowShown;
        private boolean iconNeedDraw;
        private int currentArrowAlpha;
        boolean arrowFeedbackDone;

        ArrowView(Context context, MiuiStyleBackArrowOverlay overlay) {
            super(context);
            this.overlay = overlay;
            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            backgroundPaint.setFilterBitmap(true);
            backgroundPaint.setDither(true);
            arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            arrowPaint.setFilterBitmap(true);
            arrowPaint.setDither(true);
            arrowPaint.setAlpha(0);
            // Only this child receives Xiaomi blur properties. The stock white
            // edge, arrow and bitmap fallback stay on the unblurred parent.
            materialView = new MaterialView(context, overlay);
            addView(materialView, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        // Mirrors BackGestureUtils.convertOffset(): linear for the first 80% of
        // offset/360 and cubic-polynomial friction beyond it, returning the 0..~1.13
        // background scale directly (Xiaomi divides its 0..20 result by 20).
        static float convertOffsetToScale(float offset) {
            if (offset < 0.0f) {
                return 0.0f;
            }
            return laterFriction(offset / 360.0f);
        }

        private static float laterFriction(float value) {
            if (value < (float) 0.8) {
                return value;
            }
            return (float) 0.8
                    + afterFrictionValue(value - (float) 0.8)
                    * (float) 0.5;
        }

        private static float afterFrictionValue(float value) {
            int sign = value >= 0.0f ? 1 : -1;
            float normalized = (float) Math.min(Math.abs(value) / (float) 2.0, 1.0d);
            float squared = normalized * normalized;
            return sign * ((squared * normalized / 3) - squared + normalized) * (float) 2.0;
        }

        void onSwipeStart(float localY, int newPosition) {
            resetForNewGesture(newPosition);
            Bitmap background = currentBackground();
            expectBackHeight = background == null ? 0.0f : background.getHeight();
            // Matches Xiaomi's no-explicit-height branch in setStartLocation().
            startY = localY - 20.0f;
            backgroundScale = 0.0f;
            invalidate();
        }

        /**
         * Clears visual state at the start of every input stream, before the
         * 20px horizontal intent threshold is reached.  This is separate from
         * onSwipeStart() because the latter also calculates the gesture's Y
         * origin and must remain threshold-gated.
         */
        void resetForNewGesture(int newPosition) {
            materialView.resetForNewGesture();
            if (arrowAnimator != null) {
                arrowAnimator.cancel();
            }
            cancelFinishAnimation();
            position = newPosition;
            backgroundScale = 0.0f;
            arrowPaint.setAlpha(0);
            currentArrowAlpha = 0;
            arrowShown = false;
            iconNeedDraw = false;
            arrowLit = false;
            invalidate();
        }

        void onSwipeProgress(float offset, boolean lit) {
            arrowLit = lit;
            backgroundScale = convertOffsetToScale(offset);
            invalidate();
        }

        void onSwipeStop(float releaseOffset) {
            arrowLit = false;
            if (arrowAnimator != null) {
                arrowAnimator.cancel();
            }
            float startScale = releaseOffset >= 0.0f
                    ? convertOffsetToScale(releaseOffset) : backgroundScale;
            backgroundScale = startScale;
            ValueAnimator animator = ValueAnimator.ofFloat(startScale, 0.0f);
            animator.setDuration(100L);
            animator.setInterpolator(QUAD_EASE_OUT_INTERPOLATOR);
            animator.addUpdateListener(animation -> {
                backgroundScale = (Float) animation.getAnimatedValue();
                long playTime = animation.getCurrentPlayTime();
                if (playTime > 0 && playTime < 50) {
                    iconNeedDraw = false;
                    arrowShown = false;
                }
                invalidate();
            });
            finishAnimator = animator;
            animator.start();
        }

        void cancelAnimations() {
            if (arrowAnimator != null) {
                arrowAnimator.cancel();
            }
            cancelFinishAnimation();
            materialView.releaseMaterial();
        }

        private void cancelFinishAnimation() {
            if (finishAnimator != null) {
                finishAnimator.end();
                finishAnimator = null;
            }
        }

        private Bitmap currentBackground() {
            return position == POSITION_LEFT
                    ? overlay.leftBackground : overlay.rightBackground;
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            Bitmap background = currentBackground();
            Bitmap arrowBitmap = overlay.arrow;
            if (background == null || arrowBitmap == null) {
                return;
            }
            float scaledWidth = background.getWidth() * backgroundScale;
            int arrowWidth = arrowBitmap.getWidth();
            int backgroundLeft;
            int backgroundRight;
            int arrowLeft;
            int arrowRight;
            if (position == POSITION_LEFT) {
                backgroundLeft = 0;
                backgroundRight = (int) scaledWidth;
                arrowLeft = (int) ((scaledWidth - arrowWidth) / 2.0f);
                arrowRight = (int) ((scaledWidth + arrowWidth) / 2.0f);
            } else {
                int width = getWidth();
                backgroundLeft = width - (int) scaledWidth;
                backgroundRight = width;
                arrowLeft = width - (int) ((scaledWidth + arrowWidth) / 2.0f);
                arrowRight = width - (int) ((scaledWidth - arrowWidth) / 2.0f);
            }
            backgroundDstRect.set(backgroundLeft,
                    (int) (startY - (expectBackHeight / 2.0f)),
                    backgroundRight,
                    (int) (startY + (expectBackHeight / 2.0f)));
            Bitmap materialMask = position == POSITION_LEFT
                    ? overlay.leftMaterialMask : overlay.rightMaterialMask;
            boolean materialVisible = materialView.updateMaterial(
                    materialMask, backgroundDstRect, overlay.materialRequested);
            super.dispatchDraw(canvas);
            if (materialVisible) {
                Bitmap stroke = position == POSITION_LEFT
                        ? overlay.leftMaterialStroke : overlay.rightMaterialStroke;
                canvas.drawBitmap(stroke, null, backgroundDstRect, backgroundPaint);
            } else {
                canvas.drawBitmap(background, null, backgroundDstRect, backgroundPaint);
            }
            if (arrowLit) {
                if (!arrowShown) {
                    iconNeedDraw = true;
                    startArrowAnimating(true, 100);
                    arrowShown = true;
                }
            } else if (arrowShown) {
                startArrowAnimating(false, 50);
                arrowShown = false;
            }
            if (iconNeedDraw && backgroundScale > 0.1d && arrowLit) {
                int arrowHeight = arrowBitmap.getHeight();
                arrowDstRect.set(arrowLeft,
                        (int) (startY - (arrowHeight / 2.0f)),
                        arrowRight,
                        (int) (startY + (arrowHeight / 2.0f)));
                canvas.drawBitmap(arrowBitmap, null, arrowDstRect, arrowPaint);
            }
        }

        private void startArrowAnimating(boolean show, int duration) {
            if (arrowAnimator != null) {
                arrowAnimator.cancel();
            }
            arrowFeedbackDone = false;
            ValueAnimator animator = ValueAnimator.ofInt(
                    currentArrowAlpha, show ? 255 : 0);
            animator.setDuration(duration);
            animator.setInterpolator(CUBIC_EASE_OUT_INTERPOLATOR);
            animator.addUpdateListener(animation -> {
                int alpha = (Integer) animation.getAnimatedValue();
                arrowPaint.setAlpha(alpha);
                invalidate();
                if (alpha == 0 && !show) {
                    iconNeedDraw = false;
                }
                currentArrowAlpha = alpha;
            });
            if (show) {
                // Xiaomi's AnimationSuccessListener: the ready-back haptic fires only
                // when the fade-in completes uncancelled and has not fired yet.
                animator.addListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!cancelled && !arrowFeedbackDone) {
                            arrowFeedbackDone = true;
                            overlay.dispatchArrowReady();
                        }
                    }
                });
            }
            arrowAnimator = animator;
            animator.start();
        }
    }

    private static final class MaterialView extends FrameLayout {
        private final MiuiStyleBackArrowOverlay overlay;
        private final View background;
        private final Paint materialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect lastBounds = new Rect();
        private final Matrix shapeMatrix = new Matrix();
        private MiuiIndicatorMaterial.Api api;
        private boolean apiResolved;
        private boolean failedForGesture;
        private boolean failureLogged;
        private boolean configured;
        private Bitmap lastSourceMask;
        private BitmapShader maskShader;
        private Path samplingShape;

        MaterialView(Context context, MiuiStyleBackArrowOverlay overlay) {
            super(context);
            this.overlay = overlay;
            // Xiaomi's indicator keeps a dark surface and a white arrow in both themes.
            materialPaint.setColor(0x10000000);
            setVisibility(INVISIBLE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            // The mask belongs to this unblurred container. Xiaomi may draw its
            // background after a RenderEffect installed on the blur node itself.
            background = new View(context) {
                @Override
                protected void onDraw(@NonNull Canvas canvas) {
                    super.onDraw(canvas);
                    if (configured && !lastBounds.isEmpty()) {
                        canvas.drawRect(lastBounds, materialPaint);
                    }
                }
            };
            background.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(background, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        boolean updateMaterial(Bitmap sourceMask, Rect bounds,
                boolean requested) {
            if (!requested || sourceMask == null || bounds.isEmpty()) {
                releaseMaterial();
                return false;
            }
            boolean attached = isAttachedToWindow();
            boolean hardware = isHardwareAccelerated();
            if (failedForGesture || !attached || !hardware) {
                return false;
            }
            try {
                if (!apiResolved) {
                    apiResolved = true;
                    api = MiuiIndicatorMaterial.resolve();
                }
                if (api == null) {
                    failedForGesture = true;
                    return false;
                }
                boolean shapeChanged = maskShader == null
                        || lastSourceMask != sourceMask || !lastBounds.equals(bounds);
                if (shapeChanged) {
                    shapeMatrix.setRectToRect(
                            new RectF(0, 0, sourceMask.getWidth(), sourceMask.getHeight()),
                            new RectF(bounds), Matrix.ScaleToFit.FILL);
                    if (maskShader == null || lastSourceMask != sourceMask) {
                        maskShader = new BitmapShader(sourceMask,
                                Shader.TileMode.DECAL, Shader.TileMode.DECAL);
                        maskShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                    }
                    maskShader.setLocalMatrix(shapeMatrix);
                    setRenderEffect(RenderEffect.createBlendModeEffect(
                            RenderEffect.createOffsetEffect(0, 0),
                            RenderEffect.createShaderEffect(maskShader), BlendMode.DST_IN));
                    // Native sampling stays rectangular. The container masks the
                    // complete child output, including Xiaomi's background draw.
                    samplingShape = new Path();
                    samplingShape.addRect(new RectF(bounds), Path.Direction.CW);
                    lastSourceMask = sourceMask;
                    lastBounds.set(bounds);
                }
                if (!configured) {
                    int radius = Math.min(400, Math.max(1, Math.round(
                            50.0f * getResources().getDisplayMetrics().density)));
                    configured = api.enable(background, samplingShape, radius,
                            0x99000000);
                    if (!configured) {
                        failedForGesture = true;
                        overlay.logger.log(Log.INFO,
                                "HyperOS indicator material unsupported by ViewRootImpl", null);
                        return false;
                    }
                    failureLogged = false;
                } else if (shapeChanged) {
                    api.updatePath(background, samplingShape);
                }
                setVisibility(VISIBLE);
                if (shapeChanged) {
                    background.invalidate();
                    invalidate();
                }
                return true;
            } catch (Throwable throwable) {
                failedForGesture = true;
                // Hide this node even if a partial native rollback cannot complete.
                // The parent's unchanged bitmap fallback never receives blur properties.
                releaseMaterial();
                if (!failureLogged) {
                    failureLogged = true;
                    overlay.logger.log(Log.WARN,
                            "HyperOS indicator material rejected; keeping bitmap", throwable);
                }
                return false;
            }
        }

        void resetForNewGesture() {
            releaseMaterial();
            failedForGesture = false;
        }

        void releaseMaterial() {
            setVisibility(INVISIBLE);
            setRenderEffect(null);
            if (configured) {
                configured = false;
                try {
                    api.clear(background);
                } catch (Throwable throwable) {
                    if (!failureLogged) {
                        failureLogged = true;
                        overlay.logger.log(Log.WARN,
                                "Failed to clear hidden indicator material", throwable);
                    }
                }
            }
            samplingShape = null;
            lastSourceMask = null;
            maskShader = null;
            lastBounds.setEmpty();
        }

        @Override
        protected void onDetachedFromWindow() {
            releaseMaterial();
            super.onDetachedFromWindow();
        }
    }
}
