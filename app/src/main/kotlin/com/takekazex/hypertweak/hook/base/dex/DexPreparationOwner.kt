package com.takekazex.hypertweak.hook.base.dex

/** Per-hooker lifecycle fence. Registration, retirement and publication share one generation. */
internal class DexPreparationOwner {
    private val lock = Any()
    private var generation = 0L
    @Volatile var isRetired = false
        private set
    private val tasks = HashMap<String, Pair<Long, DexPreparation>>()

    fun activate() = synchronized(lock) { isRetired = false }
    private fun current(token: Long) = !isRetired && generation == token

    fun <T : Any> prepare(
        name: String,
        create: ((Throwable) -> Unit) -> DexPreparation,
        failure: (Throwable) -> Unit,
        resolve: () -> T?,
        install: (T) -> Unit,
    ) {
        val (token, task) = synchronized(lock) {
            if (isRetired) return
            val token = generation
            token to (tasks[name]?.takeIf { it.first == token }?.second ?: create { error ->
                synchronized(lock) { if (current(token)) failure(error) }
            }.also { tasks[name] = token to it })
        }
        task.schedule(
            resolve = {
                val target = if (synchronized(lock) { current(token) }) resolve() else null
                if (synchronized(lock) { current(token) }) target else null
            },
            install = { target -> synchronized(lock) { if (current(token)) install(target) } },
        )
    }

    /** Never waits for resolution, including a task submitted concurrently with cancellation. */
    fun cancel() {
        synchronized(lock) {
            isRetired = true
            generation++
            tasks.clear()
        }
    }
}
