package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.util.logging.DebugLog

/** Restores retained framework views and reports the recovered hosts. */
internal class LeftRecovery {
    // Only framework objects and collections cross the module class-loader boundary.
    fun saveHotReloadState(): Any = with(LeftRenderer) { onMainBlocking {
        listOf(states.values.map { state ->
            listOf(state.leftHost, state.anchor, state.rightContainer, state.manager,
                state.isKeyguard, state.barRoot, state.anchorEntries.map {
                    listOf(it.view, it.parent, it.index, it.layoutParams)
                }, state.islandHandler)
        }, ccRows.values.mapNotNull { it.get() }, panelProgress, panelVisible,
            shadeSwitchSettledToControlCenter)
    } }

    fun restoreHotReloadState(state: Any?) {
        with(LeftRenderer) {
        val saved = state as? List<*> ?: return
        mainHandler.post {
            if (retiring) return@post
            (saved.getOrNull(0) as? List<*>)?.forEach { item ->
                val row = item as? List<*> ?: return@forEach
                val host = row.getOrNull(0) as? ViewGroup ?: return@forEach
                val anchor = row.getOrNull(1) as? View
                val right = row.getOrNull(2) as? ViewGroup ?: return@forEach
                val manager = row.getOrNull(3) ?: return@forEach
                val restored = LeftState(host, anchor, right, manager,
                    row.getOrNull(4) == true, row.getOrNull(5) as? View)
                (row.getOrNull(6) as? List<*>)?.forEach anchor@{ value ->
                    val entry = value as? List<*> ?: return@anchor
                    restored.anchorEntries.add(AnchorEntry(
                        entry.getOrNull(0) as? View ?: return@anchor,
                        entry.getOrNull(1) as? ViewGroup ?: return@anchor,
                        entry.getOrNull(2) as? Int ?: return@anchor,
                        entry.getOrNull(3) as? ViewGroup.LayoutParams))
                }
                restored.islandHandler = row.getOrNull(7)
                restored.islandShowing = readIslandShowing(restored.islandHandler)
                states[manager] = restored
            }
            (saved.getOrNull(1) as? List<*>)?.filterIsInstance<ViewGroup>()
                ?.filter { it.isAttachedToWindow }?.forEach(::captureShadeRow)
            runCatching { reconcileAll() }.onFailure { DebugLog.w(TAG, "restore left icons failed", it) }
        }

        }
    }

    fun recoverExistingViews(views: List<View>, progress: Float?, visible: Boolean?) {
        with(LeftRenderer) {
        val managerClass = ICON_MANAGER_BASE_CLASS.toClassOrNull() ?: return
        val groupField = hierarchyField(managerClass, "mGroup") ?: return
        views.forEach { view ->
            when (view.javaClass.name) {
                STATUS_BAR_VIEW_CLASS -> (view as? ViewGroup)?.let { rebindHomeState(it, groupField) }
                KEYGUARD_VIEW_CLASS -> (view as? ViewGroup)?.let(keyguardCapture::captureView)
                in CC_ROW_CLASSES -> (view as? ViewGroup)?.let(::captureShadeRow)
            }
        }
        reconcileAll()
        // Recovery runs once; a timer only continues if the shade is genuinely open.
        if (panelVisible || panelProgress > 0f) scheduleReconcile()
        DebugLog.i(TAG, "hot reload left roots=${states.size} rows=${ccRows.size}")

        }
    }

    /**
     * Snapshot left-icon hosts after recovery, including visibility and render eligibility.
     * Like the host state itself, this must be read on the main thread.
     */
    fun logStateSummary(stage: String) {
        with(LeftRenderer) {
        runCatching {
            val keyguard = keyguardShowing()
            val summary = synchronized(states) {
                states.values.map { state ->
                    val left = state.leftContainer
                    val parent = left?.parent as? ViewGroup
                    buildString {
                        append(if (state.isKeyguard) "keyguard" else "home")
                        append(" attached=").append(state.leftHost.isAttachedToWindow)
                        append(" hostShown=").append(state.leftHost.isShown)
                        append(" root=").append(System.identityHashCode(state.barRoot))
                        append(" rootVis=").append(state.barRoot?.visibility)
                        append(" rootAlpha=").append(state.barRoot?.alpha)
                        append(" policy=").append(state.presentation)
                        append(" container=").append(
                            when {
                                left == null -> "none"
                                parent == null -> "detached"
                                else -> "index=${parent.indexOfChild(left)}/${parent.childCount}"
                            }
                        )
                        left?.let {
                            append(" vis=").append(visibilityName(it.visibility))
                            append(" alpha=").append(it.alpha)
                            append(" w=").append(it.width).append(" h=").append(it.height)
                        }
                        append(" render=").append(shouldRender(state))
                        append(" clones=").append(state.clones.keys.sorted())
                    }
                }
            }
            DebugLog.i(
                TAG,
                "left state stage=$stage mode=$leftMode active=$active keyguard=$keyguard " +
                    "suppressed=${IconHostPorts.keyguard.suppressed()} " +
                    "wanted=${activeSlots.sorted()} rows=${summary.ifEmpty { listOf("none") }.joinToString(" | ")}"
            )
        }.onFailure { DebugLog.w(TAG, "left state summary failed", it) }

        }
    }

    private fun visibilityName(value: Int): String = when (value) {
        View.VISIBLE -> "V"
        View.INVISIBLE -> "I"
        View.GONE -> "G"
        else -> value.toString()
    }

}
