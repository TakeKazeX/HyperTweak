package com.takekazex.hypertweak.ui.page

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
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
                Toast.makeText(context, "无法打开该设置界面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "隐藏特性",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "极暗",
                        summary = "打开系统级极暗设置 (降低屏幕最低亮度)",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.BrightnessMedium,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "极暗",
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
                        title = "电池优化",
                        summary = "管理应用电池优化与后台限制",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.BatteryChargingFull,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "电池优化",
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            val intent = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                            launchSafe(intent, emptyList())
                        }
                    )

                    ArrowPreference(
                        title = "正在运行的服务",
                        summary = "查看当前正在运行的后台服务和内存占用",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "正在运行的服务",
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
                        title = "通知设置",
                        summary = "查看高级通知设置和通知历史",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "通知设置",
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
                        title = "应用管理",
                        summary = "管理已安装的所有应用和系统应用",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Apps,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "应用管理",
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
                        title = "默认应用",
                        summary = "配置系统的默认助手、浏览器、主屏幕等应用",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.SettingsSuggest,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "默认应用",
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
