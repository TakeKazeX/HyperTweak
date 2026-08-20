# HyperTweak Project Reference

This file records verifiable project facts. Agent execution rules live in
[`AGENTS.md`](AGENTS.md).

## Overview

HyperTweak is a Xiaomi HyperOS/MIUI libxposed API 102 module. Its settings UI
uses Jetpack Compose with Miuix components. The module provides system and
application hooks for customization across the processes listed in
`app/src/main/resources/META-INF/xposed/scope.list`.

## Toolchain and Dependencies

- Android `compileSdk 37`, `targetSdk 37`, `minSdk 35`; Java source and target 25.
- Android Gradle Plugin `9.3.0`; Kotlin Compose plugin `2.4.10`.
- libxposed API and service `102.0.0`; Miuix `0.9.3`; DexKit `2.2.0`.
- Native packaging is limited to `arm64-v8a`.

## Layout

Kotlin sources are under `app/src/main/kotlin/com/takekazex/hypertweak/`:

- `hook/` contains the entry point and hook implementation. `hook/base/` holds
  shared hook infrastructure; `hook/rules/*` contains feature-specific rules.
- `ui/page`, `ui/effect`, `ui/liquid`, `ui/navigation`, and `ui/theme` contain
  Compose screens and UI infrastructure.
- `util/` contains shared utilities. JVM tests are under `app/src/test/`.

## Key Components

- `HookEntry` dispatches module initialization for each process.
- `Preferences` owns runtime preference access and cross-process synchronization.
- `XposedServiceManager` manages the libxposed service boundary.
- `BaseHooker` provides common hook lifecycle and resolution behavior.
- `DexKitManager` resolves obfuscated classes with cached runtime metadata.

Hook handles and hot-reload state belong to the hook lifecycle layer; runtime
preference changes are consumed through `Preferences` and must not be coupled
to UI-only state.

## Scope and Features

The scope file is the single source of truth. It currently contains:

```text
system
com.android.systemui
com.miui.home
com.takekazex.hypertweak
com.android.settings
com.miui.aod
com.miui.securitycenter
com.miui.powerkeeper
com.xiaomi.scanner
com.milink.service
com.xiaomi.bluetooth
com.google.android.gms
com.miui.mediaeditor
```

These cover the system server, SystemUI, Launcher, the module's own process,
Settings, AOD, Security Center, PowerKeeper, Scanner, MiLink, Bluetooth, Google
Play services, and the media editor (相册编辑).

Feature areas include system/SystemUI hooks, slider percentage display, an AOSP
back gesture, AOD/fingerprint/navigation-bar behavior, lockscreen status-bar
suppression, Settings injection, restart-scope controls, logging, and theme/UI
settings. `HideLockscreenStatusBarHooker` targets the lockscreen-only
`MiuiKeyguardStatusBarView` and holds it at `INVISIBLE`/zero alpha so lockscreen
and full-screen AOD animations cannot restore its clock or status icons. The
setting requires a SystemUI restart. `GestureBarActionHooker` owns optional
long-press and double-tap actions in SystemUI, without a Launcher hook. Its only
recognizer is a SystemUI gesture `InputMonitor` that pilfers pointers once a
gesture is recognized inside the handle region, so recognition works wherever
SystemUI wins ownership and yields to Launcher everywhere else.

## Lockscreen Notification Fingerprint Avoidance

Settings → Experimental → Lockscreen Fingerprint Avoid
(`KEY_LOCKSCREEN_FINGERPRINT_AVOID`, `KeyguardFingerprintAvoidHooker`) overrides
how SystemUI keeps lockscreen notifications clear of the in-display (GXZW)
fingerprint icon. Three modes: 0 follow the system, 1 no avoidance, 2 always
avoid. OS4-only (the UI entry is gated on `PlatformLevel.isOs4`); requires a
SystemUI restart and flows through `TWEAK_RESTART_SCOPES`, so the standard
"Restart Scoped Apps" dialog covers it. It is one of the few Int-typed (selector)
tweaks in that machinery: `markTweakedInt`/`currentTweakValueInt` store an Int
baseline in the same `tweak_baseline_` slot, and `clearRestartedScopes` writes
that slot as an Int — mixing a Boolean baseline into it would crash the next
`getInt` read.

The mechanism (OS4.0.0.15.XPMCNXM): `KeyguardPanelViewController.nsslLockYPosition`
is a `StateFlow<Triple<Int,Int,Int>>` combining seven flows (two `nssl_lock_y`
paddings, `keyguard_affordance_fixed_height`, `_remoteViewY`, `_indicationAreaTop`,
`fingerApplyForKeyguard` = `Settings.Secure miui_keyguard` **== 2**, and the
enrolled-templates flow). When `MiuiConfigs.GXZW_SENSOR` (ro.hardware.fp.fod) &&
fingerprint applies && templates are enrolled, the stack bottom bound becomes
`MiuiGxzwUtils.GXZW_ICON_Y + offset − 20dp` (above the icon); otherwise it falls
back to the indication area. The triple feeds
`MiuiKeyguardRepositoryImpl.notificationBottomOnKeyguard` (which on
`isGxzwLowPosition` devices additionally subtracts the number-state view height +
10dp so the 通知数量 view sits between the stack and the icon) and every
stack/list/number strategy in `com.miui.systemui.notification.view.strategy.*`.

The hook intercepts the combine lambda
`KeyguardPanelViewController$nsslLockYPosition_delegate$lambda*$$inlined$combine$1$3`
(its 3-arg `invoke(FlowCollector, Object[], Continuation)` bridge) and forces
`Object[]` indices 5 (fingerprint-apply) and 6 (enrolled templates) for that
single computation only: mode 2 sets both true so the GXZW branch always runs,
mode 1 sets both false so it never does. `fingerApplyForKeyguard` is
deliberately **not** replaced as a flow — it also drives fingerprint-icon
visibility (`MiuiGxzwStateProviderImpl`) and the low-position indication area
(`KeyguardBottomAreaInjector$gxzwLowPositionShow`), which must keep following
the user's setting.

Class resolution is by dex name, not `getDeclaredClasses()`: on the release
SystemUI the Kotlin lambda classes are **not** reachable through
`KeyguardPanelViewController.getDeclaredClasses()` — R8 folds the `lazy {}`
lambda into the synthesized `$$ExternalSyntheticLambda6`, so the `$lambda*$`
chain that would nest the combine classes no longer loads as enclosing classes
(observed on-device 2026-08-17: the nested-class walk found nothing while the
dex still carries the full class name). The resolver loads the class by its
exact dex name (`...$lambda$106$$inlined$combine$1$3`), falling back to
enumerating the class loader's dex entries and finally to probing the lambda
ordinal, so the ordinal in the middle of the name does not matter.

Agent verification (2026-08-17, OS4.0.0.15.XPMCNXM device): the first build
silently skipped (`HOOK_SKIPPED ... combine lambda ... not found`) because of
the R8 class-resolution issue above; with the dex-name resolver the hook
installs (`HOOK_OK`) and logcat confirms the behavior flips: with mode 1 (不避让)
`StackStateStrategy calculateTargetYPosition0` shows
`notificationBottomOnKeyguard` = 2257 (`indicationAreaTop 2281 − 24` padding)
instead of the avoided 1884 (`GXZW_ICON_Y 1956 − 60` gxzw padding). The
notification stack bottom moves from just above the fingerprint icon down to
the indication area. `compileDebugKotlin`, `testDebugUnitTest`, `lintDebug` and
`assembleDebug` pass. Visual confirmation of the lockscreen layout is the
user's.

## Lockscreen Charging Detail (锁屏充电详情)

Settings → Experimental → Charging Detail Options
(`KEY_LOCKSCREEN_CHARGING_DETAIL`, `LockscreenChargingDetailHooker`, OS4-only in
the UI; the master switch and every option live in the second-level
`ChargingDetailPage`, which keeps its own state like `AospRestorePage` and is
deliberately absent from `TWEAK_RESTART_SCOPES` — the page offers its own
in-page "Restart SystemUI" row) appends live charging telemetry to the bottom
lockscreen charging indication — the line built by
`KeyguardIndicationController.updateDeviceEntryIndication(boolean)` that shows
`keyguard_charged` (已充满电) at 100% or `keyguard_charging_*_and_level_tip`
(极速/快速/充电中xx%) by `MiuiChargeManager.mBatteryStatus.chargeSpeed`, and is
rendered through `KeyguardIndicationRotateTextViewController.showIndication(int)`
with the battery role `3` (same role as the reverse-charging hint; role 13 is the
dismissible swipe hint) into `keyguard_indication_text_bottom`. The hooker is
an after-hook on `showIndication(int)`: when `mCurrIndicationType == 3` it
re-sets the view text with live values. The layout is user-configurable and
re-read on every render (Preferences memo TTL is 100 ms, so the sub-options
apply without a SystemUI restart once the master switch is on):
- `KEY_LOCKSCREEN_CHARGING_DETAIL_MULTILINE` (default on): detail on its own,
  slightly smaller line (RelativeSizeSpan 0.8) below the charging text; the
  view is switched off single-line/marquee once per instance, so the long text
  no longer scrolls;
- `KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS` (default `0b1111`): bitmask of
  wattage / voltage / current / temperature;
- `KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS` (default 2000, clamps 1000–10000):
  both the data-fetch throttle and the main-thread refresh loop period.

Data (all available to SystemUI, which holds BATTERY_STATS, and read defensively
through reflection / try-catch so a failure cannot disturb the render): current
from `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW, 2)` in µA,
falling back to `CURRENT_AVERAGE (3)` and finally
`/sys/class/power_supply/battery/current_now`; the sign is device-dependent so
only the magnitude is used; voltage (mV) and temperature (0.1°C) come from the
sticky `ACTION_BATTERY_CHANGED` broadcast; real-time wattage =
`|µA| × mV / 1e9`. Reads are throttled to 2 s; a main-thread 3 s loop keeps the
values live while the indication stays on screen; the pristine base message is
remembered per view (WeakHashMap) and only reused when it still prefixes the
current text, so a different system message (e.g. 充电保护中) is never glued to a
stale base. The master switch needs a SystemUI restart, offered by the in-page
"Restart SystemUI" row.

Agent verification (2026-08-17): `compileDebugKotlin`, `testDebugUnitTest`,
`lintDebug` and `assembleDebug` pass (only the project-wide `PrivateApi`
reflection-family lint warning, same as every other reflection-based hooker).
On-device visual confirmation of the lockscreen text — wattage / voltage /
current / temperature appearing while charging, and the sign/normalization of
the current value on this ROM — is the user's.

## Status-Bar Icon Tuner

Settings → Experimental → Icon Tuner
(`ui/page/IconTunerPage.kt`, `Route.IconTuner`) is the icon tuner ported from Hyper Helper
26.07.5 (`dev.lackluster.mihelper`, decompiled to
`/Users/ink/developer/reverse/cache/xiaomihelper-2bfd4873a4138764`; the OS4 comparison and
staged port plan live in `OS4_ADAPTATION_PLAN.md` in that cache). The page keeps its own state
like `AospRestorePage`, so its keys are deliberately absent from `TWEAK_RESTART_SCOPES` and it
offers its own SystemUI restart action.

Ported hookers live in `hook/rules/systemui/icon/`, all targets verified present on OS4 unless
noted. Two shared mechanisms recur:

- `IconTunerFlows` builds shared `ReadonlyStateFlow` instances (`false` / `0`) reflectively with
  the host class loader; the visibility hooks replace the ViewModel's flow fields with them right
  after construction, mirroring Hyper Helper. It also provides host-loader `kotlin.Pair` creation,
  `$$delegate_0` unwrapping, `setValue`, and flow-value reading for the stacked-signal machinery.
- Font-weight overrides use the system `MiSansVF.ttf` variable font (`'wght'` axis); Hyper
  Helper's user-font-file and bundled MiSansCondensed/SFPro subset support is not ported.

Current slice (cellular and WiFi visibility were the first slice):

- `CellularIconHooker` — `MiuiCellularIconVM` visibility getters (`getInOutVisible`,
  `getMobileTypeVisible`/`getMobileTypeImageVisible`, `getVowifiVisible`, `getVolteVisibleGlobal`,
  `getVolteNoService`, `getSpeechHd`) forced to a shared `false` flow. The fields are assigned
  *after* construction by the factory `MiuiMobileIconVMImpl$$ExternalSyntheticLambda0.invoke()`
  (verified on OS4.0.0.15.XPMCNXM), so upstream's after-constructor field write is clobbered and
  has no effect on OS4 — the getters are the only read path (the per-SIM impl's `transformLatest`
  lambdas and `MiuiMobileIconBinder`), so they are hooked instead; roam visibility via
  `getMobileRoamVisible`/`getSmallRoamVisible` before hooks plus a
  `StatusBarIconObserver.roamSettingBlock` constructor write.
- `WifiIconHooker` — `WifiIcon$Companion.fromModel` substitutes `WifiIcon$Hidden` for a
  connected `WifiNetworkModel$Active` (flag argument false); the `WifiViewModel`
  `getActivityInOutRes`/`getWifiStandard` getters are hooked to return a shared `0` flow —
  the OS4 factory assigns the backing fields after construction, so getter hooks (not
  constructor field writes) are the stable read boundary.
- `IconManagerHooker` — mutates the static `MiuiIconManagerUtils.RIGHT_BLOCK_LIST` /
  `CONTROL_CENTER_BLOCK_LIST` ArrayLists (consumers hold the same instance) per slot mode
  (0 follow system = lists untouched, 1 visible everywhere, 2 status bar only, 3 control center
  only, 4 hidden everywhere; every unset slot stays on 0 — the stacked signal no longer blocks
  `single_mobile_sim1`/`sim2`, the view-level renderer hides the non-data SIM itself).
  Keys are `Preferences.slotKey(slot)`; `icon_ext_blocked` adds extra slots from a list.
- `IgnoreSysIconSettingsHooker` — OS4 moved `isIconBlocked` off `StatusBarIconObserver` onto
  `StatusBarIconView`, so the hook target differs from upstream (OS4_ADAPTATION_PLAN.md T3):
  force `isIconBlocked` false except the privacy slot, `loadStatusBarIcon` returns "", and
  `NetworkSpeedController.mShowNetworkSpeed` is forced from the `network_speed` slot mode
  (constructor + R8 nest `mupdateVisibility` name match).
- `HideCellularIconHooker` — captures `MobileIconsInteractorImpl.defaultDataSubId` and replaces
  `MobileIconViewModel.isVisible` with the false flow for the non-default SIM; gated by
  `icon_hide_sim_auto` and disabled while `icon_stacked_enabled` is on.
- `CellularTypeIconHooker` — cellular single-type display (upstream `CellularTypeIcon`). On OS4
  `IOperatorCustomizedPolicy$OperatorConfig` is rebuilt inside
  `MiuiOperatorCustomizedPolicy.getMiuiOperatorConfig(int)` on every call (verified in smali — the
  `new` + field writes live in the getter), so an after-constructor write would be clobbered; the
  hook mutates the freshly returned config instead: `showMobileDataTypeSingle` forced true and
  `mobileTypeName` replaced with the custom text (`icon_tuner_cellular_type_custom_val`; a single
  value fills all 15 per-SIM entries, exactly 15 comma-separated values map one-to-one, anything
  else is ignored, mirroring upstream). The upstream font half (`MobileTypeDrawable` paints) is
  not ported.
- `CompoundIconHooker` — 合成图标, the merged alarm / DND / location / mute-vibrate icon
  (upstream `CompoundIcon`). Each source keeps its own `compound_*` status slot and a shared
  per-controller merged state (upstream's `KEY_MERGED_ICON_STATE` weak cache) shows exactly the
  highest-priority active source: `apply` lazily installs the five system drawables
  (`stat_sys_alarm`/`stat_sys_gps_on`/`stat_sys_quiet_mode`/`stat_sys_ringer_silent`/
  `stat_sys_ringer_vibrate`, resolved by name) via `StatusBarIconControllerImpl.setIcon` /
  `setIconVisibility` (the OS4 `StatusBarIconController` interface itself carries no icon
  methods; the impl does), then flips visibility to the priority winner from
  `icon_tuner_compound_priority` ("location,alarm_clock,zen,volume"). State sources: after-hooks
  on `MiuiPhoneStatusBarPolicy.updateVolumeZen` (mute/zen), `onLocationActiveChanged$1`
  (location, guarded by `MiuiPrivacyControllerImpl.isCTARequiredLocation()`),
  `PhoneStatusBarPolicy$$ExternalSyntheticLambda3.accept` classId 0 (zen lambda),
  `PhoneStatusBarPolicy$4.onAlarmChanged`, and a main-thread post from the
  `MiuiPrivacyControllerImpl` constructor mirroring the CTA-required location. The feature is
  gated on the `compound_icon` slot mode being 1..3 (upstream g32.J / `zs0(16)`), which is also
  how `IconManagerHooker` gates the five compound slots (all share one slot key, upstream `v()`).
- `HideCarrierLabelHooker` — carrier label hiding (upstream `HideCarrierLabel`, T7). On OS4 the
  rows live in `ControlCenterCarrierText` (`innerCarrierSlotId` 0/1) inside `MiuiCarrierTextLayout`,
  reused for the control-center header and the lockscreen header (`isKeyguardLayout`). The hook
  hides the row's `carrierTextView` right after the layout is built (`shouldShow()` reads the text
  view visibility, so the row is fully treated as absent), re-hides it on every live
  `ControlCenterCarrierText$mCarrierTextCallback$1.onCarrierTextChanged` update, and forces the
  HD text hidden after `ControlCenterCarrierText.updateHDText`. Keys match upstream w22.* /
  x22.e-f.
- `RegionSamplingHooker` — forced status-bar region sampling (upstream `RegionSampling`, T6).
  OS3 drove this through `LightBarControllerImplInjector.useRegionSampling`; on OS4 the gate is
  `StatusBarRegionSamplingInteractor.regionSampling`, a combine flow whose collector starts/stops
  the `RegionSamplingHelper`. The flow field is typed as the concrete inlined-combine class
  (verified in smali), so replacing it with a `StateFlow` would fail the collector's check-cast
  and crash the coroutine; instead the hook forces the transform lambda
  `StatusBarRegionSamplingInteractor$regionSampling$1.invoke` (the `Function3` bridge
  `CombineKt.combineInternal` calls) to always emit the requested value — `statusbar_region_sampling`
  mode 1 always samples, mode 2 never does.
- `StackedSignalHooker` — 堆叠信号, rebuilt on Flux Decor 2.0.3's view-level model (upstream
  `Flux_Decor_OS4-2.0.3.apk` decoded at `/tmp/deobf/flux/`; full analysis in
  `FLUX_DECOR_STACKED_SIGNAL_PLAN.md`). The old implementations are gone: the native
  `isStackable`-slot hack and the Compose/SVG `getIcon()` renderer were both deleted with their
  keys. The hooker intercepts the mobile icon at the view layer, mirroring Flux Decor:
  a before-hook on `ImageView.setImageResource(int)` loads module drawables directly and renders
  `statusbar_signal_1_{0..5}` into the data SIM's
  `mobile_signal` ImageView and the other SIM's `statusbar_signal_2_{0..5}` into a second
  `AlphaOptimizedImageView` (tag `0x7F000001`) added to the same `MobileSignalAnimatorView`
  container, so the two rows compose into one stacked icon. Level is read live from
  `MiuiCellularIconVM.signalIconId` (collected in the factory
  `MiuiMobileIconVMImpl$$ExternalSyntheticLambda0.invoke()` after-hook — the only VM
  construction site on OS4; the VM has no constructor), subId comes from
  `originIconInteractor.subId`, and the data SIM is tracked by `activeMobileDataSubscriptionId`
  (via `JavaAdapter.alwaysCollectFlow` on `MobileUiAdapter.start`). The non-data SIM's own view is
  set `GONE`, and the inherited `ModernStatusBarView.isIconVisible()` is hooked only when the
  receiver is exactly `ModernStatusBarMobileView`; it returns false for the non-data subId so the
  status icon container does not retain an empty slot. No VM flow is replaced and no framework
  `View`/`Resources` method is hooked globally. The data SIM's slot stays untouched, so
  `IconManagerHooker` no longer blocks `single_mobile_sim1`/`sim2`.
  Theme is tracked through `MiuiStatusBarIconViewHelper.transformResId(int,boolean,boolean)`
  args; module `_tint`/`_dark` drawables are loaded directly and installed with
  `ImageView.setImageDrawable`. Do not pass the internal `0x7e...` ids to
  `ImageView.setImageResource`: on this build the framework resolves them below the hooked
  `Resources` API and throws `Resources.NotFoundException`, leaving the stock icon in place.
  **Do not write fake ids into the views' `tag` fields either**: the binder's theme-change
  collector re-applies the signal drawable by reading `View.getTag()` and re-feeding it into
  `setImageResource`, so a fake id stored in `mobile_signal`'s tag throws `NotFoundException`
  inside the binder's collector coroutine, killing the whole collector scope — observed on
  device as every flow-driven cellular feature (data-activity arrows, roaming, VoLTE, VoWiFi,
  type, 5G, satellite) freezing at its initial state while the stacked composite stayed on
  screen. The tag is left untouched; `interceptMobileImage` also skips any fake-id
  `setImageResource` call (`param.result = null`) as defense in depth.
  Keys: `icon_stacked_enabled`, `icon_stacked_scale` (0.5–1.5 ×0.1); requires a SystemUI restart.

Agent verification status (2026-08-19): `compileDebugKotlin`, `testDebugUnitTest`, `lintDebug`,
and `assembleDebug` pass. On-device (OS4.0.0.15.XPMCNXM) the user confirmed the fix works:
the stacked signal icon renders (two rows), the non-data SIM no longer leaves an empty slot or a
stale drawn icon, and the cellular visibility switches (隐藏数据活动 / roaming / VoLTE / VoWiFi /
type / 5G / satellite) work again. Regression history — do not reintroduce:
- The `mobile_signal` view's `tag` must never hold a fake `0x7e...` id: the binder's theme-change
  collector re-applies the drawable from `View.getTag()` via `setImageResource`, which resolves
  fake ids below the hooks and throws `Resources.NotFoundException`, killing the whole binder
  collector scope (all flow-driven cellular features freeze). The tag is left untouched.
- `idRes()` must resolve `com.android.systemui` ids from the host view's resources, never from the
  module package context (module AssetManager cannot see SystemUI tables, returns 0 → the synthetic
  sub ImageView is never created → no stacked icon).
- `icon_stacked_scale` is a direct 0.5–1.5 factor; do not multiply by 0.1 (that was the old
  int-pref convention; applying it renders the composite at 5–15%, invisible but slot-holding).
Not yet ported, documented in `OS4_ADAPTATION_PLAN.md`: the left-container/island engine
(`LeftContainer`, T2) and Hyper Helper's remaining icon-tuner font slices. The clock
(`MiuiClockHooker`), network speed (`NetworkSpeedHooker`) and battery (`BatteryIndicatorHooker`)
slices were removed again at the user's request on 2026-08-15 (their preference keys are
deleted; the hookers and UI sections no longer exist).

## AOSP Back Gesture

The AOSP back gesture is vendored from `wxxsfxyzm/MiuiBackGestureHook`
(Apache-2.0) through upstream commit
`a5f1ae5d76609f8323d30ce108117081369c426f` (`a5f1ae5`; v0.8.5, 2026-08-09).
Upstream's reference clone lives at
`/Users/ink/developer/refrences/MiuiBackGestureHook`.

Upstream's hook ownership chain is copied under `hook/rules/backgesture/` so
future updates stay mergeable. `BackGestureHookRuntime` remains as a
HyperTweak-only composition shim after upstream removed its equivalent.
HyperTweak-local changes are marked with a `HyperTweak:` comment and are
confined to:

- `hooks/core/HookRuntimeCore.java` — the root drops `extends XposedModule`.
  Hook installation goes through a `HookRegistrar` bridged to `BaseHooker`
  (`registerHook()` replaces upstream's
  `recordHookHandle(hook(m).setId(id).intercept(f))`). Upstream renamed `log()`
  to `moduleLog()` and added its own `KEY_MODULE_LOGGING` preference; HyperTweak
  keeps the `moduleLog` name but redefines it as a static gated on
  `KEY_AOSP_BACK_LOGS` (WARN and above stay unconditional), and
  `deoptimize()`/`getInvoker()` are routed through the registrar.
- `hooks/hotreload/HotReloadHookRuntime.java` — upstream's LSPosed lifecycle
  callbacks become `saveHotReloadState()`/`restoreHotReloadState()` plus explicit
  `install*Hooks(classLoader, registrar)` entry points. A deferral throws instead
  of returning `false`. `createHotReloadHooker()` is dropped because `BaseHooker`
  already replaces handles by hook id when `onHook()` re-runs.
- `hooks/miuihome/MiuiHomeReturnHome*.java` — upstream v0.8.4 decomposed the old
  single return-home controller into a six-class chain (State → Preview → Unified
  → UnifiedCommit → Lifecycle → leaf). The HyperTweak-driven app-to-home
  machinery moved with it: `hookMiuiHomeAppToHomeGate` and the
  `drivingMiuiHomeAppToHome` thread-local live on the `MiuiHomeReturnHomeRuntime`
  leaf; `driveMiuiHomeAppToHome`, `scheduleMiuiHomeAppToHomeDrive`,
  `scheduleHandedOffSessionFinish`, `refreshMiuiHomeRunningTaskIdentity` and
  `ensureMiuiHomeStateManagerAppState` live on `ReturnHomeLifecycleController`
  next to the `startNativeClose` call site; the preview-owner staleness bounds
  live in `MiuiHomeReturnHomePreviewRuntime`; and the
  `startedUptime`/`handedOffToLauncher` session fields live in
  `MiuiHomeReturnHomeStateRuntime`.
- Preferences resolve through `Preferences` rather than upstream's own remote
  group; see the `KEY_AOSP_BACK_*` keys.

`CrossTaskWallpaperRuntime` is HyperTweak-only and is not part of upstream. It
draws the wallpaper behind the cross-task back animation when
`KEY_CROSS_TASK_WALLPAPER_BACKGROUND` is on. Upstream's
`tintCrossTaskBackground` hooks the same `BackAnimationBackground.ensureBackground`
method to repaint that layer black for its slide-back animation, so it yields
when this setting is on and the two never fight over one surface.

The launcher route is version-gated. Predictive return-home hooks ~35
`com.miui.home` Java classes (`recents.anim.*`, `RectFSpringAnim`,
`ClipAnimationHelper`, ...) that only Launcher 7 and older ship; Launcher 8
declares `android:hasCode="false"` and contains no dex at all, so none of them
can resolve there. `LauncherVersion` caches the launcher version from the UI
process into `Preferences` and hook processes fall back to probing the launcher
class loader for `GestureStubView`. The route defaults off when unsupported and
its setting is greyed out in Settings → Experimental.

Upstream v0.8.0 replaced the old direct-pilfer input model with launcher-side
arbitration (`inputModel=miuihome-accepted-token`): MIUI's `GestureStubView` owns
the screen edges for every app, so `MiuiHomeHookRuntime` hooks
`GesturesBackTouchProcessor.onPointerEvent` and publishes an accepted-input token
to SystemUI. Upstream's `SystemUiInputRuntime.onNativeDown` marks the gesture a
candidate and then returns `false` until `acceptMiuiHomeInput(token)` runs, and
`miuiHomeInputAccepted` is reachable from nowhere else. Upstream therefore needs
`com.miui.home` both in the module's LSPosed scope and running a launcher that
still has the Java gesture stack, for the gesture to start **in any app** — not
just for the launcher route.

HyperTweak restores a token-free path so that dependency is not fatal on a
launcher that cannot arbitrate at all. `onNativeDown` synthesises a token from
the current DOWN and calls `acceptMiuiHomeInput` directly, which is the pre-0.8.0
behaviour; every downstream check still applies because the synthesised token
carries this process's `systemUiInputArbiterGeneration` and the DOWN's own
identity.

That path is taken only when `LauncherVersion.mayArbitrate` is false, i.e. the
launcher is positively known to have no Java gesture stack. It deliberately does
**not** trigger on "SystemUI has not heard from the launcher yet".
`MiuiHomeHookRuntime` announces itself lazily, from
`ensureMiuiHomeInputArbiterReceiver` on its first gesture-stub interaction, so
silence does not mean absence. Claiming a gesture the launcher is also
arbitrating leaves the two sides' ownership identities diverged; on device that
showed up as predictive return-home working for the first few gestures and then
stranding the transition — no predictive animation, a white status bar, and a
delayed jump to home. `mayArbitrate` therefore treats an undetected launcher as
"might arbitrate" and fails safe.

`miuiHomeArbiterSeen` remains as the positive signal that a hooked launcher is
present, and `MiuiHomeHookRuntime` re-sends its arbiter query whenever it sees a
new SystemUI generation so a SystemUI restart re-establishes arbitration before
the next gesture.

### Why predictive return-home strands on Launcher 7.50.06

Traced on device (launcher `RELEASE-7.50.06.2372-06261924`, decompiled to
`cache/launcher-73ee007d501ecdb8`). The commit path itself works: the module holds the element in
`CLOSE_TO_DRAG` ("Held Xiaomi CLOSE_TO_DRAG for real commit transition") and composes the commit
("Composed accepted predictive return-home commit in original start transaction"). Then nothing
happens for ~1.8s until `completeUnifiedCommitTransitionTimeout` fires.

The element never leaves `CLOSE_TO_DRAG` because on this build `AnimType.CLOSE_TO_HOME` for a
gesture-driven return home is issued from exactly one place: `NavStubView` on finger-up, via
`StateManager.sendEvent(AppToHomeEvent(GestureAppUpEventInfo(... CLOSE_TO_HOME ...)))`
(`NavStubView.java:4597`). The module pilfers the pointer stream for SystemUI, so `NavStubView`
never receives the UP and never sends that event. Nothing else supplies it:
`WindowAnimParamsProvider.getRemoteAbortParams` also builds `CLOSE_TO_HOME` params, but only for
`RemoteShellAbortEvent` from `FastLaunchWindowElement`, which is an abort path, not a commit.

`animTo` itself is called constantly during the drag (106 `CLOSE_TO_DRAG` calls in one gesture),
so the `animTo$lambda$3` hook resolves and fires correctly — the hooks are not the problem. Nor is
the launcher version: every member upstream resolves exists with the expected signature on this
build.

Closing this needs the module to drive the launcher to home itself after commit, mirroring
`NavStubView:4597`, rather than waiting for an event that its own pointer pilfering prevents.
Note the launcher logs under instance tags (`WindowElement<hash>`) and `MiuiHomeLog` prefixes
`Launcher.`, with `debug()` gated behind the `is_miui_home_debug_log_enable` pref — capture all of
logcat and filter offline rather than using `logcat -s`.

The launcher hooks are split into two independently gated halves.
`installMiuiHomeInputArbitrationOnly` (gesture stub, `GesturesBackTouchProcessor`
arbiter, recents/task-launch/fullscreen state mirrors) always installs while the
AOSP back gesture is enabled, because without the accepted-input token SystemUI
never starts a gesture in any app. Only the predictive return-home half — the
deep `recents.anim.*` integration plus the module-driven `performAppToHome`
chain — is gated by `KEY_AOSP_BACK_MIUI_HOME_HOOKS` and `LauncherVersion`.
Turning that setting off must degrade to native return-home animation, never
disable the gesture.

The driven return-home chain compensates for three things NavStubView normally
does on its own finger-up, which never happens under module ownership:
`ensureMiuiHomeStateManagerAppState` promotes StateManager Idle→App so event
6004 is routed (only `AppState` handles it, `StateManager.java:1239`);
`refreshMiuiHomeRunningTaskIdentity` seeds `mRunningTask*` from the session's
closing `RemoteAnimationTarget.taskInfo` so `findClosingAnimTarget` resolves the
icon of the app actually closing; and `hookMiuiHomeAppToHomeGate` forces
`isNeedStopBecauseRecentsRemoteAnimStartFailed()` false for exactly the driven
call so `performAppToHome` takes its animation branch.

A driven return-home session is handed off, not tracked. The driven `animTo` carries
StateManager's own `RectFParams`, so `resolveUnifiedAnimToConfigOwnerToken` returns
null, `configuredAnimTo` stays null and the finish source is refused with
"Skipped superseded Xiaomi finish source" — the session then leaks on every
gesture. `ReturnHomeSession.handedOffToLauncher` marks that Xiaomi owns the
animation: `beginUnifiedNativeFinishDispatch` lets its finish through (same
reasoning as upstream's `unifiedNativeProviderCommitAdopted` case) and
`scheduleHandedOffSessionFinish` retires the session after
`MIUI_HOME_HANDOFF_FINISH_DELAY_MS`.

`systemUiShellSettling` covers the window between a Shell release and its finish
callback — ~538ms of real settling animation on device. A DOWN in that window is
rejected as "Shell is busy" and, by upstream design, abandoned for good, because
`NativeBackInputMonitor` still owns its candidate until UP/CANCEL. Rather than
reopening a rejected stream, the flag is published through
`publishSystemUiInputArbiterState` so the launcher keeps its own legacy processor
for those gestures and the user gets MIUI's native back instead of a gesture that
does nothing. All readiness publishes go through `isSystemUiArbiterReady()` so a
monitor query cannot overwrite the settling state.

`ContextualSearchSystemHooker` must not clear its uid cache or PackageManager on
hot reload. `systemPackageManager` was only ever seeded from the
`SystemServer.deviceHasConfigString` hook, which runs once during early boot, so
clearing it left `resolveUid()` returning -1 forever and every
`startContextualSearch` fell through to `enforcePermission` — Circle to Search
died after any module update until reboot. `resolvePackageManager()` now falls
back to `ActivityThread.currentApplication()`.

The cross-task wallpaper cache is warmed ahead of use, not on first use.
`ensureWallpaperCacheReady` retries while SystemUI's application context does not
exist yet, and runs again at cross-task animation start so a wallpaper change or
memory trim is rebuilt before the next gesture; the consumer runs on the Shell
animation thread and cannot wait for a decode.

On the SystemUI side, `scheduleShellSessionReleaseWatchdog` bounds a released
Shell session whose finish callback never arrives (seen after releases resolving
`actualTrigger=false` right after an app launch): after
`SHELL_RELEASE_WATCHDOG_TIMEOUT_MS` it verifies quiescence on the Shell owner
thread — controller state is only readable there — and completes the session, so
later gestures stop being rejected with "Suppressed SystemUI back while Shell is
busy".

`ReturnHomeSession` retention is bounded locally. Upstream claims unified-native preview
ownership before the rest of the WindowElement is validated, and a failure path that misses the
matching cancel leaves the session owning it with `unifiedNativeCleanupVerified` false.
`finishSession` then refuses to finish such a session ("Deferred runner finish behind Xiaomi
native owner") while cleanup verification waits on the finish, so the two wait on each other
forever. Observed on device as: predictive return-home works, then one gesture leaves the
launcher stuck blurred and scaled but interactive, every later runner is refused with "Rejected
overlapping return-home runner", and `blocksControllerReplacement` stays true so LSPosed reports
the launcher as a process that failed to hot reload. All three are the same leak.
`STALE_RETURN_HOME_PREVIEW_TIMEOUT_MS` bounds it: a preview owner older than that is cancelled,
marked verified to break the deadlock, and finished, and the same bound is applied in
`blocksControllerReplacement`. Only the preview case is bounded — a running native animation
still holds ownership for as long as it needs.

The device baseline for this feature is Launcher 7.x, which has the Java gesture
stack and so always uses upstream's arbitrated path — meaning `com.miui.home`
must be in the module's LSPosed scope there. The AOSP Back Gesture summary says
so when the installed launcher is one that owns the screen edges.

The system-server takeover bridge added in commit `6192c2a` is reverted on this
branch. That bridge observed the global pointer stream from
`PointerEventDispatcher`, gated Launcher 8's private
`InputManagerService$InputMonitorHost.pilferPointers()` call for the
`[Gesture Monitor] swipe-up` channel, and relayed recognized gestures back to
SystemUI over a broadcast. Gating returns success to Launcher's Rust recognizer
without transferring ownership and then replays the original native request from
a timer, so both Launcher and the foreground window track the same pointers and
a later, unrelated touch can be cancelled by the replay. On device this showed as
repeated redraws along the bottom of the screen and, over time, a handle region
that stopped responding. Do not restore it without a mechanism that does not lie
to Launcher about ownership.

HyperTweak does not add separate haptic feedback on dispatch, avoiding overlap
with Launcher and assistant feedback. Action selections are read at dispatch time
and do not require a SystemUI restart. Default-assistant requests call SystemUI's
`AssistManager` without HyperOS's Launcher-owned invocation type. Circle to
Search is CSService-only and does not depend on the selected digital assistant.
Direct Gemini and ChatGPT bare-launch actions were removed after on-device
testing showed the target entry activities self-terminate (ChatGPT's
translucent `AssistantProxyActivity`) or fall into the app's main UI without
engaging voice (Google's `GoogleAppVoiceAssistEntrypoint`) when launched without
an assist-framework session; persisted ids 3 and 4 now degrade to `DISABLED`.
`ContextualSearchSystemHooker` scopes its compatibility override to two callers:
`startContextualSearch` from the resolved SystemUI UID, and the provider's
`getContextualSearchState` callback from the resolved Google app UID. Both
resolve the contextual-search package name, and HyperOS leaves
`config_defaultContextualSearchPackageName` empty, so covering only the first
call leaves the callback resolving an empty package. A companion
voice-interaction repair extends HyperOS's
Binder-death recovery to the configured third-party assistant and replays only
marked HyperTweak requests after a stale service is rebound. The feature
defaults off, requires a SystemUI restart after setting changes, and needs one
reboot after module installation so the system-server hooks are installed.

`ImmediateMonetRefreshHooker` works around HyperOS dropping wallpaper colors
whose source is marked as AOD, deferring later events while the display is
awake, and failing to pass Xiaomi's independently extracted lock-wallpaper
colors to `ThemeOverlayController`. It forwards both the SystemUI listener and
Xiaomi keyguard callback through the original `handleWallpaperColors` path so
the platform still owns source selection, settings updates, and Monet overlay
generation. Fashion Gallery events remain excluded. The experimental setting
defaults on and is read for every wallpaper event, so changing it does not
require a SystemUI restart after the hook has been installed.

## Power Button Long Press (长按电源键操作)

Settings → Tweaks → Navigation Bar → Power Button Long Press
(`KEY_POWER_BUTTON_ACTION`, `hook/rules/system/PowerButtonCtsHooker.kt`) re-binds the
long-press power button to a configurable action instead of the system action:
`POWER_BUTTON_ACTION_DISABLED` (0, follow the system), `POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH`
(1), or `POWER_BUTTON_ACTION_DEFAULT_ASSISTANT` (2, default digital assistant — Google
Assistant / Gemini / 小爱). A separate `KEY_POWER_BUTTON_HAPTIC` switch (default on) plays
the platform's `LONG_PRESS_POWER_BUTTON` haptic when the custom action actually fires.
The legacy single-switch `KEY_POWER_BUTTON_CTS` boolean is superseded: reading the action
migrates it (on → Circle to Search) and `setPowerButtonAction` drops it. On the OS4
baseline the binding lives in system_server on two stacked layers:

- `com.android.server.input.shortcut.singlekeyrule.PowerKeyRule#onMiuiLongPress(Object, long)` —
  the MIUI 快捷手势 layer driven by `Settings.System.long_press_power_key`
  (`launch_voice_assistant` / `launch_google_search` / `launch_smarthome` / `none`,
  set by `GestureShortcutSettingsSelectFragment`); it preempts the AOSP layer whenever
  a function is configured, dispatching through
  `ShortCutActionsUtils.triggerFunction` (e.g. `launchGoogleSearch` →
  `BaseMiuiPhoneWindowManager.launchAssistAction`).
- `PhoneWindowManager#powerLongPress(long)` — the AOSP fallback driven by
  `Settings.Global.power_button_long_press` (1 = power menu, 2/3 = shutdown, 4 = voice
  assist, 5 = assistant), reached through `OriginalPowerKeyRuleBridge` →
  `PhoneWindowManager$PowerKeyRule.onLongPress` when MIUI does not own the long press.

The hooker intercepts both before their original dispatch: the power key is marked
handled (`PhoneWindowManager.setPowerKeyHandled(true)`, public), the selected action runs,
and the haptic plays only when the action actually fired (a failed dispatch degrades to the
original system action without a phantom vibration). Circle to Search goes through
`ContextualSearchSystemHooker.startFromSystemServer()` — the same
`IContextualSearchManager.startContextualSearch(1)` binder entry the SystemUI gesture path
uses, with the bridge flag set without the caller-uid check (the invoking thread is the
system process), so `getContextualSearchPackageName()` resolves Google's package for exactly
that call. The default-assistant action calls the policy's private
`launchAssistAction(String, int, long, int)` with the same arguments the AOSP "assistant"
long-press (setting 5) uses on this build (`null, -2, eventTime, 6`), which routes through
`SearchManager.launchAssist` to the platform assist pipeline — deliberately **not** the MIUI
overload `launchAssistAction(String, Bundle)` (`BaseMiuiPhoneWindowManager:2198`), which
forks to the MIUI 小爱 path; the platform pipeline creates a real assist-framework session,
so the default assistant's voice UI engages (bare activity launches of Gemini/ChatGPT
self-terminate without one). The haptic invokes the policy's private
`performHapticFeedback(int, String)` with `LONG_PRESS_POWER_BUTTON` (10003, @SystemApi so
a local constant in the hooker), the same effect the platform's own long-press paths play.
Neither method is overridden on `MiuiPhoneWindowManager`/`BaseMiuiPhoneWindowManager`.
`powerLongPress`'s LPP squeeze-effect block is skipped along with the behavior switch;
`powerVeryLongPress` / `onMiuiVeryLongPress` are untouched, so a configured very-long-press
power menu still works. The two long-press methods are deoptimized first (`onMiuiLongPress`
is protected and small enough to be AOT-inlined). Actions and the haptic toggle are read
live at dispatch time, so switching actions (or off) takes effect without a reboot once the
hooks are installed; enabling from disabled still needs a reboot for the system-server hooks
(and the CTS bridge gate in `ContextualSearchSystemHooker`).

**The reverse-engineering cache `framework-services-2e880646` is an older build for
two of these targets — always verify against the versioned jars.** On the real
OS4.0.0.15.XPMCNXM jars (`services-OS4.0.0.15.XPMCNXM.jar`,
`miui-services-OS4.0.0.15.XPMCNXM.jar`):
- `PowerKeyRule#onMiuiLongPress(Object singleKeyGestureEvent, long)` — the cache
  shows `(long)`; the release grew a first `Object` parameter. The hooker resolves
  by name plus a trailing `long` parameter so both shapes match.
- `IContextualSearchManager.startContextualSearch(int, ContextualSearchConfig)` —
  the AIDL grew a `ContextualSearchConfig` parameter (OS4/Android 16); the cache
  shows the old `(int)`. Both `ContextualSearchSystemHooker`'s service hook and the
  SystemUI-side `GestureBarActionHooker.ContextualSearchInvoker` resolve the method
  by name and pass `null` (the service substitutes
  `ContextualSearchConfig.DEFAULT_CONFIG`). On this device the one-arg forms did
  not exist, so the pre-existing gesture-bar Circle to Search entry had also been
  failing with `NoSuchMethodException` until the invoker was fixed the same way.
- `PhoneWindowManager#powerLongPress(long)` matches the cache (verified `HOOK_OK`).

Agent verification (2026-08-17, OS4.0.0.15.XPMCNXM device): the first on-device run
failed to install the MIUI-layer hook (`HOOK_FAILED PowerKeyRule#onMiuiLongPress`
NoSuchMethodException) and the CTS bridge (`failed to attach hooker:
ContextualSearchSystemHooker`, same cause on the stub), so long-press power fell
through to the user's configured 小爱 (`Settings.System.long_press_power_key` =
`launch_voice_assistant`) and no CTS service was reachable. After fixing both
signatures, the boot log shows `HOOK_OK` for
`PowerKeyRule#onMiuiLongPress(Object,long)` and
`...ContextualSearchManagerStub#startContextualSearch(int,ContextualSearchConfig)`
plus every bridge hook, and each long-press power logs
`PowerButtonCTS: MIUI long-press power -> Circle to Search` with no
`contextual search service failed` afterwards. `compileDebugKotlin`,
`testDebugUnitTest`, `lintDebug` and `assembleDebug` pass. Visual confirmation of
the Circle to Search overlay and of the default-assistant / haptic behavior is the
user's.

## AOSP Restore

Settings → AOSP Restore (`ui/page/AospRestorePage.kt`, `Route.AospRestore`) collects
the switches that hand a HyperOS component back to its AOSP implementation. The
page keeps its own state instead of hoisting it into `MainActivity`, so these keys
are deliberately absent from `TWEAK_RESTART_SCOPES` and each summary states its own
restart requirement.

`AospPackageInstallerHooker` restores the AOSP package installer, ported from
tehcneko's AOSP Package Installer (GPL-3.0). On the current baseline
`PackageManagerServiceImpl.updateDefaultPkgInstallerLocked()` selects the MIUI
installer unless `isCTS()` is true (`services.jar:1260`), and
`assertValidApkAndInstaller()` (`:1112`) and `hookChooseBestActivity()` (`:1220`)
gate on the same static `isCTS()` (`:1440`, returning `AppOpsUtils.isXOptMode()`).
The hooker forces `isCTS()` true for the duration of those three methods.

**This deliberately relaxes MIUI install verification**: `assertValidApkAndInstaller`
returns early when `isCTS()` is true, skipping the signature and installer checks
MIUI performs. That is what the feature is for, but it is a security-relevant
relaxation and the setting defaults off.

Because `isCTS()` is static and also gates the install-verification path at
`services.jar:643`, the override is scoped to the calling thread through a
re-entrant `ThreadLocal` depth counter. The upstream implementations use a
process-wide boolean, which lets a concurrent install on another system_server
thread skip validation. The scope is entered only while the setting is on but
always left, so toggling mid-call cannot strand the counter above zero. All four
methods are deoptimized first; they are small enough to be AOT-inlined otherwise.
`BaseHooker.deoptimize(Executable)` is the shared helper (previously private to
`PasskeyHooker`). The setting is read live inside the callbacks, so turning it off
takes effect without a reboot even though turning it on needs one.

`AospSystemUiPluginBlockHooker` restores the AOSP power menu and volume panel.
Upstream disables `miui.systemui.plugin/miui.systemui.globalactions.GlobalActionsPlugin`
and `.../miui.systemui.volume.VolumeDialogPlugin` with
`setComponentEnabledSetting`, falling back to a root `pm disable`; HyperTweak has
neither permission as a normal app, so it hides the components from SystemUI's own
plugin framework instead. `PluginActionManager.loadPluginComponent(ComponentName)`
(SystemUI `:313`) is the single funnel both discovery paths use — the R8-synthesized
package-change lambda at `:151` and `handleQueryPlugins` at `:291` — and every
caller is written as `if (instance != null)`, so returning null skips the plugin
cleanly. That needs no root, leaves no persistent component state, and is fully
reverted by turning the switch off and restarting SystemUI.

Matching is by class name, not package: `SystemUIPluginHooker` needs the
control-center plugin in the same package to keep loading. The two settings are
separate, snapshotted at `onHook()`, and require a SystemUI restart.

Whether HyperOS still ships working AOSP `GlobalActionsDialog` and
`VolumeDialogImpl` fallbacks is unverified off-device; if it does not, the failure
mode is a power or volume key with no dialog.

`ExtendUnlockHooker` repairs Extend Unlock (formerly Smart Lock), ported from
StevenWin818's HyperTrust (GPL-3.0). Xiaomi does **not** modify
`KeyguardUpdateMonitor.getUserHasTrust(int)`; on the current baseline
(SystemUI `:2131`) it is AOSP's formula verbatim,
`!isSimPinSecure() && mUserHasTrust.get(id) && isUnlockingWithBiometricAllowed(true)`.
What breaks is the `mUserHasTrust` cache going stale, so a trust grant never
reaches the keyguard. `derivedTrustState`, named in HyperTrust's description, is
not a real symbol in AOSP or HyperOS — it is that project's local variable.

The hook is an `after` hook that patches only the stale-cache case. When the
original returns false it re-checks `isSimPinSecure()` and
`isUnlockingWithBiometricAllowed(true)`; if either explains the false result the
value is left alone, so a pending SIM PIN can never be overridden into a trusted
state. Otherwise trust is re-derived from `TrustManagerService` through the
`@hide` `KeyguardManager.isDeviceSecure(int)`/`isDeviceLocked(int)` overloads.

The result is deliberately **not** written back into `mUserHasTrust`, which is
what upstream does. The field's only other reader is one assistant-visibility
check (SystemUI `:3211`), and writing a `SparseBooleanArray` from a getter
reachable off the main thread is a race AOSP asserts against in `onTrustChanged`.
Because `getUserHasTrust` is recomputed in bursts from the fingerprint listening
state, the two binder round-trips are cached per user for 200 ms and invalidated
eagerly from `onTrustChanged`. The setting defaults off and requires a SystemUI
restart.

`UnlockClipboardHooker` restores AOSP's clipboard overlay editor.
`ClipboardListener.onPrimaryClipChanged` (SystemUI `:105`) gates the whole overlay
on `sCtsTestPkgList.contains(getPrimaryClipSource())`, and on this baseline that
field is `Arrays.asList("com.android.cts.verifier")` (`:59`) — so the AOSP editor
only ever appears under CTS. The hook adds the app owning the current clip.

The list is rebuilt as `original + currentSource` rather than accumulated, so it
stays at two entries instead of growing by one for every app that has ever copied.
`sCtsTestPkgList` has exactly one reader in SystemUI (`:113`), so nothing else
observes the substitution, and `onPrepareHotReload` puts the original back.
Upstream's second branch, which hooks `start()` on baselines without the field, is
dead code here. HyperOS keeps showing its own editor too; both appear.

`sCtsTestPkgList` is `static final`, and ART on OS4 (Android 16+) rejects
reflective writes to static final fields of initialized classes with
`IllegalAccessException: Cannot set public static final field ...` (same failure
seen on-device for HyperCeiler's `Build.MANUFACTURER` spoofing). The write goes
through `util/StaticFieldWriter.kt`, which tries the reflective write first and
falls back to `Unsafe`. On OS4 the `sun.misc.Unsafe` shim dropped
`staticFieldBase`/`staticFieldOffset` (`NoSuchMethodError` at runtime), so the
writer reads the shim's `theInternalUnsafe` field and takes offset/base from the
platform's own `jdk.internal.misc.Unsafe.staticFieldOffset`/`staticFieldBase`,
writing through the shim's `putObject`/`putBoolean`; the hot-reload restore uses
the same path. Agent verification (2026-08-15, OS4 device): the hook installs
(`HOOK_OK`) but every copy previously failed at the field write — first with the
IllegalAccessException above, then with `NoSuchMethodError: staticFieldBase` —
both now fixed; `compileDebugKotlin`, unit tests and lint pass, and the debug
APK is installed with a SystemUI restart. On-device verification: after a real
copy with the fixed APK, logcat shows SystemUI creating and showing the
`ClipboardOverlay` window (pid of the new module process) with no new hook
failures in the LSPosed log, so the AOSP editor reappears; visual confirmation
is the user's.

`AospAppInfoEntryHooker` adds an entry to Security Center's app details page
(`com.miui.appmanager.fragment.ApplicationsDetailsFragment#onCreatePreferences`,
`:2916`) that opens Settings' SPA route `AppInfoSettings/{package}/{user}`, served
by `com.android.settings.spa.app.appinfo.AppInfoSettingsProvider`. The entry is
inserted directly after `app_default_pref` (`:2438`), shifting every later
preference's order by one. `UserHandle.myUserId()`/`getUserId(int)` are `@hide`, so
both go through reflection. The anchor is a `miuix.preference.TextPreference` on
this baseline, which is what the hooker creates, with `androidx.preference.Preference`
as the fallback. `package_name` and `miui.intent.extra.USER_ID` are the extras the
fragment itself reads (`:1431`, `:1437`).

`AospAppManagerEntryHooker` adds an overflow-menu entry to the app manager that
opens Settings' `AllAppList` SPA route
(`com.android.settings.spa.app.AllAppListPageProvider`). It hooks miuix's
`AppCompatActivity.onOptionsMenuViewAdded(Menu, Menu)` — where the end menu is
populated — and checks for `com.miui.appmanager.AppManagerMainActivity` inside, so
nothing else in Security Center picks up the entry. An empty end menu means miuix
has not populated it yet, so the entry is skipped for that pass. Unlike upstream,
the item carries no `Intent`; only the click listener starts the activity, so a
missing Settings SPA route fails as a no-op rather than an unhandled intent.

**`onOptionsMenuViewAdded` has an empty body** on this baseline
(`AppCompatActivity:444`), so ART inlines it away and a hook on it alone never
fires. Its only caller, `AppCompatActivity$Callback.onPanelViewAdded(int, View,
Menu, Menu)` (`:71`), is deoptimized as well. That caller is resolved by signature
across `AppCompatActivity.declaredClasses` rather than by the `$Callback` name, so
an obfuscated build still matches. Nothing in the APK overrides
`onOptionsMenuViewAdded`, so hooking the base class covers
`AppManagerMainActivity` → `com.miui.common.base.BaseActivity` →
`miuix.appcompat.app.AppCompatActivity`.

Both entries target `com.android.settings/.spa.SpaActivity`, which is
`exported=false`. That is fine here: `com.miui.securitycenter` and
`com.android.settings` both run under `android.uid.system`, and Security Center
holds `START_ANY_ACTIVITY`.

**The keyguard fix alone does nothing on a stock HyperOS device.** Verified on the
baseline: `dumpsys trust` lists only
`com.google.android.gms/.personalsafety.service.LockingTrustAgentService` — an
unrelated locking agent — and the Extend Unlock agent,
`com.google.android.gms/com.google.android.gms.auth.trustagent.GoogleTrustAgent`,
is absent from the enabled list even though the component itself is enabled and
exported. GMS therefore reports Extend Unlock as unavailable, and no trust is ever
granted for `getUserHasTrust` to report.

HyperOS ships **no** Trust agents settings screen (`cmd package query-activities`
finds no `TrustAgentSettings` activity), so the user cannot enable it either.
`ExtendUnlockHooker.syncTrustAgent` does it from SystemUI, which runs as uid system
and may write lock settings, through
`LockPatternUtils.setEnabledTrustAgents(Collection<ComponentName>, int)`
(`framework.jar:863`, which also calls `reportEnabledTrustAgentsChanged`). It runs
at `onPackageReady`, when an application `Context` exists.

That list is persistent system state that outlives the module, so the entry is
removed again when the setting is turned off; only that one component is touched.

The Extend Unlock configuration screen itself is
`com.google.android.gms/com.google.android.gms.trustagent.TrustAgentSearchEntryPointActivity`,
which is `exported=true` on this baseline, so the direct launch succeeds and the
trampoline hands off to `ConfirmUserCredentialAndStartActivity`. The SystemUI proxy
in `util/ExtendUnlockLauncher.kt` is only a fallback for builds where it is not
exported. The entry sits next to its switch on the AOSP Restore page and is greyed
out until the switch is on, since the trust agent is only enabled while it is.
`ProxyLaunchHooker` registers that receiver in
SystemUI, guarded by the module's signature-level permission and reusing
`RestartBroadcastHooker`'s registration pattern. The broadcast carries a target
key, never a component name: the receiver runs as uid system, so the component is
resolved from a hardcoded allow-list in `ProxyLaunchHooker.TARGETS`.

The manifest must `<uses-permission>` its own `RESTART_SCOPE` permission, not just
declare it. Receivers register with it as their `broadcastPermission`, which the
*sender* has to hold, so without it the module could not reach its own receivers
and every broadcast — restart and proxy launch alike — was dropped in silence.
`RestartUtils` correspondingly sends with no receiver permission: that argument
demands the *receiver* hold it, and the hooked system apps never will.

## Slider Percentage

The control-center percentage display is a plugin hook. SystemUI itself only
shows the topText percentage on the volume slider while super volume is active
(volume at/above max) and never on the brightness slider; the module attaches a
`SliderPercentageHooker` to the loaded `miui.systemui.plugin` classloader and
rewrites `ToggleSliderItemViewBinding.topText` (via
`ToggleSliderViewHolder.getTopText()`/`setTopTextVisible`) from
`BrightnessSliderController`/`VolumeSliderController` bind, progress and volume
sync events. The volume-dialog half hooks `com.android.systemui.miui.volume.*`
(`VolumeColumn`, `VolumePanelViewController`, `MiuiVolumeDialogView`), which on
OS4 moved from the SystemUI APK into the plugin APK.

`SystemUIPluginHooker` discovers the plugin by hooking
`PluginInstance.loadPlugin()`/`unloadPlugin()` and reading fields off the
instance. **OS4 renamed those fields** (verified on OS4.0.0.15.XPMCNXM):
OS3's private `mComponentName`/`mPluginFactory`/`mPlugin`/`mAppContext` (and
`PluginFactory.mAppInfo`) became public `componentName`/`pluginFactory`/
`pluginData` (with `pluginData.plugin` and `pluginData.context`, a
`PluginContextWrapper` whose `getClassLoader()` is the plugin loader) plus
`pluginFactory.pluginAppInfo`. `readPluginField` tries both spellings per
lookup, so one hooker covers both plugin generations; without it, the
`mComponentName` read throws inside the `loadPlugin` after-hook and the whole
slider hooker is never attached — the percentage silently stops working.

OS4 `VolumeSliderController.updateIconProgress` grew a second `boolean`
parameter (hook matches by name, so it keeps firing), and the OS4 brightness
panel reuses the same `BrightnessPanelAnimator`/`BrightnessPanelSliderDelegate`
shapes (delegate `binding` is now `BrightnessPanelBinding` whose `toggleSlider`
is the `ToggleSliderItemViewBinding`).

Agent verification status (OS4.0.0.15.XPMCNXM, 2026-08-14): `compileDebugKotlin`,
unit tests, and `lintDebug` pass; the debug APK was installed on the device and,
after a SystemUI restart, the LSPosed verbose log confirms every slider hook
installs — `BrightnessSliderController#onBindViewHolder/updateIconProgress/
setInMirror`, `BrightnessPanelAnimator#calculateViewValues/frameCallback`,
`BrightnessPanelSliderDelegate#prepareShow/updateIconProgress`,
`VolumeSliderController#onBindViewHolder/updateIconProgress(boolean,boolean)/
syncSystemVolume/updateSliderValue`, `VolumeColumn#initColumn`,
`VolumePanelViewController#initSuperVolumeColor/showVolumePanelH/
updateVolumeColumnH/updateSuperVolumeView/updateSuperVolumeText/
updateSuperVolumeViewColor`, `MiuiVolumeDialogView#updateSuperVolumeVisibility`,
`ToggleSliderViewHolder#updateBlendBlur` — with no hook or DexKit errors in
logcat. On-device functional testing (percentage visibility and live updates in
the control center and the volume panel) is performed by the user, not by the
agent (see AGENTS.md).

## AOSP IME Full Screen

Restores AOSP's full-screen IME navigation bar for input methods the user selects,
ported from Howard20181's Mi_AOSP_IME (GPL-3.0). It spans three places.

`AospImeHooker` runs in the selected keyboard's own process.
`InputMethodService.hideImeRenderGesturalNavButtons(String)` returns true for any
keyboard HyperOS does not recognise as MIUI-customised, collapsing the caption bar
to zero height, so the hook forces `IS_INTERNATIONAL_BUILD` — a
`private static final boolean` that is not a compile-time constant, and whose only
reader is that method, so the process-wide write does not leak elsewhere. It is
skipped when `InputMethodServiceInjector.isImeSupport(Context)` says MIUI already
draws its own bottom view for this keyboard; that method is `private static` and
declared on the injector, not on the `InputMethodServiceStub` interface.

The rest restores `NavigationBarController$Impl.getImeCaptionBarHeight(boolean)`
to 48dp (one overload only on this baseline — upstream's no-arg fallback branch is
dead code here), swaps `NavigationBarInflaterView.inflateLayout(String)` for the
configured handle, pads `NavigationBarView.mHorizontal` clear of the display's
rounded corners, and zeroes `DeadZone.mSizeMin`. `mHorizontal` must be read off
`NavigationBarView`; `NavigationBarInflaterView` declares its own field of the
same name. `getImeCaptionBarHeight` and `updateOrientationViews` are private and
small, so both are deoptimized.

Layout tokens are limited to what `NavigationBarInflaterView.createView` actually
inflates: `back`, `home_handle`, and `ime_switcher`. Everything else returns null.
`home_handle` sits in its own centre group, so a `space` placeholder for "no key"
does not decentre it.

Input-method packages cannot be listed in `HookEntry`'s `when (packageName)`
because the user picks them, so dispatch is gated on `AospImeConfig` before it.
The package is not re-validated in the hook process: every target is a
boot-classpath class present in every process, so a wrong entry installs hooks that
never fire, and `InputMethodManager` would need an app `Context` that only exists
at `onPackageReady` — after `InputMethodService` may already have run. The picker
validates instead.

`MiuiImeBottomHooker` makes MIUI's keyboard switcher list every enabled input
method rather than only the customised ones. Its target,
`com.miui.inputmethod.InputMethodBottomManager`, lives in the dex MIUI side-loads
through `InputMethodModuleManager.loadDex(ClassLoader, String)`, so `AospImeHooker`
attaches it as a child hooker onto that ClassLoader — the same pattern
`SystemUIPluginHooker` uses for the control-center plugin. `loadDex` throws for
anything that is not a `BaseDexClassLoader`, so the `after` hook checks
`param.throwable` first, and loaders are deduped through a `WeakHashMap`-backed set.

This half could not be verified against any local artifact: `com.miui.phrase`,
which supplies the dex, is not in the reverse-engineering workspace. It sits
behind its own setting (`KEY_AOSP_IME_MIUI_IME_LIST`, default off) and fails
silently.

`AospImeSystemHooker` is the system-server half.
`InputMethodDrawsNavBarResourceMonitor` derives `UserData.mImeDrawsNavBar` from
`config_isDesktopModeSupported`, false on phones, and only re-evaluates it at user
start and on overlay changes — never on an IME switch. So the flag is recomputed
where it is read, in
`InputMethodManagerService.getInputMethodNavButtonFlagsLocked(UserData)`
(`services.jar:2097`, `IME_DRAWS_IME_NAV_BAR = 1`), from
`Settings.Secure.navigation_mode` and the current `DEFAULT_INPUT_METHOD`, writing
the result back into `UserData.mImeDrawsNavBar` (a `final AtomicBoolean`) so the
next `onNavButtonFlagsChanged` agrees. The method is resolved by exact signature:
older platforms take `(int userId)` here, and a blind write to `args[0]` on that
shape would silently do nothing. It and its three callers are deoptimized.

`InputMethodManagerServiceImpl.isCallingBetweenCustomIME(Context, int, String)`
(`:757`) is extended so a selected keyboard passes MIUI's caller check.

Upstream also overrides `isCustomizedInputMethod(String)` (`:512`) to false for
selected packages. **That is deliberately not ported**: the method also feeds
`InputMethodManagerService.onHandleForceStop` (`:537`), whose "keep using it"
branch stops the system from resetting the default input method when a keyboard is
force-stopped — which is exactly how this feature gets applied. Overriding it would
make every apply-restart drop the user's keyboard selection.

The system-server half requires a reboot; there is no restart path for it.

`ui/page/AospImePage.kt` picks the target keyboards. It lists
`getEnabledInputMethodList()`, not `getInputMethodList()`: the latter also returns
services that can never be the current keyboard, such as Play Services' autofill
IME, and the system-server hook gates on `DEFAULT_INPUT_METHOD`, which only an
enabled method can ever be. The selection is applied on
demand rather than per toggle, because applying it requests Xposed scope and that
prompts the user; `ScopeManager.applyManagedDiff` is passed every installed input
method as the managed set, so unchecking one revokes its scope and nothing else is
touched. The preference is written only when the scope request succeeds.

`RestartScopeSelection` has no field for input methods and its nine booleans have
a symmetric `merge`/`without`/`intersect` contract, so instead the restart
broadcast carries `RestartProtocol.EXTRA_PACKAGES` and `RestartBroadcastHooker`
also self-kills when its own package is named there. The receiver is already
registered in every hooked package, so a newly scoped keyboard is reachable
without extra wiring. `RestartUtils.forceStopPackages` is the sender.

`InputMethodModuleManager` and `InputMethodServiceInjector` are in
`miui-framework.jar`, not `framework.jar`. Both are on the boot classpath at
runtime so resolution from the keyboard process works, but the local
`/Users/ink/developer/reverse/miui-framework.jar` is unversioned and not part of
the recorded baseline — pull a versioned copy from an OS 3.0.308.0 device and cache
it before trusting anything read from it.

## Xposed Scope

The module declares `staticScope=false` in
`app/src/main/resources/META-INF/xposed/module.prop`, so it can call
`XposedService.requestScope` for input methods the user selects at runtime. The
cost is that LSPosed then lets the user edit the whole scope list, and removing a
required entry silently disables whatever hooks it.

The scope is declared twice and both copies must stay identical:
`app/src/main/res/values/arrays.xml` (`xposed_scope`, referenced from the
manifest's `xposedscope` meta-data) is the runtime source of truth read by
`ScopeManager.requiredScope`, and `META-INF/xposed/scope.list` is the copy LSPosed
reads at install time. They had drifted — `arrays.xml` was missing
`com.miui.powerkeeper` and `com.xiaomi.bluetooth` — and are now aligned.
`scope.list` carries no comment because its parser is not known to accept one.

The system server is declared once, as `system`. Current libxposed builds no longer
accept `android` for it, so listing both made `getScope()` permanently report
`android` as missing. `ScopeManager` still treats an `android` entry coming back
from an older LSPosed as satisfying `system`, but only `system` is required.

`util/ScopeManager.kt` wraps the libxposed service 102 scope API
(`getScope()`, `requestScope(List, OnScopeEventListener)`, `removeScope(List)`).
Every call can throw an unchecked `XposedService.ServiceException` and
`requestScope`'s callbacks arrive on a Binder thread, so each result is settled
once through an `AtomicBoolean` — a partial approval can still be followed by a
failure callback. `applyManagedDiff` intersects removals with the caller's managed
set and subtracts `requiredScope`, so unchecking an entry gives its scope back
while a package another feature needs is never revoked.

`ScopeWarningCard` on the Home page names any required scope the user has removed
and offers to request it back, as a `SmallTitle` + `Card` of `BasicComponent` rows
like every other section on that page. It is a separate card rather than a fourth
hero card state, which would collide with `hotReloadAvailable`.

`missingRequiredScope` skips the module's own package: `getScope()` does not report
self-scope, so including it reported HyperTweak as unhooked on every launch, and
the hero card already tells the user to check HyperTweak itself when the module is
not active.

## Passkey / Credential Manager Page (密码和账号)

`PasskeyHooker` (feature `unlock_passkey`) unlocks the AOSP credential-manager UI on
domestic HyperOS by forcing `miui.os.Build.IS_INTERNATIONAL_BUILD` around the three
Settings entry points. Verified on OS4.0.0.15.XPMCNXM (Settings
`5e1fadcf63fb29fd`, decompiled to `cache/settings-5e1fadcf63fb29fd`):

- The 密码、通行密钥和账号 page is `AccountDashboardFragment` /
  `AccountPersonalDashboardFragment` (`accounts_*_dashboard_settings_credman.xml`):
  a `PrimaryProviderPreference` row ("首选服务", key `default_credman_autofill_main`)
  plus a `CredentialManagerPreferenceController` switch list ("其他服务").
- `IS_INTERNATIONAL_BUILD` is `public static final boolean` with a non-constant
  initializer in `/system_ext/framework/miui-framework.jar` (Settings reads it with
  `sget-boolean`, so it is not inlined). ART rejects the reflective write on OS4
  (`IllegalAccessException: Cannot set public static final field`); the
  `StaticFieldWriter` Unsafe fallback works — verified on-device with `app_process`
  (reflective write failed, Unsafe write flipped the field). The module hooks install
  (`HOOK_OK`) and fire; the provider list rendering, the picker selection and the
  flag forcing were all confirmed working by on-device instrumentation.

Two user-visible failures on this baseline were diagnosed and fixed:

1. **Tapping a 其他服务 row did nothing.** `onLeftSideClicked` gates on
   `IS_INTERNATIONAL_BUILD &&` (hooked, flag forced — works) and then calls
   `CombinedProviderInfo.launchSettingsActivityIntent`, which silently returns false
   when the provider declares no `settingsActivity`. GMS's credential XML
   (`xml/google_id_provider`, `xml/remote_provider` — flat obfuscated `res/*.xml`
   files in the GMS base APK) declares only `settingsSubtitle`; the autofill fallback
   (`AutofillServiceInfo.getSettingsActivity` → `AutofillSettingsActivity`) is
   manifest-disabled/not-exported and is the wrong page anyway. Edge
   (`PasskeyCredentialProviderService`) and Microsoft Authenticator declare nothing.
   So the intent was null and the tap was a silent no-op. `hookSettingsActivityLaunchFallback`
   now hooks `launchSettingsActivityIntent`: GMS goes straight to its exported
   `com.google.android.gms.credential.manager.PasswordManagerActivity`, and any other
   failure falls back to the app-details page (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`).
   `Context.startActivityAsUser`/`UserHandle.of` are `@hide`, so the fallback resolves
   them by name.
2. **The 其他服务 switches toggled visually but never committed, and showed off on page
   open despite being enabled.** Two stacked causes, both verified on-device:
   - `CombiPreference` wires its commit listener (`onCheckChanged` → `setEnabledProviders`)
     only when the row widget is `miuix.slidingwidget.widget.SlidingSwitch`; on OS4 the
     expressive settings theme makes `PrimarySwitchPreference` use
     `settingslib_expressive_preference_switch.xml` (a `MaterialSwitch`), whose super-class
     listener (`PrimarySwitchPreference.lambda$onBindViewHolder$0`) only flips the local
     `mChecked` — so toggles never reached the CredentialManager. `hookCombiPreferenceSwitchCommit`
     hooks `CombiPreference.onBindViewHolder` and, for any non-SlidingSwitch widget, attaches
     an `OnCheckedChangeListener` that reflectively calls the preference's
     `mOnClickListener.onCheckChanged(preference, checked)` (reverting on `false`, the
     provider-limit case), mirroring the native SlidingSwitch path.
   - Even with the commit working, the switches showed **off on page open while the system
     state was on**: the rows are built (`buildPreferenceList`) before the controller's
     `update()` refreshes `mEnabledPackageNames`, and the controller's own sync
     (`setAvailableServices` → `mPrefs.setChecked`) only reaches rows already registered in
     `mPrefs` — which on this build were empty during the initial display pass. `wireCombiPreferenceSwitch`
     therefore also forces the switch (and the preference's `mChecked`) to the controller's
     `mEnabledPackageNames` state at every bind, read through the listener's `this$0`/
     `val$packageName` fields. Verified: toggles commit (`setEnabledProviders success`,
     `credential_service` gains the components), the settings persist, and the switches
     render on after a page re-open.

**SecurityCenter half fixed for the current build**: the default-credential writers moved
from `com.miui.securitycenter.Application` to `com.miui.securitycenter.service.CacheService`
(private no-arg `C()`/`D()` wrappers calling R8-renamed `(String,int)` helpers `y0`/`x0` that
read a resource and write `Settings.Secure`), so the old `Application`-scoped DexKit queries
silently resolved nothing and MIUI's defaults were never blocked. `hookSecurityCenter` now
resolves the helpers and wrappers by shape (resource-read + `putString` invokes, and the
`autofill_service` / `credential_service`+`credential_service_primary` string wrappers)
without a class-name dependency, blocking them while `unlock_passkey` is on.

## FCM Live (Google Push Fix)

Settings → Tweaks → Fix Google Push (FCM Live), ported from howard20181's
HyperOS_FCM_Live (GPL-3.0; reference clone at
`/Users/ink/developer/refrences/HyperOS_FCM_Live-main`). Two hookers:

- `FcmLiveSystemHooker` (system_server): allows GMS c2dm broadcasts through
  `GreezeManagerService.isAllowBroadcast` and stops
  `GreezeManagerService.deferBroadcastForMiui` /
  `DomesticPolicyManager.deferBroadcast` from deferring
  `GCM_RECONNECT`/`CONNECTED`/`DISCONNECTED`/`HEARTBEAT_ALARM`; disables the
  GMS-limit action; removes GMS from `ListAppsManager`'s system blacklist and
  seeds it into the use-data whitelist; lets GMS remote intents through
  `BroadcastQueueModernStubImpl.checkApplicationAutoStart`; adds GMS to
  `ProcessPolicy.getWhiteList`; removes it from
  `AwareResourceControl.mNoNetworkBlackUids`; and in
  `ActivityManagerService.broadcastIntentWithFeature` adds
  `FLAG_INCLUDE_STOPPED_PACKAGES` plus a `GOOGLE_C2DM` temporary power
  exemption for the target package; and `BroadcastSkipPolicy`
  `shouldSkipAtEnqueueMessage`/`shouldSkipMessage` return null for GMS
  c2dm so tombstone-freezer modules cannot skip delivery (see below).
- `FcmLivePowerKeeperHooker` (com.miui.powerkeeper): forces
  `NetdExecutor.initGmsChain` to ACCEPT, drives `GmsObserver`
  `updateGmsAlarm`/`updateGmsNetWork`/`updateGoogleReletivesWakelock` to
  false, and adds GMS to `GlobalFeatureConfigureHelper.getDozeWhiteListApps`.

OS4 renamed `ListAppsManager`'s fields and made them static (verified on
OS4.0.0.15.XPMCNXM, miui-services.jar classes2.dex): `mSystemBlackList` →
`SYSTEM_BLACK_LIST`, `mUseDataWhiteList` → `USE_DATA_WHITE_LIST`; the hooker
tries both spellings. The constructor only removes GMS itself when
`PolicyManager.CN_MODEL` is false, so the blacklist removal is repeated in
the `isInWhiteList` hook to cover construction order. GMS is seeded in place
into the static `USE_DATA_WHITE_LIST` set so the platform's own
add/removeAll mutations survive.

Agent verification (OS4.0.0.15.XPMCNXM, 2026-08-15): before the fix the
LSPosed verbose log reported `Failed to hook ListAppsManager`
(NoSuchFieldException on `mSystemBlackList`) and `dumpsys greezer` showed
GMS absent from the `local:` whitelist; after the fix every FcmLive hook in
both processes reports HOOK_OK and `dumpsys greezer` lists
`com.google.android.gms` in `local:`, with GMS also in the deviceidle
whitelist. `compileDebugKotlin` and `testDebugUnitTest` pass. On-device
functional testing (push delivery) is performed by the user. The setting
requires a reboot for the system half and a PowerKeeper restart (its
restart-scope selection only covers PowerKeeper, per the UI summary).

### Tombstone-freezer interference (NOACTIVE)

Tombstone-freezer modules such as `cn.myflv.noactive` hook the private
`BroadcastSkipPolicy.shouldSkipMessage` variants and return
`"Skip broadcast to frozen process"` for any broadcast whose target
process is frozen (`!o.f9702d && q.f9722g`), so GMS's `c2dm.intent.RECEIVE`
never reaches a tombstoned app: GMS logs `broadcast intent callback:
result=CANCELLED` and retries every ~30s (`FcmRetry`), and FCM Diagnostics
records "No response to broadcast" with `time=1ms`. The freeze flag is the
cgroup freezer (`cgroup.freeze=1`), applied by the module, not greeze. The
whitelist flag (`t5.o.f9702d`) is `v6.b.a()` = NOACTIVE's 白名单应用
(`MasterConfig.boot.getUserAppSet()`), **not** its 网速识别/网络识别
setting (that only feeds the `网络传输中` thaw-while-transferring state).
Because the skip is inside the policy methods, HyperTweak short-circuits the
**public** entry points `shouldSkipAtEnqueueMessage(BroadcastRecord,Object)`
and `shouldSkipMessage(BroadcastRecord,Object)` before the private variants
run, so the bypass holds regardless of module hook order. Verified on-device
(2026-08-15): after installing the hook, the queued FCM backlog was
delivered instead of skipped and the `FcmRetry`/CANCELLED loop stopped.

## Bypass GMS China ROM Restrictions (卸载国行 GMS 限制)

Settings → Tweaks → System Core → Bypass GMS China ROM Restrictions
(`KEY_REMOVE_GMS_RESTRICTION`, `hook/rules/system/SystemConfigHooker.kt`) makes
GMS treat the device as non-CN by removing the two CN-marker features from
`SystemConfig`'s `mAvailableFeatures` at boot. The markers are declared in
`/product/etc/permissions/cn.google.services.xml`:

```xml
<feature name="cn.google.services" />
<feature name="com.google.android.feature.services_updater" />
```

read into `mAvailableFeatures` during the no-arg `SystemConfig()` constructor
(`readPermissions` → `addFeature`), so the hooker's `after` hook on that
constructor calls the private `removeFeature(String)` once per process to drop
them. The hook runs only in system_server (`scope.list` → `system`) and requires
a **full device reboot** — `SystemConfig` is a singleton built once at
system_server boot, so a scoped-app restart never re-runs it.

OS4 facts verified on OS4.0.0.15.XPMCNXM device (2026-08-19): `SystemConfig()`
is a private no-arg constructor and `getInstance()` builds it under
`synchronized(SystemConfig.class)`; `RoSystemFeatures.getReadOnlySystemEnabled
Features()` returns an empty `ArrayMap` and `Injection.maybeHasFeature` returns
null on both OS3 and OS4, so `removeFeature` scores neither the
read-only-skip branch nor a stale map — the two CN features are always removable.
LSPosed log confirms `SystemConfigHooker: HOOK_OK
target=com.android.server.SystemConfig#<init>()` installs in system_server
during early boot, so class/method resolution is fine on OS4.

The one genuine fragility is **timing of the preference read**: the removal was
gated on `Preferences.getBoolean(KEY_REMOVE_GMS_RESTRICTION)` read *inside* the
constructor callback, but `SystemConfig()` can be constructed at an arbitrary
point in early boot. If `Preferences` is not yet initialized/has not received
the daemon value at that moment, the read falls back to `false`, the removal is
silently skipped, and — because the singleton never constructs again — it is
skipped forever until the next full reboot. The hooker now captures the desired
state once at `onHook()` (after `initPreferences()` has run on the
system_server path, so the read is reliable) and only installs the constructor
hook when the switch is on; `onHook` logs
`removeGmsRestrictions=enabled/disabled` and `removeGmsRestrictions` logs a
success line after removal so the user can confirm on the next boot via logcat
(`TAG HyperTweak`, LSPosed verbose). It also hardens the toggle: with the switch
off, no constructor hook is installed, so no features are removed.

Note: toggling the switch is **not** part of `TWEAK_RESTART_SCOPES` (the
`onRemoveGmsChange` handler in `MainActivity` writes the preference directly,
without `markTweaked`); it takes effect only on the next full device reboot.
Because Bypass also removes the same two CN gates Quick Share relies on, the
"Unlock Nearby Share" switch is greyed out (`enabled = !removeGms`) while it is
on — see `TweaksScreen`.

## Quick Share (Nearby Share) on CN GMS

Settings → Tweaks → System Core → Unlock Nearby Share (Quick Share)
(`KEY_QUICK_SHARE_ENABLED`, `hook/rules/gms/QuickSharePhenotypeHooker.kt`). CN
(domestic) Google Play services hides Quick Share through two gates, both
driven by the CN marker features `com.google.android.feature.services_updater`
+ `cn.google.services`:

1. **Init gate**: `com.google.android.gms.nearby.sharing.ModuleInitializer.e(Context)`
   returns the phenotype flag `sharing_supports_latchsky` (flag package
   `com.google.android.gms.nearby`) when the CN features are present; the flag
   ships `false` on CN builds, so the runtime never starts (`Sharing is
   disabled for reason UNSUPPORTED_DEVICE_TYPE_LATCHSKY`).
2. **Device-type gate**: `defpackage.bmwx.i(Context)` classifies a CN GMS
   device as "latchsky", which makes `dqvq.f()` report a blacklisted device
   type; `imck.d/e` then refuse discovery and `egph.a()` makes
   `GAccountUtils#getSupportedAccounts` fail.

The persistent fix is an override row in GMS's credential-encrypted
`phenotype.db` (`/data/user/0/com.google.android.gms/databases/phenotype.db`),
`sharing_supports_latchsky` in `flag_overrides` with `active IS 1`, `type = 2`
(boolean), `value = 1` (true), keyed by `config_package_name` + the wildcard
`*` account, which wins over server-delivered flag values at read time. **A
plain DB row is not enough on its own**: the flag store serves per-package
protobuf snapshots (`phenotype/shared/<...>.pb`) rebuilt from
`config_packages.flags_content` + `flag_overrides` by the persistent-process
flag store, and nothing guarantees a rebuild after a raw write. The hooker
therefore combines four layers (all idempotent, all gated on the key):

1. Raw DB override row (persistent, self-healing at every GMS start).
2. The official `com.google.android.gms.phenotype.FLAG_OVERRIDE` broadcast,
   sent from the GMS process (the receiver `FlagOverrideChimeraReceiver` is
   protected by the signature permission `PHENOTYPE_OVERRIDE_FLAGS`, which only
   GMS holds): extras `package`/`user`(`*`)/`commit`/`flags`/`values`/`types`
   (or `action=delete` + `flag` for the off path) → `SetFlagOverrides
   OperationCall` (`fldk`/`fldm`) commits the override with GMS's own account
   semantics, rebuilds the shared `.pb` snapshots (`flfi`,
   PhSharedDirectoryWriter, which merges `flag_overrides`), and broadcasts
   `com.google.android.gms.phenotype.COMMITTED`, which re-runs
   `ModuleInitializer`. Sent only when the DB state actually changed.
3. Hook `ModuleInitializer.e(Context)` → true (stable, non-obfuscated class
   name — the exact gate the flag feeds), so initialization happens even if a
   future GMS version changes flag delivery.
4. Hook `bmwx.i(Context)` → false (best-effort; the class name is R8-obfuscated
   and version-fragile, so it silently no-ops when it does not resolve) to open
   the discovery device-type gate and the account-metadata path.

Mechanism, verified in `com.google.android.gms-OS4-device.apk` (26.31.31,
temporary JADX output under `/tmp/gmsapk/jadx{0,2,7,11,12,15}`): the flag is
read through `jnjr.dL()` → `guyf.a.i(261, "sharing_supports_latchsky", false)`
from the flag container `jngf.a = guwl("com.google.android.gms.nearby", …)`;
`sharing_supports_latchsky` has exactly one consumer (`ModuleInitializer`).
`PhenotypeDbHelper` (`fkyz`, DB file `phenotype.db`, opened via
`context.getDatabasePath`) stores flags in the current schema's
`flag_overrides(config_package_name, account_id, active, name, value, type,
source)` + `accounts` tables (legacy pre-1001 schema:
`FlagOverrides(packageName, user, name, flagType, intVal, boolVal, floatVal,
stringVal, extensionVal, committed)`). Overrides are matched at read time
(`flfi`) by `(config_packages.name = ? OR flag_overrides.config_package_name
IS ?)` and `(accounts.name = ? OR accounts.name = '*')` with `active IS 1`;
`type = 2` is boolean, `value = 1` is true, `source = 0` marks a local user
override. Both SQL shapes were validated against a real SQLite with the
extracted schema, including GMS's read-side EXISTS query. The broadcast
format was taken from `FlagOverrideChimeraReceiver.onReceive` (26.31.31);
26.30.61 is expected to match but is not verified locally.

The DB layer runs inside the GMS main process at package-ready (GMS must be in
the module's Xposed scope), enforces the row at every GMS start (upsert on,
delete off), and retries once after 30s when the CE database is not accessible
yet (early boot). `PRAGMA busy_timeout = 5000` covers write locks held by
GMS's own flag store. Schema-variant detection via
`sqlite_master`/`PRAGMA table_info`.

Scope: `com.google.android.gms` is a declared entry in `arrays.xml`/`scope.list`
(shown as a recommended scope in LSPosed), so the toggle only flips the
preference and marks the tweak dirty (`TWEAK_RESTART_SCOPES` →
`RestartScopeSelection(gms = true)`); the standard Home "Restart Scoped Apps"
dialog (which gained a `gms` field) restarts Google Play services, and the
hooker applies or removes the override at package-ready. Toggling off restarts
GMS first — while it is still scoped, so the hooker removes the row — and no
dynamic scope request is needed. If the restart loses the race, the override
row survives; that is benign and a later toggle-off retries. Applying takes
effect after the GMS restart; verification of the Quick Share UI is the
user's.

## Media Editor Watermark Unlock

Settings → Experimental → Watermark Unlock
(`ui/page/WatermarkPage.kt`, `Route.Watermark`,
`hook/rules/mediaeditor/MediaEditorWatermarkHooker.kt`) unlocks watermark
categories in the media editor (`com.miui.mediaeditor`, 相册编辑; gallery
itself is a Rust/Flutter shell with no dex to hook). Reverse engineering for
2.10.37.9 (OS4.0.0.15.XPMCNXM) lives in
`cache/mediaeditor-292ff5db343e5f13/` (`WATERMARK_UNLOCK_PLAN.md`, APK pulled
from the device as `MiMediaEditor-OS4.0.0.15.XPMCNXM-device.apk`).

Watermark visibility is gated in three layers, each with its own hooks:

1. **Device checks** — R8-obfuscated static helpers `wn.a` / `zn.a`
   (classes2.dex, resolved by literal name with DexKit string signatures as
   fallback) decide which brand/theme groups this device may see. Every check
   is a parameterless `boolean` over `Build.DEVICE` / `Build.BRAND` /
   `ro.boot.product.theme_customize` / `ro.theme_customize`, shared by the
   local template menu (`vy.i.d`) and the cloud filter (`vy.i0.a`), so one
   hook per method unlocks both sides of one category. Each category has its
   own preference: `wn.a.b()`=leica, `wn.a.i()`=xiaomi, `wn.a.e()`=redmi,
   `wn.a.c()`=poco, `wn.a.g()`=victoria, `wn.a.h()`=west_coast_3,
   `zn.a.g()`=lcc, `zn.a.h()`=west_coast_1, `zn.a.i()`=west_coast_2.
   **`zn.a.b()` must never be hooked**: the local watermark menu is wrapped in
   `if (!zn.a.b() || zn.a$q.b(null))`, so forcing it true hides the whole menu.
2. **Cloud config fields** — the `CloudWatermarkData` constructor after-hook
   (business class name kept, 21-parameter constructor) rewrites the
   per-watermark restriction fields parsed from the `watermark_config_v2`
   cloud config (server sends the full list; filtering is client-side) while
   the master switch is on: validFrom=0/validTo=Long.MAX_VALUE (smali-verified
   check is `now <= validTo && validFrom <= now`), supportRegions=["*"]/
   unSupportRegions=[], name_length_limitation=[], minWmVer=0.0 and
   supportDisplayApp gains "ALL". These "integrity" limits follow the master
   switch rather than per-category switches, because a category such as leica
   mixes entries with different restrictions (festival editions, camera-only
   display apps, higher min versions) and unlocking the category must show
   them all (initially they had their own switches; that left most cloud
   entries filtered when only the category switch was on). The LCC tag remap
   stays behind `KEY_WM_LCC`: `lcc_global_devices` / `lcc_cn_devices` tags in
   the support list become `*` and are dropped from the unsupported list, so
   both LCC sets pass regardless of `ro.product.mod_device`. List fields are
   mutated through the constructor arguments (shared instances); the long/
   double fields are written on `thisObject` by type order (first `long` =
   validFrom, second `long` = validTo, the only `double` = minWmVer — the
   declaration order R8 preserves).
3. **Downloaded-resource filter** — after a cloud watermark zip lands in
   `files/watermarks/`, `tb0.o0` (PhotoWmManager) re-scans and applies a
   second filter chain (id whitelist / validity / device_type / region /
   theme / system properties / name length). The whole chain is skipped when
   the system property `camera.cloud.watermark.debug` is true, read by the
   obfuscated `tb0.v$b.invoke()`; hooking that to true while
   `KEY_WM_UNLOCK_MASTER` is on keeps every downloaded resource usable.

`KEY_WM_DOWNLOAD_ALL` bulk-fetches every `CloudWatermarkItem` through the
same `yy.m.a(WatermarkItem, WatermarkCategory, ee.e.a)` dispatch path
(resource fetcher `yy.h`, listener supplied via a dynamic proxy) in a
`vy.i.b(List, boolean)` after-hook, once per editor process.

### Camera half (`com.android.camera`)

`hook/rules/camera/CameraWatermarkHooker.kt` (switch `KEY_WM_CAMERA`) unlocks
the camera's own watermark gallery (`WmGalleryFragment` → `WmGalleryPreference`,
data via the `WmBaseManager` abstraction `Gg.P`). The camera syncs the full
watermark set (festival editions included, e.g. `2026_parents_day`) into
`files/watermarks/`, but `Gg.P.d(boolean)` (`filterData`) applies the same
filter chain the editor uses — id whitelist, validity, device_type,
**system-properties match**, theme, region, name length — and on this baseline
the Leica set (ids 88..94, 111) all require `"ro.boot.product.theme_customize":
"lcc"` in their `config.json`, so a non-LCC device sees no Leica watermarks.
The chain is wrapped in `if (!C1686u.f6071a.getValue())`, and the obfuscated
reader `Gg.u$b.invoke()` reads the same `camera.cloud.watermark.debug` property
as the editor's `tb0.v$b`; hooking it to true while `KEY_WM_CAMERA` is on skips
the whole chain. The property read runs per gallery scan, so the switch takes
effect on the next menu open without restarting the camera.

All switches are read live inside the callbacks (100 ms Preferences memo), so
toggling a category takes effect the next time the watermark menu is built
without restarting the editor; only the first enable of
`KEY_WM_UNLOCK_MASTER` needs the editor process restarted so the hooks are
installed. `com.miui.mediaeditor` is a declared scope entry in
`arrays.xml`/`scope.list`; the page requests it dynamically via
`ScopeManager.request` when the master switch is turned on, so the user does
not have to toggle LSPosed by hand.

Agent verification (2026-08-16, OS4 device): `compileDebugKotlin`,
`testDebugUnitTest`, `lintDebug` and `assembleDebug` pass; the debug APK is
installed, LSPosed auto-merged the new scope entry after reinstall, and the
media-editor process reports HOOK_OK for every hook (`wn.a#b/i/e/c/g/h`,
`zn.a#g/h/i`, `CloudWatermarkData#<init>`, `tb0.v$b#invoke`,
`vy.i#b(List,boolean)`) with no failures. On-device functional testing (the
watermark menu showing the unlocked categories, festival watermarks outside
their time window, bulk downloads) is performed by the user.

## Module Configuration Storage

The module's settings live in two independent copies, which is why they "survive
uninstall" (verified on-device 2026-08-15):

- **Module data** (`/data/user/0/com.takekazex.hypertweak/shared_prefs/`
  `hypertweak_settings.xml` + `hypertweak_cache.xml`): deleted on uninstall.
- **LSPosed daemon copy**: `service.getRemotePreferences(Preferences.NAME)` is backed
  by `/data/adb/lspd/config/modules_config.db`, table `module_configs` (one row per
  key, Java-serialized value, keyed by module package with `ON DELETE CASCADE` on
  `modules`). LSPosed keeps the module row, so a reinstall silently restores the old
  config. Every hooked process also keeps its own `hypertweak_cache.xml` in *its*
  data dir, which is unreachable from the module and would keep serving stale values
  after a remote wipe.

`Preferences.clearAllSettings()` wipes all three: the remote copy (with a bumped
`prefs_epoch` row left behind), the module's own prefs, and the module's own cache.
All getters compare the cache's epoch against the remote epoch and treat a mismatch
as "cache stale → default", so hooked processes pick the reset up on their next read
without a reboot. A reinstall/clear-data is detected through `first_run_token` in the
module's own prefs (fresh data dir + remote config present → auto-reset once; the
presence of `last_known_module_activated`, written on every launch by recent versions,
marks an ordinary update as not-fresh so upgrades never wipe settings), and
Settings → Other → Clear All Settings offers the same reset manually.

## Reverse Engineering Workspace

Platform artifacts and decompiler output are intentionally external to the Git
repository:

- Artifact root: `/Users/ink/developer/reverse`
- Derived-output root: `/Users/ink/developer/reverse/cache`

The current HyperOS baseline, verified on 2026-07-25, is:

- SystemUI source:
  `/Users/ink/developer/reverse/SystemUI-OS3.0.308.0.WPMCNXM.apk`
  (`2c09361772ee6ec62d6356f165e3ed32b16318d55e9b3bb4fe727120e1502c50`)
- SystemUI cache:
  `/Users/ink/developer/reverse/cache/systemui-2c09361772ee6ec6`
  - JADX source: `jadx/`
  - APKTool resources and smali: `apktool/`
  - Cached input and checksum: `input/SystemUI.apk`, `SHA256SUMS`
- MIUI AOD source:
  `/Users/ink/developer/reverse/MIUIAod-DEV-2337.0.0.1-05181916.apk`
  (`c552fd96a8582b02f56f5b0027dcb027f316d2f59fab75152a74c49937af69ac`)
- MIUI AOD cache:
  `/Users/ink/developer/reverse/cache/aod-c552fd96a8582b02`
  - JADX source: `jadx/`
  - APKTool resources and smali: `apktool/`
  - Cached input and checksum: `input/MIUIAod.apk`, `SHA256SUMS`

The current MIUI AOD JADX run produced usable output but reported multiple
method decompilation failures. Use the APKTool smali from the same cache when a
class or method is missing or incomplete in `jadx/`.

- Security Center source:
  `/Users/ink/developer/reverse/MIUISecurityCenter-12.7.4-260711.0.1.apk`
  (`2627ffd76e9d8f7962e1a8d9a94ede950070306871540f93587f786c73c49388`)
- Security Center cache:
  `/Users/ink/developer/reverse/cache/securitycenter-2627ffd76e9d8f79`
  - JADX source: `jadx/` (reported 64 method decompilation failures; use the
    APKTool smali for anything missing)
  - APKTool resources and smali: `apktool/`
  - Cached input and checksum: `input/MIUISecurityCenter.apk`, `SHA256SUMS`
  - miuix is bundled here, so `miuix.appcompat.*` and `miuix.preference.*` resolve
    from this APK rather than the framework.

Other available mappings are:

- OS4 (HyperOS 4.0.0.15.XPMCNXM, pulled 2026-08-14 from the myron
  25102RKBEC device, build `CP2A.260605.016`):
  - Control-center plugin:
    `/Users/ink/developer/reverse/MIUISystemUIPlugin-OS4.0.0.15.XPMCNXM.apk`
    (`7a0dfbe892f558393ad28ae8aea2a12fe152de972354b067500ed16ca9e17f3e`) ->
    `/Users/ink/developer/reverse/cache/systemui-plugin-7a0dfbe892f55839`
    (JADX `jadx/`, APKTool `apktool/`, input `input/plugin.apk`)
  - SystemUI:
    `/Users/ink/developer/reverse/MiuiSystemUI-OS4.0.0.15.XPMCNXM.apk`
    (`9af08c49ea6e412e52a5ffba0d8ca0bc91034b6092c25a4af4776ac010e13cf7`) ->
    `/Users/ink/developer/reverse/cache/systemui-9af08c49ea6e412e` (JADX `jadx/`)
  - On OS4 the `com.android.systemui.miui.volume.*` classes moved from SystemUI
    into the plugin APK, and `PluginInstance` dropped its `m*` fields (see the
    Slider Percentage section).
- SystemUI plugin: `/Users/ink/developer/reverse/miui.systemui.plugin.apk`
  (`f85d514f440836aa73bcc13ffc32abb5937cf658f9584a5b828054e938ee7cc0`) ->
  `/Users/ink/developer/reverse/cache/miui-systemui-plugin-current`
- Launcher 8: `/Users/ink/developer/reverse/MiuiHome-RELEASE-8.00.02.2771-07171632-R.apk`
  (`2ceb8193d82f9566f02c196cd3a35e311d81e84fb7d3bcef2b96558b7a1be1ac`)
  -> `/Users/ink/developer/reverse/cache/launcher-2ceb8193d82f9566`
  - The manifest declares `android:hasCode="false"` and requires
    `hyperos.rustruntime.v4`; `libapp_launcher.so` contains the Rust gesture
    implementation and `libapp.so` contains Flutter AOT code.
  - Bundled Flutter version: `3.38.3-mi-1.17.16-beta3`.
  - Runtime logs confirm `gesture_input_monitor` can call `pilfer_pointers` at
    pointer down, canceling other gesture spy windows before a long-press timer.
- Launcher 7: `/Users/ink/developer/reverse/系统桌面_RELEASE-7.00.20.0000-05141423.apk`
  (`82fa1e1b776cf0e84a94a2fa31d392314f334bf93460ee4718443d07e9a113e4`)
  -> `/Users/ink/developer/reverse/cache/launcher-current`
- Framework client: `/Users/ink/developer/reverse/framework-OS3.0.308.0.WPMCNXM.jar`
  (`36426149118b109a33f4a8b143bb5390dbe50ca0e21d00fd16f357710b368f0f`)
  -> `/Users/ink/developer/reverse/cache/framework-36426149118b109a`
- Settings: `/Users/ink/developer/reverse/Settings-OS3.0.308.0.WPMCNXM.apk`
  (`022d14a4ae7d30139a8a0f251df45780960fe650b8865cf6fb320f7e8055a9f8`) — pulled to
  confirm the `AppInfoSettings` / `AllAppList` SPA routes exist; not decompiled, so
  there is no cache entry for it.
- OS4 Settings (pulled 2026-08-15):
  `/Users/ink/developer/reverse/Settings-OS4.0.0.15.XPMCNXM.apk`
  (`5e1fadcf63fb29fdb11f05da6db10191529fe3f9f59e74f6a3c7d13b61e4263b`) ->
  `/Users/ink/developer/reverse/cache/settings-5e1fadcf63fb29fd`
  (JADX `jadx/`, APKTool smali+res `apktool/`, input `input/Settings.apk`).
- OS4 framework and services (pulled 2026-08-15):
  `/Users/ink/developer/reverse/framework-OS4.0.0.15.XPMCNXM.jar`
  (`ab30b2c82d158e18fa83efb02ce32ba838456832708d2e4e4a26a1fbfecc3bbc`),
  `/Users/ink/developer/reverse/services-OS4.0.0.15.XPMCNXM.jar`, and
  `/Users/ink/developer/reverse/miui-services-OS4.0.0.15.XPMCNXM.jar`
  (`e78defb013d4dd50d1705147853b351828f130be2b6a45cfc446b7b7407903af`;
  `com.miui.server.greeze.*` — GreezeManagerService, ListAppsManager,
  AwareResourceControl — lives in its `classes2.dex`).
- Device-side app artifacts (pulled 2026-08-15, unversioned):
  `/Users/ink/developer/reverse/com.google.android.gms-OS4-device.apk` (GMS
  26.31.31; credential-provider XMLs live under obfuscated flat `res/*.xml` files)
  and `/Users/ink/developer/reverse/com.microsoft.emmx-device.apk` (Edge).
- Framework services: `/Users/ink/developer/reverse/services.jar`
  (`2e880646dd2e4d92c1a12111aaa70b8eab9a8edf838eab2eb33d87a14618d3a9`) ->
  `/Users/ink/developer/reverse/cache/framework-services-2e880646`

The gesture-bar contextual-search compatibility path references MiCTS commit
`2ead158104bdb8605cbb5eae39a30d44db71bab6` (GPL-3.0).

On the current baseline, HyperOS only enables its voice-interaction Binder-death
rebind path for `com.miui.voiceassist`. Third-party services can therefore be
left at `mBound=true` with `mService=null`; reselecting the assistant appears to
fix the issue because it forces `switchImplementationIfNeededLocked(true)`.

`/Users/ink/developer/reverse/SystemUI.apk` has SHA-256 prefix
`beaebb7f1314` and maps to the older
`/Users/ink/developer/reverse/cache/systemui-beaebb7f1314`; it is not the
current OS 3.0.308.0 SystemUI baseline.

## Camera Unlock (相机解锁)

Settings → Tweaks → Camera Unlock (`ui/page/CameraUnlockPage.kt`, `Route.CameraUnlock`)
is the flagship impersonation for `com.android.camera` (MiuiCamera). Reverse-engineering
notes live in the camera cache:
`/Users/ink/developer/reverse/cache/camera-5cd70925b1646cdf/CAMERA_UNLOCK_EVALUATION.md`
and `CAMERA_FEATURE_GATES_17ULTRA.md`. On OS4.0.0.15.XPMCNXM the whole capability
surface funnels through one object `Je.c.b.f8427a.f8420e` (the per-device config,
`com.mi.device.<Device>` created by the single factory `Je/e.q()`, resolved through the
host's R8 name-rewrite wrapper `Uf.c.a`).

`CameraImpersonationHooker` hooks `Je/e.q()` and returns a flagship instance
(`com.mi.device.Nezha` built via the host's own `Uf.c.a` so `instanceof <flagship>`
branches work), unlocking every capability/mode gate on any device. The hooks re-read
`Preferences` live (100 ms memo), so toggles apply without a camera restart once the
master switch is on; the first enable needs a camera restart (hooks install on attach).

- `KEY_CAMERA_IMPERSONATE` (master, default off).
- Watermark keep-model is **unconditional** (there is no `KEY_CAMERA_WM_KEEP_MODEL` switch any
  more, and it is not even gated on the master): the on-picture watermark is always re-forced
  back to this device's own brand + model by after-hooking `Je/c#x()` (=`v()[0]` brand) and
  `Je/c#v()` (returns the platform's 3-slot `[brand, model, third]` array so `y()`/`w()` keep
  working). The model channel is `y()=v()[1]`, brand `x()=v()[0]`, third `w()=v()[2]`
  (`Je/c.java`). EXIF `Model` is `ro.product.marketname` (`Je.d.f8434h`), never the config, so
  EXIF is unaffected. `CameraWatermarkBrand` (shared with `CameraWatermarkHooker.hookDeviceLogo`)
  resolves the values: custom brand/model wins, else normalized `Build.BRAND` and
  `ro.product.marketname` (`Build.MODEL` fallback). `CameraWatermarkBrand` caches the resolved
  values and only recomputes when the custom master/values or `Build.BRAND` change, so the hot
  `x()/v()` hooks never pay the reflective property read per call.
- `KEY_CAMERA_WM_CUSTOM` (default off): custom-watermark master switch. When on,
  `KEY_CAMERA_WM_CUSTOM_BRAND` / `KEY_CAMERA_WM_CUSTOM_MODEL` (blank = device default)
  override the on-picture watermark brand/model in the `x()/v()` keep hooks and the
  device-logo repair; when off the stored values are ignored.
- `KEY_CAMERA_IMPERSONATE_THEME_LCC` (default off): forces `Je/c#V()` true so LCC-gated
  flagship branches (e.g. Legendary portrait) open without touching any real theme prop.
- `KEY_CAMERA_KEEP_FOCAL` (default on): while impersonating, delegate the config's focal
  getters (`B1/q0/e1/A1/C1/v1/x1/y0/h1`, the zoom line-up / mm labels; all confirmed
  declared on `defpackage/C1178` = Nezha) to the REAL device config instance so 焦段 stays
  the device's own while every capability boolean still comes from the flagship. The
  original instance is rebuilt by replaying `Je/e.q()`'s full fallback chain with the real
  device base name (`Je/a.f8410c` → `com.mi.device.<Cap>` → `com.mi.device.others.<Mfr>`
  → `new Ne.a()`, the low-spec weak default a non-flagship Redmi actually uses) — the
  cache `Je.e.b` is deliberately not read (it holds the impersonated flagship). Original
  getter `Method`s are cached once (not per call).

**Watermark config cache (`S8.d`)**: the camera caches the classic/Leica watermark brand+model
ONCE in the `S8.d` singleton (`S8.d.f15058a.f68841a`, a `p288i5.d` built from `Je/c#x()/#y()`
at first construction, jadx `S8/d.java:36`); renderers (`p890zi/b.d()` etc.) read that cache
(`f43446a`=brand, `b`=model). Two failure modes mattered on the device: (a) if the singleton is
constructed before `Preferences` is ready in the camera process the old master-gated keep hooks
no-op and Nezha's own strings get baked — the "17 Ultra" watermark shown right after capture
that only reverts after a later live re-read; (b) a custom-watermark change made later never
reaches the process-lifetime cache. Fixes: keep hooks are now unconditional, and
`hookWatermarkConfigCache` hooks `S8.d#a()` (the singleton accessor) to re-assert `f68841a`
with the current brand()/model() on every access (gated on the master), so the watermark fires
this device's values or the custom override at every render.

**Watermark render funnel (`J0`)**: `com.xiaomi.cam.watermark.a#J0(String deviceLogo, String model,
boolean)` is the final funnel every classic/cloud watermark render passes through (called by
`p890zi/b.d()` with the `S8.d` cached brand+model). The watermark model view `p203fs/m.o()` treats
a model of "17 ultra by leica" / "leitzphone powered by xiaomi" as an lcc_gl device and renders
the 17-Ultra-style watermark — the origin of the "17U watermark right after capture" leak, since
some capture-time reads still see the impersonated strings. `hookWatermarkRender` before-hooks
`J0` and forces both args to `CameraWatermarkBrand.brand()/model()` (only when the incoming call
is non-blank, i.e. an active watermark), making that lcc_gl branch unreachable for every render
including the immediate capture one. Note the brand is a LOGO IMAGE (`x()=v()[0]` →
`ic_device_watermark_logo_{redmi,xiaomi,poco}.xml`), so a custom brand only renders for names
that have a bundled logo; arbitrary text brands cannot appear as a logo. The model is plain text
and renders any custom value.

Regression history — do not reintroduce:
- The `v()` keep-model after-hook MUST return a real `String[]`. `arrayOf(brand, model, third)`
  with an `Any?` third slot infers `Array<Any?>` (`Object[]`), and the caller's `String[] v()`
  check-cast throws `ClassCastException`, which dead-locked the camera on open with keep-model
  on. Always use `arrayOf<String?>(...)` and keep the third slot `String?`-typed.
- Do not gate the watermark keep hooks on the master preference read: before `Preferences` is
  initialized in the camera process `getBoolean` returns its default (`false`), the hooks no-op,
  and the `S8.d` watermark-config singleton caches the impersonated flagship's strings for the
  process lifetime (the "17 Ultra" watermark flash). Keep is unconditional.

`CameraWatermarkHooker` (separate) unlocks the cloud watermark gallery via the
`camera.cloud.watermark.debug` property read (`Gg.C1686u$b.invoke`), with DexKit
fallback by the property string. Its `hookDeviceLogo` (`Je/c#x()`) fills an empty
classic-watermark logo with `CameraWatermarkBrand.brand()` when `KEY_WM_CAMERA` is on.

The LCC impersonation normally hides the camera's built-in 相机配色 (tint color) settings
entry: `CameraCommonPreferenceFragment.addCustomizationPreferences` gates it on
`p496o9.a.f53967a.d().i()`, and `p496o9/a` selects the provider holder from
`Je/c.V()` — the LCC branch `Ox.g(5).i()` returns false, the non-LCC `Hz.h.i()` true.
`hookLccCustomizationProvider` therefore forces `Ox.g#i()` true whenever the master
switch is on, keeping the tint-color entry visible. `f53967a.d().i()` has no other
consumers in the APK, so this is side-effect free.

`compileDebugKotlin`, `testDebugUnitTest`, `lintDebug` and `assembleDebug` pass. On-device
visual confirmation (watermark model/custom brand+model, 焦段 line-up, 相机配色 entry) is
the user's.

## Build and Test

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The repository currently has JVM unit tests but no device-level Compose or UI
tests. Release CI uses JDK 25, a signing keystore, `BUILD_CHANNEL=stable`, and
verifies the APK certificate with `apksigner`.
