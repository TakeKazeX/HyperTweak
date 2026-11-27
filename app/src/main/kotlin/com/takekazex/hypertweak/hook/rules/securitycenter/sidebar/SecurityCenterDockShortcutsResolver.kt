package com.takekazex.hypertweak.hook.rules.securitycenter.sidebar

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * Sidebar long-press menu contract: the window-manager method that shows the native
 * full-screen/split card, the card field it positions, its dismiss path and the app model
 * (uid + package) carried by the long-pressed item.
 */
internal class SecurityCenterDockShortcutsResolver(private val index: DexOperandIndex) {
    private val view = "Landroid/view/View;"
    private val card = "Landroidx/cardview/widget/CardView;"
    private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
    private fun MethodReference.key() = DexOperandIndex.descriptor(this)
    private fun FieldReference.key() = "$definingClass->$name:$type"

    private fun extendsCard(type: String): Boolean {
        var current: String? = type
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            if (current == card) return true
            current = index.classDefinition(current)?.superclass
        }
        return false
    }

    private fun implements(type: String, target: String): Boolean {
        val pending = ArrayDeque(listOf(type))
        val seen = HashSet<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == target) return true
            if (!seen.add(current)) continue
            val definition = index.classDefinition(current) ?: continue
            definition.superclass?.let(pending::add)
            pending.addAll(definition.interfaces)
        }
        return false
    }

    /** The single no-argument model getter used by [method] and the one [type] field it reads from that model. */
    private fun modelRead(method: Method, type: String): Pair<MethodReference, FieldReference> {
        val code = index.code(method)
        val getters = code.calls.map { it.method }.filter { it.params().isEmpty() && it.returnType.startsWith("L") }
            .distinctBy { it.key() }
        return getters.mapNotNull { getter ->
            code.reads.filter { it.definingClass == getter.returnType && it.type == type }
                .distinctBy { it.key() }.singleOrNull()?.let { getter to it }
        }.single()
    }

    fun resolve(): Map<String, String> = buildMap {
        val show = index.strings("showOrHideShortCutMenu: invalid!!!").single {
            val params = it.params()
            params.size == 5 && params[2] == view && params[4] == "Z" && it.returnType == "V"
        }
        val owner = show.definingClass
        val item = show.params()[1]
        val callback = show.params()[3]
        val showCode = index.code(show)
        val menu = showCode.reads.filter { it.definingClass == owner && extendsCard(it.type) }.distinctBy { it.key() }.single()
        // The native card is rebuilt from the same item/callback pair before it is positioned.
        check(showCode.calls.any { it.method.definingClass == menu.type && it.method.params() == listOf(item, callback) })
        check(showCode.calls.any { it.method.definingClass == "Landroid/view/View;" && it.method.name == "getLocationInWindow" })
        val dismiss = index.strings("dismissShortCut mShortCutMenu: ").single {
            it.definingClass == owner && it.params().isEmpty() && it.returnType == "V"
        }
        check(index.code(dismiss).reads.any { it.key() == menu.key() })
        // Animated panel collapse that SystemUI requests when an app opens from the sidebar; it also
        // dismisses the native card.
        val collapse = index.strings("hideNewDockSidebar withAnim isMainDockAdded: ").single {
            it.definingClass == owner && it.params() == listOf("I") && it.returnType == "V"
        }
        check(index.localCalls(collapse).any { it.key() == dismiss.key() })

        // Menu entries are inner callbacks of the entry base type; its uid/package accessors only
        // accept the app item and read the model returned by that item's getter.
        check(callback.contains('$'))
        val base = callback.substringBefore('$') + ";"
        val members = index.declared(base)
        val uid = members.filter { it.params().isEmpty() && it.returnType == "I" && it.implementation != null }
            .mapNotNull { runCatching { modelRead(it, "I") }.getOrNull() }.single()
        val pkg = members.filter { it.params().isEmpty() && it.returnType == "Ljava/lang/String;" && it.implementation != null }
            .mapNotNull { method ->
                val code = index.code(method)
                if (code.calls.none { it.method.key() == uid.first.key() }) null
                else code.reads.filter { it.definingClass == uid.first.returnType && it.type == "Ljava/lang/String;" }
                    .distinctBy { it.key() }.singleOrNull()
            }.single()
        val app = uid.first.definingClass
        check(implements(app, item))
        // The model prints "AppInfo{uid=" from the very field used above: it is a uid, not a user id.
        val model = uid.first.returnType
        val printer = index.strings("AppInfo{uid=").single { it.definingClass == model }
        check(index.code(printer).reads.firstOrNull { it.definingClass == model }?.key() == uid.second.key())

        put("show", show.key())
        put("dismiss", dismiss.key())
        put("collapse", collapse.key())
        put("menu", menu.key())
        put("appGetter", uid.first.key())
        put("uid", uid.second.key())
        put("pkg", pkg.key())
        val showKey = show.key()
        put("callers", index.callers(listOf(showKey)).filter { !it.name.startsWith("<") }.joinToString("\n") { it.key() })
    }
}
