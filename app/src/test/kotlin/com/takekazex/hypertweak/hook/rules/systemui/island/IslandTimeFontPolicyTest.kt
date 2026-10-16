package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class IslandTimeFontPolicyTest {
    @Test fun `only Clock and Recorder qualify even for numeric slots`() {
        for (pkg in listOf(null, "com.example.timer", "com.android.deskclock.other")) {
            assertFalse(IslandTimeFontPolicy.eligible(pkg, true, "01:23"))
        }
    }
    @Test fun `running blank chronometer and paused numeric slots qualify`() {
        for (pkg in listOf("com.android.deskclock", "com.android.soundrecorder")) {
            assertTrue(IslandTimeFontPolicy.eligible(pkg, true, null))
            assertTrue(IslandTimeFontPolicy.eligible(pkg, true, "123"))
        }
    }
    @Test fun `text slots require complete time digits including localized and fractional formats`() {
        for (time in listOf("00:12", "1:02:03", "−00:01", "00:12.34", " 01:23 ", "٠١:٢٣", "１２：３４")) {
            assertTrue(time, IslandTimeFontPolicy.eligible("com.android.soundrecorder", false, time))
        }
        for (label in listOf(null, "", "录音中", "计时器 01:23", "123", "12:123", "01:23 remaining")) {
            assertFalse(label, IslandTimeFontPolicy.eligible("com.android.deskclock", false, label))
        }
    }
}
