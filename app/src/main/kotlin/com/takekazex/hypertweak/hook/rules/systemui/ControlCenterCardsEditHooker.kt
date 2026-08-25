package com.takekazex.hypertweak.hook.rules.systemui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HotReloadMode
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

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
 * ([Preferences.KEY_CC_MAIN_CONTENT_ORDER]; keys `qscards,media,brightness,volume,devicecenter`
 * plus `qslist` for the tile grid). An empty stored value means "follow the system" — the map is
 * left in stock priority order until the user drags something for the first time. Dragging
 * places the dragged section at the target's slot: for a downward move the insert index is the
 * target's slot *after* the source is removed (`to - 1`), so the section never lands one slot
 * past the target — with the tile grid as the target that used to drop the whole card section
 * below the grid, past the 未添加 pool (大卡掉落到最底下没加进开关的区域). Within 大卡片, swapping
 * the WiFi/cellular cards reorders their specs through [Preferences.KEY_CC_TOP_CARD_ORDER].
 */
class ControlCenterCardsEditHooker : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

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
     * The itemView touch listeners the host installed before the edit-mode blocker replaced
     * them (e.g. [ScaleItemViewHolder] holders — 设备中心, sliders — install themselves as the
     * itemView's OnTouchListener in their constructor). Restored when the panel leaves EDIT;
     * the value is [NULL_TOUCH_LISTENER] when the host had none.
     */
    private val originalTouchListeners = java.util.WeakHashMap<View, Any>()

    private val NULL_TOUCH_LISTENER = Any()

    /**
     * Suppresses the adapter's ControlCenterItemAnimator around drag-driven refreshes (see
     * [resolveAnimatorSuppressor]); resolved lazily in installMoveHooks.
     */
    private var suppressAnimator: ((Any, Boolean) -> Unit)? = null

    /** The adapter's `mode` field, read when re-enabling the animator after a drag. */
    private var adapterModeField: Field? = null

    /** Delay before re-enabling the editor's item animations after a drag ends. */
    private val animatorRestoreDelayMs = 400L

    /** Bumped on every consumed move; a stale animator-restore only fires for the same drag. */
    @Volatile
    private var dragGeneration = 0L

    /** The adapter's `attachedHolders` field (ArrayList[] of per-spread-row holder buckets). */
    private var attachedHoldersField: Field? = null

    /** The adapter's GridLayoutManager, whose span lookup must be invalidated after reordering. */
    private var layoutManagerField: Field? = null

    /** `ControlCenterViewHolder.endAnimation()` — resets translation/alpha/scale to rest state. */
    private var endAnimationMethod: java.lang.reflect.Method? = null

    /** Main-thread handler used to defer adapter refreshes out of ItemTouchHelper's event loop. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cached per-controller-class reader that reports whether the panel is in EDIT mode. */
    private val editModeReaders = ConcurrentHashMap<Class<*>, ((Any) -> Boolean)>()

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
                        val key = contentKey(param.thisObject)
                        val index = order.indexOf(key)
                        if (index >= 0) param.result = 10 + index * 10
                    }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: priority", t) }
                }
            }
            installed++
        }
        Log.i(TAG, "ControlCenterCardsEdit: priority hooks installed=$installed")
    }

    /** Keep the WiFi card's compact 1x2 span stable when it sits beside a 2x2 card. */
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

    private fun enabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CC_EDIT_ENABLED, false)

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
                        if (param.result == false && enabled() && isEditMode(param.thisObject)) {
                            param.result = true
                        }
                    }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: availability", t) }
                }
            }
            installed++
        }
        Log.i(TAG, "ControlCenterCardsEdit: edit visibility hooks installed=$installed")
    }

    private fun isEditMode(controller: Any): Boolean {
        val reader = editModeReaders.computeIfAbsent(controller.javaClass) { cls ->
            buildEditModeReader(cls)
        }
        return reader(controller)
    }

    /**
     * Walks the controller's field hierarchy once and builds a reader that reports whether any
     * mode-holder reachable through the fields reads EDIT. Fields hold either the mode controller
     * itself (`getMode()`) or a lazy/provider wrapper around it (`get()` — dagger Provider, or
     * `invoke()` — kotlin Function0). Enum comparison is by name so no host enum class is loaded.
     */
    private fun buildEditModeReader(cls: Class<*>): (Any) -> Boolean {
        val paths = mutableListOf<Pair<Field, String?>>() // field to optional provider-unwrap method
        var type: Class<*>? = cls
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val fieldType = field.type
                runCatching { field.isAccessible = true }
                when {
                    declaresZeroArg(fieldType, "getMode") -> paths += field to null
                    declaresZeroArg(fieldType, "get") -> paths += field to "get"
                    declaresZeroArg(fieldType, "invoke") -> paths += field to "invoke"
                }
            }
            type = type.superclass
        }
        if (paths.isEmpty()) {
            Log.w(TAG, "ControlCenterCardsEdit: no mode reader on ${cls.name}")
        }
        return { controller ->
            paths.any { (field, unwrap) ->
                runCatching {
                    var value = field.get(controller) ?: return@runCatching false
                    if (unwrap != null) {
                        value = invokeChain(value, unwrap) ?: return@runCatching false
                    }
                    invokeChain(value, "getMode")?.toString()?.endsWith("EDIT") == true
                }.getOrDefault(false)
            }
        }
    }

    private fun declaresZeroArg(cls: Class<*>, name: String): Boolean =
        cls.declaredMethods.any {
            it.name == name && it.parameterCount == 0 && it.returnType != Void.TYPE
        }

    private fun invokeChain(receiver: Any?, method: String): Any? {
        if (receiver == null) return null
        return runCatching {
            receiver.javaClass.methods.firstOrNull {
                it.name == method && it.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(receiver)
        }.getOrNull()
    }

    // ─── Draggability ──────────────────────────────────────────────────────────

    private fun installDraggableHook() {
        val holderCls =
            "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder".toClassOrNull()
                ?: run { Log.w(TAG, "ControlCenterCardsEdit: MainPanelItemViewHolder not found"); return }
        val getter = CompatibleMethodResolver.find(
            holderCls, "getDraggable", returnType = Boolean::class.javaPrimitiveType
        ) ?: run { Log.w(TAG, "ControlCenterCardsEdit: getDraggable not found"); return }
        val modeField = findField(holderCls, "mode")
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
                    if (contentKey(owner) in SECTION_KEYS) param.result = true
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
        val modeField = findField(holderCls, "mode")
        val ownerMethod = holderCls.methods.firstOrNull {
            it.name == "getOwner" && it.parameterCount == 0
        }
        val itemViewField = findField(holderCls, "itemView")
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
                        owner != null && contentKey(owner) in SECTION_KEYS
                    if (editing) {
                        // Save the host's own listener the first time we block this view, so
                        // leaving EDIT restores it instead of leaving null behind (ScaleItemViewHolder
                        // holders — 设备中心, sliders — install themselves in their constructor).
                        if (!originalTouchListeners.containsKey(itemView)) {
                            originalTouchListeners[itemView] =
                                currentTouchListener(itemView) ?: NULL_TOUCH_LISTENER
                        }
                        itemView.setOnTouchListener(blocker)
                    } else {
                        val saved = originalTouchListeners.remove(itemView)
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
        val modeField = findField(adapter, "mode")
        if (attachedHoldersField == null) attachedHoldersField = findField(adapter, "attachedHolders")
        if (endAnimationMethod == null) {
            endAnimationMethod =
                "miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterViewHolder"
                    .toClassOrNull()?.methods?.firstOrNull {
                        it.name == "endAnimation" && it.parameterCount == 0
                    }
        }
        val holderCls =
            "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder".toClassOrNull()
        val itemViewField = holderCls?.let { findField(it, "itemView") }
        notifyChanged.hook {
            after { param ->
                // Cleanup is unconditional (only touches holders we actually blocked/transformed),
                // so even toggling the master switch off mid-session cannot strand a stale state.
                runCatching {
                    val adapterInstance = param.thisObject ?: return@runCatching
                    val modeChanged = invokeChain(adapterInstance, "getModeChanged") as? Boolean
                        ?: return@runCatching
                    if (!modeChanged) return@runCatching
                    val mode = modeField?.get(adapterInstance)?.toString() ?: return@runCatching
                    if (!mode.endsWith("NORMAL")) return@runCatching
                    restoreManagedTouchListeners(adapterInstance, itemViewField)
                    clearResidualTransforms(adapterInstance)
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: mode sweep", t) }
            }
        }
        Log.i(TAG, "ControlCenterCardsEdit: mode sweep hook installed on ${adapter.name}")
    }

    /**
     * Puts back the host's own OnTouchListener on every attached managed holder that was blocked
     * during EDIT (views never blocked are not in [originalTouchListeners] and are skipped).
     */
    private fun restoreManagedTouchListeners(adapter: Any, itemViewField: Field?) {
        if (itemViewField == null) return
        forEachAttachedHolder(adapter) { holder ->
            runCatching {
                val itemView = itemViewField.get(holder) as? View ?: return@runCatching
                val saved = originalTouchListeners.remove(itemView) ?: return@runCatching
                itemView.setOnTouchListener(saved as? View.OnTouchListener)
            }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: restore touch listeners", t) }
        }
    }

    /**
     * Reads the itemView's current OnTouchListener. The SDK stub does not expose
     * `View.getOnTouchListener()` (it is @UnsupportedAppUsage), so it goes through reflection;
     * the hook runs in the SystemUI process, which is exempt from hidden-API enforcement.
     */
    private fun currentTouchListener(view: View): View.OnTouchListener? =
        runCatching {
            val getter = View::class.java.getDeclaredMethod("getOnTouchListener")
            getter.isAccessible = true
            getter.invoke(view) as? View.OnTouchListener
        }.getOrNull()

    // ─── Section order application ─────────────────────────────────────────────

    /**
     * After every redistribution, reorder the flattened content map so the managed sections
     * render in the configured order. Runs inside notifyChanged's own distributeContent call, so
     * both drag refreshes and mode switches pick the order up.
     */
    private fun installContentOrderHook() {
        val adapter = ADAPTER_NAME.toClassOrNull()
            ?: run { Log.w(TAG, "ControlCenterCardsEdit: MainPanelAdapter not found"); return }
        val distribute = resolveDistributeContent(adapter) ?: return
        val mapField = findContentMapField(adapter) ?: return
        distribute.hook {
            after { param ->
                if (!enabled()) return@after
                runCatching {
                    val current = mapField.get(param.thisObject) as? MutableMap<Any, MutableList<Any>>
                        ?: return@runCatching
                    applySectionOrder(mapField, param.thisObject, current)
                }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: content order", t) }
            }
        }
    }

    private fun resolveDistributeContent(adapter: Class<*>): java.lang.reflect.Method? =
        CompatibleMethodResolver.find(adapter, "distributeContent", parameterTypes = listOf(Boolean::class.javaPrimitiveType!!))
            ?: adapter.declaredMethods.singleOrNull {
                it.name == "distributeContent" && it.parameterTypes.size == 1
            }

    private fun findContentMapField(adapter: Class<*>): Field? =
        findField(adapter, "contentMap")
            ?: adapter.declaredFields.firstOrNull {
                !Modifier.isStatic(it.modifiers) &&
                    it.type.name.contains("LinkedHashMap")
            }?.apply { isAccessible = true }

    private fun applySectionOrder(mapField: Field, adapter: Any, current: MutableMap<Any, MutableList<Any>>) {
        val order = pendingContentOrder ?: storedContentOrder().takeIf { it.isNotEmpty() } ?: return
        val rank = order.withIndex().associate { it.value to it.index }
        val entries = current.entries.toList()
        if (entries.size <= 1) return
        // Managed sections (cards + tile grid) are ranked by the configured order; chrome
        // entries (edit button, dividers, footers, ...) are not reorderable, so pin each of them
        // to its current slot and fill the remaining slots with the ranked sections.
        val managed = entries.mapIndexedNotNull { index, entry ->
            if (contentKey(entry.key) in MANAGED_SECTIONS) entry to index else null
        }.sortedWith(compareBy({ rank[contentKey(it.first.key)] ?: Int.MAX_VALUE }, { it.second }))
        val unmanagedIndexes = entries.mapIndexedNotNull { index, entry ->
            if (contentKey(entry.key) !in MANAGED_SECTIONS) index else null
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
        (committedContentOrder ?: Preferences.getString(Preferences.KEY_CC_MAIN_CONTENT_ORDER, ""))
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun storedCardOrder(): List<String> =
        (committedCardOrder ?: Preferences.getString(Preferences.KEY_CC_TOP_CARD_ORDER, ""))
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
        suppressAnimator = resolveAnimatorSuppressor(adapter)
        adapterModeField = findField(adapter, "mode")
        attachedHoldersField = findField(adapter, "attachedHolders")
        layoutManagerField = findField(adapter, "layoutManager")
        endAnimationMethod = "miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterViewHolder"
            .toClassOrNull()?.methods?.firstOrNull {
                it.name == "endAnimation" && it.parameterCount == 0
            }
        val callbackCls = findItemTouchCallback(adapter) ?: run {
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

    private fun findItemTouchCallback(adapter: Class<*>): Class<*>? {
        val callbackSuper = "androidx.recyclerview.widget.ItemTouchHelper\$Callback"
        runCatching { adapter.declaredClasses }.getOrNull()
            ?.firstOrNull { cls -> cls.superclass?.name == callbackSuper }
            ?.let { return it }
        for (name in listOf("$ADAPTER_NAME\$itemTouchHelper\$2")) {
            name.toClassOrNull()?.let { candidate ->
                if (candidate.superclass?.name == callbackSuper ||
                    candidate.name.endsWith("\$itemTouchHelper\$2")
                ) {
                    return candidate
                }
            }
        }
        // Ordinal probing: the object-expression ordinal can shift between plugin builds.
        for (ordinal in 0..16) {
            "$ADAPTER_NAME\$itemTouchHelper\$$ordinal".toClassOrNull()?.let { candidate ->
                if (callbackSuper.let { runCatching { Class.forName(it, false, candidate.classLoader) }.getOrNull() }
                        ?.isAssignableFrom(candidate) == true
                ) {
                    return candidate
                }
            }
        }
        return null
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
        val sourceOwner = invokeChain(sourceHolder, "getOwner") ?: return null
        val targetOwner = invokeChain(targetHolder, "getOwner") ?: return null
        val sourceKey = contentKey(sourceOwner)
        val targetKey = contentKey(targetOwner)

        // Swapping the two big cards inside 大卡片.
        if (sourceKey == "qscards" && targetKey == "qscards") {
            val sourceSpec = holderItemSpec(sourceHolder)
            val targetSpec = holderItemSpec(targetHolder)
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
            persistOrder(Preferences.KEY_CC_TOP_CARD_ORDER, order)
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
        persistOrder(Preferences.KEY_CC_MAIN_CONTENT_ORDER, order)
        dragGeneration++
        postRefresh(adapter, animate = false)
        return true
    }

    /** Persist every accepted move so a host that skips its drag-end callback cannot lose it. */
    private fun persistOrder(key: String, order: List<String>) {
        val value = order.joinToString(",")
        if (key == Preferences.KEY_CC_MAIN_CONTENT_ORDER) {
            committedContentOrder = value
        } else if (key == Preferences.KEY_CC_TOP_CARD_ORDER) {
            committedCardOrder = value
        }
        Preferences.putStringSynchronous(key, value)
    }

    /** Synthesizes the full movable-section sequence from the currently rendered map. */
    private fun currentSectionSequence(adapter: Any): List<String> {
        val mapField = findContentMapField(adapter.javaClass) ?: return emptyList()
        val map = mapField.get(adapter) as? Map<*, *> ?: return emptyList()
        val seen = map.keys.filterNotNull().map { contentKey(it) }.filter { it in MANAGED_SECTIONS }
        return seen + MANAGED_SECTIONS.filterNot { it in seen }
    }

    /** Reads the qscards controller's current card specs as the initial top-card order. */
    private fun currentCardSequence(cardsController: Any): List<String> {
        val items = invokeChain(cardsController, "getListItems") as? List<*> ?: return emptyList()
        return items.mapNotNull { itemSpec(it) }
    }

    private fun holderItemSpec(holder: Any): String? {
        val method = holder.javaClass.methods.firstOrNull {
            it.name == "getItem\$miui_controlcenter_release" && it.parameterCount == 0
        } ?: holder.javaClass.methods.firstOrNull {
            it.name.startsWith("getItem") && it.parameterCount == 0 &&
                it.returnType != Int::class.javaPrimitiveType &&
                it.returnType.name.endsWith("MainPanelListItem")
        } ?: return null
        val item = runCatching { method.apply { isAccessible = true }.invoke(holder) }.getOrNull()
            ?: return null
        return itemSpec(item)
    }

    private fun itemSpec(item: Any?): String? {
        if (item == null) return null
        return invokeChain(item, "getSpec") as? String
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
        // resolveAnimatorSuppressor for why the editor's unsuppressed move animations leave views
        // stuck at wrong translations. A single big-card swap is the exception — it slides like a
        // native tile move, so `animate` un-suppresses (a no-op when the animator is already on).
        suppressAnimator?.invoke(adapter, !animate)
        notifyChanged.invoke(adapter, false, false)
    }

    /**
     * Iterates the adapter's `attachedHolders` buckets (one ArrayList per spread row).
     */
    private fun forEachAttachedHolder(adapter: Any, block: (Any) -> Unit) {
        if (attachedHoldersField == null) return
        runCatching {
            val buckets = attachedHoldersField?.get(adapter) as? Array<*> ?: return@runCatching
            for (bucket in buckets) {
                val list = bucket as? List<*> ?: continue
                for (holder in list) {
                    if (holder != null) block(holder)
                }
            }
        }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: attached holders", t) }
    }

    /**
     * Clears any residual holder transforms (translation/alpha/scale) left by interrupted move
     * animations, so a reordered card's rendered position always matches its touch bounds —
     * a drifted card looks fine but is "inoperable" because the tap lands elsewhere.
     */
    private fun clearResidualTransforms(adapter: Any) {
        if (endAnimationMethod == null) return
        forEachAttachedHolder(adapter) { holder ->
            runCatching { endAnimationMethod?.invoke(holder) }
                .onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: clear transforms", t) }
        }
    }

    /**
     * Resolves a way to suppress MainPanelAdapter's ControlCenterItemAnimator. While EDIT mode
     * is active the distributor's handleNotifyChanged keeps the animator unsuppressed (so the
     * pool-tile add/remove animates), and ControlCenterItemAnimator.prepareMove applies each
     * move's delta to the holder's *current* translation (`getHolderTransX() + delta`) — when
     * the next refresh interrupts those Folme moves (constant during a drag), views end up at
     * wrong translations, which is the grid visibly scrambling once big cards sit among the
     * tiles. Suppressing around drag refreshes makes the layout follow the finger instantly.
     */
    private fun resolveAnimatorSuppressor(adapterCls: Class<*>): ((Any, Boolean) -> Unit)? {
        val rvField = findField(adapterCls, "recyclerView") ?: return null
        val animatorCls = "miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterItemAnimator"
            .toClassOrNull() ?: return null
        val setter = animatorCls.methods.firstOrNull {
            it.name == "setSuppressAnimation" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return null
        val getItemAnimator = "androidx.recyclerview.widget.RecyclerView".toClassOrNull()
            ?.methods?.firstOrNull { it.name == "getItemAnimator" && it.parameterCount == 0 }
        return { adapter, suppress ->
            runCatching {
                val rv = rvField.get(adapter) ?: return@runCatching
                val animator = getItemAnimator?.invoke(rv) ?: return@runCatching
                setter.invoke(animator, suppress)
            }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: animator suppress", t) }
        }
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
            Preferences.putStringSynchronous(Preferences.KEY_CC_MAIN_CONTENT_ORDER, value)
            Log.i(TAG, "ControlCenterCardsEdit: content order saved=$value")
        }
        if (cards != null) {
            val value = cards.joinToString(",")
            pendingCardOrder = null
            committedCardOrder = value
            Preferences.putStringSynchronous(Preferences.KEY_CC_TOP_CARD_ORDER, value)
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
                Preferences.putStringSynchronous(Preferences.KEY_CC_MAIN_CONTENT_ORDER, value)
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
                    clearResidualTransforms(adapter)
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
                    val result = param.result as? MutableList<Any> ?: return@runCatching
                    val order = pendingCardOrder ?: storedCardOrder().takeIf { it.isNotEmpty() }
                        ?: return@runCatching
                    val rank = order.withIndex().associate { (index, key) -> key.lowercase() to index }
                    val sorted = result.withIndex()
                        .sortedWith(compareBy({ rank[itemSpec(it.value)?.lowercase()] ?: Int.MAX_VALUE }, { it.index }))
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

    private fun findField(cls: Class<*>, name: String): Field? =
        runCatching {
            var type: Class<*>? = cls
            while (type != null && type != Any::class.java) {
                try {
                    return type.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                }
            }
            null
        }.getOrNull()

    private fun contentKey(content: Any): String {
        val name = content.javaClass.simpleName.lowercase()
        return when {
            name.contains("qscards") -> "qscards"
            name.contains("mediaplayer") -> "media"
            name.contains("brightness") -> "brightness"
            name.contains("volume") -> "volume"
            name.contains("devicecenter") -> "devicecenter"
            name.contains("qslist") -> "qslist"
            else -> name.replace("controller", "").replace("content", "")
        }
    }
}
