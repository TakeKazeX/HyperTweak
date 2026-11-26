# System framework features

Part of the [feature reference](../features.md). Search by feature or class name and read only the matching section.

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

## Native photo/file pickers

AOSP / International Restore exposes independent native photo/file picker switches.
`NativePhotoPickerHooker` filters Hyper candidates only for implicit media picker queries;
explicit components and packages remain usable. `NativeFilePickerHooker` bypasses the private
vendor OPEN_DOCUMENT rewrite and redirects Hyper's Browse action through OPEN_DOCUMENT.
Each hooker owns its installation and live cached flag. Missing independent keys inherit the
legacy `KEY_NATIVE_PICKER`, while explicit false wins. Photo needs `system`; file additionally
needs `com.android.photopicker` scope. Changes affect subsequent requests, so active pickers
must be reopened. See `docs/framework/PICKER_REROUTE_RESEARCH_20261024.md`.

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
- Code: `hook/rules/milink/MiLinkSubScreenAppsHooker.kt`, `MiLinkClipboardRegionHooker.kt` (`com.milink.service`), `hook/rules/mirror/MirrorAllAppsHooker.kt` (`com.xiaomi.mirror` main process, scope added for this). Keys `milink_unlock_send_app` and `milink_ignore_clipboard_region`, default off, Purification page → MiLink section. Ported from HyperCeiler's `UnlockSendApp`/`UnlockSendAppM` and `DisablePcManagerClipboardRegionCheck`, narrowed. Research: `docs/apps/MILINK_SEND_APP_AND_CLIPBOARD_REGION_UPSTREAM_20261124.md`.
- Hosts: MiLink 18.2.4.1 (sha256 `e257e049…`), Mirror 18.03.01 (sha256 `7ef1c816…`). Contract test `MiLinkSendAppContractTest` with `MILINK_APK` / `MIRROR_APK`.
- Relay: the real gate is Mirror's provider (`CallProvider` → `isSupportSendApp` → `b4.d.i` allow list). Mirror has its own "all apps" switch (`support_all_app_sub_screen`, cached in a static boolean read by a trivial getter that `b4.d.h/i/j` and the car lists consult). The contract finds the getter as the guard of the "isSupportSubScreen[Old]: all app support" logs, its field, and the config initializer that writes it from the preference reader. The hooker sets that field (no hook on the inlinable getter), re-sets it after the initializer, and on disable/retirement re-runs the initializer so the host restores its own value. Live via the `mirror.send_app` group.
- MiLink side: only the local sub-screen/TV app-cast allow list (class logging `ML::SubScreenApps`, static `(String)Z` member) answers true; empty names, the default launcher and MiLink itself stay native, and all callers are deoptimized. `WHEN_ENABLED`, toggled through `POLICY_FEATURES`.
- Not ported on purpose: forcing `MiuiSynergySdk.isSupportSendApp*`, `RemoteDeviceInfo.isSupport*`, the `AppCirculateClientHelper` device check, `supportHandoff` and the proto getters. With Mirror's switch on, the SDK calls already return true. The others are peer-device capability or per-app Handoff support. Mirror hard-codes `isSupportSendAppToPhone` to false on this build, so phone targets stay unavailable.
- Clipboard: `UniversalClipDataReceiver.processMessageData` (process `com.milink.crossdeviceservice`) drops non-PC/Mac peers whose region differs; PC and Mac peers already skip the check natively. The contract takes the static `(Context,String)Z` call right before the "peer device is not same region" log (it must compare two reads of one region helper with `TextUtils.equals`). The hook answers true and the receiver is deoptimized. `getDeviceRegion` is only called by that check, so it is not overridden. `WHEN_ENABLED`, live through `POLICY_FEATURES`.
