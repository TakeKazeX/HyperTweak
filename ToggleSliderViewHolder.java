package miui.systemui.controlcenter.panel.main.recyclerview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.TextView;
import b1.f;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.miui.controlcenter.BrightnessControllerBase;
import com.android.systemui.plugins.miui.shade.ShadeSwitchController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import kotlin.jvm.internal.o;
import miui.systemui.controlcenter.ConfigUtils;
import miui.systemui.controlcenter.R;
import miui.systemui.controlcenter.dagger.ControlCenterScope;
import miui.systemui.controlcenter.databinding.ToggleSliderItemViewBinding;
import miui.systemui.controlcenter.panel.secondary.SliderFromView;
import miui.systemui.controlcenter.utils.ControlCenterUtils;
import miui.systemui.controlcenter.widget.AnimateColorView;
import miui.systemui.controlcenter.widget.ToggleSliderView;
import miui.systemui.controlcenter.widget.VerticalSeekBar;
import miui.systemui.controlcenter.widget.ViewTouchAnim;
import miui.systemui.controlcenter.windowview.ControlCenterExpandController;
import miui.systemui.controlcenter.windowview.GestureDispatcher;
import miui.systemui.dagger.qualifiers.SystemUI;
import miui.systemui.util.BlurUtils;
import miui.systemui.util.BlurUtilsExt;
import miui.systemui.util.CommonUtils;
import miui.systemui.util.HapticFeedback;
import miui.systemui.util.MiBlurCompat;
import miui.systemui.util.MiuiColorBlendToken;
import miuix.animation.base.AnimConfig;

/* JADX INFO: loaded from: classes.dex */
@SuppressLint({"ClickableViewAccessibility", "UseCompatLoadingForDrawables"})
public final class ToggleSliderViewHolder extends MultipleAnimViewHolder implements SliderFromView {
    private final ActivityStarter activityStarter;
    private final ToggleSliderItemViewBinding binding;
    private final BlurUtilsExt blurUtilsExt;
    private boolean disableState;
    private final ControlCenterExpandController expandController;
    private final GestureDispatcher gestureDispatcher;
    private boolean inMirror;
    private final View mirrorBlurProvider;
    private float outlineRadius;
    private float progressRadius;
    private final ShadeSwitchController shadeSwitchController;
    private final StatusBarStateController statusBarStateController;
    private final Context sysUIContext;

    @ControlCenterScope
    public static final class Factory {
        private final ActivityStarter activityStarter;
        private final BlurUtilsExt blurUtilsExt;
        private final BrightnessControllerBase brightnessController;
        private final ControlCenterExpandController expandController;
        private final GestureDispatcher gestureDispatcher;
        private final ShadeSwitchController shadeSwitchController;
        private final StatusBarStateController statusBarStateController;
        private final Context sysUIContext;

        public Factory(GestureDispatcher gestureDispatcher, ActivityStarter activityStarter, ShadeSwitchController shadeSwitchController, BrightnessControllerBase brightnessController, StatusBarStateController statusBarStateController, BlurUtilsExt blurUtilsExt, ControlCenterExpandController expandController, @SystemUI Context sysUIContext) {
            o.g(gestureDispatcher, "gestureDispatcher");
            o.g(activityStarter, "activityStarter");
            o.g(shadeSwitchController, "shadeSwitchController");
            o.g(brightnessController, "brightnessController");
            o.g(statusBarStateController, "statusBarStateController");
            o.g(blurUtilsExt, "blurUtilsExt");
            o.g(expandController, "expandController");
            o.g(sysUIContext, "sysUIContext");
            this.gestureDispatcher = gestureDispatcher;
            this.activityStarter = activityStarter;
            this.shadeSwitchController = shadeSwitchController;
            this.brightnessController = brightnessController;
            this.statusBarStateController = statusBarStateController;
            this.blurUtilsExt = blurUtilsExt;
            this.expandController = expandController;
            this.sysUIContext = sysUIContext;
        }

        public final ToggleSliderViewHolder create(ToggleSliderItemViewBinding binding) {
            o.g(binding, "binding");
            View brightnessMirrorBlurProvider = this.brightnessController.getBrightnessMirrorBlurProvider();
            o.f(brightnessMirrorBlurProvider, "getBrightnessMirrorBlurProvider(...)");
            return new ToggleSliderViewHolder(binding, brightnessMirrorBlurProvider, this.gestureDispatcher, this.activityStarter, this.shadeSwitchController, this.statusBarStateController, this.blurUtilsExt, this.expandController, this.sysUIContext);
        }
    }

    /* JADX WARN: Illegal instructions before constructor call */
    public ToggleSliderViewHolder(ToggleSliderItemViewBinding binding, View mirrorBlurProvider, GestureDispatcher gestureDispatcher, ActivityStarter activityStarter, ShadeSwitchController shadeSwitchController, StatusBarStateController statusBarStateController, BlurUtilsExt blurUtilsExt, ControlCenterExpandController expandController, Context sysUIContext) {
        o.g(binding, "binding");
        o.g(mirrorBlurProvider, "mirrorBlurProvider");
        o.g(gestureDispatcher, "gestureDispatcher");
        o.g(activityStarter, "activityStarter");
        o.g(shadeSwitchController, "shadeSwitchController");
        o.g(statusBarStateController, "statusBarStateController");
        o.g(blurUtilsExt, "blurUtilsExt");
        o.g(expandController, "expandController");
        o.g(sysUIContext, "sysUIContext");
        ToggleSliderView root = binding.getRoot();
        o.f(root, "getRoot(...)");
        super(root, shadeSwitchController, expandController);
        this.binding = binding;
        this.mirrorBlurProvider = mirrorBlurProvider;
        this.gestureDispatcher = gestureDispatcher;
        this.activityStarter = activityStarter;
        this.shadeSwitchController = shadeSwitchController;
        this.statusBarStateController = statusBarStateController;
        this.blurUtilsExt = blurUtilsExt;
        this.expandController = expandController;
        this.sysUIContext = sysUIContext;
        VerticalSeekBar slider = getSlider();
        slider.setHapticFeedbackEnabled(!HapticFeedback.Companion.getIS_SUPPORT_LINEAR_MOTOR_VIBRATE());
        slider.setDragView(binding.getRoot());
        slider.createGestureHelper(gestureDispatcher);
        slider.setActivityStarter(activityStarter);
        slider.setProgressCallback(new VerticalSeekBar.ProgressCallback() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder$1$1
            @Override // miui.systemui.controlcenter.widget.VerticalSeekBar.ProgressCallback
            public void onProgressChanged(int i2, int i3) {
                this.this$0.getBinding().progress.invalidateOutline();
            }

            @Override // miui.systemui.controlcenter.widget.VerticalSeekBar.ProgressCallback
            public void onStartTrackingTouch() {
            }

            @Override // miui.systemui.controlcenter.widget.VerticalSeekBar.ProgressCallback
            public void onStopTrackingTouch() {
            }
        });
        slider.getTouchAnim().setTouchCallback(new ViewTouchAnim.TouchCallback() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder$1$2
            @Override // miui.systemui.controlcenter.widget.ViewTouchAnim.TouchCallback
            public void onBeginScale() {
                MainPanelItemViewHolder.Companion.setTouchViewHolder(this.this$0);
            }
        });
        updateResources();
        updateSize();
        binding.toggleSliderInner.setOutlineProvider(new ViewOutlineProvider() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder.2
            @Override // android.view.ViewOutlineProvider
            public void getOutline(View view, Outline outline) {
                o.g(view, "view");
                o.g(outline, "outline");
                Drawable background = ToggleSliderViewHolder.this.getBinding().progressBg.getBackground();
                GradientDrawable gradientDrawable = background instanceof GradientDrawable ? (GradientDrawable) background : null;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), gradientDrawable != null ? gradientDrawable.getCornerRadius() : ToggleSliderViewHolder.this.getOutlineRadius());
            }
        });
        binding.toggleSliderInner.invalidateOutline();
        binding.progress.setOutlineProvider(new ViewOutlineProvider() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder.3
            @Override // android.view.ViewOutlineProvider
            public void getOutline(View view, Outline outline) {
                o.g(view, "view");
                o.g(outline, "outline");
                float progressRadius = ToggleSliderViewHolder.this.getProgressRadius();
                Drawable background = ToggleSliderViewHolder.this.getBinding().progressBg.getBackground();
                GradientDrawable gradientDrawable = background instanceof GradientDrawable ? (GradientDrawable) background : null;
                outline.setRoundRect(0, (int) (view.getHeight() * (1 - (((int) ((ToggleSliderViewHolder.this.getSlider().getProgress() / ToggleSliderViewHolder.this.getSlider().getMax()) * 10000)) / 10000.0f))), view.getWidth(), view.getHeight(), f.e(progressRadius, gradientDrawable != null ? gradientDrawable.getCornerRadius() : ToggleSliderViewHolder.this.getProgressRadius()));
            }
        });
    }

    private final void updateResources() {
        CommonUtils commonUtils = CommonUtils.INSTANCE;
        miui.systemui.widget.View progress = this.binding.progress;
        o.f(progress, "progress");
        CommonUtils.setBackgroundResourceEx$default(commonUtils, progress, this.disableState ? R.drawable.toggle_slider_progress_disabled_background : R.drawable.toggle_slider_progress_background, false, 2, null);
        miui.systemui.widget.View progressBg = this.binding.progressBg;
        o.f(progressBg, "progressBg");
        CommonUtils.setBackgroundResourceEx$default(commonUtils, progressBg, R.drawable.toggle_slider_background, false, 2, null);
    }

    public final ToggleSliderItemViewBinding getBinding() {
        return this.binding;
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SliderFromView
    public ViewGroup getContent() {
        ToggleSliderView root = this.binding.getRoot();
        o.f(root, "getRoot(...)");
        return root;
    }

    public final boolean getDisableState() {
        return this.disableState;
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SliderFromView
    public View getIcon() {
        AnimateColorView icon = this.binding.icon;
        o.f(icon, "icon");
        return icon;
    }

    public final boolean getInMirror() {
        return this.inMirror;
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SliderFromView
    public float getOutlineRadius() {
        return this.outlineRadius;
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SliderFromView
    public float getProgressRadius() {
        return this.progressRadius;
    }

    public final VerticalSeekBar getSlider() {
        VerticalSeekBar slider = this.binding.slider;
        o.f(slider, "slider");
        return slider;
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SliderFromView
    public View getTopText() {
        TextView topText = this.binding.topText;
        o.f(topText, "topText");
        return topText;
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder
    public void onConfigurationChanged(int i2) {
        ConfigUtils configUtils = ConfigUtils.INSTANCE;
        boolean zBlurChanged = configUtils.blurChanged(i2);
        boolean zDimensionsChanged = configUtils.dimensionsChanged(i2);
        if (configUtils.colorsChanged(i2) || zBlurChanged || zDimensionsChanged) {
            updateResources();
        }
        if (zDimensionsChanged) {
            updateSize();
        }
        if (zBlurChanged) {
            this.itemView.invalidateOutline();
        }
    }

    public final void setDisableState(boolean z2) {
        if (this.disableState == z2) {
            return;
        }
        this.disableState = z2;
        if (!ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(getContext())) {
            this.binding.progress.setBackground(getContext().getDrawable(z2 ? R.drawable.toggle_slider_progress_disabled_background : R.drawable.toggle_slider_progress_background));
            return;
        }
        miui.systemui.widget.View progress = this.binding.progress;
        o.f(progress, "progress");
        MiBlurCompat.setMiBackgroundBlendColors(progress, z2 ? MiuiColorBlendToken.INSTANCE.getCC_SLIDER_DISABLED_BLEND_COLORS() : MiuiColorBlendToken.INSTANCE.getCC_SLIDER_BLEND_COLORS());
    }

    public final void setInMirror(boolean z2) {
        if (this.inMirror == z2) {
            return;
        }
        this.inMirror = z2;
        if (ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(getContext())) {
            updateBlendBlur();
        }
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SliderFromView
    public void setOutlineRadius(float f2) {
        this.outlineRadius = f2;
        this.binding.toggleSliderInner.invalidateOutline();
    }

    @Override // miui.systemui.controlcenter.panel.secondary.SliderFromView
    public void setProgressRadius(float f2) {
        this.progressRadius = f2;
        this.binding.progress.invalidateOutline();
    }

    public final void setTopTextVisible(boolean z2, String text) {
        o.g(text, "text");
        this.binding.topText.setVisibility(z2 ? 0 : 8);
        VerticalSeekBar verticalSeekBar = this.binding.slider;
        if (!z2) {
            text = null;
        }
        verticalSeekBar.setAccessibilityLabel(text);
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterViewHolder
    public AnimConfig updateAnimConfig(boolean z2, boolean z3, int i2, int i3) {
        return getAnimatorConfigHelper().updateAnimEase(this, z2, z3, i2, i3);
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder
    public void updateBlendBlur() {
        if (!ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(getContext())) {
            View itemView = this.itemView;
            o.f(itemView, "itemView");
            MiBlurCompat.setPassWindowBlurEnabledCompat(itemView, false);
            View itemView2 = this.itemView;
            o.f(itemView2, "itemView");
            MiBlurCompat.setMiBackgroundBlurModeCompat(itemView2, 0);
            CommonUtils commonUtils = CommonUtils.INSTANCE;
            miui.systemui.widget.View progressBg = this.binding.progressBg;
            o.f(progressBg, "progressBg");
            commonUtils.clearMiBlurBlendEffect(progressBg);
            miui.systemui.widget.View progress = this.binding.progress;
            o.f(progress, "progress");
            commonUtils.clearMiBlurBlendEffect(progress);
            return;
        }
        if (this.inMirror) {
            boolean zIsLocked = CommonUtils.isLocked(this.statusBarStateController);
            miui.systemui.widget.View progressBg2 = this.binding.progressBg;
            o.f(progressBg2, "progressBg");
            MiBlurCompat.chooseBackgroundBlurContainerCompat(progressBg2, this.mirrorBlurProvider);
            miui.systemui.widget.View progress2 = this.binding.progress;
            o.f(progress2, "progress");
            MiBlurCompat.chooseBackgroundBlurContainerCompat(progress2, this.mirrorBlurProvider);
            AnimateColorView icon = this.binding.icon;
            o.f(icon, "icon");
            MiBlurCompat.chooseBackgroundBlurContainerCompat(icon, this.mirrorBlurProvider);
            MiBlurCompat.disableMiBackgroundContainBelowCompat(this.mirrorBlurProvider, !zIsLocked);
            MiBlurCompat.setPassWindowBlurEnabledCompat(this.mirrorBlurProvider, !zIsLocked || BlurUtils.isDefaultLockScreenTheme(this.sysUIContext));
            MiBlurCompat.setMiBackgroundBlurModeCompat(this.mirrorBlurProvider, 1);
            MiBlurCompat.setMiBackgroundBlurRadiusCompat(this.mirrorBlurProvider, this.blurUtilsExt.getMaxBlurRadius());
            MiBlurCompat.setMiBackgroundBlurScaleRatioCompat(this.mirrorBlurProvider, 0.075f);
        } else {
            miui.systemui.widget.View progressBg3 = this.binding.progressBg;
            o.f(progressBg3, "progressBg");
            MiBlurCompat.chooseBackgroundBlurContainerCompat(progressBg3, null);
            miui.systemui.widget.View progress3 = this.binding.progress;
            o.f(progress3, "progress");
            MiBlurCompat.chooseBackgroundBlurContainerCompat(progress3, null);
            AnimateColorView icon2 = this.binding.icon;
            o.f(icon2, "icon");
            MiBlurCompat.chooseBackgroundBlurContainerCompat(icon2, null);
            MiBlurCompat.setPassWindowBlurEnabledCompat(this.mirrorBlurProvider, false);
            MiBlurCompat.setMiBackgroundBlurModeCompat(this.mirrorBlurProvider, 0);
            MiBlurCompat.setMiBackgroundBlurRadiusCompat(this.mirrorBlurProvider, 0);
        }
        View itemView3 = this.itemView;
        o.f(itemView3, "itemView");
        MiBlurCompat.setMiBackgroundBlurModeCompat(itemView3, 0);
        View itemView4 = this.itemView;
        o.f(itemView4, "itemView");
        MiBlurCompat.setMiBackgroundBlurRadiusCompat(itemView4, 0);
        miui.systemui.widget.View progressBg4 = this.binding.progressBg;
        o.f(progressBg4, "progressBg");
        MiBlurCompat.setMiViewBlurModeCompat(progressBg4, 1);
        miui.systemui.widget.View progressBg5 = this.binding.progressBg;
        o.f(progressBg5, "progressBg");
        MiuiColorBlendToken miuiColorBlendToken = MiuiColorBlendToken.INSTANCE;
        MiBlurCompat.setMiBackgroundBlendColors(progressBg5, miuiColorBlendToken.getCC_TILE_DEFAULT_BLEND_COLORS());
        miui.systemui.widget.View progress4 = this.binding.progress;
        o.f(progress4, "progress");
        MiBlurCompat.setMiViewBlurModeCompat(progress4, 1);
        miui.systemui.widget.View progress5 = this.binding.progress;
        o.f(progress5, "progress");
        MiBlurCompat.setMiBackgroundBlendColors(progress5, this.disableState ? miuiColorBlendToken.getCC_SLIDER_DISABLED_BLEND_COLORS() : miuiColorBlendToken.getCC_SLIDER_BLEND_COLORS());
    }

    public final void updateSize() {
        setProgressRadius(getResources().getDimensionPixelSize(R.dimen.toggle_slider_clip_round_corner_radius));
        setOutlineRadius(getResources().getDimensionPixelSize(R.dimen.control_center_universal_corner_radius));
        int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.control_center_universal_margin);
        CommonUtils commonUtils = CommonUtils.INSTANCE;
        View itemView = this.itemView;
        o.f(itemView, "itemView");
        CommonUtils.setMargins$default(commonUtils, itemView, dimensionPixelSize, false, 2, null);
        int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.control_center_universal_1_row_size);
        int dimensionPixelSize3 = getResources().getDimensionPixelSize(R.dimen.control_center_universal_2_rows_size);
        View itemView2 = this.itemView;
        o.f(itemView2, "itemView");
        CommonUtils.setLayoutSize$default(commonUtils, itemView2, dimensionPixelSize2, dimensionPixelSize3, false, 4, null);
        int dimensionPixelSize4 = getResources().getDimensionPixelSize(R.dimen.toggle_slider_icon_size);
        VerticalSeekBar slider = this.binding.slider;
        o.f(slider, "slider");
        CommonUtils.setLayoutSize$default(commonUtils, slider, dimensionPixelSize3, dimensionPixelSize2, false, 4, null);
        AnimateColorView icon = this.binding.icon;
        o.f(icon, "icon");
        CommonUtils.setLayoutSize$default(commonUtils, icon, dimensionPixelSize4, dimensionPixelSize4, false, 4, null);
        int dimensionPixelSize5 = getResources().getDimensionPixelSize(R.dimen.toggle_slider_icon_bottom_margin);
        AnimateColorView icon2 = this.binding.icon;
        o.f(icon2, "icon");
        CommonUtils.setMarginBottom$default(commonUtils, icon2, dimensionPixelSize5, false, 2, null);
    }
}
