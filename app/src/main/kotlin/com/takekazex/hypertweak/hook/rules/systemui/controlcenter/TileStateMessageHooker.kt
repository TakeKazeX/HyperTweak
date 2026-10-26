package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import com.takekazex.hypertweak.settings.contract.TileStateMessageDuration

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reschedules only the native message slot's runnable, preserving Folme and slot recycling. */
class TileStateMessageHooker : DynamicHooker() {
    private val main = Handler(Looper.getMainLooper())
    private val completed = WeakHashMap<Any, Long>()
    private val listenerOwners = WeakHashMap<Any, java.lang.ref.WeakReference<Any>>()
    private val hookedListeners = HashSet<Class<*>>()
    private lateinit var ownerField: Field
    private lateinit var handlerField: Field
    private lateinit var runnableField: Field
    private lateinit var configField: Field
    private lateinit var listenersField: Field
    @Volatile private var retiring = false

    override fun onHook() {
        val controller = "miui.systemui.controlcenter.panel.main.header.MessageHeaderController".toClassOrNull() ?: return
        // Select the slot by its owner/runnable/config relationship, not an anonymous class suffix.
        val slot = controller.declaredFields.map { it.type }.distinct().single { type ->
            type.enclosingClass == controller && type.declaredFields.any { it.type == Runnable::class.java }
        }
        ownerField = slot.declaredFields.single { it.type == controller }.apply { isAccessible = true }
        handlerField = controller.declaredFields.single { it.type == Handler::class.java }.apply { isAccessible = true }
        runnableField = slot.declaredFields.single { it.type == Runnable::class.java }.apply { isAccessible = true }
        configField = slot.getDeclaredField("showConfig").apply { isAccessible = true }
        listenersField = configField.type.getField("listeners")
        val show = slot.getDeclaredMethod("show")
        val hide = slot.getDeclaredMethod("hide")
        check(show.returnType == Void.TYPE && hide.returnType == Void.TYPE)
        fun bind(message: Any) {
            val config = configField.get(message) ?: return
            val listener = (listenersField.get(config) as? Collection<*>)?.singleOrNull() ?: return
            val type = listener.javaClass
            val complete = type.getDeclaredMethod("onComplete", Any::class.java)
            check(complete.returnType == Void.TYPE &&
                type.declaredFields.any { it.type == slot } &&
                type.declaredFields.any { it.type == controller })
            listenerOwners[listener] = java.lang.ref.WeakReference(message)
            if (hookedListeners.add(type)) {
                deoptimize(complete)
                complete.hook {
                    after { param -> guarded("show completion") {
                        val msg = listenerOwners[param.thisObject]?.get() ?: return@guarded
                        if (retiring) return@guarded
                        completed[msg] = SystemClock.uptimeMillis()
                        reschedule(msg, duration())
                    } }
                }
            }
        }
        // This boundary also binds slots that predate the module's first installation/update.
        deoptimize(show)
        show.hook { before { param -> guarded("bind slot") {
            completed.remove(param.thisObject)
            bind(param.thisObject)
        } } }
        deoptimize(hide)
        hide.hook { before { param -> guarded("hide slot") { completed.remove(param.thisObject) } } }
        slot.declaredConstructors.forEach { ctor -> ctor.hook { after { param ->
            guarded("new slot") { bind(param.thisObject) }
        } } }
        bindSlot = ::bind
        DebugLog.hookRegistered(TAG, "native showConfig listener and message slots")
    }

    private var bindSlot: ((Any) -> Unit)? = null
    private fun duration() = Preferences.getInt(SystemUiSettingsKeys.KEY_CC_TILE_MESSAGE_DURATION_MS, TileStateMessageDuration.DEFAULT_MS)
    private fun reschedule(message: Any, duration: Int) {
        val start = completed[message] ?: return
        val handler = handlerField.get(ownerField.get(message)) as? Handler ?: return
        val runnable = runnableField.get(message) as? Runnable ?: return
        // Never resurrect a message whose native hide/cancel path has already removed its timer.
        if (!handler.hasCallbacks(runnable)) { completed.remove(message); return }
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, TileStateMessageDuration.remaining(duration, start, SystemClock.uptimeMillis()))
    }

    private val refresh = Runnable {
        if (!retiring) guarded("live duration") { completed.keys.toList().forEach { reschedule(it, duration()) } }
    }
    fun refreshSettings() { main.removeCallbacks(refresh); if (!retiring) main.post(refresh) }

    fun snapshotSlots(): List<Any> = synchronized(completed) {
        listenerOwners.values.mapNotNull { it.get() }.distinct().map { listOf(it, completed[it]) }
    }
    fun restoreSlots(state: List<*>) {
        main.post {
            if (!retiring) state.forEach { item -> guarded("restore timer") {
                val entry = item as? List<*> ?: return@guarded
                val msg = entry.firstOrNull() ?: return@guarded
                bindSlot?.invoke(msg)
                val start = entry.getOrNull(1) as? Long ?: return@guarded
                completed[msg] = start
                reschedule(msg, duration())
            } }
        }
    }

    override fun onPrepareHotReload() {
        retiring = true
        main.removeCallbacks(refresh)
        val done = CountDownLatch(1)
        val cleanup = Runnable { try { synchronized(completed) {
            completed.keys.toList().forEach { guarded("restore native timer") {
                reschedule(it, TileStateMessageDuration.DEFAULT_MS)
            } }
            completed.clear()
            listenerOwners.clear()
        } } finally { done.countDown() } }
        if (Looper.myLooper() == main.looper) cleanup.run() else {
            main.post(cleanup)
            check(done.await(5, TimeUnit.SECONDS)) { "message timer cleanup timed out" }
        }
    }

    private fun guarded(operation: String, action: () -> Unit) = HookFailurePolicy.open(TAG, operation, Unit) {
        synchronized(completed) { action() }
    }
    private companion object { const val TAG = "TileStateMessage" }
}
