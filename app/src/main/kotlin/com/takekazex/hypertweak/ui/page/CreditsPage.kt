package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
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
                title = "Credits",
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
                        summary = "Native Xposed API 101 framework",
                        onClick = { uriHandler.openUri("https://github.com/libxposed/api") }
                    )
                    ArrowPreference(
                        title = "LSPosed",
                        summary = "Mainstream Xposed framework implementation",
                        onClick = { uriHandler.openUri("https://github.com/LSPosed/LSPosed") }
                    )
                    ArrowPreference(
                        title = "KavaRef",
                        summary = "HighCapable Kotlin reflection library",
                        onClick = { uriHandler.openUri("https://github.com/HighCapable/KavaRef") }
                    )
                    ArrowPreference(
                        title = "EzHookTool",
                        summary = "Kotlin Xposed helper library by lingqiqi5211",
                        onClick = { uriHandler.openUri("https://github.com/lingqiqi5211/EzHookTool") }
                    )
                    ArrowPreference(
                        title = "DexKit",
                        summary = "Powerful Dex analysis tool for finding hook points dynamically",
                        onClick = { uriHandler.openUri("https://github.com/LuckyPray/DexKit") }
                    )
                    ArrowPreference(
                        title = "HiddenApiBypass",
                        summary = "Bypass restrictions on non-SDK interfaces in Android 9+",
                        onClick = { uriHandler.openUri("https://github.com/LSPosed/AndroidHiddenApiBypass") }
                    )
                    ArrowPreference(
                        title = "Miuix UI (compose-miuix-ui)",
                        summary = "Modern MIUI/HyperOS style Compose components",
                        onClick = { uriHandler.openUri("https://github.com/compose-miuix-ui/miuix") }
                    )
                    ArrowPreference(
                        title = "InstallerX Revived",
                        summary = "Inspiration for theme configuration and UI/UX layout design",
                        onClick = { uriHandler.openUri("https://github.com/wxxsfxyzm/InstallerX-Revived") }
                    )
                    ArrowPreference(
                        title = "HyperOShape",
                        summary = "Base logic for fingerprint icon drawing bypass",
                        onClick = { uriHandler.openUri("https://github.com/xzakota/HyperOShape") }
                    )
                    ArrowPreference(
                        title = "XiaomiHelper",
                        summary = "References for Settings preference injection",
                        onClick = { uriHandler.openUri("https://github.com/HowieHChen/XiaomiHelper") }
                    )
                    ArrowPreference(
                        title = "HyperCeiler",
                        summary = "All-in-one system enhancement references",
                        onClick = { uriHandler.openUri("https://github.com/ReChronoRain/HyperCeiler") }
                    )
                    ArrowPreference(
                        title = "HyperPasskey",
                        summary = "Unlock Google Passkey / Credential Manager by howard20181",
                        onClick = { uriHandler.openUri("https://github.com/howard20181/HyperPasskey") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
