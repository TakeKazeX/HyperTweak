package miui.systemui.controlcenter.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import androidx.core.graphics.drawable.DrawableCompat;
import kotlin.jvm.internal.h;
import kotlin.jvm.internal.o;
import miui.systemui.controlcenter.utils.ControlCenterUtils;
import miui.systemui.util.CommonUtils;
import miui.systemui.util.MiBlurCompat;
import miui.systemui.widget.ImageView;
import miuix.animation.Folme;
import miuix.animation.FolmeEase;
import miuix.animation.IStateStyle;
import miuix.animation.property.FloatProperty;
import miuix.theme.token.ColorBlendToken;

/* JADX INFO: loaded from: classes.dex */
public final class AnimateColorView extends ImageView {
    private IStateStyle _blendColorAnimator;
    private float blendColorProgress;
    private ColorBlendToken fromToken;
    private ValueAnimator normalColorAnimator;
    private ColorBlendToken toToken;
    public static final Companion Companion = new Companion(null);
    private static final AnimateColorView$Companion$BLEND_COLOR_PROGRESS$1 BLEND_COLOR_PROGRESS = new FloatProperty<AnimateColorView>() { // from class: miui.systemui.controlcenter.widget.AnimateColorView$Companion$BLEND_COLOR_PROGRESS$1
        @Override // miuix.animation.property.FloatProperty
        public float getValue(AnimateColorView view) {
            o.g(view, "view");
            return view.getBlendColorProgress();
        }

        @Override // miuix.animation.property.FloatProperty
        public void setValue(AnimateColorView view, float f2) {
            o.g(view, "view");
            view.setBlendColorProgress(f2);
        }
    };

    public static final class Companion {
        public /* synthetic */ Companion(h hVar) {
            this();
        }

        private Companion() {
        }
    }

    /* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */
    public AnimateColorView(Context context) {
        this(context, null, 0, 0, 14, null);
        o.g(context, "context");
    }

    private final void cancelBlendAnimation() {
        this._blendColorAnimator = null;
        Folme.clean(this);
    }

    private final void cancelNormalAnimation() {
        ValueAnimator valueAnimator = this.normalColorAnimator;
        if (valueAnimator != null) {
            valueAnimator.cancel();
            valueAnimator.removeAllListeners();
            valueAnimator.removeAllUpdateListeners();
        }
        this.normalColorAnimator = null;
    }

    private final IStateStyle getBlendColorAnimator() {
        IStateStyle iStateStyle = this._blendColorAnimator;
        if (iStateStyle != null) {
            return iStateStyle;
        }
        IStateStyle iStateStyleUseValue = Folme.useValue(this);
        iStateStyleUseValue.setEase(FolmeEase.sineOut(200L), BLEND_COLOR_PROGRESS);
        this._blendColorAnimator = iStateStyleUseValue;
        return iStateStyleUseValue;
    }

    private final void performBlendAnimation(float f2) {
        if (this.fromToken == null || this.toToken == null) {
            return;
        }
        MiBlurCompat.setMiViewBlurModeCompat(this, 3);
        ColorBlendToken colorBlendToken = this.fromToken;
        o.d(colorBlendToken);
        ColorBlendToken colorBlendToken2 = this.toToken;
        o.d(colorBlendToken2);
        MiBlurCompat.setMiBackgroundBlendColors(this, colorBlendToken, colorBlendToken2, f2);
    }

    private final void performNormalAnimation(int i2, int i3) {
        cancelNormalAnimation();
        ValueAnimator valueAnimatorOfArgb = ValueAnimator.ofArgb(i2, i3);
        valueAnimatorOfArgb.setDuration(200L);
        valueAnimatorOfArgb.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: miui.systemui.controlcenter.widget.a
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                AnimateColorView.performNormalAnimation$lambda$3$lambda$2(this.f5707a, valueAnimator);
            }
        });
        valueAnimatorOfArgb.start();
        this.normalColorAnimator = valueAnimatorOfArgb;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void performNormalAnimation$lambda$3$lambda$2(AnimateColorView this$0, ValueAnimator valueAnimator) {
        o.g(this$0, "this$0");
        Drawable drawable = this$0.getDrawable();
        Object animatedValue = valueAnimator.getAnimatedValue();
        o.e(animatedValue, "null cannot be cast to non-null type kotlin.Int");
        DrawableCompat.setTint(drawable, ((Integer) animatedValue).intValue());
    }

    private final void updateIconColor(ColorBlendToken colorBlendToken, int i2) {
        if (getDrawable() == null) {
            return;
        }
        Context context = getContext();
        o.f(context, "getContext(...)");
        if (!ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(context)) {
            CommonUtils.INSTANCE.clearMiBlurBlendEffect(this);
            DrawableCompat.setTint(getDrawable(), getContext().getColor(i2));
        } else {
            setBackground(null);
            MiBlurCompat.setMiViewBlurModeCompat(this, 3);
            MiBlurCompat.setMiBackgroundBlendColors(this, colorBlendToken);
        }
    }

    public static /* synthetic */ void updateIconColor$default(AnimateColorView animateColorView, ColorBlendToken colorBlendToken, ColorBlendToken colorBlendToken2, int i2, int i3, boolean z2, int i4, Object obj) {
        if ((i4 & 16) != 0) {
            z2 = true;
        }
        animateColorView.updateIconColor(colorBlendToken, colorBlendToken2, i2, i3, z2);
    }

    public final float getBlendColorProgress() {
        return this.blendColorProgress;
    }

    public final void recycle() {
        cancelNormalAnimation();
        cancelBlendAnimation();
    }

    public final void setBlendColorProgress(float f2) {
        if (this.blendColorProgress == f2) {
            return;
        }
        this.blendColorProgress = f2;
        performBlendAnimation(f2);
    }

    /* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */
    public AnimateColorView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0, 0, 12, null);
        o.g(context, "context");
    }

    /* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */
    public AnimateColorView(Context context, AttributeSet attributeSet, int i2) {
        this(context, attributeSet, i2, 0, 8, null);
        o.g(context, "context");
    }

    public /* synthetic */ AnimateColorView(Context context, AttributeSet attributeSet, int i2, int i3, int i4, h hVar) {
        this(context, (i4 & 2) != 0 ? null : attributeSet, (i4 & 4) != 0 ? 0 : i2, (i4 & 8) != 0 ? 0 : i3);
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public AnimateColorView(Context context, AttributeSet attributeSet, int i2, int i3) {
        super(context, attributeSet, i2, i3);
        o.g(context, "context");
    }

    public final void updateIconColor(ColorBlendToken fromBlendColor, ColorBlendToken toBlendColor, int i2, int i3, boolean z2) {
        o.g(fromBlendColor, "fromBlendColor");
        o.g(toBlendColor, "toBlendColor");
        if (getDrawable() == null) {
            return;
        }
        if (z2) {
            Context context = getContext();
            o.f(context, "getContext(...)");
            if (ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(context)) {
                setBackground(null);
                this.fromToken = fromBlendColor;
                this.toToken = toBlendColor;
                IStateStyle blendColorAnimator = getBlendColorAnimator();
                AnimateColorView$Companion$BLEND_COLOR_PROGRESS$1 animateColorView$Companion$BLEND_COLOR_PROGRESS$1 = BLEND_COLOR_PROGRESS;
                blendColorAnimator.setTo(animateColorView$Companion$BLEND_COLOR_PROGRESS$1, Float.valueOf(0.0f));
                blendColorAnimator.to(animateColorView$Companion$BLEND_COLOR_PROGRESS$1, Float.valueOf(1.0f));
                return;
            }
            CommonUtils.INSTANCE.clearMiBlurBlendEffect(this);
            performNormalAnimation(getContext().getColor(i2), getContext().getColor(i3));
            return;
        }
        cancelNormalAnimation();
        getBlendColorAnimator().setTo(BLEND_COLOR_PROGRESS, Float.valueOf(1.0f));
        updateIconColor(toBlendColor, i3);
    }
}
