package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Operand evidence for the baseline APK, not a version table for runtime resolution. */
class IconInfrastructureHostContractTest {
    private fun host(): DexOperandIndex {
        val file = System.getenv("SYSTEMUI_APK")?.let(::File)
        assumeTrue("Set SYSTEMUI_APK to the baseline APK", file?.isFile == true)
        val hash = MessageDigest.getInstance("SHA-256").digest(file!!.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("dc849ade5543ed6452020f7bbb2a165cd4da77a20c46618d072f8a23cebcb555", hash)
        return DexOperandIndex.fixture(file.path)
    }

    @Test fun carrierHidingUsesPhysicalSlotsAndIndependentKeyguardLayouts() {
        val dex = host()
        val row = "Lcom/android/systemui/controlcenter/shade/ControlCenterCarrierText;"
        val callback = "Lcom/android/systemui/controlcenter/shade/ControlCenterCarrierText\$mCarrierTextCallback\$1;"
        val slot = dex.code(dex.definition("${callback}->getCarrierSlotId()I")!!)
        assertTrue(slot.reads.any { it.definingClass == row && it.name == "innerCarrierSlotId" && it.type == "I" })
        val keyguard = dex.code(dex.definition("${row}->setKeyguardLayout(Z)V")!!)
        assertTrue(keyguard.writes.any { it.first.definingClass == row && it.first.name == "isKeyguardLayout" && it.first.type == "Z" })
        val update = dex.code(dex.definition("${callback}->onCarrierTextChanged(ILjava/lang/String;I)V")!!)
        assertTrue(update.reads.any { it.definingClass == row && it.name == "carrierTextView" && it.type == "Landroid/widget/TextView;" })
        assertTrue(update.calls.any { it.method.name == "setVisibility" })
        val shown = dex.code(dex.definition("${row}->shouldShow()Z")!!)
        assertTrue(shown.reads.any { it.definingClass == row && it.name == "carrierTextView" })
        assertTrue(shown.calls.any { it.method.name == "getVisibility" })
    }

    @Test fun panelExpansionAndSwitchingDriveTheNativeSurfaces() {
        val dex = host()
        val callback = "Lcom/android/systemui/controlcenter/shade/ControlCenterHeaderExpandController\$controlCenterCallback\$1;"
        val expansion = dex.definition("${callback}->onExpansionChanged(F)V")!!
        val code = dex.code(expansion)
        assertTrue(code.reads.map { it.type }.containsAll(listOf(
            "Lcom/android/systemui/controlcenter/phone/widget/ControlCenterStatusBarIcon;",
            "Lcom/android/systemui/controlcenter/phone/widget/ControlCenterFakeStatusIcons;")))
        assertTrue(code.calls.any { it.method.name == "setTranslationY" && it.method.parameterTypes.map(CharSequence::toString) == listOf("F") })
        listOf("onVisibleChanged(Z)V", "onStretchHeightChanged(F)V", "onAppearanceChanged(ZZ)V").forEach {
            assertNotNull(dex.definition("$callback->$it"))
        }
        val header = "Lcom/android/systemui/controlcenter/shade/CombinedHeaderController;"
        val switching = dex.definition("${header}->onSwitchingChanged(Z)V")!!
        assertTrue(dex.code(switching).writes.any { it.first.definingClass == header && it.first.type == "Z" })
        val progress = dex.definition("${header}->onSwitchProgressChanged(F)V")!!
        assertTrue(dex.code(progress).reads.any { it.type == "Lcom/android/systemui/controlcenter/phone/widget/ControlCenterStatusBarIcon;" })
    }

    @Test fun livingPanelCallbacksAndContainerRolesCanBeRecoveredWithoutAHostStep() {
        val dex = host()
        val delegate = "Lcom/miui/systemui/controlcenter/container/ControlCenterExpandControllerDelegate;"
        val expansion = dex.code(dex.definition("${delegate}->onExpansionChanged(F)V")!!)
        assertTrue(expansion.reads.any { it.name == "callbacks" && it.type == "Ljava/util/ArrayList;" })
        assertTrue(expansion.calls.any { it.method.name == "onExpansionChanged" &&
            it.method.definingClass == "Lcom/android/systemui/plugins/miui/shade/PanelExpandController\$Callback;" })
        val container = "Lcom/android/systemui/statusbar/views/MiuiStatusIconContainer;"
        mapOf(
            "Lcom/android/systemui/statusbar/phone/MiuiPhoneStatusBarView;->onAttachedToWindow()V" to 0L,
            "Lcom/android/systemui/statusbar/phone/KeyguardStatusBarViewController;->onViewAttached()V" to 1L,
            "Lcom/android/systemui/controlcenter/phone/widget/ControlCenterFakeStatusIcons;->onFinishInflate()V" to 5L,
            "Lcom/android/systemui/controlcenter/phone/widget/ControlCenterStatusBarIcon;->onFinishInflate()V" to 6L
        ).forEach { (method, role) ->
            val call = dex.code(dex.definition(method)!!).calls.single {
                it.method.definingClass == container && it.method.name == "updateLayoutFrom"
            }
            assertEquals(method, DexOperandIndex.Value.Number(role), call.arguments.last())
        }
    }

    @Test fun mobileFactoryAndWifiGetterHaveTheirExpectedDataFlow() {
        val dex = host()
        val adapter = dex.definition("Lcom/android/systemui/statusbar/pipeline/mobile/ui/MobileUiAdapter;->start()V")!!
        assertTrue(dex.code(adapter).calls.any { it.method.name == "getFilteredSubscriptions" })
        assertTrue(dex.code(adapter).calls.any { it.method.name == "alwaysCollectFlow" })
        val mobile = "Lcom/android/systemui/statusbar/pipeline/mobile/ui/viewmodel/MobileIconsViewModel;"
        val factory = dex.definition("${mobile}->createViewModel(I)Lkotlin/Triple;")!!
        val calls = dex.code(factory).calls.map { it.method }
        assertTrue(calls.any { it.name == "getMobileConnectionInteractorForSubId" && it.parameterTypes.map(CharSequence::toString) == listOf("I") })
        assertTrue(calls.any { it.name == "getCellProvider" && it.returnType.endsWith("MiuiCellularIconVM;") })
        val wifi = "Lcom/android/systemui/statusbar/pipeline/wifi/ui/viewmodel/WifiViewModel;"
        val getter = dex.definition("${wifi}->getWifiIcon()Lkotlinx/coroutines/flow/StateFlow;")!!
        assertTrue(dex.code(getter).reads.any { it.definingClass == wifi && it.type == "Lkotlinx/coroutines/flow/ReadonlyStateFlow;" })
    }
    @Test fun nativeContainerAdmissionUsesTheBindingModelRatherThanViewVisibility() {
        val dex = host()
        val container = "Lcom/android/systemui/statusbar/views/MiuiStatusIconContainer;"
        for (signature in listOf("onMeasure(II)V", "onLayout(ZIIII)V")) {
            val code = dex.code(dex.definition("$container->$signature")!!)
            assertTrue(signature, code.calls.any { it.method.name == "isIconVisible" &&
                it.method.definingClass == "Lcom/android/systemui/statusbar/StatusIconDisplayable;" })
            if (signature.startsWith("onMeasure")) assertFalse(code.calls.any { it.method.name == "getVisibility" })
        }
        val shared = "Lcom/android/systemui/statusbar/pipeline/shared/ui/view/ModernStatusBarView;"
        val predicate = dex.code(dex.definition("${shared}->isIconVisible()Z")!!)
        assertTrue(predicate.calls.any { it.method.name == "getShouldIconBeVisible" && it.method.returnType == "Z" })
        assertTrue(predicate.reads.any { it.name == "removeFlag" && it.type == "Z" })
        assertFalse(predicate.calls.any { it.method.name == "getVisibility" })
    }

    @Test fun controllerContainerAndObserverConnectSlotsToHostVisibility() {
        val dex = host()
        val controller = "Lcom/android/systemui/statusbar/phone/ui/StatusBarIconControllerImpl;"
        val add = dex.definition("${controller}->addIconGroup(Lcom/android/systemui/statusbar/phone/ui/IconManager;)V")!!
        val calls = dex.code(add).calls.map { it.method }
        assertTrue(calls.any { it.name == "getViewIndex" && it.definingClass.endsWith("StatusBarIconList;") })
        assertTrue(calls.any { it.name == "onIconAdded" && it.definingClass.endsWith("IconManager;") })
        val manager = "Lcom/android/systemui/statusbar/phone/ui/IconManager;"
        val added = dex.definition("Lcom/android/systemui/statusbar/phone/MiuiLightDarkIconManager;->onIconAdded(ILjava/lang/String;ZLcom/android/systemui/statusbar/phone/StatusBarIconHolder;)V")!!
        assertTrue(dex.code(added).calls.any { it.method.definingClass == manager && it.method.name == "addHolder" })
        val container = "Lcom/android/systemui/statusbar/views/MiuiStatusIconContainer;"
        listOf("onMeasure(II)V", "onLayout(ZIIII)V").forEach { signature ->
            val method = dex.definition("$container->$signature")!!
            assertTrue(dex.code(method).reads.any { it.definingClass == container && it.name == "ignoredSlots" && it.type == "Ljava/util/List;" })
        }
        val observer = "Lcom/android/systemui/statusbar/policy/StatusBarIconObserver;"
        val load = dex.definition("${observer}->loadStatusBarIcon()Ljava/lang/String;")!!
        assertTrue(dex.code(load).reads.any { it.definingClass == observer && it.name == "mCurrentUserId" && it.type == "I" })
        assertTrue(dex.code(load).calls.any { it.method.definingClass == "Landroid/provider/Settings\$System;" && it.method.name == "getStringForUser" })
    }

    @Test fun batteryAndCarrierCallbacksReachTheirVisibleChildren() {
        val dex = host()
        val battery = "Lcom/android/systemui/statusbar/views/MiuiBatteryMeterView;"
        val level = dex.definition("${battery}->onBatteryLevelChanged(IZZ)V")!!
        assertTrue(dex.code(level).writes.any { it.first.definingClass == battery && it.first.name == "mLevel" && it.first.type == "I" })
        assertTrue(dex.code(level).calls.any { it.method.name == "updateChargeAndText" && it.method.definingClass == battery })
        val style = dex.definition("${battery}->onBatteryStyleChanged(I)V")!!
        assertTrue(dex.code(style).reads.any { it.definingClass == battery && it.name == "mBatteryDigitalView" })
        assertTrue(dex.code(style).calls.any { it.method.name == "setVisibility" })
        val carrier = "Lcom/android/systemui/controlcenter/shade/MiuiCarrierTextLayout;"
        val shouldShow = dex.definition("${carrier}->getCarrierTextShouldShow()Z")!!
        assertEquals(2, dex.code(shouldShow).reads.filter { it.type == "Lcom/android/systemui/controlcenter/shade/ControlCenterCarrierText;" }.map { it.name }.distinct().size)
        assertTrue(dex.code(shouldShow).calls.any { it.method.name == "shouldShow" && it.method.returnType == "Z" })
    }

    @Test fun modernBindersAttachFlowCollectorsToTheHostViews() {
        val dex = host()
        val mobile = "Lcom/android/systemui/statusbar/pipeline/mobile/ui/view/ModernStatusBarMobileView;"
        val construct = dex.declared(mobile).single { it.name == "constructAndBind" }
        assertEquals(mobile, construct.returnType)
        assertTrue(dex.code(construct).calls.any { it.method.name == "getSubscriptionId" && it.method.returnType == "I" })
        assertTrue(dex.code(construct).calls.any { it.method.name == "setSubId" && it.method.definingClass == mobile })
        val binders = listOf(
            "Lcom/android/systemui/statusbar/pipeline/mobile/ui/binder/MiuiMobileIconBinder;" to listOf(
                "Landroid/view/ViewGroup;", "Lcom/android/systemui/statusbar/pipeline/mobile/ui/viewmodel/LocationBasedMobileViewModel;",
                "Lcom/android/systemui/statusbar/pipeline/mobile/ui/viewmodel/MiuiMobileIconViewModel;", "Lcom/android/systemui/statusbar/pipeline/mobile/ui/MobileViewLogger;"),
            "Lcom/android/systemui/statusbar/pipeline/wifi/ui/binder/MiuiWifiViewBinder;" to listOf(
                "Landroid/view/ViewGroup;", "Lcom/android/systemui/statusbar/pipeline/wifi/ui/viewmodel/LocationBasedWifiViewModel;")
        )
        binders.forEach { (owner, params) ->
            val bind = dex.declared(owner).single { it.name == "bind" && it.parameterTypes.map(CharSequence::toString) == params }
            assertTrue(dex.code(bind).calls.any { it.method.name == "repeatWhenAttached\$default" })
        }
    }

    @Test fun headerAndClassicIconAdaptersReachConcreteHostViews() {
        val dex = host()
        val header = "Lcom/android/systemui/controlcenter/shade/ControlCenterHeaderController;"
        val date = dex.definition("${header}->updateDateVisibility()V")!!
        assertTrue(dex.code(date).reads.any { it.definingClass == header && it.name == "dateView" && it.type == "Landroid/widget/TextView;" })
        assertTrue(dex.code(date).calls.any { it.method.name == "setVisibility" && it.method.parameterTypes.map(CharSequence::toString) == listOf("I") })
        val qs = "Lcom/android/systemui/qs/MiuiQSHeaderView;"
        val inflate = dex.definition("${qs}->onFinishInflate()V")!!
        assertTrue(dex.code(inflate).writes.map { it.first.type }.containsAll(listOf(
            "Lcom/android/systemui/statusbar/views/MiuiBatteryMeterView;",
            "Lcom/android/systemui/controlcenter/shade/MiuiCarrierTextLayout;",
            "Lcom/android/systemui/statusbar/views/MiuiStatusIconContainer;")))
        assertTrue(dex.code(inflate).calls.any { it.method.name == "findViewById" })
        val icon = "Lcom/android/systemui/statusbar/StatusBarIconView;"
        val set = dex.definition("${icon}->set(Lcom/android/internal/statusbar/StatusBarIcon;)Z")!!
        assertTrue(dex.code(set).calls.any { it.method.definingClass == icon && it.method.name == "updateDrawable" })
        val list = "Lcom/android/systemui/statusbar/phone/ui/StatusBarIconList;"
        val index = dex.definition("${list}->getViewIndex(ILjava/lang/String;)I")!!
        assertTrue(dex.code(index).calls.any { it.method.definingClass == list && it.method.name == "findOrInsertSlot" })
    }

}
