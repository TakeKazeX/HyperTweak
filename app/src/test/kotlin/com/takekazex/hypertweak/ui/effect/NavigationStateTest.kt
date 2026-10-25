package com.takekazex.hypertweak.ui.effect

import androidx.compose.runtime.toMutableStateList
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.navigation.navigateTo
import com.takekazex.hypertweak.ui.navigation.restoreNavigationRoutes
import com.takekazex.hypertweak.ui.page.saveKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavKey

class NavigationStateTest {
    @Test fun controllerUsesTheRestoredStackWithoutChangingSavedKeys() {
        listOf(
            listOf("Main", "SystemUi", "Appearance"),
            listOf("Main", "Purification"),
            listOf("Main", "DeviceInfo", "BatteryInfo"),
            listOf("Main", "BatteryInfo")
        ).forEach { saved ->
            val stack = restoreNavigationRoutes(saved).toMutableStateList<NavKey>()
            val controller = NavController(stack)
            assertEquals(saved, stack.map { (it as Route).saveKey })
            assertTrue(controller.pop())
            controller.navigateTo(Route.About)
            assertEquals(saved.dropLast(1) + "About", stack.map { (it as Route).saveKey })
        }
    }

    @Test fun repeatedBackCannotRemoveRoot() {
        val stack = listOf<NavKey>(Route.Main, Route.About).toMutableStateList()
        val controller = NavController(stack)
        assertTrue(controller.pop())
        repeat(3) { assertFalse(controller.pop()) }
        assertEquals(listOf(Route.Main), stack.toList())
    }

    @Test fun rapidBatteryInfoClicksFromEitherEntryKeepOnePageAndReturnToCaller() {
        listOf(
            listOf<NavKey>(Route.Main),
            listOf<NavKey>(Route.Main, Route.DeviceInfo)
        ).forEach { caller ->
            val stack = caller.toMutableStateList()
            val controller = NavController(stack)
            repeat(5) { controller.navigateTo(Route.BatteryInfo) }
            assertEquals(caller + Route.BatteryInfo, stack.toList())
            assertTrue(controller.pop())
            assertEquals(caller, stack.toList())
            controller.navigateTo(Route.BatteryInfo)
            assertEquals(caller + Route.BatteryInfo, stack.toList())
        }
    }

    @Test fun navigatingToAnExistingRouteReturnsToItWithoutDuplicatingKeys() {
        val stack = listOf<NavKey>(Route.Main, Route.BatteryInfo, Route.DeviceInfo)
            .toMutableStateList()
        val controller = NavController(stack)
        controller.navigateTo(Route.BatteryInfo)
        assertEquals(listOf(Route.Main, Route.BatteryInfo), stack.toList())
        controller.navigateTo(Route.Main)
        assertEquals(listOf(Route.Main), stack.toList())
        assertFalse(controller.pop())
    }

    @Test fun restoredLegacyStacksDiscardDuplicatesAndUnknownRoutes() {
        assertEquals(
            listOf(Route.Main, Route.DeviceInfo, Route.BatteryInfo),
            restoreNavigationRoutes(listOf("Main", "DeviceInfo", "BatteryInfo", "BatteryInfo", "removed"))
        )
        assertEquals(
            listOf(Route.Main, Route.BatteryInfo, Route.DeviceInfo),
            restoreNavigationRoutes(listOf("Main", "BatteryInfo", "DeviceInfo", "BatteryInfo"))
        )
        assertEquals(listOf(Route.Main), restoreNavigationRoutes(listOf("removed")))
        assertEquals(listOf(Route.Main), restoreNavigationRoutes(emptyList()))
    }

}
