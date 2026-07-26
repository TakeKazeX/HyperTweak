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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.hook.Preferences
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
 * Toggles that hand a HyperOS component back to its AOSP implementation.
 *
 * State is kept locally rather than hoisted into `MainActivity`, so these switches do not feed the
 * pending-restart-scope tracking; the page surfaces the restart requirement in each summary.
 */
@Composable
fun AospRestorePage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    var packageInstaller by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, false))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = "AOSP Restore",
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

            SmallTitle("System")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = packageInstaller,
                        onCheckedChange = { enabled ->
                            packageInstaller = enabled
                            Preferences.putBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, enabled)
                        },
                        title = "AOSP Package Installer",
                        summary = "Install packages with the AOSP installer instead of the MIUI one. " +
                            "Relaxes MIUI install verification. Requires a reboot"
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
