package com.takekazex.hypertweak.ui.page.market

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.MarketSettingsKeys as Keys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.page.platform.PurificationAppPage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun MarketPage(onBack: () -> Unit) {
    PurificationAppPage(stringResource(R.string.market_title), Keys.PACKAGE, onBack) {
        val store = LocalAppSettingsStore.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        val debugRevision by com.takekazex.hypertweak.util.diagnostics.DebugSettingsEvents.revision.collectAsState()
        var values by remember { mutableStateOf(Keys.keys.associateWith { store.getBoolean(it, false) }) }
        LaunchedEffect(lifecycle, debugRevision) {
            if (lifecycle == Lifecycle.State.RESUMED) values = Keys.keys.associateWith { store.getBoolean(it, false) }
        }
        SmallTitle(stringResource(R.string.market_options))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column {
                SwitchPreference(checked = values[Keys.KEY_MARKET_SPLASH] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_SPLASH, it); values = values + (Keys.KEY_MARKET_SPLASH to it) },
                    title = stringResource(R.string.market_splash), summary = stringResource(R.string.market_splash_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_DETAIL] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_DETAIL, it); values = values + (Keys.KEY_MARKET_DETAIL to it) },
                    title = stringResource(R.string.market_detail), summary = stringResource(R.string.market_detail_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_VIDEO] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_VIDEO, it); values = values + (Keys.KEY_MARKET_VIDEO to it) },
                    title = stringResource(R.string.market_video), summary = stringResource(R.string.market_video_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_SEARCH_SUGGEST] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_SEARCH_SUGGEST, it); values = values + (Keys.KEY_MARKET_SEARCH_SUGGEST to it) },
                    title = stringResource(R.string.market_search_suggest), summary = stringResource(R.string.market_search_suggest_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_HOME] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_HOME, it); values = values + (Keys.KEY_MARKET_HOME to it) },
                    title = stringResource(R.string.market_home), summary = stringResource(R.string.market_home_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_UPDATE] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_UPDATE, it); values = values + (Keys.KEY_MARKET_UPDATE to it) },
                    title = stringResource(R.string.market_update), summary = stringResource(R.string.market_update_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_MINE] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_MINE, it); values = values + (Keys.KEY_MARKET_MINE to it) },
                    title = stringResource(R.string.market_mine), summary = stringResource(R.string.market_mine_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_PUSH_DIALOG] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_PUSH_DIALOG, it); values = values + (Keys.KEY_MARKET_PUSH_DIALOG to it) },
                    title = stringResource(R.string.market_push_dialog), summary = stringResource(R.string.market_push_dialog_summary))
                SwitchPreference(checked = values[Keys.KEY_MARKET_MARKETING_ICON] == true,
                    onCheckedChange = { store.putBoolean(Keys.KEY_MARKET_MARKETING_ICON, it); values = values + (Keys.KEY_MARKET_MARKETING_ICON to it) },
                    title = stringResource(R.string.market_marketing_icon), summary = stringResource(R.string.market_marketing_icon_summary))
            }
        }
        SmallTitle(stringResource(R.string.market_live_summary))
    }
}
