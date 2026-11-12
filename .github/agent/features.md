# Feature reference

Per-feature mechanisms, host contracts and known limitations. Search by feature or class name and read only the matching section.

## App UI shell

Settings navigation uses Miuix `miuix-nav` in `ui/navigation/HyperTweakNavContainer.kt`.
The container owns the saved stack, pager-back behavior and transitions; four `*NavigationPages` builders register destinations from the current immutable `NavigationSettings` values/callbacks. The builders are recreated with composition so already-open entries observe updates; page-local remember/saveable ownership is unchanged.
`NavController` centralizes push/pop; `navigateTo` admits each singleton route only once,
returning to an existing entry on repeat navigation. `Route.saveKey` keeps the restored stack
stable across updates, and restoration discards duplicate/unknown routes; Miuix owns gesture cancellation,
settling and entry state. `ui/effect/ScalePredictiveBack.kt` supplies the optional scale transition.
The app's global blur preference is `Preferences.KEY_APP_BLUR_MODE` (0 off, 1 uniform, 2 progressive).
`ui/effect/AppScaffold.kt` captures each page's content for its top bar; About supplies its raw
animated backdrop to avoid sampling the already blurred project card. Its expanded top bar stays
transparent, enabling global top-bar blur only after the logo/title has fully collapsed. Top bars
respect explicit transparent colors in the unblurred state. `AppBlur.kt` applies the
same policy to existing card and navigation-bar blur. Progressive mode uses Miuix's native
`progressiveTextureBlur` / `progressiveBlur` pipeline, with top and bottom gradients as appropriate.
The shared `CenteredDialog` delegates to Miuix's centered `WindowDialog`. MainPager uses the
cross-axis Pager gesture driver and spring tab navigation; the liquid indicator follows Pager
selection without redispatching tab clicks. Liquid rendering and its six auxiliary files follow
Miuix v0.9.4 at `39c40f99844227b853f0049a0933b1f3ae6c00ba`: auxiliary algorithms and indicator
input/animation are copied unchanged apart from package names. The complete upstream MainPagerState
also owns tab selection, page navigation and root-page back, with the upstream syncPage effect.
Local renderer adaptations are theme wiring and the centered 80%-width pill (maximum constraint
340dp). Global blur Off stops backdrop capture and selects upstream's solid fallback; both enabled
modes use the fixed upstream uniform glass chain. About's background animation pauses when transparent,
covered or when its lifecycle is not resumed.
MainActivity explicitly provides Activity, ActivityResultRegistry and back-dispatch owners before
replacing LocalContext with the localized ConfigurationContext. Navigation entries and separate
dialog windows need these owners for document/image pickers and system back dispatch.
RestartScopeDialog follows the native WideWindowDialog example (560dp landscape maximum):
landscape actions sit to the right of the list; portrait limits the list to half the window height
and keeps actions below it. HotReloadDialog scrolls details within native WindowDialog bounds
while keeping footer actions outside the scroll area. Scope choices are saveable.

CreditsPage formats library versions from BuildConfig fields generated in app/build.gradle.kts
from the declared implementation/compileOnly dependencies. Keep translations version-free; add a
coordinate mapping when crediting another compiled library.

`IconTunerPage` keeps the carrier-left switch visible but disabled while both hide-date and
carrier-two-line are configured, with a summary explaining its single-line scope. The saved
carrier-left value is retained; disabling either prerequisite makes the switch editable again.
Rotation lock owns its enable switch, slot display scope and left-placement switch in the
Other icons tab. Its rows are omitted from the generic slot/left-placement cards, while the
full catalogs remain available to previews and icon ordering. The rotation-lock left switch
is shown only when global icon left placement is enabled; hiding it preserves its saved value.

Root 设置调试、临时授权、AI 命令与 v2/v3 诊断包维护见 `docs/DEBUG_DIAGNOSTICS.md`。

## Purification and optimization page

Features → Purification & optimization (`Route.Purification`, `ui/page/platform/PurificationPage.kt`)
contains the Copy Direct default-browser switch plus the MiTrust / GuardProvider / MiLink /
video-ringback switches with their original MainActivity callbacks. App-specific GetApps and
Xiaomi Community settings open independent `Route.Market` / `Route.Community` child pages
with their own scope guidance and application icons on the parent entries. GetApps uses nine default-off policies at
validated request/presentation boundaries; introduction video is independent of detail promotions.
See `docs/MARKET_IMPLEMENTATION_20261101.md` for contracts and cached-page limitations. Copy Direct resolves the
system default browser and its PackageManager application label for each new cue batch; the
native application highlight range, title, application icon and web Intent are rewritten
together in a copied ToolRequest at the real AI Panel dispatch boundary, before EventManager
serializes/emits it. A separate IntentBubbleService parser is not this production route.
No browser-name list is used; incomplete presentation data cannot block browser redirection.
The current native collapsed BubbleView ignores the model's highlight ranges and calls
TextView.setText(String). A separately discovered final binder applies a draw-only gradient
span to the verified browser-name range in its existing bubble_title TextView. It keeps
native metrics, bidi and ellipsis, clears only its own spans on disable/replacement and
recovers existing collapsed windows when upgrading from a build with no renderer state.
Span position lookup guards TextLine measurement re-entry; never call getPrimaryHorizontal
unguarded from CharacterStyle.updateDrawState. JSON range tests alone do not verify drawing.
Host JSON classes are derived from the host getInput return type, avoiding R8's adaptation
of constant Class.forName names to this module's serialization classes.
The native Open URL supported-app page independently includes installed APP_BROWSER entries
plus the current default, qualified for both HTTP and HTTPS. Its own models, checkbox listeners
and persisted per-package states remain in use; unrelated embedded web views are excluded.
Both native state loading and row rendering receive the additional packages; row icons come
from PackageManager. Browser apps without APP_BROWSER are included when selected as default.
Its dark-mode switch uses `DarkModeScheduleHooker` in system_server to publish automatic
sunset/custom-time changes while the display is on. A structural services.jar contract and
full-digest cache drive the shared preparation/lifecycle; runtime settings also release an
already pending switch under the native service lock. Disabling restores native scheduling.
Its wireless diagnostics section uses app-side bounded root commands
(`util/diagnostics/WirelessDiagnostics.kt`), verified vendor properties and fresh service/usage reads;
there is no injected-process destination or boot-time replay. The two Preferences values are
verified device mirrors, excluded from portable backups. Module reset/restore does not alter
these device-persistent properties; entering the page reads actual state, including after upgrades.

## Native photo/file pickers

AOSP / International Restore exposes independent native photo/file picker switches.
`NativePhotoPickerHooker` filters Hyper candidates only for implicit media picker queries;
explicit components and packages remain usable. `NativeFilePickerHooker` bypasses the private
vendor OPEN_DOCUMENT rewrite and redirects Hyper's Browse action through OPEN_DOCUMENT.
Each hooker owns its installation and live cached flag. Missing independent keys inherit the
legacy `KEY_NATIVE_PICKER`, while explicit false wins. Photo needs `system`; file additionally
needs `com.android.photopicker` scope. Changes affect subsequent requests, so active pickers
must be reopened. See `docs/PICKER_REROUTE_RESEARCH_20261024.md`.

## Upstream back-gesture module coexistence

Installed `dev.codex.miuibackgesturehook` deliberately disables overlapping back-gesture
and contextual-long-press controls and managed callbacks, regardless of the upstream
LSPosed toggle. Debug → Diagnostics → Disable upstream detection bypasses this managed
policy through live settings without deleting feature preferences. The
System Launcher page opens its exported Xposed settings entry. Native load callbacks
independently detect its mapped payload and yield shared gesture/contextual/Dart-state
hooks. Independent native rules retain their broadcast metadata and forward through
upstream's identity-options GOT chain. The separate upstream Overview observer shares
only authenticated visibility with gesture-bar hiding. See `docs/PR10_BACKGESTURE_IPC_20261021.md`
for the reproduced conflict, actual-image contracts and delivery evidence.

## Gesture bar hiding

The gesture bar (`HideBottomBarHooker`) has one decision owner, `GestureBarState`: a per-display phase machine (idle, leaving, hidden, returning). Three moments are strictly separated, because mixing them produced three device-reported regressions. The fade plays *inside the application* about 260 ms into the gesture (`FADE_OUT`, and the draw gate keeps painting via `fadingDisplays` so the animation has something to act on). The platform's `sampling=true` display-level restore then stops drawing immediately with no animation of our own (`HIDE_NOW`), because the platform fades its own copy in at that point and animating there flashes at the bottom of the screen. The reveal on `appTransitionFinished` writes the host alpha back only for a display that is genuinely not suppressed, and `selfDrivenAlpha` keeps the animation's intermediate values out of that record. Authority is explicit: the launcher's authenticated Overview broadcast is the only source of truth, because HYOS runs Recents in the Dart launcher and `ShellController.isOverviewVisible` keeps answering `false` while it is genuinely on screen. `overviewReported` records that a session heard the launcher and survives hot reload; while it is set, the platform query is never consulted - trusting it discarded live sessions and drew the bar over Recents (reported defect). The platform query is only a fallback for sessions the launcher never described, which is exactly the cancelled-swipe case: the fade already played, no Overview was ever reported, and the finished transition restores the bar instead of waiting out the stuck-report deadline. The leaving transition's own finish, which arrives with Recents still visible, is ignored. Every phase has a bounded deadline that fails safe. The launcher Overview bridge needs an application context, so it registers from `onSystemUiContextReady` - the path SystemUI actually takes; package ready alone left zero registrations and degraded the feature to timer guessing. `AospBackSystemUiHooker.onApplicationContextReady` is idempotent and also called from package ready and hot-reload restore.

## Back-gesture runtime

The vendored Android-17 back runtime is split under `hooks/systemui/` into input
policy/driver/monitor, open-transition tracking, navigation lifecycle, prepared
transitions, and animation rendering/commit. `NativeLauncherBridgeRuntime` owns
SystemUI's authenticated native launcher messages; the unused Java launcher
controller hierarchy and Android-16 back adapters have been removed. Material and
back-haptic mute are choices in the indicator/haptic dropdowns and join the shared
Boolean snapshot (both default off). Each dropdown commits its two flags together; mute preserves
the selected haptic scheme and does not mute contextual search. See
`docs/BACK_GESTURE_UPSTREAM.md` for the upstream pin and integration boundaries.

## Downloads, credentials and Google AIM

Download detail hooks select a semantically evidenced renderer, derive the download-model argument position and bind the original URL field from the host clipboard consumer. Same-prototype helper methods and other URL-like fields do not count as evidence. Credential row actions match the real Boolean-returning native launch call. Google AIM constructor overrides copy the immutable `Chain.args` and pass replacements to `proceed(array)` exactly once.

## Security Center privacy

`SecurityCenterPrivacyResolver` reuses the operand/call-graph index to recover privacy gates, native function-card builders and the beauty initializer/support path. No obfuscated owner/member names or ROM-version branches are stored. Missing optional capabilities remain independent; selecting an absent anti-peeping variant preserves the native gates/visibility. Beauty support overrides are scoped to the native initializer with nested/exception-safe thread ownership.

## Personal Assistant widget picker

The PA main-process `AndroidWidgetPickerHooker` resolves the existing native classic-widget footer lifecycle, navigator and source. Catalog gates resolve independently, including Kotlin accessors; chooser reflection is resolved lazily and cannot prevent entry registration. Assistant
source gets the native entry and a host Miuix provider chooser; desktop navigation
keeps its stock route. WidgetContainer owns AppWidget binding, host views, placement
and persistence; configuration uses the native bridge Activity. The resolver caches
reflection for interaction paths and rejects missing/ambiguous contracts. Set
`PERSONAL_ASSISTANT_APK` to the verified APK to exercise the bytecode contract test.

PA native addition selects the unique abstract WidgetContainer operation by
signature and modifiers; default startAddAnimation has the same parameters and
must not participate in uniqueness. The shared selector is tested against JVM
reflection fixtures and the real target DEX. A resolver/dialog exception shows a
load failure; the host empty-provider message is reserved for an actual empty list.

## Screenshot, Dock and IME

Screenshot auto-framing resolves its full-screen request/save/worker/preview graph and mutable
MediaStore fields with `ScreenshotFrameResolver`, using operand roles and call relationships.
No APK hash allowlist, version mapping, or obfuscated member table is used. Missing or ambiguous
contracts prevent save enrollment. `SCREENSHOT_APK` runs bytecode and renamed/duplicate/missing
contract tests against a real APK. `FRAMEWORK_JAR` checks IME root contracts.
The Dock background is the native Dock glass only: the phone Hotseat stays and only its
background becomes the Launcher's own HyperOS 4 glass, entering with the unlock animation.
The page has one switch (`launcher_native_pad_dock`), a live preview and the glass size
card (an adaptive width switch, then height with 0 = fill, horizontal margin, bottom offset,
radius) backed by `launcher_native_dock_glass`; slider drags drive the preview, releases write
the setting. The adaptive switch is a flag bit of the same packed value, so reset, backup and
the native snapshot carry it; "reset dimensions" keeps its state. With it on, the horizontal
margin slider is disabled and shows the default margin the Launcher uses
(`NativeDockGlassGeometry.effectiveMargin`), and the preview shows three centered icons and
the glass one cell narrower.
The old WMS Surface style and its Launcher frame observer are removed. An earlier generation
retires its own Surfaces, motion channel and renderer leases in `onPrepareHotReload`. Opening
the page calls `migrateLegacyDockBackground`, which removes `launcher_dock_background` in one
acknowledged patch and enables the native switch if the old style was on. The preview renders
the default Bionic glass token on a plain View with the same insets as the native factory
(`NativeDockGlassPreviewGeometry`). Native target and frame contracts are in
[native.md](native.md#native-dock-glass).
IME raising obtains
the current layout from `InputMethodService.mRootView`, without private resource integers.

Screenshot clipboard copying has its own `ScreenshotClipboardHooker`, independent of frame resolution.
Its structural resolver follows the native clipboard gate/publisher back to the save writer. Only
one gate read inside that publisher follows the module switch, including blocking copies when
off despite an enabled OS setting; no Secure setting is changed and
native notifications retain their policy. Targets use `DexRuntime.resolve` with SHA-256/schema
cache validation, cache-only startup replay and `prepareDexTargets` on misses. New/changed host
APKs retain native behavior until preparation finishes. Stable managed handles and the shared
SCREENSHOT settings group own hot replacement and live changes. `SCREENSHOT_APK` exercises real
bytecode, renamed, duplicate and disconnected save graphs; actual image paste remains manual.

`ScreenshotSaveDirectoryHooker` resolves the shared native Pictures/DCIM decision from both
filesystem and MediaStore insert paths. It redirects future screenshots to Pictures/Screenshots
without moving existing files; each AsyncTask save fixes its choice across path calculations.
It uses the same unified cache/preparation/managed-hook/settings lifecycle as clipboard copying.
Directory tests cover both storage paths, rename/ambiguity/missing-target rejection, per-save
consistency and legacy snapshot restoration. New files and MediaStore paths require device checks.

## LSPosed shortcuts

LSPosed dynamic and desktop-pinned shortcuts use the dedicated manifest-declared
ShortcutEntryActivity with empty `taskAffinity`; a background MainActivity task cannot
absorb the shortcut launch by affinity. Settings → App Shortcuts pins the bundled LSPosed
icon with stable ID `lsposed`, independent of the dynamic-menu toggles/reset. There is no
LSPosed launcher alias: removing the shortcut does not uninstall HyperTweak. Updating from
an earlier local alias build removes that component; add a real shortcut from the page.
On explicit add, bounded root IO checks MIUI/HyperOS properties, reads the current user’s
own-package shortcut AppOp, sets only operation 10017 to allow when needed, and verifies
it (including UID overrides) before requesting pinning on main after the page resumes. Failures show a root/manual-permission hint; submitting
a pin request is not reported as a completed pin. Root work disables repeat taps and has
an eight-second bound; page cancellation suppresses the subsequent pin request. Launcher
confirmation may still apply. Opening the manager requires
root granted to HyperTweak. ShortcutUtils has no MainActivity dependency; MainActivity
retains the legacy pinned-shortcut extra for upgrades, delegating the same root broadcast
to LsposedManagerLauncher. Permission behavior reference:
https://github.com/tiann/KernelSU/blob/ac1007e29dcc56fd3b5853517b3d62515e51bfa8/manager/app/src/main/java/me/weishu/kernelsu/ui/util/module/Shortcut.kt
