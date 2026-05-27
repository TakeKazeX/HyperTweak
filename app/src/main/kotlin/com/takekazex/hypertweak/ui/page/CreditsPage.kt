package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun CreditsPage(
    onBack: () -> Unit
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
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
                    BasicComponent(
                        title = "libxposed",
                        summary = "Native Xposed API 101 framework"
                    )
                    BasicComponent(
                        title = "LSPosed",
                        summary = "Mainstream Xposed framework implementation"
                    )
                    BasicComponent(
                        title = "KavaRef",
                        summary = "HighCapable Kotlin reflection library"
                    )
                    BasicComponent(
                        title = "EzHookTool",
                        summary = "Kotlin Xposed helper library by lingqiqi5211"
                    )
                    BasicComponent(
                        title = "DexKit",
                        summary = "Powerful Dex analysis tool for finding hook points dynamically"
                    )
                    BasicComponent(
                        title = "HiddenApiBypass",
                        summary = "Bypass restrictions on non-SDK interfaces in Android 9+"
                    )
                    BasicComponent(
                        title = "Miuix UI (compose-miuix-ui)",
                        summary = "Modern MIUI/HyperOS style Compose components"
                    )
                    BasicComponent(
                        title = "InstallerX Revived",
                        summary = "Inspiration for theme configuration and UI/UX layout design"
                    )
                    BasicComponent(
                        title = "HyperOShape",
                        summary = "Base logic for fingerprint icon drawing bypass"
                    )
                    BasicComponent(
                        title = "XiaomiHelper / HyperCeiler",
                        summary = "References for Settings preference injection"
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
