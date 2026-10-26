package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.DynamicHooker
import java.lang.reflect.Field

/**
 * Control-center editor cards (控制中心编辑增强).
 *
 * The OS4 编辑与排序 editor is the main control-center panel switched into
 * `MainPanelController.Mode.EDIT`; the same [MainPanelAdapter] renders it. Stock behavior keeps
 * five fixed contents out of the editor and makes them un-draggable:
 *
 * - Visibility: each fixed controller's `available(boolean)` returns false while
 *   `MainPanelModeController.mode == EDIT`, so `MainPanelContentDistributor.distributePanels`
 *   drops them from the adapter content lists. The availability hooks force them back only in
 *   EDIT mode, leaving the NORMAL panel untouched.
 * - Draggability: `ItemTouchHelper.Callback.getMovementFlags` requires
 *   `MainPanelItemViewHolder.getDraggable()`. Only `QSRecord.updateDraggable()` ever writes that
 *   flag — and it forces false for every card record (`isCard`) and is never called for the
 *   non-QSRecord fixed contents at all, so their holders stay draggable=false forever. The
 *   getDraggable hook forces the read to true for managed-section holders in EDIT mode — the
 *   single gate every fixed section (big cards, media, brightness, volume, device center) must
 *   pass to be liftable. qslist records keep managing their own flag (unadded pool tiles stay
 *   non-draggable by design, so they keep their tap-to-add behavior).
 * - Moves: the stock callback delegates every move to `owner.moveElement(item, target)`, whose
 *   interface default returns false; only `QSListController.moveElement` (plain tiles) overrides
 *   it. The onMove hook intercepts moves whose endpoints belong to the managed sections, records
 *   a section-level order change, and refreshes through the adapter's own
 *   `notifyChanged(false, false)` — the same DiffUtil-based redistribute path the stock tile
 *   mover uses, so mid-drag updates animate exactly like native tile moves. The order is held as
 *   a session override while dragging and committed to [Preferences] when the drag ends
 *   (`SpringItemTouchHelper`'s `onStopDrag`), so intermediate hover positions never spam
 *   cross-process preference writes. The committed order is also kept in a session cache and the
 *   daemon write is flushed before the final settle refresh, so the re-apply right after commit
 *   never reads the stale pre-write value (which previously snapped the layout back on release).
 *
 * While the panel is in EDIT mode the five fixed holders' item views consume all touches, so the
 * interactive contents (sliders, media buttons, card toggles) are inert and cannot be misfired
 * when lifting them for a drag; the long-press drag itself is unaffected because ItemTouchHelper
 * observes the pointer at the RecyclerView level, before child dispatch. The host's own touch
 * listener (ScaleItemViewHolder holders install themselves in their constructor) is saved on the
 * first block and restored when leaving EDIT, so the NORMAL panel keeps its press animations.
 * The blocker is (re)applied on the payload bind (`onBindViewHolder(holder, position, payloads)`)
 * as well as the plain bind, because leaving EDIT dispatches payload-only binds — a plain-bind-only
 * hook would leave the consuming listener stuck on the card views (the "reordered cards can't be
 * tapped" bug). A mode-driven sweep on `MainPanelAdapter.notifyChanged` additionally restores the
 * saved listeners and clears residual holder transforms exactly when a refresh switches the panel
 * to NORMAL, so no bind-path surprise can strand either stale state in the normal panel.
 *
 * Drag-driven refreshes suppress the editor's ControlCenterItemAnimator only for cross-section
 * re-flows: while EDIT mode is active the distributor keeps the animator unsuppressed (so the
 * pool-tile add/remove animates), and its move animation applies each move's delta to the holder's
 * *current* translation (`getHolderTransX() + delta`), so a stream of interrupted whole-grid moves
 * (constant during a section drag) leaves views stuck at wrong translations — the grid scrambling
 * once big cards sit among the tiles. Suppressed cross-section refreshes follow the finger
 * instantly; the animator is re-enabled shortly after the drag ends. A big-card swap inside
 * 大卡片 is the exception: it is a single move pair, exactly what the stock tile mover animates
 * safely, so its refresh keeps the animator on and the two cards slide to their new slots.
 *
 * Order model: sections are atomic units ranked by a comma-separated key list
 * ([SystemUiSettingsKeys.KEY_CC_MAIN_CONTENT_ORDER]; keys `qscards,media,brightness,volume,devicecenter`
 * plus `qslist` for the tile grid). An empty stored value means "follow the system" — the map is
 * left in stock priority order until the user drags something for the first time. Dragging
 * places the dragged section at the target's slot: for a downward move the insert index is the
 * target's slot *after* the source is removed (`to - 1`), so the section never lands one slot
 * past the target — with the tile grid as the target that used to drop the whole card section
 * below the grid, past the 未添加 pool (大卡掉落到最底下没加进开关的区域). Within 大卡片, swapping
 * the WiFi/cellular cards reorders their specs through [SystemUiSettingsKeys.KEY_CC_TOP_CARD_ORDER].
 */
class ControlCenterCardsEditHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingCcResizeEnabled by hookSetting(false) { Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_RESIZE_ENABLED, false) }
    private val settingCcEditEnabled by hookSetting(false) { Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, false) }
    private val settingCcMainContentOrder by hookSetting("") { Preferences.getString(SystemUiSettingsKeys.KEY_CC_MAIN_CONTENT_ORDER, "") }
    private val settingCcTopCardOrder by hookSetting("") { Preferences.getString(SystemUiSettingsKeys.KEY_CC_TOP_CARD_ORDER, "") }

    private val editor = ControlCenterEditorAccess { it.toClassOrNull() }

    private companion object {
        const val TAG = "HyperTweak"
        val SECTION_KEYS = listOf("qscards", "media", "brightness", "volume", "devicecenter")
        val MANAGED_SECTIONS = (SECTION_KEYS + "qslist").toSet()
        const val ADAPTER_NAME = "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter"
    }

    /** Section order applied while a drag is in flight; committed to prefs in clearView. */
    @Volatile
    private var pendingContentOrder: List<String>? = null

    /** Top-card spec order applied while dragging inside 大卡片; committed in clearView. */
    @Volatile
    private var pendingCardOrder: List<String>? = null

    /**
     * Section order committed to [Preferences] this session. The daemon write is asynchronous,
     * so a re-apply right after commit would read the stale (empty) value and snap the layout
     * back; these caches make the committed order visible to [applySectionOrder] immediately.
     */
    @Volatile
    private var committedContentOrder: String? = null

    /** Top-card spec order committed this session (see [committedContentOrder]). */
    @Volatile
    private var committedCardOrder: String? = null

    /**
     * Suppresses the adapter's ControlCenterItemAnimator around drag-driven refreshes (see
     * [editor.resolveAnimatorSuppressor]); resolved lazily in installMoveHooks.
     */
    private var suppressAnimator: ((Any, Boolean) -> Unit)? = null

    /** The adapter's `mode` field, read when re-enabling the animator after a drag. */
    private var adapterModeField: Field? = null

    /** Delay before re-enabling the editor's item animations after a drag ends. */
    private val animatorRestoreDelayMs = 400L

    /** Bumped on every consumed move; a stale animator-restore only fires for the same drag. */
    @Volatile
    private var dragGeneration = 0L

    /** The adapter's GridLayoutManager, whose span lookup must be invalidated after reordering. */
    private var layoutManagerField: Field? = null

    /** Main-thread handler used to defer adapter refreshes out of ItemTouchHelper's event loop. */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onHook() {
        installEditVisibilityHooks()
        installDraggableHook()
        installEditTouchBlockHook()
        installPriorityHooks()
        installCardSpanHook()
        installContentOrderHook()
        installMoveHooks()
        installTopCardOrderHook()
        installModeSweepHook()
    }

    /**
     * The host rebuilds the content list from child-controller priorities on every SystemUI
     * startup. Reordering only the adapter map is transient, so override this stable ordering
     * boundary from the persisted module order as well.
     */
    private fun installPriorityHooks() {
        val names = listOf(
            "miui.systemui.controlcenter.panel.main.qs.QSCardsController",
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerController",
            "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController",
            "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController",
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController",
            "miui.systemui.controlcenter.panel.main.qs.QSListController"
        )
        var installed = 0
        names.forEach { name ->
            val cls = name.toClassOrNull() ?: return@forEach
            val getter = CompatibleMethodResolver.find(
                cls, "getPriority", returnType = Int::class.javaPrimitiveType
            ) ?: return@forEach
            getter.hook {
                before { param ->
                    if (!enabled()) return@before
                    runCatching {
                        val order = storedContentOrder()
                        if (order.isEmpty()) return@runCatching
                        val key = editor.contentKey(param.thisObject)
                        val index = order.indexOf(key)
                        if (index >= 0) param.result = 10 + index * 10
                    }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: priority", t) }
                }
            }
            installed++
        }
        Log.i(TAG, "ControlCenterCardsEdit: priority hooks installed=$installed")
    }

    /**
     * Legacy stopgap: keeps the WiFi card at the compact 1-col span. Yielded entirely once
     * [SystemUiSettingsKeys.KEY_CC_RESIZE_ENABLED] is on — the size hooker then owns every card span from
     * its own configuration (an unconfigured spec falls back to the system span).
     */
    private fun installCardSpanHook() {
        val record = "miui.systemui.controlcenter.panel.main.qs.QSRecord".toClassOrNull()
            ?: return
        val spec = CompatibleMethodResolver.find(record, "getSpec") ?: return
        val span = CompatibleMethodResolver.find(
            record, "getSpanSize", returnType = Int::class.javaPrimitiveType
        ) ?: return
        span.hook {
            before { param ->
                if (!enabled()) return@before
                if (settingCcResizeEnabled) {
                    return@before
                }
                runCatching {
                    val tileSpec = spec.invoke(param.thisObject)?.toString()?.lowercase() ?: return@runCatching
                    if (tileSpec == "wifi" || tileSpec == "wifi1" || tileSpec == "wifi2") {
                        param.result = 1
                    }
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: card span", t) }
            }
        }
        Log.i(TAG, "ControlCenterCardsEdit: card span hook installed")
    }

    /**
     * Accepted-snapshot refresh. The editor hooks are installed for the plugin session and read
     * the switch in every callback; this pass tracks the live adapter and asks it to re-distribute
     * now, so a first enable applies to the panel that is already laid out instead of waiting
     * for the next mode switch. A disable additionally restores the host touch listeners the
     * blocker replaced.
     */
    fun refreshSettings() {
        val live = enabled()
        val adapter = lastAdapter
        mainHandler.post {
            runCatching {
                if (adapter == null) return@runCatching
                if (!live) {
                    editor.restoreManagedTouchListeners(adapter, itemViewFieldOf(adapter))
                    editor.clearResidualTransforms(adapter)
                }
                invalidateSpanCaches(adapter)
                refreshAdapter(adapter, animate = false)
            }.onFailure { Log.e(TAG, "ControlCenterCardsEdit: settings apply", it) }
        }
        Log.i(TAG, "ControlCenterCardsEdit: applied enabled=" + live + " adapter=" + (adapter != null))
    }

    /** The most recently seen MainPanelAdapter; it owns the layout a settings pass must refresh. */
    @Volatile
    private var lastAdapter: Any? = null

    /** The itemView field of a panel adapter, resolved once per adapter class. */
    private fun itemViewFieldOf(adapter: Any): Field? = runCatching {
        HostAccess.requirePublicField(adapter.javaClass, "itemView", accessible = true)
    }.getOrNull()

    private fun enabled(): Boolean =
        settingCcEditEnabled

    // ─── Edit-mode visibility ──────────────────────────────────────────────────

    private fun installEditVisibilityHooks() {
        val names = listOf(
            "miui.systemui.controlcenter.panel.main.qs.QSCardsController",
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerController",
            "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController",
            "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController",
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController"
        )
        var installed = 0
        names.forEach { name ->
            val controller = name.toClassOrNull() ?: return@forEach
            val available = CompatibleMethodResolver.find(
                controller, "available", returnType = Boolean::class.javaPrimitiveType,
                parameterTypes = listOf(Boolean::class.javaPrimitiveType!!)
            ) ?: return@forEach
            available.hook {
                after { param ->
                    runCatching {
                        if (param.result == false && enabled() && editor.isEditMode(param.thisObject)) {
                            param.result = true
                        }
                    }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: availability", t) }
                }
            }
            installed++
        }
        Log.i(TAG, "ControlCenterCardsEdit: edit visibility hooks installed=$installed")
    }

    private fun installDraggableHook() {
        val holderCls =
            "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder".toClassOrNull()
                ?: run { Log.w(TAG, "ControlCenterCardsEdit: MainPanelItemViewHolder not found"); return }
        val getter = CompatibleMethodResolver.find(
            holderCls, "getDraggable", returnType = Boolean::class.javaPrimitiveType
        ) ?: run { Log.w(TAG, "ControlCenterCardsEdit: getDraggable not found"); return }
        val modeField = editor.findField(holderCls, "mode")
        val ownerMethod = holderCls.methods.firstOrNull {
            it.name == "getOwner" && it.parameterCount == 0
        }
        // Hook the read, not the write: QSRecord.updateDraggable() is the only writer and only
        // QSRecords ever invoke it, so the fixed sections' holders (media/brightness/volume/
        // devicecenter items are not QSRecords) never get a setDraggable call and would stay
        // draggable=false forever — and the big cards only get the write after their item's mode
        // has been updated, so a setDraggable hook would also race the holder's own mode field.
        // getDraggable() is the single gate the ItemTouchHelper callback consults, so forcing the
        // read covers every section at long-press time.
        getter.hook {
            before { param ->
                if (!enabled()) return@before
                runCatching {
                    if (modeField == null || ownerMethod == null) return@runCatching
                    val mode = modeField.get(param.thisObject)?.toString()
                    if (mode == null || mode.endsWith("NORMAL")) return@runCatching
                    val owner = ownerMethod.invoke(param.thisObject) ?: return@runCatching
                    // Only the five fixed sections need the force. qslist records manage their
                    // own flag (unadded pool tiles stay non-draggable by design); overriding
                    // those would let long-press lift tiles that must be tapped to add.
                    if (editor.contentKey(owner) in SECTION_KEYS) param.result = true
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: draggable", t) }
            }
        }
        Log.i(TAG, "ControlCenterCardsEdit: draggable hook installed on ${holderCls.name}")
    }

    // ─── Edit-mode touch blocking ──────────────────────────────────────────────

    /**
     * The five fixed contents are interactive in the NORMAL panel (sliders adjust brightness/
     * volume, the media card has play/next buttons, the big cards toggle on tap). In EDIT mode
     * those interactions must be inert — otherwise lifting a card by long-press first triggers
     * its own action (dragging brightness/volume ends up adjusting them). Each managed holder's
     * itemView gets a consuming OnTouchListener while the panel is in EDIT mode, so the children
     * never see the event; the long-press drag is unaffected because ItemTouchHelper observes the
     * pointer at the RecyclerView level, before child dispatch, and the listener never calls
     * requestDisallowInterceptTouchEvent. qslist tiles keep their tap-to-add/remove behavior.
     */
    @Suppress("ClickableViewAccessibility")
    private fun installEditTouchBlockHook() {
        val adapter = ADAPTER_NAME.toClassOrNull() ?: return
        val holderCls =
            "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder".toClassOrNull()
                ?: return
        // Hook the payload bind (`onBindViewHolder(holder, position, payloads)`) first: leaving
        // EDIT dispatches only payload binds (DiffUtil sends notifyItemRangeChanged with the Mode
        // payload when `areContentsTheSame` is false for every item), which never reach the plain
        // 2-arg bind — so a 2-arg-only hook would leave the consuming edit-mode OnTouchListener
        // stuck on the managed item views (the "reordered cards can't be tapped" bug). The typed
        // 3-arg method runs on EVERY bind (RecyclerView always goes through the 3-arg path; with
        // empty payloads it falls through to the full 2-arg bind via super), and by the time the
        // after-hook runs the holder's mode field has already been updated by owner.updateMode in
        // both paths.
        val onBind = adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == holderCls
        } ?: adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == holderCls
        } ?: return
        val modeField = editor.findField(holderCls, "mode")
        val ownerMethod = holderCls.methods.firstOrNull {
            it.name == "getOwner" && it.parameterCount == 0
        }
        val itemViewField = editor.findField(holderCls, "itemView")
        val blocker = View.OnTouchListener { _, _ -> true }
        onBind.hook {
            after { param ->
                runCatching {
                    if (modeField == null || ownerMethod == null || itemViewField == null) return@runCatching
                    val holder = param.args.getOrNull(0) ?: return@runCatching
                    val itemView = itemViewField.get(holder) as? View ?: return@runCatching
                    val mode = modeField.get(holder)?.toString()
                    val owner = ownerMethod.invoke(holder)
                    val editing = mode != null && !mode.endsWith("NORMAL") &&
                        owner != null && editor.contentKey(owner) in SECTION_KEYS
                    if (editing) {
                        // Save the host's own listener the first time we block this view, so
                        // leaving EDIT restores it instead of leaving null behind (ScaleItemViewHolder
                        // holders — 设备中心, sliders — install themselves in their constructor).
                        if (!editor.originalTouchListeners.containsKey(itemView)) {
                            editor.originalTouchListeners[itemView] =
                                editor.currentTouchListener(itemView) ?: editor.NULL_TOUCH_LISTENER
                        }
                        itemView.setOnTouchListener(blocker)
                    } else {
                        val saved = editor.originalTouchListeners.remove(itemView)
                        itemView.setOnTouchListener(saved as? View.OnTouchListener)
                    }
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: edit touch block", t) }
            }
        }
        Log.i(TAG, "ControlCenterCardsEdit: edit touch-block hook installed on ${adapter.name}")
    }

    // ─── Mode-change sweep ─────────────────────────────────────────────────────

    /**
     * Mode-driven cleanup that does not depend on which bind path the panel takes. Leaving EDIT
     * (`MainPanelModeController.set_mode` → `distributePanels` + `handleNotifyChanged` →
     * `MainPanelAdapter.notifyChanged`) dispatches payload-only binds, so two stale states could
     * survive into the NORMAL panel: the edit-mode consuming touch listener still installed on
     * managed item views (taps land nowhere), and residual move-animation holder translations
     * (the view renders offset from its touch bounds — same "inoperable" symptom). The after-hook
     * on `notifyChanged` sweeps attached holders exactly once when this refresh actually changed
     * the mode to NORMAL: restores the saved host touch listeners and clears holder transforms.
     */
    private fun installModeSweepHook() {
        val adapter = ADAPTER_NAME.toClassOrNull() ?: return
        val notifyChanged = adapter.declaredMethods.firstOrNull {
            it.name == "notifyChanged" && it.parameterTypes.size == 2 &&
                it.parameterTypes.all { p -> p == Boolean::class.javaPrimitiveType }
        } ?: return
        val modeField = editor.findField(adapter, "mode")
        if (editor.attachedHoldersField == null) editor.attachedHoldersField = editor.findField(adapter, "attachedHolders")
        if (editor.endAnimationMethod == null) {
            editor.endAnimationMethod =
                "miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterViewHolder"
                    .toClassOrNull()?.methods?.firstOrNull {
                        it.name == "endAnimation" && it.parameterCount == 0
                    }
        }
        val holderCls =
            "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder".toClassOrNull()
        val itemViewField = holderCls?.let { editor.findField(it, "itemView") }
        notifyChanged.hook {
            after { param ->
                // Cleanup is unconditional (only touches holders we actually blocked/transformed),
                // so even toggling the master switch off mid-session cannot strand a stale state.
                runCatching {
                    val adapterInstance = param.thisObject
                    val modeChanged = editor.invokeChain(adapterInstance, "getModeChanged") as? Boolean
                        ?: return@runCatching
                    if (!modeChanged) return@runCatching
                    val mode = modeField?.get(adapterInstance)?.toString() ?: return@runCatching
                    if (!mode.endsWith("NORMAL")) return@runCatching
                    editor.restoreManagedTouchListeners(adapterInstance, itemViewField)
                    editor.clearResidualTransforms(adapterInstance)
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: mode sweep", t) }
            }
        }
        Log.i(TAG, "ControlCenterCardsEdit: mode sweep hook installed on ${adapter.name}")
    }

    /**
     * Puts back the host's own OnTouchListener on every attached managed holder that was blocked
     * during EDIT (views never blocked are not in [editor.originalTouchListeners] and are skipped).
     */
    // ─── Section order application ─────────────────────────────────────────────

    /**
     * After every redistribution, reorder the flattened content map so the managed sections
     * render in the configured order. Runs inside notifyChanged's own distributeContent call, so
     * both drag refreshes and mode switches pick the order up.
     */
    private fun installContentOrderHook() {
        val adapter = ADAPTER_NAME.toClassOrNull()
            ?: run { Log.w(TAG, "ControlCenterCardsEdit: MainPanelAdapter not found"); return }
        val distribute = editor.resolveDistributeContent(adapter) ?: return
        val mapField = editor.findContentMapField(adapter) ?: return
        distribute.hook {
            after { param ->
                lastAdapter = param.thisObject
                if (!enabled()) return@after
                runCatching {
                    @Suppress("UNCHECKED_CAST") // Host section map read through the resolved field.
                    val current = mapField.get(param.thisObject) as? MutableMap<Any, MutableList<Any>>
                        ?: return@runCatching
                    applySectionOrder(mapField, param.thisObject, current)
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: content order", t) }
            }
        }
    }

    private fun applySectionOrder(mapField: Field, adapter: Any, current: MutableMap<Any, MutableList<Any>>) {
        val order = pendingContentOrder ?: storedContentOrder().takeIf { it.isNotEmpty() } ?: return
        val rank = order.withIndex().associate { it.value to it.index }
        val entries = current.entries.toList()
        if (entries.size <= 1) return
        // Managed sections (cards + tile grid) are ranked by the configured order; chrome
        // entries (edit button, dividers, footers, ...) are not reorderable, so pin each of them
        // to its current slot and fill the remaining slots with the ranked sections.
        val managed = entries.mapIndexedNotNull { index, entry ->
            if (editor.contentKey(entry.key) in MANAGED_SECTIONS) entry to index else null
        }.sortedWith(compareBy({ rank[editor.contentKey(it.first.key)] ?: Int.MAX_VALUE }, { it.second }))
        val unmanagedIndexes = entries.mapIndexedNotNull { index, entry ->
            if (editor.contentKey(entry.key) !in MANAGED_SECTIONS) index else null
        }.toSet()
        val keys = arrayOfNulls<Any>(entries.size)
        unmanagedIndexes.forEach { keys[it] = entries[it].key }
        var m = 0
        for (index in entries.indices) {
            if (index in unmanagedIndexes) continue
            if (m < managed.size) keys[index] = managed[m++].first.key
        }
        if (entries.indices.all { entries[it].key === keys[it] }) return
        val reordered = LinkedHashMap<Any, MutableList<Any>>(current.size)
        keys.forEach { key -> if (key != null) reordered[key] = current.getValue(key) }
        mapField.set(adapter, reordered)
        invalidateSpanCaches(adapter)
    }

    /** Reordering content changes the item at every position; stale span caches corrupt card sizes. */
    private fun invalidateSpanCaches(adapter: Any) {
        runCatching {
            val layoutManager = layoutManagerField?.get(adapter) ?: return@runCatching
            val lookup = layoutManager.javaClass.methods.firstOrNull {
                it.name == "getSpanSizeLookup" && it.parameterCount == 0
            }?.invoke(layoutManager) ?: return@runCatching
            lookup.javaClass.methods.firstOrNull {
                it.name == "invalidateSpanIndexCache" && it.parameterCount == 0
            }?.invoke(lookup)
            lookup.javaClass.methods.firstOrNull {
                it.name == "invalidateSpanGroupIndexCache" && it.parameterCount == 0
            }?.invoke(lookup)
        }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: invalidate span cache", t) }
    }

    private fun storedContentOrder(): List<String> =
        (committedContentOrder ?: settingCcMainContentOrder)
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun storedCardOrder(): List<String> =
        (committedCardOrder ?: settingCcTopCardOrder)
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    // ─── Cross-content drag ────────────────────────────────────────────────────

    /**
     * Hooks the adapter's ItemTouchHelper callback (the anonymous `object : Callback()`
     * expression). onMove records section/card order changes into session state and refreshes
     * via the adapter's own notifyChanged; the SpringItemTouchHelper subclass's onStopDrag
     * commits the session state to Preferences once per gesture. Resolution is three-layered: a
     * structural match over the adapter's declared classes (superclass ==
     * ItemTouchHelper.Callback) first, then the known dex names, then ordinal probing — the
     * nested-class walk can come up empty on R8-processed builds even when the dex still carries
     * the class name.
     */
    private fun installMoveHooks() {
        val adapter = ADAPTER_NAME.toClassOrNull() ?: return
        suppressAnimator = editor.resolveAnimatorSuppressor(adapter)
        adapterModeField = editor.findField(adapter, "mode")
        editor.attachedHoldersField = editor.findField(adapter, "attachedHolders")
        layoutManagerField = editor.findField(adapter, "layoutManager")
        editor.endAnimationMethod = "miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterViewHolder"
            .toClassOrNull()?.methods?.firstOrNull {
                it.name == "endAnimation" && it.parameterCount == 0
            }
        val callbackCls = editor.findItemTouchCallback(adapter) ?: run {
            Log.w(TAG, "ControlCenterCardsEdit: ItemTouchHelper callback not found")
            return
        }
        val onMove = callbackCls.declaredMethods.firstOrNull {
            it.name == "onMove" && it.parameterTypes.size == 3
        } ?: return
        val adapterField = callbackCls.declaredFields.firstOrNull { it.type.name == ADAPTER_NAME }

        onMove.hook {
            before { param ->
                val active = enabled()
                Log.i(TAG, "ControlCenterCardsEdit: onMove entered enabled=$active")
                if (!active) return@before
                runCatching {
                    handleOnMove(adapterField, param.thisObject, param.args.getOrNull(1), param.args.getOrNull(2))
                        ?.let { param.result = true }
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: drag", t) }
            }
        }

        // Commit point: onStopDrag on the SpringItemTouchHelper subclass fires on every drag end
        // (SpringItemTouchHelper.select(null, 0)), before the settle animation. The callback's
        // clearView is inherited from ItemTouchHelper.Callback and is skipped when the settle
        // animation gets interrupted, so prefer the scoped onStopDrag.
        val stopDragCls = "$ADAPTER_NAME\$itemTouchHelper\$1".toClassOrNull()
        val stopDrag = stopDragCls?.declaredMethods?.firstOrNull {
            it.name == "onStopDrag" && it.parameterTypes.size == 1
        }
        if (stopDrag != null) {
            val stopDragAdapterField = stopDragCls.declaredFields.firstOrNull { it.type.name == ADAPTER_NAME }
            stopDrag.hook {
                after { param ->
                    runCatching { commitPendingOrders(stopDragAdapterField, param.thisObject) }
                        .onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: commit", t) }
                }
            }
        } else {
            // Fallback: clearView is inherited from ItemTouchHelper.Callback, so declaredMethods
            // never sees it — resolve through Class.getMethods() instead. The adapter-field guard
            // inside commitPendingOrders makes the process-wide hook a no-op for every other
            // ItemTouchHelper.Callback in SystemUI.
            val clearView = callbackCls.methods.firstOrNull {
                it.name == "clearView" && it.parameterTypes.size == 2
            }
            clearView?.hook {
                after { param ->
                    runCatching { commitPendingOrders(adapterField, param.thisObject) }
                        .onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: commit", t) }
                }
            }
        }
        Log.i(TAG, "ControlCenterCardsEdit: move hooks installed on ${callbackCls.name}")
    }

    /** Returns true when this hook consumed the move (caller sets result); null lets stock run. */
    private fun handleOnMove(
        adapterField: Field?,
        callback: Any,
        sourceHolder: Any?,
        targetHolder: Any?
    ): Boolean? {
        if (sourceHolder == null || targetHolder == null || adapterField == null) return null
        val adapter = adapterField.apply { isAccessible = true }.get(callback) ?: return null
        val sourceOwner = editor.invokeChain(sourceHolder, "getOwner") ?: return null
        val targetOwner = editor.invokeChain(targetHolder, "getOwner") ?: return null
        val sourceKey = editor.contentKey(sourceOwner)
        val targetKey = editor.contentKey(targetOwner)

        // Swapping the two big cards inside 大卡片.
        if (sourceKey == "qscards" && targetKey == "qscards") {
            val sourceSpec = editor.holderItemSpec(sourceHolder)
            val targetSpec = editor.holderItemSpec(targetHolder)
            if (sourceSpec.isNullOrBlank() || targetSpec.isNullOrBlank() || sourceSpec == targetSpec) {
                return null
            }
            val base = pendingCardOrder ?: storedCardOrder().takeIf { it.isNotEmpty() }
                ?: currentCardSequence(sourceOwner)
            if (base.isEmpty()) return null
            val order = base.toMutableList()
            val from = order.indexOfFirst { it.equals(sourceSpec, ignoreCase = true) }
            val to = order.indexOfFirst { it.equals(targetSpec, ignoreCase = true) }
            if (from < 0 || to < 0 || from == to) return null
            order.add(to, order.removeAt(from))
            pendingCardOrder = order
            persistOrder(SystemUiSettingsKeys.KEY_CC_TOP_CARD_ORDER, order)
            dragGeneration++
            // A one-position swap animates like a native tile move: do NOT suppress the editor's
            // item animator for this refresh, so the two cards slide to their new slots instead of
            // jumping. The round-4 scramble only came from whole-grid re-flows (~20 moves per
            // refresh fighting the Folme animation); a single swap pair is exactly what the stock
            // tile mover animates safely. Cross-section moves still suppress (see below).
            postRefresh(adapter, animate = true)
            return true
        }

        if (sourceKey == targetKey) return null
        if (sourceKey !in MANAGED_SECTIONS || targetKey !in MANAGED_SECTIONS) return null

        val base = pendingContentOrder ?: storedContentOrder().takeIf { it.isNotEmpty() }
            ?: currentSectionSequence(adapter)
        if (base.isEmpty()) return null
        val order = base.toMutableList()
        val from = order.indexOf(sourceKey)
        val to = order.indexOf(targetKey)
        if (from < 0 || to < 0 || from == to) return null
        // Direct placement: put the dragged section at the target's slot. `add(to, removeAt(from))`
        // is off-by-one for downward moves — after removing the source the target's slot is at
        // `to - 1`, so inserting at `to` lands the dragged section one slot PAST the target. With
        // the tile grid as the target that dropped the whole card section below the grid, past the
        // 未添加 pool ("大卡掉落到最底下没加进开关的区域"). Insert at the target's actual slot;
        // an already-adjacent downward move then collapses to a no-op, which is fine — there is
        // nothing to reorder when the source already sits at the target's boundary.
        val insertAt = if (from < to) to - 1 else to
        order.add(insertAt, order.removeAt(from))
        pendingContentOrder = order
        persistOrder(SystemUiSettingsKeys.KEY_CC_MAIN_CONTENT_ORDER, order)
        dragGeneration++
        postRefresh(adapter, animate = false)
        return true
    }

    /** Persist every accepted move so a host that skips its drag-end callback cannot lose it. */
    private fun persistOrder(key: String, order: List<String>) {
        val value = order.joinToString(",")
        if (key == SystemUiSettingsKeys.KEY_CC_MAIN_CONTENT_ORDER) {
            committedContentOrder = value
        } else if (key == SystemUiSettingsKeys.KEY_CC_TOP_CARD_ORDER) {
            committedCardOrder = value
        }
        Preferences.putStringSynchronous(key, value)
    }

    /** Synthesizes the full movable-section sequence from the currently rendered map. */
    private fun currentSectionSequence(adapter: Any): List<String> {
        val mapField = editor.findContentMapField(adapter.javaClass) ?: return emptyList()
        val map = mapField.get(adapter) as? Map<*, *> ?: return emptyList()
        val seen = map.keys.filterNotNull().map { editor.contentKey(it) }.filter { it in MANAGED_SECTIONS }
        return seen + MANAGED_SECTIONS.filterNot { it in seen }
    }

    /** Reads the qscards controller's current card specs as the initial top-card order. */
    private fun currentCardSequence(cardsController: Any): List<String> {
        val items = editor.invokeChain(cardsController, "getListItems") as? List<*> ?: return emptyList()
        return items.mapNotNull { editor.itemSpec(it) }
    }

    /**
     * Defers the adapter refresh out of ItemTouchHelper's own event handling, mirroring the
     * native tile mover (distributor.notifyChanged posts MSG_NOTIFY_CHANGED to the next loop
     * iteration): adapter.notifyChanged → distributeContent + DiffUtil dispatch animates the
     * section move mid-drag exactly like native tile moves. [animate] keeps the editor's item
     * animator unsuppressed so a small move (a big-card swap) slides; cross-section re-flows pass
     * false so the whole-grid re-flow applies instantly instead of scrambling.
     */
    private fun postRefresh(adapter: Any, animate: Boolean = false) {
        mainHandler.post { refreshAdapter(adapter, animate) }
    }

    private fun refreshAdapter(adapter: Any, animate: Boolean = false) {
        val notifyChanged = adapter.javaClass.methods.firstOrNull {
            it.name == "notifyChanged" && it.parameterTypes.size == 2 &&
                it.parameterTypes.all { p -> p == Boolean::class.javaPrimitiveType }
        }?.apply { isAccessible = true } ?: return
        // Drag-driven refreshes must not animate the whole-grid re-flow: see
        // editor.resolveAnimatorSuppressor for why the editor's unsuppressed move animations leave views
        // stuck at wrong translations. A single big-card swap is the exception — it slides like a
        // native tile move, so `animate` un-suppresses (a no-op when the animator is already on).
        lastAdapter = adapter
        suppressAnimator?.invoke(adapter, !animate)
        notifyChanged.invoke(adapter, false, false)
    }

    private fun commitPendingOrders(adapterField: Field?, callback: Any) {
        val content = pendingContentOrder
        val cards = pendingCardOrder
        // adapterField is resolved from a specific anonymous class (the itemTouchHelper$2
        // callback or the itemTouchHelper$1 helper); get() throws for any other
        // ItemTouchHelper.Callback/helper instance in the process, which also means the drag did
        // not belong to a managed MainPanelAdapter — bail out before writing anything.
        val adapter = if (adapterField != null) {
            runCatching { adapterField.apply { isAccessible = true }.get(callback) }.getOrNull() ?: return
        } else return
        if (content != null) {
            val value = content.joinToString(",")
            pendingContentOrder = null
            committedContentOrder = value
            Preferences.putStringSynchronous(SystemUiSettingsKeys.KEY_CC_MAIN_CONTENT_ORDER, value)
            Log.i(TAG, "ControlCenterCardsEdit: content order saved=$value")
        }
        if (cards != null) {
            val value = cards.joinToString(",")
            pendingCardOrder = null
            committedCardOrder = value
            Preferences.putStringSynchronous(SystemUiSettingsKeys.KEY_CC_TOP_CARD_ORDER, value)
            Log.i(TAG, "ControlCenterCardsEdit: card order saved=$value")
        }
        if (content == null && cards == null) {
            // Some plugin builds update the adapter map without returning true from the callback.
            // Capture that already-rendered order at drag end as a last-resort persistence path.
            val current = currentSectionSequence(adapter)
            val stock = SECTION_KEYS + "qslist"
            if (current.toSet() == stock.toSet() && current != stock) {
                val value = current.joinToString(",")
                committedContentOrder = value
                Preferences.putStringSynchronous(SystemUiSettingsKeys.KEY_CC_MAIN_CONTENT_ORDER, value)
                Log.i(TAG, "ControlCenterCardsEdit: captured adapter order=$value")
            }
            return
        }
        // Make the daemon write land before the final settle refresh (and before the user can
        // leave the editor), so a fresh process never re-reads the previous value.
        Preferences.flush()
        // One final settle with persisted ranks; also repairs the visual state if the last
        // onMove was consumed without a following refresh.
        postRefresh(adapter)
        // Re-enable the editor's item animations once the settle refresh has laid out; only
        // while the panel is still in EDIT (leaving it re-suppresses via onStop/onStart) and
        // only if the same drag is still the latest one (a quick re-drag suppresses again).
        val generation = dragGeneration
        mainHandler.postDelayed({
            runCatching {
                if (dragGeneration == generation) {
                    editor.clearResidualTransforms(adapter)
                    val mode = adapterModeField?.get(adapter)?.toString()
                    if (mode != null && !mode.endsWith("NORMAL")) {
                        suppressAnimator?.invoke(adapter, false)
                    }
                }
            }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: animator restore", t) }
        }, animatorRestoreDelayMs)
    }

    // ─── Top-card (大卡片) order ───────────────────────────────────────────────

    private fun installTopCardOrderHook() {
        val controller = "miui.systemui.controlcenter.panel.main.qs.QSCardsController".toClassOrNull()
            ?: return
        val method = CompatibleMethodResolver.find(controller, "getListItems") ?: return
        method.hook {
            after { param ->
                if (!enabled()) return@after
                runCatching {
                    @Suppress("UNCHECKED_CAST") // Host card list returned by the hooked method.
                    val result = param.result as? MutableList<Any> ?: return@runCatching
                    val order = pendingCardOrder ?: storedCardOrder().takeIf { it.isNotEmpty() }
                        ?: return@runCatching
                    val rank = order.withIndex().associate { (index, key) -> key.lowercase() to index }
                    val sorted = result.withIndex()
                        .sortedWith(compareBy({ rank[editor.itemSpec(it.value)?.lowercase()] ?: Int.MAX_VALUE }, { it.index }))
                        .map { it.value }
                    for (index in result.indices) {
                        if (result[index] !== sorted[index]) result[index] = sorted[index]
                    }
                }.onFailure { t ->
                    Log.e(TAG, "ControlCenterCardsEdit: card order", t)
                }
            }
        }
    }

    // ─── Shared helpers ────────────────────────────────────────────────────────

}
