package com.takekazex.hypertweak.ui.page.systemui.controlcenter

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppLazyPage
import com.takekazex.hypertweak.ui.page.systemui.icon.IconSlotCatalog
import com.takekazex.hypertweak.ui.page.systemui.icon.SlotRowGlyph
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Per-slot row selection shares the runtime policy; unknown stored slots survive every edit. */
@Composable
fun ControlCenterIconRowsPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current
    val listState = rememberLazyListState()
    var entries by rememberSaveable { mutableStateOf(settingsStore.getStringSet(SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES, emptySet()).toList()) }
    val choices = listOf(stringResource(R.string.icon_cc_row_first), stringResource(R.string.icon_cc_row_second))
    AppLazyPage(
        title = stringResource(R.string.icon_cc_rows_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.icon_back),
        state = listState,
    ) {
        item { SmallTitle(stringResource(R.string.icon_cc_rows_summary)) }
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
                        settingsStore.putStringSet(SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES, updated)
                    }
                )
            }
        }
    }
}
