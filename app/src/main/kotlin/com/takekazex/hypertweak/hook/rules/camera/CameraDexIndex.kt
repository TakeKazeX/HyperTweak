package com.takekazex.hypertweak.hook.rules.camera

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Operand-level evidence for camera feature call sites. No target code is executed here.
 *
 * DexKit finds broad semantic owners; this reader preserves the actual register/branch
 * relationship between a preference key and its capability, instead of guessing a method
 * from hundreds of identical boolean signatures. Results are descriptors, never live DEX
 * objects or reflected classes from another loader.
 */
internal class CameraDexIndex private constructor(apk: File) {
    private val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
    private val definitions by lazy {
        container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.toList()
        }
    }
    val methods: List<Method> by lazy { definitions.flatMap { it.methods.toList() } }
    private val byOwner by lazy { methods.groupBy { it.definingClass } }
    private val byType by lazy { definitions.associateBy { it.type } }
    fun classDefinition(owner: String) = byType[owner]
    private val byDescriptor by lazy { methods.associateBy { descriptor(it) } }
    private val codeCache = ConcurrentHashMap<String, Code>()
    private val stringIndex by lazy {
        buildMap<String, MutableList<Method>> {
            methods.forEach { method -> literals(method).forEach { literal ->
                getOrPut(literal) { ArrayList() }.add(method)
            } }
        }
    }

    sealed interface Value {
        data class Call(val method: MethodReference, val arguments: List<Value?> = emptyList()) : Value
        data class Argument(val index: Int) : Value
        data class Field(val field: FieldReference) : Value
        data class Text(val text: String) : Value
        data class Number(val number: Long) : Value
    }
    data class Branch(val start: Int, val end: Int, val value: Value, val skipsWhenFalse: Boolean)
    data class CallSite(val address: Int, val method: MethodReference, val arguments: List<Value?>)
    data class StringSite(val address: Int, val value: String)
    data class ArrayRead(val producer: Value?, val index: Value?)
    data class Code(
        val calls: List<CallSite>,
        val strings: List<StringSite>,
        val branches: List<Branch>,
        val reads: List<FieldReference>,
        val writes: List<Pair<FieldReference, Value?>>,
        val arrays: List<ArrayRead>,
        val edges: Map<Int, List<Int>>,
        val roots: Set<Int>,
    ) {
        fun hasString(value: String) = strings.any { it.value == value }

        /** The innermost forward guard must actually enclose this key's instruction. */
        fun guards(key: String): List<Value> = strings.filter { it.value == key }.flatMap { site ->
            branches.filter { it.skipsWhenFalse && site.address > it.start && site.address < it.end &&
                dominatesFallthrough(it, site.address) }
                .sortedByDescending { it.start }
                .map { it.value }
        }.distinct()

        private fun dominatesFallthrough(branch: Branch, target: Int): Boolean {
            val fallthrough = edges[branch.start]?.firstOrNull() ?: return false
            val visited = HashSet<Int>()
            val pending = java.util.ArrayDeque(roots)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (!visited.add(node)) continue
                if (node == target) return false
                edges[node].orEmpty().forEach { next ->
                    if (node != branch.start || next != fallthrough) pending.add(next)
                }
            }
            return true
        }
    }

    fun definition(reference: MethodReference): Method? = byDescriptor[descriptor(reference)]
    fun hasNumber(reference: MethodReference, value: Long): Boolean =
        definition(reference)?.implementation?.instructions?.any {
            it is WideLiteralInstruction && it.wideLiteral == value
        } == true
    fun declared(owner: String): List<Method> = byOwner[owner].orEmpty()
    fun code(reference: MethodReference): Code = codeCache.getOrPut(descriptor(reference)) {
        decode(definition(reference))
    }
    private fun literals(method: Method): Set<String> = (method.implementation?.instructions ?: emptyList<Instruction>())
        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
    fun strings(vararg values: String): List<Method> = values.map { stringIndex[it].orEmpty().toSet() }
        .reduceOrNull { a, b -> a intersect b }?.toList().orEmpty()
    fun ownerStrings(vararg values: String): List<String> = values.map { value ->
        stringIndex[value].orEmpty().map { it.definingClass }.toSet()
    }.reduceOrNull { a, b -> a intersect b }?.toList().orEmpty()

    fun callsFromOwner(owner: String): List<MethodReference> = declared(owner)
        .flatMap { code(it).calls }.map { it.method }.distinctBy(::descriptor)

    /** Follow same-owner helper calls only; never wander into general platform/framework code. */
    fun localCalls(root: MethodReference, depth: Int = 2): List<MethodReference> {
        val result = LinkedHashMap<String, MethodReference>()
        fun visit(method: MethodReference, remaining: Int) {
            code(method).calls.forEach { call ->
                if (result.putIfAbsent(descriptor(call.method), call.method) == null && remaining > 0 &&
                    call.method.definingClass == root.definingClass) visit(call.method, remaining - 1)
            }
        }
        visit(root, depth)
        return result.values.toList()
    }

    fun preferenceGates(key: String): List<MethodReference> = strings(key)
        .flatMap { code(it).guards(key) }.filterIsInstance<Value.Call>()
        .map { it.method }.filter { it.returnType == "Z" }
        .distinctBy(::descriptor)

    fun preferenceFields(key: String): List<FieldReference> = strings(key)
        .flatMap { code(it).guards(key) }.filterIsInstance<Value.Field>()
        .map { it.field }.filter { it.type == "Z" }.distinctBy { it.toString() }

    /** Read the real title/summary operands passed alongside the preference key. */
    fun preferenceResources(key: String): List<Int> = strings(key).flatMap { method ->
        code(method).calls.filter { call ->
            (call.method.name.startsWith("add") || call.method.name == "<init>") &&
                call.arguments.any { it == Value.Text(key) }
        }.flatMap { call ->
            val values = call.arguments + call.arguments.filterIsInstance<Value.Call>().flatMap { it.arguments }
            values.filterIsInstance<Value.Number>().map { it.number.toInt() }
                .filter { it ushr 24 == 0x7f }
        }
    }.distinct()

    fun method(reference: MethodReference, loader: ClassLoader): java.lang.reflect.Method? = runCatching {
        type(reference.definingClass, loader).getDeclaredMethod(
            reference.name, *reference.parameterTypes.map { type(it.toString(), loader) }.toTypedArray(),
        ).takeIf { it.returnType == type(reference.returnType, loader) }?.apply { isAccessible = true }
    }.getOrNull()

    fun field(reference: FieldReference, loader: ClassLoader): java.lang.reflect.Field? = runCatching {
        type(reference.definingClass, loader).getDeclaredField(reference.name)
            .takeIf { it.type == type(reference.type, loader) }?.apply { isAccessible = true }
    }.getOrNull()

    private fun decode(method: Method?): Code {
        val calls = ArrayList<CallSite>()
        val strings = ArrayList<StringSite>()
        val branches = ArrayList<Branch>()
        val reads = ArrayList<FieldReference>()
        val writes = ArrayList<Pair<FieldReference, Value?>>()
        val arrays = ArrayList<ArrayRead>()
        val registers = HashMap<Int, Value>()
        if (method?.implementation != null) {
            val widths = method.parameterTypes.map { if (it == "J" || it == "D") 2 else 1 }
            var register = method.implementation!!.registerCount - widths.sum()
            if (method.accessFlags and 8 == 0) registers[register - 1] = Value.Argument(-1)
            widths.forEachIndexed { index, width -> registers[register] = Value.Argument(index); register += width }
        }
        var pending: Value? = null
        var address = 0
        val instructions = method?.implementation?.instructions?.toList().orEmpty()
        val positions = ArrayList<Pair<Int, Instruction>>()
        instructions.forEach { positions += address to it; address += it.codeUnits }
        val byAddress = positions.toMap()
        val edges = positions.associate { (position, instruction) ->
            val op = instruction.opcode.name.uppercase().replace('-', '_').replace('/', '_')
            val next = position + instruction.codeUnits
            val target = (instruction as? OffsetInstruction)?.let { position + it.codeOffset }
            position to when {
                op.startsWith("RETURN") || op == "THROW" -> emptyList()
                op.startsWith("GOTO") -> listOfNotNull(target)
                op.startsWith("IF_") -> listOfNotNull(next, target)
                op.endsWith("SWITCH") -> listOf(next) +
                    (byAddress[target] as? SwitchPayload)?.switchElements.orEmpty().map { position + it.offset }
                else -> listOf(next).filter(byAddress::containsKey)
            }
        }
        val loopHeaders = positions.mapNotNull { (position, instruction) ->
            (instruction as? OffsetInstruction)?.takeIf { it.codeOffset <= 0 }
                ?.let { position + it.codeOffset }
        }.toSet()
        val handlers = method?.implementation?.tryBlocks.orEmpty().flatMap { block ->
            block.exceptionHandlers.map { it.handlerCodeAddress }
        }.toSet()
        val incoming = HashMap<Int, MutableList<Map<Int, Value>>>()
        var alive = true
        for ((position, instruction) in positions) {
            address = position
            val states = incoming.remove(address).orEmpty().toMutableList()
            if (alive) states.add(registers.toMap())
            if (address in loopHeaders || address in handlers) states.add(emptyMap())
            registers.clear()
            if (states.isEmpty()) continue
            registers.putAll(states.first().filter { (register, value) -> states.all { it[register] == value } })
            alive = true
            val op = instruction.opcode.name.uppercase().replace('-', '_').replace('/', '_')
            val reference = (instruction as? ReferenceInstruction)?.reference
            val a = (instruction as? OneRegisterInstruction)?.registerA
            val b = (instruction as? TwoRegisterInstruction)?.registerB
            val c = (instruction as? ThreeRegisterInstruction)?.registerC
            val priorA = a?.let(registers::get)
            val priorB = b?.let(registers::get)
            val priorC = c?.let(registers::get)
            if (instruction.opcode.setsRegister() && a != null) registers.remove(a)
            when {
                reference is StringReference && op.startsWith("CONST_STRING") -> {
                    registers[a!!] = Value.Text(reference.string)
                    strings += StringSite(address, reference.string)
                }
                instruction is WideLiteralInstruction && op.startsWith("CONST") ->
                    registers[a!!] = Value.Number(instruction.wideLiteral)
                op.startsWith("MOVE_RESULT") -> pending?.let { registers[a!!] = it }
                op.startsWith("MOVE") && b != null -> priorB?.let { registers[a!!] = it }
                reference is MethodReference && op.startsWith("INVOKE") -> {
                    val args = when (instruction) {
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD,
                            instruction.registerE, instruction.registerF, instruction.registerG)
                            .take(instruction.registerCount)
                        is RegisterRangeInstruction -> (instruction.startRegister until
                            instruction.startRegister + instruction.registerCount).toList()
                        else -> emptyList()
                    }
                    calls += CallSite(address, reference, args.map(registers::get))
                    pending = Value.Call(reference, args.map(registers::get))
                }
                reference is FieldReference && (op.startsWith("SGET") || op.startsWith("IGET")) -> {
                    reads += reference
                    registers[a!!] = Value.Field(reference)
                }
                reference is FieldReference && (op.startsWith("SPUT") || op.startsWith("IPUT")) ->
                    writes.add(reference to priorA)
                (op == "IF_EQZ" || op == "IF_NEZ") && instruction is OffsetInstruction -> {
                    if (priorA != null && instruction.codeOffset > 0) {
                        branches += Branch(address, address + instruction.codeOffset, priorA, op == "IF_EQZ")
                    }
                }
                op == "AGET_OBJECT" -> arrays += ArrayRead(priorB, priorC)
            }
            if (!op.startsWith("INVOKE") && !op.startsWith("MOVE_RESULT")) pending = null
            // Meet reaching definitions at control-flow joins. Conflicting origins become
            // unknown; a preference must never inherit a gate from a different branch.
            fun edge(target: Int) {
                if (target > address) incoming.getOrPut(target) { ArrayList() }.add(registers.toMap())
            }
            if (instruction is OffsetInstruction) {
                if (op.startsWith("IF_") || op.startsWith("GOTO")) edge(address + instruction.codeOffset)
                if (op.endsWith("SWITCH")) {
                    val payload = byAddress[address + instruction.codeOffset] as? SwitchPayload
                    payload?.switchElements?.forEach { edge(address + it.offset) }
                }
            }
            if (op.startsWith("GOTO") || op.startsWith("RETURN") || op == "THROW") {
                registers.clear()
                alive = false
            }
        }
        return Code(calls, strings, branches, reads, writes, arrays, edges, handlers + 0)
    }

    companion object {
        private var cachedPath: String? = null
        private var cached = WeakReference<CameraDexIndex>(null)
        @Synchronized fun open(path: String): CameraDexIndex {
            if (cachedPath == path) cached.get()?.let { return it }
            return CameraDexIndex(File(path)).also { cachedPath = path; cached = WeakReference(it) }
        }
        /** Keep one scan alive through an installation batch, then let GC reclaim its DEX data. */
        fun <T> withSession(path: String?, block: () -> T): T {
            val index = path?.let { runCatching { open(it) }.getOrNull() }
            return try { block() } finally { java.lang.ref.Reference.reachabilityFence(index) }
        }
        fun descriptor(method: MethodReference): String = "${method.definingClass}->${method.name}(" +
            method.parameterTypes.joinToString("") + ")${method.returnType}"
        fun descriptor(type: Class<*>): String = when {
            type.isArray -> type.name.replace('.', '/')
            type.isPrimitive -> mapOf("void" to "V", "boolean" to "Z", "byte" to "B",
                "char" to "C", "short" to "S", "int" to "I", "long" to "J", "float" to "F",
                "double" to "D").getValue(type.name)
            else -> "L${type.name.replace('.', '/')};"
        }
        fun type(descriptor: String, loader: ClassLoader): Class<*> = when (descriptor) {
            "V" -> Void.TYPE
            "Z" -> java.lang.Boolean.TYPE
            "B" -> java.lang.Byte.TYPE
            "C" -> java.lang.Character.TYPE
            "S" -> java.lang.Short.TYPE
            "I" -> Integer.TYPE
            "J" -> java.lang.Long.TYPE
            "F" -> java.lang.Float.TYPE
            "D" -> java.lang.Double.TYPE
            else -> Class.forName(if (descriptor.startsWith("L")) descriptor.substring(1, descriptor.length - 1)
                .replace('/', '.') else descriptor.replace('/', '.'), false, loader)
        }
        fun isInstanceGetter(method: MethodReference, owner: String, returns: String): Boolean =
            method.definingClass == owner && method.parameterTypes.isEmpty() && method.returnType == returns
    }
}
