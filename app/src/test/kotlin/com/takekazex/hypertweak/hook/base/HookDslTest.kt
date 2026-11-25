package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.junit.Assert.*
import org.junit.Test

/** The module DSL must keep the EzHookTool 1.3.1 chain contract that every hooker was written against. */
class HookDslTest {
    private class Target { fun join(a: String, b: String) = a + b }
    private val method: Executable = Target::class.java.getDeclaredMethod("join", String::class.java, String::class.java)

    /** Records how the original was invoked; [original] computes it from the arguments it received. */
    private inner class FakeChain(
        private val receiver: Any?,
        private val arguments: List<Any?>,
        private val original: (Any?, List<Any?>) -> Any? = { _, args -> args.joinToString("") },
    ) : XposedInterface.Chain {
        val calls = ArrayList<Pair<Any?, List<Any?>>>()
        var argsRead = 0
        override fun getExecutable() = method
        override fun getThisObject() = receiver
        override fun getArgs(): List<Any?> { argsRead++; return arguments }
        override fun getArg(index: Int): Any? = arguments[index]
        private fun run(target: Any?, args: List<Any?>): Any? { calls += target to args; return original(target, args) }
        override fun proceed() = run(receiver, arguments)
        override fun proceed(args: Array<out Any?>) = run(receiver, args.toList())
        override fun proceedWith(thisObject: Any) = run(thisObject, arguments)
        override fun proceedWith(thisObject: Any, args: Array<out Any?>) = run(thisObject, args.toList())
    }

    private fun hooker(block: HookFactory.() -> Unit) = HookFactory(method).apply { id("test"); block() }.buildHooker("HookDslTest")

    @Test fun untouchedArgumentsProceedWithoutCopying() {
        val chain = FakeChain("r", listOf("a", "b"))
        val seen = ArrayList<Any?>()
        val result = hooker { before { seen += it.arg(0) }; after { seen += it.result } }.intercept(chain)
        assertEquals("ab", result)
        assertEquals(listOf("a", "ab"), seen)
        assertEquals(0, chain.argsRead)
        assertEquals(listOf("r" to listOf("a", "b")), chain.calls)
    }

    @Test fun rewrittenArgumentsReachTheOriginal() {
        val chain = FakeChain("r", listOf("a", "b"))
        assertEquals("xb", hooker { before { it.args[0] = "x" } }.intercept(chain))
        assertEquals(listOf("r" to listOf("x", "b")), chain.calls)
    }

    @Test fun beforeResultSkipsOriginalAndLaterBeforesButNotAfters() {
        val chain = FakeChain("r", listOf("a", "b"))
        val order = ArrayList<String>()
        val result = hooker {
            before { order += "b1"; it.result = "set" }
            before { order += "b2" }
            after { order += "a1" }
            after { order += "a2"; assertTrue(it.isSkipped) }
        }.intercept(chain)
        assertEquals("set", result)
        // After callbacks run in reverse registration order.
        assertEquals(listOf("b1", "a2", "a1"), order)
        assertTrue(chain.calls.isEmpty())
    }

    @Test fun afterResultDoesNotSkipAndReplacesTheReturnValue() {
        val chain = FakeChain("r", listOf("a", "b"))
        assertEquals("AB", hooker { after { it.result = (it.result as String).uppercase() } }.intercept(chain))
        assertEquals(1, chain.calls.size)
    }

    @Test fun originalExceptionReachesAftersAndThenTheCaller() {
        val boom = IllegalStateException("original")
        val chain = FakeChain("r", listOf("a", "b")) { _, _ -> throw boom }
        var seen: Throwable? = null
        val thrown = runCatching { hooker { after { seen = it.throwable } }.intercept(chain) }.exceptionOrNull()
        assertSame(boom, seen)
        assertSame(boom, thrown)
    }

    @Test fun failingBeforeFallsBackToTheUnmodifiedOriginal() {
        val chain = FakeChain("r", listOf("a", "b"))
        val result = hooker { before { it.args[0] = "x"; error("callback") } }.intercept(chain)
        assertEquals("ab", result)
        assertEquals(listOf("r" to listOf("a", "b")), chain.calls)
    }

    @Test fun failingAfterReturnsTheOutcomeItWasGiven() {
        val chain = FakeChain("r", listOf("a", "b"))
        val result = hooker {
            after { error("callback") }
            after { it.result = "changed" }
        }.intercept(chain)
        assertEquals("changed", result)
        assertEquals(1, chain.calls.size)
    }

    @Test fun replaceAndReturnConstantSkipTheOriginal() {
        val chain = FakeChain("r", listOf("a", "b"))
        assertEquals("rep", hooker { replace { "rep" } }.intercept(chain))
        assertNull(hooker { interrupt() }.intercept(chain))
        assertEquals(true, hooker { returnConstant(true) }.intercept(chain))
        assertTrue(chain.calls.isEmpty())
    }

    @Test fun failingReplaceFallsBackToTheOriginal() {
        val chain = FakeChain("r", listOf("a", "b"))
        assertEquals("ab", hooker { replace { error("callback") } }.intercept(chain))
        assertEquals(1, chain.calls.size)
    }

    @Test fun interceptControlsArgumentsAndReceiver() {
        val chain = FakeChain("r", listOf("a", "b")) { target, args -> "$target:${args.joinToString("")}" }
        val result = hooker { intercept { c -> "<" + c.proceedWith("other", arrayOf("x", "y")) + ">" } }.intercept(chain)
        assertEquals("<other:xy>", result)
        assertEquals(listOf("other" to listOf("x", "y")), chain.calls)
    }

    @Test fun interceptThatNeverProceedsSkipsOriginal_andFallsBackWhenItThrows() {
        val chain = FakeChain("r", listOf("a", "b"))
        assertEquals("own", hooker { intercept { _ -> "own" } }.intercept(chain))
        assertTrue(chain.calls.isEmpty())
        assertEquals("ab", hooker { intercept { _ -> error("callback") } }.intercept(chain))
        assertEquals(1, chain.calls.size)
    }

    @Test fun staticReceiverProceedsWithoutReceiver() {
        val chain = FakeChain(null, listOf("a", "b"))
        val result = hooker { before { assertNull(it.thisObjectOrNull); it.args[1] = "z" } }.intercept(chain)
        assertEquals("az", result)
        assertEquals(listOf(null to listOf("a", "z")), chain.calls)
    }

    @Test fun everyInvocationIsCounted() {
        val owner = "HookDslTest.count"
        val hooker = HookFactory(method).apply { id("count"); before { } }.buildHooker(owner)
        repeat(3) { hooker.intercept(FakeChain("r", listOf("a", "b"))) }
        assertEquals(3, HookHits.counter(owner, "count").hits)
    }

    @Test fun repeatedCallbackFailuresAreCountedAndEachCallStillFallsBack() {
        val owner = "HookDslTest.failures"
        val hooker = HookFactory(method).apply { id("fail"); before { error("callback") } }.buildHooker(owner)
        repeat(25) { assertEquals("ab", hooker.intercept(FakeChain("r", listOf("a", "b")))) }
        val counter = HookHits.counter(owner, "fail")
        assertEquals(25, counter.hits)
        assertEquals(25, counter.failures)
    }
}
