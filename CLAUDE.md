# HyperTweak Project Reference

Project rules: [AGENTS.md](AGENTS.md). This reference is a map, not a prerequisite reading list.

## Platform and build facts
HyperTweak is a Xiaomi HyperOS libxposed API 102 module with a Compose/Miuix settings UI and `arm64-v8a` native code. The support floor is OS3 / Android 16 (API 36); primary development targets OS4 / Android 17 (API 37). Android 15 is outside support.

Use `build.gradle.kts` and `app/build.gradle.kts` for current plugins, SDKs, Java level, dependencies, signing, and shrinking; `gradle/wrapper/gradle-wrapper.properties` for Gradle; `gradle.properties` for the project version. Release builds enable shrinking and may fall back to debug signing when the release keystore is absent. A release variant is not necessarily a stable-channel or production-signed artifact.

## Source map
Kotlin sources: `app/src/main/kotlin/com/takekazex/hypertweak/`.
- Directory ownership: UI pages are grouped under `ui/page/<feature>/`, shared dialogs under `ui/components/dialog/`; `Route.kt` remains in `ui/page/` for stable navigation identity. Utilities are grouped under `util/{logging,restart,scope,device,platform,diagnostics,update}/`. Hook support lives in `hook/preferences/`, `hook/runtime/{settings,reload}/`, `hook/nativebridge/`, and `hook/logging/`; `HookEntry`, `Preferences`, `NativeRules`, receiver and service entry names remain stable. SystemUI gesturebar/notification/controlcenter/lockscreen, icon carrier/left and SecurityCenter battery/sidebar/privacy each keep their feature family together.
- `hook/`: process entry and feature hooks; `hook/base/`: shared resolution and lifecycle infrastructure.
- `hook/bootstrap/`: explicit package/system-server installation plans and process-side settings participants. `HookEntry` retains DEX-session, loader, handle and generation ownership; participant coverage is validated before dispatcher registration.
- `settings/contract/`: storage-independent domain keys/defaults, shared lockscreen options, Dock configuration, icon slot/control policies, signal artwork style and screenshot snapshot parsing. Callers use domain `*Keys` directly; `Preferences` remains the runtime read/write boundary without duplicate constant aliases. Storage diagnostics use `SettingsDiagnostics`, bound by `DebugLog` at process bootstrap, with a non-recursive platform fallback.
- `ui/`: Compose screens; `util/`: shared utilities, including `PlatformLevel.kt` for OS3/OS4 gates.
- `HookEntry` initializes processes; `Preferences` is the runtime/cross-process settings boundary; `BaseHooker` owns hook lifecycle. `hook/base/dex` owns feature descriptors, APK identity caches, serialized resolution and cancellable background preparation; `DexKitManager` is the native bridge backend; consumers use `DexRuntime` and `BaseHooker.prepareDexTargets` instead of managing bridge, worker, cache or cancellation state. Runtime settings refresh existing island policies without reinstalling or scanning DEX on the main thread.
- `ui/app/` owns the app Compose shell, observable setting values and persisted restart baselines. MainActivity injects the `AppSettingsStore` contract backed by `app/PreferencesAppSettings`; AppContent, AppSettingsState and AppRestartTracker share that store for effective migration reads, acknowledged writes, reset and flush. `MainActivity` retains Activity lifecycle/platform entry points. `AppContent` accepts a ComponentActivity, the Activity-owned UpdateManager and a module-status callback; it does not import MainActivity. `AppStartupEffects` owns launch effects; `app/AppActivationCoordinator` owns service-rebind/reinstall decisions and acknowledged first-run cleanup. Fresh-install evidence remains intact until cleanup succeeds. `ui/state/ReloadableSettings` uses the same readers for initial values and service-rebind/reset refreshes. The app shell also provides `LocalAppSettingsStore` to all navigation pages and dialogs; `AppSettingsStore` composes nine feature capabilities over the 12-operation `SettingsValues` storage port (backup, fingerprint, power button, back gesture, camera, launcher, SystemUI, platform and power saving); `ui/page` captures this port before invoking callbacks rather than importing Preferences. Effective migration readers, backup/restore, batched back-gesture writes and flush remain delegated to PreferencesAppSettings.
- `app/DefaultAppServices` wires the existing app-process managers through `ModuleServicePort`, `NativeUpgradePort` and `NativeRulePublisher`; `LocalAppServices` supplies the same lifetime instances to pages and deferred callbacks. UI sees immutable `ModuleServiceInfo` binding identities and process-name lists, without libxposed service/HookedTarget objects. Report/outcome/native-upgrade states, camera mode and notification icon policy live in `settings/contract`. Scope, restart, shortcuts, wireless diagnostics and update utilities receive storage/service/callback dependencies; none imports hook implementations. `app/AppServicePorts` is the app composition bundle, kept outside the storage-independent contract layer.
- `RuntimeSettingsDefinitions` declares stable group IDs, ownership predicates and target sets; `RuntimeSettingsGroups` retains the existing constant/query API over that one table.
- Settings transactions live in `SettingsMutationWriter`, injected with the existing backend, writer queue and locks. `RuntimeSettingsSnapshot`/`Ledger` are pure models; `RuntimeSettingsStore` owns the process ledger and invalidates `RuntimeSettingsReadCache` directly. `RuntimeSettingsProtocol` encodes the wire format, `RuntimeSettingsSignal` owns acknowledged publication, and authenticated sync receivers are separate from `SettingsTransport`. The vendored read-only preference facade receives a narrow reader from `Preferences`.
- `presentation/icon/` and `presentation/glass/` own shared artwork, reduced signal models and rendering math used by both previews and hooks. They contain no hook registration. IME layout vocabulary, notification-header policy and volume-key policy live in `settings/contract/`; UI code must not import `hook.rules` for these shared contracts.
- `GestureBarEvents` feeds `GestureBarState`; `GestureBarHomeProbe`, `GestureBarDeadlines` and `GestureBarPresentation` own Home evidence, timers and reversible View effects. `HideBottomBarHooker` retains admission, recovery and retirement order. Home probes and deadlines use `GestureBarScheduler`, backed by the existing Handler and uptime clock, so pending work, deadline replacement and retirement are testable without Android handlers. `ControlCenterEditorAccess` owns plugin reflection and reversible listener/animation access. `NotificationHeaderCallbacks` resolves optional methods through `DexRuntime`; `NotificationHeaderClockSizing` owns native clock baselines and resource sizing, with hook handles retained by their parent hookers.
- `Preferences` remains the runtime access and acknowledged-write boundary. `SettingsBackupManager` orchestrates bulk replacement through its storage port; `AppLogStore` owns local log persistence/locking; `ExpiringReadCache` owns the short read memo. Bulk mutations and ordinary writes retain the single serialized writer and existing acknowledgement/rollback ordering.
- Slider children consume the narrow `SliderSession` capability for class resolution and live flags. `SliderPercentageHooker` remains the composition/lifecycle owner; Common, Brightness and Volume do not depend on that concrete owner or each other.
- Callback settings use `hookSetting` volatile slots captured through `Preferences`; the existing snapshot acceptance/backend listener refreshes them before live effects. Declare an explicit cold-read default with `hookSetting(default) { ... }`; storage failures retain the previous/default value. Registry membership locks never cover storage reads. Keep storage reads inside captures or explicit setup migration readers. `ViewVisibilityRouter` owns SystemUI framework visibility/alpha hooks, while feature-owned subscriptions retire through `BaseHooker`. See `docs/HOOK_IMPLEMENTATION_SECTION3_20261026.md`.
- `BaseHooker.generationState` owns transient field reset after host cleanup; `generationRequiredState` invalidates installation metadata, and `generationCleanup` provides idempotent resource/retirement fallbacks even when ordered restoration throws; `generationResource` clears caches under their own monitor; non-concurrent readers/writers must use that same monitor. `generationMainResource` synchronously retires UI-owned collections on main, acquiring their monitor only after dispatch so the caller never waits for main while holding it. `hostRecovery(HostRecoveryPhase, ...)` declares living-host recovery beside its feature. `StatusIconHotReloadRecovery` discovers SystemUI surfaces and dispatches dependency phases across the installed root/child tree; do not add a central per-feature recovery list. Ordered listener/thread/View restoration remains in `onPrepareHotReload`. See `docs/HOT_RELOAD_RECOVERY_20261026.md` for migration and evidence.
- `hook/base/HookComponent` lends feature components their parent's loader, context and managed hook operations; components do not create lifecycle or handle stores. `HostMembers` caches reflective members/misses by actual Class identity, while `HostAccess` owns main-thread dispatch and framework traversal. Host reflection defaults to `HostAccess` (or the `cachedDeclared*` / `cachedPublic*` fluent extensions): keep declared/public/inherited lookup and explicit accessibility distinct, and include the full parameter signature. Exact/fluent APIs require an explicit `accessible` policy; never mutate a borrowed cached handle’s access flag. Existing structural selectors use `HostAccess.cached(type, contract, resultType)` with a unique key and immutable member metadata only; never cache live host values there. Do not add per-instance member tags, class-name-keyed dictionaries or hooker-wide loaded flags for varying runtime types. See `docs/REFLECTION_CACHE_20261026.md`.
- `SystemUiSettingsHost` owns icon/AOD/header/notification live-setting effects. `HookEntry` retains process generations, DEX sessions, loaders and managed handles.
- The signal pipeline is separated into `MobileSignalSource` (host binding) with `MobileSignalSession` (pending replay/reducer/completeness) and `StackedSignalHooker` (artwork/slot publication). `HostFlowSubscriptions` owns partial-bind rollback, initial replay and per-binding retirement gates. Carrier and Duo reuse it without changing their replay policies.
- `bootstrap/SystemUiIconPorts` wires cross-feature icon capabilities before package attachment and releases them after successful generation retirement. `IconHostServices` supplies masks, row anchors, left-slot ownership, merged-list refresh and pipeline readiness without feature-to-feature back calls.
- Icon presentation components have explicit host ports: `LeftIconCloner` and `LeftHandoverController`; `CarrierRowRenderer`, `CarrierBlockViews` and `CarrierHandoverController`; `DuoIconView`, `DuoBinding`, `DuoAodController` and `DuoPanelHandover`. `CarrierPresentation` owns row construction/style/measurement and `LeftPresentation` owns clone synchronization and exact keyguard-anchor tree restoration over `LeftState`. `DuoNetworkSource` owns network callbacks/flow replay and `DuoSignalArtwork` owns vector/AOD recording caches. Their parent hookers still sequence host capture, restoration and generation retirement.
- Island hook assembly lives in `IslandHookPlan`, separate from `IslandHookContext`. `IslandRuntime` constructs the per-generation coordinators; `IslandSessionState` retains the single session registry, callback scopes and projections, and publishes typed `*Actions` capabilities. Binders and coordinators consume this state and ports without importing the composition root or another coordinator implementation. Native target discovery, install order, setting refresh and saved-owner migration stay in the existing ThreeIsland lifecycle.
- Camera profile access is shared through `CameraConfigAccess`; `CameraMasterLiveHooks` owns the donor/effect/focal/probe feature family. `MediaEditorWatermarkResolver` discovers/validates `WatermarkContract` targets before its parent installs them. `ImeRaiseController` owns keyboard padding/caption injection state and upgrade restoration.
- JVM tests: `app/src/test/`. Scope metadata: `app/src/main/resources/META-INF/xposed/scope.list`; also inspect `app/src/main/res/values/arrays.xml` when changing scope presentation or checking release parity.

Features → Purification & optimization (`Route.Purification`, `ui/page/platform/PurificationPage.kt`)
contains the Copy Direct default-browser switch plus the MiTrust / GuardProvider / MiLink /
video-ringback switches with their original MainActivity callbacks. Copy Direct resolves the
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

AOSP / International Restore exposes independent native photo/file picker switches.
`NativePhotoPickerHooker` filters Hyper candidates only for implicit media picker queries;
explicit components and packages remain usable. `NativeFilePickerHooker` bypasses the private
vendor OPEN_DOCUMENT rewrite and redirects Hyper's Browse action through OPEN_DOCUMENT.
Each hooker owns its installation and live cached flag. Missing independent keys inherit the
legacy `KEY_NATIVE_PICKER`, while explicit false wins. Photo needs `system`; file additionally
needs `com.android.photopicker` scope. Changes affect subsequent requests, so active pickers
must be reopened. See `docs/PICKER_REROUTE_RESEARCH_20261024.md`.

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

## Logging

`util/logging/DebugLog.kt` owns severity, bounded dispatch and counted repeat suppression. `bootstrap/ProcessLogging` injects a live level reader through `LogBackend`; only the module Application receives the existing AppLogStore sink. Cold/hot hook entry binds a level reader without a persistence capability. Retirement flushes before dropping the binding; the logger never imports Preferences. `LogRecord`/`LogCodec` is the shared wire format; errors retain stack traces and expected preference/process skips use DEBUG. Detailed hook registration, target resolution and high-frequency rendering diagnostics belong at DEBUG; INFO describes meaningful runtime state changes. Do not classify severity from words inside a message, and do not silently swallow failures at shared resolver, persistence or system-callback boundaries.

App records are stored by the existing AppLogStore; LogRepository receives its read-only snapshot capability from ProcessLogging and never imports Preferences; injected processes forward to the LSPosed logger, without attempting to write hook-side remote preferences. App log buffers/session/process selections and obsolete per-process log thresholds are local-only at every typed getter/setter, including synchronous strings. Only module Application initialization grants access to this store; injected host caches are never log destinations. On application service binding, the serialized writer removes historical app-log keys from the daemon without clearing shared recording/log-level controls, setting revisions or local app records. A rejected cleanup retains its deletion keys independently of the mutable remote shadow, reports failure, and retries on the next binding. Reset/restore and rollback cannot reintroduce those keys remotely. `LogRepository` merges app records with `LsposedLogReader` records selected by exact module identity. The log page refreshes on entry/service rebinding or an explicit tap, displays source/process/PID, and exports the same snapshot. Reading LSPosed files requires root permission granted to HyperTweak; denial, missing files, timeout and read failure remain visible while app logs are still available. Reads are limited to the four newest active module log files, 2 MiB per file and 2,000 module entries. It does not modify framework files or collect other modules into the snapshot. A single global log level applies to the app and every injected process. Session rotation/clearing only clears app records. The diagnostic provider uses the same repository and retains its own/root/shell caller restriction.

## Host readiness and semantic repair

Installed `dev.codex.miuibackgesturehook` owns back gestures: `UpstreamBackGesture`
gates managed callbacks and settings UI without deleting saved preferences; the
System Launcher page opens its exported Xposed settings entry. Native load callbacks
independently detect its mapped payload and yield shared gesture/contextual/Dart-state
hooks. Independent native rules retain their broadcast metadata and forward through
upstream's identity-options GOT chain. The separate upstream Overview observer shares
only authenticated visibility with gesture-bar hiding. See `docs/PR10_BACKGESTURE_IPC_20261021.md`
for the reproduced conflict, actual-image contracts and delivery evidence.

Host preference caches are optional storage capabilities. `Preferences.initLocalCache` publishes a usable context/cache pair only after checking the context's data directory and opening its preferences; a system/provider context without application storage continues to use the remote configuration and does not abort package-ready dispatch.

The gesture bar (`HideBottomBarHooker`) has one decision owner, `GestureBarState`: a per-display phase machine (idle, leaving, hidden, returning). Three moments are strictly separated, because mixing them produced three device-reported regressions. The fade plays *inside the application* about 260 ms into the gesture (`FADE_OUT`, and the draw gate keeps painting via `fadingDisplays` so the animation has something to act on). The platform's `sampling=true` display-level restore then stops drawing immediately with no animation of our own (`HIDE_NOW`), because the platform fades its own copy in at that point and animating there flashes at the bottom of the screen. The reveal on `appTransitionFinished` writes the host alpha back only for a display that is genuinely not suppressed, and `selfDrivenAlpha` keeps the animation's intermediate values out of that record. Authority is explicit: the launcher's authenticated Overview broadcast is the only source of truth, because HYOS runs Recents in the Dart launcher and `ShellController.isOverviewVisible` keeps answering `false` while it is genuinely on screen. `overviewReported` records that a session heard the launcher and survives hot reload; while it is set, the platform query is never consulted - trusting it discarded live sessions and drew the bar over Recents (reported defect). The platform query is only a fallback for sessions the launcher never described, which is exactly the cancelled-swipe case: the fade already played, no Overview was ever reported, and the finished transition restores the bar instead of waiting out the stuck-report deadline. The leaving transition's own finish, which arrives with Recents still visible, is ignored. Every phase has a bounded deadline that fails safe. The launcher Overview bridge needs an application context, so it registers from `onSystemUiContextReady` - the path SystemUI actually takes; package ready alone left zero registrations and degraded the feature to timer guessing. `AospBackSystemUiHooker.onApplicationContextReady` is idempotent and also called from package ready and hot-reload restore.

`SystemServerReadinessHooker` observes the framework boot-phase boundary. Assistant/gesture settings recovery probes live content/role services, binds observers before alignment, uses bounded polling windows and rearms pending work on lifecycle events. `SettingBackupTransaction` requires acknowledged backup/live writes and retains originals after failed restoration. `NativeRules` binds JNI only for the exact launcher owner; status inspection never loads a library, and a failed load is not repeatedly retried in an unrelated process.

Native settings have two event paths: framework remote preference callbacks and authenticated app-to-SystemUI notifications after acknowledged commits. Native setters derive a complete desired native subset from the last acknowledgement. Ordinary remote commits send only differences against both that acknowledgement and the current editor shadow, so rejected shadow values cannot contaminate a later commit; reset/restore and clear-based rollbacks explicitly repopulate the complete desired subset. Successful native commits reconcile the app-local mirror only when no newer edit is queued. Failed reset or backup restoration replaces the entire map captured on the serialized writer so newly introduced keys cannot survive rollback. Requests use the app acknowledgement ledger, not a possibly uncommitted mutable editor map. `native_rules_revision` is runtime bookkeeping, excluded from portable backups; reset epochs supersede older commits. `NativeSnapshotLedger` rejects stale/conflicting snapshots and carries only bootstrap primitive values across classloader replacement. SystemUI attaches its receiver even while its remote channel is recovering, requests an app snapshot on readiness/boot/unlock and finishes publication after restoring package state. All transport replays/retries are finite; retired publishers close their receivers and queued work. Normal setting changes never invoke native activation or root.

The hot-reload dialog includes a separate native-update action requiring root and an up-to-date SystemUI Java target. `NativeUpgradeManager` compares the actual payload hashes in the signed mapped/installed APKs. HYOS may label its root spawner `usap64`; ownership is established from the launcher's parent, exact executable, uid and `/proc` start time, not its displayed name or a ROM table. A changed native image is activated by guarded spawner replacement following upstream `abe7d4d6f880422d7faef973bd9c90457d662495`. Verification checks the new process family, current APK inode mapping and the native `runtime_identity` record (version/pid/start time/checksum). This is a launcher-process refresh, not same-process `.so` unloading. The previous complete signed APK is retained for explicit PackageManager rollback with data preserved. Root IO, output sizes and activation waits are bounded; an interrupted activation is reported after reopening. Temporary mapped APK copies are removed on inspection failure, and successful process checks are not restored as current facts from the journal. Rollback always resolves the current spawner afresh and records failures. Code loading/initialization does not prove native feature UI acceptance.

Download detail hooks select a semantically evidenced renderer, derive the download-model argument position and bind the original URL field from the host clipboard consumer. Same-prototype helper methods and other URL-like fields do not count as evidence. Credential row actions match the real Boolean-returning native launch call. Google AIM constructor overrides copy the immutable `Chain.args` and pass replacements to `proceed(array)` exactly once.

`SecurityCenterPrivacyResolver` reuses the operand/call-graph index to recover privacy gates, native function-card builders and the beauty initializer/support path. No obfuscated owner/member names or ROM-version branches are stored. Missing optional capabilities remain independent; selecting an absent anti-peeping variant preserves the native gates/visibility. Beauty support overrides are scoped to the native initializer with nested/exception-safe thread ownership.

The vendored Android-17 back runtime is split under `hooks/systemui/` into input
policy/driver/monitor, open-transition tracking, navigation lifecycle, prepared
transitions, and animation rendering/commit. `NativeLauncherBridgeRuntime` owns
SystemUI's authenticated native launcher messages; the unused Java launcher
controller hierarchy and Android-16 back adapters have been removed. Material and
back-haptic mute are choices in the indicator/haptic dropdowns and join the shared
Boolean snapshot (both default off). Each dropdown commits its two flags together; mute preserves
the selected haptic scheme and does not mute contextual search. See
`docs/BACK_GESTURE_UPSTREAM.md` for the upstream pin and integration boundaries.

## Shared runtime settings reload

`RuntimeSettingsGroups` registers setting ownership, destinations and resync authorization.
Every explicit live-settings group uses the common snapshot/dispatcher: icons, islands,
screenshot, back gesture, native launcher settings, Dock, volume-key steps, XMSF focus
authentication, gesture-bar surfaces, security/offline checks, Security Center privacy and
warnings, cross-package clipboard and bubble notifications, and the SystemUI domains added
by the 2026-10-22 live-settings audit (`systemui.aod`, `.keyguard`, `.notification_header`,
`.notification_text`, `.media_card`, `.control_center_plugin`, `.glass`, `.lockscreen`,
`.power_save`). Participants re-derive policies on living hosts; optional hooks use the
managed on-demand installation lifecycle. The keyguard battery row uses `AodKeyguardPolicy`: an
explicit host phase from `MiuiFullAodManager.isDozing` (`mToLockScreen` alone is not a dozing flag) keeps the native row in every
lit/unknown state, and only the accepted ordinary Duo switch replaces it; AOD options apply
only in AOD phases, and every suppression sets one release-able ownership flag.
Snapshots carry their committed changed keys, so a domain that owns several hooks
routes each key to the hooker that applies it. A switchable feature never substitutes
zero for "no override" (corner radius) and keeps the host request separate from the
module suppression (lockscreen status bar).
`Preferences` commits complete acknowledged subsets through
`RuntimeSettingsSignal`; `RuntimeSettingsReceiver` delivers per-destination scoped snapshots
to `SettingsReloadDispatcher`. Legacy saved scopes cannot invent defaults for newly migrated
features. Bulk reset/restore and Dock commits use the same acknowledgement and rollback path.

`ManagedHookReload` / `BaseHooker.replaceChildren` own hook recreation and host state recovery;
islands use fresh instances. The vendored back runtime reads the common preferences facade.
Native launcher delivery and Dock rendering remain output/host adapters, with their old
independent settings inputs removed. API-102 replacement cancels dispatch, carries the
scoped primitive snapshot, closes old observers and resumes registered participants after
restoration. Dedicated native binary updates retain their own acceptance boundary; Java hook
replacement is governed by the lifecycle and recovery contracts above. See `docs/RUNTIME_SETTINGS_RELOAD.md` for architecture
and `docs/HOT_RELOAD_TEST_CHECKLIST.md` for the complete test inventory.

## Native payload
`app/src/main/cpp/NativeRules/` builds `libhypertweak_native.so` from `app/CMakeLists.txt`; LSPosed injects it via `app/src/main/resources/META-INF/xposed/native_init.list` and calls the upstream `miui_home_native_hook.cpp` entry. The launcher lifecycle, AOT state hooks, remap repair, fork owner reset, and LSPlt backend derive from the Apache-2.0 upstream implementation. Launcher entry selection always uses structural resolution; version tables and fixed-address fallback candidates are removed. `clear_button_rule.cpp`, `folder_columns_rule.cpp`, and `assistant_widget_rule.cpp` are the feature-specific rules; they resolve their targets through the structural registry (see below) and use upstream's validated Dart image and action-down maintenance paths. Their load callbacks consume the supplied handle and never call `dlopen`. The launcher-side rule switches normally arrive from SystemUI, which reads complete remote `Preferences` snapshots and publishes an explicit `com.android.systemui.fsgesture` intent to `com.miui.home` with shared sender identity. `NativeRuleStatePublisher` performs finite replays at startup/settings changes/boot/unlock and closes its receiver and queued tasks on hot reload. The independent receiver-dispatch PLT hook captures and authenticates the actual SystemUI package and uid, schema and complete primitive payload before forwarding the borrowed Intent. After the original receiver returns it accepts the monotonic revision and applies cached rules without reading the consumed Intent. It preserves upstream's inline receiver and private-send hook ownership. Launcher JNI remains a fallback: the native HYOS launcher does not reliably execute the Java settings route. The stacked-folder preview rule for the structurally verified big-folder family transforms validated cached preview geometry at the native getter boundary and adjusts primitive count/index arguments in the original Dart child generator. It decodes family CIDs through the shared constructor, infers rows/columns from canonical native geometry, preserves every large icon and small-preview interaction classification, and renders at most three stacked cards with the earliest small application in front, and leaves the source application list and original Dart stores/write barriers intact. All six hook sites (geometry/count/order/frame/scoped-ID/completion) must be ready before callbacks apply the feature; staged failures roll back and contended callbacks retain native arguments. Value changes, first installation and fork recovery enqueue one primitive refresh generation. At the next native begin-frame boundary, before build/layout, a verified Dart tail transfer invokes the existing all-folder preview invalidation body after popping the original frame. Within this module-owned DartThread scope, the original ID-array fill reads the parent layout GetBuilder ID decoded from its registration site; normal monochrome calls retain their preview ID. The parent rebuild is required because the preview-container builder can return its cached Widget unchanged. The original refresh return records completion and clears scope. This restores canonical geometry on disable/reset without retaining heap roots or calling Dart from C++. Model/DI initialization guards retain pending work; equal snapshots remain idle. No new frame is forced while the launcher is fully idle or paused; returning Home supplies the next frame. The switches are `folder_stack_preview`, `android_widget_stack`, `hide_recents_clear`, `recents_card_haptic`, `opened_folder_columns`, `contextual_search_long_press`, and `allow_android_widgets_to_assistant`. Unavailable or malformed remote Preferences never publish defaults. `native_rule_runtime.cpp` saves accepted snapshots in launcher-owned device-encrypted `hypertweak-native` storage and restores them without Java initialization. One eventfd worker prepares targets; input/receiver/load boundaries consume prepared results. A thread-local internal-library-query guard and generation gate prevent LSPosed's `RTLD_NOLOAD` callback from requeueing the worker; completed generations remain idle and failures retry only on bounded external events. `dart_resolution_cache.cpp` persists successful relative offsets keyed by Dart build ID, module version, schema and site contracts, with executable-range and exact 32-byte evidence checks before reuse. Failed results remain process-local; configuration/fork/identity changes permit fresh preparation. The opened grid reads a late-static column cache rather than recomputing the getter on each open. A separately resolved `onFolderOpen` Dart shim synchronizes that cache on the owning Dart thread before layout, using the field displacement decoded from the unique width-calculation reader. It captures and restores the native SMI on disable, leaves sentinels/objects untouched, and retains a passive open boundary after removing grid/preview overrides. It preserves the native frame, arguments and NZCV without entering a C++ or Dart trampoline. Device testing on 2026-10-03 confirmed live clear-button changes, idle preparation while dozing, and user-visible 4/5/native column changes on reopening, with folder close/Home return and preview intact. `liblsplt.so` comes from the vendored AAR in `app/libs/`. The payload is fail-closed and runs its native launcher hooks only in the upstream HYOS launcher process family.

## Task references

Native files under `app/src/main/cpp/NativeRules/` are grouped into `resolver/`, `runtime/`, `transport/`, `rules/`, `bridge/`, `support/` and `tools/`; the entry TU and export map stay at the root. Native standalone runners use `app/src/test/cpp/native_test_paths.py` for explicit cross-domain include directories. Source contents and exported ABI are preserved by directory migration.

Native AOT identity: `_kDartSnapshotBuildId` points to the 32-byte GNU note (16-byte header followed by the 16-byte ID), not the ID alone. The upstream Dart resolver validates this note and the mapped executable ranges before feature code reads the target.

Dart feature targets are resolved **structurally**, not from a per-build table: `dart_image.cpp` (ELF validation, AArch64 decoding, instruction-kind classification, masked patterns) plus `dart_targets.cpp` (one spec per site, tiered evidence) re-derive each address from instruction shape and call-graph relationships, so a launcher update does not need a new table row. `dart_rule_support.cpp` is the Android-only bridge that turns a spec into runtime addresses for a rule; the two rules carry no build id and no RVA. Any site that is not uniquely resolvable is rejected rather than guessed.

`launcher_process_identity.*` owns native process/library admission; `dart_mapped_image.*` owns mapped-ELF bounds and branch checks plus the existing image-resolution ABI. `dart_state_publisher.*` owns publication receipts, per-channel in-flight gates and retry decisions; `broadcast_transport.*` owns public Rust broadcast allocation/consumption, runtime retain/release, share-identity option lifetime and diagnostics. `dart_state_worker.*` owns eventfd start/wake/coalescing/retry/readiness and child-only fd reset; `dart_observer_retirement.*` owns paired observer gating and stable draining before remap repair. Both borrow pointers to the original entry cells. The entry TU retains host symbol/profile binding, process-owner admission/epoch reset ordering, structural remap detection/patch repair, raw callbacks and assembly cells. Carrier/Duo target leases use referential identity through `SignalTargetOwnership`; `SignalHandoverEvents` routes Shade events without the Carrier constructing the Duo renderer. View indexes, animation state and masks retain their own lifecycles.

Authoring a new hook:
- Declare a `TargetSpec` in `dart_targets.cpp` and a rule that consumes it. Nothing in `miui_home_native_hook.cpp` changes.
- A byte pattern is only usable when it is both **stable across builds and unique**. Verify both: many Dart prologues/epilogues are stock shared code, and a masked pattern is strictly more general than the bytes it masks. A site whose stable prefix is short, or whose match count is large, needs structural evidence instead.
- Use the host probe to develop and regression-test offline; it shares `dart_image.cpp`/`dart_targets.cpp` with the payload, so its verdict is the payload's verdict:
  `clang++ -std=c++17 -O2 -Wall -Wextra -Werror -I resolver -I rules -o /tmp/dart_probe tools/dart_probe.cpp resolver/dart_image.cpp resolver/dart_targets.cpp`
  (run from `app/src/main/cpp/NativeRules`; `rules/` supplies the `*_target.inc` files that `dart_targets.cpp` includes)
  then `dart_probe <libapp.so>` (resolve + report), `--expect site=0xADDR` (assert a known RVA), `--mutate 0xADDR` (fail-closed negative control), `--classes 0xADDR` (dump the instruction-class sequence used for structural specs).
- `dart_runtime_resolver.cpp` stays byte-identical to upstream. `runtime_profile_resolver.cpp` retains the upstream structural algorithms with the fixed-address side-handler fallback removed; the launcher entry path never consults a per-build profile table.
- New launcher artifacts and AOT research: [launcher research manual](docs/LAUNCHER_UNPACK_RESEARCH_MANUAL.md). Native target discovery, settings, lifecycle and ABI work: [native hook implementation manual](docs/NATIVE_HOOK_IMPLEMENTATION_MANUAL.md). Never add version/build-ID/RVA target tables.
- Validation, reverse engineering, release/device delivery, and Git: [.github/AGENT_WORKFLOWS.md](.github/AGENT_WORKFLOWS.md), relevant section only.
- CI behavior: `.github/workflows/ci.yml` and `.github/workflows/release.yml`; published release-note format: [.github/release-notes/README.md](.github/release-notes/README.md).
- Local feature mechanisms, baseline hashes, and regressions: `docs/FEATURE_DETAIL.md`, searched by feature or symbol. `docs/` is git-ignored and may be absent in another checkout; its dated findings are not automatically the current device baseline.

The Android-widget-stack rule (`widget_stack`) resolves the launcher's paired eligibility gates from one Dart image contract: the drag-side and target-side MIUI-flag reads must agree with a third site, the AppWidget serializer, on both the model field displacement and the class ID. Each finder re-derives the whole family, so a relocated or recompiled gate is accepted while a partial or ambiguous one is rejected. Two passive shims replace the conditional branches that carry the flag decision: while the setting is on and preparation succeeded they resume at the native success block, otherwise they replay the stolen words and continue to the native rejection block. The shims never write the model's MIUI bit and never touch span equality, the 1x1 rule, the ten-card capacity, gadget/serviceDelivery exclusion, or the HyperOS4/PA gates, and they preserve every general-purpose, SIMD, SP and NZCV value. Disabling keeps the shims installed and merely clears the request/ready pair, so no executing trampoline is ever freed; already-created stacks remain ordinary native containers. Test the structural contract with `widget_stack_target_test.cpp` and the production assembly offline with `run_widget_stack_abi_test.py` (Unicorn + pyelftools, Android NDK clang); `run_dart_rule_cache_test.py` covers its preparation deferral, paired install, remap repair and foreign-patch rejection. Static and offline verification is not manual drag acceptance, and provider binding, RemoteViews rendering, card paging and dissolve/restart recovery remain manual device checks.

The OS4 Android-widget rule resolves a unique eligibility/serializer family from the current Dart image, deriving the provider flag displacement and AppWidget CID. Its assembly replays the stolen instructions and bypasses the MIUI identity and size eligibility rejection only for ordinary AppWidgets of that CID. It derives and verifies the native success continuation; model dimensions/identity, Bundle metadata, placement, and drop acknowledgement remain stock. MIUI providers and other card types keep their size policy. It uses the same preparation/cache/settings/remap lifecycle and restores its owned hook when disabled. Test the structural contract with `assistant_widget_target_test.cpp` and the actual assembly offline with `run_assistant_widget_abi_test.py` (Unicorn + pyelftools, Android NDK clang). Current 7722 static/build verification is not manual drag acceptance.

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

Screenshot auto-framing resolves its full-screen request/save/worker/preview graph and mutable
MediaStore fields with `ScreenshotFrameResolver`, using operand roles and call relationships.
No APK hash allowlist, version mapping, or obfuscated member table is used. Missing or ambiguous
contracts prevent save enrollment. `SCREENSHOT_APK` runs bytecode and renamed/duplicate/missing
contract tests against a real APK. `FRAMEWORK_JAR` checks Dock native capture and IME root contracts.
The Dock host uses only its own ViewRoot/RenderNode sampling state when the platform rejects
its cross-window blur admission; it never edits the global admission policy. IME raising obtains
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

Dynamic LSPosed shortcuts use the dedicated manifest-declared ShortcutEntryActivity. ShortcutUtils has no MainActivity dependency; MainActivity retains the legacy pinned-shortcut extra for upgrades, delegating the same root broadcast to LsposedManagerLauncher.
