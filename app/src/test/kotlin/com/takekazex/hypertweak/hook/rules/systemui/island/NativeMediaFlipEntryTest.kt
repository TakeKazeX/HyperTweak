package com.takekazex.hypertweak.hook.rules.systemui.island

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaFlipEntry
import org.junit.Assert.*
import org.junit.Test

// Intentionally outside the utility: no declaredClasses relationship is available.
private interface ExternalCoverCallback { fun onFlip() }
private interface WrongCoverCallback { fun onFlip(direction: Boolean) }

class NativeMediaFlipEntryTest {
    private class Utility {
        @Suppress("UNUSED_PARAMETER")
        fun startFlipAnimation(view: View, positive: Boolean, changed: Boolean, callback: ExternalCoverCallback) = Unit
    }
    private class WrongUtility {
        @Suppress("UNUSED_PARAMETER")
        fun startFlipAnimation(view: View, positive: Boolean, changed: Boolean, callback: WrongCoverCallback) = Unit
    }

    @Test fun `callback resolution does not require a nested class relationship`() {
        assertTrue(Utility::class.java.declaredClasses.isEmpty())
        val method = NativeMediaFlipEntry.resolve(Utility::class.java)
        assertEquals(ExternalCoverCallback::class.java, method.parameterTypes.last())
    }

    @Test fun `incompatible callback fails with an actionable contract error`() {
        val error = runCatching { NativeMediaFlipEntry.resolve(WrongUtility::class.java) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("found 0"))
    }
}
