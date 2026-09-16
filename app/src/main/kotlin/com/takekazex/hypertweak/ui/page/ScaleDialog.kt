package com.takekazex.hypertweak.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import kotlin.math.roundToInt

/** Interface-scale row: 85%–115% typed as a whole percentage. */
@Composable
fun ScaleDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    volumeState: () -> Float,
    onVolumeChange: (Float) -> Unit,
) {
    val current = { (volumeState() * 100).roundToInt() }
    IntValueDialog(
        show = show,
        title = stringResource(R.string.scale_title),
        summary = stringResource(R.string.scale_summary),
        suffix = stringResource(R.string.scale_percent),
        range = 85..115,
        currentValue = current,
        // A blank field keeps the current scale instead of jumping to the minimum.
        emptyValue = current(),
        onValueConfirmed = { onVolumeChange(it / 100f) },
        onDismissRequest = onDismissRequest,
    )
}
