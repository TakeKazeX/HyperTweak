package com.takekazex.hypertweak.hook.rules.backgesture.hooks.core

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HostEnumResolverTest {
    private enum class AnimationType {
        CLOSE_TO_DRAG {
            override fun toString() = "localized display label"
        },
        APP_TO_APP,
    }

    @Test fun `resolves the declared name even with a constant-specific subclass and label`() {
        assertSame(AnimationType.CLOSE_TO_DRAG,
            HostEnumResolver.valueOf(AnimationType::class.java, "CLOSE_TO_DRAG"))
        assertSame(AnimationType.APP_TO_APP,
            HostEnumResolver.valueOf(AnimationType::class.java, "APP_TO_APP"))
    }

    @Test fun `missing and differently cased host constants fail closed`() {
        for (name in listOf("MISSING", "app_to_app", "localized display label")) {
            assertThrows(IllegalArgumentException::class.java) {
                HostEnumResolver.valueOf(AnimationType::class.java, name)
            }
        }
    }

    @Test fun `a non-enum or an enum constant subclass is rejected`() {
        for (type in listOf(String::class.java, AnimationType.CLOSE_TO_DRAG.javaClass)) {
            assertThrows(IllegalArgumentException::class.java) {
                HostEnumResolver.valueOf(type, "CLOSE_TO_DRAG")
            }
        }
    }
}
