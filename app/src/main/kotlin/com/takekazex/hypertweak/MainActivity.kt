package com.takekazex.hypertweak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class Screen {
    MAIN,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    // Intercepted by ModuleStatusHooker. Keep annotation prevents R8 optimization/inlining.
    @Keep
    fun isModuleActive(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Init connection to LSPosed preferences
        XposedServiceManager.init()

        setContent {
            MiuixTheme {
                val serviceConnected by XposedServiceManager.serviceFlow.collectAsState()

                // State variables for toggles
                var aodFullscreen by remember { mutableStateOf(false) }
                var removeGms by remember { mutableStateOf(false) }
                var hideFingerprint by remember { mutableStateOf(false) }
                var showInSettings by remember { mutableStateOf(false) }
                var hideLauncherIcon by remember { mutableStateOf(false) }

                var currentScreen by remember { mutableStateOf(Screen.MAIN) }

                // Sync UI state when Preferences are initialized or service binds
                LaunchedEffect(serviceConnected) {
                    if (Preferences.isInitialized) {
                        aodFullscreen = Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)
                        removeGms = Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)
                        hideFingerprint = Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)
                        showInSettings = Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)
                        hideLauncherIcon = Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)
                    }
                }

                val moduleActive = isModuleActive()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = if (currentScreen == Screen.MAIN) "Ink Tweaks" else "Settings",
                            navigationIcon = {
                                if (currentScreen == Screen.SETTINGS) {
                                    IconButton(
                                        onClick = { currentScreen = Screen.MAIN }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowBack,
                                            contentDescription = "Back",
                                            tint = MiuixTheme.colorScheme.onSurfaceSecondary
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (currentScreen == Screen.MAIN) {
                                    IconButton(
                                        onClick = { currentScreen = Screen.SETTINGS }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            tint = MiuixTheme.colorScheme.onSurfaceSecondary
                                        )
                                    }
                                }
                            }
                        )
                    }
                ) { padding ->
                    if (currentScreen == Screen.MAIN) {
                        MainScreenContent(
                            padding = padding,
                            moduleActive = moduleActive,
                            aodFullscreen = aodFullscreen,
                            onAodFullscreenChange = { checked: Boolean ->
                                aodFullscreen = checked
                                Preferences.putBoolean(Preferences.KEY_AOD_FULLSCREEN, checked)
                            },
                            removeGms = removeGms,
                            onRemoveGmsChange = { checked: Boolean ->
                                removeGms = checked
                                Preferences.putBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, checked)
                            },
                            hideFingerprint = hideFingerprint,
                            onHideFingerprintChange = { checked: Boolean ->
                                hideFingerprint = checked
                                Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT, checked)
                            }
                        )
                    } else {
                        SettingsScreenContent(
                            padding = padding,
                            showInSettings = showInSettings,
                            onShowInSettingsChange = { checked: Boolean ->
                                showInSettings = checked
                                Preferences.putBoolean(Preferences.KEY_SHOW_IN_SETTINGS, checked)
                            },
                            hideLauncherIcon = hideLauncherIcon,
                            onHideLauncherIconChange = { checked: Boolean ->
                                hideLauncherIcon = checked
                                Preferences.putBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, checked)
                                setLauncherIconVisible(this@MainActivity, !checked)
                            },
                            packageName = packageName,
                            targetSdk = applicationInfo.targetSdkVersion
                        )
                    }
                }
            }
        }
    }

    private fun setLauncherIconVisible(context: Context, visible: Boolean) {
        try {
            val pm = context.packageManager
            val componentName = ComponentName(context, "com.takekazex.hypertweak.MainActivityAlias")
            val state = if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            // Ignore
        }
    }
}

@Composable
fun MainScreenContent(
    padding: PaddingValues,
    moduleActive: Boolean,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    hideFingerprint: Boolean,
    onHideFingerprintChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = if (moduleActive) "Module is ACTIVE" else "Module is NOT ACTIVE",
                    color = Color(if (moduleActive) 0xFF4CAF50 else 0xFFF44336),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (moduleActive) 
                        "Native libxposed module loaded successfully." 
                    else 
                        "Please enable the module in LSPosed manager and reboot your device.",
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    fontSize = 13.sp
                )
            }
        }

        // Settings Header
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "Features Configuration",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary
            )
        }

        // Settings Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Toggle 1: AOD Fullscreen
                SettingSwitchRow(
                    title = "Always-On Display Fullscreen",
                    summary = "Unlock full screen background support for AOD",
                    checked = aodFullscreen,
                    onCheckedChange = onAodFullscreenChange
                )

                // Toggle 2: Remove GMS Restrictions
                SettingSwitchRow(
                    title = "Bypass GMS China ROM Restrictions",
                    summary = "Remove Google Play Services installation restrictions on Chinese firmware",
                    checked = removeGms,
                    onCheckedChange = onRemoveGmsChange
                )

                // Toggle 3: Hide Lockscreen Fingerprint Icon
                SettingSwitchRow(
                    title = "Hide Lockscreen Fingerprint",
                    summary = "Completely remove the fingerprint sensor circle icon on lockscreen",
                    checked = hideFingerprint,
                    onCheckedChange = onHideFingerprintChange
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingsScreenContent(
    padding: PaddingValues,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    packageName: String,
    targetSdk: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Module Preferences Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingSwitchRow(
                    title = "Show Entry in System Settings",
                    summary = "Inject an entry point for Ink Tweaks in the system Settings app",
                    checked = showInSettings,
                    onCheckedChange = onShowInSettingsChange
                )

                SettingSwitchRow(
                    title = "Hide Desktop Icon",
                    summary = "Hide launcher icon (access module via LSPosed or system settings)",
                    checked = hideLauncherIcon,
                    onCheckedChange = onHideLauncherIconChange
                )
            }
        }

        // Diagnostics Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "System Diagnostics",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Module Package: $packageName", fontSize = 12.sp)
                Text(text = "Target SDK: $targetSdk", fontSize = 12.sp)
                Text(text = "Device Android Version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})", fontSize = 12.sp)
            }
        }

        // Credits Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Acknowledgements & Credits",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "• libxposed: Native Xposed API 101 framework", fontSize = 12.sp)
                Text(text = "• KavaRef: HighCapable Kotlin reflection library", fontSize = 12.sp)
                Text(text = "• Miuix UI: Modern MIUI/HyperOS style Compose components", fontSize = 12.sp)
                Text(text = "• HyperOShape: Base logic for fingerprint icon drawing bypass", fontSize = 12.sp)
                Text(text = "• XiaomiHelper / HyperCeiler: References for Settings preference injection", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
