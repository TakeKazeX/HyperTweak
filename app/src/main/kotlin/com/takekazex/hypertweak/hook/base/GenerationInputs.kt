package com.takekazex.hypertweak.hook.base

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Host and system inputs a hooker owns: registrations, posted work, workers and scopes.
 *
 * Each of these keeps the module generation reachable from the host (a registered binder or
 * callback, a pending `Message.callback`, a live thread), so retirement must release every one.
 * [BaseHooker.prepareForHotReload] releases them all before `onPrepareHotReload`: inputs stop first,
 * then the feature restores the host. Retirement is not terminal for a hooker instance (settings
 * reloads and plugin sessions retire and reinstall the same object), so reusable owners stay usable.
 *
 * Between retirement and [activate], new registrations are refused. During a hot reload the old
 * generation's hooks keep firing until the replacement swaps the handles, and a lazy registration
 * made from one of those callbacks would otherwise outlive the generation and pin its loader.
 */
internal class GenerationInputs {
    /** Something cleared on retirement that remains usable afterwards. */
    fun interface Reusable { fun release() }

    private val registrations = CopyOnWriteArraySet<Registration>()
    private var retired = false
    private val reusables = CopyOnWriteArrayList<Reusable>()

    /** One host registration. Closing it unregisters at most once; it never registers again. */
    inner class Registration : AutoCloseable {
        private var release: (() -> Unit)? = null
        private var closed = false

        val isClosed: Boolean @Synchronized get() = closed

        /**
         * Registers unless already closed. Holding the monitor makes a concurrent [close] either
         * prevent the registration or observe it and unregister.
         */
        @Synchronized
        fun <T> attach(register: () -> T, unregister: (T) -> Unit): Boolean {
            if (closed) return false
            check(release == null) { "registration already attached" }
            val handle = register()
            release = { unregister(handle) }
            return true
        }

        override fun close() {
            val action = synchronized(this) {
                if (closed) return
                closed = true
                release.also { release = null }
            }
            registrations.remove(this)
            action?.invoke()
        }
    }

    /** A registration; already closed (so it never registers) while this owner is retired. */
    fun open(): Registration = synchronized(this) {
        Registration().also { if (retired) it.close() else registrations.add(it) }
    }

    /** True between retirement and the next (re)installation. */
    val isRetired: Boolean get() = synchronized(this) { retired }

    /** Accepts registrations again; the owner is being (re)installed. */
    fun activate() = synchronized(this) { retired = false }

    fun <T : Reusable> reusable(owner: T): T = owner.also(reusables::add)

    /**
     * Registers now and unregisters on retirement or [AutoCloseable.close], at most once. After
     * retirement and before reinstall nothing is registered.
     */
    fun registration(register: () -> Unit, unregister: () -> Unit): Registration {
        val registration = open()
        try { registration.attach(register) { unregister() } } catch (t: Throwable) { registration.close(); throw t }
        return registration
    }

    fun observer(
        resolver: ContentResolver, uri: Uri, descendants: Boolean, handler: Handler?,
        onChange: (selfChange: Boolean, uri: Uri?) -> Unit,
    ): AutoCloseable {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, changed: Uri?) = onChange(selfChange, changed)
        }
        return registration({ resolver.registerContentObserver(uri, descendants, observer) }) {
            resolver.unregisterContentObserver(observer)
        }
    }

    /** See [BaseHooker.generationReceiver]; [logTag] names the owning hooker in failures. */
    fun receiver(
        logTag: String, context: Context, filter: IntentFilter, flags: Int, permission: String?, handler: Handler?,
        onReceive: (Context, Intent) -> Unit,
    ): AutoCloseable {
        val registration = open()
        val receiver = object : ModuleBroadcastReceiver() {
            override fun receive(context: Context, intent: Intent) {
                if (!registration.isClosed) onReceive(context, intent)
            }
        }
        HostApplicationContext.whenPublished(context) { application ->
            runCatching {
                registration.attach({ ContextCompat.registerReceiver(application, receiver, filter, permission, handler, flags) }) {
                    try { application.unregisterReceiver(receiver) }
                    catch (e: IllegalArgumentException) { if (e.message?.contains("not registered") != true) throw e }
                }
            }.onFailure { DebugLog.e(logTag, "receiver registration failed actions=${filter.actionsIterator().asSequence().toList()}", it) }
        }
        return registration
    }

    /** Releases everything; every input is attempted even when one fails. */
    fun releaseAll() {
        // Set before the snapshot below, so a concurrent open() is either refused or closed here.
        synchronized(this) { retired = true }
        val failures = mutableListOf<Throwable>()
        reusables.forEach { runCatching { it.release() }.onFailure(failures::add) }
        registrations.toList().forEach { runCatching { it.close() }.onFailure(failures::add) }
        if (failures.isNotEmpty()) throw failures.first().apply { failures.drop(1).forEach(::addSuppressed) }
    }
}

/** A single-thread worker recreated on first use after each retirement. */
internal class GenerationWorker(private val name: String, private val idleSeconds: Long = 30) : GenerationInputs.Reusable {
    private var current: RetiringWorker? = null

    /** False when the task was rejected (queue full); callers keep their native fallback. */
    fun execute(action: () -> Unit): Boolean =
        synchronized(this) { current ?: RetiringWorker(name, idleSeconds).also { current = it } }.execute(action)

    override fun release() {
        synchronized(this) { current.also { current = null } }?.close()
    }
}

/** A scope whose job is cancelled on retirement; launches afterwards use a fresh job. */
internal class GenerationScope(private val context: CoroutineContext) : CoroutineScope, GenerationInputs.Reusable {
    @Volatile private var job: Job = SupervisorJob()

    override val coroutineContext: CoroutineContext get() = context + job

    override fun release() {
        val retired = job
        job = SupervisorJob()
        retired.cancel()
    }
}
