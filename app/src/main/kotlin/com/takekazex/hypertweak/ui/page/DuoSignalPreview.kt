package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoBattery
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoContent
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoDrawable
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Uses the production drawable, so settings previews cannot silently diverge from its geometry. */
@Composable
internal fun DuoSignalPreview() {
    val foreground = MiuixTheme.colorScheme.onSurface.toArgb()
    val description = stringResource(R.string.icon_duo_preview)
    val icons = remember {
        listOf(
            DuoContent(DuoBattery(80, false, false), 4, null, 4, false, false),
            DuoContent(DuoBattery(65, true, false), null, "5G", 3, false, false),
            DuoContent(DuoBattery(15, false, false), null, "4G", 2, false, false),
            DuoContent(DuoBattery(45, false, true), null, "5G-A", 4, false, false)
        ).map { state -> DuoDrawable().apply { content = state } }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = description }) {
        icons.forEach { drawable ->
            Canvas(Modifier.weight(1f).height(52.dp).padding(8.dp)) {
                drawable.foreground = foreground
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawIntoCanvas { drawable.draw(it.nativeCanvas) }
            }
        }
    }
}
