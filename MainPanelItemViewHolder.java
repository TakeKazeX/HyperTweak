package miui.systemui.controlcenter.panel.main.recyclerview;

import G0.i;
import G0.s;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import androidx.lifecycle.Lifecycle;
import b1.f;
import kotlin.jvm.internal.h;
import kotlin.jvm.internal.o;
import miui.systemui.controlcenter.ConfigUtils;
import miui.systemui.controlcenter.panel.main.MainPanelContent;
import miui.systemui.controlcenter.panel.main.MainPanelController;
import miui.systemui.controlcenter.panel.main.anim.LowEndItemHolderAnimator;
import miui.systemui.controlcenter.utils.ControlCenterUtils;
import miui.systemui.controlcenter.widget.VerticalSeekBar;
import miui.systemui.util.AnimationUtils;
import miui.systemui.util.CommonUtils;
import miui.systemui.util.Constant;
import miui.systemui.util.FlipUtils;
import miui.systemui.util.MiBlurCompat;
import miui.systemui.util.MiuiColorBlendToken;
import miuix.animation.Folme;
import miuix.animation.FolmeEase;
import miuix.animation.IStateStyle;
import miuix.animation.base.AnimConfig;
import miuix.animation.property.FloatProperty;
import miuix.animation.utils.EaseManager;

/* JADX INFO: loaded from: classes.dex */
public abstract class MainPanelItemViewHolder extends ControlCenterViewHolder {
    public static final Companion Companion = new Companion(null);
    private static final EaseManager.EaseStyle EASE_SLIDE_HIDE_COLOR_ALPHA;
    private static final EaseManager.EaseStyle EASE_SLIDE_SHOW_COLOR_ALPHA;
    private static final FloatProperty<MainPanelItemViewHolder> EXPAND_ALPHA;
    private static final FloatProperty<MainPanelItemViewHolder> EXPAND_SCALE;
    private static final float EXPAND_START_SCALE = 0.65f;
    private static final float EXPAND_START_SCALE_LOW_END = 0.9f;
    private static final FloatProperty<MainPanelItemViewHolder> EXPAND_TRANS_Y;
    private static final FloatProperty<MainPanelItemViewHolder> SHRINK_EXPAND_TRANS_Y;
    private static final FloatProperty<MainPanelItemViewHolder> SHRINK_X;
    private static final FloatProperty<MainPanelItemViewHolder> SHRINK_Y;
    public static final String TAG = "MainPanelItemViewHolder";
    private static final EaseManager.EaseStyle expandTransYEase;
    private static MainPanelItemViewHolder touchViewHolder;
    private final AnimatorConfigHelper animatorConfigHelper;
    private boolean attached;
    private float centerX;
    private float centerY;
    private float centerYFromHeader;
    private float centerYFromHeaderGap;
    private final Configuration configuration;
    private float delayPer;
    private float deltaY;
    private boolean draggable;
    private float expandAlpha;
    private float expandScaleRatio;
    private float expandTransY;
    private EaseManager.EaseStyle hideEase;
    private MainPanelListItem item;
    private float lastPrintAlpha;
    private float lastPrintAnimateAlpha;
    private final int[] loc;
    private boolean mirrorShowing;
    private MainPanelController.Mode mode;
    private MainPanelContent owner;
    private boolean pendingUpdateLocation;
    private EaseManager.EaseStyle releaseTransYEase;
    private float scaleFactor;
    private EaseManager.EaseStyle showEase;
    private float shrinkCenterX;
    private float shrinkCenterY;
    private float shrinkExpandTransY;
    private EaseManager.EaseStyle shrinkExpandTransYEase;
    private float shrinkX;
    private EaseManager.EaseStyle shrinkXEase;
    private float shrinkY;
    private EaseManager.EaseStyle shrinkYEase;
    private int spreadRow;
    private float spreadScale;
    private float spreadSlideTransX;
    private MainPanelController.Style style;

    public static final class Companion {
        public /* synthetic */ Companion(h hVar) {
            this();
        }

        public final EaseManager.EaseStyle getEASE_SLIDE_HIDE_COLOR_ALPHA() {
            return MainPanelItemViewHolder.EASE_SLIDE_HIDE_COLOR_ALPHA;
        }

        public final EaseManager.EaseStyle getEASE_SLIDE_SHOW_COLOR_ALPHA() {
            return MainPanelItemViewHolder.EASE_SLIDE_SHOW_COLOR_ALPHA;
        }

        public final FloatProperty<MainPanelItemViewHolder> getEXPAND_ALPHA() {
            return MainPanelItemViewHolder.EXPAND_ALPHA;
        }

        public final FloatProperty<MainPanelItemViewHolder> getEXPAND_SCALE() {
            return MainPanelItemViewHolder.EXPAND_SCALE;
        }

        public final FloatProperty<MainPanelItemViewHolder> getEXPAND_TRANS_Y() {
            return MainPanelItemViewHolder.EXPAND_TRANS_Y;
        }

        public final EaseManager.EaseStyle getExpandTransYEase() {
            return MainPanelItemViewHolder.expandTransYEase;
        }

        public final FloatProperty<MainPanelItemViewHolder> getSHRINK_EXPAND_TRANS_Y() {
            return MainPanelItemViewHolder.SHRINK_EXPAND_TRANS_Y;
        }

        public final FloatProperty<MainPanelItemViewHolder> getSHRINK_X() {
            return MainPanelItemViewHolder.SHRINK_X;
        }

        public final FloatProperty<MainPanelItemViewHolder> getSHRINK_Y() {
            return MainPanelItemViewHolder.SHRINK_Y;
        }

        public final MainPanelItemViewHolder getTouchViewHolder() {
            return MainPanelItemViewHolder.touchViewHolder;
        }

        public final float lerp(float f2, float f3, float f4) {
            return f3 + ((f4 - f3) * f2);
        }

        public final void releaseTouchNow() {
            VerticalSeekBar slider;
            Object touchViewHolder = getTouchViewHolder();
            TouchAnimator touchAnimator = touchViewHolder instanceof TouchAnimator ? (TouchAnimator) touchViewHolder : null;
            if (touchAnimator != null) {
                touchAnimator.touchReleaseNow();
            }
            MainPanelItemViewHolder touchViewHolder2 = getTouchViewHolder();
            ToggleSliderViewHolder toggleSliderViewHolder = touchViewHolder2 instanceof ToggleSliderViewHolder ? (ToggleSliderViewHolder) touchViewHolder2 : null;
            if (toggleSliderViewHolder != null && (slider = toggleSliderViewHolder.getSlider()) != null) {
                slider.resetTouchAnimNow();
            }
            setTouchViewHolder(null);
        }

        public final void setTouchViewHolder(MainPanelItemViewHolder mainPanelItemViewHolder) {
            MainPanelItemViewHolder.touchViewHolder = mainPanelItemViewHolder;
        }

        private Companion() {
        }
    }

    public interface TouchAnimator extends View.OnTouchListener {
        default void attachTouchTarget(View view) {
            o.g(view, "view");
        }

        default void detachTouchTarget() {
        }

        @Override // android.view.View.OnTouchListener
        default boolean onTouch(View view, MotionEvent motionEvent) {
            Integer numValueOf = motionEvent != null ? Integer.valueOf(motionEvent.getActionMasked()) : null;
            if (numValueOf != null && numValueOf.intValue() == 0) {
                touchDown(motionEvent);
                return false;
            }
            if (numValueOf != null && numValueOf.intValue() == 1) {
                touchRelease();
                return false;
            }
            if (numValueOf == null || numValueOf.intValue() != 3) {
                return false;
            }
            touchCancel();
            return false;
        }

        default void touchCancel() {
            touchRelease();
        }

        void touchDown(MotionEvent motionEvent);

        void touchRelease();

        default void touchReleaseNow() {
            touchRelease();
        }

        void touchTrigger();
    }

    static {
        EaseManager.EaseStyle style = EaseManager.getStyle(-2, 0.9f, 0.3f);
        o.f(style, "getStyle(...)");
        expandTransYEase = style;
        EXPAND_ALPHA = new FloatProperty<MainPanelItemViewHolder>() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder$Companion$EXPAND_ALPHA$1
            @Override // miuix.animation.property.FloatProperty
            public float getValue(MainPanelItemViewHolder mainPanelItemViewHolder) {
                if (mainPanelItemViewHolder != null) {
                    return mainPanelItemViewHolder.getExpandAlpha();
                }
                return 1.0f;
            }

            @Override // miuix.animation.property.FloatProperty
            public void setValue(MainPanelItemViewHolder mainPanelItemViewHolder, float f2) {
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.setExpandAlpha(f2);
                }
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.scheduleUpdate();
                }
                if (mainPanelItemViewHolder == null || !mainPanelItemViewHolder.getPrintAnimateLog()) {
                    return;
                }
                if (!(f2 == 0.0f || f2 == 1.0f) || f2 == mainPanelItemViewHolder.lastPrintAnimateAlpha) {
                    return;
                }
                mainPanelItemViewHolder.lastPrintAnimateAlpha = f2;
                Log.d(MainPanelItemViewHolder.TAG, "update animate alpha " + f2);
            }
        };
        EXPAND_TRANS_Y = new FloatProperty<MainPanelItemViewHolder>() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder$Companion$EXPAND_TRANS_Y$1
            @Override // miuix.animation.property.FloatProperty
            public float getValue(MainPanelItemViewHolder mainPanelItemViewHolder) {
                if (mainPanelItemViewHolder != null) {
                    return mainPanelItemViewHolder.getExpandTransY();
                }
                return 0.0f;
            }

            @Override // miuix.animation.property.FloatProperty
            public void setValue(MainPanelItemViewHolder mainPanelItemViewHolder, float f2) {
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.setExpandTransY(f2);
                }
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.scheduleUpdate();
                }
            }
        };
        EXPAND_SCALE = new FloatProperty<MainPanelItemViewHolder>() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder$Companion$EXPAND_SCALE$1
            @Override // miuix.animation.property.FloatProperty
            public float getValue(MainPanelItemViewHolder mainPanelItemViewHolder) {
                if (mainPanelItemViewHolder != null) {
                    return mainPanelItemViewHolder.getExpandScaleRatio();
                }
                return 1.0f;
            }

            @Override // miuix.animation.property.FloatProperty
            public void setValue(MainPanelItemViewHolder mainPanelItemViewHolder, float f2) {
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.setExpandScaleRatio(f2);
                }
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.scheduleUpdate();
                }
            }
        };
        SHRINK_EXPAND_TRANS_Y = new FloatProperty<MainPanelItemViewHolder>() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder$Companion$SHRINK_EXPAND_TRANS_Y$1
            @Override // miuix.animation.property.FloatProperty
            public float getValue(MainPanelItemViewHolder mainPanelItemViewHolder) {
                if (mainPanelItemViewHolder != null) {
                    return mainPanelItemViewHolder.getShrinkExpandTransY();
                }
                return 0.0f;
            }

            @Override // miuix.animation.property.FloatProperty
            public void setValue(MainPanelItemViewHolder mainPanelItemViewHolder, float f2) {
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.setShrinkExpandTransY(f2);
                }
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.scheduleUpdate();
                }
            }
        };
        SHRINK_X = new FloatProperty<MainPanelItemViewHolder>() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder$Companion$SHRINK_X$1
            @Override // miuix.animation.property.FloatProperty
            public float getValue(MainPanelItemViewHolder mainPanelItemViewHolder) {
                if (mainPanelItemViewHolder != null) {
                    return mainPanelItemViewHolder.getShrinkX();
                }
                return 1.0f;
            }

            @Override // miuix.animation.property.FloatProperty
            public void setValue(MainPanelItemViewHolder mainPanelItemViewHolder, float f2) {
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.setShrinkX(f2);
                }
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.scheduleUpdate();
                }
            }
        };
        SHRINK_Y = new FloatProperty<MainPanelItemViewHolder>() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder$Companion$SHRINK_Y$1
            @Override // miuix.animation.property.FloatProperty
            public float getValue(MainPanelItemViewHolder mainPanelItemViewHolder) {
                if (mainPanelItemViewHolder != null) {
                    return mainPanelItemViewHolder.getShrinkY();
                }
                return 1.0f;
            }

            @Override // miuix.animation.property.FloatProperty
            public void setValue(MainPanelItemViewHolder mainPanelItemViewHolder, float f2) {
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.setShrinkY(f2);
                }
                if (mainPanelItemViewHolder != null) {
                    mainPanelItemViewHolder.scheduleUpdate();
                }
            }
        };
        EaseManager.EaseStyle easeStyleSpring = FolmeEase.spring(0.95f, 0.45f);
        o.f(easeStyleSpring, "spring(...)");
        EASE_SLIDE_SHOW_COLOR_ALPHA = easeStyleSpring;
        EaseManager.EaseStyle easeStyleSpring2 = FolmeEase.spring(0.9f, 0.2f);
        o.f(easeStyleSpring2, "spring(...)");
        EASE_SLIDE_HIDE_COLOR_ALPHA = easeStyleSpring2;
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public MainPanelItemViewHolder(View itemView) {
        super(itemView);
        o.g(itemView, "itemView");
        this.style = MainPanelController.Style.VERTICAL;
        this.mode = MainPanelController.Mode.NORMAL;
        this.configuration = new Configuration(getResources().getConfiguration());
        this.animatorConfigHelper = new AnimatorConfigHelper(getContext());
        this.spreadScale = 1.0f;
        this.expandAlpha = 1.0f;
        this.expandScaleRatio = 1.0f;
        this.shrinkX = 1.0f;
        this.shrinkY = 1.0f;
        itemView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { // from class: miui.systemui.controlcenter.panel.main.recyclerview.d
            @Override // android.view.View.OnLayoutChangeListener
            public final void onLayoutChange(View view, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9) {
                MainPanelItemViewHolder._init_$lambda$0(this.f5645a, view, i2, i3, i4, i5, i6, i7, i8, i9);
            }
        });
        this.loc = new int[2];
        this.lastPrintAlpha = -1.0f;
        this.lastPrintAnimateAlpha = -1.0f;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void _init_$lambda$0(MainPanelItemViewHolder this$0, View view, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9) {
        o.g(this$0, "this$0");
        if (Constant.INSTANCE.getDEBUG()) {
            Log.d(this$0.getTag(), "on layout changed");
        }
        if (!MainPanelController.Companion.getLowEndAnim()) {
            this$0.prepareLocation();
        }
        this$0.updateEases();
        this$0.pendingUpdateLocation = true;
    }

    public static /* synthetic */ i calExpandedStretch$default(MainPanelItemViewHolder mainPanelItemViewHolder, float f2, float f3, float f4, boolean z2, boolean z3, int i2, Object obj) {
        if (obj == null) {
            return mainPanelItemViewHolder.calExpandedStretch(f2, f3, f4, (i2 & 8) != 0 ? true : z2, (i2 & 16) != 0 ? true : z3);
        }
        throw new UnsupportedOperationException("Super calls with default arguments not supported in this target, function: calExpandedStretch");
    }

    public static /* synthetic */ void changeExpand$default(MainPanelItemViewHolder mainPanelItemViewHolder, float f2, float f3, float f4, boolean z2, boolean z3, int i2, Object obj) {
        if (obj != null) {
            throw new UnsupportedOperationException("Super calls with default arguments not supported in this target, function: changeExpand");
        }
        if ((i2 & 16) != 0) {
            z3 = true;
        }
        mainPanelItemViewHolder.changeExpand(f2, f3, f4, z2, z3);
    }

    public static /* synthetic */ void changeVisible$default(MainPanelItemViewHolder mainPanelItemViewHolder, boolean z2, boolean z3, boolean z4, int i2, Object obj) {
        if (obj != null) {
            throw new UnsupportedOperationException("Super calls with default arguments not supported in this target, function: changeVisible");
        }
        if ((i2 & 2) != 0) {
            z3 = true;
        }
        if ((i2 & 4) != 0) {
            z4 = false;
        }
        mainPanelItemViewHolder.changeVisible(z2, z3, z4);
    }

    private final float getExpandScale() {
        return Companion.lerp(this.expandScaleRatio, MainPanelController.Companion.getLowEndAnim() ? 0.9f : EXPAND_START_SCALE, 1.0f);
    }

    private final float getHeaderHeight() {
        return getMainPanelAdapter().getHeaderHeight();
    }

    private final boolean getMirrorStarted() {
        return getMainPanelAdapter().getMirrorLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final boolean getPrintAnimateLog() {
        return getPosition() == 7;
    }

    private final int getScreenHeight() {
        return getMainPanelAdapter().getScreenHeight();
    }

    private final int getScreenWidth() {
        return getMainPanelAdapter().getScreenWidth();
    }

    private final String getTag() {
        return "item_" + getPosition();
    }

    private final void mirrorWithAnimate(boolean z2) {
        IStateStyle anim = getAnim();
        FloatProperty<MainPanelItemViewHolder> floatProperty = EXPAND_ALPHA;
        Float fValueOf = Float.valueOf(z2 ? 1.0f : 0.0f);
        AnimConfig animConfig = new AnimConfig();
        animConfig.setEase(z2 ? EASE_SLIDE_SHOW_COLOR_ALPHA : EASE_SLIDE_HIDE_COLOR_ALPHA);
        animConfig.setDelay(z2 ? 100L : 0L);
        s sVar = s.f193a;
        anim.to(floatProperty, fValueOf, animConfig);
    }

    private final void prepareLocation() {
        float flipOutInsetLeft;
        float headerHeight = getHeaderHeight();
        CommonUtils commonUtils = CommonUtils.INSTANCE;
        View itemView = this.itemView;
        o.f(itemView, "itemView");
        commonUtils.getLocationInWindowWithoutTransform(itemView, this.loc);
        float f2 = 2;
        this.centerX = this.loc[0] + (this.itemView.getWidth() / f2);
        float height = this.loc[1] + (this.itemView.getHeight() / f2);
        this.centerY = height;
        float f3 = height - headerHeight;
        this.centerYFromHeader = f3;
        this.centerYFromHeaderGap = f3;
        if (CommonUtils.isLayoutRtl(getContext())) {
            FlipUtils flipUtils = FlipUtils.INSTANCE;
            flipOutInsetLeft = flipUtils.getFlipOutInsetLeft() + ((this.centerX - flipUtils.getFlipOutInsetLeft()) * EXPAND_START_SCALE);
        } else {
            float screenWidth = getScreenWidth() - FlipUtils.INSTANCE.getFlipOutInsetRight();
            flipOutInsetLeft = screenWidth - ((screenWidth - this.centerX) * EXPAND_START_SCALE);
        }
        this.shrinkCenterX = flipOutInsetLeft;
        this.shrinkCenterY = ((this.centerY - headerHeight) * EXPAND_START_SCALE) + headerHeight;
        this.pendingUpdateLocation = false;
    }

    private final float updateDelta() {
        float screenWidth = (getScreenWidth() - this.centerX) / f.c(getScreenWidth(), getScreenHeight());
        this.deltaY = this.centerYFromHeader / f.c(getScreenWidth(), getScreenHeight());
        return (float) Math.sqrt((screenWidth * screenWidth) + (r1 * r1));
    }

    private final void updateEases() {
        EaseManager.EaseStyle easeStyle;
        float fUpdateDelta = updateDelta();
        if (fUpdateDelta == this.delayPer || MainPanelController.Companion.getLowEndAnim()) {
            return;
        }
        this.delayPer = fUpdateDelta;
        EaseManager.EaseStyle easeStyle2 = this.releaseTransYEase;
        if (easeStyle2 != null) {
            if (easeStyle2 == null) {
                o.w("releaseTransYEase");
                easeStyle2 = null;
            }
            Companion companion = Companion;
            easeStyle2.setFactors(companion.lerp(this.deltaY, 0.7f, 1.0f), companion.lerp(this.deltaY, 0.4f, 0.45f));
        } else {
            Companion companion2 = Companion;
            EaseManager.EaseStyle style = EaseManager.getStyle(-2, companion2.lerp(this.deltaY, 0.7f, 1.0f), companion2.lerp(this.deltaY, 0.4f, 0.45f));
            o.f(style, "getStyle(...)");
            this.releaseTransYEase = style;
        }
        EaseManager.EaseStyle easeStyle3 = this.shrinkXEase;
        if (easeStyle3 != null) {
            if (easeStyle3 == null) {
                o.w("shrinkXEase");
                easeStyle3 = null;
            }
            Companion companion3 = Companion;
            easeStyle3.setFactors(companion3.lerp(this.delayPer, 0.8f, 0.95f), companion3.lerp(this.delayPer, 0.3f, 0.55f));
        } else {
            Companion companion4 = Companion;
            EaseManager.EaseStyle style2 = EaseManager.getStyle(-2, companion4.lerp(this.delayPer, 0.8f, 0.95f), companion4.lerp(this.delayPer, 0.3f, 0.55f));
            o.f(style2, "getStyle(...)");
            this.shrinkXEase = style2;
        }
        EaseManager.EaseStyle easeStyle4 = this.shrinkYEase;
        if (easeStyle4 != null) {
            if (easeStyle4 == null) {
                o.w("shrinkYEase");
                easeStyle4 = null;
            }
            Companion companion5 = Companion;
            easeStyle4.setFactors(companion5.lerp(this.delayPer, 0.6f, 0.95f), companion5.lerp(this.delayPer, 0.35f, 0.45f));
        } else {
            Companion companion6 = Companion;
            EaseManager.EaseStyle style3 = EaseManager.getStyle(-2, companion6.lerp(this.delayPer, 0.6f, 0.95f), companion6.lerp(this.delayPer, 0.35f, 0.45f));
            o.f(style3, "getStyle(...)");
            this.shrinkYEase = style3;
        }
        EaseManager.EaseStyle easeStyle5 = this.showEase;
        if (easeStyle5 != null) {
            if (easeStyle5 == null) {
                o.w("showEase");
                easeStyle5 = null;
            }
            Companion companion7 = Companion;
            easeStyle5.setFactors(companion7.lerp(this.delayPer, 0.8f, 0.95f), companion7.lerp(this.delayPer, 0.4f, 0.6f));
        } else {
            Companion companion8 = Companion;
            EaseManager.EaseStyle style4 = EaseManager.getStyle(-2, companion8.lerp(this.delayPer, 0.8f, 0.95f), companion8.lerp(this.delayPer, 0.4f, 0.6f));
            o.f(style4, "getStyle(...)");
            this.showEase = style4;
        }
        EaseManager.EaseStyle easeStyle6 = this.hideEase;
        if (easeStyle6 == null) {
            Companion companion9 = Companion;
            EaseManager.EaseStyle style5 = EaseManager.getStyle(-2, companion9.lerp(this.delayPer, 0.8f, 0.9f), companion9.lerp(this.delayPer, 0.25f, 0.2f));
            o.f(style5, "getStyle(...)");
            this.hideEase = style5;
            return;
        }
        if (easeStyle6 == null) {
            o.w("hideEase");
            easeStyle = null;
        } else {
            easeStyle = easeStyle6;
        }
        Companion companion10 = Companion;
        easeStyle.setFactors(companion10.lerp(this.delayPer, 0.8f, 0.9f), companion10.lerp(this.delayPer, 0.25f, 0.2f));
    }

    public final void applyPayload(Object payload) {
        o.g(payload, "payload");
    }

    public final i calExpandedStretch(float f2, float f3, float f4, boolean z2, boolean z3) {
        if (this.pendingUpdateLocation || z3) {
            prepareLocation();
        }
        updateEases();
        float fB = f.b(f2 / f3, 0.0f);
        float fAfterFrictionValue = fB > 1.0f ? (Folme.afterFrictionValue(f2 - f3, getScreenHeight()) * 0.4f) + f3 : Companion.lerp(fB, 0.0f, f3);
        float fAfterFrictionValue2 = fB > 1.0f ? Folme.afterFrictionValue(Math.max(0.0f, f2 - f3), getScreenHeight()) : 0.0f;
        float f5 = fAfterFrictionValue - f3;
        float fPow = (fAfterFrictionValue2 * ((float) Math.pow(f4, 1.5f)) * 0.5f) + f5;
        float f6 = Float.isNaN(fPow) ? 0.0f : fPow;
        return z2 ? new i(Float.valueOf(f6), Float.valueOf(this.centerYFromHeader)) : new i(Float.valueOf(f6), Float.valueOf(f5));
    }

    public final void changeExpand(float f2, float f3, float f4, boolean z2, boolean z3) {
        EaseManager.EaseStyle easeStyle;
        if (!this.attached || MainPanelController.Companion.getLowEndAnim()) {
            return;
        }
        i iVarCalExpandedStretch$default = calExpandedStretch$default(this, f2, f3, this.deltaY, false, false, 16, null);
        if (!z3) {
            getAnim().setTo(EXPAND_TRANS_Y, iVarCalExpandedStretch$default.d(), SHRINK_EXPAND_TRANS_Y, iVarCalExpandedStretch$default.e());
            return;
        }
        IStateStyle anim = getAnim();
        FloatProperty<MainPanelItemViewHolder> floatProperty = EXPAND_TRANS_Y;
        Object objD = iVarCalExpandedStretch$default.d();
        FloatProperty<MainPanelItemViewHolder> floatProperty2 = SHRINK_EXPAND_TRANS_Y;
        Object objE = iVarCalExpandedStretch$default.e();
        AnimConfig animConfig = new AnimConfig();
        if (z2) {
            easeStyle = this.releaseTransYEase;
            if (easeStyle == null) {
                o.w("releaseTransYEase");
                easeStyle = null;
            }
        } else {
            easeStyle = expandTransYEase;
        }
        anim.to(floatProperty, objD, floatProperty2, objE, animConfig.setEase(easeStyle));
    }

    /* JADX WARN: Removed duplicated region for block: B:31:0x00b0  */
    /* JADX WARN: Removed duplicated region for block: B:35:0x00b9  */
    /* JADX WARN: Removed duplicated region for block: B:41:0x00ce  */
    /* JADX WARN: Removed duplicated region for block: B:44:0x00df  */
    /* JADX WARN: Removed duplicated region for block: B:45:0x00e5  */
    /* JADX WARN: Removed duplicated region for block: B:48:0x00f3  */
    /* JADX WARN: Removed duplicated region for block: B:49:0x00f5  */
    /* JADX WARN: Removed duplicated region for block: B:52:0x00fc  */
    /* JADX WARN: Removed duplicated region for block: B:53:0x00fe  */
    /* JADX WARN: Removed duplicated region for block: B:56:0x0105  */
    /* JADX WARN: Removed duplicated region for block: B:57:0x0107  */
    /* JADX WARN: Removed duplicated region for block: B:60:0x010e  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void changeVisible(boolean r21, boolean r22, boolean r23) {
        /*
            Method dump skipped, instruction units count: 405
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder.changeVisible(boolean, boolean, boolean):void");
    }

    public final AnimatorConfigHelper getAnimatorConfigHelper() {
        return this.animatorConfigHelper;
    }

    public final boolean getAttached$miui_controlcenter_release() {
        return this.attached;
    }

    public final float getCenterX$miui_controlcenter_release() {
        return this.centerX;
    }

    public final float getCenterY$miui_controlcenter_release() {
        return this.centerY;
    }

    public final float getCenterYFromHeader() {
        return this.centerYFromHeader;
    }

    public final Context getContext() {
        Context context = this.itemView.getContext();
        o.f(context, "getContext(...)");
        return context;
    }

    public final float getDeltaY() {
        return this.deltaY;
    }

    public final boolean getDraggable() {
        return this.draggable;
    }

    public final float getExpandAlpha() {
        return this.expandAlpha;
    }

    public final float getExpandScaleRatio() {
        return this.expandScaleRatio;
    }

    public final float getExpandTransY() {
        return this.expandTransY;
    }

    public final MainPanelListItem getItem$miui_controlcenter_release() {
        return this.item;
    }

    public final boolean getMirrorShowing() {
        return this.mirrorShowing;
    }

    public final MainPanelContent getOwner() {
        return this.owner;
    }

    public final boolean getPendingUpdateLocation() {
        return this.pendingUpdateLocation;
    }

    public final Resources getResources() {
        Resources resources = this.itemView.getResources();
        o.f(resources, "getResources(...)");
        return resources;
    }

    public float getScale() {
        return this.spreadScale * getHolderScale();
    }

    public float getScaleFactor() {
        return this.scaleFactor;
    }

    public final float getShrinkCenterX$miui_controlcenter_release() {
        return this.shrinkCenterX;
    }

    public final float getShrinkCenterY$miui_controlcenter_release() {
        return this.shrinkCenterY;
    }

    public final float getShrinkExpandTransY() {
        return this.shrinkExpandTransY;
    }

    public final float getShrinkX() {
        return this.shrinkX;
    }

    public final float getShrinkY() {
        return this.shrinkY;
    }

    public final int getSpreadRow$miui_controlcenter_release() {
        return this.spreadRow;
    }

    public final float getSpreadScale() {
        return this.spreadScale;
    }

    public final float getSpreadSlideTransX() {
        return this.spreadSlideTransX;
    }

    public void handleDimensionChange() {
    }

    public final void init() {
        updateBlendBlur();
    }

    public abstract void onConfigurationChanged(int i2);

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterViewHolder
    public void onFrameCallback() {
        if (!getIgnoreHolderAlpha()) {
            float f2 = (this.mirrorShowing && (getItemViewType() == 274442)) ? 1.0f : this.expandAlpha;
            CommonUtils commonUtils = CommonUtils.INSTANCE;
            View itemView = this.itemView;
            o.f(itemView, "itemView");
            commonUtils.setAlphaEx(itemView, getHolderAlpha() * f2);
            if (getPrintAnimateLog()) {
                float f3 = this.expandAlpha;
                if ((f3 == 0.0f || f3 == 1.0f) && this.lastPrintAlpha != f3) {
                    this.lastPrintAlpha = f3;
                    Log.d(TAG, "item frame callback " + f3 + " " + this.itemView.getAlpha() + " " + getHolderAlpha());
                }
            }
        } else if (getPrintAnimateLog()) {
            Log.d(TAG, "ignore holder alpha");
        }
        MainPanelContent mainPanelContent = this.owner;
        if (mainPanelContent != null) {
            mainPanelContent.onBrightnessChange(this.expandAlpha, this.mirrorShowing);
        }
        if (!getIgnoreHolderScale()) {
            float expandScale = getExpandScale();
            if (expandScale < 1.0f) {
                this.itemView.setScaleX(expandScale);
                this.itemView.setScaleY(expandScale);
            } else {
                AnimationUtils animationUtils = AnimationUtils.INSTANCE;
                View itemView2 = this.itemView;
                o.f(itemView2, "itemView");
                animationUtils.setFactorScale(itemView2, getScale(), getScaleFactor());
            }
        }
        MainPanelController.Companion companion = MainPanelController.Companion;
        if (!companion.getLowEndAnim()) {
            prepareLocation();
        }
        handleDimensionChange();
        if (getIgnoreHolderTranslation()) {
            return;
        }
        float fLerp = companion.getLowEndAnim() ? 0.0f : Companion.lerp(this.shrinkX, this.shrinkCenterX, this.centerX) - this.centerX;
        float fLerp2 = companion.getLowEndAnim() ? this.expandTransY : Companion.lerp(this.shrinkY, this.shrinkCenterY + this.shrinkExpandTransY, this.centerY + this.expandTransY) - this.centerY;
        this.itemView.setTranslationX(getHolderTransX() + fLerp + this.spreadSlideTransX);
        this.itemView.setTranslationY(getHolderTransY() + fLerp2);
    }

    public void onModeChanged(MainPanelController.Mode mode, boolean z2) {
        o.g(mode, "mode");
    }

    public void onStyleChanged(MainPanelController.Style style) {
        o.g(style, "style");
    }

    public final void onSuperSaveModeChanged() {
        onConfigurationChanged(MiBlurCompat.INSTANCE.getCONFIG_BLUR());
        updateBlendBlur();
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterViewHolder
    public void onViewAttachedToWindow() {
        super.onViewAttachedToWindow();
        this.expandTransY = 0.0f;
        this.shrinkExpandTransY = 0.0f;
        if (Constant.INSTANCE.getDEBUG()) {
            Log.d(getTag(), "on view attached to window " + getMirrorStarted() + " " + getMainPanelAdapter().getExpandVisble());
        }
        if (MainPanelController.Companion.getLowEndAnim()) {
            LowEndItemHolderAnimator.Companion.changeVisibleWhenAttach(this, getMainPanelAdapter().getExpandVisble());
        } else {
            changeVisible(getMainPanelAdapter().getExpandVisble(), false, getMirrorStarted());
        }
    }

    public final void setAttached$miui_controlcenter_release(boolean z2) {
        this.attached = z2;
    }

    public final void setCenterX$miui_controlcenter_release(float f2) {
        this.centerX = f2;
    }

    public final void setCenterY$miui_controlcenter_release(float f2) {
        this.centerY = f2;
    }

    public final void setDraggable(boolean z2) {
        this.draggable = z2;
    }

    public final void setExpandAlpha(float f2) {
        if (Constant.INSTANCE.getDEBUG()) {
            Log.d(getTag(), "updating expand alpha " + this.expandAlpha + " -> " + f2);
        }
        this.expandAlpha = f2;
    }

    public final void setExpandScaleRatio(float f2) {
        this.expandScaleRatio = f2;
    }

    public final void setExpandTransY(float f2) {
        this.expandTransY = f2;
    }

    public final void setItem$miui_controlcenter_release(MainPanelListItem mainPanelListItem) {
        this.item = mainPanelListItem;
    }

    public final void setMirrorShowing(boolean z2) {
        this.mirrorShowing = z2;
    }

    public final void setOwner(MainPanelContent mainPanelContent) {
        this.owner = mainPanelContent;
    }

    public final void setPendingUpdateLocation(boolean z2) {
        this.pendingUpdateLocation = z2;
    }

    public void setScaleFactor(float f2) {
        this.scaleFactor = f2;
    }

    public final void setShrinkCenterX$miui_controlcenter_release(float f2) {
        this.shrinkCenterX = f2;
    }

    public final void setShrinkCenterY$miui_controlcenter_release(float f2) {
        this.shrinkCenterY = f2;
    }

    public final void setShrinkExpandTransY(float f2) {
        this.shrinkExpandTransY = f2;
    }

    public final void setShrinkX(float f2) {
        this.shrinkX = f2;
    }

    public final void setShrinkY(float f2) {
        this.shrinkY = f2;
    }

    public final void setSpreadRow$miui_controlcenter_release(int i2) {
        this.spreadRow = i2;
    }

    public final void setSpreadScale(float f2) {
        this.spreadScale = f2;
    }

    public final void setSpreadSlideTransX(float f2) {
        this.spreadSlideTransX = f2;
    }

    public void updateBlendBlur() {
        if (!ControlCenterUtils.getBackgroundBlurOpenedInDefaultTheme(getContext())) {
            CommonUtils commonUtils = CommonUtils.INSTANCE;
            View itemView = this.itemView;
            o.f(itemView, "itemView");
            commonUtils.clearMiBlurBlendEffect(itemView);
            return;
        }
        View itemView2 = this.itemView;
        o.f(itemView2, "itemView");
        MiBlurCompat.setMiViewBlurModeCompat(itemView2, 1);
        View itemView3 = this.itemView;
        o.f(itemView3, "itemView");
        MiBlurCompat.setMiBackgroundBlendColors(itemView3, MiuiColorBlendToken.INSTANCE.getCC_TILE_DEFAULT_BLEND_COLORS());
    }

    public final void updateConfiguration(Configuration config) {
        o.g(config, "config");
        ConfigUtils configUtils = ConfigUtils.INSTANCE;
        int iUpdate = configUtils.update(this.configuration, config);
        if (iUpdate != 0) {
            onConfigurationChanged(iUpdate);
        }
        if (configUtils.blurChanged(iUpdate) || configUtils.colorsChanged(iUpdate)) {
            updateBlendBlur();
        }
    }

    public final void updateMode(MainPanelController.Mode mode, boolean z2) {
        o.g(mode, "mode");
        if (this.mode == mode) {
            return;
        }
        this.mode = mode;
        if (mode == MainPanelController.Mode.NORMAL) {
            setIgnoreHolderTranslation(false);
        }
        onModeChanged(mode, z2);
    }

    public final void updateStyle(MainPanelController.Style style) {
        o.g(style, "style");
        if (this.style == style) {
            return;
        }
        this.style = style;
        onStyleChanged(style);
    }
}
