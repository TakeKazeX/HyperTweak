package com.takekazex.hypertweak.ui.page

import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.ScopeManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Flagship impersonation for the camera app (`com.android.camera`, MiuiCamera).
 *
 * When the master switch is on, `CameraImpersonationHooker` swaps the per-device capability
 * config for a flagship (`com.mi.device.Nezha`) instance so every capability gate opens on any
 * device. The "keep model" switch (on by default) re-forces the on-picture watermark back to
 * this device's own brand + model, so impersonation can never change the watermark model.
 * The initial enable needs a camera app restart (the hooks are installed on attach); toggling
 * the switches afterwards is live.
 */
@Composable
fun CameraUnlockPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var master by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE, false))
    }
    var keepModel by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_WM_KEEP_MODEL, true))
    }
    var themeLcc by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, false))
    }

    fun set(key: String, value: Boolean) {
        Preferences.putBoolean(key, value)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.camera_unlock_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.camera_unlock_back)) }
            }
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

            SmallTitle(stringResource(R.string.camera_unlock_master))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = master,
                        onCheckedChange = { enabled ->
                            master = enabled
                            set(Preferences.KEY_CAMERA_IMPERSONATE, enabled)
                            if (enabled) {
                                // com.android.camera is a declared scope (scope.list); make
                                // sure LSPosed actually granted it before the camera is restarted.
                                coroutineScope.launch {
                                    when (val result = ScopeManager.request(setOf("com.android.camera"))) {
                                        is ScopeManager.Result.Applied -> Toast.makeText(
                                            context,
                                            context.getString(R.string.watermark_scope_added_camera),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.NoChange -> Unit
                                        is ScopeManager.Result.Rejected -> Toast.makeText(
                                            context,
                                            context.getString(R.string.watermark_scope_declined_camera),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        is ScopeManager.Result.Failed -> Toast.makeText(
                                            context,
                                            context.getString(R.string.watermark_scope_failed_camera, result.message),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                            context,
                                            context.getString(R.string.watermark_scope_unavailable_camera),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        title = stringResource(R.string.camera_unlock_master_title),
                        summary = stringResource(R.string.camera_unlock_master_summary)
                    )
                    SwitchPreference(
                        checked = keepModel,
                        onCheckedChange = { enabled ->
                            keepModel = enabled
                            set(Preferences.KEY_CAMERA_WM_KEEP_MODEL, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_keep_model_title),
                        summary = stringResource(R.string.camera_unlock_keep_model_summary)
                    )
                    SwitchPreference(
                        checked = themeLcc,
                        onCheckedChange = { enabled ->
                            themeLcc = enabled
                            set(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_theme_lcc_title),
                        summary = stringResource(R.string.camera_unlock_theme_lcc_summary)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SmallTitle(stringResource(R.string.camera_unlock_notes))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.camera_unlock_note_restart),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.camera_unlock_note_exif),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
        }
    }
}
