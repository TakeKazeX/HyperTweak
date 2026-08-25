package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.ime.AospImeConfig
import com.takekazex.hypertweak.util.RestartUtils
import com.takekazex.hypertweak.util.ScopeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

private data class InstalledIme(val packageName: String, val label: String)

/** Order matches [NAV_BAR_KEY_VALUES]. */
private val NAV_BAR_KEY_LABELS = listOf(
    R.string.ime_nav_key_hide,
    R.string.ime_nav_key_switch,
    R.string.ime_nav_key_none
)

private val NAV_BAR_KEY_VALUES = listOf(
    AospImeConfig.BUTTON_HIDE_IME,
    AospImeConfig.BUTTON_IME_SWITCHER,
    AospImeConfig.BUTTON_NONE
)

/** Order matches [RAISE_STYLE_LABELS]. */
private val RAISE_STYLE_VALUES = listOf(
    AospImeConfig.RAISE_STYLE_AOSP,
    AospImeConfig.RAISE_STYLE_MIUI
)

private val RAISE_STYLE_LABELS = listOf(
    R.string.ime_raise_style_aosp,
    R.string.ime_raise_style_miui
)

private fun navBarKeyIndex(value: String): Int =
    NAV_BAR_KEY_VALUES.indexOf(value).takeIf { it >= 0 } ?: 0

/**
 * Picks which input methods get the AOSP full-screen navigation bar.
 *
 * The selection is applied on demand rather than per toggle, because applying it requests Xposed
 * scope for the newly selected packages and that prompts the user.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun AospImePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var enabled by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_IME_ENABLED, false))
    }
    var miuiImeList by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_IME_MIUI_IME_LIST, false))
    }
    var forceAospAll by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_IME_FORCE_ALL, false))
    }
    var startKey by remember { mutableStateOf(AospImeConfig.navBarLayoutStart()) }
    var endKey by remember { mutableStateOf(AospImeConfig.navBarLayoutEnd()) }
    var raiseStyle by remember { mutableStateOf(AospImeConfig.raiseStyle()) }

    var applied by remember { mutableStateOf(Preferences.getStringSet(Preferences.KEY_AOSP_IME_PACKAGES)) }
    var selected by remember { mutableStateOf(applied) }
    var applying by remember { mutableStateOf(false) }

    var imes by remember { mutableStateOf<List<InstalledIme>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        imes = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val imm = context.getSystemService(InputMethodManager::class.java)
            // Enabled methods only. getInputMethodList() also returns services that are installed
            // but can never be the current keyboard — notably Play Services' autofill IME — and
            // the system-server hook gates on DEFAULT_INPUT_METHOD, which only an enabled method
            // can ever be.
            (imm?.enabledInputMethodList ?: emptyList())
                .distinctBy { it.packageName }
                .map { InstalledIme(it.packageName, it.loadLabel(pm).toString()) }
                .sortedBy { it.label.lowercase() }
        }
        loading = false
    }

    fun apply() {
        applying = true
        coroutineScope.launch {
            val result = ScopeManager.applyManagedDiff(
                context = context,
                target = selected,
                managed = imes.map { it.packageName }.toSet()
            )
            when (result) {
                is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> {
                    Preferences.putStringSet(Preferences.KEY_AOSP_IME_PACKAGES, selected)
                    applied = selected
                    Toast.makeText(context, context.getString(R.string.ime_applied), Toast.LENGTH_SHORT).show()
                }
                is ScopeManager.Result.Rejected -> Toast.makeText(
                    context,
                    context.getString(R.string.ime_scope_not_granted, result.missing.joinToString()),
                    Toast.LENGTH_LONG
                ).show()
                is ScopeManager.Result.Failed -> Toast.makeText(
                    context,
                    context.getString(R.string.ime_scope_update_failed, result.message),
                    Toast.LENGTH_LONG
                ).show()
                ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                    context,
                    context.getString(R.string.ime_service_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
            }
            applying = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.ime_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.ime_back)) } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .overScrollVertical()
        ) {
            item("spacer_top") { Spacer(Modifier.height(8.dp)) }

            item("options") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                Preferences.putBoolean(Preferences.KEY_AOSP_IME_ENABLED, it)
                            },
                            title = stringResource(R.string.ime_nav_bar_title),
                            summary = stringResource(R.string.ime_nav_bar_summary)
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.ime_left_key),
                            items = NAV_BAR_KEY_LABELS.map { stringResource(it) },
                            selectedIndex = navBarKeyIndex(startKey),
                            onSelectedIndexChange = { index ->
                                startKey = NAV_BAR_KEY_VALUES[index]
                                Preferences.putString(Preferences.KEY_AOSP_IME_NAV_BAR_START, startKey)
                            },
                            enabled = enabled
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.ime_right_key),
                            items = NAV_BAR_KEY_LABELS.map { stringResource(it) },
                            selectedIndex = navBarKeyIndex(endKey),
                            onSelectedIndexChange = { index ->
                                endKey = NAV_BAR_KEY_VALUES[index]
                                Preferences.putString(Preferences.KEY_AOSP_IME_NAV_BAR_END, endKey)
                            },
                            enabled = enabled
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.ime_raise_style_title),
                            summary = stringResource(R.string.ime_raise_style_summary),
                            items = RAISE_STYLE_LABELS.map { stringResource(it) },
                            selectedIndex = RAISE_STYLE_VALUES.indexOf(raiseStyle).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                RAISE_STYLE_VALUES.getOrNull(index)?.let {
                                    raiseStyle = it
                                    Preferences.putInt(Preferences.KEY_AOSP_IME_RAISE_STYLE, it)
                                }
                            },
                            enabled = enabled
                        )
                        BasicComponent(
                            title = stringResource(R.string.ime_layout_title),
                            summary = "$startKey[70AC];${AospImeConfig.BUTTON_HOME_HANDLE};$endKey[70AC]"
                        )
                        SwitchPreference(
                            checked = miuiImeList,
                            onCheckedChange = {
                                miuiImeList = it
                                Preferences.putBoolean(Preferences.KEY_AOSP_IME_MIUI_IME_LIST, it)
                            },
                            title = stringResource(R.string.ime_list_all_keyboards),
                            summary = stringResource(R.string.ime_list_all_keyboards_summary)
                        )
                        SwitchPreference(
                            checked = forceAospAll,
                            onCheckedChange = {
                                forceAospAll = it
                                Preferences.putBoolean(Preferences.KEY_AOSP_IME_FORCE_ALL, it)
                            },
                            title = stringResource(R.string.ime_force_aosp_title),
                            summary = stringResource(R.string.ime_force_aosp_summary),
                            enabled = enabled
                        )
                    }
                }
            }

            item("restart_note") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BasicComponent(
                        title = stringResource(R.string.ime_applying_changes),
                        summary = stringResource(R.string.ime_applying_changes_summary)
                    )
                }
            }

            item("targets_title") {
                SmallTitle(
                    text = when {
                        loading -> stringResource(R.string.ime_loading)
                        else -> stringResource(R.string.ime_selected_count, selected.size)
                    }
                )
            }

            item("targets") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        imes.forEach { ime ->
                            key(ime.packageName) {
                                SwitchPreference(
                                    checked = ime.packageName in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) {
                                            selected + ime.packageName
                                        } else {
                                            selected - ime.packageName
                                        }
                                    },
                                    title = ime.label,
                                    summary = ime.packageName,
                                    enabled = enabled && !applying
                                )
                            }
                        }
                    }
                }
            }

            item("actions") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    TextButton(
                        text = if (applying) {
                            stringResource(R.string.ime_applying_button)
                        } else {
                            stringResource(R.string.ime_apply_selection)
                        },
                        enabled = selected != applied && !applying,
                        onClick = { apply() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item("restart") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ime_restart_keyboards),
                        summary = stringResource(R.string.ime_restart_keyboards_summary),
                        onClick = {
                            RestartUtils.forceStopPackages(
                                context = context,
                                coroutineScope = coroutineScope,
                                packages = Preferences.getStringSet(Preferences.KEY_AOSP_IME_PACKAGES)
                            )
                        }
                    )
                }
            }

            item("spacer_bottom") {
                Spacer(Modifier.height(padding.calculateBottomPadding() + 48.dp))
            }
        }
    }
}
