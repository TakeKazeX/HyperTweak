package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicyConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.util.RestartScopeSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

/** Uniform row height: the drag math converts a pixel offset into an index with it. */
private val ORDER_ROW_HEIGHT = 56.dp

/** Pixels scrolled per frame while the dragged row is held near a viewport edge. */
private const val AUTO_SCROLL_STEP_PX = 16f
private const val AUTO_SCROLL_FRAME_MS = 16L

/** Fraction of the visible height, at each end, that triggers auto-scrolling. */
private const val AUTO_SCROLL_EDGE_FRACTION = 0.2f

/**
 * Order editor behind the icon tuner's 图标顺序 row.
 *
 * The Layout tab used to ask for the custom order as a comma-separated list of host-slot wire names
 * (`stacked_mobile_icon`, …), which gave no feedback about what was being ordered. This page
 * replaces it with the same [IconSlotCatalog] rows the 其他图标 tab renders — Material preview icon
 * plus localized name — as a drag list written back through [IconSlotPolicy.orderEntries], the exact
 * wire format the host hook already reads. The drag list is [IconSlotCatalog.orderSlots], the host's
 * own slot order, so before the first drag it shows the order SystemUI is already using.
 *
 * Hiding an icon deliberately stays on the 其他图标 tab: its `icon_tuner_slot_*` mode is a superset
 * of the legacy `icon_ext_blocked` list (HIDE_EVERYWHERE *is* "extra hidden"), without that key's
 * precedence over the mode, so there is no second hiding control here.
 *
 * Every edit reports SystemUI to the shared Home restart dialog; like every other secondary page
 * this one keeps no preference cache, so a value written here is re-read on the next visit.
 */
@Composable
fun IconOrderPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val pageScroll = rememberScrollState()

    // The auto-scroll threshold is compared against the scroll viewport's own bounds, so a
    // platform inset or a large-font top bar cannot move the effective edge.
    val viewport = remember { OrderViewport() }

    val storedEntries = remember {
        Preferences.getStringSet(IconTunerOptions.KEY_POSITION_VALUES, emptySet())
    }
    // Entries the catalog cannot draw are carried over verbatim: the user never sees them here,
    // and dropping them would silently undo an older hand-written configuration.
    val offCatalogSlots = remember {
        IconSlotPolicy.parseLegacyOrder(storedEntries)
            .map { it.slot }
            .filterNot { it in IconSlotCatalog.orderSlots }
            .distinct()
    }

    var position by rememberSaveable {
        mutableIntStateOf(
            Preferences.getInt(
                IconTunerOptions.KEY_POSITION,
                IconSlotPolicyConfig.POSITION_SYSTEM
            )
        )
    }
    var reorderHidden by rememberSaveable {
        mutableStateOf(Preferences.getBoolean(IconTunerOptions.KEY_POSITION_REORDER, false))
    }
    var order by rememberSaveable {
        mutableStateOf(IconSlotPolicy.displayOrder(storedEntries, IconSlotCatalog.orderSlots))
    }

    fun markDirty() {
        requestRestartScopes(RestartScopeSelection(systemUi = true))
    }

    /** Stores the displayed order plus the carry-over entries the list cannot show. */
    fun persistOrder(slots: List<String>) {
        Preferences.putStringSet(
            IconTunerOptions.KEY_POSITION_VALUES,
            IconSlotPolicy.orderEntries(slots + offCatalogSlots)
        )
        markDirty()
    }

    fun writeOrder(slots: List<String>) {
        order = slots
        persistOrder(slots)
    }

    val positions = listOf(
        stringResource(R.string.icon_position_system),
        stringResource(R.string.icon_position_wifi_before_mobile),
        stringResource(R.string.icon_position_custom)
    )
    val custom = position == IconSlotPolicyConfig.POSITION_CUSTOM

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.icon_position_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.icon_back))
                }
            }
        )
    }) { padding ->
        // The measurement has to sit outside the scroll modifier: coordinates reported from inside
        // a scrolling container move with the content instead of describing the viewport.
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                viewport.top = bounds.top
                viewport.bottom = bounds.bottom
            }
        ) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(pageScroll)
            ) {
                Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

                SmallTitle(stringResource(R.string.icon_order_mode_title))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.icon_order_mode_title),
                            summary = stringResource(R.string.icon_position_summary),
                            items = positions,
                            selectedIndex = position.coerceIn(0, positions.lastIndex),
                            onSelectedIndexChange = { index ->
                                position = index
                                Preferences.putInt(IconTunerOptions.KEY_POSITION, index)
                                markDirty()
                            }
                        )
                        SwitchPreference(
                            checked = reorderHidden,
                            onCheckedChange = { checked ->
                                reorderHidden = checked
                                Preferences.putBoolean(
                                    IconTunerOptions.KEY_POSITION_REORDER,
                                    checked
                                )
                                markDirty()
                            },
                            title = stringResource(R.string.icon_position_reorder_hidden),
                            summary = stringResource(R.string.icon_position_reorder_hidden_summary)
                        )
                        // Hiding itself stays on the 其他图标 tab, where the per-slot mode dropdown
                        // already offers 隐藏; say so instead of leaving a dead end here.
                        OrderHint(stringResource(R.string.icon_order_hide_pointer))
                    }
                }

                if (custom) {
                    SmallTitle(stringResource(R.string.icon_position_custom))
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            OrderHint(stringResource(R.string.icon_order_custom_hint))
                            SlotOrderList(
                                slots = order,
                                scrollState = pageScroll,
                                viewport = viewport,
                                onReorder = { writeOrder(it) }
                            )
                            TextButton(
                                text = stringResource(R.string.icon_order_custom_reset),
                                onClick = {
                                    // Reset re-writes the stored set from the carry-over entries
                                    // only: an empty set is the stored form of "system order".
                                    order = IconSlotCatalog.orderSlots
                                    Preferences.putStringSet(
                                        IconTunerOptions.KEY_POSITION_VALUES,
                                        IconSlotPolicy.orderEntries(offCatalogSlots)
                                    )
                                    markDirty()
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
            }
        }
    }
}

/** Mutable viewport bounds shared with [SlotOrderList] without recomposing the whole page. */
private class OrderViewport {
    var top by mutableFloatStateOf(0f)
    var bottom by mutableFloatStateOf(0f)
}

@Composable
private fun OrderHint(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * The reorderable half of the page. [slots] is always the full order list
 * ([IconSlotCatalog.orderSlots]), so every row the host can place can be dragged to any position.
 *
 * Interaction is the module's established long-press-then-drag (see [AppShortcutsPage]): the drag
 * consumes the pointer stream, which keeps the page's own scroll container from fighting it. The
 * row itself follows the finger through `graphicsLayer`, and the list scrolls by itself while the
 * row is held near a viewport edge, so a slot can be dragged past the visible area in one gesture.
 */
@Composable
private fun SlotOrderList(
    slots: List<String>,
    scrollState: ScrollState,
    viewport: OrderViewport,
    onReorder: (List<String>) -> Unit
) {
    val rowHeightPx = with(LocalDensity.current) { ORDER_ROW_HEIGHT.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var listTopInRoot by remember { mutableFloatStateOf(0f) }

    val viewportHeight = viewport.bottom - viewport.top
    val edge = viewportHeight * AUTO_SCROLL_EDGE_FRACTION
    val pointerInRoot = listTopInRoot + dragIndex * rowHeightPx + dragOffsetY + rowHeightPx / 2f
    val autoScroll = when {
        dragIndex < 0 || viewportHeight <= 0f -> 0
        pointerInRoot < viewport.top + edge -> -1
        pointerInRoot > viewport.bottom - edge -> 1
        else -> 0
    }

    LaunchedEffect(dragIndex, autoScroll) {
        if (dragIndex < 0 || autoScroll == 0) return@LaunchedEffect
        while (isActive) {
            val consumed = scrollState.scrollBy(autoScroll * AUTO_SCROLL_STEP_PX)
            if (consumed == 0f) break
            // The scroll moves the row's slot too; adding the same delta back keeps the row under
            // the finger and keeps the committed index consistent with the visible order.
            dragOffsetY += consumed
            delay(AUTO_SCROLL_FRAME_MS)
        }
    }

    val virtualIndex = if (dragIndex >= 0) {
        (dragIndex + (dragOffsetY / rowHeightPx).roundToInt()).coerceIn(0, slots.lastIndex)
    } else {
        -1
    }

    Column(
        Modifier.fillMaxWidth().onGloballyPositioned {
            listTopInRoot = it.boundsInRoot().top
        }
    ) {
        slots.forEachIndexed { index, slot ->
            val dragging = index == dragIndex
            val shift = when {
                dragging || dragIndex !in slots.indices -> 0f
                index in (dragIndex + 1)..virtualIndex -> -rowHeightPx
                index in virtualIndex until dragIndex -> rowHeightPx
                else -> 0f
            }
            SlotOrderRow(
                slot = slot,
                dragging = dragging,
                translationY = if (dragging) dragOffsetY else shift,
                onDragStart = {
                    dragIndex = index
                    dragOffsetY = 0f
                },
                onDrag = { deltaY -> dragOffsetY += deltaY },
                onDragEnd = {
                    val target = (
                        dragIndex + (dragOffsetY / rowHeightPx).roundToInt()
                        ).coerceIn(0, slots.lastIndex)
                    if (target != dragIndex) {
                        onReorder(slots.toMutableList().apply { add(target, removeAt(dragIndex)) })
                    }
                    dragIndex = -1
                    dragOffsetY = 0f
                }
            )
        }
    }
}

@Composable
private fun SlotOrderRow(
    slot: String,
    dragging: Boolean,
    translationY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val info = IconSlotCatalog.of(slot)
    Box(
        Modifier.fillMaxWidth()
            .height(ORDER_ROW_HEIGHT)
            .zIndex(if (dragging) 10f else 0f)
            .graphicsLayer {
                this.translationY = translationY
                alpha = if (dragging) 0.92f else 1f
                shadowElevation = if (dragging) 8f else 0f
            }
            .background(
                if (dragging) MiuixTheme.colorScheme.surfaceContainerHigh
                else MiuixTheme.colorScheme.surfaceContainer
            )
            .pointerInput(slot) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                        ?: return@awaitEachGesture
                    longPress.consume()
                    onDragStart()
                    drag(longPress.id) { change ->
                        change.consume()
                        onDrag(change.position.y - change.previousPosition.y)
                    }
                    onDragEnd()
                }
            }
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = info?.icon ?: IconSlotCatalog.fallbackIcon(),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (info != null) {
                    stringResource(info.labelRes)
                } else {
                    IconSlotCatalog.fallbackLabel(slot)
                },
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = MiuixIcons.Sort,
                contentDescription = stringResource(R.string.icon_order_drag_handle),
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }
    }
}
