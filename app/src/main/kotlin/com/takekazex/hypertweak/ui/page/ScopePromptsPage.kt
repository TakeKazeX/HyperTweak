package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.ScopePrompt
import com.takekazex.hypertweak.util.ScopePromptAction
import com.takekazex.hypertweak.util.ScopePromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ScopePromptsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var refreshKey by remember { mutableIntStateOf(0) }
    val ignoredPrompts by produceState<Set<ScopePrompt>>(emptySet(), refreshKey) {
        value = withContext(Dispatchers.IO) { ScopePromptStore.ignoredPrompts() }
    }
    val prompts = ignoredPrompts.sortedWith(
        compareBy<ScopePrompt>({ it.action.storageKey }, { it.packageName })
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.scope_prompts_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, stringResource(R.string.predictive_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            if (prompts.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    BasicComponent(
                        title = stringResource(R.string.scope_prompts_empty),
                        summary = stringResource(R.string.scope_prompts_empty_summary)
                    )
                }
            } else {
                SmallTitle(text = stringResource(R.string.scope_prompts_title))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        prompts.forEach { prompt ->
                            IgnoredScopePromptRow(
                                prompt = prompt,
                                onShowAgain = {
                                    ScopePromptStore.unignore(prompt)
                                    refreshKey++
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun IgnoredScopePromptRow(
    prompt: ScopePrompt,
    onShowAgain: () -> Unit
) {
    val context = LocalContext.current
    val appName by produceState(
        initialValue = friendlyProcessName(context, prompt.packageName),
        prompt.packageName
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(prompt.packageName, 0)).toString()
            }.getOrDefault(friendlyProcessName(context, prompt.packageName))
        }
    }
    val restoring = prompt.action == ScopePromptAction.RESTORE

    BasicComponent(
        title = appName,
        summary = buildString {
            append(
                stringResource(
                    if (restoring) R.string.scope_prompt_restore else R.string.scope_prompt_remove
                )
            )
            append(" · ")
            append(prompt.packageName)
        },
        startAction = {
            Icon(
                imageVector = if (restoring) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
                tint = if (restoring) Color(0xFFFFB300) else Color(0xFF42A5F5)
            )
        },
        endActions = {
            TextButton(
                text = stringResource(R.string.scope_prompt_show_again),
                onClick = onShowAgain,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    )
}
