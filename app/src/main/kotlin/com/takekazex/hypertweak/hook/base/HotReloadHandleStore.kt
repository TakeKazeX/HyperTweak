package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface
import java.util.ArrayDeque
import java.util.IdentityHashMap

class HotReloadHandleStore(handles: Collection<XposedInterface.HookHandle>) {
    private val remaining = IdentityHashMap<XposedInterface.HookHandle, Unit>().apply {
        handles.forEach { put(it, Unit) }
    }
    private val handlesById = mutableMapOf<String, ArrayDeque<XposedInterface.HookHandle>>()

    init {
        handles.forEach { handle ->
            val id = handle.id ?: return@forEach
            handlesById.getOrPut(id) { ArrayDeque() }.add(handle)
        }
    }

    val totalCount: Int = handles.size

    val idCount: Int
        get() = handlesById.size

    val unnamedCount: Int = handles.count { it.id == null }

    val duplicateIdCount: Int = handles.size - unnamedCount - handlesById.size

    val remainingCount: Int
        get() = remaining.size

    val ids: Set<String>
        get() = handlesById.keys.toSet()

    fun firstForId(id: String): XposedInterface.HookHandle? {
        return handlesById[id]?.peekFirst()
    }

    fun markHandled(handle: XposedInterface.HookHandle) {
        remaining.remove(handle)
        val id = handle.id ?: return
        val queue = handlesById[id] ?: return
        queue.remove(handle)
        if (queue.isEmpty()) {
            handlesById.remove(id)
        }
    }

    fun remainingHandles(): List<XposedInterface.HookHandle> {
        return remaining.keys.toList()
    }
}
