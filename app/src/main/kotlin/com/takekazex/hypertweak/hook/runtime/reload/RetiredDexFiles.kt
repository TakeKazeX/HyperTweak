package com.takekazex.hypertweak.hook.runtime.reload

import com.takekazex.hypertweak.hook.base.HostMembers
import com.takekazex.hypertweak.util.logging.DebugLog
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
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
 *
 * A cleared loader reference is not yet a deleted loader. ART clears weak references while a GC
 * traces, but unregisters the loader's classes later, in `ClassLinker::CleanupClassLoaders` during
 * that GC's reclaim phase. In between, the dex cache counts as unregistered, so `close()` really frees
 * the dex, and the class-hierarchy cleanup then reads interface class definitions from the freed
 * mapping: SIGSEGV in `ResetSingleImplementationInHierarchy` on HeapTaskDaemon (Security Center,
 * 2026-11-27, one second after the first close). A close therefore waits for [GC_FENCES] sentinel
 * objects, each allocated after the previous was collected: a sentinel allocated after the
 * observation only dies in a later GC, and ART starts a GC only after the previous one finished.
 *
 * The loader is observed through a [PhantomReference], not a weak one. ART keeps a loader's classes
 * registered until its JNI weak root is swept, and that root, like a phantom referent, survives while
 * the loader is still finalizer-reachable: any pending finalizer of that generation keeps it. A weak
 * reference clears first, so the fence could pass while the dex was still registered; every close
 * was then refused and retried, until one retry landed between the root sweep and
 * `CleanupClassLoaders` of the GC that finally unloaded it (system_server, 2026-11-27, after
 * `closed ... still open=1` repeated for an hour). A close refused after the fence means the dex is
 * still in use for a reason this cannot see, so that generation is held, never closed again, and
 * carried for the life of the process. Its native copy stays mapped, as it did before this cleanup
 * existed. A generation saved by a build that tracked a weak reference is held the same way.
 */
internal object RetiredDexFiles {
    const val STATE_KEY = "HyperTweak.RetiredDexFiles"
    private const val TAG = "RetiredDexFiles"
    private const val GC_FENCES = 2
    /** Saved in place of the loader reference for a generation whose dex is never closed. */
    private const val HELD = "held"
    // Phantom references need a queue to be tracked; nothing polls it, it only has to stay reachable.
    private val phantomQueue = ReferenceQueue<ClassLoader>()
    private const val FENCE_POLL_MS = 5_000L
    private const val FENCE_POLLS = 120
    // Class unloading needs a full host GC; background processes may go minutes without one.
    private val retryDelaysMs = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000) +
        LongArray(11) { 300_000 }

    /**
     * One retired generation: its loader, phantom-reachable, and the dex files that loader owned.
     * A null [loader] marks a held generation: its files are kept open and never closed.
     */
    private class Generation(val loader: PhantomReference<ClassLoader>?, var files: List<DexFile>) {
        private var fences = 0
        private var fencePolls = 0
        private var sentinel: WeakReference<Any>? = null
        val held: Boolean get() = loader == null

        /**
         * The loader is gone and the sentinel fence has started, so progress needs only short polls.
         * Each generation has its own budget: one shared count let a batch of 20 generations use it
         * up, and the next ones waited minutes per fence step.
         */
        val fencing: Boolean get() = !held && sentinel != null && fencePolls < FENCE_POLLS

        fun save(): Array<Any?> = arrayOf<Any?>(loader ?: HELD, *files.toTypedArray())

        /** True once [GC_FENCES] whole collections have finished since the loader was seen gone. */
        fun unloadSettled(): Boolean {
            val ref = loader ?: return false
            if (!ref.refersTo(null)) return false
            val current = sentinel
            if (current != null && current.get() != null) { fencePolls++; return false }
            if (current != null) fences++
            if (fences >= GC_FENCES) return true
            sentinel = WeakReference(Any())
            return false
        }
    }

    private val lock = Any()
    private var pending = emptyList<Generation>()
    private var worker: Thread? = null
    /**
     * Set when the snapshot for the replacement is taken. Retirement stops the worker only in its
     * final steps, so without this the worker could still close a generation, or turn a refused one
     * into a held one, after the snapshot was handed over. The replacement would then see that
     * generation as closable and retry the refused close.
     */
    private var frozen = false

    /**
     * Retired generations still open, plus this generation's own, for the replacement to close.
     * Freezes this generation's worker first; [resume] undoes that when the hot reload is rejected.
     */
    fun saveForReplacement(): Array<Any?> {
        val own = runCatching { ownGeneration() }
            .onFailure { DebugLog.w(TAG, "own module dex files unavailable", it) }
            .getOrNull()
        val carried = synchronized(lock) {
            frozen = true
            pending.filter { it.files.any(::isOpen) }
        }
        return (carried + listOfNotNull(own)).map(Generation::save).toTypedArray()
    }

    /** A rejected hot reload keeps this generation; its worker may close again. */
    fun resume() = synchronized(lock) { frozen = false }

    /** Starts closing the dex files the retired generations handed over. */
    fun adopt(state: Any?) {
        val generations = (state as? Array<*>).orEmpty().mapNotNull { entry ->
            val values = entry as? Array<*> ?: return@mapNotNull null
            // A weak loader reference (older builds) cannot show when closing is safe: hold it.
            @Suppress("UNCHECKED_CAST")
            val loader = when (val first = values.firstOrNull()) {
                is PhantomReference<*> -> first as PhantomReference<ClassLoader>
                HELD, is WeakReference<*> -> null
                else -> return@mapNotNull null
            }
            val files = values.drop(1).filterIsInstance<DexFile>().filter(::isOpen)
            if (files.isEmpty()) null else Generation(loader, files)
        }
        if (generations.isEmpty()) return
        synchronized(lock) {
            pending = generations
            if (generations.all(Generation::held)) {
                DebugLog.d(TAG, "holding retired generations=${generations.size}; none can be closed safely")
                return
            }
            worker?.interrupt()
            worker = Thread({ releaseLoop() }, "HyperTweak-RetiredDex").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
        DebugLog.d(TAG, "adopted retired generations=${generations.size} held=${generations.count(Generation::held)} " +
            "dex files=${generations.sumOf { it.files.size }}")
    }

    /** Hot-reload cleanup: this generation's worker must not outlive it. */
    fun stop() {
        val thread = synchronized(lock) { worker.also { worker = null } } ?: return
        thread.interrupt()
        thread.join(500)
    }

    private fun releaseLoop() {
        var index = 0
        while (index < retryDelaysMs.size) {
            // A started fence waits only for GCs, not for the loader; don't wait minutes between them.
            val fencing = synchronized(lock) { pending.any { it.fencing } }
            val delay = if (fencing) FENCE_POLL_MS else retryDelaysMs[index++]
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
            if (closeUnloaded() == 0) {
                val held = synchronized(lock) { pending.size }
                DebugLog.i(TAG, if (held == 0) "released retired module dex files"
                    else "no closable retired generation left; held for the process lifetime=$held")
                return
            }
        }
        DebugLog.d(TAG, "retired module generations still loaded=${synchronized(lock) { pending.count { !it.held } }}; carried to next reload")
    }

    /** Closes the dex files of generations whose loader is gone; returns the closable generations left. */
    private fun closeUnloaded(): Int = synchronized(lock) {
        // The snapshot is already handed over; any state change now would diverge from it.
        if (frozen) return@synchronized pending.count { !it.held }
        pending = pending.map { generation ->
            if (!generation.unloadSettled()) return@map generation
            generation.files.forEach { file ->
                // No replacement API releases an already loaded DexFile; see the class comment.
                @Suppress("DEPRECATION")
                runCatching { file.close() }.onFailure { DebugLog.w(TAG, "retired dex close failed", it) }
            }
            val open = generation.files.filter(::isOpen)
            if (open.isEmpty()) {
                DebugLog.d(TAG, "closed retired generation dex files=${generation.files.size}")
                generation.files = open
                generation
            } else {
                // Retrying is what raced ART's unloading; keep these mapped for the process lifetime.
                DebugLog.i(TAG, "retired generation dex still in use after the GC fence; holding open=${open.size}")
                Generation(null, open)
            }
        }.filter { it.files.isNotEmpty() }
        pending.count { !it.held }
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
        return if (files.isEmpty()) null else Generation(PhantomReference(loader, phantomQueue), files)
    }
}
