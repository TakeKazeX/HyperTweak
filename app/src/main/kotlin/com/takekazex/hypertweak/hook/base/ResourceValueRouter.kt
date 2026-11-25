package com.takekazex.hypertweak.hook.base

import android.content.res.AssetManager
import android.content.res.Resources
import android.util.SparseArray
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.WeakHashMap

/**
 * One framework hook per `Resources` reader per SystemUI generation, shared by every feature that
 * rewrites a resource value. Features subscribe with the resource entry names they own while
 * their switch is on; a reader with no subscriber was never hooked.
 *
 * Every SystemUI resource read crosses these methods, so the dispatch resolves an ID to its
 * matching rules once per asset table and caches the answer (an empty answer included) in a
 * `SparseArray`; later reads cost one lookup and no entry-name query.
 */
internal object ResourceValueRouter : StaticHooker() {
    enum class Channel(val target: String) {
        COLOR("Resources#getColor(int,Theme)"),
        DIMENSION_PIXEL_SIZE("Resources#getDimensionPixelSize(int)"),
        INT_ARRAY("Resources#getIntArray(int)"),
    }

    /**
     * [packages] null accepts any package; otherwise the ID's package must be listed, so a name
     * reused by another table is never rewritten. [transform] receives the host's result and
     * returns the replacement, or null to keep it.
     */
    private class Rule(
        val owner: String,
        val channel: Channel,
        val names: Set<String>,
        val packages: Set<String>?,
        val transform: (Resources, Int, Any?) -> Any?,
    )

    private val NO_RULES = emptyArray<Rule>()

    @Volatile private var rules = NO_RULES
    // Plugin and SystemUI resource tables reuse numeric IDs, so matches are cached per table and
    // dropped whenever the rule set changes.
    private val matches = generationResource(WeakHashMap<AssetManager, SparseArray<Array<Rule>>>()) {
        synchronized(it) { it.clear() }
    }
    private val installations = generationResource(Channel.entries.associateWith { channel ->
        HookInstallation { DebugLog.hookFailed(hookerName, channel.target, it) }
    }) { it.values.forEach(HookInstallation::reset) }

    override val expectsHooks: Boolean get() = false

    /** Installs [channel]'s reader on first use; the returned subscription is owned by [owner]. */
    fun subscribe(
        owner: BaseHooker,
        channel: Channel,
        names: Set<String>,
        packages: Set<String>? = null,
        transform: (Resources, Int, Any?) -> Any?,
    ): AutoCloseable {
        // A retiring generation's late settings callback must not hook framework readers again.
        if (generationRetired || owner.generationRetired) return AutoCloseable {}
        val rule = Rule(owner.hookerName, channel, names, packages, transform)
        publish { it + rule }
        install(channel)
        installations.getValue(channel).let { installation ->
            if (!installation.isInstalled) DebugLog.hookFailed(owner.hookerName, "${channel.target} subscription",
                installation.lastFailure ?: IllegalStateException("resource router channel is not ready"))
        }
        val subscription = AutoCloseable { publish { current -> current.filterNot { it === rule }.toTypedArray() } }
        owner.ownSubscription(subscription)
        return subscription
    }

    @Synchronized private fun publish(change: (Array<Rule>) -> Array<Rule>) {
        rules = change(rules)
        synchronized(matches) { matches.clear() }
    }

    private fun install(channel: Channel) = installations.getValue(channel).install {
        val type = Resources::class.java
        val int = Int::class.javaPrimitiveType
        val method = when (channel) {
            Channel.COLOR -> HostAccess.requirePublicMethod(type, "getColor", int, Resources.Theme::class.java, accessible = false)
            Channel.DIMENSION_PIXEL_SIZE -> HostAccess.requirePublicMethod(type, "getDimensionPixelSize", int, accessible = false)
            Channel.INT_ARRAY -> HostAccess.requirePublicMethod(type, "getIntArray", int, accessible = false)
        }
        method.hook("HyperTweak.ResourceValueRouter.${channel.name}") {
            after { param ->
                if (rules.isEmpty()) return@after
                val resources = param.thisObject as? Resources ?: return@after
                val id = param.args[0] as? Int ?: return@after
                if (id == 0 || param.throwable != null) return@after
                for (rule in rulesFor(resources, id)) {
                    if (rule.channel != channel) continue
                    try {
                        rule.transform(resources, id, param.result)?.let { param.result = it }
                    } catch (error: Throwable) {
                        DebugLog.w("ResourceValueRouter", "${rule.owner} ${channel.target} rule failed", error)
                    }
                }
            }
        }
    }

    private fun rulesFor(resources: Resources, id: Int): Array<Rule> {
        val assets = resources.assets
        synchronized(matches) { matches[assets]?.get(id)?.let { return it } }
        val current = rules
        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull()
        var packageName: String? = null
        val matched = if (name == null) NO_RULES else current.filter { rule ->
            name in rule.names && (rule.packages == null || (packageName
                ?: runCatching { resources.getResourcePackageName(id) }.getOrNull().also { packageName = it }) in rule.packages)
        }.toTypedArray().ifEmpty { NO_RULES }
        synchronized(matches) {
            // A rule set published while this ID was classified must not cache a stale answer.
            if (rules === current) matches.getOrPut(assets) { SparseArray() }.put(id, matched)
        }
        return matched
    }

    override fun onPrepareHotReload() = publish { NO_RULES }
}
