package com.takekazex.hypertweak.hook.rules.securitycenter.battery

import android.os.Handler
import android.os.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeProtectHandlerContractTest {
    @Suppress("unused") class RefreshHandler : Handler() { override fun handleMessage(msg: Message) = Unit }
    @Suppress("unused") class OtherHandler : Handler() { override fun handleMessage(msg: Message) = Unit }
    @Suppress("unused") class Task : Runnable { override fun run() = Unit }

    @Suppress("unused") class Current {
        private val task: Task? = null
        private val handler: RefreshHandler? = null
        private val plain: Handler? = null
        companion object { @JvmStatic private val shared: OtherHandler? = null }
    }

    @Suppress("unused") class Ambiguous {
        private val first: RefreshHandler? = null
        private val second: OtherHandler? = null
    }

    @Suppress("unused") class Missing { private val task: Task? = null }

    @Test
    fun resolvesTheOnlyInstanceHandlerSubclassRegardlessOfNestedName() {
        val method = ChargeProtectHandlerContract.resolve(Current::class.java)
        assertEquals(RefreshHandler::class.java, method?.declaringClass)
        assertEquals(listOf(Message::class.java), method?.parameterTypes?.toList())
    }

    @Test
    fun rejectsAmbiguousOrMissingHandlers() {
        assertNull(ChargeProtectHandlerContract.resolve(Ambiguous::class.java))
        assertNull(ChargeProtectHandlerContract.resolve(Missing::class.java))
    }
}
