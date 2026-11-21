package com.takekazex.hypertweak.ui.page.platform

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.OtaNotificationReplay
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun HtmlViewerPage(onBack: () -> Unit) {
    PurificationAppPage(stringResource(R.string.html_viewer_section), OtaNotificationReplay.VIEWER, onBack) {
        val context = LocalContext.current
        val settings = LocalAppSettingsStore.current
        val key = PlatformSettingsKeys.KEY_HTML_VIEWER_MILLET
        var allowFreeze by rememberStoredSetting(key, settings::putBoolean) { settings.getBoolean(key) }
        var replaying by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        MissingScopeCard(OtaNotificationReplay.SETTINGS)
        AppSection {
            SwitchPreference(
                checked = allowFreeze,
                onCheckedChange = { allowFreeze = it },
                title = stringResource(R.string.html_viewer_millet_title),
                summary = stringResource(R.string.html_viewer_millet_summary),
            )
            ArrowPreference(
                title = stringResource(R.string.ota_banner_replay_title),
                summary = stringResource(R.string.ota_banner_replay_summary),
                enabled = !replaying,
                onClick = {
                    replaying = true
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val uri = Uri.parse("content://${OtaNotificationReplay.SETTINGS_AUTHORITY}")
                                fun request() = try {
                                    context.contentResolver.call(uri, OtaNotificationReplay.METHOD, null, null)
                                        ?.takeIf { it.containsKey(OtaNotificationReplay.RESULT) }
                                        ?.getInt(OtaNotificationReplay.RESULT)
                                } catch (error: RuntimeException) {
                                    DebugLog.w("OtaNotificationReplay", "Settings request unavailable", error)
                                    null
                                }
                                request() ?: run {
                                    // Give either host time to finish resolving its hook after a cold launch.
                                    delay(2000)
                                    request()
                                }
                            }
                            val message = when (result) {
                                OtaNotificationReplay.POSTED -> null
                                OtaNotificationReplay.BLOCKED -> R.string.ota_notification_blocked
                                OtaNotificationReplay.UNAVAILABLE -> R.string.ota_banner_unavailable
                                else -> R.string.ota_notification_scope_unavailable
                            }
                            if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } finally {
                            replaying = false
                        }
                    }
                },
            )
        }
    }
}
