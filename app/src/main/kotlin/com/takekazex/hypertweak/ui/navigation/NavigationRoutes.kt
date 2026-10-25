package com.takekazex.hypertweak.ui.navigation

import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.routeFromSaveKey
import top.yukonga.miuix.kmp.nav.core.NavController

/** Singleton routes must occur only once: Miuix uses the route as its content key. */
internal fun NavController.navigateTo(route: Route) {
    if (route in backStack) {
        popUntil { it == route }
    } else {
        push(route)
    }
}

/** Also repair stacks saved before duplicate route admission was prevented. */
internal fun restoreNavigationRoutes(keys: List<String>): List<Route> =
    keys.mapNotNull(::routeFromSaveKey).distinct().ifEmpty { listOf(Route.Main) }
