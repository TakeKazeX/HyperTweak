package com.takekazex.hypertweak.hook.base

/** Fluent equivalents for Class expressions and nullable loader results; lookup scope is unchanged. Access policy is always explicit. */
internal fun Class<*>.cachedDeclaredField(name: String, accessible: Boolean) = HostMembers.requireDeclaredField(this, name, accessible)
internal fun Class<*>.cachedPublicField(name: String, accessible: Boolean) = HostMembers.requirePublicField(this, name, accessible)
internal fun Class<*>.cachedDeclaredMethod(name: String, vararg parameters: Class<*>?, accessible: Boolean) =
    HostMembers.requireDeclaredMethodArray(this, name, parameters, accessible)
internal fun Class<*>.cachedPublicMethod(name: String, vararg parameters: Class<*>?, accessible: Boolean) =
    HostMembers.requirePublicMethodArray(this, name, parameters, accessible)

internal fun Class<*>.cachedDeclaredMethod(name: String, accessible: Boolean) =
    HostMembers.requireDeclaredMethodArray(this, name, HostMembers.noParameters, accessible)
internal fun Class<*>.cachedPublicMethod(name: String, accessible: Boolean) =
    HostMembers.requirePublicMethodArray(this, name, HostMembers.noParameters, accessible)
