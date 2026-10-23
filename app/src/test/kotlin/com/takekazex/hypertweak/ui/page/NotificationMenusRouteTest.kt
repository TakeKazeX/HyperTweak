package com.takekazex.hypertweak.ui.page

import org.junit.Assert.*
import org.junit.Test

class NotificationMenusRouteTest {
    @Test fun notificationAndMediaMenusHaveIndependentRestorableBackStacks() {
        for (menu in listOf(Route.Notifications, Route.MediaCard)) {
            val stack = listOf(Route.Main, Route.SystemUi, menu)
            assertEquals(stack, stack.map { routeFromSaveKey(it.saveKey) })
            assertEquals(Route.SystemUi, stack.dropLast(1).last())
        }
        val nested = listOf(Route.Main, Route.SystemUi, Route.Notifications, Route.NotificationHeader)
        assertEquals(nested, nested.map { routeFromSaveKey(it.saveKey) })
    }
}
