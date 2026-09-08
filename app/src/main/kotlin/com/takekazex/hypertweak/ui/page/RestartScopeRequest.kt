package com.takekazex.hypertweak.ui.page

import androidx.compose.runtime.staticCompositionLocalOf
import com.takekazex.hypertweak.util.RestartScopeSelection

/**
 * Reports a setting change that needs one of the currently scoped processes restarted.
 *
 * Secondary pages are deliberately self-contained, so this keeps their controls connected to the
 * Home page's single restart dialog without threading another callback through every navigation
 * entry. The default is a no-op so the pages remain preview/testable outside MainActivity.
 */
val LocalRestartScopeRequest = staticCompositionLocalOf<(RestartScopeSelection) -> Unit> { {} }

/** Clears the shared Home reminder after an explicit page-level restart completes. */
val LocalRestartScopeHandled = staticCompositionLocalOf<(RestartScopeSelection) -> Unit> { {} }
