package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import org.luckypray.dexkit.DexKitBridge

/**
 * A feature component borrows its parent's hook operations, loader and process context.
 * It never creates a second lifecycle or handle store: default IDs, replacement handles,
 * deoptimization and DEX cache identity still belong to the original BaseHooker.
 */
internal abstract class HookComponent(protected val owner: BaseHooker) {
    protected val classLoader get() = owner.classLoader
    protected val hookParam get() = owner.hookParam

    protected fun String.toClassOrNull(initialize: Boolean = false): Class<Any>? =
        with(owner) { this@toClassOrNull.toClassOrNull(initialize) }
    protected fun String.toClass(initialize: Boolean = false): Class<Any> =
        with(owner) { this@toClass.toClass(initialize) }
    protected fun Method.hook(block: HookFactory.() -> Unit) = with(owner) { this@hook.hook(block = block) }
    protected fun Method.hook(id: String, block: HookFactory.() -> Unit) = with(owner) { this@hook.hook(id, block = block) }
    protected fun Constructor<*>.hook(block: HookFactory.() -> Unit) = with(owner) { this@hook.hook(block = block) }
    protected fun Constructor<*>.hook(id: String, block: HookFactory.() -> Unit) = with(owner) { this@hook.hook(id, block = block) }
    protected fun Class<*>.hookAllConstructors(block: HookFactory.() -> Unit) =
        with(owner) { this@hookAllConstructors.hookAllConstructors(block) }
    protected fun Class<*>.findMethodOrNull(query: io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery.() -> Unit): Method? =
        with(owner) { this@findMethodOrNull.findMethodOrNull(query) }
    protected fun deoptimize(executable: Executable) = owner.deoptimize(executable)
    protected fun <T : Any> resolveDexContract(
        id: String, apk: String, schema: Int = 1, compute: (DexKitBridge) -> T?,
        encode: (T) -> Map<String, String>, decode: (DexContractMembers) -> T?
    ): T? = owner.resolveDexContract(id, apk, schema, compute, encode, decode)
}
