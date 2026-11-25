package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Structural selection of one declared host member.
 *
 * A selection succeeds only when exactly one declared member matches; none or several return null,
 * and an ambiguity is logged with its candidates. Bridge and synthetic methods match only after
 * [MethodSelector.synthetic]; constructors and fields are never filtered by those flags, because
 * Kotlin default-argument constructors and captured `val$`/`this$0` fields are synthetic and are
 * real targets. Selected members are made accessible before they are returned (and cached).
 *
 * With a `key`, the outcome (including a miss) is cached per actual Class through [HostMembers.cached],
 * so selections inside hook callbacks cost one map lookup instead of copying the member array.
 * The key must be unique for that Class across the process; prefix it with the feature.
 */
internal object HostSelect {
    fun method(type: Class<*>, key: String? = null, query: MethodSelector.() -> Unit): Method? =
        cachedOrResolve(type, key, Method::class.java) {
            unique(type, MethodSelector().apply(query), type.declaredMethods.asList())
        }

    fun field(type: Class<*>, key: String? = null, query: FieldSelector.() -> Unit): Field? =
        cachedOrResolve(type, key, Field::class.java) {
            unique(type, FieldSelector().apply(query), type.declaredFields.asList())
        }

    fun constructor(type: Class<*>, key: String? = null, query: ConstructorSelector.() -> Unit): Constructor<*>? =
        cachedOrResolve(type, key, Constructor::class.java) {
            unique(type, ConstructorSelector().apply(query), type.declaredConstructors.asList())
        }

    private fun <T : Any> cachedOrResolve(type: Class<*>, key: String?, resultType: Class<T>, resolve: () -> T?): T? =
        if (key == null) resolve() else HostMembers.cached(type, "select:$key", resultType, resolve)

    private fun <M : java.lang.reflect.AccessibleObject> unique(type: Class<*>, selector: MemberSelector<M>, members: List<M>): M? {
        var match: M? = null
        var count = 0
        for (member in members) {
            if (!selector.matches(member)) continue
            if (count++ == 0) match = member
        }
        if (count > 1) {
            DebugLog.w("HostSelect", "ambiguous ${type.name} $selector candidates=" +
                members.filter(selector::matches).joinToString { it.toString() })
            return null
        }
        return match?.apply { isAccessible = true }
    }
}

internal abstract class MemberSelector<M> {
    private val conditions = ArrayList<(M) -> Boolean>(4)
    private val description = ArrayList<String>(4)

    protected fun require(label: String, condition: (M) -> Boolean) {
        conditions += condition
        description += label
    }

    /** Arbitrary structural predicate; [label] names it in ambiguity logs. */
    fun where(label: String = "where", predicate: (M) -> Boolean) = require(label, predicate)

    internal open fun matches(member: M): Boolean = conditions.all { it(member) }

    override fun toString() = description.joinToString(prefix = "{", postfix = "}")
}

internal abstract class ExecutableSelector<M : java.lang.reflect.Executable> : MemberSelector<M>() {
    /** Exact parameter list; a null entry accepts any type at that position. */
    fun params(vararg types: Class<*>?) = require("params(${types.joinToString { it?.simpleName ?: "*" }})") { member ->
        val actual = member.parameterTypes
        actual.size == types.size && types.indices.all { types[it] == null || actual[it] == types[it] }
    }
    fun noParams() = paramCount(0)
    fun paramCount(count: Int) = require("paramCount($count)") { it.parameterCount == count }
}

internal class MethodSelector : ExecutableSelector<Method>() {
    fun name(value: String) = require("name=$value") { it.name == value }
    fun returns(type: Class<*>?) = require("returns=${type?.simpleName}") { it.returnType == type }
    fun static() = require("static") { Modifier.isStatic(it.modifiers) }
    fun instance() = require("instance") { !Modifier.isStatic(it.modifiers) }
    private var includeSynthetic = false
    /** Also accept bridge/synthetic methods, such as Kotlin `$default` or D8 nest accessors. */
    fun synthetic() { includeSynthetic = true }
    override fun matches(member: Method) =
        (includeSynthetic || (!member.isBridge && !member.isSynthetic)) && super.matches(member)
}

internal class ConstructorSelector : ExecutableSelector<Constructor<*>>()

internal class FieldSelector : MemberSelector<Field>() {
    fun name(value: String) = require("name=$value") { it.name == value }
    /** Exact declared type. */
    fun type(value: Class<*>?) = require("type=${value?.simpleName}") { it.type == value }
    /** Declared type assignable to [supertype]. */
    fun typeIs(supertype: Class<*>?) = require("type<:${supertype?.simpleName}") { supertype != null && supertype.isAssignableFrom(it.type) }
    fun static() = require("static") { Modifier.isStatic(it.modifiers) }
    fun instance() = require("instance") { !Modifier.isStatic(it.modifiers) }
}
