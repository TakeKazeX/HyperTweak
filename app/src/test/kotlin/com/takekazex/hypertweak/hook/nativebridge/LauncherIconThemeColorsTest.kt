package com.takekazex.hypertweak.hook.nativebridge

import org.junit.Assert.*
import org.junit.Test

class LauncherIconThemeColorsTest {
    @Test fun `absent transparent malformed and partial alpha use visible native preset`() {
        for (value in listOf(null, "0", "16711680", "2147483647", "bad", "4294967296", "-2147483649")) {
            assertEquals(0xffd8e1fc.toInt(), LauncherIconThemeColors.nativeColor(value))
        }
    }
    @Test fun `opaque native colors retain signed and unsigned encoding`() {
        assertEquals(0xff123456.toInt(), LauncherIconThemeColors.nativeColor("-15584170"))
        assertEquals(0xff123456.toInt(), LauncherIconThemeColors.nativeColor("4279383126"))
    }
}
