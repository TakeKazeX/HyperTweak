package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.CommunitySettings
import com.takekazex.hypertweak.settings.contract.CommunitySettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.components.dialog.CenteredDialog
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun CommunitySection() {
    val store = LocalAppSettingsStore.current
    var block by remember { mutableStateOf(store.getBoolean(CommunitySettingsKeys.KEY_COMMUNITY_BLOCK_DETECTION, false)) }
    var modify by remember { mutableStateOf(store.getBoolean(CommunitySettingsKeys.KEY_COMMUNITY_DEVICE_MODIFY, false)) }
    var model by remember { mutableStateOf(store.getString(CommunitySettingsKeys.KEY_COMMUNITY_MODEL, "")) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val debugRevision by com.takekazex.hypertweak.util.diagnostics.DebugSettingsEvents.revision.collectAsState()
    LaunchedEffect(lifecycle, debugRevision) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            block = store.getBoolean(CommunitySettingsKeys.KEY_COMMUNITY_BLOCK_DETECTION, false)
            modify = store.getBoolean(CommunitySettingsKeys.KEY_COMMUNITY_DEVICE_MODIFY, false)
            model = store.getString(CommunitySettingsKeys.KEY_COMMUNITY_MODEL, "")
        }
    }
    SmallTitle(stringResource(R.string.community_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            SwitchPreference(checked = block, onCheckedChange = {
                store.putBoolean(CommunitySettingsKeys.KEY_COMMUNITY_BLOCK_DETECTION, it); block = it
            }, title = stringResource(R.string.community_block), summary = stringResource(R.string.community_block_summary))
            SwitchPreference(checked = modify, onCheckedChange = {
                store.putBoolean(CommunitySettingsKeys.KEY_COMMUNITY_DEVICE_MODIFY, it); modify = it
            }, title = stringResource(R.string.community_modify), summary = stringResource(R.string.community_modify_summary))
            if (modify) ArrowPreference(title = stringResource(R.string.community_model),
                summary = model.ifBlank { stringResource(R.string.community_model_empty) },
                onClick = { draft = model; editing = true })
        }
    }
    CenteredDialog(show = editing, title = stringResource(R.string.community_model),
        summary = stringResource(R.string.community_model_empty), onDismissRequest = { editing = false }) {
        TextField(value = draft, onValueChange = { draft = it }, maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(text = stringResource(R.string.scale_cancel), modifier = Modifier.weight(1f), onClick = { editing = false })
            TextButton(text = stringResource(R.string.scale_ok), modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                    model = draft.trim(); store.putString(CommunitySettingsKeys.KEY_COMMUNITY_MODEL, model); editing = false
                })
        }
    }
}
