# Other system apps: downloads, Google, PA, recorder, screenshot, HTML Viewer

Part of the [feature reference](../features.md). Search by feature or class name and read only the matching section.

## Downloads, credentials and Google AIM

Download detail hooks select a semantically evidenced renderer, derive the download-model argument position and bind the original URL field from the host clipboard consumer. Same-prototype helper methods and other URL-like fields do not count as evidence. Credential row actions match the real Boolean-returning native launch call.

Google Ask Screen resolves the `vidcip` thumbnail owner, native OMNI eligibility and the search-box
model from DEX. It follows the eligibility-dependent bitmap binding to prove the attachment flag.
When the model's hint renderer branches between an empty hint and the supplied string resource,
the resolver records that suppression flag separately. All overridden fields require direct,
unique constructor assignments. Google App 17.77.19 adds the suppression flag among the
booleans before `Executor`, so that parameter grouping cannot identify the fields to enable.
Cache schema 5 stores the individual roles. In OMNI, the live override enables the contextual
hint and attachment fields and clears the proven suppression flag. Disable and generation
retirement restore the original values. Google renders them on its next native bind.
With a missing or empty hot-reload package snapshot, HookEntry recovers the living
`:googleapp` application after verifying its package, UID and source path. It keeps the
saved hooker state and runs the same deferred resolver used on cold start.
Committed settings received during DEX preparation are applied to living models at publication,
using the latest snapshot. The settings dispatcher does not retry while the resolver is pending.

On OS4, the Xiaomi Launcher plays the light-impact haptic when it recognizes a
navigation-handle long press for Circle to Search. SystemUI admits the forwarded request
and starts contextual search without playing another click. There is no OS4 setting for
the retired extra click. The saved preference still decodes for legacy feedback paths.

`DownloadFocusNotificationHooker` (provider process) gives each task its own notification
and the host's native `file_download` focus payload; SystemUI renders it with its own
templates, text layout and animation. Five structural boundaries only:
- The static tag builder splits active/waiting tasks to `1:<source>:<id>` (user-paused 193
  keeps that identity so pause/resume never recreates the island) and successes to
  `3:<source>:success:<id>`, keeping native completion channels, open/hide intents and
  auto-cancel. Failures and other tags stay native.
- `updateWithLocked` captures the live notifier/collection for settings refresh.
- `NotificationHelper.notify` attaches `miui.focus.param` / `pics` / `actions` plus the
  `hypertweak.download.focus.task_id` marker, and clears the icon URL (the host's async icon
  queue is keyed by package + URL and would hold back same-source peers). A tag without a
  snapshot is published natively.
- The native notification receiver handles the injected pause/resume broadcasts.
- `DownloadNotifier.cancelAll` (called when a stopped service restarts, e.g. resuming while
  all tasks are paused) keeps marked task notifications and seeds the new notifier's
  `mActiveNotifs` with their tags and posted `when`; the native stale-tag sweep then cancels
  tasks that are gone. Otherwise every island is torn down and re-posted.
Text comes from the provider's own string resources, speed from the notifier's
`mDownloadSpeed`, icons from its `file_icon_*` drawables (island slots use a 15%-inset copy).
Completion uses the native recorder's done style: the big island's right `picInfo` and the
small island show its green check (redrawn from the recorder's 22dp finish vector), with no
percentage, bar or action. Settings refresh rediscovers
`DownloadService` via `ActivityThread.mServices`; a stopped service falls back to the
retained notifier and replays only tasks represented by posted notifications (marker or
native `extra_click_download_ids`), rejecting the whole replay if any identity is missing.

`DownloadFocusAuthPluginHooker` (SystemUI plugin) runs the native auth-success callback only
for a marked, identity-validated provider notification. It never changes the package-level
XMS allowlist or the focus whitelist; native per-app `canShowFocus` still applies. There are
no SystemUI title, mount or row hooks: long file names follow native island truncation.
Validation: `DownloadFocus*Test` and package-plan/runtime-settings tests; the user checks
multiple downloads, pause/resume, completion, enable/disable/reset and hot reload on device.

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

## Screen recorder

The OS4 screen recorder page has independent, default-off destination and frame-rate switches.
Its contracts resolve separately from the currently installed recorder APK. The settings fragment,
controller, MediaStore insert and folder links run in `com.miui.screenrecorder`. The package also
declares a separate core service. The hooks act where the main process prepares the destination
and frame configuration.

- The path hook redirects the public recording directory and the matching pending MediaStore
  insert during preparation. It follows the directory choice for the whole preparation call.
  Album, existence query and document URI consumers use `Movies/ScreenRecorder` when enabled.
  Existing recordings stay in their original directories. A failed directory creation keeps the
  native destination for that save. Existing notifications can retain previously built folder intents.
- The frame hook extends only the host frame dropdown's label and value arrays using the
  selected resolution, physical refresh modes and advertised surface-encoder capabilities for
  both orientations. It keeps the host frame list and bitrate table intact. Unsupported saved
  rates above the host list use the host default at recording start. The common runtime settings
  dispatcher refreshes the living settings fragment, found via the host activity and its native
  fragment tag, including after hot reload and upgrades from builds without this feature. A native
  resolution change also refreshes the dropdown. Advertised support does not measure recorded
  frame cadence under variable refresh, load or thermal limits.

Set `SCREEN_RECORDER_APK` to run `ScreenRecorderHostContractTest` against the verified APK.

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
[native.md](../native.md#native-dock-glass).
IME raising obtains
the current layout from `InputMethodService.mRootView`, without private resource integers.
`AospImeHooker` installs only in the processes that the selected keyboard's
`android.view.InputMethod` services declare (`ImeServiceProcess`, e.g. WeType `:hld`). The
plan and late keyboard selection both check this. An unknown answer (no PackageManager, failed
query, no declared service) installs everywhere, as before.

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

## HTML Viewer freeze and OTA notification replay

- **Purification & optimization → HTML Viewer** exposes a default-off Millet switch. The system-server hook resolves the final system-app decision from the `systemapp` reason, package operand and system-app control membership. It treats only HTML Viewer as eligible at that decision and leaves the surrounding Millet checks in place. The policy runs on the shared app UID. The dispatcher updates the hook slot without editing Millet lists or touching processes.
- **Replay the OTA Super Island** uses an authenticated Settings provider relay to reach the signature-protected HTML Viewer provider. HTML Viewer reads its native cached OTA response and invokes the stock notification publisher as the originating app. The original focus metadata, assets, channel, and tap destination come from that publisher. The command checks the cached system version and notification settings, and requires the Settings and HTML Viewer scopes. Navigation uses the shared app-specific Purification page shell.

## Camera profile access

- Camera config hooks install synchronously at `CameraApplicationInit` (after provider publication, before `Application.onCreate` builds the modes), and the device-mismatch gate inside the provider's own create. Both declare `DexRuntime.synchronousScan`. A warm start must never build the camera operand index (35k classes, 0.2-0.8 s on the install thread): every `CameraSemantics` lookup that reads bytecode goes through `cachedUnique`, `cachedMethods`, `cachedField` or `cachedValues`, and a cache hit is validated by reflection alone. `anchored(..., replay = ...)` separates a cheap signature check from a `shape` that reads code. A new direct `semantics.dex.*` query outside such a compute lambda brings the index back; `DexOperandIndex: loaded ... thread=main caller=...` names it.

- Camera profile access is shared through `CameraConfigAccess`; `CameraMasterLiveHooks` owns the donor/effect/focal/probe feature family. `MediaEditorWatermarkResolver` discovers/validates `WatermarkContract` targets before its parent installs them. `ImeRaiseController` owns keyboard padding/caption injection state and upgrade restoration.
