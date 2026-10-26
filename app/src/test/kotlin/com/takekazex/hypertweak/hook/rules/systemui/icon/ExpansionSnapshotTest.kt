package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class ExpansionSnapshotTest {
    class Flow(private val value: Any) { fun getValue(): Any = value }
    class Provider(private val value: Any) { fun get(): Any = value }
    class Component(delegate: Any) {
        private val controlCenterExpandControllerDelegateProvider = Provider(delegate)
    }
    class Delegate {
        private val expansionState = Flow(.5f)
        private val visibleState = Flow(true)
        private val stretchHeightState = Flow(12f)
    }
    class MalformedDelegate {
        private val expansionState = Flow("bad")
        private val visibleState = Flow(1)
    }

    @Test fun readsTheNativeDelegateWithoutRetainingOrRewritingItsValues() {
        val delegate = Delegate()
        val expected = IconTunerFlows.ExpansionSnapshot(.5f, true, 12f)
        assertEquals(expected, IconTunerFlows.readExpansionSnapshot(Component(delegate)))
        assertEquals(expected, IconTunerFlows.readExpansionSnapshot(Component(delegate)))
    }

    @Test fun absentOrMalformedHostsPreserveUnknownValues() {
        val unknown = IconTunerFlows.ExpansionSnapshot(null, null, null)
        assertEquals(unknown, IconTunerFlows.readExpansionSnapshot(null))
        assertEquals(unknown, IconTunerFlows.readExpansionSnapshot(Any()))
        assertEquals(unknown, IconTunerFlows.readExpansionSnapshot(Component(MalformedDelegate())))
    }
}
