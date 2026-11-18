package com.takekazex.hypertweak.ui.chrome

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** True only inside the navigation/action slots of an OS4 glass top bar. */
internal val LocalGlassTopBarSlot = staticCompositionLocalOf { false }

@Composable
internal fun GlassTopBarSlot(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassTopBarSlot provides true, content = content)
}

private const val DISABLED_ALPHA = 0.38f

/**
 * Drop-in for Miuix `IconButton`: an upstream [GlassIconButton] pill inside an OS4 top bar
 * (inheriting the bar's backdrop and floating motion), the classic button everywhere else.
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    minHeight: Dp = IconButtonDefaults.MinHeight,
    minWidth: Dp = IconButtonDefaults.MinWidth,
    content: @Composable () -> Unit,
) {
    if (LocalGlassTopBarSlot.current) {
        GlassIconButton(
            onClick = { if (enabled) onClick() },
            modifier = modifier.then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)),
            content = content,
        )
    } else {
        top.yukonga.miuix.kmp.basic.IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            holdDownState = holdDownState,
            backgroundColor = backgroundColor,
            cornerRadius = cornerRadius,
            minHeight = minHeight,
            minWidth = minWidth,
            content = content,
        )
    }
}

/** Back button: glass chevron in OS4 bars (upstream example), the classic Miuix arrow otherwise. */
@Composable
internal fun BackNavigationIcon(onClick: () -> Unit, contentDescription: String) {
    val glass = LocalGlassTopBarSlot.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AppIconButton(onClick = onClick) {
        Icon(
            modifier = Modifier
                .then(if (glass) Modifier.size(28.dp) else Modifier)
                .graphicsLayer { if (rtl) scaleX = -1f },
            imageVector = if (glass) MiuixIcons.Glass.ChevronBackward else MiuixIcons.Back,
            contentDescription = contentDescription,
            tint = if (glass) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onBackground,
        )
    }
}

/** Overlay dialog hosted by [AppPage]: upstream `GlassDialog` in OS4, Miuix `OverlayDialog` in OS3. */
@Composable
internal fun AppOverlayDialog(
    show: Boolean,
    title: String,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    check(LocalAppOverlayHost.current) { "AppOverlayDialog must be inside AppPage" }
    if (LocalAppUiStyle.current == AppUiStyle.Os4) {
        GlassDialog(
            show = show,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
            backdrop = LocalPageBackdrop.current,
        ) {
            CompositionLocalProvider(LocalGlassDialog provides true, content = content)
        }
    } else {
        top.yukonga.miuix.kmp.overlay.OverlayDialog(
            show = show,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
            content = content,
        )
    }
}

/**
 * Drop-in for Miuix `OverlayDropdownPreference` (string-items form): the upstream glass popup
 * over the page backdrop in OS4, the classic dropdown in OS3.
 */
@Composable
fun AppDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    renderInRootScaffold: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    if (LocalAppUiStyle.current == AppUiStyle.Os4) {
        GlassDropdownPreference(
            onSelectedIndexChange = { onSelectedIndexChange?.invoke(it) },
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            backdrop = LocalPageBackdrop.current,
            modifier = modifier,
            enabled = enabled,
            showValue = showValue,
            renderInRootScaffold = renderInRootScaffold,
            titleColor = titleColor,
            summary = summary,
            summaryColor = summaryColor,
            startAction = startAction,
            bottomAction = bottomAction,
            insideMargin = insideMargin,
            sizing = if (maxHeight != null) GlassPopupSizing(maxHeight = maxHeight) else GlassPopupSizing(),
            onExpandedChange = onExpandedChange,
        )
    } else {
        OverlayDropdownPreference(
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            modifier = modifier,
            titleColor = titleColor,
            summary = summary,
            summaryColor = summaryColor,
            dropdownColors = dropdownColors,
            startAction = startAction,
            bottomAction = bottomAction,
            insideMargin = insideMargin,
            maxHeight = maxHeight,
            enabled = enabled,
            showValue = showValue,
            renderInRootScaffold = renderInRootScaffold,
            onExpandedChange = onExpandedChange,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    }
}

/** Entry form (grouped items) has no glass counterpart upstream and stays classic. */
@Composable
fun AppDropdownPreference(
    entry: DropdownEntry,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    collapseOnSelection: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    OverlayDropdownPreference(
        entry = entry,
        title = title,
        modifier = modifier,
        summary = summary,
        startAction = startAction,
        enabled = enabled,
        showValue = showValue,
        collapseOnSelection = collapseOnSelection,
        onExpandedChange = onExpandedChange,
    )
}

/**
 * Page tab row: upstream `GlassTabRow` over the page backdrop in OS4, Miuix `TabRowWithContour`
 * in OS3. The classic contour uses the container surface so it keeps a visible base on the
 * `surface` page.
 */
@Composable
fun AppTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = selectedIndex.coerceIn(0, tabs.lastIndex)
    val rowModifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    if (LocalAppUiStyle.current == AppUiStyle.Os4) {
        top.yukonga.miuix.kmp.glass.GlassTabRow(
            tabs = tabs,
            selectedIndex = index,
            onSelect = onSelect,
            backdrop = LocalPageBackdrop.current,
            modifier = rowModifier,
        )
    } else {
        top.yukonga.miuix.kmp.basic.TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = index,
            onTabSelected = onSelect,
            colors = top.yukonga.miuix.kmp.basic.TabRowDefaults.tabRowColors(
                backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                selectedBackgroundColor = MiuixTheme.colorScheme.surface,
            ),
            modifier = rowModifier,
        )
    }
}

/** True inside an OS4 glass dialog; [AppTextButton] renders upstream glass dialog actions there. */
internal val LocalGlassDialog = staticCompositionLocalOf { false }

/**
 * Drop-in for Miuix `TextButton`: inside a glass dialog it becomes a `GlassDialogButton`, with the
 * primary role when the caller passed `ButtonDefaults.textButtonColorsPrimary()`.
 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: top.yukonga.miuix.kmp.basic.TextButtonColors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(),
    destructive: Boolean = false,
) {
    if (LocalGlassDialog.current) {
        val primary = colors == top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
        top.yukonga.miuix.kmp.glass.GlassDialogButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            role = when {
                destructive -> top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Destructive
                primary -> top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary
                else -> top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Normal
            },
        ) {
            top.yukonga.miuix.kmp.basic.Text(text = text, style = MiuixTheme.textStyles.button)
        }
    } else {
        val error = com.takekazex.hypertweak.ui.theme.statusColors(com.takekazex.hypertweak.ui.theme.StatusTone.Error).accent
        top.yukonga.miuix.kmp.basic.TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (destructive) {
                top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(
                    color = error,
                    disabledColor = error.copy(alpha = 0.4f),
                    textColor = Color.White,
                    disabledTextColor = Color.White.copy(alpha = 0.6f),
                )
            } else {
                colors
            },
        )
    }
}
