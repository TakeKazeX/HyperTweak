package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.DownloadXlStorage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Download Manager UI and provider tweaks kept together on a dedicated second-level page. */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun DownloadManagerPage(
    onBack: () -> Unit,
    blockDownloadXlLogDir: Boolean,
    onBlockDownloadXlLogDirChange: (Boolean) -> Unit,
    alwaysShowFullLink: Boolean,
    onAlwaysShowFullLinkChange: (Boolean) -> Unit,
    hideXl: Boolean,
    onHideXlChange: (Boolean) -> Unit,
    addNewButton: Boolean,
    onAddNewButtonChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    var showXlDeleteDialog by remember { mutableStateOf(false) }
    var xlCheckToken by remember { mutableIntStateOf(0) }

    fun handleBlockDownloadXlLogDirChange(checked: Boolean) {
        onBlockDownloadXlLogDirChange(checked)
        xlCheckToken++
        val token = xlCheckToken
        if (!checked) {
            showXlDeleteDialog = false
            return
        }
        coroutineScope.launch {
            when (DownloadXlStorage.directoryExists()) {
                true -> if (token == xlCheckToken) showXlDeleteDialog = true
                false -> Unit
                null -> if (token == xlCheckToken) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_manager_xl_check_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.download_manager_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            MiuixIcons.Back,
                            contentDescription = stringResource(R.string.download_manager_back)
                        )
                    }
                }
            )
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

            SmallTitle(text = stringResource(R.string.download_manager_section_general))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = alwaysShowFullLink,
                        onCheckedChange = onAlwaysShowFullLinkChange,
                        title = stringResource(R.string.download_manager_always_show_link_title),
                        summary = stringResource(R.string.download_manager_always_show_link_summary)
                    )
                    SwitchPreference(
                        checked = hideXl,
                        onCheckedChange = onHideXlChange,
                        title = stringResource(R.string.download_manager_hide_xl_title),
                        summary = stringResource(R.string.download_manager_hide_xl_summary)
                    )
                    SwitchPreference(
                        checked = addNewButton,
                        onCheckedChange = onAddNewButtonChange,
                        title = stringResource(R.string.download_manager_add_new_button_title),
                        summary = stringResource(R.string.download_manager_add_new_button_summary)
                    )
                    SwitchPreference(
                        checked = blockDownloadXlLogDir,
                        onCheckedChange = ::handleBlockDownloadXlLogDirChange,
                        title = stringResource(R.string.download_manager_block_xl_title),
                        summary = stringResource(R.string.download_manager_block_xl_summary)
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    OverlayDialog(
        show = showXlDeleteDialog,
        title = stringResource(R.string.download_manager_xl_dialog_title),
        summary = stringResource(R.string.download_manager_xl_dialog_summary),
        onDismissRequest = { showXlDeleteDialog = false },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.download_manager_xl_keep),
                    onClick = { showXlDeleteDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.download_manager_xl_delete),
                    onClick = {
                        showXlDeleteDialog = false
                        coroutineScope.launch {
                            val deleted = DownloadXlStorage.deleteDirectory()
                            val message = when (deleted) {
                                true -> R.string.download_manager_xl_delete_success
                                false, null -> R.string.download_manager_xl_delete_failed
                            }
                            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}
