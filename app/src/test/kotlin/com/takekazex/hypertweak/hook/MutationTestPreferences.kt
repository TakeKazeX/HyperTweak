package com.takekazex.hypertweak.hook

import android.content.SharedPreferences
import java.lang.reflect.Proxy

internal class MutationTestPreferences(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap()
        val persisted = initial.toMutableMap()
        var shadowBeforeAcknowledgement = false
        var commits = 0
        var accept = true
        var rejectNext = false
        var reads = 0
        var failReads = false
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor()
                "equals" -> false
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "TestStore"
                else -> {
                    reads++
                    check(!failReads) { "Remote must not be read" }
                    val key = args?.firstOrNull() as? String
                    when (method.name) {
                        "getAll" -> values.toMap()
                        "contains" -> values.containsKey(key)
                        else -> if (method.name.startsWith("get")) values[key] ?: args!![1]
                            else error(method.name)
                    }
                }
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            fun commit(): Boolean {
                commits++
                val accepted = accept && !rejectNext
                rejectNext = false
                fun applyTo(target: MutableMap<String, Any>) {
                    if (clear) target.clear()
                    pending.forEach { (key, value) -> if (value == null) target.remove(key) else target[key] = value }
                }
                if (accepted || shadowBeforeAcknowledgement) applyTo(values)
                if (!accepted) return false
                applyTo(persisted)
                return true
            }
            editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "commit" -> commit()
                    "apply" -> { commit(); null }
                    "clear" -> { clear = true; editor }
                    "remove" -> { pending[args!![0] as String] = null; editor }
                    else -> { check(method.name.startsWith("put")); pending[args!![0] as String] = args[1]; editor }
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
