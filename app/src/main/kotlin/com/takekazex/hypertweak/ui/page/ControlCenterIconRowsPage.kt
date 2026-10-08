package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy
import com.takekazex.hypertweak.util.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Per-slot row selection shares the runtime policy; unknown stored slots survive every edit. */
@Composable
fun ControlCenterIconRowsPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val requestRestartScopes = LocalRestartScopeRequest.current
    var entries by rememberSaveable {
        mutableStateOf(Preferences.getStringSet(Preferences.KEY_CC_ICON_ROW_OVERRIDES, emptySet()).toList())
    }
    val choices = listOf(stringResource(R.string.icon_cc_row_first), stringResource(R.string.icon_cc_row_second))
    Scaffold(topBar = {
        TopAppBar(title = stringResource(R.string.icon_cc_rows_title), scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.icon_back)) }
            })
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)) {
            item {
                Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
                SmallTitle(stringResource(R.string.icon_cc_rows_summary))
            }
            items(IconSlotCatalog.orderSlots, key = { it }) { slot ->
                val info = IconSlotCatalog.bySlot.getValue(slot)
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    OverlayDropdownPreference(
                        title = stringResource(info.labelRes), items = choices,
                        startAction = {
                            SlotRowGlyph(
                                iconRes = info.iconRes,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        },
                        selectedIndex = IconSlotPolicy.controlCenterRow(slot, entries.toSet()) - 1,
                        onSelectedIndexChange = { index ->
                            val updated = IconSlotPolicy.withControlCenterRow(entries.toSet(), slot, index + 1)
                            entries = updated.toList()
                            Preferences.putStringSet(Preferences.KEY_CC_ICON_ROW_OVERRIDES, updated)
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp)) }
        }
    }
}
