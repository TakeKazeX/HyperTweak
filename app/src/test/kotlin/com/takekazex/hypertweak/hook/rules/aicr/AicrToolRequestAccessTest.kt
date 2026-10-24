package com.takekazex.hypertweak.hook.rules.aicr

import org.junit.Assert.*
import org.junit.Test

class AicrToolRequestAccessTest {
    class Element(private val raw: String) { override fun toString() = raw }
    class Request(val queryId: String, val toolId: String, val method: String, val clientInfo: String?, val input: Element?)
    private fun access() = AicrToolRequestAccess.resolve(Request::class.java, Element::class.java, ::Element)!!

    @Test fun rewritesTheNativeTypedInputWithoutMutatingTheOriginalRequest() {
        val original = Request("cue.id", "aipanel", "aipanel", null, Element("original"))
        val copy = access().rewrite(original) { "rewritten" } as Request
        assertNotSame(original, copy)
        assertEquals("original", original.input.toString())
        assertEquals("rewritten", copy.input.toString())
        assertEquals(original.queryId, copy.queryId)
        assertEquals(original.toolId, copy.toolId)
        assertEquals(original.method, copy.method)
        assertNull(copy.clientInfo)
    }

    @Test fun otherToolsAndNullInputsDoNotReachTheTransform() {
        val access = access()
        assertNull(access.rewrite(Request("id", "action", "resolve_intent", "client", Element("{}"))) {
            fail("other tools must be untouched"); null
        })
        assertNull(access.rewrite(Request("id", "aipanel", "aipanel", "client", null)) {
            fail("no input"); null
        })
    }

    @Test fun unsupportedOrUnchangedPayloadKeepsTheOriginalObject() {
        assertNull(access().rewrite(Request("id", "aipanel", "aipanel", "client", Element("{}"))) { null })
        assertNull(AicrToolRequestAccess.resolve(String::class.java, Element::class.java, ::Element))
    }
}
