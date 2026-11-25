package com.takekazex.hypertweak.ui.components.dialog

import com.takekazex.hypertweak.ui.chrome.AppTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppOverlayDialog as OverlayDialog
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
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
            val input = remember(show) { TextFieldState(currentValue().toString()) }
            AppTextField(
                modifier = Modifier.padding(bottom = 16.dp),
                state = input,
                lineLimits = TextFieldLineLimits.SingleLine,
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
                inputTransformation = InputTransformation.byValue { previous, proposed ->
                    // A leading minus is accepted only for signed ranges.
                    val digits = if (range.first < 0 && proposed.startsWith('-')) proposed.drop(1) else proposed
                    if (digits.all(Char::isDigit)) proposed else previous
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
                        val resolved = (input.text.toString().toIntOrNull() ?: emptyValue)
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
