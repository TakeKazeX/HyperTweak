package com.takekazex.hypertweak.ui.page.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.search.SearchEntry
import com.takekazex.hypertweak.ui.search.SearchGroup
import com.takekazex.hypertweak.ui.search.SearchItem
import com.takekazex.hypertweak.ui.search.SettingsSearchIndex
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The search index resolved once per locale; it holds a few hundred string ids. */
@Composable
internal fun rememberSearchItems(): List<SearchItem> {
    val resources = LocalResources.current
    return remember(resources) {
        fun text(id: Int) = if (id == 0) "" else resources.getString(id)
        SettingsSearchIndex.map {
            SearchItem(it, text(it.title), text(it.summary), text(it.page), text(it.section))
        }
    }
}

/**
 * Matching rows grouped by the page that holds them. Each row's summary is the section it sits in
 * (or its own summary when the section only repeats the page). Shared by the classic search page
 * and the OS4 glass search overlay.
 */
internal fun LazyListScope.searchResults(
    query: String,
    groups: List<SearchGroup>,
    onOpen: (SearchEntry) -> Unit,
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
                    onClick = { onOpen(item.entry) },
                )
            }
        }
    }
}

/** Where the row sits on its page, falling back to what it does. */
private fun SearchItem.detail(): String? =
    section.takeIf { it.isNotEmpty() && it != page && it != title }
        ?: summary.ifEmpty { null }
