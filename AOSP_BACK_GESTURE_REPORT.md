# AOSP Back Gesture — Port and Investigation Report

Port of `wxxsfxyzm/MiuiBackGestureHook` v0.4.3 → v0.8.1, and the on-device
investigation that followed.

- Upstream baseline: `efa595d` (v0.8.1, 2026-07-26)
- Upstream clone: `/Users/ink/developer/refrences/MiuiBackGestureHook`
- Device: `25102RKBEC`, launcher `com.miui.home RELEASE-7.50.06.2372-06261924`
- Launcher artifact: `reverse/MiuiHome-RELEASE-7.50.06.2372-06261924.apk`,
  decompiled to `reverse/cache/launcher-73ee007d501ecdb8`

## 1. What the port did

Upstream replaced a single 3,701-line class with a nine-class chain. The chain is
vendored verbatim so future updates stay mergeable; all local changes carry a
`HyperTweak:` comment.

| File | Lines | Local changes |
|---|---:|---:|
| `hooks/miuihome/MiuiHomeReturnHomeRuntime.java` | 12,647 | 5 |
| `hooks/systemui/SystemUiHookRuntime.java` | 3,968 | 2 |
| `hooks/systemui/SystemUiInputRuntime.java` | 3,778 | 5 |
| `hooks/miuihome/MiuiHomeHookRuntime.java` | 3,415 | 1 |
| `hooks/core/HookRuntimeCore.java` | 1,448 | 4 |
| `CrossTaskWallpaperRuntime.java` | 713 | HyperTweak-only |
| `hooks/systemserver/SystemServerHookRuntime.java` | 735 | 1 |
| `hooks/systemui/MiuiStyleBackArrowOverlay.java` | 555 | 0 |
| `hooks/hotreload/HotReloadHookRuntime.java` | 386 | 4 |
| `hooks/systemui/MiuiHapticFeedbackHelper.java` | 158 | 0 |
| `AospBackGestureRuntime.java` | 70 | leaf |
| `core/BackGestureHookRuntime.java` | 11 | 0 |

Adaptation is concentrated in two files:

- **`HookRuntimeCore`** — dropped `extends XposedModule`. Hook installation goes
  through a `HookRegistrar` bridged to `BaseHooker`; `registerHook()` replaced
  upstream's `recordHookHandle(hook(m).setId(id).intercept(f))` at 62 call sites.
  `log()` became a static, and `deoptimize()` routes through the registrar.
- **`HotReloadHookRuntime`** — LSPosed lifecycle callbacks became
  `saveHotReloadState()` / `restoreHotReloadState()` plus explicit
  `install*Hooks(classLoader, registrar)`. Deferral throws instead of returning
  `false`. `createHotReloadHooker()` was dropped: `BaseHooker` already replaces
  handles by hook id when `onHook()` re-runs, so the table was unreachable.

Verification: regenerating each vendored file from upstream with the same package
rewrite and `registerHook` transform, then diffing, leaves only the marked
changes and line-wrapping. `MiuiHomeReturnHomeRuntime` is otherwise identical.

## 2. Fixed

### Deprecation warnings in the release build

Fixed at source, not suppressed. Zero warnings under `-Xlint:deprecation`.

- `Intent.ACTION_WALLPAPER_CHANGED` → `WallpaperManager.addOnColorsChangedListener`.
  The deprecated broadcast is no longer delivered to manifest receivers, so this
  is also more correct.
- `ComponentCallbacks.onLowMemory()` → `ComponentCallbacks2.onTrimMemory`,
  reacting to `TRIM_MEMORY_BACKGROUND` / `TRIM_MEMORY_UI_HIDDEN`, the only two
  levels not themselves deprecated. `onLowMemory` is abstract on the parent
  interface so it must still be implemented; the override carries `@Deprecated`
  to state that rather than hide it.
- Three imports left unused by the above were removed.

One deliberate exception: `ActivityManager.getRunningTasks` in
`SystemUiInputRuntime.findTopActivity`. That deprecation targets third-party
callers, who receive only their own task. This runs inside SystemUI, which holds
`REAL_GET_TASKS` and receives the full list, and no public API replaces it.
`@SuppressWarnings("deprecation")` is scoped to that one method — not the
class-wide blanket the previous monolith carried — so a genuinely stale call
elsewhere still surfaces.

### Silent hook failures

`log()` gated every level behind `KEY_AOSP_BACK_LOGS`, so a half-installed hook
set was indistinguishable from a healthy one. WARN and above are now
unconditional; INFO and below stay behind the switch. Every finding below came
from logs this made visible.

### Leaked return-home session

One leak produced three separate symptoms:

| Symptom | Mechanism |
|---|---|
| Launcher stuck shrunk and blurred, still interactive | session frozen mid-drag |
| Predictive back dead afterwards | later runners hit `retainedPreviewOwner` |
| LSPosed: 1 process failed hot reload | `blocksControllerReplacement()` saw `cleaned == 0` forever |

Upstream claims unified-native preview ownership before validating the rest of
the `WindowElement`, and `finishSession()` refuses to finish a session while that
ownership is unverified — so finishing waits on cleanup and cleanup waits on the
finish. `STALE_RETURN_HOME_PREVIEW_TIMEOUT_MS` (3s) bounds it: a stale preview
owner is cancelled, marked verified to break the deadlock, then finished, and the
same bound applies in `blocksControllerReplacement`. Only the preview case is
bounded; a running native animation keeps ownership as long as it needs.

### Launcher-scope dependency (partially)

Upstream v0.8.0 replaced direct pointer pilfering with launcher-side arbitration.
`onNativeDown` marks a gesture a candidate and returns `false` until
`acceptMiuiHomeInput(token)` runs, and nothing else sets `miuiHomeInputAccepted`.
So upstream needs `com.miui.home` in scope *and* a launcher with the Java gesture
stack for the gesture to start **in any app**.

A token-free path restores the pre-0.8.0 behaviour by synthesising the token from
the current DOWN. It engages only when `LauncherVersion.mayArbitrate` is false —
the launcher is positively known to have no gesture code. It deliberately does
not trigger on "SystemUI has not heard from the launcher yet": the launcher
announces itself lazily on its first gesture-stub interaction, and claiming a
gesture it is also arbitrating diverges both sides' ownership identities. That
mistake was made once and observed on device as return-home working for a few
gestures and then stranding.

## 3. Root cause of the stranded return-home

Traced from a single clean gesture on a freshly restarted launcher.

```
17:41:51.442  Held Xiaomi CLOSE_TO_DRAG for real commit transition
17:41:51.457  Composed accepted predictive return-home commit in original start transaction
              ── 1.8s, nothing ──
17:41:53.243  W Retained committed Xiaomi drag owner without same-epoch end, attempt=1
```

The commit path works. The element simply never leaves `CLOSE_TO_DRAG`.

`animTo` is called 106 times in that one gesture, all `CLOSE_TO_DRAG`, so the
`animTo$lambda$3` hook resolves and fires correctly. Version mismatch was ruled
out too: every member upstream resolves exists with the expected signature on
7.50.06.

On this launcher line, `AnimType.CLOSE_TO_HOME` for a gesture-driven return home
is issued from exactly one place:

```java
// NavStubView.java:4597, from startHalfAppToHomeAnim()/startAppToHomeAnim()
StateManager.Companion.getInstance().sendEvent(new AppToHomeEvent(
    new GestureAppUpEventInfo(new RectFParams(..., AnimType.CLOSE_TO_HOME, ...))))
```

`NavStubView` — the launcher's own gesture view — on **finger-up**. The module
pilfers the pointer stream for SystemUI, so NavStubView never receives that UP
and never sends the event. The only other producer of `CLOSE_TO_HOME` params,
`WindowAnimParamsProvider.getRemoteAbortParams`, is reachable only from
`RemoteShellAbortEvent` in `FastLaunchWindowElement` — an abort path, not a
commit.

The module was therefore waiting for an event its own pointer pilfering
guarantees will never arrive.

### Why driving performAppToHome alone was not enough

The first driven attempt reached `startAppToHomeAnim` and resolved the icon target, yet the
element still never left `CLOSE_TO_DRAG`. The capture explains it:

```
18:08:01.238  D/AnimStateManager: send event 6004 currentState = IdleState windowElement = WindowElement@9443c5d
18:08:01.238  I/AnimStateManager: IdleState handle event 6004
18:08:01.238  I/AnimStateManager: CommonState handle event 6004      ← dropped: no case 6004 here
```

`StateManager` routes `AppToHomeEvent` (type 6004) in exactly one place — `AppState`
(`StateManager.java:1239`). A natural gesture enters AppState via the 6001 gesture-start event
from `NavStubView`; a module-owned gesture drives the element directly and never sends 6001, so
the machine stays in `IdleState`, which delegates to `CommonState`, which silently drops 6004.
The reference recording of a natural launcher gesture two seconds earlier shows the working
sequence: `6001` (Idle→App, createWindowElement) → `6003`... (drag) → `6004` in AppState →
`animTo(CLOSE_TO_HOME)` → `gotoState(homeState)`.

The additional fix: `ensureMiuiHomeStateManagerAppState` promotes the StateManager from Idle to
AppState immediately before the driven `performAppToHome`. `gotoState` is a bare `currentState`
assignment with no enter/exit hooks, and case 6004 itself ends in `gotoState(homeState)`, so the
machine self-heals after handling. Promotion only happens from IdleState — any other state means
Xiaomi's own pipeline is active and is left alone. The retargeted `animTo` reuses the running
spring (`runningAnimUpdate`), keeping the animation identity the module already tracks, and the
finish then arrives as `CLOSE_TO_HOME`, which the module's unconfigured-cancelled-commit rule is
built to accept.

### Fix under test

`driveMiuiHomeAppToHome()` calls the launcher's own public
`NavStubView.performAppToHome()`, reached through the break controller's
`getNavStubView()`. Driving the launcher's entry point rather than rebuilding the
params is deliberate: `startHalfAppToHomeAnim` derives geometry, corner radii and
listener wiring through `CoordinateTransforms`, `findClosingAnimTarget` and
`PathDataIconUtil`, and reconstructing that by hand would be subtly wrong.

`scheduleMiuiHomeAppToHomeDrive()` posts it `MIUI_HOME_APP_TO_HOME_DRIVE_DELAY_MS`
(120ms) after the commit, and only if the element is still parked in the drag
type with no configured animTo — so where Xiaomi does retarget on its own,
upstream's ordering is untouched.

**Status: on device, not yet confirmed working.** Look for
`Drove MiuiHome performAppToHome for committed return-home` in logcat.

### Follow-up fixes after the state promotion

- **Wrong destination icon** — every app animated back to the HyperTweak icon.
  `findClosingAnimTarget` resolves the icon from NavStubView's `mRunningTask*`
  fields, refreshed only by its own `backGestureDown()`, which never runs under
  module ownership. `refreshMiuiHomeRunningTaskIdentity` seeds them from the
  session's closing `RemoteAnimationTarget.taskInfo` before the drive.
- **Dead gesture right after app launch** — a release resolving
  `actualTrigger=false, outcome=post-commit` never gets a Shell finish callback,
  so `activeShellSession` stayed set and every later DOWN was rejected
  ("Suppressed SystemUI back while Shell is busy", sessionId constant across
  rejections). `scheduleShellSessionReleaseWatchdog` completes such a session
  after 1.5s once Shell is verified quiescent on its owner thread.
- **Setting off broke the whole gesture** — the experimental toggle used to gate
  all launcher hooks including input arbitration, which the entire gesture
  depends on. `installMiuiHomeInputArbitrationOnly` now always installs the
  arbitration half; the toggle gates only predictive return-home.

## 4. Known-good and known-broken

Working: predictive back progress, enhanced haptics, slide-back animation,
per-app opt-in, cross-task wallpaper background.

Unverified: the fix above. The token-free fallback path is compile-verified only —
Launcher 7.50 always takes the arbitrated path, so exercising it means
deliberately unchecking the launcher scope.

## 5. Notes for future debugging

- The launcher logs under **instance tags** (`WindowElement<hash>`), and
  `MiuiHomeLog` prefixes `Launcher.` with `debug()` gated behind the
  `is_miui_home_debug_log_enable` pref. `logcat -s` misses all of it — capture
  everything and filter offline.
- Synthetic `adb shell input swipe` does not reach the gesture InputMonitor; the
  gesture must be performed by hand.
- Only the first failure after a launcher restart is diagnostically clean. Once a
  session leaks, every later gesture is the cascade, not the cause.
