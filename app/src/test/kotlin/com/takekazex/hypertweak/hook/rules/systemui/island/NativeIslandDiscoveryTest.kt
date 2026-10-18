package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandAnchors
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandContract
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandDiscovery
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandMembers
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec

import org.junit.Assert.*
import org.junit.Test

class NativeIslandDiscoveryTest {
    // All implementation owners and semantic entries deliberately use different names.
    @Suppress("unused", "UNUSED_PARAMETER")
    private object Fixture {
        class Builder {
            private var cutout: android.widget.FrameLayout? = null
            fun width(value: Int) {}
        }
        open class B
        class V : B() { fun getState() = S(); fun getCurrentIslandData() = Data(); fun getFakeView() = F(); fun getViewModel() = VM(); fun getWindowState() = WS(); fun m() {} }
        class VM
        class WS
        class F : B()
        open class S { class SmallIsland; class BigIsland; class Hidden }
        class E { class SwipeLeft; class SwipeRight }
        open class H { fun p() {} }
        class HH : H()
        class W { fun getWindowViewController() = WC() }
        class WC
        class Data
        class C {
            fun t(event: E, view: V) {}
            fun getHiddenStateHandler() = HH()
            fun getAnimationController() = A()
            fun getWindowView() = W()
        }
        class A { fun a(view: V) {}; fun getAnimator(view: B) = D() }
        class D { private val u = U(); fun getCoords() = Coords(); fun getGestureAnimator() = G(); fun getFakeViewAnimator() = FA() }
        class FA { fun getHelper() = FH() }
        class FH
        class Coords
        class G
        class P
        class U { fun updateBigIsland(props: P) {}; fun updateContainer(props: P) {} }
        class Params
        class Result
        class M { fun m(params: Params) = Result() }
        class Swipe { fun s(x: Float, y: Float, expanded: Boolean, queue: ArrayList<V>) {} }
        class Touch { fun t(event: android.view.MotionEvent, source: String): Boolean? = true }
        class Context
        class Config
        class Tween { fun to(value: Any, config: Array<Config>): Tween = this }
        class R { fun r(old: S, current: S, context: Context): List<Any> = emptyList() }
        fun entries() = mapOf(
            "transaction" to C::class.java.declaredMethods.single { it.name == "t" },
            "publication" to H::class.java.declaredMethods.single { it.name == "p" },
            "contentMeasure" to V::class.java.declaredMethods.single { it.name == "m" },
            "phoneMeasure" to M::class.java.declaredMethods.single(),
            "animation" to A::class.java.declaredMethods.single { it.name == "a" },
            "swipe" to Swipe::class.java.declaredMethods.single(),
            "touch" to Touch::class.java.declaredMethods.single(),
            "transitions" to R::class.java.declaredMethods.single(),
            "widthTween" to Tween::class.java.declaredMethods.single(),
        )
    }

    @Test fun `cached discovery revalidates renamed entries writer and owner relationships`() {
        val values = Fixture.entries().mapKeys { "entry.${it.key}" }.mapValues {
            MethodDescriptorCodec.descriptor(it.value)
        }.toMutableMap()
        val writer = Fixture.Builder::class.java.getDeclaredMethod("width", Int::class.javaPrimitiveType)
        values["writer.method"] = MethodDescriptorCodec.descriptor(writer)
        values["writer.field"] = MethodDescriptorCodec.descriptor(Fixture.Builder::class.java.getDeclaredField("cutout"))
        values["writer.callers"] = "1"
        values["writer.caller.0"] = values.getValue("entry.contentMeasure")
        val loader = javaClass.classLoader!!
        val resolved = NativeIslandDiscovery.decode(values, loader)
        assertEquals(values, NativeIslandDiscovery.encode(resolved))
        assertEquals(Fixture.C::class.java, resolved.entries.getValue("transaction").declaringClass)
        values["entry.contentMeasure"] = values.getValue("entry.publication")
        assertThrows(IllegalStateException::class.java) { NativeIslandDiscovery.decode(values, loader) }
    }

    @Test fun `complete discovery derives renamed owners through type relationships`() {
        val entries = Fixture.entries()
        val resolved = NativeIslandDiscovery.resolve { listOf(entries.getValue(it)) }
        val root = "miui.systemui.dynamicisland."
        assertEquals(Fixture.C::class.java, resolved.classes[root + "event.DynamicIslandEventCoordinator"])
        assertEquals(Fixture.V::class.java, resolved.classes[root + "window.content.DynamicIslandContentView"])
        assertEquals(Fixture.Coords::class.java, resolved.classes[root + "anim.ui.animator.IslandCoordinateCalculator"])
        assertEquals(Fixture.U::class.java, resolved.classes[root + "anim.ui.animator.IslandPropertyUpdater"])
        assertTrue("Every access-registry owner must be available through discovery",
            resolved.classes.keys.containsAll(NativeIslandContract.methods.keys))
        assertEquals(Fixture.FA::class.java, resolved.classes[root + "anim.ui.animator.FakeViewAnimator"])
        assertEquals(Fixture.FH::class.java, resolved.classes[root + "anim.ui.animator.FakeViewAnimHelper"])
        assertEquals(Fixture.WC::class.java, resolved.classes[root + "window.DynamicIslandWindowViewController"])
        assertEquals("t", resolved.entries.getValue("transaction").name)
        assertEquals("p", resolved.entries.getValue("publication").name)
    }

    private class Renamed {
        @Suppress("unused") private var a = ArrayList<String>()
        @Suppress("unused") fun read(): Int = 1
        @Suppress("unused", "UNUSED_PARAMETER") fun read(value: String): Int = 2
        companion object {
            @JvmStatic
            @Suppress("unused", "UNUSED_PARAMETER") fun read(value: Boolean?, flags: Int, marker: Any?): Int = 3
        }
    }
    private class Ambiguous {
        @Suppress("unused") private var a = ArrayList<String>()
        @Suppress("unused") private var b = ArrayList<String>()
    }
    private data class Candidate(val owner: String, val name: String, val params: List<String>, val result: String)
    private fun select(role: String, vararg candidates: Candidate) = NativeIslandAnchors.select(role, candidates.toList(), Candidate::params, Candidate::result)

    @Test fun `renamed private fields are selected by unique type and overloaded methods by signature`() {
        assertEquals("a", NativeIslandMembers.field(Renamed::class.java, ArrayList::class.java).name)
        assertEquals(0, NativeIslandMembers.named(Renamed::class.java, "read", emptyList()).parameterCount)
        assertEquals(1, NativeIslandMembers.named(Renamed::class.java, "read", listOf(String::class.java)).parameterCount)
    }

    @Test fun `static Kotlin default bridges resolve separately from instance members`() {
        val parameters = listOf(Boolean::class.javaObjectType, Int::class.javaPrimitiveType!!, Any::class.java)
        val bridge = NativeIslandMembers.namedStatic(Renamed::class.java, "read", parameters)
        assertTrue(java.lang.reflect.Modifier.isStatic(bridge.modifiers))
        assertEquals(3, bridge.invoke(null, null, 1, null))
        assertThrows(NoSuchElementException::class.java) {
            NativeIslandMembers.named(Renamed::class.java, "read", parameters)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun `ambiguous field types are rejected`() {
        NativeIslandMembers.field(Ambiguous::class.java, ArrayList::class.java)
    }

    @Test fun `semantic entries tolerate owner and entry name changes and ignore incompatible overloads`() {
        val renamed = Candidate("x.y", "a", listOf("model.Event", "model.View"), "void")
        assertEquals(renamed, select("transaction", renamed,
            renamed.copy(name = "overload", params = listOf("int"))))
        val changed = renamed.copy(owner = "other.Owner", name = "b")
        assertEquals(changed, select("transaction", changed))
    }

    @Test(expected = IllegalArgumentException::class) fun `duplicate semantic entries cannot silently choose a host`() {
        val one = Candidate("x.y", "a", emptyList(), "void")
        select("publication", one, one.copy(owner = "x.z"))
    }

    @Test(expected = NoSuchElementException::class) fun `changed publication signature is rejected`() {
        select("publication", Candidate("x.y", "a", listOf("java.lang.Runnable"), "void"))
    }
}
