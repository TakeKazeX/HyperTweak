package com.takekazex.hypertweak.ui.page.platform

import com.takekazex.hypertweak.ui.app.LocalAppServices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.ImeLayout
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.util.scope.ScopeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

private data class InstalledIme(val packageName: String, val label: String)

/** Play Services' autofill proxy is enabled but can never become the current keyboard. */
private const val AUXILIARY_IME_PACKAGE = "com.google.android.gms"

/** Order matches [NAV_BAR_KEY_VALUES]. */
private val NAV_BAR_KEY_LABELS = listOf(
    R.string.ime_nav_key_hide,
    R.string.ime_nav_key_switch,
    R.string.ime_nav_key_none
)

private val NAV_BAR_KEY_VALUES = listOf(
    ImeLayout.BUTTON_HIDE_IME,
    ImeLayout.BUTTON_IME_SWITCHER,
    ImeLayout.BUTTON_NONE
)

/** Order matches [RAISE_STYLE_LABELS]. */
private val RAISE_STYLE_VALUES = listOf(
    ImeLayout.RAISE_STYLE_AOSP,
    ImeLayout.RAISE_STYLE_MIUI
)

private val RAISE_STYLE_LABELS = listOf(
    R.string.ime_raise_style_aosp,
    R.string.ime_raise_style_miui
)

private fun navBarKeyIndex(value: String): Int =
    NAV_BAR_KEY_VALUES.indexOf(value).takeIf { it >= 0 } ?: 0

private fun auxiliary(ime: InputMethodInfo): Boolean =
    ime.packageName == AUXILIARY_IME_PACKAGE ||
        ime.serviceInfo?.name?.contains("Autofill", ignoreCase = true) == true

/**
 * Reads the keyboards the system actually offers. The secure enabled list is authoritative;
 * enabledInputMethodList is the fallback because some ROMs omit third-party entries from the
 * process-local list. A transient failure must never produce an empty selection write.
 */
private fun loadInstalledImes(context: Context, saved: Set<String>): List<InstalledIme> {
    val pm = context.packageManager
    val imm = context.getSystemService(InputMethodManager::class.java)
    val enabledList = imm?.enabledInputMethodList.orEmpty()
    val allList = imm?.inputMethodList.orEmpty()
    // Some ROMs return a partial inputMethodList; merge both sources by package.
    val pool = LinkedHashMap<String, InputMethodInfo>()
    allList.forEach { ime -> ime.packageName?.let { pool[it] = ime } }
    enabledList.forEach { ime -> ime.packageName?.let { pool.putIfAbsent(it, ime) } }
    val securePackages = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            .orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it.substringBefore(';'))?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    fun label(ime: InputMethodInfo, packageName: String): InstalledIme =
        InstalledIme(packageName, runCatching { ime.loadLabel(pm).toString() }.getOrDefault(packageName))

    val chosen = buildList {
        if (securePackages.isNotEmpty()) {
            securePackages.forEach { pkg ->
                pool[pkg]?.takeUnless(::auxiliary)?.let { add(label(it, pkg)) }
            }
        }
        if (isEmpty()) {
            enabledList.filterNot(::auxiliary).forEach { ime ->
                ime.packageName?.let { add(label(ime, it)) }
            }
        }
    }
    val retained = saved.filterNot { it == AUXILIARY_IME_PACKAGE }.map { pkg ->
        pool[pkg]?.let { label(it, pkg) } ?: InstalledIme(pkg, pkg)
    }
    return (chosen + retained).distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

/**
 * Picks which input methods get the AOSP full-screen navigation bar.
 *
 * The selection is applied on demand rather than per toggle, because applying it requests Xposed
 * scope for the newly selected packages and that prompts the user.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun AospImePage(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var enabled by rememberSaveable {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_IME_ENABLED, false))
    }
    var miuiImeList by rememberSaveable {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_IME_MIUI_IME_LIST, false))
    }
    var startKey by rememberSaveable { mutableStateOf(ImeLayout.button(settingsStore.getString(PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_START, ImeLayout.BUTTON_HIDE_IME), ImeLayout.BUTTON_HIDE_IME)) }
    var endKey by rememberSaveable { mutableStateOf(ImeLayout.button(settingsStore.getString(PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_END, ImeLayout.BUTTON_IME_SWITCHER), ImeLayout.BUTTON_IME_SWITCHER)) }
    var raiseStyle by rememberSaveable { mutableIntStateOf(settingsStore.getInt(PlatformSettingsKeys.KEY_AOSP_IME_RAISE_STYLE, ImeLayout.RAISE_STYLE_AOSP)) }

    // Drop the auxiliary autofill proxy from an older saved selection in memory only. Persisting
    // happens through apply(), so a failed list read can never erase the real selection.
    var applied by rememberSaveable {
        mutableStateOf(settingsStore.getStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES)
            .filterNot { it == AUXILIARY_IME_PACKAGE }.toSet())
    }
    var selected by rememberSaveable { mutableStateOf(applied) }
    var applying by remember { mutableStateOf(false) }

    var imes by remember { mutableStateOf<List<InstalledIme>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var missingScope by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        imes = withContext(Dispatchers.IO) { loadInstalledImes(context, applied) }
        loading = false
    }

    fun apply() {
        if (applying) return
        val requested = selected.toSet()
        applying = true
        coroutineScope.launch {
            val result = services.scopes.applyManagedDiff(
                context = context,
                target = requested,
                managed = imes.map { it.packageName }.toSet() + applied
            )
            when (result) {
                is ScopeManager.Result.Applied -> {
                    settingsStore.putStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES, requested)
                    applied = requested
                    missingScope = emptySet()
                    if (result.added.isNotEmpty()) {
                        // LSPosed only injects a package when its process starts. Restarting the
                        // newly scoped keyboards is what makes the first enable effective.
                        services.restarts.forceStopPackages(
                            context = context,
                            coroutineScope = coroutineScope,
                            packages = result.added
                        )
                        Toast.makeText(context, context.getString(R.string.ime_applied_restarted),
                            Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.ime_applied), Toast.LENGTH_SHORT).show()
                    }
                }
                ScopeManager.Result.NoChange -> {
                    settingsStore.putStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES, requested)
                    applied = requested
                    missingScope = emptySet()
                    Toast.makeText(context, context.getString(R.string.ime_applied), Toast.LENGTH_SHORT).show()
                }
                is ScopeManager.Result.Rejected -> {
                    missingScope = result.missing
                    Toast.makeText(
                        context,
                        context.getString(R.string.ime_scope_not_granted, result.missing.joinToString()),
                        Toast.LENGTH_LONG
                    ).show()
                }
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

    // Heal a scope that was restored from a backup or left behind by an interrupted apply:
    // the saved selection is only effective once LSPosed has every selected package.
    var scopeHealed by remember { mutableStateOf(false) }
    LaunchedEffect(loading, enabled, applied, imes) {
        if (loading || !enabled) return@LaunchedEffect
        if (selected.isEmpty() && imes.isNotEmpty()) selected = imes.map { it.packageName }.toSet()
        if (scopeHealed || selected.isEmpty()) return@LaunchedEffect
        // The LSPosed service can still be binding when the page opens; retry briefly instead of
        // silently leaving the saved selection without its scope.
        var live: Set<String>? = null
        for (attempt in 0 until 6) {
            live = services.scopes.currentScope()
            if (live != null) break
            delay(500)
        }
        val granted = live ?: return@LaunchedEffect
        val missing = selected - granted
        missingScope = missing
        if (missing.isEmpty()) return@LaunchedEffect
        scopeHealed = true
        apply()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.ime_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.ime_back)) } }
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

            SmallTitle(stringResource(R.string.ime_section_behavior))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_IME_ENABLED, it)
                            if (it) {
                                // "All keyboards" is the intended default: select every enabled
                                // input method and request its scope in the same action.
                                if (selected.isEmpty()) selected = imes.map { ime -> ime.packageName }.toSet()
                                if (selected.isNotEmpty() && !applying) apply()
                            }
                        },
                        title = stringResource(R.string.ime_nav_bar_title),
                        summary = stringResource(R.string.ime_nav_bar_summary)
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.ime_raise_style_title),
                        summary = stringResource(R.string.ime_raise_style_summary),
                        items = RAISE_STYLE_LABELS.map { stringResource(it) },
                        selectedIndex = RAISE_STYLE_VALUES.indexOf(raiseStyle).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            RAISE_STYLE_VALUES.getOrNull(index)?.let {
                                raiseStyle = it
                                settingsStore.putInt(PlatformSettingsKeys.KEY_AOSP_IME_RAISE_STYLE, it)
                            }
                        },
                        enabled = enabled
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.ime_left_key),
                        items = NAV_BAR_KEY_LABELS.map { stringResource(it) },
                        selectedIndex = navBarKeyIndex(startKey),
                        onSelectedIndexChange = { index ->
                            startKey = NAV_BAR_KEY_VALUES[index]
                            settingsStore.putString(PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_START, startKey)
                        },
                        enabled = enabled
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.ime_right_key),
                        items = NAV_BAR_KEY_LABELS.map { stringResource(it) },
                        selectedIndex = navBarKeyIndex(endKey),
                        onSelectedIndexChange = { index ->
                            endKey = NAV_BAR_KEY_VALUES[index]
                            settingsStore.putString(PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_END, endKey)
                        },
                        enabled = enabled
                    )
                    BasicComponent(
                        title = stringResource(R.string.ime_layout_title),
                        summary = stringResource(
                            R.string.ime_layout_summary,
                            stringResource(NAV_BAR_KEY_LABELS[navBarKeyIndex(startKey)]),
                            stringResource(NAV_BAR_KEY_LABELS[navBarKeyIndex(endKey)])
                        )
                    )
                }
            }

            SmallTitle(stringResource(R.string.ime_section_keyboards))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    when {
                        loading -> BasicComponent(
                            title = stringResource(R.string.ime_loading),
                            summary = stringResource(R.string.ime_loading_summary)
                        )
                        imes.isEmpty() -> {
                            BasicComponent(
                                title = stringResource(R.string.ime_empty_title),
                                summary = stringResource(R.string.ime_empty_summary)
                            )
                            TextButton(
                                text = stringResource(R.string.ime_reload),
                                onClick = { reloadToken++ },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
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
                            BasicComponent(
                                title = stringResource(R.string.ime_selected_title),
                                summary = pluralStringResource(
                                    R.plurals.ime_selected_count,
                                    selected.size,
                                    selected.size
                                )
                            )
                        }
                    }
                }
            }

            SmallTitle(stringResource(R.string.ime_section_scope))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = stringResource(R.string.ime_applying_changes),
                        summary = stringResource(R.string.ime_applying_changes_summary)
                    )
                    if (missingScope.isNotEmpty()) {
                        BasicComponent(
                            title = stringResource(R.string.ime_scope_required_title),
                            summary = stringResource(
                                R.string.ime_scope_required_summary,
                                missingScope.sorted().joinToString()
                            )
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.ime_select_all),
                        enabled = enabled && !applying && imes.isNotEmpty() &&
                            selected.size != imes.size,
                        onClick = { selected = imes.map { it.packageName }.toSet() },
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    ArrowPreference(
                        title = stringResource(R.string.ime_restart_keyboards),
                        summary = stringResource(R.string.ime_restart_keyboards_summary),
                        onClick = {
                            services.restarts.forceStopPackages(
                                context = context,
                                coroutineScope = coroutineScope,
                                packages = settingsStore.getStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES)
                            )
                        }
                    )
                }
            }

            SmallTitle(stringResource(R.string.ime_section_other))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = miuiImeList,
                        onCheckedChange = {
                            miuiImeList = it
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_IME_MIUI_IME_LIST, it)
                        },
                        title = stringResource(R.string.ime_list_all_keyboards),
                        summary = stringResource(R.string.ime_list_all_keyboards_summary)
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
