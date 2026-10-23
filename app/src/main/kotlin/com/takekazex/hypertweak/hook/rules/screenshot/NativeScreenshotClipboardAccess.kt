package com.takekazex.hypertweak.hook.rules.screenshot

import android.content.Context
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Cache materialization validates the current host loader before any hook is installed. */
internal data class NativeScreenshotClipboardAccess(val gate: Method, val copy: Method, val callers: List<Method>, val commitWriter: Method, val saveWriter: Method, val notify: java.lang.reflect.Field, val success: java.lang.reflect.Field) {
    companion object {
        fun decode(values: Map<String, String>, loader: ClassLoader): NativeScreenshotClipboardAccess? {
            fun method(key: String) = values[key]?.let { MethodDescriptorCodec.method(it, loader) }
            val gate = method("gate") ?: return null
            val copy = method("copy") ?: return null
            val commitWriter = method("commitWriter") ?: return null
            val saveWriter = method("saveWriter") ?: return null
            val notify = values["notify"]?.let { MethodDescriptorCodec.field(it, loader) } ?: return null
            val success = values["success"]?.let { MethodDescriptorCodec.field(it, loader) } ?: return null
            val callers = values["callers"]?.split('\n')?.takeIf { it.size in 2..128 }
                ?.map { MethodDescriptorCodec.method(it, loader) ?: return null } ?: return null
            if (!Modifier.isStatic(gate.modifiers) || gate.returnType != Boolean::class.javaPrimitiveType ||
                gate.parameterTypes.toList() != listOf(Context::class.java) ||
                copy.returnType != Void.TYPE || copy.parameterTypes.toList() !=
                    listOf(Context::class.java, android.net.Uri::class.java, Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType) || copy !in callers || commitWriter !in callers ||
                saveWriter !in callers || saveWriter.returnType != Void.TYPE ||
                notify.declaringClass != saveWriter.declaringClass || success.declaringClass != notify.type ||
                success.type != java.lang.Boolean::class.java ||
                commitWriter.returnType != Boolean::class.javaPrimitiveType ||
                commitWriter.parameterTypes.take(3) != listOf(Context::class.java, android.graphics.Bitmap::class.java,
                    android.net.Uri::class.java)) return null
            return NativeScreenshotClipboardAccess(gate, copy, callers, commitWriter, saveWriter, notify, success)
        }
    }
}

/** The first gate read gets an explicit on/off decision; null leaves unrelated reads native. */
internal class ScreenshotClipboardRequests {
    private val requests = ThreadLocal<ArrayDeque<Boolean?>>()
    fun enter(enabled: Boolean) {
        val stack = requests.get() ?: ArrayDeque<Boolean?>().also(requests::set)
        stack.addLast(enabled)
    }
    fun consume(): Boolean? {
        val stack = requests.get() ?: return null
        if (stack.isEmpty()) return null
        val enabled = stack.last()
        stack[stack.lastIndex] = null
        return enabled
    }
    fun leave() {
        requests.get()?.let { stack ->
            if (stack.isNotEmpty()) stack.removeLast()
            if (stack.isEmpty()) requests.remove()
        }
    }
}
