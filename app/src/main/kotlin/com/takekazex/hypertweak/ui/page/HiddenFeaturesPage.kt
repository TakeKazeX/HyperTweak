package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun HiddenFeaturesPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.hidden__page_title),
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.hidden__back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SmallTitle(text = stringResource(R.string.hidden__quick_shortcuts))

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.hidden__developer_settings),
                        summary = stringResource(R.string.hidden__developer_settings_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Code,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__developer_settings),
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            val intent = Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                            launchSafe(intent, emptyList())
                        }
                    )

                    ArrowPreference(
                        title = stringResource(R.string.hidden__google_services),
                        summary = stringResource(R.string.hidden__google_services_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__google_services),
                                tint = MiuixTheme.colorScheme.primary
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
                                imageVector = Icons.Rounded.BugReport,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__fcm_debug),
                                tint = MiuixTheme.colorScheme.primary
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
            }

            SmallTitle(text = stringResource(R.string.hidden__app_shortcuts))

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.hidden__lsposed_manager),
                        summary = stringResource(R.string.hidden__lsposed_manager_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Extension,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__lsposed_manager),
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            Thread {
                                try {
                                    Runtime.getRuntime().exec("su").outputStream.bufferedWriter().use { w ->
                                        w.write("am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733\nexit\n")
                                        w.flush()
                                    }
                                } catch (e: Exception) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, context.getString(R.string.hidden__run_shell_failed, e.message), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        }
                    )

                    ArrowPreference(
                        title = stringResource(R.string.hidden__installerx_revived),
                        summary = stringResource(R.string.hidden__installerx_revived_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.InstallMobile,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__installerx_revived),
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            val intent1 = Intent().setClassName("com.android.packageinstaller", "com.rosan.installer.ui.activity.SettingsActivity")
                            val intent2 = Intent().setClassName("com.rosan.installer.x.revived", "com.rosan.installer.ui.activity.SettingsActivity")
                            launchSafe(intent1, listOf(intent2))
                        }
                    )
                }
            }

            SmallTitle(text = stringResource(R.string.hidden__page_title))

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.hidden__extra_dim),
                        summary = stringResource(R.string.hidden__extra_dim_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.BrightnessMedium,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__extra_dim),
                                tint = MiuixTheme.colorScheme.primary
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
                                imageVector = Icons.Rounded.BatteryChargingFull,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__battery_optimization),
                                tint = MiuixTheme.colorScheme.primary
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
                                imageVector = Icons.Rounded.PrivacyTip,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__privacy),
                                tint = MiuixTheme.colorScheme.primary
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
                                imageVector = Icons.Rounded.Memory,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__running_services),
                                tint = MiuixTheme.colorScheme.primary
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
                                imageVector = Icons.Rounded.Notifications,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__notification_settings),
                                tint = MiuixTheme.colorScheme.primary
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
                                imageVector = Icons.Rounded.Apps,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__manage_applications),
                                tint = MiuixTheme.colorScheme.primary
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
                                imageVector = Icons.Rounded.SettingsSuggest,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.hidden__default_apps),
                                tint = MiuixTheme.colorScheme.primary
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

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}