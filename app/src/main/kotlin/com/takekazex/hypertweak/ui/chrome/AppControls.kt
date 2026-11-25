package com.takekazex.hypertweak.ui.chrome

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRowDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Search as ClassicSearch
import top.yukonga.miuix.kmp.icon.glass.Search as GlassSearch
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

/** Top-bar search entry: the glass search glyph in OS4 bars, the classic Miuix one otherwise. */
@Composable
fun AppSearchAction(onClick: () -> Unit) {
    val glass = LocalGlassTopBarSlot.current
    AppIconButton(onClick = onClick) {
        Icon(
            modifier = if (glass) Modifier.size(28.dp) else Modifier,
            imageVector = if (glass) MiuixIcons.Glass.GlassSearch else MiuixIcons.ClassicSearch,
            contentDescription = stringResource(R.string.search_title),
            tint = if (glass) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onBackground,
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

/** Miuix `TabRowWithContour` internals: fixed track inset (not a parameter) and default spacing. */
private val ContourTrackPadding = 5.dp
private val ContourTabSpacing = 5.dp

/**
 * Page tab row: upstream `GlassSegmentedTabRow` in OS4, Miuix `TabRowWithContour` in OS3. The
 * glass track rests clear and rises once content scrolls under the top bar (upstream
 * `surfaceAlpha`); without blur it stays a plain fill. The classic contour uses the container
 * surface so it keeps a visible base on the `surface` page.
 */
@Composable
fun AppTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val index = selectedIndex.coerceIn(tabs.indices)
    val glass = LocalAppUiStyle.current == AppUiStyle.Os4
    val textStyles = MiuixTheme.textStyles
    val selectedStyle = if (glass) textStyles.subtitle else textStyles.body1.copy(fontWeight = FontWeight.Bold)
    val idleStyle = if (glass) textStyles.body2 else textStyles.body1.copy(fontWeight = FontWeight.Normal)
    val measurer = rememberTextMeasurer()
    val (widthPx, heightPx) = remember(tabs, measurer, selectedStyle, idleStyle) {
        val sizes = tabs.flatMap { label ->
            listOf(
                measurer.measure(label, style = selectedStyle, maxLines = 1).size,
                measurer.measure(label, style = idleStyle, maxLines = 1).size,
            )
        }
        sizes.maxOf { it.width } to sizes.maxOf { it.height }
    }
    val (labelWidth, labelHeight) = with(LocalDensity.current) { widthPx.toDp() to heightPx.toDp() }
    if (glass) {
        val backdrop = LocalTopAppBarBackdrop.current
        val fallbackBackdrop = rememberLayerBackdrop()
        val scrolled = LocalTopBarContentScrolled.current
        GlassSegmentedTabRow(
            tabs = tabs,
            selectedIndex = index,
            onSelect = onSelect,
            backdrop = backdrop ?: fallbackBackdrop,
            surfaceAlpha = if (backdrop != null && scrolled) 1f else 0f,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 12.dp),
            // Large font scales only; at default sizes this is the upstream height.
            height = maxOf(GlassSegmentedTabRowDefaults.Height, labelHeight + 16.dp),
        )
    } else {
        val minWidth = maxOf(TabRowDefaults.TabRowWithContourMinWidth, labelWidth + 24.dp)
        val density = LocalDensity.current
        BoxWithConstraints(
            modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Miuix rounds each fractional tab width to whole pixels, so fitting tabs can sum a
            // pixel or two past the track; its LazyRow then reports a scroll range and lets the
            // row be dragged/overscrolled. Shrink the track (by < tab count px) to a width whose
            // tab share is integral, keeping the row static whenever the tabs fit.
            val trackWidth = with(density) {
                val outerPx = maxWidth.roundToPx()
                val contourPx = ContourTrackPadding.roundToPx() * 2
                val spacingPx = ContourTabSpacing.roundToPx() * tabs.lastIndex
                val tabPx = (outerPx - contourPx - spacingPx) / tabs.size
                if (tabPx >= minWidth.roundToPx()) (tabPx * tabs.size + spacingPx + contourPx).toDp() else maxWidth
            }
            Box(Modifier.width(trackWidth)) {
                TabRowWithContour(
                    tabs = tabs,
                    selectedTabIndex = index,
                    onTabSelected = onSelect,
                    colors = TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                        selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                    ),
                    minWidth = minWidth,
                    maxWidth = maxOf(minWidth, TabRowDefaults.TabRowWithContourMaxWidth),
                    height = maxOf(TabRowDefaults.TabRowWithContourHeight, labelHeight + 12.dp),
                    itemSpacing = ContourTabSpacing,
                )
            }
        }
    }
}

/**
 * Drop-in for Miuix `TextField` (state form): upstream `GlassTextField` inside an OS4 glass
 * dialog, so its fill and label contrast follow the glass material; the classic field elsewhere.
 */
@Composable
fun AppTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    inputTransformation: InputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    if (LocalGlassDialog.current) {
        top.yukonga.miuix.kmp.glass.GlassTextField(
            state = state,
            modifier = modifier,
            label = label,
            enabled = enabled,
            inputTransformation = inputTransformation,
            keyboardOptions = keyboardOptions,
            lineLimits = lineLimits,
            trailingIcon = trailingIcon,
        )
    } else {
        top.yukonga.miuix.kmp.basic.TextField(
            state = state,
            modifier = modifier,
            label = label,
            enabled = enabled,
            inputTransformation = inputTransformation,
            keyboardOptions = keyboardOptions,
            lineLimits = lineLimits,
            trailingIcon = trailingIcon,
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
