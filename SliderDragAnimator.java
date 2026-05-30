package miui.systemui.controlcenter.panel.main.volume;

import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import androidx.lifecycle.Lifecycle;
import com.xiaomi.onetrack.util.ab;
import kotlin.jvm.internal.o;
import miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController;
import miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder;
import miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate;
import miui.systemui.controlcenter.widget.VerticalSeekBar;
import miui.systemui.controlcenter.widget.VerticalSeekBarDragAnim;
import miuix.animation.Folme;
import miuix.animation.IStateStyle;
import miuix.animation.property.FloatProperty;

/* JADX INFO: loaded from: classes.dex */
public final class SliderDragAnimator {
    public static final int HAPTIC_V2_MAX = 203;
    public static final int HAPTIC_V2_MIN = 202;
    private static final long LONG_PRESS_RELEASE_DELAY = 0;
    private static final float MAX_MOVE_DISTANCE_DEFAULT = 800.0f;
    private static final long SHORT_PRESS_RELEASE_DELAY = 100;
    public static final String TAG = "SliderDragAnimator";
    public static final String TYPE_BRIGHTNESS = "brightness";
    public static final String TYPE_BRIGHTNESS_PANEL = "brightness_panel";
    public static final String TYPE_VOLUME = "volume";
    private final int STATE_ANIMATING;
    private final int STATE_IDLE;
    private boolean abortEvent;
    private IStateStyle anim;
    private int animateState;
    private final BrightnessPanelSliderDelegate brightnessPanelSliderDelegate;
    private final BrightnessSliderController brightnessSliderController;
    private float direction;
    private float dragOffset;
    private final Lifecycle lifecycle;
    private int pressCount;
    private boolean touching;
    private float translationFactor;
    private final Handler uiHandler;
    private final VolumeSliderController volumeSliderController;
    public static final Companion Companion = new Companion(null);
    private static final SliderDragAnimator$Companion$KEY_DRAG_PROGRESS_BRIGHTNESS$1 KEY_DRAG_PROGRESS_BRIGHTNESS = new FloatProperty<SliderDragAnimator>() { // from class: miui.systemui.controlcenter.panel.main.volume.SliderDragAnimator$Companion$KEY_DRAG_PROGRESS_BRIGHTNESS$1
        @Override // miuix.animation.property.FloatProperty
        public float getValue(SliderDragAnimator sliderDragAnimator) {
            if (sliderDragAnimator != null) {
                return sliderDragAnimator.dragOffset;
            }
            return 0.0f;
        }

        @Override // miuix.animation.property.FloatProperty
        public void setValue(SliderDragAnimator sliderDragAnimator, float f2) {
            VerticalSeekBar brightnessSlider;
            if (f2 == -1.0f) {
                if (sliderDragAnimator != null) {
                    sliderDragAnimator.dragOffset = 0.0f;
                }
                Log.i(SliderDragAnimator.TAG, "brightness slider stop immediately");
            } else {
                if (sliderDragAnimator != null) {
                    sliderDragAnimator.dragOffset = Math.abs(f2);
                }
                if (sliderDragAnimator == null || (brightnessSlider = sliderDragAnimator.getBrightnessSlider()) == null) {
                    return;
                }
                VerticalSeekBarDragAnim.performDrag$default(brightnessSlider.getDragAnim(), sliderDragAnimator.dragOffset * sliderDragAnimator.direction, false, 2, null);
            }
        }
    };
    private static final SliderDragAnimator$Companion$KEY_DRAG_PROGRESS_BRIGHTNESS_PANEL$1 KEY_DRAG_PROGRESS_BRIGHTNESS_PANEL = new FloatProperty<SliderDragAnimator>() { // from class: miui.systemui.controlcenter.panel.main.volume.SliderDragAnimator$Companion$KEY_DRAG_PROGRESS_BRIGHTNESS_PANEL$1
        @Override // miuix.animation.property.FloatProperty
        public float getValue(SliderDragAnimator sliderDragAnimator) {
            if (sliderDragAnimator != null) {
                return sliderDragAnimator.dragOffset;
            }
            return 0.0f;
        }

        @Override // miuix.animation.property.FloatProperty
        public void setValue(SliderDragAnimator sliderDragAnimator, float f2) {
            VerticalSeekBar brightnessPanelSlider;
            if (f2 == -1.0f) {
                if (sliderDragAnimator != null) {
                    sliderDragAnimator.dragOffset = 0.0f;
                }
                Log.i(SliderDragAnimator.TAG, "brightness panel slider stop immediately");
            } else {
                if (sliderDragAnimator != null) {
                    sliderDragAnimator.dragOffset = Math.abs(f2);
                }
                if (sliderDragAnimator == null || (brightnessPanelSlider = sliderDragAnimator.getBrightnessPanelSlider()) == null) {
                    return;
                }
                VerticalSeekBarDragAnim.performDrag$default(brightnessPanelSlider.getDragAnim(), sliderDragAnimator.dragOffset * sliderDragAnimator.direction, false, 2, null);
            }
        }
    };
    private static final SliderDragAnimator$Companion$KEY_DRAG_PROGRESS_VOLUME$1 KEY_DRAG_PROGRESS_VOLUME = new FloatProperty<SliderDragAnimator>() { // from class: miui.systemui.controlcenter.panel.main.volume.SliderDragAnimator$Companion$KEY_DRAG_PROGRESS_VOLUME$1
        @Override // miuix.animation.property.FloatProperty
        public float getValue(SliderDragAnimator sliderDragAnimator) {
            if (sliderDragAnimator != null) {
                return sliderDragAnimator.dragOffset;
            }
            return 0.0f;
        }

        @Override // miuix.animation.property.FloatProperty
        public void setValue(SliderDragAnimator sliderDragAnimator, float f2) {
            VerticalSeekBar volumeSlider;
            if (f2 == -1.0f) {
                if (sliderDragAnimator != null) {
                    sliderDragAnimator.dragOffset = 0.0f;
                }
                Log.i(SliderDragAnimator.TAG, "volume slider stop immediately");
            } else {
                if (sliderDragAnimator != null) {
                    sliderDragAnimator.dragOffset = Math.abs(f2);
                }
                if (sliderDragAnimator == null || (volumeSlider = sliderDragAnimator.getVolumeSlider()) == null) {
                    return;
                }
                VerticalSeekBarDragAnim.performDrag$default(volumeSlider.getDragAnim(), sliderDragAnimator.dragOffset * sliderDragAnimator.direction, false, 2, null);
            }
        }
    };

    public static final class Companion {
        public /* synthetic */ Companion(kotlin.jvm.internal.h hVar) {
            this();
        }

        private Companion() {
        }
    }

    public SliderDragAnimator(VolumeSliderController volumeSliderController, BrightnessSliderController brightnessSliderController, BrightnessPanelSliderDelegate brightnessPanelSliderDelegate, Handler uiHandler, Lifecycle lifecycle) {
        o.g(uiHandler, "uiHandler");
        o.g(lifecycle, "lifecycle");
        this.volumeSliderController = volumeSliderController;
        this.brightnessSliderController = brightnessSliderController;
        this.brightnessPanelSliderDelegate = brightnessPanelSliderDelegate;
        this.uiHandler = uiHandler;
        this.lifecycle = lifecycle;
        this.direction = -1.0f;
        this.STATE_ANIMATING = 1;
        this.animateState = this.STATE_IDLE;
        this.translationFactor = 1.5f;
    }

    private final boolean allowPerformDragAnim() {
        return (this.abortEvent || this.touching || !this.lifecycle.getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final VerticalSeekBar getBrightnessPanelSlider() {
        BrightnessPanelSliderDelegate brightnessPanelSliderDelegate = this.brightnessPanelSliderDelegate;
        if (brightnessPanelSliderDelegate != null) {
            return brightnessPanelSliderDelegate.getVSlider();
        }
        return null;
    }

    private final float getBrightnessPanelSliderMaxMoveDistance() {
        VerticalSeekBarDragAnim dragAnim;
        VerticalSeekBar brightnessPanelSlider = getBrightnessPanelSlider();
        return (brightnessPanelSlider == null || (dragAnim = brightnessPanelSlider.getDragAnim()) == null) ? MAX_MOVE_DISTANCE_DEFAULT : dragAnim.getMaxMoveDistance();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final VerticalSeekBar getBrightnessSlider() {
        BrightnessSliderController brightnessSliderController = this.brightnessSliderController;
        if (brightnessSliderController != null) {
            return brightnessSliderController.getSlider();
        }
        return null;
    }

    private final float getBrightnessSliderMaxMoveDistance() {
        VerticalSeekBarDragAnim dragAnim;
        VerticalSeekBar brightnessSlider = getBrightnessSlider();
        return (brightnessSlider == null || (dragAnim = brightnessSlider.getDragAnim()) == null) ? MAX_MOVE_DISTANCE_DEFAULT : dragAnim.getMaxMoveDistance();
    }

    private final long getRelease_delay() {
        if (this.pressCount > 2) {
            return 0L;
        }
        return SHORT_PRESS_RELEASE_DELAY;
    }

    private final float getVolumeMaxMoveDistance() {
        VerticalSeekBarDragAnim dragAnim;
        VerticalSeekBar volumeSlider = getVolumeSlider();
        return (volumeSlider == null || (dragAnim = volumeSlider.getDragAnim()) == null) ? MAX_MOVE_DISTANCE_DEFAULT : dragAnim.getMaxMoveDistance();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final VerticalSeekBar getVolumeSlider() {
        ToggleSliderViewHolder sliderHolder;
        VolumeSliderController volumeSliderController = this.volumeSliderController;
        if (volumeSliderController == null || (sliderHolder = volumeSliderController.getSliderHolder()) == null) {
            return null;
        }
        return sliderHolder.getSlider();
    }

    /* JADX WARN: Removed duplicated region for block: B:21:0x007b  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private final void startDragAnim(java.lang.String r12) {
        /*
            Method dump skipped, instruction units count: 299
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: miui.systemui.controlcenter.panel.main.volume.SliderDragAnimator.startDragAnim(java.lang.String):void");
    }

    public static /* synthetic */ void stopImmediately$default(SliderDragAnimator sliderDragAnimator, long j2, VerticalSeekBar verticalSeekBar, String str, int i2, Object obj) {
        if ((i2 & 1) != 0) {
            j2 = 0;
        }
        sliderDragAnimator.stopImmediately(j2, verticalSeekBar, str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void stopImmediately$lambda$1(SliderDragAnimator this$0, long j2, String str, VerticalSeekBar verticalSeekBar) {
        VerticalSeekBarDragAnim dragAnim;
        o.g(this$0, "this$0");
        Log.i(TAG, "execute RestoreAnim " + this$0.animateState + " " + j2);
        if (this$0.animateState == this$0.STATE_ANIMATING && str != null) {
            int iHashCode = str.hashCode();
            if (iHashCode != -810883302) {
                if (iHashCode != 480139414) {
                    if (iHashCode != 648162385 || !str.equals("brightness")) {
                        return;
                    }
                    IStateStyle iStateStyle = this$0.anim;
                    if (iStateStyle != null) {
                        iStateStyle.setTo(KEY_DRAG_PROGRESS_BRIGHTNESS, -1);
                    }
                } else {
                    if (!str.equals(TYPE_BRIGHTNESS_PANEL)) {
                        return;
                    }
                    IStateStyle iStateStyle2 = this$0.anim;
                    if (iStateStyle2 != null) {
                        iStateStyle2.setTo(KEY_DRAG_PROGRESS_BRIGHTNESS_PANEL, -1);
                    }
                }
            } else {
                if (!str.equals("volume")) {
                    return;
                }
                IStateStyle iStateStyle3 = this$0.anim;
                if (iStateStyle3 != null) {
                    iStateStyle3.setTo(KEY_DRAG_PROGRESS_VOLUME, -1);
                }
            }
            if (verticalSeekBar != null && (dragAnim = verticalSeekBar.getDragAnim()) != null) {
                VerticalSeekBarDragAnim.animUpTo$default(dragAnim, 0.0f, 0.0f, 3, null);
            }
            this$0.animateState = this$0.STATE_IDLE;
        }
    }

    public final int getSTATE_ANIMATING() {
        return this.STATE_ANIMATING;
    }

    public final int getSTATE_IDLE() {
        return this.STATE_IDLE;
    }

    public final float getTranslationFactor() {
        return this.translationFactor;
    }

    public final void handleTouchEvent(MotionEvent event, VerticalSeekBar verticalSeekBar, String str) {
        o.g(event, "event");
        this.touching = (event.getActionMasked() == 1 || event.getActionMasked() == 3) ? false : true;
        if (this.animateState != this.STATE_ANIMATING) {
            return;
        }
        stopImmediately(0L, verticalSeekBar, str);
        this.abortEvent = this.touching;
    }

    public final void keyDownEvent(int i2, boolean z2, VerticalSeekBar verticalSeekBar, String str) {
        int i3 = i2 + (z2 ? 1 : -1);
        Log.i(TAG, str + " KeyDownEvent," + this.abortEvent + ab.f3599b + this.touching + ab.f3599b + this.lifecycle.getCurrentState());
        if (i3 <= (verticalSeekBar != null ? verticalSeekBar.getMax() : Integer.MAX_VALUE)) {
            if (i3 >= (verticalSeekBar != null ? verticalSeekBar.getMin() : Integer.MIN_VALUE)) {
                return;
            }
        }
        if (allowPerformDragAnim()) {
            this.direction = z2 ? -1.0f : 1.0f;
            int i4 = this.pressCount + 1;
            this.pressCount = i4;
            if (i4 == 1) {
                if (verticalSeekBar != null) {
                    verticalSeekBar.getDragAnim().setTranslationFactor(this.translationFactor);
                    verticalSeekBar.getDragAnim().updateProgress(verticalSeekBar);
                    verticalSeekBar.getDragAnim().calAnimationBound();
                }
                startDragAnim(str);
            }
            this.animateState = this.STATE_ANIMATING;
        }
    }

    public final void keyUpEvent(VerticalSeekBar verticalSeekBar, String str) {
        Log.i(TAG, str + " KeyUpEvent " + this.abortEvent + " " + this.touching + " " + this.lifecycle.getCurrentState());
        if (allowPerformDragAnim()) {
            stopImmediately(getRelease_delay(), verticalSeekBar, str);
        } else {
            this.abortEvent = false;
        }
    }

    public final void onCreate() {
        this.anim = Folme.useValue(this);
    }

    public final void onDestroy() {
        Folme.clean(this);
    }

    public final void resetAbortEvent() {
        this.abortEvent = false;
    }

    public final void resetTouchStatus() {
        this.touching = false;
    }

    public final void setTranslationFactor(float f2) {
        this.translationFactor = f2;
    }

    public final void stopImmediately(final long j2, final VerticalSeekBar verticalSeekBar, final String str) {
        this.uiHandler.postDelayed(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.a
            @Override // java.lang.Runnable
            public final void run() {
                SliderDragAnimator.stopImmediately$lambda$1(this.f5653a, j2, str, verticalSeekBar);
            }
        }, j2);
        this.pressCount = 0;
    }
}
