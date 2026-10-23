package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.instruction.ReferenceInstruction

/** Semantic anchors plus connected signatures; resource operands come from the current DEX. */
internal class SecurityCenterPopupResolver(private val index: CameraDexIndex) {
    private fun <T> Iterable<T>.unique(predicate: (T) -> Boolean): T = filter(predicate).single()
    private val string = "Ljava/lang/String;"
    private val list = "Ljava/util/List;"
    private val view = "Landroid/view/View;"
    private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
    private fun MethodReference.key() = CameraDexIndex.descriptor(this)
    private fun Method.calls() = index.code(this).calls.map { it.method }
    private fun Method.addsWindow() = calls().any { it.definingClass in setOf("Landroid/view/WindowManager;", "Landroid/view/ViewManager;") && it.name == "addView" }
    private fun anchor(text: String, params: List<String>) = index.strings(text).unique { it.params() == params && it.returnType == "V" }

    fun resolve(): Map<String, String> = buildMap {
        fun putMethod(role: String, method: MethodReference) { put("m.$role", method.key()) }
        val timing = anchor("showTimingBubble: ", listOf("I", "I", "I", string, string, "Ljava/util/Map;"))
        check(timing.addsWindow() && index.code(timing).hasString("pref_bubble_lifetime_show_times"))
        putMethod("timing", timing)
        val dock = timing.definingClass
        val wrapper = index.declared(dock).unique { candidate ->
            candidate.params() == listOf("I", "I", string, string) && candidate.returnType == "V" &&
                candidate.calls().any { it.key() == timing.key() }
        }
        val forward = index.code(wrapper).calls.unique { it.method.key() == timing.key() }.arguments
        check(forward.drop(1) == listOf(CameraDexIndex.Value.Number(-1), CameraDexIndex.Value.Argument(0),
            CameraDexIndex.Value.Argument(1), CameraDexIndex.Value.Argument(2), CameraDexIndex.Value.Argument(3), CameraDexIndex.Value.Number(0)))
        val producer = index.declared("Lcom/miui/gamebooster/service/DockWindowManagerService;").unique { candidate ->
            candidate.params().isEmpty() && candidate.returnType == "V" && index.code(candidate).calls.any {
                it.method.key() == wrapper.key() && it.arguments.contains(CameraDexIndex.Value.Text("dolby"))
            }
        }
        val operands = index.code(producer).calls.unique { it.method.key() == wrapper.key() }.arguments
        val text = (operands[1] as CameraDexIndex.Value.Number).number.toInt()
        val layout = (operands[2] as CameraDexIndex.Value.Number).number.toInt()
        check(text ushr 24 == 0x7f && layout ushr 24 == 0x7f && text != layout)
        put("dolbyText", text.toString()); put("dolbyLayout", layout.toString())
        putMethod("dolbyProducer", producer)

        val wild = anchor("showWildModeToastView: ", emptyList())
        check(wild.calls().any { it.name == "postDelayed" && it.definingClass == "Landroid/os/Handler;" })
        val owner = wild.definingClass
        putMethod("wild", wild)
        val immediate = anchor("showNewWildModeToastView: ", listOf(view))
        check(immediate.definingClass == owner && immediate.addsWindow())
        putMethod("immediate", immediate)
        val members = index.declared(owner)
        val launch = members.unique { it.params() == listOf(list, dock, "Z", "I", string, string) && it.returnType == "V" }
        check(launch.calls().any { it.name == "postDelayed" && it.definingClass == "Landroid/os/Handler;" })
        putMethod("launch", launch)
        // Final publication also fences callbacks queued before the switch or module upgrade.
        val render = members.unique { it.params() == listOf(list, dock, "I", string, string) && it.returnType == "V" && it.addsWindow() }
        putMethod("launchRender", render)
        val runnable = wild.calls().unique { it.name == "<init>" && it.params() == listOf(owner) }.definingClass
        val run = index.declared(runnable).unique { it.name == "run" && it.params().isEmpty() }
        val relay = run.calls().unique { it.definingClass == owner && it.returnType == "V" }
        val wildRender = (listOf(relay) + index.localCalls(relay)).distinctBy { it.key() }.unique {
            it.definingClass == owner && it.params().isEmpty() && it.returnType == "V" && index.definition(it)?.addsWindow() == true
        }
        check(index.definition(wildRender)?.addsWindow() == true)
        putMethod("wildRender", wildRender)
        val window = anchor("showNewWindowToastView: ", listOf("Z"))
        check(window.definingClass == owner)
        putMethod("window", window)
        val windowRunnable = window.calls().unique { it.name == "<init>" && it.params() == listOf(owner, "Z") }.definingClass
        val windowRun = index.declared(windowRunnable).unique { it.name == "run" && it.params().isEmpty() }
        val windowRelay = windowRun.calls().unique { it.definingClass == owner && it.returnType == "V" }
        val windowRender = (listOf(windowRelay) + index.localCalls(windowRelay)).distinctBy { it.key() }.unique {
            it.definingClass == owner && it.params() == listOf("Z") && it.returnType == "V" && index.definition(it)?.addsWindow() == true
        }
        putMethod("windowRender", windowRender)
        for ((role, textAnchor, params) in listOf(
            Triple("removeWindow", "removeNewWindowToastView: ", listOf(string)),
            Triple("removeWild", "removeWildModeToastView: ", listOf(string)),
            Triple("removeImmediate", "removeNewWildToastView: ", listOf(string)),
            Triple("removeLaunch", "remove launch awareness toast error", listOf(string, "Z")),
        )) {
            val method = anchor(textAnchor, params)
            check(method.definingClass == owner)
            putMethod(role, method)
        }
        val singleton = index.classDefinition(owner)!!.fields.unique { it.type == owner && it.accessFlags and 8 != 0 }
        put("singleton", "${singleton.definingClass}->${singleton.name}:${singleton.type}")
        val launchView = index.code(render).writes.map { it.first }.filter {
            index.classDefinition(it.type)?.superclass == "Landroid/widget/RelativeLayout;"
        }.distinctBy { "${it.definingClass}->${it.name}" }.single()
        put("launchView", "${launchView.definingClass}->${launchView.name}:${launchView.type}")
        val boundaries = listOf(timing, producer, wild, immediate, launch, render, wildRender, window, windowRender).map { it.key() }.toSet()
        put("callers", index.methods.filter { candidate ->
            !candidate.name.startsWith("<") && candidate.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.key() in boundaries
            } == true
        }.joinToString("\n") { it.key() })
    }
}
