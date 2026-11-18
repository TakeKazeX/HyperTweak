package com.takekazex.hypertweak.ui.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * A settings group: optional Miuix [SmallTitle] over a full-width card inset 12dp from the page
 * edges, its rows stacked in a full-width column (the Miuix settings-list layout).
 */
@Composable
fun AppSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) SmallTitle(title)
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
