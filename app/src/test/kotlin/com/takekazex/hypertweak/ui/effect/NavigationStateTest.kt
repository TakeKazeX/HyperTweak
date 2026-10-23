package com.takekazex.hypertweak.ui.effect

import androidx.compose.runtime.toMutableStateList
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.routeFromSaveKey
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
            val stack = saved.mapNotNull { routeFromSaveKey(it) as NavKey? }.toMutableStateList()
            val controller = NavController(stack)
            assertEquals(saved, stack.map { (it as Route).saveKey })
            assertTrue(controller.pop())
            controller.push(Route.About)
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
}
