package com.takekazex.hypertweak.hook.runtime.reload

import com.takekazex.hypertweak.hook.base.HostMembers
import com.takekazex.hypertweak.util.logging.DebugLog
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import java.lang.ref.WeakReference

/**
 * Frees the in-memory DEX of retired module generations.
 *
 * LSPosed loads every generation from memory (`[anon:dalvik-DEX data]`, about 12 MB for a release
 * build). Once a retired loader is unreachable, ART can finalize its `DexFile` before the ClassLinker
 * unregisters that dex; `closeDexFile` then refuses ("Failed to close dex file in finalizer") and the
 * native copy stays mapped for the life of the host process, even though the loader itself unloads.
 *
 * The retiring generation therefore hands its `DexFile` objects to its replacement, together with a
 * weak reference to its own loader. Both are boot classes; the weak reference does not keep the
 * loader alive. Holding the `DexFile`s prevents the early finalizer.
 *
 * A `DexFile` is closed only after its loader has been collected. `DexFile.close()` clears the
 * cookie that class definition reads even when ART cannot free the dex yet, so closing while the
 * retired generation can still load a class (any of its threads, timers or late callbacks) aborts
 * the host in `DexFile.defineClassNative`. A collected loader means no code of that generation can
 * run any more. Entries still open are carried into the next hot reload. State saved by a build that
 * carried no loader reference is left to ART, since its liveness cannot be known.
 */
internal object RetiredDexFiles {
    const val STATE_KEY = "HyperTweak.RetiredDexFiles"
    private const val TAG = "RetiredDexFiles"
    // Class unloading needs a full host GC; background processes may go minutes without one.
    private val retryDelaysMs = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000) +
        LongArray(11) { 300_000 }

    /** One retired generation: its loader, weakly, and the dex files that loader owned. */
    private class Generation(val loader: WeakReference<ClassLoader>, var files: List<DexFile>) {
        fun save(): Array<Any?> = arrayOf<Any?>(loader, *files.toTypedArray())
    }

    private val lock = Any()
    private var pending = emptyList<Generation>()
    private var worker: Thread? = null

    /** Retired generations still open, plus this generation's own, for the replacement to close. */
    fun saveForReplacement(): Array<Any?> {
        val own = runCatching { ownGeneration() }
            .onFailure { DebugLog.w(TAG, "own module dex files unavailable", it) }
            .getOrNull()
        val carried = synchronized(lock) { pending.filter { it.files.any(::isOpen) } }
        return (carried + listOfNotNull(own)).map(Generation::save).toTypedArray()
    }

    /** Starts closing the dex files the retired generations handed over. */
    fun adopt(state: Any?) {
        val generations = (state as? Array<*>).orEmpty().mapNotNull { entry ->
            val values = entry as? Array<*> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val loader = values.firstOrNull() as? WeakReference<ClassLoader> ?: return@mapNotNull null
            val files = values.drop(1).filterIsInstance<DexFile>().filter(::isOpen)
            if (files.isEmpty()) null else Generation(loader, files)
        }
        if (generations.isEmpty()) return
        synchronized(lock) {
            pending = generations
            worker?.interrupt()
            worker = Thread({ releaseLoop() }, "HyperTweak-RetiredDex").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
        DebugLog.d(TAG, "adopted retired generations=${generations.size} dex files=${generations.sumOf { it.files.size }}")
    }

    /** Hot-reload cleanup: this generation's worker must not outlive it. */
    fun stop() {
        val thread = synchronized(lock) { worker.also { worker = null } } ?: return
        thread.interrupt()
        thread.join(500)
    }

    private fun releaseLoop() {
        for (delay in retryDelaysMs) {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
            if (closeUnloaded() == 0) {
                DebugLog.i(TAG, "released retired module dex files")
                return
            }
        }
        DebugLog.d(TAG, "retired module generations still loaded=${synchronized(lock) { pending.size }}; carried to next reload")
    }

    /** Closes the dex files of generations whose loader is gone; returns the generations left. */
    private fun closeUnloaded(): Int = synchronized(lock) {
        pending.forEach { generation ->
            if (generation.loader.get() != null) return@forEach
            generation.files.forEach { file ->
                // No replacement API releases an already loaded DexFile; see the class comment.
                @Suppress("DEPRECATION")
                runCatching { file.close() }.onFailure { DebugLog.w(TAG, "retired dex close failed", it) }
            }
            generation.files = generation.files.filter(::isOpen)
        }
        pending = pending.filter { it.files.isNotEmpty() }
        pending.size
    }

    /** `close()` clears the internal cookie only when ART actually deleted the native dex. */
    private fun isOpen(file: DexFile): Boolean =
        runCatching { HostMembers.field(DexFile::class.java, "mInternalCookie")?.get(file) != null }
            .getOrDefault(true)

    private fun ownGeneration(): Generation? {
        val loader = RetiredDexFiles::class.java.classLoader as? BaseDexClassLoader ?: return null
        val pathList = HostMembers.field(BaseDexClassLoader::class.java, "pathList")?.get(loader) ?: return null
        val elements = HostMembers.read(pathList, "dexElements") as? Array<*> ?: return null
        val files = elements.mapNotNull { element -> element?.let { HostMembers.read(it, "dexFile") as? DexFile } }
        return if (files.isEmpty()) null else Generation(WeakReference(loader), files)
    }
}
