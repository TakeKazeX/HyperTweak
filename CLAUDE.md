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
```

These cover the system server, SystemUI, Launcher, the module's own process,
Settings, AOD, Security Center, PowerKeeper, Scanner, MiLink, and Bluetooth.

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

## AOSP Back Gesture

The AOSP back gesture is vendored from `wxxsfxyzm/MiuiBackGestureHook`
(Apache-2.0) at upstream commit `efa595d` (v0.8.1, 2026-07-26). Upstream's
reference clone lives at `/Users/ink/developer/refrences/MiuiBackGestureHook`.

Upstream's class chain is copied verbatim under
`hook/rules/backgesture/` so future updates stay mergeable. HyperTweak-local
changes are marked with a `HyperTweak:` comment and are confined to:

- `hooks/core/HookRuntimeCore.java` — the root drops `extends XposedModule`.
  Hook installation goes through a `HookRegistrar` bridged to `BaseHooker`
  (`registerHook()` replaces upstream's
  `recordHookHandle(hook(m).setId(id).intercept(f))`, 62 call sites), `log()` is
  redefined as a static gated on `KEY_AOSP_BACK_LOGS`, and `deoptimize()` is
  routed through the registrar.
- `hooks/hotreload/HotReloadHookRuntime.java` — upstream's LSPosed lifecycle
  callbacks become `saveHotReloadState()`/`restoreHotReloadState()` plus explicit
  `install*Hooks(classLoader, registrar)` entry points. A deferral throws instead
  of returning `false`. `createHotReloadHooker()` is dropped because `BaseHooker`
  already replaces handles by hook id when `onHook()` re-runs.
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
`AssistManager` without HyperOS's Launcher-owned invocation type, while direct
Gemini and ChatGPT actions resolve only exported assistant activities. Circle to
Search is CSService-only and does not depend on the selected digital assistant.
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

Other available mappings are:

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
