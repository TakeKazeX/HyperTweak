package com.takekazex.hypertweak.ui.page.platform

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Battery90Charging
import top.yukonga.miuix.kmp.icon.glass.Contacts
import top.yukonga.miuix.kmp.icon.glass.DisplayBrightness
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.HtmlViewer
import top.yukonga.miuix.kmp.icon.glass.Install
import top.yukonga.miuix.kmp.icon.glass.Log
import top.yukonga.miuix.kmp.icon.glass.NotificationOutline
import top.yukonga.miuix.kmp.icon.glass.PrivacySecurity
import top.yukonga.miuix.kmp.icon.glass.Ram
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.icon.glass.Widget
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun HiddenFeaturesPage(
    onBack: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launchSafe = { intent: Intent, fallbacks: List<Intent> ->
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            var launched = false
            for (fb in fallbacks) {
                try {
                    fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fb)
                    launched = true
                    break
                } catch (ignored: Exception) {
                }
            }
            if (!launched) {
                Toast.makeText(context, context.getString(R.string.hidden__unable_to_open_settings), Toast.LENGTH_SHORT).show()
            }
        }
    }

    AppScrollPage(
        title = stringResource(R.string.hidden__page_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.hidden__back),
    ) {

        AppSection(stringResource(R.string.hidden__quick_shortcuts)) {
            ArrowPreference(
                title = stringResource(R.string.hidden__developer_settings),
                summary = stringResource(R.string.hidden__developer_settings_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.HtmlViewer,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__developer_settings),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                // Opens this module's own developer settings page (the same one the Debug
                // page links to); the full system developer options are reachable from there.
                onClick = onNavigateToDeveloperSettings
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__google_services),
                summary = stringResource(R.string.hidden__google_services_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Contacts,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__google_services),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent = Intent().apply {
                        component = ComponentName("com.google.android.gms", "com.google.android.gms.app.settings.GoogleSettingsIALink")
                    }
                    val fallback = Intent("com.google.android.gms.settings.SETTINGS")
                    launchSafe(intent, listOf(fallback))
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__fcm_debug),
                summary = stringResource(R.string.hidden__fcm_debug_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Log,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__fcm_debug),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent1 = Intent().apply {
                        component = ComponentName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics")
                    }
                    val intent2 = Intent().apply {
                        component = ComponentName("com.google.android.gms", "com.google.android.gms.chimera.GmsIntentOperationService")
                    }
                    launchSafe(intent1, listOf(intent2))
                }
            )
        }

        AppSection(stringResource(R.string.hidden__app_shortcuts)) {
            ArrowPreference(
                title = stringResource(R.string.hidden__lsposed_manager),
                summary = stringResource(R.string.hidden__lsposed_manager_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Widget,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__lsposed_manager),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    scope.launch {
                        val failure = withContext(Dispatchers.IO) {
                            runCatching {
                                Runtime.getRuntime().exec("su").outputStream.bufferedWriter().use { w ->
                                    w.write("am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733\nexit\n")
                                    w.flush()
                                }
                            }.exceptionOrNull()
                        }
                        if (failure != null) {
                            Toast.makeText(context, context.getString(R.string.hidden__run_shell_failed, failure.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__installerx_revived),
                summary = stringResource(R.string.hidden__installerx_revived_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Install,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__installerx_revived),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent1 = Intent().setClassName("com.android.packageinstaller", "com.rosan.installer.ui.activity.SettingsActivity")
                    val intent2 = Intent().setClassName("com.rosan.installer.x.revived", "com.rosan.installer.ui.activity.SettingsActivity")
                    launchSafe(intent1, listOf(intent2))
                }
            )
        }

        AppSection(stringResource(R.string.hidden__page_title)) {
            ArrowPreference(
                title = stringResource(R.string.hidden__extra_dim),
                summary = stringResource(R.string.hidden__extra_dim_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.DisplayBrightness,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__extra_dim),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.Settings\$ReduceBrightColorsSettingsActivity")
                    }
                    val fallback = Intent("android.settings.ACCESSIBILITY_SETTINGS")
                    launchSafe(intent, listOf(fallback))
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__battery_optimization),
                summary = stringResource(R.string.hidden__battery_optimization_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Battery90Charging,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__battery_optimization),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                    launchSafe(intent, emptyList())
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__privacy),
                summary = stringResource(R.string.hidden__privacy_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.PrivacySecurity,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__privacy),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent = Intent(Settings.ACTION_PRIVACY_SETTINGS)
                    val fallback = Intent("android.settings.SETTINGS")
                    launchSafe(intent, listOf(fallback))
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__running_services),
                summary = stringResource(R.string.hidden__running_services_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Ram,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__running_services),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent1 = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.SubSettings")
                        putExtra(":settings:show_fragment", "com.android.settings.applications.RunningServices")
                    }
                    val intent2 = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.Settings\$DevRunningServicesActivity")
                    }
                    val intent3 = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.Settings\$RunningServicesActivity")
                    }
                    val fallback = Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                    launchSafe(intent1, listOf(intent2, intent3, fallback))
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__notification_settings),
                summary = stringResource(R.string.hidden__notification_settings_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.NotificationOutline,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__notification_settings),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.Settings\$ConfigureNotificationSettingsActivity")
                    }
                    val fallback = Intent("android.settings.NOTIFICATION_SETTINGS")
                    launchSafe(intent, listOf(fallback))
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__manage_applications),
                summary = stringResource(R.string.hidden__manage_applications_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.GridView,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__manage_applications),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent1 = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")
                    }
                    val intent2 = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.Settings\$DevRunningServicesActivity")
                    }
                    val intent3 = Intent().apply {
                        component = ComponentName("com.android.settings", "com.android.settings.Settings\$RunningServicesActivity")
                    }
                    val fallback = Intent("android.settings.MANAGE_APPLICATIONS_SETTINGS")
                    launchSafe(intent1, listOf(intent2, intent3, fallback))
                }
            )

            ArrowPreference(
                title = stringResource(R.string.hidden__default_apps),
                summary = stringResource(R.string.hidden__default_apps_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.SettingsOutline,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.hidden__default_apps),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    val intent = Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
                    val fallback = Intent("android.settings.SETTINGS")
                    launchSafe(intent, listOf(fallback))
                }
            )
        }
    }
}
