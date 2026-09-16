package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * The "tap the row to type an exact number" dialog shared by every numeric settings row.
 *
 * The pages that offer an inline slider (interface scale, corner radius, notification-icon limit)
 * all need the same second input path, because a slider cannot reach an arbitrary value on a
 * narrow screen. Keeping one implementation also keeps the digit-only filter, the empty-field
 * meaning, and the range clamp identical everywhere.
 *
 * @param suffix trailing unit shown inside the field (for example `%`); null hides it.
 * @param emptyValue the value an empty field commits, so callers can decide whether a blank field
 *        means "the minimum" or "keep the current value".
 */
@Composable
fun IntValueDialog(
    show: Boolean,
    title: String,
    summary: String,
    suffix: String?,
    range: IntRange,
    currentValue: () -> Int,
    emptyValue: Int,
    onValueConfirmed: (Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        content = {
            var text by remember(show) { mutableStateOf(currentValue().toString()) }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                trailingIcon = if (suffix == null) {
                    null
                } else {
                    {
                        Text(
                            text = suffix,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.onSurfaceVariantActions,
                        )
                    }
                },
                onValueChange = { newValue ->
                    // Digit-only, and empty is allowed while editing so the user can clear the
                    // field before typing a new value.
                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                        text = newValue
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.scale_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.scale_ok),
                    onClick = {
                        val resolved = (text.toIntOrNull() ?: emptyValue)
                            .coerceIn(range.first, range.last)
                        onValueConfirmed(resolved)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}
