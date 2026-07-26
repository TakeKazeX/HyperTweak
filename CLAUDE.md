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
