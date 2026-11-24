package com.takekazex.hypertweak.ui.page.search

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppLazyPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.search.SearchEntry
import com.takekazex.hypertweak.ui.search.SearchItem
import com.takekazex.hypertweak.ui.search.SettingsSearch
import com.takekazex.hypertweak.ui.search.SettingsSearchIndex
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Settings search: a Miuix search field under the title and the matching rows grouped by the page
 * that holds them. Each row's summary is the section it sits in (or its own summary when the
 * section only repeats the page). Selecting a row opens that page; the query survives the trip.
 */
@Composable
internal fun SearchPage(onBack: () -> Unit, onOpen: (SearchEntry) -> Unit) {
    val resources = LocalResources.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    // Resolved once per locale; the index holds a few hundred string ids.
    val items = remember(resources) {
        fun text(id: Int) = if (id == 0) "" else resources.getString(id)
        SettingsSearchIndex.map {
            SearchItem(it, text(it.title), text(it.summary), text(it.page), text(it.section))
        }
    }
    val groups = remember(items, query) { SettingsSearch.search(items, query) }

    LaunchedEffect(Unit) {
        // Opening search starts typing; coming back from a result keeps the list in view.
        if (query.isEmpty()) focusRequester.requestFocus()
    }

    AppLazyPage(
        title = stringResource(R.string.search_title),
        onBack = onBack,
        topBarBottom = {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { keyboard?.hide() },
                expanded = focused,
                onExpandedChange = { focused = it },
                label = stringResource(R.string.search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
                    .focusRequester(focusRequester),
            )
        },
    ) {
        if (query.isNotBlank() && groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.search_no_results),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(groups, key = { group -> group.items.first().entry.let { "${it.route}/${it.tab}" } }) { group ->
            AppSection(
                modifier = Modifier.padding(bottom = 12.dp),
                title = group.label.ifEmpty { null },
            ) {
                group.items.forEach { item ->
                    ArrowPreference(
                        title = item.title,
                        summary = item.detail(),
                        onClick = {
                            keyboard?.hide()
                            onOpen(item.entry)
                        },
                    )
                }
            }
        }
        item(key = "ime") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime)) }
    }
}

/** Where the row sits on its page, falling back to what it does. */
private fun SearchItem.detail(): String? =
    section.takeIf { it.isNotEmpty() && it != page && it != title }
        ?: summary.ifEmpty { null }
