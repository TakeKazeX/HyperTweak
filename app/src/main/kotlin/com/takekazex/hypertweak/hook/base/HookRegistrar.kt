package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet

/**
 * One hooker's host hook handles: installation, hot-reload replacement, ownership and
 * deoptimization. [BaseHooker] exposes it through its hook DSL; nothing else holds a reference.
 */
internal class HookRegistrar(private val owner: BaseHooker) {
    private val handles = CopyOnWriteArraySet<XposedInterface.HookHandle>()
    // Identity of executables this hooker deoptimized; read only for the DEBUG diagnostic below.
    private val deoptimizedTargets: MutableSet<Executable> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    @Volatile private var selfDeoptimizationReported = false

    /** Old-generation handles this hooker may replace in place during an API-102 hot reload. */
    var replacementHandles: HotReloadHandleStore? = null

    /** Hook installations, plus installations handed to a later host boundary; read by the audit. */
    @Volatile var registrations = 0
        private set

    val hasHandles: Boolean get() = handles.isNotEmpty()
    val ownedHandles: List<XposedInterface.HookHandle> get() = handles.toList()

    fun noteDeferredInstallation() { registrations++ }

    /** Unhooks every owned handle; successful managed unhooks remove themselves. */
    fun unhookAll(): List<Throwable> = handles.mapNotNull { runCatching { it.unhook() }.exceptionOrNull() }

    fun clear() = handles.clear()

    fun hook(executable: Executable, hookId: String?, managed: Boolean, block: HookFactory.() -> Unit): XposedInterface.HookHandle {
        val id = hookId ?: defaultHookId(executable)
        val target = formatExecutable(executable)
        // Deoptimizing a hook target only interprets its original body; inlining is undone at the
        // caller. One line per hooker names the first such target, so intentional cases (a target
        // that itself inlines another hooked method) stay visible without flooding the log.
        if (executable is Method && executable in deoptimizedTargets && !selfDeoptimizationReported) {
            selfDeoptimizationReported = true
            DebugLog.d(owner.hookerName) { "self-deoptimized hook target $target (first of this hooker)" }
        }
        return try {
            val replacementMap = replacementHandles
            val oldHandle = replacementMap?.firstForTarget(id, executable)
            val handle = if (oldHandle != null) {
                replaceOldHandle(oldHandle, target, id, block).also {
                    replacementMap.markHandled(oldHandle)
                }
            } else installHook(executable, id, block)
            registerHandle(handle, target, managed)
        } catch (t: Throwable) {
            DebugLog.hookFailed(owner.hookerName, target, t)
            throw t
        }
    }

    /**
     * Adopts a handle that was replaced before the new hooker generation was attached.
     * Hot-reload coordinators use this to keep carried hooks visible to lifecycle cleanup and
     * diagnostics even when their normal install path intentionally skips an already-replaced id.
     */
    fun adopt(handle: XposedInterface.HookHandle) {
        own(handle)
        DebugLog.hookRegistered(owner.hookerName, "carried id=${handle.id}")
    }

    /**
     * Undo AOT inlining of [executable] so hooks on small or private methods actually fire.
     * The framework's Boolean result is preserved; a thrown failure is logged and reads as false.
     */
    fun deoptimize(executable: Executable): Boolean {
        deoptimizedTargets += executable
        return runCatching {
            owner.module.deoptimize(executable)
        }.onFailure { t ->
            DebugLog.w(owner.hookerName, "failed to deoptimize ${formatExecutable(executable)}", t)
        }.getOrDefault(false)
    }

    private fun registerHandle(
        handle: XposedInterface.HookHandle,
        target: String,
        managed: Boolean
    ): XposedInterface.HookHandle {
        DebugLog.hookRegistered(owner.hookerName, "$target id=${handle.id}")
        registrations++
        if (!managed) return handle
        return own(handle)
    }

    private fun installHook(
        target: Executable,
        hookId: String,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val factory = HookFactory(target).apply { id(hookId); block() }
        return owner.module.hook(target)
            .setPriority(factory.priority)
            .setExceptionMode(factory.exceptionMode)
            .setId(factory.hookId ?: hookId)
            .intercept(factory.buildHooker(owner.hookerName))
    }

    private fun replaceOldHandle(
        oldHandle: XposedInterface.HookHandle,
        target: String,
        hookId: String,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val factory = HookFactory(oldHandle.executable).apply { id(hookId); block() }
        val replacement = oldHandle.replaceHook(factory.buildHooker(owner.hookerName))
        DebugLog.hookRegistered(owner.hookerName, "$target id=$hookId replaced=true")
        return replacement
    }

    /** Re-registering an already owned replacement must not add a second wrapper. */
    fun own(handle: XposedInterface.HookHandle): XposedInterface.HookHandle {
        if (handle is ManagedHookHandle && handle.registrar === this) return handle
        return ManagedHookHandle(handle).also(handles::add)
    }

    private inner class ManagedHookHandle(private val original: XposedInterface.HookHandle) : XposedInterface.HookHandle {
        val registrar: HookRegistrar = this@HookRegistrar
        override fun getExecutable(): Executable = original.executable
        override fun getId(): String? = original.getId()
        override fun unhook() {
            original.unhook()
            handles.remove(this)
        }
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            val replaced = original.replaceHook(hooker)
            handles.remove(this)
            return own(replaced)
        }
    }

    private fun defaultHookId(executable: Executable): String {
        return "${owner.hookerName}:${formatExecutable(executable)}"
    }

    companion object {
        fun formatExecutable(executable: Executable): String {
            val owner = executable.declaringClass.name
            val name = when (executable) {
                is Constructor<*> -> "<init>"
                is Method -> executable.name
                else -> executable.name
            }
            val params = executable.parameterTypes.joinToString(",") { it.simpleName }
            return "$owner#$name($params)"
        }
    }
}
