package com.takekazex.hypertweak.ui.page.search

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppLazyPage
import com.takekazex.hypertweak.ui.search.SearchEntry
import com.takekazex.hypertweak.ui.search.SettingsSearch
import top.yukonga.miuix.kmp.basic.InputField

/**
 * Classic (OS3) settings search page: a Miuix search field under the title and the matching rows
 * grouped by page ([searchResults]). Selecting a row opens that page; the query survives the trip.
 * OS4 searches through the glass overlay on the main pager instead ([SettingsSearchOverlay]).
 */
@Composable
internal fun SearchPage(onBack: () -> Unit, onOpen: (SearchEntry) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val items = rememberSearchItems()
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
        searchResults(query, groups) { entry ->
            keyboard?.hide()
            onOpen(entry)
        }
        item(key = "ime") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime)) }
    }
}
