package com.takekazex.hypertweak.ui.effect

import androidx.compose.ui.graphics.Color
import com.takekazex.hypertweak.ui.page.navigationItemTint
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationItemTintTest {
    @Test fun pressingNeverMakesTransparentItemsVisible() {
        for (selected in listOf(false, true)) {
            assertEquals(0f, navigationItemTint(selected, true, Color.Transparent, Color.Transparent).alpha, 0f)
        }
    }

    @Test fun selectedTintRetainsItsAuthoredOpacity() {
        val selected = Color.Red.copy(alpha = 0.25f)
        val pressed = navigationItemTint(true, true, selected, Color.Blue)
        assertEquals(0.125f, pressed.alpha, 0.005f)
        assertEquals(selected, navigationItemTint(true, false, selected, Color.Blue))
    }
}
