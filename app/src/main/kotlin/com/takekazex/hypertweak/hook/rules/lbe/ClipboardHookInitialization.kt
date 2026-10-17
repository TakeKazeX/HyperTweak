package com.takekazex.hypertweak.hook.rules.lbe

/** Keeps slow resolution outside startup and prevents retired work from publishing hooks. */
internal class ClipboardHookInitialization(
    private val afterStartup: (() -> Unit) -> Unit,
    private val background: (() -> Unit) -> Unit,
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
        try {
            afterStartup {
                if (current()) {
                    try {
                        background {
                            try {
                                if (current()) {
                                    val target = resolve()
                                    synchronized(lock) {
                                        if (generation == token && target != null) install(target)
                                    }
                                }
                            } finally {
                                finish()
                            }
                        }
                    } catch (error: Throwable) {
                        finish()
                        throw error
                    }
                }
            }
        } catch (error: Throwable) {
            finish()
            throw error
        }
    }

    /** Does not wait for native scanning; only the short hook publication is serialized. */
    fun cancel() = synchronized(lock) {
        generation++
        pending = false
    }
}
