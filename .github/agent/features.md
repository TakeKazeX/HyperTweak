# Feature reference

Per-feature mechanisms, host contracts and known limitations. Search by feature or class name and read only the matching section.

## SMS verification-code copying

`SmsCodeHooker` runs in the Xiaomi Messaging main process only (`com.android.mms`),
on OS4. `sms_auto_copy_code` defaults off and belongs to the SMS runtime-settings
group. The app list opens a Miuix SMS page with local missing-scope guidance;
`scope.list` and `xposed_scope` recommend the same package. Debugging never edits
the live LSPosed scope. Package context publication and `AppHostRecovery` support
cold attachment and upgrade from a generation with no SMS state.

`SmsCodeResolver` follows the native copy-action Intent's text operand back to its
OTP getter, then proves the recognition wrapper's message-ID getter and the
SMS_DELIVER Handler caller. Names/version tables do not select targets. Contracts
resolve on the managed DEX worker, validate against the host loader on replay,
and fail closed when missing/ambiguous. Receive and recognition callers are
deoptimized before managed hooks publish. A thread-local delivery scope excludes
UI reclassification, notification updates, cloud SMS, RCS and Message Flow.
The module consumes the native result without invoking recognition again, so
it inherits the host's recognition/admission limitations. A cache miss preserves
native behavior until preparation completes.

`SmsCopyQueue` deduplicates up to 128 ordinary SMS message IDs per host/user
process and orders recognition results by admission. Only nonempty OTPs qualify;
express/pickup-code results do not. On keyguard, only the newest qualifying code
waits, for at most two elapsed-time minutes. USER_PRESENT reevaluates the pending
candidate; disable/reset cancels it and in-flight tickets. A newer readable
clipboard-description timestamp suppresses delayed publication; background
metadata can be unavailable on vendor builds, so this guard is best effort.
No clipboard text is read. Pending state is lost if the host process dies.

Hot replacement carries bounded ID/order state and an unexpired pending code as
JDK values in process-local saved state, never preferences/files. Registration
uses the published Application; retirement unregisters the unlock receiver and
cancels main-thread tasks. Generations with no saved state start without scanning
history. Publication writes text ClipData with both the native
`mms_is_ververification_code` marker and `EXTRA_IS_SENSITIVE`. It does not send the
native copy PendingIntent: that path also marks messages read. No SMS body,
sender or OTP is logged, and no notification/clipboard overlay is added.

Validation: `SmsCopyQueueTest`, `SmsSettingsRoutingTest`, and the optional
`SmsCodeResolverApkTest` with `SMS_APK` pointing outside the checkout. The latter
verifies the full artifact SHA-256 before resolving. Device acceptance must cover
foreground/background SMS, lock/unlock, reset, hot replacement, duplicate/order
handling, native recognition misses and preservation of unread state.

## Package manager core patches

`PackageManagerHooker` runs only in `system_server`. The shared UID signature option
(`PackageManagerSettingsKeys.KEY_SHARED_UID`) defaults off and follows the core master
switch through the shared runtime-settings dispatcher. With only this option enabled, it
changes only error -8 from `PackageManagerServiceUtils.verifySignatures` during install
preparation/reconciliation for ordinary application shared groups; factory packages remain excluded.
The nested `KEY_SYSTEM_SHARED_UID` option also defaults off and requires the parent and master.
It admits -7/-8 for system/privileged shared groups (including factory-package updates) and
scopes the subsequent installed-data capability override to the exact incoming/installed
signer pair for that prepare call. Permission-signature comparisons keep their native result;
only same-package permission redeclaration may be allowed when incoming/installed shared-user
names match and the installed package belongs to a system/privileged shared identity.
Non-shared system updates, cross-package permission ownership, permission grants and signer
identity retain native behavior. Reconciliation is deoptimized to retain the verify call boundary.
`SharedUidInstallPolicy` centralizes the failure/UID/group classification for JVM regression tests.
The hook owns no persistent host state; live disable/reset and hot reload restore the native
decision for subsequent requests. Already installed shared-UID membership is persistent PMS
state and is not undone by disabling the option. Both locale descriptions label it extremely
dangerous and explain shared permissions/private-data access and this persistence limitation.
`PackageManagerPage` expands the system option only under the enabled parent. Enabling it
requires a Miuix WindowDialog confirmation before writing true; cancel/back/dismiss leaves it
off. The warning body scrolls separately from the buttons. Master disable keeps selections,
while disabling/resetting the shared-UID parent clears the system child (including debug edits).
The page also clears a stale true child under a false parent from an older build. Re-enabling
the parent therefore requires a new child confirmation. UI/installation acceptance,
including any re-signed SystemUI plugin, remains separate from build and hook-load evidence.

## App UI shell

Settings navigation uses Miuix `miuix-nav` in `ui/navigation/HyperTweakNavContainer.kt`.
The container owns the saved stack, pager-back behavior and transitions; four `*NavigationPages` builders register destinations from `NavigationSettings`, which now carries only shell state and actions (module/hot-reload status, restart, backup, links). Setting values are not passed through it: pages read and assign `LocalAppSettings.current` (`AppSettingsState`). The builders are recreated with composition so already-open entries observe updates; page-local remember/saveable ownership is unchanged.
`NavController` centralizes push/pop; `navigateTo` admits each singleton route only once,
returning to an existing entry on repeat navigation. `Route.saveKey` keeps the restored stack
stable across updates, and restoration discards duplicate/unknown routes; Miuix owns gesture cancellation,
settling and entry state. `ui/effect/ScalePredictiveBack.kt` supplies the optional scale transition.
The app's global blur preference is `Preferences.KEY_APP_BLUR_MODE` (0 off, 1 uniform, 2 progressive).
Interface style is `AppSettingsKeys.KEY_APP_UI_STYLE` (0 HyperOS 3, 1 HyperOS 4, default 1).

Page chrome lives only in `ui/chrome/` and mirrors the upstream example (`AdaptiveTopAppBar`,
`BlurredBar`, `rememberBlurBackdrop`, `pageScrollModifiers`, `pageContentPadding`):
- Every route is an `AppScrollPage` (column), `AppLazyPage` (lazy list) or, for custom layouts,
  `AppPage { padding -> }`. The frame owns the scroll behavior, Miuix `Scaffold`, popup host,
  `LocalAppOverlayHost`, the page backdrop (`rememberPageBackdrop`, null when blur is off or
  RuntimeShader is missing) and `AppTopBar` with the built-in back button. Pages never build a
  Scaffold or top bar themselves and never pad content *below* the bar: content is laid out under
  it from the Scaffold padding, so the bar can collapse and blur over it. Tab rows and pinned
  previews go in `topBarBottom` (Icon tuner, Camera). Root tabs pass `onBack = null` and the
  pager's bottom bar height as `bottomInset`.
- `AppTopBar`: OS4 renders upstream `GlassTopAppBar` (band clear until content scrolls under it,
  then a progressive blur of the page backdrop, or a surface gradient when blur is off); OS3 renders
  the classic `TopAppBar` over a uniform/progressive blur layer. `contentScrolled`/`titleAlpha`
  serve About's collapsing logo. Slot buttons use `AppIconButton` (imported as `IconButton`), which
  becomes a `GlassIconButton` pill only inside the OS4 bar.
- `Modifier.appPageScroll` mirrors elastic overscroll into `TopAppBarState.overscrollOffset` and
  attaches the nested-scroll connection. OS3 defers the backdrop capture until the entry transition
  settles; OS4 captures from the first frame so glass never enters as its fallback.
- `AppOverlayDialog` → `GlassDialog` and the string-items `AppDropdownPreference` (imported as
  `OverlayDropdownPreference`) → `GlassDropdownPreference` in OS4, sampling `LocalPageBackdrop`.
  `AppWindowDialog` (and `CenteredDialog`) also renders an in-page `GlassDialog` in OS4 when it is
  inside an `AppPage`; outside a page and in OS3 it keeps its own `WindowDialog`. Inside a glass
  dialog `AppTextButton` (imported as `TextButton`) becomes `GlassDialogButton` (Primary for
  `textButtonColorsPrimary()`, Destructive for `destructive = true`). The entry-form dropdown stays classic.
- About records its own animated backdrop for the cards; its bar uses the shared frame.
- Icons are Miuix only (`MiuixIcons` / `MiuixIcons.Glass`); there is no Material icons dependency.
- Settings groups use `AppSection(title) { rows }` (SmallTitle + 12dp-inset card + full-width
  column); page tab rows use `AppTabRow` (OS4 `GlassTabRow` over the page backdrop, OS3
  `TabRowWithContour`). Dark-dependent colors use `isAppInDarkTheme()` (active Miuix scheme), not
  `isSystemInDarkTheme()`, so the in-app theme mode is honoured.
- Settings state is write-through. `AppSettingsState` properties are `persisted(KEY, put) { read }`:
  assigning one stores it and notifies `AppRestartTracker` through `beforeWrite`. Keys without a
  property use `rememberStoredSetting(KEY, settingsStore::putX) { read }` in the page. Do not pair
  an assignment with a manual `put`. `reload()` refreshes values without writing.
- Status tones (success/warning/error/info × container/onContainer/accent) come from
  `ui/theme/StatusPalette.kt`; text sizes use `MiuixTheme.textStyles`, not `fontSize` literals.
- UI setting writes do not call `settingsStore.flush()`; it blocks on the daemon queue and is only
  for restart paths, which already flush before killing scopes. Flows are collected with
  `collectAsStateWithLifecycle`.

The main bottom bar style is independent of the interface style (`BottomBarStyle` in
`ui/chrome/AppChrome.kt`), stored in the existing `KEY_USE_FLOATING_BOTTOM_BAR` +
`KEY_FLOATING_BAR_STYLE` pair: floating + style 1 is the iOS-like liquid bar in both styles;
otherwise OS4 shows `GlassNavigationBar` and OS3 the classic or floating Miuix bar with stock
items. Landscape OS4 overlays a `GlassNavigationRail` whose progress pads the page and drives
`LocalTopBarTitleAlpha`. `AppBlur.kt` applies the blur policy to card and navigation-bar blur;
progressive mode uses Miuix's native `progressiveTextureBlur` pipeline.
The shared `CenteredDialog` delegates to Miuix's centered `WindowDialog`. MainPager uses the
cross-axis Pager gesture driver and spring tab navigation; the liquid indicator follows Pager
selection without redispatching tab clicks. Liquid rendering and its six auxiliary files follow
Miuix v0.9.5-beta01 at `6d2554a850b8960e2616c968263ff64beb5ffb03`: auxiliary algorithms and indicator
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
Airplane mode has the same kind of card in that tab: slot display scope, mirror, left placement
(shown only with left placement on) and one dropdown per header for the carrier "airplane mode" label
(`icon_airplane_label_{control_center,notification,keyguard}`, `AirplaneLabelMode` 0–4: system / hide /
glyph only / glyph before / glyph after). The notification dropdown is disabled, keeping its value, while
`notification_header_hide_carrier` hides that carrier area. Every header applies its own mode. On the
lockscreen the glyph and the icons' airplane (left clone, which leads the left container, else Duo's ring,
else the native slot) are never shown together: with left icons the glyph follows the carrier/icons swap;
without them Duo receives the glyph after the screen-on intro even when left placement is off. Without
Duo, a glyph beside the text stays in the label; a replacing glyph moves to the native right slot after
the intro unless left placement is off entirely (`LEFT_MODE_DISABLED`) or the airplane itself is placed
left; either keeps every glyph in the left-side label and masks the right slot.
A lockscreen Duo ring that starts or stops drawing the airplane posts a left reconcile, and an endpoint
lost mid-flight re-resolves the location on the next main-loop turn. Flight uses a critically damped spring; mid-flight reversal
keeps position, velocity and overlay. The glyph moves along the straight line between its endpoints without a cutout detour. The returning glyph waits for a drawn overlay before its
previous owner yields.

The Other tab's carrier-label card starts with `icon_cc_carrier_name_hiding` (the storage
name is retained), a native-carrier-row dropdown: show / hide SIM 1 / hide SIM 2 / hide both
(0–3), keyed by physical SIM slot. While hide-date + two-line carrier are both enabled, the
row is disabled with an explanation and retains its saved value. UI and
`ControlCenterIconConfig.nativeCarrierHiding` use `CarrierRowVisibility.nativeControlsEnabled`;
the effective hook mode is NONE during compact layout. Compact render inputs and
`hiddenCarrierSlots` do not consume this dropdown; only the separate shared SIM policy may
suppress their rows. Disabling compact layout restores the saved native hiding choice.
The former `icon_cc_hide_carrier_name` boolean migrates true to 3; explicit current values,
including 0, win. `HideCarrierLabelHooker` suppresses native row, text, HD and plus children.
Before native HD updates it releases its child masks and then recaptures the latest native
visibility, preventing stale HD restoration. Scoped header reload handles layout transitions,
reset and upgrade. Status-bar SIM visibility, notification/lockscreen labels and airplane
labels retain their own controls; older SIM carrier booleans remain migration only.

SIM visibility settings live together in the Mobile network tab's SIM visibility card:
physical SIM 1/2 and the non-default data SIM each have Default / Hide status bar icons only /
Hide everywhere dropdowns; the two lockscreen carrier switches stay independent in that card.
Status-bar-only preserves expanded control-center signals, types and carrier labels. The animated status-bar mirror keeps those SIMs hidden and carrier handover never borrows them as signal/type sources. All hides
both the cellular row and the corresponding carrier row, including compact two-line rows.
Overlapping rules use the strongest mode and non-default follows the active data subscription.
Old SIM switches decode as status-bar-only, old control-center carrier switches as all; explicit
new defaults override all old keys. The non-default rule does nothing without a known second SIM.

Status-bar icon ownership and theme rules:
- **Independent publication.** Stacked bars use `hypertweak_stacked_mobile_icon`; the former shared `stacked_mobile_icon` name is migration input only. Slot modes, custom ordering, hidden lists and control-center row overrides migrate through `IconConfigMigration`, with explicit current entries taking precedence. Never normalize a foreign runtime holder into the current slot.
- **Module priority.** `IconHostHooks` registers host observers at priority 10,000 (EzHookTool default: 50). Its stable IDs include the priority, because API-102 `replaceHook` keeps the original handle's priority. A priority change must create new handles and retire old ones. This does not guarantee dominance over arbitrary hooks at still higher priority.
- **Reserved slots.** `HostIconBridge.publish` replaces an ordinary foreign holder in `IconSlotPolicy.moduleSlots` instead of yielding. Other slots remain ownership-checked. Publication readback, rollback on failure, and exact icon identity for generation cleanup remain mandatory. Unknown holder shapes are rejected.
- **No Helper coexistence.** There is no Hyper Helper detection, foreign type-slot masking, or conflict-based preference for its output. Users must disable overlapping Helper features. Do not add foreign `stacked_mobile_type` or `single_mobile_sim1/2` aliases to `IconSlotParts.CELLULAR_SLOTS`.
- **Retired slots.** `StackedRenderer` cleans retired signal/type holders through `HostIconBridge.removeRetiredModuleHolder`, independently of settings and asset readiness. It requires this module's package and an ordinary type/tag and reads the host list back. A holder of another package stays untouched; `removeOwned` retains strict generation identity.
- **Native type label.** Resolve unique `mobile_group`, TextView `mobile_type_single` and ViewGroup `mobile_signal_container` roles under the verified mobile root. `TypeOnlyBranchPolicy` preserves the complete label ancestry and masks sibling branches, supporting wrappers and a label relocated within the root. Layout adoption updates the topology; mask release restores the latest native visibility request. Only missing/ambiguous roles reject the host contract, never another module's presence or a wrapper by itself.
- **Left clones and themes.** Clones are outside `mIconGroups`, so `LeftHostSubscriptions` forwards `StatusBarIconControllerImpl.onMiuiThemeChanged(boolean)` to each clone's `onMiuiThemeChanged`, then re-syncs and re-samples the handover.
- **Two-line carrier budget.** The control-center status layout reports each icon line's free width (`IconHostPorts.carrier.rowSlack`, real LTR row only). Each carrier row extends by its own line's slack through a negative row end margin. Badges share one column only while every row can afford it; a row that cannot fit its own badge drops it.

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

## Slider percentages
- Code: `hook/rules/systemui/slider/`. `SliderPercentageHooker` belongs to the control-center plugin session and owns the policy (`SliderPercentagePolicy`, which includes `SliderPercentageTypography`). It also owns `PercentLabels`, which holds one host baseline per view, and `PercentLabelStyle`, which owns colour, MiBlur blend and typography. `ControlCenterPercentage` (CC tiles, expanded brightness, brightness transition) and `VolumePanelPercentage` (inner column labels, outer capsule) are `HookComponent`s of that hooker.
- CC labels are restyled on every drag frame, so `PercentLabelStyle` writes MiBlur state only when the colour mode changes per label (`TextBaseline.blend`) or when the host's `updateBlendBlur` forces it, and skips unchanged text colours.
- A label is written only after `PercentLabels.capture`, and its font and size only through `PercentLabelStyle`. Typography runs last, so no capture or refresh path can reset the font after styling. Size is relative to the host size; a later host size write becomes the new base.
- Disable, live change and hot reload all restore from the single baseline. The popup re-runs `initSuperVolumeColor` / `updateVolumeColumnH` with module writes suspended (`replaying`) before applying the new policy.
- Host classes are loaded by name from the plugin loader; there is no DexKit fallback. The CC holder comes from `getSliderHolder()`, whose return type is checked against `ToggleSliderViewHolder`. Verified on plugin sha256 `233eede4…`.
- Size and weight always apply: with the custom option off, `SliderPercentageTypography.effective()` gives the defaults (110%, 400), not the host face. Settings UI: one entry in System UI's control-center card opens `SliderPercentagePage`, which holds every slider-percentage option.
- Hot reload with the control center closed finds no living CC owner: the seek-bar listener that `recoverExisting` follows is only set while listening, and the retired generation leaves the label at the host's GONE. Both CC controllers therefore render on `setListening(true)`.
- Both percentage labels have a 12dip host size (`control_center_toggle_slider_top_text_size`, `miui_super_volume_text_size`). The outer capsule (native 45x20dip) scales with sizes above 100% and keeps its native size below that; the settings preview applies the same rule. The CC brightness controller's `getSliderHolder()` is private, so it is read as a declared method. An enlarged capsule keeps its bottom edge: an after hook on `MiuiVolumeDialogMotion.updateSuperVolumeState()` (the only writer of its `topMargin`) subtracts the extra height, so the capsule grows upward, away from the collapsed bar.
- Colours live in `presentation/slider/SliderPercentageColors`, shared with the settings preview. The flat popup capsule uses its own light/dark pair, because the host's collapsed button colour (`miui_volume_bg_color_collapse`, #6c7074 / #343333) is dark grey in both themes. In normal (non-unified) colour mode, a CC label uses the host `toggle_slider_top_text_color` (#7f000000, drawn for the white fill) when the slider fill reaches the label's centre, and light text above the fill.

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

Feature synchronization through upstream 0.13.0 (`c4a39e0`) adds a Binder-identity
Launcher connection observer, revocable Recents OPEN completion receipts, and
independent default-off status-bar-follow / return-to-Home-preview switches.
`SystemUiReturnHomePreviewRuntime` owns only the optional runner/Surface handoff;
`barfollow/` owns the private App bar graph and authenticated WMS window leases.
The private App graph uses the native HOME DarkIconManager, block-list binder and
MiuiPhoneStatusBarView attachment boundaries, so icon consumers observe it through
the existing shared host hooks. Do not add a second icon-policy or rendering owner.
Trigger-height/position controls were removed at user request; saved values are
ignored. SystemUI uses the native full-height region, and installation/reload asks
WMS to recompute native exclusion once to clear strips from earlier generations.
Graph queries borrow DexRuntime on its preparation worker; helper reflection uses
HostAccess. Keep the narrow shared Kotlin/coroutine/Compose interface names in
`app/proguard-rules.pro`: R8 otherwise rewrites host-loader reflection strings
to module-private names (observed for Function1 in the first release smoke test).
Retire window leases before reload, revoke Recents identities, detach
connection/settings listeners, and recover the current WMS owner only on reload
(never force the singleton during bootstrap). The optional windows and blur have
no claimed performance benefit. Visual/gesture acceptance remains manual.

Queue the back-gesture extension installer only once per runtime generation; its
asynchronous hook installation must never be launched twice with an empty ID set.
`ServerBarGestureScope` qualifies the exact admitted WMS state-6 callback and its
controller. With a revalidated, authenticated live App-window lease, only its
synchronous `updateStatusBarTaskSnapshot` alpha-hide call is suppressed: live status
pixels already follow the task. Native state bookkeeping, navigation work, state-10
finish and unowned/disabled/retired windows retain their original paths. Never drop
the whole state-6 Binder callback or force Surface alpha on each frame.
The same scope defers only an early Home alpha target inside native Recents
initialization, after native Recents classification and an exact display-0
fullscreen Home TO_FRONT / current-app TO_BACK task pair are proven. Parenting and
initialization still run; terminal states select their original alpha targets.
This covers initialization arriving before state 6 as well as the opposite order.
After that proven native initialization returns, commit its existing status-bar
transaction once under the WMS lock and exact lease/surface/transaction identities.
Deferring the alpha animation otherwise removes the Folme frame which used to
flush the queued task-parent commands, leaving a fresh window stationary until a
second gesture after hot reload/live enable. Add no Surface alpha/geometry writes.


## Downloads, credentials and Google AIM

Download detail hooks select a semantically evidenced renderer, derive the download-model argument position and bind the original URL field from the host clipboard consumer. Same-prototype helper methods and other URL-like fields do not count as evidence. Credential row actions match the real Boolean-returning native launch call. Google AIM constructor overrides copy the immutable `Chain.args` and pass replacements to `proceed(array)` exactly once.

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

## Security Center privacy

`SecurityCenterPrivacyResolver` reuses the operand/call-graph index to recover privacy gates, native function-card builders and the beauty initializer/support path. No obfuscated owner/member names or ROM-version branches are stored. Missing optional capabilities remain independent; selecting an absent anti-peeping variant preserves the native gates/visibility. Beauty support overrides are scoped to the native initializer with nested/exception-safe thread ownership.

## Security Center sidebar app shortcuts

`SecurityCenterDockShortcutsHooker` (`com.miui.securitycenter:ui`, switch
`securitycenter_dock_app_shortcuts`, own runtime group `securitycenter.dock_shortcuts` so toggling
never rebuilds dock models) adds a card of the long-pressed app's App Shortcuts under the native
full-screen/split card. Security Center is UID 1000, which `checkPermission(ACCESS_SHORTCUTS)`
grants, so `LauncherApps.getShortcuts`/`startShortcut` work in-process with no system_server hook.
`SecurityCenterDockShortcutsResolver` anchors the window-manager show method
(`showOrHideShortCutMenu: invalid!!!`, `(panel, item, View, callback, Z)V`), the CardView field it
positions, the dismiss method (`dismissShortCut mShortCutMenu: `), and the app model via the
callback base type's uid/package accessors; the model's `AppInfo{uid=` printer proves the int is
a uid. Only an after-hook on the show method is installed. The card is a host `CardView` sibling in
the same panel `FrameLayout`, copying radius, colour, compat padding, elevation and `setMiShadow`
from the native card. A pre-draw listener mirrors its visibility, alpha and scale, so every native
dismiss path also hides the card. The horizontal-sidebar branch (`Z = true`) stays native.
Rows use the host's Miuix drop-down metrics/typography (`miuix_appcompat_popup_menu_item_min_height`,
`drop_down_item_text_size`, `miuix_font_weight_headline2`, immersion text colours). Each row's
foreground is the host's `drawable/miuix_popup_window_list_item_fg` (`PressEffectDrawable`: inset,
rounded, Folme-animated alpha over the whole row, light/night variants). Folme touch tint is not
used: on a transparent row it only darkens the icon pixels. After `startShortcut` the hooker calls the
window manager's animated collapse (`hideNewDockSidebar withAnim`, `(I)V`, gesture 0), which SystemUI
normally requests over the overlay binder for native launches. Results are cached per user/package and invalidated by `LauncherApps.Callback`.
Ordering follows the launcher (manifest first, then rank) with no count cap; a card taller than the
window scrolls. `securitycenter_dock_app_shortcuts_freeform` passes the framework
`MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true)` to `startShortcut`; a null result (app
without small-window support) opens normally. Validation: `SecurityCenterDockShortcutsContractTest`
(set `SECURITY_CENTER_APK`); the user checks placement, tapping, dark mode and hot reload on device.

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
