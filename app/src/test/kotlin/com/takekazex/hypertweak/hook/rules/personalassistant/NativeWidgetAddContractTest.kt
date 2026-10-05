package com.takekazex.hypertweak.hook.rules.personalassistant

import org.junit.Assert.*
import org.junit.Test

class NativeWidgetAddContractTest {
    class View
    open class Item
    class Widget : Item()
    interface Container {
        fun required(view: View?, item: Item)
        fun animation(view: View?, item: Item) {}
    }
    interface Ambiguous {
        fun first(view: View?, item: Item)
        fun second(view: View?, item: Item)
    }
    interface OptionalOnly { fun animation(view: View?, item: Item) {} }

    @Test fun `same signature default animation does not hide required native addition`() {
        val methods = Container::class.java.methods.toList()
        assertEquals(2, methods.count { it.returnType == Void.TYPE && it.parameterCount == 2 })
        assertEquals("required", NativeWidgetAddContract.select(methods, View::class.java, Widget::class.java)?.name)
    }
    @Test fun `two required operations reject ambiguity`() {
        assertNull(NativeWidgetAddContract.select(Ambiguous::class.java.methods.toList(), View::class.java, Widget::class.java))
    }
    @Test fun `optional helper is never treated as addition`() {
        assertNull(NativeWidgetAddContract.select(OptionalOnly::class.java.methods.toList(), View::class.java, Widget::class.java))
    }
}
