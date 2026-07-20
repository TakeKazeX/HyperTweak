package com.takekazex.hypertweak.hook.base

internal object CollectionOverrides {
    fun stringSet(source: Collection<*>?, vararg additions: String): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        source?.forEach { value -> if (value is String) result += value }
        result.addAll(additions)
        return result
    }

    fun stringList(source: Collection<*>?, vararg additions: String): List<String> {
        return stringSet(source, *additions).toList()
    }
}
