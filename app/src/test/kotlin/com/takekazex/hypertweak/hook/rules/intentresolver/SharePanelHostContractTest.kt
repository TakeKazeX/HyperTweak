package com.takekazex.hypertweak.hook.rules.intentresolver

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the installed vendor implementation, including library members removed by R8. */
class SharePanelHostContractTest {
    @Test fun installedHostUsesOneReadOnlyOverflowGateForCountAndBinding() {
        val path = System.getenv("SHARE_PANEL_APK")
        assumeTrue("Set SHARE_PANEL_APK to verify the actual host", path != null)
        val dex = DexOperandIndex.fixture(path!!)
        val group = "Lcom/android/intentresolver/adapter/GroupTargetAdapter;"
        val miui = "Lcom/android/intentresolver/adapter/MiuiChooserGridAdapter;"
        val grid = "Lcom/android/intentresolver/grid/ChooserGridAdapter;"
        val stub = "Lcom/android/intentresolver/ChooserActivityStubImpl;"
        val gate = "$group->isCollapsedWithOverflow()Z"
        val gateCode = dex.code(gate)
        assertEquals(1, dex.declared(group).count { DexOperandIndex.descriptor(it) == gate })
        assertTrue(gateCode.writes.isEmpty())
        assertTrue(gateCode.calls.any { it.method.name == "isGroupTargetExpanded" && it.method.returnType == "Z" })
        assertTrue(gateCode.calls.any { it.method.name == "getGroupedCollapsedLimit" && it.method.returnType == "I" })
        assertTrue(gateCode.reads.any { it.name == "mGroupedTargets" && it.type == "Ljava/util/List;" })
        listOf("$miui->getGroupTargetCount()I",
            "$group->onBindGroupTargetItem(Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;I)V").forEach { consumer ->
            assertTrue(consumer, dex.code(consumer).calls.any { DexOperandIndex.descriptor(it.method) == gate })
        }
        assertTrue(dex.code("$miui->getGroupTargetCount()I").reads.any { it.name == "mGroupedTargets" })
        val expandedFlag = dex.classDefinition(stub)!!.fields.single { it.name == "mGroupTargetExpanded" }
        assertEquals("Z", expandedFlag.type)
        assertTrue(dex.code("$stub->isGroupTargetExpanded()Z").reads.any { it.name == expandedFlag.name })
        assertEquals(grid, dex.classDefinition(group)!!.fields.single { it.name == "mChooserGridAdapter" }.type)
        val recycler = dex.classDefinition("Landroidx/recyclerview/widget/RecyclerView;")!!
        assertEquals("Landroidx/recyclerview/widget/RecyclerView\$Adapter;",
            recycler.fields.single { it.name == "mAdapter" }.type)
        assertTrue(dex.declared(grid).any { it.name == "notifyDataSetChangedSafely" && it.parameterTypes.isEmpty() && it.returnType == "V" })
        val notifyCode = dex.code("$grid->notifyDataSetChangedSafely()V")
        assertTrue(notifyCode.calls.any { it.method.name == "isComputingLayout" })
        assertTrue(notifyCode.calls.any { it.method.name == "notifyDataSetChanged" })
    }
}
