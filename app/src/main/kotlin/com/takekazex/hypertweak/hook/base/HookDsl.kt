@file:Suppress("UNCHECKED_CAST")

package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.Collections

/**
 * Module-owned hook DSL on libxposed API 102.
 *
 * Callback semantics match the EzHookTool 1.3.1 chain it replaces, with its safe mode on:
 * - `before` callbacks run in registration order and stop once one sets a result or throwable;
 * - `replace`/`intercept` stages then run around the original;
 * - `after` callbacks run in reverse registration order, even when the original was skipped;
 * - a failing callback is logged, and the call falls back: `before`/`replace` (and an
 *   `intercept` that never proceeded) rerun the original with the original receiver and
 *   arguments, while an `after` returns the outcome it was given.
 *
 * Differences that do not change behavior: one [HookParam] per invocation is shared by every
 * stage, and the argument array is copied from the chain only when a callback reads [HookParam.args].
 */
class HookFactory internal constructor(private val target: Executable) {
    private val befores = ArrayList<(HookParam) -> Unit>(1)
    private val arounds = ArrayList<AroundStage>(0)
    private val afters = ArrayList<(HookParam) -> Unit>(1)
    internal var priority: Int = XposedInterface.PRIORITY_DEFAULT
        private set
    internal var exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT
        private set
    internal var hookId: String? = null
        private set

    fun before(callback: (HookParam) -> Unit) { befores += callback }

    fun after(callback: (HookParam) -> Unit) { afters += callback }

    /** Replaces the original; the callback's value is returned to the caller. */
    fun replace(callback: (HookParam) -> Any?) { arounds += AroundStage.Replace(callback) }

    /** Around callback that decides itself whether, and with what, the original proceeds. */
    fun intercept(callback: (XposedInterface.Chain) -> Any?) { arounds += AroundStage.Intercept(callback) }

    fun intercept(hooker: XposedInterface.Hooker) { arounds += AroundStage.Intercept { hooker.intercept(it) } }

    /** Skips the original and returns null. */
    fun interrupt() = returnConstant(null)

    fun returnConstant(value: Any?) { arounds += AroundStage.Replace { value } }

    /** Higher values run first. */
    fun priority(value: Int) { priority = value }

    fun exceptionMode(mode: XposedInterface.ExceptionMode) { exceptionMode = mode }

    /** A new hook with the same id on the same executable atomically replaces the old one. */
    fun id(value: String) {
        require(value.isNotBlank()) { "hook ID must not be blank." }
        hookId = value
    }

    internal fun buildHooker(owner: String): XposedInterface.Hooker {
        require(befores.isNotEmpty() || arounds.isNotEmpty() || afters.isNotEmpty()) { "No hook callback specified" }
        return HookDispatcher(
            owner = owner,
            target = target,
            befores = befores.toTypedArray(),
            arounds = arounds.toTypedArray(),
            afters = afters.asReversed().toTypedArray(),
            counter = HookHits.counter(owner, hookId ?: target.toString()),
        )
    }
}

/** Invocation state shared by every stage of one hooked call. */
class HookParam internal constructor(private val chain: XposedInterface.Chain) {
    private val originalReceiver: Any? = chain.thisObject
    internal var receiver: Any? = originalReceiver
    // Null until a callback reads or replaces the arguments: the chain's own are still current.
    internal var argArray: Array<Any?>? = null
    internal var afterStage = false
    internal var skipped = false
    private var outcome: Any? = null
    private var failure: Throwable? = null

    val executable: Executable get() = chain.executable
    val member: Executable get() = executable

    /** The receiver of an instance method; throws for a static method. */
    val thisObject: Any
        get() = receiver ?: throw NullPointerException("static method should not have thisObject")
    val thisObjectOrNull: Any? get() = receiver
    fun <T> thisObjectAs(): T = thisObject as T

    /** Mutable arguments passed to the original when it proceeds. */
    val args: Array<Any?>
        get() = argArray ?: chain.args.toTypedArray().also { argArray = it }
    fun arg(index: Int): Any? {
        val arguments = argArray
        return if (arguments != null) arguments[index] else chain.getArg(index)
    }
    fun <T> argAs(index: Int): T = arg(index) as T

    val isSkipped: Boolean get() = skipped

    /** Setting a result before the original runs skips it. */
    var result: Any?
        get() = outcome
        set(value) {
            if (!afterStage) skipped = true
            failure = null
            outcome = value
        }
    fun <T> resultAs(): T = result as T

    /** Setting a throwable before the original runs skips it; the caller receives it. */
    var throwable: Throwable?
        get() = failure
        set(value) {
            if (!afterStage) skipped = true
            outcome = null
            failure = value
        }
    val hasThrowable: Boolean get() = failure != null

    internal fun setOutcome(value: Any?, error: Throwable?) {
        outcome = value
        failure = error
    }

    internal fun proceedOriginal() {
        if (skipped) return
        try {
            val arguments = argArray
            val target = receiver
            outcome = when {
                arguments == null && target === originalReceiver -> chain.proceed()
                target == null -> chain.proceed(arguments ?: chain.args.toTypedArray())
                else -> chain.proceedWith(target, arguments ?: chain.args.toTypedArray())
            }
            failure = null
        } catch (t: Throwable) {
            outcome = null
            failure = t
        }
    }

    internal fun outcomeOrThrow(): Any? {
        failure?.let { throw it }
        return outcome
    }

    /** Runs the original exactly as the caller invoked it, discarding all callback changes. */
    internal fun fallbackToOriginal(): Any? {
        receiver = originalReceiver
        argArray = null
        skipped = false
        outcome = null
        failure = null
        proceedOriginal()
        return outcomeOrThrow()
    }
}

internal sealed class AroundStage {
    abstract fun run(param: HookParam, proceed: () -> Unit)

    class Replace(private val callback: (HookParam) -> Any?) : AroundStage() {
        override fun run(param: HookParam, proceed: () -> Unit) {
            param.afterStage = false
            param.skipped = true
            val value = try {
                callback(param)
            } catch (t: Throwable) {
                throw HookStageFailure("replace", t, HookStageFailure.Fallback.ORIGINAL)
            }
            param.setOutcome(value, null)
        }
    }

    class Intercept(private val callback: (XposedInterface.Chain) -> Any?) : AroundStage() {
        override fun run(param: HookParam, proceed: () -> Unit) {
            param.afterStage = false
            val chain = StageChain(param, proceed)
            try {
                val value = try {
                    callback(chain)
                } catch (t: Throwable) {
                    // A nested stage failure or the original's own exception propagates unchanged.
                    if (t is HookStageFailure || param.throwable === t) throw t
                    throw HookStageFailure("intercept", t,
                        if (chain.proceedCount == 0) HookStageFailure.Fallback.ORIGINAL
                        else HookStageFailure.Fallback.CURRENT)
                }
                if (chain.proceedCount == 0) param.skipped = true
                param.setOutcome(value, null)
            } finally {
                chain.closed = true
            }
        }
    }
}

/** The chain an `intercept` stage sees: proceeding continues with the remaining stages. */
private class StageChain(
    private val param: HookParam,
    private val proceedStages: () -> Unit,
) : XposedInterface.Chain {
    private val ownerThread = Thread.currentThread()
    var proceedCount = 0
    var closed = false

    private fun ensureUsable() {
        check(Thread.currentThread() === ownerThread) { "Chain cannot be shared across threads" }
        check(!closed) { "Chain cannot be reused after intercept returns" }
    }

    override fun getExecutable(): Executable { ensureUsable(); return param.executable }
    override fun getThisObject(): Any? { ensureUsable(); return param.receiver }
    override fun getArgs(): List<Any?> { ensureUsable(); return Collections.unmodifiableList(param.args.asList()) }
    override fun getArg(index: Int): Any? { ensureUsable(); return param.args[index] }
    override fun proceed(): Any? { ensureUsable(); return proceedWithArgs(param.args) }
    override fun proceed(args: Array<out Any?>): Any? { ensureUsable(); return proceedWithArgs(Array(args.size) { args[it] }) }
    override fun proceedWith(thisObject: Any): Any? = proceedWith(thisObject, param.args)
    override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? {
        ensureUsable()
        val previous = param.receiver
        param.receiver = thisObject
        try {
            return proceedWithArgs(Array(args.size) { args[it] })
        } finally {
            param.receiver = previous
        }
    }

    private fun proceedWithArgs(args: Array<Any?>): Any? {
        proceedCount += 1
        param.argArray = args
        proceedStages()
        return param.outcomeOrThrow()
    }
}

internal class HookStageFailure(
    val phase: String,
    cause: Throwable,
    val fallback: Fallback,
    val savedResult: Any? = null,
    val savedThrowable: Throwable? = null,
) : RuntimeException(cause) {
    enum class Fallback { ORIGINAL, SAVED, CURRENT }
}

internal class HookDispatcher(
    private val owner: String,
    private val target: Executable,
    private val befores: Array<(HookParam) -> Unit>,
    private val arounds: Array<AroundStage>,
    private val afters: Array<(HookParam) -> Unit>,
    private val counter: HookHits.Counter,
) : XposedInterface.Hooker {

    override fun intercept(chain: XposedInterface.Chain): Any? {
        counter.hits++
        val param = HookParam(chain)
        return try {
            dispatch(param)
        } catch (failure: HookStageFailure) {
            // A logging failure must not cancel the fallback already chosen for the host call.
            try {
                logFailure(failure)
            } catch (_: Throwable) {
            }
            when (failure.fallback) {
                HookStageFailure.Fallback.ORIGINAL -> param.fallbackToOriginal()
                HookStageFailure.Fallback.CURRENT -> param.outcomeOrThrow()
                HookStageFailure.Fallback.SAVED -> {
                    failure.savedThrowable?.let { throw it }
                    failure.savedResult
                }
            }
        }
    }

    /**
     * Callback bodies rely on this boundary instead of wrapping themselves in `runCatching`, so a
     * callback that fails on every call of a hot method must not flood the log ([FailureLogBudget]).
     */
    private fun logFailure(failure: HookStageFailure) {
        val count = ++counter.failures
        val cause = failure.cause ?: failure
        when (FailureLogBudget.entry(count)) {
            FailureLogBudget.Entry.FULL -> DebugLog.e(owner, "${failure.phase} hook failed for $target (failure $count)", cause)
            FailureLogBudget.Entry.SUMMARY -> DebugLog.e(owner, "${failure.phase} hook failed for $target " +
                "failures=$count last=${cause.javaClass.name}: ${cause.message}")
            FailureLogBudget.Entry.NONE -> Unit
        }
    }

    private fun dispatch(param: HookParam): Any? {
        for (callback in befores) {
            param.afterStage = false
            try {
                callback(param)
            } catch (t: Throwable) {
                throw HookStageFailure("before", t, HookStageFailure.Fallback.ORIGINAL)
            }
            if (param.skipped) break
        }
        if (!param.skipped) proceedAround(param, 0)
        for (callback in afters) {
            val savedResult = param.result
            val savedThrowable = param.throwable
            param.afterStage = true
            try {
                callback(param)
            } catch (t: Throwable) {
                throw HookStageFailure("after", t, HookStageFailure.Fallback.SAVED, savedResult, savedThrowable)
            } finally {
                param.afterStage = false
            }
        }
        return param.outcomeOrThrow()
    }

    private fun proceedAround(param: HookParam, index: Int) {
        if (index >= arounds.size) {
            param.proceedOriginal()
            return
        }
        arounds[index].run(param) { proceedAround(param, index + 1) }
    }
}

/**
 * Per-hook invocation counters for this generation, read only by the on-demand dump.
 *
 * Increments are deliberately unsynchronized: a lost update under contention costs nothing, and a
 * field increment is the whole per-call price.
 */
internal object HookHits {
    class Counter(val hooker: String, val id: String) {
        @JvmField var hits: Long = 0
        /** Callback failures handled by the dispatcher fallback; never reset with the hit window. */
        @JvmField var failures: Long = 0
    }

    private val counters = java.util.concurrent.ConcurrentHashMap<String, Counter>()
    @Volatile private var windowStart = android.os.SystemClock.uptimeMillis()

    /** A replaced or reinstalled hook keeps counting on the same counter. */
    fun counter(hooker: String, id: String): Counter =
        counters.computeIfAbsent("$hooker\u0000$id") { Counter(hooker, id) }

    /** Logs per-hooker totals and the hottest hooks of this process, then optionally restarts the window. */
    fun dump(process: String, reset: Boolean, top: Int = 25) {
        val all = counters.values.toList()
        val seconds = (android.os.SystemClock.uptimeMillis() - windowStart) / 1000.0
        val tag = "HookHits"
        DebugLog.i(tag, "process=$process window=${"%.0f".format(seconds)}s hooks=${all.size} " +
            "hit=${all.count { it.hits > 0 }} zero=${all.count { it.hits == 0L }}")
        all.groupBy { it.hooker }
            .map { (hooker, list) -> Triple(hooker, list, list.sumOf { it.hits }) }
            .sortedByDescending { it.third }
            .forEach { (hooker, list, total) ->
                DebugLog.i(tag, "process=$process hooker=$hooker hooks=${list.size} hits=$total " +
                    "zero=${list.count { it.hits == 0L }}")
            }
        all.filter { it.hits > 0 }.sortedByDescending { it.hits }.take(top).forEach {
            DebugLog.i(tag, "process=$process hot hooker=${it.hooker} id=${it.id} hits=${it.hits}")
        }
        all.filter { it.failures > 0 }.sortedByDescending { it.failures }.forEach {
            DebugLog.i(tag, "process=$process failing hooker=${it.hooker} id=${it.id} failures=${it.failures}")
        }
        if (reset) {
            all.forEach { it.hits = 0 }
            windowStart = android.os.SystemClock.uptimeMillis()
        }
    }
}
