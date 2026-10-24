package com.takekazex.hypertweak.hook.base.dex

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import java.io.File
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import java.util.concurrent.ConcurrentHashMap

/** Shared operand-level evidence for host contracts. No target code is executed here.
 *
 * DexKit finds broad semantic owners; this reader preserves the actual register/branch
 * relationship between a preference key and its capability, instead of guessing a method
 * from hundreds of identical boolean signatures. Results are descriptors, never live DEX
 * objects or reflected classes from another loader.
 */
internal class DexOperandIndex internal constructor(
    private val containers: List<File>,
    private val stringLookup: ((String) -> List<String>?)? = null,
) {
    private val definitions by lazy {
        val result = containers.flatMap { apk ->
        if (apk.extension.lowercase() in setOf("apk", "zip", "jar")) {
            // ZipDexContainer.getDexEntryNames probes EVERY resource's header. Camera has
            // thousands of assets; probing them created thousands of exceptions during boot.
            // ART loads root multidex entries. Validate only those streams as real DEX files.
            java.util.zip.ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().filter {
                    !it.isDirectory && ROOT_DEX.matches(it.name)
                }.toList()
                require(entries.isNotEmpty()) { "APK has no root DEX entries" }
                entries.flatMap { entry ->
                    zip.getInputStream(entry).buffered().use { input ->
                        org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                            Opcodes.getDefault(), input,
                        ).classes.toList()
                    }
                }
            }
        } else {
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.toList()
            }
        }
        }
        require(result.map { it.type }.distinct().size == result.size) { "Ambiguous duplicate DEX class definitions" }
        result
    }
    val methods: List<Method> by lazy { definitions.flatMap { it.methods.toList() } }
    private val byOwner = ConcurrentHashMap<String, List<Method>>()
    private val byType by lazy { definitions.associateBy { it.type } }
    fun classDefinition(owner: String) = byType[owner]
    private fun methodByDescriptor(value: String): Method? {
        val arrow = value.indexOf("->")
        if (arrow <= 0) return null
        return declared(value.substring(0, arrow)).singleOrNull { descriptor(it) == value }
    }
    private val targetedStrings = ConcurrentHashMap<String, List<Method>>()
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
            return DexControlFlow.edgeDominates(edges, roots, branch.start, fallthrough, target)
        }
    }

    /** Materialize a backend candidate without enumerating every method in the container. */
    fun definition(descriptor: String): Method? = methodByDescriptor(descriptor)
    fun definition(reference: MethodReference): Method? = methodByDescriptor(descriptor(reference))
    fun code(descriptor: String): Code = codeCache.getOrPut(descriptor) {
        decode(methodByDescriptor(descriptor))
    }
    fun hasNumber(reference: MethodReference, value: Long): Boolean =
        definition(reference)?.implementation?.instructions?.any {
            it is WideLiteralInstruction && it.wideLiteral == value
        } == true
    fun declared(owner: String): List<Method> = byOwner.getOrPut(owner) {
        byType[owner]?.methods?.toList().orEmpty()
    }
    fun code(reference: MethodReference): Code = codeCache.getOrPut(descriptor(reference)) {
        decode(definition(reference))
    }
    private fun literals(method: Method): Set<String> = (method.implementation?.instructions ?: emptyList<Instruction>())
        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
    internal fun matchesString(descriptor: String, value: String): Boolean =
        methodByDescriptor(descriptor)?.let { value in literals(it) } == true
    private fun methodsUsing(value: String): List<Method> = if (stringLookup == null) stringIndex[value].orEmpty()
        else targetedStrings.getOrPut(value) {
            val descriptors = requireNotNull(stringLookup.invoke(value)) { "targeted string lookup failed: $value" }
            descriptors.map { descriptor ->
                requireNotNull(methodByDescriptor(descriptor)) { "missing targeted method: $descriptor" }
                    .also { require(value in literals(it)) { "targeted method does not use requested string" } }
            }.distinctBy(::descriptor)
        }

    fun strings(vararg values: String): List<Method> = values.map { methodsUsing(it).toSet() }
        .reduceOrNull { a, b -> a intersect b }?.toList().orEmpty()
    fun ownerStrings(vararg values: String): List<String> = values.map { value ->
        methodsUsing(value).map { it.definingClass }.toSet()
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

    fun method(reference: MethodReference, loader: ClassLoader): java.lang.reflect.Method? =
        MethodDescriptorCodec.method(MethodDescriptorCodec.descriptor(reference), loader)
    fun field(reference: FieldReference, loader: ClassLoader): java.lang.reflect.Field? =
        MethodDescriptorCodec.field(MethodDescriptorCodec.descriptor(reference), loader)

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
        private val ROOT_DEX = Regex("classes(?:[0-9]+)?\\.dex")
        /** Offline fixture reader. Production indexes are obtained only through DexRuntime. */
        fun fixture(path: String): DexOperandIndex = DexOperandIndex(listOf(File(path)))
        fun openWithStringLookup(path: String, lookup: (String) -> List<String>?): DexOperandIndex =
            DexOperandIndex(listOf(File(path)), lookup)

        fun descriptor(method: MethodReference): String = MethodDescriptorCodec.descriptor(method)
        fun descriptor(type: Class<*>): String = MethodDescriptorCodec.typeDescriptor(type)
        fun type(descriptor: String, loader: ClassLoader): Class<*> =
            requireNotNull(MethodDescriptorCodec.type(descriptor, loader)) { "Invalid type: $descriptor" }
        fun isInstanceGetter(method: MethodReference, owner: String, returns: String): Boolean =
            method.definingClass == owner && method.parameterTypes.isEmpty() && method.returnType == returns
    }
}
