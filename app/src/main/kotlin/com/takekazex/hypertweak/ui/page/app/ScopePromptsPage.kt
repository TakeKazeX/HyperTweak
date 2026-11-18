package com.takekazex.hypertweak.ui.page.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.page.home.friendlyProcessName
import com.takekazex.hypertweak.ui.theme.StatusTone
import com.takekazex.hypertweak.ui.theme.statusColors
import com.takekazex.hypertweak.util.scope.ScopePrompt
import com.takekazex.hypertweak.util.scope.ScopePromptAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Report

@Composable
fun ScopePromptsPage(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val ignoredPrompts by produceState<Set<ScopePrompt>>(emptySet(), refreshKey) {
        value = withContext(Dispatchers.IO) { services.scopePrompts.ignoredPrompts() }
    }
    val prompts = ignoredPrompts.sortedWith(
        compareBy<ScopePrompt>({ it.action.storageKey }, { it.packageName })
    )

    AppScrollPage(
        title = stringResource(R.string.scope_prompts_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.predictive_back),
    ) {
        if (prompts.isEmpty()) {
            AppSection {
                BasicComponent(
                    title = stringResource(R.string.scope_prompts_empty),
                    summary = stringResource(R.string.scope_prompts_empty_summary)
                )
            }
        } else {
            AppSection(stringResource(R.string.scope_prompts_title)) {
                prompts.forEach { prompt ->
                    IgnoredScopePromptRow(
                        prompt = prompt,
                        onShowAgain = {
                            services.scopePrompts.unignore(prompt)
                            refreshKey++
                        }
                    )
                }
            }
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
                imageVector = if (restoring) MiuixIcons.Glass.Report else MiuixIcons.Glass.Info,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
                tint = statusColors(if (restoring) StatusTone.Warning else StatusTone.Info).accent
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
