package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.ScopeManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Unlocks watermark categories in the media editor (相册编辑, `com.miui.mediaeditor`).
 *
 * The switches are read live by the hooks, so a category toggle takes effect the next time the
 * watermark menu is opened; only the first enable of the master switch needs the editor process
 * restarted so the hooks are installed (LSPosed scope restart).
 */
@Composable
fun WatermarkPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var master by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_UNLOCK_MASTER, false))
    }
    var camera by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_CAMERA, false))
    }
    var leica by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_LEICA, false))
    }
    var xiaomi by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_XIAOMI, false))
    }
    var redmi by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_REDMI, false))
    }
    var poco by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_POCO, false))
    }
    var disney1 by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_DISNEY1, false))
    }
    var disney2 by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_DISNEY2, false))
    }
    var disney3 by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_DISNEY3, false))
    }
    var victoria by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_VICTORIA, false))
    }
    var lcc by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_LCC, false))
    }
    var downloadAll by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_WM_DOWNLOAD_ALL, false))
    }

    fun set(key: String, value: Boolean) {
        Preferences.putBoolean(key, value)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = "Watermark Unlock",
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

            SmallTitle("Master")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = master,
                        onCheckedChange = { enabled ->
                            master = enabled
                            set(Preferences.KEY_WM_UNLOCK_MASTER, enabled)
                            if (enabled) {
                                // com.miui.mediaeditor is a new scope entry; ask LSPosed for it
                                // so the user does not have to toggle it by hand. The editor
                                // process must restart after the scope is granted.
                                coroutineScope.launch {
                                    when (val result = ScopeManager.request(setOf("com.miui.mediaeditor"))) {
                                        is ScopeManager.Result.Applied -> Toast.makeText(
                                            context,
                                            "Scope added; restart 相册编辑 for the hooks to load",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.NoChange -> Unit
                                        is ScopeManager.Result.Rejected -> Toast.makeText(
                                            context,
                                            "Scope request declined; enable com.miui.mediaeditor in LSPosed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        is ScopeManager.Result.Failed -> Toast.makeText(
                                            context,
                                            "Scope request failed: ${result.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                            context,
                                            "Xposed service unavailable; enable com.miui.mediaeditor in LSPosed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        title = "Unlock Media Editor Watermarks",
                        summary = "Show watermarks that are normally hidden on this device " +
                            "(Leica, Disney, POCO, LCC, festival editions, ...), ignoring " +
                            "region / time / length / version limits. Needs " +
                            "com.miui.mediaeditor in the LSPosed scope; restart the editor " +
                            "after enabling it the first time"
                    )
                    SwitchPreference(
                        checked = camera,
                        onCheckedChange = { enabled ->
                            camera = enabled
                            set(Preferences.KEY_WM_CAMERA, enabled)
                            if (enabled) {
                                coroutineScope.launch {
                                    when (val result = ScopeManager.request(setOf("com.android.camera"))) {
                                        is ScopeManager.Result.Applied -> Toast.makeText(
                                            context,
                                            "Scope added; restart 相机 for the hooks to load",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.NoChange -> Unit
                                        is ScopeManager.Result.Rejected -> Toast.makeText(
                                            context,
                                            "Scope request declined; enable com.android.camera in LSPosed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        is ScopeManager.Result.Failed -> Toast.makeText(
                                            context,
                                            "Scope request failed: ${result.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                            context,
                                            "Xposed service unavailable; enable com.android.camera in LSPosed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        title = "Unlock Camera Watermarks",
                        summary = "Show every watermark the camera app synced (Leica, festival, " +
                            "sports, ...) instead of filtering by device properties. Needs " +
                            "com.android.camera in the LSPosed scope"
                    )
                }
            }

            SmallTitle("Brand")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = leica,
                        onCheckedChange = { enabled ->
                            leica = enabled
                            set(Preferences.KEY_WM_LEICA, enabled)
                        },
                        title = "Leica (徕卡)",
                        summary = "Leica watermarks and the Leica template group",
                        enabled = master
                    )
                    SwitchPreference(
                        checked = xiaomi,
                        onCheckedChange = { enabled ->
                            xiaomi = enabled
                            set(Preferences.KEY_WM_XIAOMI, enabled)
                        },
                        title = "Xiaomi",
                        summary = "Xiaomi-brand watermarks and template groups",
                        enabled = master
                    )
                    SwitchPreference(
                        checked = redmi,
                        onCheckedChange = { enabled ->
                            redmi = enabled
                            set(Preferences.KEY_WM_REDMI, enabled)
                        },
                        title = "Redmi",
                        summary = "Redmi-brand watermarks and template groups",
                        enabled = master
                    )
                    SwitchPreference(
                        checked = poco,
                        onCheckedChange = { enabled ->
                            poco = enabled
                            set(Preferences.KEY_WM_POCO, enabled)
                        },
                        title = "POCO",
                        summary = "POCO-brand watermarks and template groups",
                        enabled = master
                    )
                }
            }

            SmallTitle("Theme")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = disney1,
                        onCheckedChange = { enabled ->
                            disney1 = enabled
                            set(Preferences.KEY_WM_DISNEY1, enabled)
                        },
                        title = "Disney 1 (west coast)",
                        summary = "First Disney watermark set",
                        enabled = master
                    )
                    SwitchPreference(
                        checked = disney2,
                        onCheckedChange = { enabled ->
                            disney2 = enabled
                            set(Preferences.KEY_WM_DISNEY2, enabled)
                        },
                        title = "Disney 2 (west coast II)",
                        summary = "Second Disney watermark set",
                        enabled = master
                    )
                    SwitchPreference(
                        checked = disney3,
                        onCheckedChange = { enabled ->
                            disney3 = enabled
                            set(Preferences.KEY_WM_DISNEY3, enabled)
                        },
                        title = "Disney 3 (west coast III)",
                        summary = "Third Disney watermark set",
                        enabled = master
                    )
                    SwitchPreference(
                        checked = victoria,
                        onCheckedChange = { enabled ->
                            victoria = enabled
                            set(Preferences.KEY_WM_VICTORIA, enabled)
                        },
                        title = "Victoria",
                        summary = "Victoria-limited watermark variants",
                        enabled = master
                    )
                    SwitchPreference(
                        checked = lcc,
                        onCheckedChange = { enabled ->
                            lcc = enabled
                            set(Preferences.KEY_WM_LCC, enabled)
                        },
                        title = "LCC (Leitz / 17 Ultra by Leica)",
                        summary = "LCC, Leitzphone and 17 Ultra by Leica watermarks (CN and global)",
                        enabled = master
                    )
                }
            }

            SmallTitle("Advanced")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = downloadAll,
                        onCheckedChange = { enabled ->
                            downloadAll = enabled
                            set(Preferences.KEY_WM_DOWNLOAD_ALL, enabled)
                        },
                        title = "Download all cloud watermarks",
                        summary = "Fetch every cloud watermark resource once the menu is built " +
                            "(needs network; a one-time trigger per editor process)",
                        enabled = master
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
