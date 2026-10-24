package com.takekazex.hypertweak.hook.rules.aicr

import java.lang.reflect.Constructor
import java.lang.reflect.Method

/** Cached access to the host's immutable request and its own Kotlin serialization runtime. */
internal class AicrToolRequestAccess private constructor(
    private val input: Method,
    private val strings: List<Method>,
    private val constructor: Constructor<*>,
    private val parse: (String) -> Any
) {
    fun rewrite(request: Any, transform: (String) -> String?): Any? {
        // queryId, toolId, method, clientInfo are copied without changing their protocol identity.
        val values = strings.map { it.invoke(request) }
        if (values[1] != "aipanel") return null
        val original = input.invoke(request) ?: return null
        val rewritten = transform(original.toString()) ?: return null
        return constructor.newInstance(*values.toTypedArray(), parse(rewritten))
    }

    companion object {
        fun resolve(request: Class<*>, element: Class<*>, parse: (String) -> Any): AicrToolRequestAccess? {
            fun getter(name: String, result: Class<*>) = request.declaredMethods.singleOrNull {
                it.name == name && it.parameterCount == 0 && it.returnType == result
            }?.apply { isAccessible = true }
            val input = getter("getInput", element) ?: return null
            val getters = listOf("getQueryId", "getToolId", "getMethod", "getClientInfo").map {
                getter(it, String::class.java) ?: return null
            }
            val types = arrayOf(String::class.java, String::class.java, String::class.java, String::class.java, element)
            val constructor = request.declaredConstructors.singleOrNull {
                it.parameterTypes.contentEquals(types)
            }?.apply { isAccessible = true } ?: return null
            return AicrToolRequestAccess(input, getters, constructor, parse)
        }

        fun resolveHost(request: Class<*>): AicrToolRequestAccess? {
            val loader = request.classLoader
            // Derive host types from the host method, never Class.forName a constant library
            // name: R8 otherwise rewrites it to this module's obfuscated Json/JsonElement.
            val element = request.declaredMethods.singleOrNull {
                it.name == "getInput" && it.parameterCount == 0 && !it.returnType.isPrimitive
            }?.returnType ?: return null
            val namespace = element.name.substringBeforeLast('.', "")
            if (element.simpleName != "JsonElement" || namespace.isBlank()) return null
            val json = Class.forName(namespace + "." + "Json", false, loader)
            val receiver = json.declaredFields.filter {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && json.isAssignableFrom(it.type)
            }.singleOrNull()?.apply { isAccessible = true }?.get(null) ?: return null
            val parser = json.methods.singleOrNull {
                it.parameterTypes.contentEquals(arrayOf(String::class.java)) && it.returnType == element
            }?.apply { isAccessible = true } ?: return null
            return resolve(request, element) { value -> requireNotNull(parser.invoke(receiver, value)) }
        }
    }
}
