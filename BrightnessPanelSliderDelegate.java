package miui.systemui.controlcenter.panel.secondary.brightness;

import android.animation.AnimatorSet;
import android.graphics.Outline;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.SeekBar;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.lifecycle.Lifecycle;
import b1.f;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.miui.controlcenter.BrightnessControllerBase;
import kotlin.jvm.internal.h;
import kotlin.jvm.internal.o;
import miui.systemui.animation.FolmeKt;
import miui.systemui.animation.drawable.SVGUtils;
import miui.systemui.animation.drawable.SVGUtilsExt;
import miui.systemui.animation.drawable.VectorDrawableSetParams;
import miui.systemui.brightness.BrightnessWindowManager;
import miui.systemui.controlcenter.ConfigUtils;
import miui.systemui.controlcenter.R;
import miui.systemui.controlcenter.dagger.ControlCenterScope;
import miui.systemui.controlcenter.dagger.qualifiers.Qualifiers;
import miui.systemui.controlcenter.databinding.BrightnessPanelBinding;
import miui.systemui.controlcenter.databinding.ControlCenterSecondaryBinding;
import miui.systemui.controlcenter.databinding.ToggleSliderItemViewBinding;
import miui.systemui.controlcenter.events.ControlCenterEventTracker;
import miui.systemui.controlcenter.events.ControlCenterScenarioTracker;
import miui.systemui.controlcenter.panel.SecondaryPanelRouter;
import miui.systemui.controlcenter.panel.main.volume.SliderDragAnimator;
import miui.systemui.controlcenter.panel.secondary.BrightnessPanelParams;
import miui.systemui.controlcenter.panel.secondary.SecondaryPanelDelegateBase;
import miui.systemui.controlcenter.utils.ControlCenterUtils;
import miui.systemui.controlcenter.widget.AnimateColorView;
import miui.systemui.controlcenter.widget.NoTransformTouchFrameLayout;
import miui.systemui.controlcenter.widget.SliderConfig;
import miui.systemui.controlcenter.widget.ToggleSliderView;
import miui.systemui.controlcenter.widget.VerticalSeekBar;
import miui.systemui.controlcenter.windowview.GestureDispatcher;
import miui.systemui.controls.ColorUtils;
import miui.systemui.dagger.qualifiers.Background;
import miui.systemui.dagger.qualifiers.Main;
import miui.systemui.util.CommonUtils;
import miui.systemui.util.HapticFeedback;
import miui.systemui.util.KeyboardShortcutKeyController;
import miui.systemui.util.MiBlurCompat;
import miui.systemui.util.MiuiColorBlendToken;
import miui.systemui.util.MiuiMathUtils;
import miui.systemui.util.SLIDER_PROGRESS;
import miui.systemui.widget.View;
import miuix.view.HapticFeedbackConstants;
import systemui.plugin.eventtracking.EventTracker;

/* JADX INFO: loaded from: classes.dex */
@ControlCenterScope
public final class BrightnessPanelSliderDelegate extends SecondaryPanelDelegateBase<BrightnessPanelParams> {
    public static final Companion Companion = new Companion(null);
    private static final String TAG = "BrightnessPanelSliderController";
    private final ActivityStarter activityStarter;
    private int afterValue;
    private VectorDrawableSetParams animateIconParams;
    private final Handler bgHandler;
    private final BrightnessPanelBinding binding;
    private final BrightnessControllerBase brightnessController;
    private final BrightnessWindowManager brightnessWindowManager;
    private SliderConfig.ColorState currentColorState;
    private final GestureDispatcher gestureDispatcher;
    private final HapticFeedback hapticFeedback;
    private final KeyboardShortcutKeyController keyboardShortcutKeyController;
    private final BrightnessPanelSliderDelegate$keyboardShortcutKeyListener$1 keyboardShortcutKeyListener;
    private boolean listening;
    private boolean needCallStopTrackingTouchMethod;
    private float outlineRadius;
    private int preValue;
    private int pressCount;
    private float progressRadius;
    private final ControlCenterSecondaryBinding secondaryBinding;
    private final D0.a secondaryPanelRouter;
    private final BrightnessPanelSliderDelegate$seekBarListener$1 seekBarListener;
    private final SliderDragAnimator sliderDragAnimator;
    private int sliderHeight;
    private int sliderWidth;
    private final Handler uiHandler;
    private final Lifecycle windowViewLifecycle;

    public static final class Companion {
        public /* synthetic */ Companion(h hVar) {
            this();
        }

        private Companion() {
        }
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    /* JADX WARN: Type inference failed for: r2v1, types: [miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate$seekBarListener$1] */
    /* JADX WARN: Type inference failed for: r2v2, types: [miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate$keyboardShortcutKeyListener$1] */
    public BrightnessPanelSliderDelegate(@Main Handler uiHandler, @Background Handler bgHandler, @Qualifiers.ControlCenter Lifecycle windowViewLifecycle, ControlCenterSecondaryBinding secondaryBinding, BrightnessPanelBinding binding, GestureDispatcher gestureDispatcher, ActivityStarter activityStarter, BrightnessControllerBase brightnessController, HapticFeedback hapticFeedback, KeyboardShortcutKeyController keyboardShortcutKeyController, D0.a secondaryPanelRouter, BrightnessWindowManager brightnessWindowManager) {
        super(secondaryBinding);
        o.g(uiHandler, "uiHandler");
        o.g(bgHandler, "bgHandler");
        o.g(windowViewLifecycle, "windowViewLifecycle");
        o.g(secondaryBinding, "secondaryBinding");
        o.g(binding, "binding");
        o.g(gestureDispatcher, "gestureDispatcher");
        o.g(activityStarter, "activityStarter");
        o.g(brightnessController, "brightnessController");
        o.g(hapticFeedback, "hapticFeedback");
        o.g(keyboardShortcutKeyController, "keyboardShortcutKeyController");
        o.g(secondaryPanelRouter, "secondaryPanelRouter");
        o.g(brightnessWindowManager, "brightnessWindowManager");
        this.uiHandler = uiHandler;
        this.bgHandler = bgHandler;
        this.windowViewLifecycle = windowViewLifecycle;
        this.secondaryBinding = secondaryBinding;
        this.binding = binding;
        this.gestureDispatcher = gestureDispatcher;
        this.activityStarter = activityStarter;
        this.brightnessController = brightnessController;
        this.hapticFeedback = hapticFeedback;
        this.keyboardShortcutKeyController = keyboardShortcutKeyController;
        this.secondaryPanelRouter = secondaryPanelRouter;
        this.brightnessWindowManager = brightnessWindowManager;
        this.animateIconParams = new VectorDrawableSetParams();
        this.sliderDragAnimator = new SliderDragAnimator(null, null, this, uiHandler, windowViewLifecycle);
        this.seekBarListener = new SeekBar.OnSeekBarChangeListener() { // from class: miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate$seekBarListener$1
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int i2, boolean z2) {
                BrightnessPanelSliderDelegate.updateIconProgress$default(this.this$0, false, 1, null);
                if (z2) {
                    this.this$0.needCallStopTrackingTouchMethod = true;
                    if (seekBar != null) {
                        if (i2 <= seekBar.getMin() || i2 >= seekBar.getMax()) {
                            if (HapticFeedback.Companion.getIS_SUPPORT_LINEAR_MOTOR_VIBRATE()) {
                                HapticFeedback.postHapticFeedback$default(this.this$0.hapticFeedback, "mesh_heavy", false, 2, null);
                            } else {
                                seekBar.performHapticFeedback(1);
                            }
                        }
                    }
                }
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
                ControlCenterScenarioTracker.setControlCenterScenarioState(497L, true);
                this.this$0.preValue = seekBar != null ? seekBar.getProgress() : 0;
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
                this.this$0.needCallStopTrackingTouchMethod = false;
                this.this$0.afterValue = seekBar != null ? seekBar.getProgress() : 0;
                ControlCenterScenarioTracker.setControlCenterScenarioState(497L, false);
            }
        };
        this.keyboardShortcutKeyListener = new KeyboardShortcutKeyController.KeyboardShortcutKeyListener() { // from class: miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate$keyboardShortcutKeyListener$1
            @Override // miui.systemui.util.KeyboardShortcutKeyController.KeyboardShortcutKeyListener
            public void onBrightnessShortcutKeyReceive(boolean z2, boolean z3) {
                if (this.this$0.brightnessWindowManager.isShown() || !((SecondaryPanelRouter) this.this$0.secondaryPanelRouter.get()).getInBrightnessPanel()) {
                    return;
                }
                this.this$0.changeBrightness(z2, z3);
            }
        };
        this.currentColorState = SliderConfig.ColorState.HIGHLIGHT;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void changeBrightness(boolean z2, boolean z3) {
        int max = getVSlider().getMax();
        int progress = getVSlider().getProgress();
        int i2 = max / 14;
        if (!z2) {
            i2 = -i2;
        }
        int i3 = f.i(i2 + progress, 0, max);
        performKeyHapticFeedback(i3, getVSlider());
        if (!z3) {
            this.pressCount = 0;
            this.sliderDragAnimator.keyUpEvent(getVSlider(), SliderDragAnimator.TYPE_BRIGHTNESS_PANEL);
        } else {
            this.pressCount++;
            FolmeKt.getFolme(getVSlider()).to(SLIDER_PROGRESS.INSTANCE, Integer.valueOf(i3));
            this.sliderDragAnimator.keyDownEvent(progress, z2, getVSlider(), SliderDragAnimator.TYPE_BRIGHTNESS_PANEL);
        }
    }

    private final ToggleSliderItemViewBinding getSliderBinding() {
        ToggleSliderItemViewBinding toggleSlider = this.binding.toggleSlider;
        o.f(toggleSlider, "toggleSlider");
        return toggleSlider;
    }

    private final ToggleSliderView getVToggleSlider() {
        ToggleSliderView toggleSlider = getSliderBinding().toggleSlider;
        o.f(toggleSlider, "toggleSlider");
        return toggleSlider;
    }

    private final boolean isLongPress() {
        return this.pressCount > 2;
    }

    private final void performKeyHapticFeedback(int i2, VerticalSeekBar verticalSeekBar) {
        boolean z2 = i2 >= verticalSeekBar.getMax();
        boolean z3 = i2 <= verticalSeekBar.getMin();
        ViewParent parent = verticalSeekBar.getParent();
        final ViewGroup viewGroup = parent instanceof ViewGroup ? (ViewGroup) parent : null;
        if (viewGroup != null) {
            if (isLongPress() && !z2 && !z3) {
                this.bgHandler.post(new Runnable() { // from class: miui.systemui.controlcenter.panel.secondary.brightness.a
                    @Override // java.lang.Runnable
                    public final void run() {
                        BrightnessPanelSliderDelegate.performKeyHapticFeedback$lambda$4$lambda$3(this.f5669a, viewGroup);
                    }
                });
            } else if ((z3 || z2) && !isLongPress()) {
                this.hapticFeedback.postHapticFeedback(z2 ? 203 : 202, viewGroup, HapticFeedbackConstants.MIUI_MESH_HEAVY);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void performKeyHapticFeedback$lambda$4$lambda$3(BrightnessPanelSliderDelegate this$0, ViewGroup this_apply) {
        o.g(this$0, "this$0");
        o.g(this_apply, "$this_apply");
        this$0.hapticFeedback.postPerformHapticFeedback(this_apply, HapticFeedbackConstants.MIUI_MESH_LIGHT);
    }

    private final void updateBlendBlur() {
        if (!ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(getContext())) {
            MiBlurCompat.setPassWindowBlurEnabledCompat(getVToggleSlider(), false);
            MiBlurCompat.setMiBackgroundBlurModeCompat(getVToggleSlider(), 0);
            CommonUtils commonUtils = CommonUtils.INSTANCE;
            commonUtils.clearMiBlurBlendEffect(getVProgressBg());
            commonUtils.clearMiBlurBlendEffect(getVProgress());
            return;
        }
        MiBlurCompat.setPassWindowBlurEnabledCompat(getVToggleSlider(), false);
        MiBlurCompat.setMiBackgroundBlurModeCompat(getVToggleSlider(), 0);
        MiBlurCompat.setMiBackgroundBlurRadiusCompat(getVToggleSlider(), 0);
        MiBlurCompat.setMiViewBlurModeCompat(getVProgressBg(), 1);
        View vProgressBg = getVProgressBg();
        MiuiColorBlendToken miuiColorBlendToken = MiuiColorBlendToken.INSTANCE;
        MiBlurCompat.setMiBackgroundBlendColors(vProgressBg, miuiColorBlendToken.getCC_DETAIL_PANEL_SLIDER_BACKGROUND_BLEND_COLORS());
        MiBlurCompat.setMiViewBlurModeCompat(getVProgress(), 1);
        MiBlurCompat.setMiBackgroundBlendColors(getVProgress(), miuiColorBlendToken.getCC_DETAIL_PANEL_SLIDER_BLEND_COLORS());
    }

    private final void updateIcon() {
        AnimatorSet animatorSetCompat;
        AnimateColorView vIcon = getVIcon();
        if (!SVGUtilsExt.INSTANCE.getSupportVectorDrawableParams()) {
            vIcon.setImageResource(R.drawable.ic_brightness_slider);
            return;
        }
        vIcon.setImageResource(R.drawable.ic_brightness_slider_animate_icon);
        Drawable drawable = vIcon.getDrawable();
        AnimatedVectorDrawable animatedVectorDrawable = drawable instanceof AnimatedVectorDrawable ? (AnimatedVectorDrawable) drawable : null;
        if (animatedVectorDrawable == null || (animatorSetCompat = SVGUtilsExt.getAnimatorSetCompat(animatedVectorDrawable)) == null) {
            return;
        }
        this.animateIconParams.clearDrawableParams();
        SVGUtils.analyzeAnimator(animatorSetCompat, this.animateIconParams, vIcon.getContext().getColor(R.color.toggle_slider_icon_color));
        updateIconProgress(true);
    }

    private final void updateIconProgress(boolean z2) {
        if (SVGUtilsExt.INSTANCE.getSupportVectorDrawableParams()) {
            VerticalSeekBar vSlider = getVSlider();
            float fLerpInv = MiuiMathUtils.INSTANCE.lerpInv(vSlider.getMin(), vSlider.getMax(), vSlider.getValue());
            this.animateIconParams.setFraction(fLerpInv);
            AnimateColorView vIcon = getVIcon();
            if (fLerpInv >= 0.12f) {
                SliderConfig.ColorState colorState = this.currentColorState;
                SliderConfig.ColorState colorState2 = SliderConfig.ColorState.HIGHLIGHT;
                if (colorState != colorState2 || z2) {
                    MiuiColorBlendToken miuiColorBlendToken = MiuiColorBlendToken.INSTANCE;
                    vIcon.updateIconColor(miuiColorBlendToken.getCC_BRIGHTNESS_SLIDER_ICON_BLEND_COLORS_DEFAULT(), miuiColorBlendToken.getCC_BRIGHTNESS_SLIDER_ICON_BLEND_COLORS(), R.color.toggle_slider_icon_color, R.color.toggle_slider_brightness_icon_color, !z2);
                    this.currentColorState = colorState2;
                }
            } else {
                SliderConfig.ColorState colorState3 = this.currentColorState;
                SliderConfig.ColorState colorState4 = SliderConfig.ColorState.NORMAL;
                if (colorState3 != colorState4 || z2) {
                    MiuiColorBlendToken miuiColorBlendToken2 = MiuiColorBlendToken.INSTANCE;
                    vIcon.updateIconColor(miuiColorBlendToken2.getCC_BRIGHTNESS_SLIDER_ICON_BLEND_COLORS(), miuiColorBlendToken2.getCC_BRIGHTNESS_SLIDER_ICON_BLEND_COLORS_DEFAULT(), R.color.toggle_slider_brightness_icon_color, R.color.toggle_slider_icon_color, !z2);
                    this.currentColorState = colorState4;
                }
            }
            vIcon.invalidate();
        }
    }

    public static /* synthetic */ void updateIconProgress$default(BrightnessPanelSliderDelegate brightnessPanelSliderDelegate, boolean z2, int i2, Object obj) {
        if ((i2 & 1) != 0) {
            z2 = false;
        }
        brightnessPanelSliderDelegate.updateIconProgress(z2);
    }

    private final void updateLargeSize() {
        this.sliderWidth = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_width_large);
        this.sliderHeight = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_height_large);
        setProgressRadius(getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_clip_radius_large));
        setOutlineRadius(getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_radius));
        int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.brightness_panel_toggle_slider_margin_top_large);
        int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_icon_size_large);
        int dimensionPixelSize3 = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_icon_margin_bottom_large);
        CommonUtils commonUtils = CommonUtils.INSTANCE;
        CommonUtils.setMarginTop$default(commonUtils, getVToggleSlider(), dimensionPixelSize, false, 2, null);
        CommonUtils.setLayoutSize$default(commonUtils, getVToggleSlider(), this.sliderWidth, this.sliderHeight, false, 4, null);
        CommonUtils.setLayoutSize$default(commonUtils, getVSlider(), this.sliderHeight, this.sliderWidth, false, 4, null);
        CommonUtils.setLayoutSize$default(commonUtils, getVIcon(), dimensionPixelSize2, dimensionPixelSize2, false, 4, null);
        CommonUtils.setMarginBottom$default(commonUtils, getVIcon(), dimensionPixelSize3, false, 2, null);
    }

    private final void updateResources() {
        getVProgressBg().setBackground(AppCompatResources.getDrawable(getContext(), R.drawable.toggle_slider_detail_background));
        getVProgress().setBackground(AppCompatResources.getDrawable(getContext(), R.drawable.toggle_slider_detail_progress_background));
        updateBlendBlur();
        updateIcon();
    }

    private final void updateSize() {
        if (CommonUtils.INSTANCE.getInVerticalMode(getContext())) {
            updateLargeSize();
        } else {
            updateSmallSize();
        }
    }

    private final void updateSmallSize() {
        this.sliderWidth = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_width_small);
        this.sliderHeight = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_height_small);
        setProgressRadius(getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_clip_radius_small));
        setOutlineRadius(getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_radius));
        int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.brightness_panel_toggle_slider_margin_top_small);
        int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_icon_size_small);
        int dimensionPixelSize3 = getResources().getDimensionPixelSize(R.dimen.brightness_panel_slider_icon_margin_bottom_small);
        CommonUtils commonUtils = CommonUtils.INSTANCE;
        CommonUtils.setMarginTop$default(commonUtils, getVToggleSlider(), dimensionPixelSize, false, 2, null);
        CommonUtils.setLayoutSize$default(commonUtils, getVToggleSlider(), this.sliderWidth, this.sliderHeight, false, 4, null);
        CommonUtils.setLayoutSize$default(commonUtils, getVSlider(), this.sliderHeight, this.sliderWidth, false, 4, null);
        CommonUtils.setLayoutSize$default(commonUtils, getVIcon(), dimensionPixelSize2, dimensionPixelSize2, false, 4, null);
        CommonUtils.setMarginBottom$default(commonUtils, getVIcon(), dimensionPixelSize3, false, 2, null);
    }

    private final void updateText() {
        VerticalSeekBar vSlider = getVSlider();
        vSlider.setContentDescription(vSlider.getContext().getString(R.string.accessibility_brightness));
    }

    public final void animUpdateBlendBlur(float f2) {
        if (ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(getContext())) {
            View vProgressBg = getVProgressBg();
            MiuiColorBlendToken miuiColorBlendToken = MiuiColorBlendToken.INSTANCE;
            MiBlurCompat.setMiBackgroundBlendColors((android.view.View) vProgressBg, miuiColorBlendToken.getCC_TILE_DEFAULT_BLEND_COLORS(), miuiColorBlendToken.getCC_DETAIL_PANEL_SLIDER_BACKGROUND_BLEND_COLORS(), f2, true);
            MiBlurCompat.setMiBackgroundBlendColors((android.view.View) getVProgress(), miuiColorBlendToken.getCC_SLIDER_BLEND_COLORS(), miuiColorBlendToken.getCC_DETAIL_PANEL_SLIDER_BLEND_COLORS(), f2, true);
            return;
        }
        Drawable background = getVProgressBg().getBackground();
        GradientDrawable gradientDrawable = background instanceof GradientDrawable ? (GradientDrawable) background : null;
        if (gradientDrawable != null) {
            gradientDrawable.setColor(ColorUtils.INSTANCE.blendARGB(getResources().getColor(R.color.toggle_slider_progress_background_color), getResources().getColor(R.color.toggle_slider_detail_progress_background_color), f2));
        }
        Drawable background2 = getVProgress().getBackground();
        GradientDrawable gradientDrawable2 = background2 instanceof GradientDrawable ? (GradientDrawable) background2 : null;
        if (gradientDrawable2 != null) {
            gradientDrawable2.setColor(ColorUtils.INSTANCE.blendARGB(getResources().getColor(R.color.toggle_slider_progress_color), getResources().getColor(R.color.toggle_slider_detail_progress_color), f2));
        }
    }

    public final boolean getListening() {
        return this.listening;
    }

    public final float getOutlineRadius() {
        return this.outlineRadius;
    }

    public final float getProgressRadius() {
        return this.progressRadius;
    }

    public final AnimateColorView getVIcon() {
        AnimateColorView icon = getSliderBinding().icon;
        o.f(icon, "icon");
        return icon;
    }

    public final View getVProgress() {
        View progress = getSliderBinding().progress;
        o.f(progress, "progress");
        return progress;
    }

    public final View getVProgressBg() {
        View progressBg = getSliderBinding().progressBg;
        o.f(progressBg, "progressBg");
        return progressBg;
    }

    public final VerticalSeekBar getVSlider() {
        VerticalSeekBar slider = getSliderBinding().slider;
        o.f(slider, "slider");
        return slider;
    }

    public final NoTransformTouchFrameLayout getVToggleSliderInner() {
        NoTransformTouchFrameLayout toggleSliderInner = getSliderBinding().toggleSliderInner;
        o.f(toggleSliderInner, "toggleSliderInner");
        return toggleSliderInner;
    }

    @Override // miui.systemui.controlcenter.utils.ControlCenterViewController, miui.systemui.controlcenter.ConfigUtils.OnConfigChangeListener
    public void onConfigurationChanged(int i2) {
        super.onConfigurationChanged(i2);
        ConfigUtils configUtils = ConfigUtils.INSTANCE;
        boolean zOrientationChanged = configUtils.orientationChanged(i2);
        boolean zDimensionsChanged = configUtils.dimensionsChanged(i2);
        boolean zColorsChanged = configUtils.colorsChanged(i2);
        boolean zTextsChanged = configUtils.textsChanged(i2);
        if (zColorsChanged || zDimensionsChanged) {
            updateResources();
        }
        if (zDimensionsChanged || zOrientationChanged) {
            updateSize();
        }
        if (zTextsChanged) {
            updateText();
        }
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SecondaryPanelDelegateBase
    public void prepareHide() {
        super.prepareHide();
        getVSlider().resetDragAnim();
        if (this.afterValue != this.preValue) {
            ControlCenterEventTracker.Companion.trackBrightnessSeekbarAdjustEvent(true, EventTracker.Companion.getScreenType(getContext()), this.preValue, this.afterValue);
        }
    }

    public final void setListening(boolean z2) {
        if (this.listening == z2) {
            return;
        }
        this.listening = z2;
        if (z2) {
            getVSlider().setOnSeekBarChangeListener(this.seekBarListener);
            this.brightnessController.addToggleSlider(getVSlider());
            this.keyboardShortcutKeyController.addKeyboardShortcutKeyListener(this.keyboardShortcutKeyListener);
            this.sliderDragAnimator.onCreate();
            return;
        }
        VerticalSeekBar vSlider = getVSlider();
        vSlider.setOnSeekBarChangeListener(null);
        if (this.needCallStopTrackingTouchMethod) {
            this.needCallStopTrackingTouchMethod = false;
            vSlider.stopTrackingTouch(vSlider);
        }
        this.brightnessController.removeToggleSlider(getVSlider());
        this.keyboardShortcutKeyController.removeKeyboardShortcutKeyListener(this.keyboardShortcutKeyListener);
        this.sliderDragAnimator.onDestroy();
    }

    public final void setOutlineRadius(float f2) {
        if (this.outlineRadius == f2) {
            return;
        }
        this.outlineRadius = f2;
        getVToggleSliderInner().invalidateOutline();
    }

    public final void setProgressRadius(float f2) {
        if (this.progressRadius == f2) {
            return;
        }
        this.progressRadius = f2;
        getVProgress().invalidateOutline();
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SecondaryPanelDelegateBase
    public void prepareShow(BrightnessPanelParams brightnessPanelParams) {
        super.prepareShow(brightnessPanelParams);
        updateResources();
        updateSize();
        updateText();
        ToggleSliderView vToggleSlider = getVToggleSlider();
        CommonUtils.INSTANCE.setAlphaEx(vToggleSlider, 1.0f);
        vToggleSlider.setClickable(true);
        vToggleSlider.setFocusable(true);
        vToggleSlider.setImportantForAccessibility(0);
        VerticalSeekBar vSlider = getVSlider();
        vSlider.setHapticFeedbackEnabled(true ^ HapticFeedback.Companion.getIS_SUPPORT_LINEAR_MOTOR_VIBRATE());
        vSlider.createGestureHelper(this.gestureDispatcher);
        vSlider.setActivityStarter(this.activityStarter);
        getVToggleSliderInner().setOutlineProvider(new ViewOutlineProvider() { // from class: miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate.prepareShow.3
            @Override // android.view.ViewOutlineProvider
            public void getOutline(android.view.View v2, Outline outline) {
                o.g(v2, "v");
                o.g(outline, "outline");
                outline.setRoundRect(0, 0, v2.getWidth(), v2.getHeight(), BrightnessPanelSliderDelegate.this.getOutlineRadius());
            }
        });
        getVToggleSliderInner().invalidateOutline();
        getVProgress().setOutlineProvider(new ViewOutlineProvider() { // from class: miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate.prepareShow.4
            @Override // android.view.ViewOutlineProvider
            public void getOutline(android.view.View v2, Outline outline) {
                o.g(v2, "v");
                o.g(outline, "outline");
                outline.setRoundRect(0, (int) (v2.getHeight() * (1 - (((int) ((BrightnessPanelSliderDelegate.this.getVSlider().getProgress() / BrightnessPanelSliderDelegate.this.getVSlider().getMax()) * 10000)) / 10000.0f))), v2.getWidth(), v2.getHeight(), BrightnessPanelSliderDelegate.this.getProgressRadius());
            }
        });
        getVSlider().setProgressCallback(new VerticalSeekBar.ProgressCallback() { // from class: miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate.prepareShow.5
            @Override // miui.systemui.controlcenter.widget.VerticalSeekBar.ProgressCallback
            public void onProgressChanged(int i2, int i3) {
                BrightnessPanelSliderDelegate.this.getVProgress().invalidateOutline();
            }

            @Override // miui.systemui.controlcenter.widget.VerticalSeekBar.ProgressCallback
            public void onStartTrackingTouch() {
            }

            @Override // miui.systemui.controlcenter.widget.VerticalSeekBar.ProgressCallback
            public void onStopTrackingTouch() {
            }
        });
    }
}
