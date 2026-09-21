package com.takekazex.hypertweak.hook.rules.googleapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class GoogleAppAskAboutScreenHookerTest {
    private class ExecutorImplementation : Executor {
        override fun execute(command: Runnable) = Unit
    }

    private class SignatureWithExecutorImplementations(
        val unrelatedExecutor: ExecutorImplementation,
        val firstAimSwitch: Boolean,
        val secondAimSwitch: Boolean,
        val executor: Executor,
        val trailingExecutor: ExecutorImplementation
    )

    private class SignatureWithoutExecutorInterface(
        val executorImplementation: ExecutorImplementation,
        val ordinaryFlag: Boolean
    )

    @Test
    fun findsBooleanTailBeforeExactExecutorParameter() {
        val parameterTypes = SignatureWithExecutorImplementations::class.java
            .declaredConstructors.single().parameterTypes

        assertArrayEquals(
            intArrayOf(1, 2),
            GoogleAppAskAboutScreenHooker.findAimBooleanParameterIndices(parameterTypes)
        )
    }

    @Test
    fun failsClosedWhenOnlyExecutorImplementationsArePresent() {
        val parameterTypes = SignatureWithoutExecutorInterface::class.java
            .declaredConstructors.single().parameterTypes

        assertTrue(
            GoogleAppAskAboutScreenHooker.findAimBooleanParameterIndices(parameterTypes).isEmpty()
        )
    }
}
