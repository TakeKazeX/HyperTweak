package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun CreditsPage(
    onBack: () -> Unit
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.credits_title),
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.credits_back))
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
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ArrowPreference(
                        title = "libxposed",
                        summary = stringResource(R.string.credits_libxposed_desc),
                        onClick = { uriHandler.openUri("https://github.com/libxposed/api") }
                    )
                    ArrowPreference(
                        title = "LSPosed",
                        summary = stringResource(R.string.credits_lsposed_desc),
                        onClick = { uriHandler.openUri("https://github.com/LSPosed/LSPosed") }
                    )
                    ArrowPreference(
                        title = "EzHookTool",
                        summary = stringResource(R.string.credits_ezhook_desc),
                        onClick = { uriHandler.openUri("https://github.com/lingqiqi5211/EzHookTool") }
                    )
                    ArrowPreference(
                        title = "DexKit",
                        summary = stringResource(R.string.credits_dexkit_desc),
                        onClick = { uriHandler.openUri("https://github.com/LuckyPray/DexKit") }
                    )
                    ArrowPreference(
                        title = "HiddenApiBypass",
                        summary = stringResource(R.string.credits_hiddenapibypass_desc),
                        onClick = { uriHandler.openUri("https://github.com/LSPosed/AndroidHiddenApiBypass") }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.credits_miuix_title),
                        summary = stringResource(R.string.credits_miuix_desc),
                        onClick = { uriHandler.openUri("https://github.com/compose-miuix-ui/miuix") }
                    )
                    ArrowPreference(
                        title = "InstallerX Revived",
                        summary = stringResource(R.string.credits_installerx_desc),
                        onClick = { uriHandler.openUri("https://github.com/wxxsfxyzm/InstallerX-Revived") }
                    )
                    ArrowPreference(
                        title = "HyperOShape",
                        summary = stringResource(R.string.credits_hyperorch_desc),
                        onClick = { uriHandler.openUri("https://github.com/xzakota/HyperOShape") }
                    )
                    ArrowPreference(
                        title = "XiaomiHelper",
                        summary = stringResource(R.string.credits_xiaomihelper_desc),
                        onClick = { uriHandler.openUri("https://github.com/HowieHChen/XiaomiHelper") }
                    )
                    ArrowPreference(
                        title = "HyperCeiler",
                        summary = stringResource(R.string.credits_hyperceiler_desc),
                        onClick = { uriHandler.openUri("https://github.com/ReChronoRain/HyperCeiler") }
                    )
                    ArrowPreference(
                        title = "HyperPasskey",
                        summary = stringResource(R.string.credits_hyperpasskey_desc),
                        onClick = { uriHandler.openUri("https://github.com/howard20181/HyperPasskey") }
                    )
                    ArrowPreference(
                        title = "HyperOS_FCM_Live",
                        summary = stringResource(R.string.credits_fcm_desc),
                        onClick = { uriHandler.openUri("https://github.com/howard20181/HyperOS_FCM_Live") }
                    )
                    ArrowPreference(
                        title = "MiuiBackGestureHook",
                        summary = stringResource(R.string.credits_backgesture_desc),
                        onClick = { uriHandler.openUri("https://github.com/wxxsfxyzm/MiuiBackGestureHook/commit/a5f1ae5d76609f8323d30ce108117081369c426f") }
                    )
                    ArrowPreference(
                        title = "AOSP Package Installer",
                        summary = stringResource(R.string.credits_aospinstaller_desc),
                        onClick = { uriHandler.openUri("https://github.com/tehcneko/AospPackageInstaller") }
                    )
                    ArrowPreference(
                        title = "HyperTrust",
                        summary = stringResource(R.string.credits_hydtrust_desc),
                        onClick = { uriHandler.openUri("https://github.com/StevenWin818/HyperTrust") }
                    )
                    ArrowPreference(
                        title = "HighLight Icons",
                        summary = stringResource(R.string.credits_highlighticons_desc),
                        onClick = { uriHandler.openUri("https://t.me/HighLightIcons") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}