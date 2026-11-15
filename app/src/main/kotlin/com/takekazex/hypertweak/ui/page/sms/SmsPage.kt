package com.takekazex.hypertweak.ui.page.sms

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.SmsSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.page.platform.PurificationAppPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SmsPage(onBack: () -> Unit) {
    val store = LocalAppSettingsStore.current
    var enabled by remember { mutableStateOf(store.getBoolean(SmsSettingsKeys.KEY_SMS_AUTO_COPY_CODE, false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val revision by com.takekazex.hypertweak.util.diagnostics.DebugSettingsEvents.revision.collectAsState()
    LaunchedEffect(lifecycle, revision) {
        if (lifecycle == Lifecycle.State.RESUMED) enabled = store.getBoolean(SmsSettingsKeys.KEY_SMS_AUTO_COPY_CODE, false)
    }
    PurificationAppPage(stringResource(R.string.sms_title), SmsSettingsKeys.PACKAGE, onBack) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            SwitchPreference(
                title = stringResource(R.string.sms_auto_copy_title),
                summary = stringResource(R.string.sms_auto_copy_summary),
                checked = enabled,
                onCheckedChange = { value ->
                    store.putBoolean(SmsSettingsKeys.KEY_SMS_AUTO_COPY_CODE, value)
                    enabled = value
                }
            )
        }
        SmallTitle(stringResource(R.string.sms_live_summary))
    }
}
