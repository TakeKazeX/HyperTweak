package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandAnchors
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandContract

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Validate against both APKs: the plugin API also depends on classes supplied by SystemUI. */
class NativeIslandTargetApkTest {
    @Test fun `native recovery uses the deletion refill and rebuild arbitration contracts`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val root = "Lmiui/systemui/dynamicisland/"
        val content = root + "window/content/DynamicIslandContentView;"
        val handler = classes.getValue(root + "event/handler/StateHandler;")
        val fill = handler.methods.single { it.parameterTypes == listOf("Ljava/util/ArrayList;") && it.returnType == content }
        fun calls(type: String) = classes.getValue(type).methods.flatMap { method ->
            method.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }?.toList().orEmpty()
        }
        val admitCall = calls(root + "event/rebuild/RebuildStateKeeper;").filter {
            it.parameterTypes == listOf(content, "Ljava/util/ArrayList;", "Ljava/lang/Boolean;") && it.returnType == "V"
        }.distinctBy { it.toString() }.single()
        val admit = handler.methods.single { it.name == admitCall.name && it.parameterTypes == admitCall.parameterTypes }
        assertTrue(calls(root + "event/DeletedEventCoordinator;").any { it.name == fill.name && it.parameterTypes == fill.parameterTypes && it.returnType == content })
        assertTrue(calls(root + "event/rebuild/RebuildStateKeeper;").any { it.name == admit.name && it.parameterTypes == admit.parameterTypes && it.returnType == "V" })
        val eventConstructor = classes.getValue(root + "event/EventCoordinator;").methods.single { it.name == "<init>" }
        val constructorCalls = eventConstructor.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        assertTrue("native stop owner is AppStateHandler, not a summary slot",
            constructorCalls.any { it.name == "getAppStateHandler" && it.returnType.endsWith("/AppStateHandler;") })
        val window = classes.getValue(root + "window/DynamicIslandWindowView;")
        val inventory = window.methods.single { it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/concurrent/CopyOnWriteArrayList;" }
        val fields = inventory.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? org.jf.dexlib2.iface.reference.FieldReference
        }.toList()
        assertEquals(1, fields.size)
        assertEquals(window.type, fields.single().definingClass)
        assertEquals("Ljava/util/concurrent/CopyOnWriteArrayList;", fields.single().type)
    }

    @Test fun `native measurement owns timer premeasure and real fake camera publication`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val root = "Lmiui/systemui/dynamicisland/"
        val content = classes.getValue(root + "window/content/DynamicIslandContentView;")
        val base = classes.getValue(content.superclass!!)
        val fake = classes.getValue(root + "window/content/DynamicIslandContentFakeView;")
        fun calls(method: org.jf.dexlib2.iface.Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        val measurement = content.methods.single { it.name == "calculateBigIslandWidth" }
        val premeasure = content.methods.single { it.name == "applyPreMeasureMode" }
        assertEquals(listOf("Landroid/view/ViewGroup;", "Z"), premeasure.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", premeasure.returnType)
        assertTrue(calls(premeasure).any { it.name == "enablePreMeasureMode" && it.parameterTypes == listOf("Z") })
        val measurementCalls = calls(measurement)
        val removal = content.methods.single { it.name == "removeByError" }
        assertTrue("measurement can destructively remove a view with no template", measurementCalls.any {
            it.name == removal.name && it.definingClass == content.type })
        val removalCalls = calls(removal)
        assertTrue(removalCalls.any { it.name == "removeAllViews" })
        assertTrue("error removal clears the native state", removalCalls.any { it.name == "setState" &&
            it.parameterTypes == listOf("Lmiui/systemui/dynamicisland/event/DynamicIslandState;") })
        assertEquals("measurement publishes the native layout exactly once", 1,
            measurementCalls.count { it.definingClass == content.type && it.name == "updateBigIslandLayout" &&
                it.parameterTypes.isEmpty() && it.returnType == "V" })
        val modeCalls = measurementCalls.withIndex().filter { it.value.name == premeasure.name }
        assertEquals("native measurement enters and exits premeasure", 2, modeCalls.size)
        assertTrue(measurementCalls.withIndex().any { it.value.name == "measure" &&
            it.index > modeCalls.first().index && it.index < modeCalls.last().index })
        assertTrue(measurementCalls.withIndex().any { it.value.name == "calculateBigIslandWidth" &&
            it.value.definingClass != content.type && it.index > modeCalls.last().index })
        // Hooking this inherited getter must cover the fake callback owner as well.
        assertEquals(base.type, fake.superclass)
        assertTrue("fake width must be available in the runtime registry before hook installation",
            "getSmallIslandViewWidth" in NativeIslandContract.methods.getValue(fake.type.removePrefix("L").removeSuffix(";").replace('/', '.')))
        assertTrue(base.methods.any { it.name == "getSmallIslandViewWidth" &&
            it.parameterTypes.isEmpty() && it.returnType == "I" })
        assertTrue(base.methods.any { it.name == "getFixedCutoutWidth" && it.returnType == "I" })
        assertFalse(fake.methods.any { it.name == "getFixedCutoutWidth" })
        val callback = classes.values.flatMap { it.methods.toList() }.filter { it.name == "onBegin" }
            .single { method -> calls(method).any { it.name == "updateCutoutWidth" } &&
                calls(method).any { it.name == "getFixedCutoutWidth" } }
        val factory = classes.values.flatMap { it.methods.toList() }.single { method ->
            val strings = (method.implementation?.instructions ?: emptyList()).mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.toList()
            "updateCutoutWidth " in strings && method.parameterTypes.size == 3
        }
        val writerCall = calls(factory).filter { it.parameterTypes == listOf("I") && it.returnType == "V" }
            .distinctBy { it.toString() }.single()
        val writer = classes.getValue(writerCall.definingClass).methods.single {
            it.name == writerCall.name && it.parameterTypes == listOf("I")
        }
        val cutoutFields = writer.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? org.jf.dexlib2.iface.reference.FieldReference)
                ?.takeIf { field -> field.type == "Landroid/widget/FrameLayout;" && field.definingClass == writer.definingClass }
        }.distinctBy { it.toString() }.toList()
        assertEquals("one exact builder cutout field", 1, cutoutFields.size)
        assertTrue(calls(callback).any { it.definingClass == factory.definingClass && it.name == factory.name })
        val coordinator = classes.getValue(root + "event/DynamicIslandEventCoordinator;")
        val shadow = coordinator.methods.single { it.name == "setShadow" }
        assertTrue(calls(shadow).any { it.name == "clearShadow" })
        assertTrue("native shadow cleanup is hooked so leased tasks are included",
            "setShadow" in NativeIslandContract.methods.getValue(coordinator.type.removePrefix("L").removeSuffix(";").replace('/', '.')))
        val callbackCalls = calls(callback)
        assertTrue(callbackCalls.indexOfFirst { it.name == "getFixedCutoutWidth" } <
            callbackCalls.indexOfFirst { it.name == "updateCutoutWidth" })
    }

    @Test fun `live whitelist gates preserve verified host signatures`() {
        val paths = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK", "XMSF_AUTH_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        val classes = paths.filterNotNull().flatMap { path ->
            val container = DexFileFactory.loadDexContainer(path, Opcodes.getDefault())
            container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        val manager = classes.getValue("Lcom/miui/systemui/notification/NotificationSettingsManager;")
        for (name in listOf("canShowFocusState", "canShowFocusStateApp")) {
            val method = manager.methods.single { it.name == name && it.parameterTypes.size == 2 }
            assertEquals(listOf("Landroid/content/Context;", "Ljava/lang/String;"), method.parameterTypes.map(CharSequence::toString))
            assertEquals("I", method.returnType)
        }
        val media = classes.getValue("Lmiui/systemui/notification/NotificationSettingsManager;")
            .methods.single { it.name == "mediaIslandSupportMiniWindow" }
        assertEquals(listOf("Ljava/lang/String;"), media.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", media.returnType)
        val session = classes.getValue("Lcom/xiaomi/xms/auth/AuthSession;")
        val publicFinal = session.methods.filter { it.accessFlags and 0x11 == 0x11 && it.returnType == "Landroid/os/Bundle;" }
        val error = publicFinal.single { it.parameterTypes.map(CharSequence::toString) == listOf("Lcom/xiaomi/xms/auth/AuthError;") }
        val success = publicFinal.single { it.parameterTypes.isEmpty() }
        fun strings(method: org.jf.dexlib2.iface.Method) = method.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.toSet()
        assertTrue("result_code" in strings(error))
        assertTrue("Auth is successful" in strings(success))
        assertEquals(1, classes.getValue("Lcom/xiaomi/xms/auth/AuthError;").fields.count { it.type == "I" })
    }

    @Test fun `semantic discovery has unique entries connected to the native render and scheduling graph`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val methods = classes.values.flatMap { it.methods.toList() }
        fun javaType(type: String): String = when (type) {
            "V" -> "void"; "Z" -> "boolean"; "F" -> "float"; "I" -> "int"
            else -> if (type.startsWith("[")) type.replace('/', '.') else type.removePrefix("L").removeSuffix(";").replace('/', '.')
        }
        val entries = NativeIslandAnchors.strings.mapValues { (role, anchors) ->
            val candidates = methods.filter { method ->
                val strings = (method.implementation?.instructions ?: emptyList()).mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }.toList()
                anchors.all { anchor -> strings.any { anchor in it } }
            }
            NativeIslandAnchors.select(role, candidates,
                { it.parameterTypes.map { type -> javaType(type.toString()) } }, { javaType(it.returnType) })
        }
        val tween = entries.getValue("widthTween")
        assertTrue(classes.getValue(tween.definingClass).methods.any { it.name == "setTo" &&
            it.parameterTypes == tween.parameterTypes && it.returnType == tween.returnType })
        val transaction = entries.getValue("transaction")
        val animation = entries.getValue("animation")
        val content = transaction.parameterTypes[1].toString()
        assertEquals(content, entries.getValue("contentMeasure").definingClass)
        assertEquals(listOf(content), animation.parameterTypes.map(CharSequence::toString))
        val transitions = entries.getValue("transitions")
        val calls = animation.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        assertTrue(calls.any { it.definingClass == transitions.definingClass && it.name == transitions.name &&
            it.parameterTypes == transitions.parameterTypes && it.returnType == transitions.returnType })
        val coordinator = classes.getValue(transaction.definingClass)
        assertEquals(1, coordinator.fields.count { it.type == "Ljava/util/ArrayList;" })
        val gesture = classes.getValue("Lmiui/systemui/dynamicisland/anim/ui/animator/IslandGestureAnimator;")
        assertEquals(1, gesture.fields.count { it.type == classes.getValue(content).superclass })
        val press = gesture.methods.single { it.name == "onPressBigIsland" }
        val reset = gesture.methods.single { it.name == "resetPressBigIsland" }
        assertEquals(listOf(content), press.parameterTypes.map(CharSequence::toString))
        assertEquals(listOf(content), reset.parameterTypes.map(CharSequence::toString))
        val full = classes.getValue("Lmiui/systemui/dynamicisland/anim/ui/animator/IslandAnimStateFactory;")
            .methods.single { it.name == "createBigIslandProperties" }
        assertTrue(full.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.any { it.name == "getBigIslandStart" })
        // Full-template exit deferral: one per-view delegate and one lifecycle dispatcher.
        val base = classes.getValue(content).superclass
        val delegate = classes.getValue(classes.getValue(animation.definingClass).methods.single {
            it.name == "getAnimator" && it.parameterTypes.map(CharSequence::toString) == listOf(base)
        }.returnType)
        assertEquals(1, delegate.fields.count { it.type == base && !AccessFlags.STATIC.isSet(it.accessFlags) })
        fun lifecycleEnum(type: String) = classes[type]?.let { enum ->
            AccessFlags.ENUM.isSet(enum.accessFlags) &&
                enum.staticFields.map { it.name }.containsAll(listOf("ANIM_START", "ANIM_FINISH", "ANIM_CANCEL"))
        } == true
        assertEquals(1, delegate.methods.count { method ->
            val types = method.parameterTypes.map(CharSequence::toString)
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && types.size == 3 &&
                types[1] == "Ljava/lang/String;" && lifecycleEnum(types[2])
        })
        // Status widths: one instance writer stores maximum, clock and battery together.
        assertEquals(1, classes.getValue(checkNotNull(base)).methods.count { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("F", "F", "F")
        })
    }

    @Test fun `verified host exposes the complete scheduling measurement and touch contract`() {
        val paths = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        val classes = paths.filterNotNull().flatMap { path ->
            val container = DexFileFactory.loadDexContainer(path, Opcodes.getDefault())
            container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        fun type(name: String) = "L${name.replace('.', '/')};"
        fun hierarchy(name: String): Sequence<ClassDef> = generateSequence(classes.getValue(type(name))) {
            classes[it.superclass]
        }
        NativeIslandContract.methods.forEach { (name, members) ->
            members.forEach { member ->
                val targets = hierarchy(name).flatMap { it.methods.asSequence() }.filter { it.name == member }
                    .distinctBy { it.parameterTypes.map(CharSequence::toString) }.toList()
                assertEquals("$name.$member must resolve unambiguously", 1, targets.size)
            }
        }
        val root = "miui.systemui.dynamicisland."
        listOf("BigIsland", "SmallIsland", "Hidden").forEach { state ->
            assertTrue(classes.getValue(type(root + "event.DynamicIslandState\$$state")).methods.any {
                it.name == "<init>" && it.parameterTypes.isEmpty()
            })
        }
        listOf("SwipeLeft", "SwipeRight").forEach { event ->
            assertTrue(type(root + "event.DynamicIslandEvent\$$event") in classes)
        }
        val swipe = classes.getValue(type(root + "event.SwipeEventCoordinator"))
        mapOf(
            "handleAppEvent" to listOf(type(root + "event.DynamicIslandEvent"), type(root + "window.content.DynamicIslandContentView"), "Ljava/util/ArrayList;"),
            "handleSwipe" to listOf("F", "F", "Z", "Ljava/util/ArrayList;"),
            "resetSwipe" to listOf("F", "F", "Ljava/util/ArrayList;")
        ).forEach { (name, parameters) ->
            assertEquals(parameters, swipe.methods.single { it.name == name }.parameterTypes.map(CharSequence::toString))
        }
        val window = classes.getValue(type(root + "window.DynamicIslandWindowView"))
        assertEquals(listOf("F"), window.methods.single { it.name == "isSwipeTowardsSmallIsland" }.parameterTypes.map(CharSequence::toString))
        val hidden = classes.getValue(type(root + "event.handler.HiddenStateHandler"))
        assertEquals(listOf(type(root + "window.content.DynamicIslandContentView"), "Ljava/lang/Boolean;"),
            hidden.methods.single { it.name == "enqueueHiddenStateCallback" }.parameterTypes.map(CharSequence::toString))
        mapOf(
            "event.DynamicIslandEventCoordinator" to listOf("hiddenList"),
            "event.handler.StateHandler" to listOf("newList"),
            "anim.ui.animator.IslandCoordinateCalculator" to listOf("view"),
            "touch.domain.interactor.DynamicIslandTouchInteractor" to listOf("windowView")
        ).forEach { (name, fields) ->
            val declared = classes.getValue(type(root + name)).fields.map { it.name }
            fields.forEach { assertTrue("$name.$it", it in declared) }
        }
        val handler = classes.getValue(type(root + "event.handler.StateHandler"))
        val stop = handler.methods.single { it.name == "stop" }
        val calls = stop.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        assertTrue(calls.any { it.name == "updateState" && it.parameterTypes == listOf(type(root + "window.content.DynamicIslandContentView")) })
        val coordinator = classes.getValue(type(root + "event.DynamicIslandEventCoordinator"))
        val handle = coordinator.methods.single { it.name == "handleEvent" }
        assertEquals(listOf(type(root + "event.DynamicIslandEvent"), type(root + "window.content.DynamicIslandContentView")),
            handle.parameterTypes.map(CharSequence::toString))
        val result = classes.getValue(type(root + "model.IslandContentViewCalculationResult"))
        assertTrue(result.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == List(9) { "I" } })
        // stop() publishes the state synchronously; a changed asynchronous protocol needs rework.
        assertEquals("V", stop.returnType)
    }

    @Test fun `template side budgets and timer holder width pass are reachable from the content view`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val root = "Lmiui/systemui/dynamicisland/"
        val base = classes.getValue(root + "window/content/DynamicIslandBaseContentView;")
        fun single(owner: ClassDef, name: String, vararg parameters: String) = owner.methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters.toList()
        }
        val controller = classes.getValue(single(base, "getController").returnType)
        val factory = classes.getValue(single(controller, "getIslandTemplateFactory").returnType)
        assertEquals(root + "template/IslandTemplateFactory;", factory.type)
        val data = "Lcom/android/systemui/plugins/miui/dynamicisland/DynamicIslandData;"
        listOf("updateLeftWidth", "updateRightWidth").forEach {
            assertEquals("V", single(factory, it, data, "I", "Z").returnType)
        }
        // A font swap outside the native pass re-runs these two public entry points.
        val holder = classes.getValue(root + "module/BaseIslandModuleViewHolder;")
        assertEquals("V", single(holder, "updateWidth", "I").returnType)
        assertEquals("Landroid/view/View;", single(holder, "getView").returnType)
        val measure = single(base, "calculateBigIslandWidth")
        assertTrue(AccessFlags.PUBLIC.isSet(measure.accessFlags))
        val content = classes.getValue(root + "window/content/DynamicIslandContentView;")
        val applies = single(content, "calculateBigIslandWidth").implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }
        assertTrue(applies.any { it.name == "updateBigIslandLayout" && it.parameterTypes.isEmpty() })
    }

    @Test fun `pull-down frames and the media slide offset flow through the fake view contract`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val root = "Lmiui/systemui/dynamicisland/window/content/"
        val fake = classes.getValue(root + "DynamicIslandContentFakeView;")
        fun calls(method: org.jf.dexlib2.iface.Method) = method.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        fun method(owner: ClassDef, name: String, vararg parameters: String) = owner.methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters.toList()
        }
        val params = root + "FakeViewTrackingParams;"
        assertEquals(params, fake.fields.single { it.name == "mFakeViewTrackingParams" }.type)
        assertEquals("I", fake.fields.single { it.name == "mFakeViewHeight" }.type)
        // Every frame (drag and Folme rebound) publishes the grown outline from these parameters.
        val updated = method(fake, "onFakeViewTrackingParamsUpdated")
        assertTrue(calls(updated).map { it.name }.containsAll(listOf("getTop", "getBottom", "updateTrack")))
        val rebound = classes.getValue(root + "DynamicIslandContentFakeView\$mAnimConfig\$1;")
        assertTrue(calls(method(rebound, "onUpdate", "Ljava/lang/Object;", "Ljava/util/Collection;"))
            .any { it.name == "access\$onFakeViewTrackingParamsUpdated" })
        assertTrue(calls(method(fake, "onTrackingFakeViewUpdate", "F")).any { it.name == "setAlpha" && it.definingClass == params })
        // The tracker's only payload is the slide offset the media binder applies to its background.
        val tracker = classes.getValue(root + "DynamicIslandContentFakeView\$ExpandedTracker;")
        val strings = method(tracker, "updateTrack", "F").implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
        assertTrue("pull_down_action_offset_y" in strings)
        listOf("onTrackingFakeViewStart", "onTrackingFakeViewReset", "resetFakeViewState", "onDetachedFromWindow")
            .forEach { assertEquals("V", method(fake, it).returnType) }
        assertEquals("V", method(fake, "setVisibility", "I").returnType)
        val base = classes.getValue(root + "DynamicIslandBaseContentView;")
        assertEquals("Landroid/widget/FrameLayout;", method(base, "getFakeExpandedView").returnType)
        val controller = classes.getValue("Lmiui/systemui/dynamicisland/anim/DynamicIslandAnimationController;")
        assertTrue(AccessFlags.STATIC.isSet(method(controller, "setMiSelfBlurCompat", "Landroid/view/View;", "I").accessFlags))
        val blur = classes.getValue("Lmiui/systemui/util/MiBlurCompat;")
        assertTrue(AccessFlags.STATIC.isSet(method(blur, "getBackgroundMaterialOpened", "Landroid/content/Context;").accessFlags))
    }

    @Test fun `the anim bridge listener resumes paused island Lottie only behind its flag`() {
        val path = System.getenv("SYSTEMUI_PLUGIN_APK")?.let(::File)
        assumeTrue(path?.isFile == true)
        val container = DexFileFactory.loadDexContainer(path!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val root = "Lmiui/systemui/dynamicisland/"
        val listener = classes.getValue(root + "event/DynamicIslandEventCoordinator\$initAnimEventBridge\$1;")
        assertTrue(root + "anim/ui/viewbinder/AnimEventBridge\$Listener;" in listener.interfaces)
        val fields = listener.fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
        assertEquals(listOf("lottiePausedByAnim"), fields.filter { it.type == "Z" }.map { it.name })
        assertEquals("Ljava/util/Set;", fields.single { it.name == "pausedLottieKeys" }.type)
        assertEquals("Ljava/util/Map;", fields.single { it.name == "pendingLottiePauseMap" }.type)
        fun method(name: String, event: String) = listener.methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(root + "anim/model/AnimEvent\$$event;")
        }
        fun instructions(name: String, event: String) = method(name, event).implementation!!.instructions.toList()
        fun refs(name: String, event: String) = instructions(name, event).mapNotNull { (it as? ReferenceInstruction)?.reference }
        fun flagWrites(name: String, event: String) = instructions(name, event).count {
            it.opcode == org.jf.dexlib2.Opcode.IPUT_BOOLEAN &&
                ((it as ReferenceInstruction).reference as org.jf.dexlib2.iface.reference.FieldReference).name == "lottiePausedByAnim"
        }
        // Start pauses pending keys and writes the flag on both branches, including the empty-pending one.
        assertTrue(refs("onAnimTrulyStarted", "Started").any { it is MethodReference && it.name == "pauseForIslandAnim" })
        assertEquals(2, flagWrites("onAnimTrulyStarted", "Started"))
        // Only the two end events resume, and each clears the flag after resuming the paused keys.
        listOf("onAnimTrulyFinished" to "Finished", "onAnimCancelled" to "Cancelled").forEach { (name, event) ->
            assertEquals("V", method(name, event).returnType)
            assertTrue(refs(name, event).any { it is MethodReference && it.name == "resumeAfterIslandAnim" })
            assertEquals(1, flagWrites(name, event))
        }
    }
}
