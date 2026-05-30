package miui.systemui.controlcenter.widget;

import G0.d;
import G0.e;
import G0.s;
import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.miui.controlcenter.ToggleSliderBase;
import kotlin.jvm.internal.h;
import kotlin.jvm.internal.o;
import kotlin.jvm.internal.p;
import miui.systemui.controlcenter.brightness.ToggleSlider;
import miui.systemui.controlcenter.windowview.GestureDispatcher;
import miui.systemui.widget.RelativeSeekBarInjector;
import q.AbstractC0785a;

/* JADX INFO: loaded from: classes.dex */
@SuppressLint({"AppCompatCustomView", "ClickableViewAccessibility"})
public class VerticalSeekBar extends SeekBar implements GestureDispatcher.GestureAcceptor, ToggleSlider {
    public static final Companion Companion = new Companion(null);
    private static final String TAG = "VerticalSeekBar";
    private String accessibilityLabel;
    public ActivityStarter activityStarter;
    private final VerticalSeekBarDragAnim dragAnim;
    private AbstractC0785a.C0175a enforcedAdmin;
    private GestureDispatcher.GestureHelper gestureHelper;
    private final d injector$delegate;
    private boolean intercepted;
    private SeekBar.OnSeekBarChangeListener onSeekBarChangeListener;
    private ProgressCallback progressCallback;
    private ToggleSliderBase.Listener toggleSliderListener;
    private final ViewTouchAnim touchAnim;
    private boolean tracking;

    public static final class Companion {
        public /* synthetic */ Companion(h hVar) {
            this();
        }

        private Companion() {
        }
    }

    public interface ProgressCallback {
        void onProgressChanged(int i2, int i3);

        void onStartTrackingTouch();

        void onStopTrackingTouch();
    }

    /* JADX INFO: renamed from: miui.systemui.controlcenter.widget.VerticalSeekBar$onAttachedToWindow$1, reason: invalid class name and case insensitive filesystem */
    public static final class C04981 extends p implements U0.a {
        public C04981() {
            super(0);
        }

        @Override // U0.a
        public /* bridge */ /* synthetic */ Object invoke() {
            m98invoke();
            return s.f193a;
        }

        /* JADX INFO: renamed from: invoke, reason: collision with other method in class */
        public final void m98invoke() {
            VerticalSeekBar.this.performLongClick();
        }
    }

    /* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */
    public VerticalSeekBar(Context context) {
        this(context, null, 0, 0, 14, null);
        o.g(context, "context");
    }

    private final RelativeSeekBarInjector getInjector() {
        return (RelativeSeekBarInjector) this.injector$delegate.getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void startTrackingTouch(SeekBar seekBar) {
        Log.w(TAG, "startTrackingTouch " + this.intercepted + " " + this.tracking);
        ProgressCallback progressCallback = this.progressCallback;
        if (progressCallback != null) {
            progressCallback.onStartTrackingTouch();
        }
        if (!this.intercepted || this.tracking) {
            return;
        }
        this.tracking = true;
        ToggleSliderBase.Listener listener = this.toggleSliderListener;
        if (listener != null) {
            listener.onChanged(this, true, false, getValue(), false);
        }
        ToggleSliderBase.Listener listener2 = this.toggleSliderListener;
        if (listener2 != null) {
            listener2.onStart(getValue());
        }
        SeekBar.OnSeekBarChangeListener onSeekBarChangeListener = this.onSeekBarChangeListener;
        if (onSeekBarChangeListener != null) {
            onSeekBarChangeListener.onStartTrackingTouch(seekBar);
        }
    }

    @Override // miui.systemui.controlcenter.windowview.GestureDispatcher.GestureAcceptor
    public GestureDispatcher.GestureHelper createGestureHelper(GestureDispatcher gestureDispatcher) {
        o.g(gestureDispatcher, "gestureDispatcher");
        GestureDispatcher.GestureHelper gestureHelper = new GestureDispatcher.GestureHelper(this, gestureDispatcher) { // from class: miui.systemui.controlcenter.widget.VerticalSeekBar.createGestureHelper.1
            {
                Boolean bool = Boolean.TRUE;
            }

            @Override // miui.systemui.controlcenter.windowview.GestureDispatcher.GestureHelper
            public void onTouchEvent(MotionEvent event) {
                o.g(event, "event");
            }
        };
        this.gestureHelper = gestureHelper;
        return gestureHelper;
    }

    /* JADX WARN: Removed duplicated region for block: B:10:0x0018  */
    @Override // android.view.View
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean dispatchTouchEvent(android.view.MotionEvent r5) {
        /*
            r4 = this;
            r0 = 0
            if (r5 == 0) goto L18
            miui.systemui.controlcenter.windowview.GestureDispatcher$GestureHelper r1 = r4.gestureHelper
            if (r1 == 0) goto L10
            boolean r1 = r1.onInterceptTouchEvent(r5)
            java.lang.Boolean r1 = java.lang.Boolean.valueOf(r1)
            goto L11
        L10:
            r1 = 0
        L11:
            if (r1 == 0) goto L18
            boolean r1 = r1.booleanValue()
            goto L19
        L18:
            r1 = r0
        L19:
            r4.intercepted = r1
            r1 = 1
            if (r5 == 0) goto L26
            int r2 = r5.getActionMasked()
            r3 = 3
            if (r2 != r3) goto L26
            r0 = r1
        L26:
            boolean r2 = r4.intercepted
            if (r2 == 0) goto L39
            boolean r2 = r4.tracking
            if (r2 != 0) goto L39
            if (r0 != 0) goto L39
            r4.tracking = r1
            android.widget.SeekBar$OnSeekBarChangeListener r0 = r4.onSeekBarChangeListener
            if (r0 == 0) goto L39
            r0.onStartTrackingTouch(r4)
        L39:
            miui.systemui.controlcenter.widget.VerticalSeekBarDragAnim r0 = r4.dragAnim
            r0.dispatchTouchEvent(r4, r5)
            boolean r4 = super.dispatchTouchEvent(r5)
            return r4
        */
        throw new UnsupportedOperationException("Method not decompiled: miui.systemui.controlcenter.widget.VerticalSeekBar.dispatchTouchEvent(android.view.MotionEvent):boolean");
    }

    public final String getAccessibilityLabel() {
        return this.accessibilityLabel;
    }

    public final ActivityStarter getActivityStarter() {
        ActivityStarter activityStarter = this.activityStarter;
        if (activityStarter != null) {
            return activityStarter;
        }
        o.w("activityStarter");
        return null;
    }

    public final VerticalSeekBarDragAnim getDragAnim() {
        return this.dragAnim;
    }

    @Override // android.widget.ProgressBar, miui.systemui.controlcenter.brightness.ToggleSlider
    public int getMax() {
        return super.getMax();
    }

    @Override // android.widget.ProgressBar, miui.systemui.controlcenter.brightness.ToggleSlider
    public int getMin() {
        return super.getMin();
    }

    public final ProgressCallback getProgressCallback() {
        return this.progressCallback;
    }

    public final ViewTouchAnim getTouchAnim() {
        return this.touchAnim;
    }

    public final boolean getTracking() {
        return this.tracking;
    }

    public int getValue() {
        return getProgress();
    }

    @Override // android.view.View
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void internalSetPadding(int i2, int i3, int i4, int i5) {
    }

    public boolean isInScrollingContainer() {
        return false;
    }

    @Override // android.widget.ProgressBar, android.view.View
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.dragAnim.setDragEnabled(true);
        this.touchAnim.setTouchView(this);
        this.touchAnim.setLongClickAction(new C04981());
    }

    @Override // android.widget.ProgressBar, android.view.View
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.dragAnim.cleanAnim();
        this.touchAnim.setLongClickAction(null);
        this.touchAnim.cleanAnim();
    }

    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        o.g(info, "info");
        super.onInitializeAccessibilityNodeInfoInternal(info);
        String str = this.accessibilityLabel;
        if (str != null) {
            info.setStateDescription(str);
        }
    }

    @Override // android.widget.AbsSeekBar, android.view.View
    public boolean onTouchEvent(MotionEvent motionEvent) {
        setEnabled(true);
        this.touchAnim.onTouchEvent(motionEvent);
        AbstractC0785a.C0175a c0175a = this.enforcedAdmin;
        if (c0175a == null) {
            getInjector().transformTouchEvent(motionEvent);
            return super.onTouchEvent(motionEvent);
        }
        getActivityStarter().postStartActivityDismissingKeyguard(AbstractC0785a.c(getContext(), c0175a), 0);
        return true;
    }

    public final void resetDragAnim() {
        this.dragAnim.resetAnim();
    }

    public final void resetTouchAnim() {
        ViewTouchAnim.endScale$default(this.touchAnim, false, 1, null);
    }

    public final void resetTouchAnimNow() {
        this.touchAnim.endScale(true);
    }

    public final void setAccessibilityLabel(String str) {
        this.accessibilityLabel = str;
    }

    public final void setActivityStarter(ActivityStarter activityStarter) {
        o.g(activityStarter, "<set-?>");
        this.activityStarter = activityStarter;
    }

    @Override // miui.systemui.controlcenter.brightness.ToggleSlider
    public void setContentDescription(String str) {
        setContentDescription((CharSequence) str);
    }

    public final void setDragView(View view) {
        this.dragAnim.setDragView(view);
    }

    @Override // miui.systemui.controlcenter.brightness.ToggleSlider
    public void setEnforcedAdmin(AbstractC0785a.C0175a c0175a) {
        this.enforcedAdmin = c0175a;
    }

    @Override // android.widget.AbsSeekBar, android.widget.ProgressBar
    public void setMax(int i2) {
        ProgressCallback progressCallback = this.progressCallback;
        if (progressCallback != null) {
            progressCallback.onProgressChanged(getProgress(), i2);
        }
        super.setMax(i2);
    }

    @Override // android.widget.AbsSeekBar, android.widget.ProgressBar, miui.systemui.controlcenter.brightness.ToggleSlider
    public void setMin(int i2) {
        super.setMin(i2);
    }

    public void setOnChangedListener(ToggleSliderBase.Listener listener) {
        ToggleSliderBase.Listener listener2 = this.toggleSliderListener;
        if (listener2 != null) {
            listener2.onStop(getProgress());
        }
        this.toggleSliderListener = listener;
    }

    @Override // android.widget.SeekBar
    public void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener onSeekBarChangeListener) {
        this.onSeekBarChangeListener = onSeekBarChangeListener;
    }

    public final void setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback;
    }

    public final void setTracking(boolean z2) {
        this.tracking = z2;
    }

    public void setValue(int i2) {
        setProgress(i2);
    }

    public final void stopTrackingTouch(SeekBar seekBar) {
        Log.w(TAG, "stopTrackingTouch");
        resetTouchAnim();
        ProgressCallback progressCallback = this.progressCallback;
        if (progressCallback != null) {
            progressCallback.onStopTrackingTouch();
        }
        ToggleSliderBase.Listener listener = this.toggleSliderListener;
        if (listener != null) {
            listener.onChanged(this, false, false, getValue(), true);
        }
        ToggleSliderBase.Listener listener2 = this.toggleSliderListener;
        if (listener2 != null) {
            listener2.onStop(getValue());
        }
        SeekBar.OnSeekBarChangeListener onSeekBarChangeListener = this.onSeekBarChangeListener;
        if (onSeekBarChangeListener != null) {
            onSeekBarChangeListener.onStopTrackingTouch(seekBar);
        }
        this.intercepted = false;
        this.tracking = false;
    }

    /* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */
    public VerticalSeekBar(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0, 0, 12, null);
        o.g(context, "context");
    }

    /* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */
    public VerticalSeekBar(Context context, AttributeSet attributeSet, int i2) {
        this(context, attributeSet, i2, 0, 8, null);
        o.g(context, "context");
    }

    public /* synthetic */ VerticalSeekBar(Context context, AttributeSet attributeSet, int i2, int i3, int i4, h hVar) {
        this(context, (i4 & 2) != 0 ? null : attributeSet, (i4 & 4) != 0 ? 0 : i2, (i4 & 8) != 0 ? 0 : i3);
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public VerticalSeekBar(Context context, AttributeSet attributeSet, int i2, int i3) {
        super(context, attributeSet, i2, i3);
        o.g(context, "context");
        this.dragAnim = new VerticalSeekBarDragAnim();
        this.touchAnim = new ViewTouchAnim(context);
        this.injector$delegate = e.b(new VerticalSeekBar$injector$2(this));
        super.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: miui.systemui.controlcenter.widget.VerticalSeekBar.1
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int i4, boolean z2) {
                ProgressCallback progressCallback = VerticalSeekBar.this.getProgressCallback();
                if (progressCallback != null) {
                    progressCallback.onProgressChanged(i4, VerticalSeekBar.this.getMax());
                }
                Log.i(VerticalSeekBar.TAG, "onProgressChanged " + i4 + " " + z2);
                ToggleSliderBase.Listener listener = VerticalSeekBar.this.toggleSliderListener;
                if (listener != null) {
                    listener.onChanged(VerticalSeekBar.this, z2, false, i4, false);
                }
                SeekBar.OnSeekBarChangeListener onSeekBarChangeListener = VerticalSeekBar.this.onSeekBarChangeListener;
                if (onSeekBarChangeListener != null) {
                    onSeekBarChangeListener.onProgressChanged(seekBar, i4, z2);
                }
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
                VerticalSeekBar.this.startTrackingTouch(seekBar);
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
                VerticalSeekBar.this.stopTrackingTouch(seekBar);
            }
        });
    }
}
