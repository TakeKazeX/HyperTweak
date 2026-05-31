package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.util.ShortcutUtils
import kotlin.math.roundToInt

private val DEFAULT_ORDER = listOf("lsposed", "installerx", "dev_settings")

@Composable
private fun DraggableItem(
    id: String,
    label: String,
    checked: Boolean,
    isDragging: Boolean,
    translationY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                this.translationY = translationY
                alpha = if (isDragging) 0.92f else 1f
                shadowElevation = if (isDragging) 8f else 0f
            }
            .background(
                if (isDragging) MiuixTheme.colorScheme.surfaceContainerHigh
                else MiuixTheme.colorScheme.surfaceContainer
            )
            .pointerInput(id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                        ?: return@awaitEachGesture
                    longPress.consume()
                    var totalY = 0f
                    onDragStart()
                    drag(longPress.id) { change ->
                        change.consume()
                        val dy = change.position.y - change.previousPosition.y
                        totalY += dy
                        onDrag(dy)
                    }
                    onDragEnd(totalY)
                }
            }
    ) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = label
        )
    }
}

@Composable
fun AppShortcutsPage(
    onBack: () -> Unit,
    onShortcutsChanged: () -> Unit
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val allShortcuts = remember { ShortcutUtils.getAvailableShortcuts() }
    val itemHeightPx = with(LocalDensity.current) { 56.dp.toPx() }

    var orderedIds by remember {
        val ordered = ShortcutUtils.getOrderedList()
        val full = ordered + (allShortcuts.map { it.id } - ordered.toSet())
        mutableStateOf(full)
    }
    var enabledIds by remember { mutableStateOf(ShortcutUtils.getEnabledShortcutIds()) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    fun save() {
        val enabledOrdered = orderedIds.filter { it in enabledIds }
        ShortcutUtils.saveOrder(enabledOrdered)
        onShortcutsChanged()
    }

    fun reset() {
        val all = allShortcuts.map { it.id }
        orderedIds = DEFAULT_ORDER + (all - DEFAULT_ORDER.toSet())
        enabledIds = DEFAULT_ORDER.toSet()
        save()
    }

    val virtualOffset = if (dragIndex >= 0) (dragOffsetY / itemHeightPx).roundToInt() else 0
    val virtualIndex = dragIndex + virtualOffset

    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "App Shortcuts",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reset() }, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reset",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .overScrollVertical(),
        ) {
            item("spacer_top") { Spacer(modifier = Modifier.height(8.dp)) }
            item("title") {
                SmallTitle(text = "Long press to drag, toggle to enable (max 5)")
            }
            item("card") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        orderedIds.forEachIndexed { index, id ->
                            val def = allShortcuts.find { it.id == id } ?: return@forEachIndexed
                            val isDragged = index == dragIndex
                            val shift = when {
                                !isDragged && dragIndex in 0 until orderedIds.size && index in (dragIndex + 1)..virtualIndex -> -itemHeightPx
                                !isDragged && dragIndex in 0 until orderedIds.size && index in virtualIndex until dragIndex -> itemHeightPx
                                else -> 0f
                            }
                            DraggableItem(
                                id = id,
                                label = def.label,
                                checked = id in enabledIds,
                                isDragging = isDragged,
                                translationY = if (isDragged) dragOffsetY else shift,
                                onDragStart = {
                                    dragIndex = index
                                    dragOffsetY = 0f
                                },
                                onDrag = { deltaY ->
                                    dragOffsetY += deltaY
                                },
                                onDragEnd = { totalY ->
                                    val offset = (totalY / itemHeightPx).roundToInt()
                                    val fi = (dragIndex + offset).coerceIn(0, orderedIds.size - 1)
                                    if (fi != dragIndex) {
                                        val m = orderedIds.toMutableList()
                                        val item = m.removeAt(dragIndex)
                                        m.add(fi, item)
                                        orderedIds = m
                                    }
                                    dragIndex = -1
                                    dragOffsetY = 0f
                                    save()
                                },
                                onCheckedChange = { enabled ->
                                    enabledIds = if (enabled) {
                                        if (enabledIds.size >= 5) return@DraggableItem
                                        enabledIds + id
                                    } else {
                                        enabledIds - id
                                    }
                                    save()
                                }
                            )
                        }
                    }
                }
            }
            item("spacer_bottom") { Spacer(modifier = Modifier.height(48.dp)) }
        }
    }
}
