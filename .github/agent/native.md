# Native payload reference

The launcher native payload (`app/src/main/cpp/NativeRules/`): structure, structural Dart resolution, settings delivery and per-rule contracts. Validation recipes are in [workflows.md](workflows.md#native-launcher-rules).

## Payload overview

`app/src/main/cpp/NativeRules/` builds `libhypertweak_native.so` from `app/CMakeLists.txt`; LSPosed injects it via `app/src/main/resources/META-INF/xposed/native_init.list` and calls the upstream `miui_home_native_hook.cpp` entry. The launcher lifecycle, AOT state hooks, remap repair, fork owner reset, and LSPlt backend derive from the Apache-2.0 upstream implementation. Launcher entry selection always uses structural resolution; version tables and fixed-address fallback candidates are removed. `clear_button_rule.cpp`, `folder_columns_rule.cpp`, and `assistant_widget_rule.cpp` are the feature-specific rules; they resolve their targets through the structural registry (see below) and use upstream's validated Dart image and action-down maintenance paths. Their load callbacks consume the supplied handle and never call `dlopen`.

### Settings transport

The launcher-side rule switches normally arrive from SystemUI, which reads complete remote `Preferences` snapshots and publishes an explicit `com.android.systemui.fsgesture` intent to `com.miui.home` with shared sender identity. `NativeRuleStatePublisher` performs finite replays at startup/settings changes/boot/unlock and closes its receiver and queued tasks on hot reload. The independent receiver-dispatch PLT hook captures and authenticates the actual SystemUI package and uid, schema and complete primitive payload before forwarding the borrowed Intent. After the original receiver returns it accepts the monotonic revision and applies cached rules without reading the consumed Intent. It preserves upstream's inline receiver and private-send hook ownership. Launcher JNI remains a fallback: the native HYOS launcher does not reliably execute the Java settings route.

### Stacked-folder preview

The stacked-folder preview rule for the structurally verified big-folder family transforms validated cached preview geometry at the native getter boundary and adjusts primitive count/index arguments in the original Dart child generator. It decodes family CIDs through the shared constructor, infers rows/columns from canonical native geometry, preserves every large icon and small-preview interaction classification, and renders at most three stacked cards with the earliest small application in front, and leaves the source application list and original Dart stores/write barriers intact. All six hook sites (geometry/count/order/frame/scoped-ID/completion) must be ready before callbacks apply the feature; staged failures roll back and contended callbacks retain native arguments. Value changes, first installation and fork recovery enqueue one primitive refresh generation. At the next native begin-frame boundary, before build/layout, a verified Dart tail transfer invokes the existing all-folder preview invalidation body after popping the original frame. Within this module-owned DartThread scope, the original ID-array fill reads the parent layout GetBuilder ID decoded from its registration site; normal monochrome calls retain their preview ID. The parent rebuild is required because the preview-container builder can return its cached Widget unchanged. The original refresh return records completion and clears scope. This restores canonical geometry on disable/reset without retaining heap roots or calling Dart from C++. Model/DI initialization guards retain pending work; equal snapshots remain idle. No new frame is forced while the launcher is fully idle or paused; returning Home supplies the next frame.

### Rule switches

The switches are `folder_stack_preview`, `android_widget_stack`, `hide_recents_clear`, `recents_card_haptic`, `opened_folder_columns`, `contextual_search_long_press`, and `allow_android_widgets_to_assistant`. Unavailable or malformed remote Preferences never publish defaults.

### Preparation worker and caches

`native_rule_runtime.cpp` saves accepted snapshots in launcher-owned device-encrypted `hypertweak-native` storage and restores them without Java initialization. One eventfd worker prepares targets; input/receiver/load boundaries consume prepared results. A thread-local internal-library-query guard and generation gate prevent LSPosed's `RTLD_NOLOAD` callback from requeueing the worker; completed generations remain idle and failures retry only on bounded external events. `dart_resolution_cache.cpp` persists successful relative offsets keyed by Dart build ID, module version, schema and site contracts, with executable-range and exact 32-byte evidence checks before reuse. Failed results remain process-local; configuration/fork/identity changes permit fresh preparation.

### Opened-folder columns

The opened grid reads a late-static column cache rather than recomputing the getter on each open. A separately resolved `onFolderOpen` Dart shim synchronizes that cache on the owning Dart thread before layout, using the field displacement decoded from the unique width-calculation reader. It captures and restores the native SMI on disable, leaves sentinels/objects untouched, and retains a passive open boundary after removing grid/preview overrides. It preserves the native frame, arguments and NZCV without entering a C++ or Dart trampoline. Device testing on 2026-10-03 confirmed live clear-button changes, idle preparation while dozing, and user-visible 4/5/native column changes on reopening, with folder close/Home return and preview intact.

### Packaging

`liblsplt.so` comes from the vendored AAR in `app/libs/`. The payload is fail-closed and runs its native launcher hooks only in the upstream HYOS launcher process family.

## Source layout and ownership

Native files under `app/src/main/cpp/NativeRules/` are grouped into `resolver/`, `runtime/`, `transport/`, `rules/`, `bridge/`, `support/` and `tools/`; the entry TU and export map stay at the root. Native standalone runners use `app/src/test/cpp/native_test_paths.py` for explicit cross-domain include directories. Source contents and exported ABI are preserved by directory migration.

Native AOT identity: `_kDartSnapshotBuildId` points to the 32-byte GNU note (16-byte header followed by the 16-byte ID), not the ID alone. The upstream Dart resolver validates this note and the mapped executable ranges before feature code reads the target.

`launcher_process_identity.*` owns native process/library admission; `dart_mapped_image.*` owns mapped-ELF bounds and branch checks plus the existing image-resolution ABI. `dart_state_publisher.*` owns publication receipts, per-channel in-flight gates and retry decisions; `broadcast_transport.*` owns public Rust broadcast allocation/consumption, runtime retain/release, share-identity option lifetime and diagnostics. `dart_state_worker.*` owns eventfd start/wake/coalescing/retry/readiness and child-only fd reset; `dart_observer_retirement.*` owns paired observer gating and stable draining before remap repair. Both borrow pointers to the original entry cells. The entry TU retains host symbol/profile binding, process-owner admission/epoch reset ordering, structural remap detection/patch repair, raw callbacks and assembly cells. Carrier/Duo target leases use referential identity through `SignalTargetOwnership`; `SignalHandoverEvents` routes Shade events without the Carrier constructing the Duo renderer. View indexes, animation state and masks retain their own lifecycles.

Dart publication failures retain pending bits and use exponential retry delays from 16 ms up to 5 seconds. Raw observer eventfd writes are drained without resetting the current retry deadline, so repeated observations cannot turn an unavailable publisher into a busy loop. Explicit `Wake`/readiness requests interrupt the backoff through a private urgent flag; they do not alter the assembly-visible input cells. Idle workers still block indefinitely. Validate the production Bionic worker with sustained failure/raw events, prompt explicit recovery, owner changes and fork reset; the detached performance runner preserves baseline/current source hashes and uses ABBA order.

## Structural Dart target resolution

Dart feature targets are resolved **structurally**, not from a per-build table: `dart_image.cpp` (ELF validation, AArch64 decoding, instruction-kind classification, masked patterns) plus `dart_targets.cpp` (one spec per site, tiered evidence) re-derive each address from instruction shape and call-graph relationships, so a launcher update does not need a new table row. `dart_rule_support.cpp` is the Android-only bridge that turns a spec into runtime addresses for a rule; the two rules carry no build id and no RVA. Any site that is not uniquely resolvable is rejected rather than guessed.

Authoring a new hook:
- Declare a `TargetSpec` in `dart_targets.cpp` and a rule that consumes it. Nothing in `miui_home_native_hook.cpp` changes.
- A byte pattern is only usable when it is both **stable across builds and unique**. Verify both: many Dart prologues/epilogues are stock shared code, and a masked pattern is strictly more general than the bytes it masks. A site whose stable prefix is short, or whose match count is large, needs structural evidence instead.
- Use the host probe to develop and regression-test offline; it shares `dart_image.cpp`/`dart_targets.cpp` with the payload, so its verdict is the payload's verdict:
  `clang++ -std=c++17 -O2 -Wall -Wextra -Werror -I resolver -I rules -o /tmp/dart_probe tools/dart_probe.cpp resolver/dart_image.cpp resolver/dart_targets.cpp`
  (run from `app/src/main/cpp/NativeRules`; `rules/` supplies the `*_target.inc` files that `dart_targets.cpp` includes)
  then `dart_probe <libapp.so>` (resolve + report), `--expect site=0xADDR` (assert a known RVA), `--mutate 0xADDR` (fail-closed negative control), `--classes 0xADDR` (dump the instruction-class sequence used for structural specs).
- `dart_runtime_resolver.cpp` stays byte-identical to upstream. `runtime_profile_resolver.cpp` retains the upstream structural algorithms with the fixed-address side-handler fallback removed; the launcher entry path never consults a per-build profile table.
- New launcher artifacts and AOT research: [launcher research manual](../../docs/LAUNCHER_UNPACK_RESEARCH_MANUAL.md). Native target discovery, settings, lifecycle and ABI work: [native hook implementation manual](../../docs/NATIVE_HOOK_IMPLEMENTATION_MANUAL.md). Never add version/build-ID/RVA target tables.

## Native settings delivery

Native settings have two event paths: framework remote preference callbacks and authenticated app-to-SystemUI notifications after acknowledged commits. Native setters derive a complete desired native subset from the last acknowledgement. Ordinary remote commits send only differences against both that acknowledgement and the current editor shadow, so rejected shadow values cannot contaminate a later commit; reset/restore and clear-based rollbacks explicitly repopulate the complete desired subset. Successful native commits reconcile the app-local mirror only when no newer edit is queued. Failed reset or backup restoration replaces the entire map captured on the serialized writer so newly introduced keys cannot survive rollback. Requests use the app acknowledgement ledger, not a possibly uncommitted mutable editor map. `native_rules_revision` is runtime bookkeeping, excluded from portable backups; reset epochs supersede older commits. `NativeSnapshotLedger` rejects stale/conflicting snapshots and carries only bootstrap primitive values across classloader replacement. SystemUI attaches its receiver even while its remote channel is recovering, requests an app snapshot on readiness/boot/unlock and finishes publication after restoring package state. All transport replays/retries are finite; retired publishers close their receivers and queued work. Normal setting changes never invoke native activation or root.

### Native Dock glass

This is the only Dock background style; the old WMS Surface style is retired. It keeps
the phone Hotseat with its own animation, edit, recents and size behavior, and replaces
only the background child with the Launcher's HyperOS 4 glass, wrapped in the Hotseat's
own unlock animation widget so it enters with the icons. The settings key and native
option bit keep their historical `native_pad_dock` names.

`native_dock_glass` matches the phone Hotseat LayoutBuilder's optional background
branch, which appends the factory result only while a captured Rx flag is true. The
captured flag displacement comes from the branch. The `tbnz` skip must land on the
list-without-background move. The factory must be a single allocation whose stub
differs from the glass arm's stub. The glass arm is `_buildStrategy`'s first mode arm.
Its prologue must spill state, mode and the radius as an unboxed double into the
0x20-byte frame. The arm fills radius, tint and opacity only from that frame and the
object pool. The same family also decodes the geometry wrapper:
- The Hotseat return site: the three displaced words after the factory call give the
  child-list and result slots and the list length field. The constraints argument slot
  and `maxHeight` field come from the LayoutBuilder body.
- `EdgeInsets operator +`: the four unboxed frame slots stored to left/top/right/bottom
  in field order, the frame size and the allocation stub's class id.
- The Padding builder fragment: its padding slot must be fed by
  `bl insets_stub; stur x0, [fp, #slot]`, its child call's return PC must store the
  child, and the Padding class id comes from its allocation stub. Of several insets
  candidates (EdgeInsets and EdgeInsetsDirectional), only the one whose stub feeds a
  Padding fragment is accepted.
- The item builder (`_buildItems`, found from the closure's `ldur x6, [fp, #S]; bl`
  call): the Dock center call is a switchable call through a pool pair whose receiver
  is the context field that the x6 spill feeds and that the closure slot `S` holds
  (stored only before the gate). The fragment after it gives the item slot, center slot
  and info field it displaces. The unlock wrapper allocation (class id distinct from
  Padding and EdgeInsets) must store child, position, kind 2 (hotseat), fixed `true`
  and screen −1, then the title (field 0x2b) from its slot, followed by a pool load.
  No branch may land inside either 16-byte window; the frame size, child slot and
  position slot come from these stores.

The probe resolves on 9239 (center 0x10dcd40, wrap 0x10dcf98) and 9226 (0x11047ac,
0x1104a04). It rejects mutations of the branch shape, flag
operand, skip target, factory call, strategy prologue and glass stores, and of the
insets field store, insets slot load, padding slot store/load, child store, the
return-site resume words, the context capture field, the center receiver field and
call register, the wrapper kind, fixed flag and title fields, the controller argument
register, a controller slot rewritten after the gate, a branch into either window, and the item-cell flag store or its `true` value.

`native_dock_refresh` finds the scheduler's successful
return only when the normal and exceptional exits restore the same idle phase and clear
the same timestamp on the saved receiver. It derives the root and build-owner fields
from the original Flutter reassemble bridge and requires the draw path to read both
fields in the same roles. The bridge's original calls identify the Element entry.
It rejects a duplicate frame or changes to the frame return, exception cleanup, root
field or Element dispatch.

`native_dock_route` and `native_dock_interaction` are still resolved, but none of their
sites are patched. They supply the HomePage binding, DeviceConfig form-factor fields
and drag guards that the idle admission check reads.

Five glass gates and one scheduler idle shim install as a bank. The branch gate
forces the background child only for the selected isolate. The factory gate acts only
on the verified Hotseat call's return PC; every other path tail-calls the stock
allocation stub. No module PC is ever on the Dart stack, and native code runs only
between host fragments:
1. Factory gate: reads `maxHeight` from the LayoutBuilder constraints, computes the
   insets, opens an `EdgeInsets +` frame (tagged slots null, unboxed slots filled) and
   enters its allocation. That returns to the Hotseat call's return site.
2. Return gate, stage 1 (same thread, result class EdgeInsets): opens the Padding
   builder frame returning to the Hotseat site with the insets in its live padding
   slot, then the glass strategy frame returning into the Padding child call, and
   enters the glass arm.
3. Return gate, stage 2 (result class Padding): opens an item builder frame whose
   saved PC is the Hotseat site, keeps the Padding in its child slot (every other
   tagged slot null), and performs the center call on the captured controller with
   the genuine center-call return PC.
4. Center gate, stage 3, only in that owned frame (its saved PC is the Hotseat site):
   stores the center Offset in the position slot and enters the wrapper allocation.
   A non-object center returns the plain Padding instead. Every other frame replays
   the displaced words.
5. Wrap gate: always replays the title store. At stage 4 in the owned frame it clears
   the wrapper's item-cell flag (field 0x27, stored `true` by the host), pops the
   frame and returns the wrapper to the Hotseat site, where the return gate resumes
   the Hotseat code. Any other stage or result replays and resumes.

The flag matters: with it set, `_UnlockWidgetState.isCurrentScreen` needs item info
(container −101…−106) or a matching screen id, so a wrapper without info logs
`canRunUnlockAnim, null, false, …` and never animates. Cleared, it counts as current
screen, and `_resolveCellLocationInfo` falls back to the wrapper's position (the Dock
center) for kind 2, the same supported path the indicator and search bar wrappers use.

Geometry is relative to the Hotseat LayoutBuilder area (about 102 dp on HyperOS 4
phones): left = right = margin, bottom = bottom offset, top = max(0, maxHeight −
bottom − height). Height 0 fills the area, and an unbounded maxHeight (≥ 4096) gets
no top inset. The setting `launcher_native_dock_glass` packs height | margin << 8 |
bottom << 16 | radius << 24; every byte stays below 128 except bit 7 of the height
byte, the adaptive width flag (`kNativeDockGlassAdaptive`, Kotlin `ADAPTIVE_BIT`), and
the Kotlin `NativeDockGlassGeometry.DEFAULT` must equal the native `kNativeDockGlassDefault`. It
travels in the native rule snapshot (`hypertweak_rule_dock_glass`, absent or malformed
= default), is persisted in its own checksummed `dock_glass` record only when it
changes, and is restored with the settings record at bootstrap. A change while the
glass is selected sets the recovery flag, so the next idle frame rebuilds once. The
factory gate records the last `maxHeight` and the center gate the last center; the
idle log prints both. The settings page preview mirrors these insets on a measured
area (`NativeDockGlassPreviewGeometry`), which is never sent to the Launcher.

Adaptive width (optional contract, off by default). The phone Hotseat keeps a fixed
cell (`GridController.dockCellWidth`) and centers fewer icons, so with the flag set the
factory gate uses the default full-row margin (the packed margin is ignored) and adds
(columns − max(n, 1)) × cell / 2 to both sides, capped at half the width: an empty row
keeps one cell, n ≥ columns or unbounded width keep the margin. The cell is read the way
the item builder computes it: static table `[THR + table]`, a flag holder static whose
bool field is tested with the host's own `tbnz #4` (true → `config.cell + extra`, false →
`config.alt`), and `config` = the `currentConfig` holder static → Rx field → Rx value. A
null, sentinel (`pp[sentinel]`) or non-object at any step, or a cell outside
(0, maxWidth), keeps the margin. The decoder takes, from the Hotseat builder, the
`ldur xC, [fp, #slot]; ldur w5, [xC, #items]; add x5, x5, heap` load before the item
builder call (followed only by frame reloads, the slot written only before the gate) and
the single `ldur d, [x, #maxWidth]; fmov d, #columns; fdiv` (an integer 3–6). The item
builder must spill x5, publish it into its own context and iterate it with the resume
length field, and contain exactly one `kCell` sequence (flag load, `tbnz #4`, two
`currentConfig` calls on the same receiver, `fmov #extra`, `fadd`, the alt load, then a
halved add to the item x); `currentConfig` must be the static-or-find getter whose Rx
getter rejects the sentinel. Any mismatch leaves columns 0 and the glass at fixed width,
logged once. At run time the list must have the child list's class id and a Smi length.
9239 and 9226 both decode slot −8, field 0x13, 4 columns, table 0x78, flag 0x27c0/0xe7,
config 0x2828 → 0x2f → 0x13, cell 0x77 + 20, alt 0x2b (the device measures a 94.4 dp
pitch). Mutations of the fmov, fdiv, maxWidth load, x5 load/add, argument reloads, a
post-gate slot store, the builder spill, publish and length field, and of every structural
word of `kCell` and its static prelude disable it; operand-only flips re-decode
consistently. The factory also records maxWidth, n and the cell, and the wrap gate's host
path records the first and last item unlock center x since that factory (integer
registers only); the selection log prints them. The glass width changes when the
Hotseat rebuilds after an item change; it does not animate, and during a drag the host
keeps the full-row layout until the drop.

The idle callback requires a live phone HomePage and inactive drag and drop guards. It
then sets the isolate-local selection and tail-transfers to the host's Element
reassemble with the original caller PC. Disable restores stock on the same boundary.

## Native binary upgrade

The hot-reload dialog includes a separate native-update action requiring root and an up-to-date SystemUI Java target. `NativeUpgradeManager` compares the actual payload hashes in the signed mapped/installed APKs. HYOS may label its root spawner `usap64`; ownership is established from the launcher's parent, exact executable, uid and `/proc` start time, not its displayed name or a ROM table. A changed native image is activated by guarded spawner replacement following upstream `abe7d4d6f880422d7faef973bd9c90457d662495`. Verification checks the new process family, current APK inode mapping and the native `runtime_identity` record (version/pid/start time/checksum). This is a launcher-process refresh, not same-process `.so` unloading. The previous complete signed APK is retained for explicit PackageManager rollback with data preserved. Root IO, output sizes and activation waits are bounded; an interrupted activation is reported after reopening. Temporary mapped APK copies are removed on inspection failure, and successful process checks are not restored as current facts from the journal. Rollback always resolves the current spawner afresh and records failures. Code loading/initialization does not prove native feature UI acceptance.

## Android widget stack

The Android-widget-stack rule (`widget_stack`) resolves the launcher's paired eligibility gates from one Dart image contract: the drag-side and target-side MIUI-flag reads must agree with a third site, the AppWidget serializer, on both the model field displacement and the class ID. Each finder re-derives the whole family, so a relocated or recompiled gate is accepted while a partial or ambiguous one is rejected. Two passive shims replace the conditional branches that carry the flag decision: while the setting is on and preparation succeeded they resume at the native success block, otherwise they replay the stolen words and continue to the native rejection block. The shims never write the model's MIUI bit and never touch span equality, the 1x1 rule, the ten-card capacity, gadget/serviceDelivery exclusion, or the HyperOS4/PA gates, and they preserve every general-purpose, SIMD, SP and NZCV value. Disabling keeps the shims installed and merely clears the request/ready pair, so no executing trampoline is ever freed; already-created stacks remain ordinary native containers. Test the structural contract with `widget_stack_target_test.cpp` and the production assembly offline with `run_widget_stack_abi_test.py` (Unicorn + pyelftools, Android NDK clang); `run_dart_rule_cache_test.py` covers its preparation deferral, paired install, remap repair and foreign-patch rejection. Static and offline verification is not manual drag acceptance, and provider binding, RemoteViews rendering, card paging and dissolve/restart recovery remain manual device checks.

## OS4 Android widget to assistant

The OS4 Android-widget rule resolves a unique eligibility/serializer family from the current Dart image, deriving the provider flag displacement and AppWidget CID. Its assembly replays the stolen instructions and bypasses the MIUI identity and size eligibility rejection only for ordinary AppWidgets of that CID. It derives and verifies the native success continuation; model dimensions/identity, Bundle metadata, placement, and drop acknowledgement remain stock. MIUI providers and other card types keep their size policy. It uses the same preparation/cache/settings/remap lifecycle and restores its owned hook when disabled. Test the structural contract with `assistant_widget_target_test.cpp` and the actual assembly offline with `run_assistant_widget_abi_test.py` (Unicorn + pyelftools, Android NDK clang). Current 7722 static/build verification is not manual drag acceptance.

## Back gesture 0.13.0 native extensions

`dart_runtime_resolver.cpp` stays byte-identical to upstream `c4a39e0`.
The optional NavLongPress selector preserves the native Dart action setting and
replays the resolved frame/register contract; module JNI remains the sole writer
of the contextual-search switch. Physical cancellation is observed before native
redirection, through the uniquely proved id/action/ack/manager dispatch graph.
Saved event/receiver/token registers are decoded by operand role, including the
current compiler's direct-self capture. The native Fn completes its own state;
never replace its marker with an offset write or reinterpret its callback as cleanup.
XiaoAi supports both direct and owned-string-adapter graphs, rejecting ambiguity.

The optional SfAnim start rewrite decodes geometry from the entry log argument
array, validates every direct caller's parameter copy, and decodes app-vector and
task-ID fields from the same native task comparison. SystemUI's private handoff
binds UID, task, arbiter generation, monotonic sequence and expiry. Parse borrowed
Intent data before forwarding the OEM receiver. Independent-module native ownership
protection still covers these extensions. Native payload activation needs the guarded
HYOS process refresh; Java hot reload cannot replace it.
