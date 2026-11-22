package com.takekazex.hypertweak.ui.page.phrase

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PhraseSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.page.platform.PurificationAppPage
import com.takekazex.hypertweak.util.diagnostics.DebugSettingsEvents
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun PhrasePage(onBack: () -> Unit) {
    val store = LocalAppSettingsStore.current
    val keys = PhraseSettingsKeys
    var allowIme by remember { mutableStateOf(false) }
    var unlockPhrases by remember { mutableStateOf(false) }
    var customLimits by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(PhraseSettingsKeys.DEFAULT_COUNT) }
    var days by remember { mutableIntStateOf(PhraseSettingsKeys.DEFAULT_DAYS) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val revision by DebugSettingsEvents.revision.collectAsStateWithLifecycle()
    LaunchedEffect(store, lifecycle, revision) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            allowIme = store.getBoolean(PhraseSettingsKeys.KEY_ALLOW_IME_HISTORY, false)
            unlockPhrases = store.getBoolean(PhraseSettingsKeys.KEY_UNLOCK_LIMITS, false)
            customLimits = store.getBoolean(PhraseSettingsKeys.KEY_CLIPBOARD_LIMITS, false)
            count = keys.normalizeCount(store.getInt(PhraseSettingsKeys.KEY_CLIPBOARD_COUNT, PhraseSettingsKeys.DEFAULT_COUNT))
            days = keys.normalizeDays(store.getInt(PhraseSettingsKeys.KEY_CLIPBOARD_DAYS, PhraseSettingsKeys.DEFAULT_DAYS))
        }
    }
    PurificationAppPage(stringResource(R.string.phrase_title), keys.PACKAGE, onBack) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            SwitchPreference(
                title = stringResource(R.string.phrase_ime_history_title),
                summary = stringResource(R.string.phrase_ime_history_summary),
                checked = allowIme,
                onCheckedChange = { value ->
                    store.putBoolean(keys.KEY_ALLOW_IME_HISTORY, value)
                    allowIme = value
                },
            )
            SwitchPreference(
                title = stringResource(R.string.phrase_unlock_title),
                summary = stringResource(R.string.phrase_unlock_summary),
                checked = unlockPhrases,
                onCheckedChange = { value ->
                    store.putBoolean(keys.KEY_UNLOCK_LIMITS, value)
                    unlockPhrases = value
                },
            )
            SwitchPreference(
                title = stringResource(R.string.phrase_clipboard_limits_title),
                summary = stringResource(R.string.phrase_clipboard_limits_summary),
                checked = customLimits,
                onCheckedChange = { value ->
                    store.putBoolean(keys.KEY_CLIPBOARD_LIMITS, value)
                    customLimits = value
                },
            )
            MiuixExpandableContent(visible = customLimits) {
                AppDropdownPreference(
                    title = stringResource(R.string.phrase_clipboard_count),
                    items = keys.clipboardCounts.map { stringResource(R.string.phrase_clipboard_count_item, it) },
                    selectedIndex = keys.clipboardCounts.indexOf(count),
                    onSelectedIndexChange = { index ->
                        count = keys.clipboardCounts[index]
                        store.putInt(keys.KEY_CLIPBOARD_COUNT, count)
                    },
                )
                AppDropdownPreference(
                    title = stringResource(R.string.phrase_clipboard_retention),
                    summary = stringResource(R.string.phrase_clipboard_retention_scope),
                    items = listOf(
                        stringResource(R.string.phrase_clipboard_three_days),
                        stringResource(R.string.phrase_clipboard_week),
                        stringResource(R.string.phrase_clipboard_month),
                        stringResource(R.string.phrase_clipboard_three_months),
                    ),
                    selectedIndex = keys.clipboardDays.indexOf(days),
                    onSelectedIndexChange = { index ->
                        days = keys.clipboardDays[index]
                        store.putInt(keys.KEY_CLIPBOARD_DAYS, days)
                    },
                )
            }
        }
    }
}
