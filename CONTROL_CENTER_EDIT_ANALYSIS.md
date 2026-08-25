# Control Center Edit Drag Analysis

This file documents how the OS4 编辑与排序 editor's drag machinery works in
`miui.systemui.plugin` (verified against the OS4.0.0.15.XPMCNXM decompile in
`/Users/ink/developer/reverse/cache/systemui-plugin-7a0dfbe892f55839/jadx/sources`,
class names confirmed identical in the newer `miui-systemui-plugin-current` dex)
and why the `ControlCenterCardsEditHooker` fixes behave the way they do.

## How the editor drag works (stock)

- `MainPanelAdapter` builds one `MainPanelAdapter$itemTouchHelper$1` per panel
  instance — a `SpringItemTouchHelper` (patched androidx `ItemTouchHelper`)
  wrapping the anonymous `object : ItemTouchHelper.Callback()`
  (`MainPanelAdapter$itemTouchHelper$2`). The callback decides drag eligibility in
  `getMovementFlags(RecyclerView, ViewHolder)`:

  ```
  isLongPressDragEnabled()                          // mode != NORMAL && !mainPanelScrolling
  && viewHolder is MainPanelItemViewHolder
  && viewHolder.getDraggable()
  && !recyclerView.isAnimating()
  → DRAG_FLAG = makeMovementFlags(15, 0)
  ```

- `onMove(recyclerView, sourceHolder, targetHolder)` reads the **source holder's
  owner** (`MainPanelContent`) and calls `owner.moveElement(sourceItem, targetItem)`.
  Only `QSListController.moveElement` overrides it (moves within `addedTiles`,
  then `distributor.notifyChanged(...)` → next main-loop iteration →
  `adapter.notifyChanged` → `distributeContent` + DiffUtil). Every other content
  inherits the interface default `false`, so a cross-content move snaps back.

- Drag end: `SpringItemTouchHelper.select(null, 0)` calls the subclass's
  `onStopDrag(holder)` first, then runs the Folme recover animation, whose
  `onAnimationEnd` calls the callback's inherited `clearView(recyclerView, holder)`
  (skipped if the settle is interrupted).

## Root causes fixed (2026-08-23)

1. **Cards could not be lifted (拖不动).** The only writer of the holder's
   `draggable` flag is `QSRecord.updateDraggable()`, which forces `false` for card
   records (`isCard`) and is **never invoked for the non-QSRecord fixed contents**
   (media/brightness/volume/devicecenter items are plain `MainPanelListItem`s whose
   `updateMode` is a no-op). Hooking `setDraggable` therefore missed those sections
   entirely, and even the big cards only got the write on the bind *after* the
   holder's own `mode` field was updated — a stale-NORMAL race on the first EDIT
   bind. Fix: hook `MainPanelItemViewHolder.getDraggable()` (the single gate the
   callback consults) and force `true` when the holder's owner is one of the five
   fixed sections and the holder is in EDIT mode. qslist keeps managing its own
   flag, so unadded pool tiles stay non-draggable (tap-to-add preserved).

2. **Tiles could not squeeze cards (用 qstile 挤不动).** Stock `onMove` delegates to
   the source owner, and `QSListController.moveElement` only accepts targets inside
   `addedTiles` — a tile dragged onto a card returned `false` and snapped back. The
   hooker additionally rejected `qslist`→card moves. Fix: all cross-section moves
   whose endpoints are both managed (the five cards + `qslist`) now go through the
   section-reorder path — dragging a tile onto a card moves the tile grid to the
   card's slot (挤压), dragging a card onto the grid does the reverse.

3. **Pending orders were never committed.** `clearView` is inherited from
   `ItemTouchHelper.Callback`, not declared on `MainPanelAdapter$itemTouchHelper$2`,
   so the old `declaredMethods` lookup silently returned null and no hook was
   installed. Fix: commit on `MainPanelAdapter$itemTouchHelper$1.onStopDrag`
   (declared, scoped to this panel's helper, fires on every drag end before the
   settle), falling back to `clearView` resolved through `Class.getMethods()`.
   `commitPendingOrders` now resolves the owning adapter from the callback/helper
   instance first and bails out for any other `ItemTouchHelper.Callback` in the
   process, so the fallback's process-wide hook is a no-op elsewhere.

4. **Adapter refreshes are deferred.** The native tile mover posts its
   `notifyChanged` to the next main-loop iteration; the hooker now mirrors that via
   a main-thread `Handler` instead of dispatching DiffUtil updates synchronously
   inside `ItemTouchHelper`'s own event handling.

5. **Section ordering keeps chrome pinned.** The `contentMap` also contains
   non-movable entries (edit button, dividers, footers, header/footer spaces).
   `applySectionOrder` now pins each of those to its current slot and fills the
   remaining slots with the ranked managed sections, instead of pushing all
   unmanaged entries to the end.

## Follow-up fixes (2026-08-23, round 2)

On-device after round 1: dragging and 挤压 work, but the order **snapped back on
release** and the interactive cards still **responded to taps** in EDIT mode
(dragging brightness/volume adjusted them). Two root causes:

1. **Commit wrote asynchronously, re-apply read the stale value.** `Preferences.putString`
   queues the daemon write on `serializedWriter`; the settle refresh posted right after
   commit re-read `KEY_CC_MAIN_CONTENT_ORDER` before the write landed, got the old (empty)
   value, and `applySectionOrder` left the map in stock order — the visible "snap back".
   Fix: `commitPendingOrders` keeps a session cache (`committedContentOrder` /
   `committedCardOrder`) that `storedContentOrder()`/`storedCardOrder()` consult first, and
   calls `Preferences.flush()` before the final `postRefresh`, so both the immediate re-apply
   and any later redistribute see the committed order.

2. **Fixed contents are interactive views.** The brightness/volume sliders adjust on touch
   (`VerticalSeekBar.onTouchEvent`), the media card has root click/long-click + play/next
   buttons, the big cards toggle on tap (`QSCardItemView` click). In EDIT mode all of those
   must be inert. Fix: an after-hook on `MainPanelAdapter.onBindViewHolder` sets a consuming
   `OnTouchListener` (`{ _, _ -> true }`) on the item view of holders whose owner is one of
   the five fixed sections while the holder's mode is EDIT, and clears it back on NORMAL
   binds. The long-press drag is unaffected: ItemTouchHelper observes the pointer through the
   RecyclerView's `OnItemTouchListener`/GestureDetector before child dispatch, and the
   blocker never calls `requestDisallowInterceptTouchEvent` (so it cannot cancel a drag the
   way the slider's own disallow would). qslist tiles keep their tap-to-add/remove behavior.
   RecyclerView scroll over a card still works — the RV's scroll interception happens before
   child dispatch too.

## Follow-up fixes (2026-08-23, round 3)

On-device after round 2: the 设备中心 card seemed impossible to reposition and touching it made
the whole editor jump; dragging cards across regions (大卡放小卡的区域) scrambled the whole grid
and the dragged card could not be steered back. Two root causes, both in the move model:

1. **Whole-section teleporting on every onMove.** `handleOnMove` did
   `order.add(to, order.removeAt(from))` — moving the source section all the way to the target's
   slot. With a full-width section (设备中心, span 4) that is a whole-row jump re-flowing the
   entire grid; and when the drop target flipped between two boundary sections (finger hovering
   at a section boundary), the order oscillated between two arrangements on consecutive onMove
   calls — the interface visibly jumping back and forth and the dragged card becoming
   uncontrollable ("拖不回去"). Fix: the cross-section branch now moves the source section one
   slot toward the target per onMove (swap with the immediate neighbor; when already adjacent
   there is nothing to reorder — the source sits at the target's boundary — so the move is
   consumed without touching the order). This converges monotonically, keeps every refresh to a
   single item move like the native tile editor, and never oscillates. The top-card branch
   (swapping the two big cards inside 大卡片) keeps its direct swap — a one-position move by
   nature.

2. **The edit-mode touch blocker clobbered host touch listeners.** `ScaleItemViewHolder`
   holders (设备中心, sliders) install *themselves* as `itemView`'s OnTouchListener in their
   constructor (`DeviceCenterEntryViewHolder` line 96). Round 2's blocker did
   `setOnTouchListener(if (editing) blocker else null)` — leaving EDIT permanently removed the
   holder's own listener (press-scale animations gone in the NORMAL panel). Fix: the blocker
   now snapshots the current listener via reflection
   (`View.getOnTouchListener`, hidden-API-exempt inside SystemUI) into a WeakHashMap on the
   first block and restores it when leaving EDIT; a sentinel marks "host had none".

`compileDebugKotlin`, `testDebugUnitTest`, `lintDebug`, `assembleDebug` pass. On-device
verification is the user's (SystemUI restart or hot reload needed for the new hooks):
dragging 设备中心 should now reposition it one row at a time with no whole-panel jumping,
cross-region card drags should converge instead of scrambling, and the 设备中心/滑块 press
animations should still work after leaving the editor.

## Follow-up fixes (2026-08-23, round 4)

On-device after round 3: dragging now converges, but once big cards end up **mixed among the
tiles** the grid display stays scrambled. Root cause — the editor's item animator:

- `MainPanelContentDistributor.handleNotifyChanged` calls `suppressItemAnimator(false)` whenever
  the mode is EDIT, so the `ControlCenterItemAnimator` is **unsuppressed for the whole edit
  session** (that is what animates pool-tile add/remove).
- `ControlCenterItemAnimator.animateMove` → `ControlCenterViewHolder.prepareMove` applies each
  move's delta to the holder's **current** translation (`getHolderTransX() + i6`, then animates
  back to 0). During a drag our refreshes dispatch up to ~20 `notifyItemMoved` at once (section
  swaps re-flow the whole grid), and the next refresh interrupts those Folme moves mid-flight;
  the next `prepareMove` then adds its delta to a half-animated translation → views end up
  **stuck at wrong translations** = the static scramble (same translation state is also used by
  the ItemTouchHelper drag spring, so the two Folme animations fight on the held view).

Fix: `refreshAdapter` suppresses the adapter's `ControlCenterItemAnimator`
(`setSuppressAnimation(true)` via the adapter's `recyclerView.itemAnimator`, resolved once)
around every drag-driven `notifyChanged`, so moves apply instantly and nothing accumulates;
`commitPendingOrders` re-enables it 400 ms after the drag ends (only if the panel is still in
EDIT and the same drag is still the latest — a `dragGeneration` counter guards quick re-drags).
The stock path re-suppresses on leaving EDIT (`onStop`/`onStart`). Pool-tile add/remove
animations return after each drag via the delayed restore.

`compileDebugKotlin`, `testDebugUnitTest`, `lintDebug`, `assembleDebug` pass. On-device
verification is the user's: big cards moved among the tiles should now stay put and render
correctly, with the grid following the finger instantly during the drag.

## Follow-up fixes (2026-08-23, round 5)

On-device after round 4: the one-slot-at-a-time cross-region move felt bad (reverted), moved
big cards became inoperable, and placing sections with the tile grid still produced layout
errors. Changes:

1. **Cross-section move reverted to direct placement.** `handleOnMove` again does
   `order.add(to, order.removeAt(from))` — the dragged section goes straight to the target's
   slot. The animator suppression (round 4) stays, so the re-flow applies instantly instead of
   animating ~20 moves; that animation churn — not the teleport itself — was what visually
   scrambled the grid and made the order look like it oscillated.

2. **Inoperable reordered cards — residual transforms.** The interrupted move animations left
   holders with non-zero translationX/Y (round-4 analysis). A view that *renders* offset but
   whose *touch bounds* stay at the layout position makes taps land nowhere — "操作不了".
   Fixes: the round-4 suppression prevents new animation state during drags, and
   `commitPendingOrders` now also runs a delayed `clearResidualTransforms` sweep over the
   adapter's `attachedHolders` calling `ControlCenterViewHolder.endAnimation()` (resets
   translation/alpha/scale to rest) after the drag settles — guarded by the same
   `dragGeneration` check so a quick re-drag is not clobbered.

3. **Layout error with qstile — same root cause.** Mixed spans (span-2 cards among span-1
   tiles) flow correctly in the GridLayoutManager (span caches are invalidated on moves,
   `onItemsMoved` L782); the "布局错误" trace to the interrupted-move translations too.
   The suppression + sweep above are the fix. If it still reproduces on this build, the user
   will describe the exact appearance, since the static grid math checks out in the decompile.

`compileDebugKotlin`, `testDebugUnitTest`, `lintDebug`, `assembleDebug` pass. On-device
verification is the user's (SystemUI restart or hot reload needed for the new hooks).

## Follow-up fixes (2026-08-23, round 6)

On-device after round 5: (1) dragging big cards to sort them inside 大卡片 had **no sliding
animation** — the cards just switched places; (2) when big cards mixed with the tile grid, the
card section could end up **past the whole grid, below the 未添加 pool**; (3) after committing a
reorder, the 播控中心 / WiFi / 流量 big cards **could not be tapped at all** in the normal panel.
Three root causes:

1. **Big-card swaps were force-suppressed.** `refreshAdapter` unconditionally called
   `ControlCenterItemAnimator.setSuppressAnimation(true)`, so even the single two-card swap inside
   大卡片 applied instantly (`dispatchMoveFinished`, no `prepareMove`). Native tile moves animate
   exactly this way (one position per onMove, `prepareMove` spring-slides the displaced tile).
   Fix: `postRefresh(adapter, animate)` — the intra-大卡片 branch passes `animate = true`
   (un-suppresses, so the two cards slide), cross-section re-flows keep `animate = false`
   (suppression stays — the round-4 scramble only ever came from ~20-move whole-grid re-flows
   fighting the Folme animation, not from a single swap pair).

2. **Off-by-one in downward cross-section placement.** `order.add(to, order.removeAt(from))`
   inserts at index `to` *after* the source is removed, so for a downward move (from < to) the
   dragged section lands **one slot past the target**. With `qslist` as the target that put the
   whole card section *after* the grid — below the pool — "大卡掉落到最底下没加进开关的区域".
   Fix: `insertAt = if (from < to) to - 1 else to`. After removal the target's slot is at
   `to - 1`, so the dragged section lands at the target's actual slot; an already-adjacent
   downward move collapses to a no-op (the source already sits at the target's boundary).
   The intra-大卡片 swap keeps plain `add(to, removeAt(from))` — for a same-size sequence swap
   that is the correct "dragged card takes the target card's slot" semantics.

3. **The edit-mode touch blocker survived leaving EDIT.** The blocker was applied/restored only on
   the plain `onBindViewHolder(holder, position)` bind, but leaving EDIT
   (`MainPanelModeController.set_mode` → `distributePanels` + `handleNotifyChanged` →
   `adapter.notifyChanged`) dispatches **payload-only** binds: DiffUtil's `areContentsTheSame`
   returns false for every item (`!modeChanged`), so it sends `notifyItemRangeChanged(pos, Mode)`
   and the plain 2-arg bind never runs for the attached holders. The consuming `OnTouchListener`
   stayed on the managed item views → taps died (播控中心/WiFi/流量 unopenable). Fixes:
   - `installEditTouchBlockHook` now hooks the typed 3-arg
     `onBindViewHolder(MainPanelItemViewHolder, int, List)` first (falling back to the 2-arg):
     RecyclerView always binds through the 3-arg path, and by the time the after-hook runs the
     holder's `mode` field was already updated (`owner.updateMode` in both the empty-payload
     super-chain and the Mode-payload branch), so the blocker is applied on entering EDIT and
     restored on leaving it in every bind path.
   - `installModeSweepHook` — a mode-driven sweep on `MainPanelAdapter.notifyChanged` that runs
     exactly when a refresh changed the mode to NORMAL (`getModeChanged()` && mode == NORMAL):
     restores the saved host touch listeners on every attached managed holder and calls
     `endAnimation()` on all attached holders to clear residual move translations. This catches
     any stale state regardless of bind-path surprises, and cleanup is unconditional so a
     mid-session master-switch toggle cannot strand it.

`compileDebugKotlin`, `testDebugUnitTest`, `lintDebug`, `assembleDebug` pass. On-device
verification is the user's (SystemUI restart or hot reload needed for the new hooks): dragging a
big card should now slide the displaced card like a native tile move; dropping a big card onto the
tile grid must place it at the grid's boundary (never past the pool); and after leaving the
editor the 播控中心 / WiFi / 流量 cards must toggle/open normally again.

## Runtime state model

- Section order: `KEY_CC_MAIN_CONTENT_ORDER` (comma-separated
  `qscards,media,brightness,volume,devicecenter,qslist`). Empty = follow system.
  Session override while dragging, committed on drag end.
- 大卡片 internal order: `KEY_CC_TOP_CARD_ORDER` (WiFi/cellular specs), applied by
  the `QSCardsController.getListItems()` after-hook.
- All callbacks re-read `KEY_CC_EDIT_ENABLED` live; the hooker itself only attaches
  at plugin load, so the master switch still needs a SystemUI restart to turn on.