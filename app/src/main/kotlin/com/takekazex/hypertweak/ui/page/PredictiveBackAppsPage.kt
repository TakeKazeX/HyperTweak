package com.takekazex.hypertweak.ui.page

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.hook.Preferences

private data class OptInApp(val packageName: String, val label: String, val isSystem: Boolean)

/**
 * Per-app predictive-back opt-in. The system server reads the selected package set and reports
 * predictive back as enabled for those activities, which makes apps that never opted in animate
 * with the AOSP back gesture instead of falling back to the legacy path.
 */
@Composable
fun PredictiveBackAppsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    var apps by remember { mutableStateOf<List<OptInApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selected by remember {
        mutableStateOf(Preferences.getStringSet(Preferences.KEY_AOSP_BACK_OPT_IN_PACKAGES))
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .map {
                    OptInApp(
                        packageName = it.packageName,
                        label = pm.getApplicationLabel(it).toString(),
                        isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    )
                }
                .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        }
        loading = false
    }

    fun toggle(packageName: String, enabled: Boolean) {
        val next = if (enabled) selected + packageName else selected - packageName
        selected = next
        Preferences.putStringSet(Preferences.KEY_AOSP_BACK_OPT_IN_PACKAGES, next)
    }

    val visible = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Predictive Back Apps",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .overScrollVertical()
        ) {
            item("spacer_top") { Spacer(modifier = Modifier.height(8.dp)) }
            item("search") {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
            }
            item("title") {
                SmallTitle(
                    text = when {
                        loading -> "Loading installed apps…"
                        else -> "${selected.size} selected"
                    }
                )
            }
            item("card") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        visible.forEach { app ->
                            key(app.packageName) {
                                SwitchPreference(
                                    checked = app.packageName in selected,
                                    onCheckedChange = { toggle(app.packageName, it) },
                                    title = app.label,
                                    summary = app.packageName
                                )
                            }
                        }
                    }
                }
            }
            item("spacer_bottom") {
                Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 48.dp))
            }
        }
    }
}
