package com.takekazex.hypertweak.ui.page

import android.content.ComponentName
import android.content.Intent
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
import androidx.compose.ui.unit.dp
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
                Toast.makeText(context, "Unable to open this settings page", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Hidden Features",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
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

            SmallTitle(text = "Quick Shortcuts")

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "Developer Settings",
                        summary = "Open developer options and USB debugging settings",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Code,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Developer Settings",
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            val intent = Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                            launchSafe(intent, emptyList())
                        }
                    )

                    ArrowPreference(
                        title = "Google Services",
                        summary = "Open Google Play Services settings and account management",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Google Services",
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
                        title = "FCM Debug",
                        summary = "Open Firebase Cloud Messaging diagnostic and registration status",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.BugReport,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "FCM Debug",
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

            SmallTitle(text = "App Shortcuts")

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "LSPosed Manager",
                        summary = "Open LSPosed framework manager (*#*#5776733#*#*)",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Extension,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "LSPosed Manager",
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            Thread {
                                try {
                                    val action = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "android.telephony.action.SECRET_CODE" else "android.provider.Telephony.SECRET_CODE"
                                    Runtime.getRuntime().exec("su").outputStream.bufferedWriter().use { w ->
                                        w.write("am broadcast -a $action -d android_secret_code://5776733\nexit\n")
                                        w.flush()
                                    }
                                } catch (e: Exception) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        }
                    )

                    ArrowPreference(
                        title = "InstallerX Revived",
                        summary = "Open InstallerX package installer",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.InstallMobile,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "InstallerX Revived",
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

            SmallTitle(text = "Hidden Features")

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "Extra Dim",
                        summary = "Open system-level extra dim settings (reduce screen minimum brightness)",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.BrightnessMedium,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Extra Dim",
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
                        title = "Battery Optimization",
                        summary = "Manage app battery optimization and background restrictions",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.BatteryChargingFull,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Battery Optimization",
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            val intent = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                            launchSafe(intent, emptyList())
                        }
                    )

                    ArrowPreference(
                        title = "Running Services",
                        summary = "View currently running background services and RAM usage",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Running Services",
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
                        title = "Notification Settings",
                        summary = "View advanced notification settings and notification history",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Notification Settings",
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
                        title = "Manage Applications",
                        summary = "Manage all installed and system applications",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Apps,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Manage Applications",
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
                        title = "Default Apps",
                        summary = "Configure default assistant, browser, home screen, and other apps",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.SettingsSuggest,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Default Apps",
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
