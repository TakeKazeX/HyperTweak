package com.takekazex.hypertweak.util.platform

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import java.util.WeakHashMap

/**
 * Memoized [Resources.getIdentifier]. A name lookup walks the package string pools and costs tens of
 * microseconds, which is significant on layout, bind and frame paths; identifiers never change for
 * the lifetime of one [AssetManager]. Results, including misses, are kept per AssetManager so a
 * plugin's or another package's Resources cannot answer for a different asset set; a rebuilt
 * AssetManager (overlay or loader change) starts empty and its old entries are collected with it.
 */
object ResourceLookup {
    private data class Key(
        val packageName: String,
        val type: String,
        val name: String
    )

    private val idCache = WeakHashMap<AssetManager, HashMap<Key, Int>>()

    fun identifier(
        resources: Resources,
        name: String,
        type: String,
        packageName: String
    ): Int {
        val assets = resources.assets
        val key = Key(packageName, type, name)
        synchronized(idCache) { idCache[assets]?.get(key) }?.let { return it }
        val id = resources.getIdentifier(name, type, packageName)
        synchronized(idCache) { idCache.getOrPut(assets) { HashMap() }[key] = id }
        return id
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
