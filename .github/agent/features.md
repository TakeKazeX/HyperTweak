# Feature reference

Per-feature mechanisms, host contracts and known limitations. Search by feature or class name and read only the matching section.

## Clipboard and phrases

The Clipboard & Phrases page has three default-off switches with independent
effects. The former `phrase_unlock_limits` key now controls phrase count and
editor length. The clipboard switch selects 10, 20, 50, 100, 250 or 500 stored
entries and a fixed age of 3, 7, 30 or 90 days. Absent and unrecognized values
normalize to the host defaults of 20 and 3.

The provider scope is `com.miui.phrase` on OS4. The access switch checks that
the Binder caller is the current user's default IME with an unshared UID.
Additional access applies only to query and insert on the native local-history
URI. The native signature-list decision is delegated only within that authorized
insert. It uses the original transfer and storage path. Grants are thread-local,
and incomplete chunk transfers are tied to the caller UID and the expected next
index. Disabling or replacing the module restores authorization on subsequent
calls and retires module-managed partial transfers.

The provider applies age and count when merging a new item, committing the
native history write-back and building a query snapshot. A result with no retained
rows stays empty instead of exposing the provider's raw-JSON fallback. Single-item writes keep
native model deduplication, parsing, encoding and eviction cleanup. The native
clipboard storage path still rewrites the full retained JSON. Large count limits
can increase latency. Lowering limits filters the next query and prunes storage
on a subsequent write.

With either clipboard switch active, write and query paths coalesce a local
single-item plain-text variant and its HTML counterpart when the visible text and
size match within two seconds. The formatted record wins. The device showed paired
writes 904 ms and 1,063 ms apart with identical text and different raw payloads.
Files, multi-item clips, separate copies outside that window and cloud items keep
their native identities.

The native panel separately filters for three-day expiry inside the side-loaded
phrase APK. The configured keyboard's existing IME layout loader installs the
matching retention adjustment and receives live changes in the same settings
group. The keyboard must be selected for IME layout hooking and in LSPosed scope
to display history older than three days. The existing panel's incremental
local-copy and cloud-merge trims may still temporarily affect how many rows are
visible while the panel stays open. Keyboards with private history require their
own integration.

The phrase hooks keep the native editor intent and restore its original
256-character filter on disable, reset and hot replacement. Open drafts are
preserved and replacement generations can find living editors in the activity
registry.

Run `PhraseLimitResolverApkTest` with `PHRASE_APK` pointing to the verified
MIUIFrequentPhrase 5.7.4 baseline, SHA-256
`5e15d459cfa71ec0515e8d59f155f84000d7e2be0ba7dc1ef825ca813c723f69`.
Device acceptance covers default keyboard changes, native panel read/write,
copy ordering and deduplication, count and age options, adding a 21st phrase,
and editing more than 256 characters across enable, disable and reload.

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

## Force allow freeform

On HyperOS 4.0.6.0, the freeform eligibility query for Recents and the actual
window-mode change use separate checks. In system_server,
`ActivityTaskSupervisor.findTaskToMoveToFront` calls `Task.isResizeable()`
before applying launch bounds. The override applies to the requested task
during an explicit freeform launch. `TaskDisplayArea.isValidWindowingMode`
also performs a generic multi-window check when applying a freeform
transaction, including entry into mini freeform. During validation of mode 5
with a task, the display-area override lets this check accept non-resizable
tasks while the framework still evaluates the requested mode, display support,
and minimum sizes. The scopes unwind after the synchronous host calls and
sample the live switch at the decision points, so no task metadata is retained
or rewritten. Disabling or resetting the switch restores the native decision
for subsequent requests. The framework owns tasks already in freeform.

On device, verify both Recents freeform and mini freeform with a non-resizable
task such as System Updater, then retry after disabling the setting.

## Force allow split screen

The independent, default-off switch uses the shared `system.freeform` settings
domain. `ForceSplitScreenHooker` runs in system_server. HyperOS 4.0.6.0 produces
Recents and organizer eligibility through `Task.fillTaskInfo` and checks
split capability again when selecting a launch root. The split-specific task
and ActivityRecord checks get a call-scoped non-resizable allowance for the
evaluated display area. Native split capability, the resize denylist, and
minimum-size rules still apply. SoSc reads both split and generic multi-window
fields, so standard tasks that pass the split check advertise both in the
generated snapshot. Task and ActivityInfo resize modes are never rewritten.

For a direct organizer reparent or a Recents launch that moves an existing
task, the generic multi-window check gets the allowance only for a verified
SoSc stage destination and a task that passes the native split check with the
allowance. Full-screen, freeform and PiP reparent requests use their own rules.
On live setting changes and hot replacement, the hook asks the existing task
organizer to republish leaf task info and sends the native task-stack change
notification. Disabling restores native eligibility and delegates any active
split-session response to the host. On device, verify opening System Updater
as the first and second app from Recents, exiting split, and disabling the
setting. Layout compatibility is app-specific.

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

Settings search opens through `MainSearch` (`ui/navigation/GlassRailHost.kt`), owned by the nav container. In OS4 it is the upstream `GlassSearchOverlay` on the main pager (`SettingsSearchOverlay`, migration guide §7.1): the pager Scaffold fades with `rememberGlassSearchBarAlpha` and the top bars get `glassSearchBarTransition` on their `contentModifier` through `LocalTopBarSearchExpanded`; results clear the bar with a lazily computed top padding (`LocalSearchTopInset` drops it below a collapsed rail capsule) and return to the top on every query through `requestScrollToItem` in the same frame (a `LaunchedEffect` scroll lands a frame late and flashes the old position under the band). Opening it from a secondary page pops to the main pager without a transition; picking a result dismisses it and opens the page. OS3 keeps `Route.Search` (`SearchPage`, a Miuix `InputField` under the title); both share `searchResults`, grouped by destination page. Every main bottom bar offers search except on the Settings tab (`SETTINGS_PAGE`): the glass bar as upstream's `trailingAction` (animated away), the liquid bar as `IosLiquidGlassActionButton` beside its capsule (Kyant interactive-glass-bottom-bar layout: capsule `weight(1f)` + bar-high round action 8dp apart, the row widening toward 16dp margins so tabs keep their width; the button follows catalog `LiquidButton` press/pull in `layerBlock` and slides out from under the capsule's end cap), the classic and floating Miuix bars as a fourth never-selected item. The OS4 glass rail has it as a secondary item, hidden while the main pager shows Settings; only the OS3 classic rail falls back to an `AppSearchAction` top-bar button on Home and Features. `ui/search/SettingsSearchIndex.kt` is generated by `.github/scripts/generate_search_index.py`; CI runs it with `--check`, so rerun it after you add, rename or move a row. The script indexes rows whose `title` is a plain `stringResource(R.string.x)` inside the `ROW_CALLEES` composables, and takes the enclosing `AppSection` title as the row's section. It works out each row's destination from the `entry<Route.X>` blocks, and helper composables inherit the destination of the composables that call them. A row whose `onClick` is a nav `onNavigateTo…` callback points at the page it opens. If a composable with rows resolves to no destination, or to more than one, the script fails. Tabs come from `MAIN_TABS` (main pager) and `PAGE_TABS` (in-page tabs). A tabbed page reads its tab through `rememberSearchLandingTab(route)`, so a result opens on the right tab. Rows whose titles have format arguments or are built dynamically are not indexed. Results open the page only: they don't scroll to the row or highlight it.

Page chrome lives only in `ui/chrome/` and mirrors the upstream example (`AdaptiveTopAppBar`,
`BlurredBar`, `rememberBlurBackdrop`, `pageScrollModifiers`, `pageContentPadding`) and the
upstream glass migration guide (Mishka `docs/miuix-glass-migration.md`, Miuix 0.9.5-rc01):
- Every route is an `AppScrollPage` (column), `AppLazyPage` (lazy list) or, for custom layouts,
  `AppPage { padding -> }`. The frame owns the scroll behavior, Miuix `Scaffold`, popup host,
  `LocalAppOverlayHost`, the content-only top-bar backdrop (`LocalTopAppBarBackdrop`, from
  `rememberPageBackdrop`, null when blur is off or RuntimeShader is missing) and `AppTopBar` with the built-in back button. Pages never build a
  Scaffold or top bar themselves and never pad content *below* the bar: content is laid out under
  it from the Scaffold padding, so the bar can collapse and blur over it. Tab rows and pinned
  previews go in `topBarBottom` (Icon tuner, Camera). Root tabs pass `onBack = null` and the
  pager's bottom bar height as `bottomInset`.
- Two-level sampling: the top bar, its buttons and tab row sample the content-only
  `LocalTopAppBarBackdrop`; OS4 floating overlays (dialogs, dropdown popups) sample
  `LocalPageBackdrop`, the whole-page `PageBackdropBox` wrapped around `NavDisplay` (bars
  included, recording only in OS4). Overlays render in the root Scaffold's popup host, so a
  transparent root Scaffold sits *outside* that box (upstream Mishka layout). Never let a
  recording contain the glass that samples it: RenderThread recurses until the stack overflows.
- `AppTopBar`: OS4 renders upstream `GlassTopAppBar` (band clear until content scrolls under it,
  i.e. `contentOffset < -1f || overscrollOffset < -1f`, then a progressive blur of the content
  backdrop, or a surface gradient when blur is off); OS3 renders
  the classic `TopAppBar` over a uniform/progressive blur layer. `contentScrolled`/`titleAlpha`
  serve About's collapsing logo. Slot buttons use `AppIconButton` (imported as `IconButton`), which
  becomes a `GlassIconButton` pill only inside the OS4 bar.
- `Modifier.appPageScroll` mirrors elastic overscroll into `TopAppBarState.overscrollOffset` and
  attaches the nested-scroll connection. It owns the rebound, so the scrollable under it passes
  `overscrollEffect = null` (the Miuix theme would add a second effect). OS3 defers the backdrop capture until the entry transition
  settles; OS4 captures from the first frame so glass never enters as its fallback.
- `AppOverlayDialog` → `GlassDialog` and the string-items `AppDropdownPreference` (imported as
  `OverlayDropdownPreference`) → `GlassDropdownPreference` in OS4, sampling `LocalPageBackdrop`.
  `AppTextField` (state form) becomes `GlassTextField` inside a glass dialog.
  `AppWindowDialog` (and `CenteredDialog`) also renders an in-page `GlassDialog` in OS4 when it is
  inside an `AppPage`; outside a page and in OS3 it keeps its own `WindowDialog`. Inside a glass
  dialog `AppTextButton` (imported as `TextButton`) becomes `GlassDialogButton` (Primary for
  `textButtonColorsPrimary()`, Destructive for `destructive = true`). The entry-form dropdown stays classic.
- About follows upstream `AboutPage`: `GlassCard` groups over the animated background (only the
  hero icon/title keep `textureBlur`), title fades in at 45% collapse (200 ms), and the bar
  material appears once the logo has fully scrolled away.
- Icons are Miuix only (`MiuixIcons` / `MiuixIcons.Glass`); there is no Material icons dependency.
- Settings groups use `AppSection(title) { rows }` (SmallTitle + 12dp-inset card + full-width
  column); page tab rows use `AppTabRow` (OS4 `GlassSegmentedTabRow` whose `surfaceAlpha` rests at
  0 and rises to 1 once content is under the bar, via `LocalTopBarContentScrolled`; OS3
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
items. Landscape OS4 shows a `GlassNavigationRail` owned by `GlassRailHost`, outside `NavDisplay`, so
it persists on secondary pages (which select nothing; picking a tab there pops to the main pager
with an instant transition). NavDisplay stays full screen; the rail's progress drives
`LocalTopBarStartInset` (applied by the top bar `contentModifier` and page content after
`layerBackdrop`, via `sidebarStartInset`), `LocalTopBarTitleAlpha` and `LocalSearchTopInset`.
Root-Scaffold popups draw above the rail. Predictive-back corners are dropped in multi-window. `AppBlur.kt` applies the blur policy to card and navigation-bar blur;
progressive mode uses Miuix's native `progressiveTextureBlur` pipeline.
Page tab rows measure labels in selected and unselected Miuix typography; the glass segmented
row only grows its height for large font scales. Classic tabs expand their native scrollable items; when
they fit, the track is narrowed to an integral per-tab pixel width because Miuix rounds tab
widths and would otherwise expose a 1-2 px drag/overscroll range.
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
- **OS3 VoLTE compatibility.** If the live VoLTE gate cannot find the public host `flowCombine` method, OS3 may use the verified inlined two-Flow constructor. This fallback is scoped to Hide VoLTE and preserves the host emission type and live settings. OS4 uses the public method.
- **Native Wi-Fi visibility.** Let SystemUI produce the native Wi-Fi icon for its current route. Hide Connected Wi-Fi Icon masks active Wi-Fi at the status-bar binder flow and the module-rendered surfaces. Keep default-route state for signal composition. Do not override the shared Wi-Fi icon factory, which also feeds Quick Settings.
- **Independent publication.** Stacked bars use `hypertweak_stacked_mobile_icon`; the former shared `stacked_mobile_icon` name is migration input only. Slot modes, custom ordering, hidden lists and control-center row overrides migrate through `IconConfigMigration`, with explicit current entries taking precedence. Never normalize a foreign runtime holder into the current slot.
- **Module priority.** `IconHostHooks` registers host observers at priority 10,000 (EzHookTool default: 50). Its stable IDs include the priority, because API-102 `replaceHook` keeps the original handle's priority. A priority change must create new handles and retire old ones. This does not guarantee dominance over arbitrary hooks at still higher priority.
- **Reserved slots.** `HostIconBridge.publish` replaces an ordinary foreign holder in `IconSlotPolicy.moduleSlots` instead of yielding. Other slots remain ownership-checked. Publication readback, rollback on failure, and exact icon identity for generation cleanup remain mandatory. Unknown holder shapes are rejected.
- **No Helper coexistence.** There is no Hyper Helper detection, foreign type-slot masking, or conflict-based preference for its output. Users must disable overlapping Helper features. Do not add foreign `stacked_mobile_type` or `single_mobile_sim1/2` aliases to `IconSlotParts.CELLULAR_SLOTS`.
- **Retired slots.** `StackedRenderer` cleans retired signal/type holders through `HostIconBridge.removeRetiredModuleHolder`, independently of settings and asset readiness. It requires this module's package and an ordinary type/tag and reads the host list back. A holder of another package stays untouched; `removeOwned` retains strict generation identity.
- **Native type label.** Resolve unique `mobile_group`, TextView `mobile_type_single` and ViewGroup `mobile_signal_container` roles under the verified mobile root. `TypeOnlyBranchPolicy` preserves the complete label ancestry and masks sibling branches, supporting wrappers and a label relocated within the root. Layout adoption updates the topology; mask release restores the latest native visibility request. Only missing/ambiguous roles reject the host contract, never another module's presence or a wrapper by itself.
- **Left clones and themes.** Clones are outside `mIconGroups`, so `LeftHostSubscriptions` forwards `StatusBarIconControllerImpl.onMiuiThemeChanged(boolean)` to each clone's `onMiuiThemeChanged`, then re-syncs and re-samples the handover.
- **Two-line carrier budget.** The control-center status layout reports each icon line's free width (`IconHostPorts.carrier.rowSlack`, real LTR row only). Each carrier row extends by its own line's slack through a negative row end margin. Badges share one column only while every row can afford it; a row that cannot fit its own badge drops it.

Root 设置调试、临时授权、AI 命令与 v2/v3 诊断包维护见 `docs/DEBUG_DIAGNOSTICS.md`。

## HyperOS media carousel

In the unlocked notification shade, the HyperOS style yields to the stock media row when the
host has sessions from a single app and profile and none are hidden or dismissed. Filtered
sessions keep the carousel in charge so the stock row cannot resurface a suppressed session.
The host owns its top session and the stock row remains bound throughout.

The pager keeps surviving native card views attached through session changes, cancels stale
page animations, and aligns the selected page after layout. The long-press modal temporarily
sets its source page invisible without changing its size. Dismissal restores visibility before
releasing modal resources. Pager movement, session membership changes and shade visibility
loss dismiss the modal.

## Purification and optimization page

Device and settings refreshes use the committed top route and Activity foreground state.
An in-progress or cancelled predictive return leaves the refresh state intact.

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

The Phone section's default-off network type menu switch uses the TeleService
process. The structural contract matches the removal branch for
`button_preferred_network_type_key` and the search provider's network-type entry.
Only those two consumers can override the `Phone` delegate's UI visibility result.
Enterprise-managed devices retain the native result. The slot-ID policy still drives
carrier option filtering and automatic network selection. Settings updates and hook
publication queue existing SIM settings activities for native recreation on resume,
including activities opened before the feature was installed. Already cached Settings
search results depend on the host's next index refresh. Run
`NetworkTypeMenuContractTest` with `TELESERVICE_APK` set to the verified host APK.

The Wi-Fi section's default-off network acceleration switch (OS4, Settings main process)
lists every launcher app on the acceleration page. `LinkTurboAppsHooker` resolves the AsyncTask
that copies a no-argument List getter into the HashSet gating launcher
`ActivityInfo.packageName`. Only reads inside that task are widened, to the native list plus
every launcher package. Backup agents keep the native list, and the host still applies uid,
internet and per-app whitelist state. The page re-reads the list on its next open. Run
`SettingsDisplayNetworkContractTest` with `SETTINGS_APK` set to the verified host APK.

The Display section's default-off "Never" screen timeout switch (OS4, Settings main process)
uses `NeverScreenTimeoutHooker`. It resolves the timeout preference whose initializer writes the
`oled` static flag and whose filter reads it next to `getMaximumTimeToLock` and the `0x7ffffffe`
cap. On the main thread the filter runs with the flag cleared, so device-admin limits stay
native. Each preference's unfiltered entries are captured on first sight, so enabling restores
"Never" on a resumed page. A preference already filtered before the hook loaded needs the page
reopened. Disabling lets the host rewrite a stored "Never" to its longest remaining entry.

The Display section's default-off refresh-rate switch runs in MiSettings' main process
(`com.xiaomi.misettings` scope). `RefreshRateListHooker` changes the reflective `fpsList`
helper's result only while the page that sorts it into `customize_fps_ddp` is building. It adds
default-display mode rates at the current resolution up to the native maximum, so the host's
"maximum" label is unchanged; restore, search and AI-action readers keep the native list.
Preparation is asynchronous, so a page opened immediately after a cold MiSettings start can
still show the native list. Upstream's forced "custom high refresh apps" entry is not ported: it
is native on the verified device and depends on PowerKeeper config. Run `RefreshRateListTest`
with `MISETTINGS_APK` set to the verified host APK.

The Display section's default-off per-app refresh rate switch spans MiSettings and PowerKeeper
(both main processes). `AppRefreshRateRowsHooker` resolves the "custom high refresh apps" row
holder from its bind (item field, `SlidingButton`, row click listener that reports
`(item, checked)` to the page). It replaces the switch with the host's bundled Miuix drop-down,
built as `miuix.preference.DropDownPreference` builds it (`MiuixDropDownContract`: the
preference's double-line content adapter with its entries field, wrapped in the checkable
`(Context, layout, content, isChecked)` adapter, on a `miuix.appcompat.widget.Spinner`). The
spinner shows the rate in the row, and a row click opens the HyperOS drop-down menu of the
panel's rates. The top rate and
60 Hz stay Xiaomi's on/off state and are applied through the host's own row click; other rates
are stored in `Settings.Secure` `hypertweak_app_refresh_rates` (`AppRefreshRates`; both hosts
run as the system uid) and keep the app on. `AppRefreshRateHooker` resolves PowerKeeper's
custom-mode updater (custom map → active map alias) and the `ForegroundInfo` handler that looks
the foreground package up in the active map. Only in custom mode and only for apps absent from
the custom map, it places the stored rate there for that one call and removes it afterwards, so
native scenes still win and PowerKeeper's cap at the global rate still applies. Custom mode
exists only at the highest global rate (120/144). Rows re-render on their next bind; a page
open while the switch is turned off keeps its value rows until it is reopened. Run
`AppRefreshRateTest` with `MISETTINGS_APK` and `POWERKEEPER_APK` set to verified host APKs.

The Analytics section has two default-off controls. Package disable is a main-user
app-side root operation. It enumerates all installed user IDs, records original enabled
states against Android user serial numbers before writing, and restores only states it
owns. The durable journal survives upgrade and partial operations. Reconciliation runs
on page entry/resume, boot and package replacement; Android may defer broadcasts for
other profiles until the next reconciliation. Full settings reset restores the managed
package states. The journal is device-local and excluded from portable backups.

Telemetry blocking uses the `com.miui.analytics` scope and the shared live settings
dispatcher. The feature resolves the OneTrack worker, replay, usage, installed-app,
radio, network and event-store boundaries against the host DEX. It also uses the legacy
pipeline's existing provisioning veto at event ingress and dispatch. Incomplete or ambiguous contracts skip the entire
installation. Existing events stay on disk. A host may resume normal reporting, including
its usual historical usage queries, when the block is cleared. Other apps' own telemetry
and transfers already in flight are outside the gating points. The telemetry preference
is retained while the package is disabled and its UI control is hidden after verification.

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

## Motion Cues and floating window gestures

On OS4, the Google Services switch `motion_cues_freeform_fix` is off by default.
Xiaomi puts the Motion Cues `TYPE_VOLUME_OVERLAY` in both tap-excluded window lists and
merges its window frame into both gesture regions. Resolve the SystemUI creation method
and the stored overlay by following the same view allocation into `addView` and the
host field. Deoptimize the verified session and configuration callers before hooking
creation. The overlay alone reports an empty touchable region through internal insets,
so WMS still computes both regions from all other sources.

`MotionCuesFreeformHooker` tracks overlay attachment on the main thread and scans the
actual WindowManager roots when targets are published or settings are refreshed. This
recovers an overlay created before a hot reload. Disable and retirement remove the
listener and request another traversal to send the platform's default insets mode.
For host verification, check both regions received by `MiuiFreeformModeController`,
then manually test top and bottom captions, resize, and rotation with dots visible.

## MiLink: relay all apps and clipboard region
- Code: `hook/rules/milink/MiLinkSubScreenAppsHooker.kt`, `MiLinkClipboardRegionHooker.kt` (`com.milink.service`), `hook/rules/mirror/MirrorAllAppsHooker.kt` (`com.xiaomi.mirror` main process, scope added for this). Keys `milink_unlock_send_app` and `milink_ignore_clipboard_region`, default off, Purification page → MiLink section. Ported from HyperCeiler's `UnlockSendApp`/`UnlockSendAppM` and `DisablePcManagerClipboardRegionCheck`, narrowed. Research: `docs/MILINK_SEND_APP_AND_CLIPBOARD_REGION_UPSTREAM_20261124.md`.
- Hosts: MiLink 18.2.4.1 (sha256 `e257e049…`), Mirror 18.03.01 (sha256 `7ef1c816…`). Contract test `MiLinkSendAppContractTest` with `MILINK_APK` / `MIRROR_APK`.
- Relay: the real gate is Mirror's provider (`CallProvider` → `isSupportSendApp` → `b4.d.i` allow list). Mirror has its own "all apps" switch (`support_all_app_sub_screen`, cached in a static boolean read by a trivial getter that `b4.d.h/i/j` and the car lists consult). The contract finds the getter as the guard of the "isSupportSubScreen[Old]: all app support" logs, its field, and the config initializer that writes it from the preference reader. The hooker sets that field (no hook on the inlinable getter), re-sets it after the initializer, and on disable/retirement re-runs the initializer so the host restores its own value. Live via the `mirror.send_app` group.
- MiLink side: only the local sub-screen/TV app-cast allow list (class logging `ML::SubScreenApps`, static `(String)Z` member) answers true; empty names, the default launcher and MiLink itself stay native, and all callers are deoptimized. `WHEN_ENABLED`, toggled through `POLICY_FEATURES`.
- Not ported on purpose: forcing `MiuiSynergySdk.isSupportSendApp*`, `RemoteDeviceInfo.isSupport*`, the `AppCirculateClientHelper` device check, `supportHandoff` and the proto getters. With Mirror's switch on, the SDK calls already return true. The others are peer-device capability or per-app Handoff support. Mirror hard-codes `isSupportSendAppToPhone` to false on this build, so phone targets stay unavailable.
- Clipboard: `UniversalClipDataReceiver.processMessageData` (process `com.milink.crossdeviceservice`) drops non-PC/Mac peers whose region differs; PC and Mac peers already skip the check natively. The contract takes the static `(Context,String)Z` call right before the "peer device is not same region" log (it must compare two reads of one region helper with `TextUtils.equals`). The hook answers true and the receiver is deoptimized. `getDeviceRegion` is only called by that check, so it is not overridden. `WHEN_ENABLED`, live through `POLICY_FEATURES`.

## Hide status bar in screenshots
- Code: `hook/rules/screenshot/ScreenshotHideStatusBarHooker.kt` (`com.miui.screenshot`, `screenshot_hide_status_bar`, default off; Screenshot page; `screenshot.frame` group). Same user effect as HyperCeiler's `HideStatusBarBeforeScreenshot`, by a different mechanism.
- Host (`com.miui.screenshot` 1.6.3.47, sha256 `0f1f4bed…`): every non-rear-display capture (full, partial, long) calls `DisplayCapture.captureDisplay(Context,int,Rect,String[])`, which appends `resolveExcludeLayerNames(Context)` to its exclusions and passes them to `CaptureArgs.Builder.setExcludeOrIncludeLayerNames` (strategy `W0/c`). The host's own `WORK_PROFILE_EXCLUDE_LAYERS` already excludes `StatusBar` that way. An after-hook adds `StatusBar` to the resolver result; `captureDisplay` is deoptimized against inlining. Contract: both static signatures plus a static `String[]` holding `StatusBar`; otherwise nothing is hooked.
- The bar never leaves the screen and the switch is read per capture. Do not hide it from SystemUI on `miui.intent.TAKE_SCREENSHOT`: on this build the service's start and finish broadcasts are ~30 ms apart (broadcast history), so the capture is not ordered after a SystemUI-side hide. The lock-screen status bar (notification-shade window), `screencap` and third-party capture are not covered.

## Lock-screen PIN scramble
- Code: `hook/rules/systemui/lockscreen/LockscreenPinScrambleHooker.kt` (SystemUI main process, `systemui_lockscreen_scramble_pin`, default off; System UI → Lock screen, OS4 only; `systemui.keyguard` group). Behavior follows HyperCeiler's `ScramblePIN`; independently implemented. Key moves use `hook/rules/shared/DigitKeypadShuffle`.
- Host (SystemUI sha256 `6c6c1121…`, 17.03.260226.r): `KeyguardPINView.onFinishInflate()` builds `mViews` (6 rows; rows 1..3 the digit rows, `[4][1]` the 0 key) and `mNumViews` (4 rows; `[0..2]` are the same arrays as `mViews[1..3]`, `[3][1]` the same 0 key). `NumPadKey.mDigit` holds each digit and `KeyguardPinBasedInputView`'s button array is indexed by digit, so moving keys keeps input. Contract: both `View[][]` fields with exactly that shape and sharing, ten `NumPadKey`s with digits 0..9, each attached to a row. Anything else leaves the view native.
- A new order is drawn after `onFinishInflate()` and before every `startAppearAnimation()`; on this build the security container calls the latter on every bouncer show (`KeyguardSecurityContainerController` `onViewInflated` lambda). Both matrices are rewritten so the host's appear animation follows the shuffled rows. `TinyKeyguardPINView` inherits both methods. Pattern, password and SIM PIN views are untouched.
- Live and lifecycle: enabling shuffles living PIN views, disabling restores keypad order; retirement restores keypad order and `hostRecovery(WINDOWS)` re-applies the switch to views in the window snapshot. Keypad order is derived from the digits, so a view left shuffled by an older build is repaired.

## Lock-screen PIN glass keys
- Code: `hook/rules/systemui/lockscreen/LockscreenPinGlassKeysHooker.kt` (SystemUI main process, `systemui_lockscreen_pin_glass_keys`, default off; System UI → Lock screen, OS4 only; `systemui.keyguard` group).
- Source look: emergency dialer keys (TeleService `a4d6176a…`, `DialpadButton` → miuix `CallOvalImageButton` with `MaterialUtils.Glass_Colored_Extra_Thin_Dark`). In glass mode only the glass config applies: `setMiViewBlurMode(1)`, `setMiViewMaterialType(1)`, `setMiGlassBlurRadius(120, 120)`, `setMiGlass(Glass_Bionic_Medium_Bold_Motion)`; `PressLightAnimator` lerps the 42 params to `..._Press` (only indices 37..41 differ). `GlassToken.toShaderParams()` flattens the `create()` arguments in order, so the arrays are copied from `MaterialUtils`.
- Host (SystemUI `6c6c1121…`): `NumPadKey` (ViewGroup) draws nothing at rest; its `NumPadRippleDrawable` press ring reaches `min(w, h) / 2`, and `onLayout` only positions the digit and letter text views. A tagged (`hypertweak:pin_glass_disc`) non-clickable disc is added as child 0 from the constructor (may run on an async inflation thread), centred and sized to `min(w, h)` after `onLayout`, and shaped by a round-rect outline the way SystemUI shapes its own keyguard glass buttons (`NotificationUtil.setRoundRect`). `drawableStateChanged` drives the press light. As on the dialers there is no stroke: the key's `NumPadRippleDrawable` is removed and parked in view tag `0x5a71c0de` (restored on disable/retirement by any generation); the host's ring setters only act on `background is NumPadRippleDrawable`. The disc keeps the native ring diameter on purpose: PIN rows are packed to clear the fingerprint sensor, so a 68 dp in-call size would collide.
- MiBlur glass is a container/element pair: the disc (element, which also needs a background drawable because MiGlass draws in the background pass) only renders once an ancestor is a blur container. The dialer's container is `R.id.emergency_dialer` (`setBackgroundBlur(60dp)`); here the enclosing `KeyguardSecurityContainer` gets what the in-call dialpad uses (InCallUI `258b1856…`: button container `setBackgroundBlur(60dp)`, root `Mask_Glass_Light`): pass-window blur, background blur mode 1, radius 60 dp and the SOFT_LIGHT `0xFF999999` + LAB `0xFF0D8000` blends, from the key's `onLayout`; it is cleared on disable and retirement. Without the container the element draws nothing (device-confirmed).
- Gate: `persist.sys.bionic_material_supported`, Secure `material_style==1` and `background_blur_enable==1`, read when a key is built or recovered; changing them does not refresh living keys until the next rebuild or toggle.
- Live and lifecycle: enable adds discs to living keys, disable and retirement remove them; discs are found by tag, so `hostRecovery(WINDOWS)` also removes or reuses discs left by any older build.

## Gesture bar hiding

The gesture bar (`HideBottomBarHooker`) has one decision owner, `GestureBarState`: a per-display phase machine (idle, leaving, hidden, returning). Three moments are strictly separated, because mixing them produced three device-reported regressions. The fade plays *inside the application* about 260 ms into the gesture (`FADE_OUT`, and the draw gate keeps painting via `fadingDisplays` so the animation has something to act on). The platform's `sampling=true` display-level restore then stops drawing immediately with no animation of our own (`HIDE_NOW`), because the platform fades its own copy in at that point and animating there flashes at the bottom of the screen. The reveal on `appTransitionFinished` writes the host alpha back only for a display that is genuinely not suppressed, and `selfDrivenAlpha` keeps the animation's intermediate values out of that record. Authority is explicit: the launcher's authenticated Overview broadcast is the only source of truth, because HYOS runs Recents in the Dart launcher and `ShellController.isOverviewVisible` keeps answering `false` while it is genuinely on screen. `overviewReported` records that a session heard the launcher and survives hot reload; while it is set, the platform query is never consulted - trusting it discarded live sessions and drew the bar over Recents (reported defect). The platform query is only a fallback for sessions the launcher never described, which is exactly the cancelled-swipe case: the fade already played, no Overview was ever reported, and the finished transition restores the bar instead of waiting out the stuck-report deadline. The leaving transition's own finish, which arrives with Recents still visible, is ignored. Every phase has a bounded deadline that fails safe. The launcher Overview bridge needs an application context, so it registers from `onSystemUiContextReady` - the path SystemUI actually takes; package ready alone left zero registrations and degraded the feature to timer guessing. `AospBackSystemUiHooker.onApplicationContextReady` is idempotent and also called from package ready and hot-reload restore.

## Slider percentages
- Code: `hook/rules/systemui/slider/`. `SliderPercentageHooker` belongs to the control-center plugin session and owns the policy (`SliderPercentagePolicy`, which includes `SliderPercentageTypography`). It also owns `PercentLabels`, which holds one host baseline per view, and `PercentLabelStyle`, which owns colour, MiBlur blend and typography. `ControlCenterPercentage` (CC tiles, expanded brightness, brightness transition) and `VolumePanelPercentage` (inner column labels, outer capsule) are `HookComponent`s of that hooker.
- CC labels are restyled on every drag frame, so `PercentLabelStyle` writes MiBlur state only when the colour mode changes per label (`TextBaseline.blend`) or when the host's `updateBlendBlur` forces it, and skips unchanged text colours.
- Volume labels are styled on every `updateVolumeColumnH` (each column, each drag frame), so `VolumeBadgeStyler.detachTextBackground` strips a label's host material once and repeats only when forced after a host material writer: `initColumn` for column labels, `initSuperVolumeColor` for the outer label. While the popup is enabled the host's per-frame `updateSuperVolumeViewColor` (called from `updateVolumeColumnSliderH` / `updateTempColumnH`) is skipped instead of undone; replay and retirement let it run. Excluded-scope enforcement (`suppressExcluded`, after every host update) writes only views not already GONE/alpha 0: each `setVisibility`/`setAlpha` runs the full hooked-setter chain, and redundant writes cost about a third of the SystemUI main thread while dragging. Both were the drag jank in issue #18. Per-update label paths use `setVisibilityIfChanged` / `setAlphaIfChanged` (`SliderPercent.kt`) for the same reason, and `captureController` returns early for a controller already captured with the same column count; panel init, show, refresh and recovery pass `force`.
- A label is written only after `PercentLabels.capture`, and its font and size only through `PercentLabelStyle`. Typography runs last, so no capture or refresh path can reset the font after styling. Size is relative to the host size; a later host size write becomes the new base.
- Disable, live change and hot reload all restore from the single baseline. The popup re-runs `initSuperVolumeColor` / `updateVolumeColumnH` with module writes suspended (`replaying`) before applying the new policy.
- Host classes are loaded by name from the plugin loader; there is no DexKit fallback. The CC holder comes from `getSliderHolder()`, whose return type is checked against `ToggleSliderViewHolder`. Verified on plugin sha256 `233eede4…`.
- Size and weight always apply: with the custom option off, `SliderPercentageTypography.effective()` gives the defaults (110%, 400), not the host face. Settings UI: one entry in System UI's control-center card opens `SliderPercentagePage`, which holds every slider-percentage option.
- Hot reload with the control center closed finds no living CC owner: the seek-bar listener that `recoverExisting` follows is only set while listening, and the retired generation leaves the label at the host's GONE. Both CC controllers therefore render on `setListening(true)`.
- Both percentage labels have a 12dip host size (`control_center_toggle_slider_top_text_size`, `miui_super_volume_text_size`). The outer capsule (native 45x20dip) scales with sizes above 100% and keeps its native size below that; the settings preview applies the same rule. The CC brightness controller's `getSliderHolder()` is private, so it is read as a declared method. An enlarged capsule keeps its bottom edge: an after hook on `MiuiVolumeDialogMotion.updateSuperVolumeState()` (the only writer of its `topMargin`) subtracts the extra height, so the capsule grows upward, away from the collapsed bar.
- Colours live in `presentation/slider/SliderPercentageColors`, shared with the settings preview. The flat popup capsule uses its own light/dark pair, because the host's collapsed button colour (`miui_volume_bg_color_collapse`, #6c7074 / #343333) is dark grey in both themes. In normal (non-unified) colour mode, a CC label uses the host `toggle_slider_top_text_color` (#7f000000, drawn for the white fill) when the slider fill reaches the label's centre, and light text above the fill.

## Device Center single row
- Code: `hook/rules/systemui/controlcenter/DeviceCenterSingleRowHooker.kt`, an optional sub-hooker of the control-center plugin session (`systemui_cc_device_center_single_row`, default off; switch in System UI → Control Center, OS4 only).
- The host card controller (`DeviceCenterCardController`) builds its items in a private `(Z)V` method from its pending `List<DeviceInfoWrapper>` and appends the native "more devices" `DetailItem`; more than four items selects `MODE_2_ROWS`. The hook swaps the pending list for its first 3 entries only while that builder runs and restores it in `finally`, so the host's own detail item takes the fourth slot and its own mode logic picks one row. The device controller's list, discovery tracking and other listeners keep the full list. Changing `getMode()` or the card height alone would clip devices instead of showing More.
- Contract (structural, reflection on the plugin loader): a single-method `(List)V` listener interface, exactly one non-final instance `List` field, one `ArrayList` field, a no-arg 3-constant enum getter, and exactly one private instance `(Z)V` method. Any mismatch leaves the card native. Verified on plugin sha256 `233eede4…` (18.3.2.57.0).
- Live change and hot reload re-invoke the builder (`false`, no animation) on the controllers this generation has seen; the builder no-ops while the card is not listening, and `onResume` rebuilds on every Control Center open, which also covers controllers first seen by an older build. The third-party device-control branch is unaffected. Limitation: the count is fixed at 3 for the full-width card; a card resized narrower than four columns can still wrap.

## Control Center Edit button
- Code: `hook/rules/systemui/controlcenter/ControlCenterEditButtonHooker.kt`, attached for every control-center plugin session (`systemui_cc_hide_edit_button`, default off; System UI → Control Center, OS4 only). Behavior follows HyperCeiler's `HideEditButton`; independently implemented.
- Host (plugin sha256 `233eede4…`, 18.3.2.57.0): `MainPanelContentDistributor.distributePanels(Z)V` lays out only the child `MainPanelContent`s whose `available(Z)Z` is true. An after-hook turns `EditButtonController.available(Z)` true into false, so the host drops the button without an empty slot. Only the single-argument overload exists on this build; the contract also requires the distributor constructor to take `EditButtonController`. The button is the only `QSListController.startQuery(Mode.EDIT)` caller, so hiding it also removes the entry to the card editor.
- Live: the distributor is remembered in a before-hook on `distributePanels(Z)`. A settings change and a hot-reload restore call the host's `distributePanels$default(d, false, 1, null)` then `handleNotifyChanged$default(d, false, false, 2, null)` (the entry controllers' own refresh). Each distributor remembers the hidden value of its last layout; `onStart()` (dispatched on every Control Center open) redistributes one that lags, which covers distributors first seen by an older build and changes made while the panel was closed.

## App volume in the volume dialog
- Code: `hook/rules/systemui/volume/app/`. `AppVolumeHooker` is an optional sub-hooker of the control-center plugin session (`systemui_volume_app_volume`, plus `systemui_volume_app_volume_auto_expand` and `systemui_volume_app_volume_percent` 0 inside / 1 above / 2 hidden; switches default off, percentage inside; System UI → Volume panel, OS4 only, disabled while the AOSP volume panel is on). Behavior is ported from AppVolumeBarHook `952ce50` but independently implemented; no upstream code or assets are copied.
- Gain path: `AudioManager.setPlayerVolume(AudioPlaybackConfiguration, float)` / `getPlayerVolume(String)` → `AudioService` (no caller check) → `PlaybackActivityMonitorStubImpl`, which no-ops unless `Settings.Global.sound_assist_key` is 1, skips client UID < 10000, applies the 0–1 gain to every MEDIA/MUSIC player of that UID, and stores it in memory by `getNameForUid(uid)`. SystemUI holds `MODIFY_AUDIO_ROUTING`, so its playback configs carry the player proxy. MiSound's provider is not written, so values last until reboot. `AppVolumeCandidates` mirrors those rules (one row per gain key, one representative config per UID). Verified on myron OS4.0.6.0.XPMCNXM `framework.jar`/`services.jar`/`miui-services.jar`.
- Host (plugin sha256 `233eede4…`): `VolumePanelViewController` `initPanelView()`, `showVolumePanelH(int)` (before-hook, so the strip is decided before the show pre-draw), `updateExpandedH(Z,Z,Z)`, `onConfigChanged(int)`, `onMaterialModeChanged()`, `destroy()` and `rescheduleTimeoutH()`, all bound by exact signature. The collapsed `MiuiVolumeDialogView` sets its touchable region to its own bounds, so the module's views live inside it: an app column strip at index 0 of `volume_dialog_container` (left of the native column), and an entry inflated from `miui_ringer_mode_layout` under the ringer layout. Sizes and materials call `VolumeColumnRes`, `RingerButtonRes`, `MiuiVolumeDialogRes` and `Util` statics (`AppVolumeHost`), so they track the host's glass/blur/default material. The entry mirrors the DND button's drag-stretch translation and scale, and hides with the native footer.
- Motion: `VolumeShowHideAnimator` moves `mVolumeView` and each ringer button on separate springs (silent +30 ms, DND +50 ms) and rests the buttons at x = 0 (`setViewX`); the entry replays the DND button's x 20 ms later and scales its show-time Y pull by distance from the container center. `MiuiVolumeDialogMotion.getShowX()` is the absolute show X sized for native columns only; an after-hook subtracts the visible strip width (not for `volumeShowLeft()`), otherwise auto-expand pushes the native column off the right edge. After a show the dialog keeps its translation, so a later strip toggle widens it leftward without that hook. Opening animates each column from under the native column (`translationX` = slots to the native column, alpha 0, 35 ms stagger nearest-first); closing folds them back before the strip goes GONE.
- Percentages: the native outer capsule follows the transformed center of the native column (including the temporary column when requested by the host) once the strip opens and for the rest of that collapsed dialog show. The host animator writes X from the dialog origin, so its last X while the strip was open is no longer valid when the strip closes. Folding keeps the badge aligned during the width change. Full expansion or teardown leaves the badge at its current position for the host transition. App labels reuse the slider percentage typography and capsule material. `systemui_volume_app_volume_percent_color` can inherit the slider color, sample the app icon, use a preset or use neutral text independent of the slider color. The playback worker caches the sample before badging, and the renderer adjusts the shade for the track, fill or capsule. For a monochrome themed icon the worker also checks the declared icon, then falls back to the sampled tone. The inherited color is used when there are no visible pixels to sample. Above-bar app capsules are in a non-touchable layer; their positions follow each column's transformed top and the gaps grow to accommodate larger capsules. Without the layer, the app label falls back to inside.
- Overscroll: the native seekbar's `SliderKeyAnim` / `SlideContainerAnim` writes scale and Y translation to `mVolumeContentView` or the temporary column through the controller's seekbar listener. App columns mirror those properties on pre-draw, including the native key-release spring and reset. An app drag can also pull past its own gain bounds and rebound; gain sent to the service stays within 0–1.
- MiSound floating entry: `AudioService.sendVolumeUpdate` → `PlaybackActivityMonitorStubImpl.startPlayerVolumeService(Context,int,int)` (miui-services) only starts `com.miui.misound/.playervolume.VolumeUIService` in the foreground. `hook/rules/system/MiSoundVolumeEntryHooker` (system_server) skips that call while app volume is on and the AOSP panel is off. `systemui_volume_app_volume` is therefore in the `VOLUME` runtime group (SystemUI + android); its SystemUI participant calls `SystemUIPluginHooker.reloadControlCenterSettings()`.
- Lifecycle: playback callback via `generationRegistration`, Binder/PackageManager work on one `generationWorker`, drag writes coalesced to the latest value per key. Views carry the `hypertweak.appVolume` tag so a newer generation removes an older one's views; living dialogs are recovered through the native sliders' listener owner. Limitations: second-space clones share the main app's gain (host keys by package name); with auto-expand off the entry is one extra tap; an already running MiSound `VolumeUIService` is left to stop itself.

## Instant volume adjustment
- Code: `hook/rules/system/VolumeFirstPressHooker` (system_server, `volume_first_press`, default off; System Framework → Volume Keys). Behavior follows HyperCeiler's `VolumeFirstPress`; independently implemented.
- Host: `AudioService$VolumeController.suppressAdjustment(IIZ)Z` returns true for STREAM_MUSIC while the panel is hidden and media is idle; its only caller `AudioService.adjustSuggestedStreamVolume(IIILString;String;IIZI)V` then adjusts with direction 0 (panel only). The after-hook turns a true result into false for non-mute STREAM_MUSIC; other streams keep the native rule. The caller is deoptimized against AOT inlining. Verified on myron OS4.0.6.0.XPMCNXM `services.jar` (sha256 `fe7406b3…`).
- Live: the switch is in the `VOLUME` runtime group and is read per call; no hook is installed or removed. With custom volume steps on, the first press also uses the configured step.

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

## Security Center app link management

The default-off `app_link_management` switch lives under App details. On OS4, the
Security Center main process reuses the host's `app_default_pref` row. The DEX contract
checks the preference binding, click branch, loader bridge and package parsing, and
resolves the viewed app from the host's `ApplicationInfo`. The title and link-permission
summary refresh after native data loads and when returning from Android Settings.
Clicking an owned row opens the system open-by-default page for that package. A failed
launch restores the row and consumes that click to prevent an accidental clear.

Removed rows (including the browser on the verified build), apps belonging to a different
user, and unavailable Settings routes retain native behavior. Android's public domain
state and Settings action address the calling user. Missing domain state displays the
disallowed summary. The preference key and order stay available to the AOSP app info
entry. Settings changes and new generations bind live fragments through the activity's
typed fragment field. Disable, reset and retirement restore native text.

## Security Center privacy

`SecurityCenterPrivacyResolver` reuses the operand/call-graph index to recover privacy gates, native function-card builders and the beauty initializer/support path. No obfuscated owner/member names or ROM-version branches are stored. Missing optional capabilities remain independent; selecting an absent anti-peeping variant preserves the native gates/visibility. Beauty support overrides are scoped to the native initializer with nested/exception-safe thread ownership.

## App Lock PIN scramble
- Code: `hook/rules/securitycenter/AppLockPinScrambleHooker.kt` (Security Center main process, `securitycenter_app_lock_scramble_pin`, default off; Security Center → App Lock; runtime group `securitycenter.app_lock`). Behavior follows HyperCeiler's `AppLockPinScramble`; independently implemented. Key moves go through the shared `hook/rules/shared/DigitKeypadShuffle`, also used by the lock-screen PIN.
- Host (`com.miui.securitycenter` 13.8.0-261020.0.1, sha256 `e53a09e3…`): `com.miui.applicationlock.widget.MiuiNumericInputView` (a `LinearLayout`) builds itself in its only constructor: four fields of one nested `LinearLayout` row class (three nested `FrameLayout` keys each) and one nested-key array indexed by key code, 0..9 digits and 10/11 the bottom-row action keys around 0. Contract: exactly one such array (12 entries at use), four row fields of one type, four row children of that type with three keys each, and the two bottom-row ends being codes 10/11. Anything else leaves the pad native.
- The after-constructor hook shuffles each pad once; listeners and the key-code array are untouched. Every Security Center screen using this widget is shuffled (unlock, set/change PIN). `com.miui.auth.widget.MiuiNumericInputView` (privacy password / biometric PIN) is a different widget and is not covered.
- Live and lifecycle: enabling shuffles living pads, disabling restores keypad order; retirement restores keypad order, and a new generation finds living pads through `WindowInspector`. Keypad order is derived from the key-code array, so a pad left shuffled by a build that saved no state is repaired too.

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

## PowerKeeper allowlist restore block

`PowerKeeperWhitelistRestoreHooker` runs in every `com.miui.powerkeeper` process. Its switch is on the Features tab, key `powerkeeper_block_whitelist_restore`, group `PASSIVE_POLICIES`. Verified on PowerKeeper 4.2.00 / OS4.0.6.0.XPMCNXM (APK SHA-256 `164f2b62…5448c6`).

**Where PowerKeeper adds apps.** `DeviceIdlePolicyHelper.c(String[]) → CommonAdapter.addPowerSaveWhitelistApps → IDeviceIdleController.addPowerSaveWhitelistApps(List)` is its only allowlist add. Three sources feed it:
- `DeviceIdleController.onHandleMessage(what=1)` re-adds `sAlwaysWhiteApps` on every rule pass. On CN builds that set is `array/doze_always_white_apps` (WeChat, QQ, QQ Lite) plus `pkg_deskclock`.
- Apps whose rule allows them, such as those in the cloud `dozeWhiteListApps` list or set to user `noRestrict`, are added through `setUidState`.
- The force-idle and power-save restores.

PowerKeeper removes apps only through `IDeviceIdleController.removePowerSaveWhitelistApp(String)`. Rule flips also remove apps that the user allowlisted in Settings. Natively, PowerKeeper adds them back later.

**What the hook does.** It sits on the framework `IDeviceIdleController$Stub$Proxy`, so it is independent of PowerKeeper obfuscation:
- **Remove:** before each removal, it queries `getUserPowerWhitelist()`. If the package is on the list, it records the package in `AllowlistRestoreLedger`, whether or not the switch is on.
- **Add, switch on:** it passes only recorded packages, each once, plus GMS while FCM Live is on, because FCM Live feeds GMS through `getDozeWhiteListApps`. It drops everything else. When the switch is off, adds pass unchanged but still consume their records.
- **Settings:** writes from its own process and is unaffected.
- **Hot reload:** the ledger crosses as a `HashSet<String>`.
- **Limitations:**
  - A PowerKeeper process restart, or an upgrade from a build that saved no state, forgets pending restores.
  - If the user restricts an app in Settings while PowerKeeper has it removed, PowerKeeper's later restore overrides that change.

**Compared with upstream.** HyperCeiler's `PreventBatteryWitelist` hooks `DeviceIdlePolicyHelper.c` and blocks only arrays longer than 1. It misses single-package adds, and it blocks PowerKeeper's legitimate restores whenever they are batched.

**Inlining.** `CommonAdapter.addPowerSaveWhitelistApps` has no AOT code in `PowerKeeper.odex`, so nothing inlines the proxy.

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
[native.md](native.md#native-dock-glass).
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

### HTML Viewer freeze and OTA notification replay

- **Purification & optimization → HTML Viewer** exposes a default-off Millet switch. The system-server hook resolves the final system-app decision from the `systemapp` reason, package operand and system-app control membership. It treats only HTML Viewer as eligible at that decision and leaves the surrounding Millet checks in place. The policy runs on the shared app UID. The dispatcher updates the hook slot without editing Millet lists or touching processes.
- **Replay the OTA Super Island** uses an authenticated Settings provider relay to reach the signature-protected HTML Viewer provider. HTML Viewer reads its native cached OTA response and invokes the stock notification publisher as the originating app. The original focus metadata, assets, channel, and tap destination come from that publisher. The command checks the cached system version and notification settings, and requires the Settings and HTML Viewer scopes. Navigation uses the shared app-specific Purification page shell.
