# Back gestures

Part of the [feature reference](../features.md). Search by feature or class name and read only the matching section.

## Upstream back-gesture module coexistence

Installed `dev.codex.miuibackgesturehook` deliberately disables overlapping back-gesture
and contextual-long-press controls and managed callbacks, regardless of the upstream
LSPosed toggle. Debug → Diagnostics → Disable upstream detection bypasses this managed
policy through live settings without deleting feature preferences. The
System Launcher page opens its exported Xposed settings entry. Native load callbacks
independently detect its mapped payload and yield shared gesture/contextual/Dart-state
hooks. Independent native rules retain their broadcast metadata and forward through
upstream's identity-options GOT chain. The separate upstream Overview observer shares
only authenticated visibility with gesture-bar hiding. See `docs/backgesture/PR10_BACKGESTURE_IPC_20261021.md`
for the reproduced conflict, actual-image contracts and delivery evidence.

## Back-gesture runtime

The vendored Android-17 back runtime is split under `hooks/systemui/` into input
policy/driver/monitor, open-transition tracking, navigation lifecycle, prepared
transitions, and animation rendering/commit. `NativeLauncherBridgeRuntime` owns
SystemUI's authenticated native launcher messages; the unused Java launcher
controller hierarchy and Android-16 back adapters have been removed. Material and
back-haptic mute are choices in the indicator/haptic dropdowns and join the shared
Boolean snapshot (both default off). Each dropdown commits its two flags together; mute preserves
the selected haptic scheme and does not mute contextual search. See
`docs/backgesture/BACK_GESTURE_UPSTREAM.md` for the upstream pin and integration boundaries.

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
