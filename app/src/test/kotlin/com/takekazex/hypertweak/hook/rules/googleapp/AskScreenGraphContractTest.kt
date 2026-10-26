package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec as Codec
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.FieldReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AskScreenGraphContractTest {
    private fun resolve(index: DexOperandIndex, root: String, entry: String): List<AskScreenGraphContract.Match> {
        fun Method.references(target: String, prefix: String): Boolean = implementation?.instructions?.any {
            if (!it.opcode.name.uppercase().startsWith(prefix)) false else when (val ref = (it as? ReferenceInstruction)?.reference) {
                is MethodReference -> Codec.descriptor(ref) == target
                is FieldReference -> Codec.descriptor(ref) == target
                else -> false
            }
        } == true
        return AskScreenGraphContract.resolve(index, root,
            callers = { target -> index.methods.filter { it.references(Codec.descriptor(target), "INVOKE") } },
            uses = { target ->
                val descriptor = Codec.descriptor(target)
                AskScreenGraphContract.FieldUses(
                    index.methods.filter { it.references(descriptor, "IGET") || it.references(descriptor, "SGET") },
                    index.methods.filter { it.references(descriptor, "IPUT") || it.references(descriptor, "SPUT") },
                )
            },
            isOmniEntry = { target ->
                if (Codec.descriptor(target) != entry) false else {
                    val code = index.code(target)
                    val field = code.reads.single()
                    assertEquals(field.type, code.calls.single().method.returnType)
                    assertTrue(index.classDefinition(field.definingClass)!!.staticFields.any {
                        Codec.descriptor(it) == Codec.descriptor(field) && it.accessFlags and 0x4000 != 0
                    })
                    true
                }
            },
        )
    }

    private fun artifact(variable: String, digest: String): File {
        val path = System.getenv(variable)
        assumeTrue("Set $variable to the verified APK", !path.isNullOrBlank())
        val file = File(path!!)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(131072)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }
        assertEquals(digest, hash.digest().joinToString("") { "%02x".format(it) })
        return file
    }

    @Test fun oldHostResolvesTheConnectedGraphWithoutUsingAnyHintLogText() {
        val apk = artifact("GOOGLE_APP_APK", "693c70e37b329840e93fe1b1683a251fea773f511adb4aa01b396193b8936ae2")
        val index = DexOperandIndex(listOf(apk))
        val result = resolve(index, "Ldwta;->bx(Lexcq;)Z", "Ldvmi;->f()Z").single()
        assertEquals("Leb;->h(Landroid/view/View;)Landroid/support/v4/app/Fragment;",
            AskScreenGraphContract.fragmentLookup(index, "Leb;", "Landroid/support/v4/app/Fragment;"))
        assertEquals("Ldtbp;->d()Z", result.eligibility)
        assertEquals("Ldxue;->w:Z", result.hint)
        assertEquals("Ldxue;->g:Ldtbp;", result.modelCoordinator)
    }

    @Test fun installedHostResolvesAfterRenamedDiagnosticAndExtractedRenderer() {
        val apk = artifact("GOOGLE_APP_CURRENT_APK", "c997af570b358f3f1e2b995f25bfd02c67e09cf2a3541bf17942c52cc84b0335")
        val index = DexOperandIndex(listOf(apk))
        val result = resolve(index, "Ldygl;->bG(Lezbq;)Z", "Ldwyp;->f()Z").single()
        assertEquals("Lec;->h(Landroid/view/View;)Landroid/support/v4/app/Fragment;",
            AskScreenGraphContract.fragmentLookup(index, "Lec;", "Landroid/support/v4/app/Fragment;"))
        assertEquals("Ldunh;->d()Z", result.eligibility)
        assertEquals("Ldzhs;->v:Z", result.hint)
        assertEquals("Ldunh;->d:Z", result.cached)
        assertEquals("Ldzhs;->g:Ldunh;", result.modelCoordinator)
        assertEquals("Ldunh;->f:Ldwyp;", result.entryOwner)
        val constructor = index.definition(result.constructor)!!
        val parameters = constructor.parameterTypes.map(CharSequence::toString)
        val boundary = parameters.indexOf("Ljava/util/concurrent/Executor;")
        val indices = (boundary - 1 downTo 0).takeWhile { parameters[it] == "Z" }.toSet()
        val fields = AskScreenModelContract.fields(index, result.constructor, indices)!!
        assertTrue(result.hint in fields)
        assertEquals(5, fields.size)
        assertTrue(parameters.flatMap { index.classDefinition(it)?.fields?.toList().orEmpty() }
            .any { it.definingClass == "Ldzhg;" && it.type == "Ldzhs;" })
    }
}
