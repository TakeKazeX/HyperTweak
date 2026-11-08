package com.takekazex.hypertweak.ui.page.wifi

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun WifiDetailsControls() {
    val settingsStore = LocalAppSettingsStore.current
    var ipMultiline by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_WIFI_DETAIL_IP_MULTILINE, false))
    }
    var savedNetworksPosition by remember {
        mutableStateOf(
            settingsStore.getInt(
                PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORKS_ENTRY_POSITION,
                PlatformSettingsKeys.WIFI_SAVED_NETWORKS_POSITION_DEFAULT
            )
        )
    }
    var customActionsEnabled by remember {
        mutableStateOf(
            settingsStore.getBoolean(
                PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORK_CUSTOM_ACTIONS,
                false
            )
        )
    }

    val positionItems = listOf(
        stringResource(R.string.wifi_saved_networks_position_default),
        stringResource(R.string.wifi_saved_networks_position_accel),
        stringResource(R.string.wifi_saved_networks_position_advanced)
    )

    SmallTitle(stringResource(R.string.wifi_details_section_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = ipMultiline,
                onCheckedChange = { enabled ->
                    ipMultiline = enabled
                    settingsStore.putBoolean(PlatformSettingsKeys.KEY_WIFI_DETAIL_IP_MULTILINE, enabled)
                },
                title = stringResource(R.string.wifi_detail_ip_multiline_title),
                summary = stringResource(R.string.wifi_detail_ip_multiline_summary)
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.wifi_saved_networks_entry_title),
                summary = stringResource(R.string.wifi_saved_networks_entry_summary),
                items = positionItems,
                selectedIndex = savedNetworksPosition.coerceIn(0, 2),
                onSelectedIndexChange = { index ->
                    savedNetworksPosition = index
                    settingsStore.putInt(PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORKS_ENTRY_POSITION, index)
                }
            )
            SwitchPreference(
                checked = customActionsEnabled,
                onCheckedChange = { enabled ->
                    customActionsEnabled = enabled
                    settingsStore.putBoolean(PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORK_CUSTOM_ACTIONS, enabled)
                },
                title = stringResource(R.string.wifi_saved_network_custom_actions_title),
                summary = stringResource(R.string.wifi_saved_network_custom_actions_summary)
            )
        }
    }

}
