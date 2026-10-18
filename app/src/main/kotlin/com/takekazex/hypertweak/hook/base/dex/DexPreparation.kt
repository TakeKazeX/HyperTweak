package com.takekazex.hypertweak.hook.base.dex

/** Resolution never holds the lifecycle lock. Only publication is serialized with retirement. */
internal class DexPreparation(
    private val background: (() -> Unit) -> Unit,
    private val publish: (() -> Unit) -> Unit,
    private val afterStartup: (() -> Unit) -> Unit = { it() },
    private val failure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var pending = false

    fun <T : Any> schedule(resolve: () -> T?, install: (T) -> Unit) {
        val token = synchronized(lock) {
            if (pending) return
            pending = true
            generation
        }
        fun current() = synchronized(lock) { generation == token }
        fun finish() = synchronized(lock) { if (generation == token) pending = false }
        fun failed(error: Throwable) {
            finish()
            if (current()) failure(error)
        }
        try {
            afterStartup {
                if (!current()) return@afterStartup
                try {
                    background {
                        if (!current()) return@background
                        try {
                            val target = resolve()
                            if (target == null || !current()) finish()
                            else publish {
                                try {
                                    synchronized(lock) { if (generation == token) install(target) }
                                } catch (error: Throwable) {
                                    if (current()) failure(error)
                                } finally { finish() }
                            }
                        } catch (error: Throwable) {
                            finish()
                            if (current()) failure(error)
                        }
                    }
                } catch (error: Throwable) {
                    failed(error)
                }
            }
        } catch (error: Throwable) {
            failed(error)
        }
    }

    /** In-flight native work may finish, but can never publish into a retired owner. */
    fun cancel() = synchronized(lock) {
        generation++
        pending = false
    }
}
