package com.takekazex.hypertweak.util

import android.content.Context
import android.content.res.Resources
import java.util.concurrent.ConcurrentHashMap

object ResourceLookup {
    private data class Key(
        val packageName: String,
        val type: String,
        val name: String
    )

    private val idCache = ConcurrentHashMap<Key, Int>()

    fun identifier(
        resources: Resources,
        name: String,
        type: String,
        packageName: String
    ): Int {
        val key = Key(packageName, type, name)
        return idCache.getOrPut(key) {
            resources.getIdentifier(name, type, packageName)
        }
    }

    fun identifier(
        context: Context,
        name: String,
        type: String,
        packageName: String = context.packageName
    ): Int = identifier(context.resources, name, type, packageName)

    fun packageContext(context: Context, packageName: String): Context? {
        return runCatching {
            context.createPackageContext(packageName, 0)
        }.getOrNull()
    }

    fun packageResources(context: Context, packageName: String): Resources? {
        return packageContext(context, packageName)?.resources
    }
}
