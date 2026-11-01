package com.takekazex.hypertweak.ui.components.scope

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.util.scope.ScopeManager
import com.takekazex.hypertweak.util.scope.ScopePrompt
import com.takekazex.hypertweak.util.scope.ScopePromptAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton

/** Place inside the feature page's scrolling content, alongside the affected application. */
@Composable
internal fun MissingScopeCard(vararg packages: String) {
    val services = LocalAppServices.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemName = stringResource(R.string.home_scope_system_server)
    val serviceUnavailable = stringResource(R.string.home_scope_service_unavailable)
    val rejected = stringResource(R.string.feature_scope_rejected)
    val service by services.module.connection.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val targets = packages.toSet()
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // NavDisplay can dispose a covered entry and recreate it at STARTED during predictive back.
    // Save package names in the entry state so the revealed page keeps its verified card too.
    var missingPackages by rememberSaveable(*packages) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(targets, service, lifecycle, refreshKey) {
        // Predictive back lowers the entry lifecycle before the gesture commits. Keep the last
        // verified snapshot while paused, disconnected or querying, including a cancelled gesture.
        // Unknown service state is not evidence that the scope changed.
        if (service == null || lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        val live = services.scopes.currentScope() ?: return@LaunchedEffect
        val ignored = withContext(Dispatchers.IO) { services.scopePrompts.ignoredIds() }
        missingPackages = targets.filterNot { target ->
            target in live || (target == "system" && live.any { it == "android" || it == "system_server" })
        }.filterNot { ScopePrompt(ScopePromptAction.RESTORE, it).id in ignored }
    }

    missingPackages.map { ScopePrompt(ScopePromptAction.RESTORE, it) }.forEach { prompt ->
        key(prompt.id) {
            val appName by produceState(prompt.packageName, prompt.packageName, configuration) {
                value = withContext(Dispatchers.IO) {
                    if (prompt.packageName == "system") systemName
                    else runCatching {
                        val pm = context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(prompt.packageName, 0)).toString()
                    }.getOrDefault(prompt.packageName)
                }
            }
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = stringResource(R.string.feature_scope_missing),
                        summary = stringResource(R.string.feature_scope_missing_summary, appName, prompt.packageName)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.feature_scope_ignore),
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                services.scopePrompts.ignore(prompt)
                                refreshKey++
                            }
                        )
                        TextButton(
                            text = stringResource(R.string.scope_prompt_restore),
                            enabled = !busy,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        val result = services.scopes.request(setOf(prompt.packageName))
                                        val message = when (result) {
                                            is ScopeManager.Result.Failed -> result.message
                                            ScopeManager.Result.ServiceUnavailable -> serviceUnavailable
                                            is ScopeManager.Result.Rejected -> rejected
                                            is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> null
                                        }
                                        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                    } finally {
                                        busy = false
                                        refreshKey++
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
