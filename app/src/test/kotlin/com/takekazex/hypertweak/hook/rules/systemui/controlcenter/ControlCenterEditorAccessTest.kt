package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import org.junit.Assert.*
import org.junit.Test

class ControlCenterEditorAccessTest {
    enum class Mode { NORMAL, EDIT }
    class ModeOwner { var mode = Mode.NORMAL }
    class Provider(val owner: ModeOwner) { fun get() = owner }
    open class BaseController(val provider: Provider)
    class Controller(provider: Provider) : BaseController(provider)
    private val access = ControlCenterEditorAccess { null }

    @Test fun cachedInheritedProviderPathReadsTheCurrentModeOnEveryCallback() {
        val owner = ModeOwner()
        val controller = Controller(Provider(owner))
        assertFalse(access.isEditMode(controller))
        owner.mode = Mode.EDIT
        assertTrue(access.isEditMode(controller))
        owner.mode = Mode.NORMAL
        assertFalse(access.isEditMode(controller))
    }

    @Test fun cachedPathNeverRetainsAnotherControllerInstance() {
        val first = Controller(Provider(ModeOwner().apply { mode = Mode.EDIT }))
        val second = Controller(Provider(ModeOwner()))
        assertTrue(access.isEditMode(first))
        assertFalse(access.isEditMode(second))
        assertTrue(access.isEditMode(first))
    }
}
