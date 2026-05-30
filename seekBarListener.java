package miui.systemui.controlcenter.panel.main.brightness;

import android.util.Log;
import android.widget.SeekBar;
import androidx.lifecycle.Lifecycle;
import miui.systemui.controlcenter.events.ControlCenterScenarioTracker;
import miui.systemui.controlcenter.panel.SecondaryPanelRouter;
import miui.systemui.controlcenter.windowview.ControlCenterWindowViewImpl;
import miui.systemui.util.HapticFeedback;

/* JADX INFO: loaded from: classes.dex */
public final class BrightnessSliderController$seekBarListener$1 implements SeekBar.OnSeekBarChangeListener {
    final /* synthetic */ ControlCenterWindowViewImpl $windowView;
    final /* synthetic */ BrightnessSliderController this$0;

    public BrightnessSliderController$seekBarListener$1(BrightnessSliderController brightnessSliderController, ControlCenterWindowViewImpl controlCenterWindowViewImpl) {
        this.this$0 = brightnessSliderController;
        this.$windowView = controlCenterWindowViewImpl;
    }

    @Override // android.widget.SeekBar.OnSeekBarChangeListener
    public void onProgressChanged(SeekBar seekBar, int i2, boolean z2) {
        Log.i("BrightnessSliderController", "onProgressChanged " + z2 + " " + i2);
        BrightnessSliderController.updateIconProgress$default(this.this$0, false, 1, null);
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
        if (this.this$0.isInEditMode || !((SecondaryPanelRouter) this.this$0.secondaryPanelRouter.get()).getInMainPanel()) {
            return;
        }
        Log.i("BrightnessSliderController", "onStartTrackingTouch");
        this.this$0.preValue = seekBar != null ? seekBar.getProgress() : 0;
        this.this$0.isTouch = true;
        this.$windowView.requestBlockPointerDown("BrightnessSliderController", true);
        this.this$0.mirrorLifecycle.setCurrentState(Lifecycle.State.STARTED);
        ControlCenterScenarioTracker.setControlCenterScenarioState(447L, true);
    }

    @Override // android.widget.SeekBar.OnSeekBarChangeListener
    public void onStopTrackingTouch(SeekBar seekBar) {
        Log.i("BrightnessSliderController", "onStopTrackingTouch");
        this.this$0.afterValue = seekBar != null ? seekBar.getProgress() : 0;
        this.$windowView.requestBlockPointerDown("BrightnessSliderController", false);
        this.this$0.mirrorLifecycle.setCurrentState(Lifecycle.State.CREATED);
        this.this$0.needCallStopTrackingTouchMethod = false;
        ControlCenterScenarioTracker.setControlCenterScenarioState(447L, false);
    }
}
