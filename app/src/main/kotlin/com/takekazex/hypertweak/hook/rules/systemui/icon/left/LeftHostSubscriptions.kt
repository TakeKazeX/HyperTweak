package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.view.ViewGroup
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.ViewVisibilityRouter
import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderComponent
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.util.logging.DebugLog

/** Registers left-renderer callbacks through persistent host adapters. */
internal class LeftHostSubscriptions(owner: BaseHooker) : IconRenderComponent(owner) {
    fun install() {
        with(LeftRenderer) {
        retiring = false
        IconTunerFlows.init(classLoader)

        val managerBaseClass = ICON_MANAGER_BASE_CLASS.toClassOrNull()
        if (managerBaseClass == null) {
            DebugLog.hookSkipped(TAG, ICON_MANAGER_BASE_CLASS, "class not found")
            return
        }
        val groupField = hierarchyField(managerBaseClass, "mGroup")
        if (groupField == null) {
            DebugLog.hookSkipped(TAG, "$ICON_MANAGER_BASE_CLASS#mGroup", "field not found")
            return
        }

        if (!cloner.prepare()) return

        // 1. Capture the home manager when the status bar view receives it.
        val statusBarViewClass = STATUS_BAR_VIEW_CLASS.toClassOrNull()
        if (statusBarViewClass == null) {
            DebugLog.hookSkipped(TAG, STATUS_BAR_VIEW_CLASS, "class not found")
            return
        }
        statusBarViewClass.findMethodOrNull { name("setDarkIconManager"); paramCount(1) }
            ?.let { method ->
                method.observe {
                    after { param ->
                        val root = param.thisObject as? ViewGroup ?: return@after
                        val manager = param.args.getOrNull(0) ?: return@after
                        synchronized(states) {
                            if (states.containsKey(manager)) return@after
                            captureManager(root, manager, groupField)?.also {
                                it.islandHandler = resolveIslandHandler(root)
                                it.islandShowing = readIslandShowing(it.islandHandler)
                            } ?: return@after
                        }
                        reconcileAll()
                    }
                }
            }
            ?: DebugLog.hookSkipped(TAG, "$STATUS_BAR_VIEW_CLASS#setDarkIconManager", "method not found")

        // 2. Remember the pristine system block list on every binder emission, and immediately
        //    re-apply system ∪ selected so the right cluster hides the selected slots.
        //    (setBlockList is declared as final on the BASE IconManager; resolve it there.)
        val managerClass = ICON_MANAGER_CLASS.toClassOrNull()
        if (managerClass == null) {
            DebugLog.hookSkipped(TAG, ICON_MANAGER_CLASS, "class not found")
            return
        }
        val blockListOwner = ICON_MANAGER_BASE_CLASS.toClassOrNull()
        if (blockListOwner == null) {
            DebugLog.hookSkipped(TAG, ICON_MANAGER_BASE_CLASS, "class not found")
            return
        }
        // The block list is no longer written from here: `IconManagerHooker` owns that single merge
        // path (slot modes first, then this hooker's owned slots per host location). This hook only
        // had to observe the emission to remember the pristine list, which the policy hook already
        // tracks itself — writing it from here used to bypass the slot modes entirely.

        // 3. Re-sync the left clones whenever the bar changes (slot scan, no indices).
        managerClass.findMethodOrNull {
            name("onIconAdded"); paramCount(4)
        }?.let { method ->
            deoptimize(method)
            method.observe {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onIconAdded", "method not found")

        managerClass.findMethodOrNull { name("onSetIcon"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.observe {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onSetIcon", "method not found")

        managerClass.findMethodOrNull { name("onRemoveIcon"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.observe {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onRemoveIcon", "method not found")

        // The lockscreen uses MiuiLightDarkIconManager, which overrides the event callbacks on a
        // different concrete class from the home DarkIconManager. Hook those overrides as well so
        // the clone lifecycle is independent for the two icon groups.
        KEYGUARD_ICON_MANAGER_CLASS.toClassOrNull()?.let { keyguardManagerClass ->
            hookManagerEvents(keyguardManagerClass)
        }

        // Capture the lockscreen manager only after KeyguardStatusBarViewController has completed
        // onViewAttached(): that is when mTintedIconManager and its group are initialized.
        KEYGUARD_VIEW_CONTROLLER_CLASS.toClassOrNull()?.let { controllerClass ->
            controllerClass.findMethodOrNull { name("onViewAttached"); noParams() }.orSkip("LeftRenderer", "controllerClass#onViewAttached")?.observe {
                after { param ->
                    runCatching { keyguardCapture.captureController(param.thisObject) }
                        .onFailure { DebugLog.w(TAG, "lockscreen state capture failed", it) }
                    reconcileAll()
                }
            } ?: DebugLog.hookSkipped(TAG, "$KEYGUARD_VIEW_CONTROLLER_CLASS#onViewAttached", "method not found")
            controllerClass.findMethodOrNull { name("onViewDetached"); noParams() }.orSkip("LeftRenderer", "controllerClass#onViewDetached")?.observe {
                before { param ->
                    runCatching { keyguardCapture.detachController(param.thisObject) }
                        .onFailure { DebugLog.w(TAG, "lockscreen teardown failed", it) }
                }
                after {
                    // Unlock can only detach the keyguard layer while leaving the home root
                    // attached. Give the home clone state an immediate hand-off instead of
                    // waiting for the periodic ticker to notice the changed keyguard state.
                    mainHandler.post {
                        if (retiring) return@post
                        runCatching { reconcileAll() }
                            .onFailure { DebugLog.w(TAG, "unlock left-container reconcile failed", it) }
                    }
                }
            } ?: DebugLog.hookSkipped(TAG, "$KEYGUARD_VIEW_CONTROLLER_CLASS#onViewDetached", "method not found")
        } ?: DebugLog.hookSkipped(TAG, KEYGUARD_VIEW_CONTROLLER_CLASS, "class not found")

        // The home view can detach/re-attach without recreating its manager. Teardown is kept
        // reversible and the state object remains available for the next attachment.
        statusBarViewClass.findMethodOrNull { name("onDetachedFromWindow"); noParams() }
            .orSkip("LeftRenderer", "${statusBarViewClass.name}#onDetachedFromWindow")
            ?.observe {
            before { param ->
                runCatching { detachHomeState(param.thisObject) }
                    .onFailure { DebugLog.w(TAG, "home left-container teardown failed", it) }
            }
        } ?: DebugLog.hookSkipped(TAG, "$STATUS_BAR_VIEW_CLASS#onDetachedFromWindow", "method not found")

        // The Compose home status-bar root is detached while the lockscreen is shown and can be
        // re-attached with the same manager (or a freshly-created one). The manager is assigned
        // before the root is attached, so setDarkIconManager() may capture a state that is
        // immediately torn down by shouldRender(). Reconcile again after the root is attached;
        // otherwise unlock has no icon event left to trigger clone creation.
        statusBarViewClass.findMethodOrNull { name("onAttachedToWindow"); noParams() }
            .orSkip("LeftRenderer", "${statusBarViewClass.name}#onAttachedToWindow")
            ?.observe {
            after { param ->
                val root = param.thisObject as? ViewGroup ?: return@after
                mainHandler.post {
                    if (retiring) return@post
                    runCatching { rebindHomeState(root, groupField) }
                        .onFailure { DebugLog.w(TAG, "home left-container rebind failed", it) }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$STATUS_BAR_VIEW_CLASS#onAttachedToWindow", "method not found")

        // A host root can hide/reveal without attachment or an icon payload change.
        ViewVisibilityRouter.visibility(owner, ViewGroup::class.java, after = { root ->
            if (retiring) return@visibility
            val states = synchronized(states) { states.values.toList() }
            if (!reconciling && states.any { it.barRoot === root }) {
                requestReconcile()
            } else if (states.any { it.isKeyguard && isBetween(root, it.leftHost, it.barRoot) }) {
                // On screen-on the lockscreen reveals an intermediate side container after the
                // pass that saw it hidden; deferred so a reveal inside a pass is not dropped.
                mainHandler.post { if (!retiring) requestReconcile() }
            }
        })
        // The host swaps carrier, clock and alarm in the lockscreen frame by visibility alone.
        ViewVisibilityRouter.visibility(owner, ViewGroup::class.java, after = { view ->
            if (!retiring) synchronized(states) { states.values.firstOrNull { it.keyguardCarrier === view } }
                ?.let(carrierSwap::update)
        })
        ViewVisibilityRouter.alpha(owner, ViewGroup::class.java, after = { view ->
            if (!retiring && !reconciling && synchronized(states) { states.values.any { it.barRoot === view || it.leftHost === view } }) {
                runOnMain { handover.applyHandoverGuarded(panelProgress) }
            }
        })

        // 4. Hide the left container while the status-bar island is showing.
        val islandHandlerClass = ISLAND_HANDLER_CLASS.toClassOrNull()
        if (islandHandlerClass == null) {
            DebugLog.hookSkipped(TAG, ISLAND_HANDLER_CLASS, "class not found")
        } else {
            islandShowingField = runCatching {
                HostAccess.requireDeclaredField(islandHandlerClass, "islandShowing", accessible = true)
            }.getOrNull()
            islandHandlerClass.findMethodOrNull { name("islandUpdate"); paramCount(2) }
                ?.let { method ->
                    method.observe {
                        after { param ->
                            val showing = islandShowingField?.let { field ->
                                runCatching { field.getBoolean(param.thisObject) }.getOrNull()
                            } ?: return@after
                            applyIslandVisibility(param.thisObject, showing)
                        }
                    }
                }
                ?: DebugLog.hookSkipped(
                    TAG, "$ISLAND_HANDLER_CLASS#islandUpdate", "method not found"
                )
        }

        // 5. Hand the moved icons over to the control center while the shade is dragged.
        hookControlCenterHandover()

        reloadSnapshot()
        // One repair pass after installation; the timer only continues while the shade is open.
        scheduleReconcile()
        DebugLog.i(
            TAG,
            "LeftContainer hooks installed (clone + policy merge, panelProgress=$panelProgressHooked, " +
                "snapshot=$activeSlots)"
        )
        }
    }
}

/** True when [view] is [host] or one of its ancestors below [root]; [LeftState.presentation] reads that chain. */
private fun isBetween(view: android.view.View, host: android.view.View, root: android.view.View?): Boolean {
    var current: android.view.View? = host
    while (current != null) {
        if (current === view) return true
        if (current === root) return false
        current = current.parent as? android.view.View
    }
    return false
}
