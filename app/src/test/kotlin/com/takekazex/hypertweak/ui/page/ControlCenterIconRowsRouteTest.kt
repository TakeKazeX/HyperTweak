package com.takekazex.hypertweak.ui.page

import org.junit.Assert.*
import org.junit.Test

class ControlCenterIconRowsRouteTest {
    @Test fun rowMenuBackStackSurvivesSavedNavigationAndReturnsToTheTuner() {
        val stack = listOf(Route.Main, Route.SystemUi, Route.IconTuner, Route.ControlCenterIconRows)
        val restored = stack.map { routeFromSaveKey(it.saveKey) }
        assertEquals(stack, restored)
        assertEquals(Route.IconTuner, restored.dropLast(1).last())
        assertNull(routeFromSaveKey("UnknownFutureMenu"))
    }
}
