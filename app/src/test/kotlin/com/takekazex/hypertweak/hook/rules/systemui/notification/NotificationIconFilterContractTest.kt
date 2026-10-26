package com.takekazex.hypertweak.hook.rules.systemui.notification

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.*
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationIconFilterContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = "Lcom/android/systemui/statusbar/notification/shared/ActiveNotificationModel;"
    private fun fixture(owner: String, method: String, ambient: String, silent: String, valid: Boolean = true): ImmutableClassDef {
        val instructions = listOf(
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 3, ImmutableFieldReference(owner, ambient, "Z")),
            ImmutableInstruction21t(Opcode.IF_NEZ, 0, 4),
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 1, 4, ImmutableFieldReference(model, "isAmbient", "Z")),
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 3, ImmutableFieldReference(owner, silent, "Z")),
            ImmutableInstruction21t(Opcode.IF_NEZ, 0, 4),
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 1, 4, ImmutableFieldReference(model, if (valid) "isSilent" else "unrelated", "Z")),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 1, 2, 0, 0, 0,
                ImmutableMethodReference("Lcom/android/wm/shell/bubbles/Bubbles;", "isBubbleExpanded", listOf("Ljava/lang/String;"), "Z")),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 4)
        )
        val body = ImmutableMethod(owner, method, listOf(ImmutableMethodParameter("Ljava/lang/Object;", emptySet(), null)),
            "Ljava/lang/Object;", 1, emptySet(), emptySet(), ImmutableMethodImplementation(5, instructions, emptyList(), emptyList()))
        return ImmutableClassDef(owner, 1, "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(), listOf(body))
    }
    private fun resolve(vararg classes: ImmutableClassDef): NotificationIconFilterContract.Target? {
        val file = temporary.newFile("${System.nanoTime()}.dex")
        DexPool.writeTo(file.absolutePath, ImmutableDexFile(Opcodes.getDefault(), classes.toList()))
        return NotificationIconFilterContract.resolve(DexOperandIndex.fixture(file.absolutePath))
    }
    @Test fun renamedPredicateAndCapturedFieldsResolveThroughTheirBranchOperands() {
        val owner = "Lcom/android/systemui/statusbar/notification/icon/renamed/Other;"
        val target = resolve(fixture(owner, "differentMethod", "renamedAmbient", "renamedSilent"))!!
        assertEquals("differentMethod", target.method)
        assertEquals("renamedAmbient", target.ambientFlag)
        assertEquals("renamedSilent", target.silentFlag)
    }
    @Test fun missingSilentConsumerAndAmbiguousPredicatesFailClosed() {
        val one = "Lcom/android/systemui/statusbar/notification/icon/renamed/One;"
        val two = "Lcom/android/systemui/statusbar/notification/icon/renamed/Two;"
        assertNull(resolve(fixture(one, "invoke", "a", "b", false)))
        assertNull(resolve(fixture(one, "invoke", "a", "b"), fixture(two, "other", "c", "d")))
    }
    private class ReplayPredicate {
        @Suppress("unused") private var ambient = false
        @Suppress("unused") private var silent = false
        @Suppress("unused") fun invoke(value: Any): Any = value
        @Suppress("unused") fun caller(value: Any): Any = invoke(value)
    }
    @org.junit.Test fun `full cache replay validates captured flags and every caller without a reader`() {
        val type = ReplayPredicate::class.java
        val target = NotificationIconFilterContract.Target(type.name, "invoke", "ambient", "silent", listOf(type.name to "caller"))
        val encoded = NotificationIconFilterContract.encode(target)
        val loader = type.classLoader!!
        org.junit.Assert.assertEquals(target, NotificationIconFilterContract.decode(encoded, loader))
        org.junit.Assert.assertNull(NotificationIconFilterContract.decode(encoded + ("silent" to "ambient"), loader))
        org.junit.Assert.assertNull(NotificationIconFilterContract.decode(encoded + ("callers" to type.name + "#missing"), loader))
        org.junit.Assert.assertNull(NotificationIconFilterContract.decode(encoded - "callers", loader))
    }

}
