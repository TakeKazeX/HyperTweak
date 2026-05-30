package miui.systemui.controlcenter.panel.main.volume;

import G0.k;
import G0.s;
import H0.m;
import M0.l;
import android.animation.AnimatorSet;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import c1.InterfaceC0252i;
import com.android.systemui.miui.volume.VolumeColumn;
import com.android.systemui.plugins.miui.controlcenter.BrightnessControllerBase;
import com.android.systemui.plugins.miui.settings.SuperSaveModeController;
import f1.AbstractC0376g;
import f1.E;
import f1.F;
import f1.InterfaceC0387l0;
import i1.InterfaceC0423g;
import i1.u;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import kotlin.jvm.internal.A;
import kotlin.jvm.internal.o;
import kotlin.jvm.internal.r;
import miui.systemui.animation.drawable.SVGUtils;
import miui.systemui.animation.drawable.SVGUtilsExt;
import miui.systemui.animation.drawable.VectorDrawableSetParams;
import miui.systemui.broadcast.BroadcastDispatcher;
import miui.systemui.controlcenter.ConfigUtils;
import miui.systemui.controlcenter.R;
import miui.systemui.controlcenter.brightness.ToggleSlider;
import miui.systemui.controlcenter.dagger.ControlCenterScope;
import miui.systemui.controlcenter.dagger.qualifiers.Qualifiers;
import miui.systemui.controlcenter.databinding.ToggleSliderItemViewBinding;
import miui.systemui.controlcenter.events.ControlCenterEventTracker;
import miui.systemui.controlcenter.events.ControlCenterScenarioTracker;
import miui.systemui.controlcenter.panel.SecondaryPanelRouter;
import miui.systemui.controlcenter.panel.main.MainPanelContent;
import miui.systemui.controlcenter.panel.main.MainPanelController;
import miui.systemui.controlcenter.panel.main.MainPanelModeController;
import miui.systemui.controlcenter.panel.main.MainPanelStyleController;
import miui.systemui.controlcenter.panel.main.header.MessageHeaderController;
import miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder;
import miui.systemui.controlcenter.panel.main.recyclerview.MainPanelListItem;
import miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder;
import miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$volumeReceiver$2;
import miui.systemui.controlcenter.panel.secondary.VolumePanelParams;
import miui.systemui.controlcenter.panel.secondary.volume.OriginalVolumeCallback;
import miui.systemui.controlcenter.panel.secondary.volume.VolumePanelController;
import miui.systemui.controlcenter.widget.AnimateColorView;
import miui.systemui.controlcenter.widget.SliderConfig;
import miui.systemui.controlcenter.widget.VerticalSeekBar;
import miui.systemui.controlcenter.windowview.ControlCenterWindowViewController;
import miui.systemui.controlcenter.windowview.ControlCenterWindowViewImpl;
import miui.systemui.dagger.qualifiers.Background;
import miui.systemui.dagger.qualifiers.Main;
import miui.systemui.util.CommonUtils;
import miui.systemui.util.DeviceStateManagerCompat;
import miui.systemui.util.HapticFeedback;
import miui.systemui.util.MiuiColorBlendToken;
import miui.systemui.util.MiuiMathUtils;
import miui.systemui.util.VolumeUtils;
import miui.systemui.util.concurrency.DelayableExecutor;
import miui.telephony.TelephonyManager;
import miuix.animation.Folme;
import miuix.animation.IStateStyle;
import miuix.animation.base.AnimConfig;
import miuix.animation.property.FloatProperty;
import miuix.view.HapticFeedbackConstants;
import q.AbstractC0785a;
import q.AbstractC0786b;
import systemui.plugin.eventtracking.EventTracker;

/* JADX INFO: loaded from: classes.dex */
@ControlCenterScope
public final class VolumeSliderController extends MainPanelListItem.Controller<ControlCenterWindowViewImpl> implements MainPanelContent {
    private static final String AUDIO_REMOTE_DEVICES_CHANGED_ACTION = "android.media.action.AUDIO_REMOTE_DEVICES_CHANGED";
    private static final String EXTRA_STREAM_VOLUME_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final int MSG_SET_MUTE = 739;
    private static final int MSG_SET_VOLUME = 738;
    private static final int MSG_SYNC_VOLUME = 7962;
    private static final int MSG_UPDATE_SLIDER = 754337;
    private static final long SET_VOLUME_INTERVAL = 100;
    private static final String STREAM_DEVICES_CHANGED_ACTION = "android.media.STREAM_DEVICES_CHANGED_ACTION";
    private static final String STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final long SYNC_VOLUME_DELAY = 1000;
    private static final long SYNC_VOLUME_DELAY_MIPLAY = 3000;
    private static final String TAG = "VolumeSliderController";
    public static final int TYPE_VOLUME_SLIDER = 865269;
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private int afterVolume;
    private IStateStyle anim;
    private final G0.d animConfig$delegate;
    private final VectorDrawableSetParams animateIconParams;
    private int animatingValue;
    private final G0.d audioManager$delegate;
    private final DelayableExecutor bgExecutor;
    private final VolumeSliderController$bgHandler$1 bgHandler;
    private final Looper bgLooper;
    private final BrightnessControllerBase brightnessController;
    private final BroadcastDispatcher broadcastDispatcher;
    private final D0.a controlCenterWindowViewController;
    private SliderConfig.ColorState currentColorState;
    private final DeviceStateManagerCompat.FoldStateCallback foldStateCallback;
    private final HapticFeedback hapticFeedback;
    private int iconRes;
    private boolean isDragging;
    private volatile boolean isEarpiece;
    private volatile boolean isHeadSet;
    private boolean isNeedShowHeadSetIcon;
    private boolean isTouch;
    private final ToggleSliderViewHolder.Factory itemFactory;
    private final Lifecycle lifecycle;
    private final ArrayList<VolumeSliderController> listItems;
    private final VolumeSliderController$listener$1 listener;
    private boolean listening;
    private final View.OnLongClickListener longClickListener;
    private final D0.a mainPanelController;
    private final D0.a mainPanelModeController;
    private final D0.a mainPanelStyleController;
    private final G0.d mainScope$delegate;
    private int miplayDeviceType;
    private final D0.a msgHeader;
    private boolean muted;
    private boolean mutedMode;
    private int notLongPressCount;
    private final VolumeSliderController$originalVolumeCallback$1 originalVolumeCallback;
    private int preVolume;
    private int pressCount;
    private final int priority;
    private final boolean rightOrLeft;
    private final D0.a secondaryPanelRouter;
    private final VolumeSliderController$seekBarListener$1 seekBarListener;
    private InterfaceC0387l0 sharedSliderRatioJob;
    private boolean showSuperVolumeText;
    private int showSuperVolumeToast;
    private final SliderDragAnimator sliderDragAnimator;
    private volatile int sliderKeyStep;
    private int sliderMaxValue;
    private int sliderMinValue;
    private final Y0.c sliderRatio$delegate;
    private final int spanSize;
    private volatile int streamMaxVolume;
    private volatile int streamMinVolume;
    private final VolumeSliderController$styleChangeObserver$1 styleChangeObserver;
    private final SuperSaveModeController superSaveModeController;
    private String superVolumeText;
    private int superVolumeToastThreshold;
    private final boolean supportSuperVolume;
    private volatile int systemVolume;
    private boolean tracking;
    private final int type;
    private final Executor uiExecutor;
    private final VolumeSliderController$uiHandler$1 uiHandler;
    private final Looper uiLooper;
    private long userAttemptSet;
    private long userAttemptTouch;
    private final D0.a volumePanelController;
    private final G0.d volumeReceiver$delegate;
    static final /* synthetic */ InterfaceC0252i[] $$delegatedProperties = {A.d(new r(VolumeSliderController.class, "sliderRatio", "getSliderRatio()F", 0))};
    public static final Companion Companion = new Companion(null);
    private static final VolumeSliderController$Companion$SLIDER$1 SLIDER = new FloatProperty<VolumeSliderController>() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$Companion$SLIDER$1
        @Override // miuix.animation.property.FloatProperty
        public float getValue(VolumeSliderController sliderController) {
            o.g(sliderController, "sliderController");
            if (sliderController.getSlider() != null) {
                return r0.getValue();
            }
            return 0.0f;
        }

        @Override // miuix.animation.property.FloatProperty
        public void setValue(VolumeSliderController sliderController, float f2) {
            o.g(sliderController, "sliderController");
            VerticalSeekBar slider = sliderController.getSlider();
            if (slider == null) {
                return;
            }
            slider.setValue((int) f2);
        }
    };
    private static final VolumeSliderController$Companion$ICON$1 ICON = new FloatProperty<VolumeSliderController>() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$Companion$ICON$1
        @Override // miuix.animation.property.FloatProperty
        public float getValue(VolumeSliderController sliderController) {
            o.g(sliderController, "sliderController");
            return sliderController.animateIconParams.getFraction();
        }

        @Override // miuix.animation.property.FloatProperty
        public void setValue(VolumeSliderController sliderController, float f2) {
            ToggleSliderItemViewBinding binding;
            AnimateColorView animateColorView;
            o.g(sliderController, "sliderController");
            sliderController.animateIconParams.setFraction(f2);
            ToggleSliderViewHolder sliderHolder = sliderController.getSliderHolder();
            if (sliderHolder == null || (binding = sliderHolder.getBinding()) == null || (animateColorView = binding.icon) == null) {
                return;
            }
            animateColorView.invalidate();
        }
    };

    public static final class Companion {
        public /* synthetic */ Companion(kotlin.jvm.internal.h hVar) {
            this();
        }

        public final int getKeyStep(int i2) {
            if (VolumeUtils.getSUPER_VOLUME_SUPPORTED()) {
                return i2 / VolumeUtils.SUPER_VOLUME_STEP_TRANSFORM;
            }
            return 15;
        }

        private Companion() {
        }
    }

    /* JADX INFO: renamed from: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$sharedSliderRatio$1, reason: invalid class name */
    @M0.f(c = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$sharedSliderRatio$1", f = "VolumeSliderController.kt", l = {581}, m = "invokeSuspend")
    public static final class AnonymousClass1 extends l implements U0.o {
        int label;

        public AnonymousClass1(K0.d dVar) {
            super(2, dVar);
        }

        @Override // M0.a
        public final K0.d create(Object obj, K0.d dVar) {
            return VolumeSliderController.this.new AnonymousClass1(dVar);
        }

        @Override // U0.o
        public final Object invoke(E e2, K0.d dVar) {
            return ((AnonymousClass1) create(e2, dVar)).invokeSuspend(s.f193a);
        }

        @Override // M0.a
        public final Object invokeSuspend(Object obj) throws Throwable {
            Object objC = L0.c.c();
            int i2 = this.label;
            if (i2 == 0) {
                k.b(obj);
                u sliderRatioFlow = VolumeColumn.Companion.getSliderRatioFlow(3);
                final VolumeSliderController volumeSliderController = VolumeSliderController.this;
                InterfaceC0423g interfaceC0423g = new InterfaceC0423g() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController.sharedSliderRatio.1.1
                    @Override // i1.InterfaceC0423g
                    public /* bridge */ /* synthetic */ Object emit(Object obj2, K0.d dVar) {
                        return emit(((Number) obj2).floatValue(), dVar);
                    }

                    public final Object emit(float f2, K0.d dVar) {
                        if (f2 == -1.0f) {
                            return s.f193a;
                        }
                        if (volumeSliderController.getSlider() != null) {
                            VolumeColumn.Companion companion = VolumeColumn.Companion;
                            if (companion.roundTo3DecimalPlaces(f2) != companion.roundTo3DecimalPlaces(volumeSliderController.getSliderRatio()) && !volumeSliderController.tracking) {
                                Log.d(VolumeSliderController.TAG, "On slider ratio change! sliderRatio: " + f2);
                                VolumeSliderController volumeSliderController2 = volumeSliderController;
                                o.d(volumeSliderController2.getSlider());
                                volumeSliderController2.updateSliderValue((int) (f2 * r0.getMax()), false);
                                volumeSliderController.updateIconProgress(false, true);
                            }
                        }
                        return s.f193a;
                    }
                };
                this.label = 1;
                if (sliderRatioFlow.collect(interfaceC0423g, this) == objC) {
                    return objC;
                }
            } else {
                if (i2 != 1) {
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                }
                k.b(obj);
            }
            throw new G0.c();
        }
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    /* JADX WARN: Type inference failed for: r1v1, types: [miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$originalVolumeCallback$1] */
    /* JADX WARN: Type inference failed for: r3v1, types: [miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$bgHandler$1] */
    /* JADX WARN: Type inference failed for: r3v3, types: [miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$styleChangeObserver$1] */
    /* JADX WARN: Type inference failed for: r3v6, types: [miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$listener$1] */
    /* JADX WARN: Type inference failed for: r3v7, types: [miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$seekBarListener$1] */
    /* JADX WARN: Type inference failed for: r4v2, types: [android.os.Handler, miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$uiHandler$1] */
    public VolumeSliderController(@Qualifiers.WindowView ControlCenterWindowViewImpl windowView, @Qualifiers.ControlCenter Lifecycle lifecycle, ToggleSliderViewHolder.Factory itemFactory, @Main final Looper uiLooper, @Main Executor uiExecutor, @Background final Looper bgLooper, @Background DelayableExecutor bgExecutor, BroadcastDispatcher broadcastDispatcher, HapticFeedback hapticFeedback, D0.a controlCenterWindowViewController, D0.a mainPanelController, D0.a mainPanelStyleController, D0.a mainPanelModeController, D0.a secondaryPanelRouter, D0.a volumePanelController, D0.a msgHeader, BrightnessControllerBase brightnessController, SuperSaveModeController superSaveModeController) {
        super(windowView, lifecycle);
        o.g(windowView, "windowView");
        o.g(lifecycle, "lifecycle");
        o.g(itemFactory, "itemFactory");
        o.g(uiLooper, "uiLooper");
        o.g(uiExecutor, "uiExecutor");
        o.g(bgLooper, "bgLooper");
        o.g(bgExecutor, "bgExecutor");
        o.g(broadcastDispatcher, "broadcastDispatcher");
        o.g(hapticFeedback, "hapticFeedback");
        o.g(controlCenterWindowViewController, "controlCenterWindowViewController");
        o.g(mainPanelController, "mainPanelController");
        o.g(mainPanelStyleController, "mainPanelStyleController");
        o.g(mainPanelModeController, "mainPanelModeController");
        o.g(secondaryPanelRouter, "secondaryPanelRouter");
        o.g(volumePanelController, "volumePanelController");
        o.g(msgHeader, "msgHeader");
        o.g(brightnessController, "brightnessController");
        o.g(superSaveModeController, "superSaveModeController");
        this.lifecycle = lifecycle;
        this.itemFactory = itemFactory;
        this.uiLooper = uiLooper;
        this.uiExecutor = uiExecutor;
        this.bgLooper = bgLooper;
        this.bgExecutor = bgExecutor;
        this.broadcastDispatcher = broadcastDispatcher;
        this.hapticFeedback = hapticFeedback;
        this.controlCenterWindowViewController = controlCenterWindowViewController;
        this.mainPanelController = mainPanelController;
        this.mainPanelStyleController = mainPanelStyleController;
        this.mainPanelModeController = mainPanelModeController;
        this.secondaryPanelRouter = secondaryPanelRouter;
        this.volumePanelController = volumePanelController;
        this.msgHeader = msgHeader;
        this.brightnessController = brightnessController;
        this.superSaveModeController = superSaveModeController;
        this.audioManager$delegate = G0.e.b(new VolumeSliderController$audioManager$2(this));
        this.animateIconParams = new VectorDrawableSetParams();
        this.animConfig$delegate = G0.e.b(VolumeSliderController$animConfig$2.INSTANCE);
        this.supportSuperVolume = VolumeUtils.getSUPER_VOLUME_SUPPORTED();
        this.superVolumeText = VolumeUtils.getSuperVolumePercent(getContext());
        this.showSuperVolumeToast = -1;
        this.miplayDeviceType = -1;
        this.systemVolume = Integer.MIN_VALUE;
        this.animatingValue = Integer.MIN_VALUE;
        ?? r4 = new Handler(uiLooper) { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$uiHandler$1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                o.g(msg, "msg");
                if (msg.what == 754337 && this.this$0.getListening()) {
                    VolumeSliderController volumeSliderController = this.this$0;
                    Object obj = msg.obj;
                    Boolean bool = obj instanceof Boolean ? (Boolean) obj : null;
                    volumeSliderController.handleUpdateSlider(true, bool != null ? bool.booleanValue() : false);
                }
            }
        };
        this.uiHandler = r4;
        this.bgHandler = new Handler(bgLooper) { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$bgHandler$1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                o.g(msg, "msg");
                int i2 = msg.what;
                if (i2 == 738) {
                    int lastAudibleStreamVolume = this.this$0.getAudioManager().getLastAudibleStreamVolume(3);
                    int i3 = msg.arg1;
                    if (lastAudibleStreamVolume != i3) {
                        this.this$0.userAttemptSet = System.currentTimeMillis();
                        this.this$0.getAudioManager().setStreamVolume(3, i3, 0);
                        return;
                    }
                    return;
                }
                if (i2 != 739) {
                    if (i2 != 7962) {
                        return;
                    }
                    this.this$0.syncSystemVolume();
                    VolumeSliderController.updateSlider$default(this.this$0, false, 1, null);
                    return;
                }
                boolean z2 = msg.arg1 != 0;
                if (this.this$0.getAudioManager().isStreamMute(3) != z2) {
                    this.this$0.getAudioManager().adjustStreamVolume(3, z2 ? -100 : 100, 0);
                }
            }
        };
        this.foldStateCallback = new DeviceStateManagerCompat.FoldStateCallback() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$foldStateCallback$1
            @Override // miui.systemui.util.DeviceStateManagerCompat.FoldStateCallback
            public void onFoldStateChanged(boolean z2) {
                this.this$0.sliderDragAnimator.resetTouchStatus();
            }
        };
        this.styleChangeObserver = new MainPanelStyleController.OnStyleChangedListener() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$styleChangeObserver$1
            @Override // miui.systemui.controlcenter.panel.main.MainPanelStyleController.OnStyleChangedListener
            public void onStyleChanged() {
                this.this$0.sliderDragAnimator.setTranslationFactor(this.this$0.getDragTranslationFactor());
            }
        };
        this.volumeReceiver$delegate = G0.e.b(new VolumeSliderController$volumeReceiver$2(this));
        this.listener = new ToggleSlider.Listener() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$listener$1
            @Override // miui.systemui.controlcenter.brightness.ToggleSlider.Listener
            public void onChanged(boolean z2, int i2, boolean z3) {
                CommonUtils.debugLog$default(CommonUtils.INSTANCE, "VolumeSliderController", "onChanged " + z2 + " " + i2 + " " + z3, null, 4, null);
                this.this$0.tracking = z2 && !z3;
                VolumeSliderController volumeSliderController = this.this$0;
                volumeSliderController.preVolume = volumeSliderController.afterVolume;
                this.this$0.isTouch = true;
                if (z2) {
                    this.this$0.animatingValue = Integer.MIN_VALUE;
                    IStateStyle iStateStyle = this.this$0.anim;
                    if (iStateStyle != null) {
                        iStateStyle.cancel(VolumeSliderController.SLIDER);
                    }
                    this.this$0.userAttemptTouch = SystemClock.uptimeMillis();
                    VolumeSliderController volumeSliderController2 = this.this$0;
                    volumeSliderController2.setStreamVolume(volumeSliderController2.valueToVolume(i2));
                    removeMessages(7962);
                }
                VolumeSliderController.updateIconProgress$default(this.this$0, true, false, 2, null);
                VolumeSliderController.updateSuperVolume$default(this.this$0, false, 1, null);
                if (z3) {
                    Log.i("VolumeSliderController", "User finished volume change: final volume = " + this.this$0.valueToVolume(i2));
                    removeMessages(7962);
                    sendEmptyMessageDelayed(7962, this.this$0.getDelayTime());
                }
            }

            @Override // miui.systemui.controlcenter.brightness.ToggleSlider.Listener
            public void onStart(int i2) {
                this.this$0.tracking = true;
            }

            @Override // miui.systemui.controlcenter.brightness.ToggleSlider.Listener
            public void onStop(int i2) {
                this.this$0.tracking = false;
                VolumeSliderController volumeSliderController = this.this$0;
                volumeSliderController.afterVolume = volumeSliderController.valueToVolume(i2);
                if (((ControlCenterWindowViewController) this.this$0.controlCenterWindowViewController.get()).isCollapsed() && this.this$0.preVolume - this.this$0.afterVolume != 0 && this.this$0.isTouch) {
                    ControlCenterEventTracker.Companion.trackVolumeSeekbarAdjustEvent(this.this$0.sliderMaxValue, VolumeUtils.getSUPER_VOLUME_INDEX_ADD(), false, EventTracker.Companion.getScreenType(this.this$0.getContext()), this.this$0.preVolume, this.this$0.valueToVolume(i2), this.this$0.isDragging);
                    this.this$0.isTouch = false;
                }
            }
        };
        this.seekBarListener = new SeekBar.OnSeekBarChangeListener() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$seekBarListener$1
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int i2, boolean z2) {
                if (z2) {
                    this.this$0.isDragging = true;
                    if (seekBar != null && (i2 <= seekBar.getMin() || i2 >= seekBar.getMax())) {
                        if (HapticFeedback.Companion.getIS_SUPPORT_LINEAR_MOTOR_VIBRATE()) {
                            HapticFeedback.postHapticFeedback$default(this.this$0.hapticFeedback, "mesh_heavy", false, 2, null);
                        } else {
                            seekBar.performHapticFeedback(1);
                        }
                    }
                }
                if (seekBar != null) {
                    VolumeSliderController volumeSliderController = this.this$0;
                    if (volumeSliderController.tracking) {
                        volumeSliderController.setSliderRatio(i2 / seekBar.getMax());
                    }
                }
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
                ControlCenterScenarioTracker.setControlCenterScenarioState(448L, true);
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
                ControlCenterScenarioTracker.setControlCenterScenarioState(448L, false);
            }
        };
        this.longClickListener = new View.OnLongClickListener() { // from class: miui.systemui.controlcenter.panel.main.volume.e
            @Override // android.view.View.OnLongClickListener
            public final boolean onLongClick(View view) {
                return VolumeSliderController.longClickListener$lambda$3(this.f5661a, view);
            }
        };
        this.sliderDragAnimator = new SliderDragAnimator(this, null, null, r4, lifecycle);
        this.originalVolumeCallback = new OriginalVolumeCallback() { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$originalVolumeCallback$1
            @Override // miui.systemui.controlcenter.panel.secondary.volume.OriginalVolumeCallback
            public void invoke(Float f2) {
                if (f2 == null || Float.isNaN(f2.floatValue())) {
                    return;
                }
                int iB = X0.b.b(f2.floatValue());
                int iFloatValue = (int) ((f2.floatValue() * this.this$0.sliderMaxValue) / this.this$0.streamMaxVolume);
                Log.i("VolumeSliderController", "originalVolumeCallback " + f2 + " " + iB + " " + iFloatValue);
                this.this$0.updateSliderValue(iFloatValue, false);
                this.this$0.updateIconProgress(false, true);
            }
        };
        this.mainScope$delegate = G0.e.b(VolumeSliderController$mainScope$2.INSTANCE);
        Y0.a aVar = Y0.a.f959a;
        final Float fValueOf = Float.valueOf(0.0f);
        this.sliderRatio$delegate = new Y0.b(fValueOf) { // from class: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$special$$inlined$observable$1
            @Override // Y0.b
            public void afterChange(InterfaceC0252i property, Float f2, Float f3) {
                o.g(property, "property");
                float fFloatValue = f3.floatValue();
                if (f2.floatValue() == fFloatValue) {
                    return;
                }
                Log.d("VolumeSliderController", "Send slider ratio! sliderRatio: " + fFloatValue);
                VolumeColumn.Companion.getSliderRatioFlow(3).setValue(Float.valueOf(fFloatValue));
            }
        };
        this.iconRes = -1;
        this.currentColorState = SliderConfig.ColorState.HIGHLIGHT;
        this.type = TYPE_VOLUME_SLIDER;
        this.spanSize = 1;
        this.priority = 32;
        this.rightOrLeft = true;
        this.listItems = m.f(this);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void _set_isNeedShowHeadSetIcon_$lambda$0(VolumeSliderController this$0) {
        o.g(this$0, "this$0");
        updateIcon$default(this$0, false, false, true, 1, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void _set_miplayDeviceType_$lambda$1(VolumeSliderController this$0) {
        o.g(this$0, "this$0");
        updateIcon$default(this$0, false, false, true, 1, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void _set_mutedMode_$lambda$2(VolumeSliderController this$0, boolean z2) {
        o.g(this$0, "this$0");
        ToggleSliderViewHolder sliderHolder = this$0.getSliderHolder();
        if (sliderHolder == null) {
            return;
        }
        sliderHolder.setDisableState(z2);
    }

    private final void checkRestrictionAndSetEnabled() {
        this.bgExecutor.execute(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.g
            @Override // java.lang.Runnable
            public final void run() {
                VolumeSliderController.checkRestrictionAndSetEnabled$lambda$7(this.f5664a);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void checkRestrictionAndSetEnabled$lambda$7(final VolumeSliderController this$0) {
        o.g(this$0, "this$0");
        final AbstractC0785a.C0175a c0175aD = AbstractC0786b.d(this$0.getContext(), "no_adjust_volume", ((ControlCenterWindowViewController) this$0.controlCenterWindowViewController.get()).getCurrentUserId());
        if (c0175aD != null) {
            Log.i(TAG, "volume control is blocked, device is managed by admin!");
        }
        this$0.uiExecutor.execute(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.h
            @Override // java.lang.Runnable
            public final void run() {
                VolumeSliderController.checkRestrictionAndSetEnabled$lambda$7$lambda$6(this.f5665a, c0175aD);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void checkRestrictionAndSetEnabled$lambda$7$lambda$6(VolumeSliderController this$0, AbstractC0785a.C0175a c0175a) {
        o.g(this$0, "this$0");
        VerticalSeekBar slider = this$0.getSlider();
        if (slider != null) {
            slider.setEnforcedAdmin(c0175a);
        }
    }

    private final AnimConfig getAnimConfig() {
        return (AnimConfig) this.animConfig$delegate.getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final AudioManager getAudioManager() {
        return (AudioManager) this.audioManager$delegate.getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final float getDragTranslationFactor() {
        return ((MainPanelStyleController) this.mainPanelStyleController.get()).getStyle() == MainPanelController.Style.COMPACT ? 1.0f : 1.5f;
    }

    private final E getMainScope() {
        return (E) this.mainScope$delegate.getValue();
    }

    private final int getNewValueForSleepLowVolume(boolean z2) {
        return z2 ? getNewValueForSleepLowVolumeIncrease() : getNewValueForSleepLowVolumeDecrease();
    }

    private final int getNewValueForSleepLowVolumeDecrease() {
        int iVolumeToValue = volumeToValue(valueToVolume(getTargetValue()));
        float sleep_low_volume = this.sliderKeyStep * VolumeUtils.getSLEEP_LOW_VOLUME();
        if (iVolumeToValue > sleep_low_volume && iVolumeToValue <= this.sliderKeyStep) {
            return (int) sleep_low_volume;
        }
        if (iVolumeToValue > this.sliderKeyStep && iVolumeToValue < this.sliderKeyStep * 2) {
            return this.sliderKeyStep;
        }
        Integer numValueOf = Integer.valueOf(iVolumeToValue - this.sliderKeyStep);
        VerticalSeekBar slider = getSlider();
        Integer numValueOf2 = slider != null ? Integer.valueOf(slider.getMin()) : null;
        VerticalSeekBar slider2 = getSlider();
        return ((Number) b1.f.j(numValueOf, numValueOf2, slider2 != null ? Integer.valueOf(slider2.getMax()) : null)).intValue();
    }

    private final int getNewValueForSleepLowVolumeIncrease() {
        int iVolumeToValue = volumeToValue(valueToVolume(getTargetValue()));
        float sleep_low_volume = this.sliderKeyStep * VolumeUtils.getSLEEP_LOW_VOLUME();
        float f2 = iVolumeToValue;
        if (f2 < sleep_low_volume) {
            return (int) sleep_low_volume;
        }
        if (f2 >= sleep_low_volume && iVolumeToValue < this.sliderKeyStep) {
            return this.sliderKeyStep;
        }
        Integer numValueOf = Integer.valueOf(iVolumeToValue + this.sliderKeyStep);
        VerticalSeekBar slider = getSlider();
        Integer numValueOf2 = slider != null ? Integer.valueOf(slider.getMin()) : null;
        VerticalSeekBar slider2 = getSlider();
        return ((Number) b1.f.j(numValueOf, numValueOf2, slider2 != null ? Integer.valueOf(slider2.getMax()) : null)).intValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final float getSliderRatio() {
        return ((Number) this.sliderRatio$delegate.getValue(this, $$delegatedProperties[0])).floatValue();
    }

    private final boolean getSupportLongClick() {
        return (this.superSaveModeController.isActive() || ((MainPanelStyleController) this.mainPanelStyleController.get()).getStyle() == MainPanelController.Style.COMPACT) ? false : true;
    }

    private final int getTargetValue() {
        int i2 = this.animatingValue;
        if (i2 != Integer.MIN_VALUE) {
            return i2;
        }
        VerticalSeekBar slider = getSlider();
        if (slider != null) {
            return slider.getValue();
        }
        return 0;
    }

    private final VolumeSliderController$volumeReceiver$2.AnonymousClass1 getVolumeReceiver() {
        return (VolumeSliderController$volumeReceiver$2.AnonymousClass1) this.volumeReceiver$delegate.getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void handlePhoneStateChange(Intent intent) {
        String stringExtra = intent.getStringExtra("state");
        Log.d(TAG, "Phone state: " + stringExtra);
        if (o.c(stringExtra, TelephonyManager.EXTRA_STATE_OFFHOOK) || o.c(stringExtra, TelephonyManager.EXTRA_STATE_IDLE)) {
            syncVolumeRange();
            syncDeviceType();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void handleUpdateSlider(boolean z2, boolean z3) {
        VerticalSeekBar slider;
        if (this.systemVolume == Integer.MIN_VALUE || Float.isNaN(this.systemVolume) || this.tracking || (slider = getSlider()) == null) {
            return;
        }
        boolean z4 = (z2 || this.animatingValue == Integer.MIN_VALUE) ? false : true;
        boolean z5 = Math.abs((((float) this.systemVolume) / ((float) this.streamMaxVolume)) - (((float) slider.getProgress()) / ((float) slider.getMax()))) > 0.001f;
        int max = slider.getMax();
        int i2 = this.sliderMaxValue;
        if (max != i2) {
            slider.setMax(i2);
        }
        int min = slider.getMin();
        int i3 = this.sliderMinValue;
        if (min != i3) {
            slider.setMin(i3);
        }
        if (valueToVolume(getTargetValue()) != this.systemVolume || z4) {
            Log.i(TAG, "handleUpdateSlider: " + valueToVolume(getTargetValue()) + " " + this.systemVolume + " ，isVolumeRatioChange: " + z5);
            updateSliderValue(volumeToValue(this.systemVolume), z2 && z5);
        }
        updateIconProgress$default(this, z2, false, 2, null);
        updateSuperVolume(z3);
    }

    public static /* synthetic */ void handleUpdateSlider$default(VolumeSliderController volumeSliderController, boolean z2, boolean z3, int i2, Object obj) {
        if ((i2 & 1) != 0) {
            z2 = true;
        }
        if ((i2 & 2) != 0) {
            z3 = volumeSliderController.tracking;
        }
        volumeSliderController.handleUpdateSlider(z2, z3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final boolean longClickListener$lambda$3(VolumeSliderController this$0, View view) {
        o.g(this$0, "this$0");
        if (!this$0.supportLongClick()) {
            MainPanelItemViewHolder.Companion.releaseTouchNow();
            return false;
        }
        ControlCenterEventTracker.Companion.trackVolumeSeekbarLongClickEvent(EventTracker.Companion.getScreenType(this$0.getContext()), this$0.getContext().getResources().getConfiguration().orientation, "滑动条", "volume");
        if (HapticFeedback.Companion.getIS_SUPPORT_LINEAR_MOTOR_VIBRATE()) {
            this$0.hapticFeedback.longClick();
        } else {
            VerticalSeekBar slider = this$0.getSlider();
            if (slider != null) {
                slider.performHapticFeedback(0);
            }
        }
        SecondaryPanelRouter secondaryPanelRouter = (SecondaryPanelRouter) this$0.secondaryPanelRouter.get();
        ToggleSliderViewHolder sliderHolder = this$0.getSliderHolder();
        if (sliderHolder == null) {
            sliderHolder = null;
        }
        secondaryPanelRouter.routeToVolumePanel(new VolumePanelParams(sliderHolder, this$0.originalVolumeForPanel(), this$0.originalVolumeCallback));
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void onCreate$lambda$9(VolumeSliderController this$0) {
        o.g(this$0, "this$0");
        this$0.syncSystemVolume();
        this$0.syncVolumeRange();
        this$0.syncDeviceType();
        updateSlider$default(this$0, false, 1, null);
    }

    private final float originalVolumeForPanel() {
        VerticalSeekBar slider = getSlider();
        int value = slider != null ? slider.getValue() : -1;
        if (value == -1) {
            return this.systemVolume;
        }
        float f2 = (value * this.streamMaxVolume) / this.sliderMaxValue;
        Log.i(TAG, "originalVolumeForPanel " + f2 + " " + X0.b.b(f2) + " " + value);
        return f2;
    }

    private final void performKeyHapticFeedback(int i2, VerticalSeekBar verticalSeekBar) {
        boolean z2 = i2 >= verticalSeekBar.getMax();
        boolean z3 = i2 <= verticalSeekBar.getMin();
        ViewParent parent = verticalSeekBar.getParent();
        final ViewGroup viewGroup = parent instanceof ViewGroup ? (ViewGroup) parent : null;
        if (viewGroup != null) {
            if (isLongPress() && !z2 && !z3) {
                post(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.d
                    @Override // java.lang.Runnable
                    public final void run() {
                        VolumeSliderController.performKeyHapticFeedback$lambda$18$lambda$17(this.f5659a, viewGroup);
                    }
                });
                return;
            }
            if ((z3 || z2) && !isLongPress()) {
                int i3 = this.notLongPressCount + 1;
                this.notLongPressCount = i3;
                if (i3 != 1) {
                    return;
                }
                this.hapticFeedback.postHapticFeedback(z2 ? 203 : 202, viewGroup, HapticFeedbackConstants.MIUI_MESH_HEAVY);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void performKeyHapticFeedback$lambda$18$lambda$17(VolumeSliderController this$0, ViewGroup this_apply) {
        o.g(this$0, "this$0");
        o.g(this_apply, "$this_apply");
        this$0.hapticFeedback.postPerformHapticFeedback(this_apply, HapticFeedbackConstants.MIUI_MESH_LIGHT);
    }

    private final void setHeadSet(boolean z2) {
        if (this.isHeadSet == z2) {
            return;
        }
        this.isHeadSet = z2;
    }

    private final void setMiplayDeviceType(int i2) {
        if (this.miplayDeviceType == i2) {
            return;
        }
        this.miplayDeviceType = i2;
        this.uiExecutor.execute(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.i
            @Override // java.lang.Runnable
            public final void run() {
                VolumeSliderController._set_miplayDeviceType_$lambda$1(this.f5667a);
            }
        });
    }

    private final void setMuted(boolean z2) {
        boolean z3 = this.muted;
        if (z3 == z2) {
            return;
        }
        Log.i(TAG, "muted: " + z3 + " -> " + z2);
        this.muted = z2;
        ToggleSliderViewHolder sliderHolder = getSliderHolder();
        if (sliderHolder != null) {
            sliderHolder.setDisableState(this.mutedMode);
        }
        updateIcon$default(this, false, false, true, 3, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void setMutedMode(final boolean z2) {
        if (this.mutedMode == z2) {
            return;
        }
        this.mutedMode = z2;
        this.uiExecutor.execute(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.f
            @Override // java.lang.Runnable
            public final void run() {
                VolumeSliderController._set_mutedMode_$lambda$2(this.f5662a, z2);
            }
        });
    }

    private final void setNeedShowHeadSetIcon(boolean z2) {
        if (this.isNeedShowHeadSetIcon == z2) {
            return;
        }
        this.isNeedShowHeadSetIcon = z2;
        this.uiExecutor.execute(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.b
            @Override // java.lang.Runnable
            public final void run() {
                VolumeSliderController._set_isNeedShowHeadSetIcon_$lambda$0(this.f5657a);
            }
        });
    }

    private final void setShowSuperVolumeText(boolean z2) {
        if (this.showSuperVolumeText == z2) {
            return;
        }
        this.showSuperVolumeText = z2;
        ToggleSliderViewHolder sliderHolder = getSliderHolder();
        if (sliderHolder != null) {
            sliderHolder.setTopTextVisible(z2, this.superVolumeText);
        }
    }

    private final void setShowSuperVolumeToast(int i2) {
        if (this.showSuperVolumeToast == i2) {
            return;
        }
        int i3 = R.string.super_volume_toast;
        if (VolumeUtils.getSUPER_VOLUME_VERSION_P()) {
            if (i2 > 0) {
                if (i2 < VolumeUtils.getIndexAdd()) {
                    i3 = R.string.super_volume_nearly_full_toast;
                }
                MessageHeaderController messageHeaderController = (MessageHeaderController) this.msgHeader.get();
                String string = getContext().getString(i3);
                o.f(string, "getString(...)");
                messageHeaderController.showMsg(string);
            }
        } else if (this.showSuperVolumeToast <= 0 && i2 > 0) {
            MessageHeaderController messageHeaderController2 = (MessageHeaderController) this.msgHeader.get();
            String string2 = getContext().getString(i3);
            o.f(string2, "getString(...)");
            messageHeaderController2.showMsg(string2);
        }
        this.showSuperVolumeToast = i2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void setSliderRatio(float f2) {
        this.sliderRatio$delegate.setValue(this, $$delegatedProperties[0], Float.valueOf(f2));
    }

    private final void setStreamMute(boolean z2) {
        removeMessages(MSG_SET_MUTE);
        obtainMessage(MSG_SET_MUTE, z2 ? 1 : 0, 0).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0038  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void setStreamVolume(int r7) {
        /*
            r6 = this;
            int r0 = r6.miplayDeviceType
            r1 = -1
            if (r0 == r1) goto L38
            long r0 = java.lang.System.currentTimeMillis()
            long r2 = r6.userAttemptSet
            long r0 = r0 - r2
            java.lang.StringBuilder r4 = new java.lang.StringBuilder
            r4.<init>()
            java.lang.String r5 = "setStreamVolume: "
            r4.append(r5)
            r4.append(r7)
            java.lang.String r5 = " "
            r4.append(r5)
            r4.append(r0)
            r4.append(r5)
            r4.append(r2)
            java.lang.String r2 = r4.toString()
            java.lang.String r3 = "VolumeSliderController"
            android.util.Log.i(r3, r2)
            r2 = 100
            int r4 = (r0 > r2 ? 1 : (r0 == r2 ? 0 : -1))
            if (r4 >= 0) goto L38
            long r2 = r2 - r0
            goto L3a
        L38:
            r2 = 0
        L3a:
            miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$bgHandler$1 r0 = r6.bgHandler
            r1 = 738(0x2e2, float:1.034E-42)
            r0.removeMessages(r1)
            miui.systemui.controlcenter.panel.main.volume.VolumeSliderController$bgHandler$1 r6 = r6.bgHandler
            r0 = 0
            android.os.Message r7 = r6.obtainMessage(r1, r7, r0)
            r6.sendMessageDelayed(r7, r2)
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: miui.systemui.controlcenter.panel.main.volume.VolumeSliderController.setStreamVolume(int):void");
    }

    private final void setSuperVolumeText(String str) {
        ToggleSliderItemViewBinding binding;
        if (o.c(this.superVolumeText, str)) {
            return;
        }
        this.superVolumeText = str;
        ToggleSliderViewHolder sliderHolder = getSliderHolder();
        TextView textView = (sliderHolder == null || (binding = sliderHolder.getBinding()) == null) ? null : binding.topText;
        if (textView == null) {
            return;
        }
        textView.setText(this.superVolumeText);
    }

    private final void sharedSliderRatio() {
        InterfaceC0387l0 interfaceC0387l0 = this.sharedSliderRatioJob;
        if (interfaceC0387l0 != null) {
            InterfaceC0387l0.a.a(interfaceC0387l0, null, 1, null);
        }
        this.sharedSliderRatioJob = AbstractC0376g.b(getMainScope(), null, null, new AnonymousClass1(null), 3, null);
    }

    private final boolean supportLongClick() {
        if (!getSupportLongClick()) {
            Log.w(TAG, "long click: not support");
            return false;
        }
        if (((MainPanelModeController) this.mainPanelModeController.get()).getInNormalMode() && !((MainPanelModeController) this.mainPanelModeController.get()).getInPendingEditMode()) {
            return true;
        }
        Log.w(TAG, "long click: not in normal mode");
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void syncDeviceType() {
        int devicesForStream = getAudioManager().getDevicesForStream(3);
        setHeadSet(VolumeUtils.deviceIsHeadset(devicesForStream));
        setNeedShowHeadSetIcon(this.isHeadSet && !VolumeUtils.isPhoneCall(getContext()));
        this.isEarpiece = VolumeUtils.deviceIsEarpiece(devicesForStream);
        setMiplayDeviceType(VolumeUtils.deviceIsRemote(devicesForStream) ? VolumeUtils.getRemoteDeviceHardwareType(getContext()) : -1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void syncSystemVolume() {
        this.systemVolume = getAudioManager().getLastAudibleStreamVolume(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void syncVolumeRange() {
        this.streamMaxVolume = getAudioManager().getStreamMaxVolume(3);
        this.streamMinVolume = getAudioManager().getStreamMinVolume(3);
        setMutedMode(getAudioManager().isStreamMute(3));
        this.sliderMaxValue = this.streamMaxVolume * 1000;
        this.sliderMinValue = this.streamMinVolume == 0 ? 0 : this.streamMinVolume * 1000;
        this.sliderKeyStep = this.sliderMaxValue / Companion.getKeyStep(this.streamMaxVolume);
        this.superVolumeToastThreshold = VolumeUtils.getInitialMaxVolume(getContext())[3].intValue();
        this.animatingValue = Integer.MIN_VALUE;
        Log.i(TAG, "syncVolumeRange " + this.streamMaxVolume + " " + this.streamMinVolume + " " + this.sliderMaxValue + " " + this.sliderMinValue + " " + this.sliderKeyStep + " " + this.superVolumeToastThreshold + " " + this.systemVolume);
    }

    private final void updateAnimateIconParams() {
        AnimatorSet animatorSetCompat;
        ToggleSliderItemViewBinding binding;
        AnimateColorView animateColorView;
        if (SVGUtilsExt.INSTANCE.getSupportVectorDrawableParams() && !this.isNeedShowHeadSetIcon && this.iconRes == R.drawable.ic_volume_slider_animate_icon) {
            Log.i(TAG, "updateAnimateIconParams");
            ToggleSliderViewHolder sliderHolder = getSliderHolder();
            Drawable drawable = (sliderHolder == null || (binding = sliderHolder.getBinding()) == null || (animateColorView = binding.icon) == null) ? null : animateColorView.getDrawable();
            if (drawable == null) {
                return;
            }
            AnimatedVectorDrawable animatedVectorDrawable = drawable instanceof AnimatedVectorDrawable ? (AnimatedVectorDrawable) drawable : null;
            if (animatedVectorDrawable == null || (animatorSetCompat = SVGUtilsExt.getAnimatorSetCompat(animatedVectorDrawable)) == null) {
                return;
            }
            this.animateIconParams.clearDrawableParams();
            SVGUtils.analyzeAnimator(animatorSetCompat, this.animateIconParams, getContext().getColor(R.color.toggle_slider_icon_color), 0, 1500);
        }
    }

    private final void updateIcon(boolean z2, boolean z3, boolean z4) {
        ToggleSliderItemViewBinding binding;
        AnimateColorView animateColorView;
        int iUpdateMiPlayIcon = this.miplayDeviceType != -1 ? updateMiPlayIcon() : SVGUtilsExt.INSTANCE.getSupportVectorDrawableParams() ? this.isNeedShowHeadSetIcon ? this.muted ? R.drawable.ic_miui_volume_headset_mute : R.drawable.ic_miui_volume_headset : R.drawable.ic_volume_slider_animate_icon : this.muted ? this.isNeedShowHeadSetIcon ? R.drawable.ic_miui_volume_headset_mute : R.drawable.ic_volume_slider_media_disabled : this.isNeedShowHeadSetIcon ? R.drawable.ic_miui_volume_headset : R.drawable.ic_volume_slider_media_enabled;
        if (this.iconRes != iUpdateMiPlayIcon || z2) {
            this.iconRes = iUpdateMiPlayIcon;
            ToggleSliderViewHolder sliderHolder = getSliderHolder();
            if (sliderHolder != null && (binding = sliderHolder.getBinding()) != null && (animateColorView = binding.icon) != null) {
                animateColorView.setImageResource(this.iconRes);
            }
            updateAnimateIconParams();
        }
        updateIconProgress(z3, z4);
    }

    public static /* synthetic */ void updateIcon$default(VolumeSliderController volumeSliderController, boolean z2, boolean z3, boolean z4, int i2, Object obj) {
        if ((i2 & 1) != 0) {
            z2 = false;
        }
        if ((i2 & 2) != 0) {
            z3 = true;
        }
        if ((i2 & 4) != 0) {
            z4 = false;
        }
        volumeSliderController.updateIcon(z2, z3, z4);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void updateIconProgress(boolean z2, boolean z3) {
        VerticalSeekBar slider;
        float f2;
        IStateStyle iStateStyle;
        ToggleSliderItemViewBinding binding;
        AnimateColorView animateColorView;
        setMuted(getTargetValue() <= this.sliderMinValue || this.mutedMode);
        if (SVGUtilsExt.INSTANCE.getSupportVectorDrawableParams() && (slider = getSlider()) != null) {
            float fRoundTo3DecimalPlaces = VolumeColumn.Companion.roundTo3DecimalPlaces(MiuiMathUtils.INSTANCE.lerpInv(slider.getMin(), slider.getMax(), getTargetValue()));
            if (this.muted || fRoundTo3DecimalPlaces == 0.0f) {
                f2 = 0.0f;
            } else {
                f2 = 0.33333334f;
                if (fRoundTo3DecimalPlaces >= 0.33333334f) {
                    f2 = 0.6666667f;
                    if (fRoundTo3DecimalPlaces >= 0.6666667f) {
                        f2 = 1.0f;
                    }
                }
            }
            ToggleSliderViewHolder sliderHolder = getSliderHolder();
            if (sliderHolder != null && (binding = sliderHolder.getBinding()) != null && (animateColorView = binding.icon) != null) {
                if (fRoundTo3DecimalPlaces < 0.12f || this.muted) {
                    SliderConfig.ColorState colorState = this.currentColorState;
                    SliderConfig.ColorState colorState2 = SliderConfig.ColorState.NORMAL;
                    if (colorState != colorState2 || z3) {
                        MiuiColorBlendToken miuiColorBlendToken = MiuiColorBlendToken.INSTANCE;
                        animateColorView.updateIconColor(miuiColorBlendToken.getCC_VOLUME_SLIDER_ICON_BLEND_COLORS(), miuiColorBlendToken.getCC_VOLUME_SLIDER_ICON_BLEND_COLORS_DEFAULT(), R.color.toggle_slider_volume_icon_color, R.color.toggle_slider_icon_color, !z3);
                        this.currentColorState = colorState2;
                    }
                } else {
                    SliderConfig.ColorState colorState3 = this.currentColorState;
                    SliderConfig.ColorState colorState4 = SliderConfig.ColorState.HIGHLIGHT;
                    if (colorState3 != colorState4 || z3) {
                        MiuiColorBlendToken miuiColorBlendToken2 = MiuiColorBlendToken.INSTANCE;
                        animateColorView.updateIconColor(miuiColorBlendToken2.getCC_VOLUME_SLIDER_ICON_BLEND_COLORS_DEFAULT(), miuiColorBlendToken2.getCC_VOLUME_SLIDER_ICON_BLEND_COLORS(), R.color.toggle_slider_icon_color, R.color.toggle_slider_volume_icon_color, !z3);
                        this.currentColorState = colorState4;
                    }
                }
                animateColorView.invalidate();
            }
            if (this.isNeedShowHeadSetIcon || (iStateStyle = this.anim) == null) {
                return;
            }
            float fH = b1.f.h(f2, 5.0E-4f, 1.0f);
            if (z2) {
                iStateStyle.to(ICON, Float.valueOf(fH), getAnimConfig());
                return;
            }
            VolumeSliderController$Companion$ICON$1 volumeSliderController$Companion$ICON$1 = ICON;
            iStateStyle.setTo(volumeSliderController$Companion$ICON$1, Float.valueOf(0.0f));
            iStateStyle.setTo(volumeSliderController$Companion$ICON$1, Float.valueOf(fH));
        }
    }

    public static /* synthetic */ void updateIconProgress$default(VolumeSliderController volumeSliderController, boolean z2, boolean z3, int i2, Object obj) {
        if ((i2 & 2) != 0) {
            z3 = false;
        }
        volumeSliderController.updateIconProgress(z2, z3);
    }

    private final int updateMiPlayIcon() {
        G0.i miPlayIconRes = VolumeUtils.getMiPlayIconRes(getContext());
        return this.muted ? ((Number) miPlayIconRes.b()).intValue() : ((Number) miPlayIconRes.a()).intValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void updateSlider(boolean z2) {
        if (SystemClock.uptimeMillis() - this.userAttemptTouch < getDelayTime()) {
            removeMessages(MSG_SYNC_VOLUME);
            sendEmptyMessageAtTime(MSG_SYNC_VOLUME, this.userAttemptTouch + getDelayTime());
        } else {
            removeMessages(MSG_UPDATE_SLIDER);
            obtainMessage(MSG_UPDATE_SLIDER, Boolean.valueOf(z2)).sendToTarget();
        }
    }

    public static /* synthetic */ void updateSlider$default(VolumeSliderController volumeSliderController, boolean z2, int i2, Object obj) {
        if ((i2 & 1) != 0) {
            z2 = volumeSliderController.tracking;
        }
        volumeSliderController.updateSlider(z2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void updateSliderValue(int i2, boolean z2) {
        if (getSliderHolder() != null) {
            if (z2) {
                this.animatingValue = i2;
                IStateStyle iStateStyle = this.anim;
                if (iStateStyle != null) {
                    iStateStyle.to(SLIDER, Integer.valueOf(i2), getAnimConfig());
                    return;
                }
                return;
            }
            IStateStyle iStateStyle2 = this.anim;
            if (iStateStyle2 != null) {
                iStateStyle2.cancel(SLIDER);
            }
            this.animatingValue = Integer.MIN_VALUE;
            IStateStyle iStateStyle3 = this.anim;
            if (iStateStyle3 != null) {
                iStateStyle3.setTo(SLIDER, Integer.valueOf(i2));
            }
        }
    }

    public static /* synthetic */ void updateSliderValue$default(VolumeSliderController volumeSliderController, int i2, boolean z2, int i3, Object obj) {
        if ((i3 & 2) != 0) {
            z2 = false;
        }
        volumeSliderController.updateSliderValue(i2, z2);
    }

    private final void updateSuperVolume(boolean z2) {
        boolean z3 = false;
        boolean z4 = (!this.supportSuperVolume || this.isHeadSet || this.isEarpiece) ? false : true;
        int iValueToVolume = valueToVolume(getTargetValue());
        int i2 = this.superVolumeToastThreshold;
        if (z4 && !VolumeUtils.MEDIA_VOL_STEPS_IS_15) {
            int i3 = VolumeUtils.SUPER_VOLUME_STEP_TRANSFORM;
            iValueToVolume /= i3;
            i2 /= i3;
        }
        int i4 = iValueToVolume - i2;
        boolean z5 = VolumeUtils.getSUPER_VOLUME_VERSION_P() && VolumeUtils.getIndexAdd() > 1 && i4 > 0 && i4 < VolumeUtils.getIndexAdd();
        if (z4) {
            setSuperVolumeText(VolumeUtils.getSuperVolumePercent(getContext(), i4));
        }
        if (z4 && (getTargetValue() >= this.sliderMaxValue || z5)) {
            z3 = true;
        }
        setShowSuperVolumeText(z3);
        if (z4 && z2 && ((SecondaryPanelRouter) this.secondaryPanelRouter.get()).getInMainPanel()) {
            setShowSuperVolumeToast(i4);
        }
    }

    public static /* synthetic */ void updateSuperVolume$default(VolumeSliderController volumeSliderController, boolean z2, int i2, Object obj) {
        if ((i2 & 1) != 0) {
            z2 = volumeSliderController.tracking;
        }
        volumeSliderController.updateSuperVolume(z2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final int valueToVolume(int i2) {
        return b1.f.i(VolumeUtils.progressToLevel(this.sliderMaxValue, i2), this.streamMinVolume, this.streamMaxVolume);
    }

    private final int volumeToValue(int i2) {
        float fH = b1.f.h((i2 * this.sliderMaxValue) / this.streamMaxVolume, this.sliderMinValue, this.sliderMaxValue);
        if (!Float.isNaN(fH)) {
            return X0.b.b(fH);
        }
        Log.w(TAG, "volumeToValue: NaN");
        return 0;
    }

    @Override // miui.systemui.controlcenter.panel.main.MainPanelContent
    public boolean available(boolean z2) {
        return ((MainPanelModeController) this.mainPanelModeController.get()).getMode() != MainPanelController.Mode.EDIT;
    }

    public final void changeMuteMode() {
        if (getSlider() == null || this.tracking) {
            return;
        }
        setStreamMute(!this.mutedMode);
    }

    public final void changeVolume(boolean z2) {
        int i2;
        this.pressCount++;
        VerticalSeekBar slider = getSlider();
        if (slider == null || this.tracking) {
            return;
        }
        this.isDragging = false;
        if (VolumeUtils.getSLEEP_LOW_VOLUME() > 0.0f) {
            i2 = getNewValueForSleepLowVolume(z2);
        } else {
            int targetValue = getTargetValue();
            int i3 = this.sliderKeyStep;
            if (!z2) {
                i3 = -i3;
            }
            i2 = b1.f.i(targetValue + i3, slider.getMin(), slider.getMax());
        }
        Log.d(TAG, "changeVolume increase:" + z2 + " newValue:" + i2 + " targetValue:" + getTargetValue());
        if (getListening() && ((SecondaryPanelRouter) this.secondaryPanelRouter.get()).getInMainPanel()) {
            this.sliderDragAnimator.keyDownEvent(getTargetValue(), z2, slider, "volume");
            performKeyHapticFeedback(i2, slider);
        }
        if (i2 == getTargetValue()) {
            return;
        }
        int i4 = b1.f.i(valueToVolume(i2), this.streamMinVolume, this.streamMaxVolume);
        this.userAttemptTouch = 0L;
        setStreamVolume(i4);
        removeMessages(MSG_SYNC_VOLUME);
        sendEmptyMessageDelayed(MSG_SYNC_VOLUME, getDelayTime());
        ((MainPanelController) this.mainPanelController.get()).smoothScrollToTop();
    }

    @Override // miui.systemui.controlcenter.panel.main.MainPanelContent
    public MainPanelItemViewHolder createViewHolder(ViewGroup parent, int i2) {
        o.g(parent, "parent");
        if (i2 != 865269) {
            return null;
        }
        ToggleSliderItemViewBinding toggleSliderItemViewBindingInflate = ToggleSliderItemViewBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        o.f(toggleSliderItemViewBindingInflate, "inflate(...)");
        ToggleSliderViewHolder toggleSliderViewHolderCreate = this.itemFactory.create(toggleSliderItemViewBindingInflate);
        toggleSliderItemViewBindingInflate.slider.setMax(this.sliderMaxValue);
        return toggleSliderViewHolderCreate;
    }

    public final long getDelayTime() {
        return this.miplayDeviceType == -1 ? 1000L : 3000L;
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelListItem.Controller
    public boolean getListening() {
        return this.listening;
    }

    @Override // miui.systemui.controlcenter.panel.main.MainPanelContent
    public int getPriority() {
        return this.priority;
    }

    @Override // miui.systemui.controlcenter.panel.main.MainPanelContent
    public boolean getRightOrLeft() {
        return this.rightOrLeft;
    }

    public final VerticalSeekBar getSlider() {
        ToggleSliderViewHolder sliderHolder = getSliderHolder();
        if (sliderHolder != null) {
            return sliderHolder.getSlider();
        }
        return null;
    }

    public final ToggleSliderViewHolder getSliderHolder() {
        MainPanelItemViewHolder holder = getHolder();
        if (holder instanceof ToggleSliderViewHolder) {
            return (ToggleSliderViewHolder) holder;
        }
        return null;
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelListItem
    public int getSpanSize() {
        return this.spanSize;
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelListItem
    public int getType() {
        return this.type;
    }

    public final void handleMainPanelShown() {
        this.sliderDragAnimator.resetAbortEvent();
    }

    public final void handlePanelTouchEvent(MotionEvent ev) {
        o.g(ev, "ev");
        if (getListening()) {
            this.sliderDragAnimator.handleTouchEvent(ev, getSlider(), "volume");
        }
    }

    public final boolean isLongPress() {
        return this.pressCount > 2;
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelListItem
    public void onBindViewHolder() {
        ToggleSliderItemViewBinding binding;
        VerticalSeekBar slider = getSlider();
        if (slider != null) {
            slider.setContentDescription(slider.getContext().getString(R.string.controls_media_title));
        }
        if (this.supportSuperVolume) {
            ToggleSliderViewHolder sliderHolder = getSliderHolder();
            TextView textView = (sliderHolder == null || (binding = sliderHolder.getBinding()) == null) ? null : binding.topText;
            if (textView != null) {
                textView.setText(this.superVolumeText);
            }
        }
        updateIcon$default(this, true, false, true, 2, null);
        handleUpdateSlider$default(this, false, false, 2, null);
    }

    @Override // miui.systemui.controlcenter.utils.ControlCenterViewController, miui.systemui.controlcenter.ConfigUtils.OnConfigChangeListener
    public void onConfigurationChanged(int i2) {
        ToggleSliderViewHolder sliderHolder;
        ToggleSliderViewHolder sliderHolder2;
        ToggleSliderItemViewBinding binding;
        TextView textView;
        ToggleSliderItemViewBinding binding2;
        ConfigUtils configUtils = ConfigUtils.INSTANCE;
        boolean zDimensionsChanged = configUtils.dimensionsChanged(i2);
        if (configUtils.textsChanged(i2)) {
            VerticalSeekBar slider = getSlider();
            if (slider != null) {
                slider.setContentDescription(getContext().getString(R.string.controls_media_title));
            }
            if (this.supportSuperVolume) {
                ToggleSliderViewHolder sliderHolder3 = getSliderHolder();
                TextView textView2 = (sliderHolder3 == null || (binding2 = sliderHolder3.getBinding()) == null) ? null : binding2.topText;
                if (textView2 != null) {
                    textView2.setText(this.superVolumeText);
                }
            }
        }
        if (configUtils.textAppearanceChanged(i2) && this.supportSuperVolume && (sliderHolder2 = getSliderHolder()) != null && (binding = sliderHolder2.getBinding()) != null && (textView = binding.topText) != null) {
            textView.setTextAppearance(R.style.TextAppearance_VolumeSlider);
        }
        if (zDimensionsChanged && (sliderHolder = getSliderHolder()) != null) {
            sliderHolder.updateSize();
        }
        if (configUtils.colorsChanged(i2) || configUtils.blurChanged(i2)) {
            updateIcon$default(this, true, false, true, 2, null);
            handleUpdateSlider$default(this, false, false, 3, null);
        }
    }

    @Override // miui.systemui.util.ViewController
    public void onCreate() {
        this.anim = Folme.useValue(this);
        this.bgExecutor.execute(new Runnable() { // from class: miui.systemui.controlcenter.panel.main.volume.c
            @Override // java.lang.Runnable
            public final void run() {
                VolumeSliderController.onCreate$lambda$9(this.f5658a);
            }
        });
        BroadcastDispatcher broadcastDispatcher = this.broadcastDispatcher;
        VolumeSliderController$volumeReceiver$2.AnonymousClass1 volumeReceiver = getVolumeReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.media.VOLUME_CHANGED_ACTION");
        intentFilter.addAction(STREAM_DEVICES_CHANGED_ACTION);
        intentFilter.addAction(STREAM_MUTE_CHANGED_ACTION);
        intentFilter.addAction(AUDIO_REMOTE_DEVICES_CHANGED_ACTION);
        intentFilter.addAction("android.intent.action.PHONE_STATE");
        s sVar = s.f193a;
        BroadcastDispatcher.registerReceiver$default(broadcastDispatcher, volumeReceiver, intentFilter, this.bgExecutor, null, 8, null);
        this.sliderDragAnimator.onCreate();
        ((ControlCenterWindowViewController) this.controlCenterWindowViewController.get()).addFoldStateCallback(this.foldStateCallback);
        ((MainPanelStyleController) this.mainPanelStyleController.get()).addOnStyleChangedListener(this.styleChangeObserver);
        this.sliderDragAnimator.setTranslationFactor(getDragTranslationFactor());
        sharedSliderRatio();
    }

    @Override // miui.systemui.controlcenter.utils.ControlCenterViewController
    public void onDestroy() {
        ToggleSliderItemViewBinding binding;
        AnimateColorView animateColorView;
        this.broadcastDispatcher.unregisterReceiver(getVolumeReceiver());
        Folme.clean(this);
        this.sliderDragAnimator.onDestroy();
        ToggleSliderViewHolder sliderHolder = getSliderHolder();
        if (sliderHolder != null && (binding = sliderHolder.getBinding()) != null && (animateColorView = binding.icon) != null) {
            animateColorView.recycle();
        }
        ((ControlCenterWindowViewController) this.controlCenterWindowViewController.get()).removeFoldStateCallback(this.foldStateCallback);
        ((MainPanelStyleController) this.mainPanelStyleController.get()).removeOnStyleChangedListener(this.styleChangeObserver);
        F.e(getMainScope(), null, 1, null);
    }

    @Override // miui.systemui.controlcenter.utils.ControlCenterViewController
    public void onPause() {
        super.onPause();
        this.sliderDragAnimator.stopImmediately(0L, getSlider(), "volume");
    }

    @Override // miui.systemui.controlcenter.utils.ControlCenterViewController
    public void onSuperPowerModeChanged(boolean z2) {
        VerticalSeekBar slider;
        if (!getListening() || (slider = getSlider()) == null) {
            return;
        }
        slider.setOnLongClickListener(getSupportLongClick() ? this.longClickListener : null);
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelListItem
    public void onUnbindViewHolder() {
        setListening(false);
    }

    @Override // miui.systemui.controlcenter.panel.main.recyclerview.MainPanelListItem.Controller
    public void setListening(boolean z2) {
        if (this.listening == z2) {
            return;
        }
        this.listening = z2;
        ((VolumePanelController) this.volumePanelController.get()).setListening(z2);
        if (!z2) {
            this.tracking = false;
            VerticalSeekBar slider = getSlider();
            if (slider != null) {
                slider.setOnLongClickListener(null);
                slider.setOnChangedListener(null);
                slider.setOnSeekBarChangeListener(null);
                return;
            }
            return;
        }
        ToggleSliderViewHolder sliderHolder = getSliderHolder();
        if (sliderHolder != null) {
            sliderHolder.setDisableState(this.mutedMode);
        }
        checkRestrictionAndSetEnabled();
        handleUpdateSlider$default(this, false, false, 3, null);
        VerticalSeekBar slider2 = getSlider();
        if (slider2 != null) {
            slider2.setOnLongClickListener(getSupportLongClick() ? this.longClickListener : null);
            slider2.setOnChangedListener(this.listener);
            slider2.setOnSeekBarChangeListener(this.seekBarListener);
        }
    }

    public final void volumeKeyUp() {
        this.pressCount = 0;
        this.notLongPressCount = 0;
        if (!this.tracking && getListening() && ((SecondaryPanelRouter) this.secondaryPanelRouter.get()).getInMainPanel()) {
            this.sliderDragAnimator.keyUpEvent(getSlider(), "volume");
        }
    }

    @Override // miui.systemui.controlcenter.panel.main.MainPanelContent
    public ArrayList<VolumeSliderController> getListItems() {
        return this.listItems;
    }
}
