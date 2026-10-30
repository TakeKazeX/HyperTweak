package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.ViewVisibilityRouter
import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.calcVolumePercent
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.calcVolumePercentFromSliderValue
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.findHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTopTextFromHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.formatPercent
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.initTopText
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.refreshViewCache
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.putTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.updatePercentageText

private const val TAG = "VolumeSlider"

class VolumeSliderHooker(
    private val parent: SliderSession
) : DynamicHooker() {

    /** Owns the collapsed badge capsule's material/style; cleared with the hooker on hot reload. */
    private val badgeStyler = VolumeBadgeStyler(TAG)

    private var isVolumeViewHooked by generationState { false }

    // Cache textView → stream mapping to avoid expensive column search in updateSuperVolumeText
    private val textViewToStreamCache = generationMainResource(java.util.WeakHashMap<TextView, Int>()) { it.clear() }

    private val controllers = generationMainResource(java.util.WeakHashMap<Any, Boolean>()) { it.clear() }
    private data class TextState(val text: CharSequence, val colors: android.content.res.ColorStateList, val typeface: android.graphics.Typeface?)
    private data class ViewState(
        val visibility: Int, val alpha: Float, val text: TextState?,
        val background: android.graphics.drawable.Drawable?, val tint: android.content.res.ColorStateList?,
        val padding: IntArray,
        val topMargin: Int?, val marginEnd: Int?, val width: Int?, val height: Int?
    )
    private val nativeViews = generationMainResource(java.util.WeakHashMap<View, ViewState>()) { it.clear() }
    private class PercentageOwner(owner: Any, val column: Boolean) {
        val instance = java.lang.ref.WeakReference(owner)
    }
    private val percentageOwners = generationMainResource(java.util.WeakHashMap<View, PercentageOwner>()) { it.clear() }
    private var replaying by generationState { false }
    private var renderBadge by generationState<((Any) -> Unit)?> { null }

    private fun inControlCenter(controller: Any): Boolean =
        runCatching { HostAccess.readDeclared(controller, "isControlCenterPanel") as? Boolean ?: false }.getOrDefault(false)

    private fun enabled(controller: Any): Boolean = !replaying && parent.policy.volumePanel(inControlCenter(controller))

    private fun shouldSuppress(view: View): Boolean {
        if (replaying) return false
        val record = percentageOwners[view] ?: return false
        val owner = record.instance.get() ?: return false
        val inCC = if (record.column) {
            runCatching { HostAccess.publicMethod(owner.javaClass, "isInCCMainPage", accessible = false)?.invoke(owner) as? Boolean }.getOrNull() ?: false
        } else inControlCenter(owner)
        return parent.policy.suppressVolumePanel(inCC)
    }

    private fun trackPercentage(view: View?, owner: Any, column: Boolean = false) {
        if (view == null || replaying) return
        capture(view)
        percentageOwners[view] = PercentageOwner(owner, column)
    }

    private fun suppressExcluded(controller: Any) {
        if (replaying) return
        captureController(controller)
        percentageOwners.entries.toList().forEach { (view, _) ->
            if (shouldSuppress(view)) {
                view.visibility = View.GONE
                view.alpha = 0f
            }
        }
    }

    private fun capture(view: View?) {
        if (view == null || nativeViews.containsKey(view)) return
        nativeViews[view] = ViewState(view.visibility, view.alpha, (view as? TextView)?.let { TextState(it.text, it.textColors, it.typeface) },
            view.background, view.backgroundTintList, intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom),
            (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin,
            (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginEnd,
            view.layoutParams?.width, view.layoutParams?.height)
    }

    fun captureController(controller: Any) {
        if (replaying) return
        controllers[controller] = true
        val badge = HostAccess.readDeclared(controller, "mSuperVolumeBg") as? View
        // Repair dimensions left by the earlier generation before taking a new native baseline.
        badge?.let { badgeStyler.restoreNativeSize(it, requireNotNull(controller.javaClass.classLoader)) }
        trackPercentage(badge, controller)
        val text = HostAccess.readDeclared(controller, "mSuperVolume") as? TextView
        text?.let(badgeStyler::restoreNativeFont)
        trackPercentage(text, controller)
        (HostAccess.readDeclared(controller, "mColumns") as? List<*>)?.filterNotNull()?.forEach {
            trackPercentage(HostAccess.readDeclared(it, "superVolume") as? View, controller)
        }
    }

    private fun restoreViews() {
        nativeViews.forEach { (view, state) ->
            view.visibility = state.visibility
            view.alpha = state.alpha
            view.background = state.background
            view.backgroundTintList = state.tint
            view.setPadding(state.padding[0], state.padding[1], state.padding[2], state.padding[3])
            badgeStyler.clearMaterial(view)
            val params = view.layoutParams
            if (params != null) {
                state.width?.let { params.width = it }
                state.height?.let { params.height = it }
                if (params is android.view.ViewGroup.MarginLayoutParams) {
                    state.topMargin?.let { params.topMargin = it }
                    state.marginEnd?.let { params.marginEnd = it }
                }
                view.layoutParams = params
            }
            if (view is TextView) state.text?.let {
                view.text = it.text
                view.setTextColor(it.colors)
                view.typeface = it.typeface
            }
        }
        nativeViews.clear()
    }

    private fun updateNativeOuterGeometry(controller: Any) {
        if (!enabled(controller) || inControlCenter(controller) || !parent.policy.outside) return
        val badge = HostAccess.readDeclared(controller, "mSuperVolumeBg") as? View ?: return
        badgeStyler.restoreNativeSize(badge, requireNotNull(controller.javaClass.classLoader))
        val volume = HostAccess.readDeclared(controller, "mVolumeView") ?: return
        val motion = HostAccess.readDeclared(volume, "mMotion") ?: return
        // Same call made by the host's show pre-draw callback: computes top/end margins using
        // its display metrics, insets and current expansion state. Never copy laid-out .top.
        HostAccess.declaredMethod(motion.javaClass, "updateSuperVolumeState", accessible = true)?.invoke(motion)
    }

    private fun stylePercentageText(text: TextView, unified: Boolean, inside: Boolean, inCC: Boolean, expanded: Boolean = false) {
        capture(text)
        // Inner percentages are always bare text. Material selection belongs to the outer capsule.
        badgeStyler.detachTextBackground(text)
        val params = text.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val nativeTop = nativeViews[text]?.topMargin
        if (inside && params != null && nativeTop != null) {
            val offset = if (!inCC && !expanded && !parent.policy.outside) {
                (12f * text.resources.displayMetrics.density).toInt()
            } else 0
            val top = nativeTop + offset
            if (params.topMargin != top) {
                params.topMargin = top
                text.layoutParams = params
            }
        }
        text.alpha = 1f
        badgeStyler.applyTextStyle(text, unified)
    }

    fun refreshSettings() = HostAccess.onMain {
        replaying = true
        try { restoreViews() } finally { replaying = false }
        controllers.keys.toList().forEach { controller ->
            runCatching {
                // Re-run the native renderer first with overrides suspended, then apply the new policy.
                replaying = true
                HostAccess.declaredMethod(controller.javaClass, "initSuperVolumeColor", accessible = true)?.invoke(controller)
                val columns = (HostAccess.readDeclared(controller, "mColumns") as? List<*>)?.filterNotNull().orEmpty()
                columns.forEach { column ->
                    HostAccess.declaredMethod(controller.javaClass, "updateVolumeColumnH", column.javaClass, accessible = true)?.invoke(controller, column)
                }
                replaying = false
                captureController(controller)
                columns.forEach { column ->
                    HostAccess.declaredMethod(controller.javaClass, "updateVolumeColumnH", column.javaClass, accessible = true)?.invoke(controller, column)
                }
                renderBadge?.invoke(controller)
                suppressExcluded(controller)
            }.onFailure { Log.e(TAG, "Cannot refresh existing volume panel", it) }
            replaying = false
        }
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            replaying = true
            restoreViews()
            controllers.keys.toList().forEach { controller ->
                runCatching { HostAccess.declaredMethod(controller.javaClass, "initSuperVolumeColor", accessible = true)?.invoke(controller) }
            }
        }
        renderBadge = null
        badgeStyler.clear()
    }

    override fun onHook() {
        // Native column updates and dialog animations both own these same reused percentage views.
        // The shared router enforces only excluded scopes; active scopes keep their normal animations.
        ViewVisibilityRouter.visibility(this, before = { view, _ ->
            if (shouldSuppress(view)) View.GONE else null
        })
        ViewVisibilityRouter.alpha(this, before = { view, _ ->
            if (shouldSuppress(view)) 0f else null
        })
        val clzVolumeSlider = parent.resolveClass("miui.systemui.controlcenter.panel.main.volume.VolumeSliderController")

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "onUnbindViewHolder" }?.let { method ->
            method.hook { before { param -> SliderHookHelper.releaseSlider(param.thisObject) } }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        val holder = findHolder(param.thisObject) ?: return@runCatching
                        val text = getTopTextFromHolder(holder) ?: return@runCatching
                        refreshViewCache(param.thisObject, holder, text)
                    }
                    if (parent.policy.controlCenter(false)) {
                        runCatching {
                            val holder = findHolder(param.thisObject) ?: return@runCatching
                            val topText = getTopTextFromHolder(holder) ?: return@runCatching
                            refreshViewCache(param.thisObject, holder, topText)
                            initTopText(topText)
                            putTag(topText, "sliderType", "VolumeSliderController")
                            applyTopTextStyle(topText, force = true, sliderType = "VolumeSliderController")
                            updatePercentageText(param.thisObject, "VolumeSliderController")
                        }
                    }
                }
            }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "updateIconProgress" }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.policy.controlCenter(false)) {
                        updatePercentageText(param.thisObject, "VolumeSliderController")
                    }
                }
            }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "syncSystemVolume" }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.policy.controlCenter(false)) {
                        val controller = param.thisObject
                        runCatching {
                            val topText = getTag(controller, "cached_topText") as? TextView
                                ?: run {
                                    val holder = findHolder(controller) ?: return@runCatching
                                    val tt = getTopTextFromHolder(holder) ?: return@runCatching
                                    putTag(controller, "cached_topText", tt)
                                    tt
                                }
                            val pct = calcVolumePercent(controller) ?: return@runCatching
                            topText.post {
                                setPercentIfChanged(topText, pct)
                                applyTopTextStyle(topText, sliderType = "VolumeSliderController")
                            }
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in syncSystemVolume hook", t)
                        }
                    }
                }
            }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull {
            it.name == "updateSliderValue" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.policy.controlCenter(false)) {
                        val isOriginalVolumeCallback = param.args[1] as Boolean
                        if (!isOriginalVolumeCallback) {
                            val controller = param.thisObject
                            val sliderValue = param.args[0] as Int
                            runCatching {
                                val topText = getTag(controller, "cached_topText") as? TextView
                                    ?: run {
                                        val holder = findHolder(controller) ?: return@runCatching
                                        val tt = getTopTextFromHolder(holder) ?: return@runCatching
                                        putTag(controller, "cached_topText", tt)
                                        tt
                                    }
                                val pct = calcVolumePercentFromSliderValue(controller, sliderValue) ?: return@runCatching
                                setPercentIfChanged(topText, pct)
                                applyTopTextStyle(topText, sliderType = "VolumeSliderController")
                            }.onFailure { t ->
                                Log.e("HyperTweak", "Error in updateSliderValue hook", t)
                            }
                        }
                    }
                }
            }
        }

        // ─── VolumeColumn initColumn ───────────────────────────────────────────
        val clzVolumeColumn = parent.resolveClass("com.android.systemui.miui.volume.VolumeColumn")
        clzVolumeColumn?.declaredMethods?.singleOrNull {
            it.name == "setSuperVolumeParma" && it.parameterCount == 0
        }?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        val text = HostAccess.readDeclared(param.thisObject, "superVolume") as? TextView ?: return@runCatching
                        val layout = text.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return@runCatching
                        // The host has just recomputed the real margin for this configuration.
                        nativeViews[text]?.let { nativeViews[text] = it.copy(topMargin = layout.topMargin) }
                    }
                }
            }
        }

        clzVolumeColumn?.declaredMethods?.firstOrNull { method ->
            method.name == "initColumn" && method.parameterTypes.size == 6
        }?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        val text = HostAccess.readDeclared(param.thisObject, "superVolume") as? View
                        trackPercentage(text, param.thisObject, column = true)
                        if (text != null && shouldSuppress(text)) {
                            text.visibility = View.GONE
                            text.alpha = 0f
                        }
                    }
                    val columnInCC = runCatching {
                        HostAccess.requirePublicMethod(param.thisObject.javaClass, "isInCCMainPage", accessible = false).invoke(param.thisObject) as? Boolean
                    }.getOrNull() ?: false
                    if (!replaying && parent.policy.volumePanel(columnInCC)) {
                        val sameStyle = parent.sameStyleEnabled
                        val isExpanded = param.args[5] as? Boolean ?: false
                        val thisObject = param.thisObject
                        val isInCCMainPage = runCatching {
                            HostAccess.requirePublicMethod(thisObject.javaClass, "isInCCMainPage", accessible = false).invoke(thisObject) as Boolean
                        }.getOrDefault(true)
                        
                        val shouldShow = parent.policy.innerVolume(isExpanded, isInCCMainPage)
                        runCatching {
                            val superVolume = HostAccess.requireDeclaredField(thisObject.javaClass, "superVolume", accessible = true).get(thisObject) as? TextView ?: return@runCatching
                            superVolume.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
                            stylePercentageText(superVolume, sameStyle, inside = true, inCC = isInCCMainPage, expanded = isExpanded)
                        }
                    }
                }
            }
        }

        // ─── VolumePanelViewController ─────────────────────────────────────────
        val clzVolumeViewController = parent.resolveClass("com.android.systemui.miui.volume.VolumePanelViewController")

        clzVolumeViewController?.declaredMethods?.filter {
            it.name in setOf("initPanelView", "showVolumePanelH", "updateVolumeColumnH", "updateSuperVolumeView", "updateSuperVolumeText")
        }?.forEach { method ->
            method.hook {
                before { param -> runCatching { captureController(param.thisObject) } }
                after { param -> runCatching { suppressExcluded(param.thisObject) } }
            }
        }

        // Track whether badge theme has been applied since last show, to avoid re-applying on every update
        var badgeThemeApplied = false

        /**
         * Binds the dialog view's `updateSuperVolumeVisibility` once the volume view exists, so the
         * module's collapsed/CC visibility decision wins over the host's super-volume-only rule.
         * This resolves a view class that is only reachable from a live controller, hence the hook
         * installed at runtime instead of at registration time.
         */
        fun hookSuperVolumeVisibilityFallback(thisObject: Any) {
            if (isVolumeViewHooked) return
            runCatching {
                val mVolumeView = HostAccess.readDeclared(thisObject, "mVolumeView") ?: return@runCatching
                mVolumeView.javaClass.declaredMethods.firstOrNull {
                    it.name == "updateSuperVolumeVisibility" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }?.let { method ->
                    method.hook {
                        before { param ->
                            if (!replaying && parent.policy.volumePanel(HostAccess.readDeclared(param.thisObject, "isControlCenterPanel") as? Boolean ?: false)) {
                                val view = param.thisObject
                                val isExpanded = runCatching {
                                    HostAccess.requirePublicMethod(view.javaClass, "isExpanded", accessible = false).invoke(view) as Boolean
                                }.getOrDefault(false)
                                val inCCMainPage = runCatching {
                                    HostAccess.requirePublicMethod(view.javaClass, "inCCMainPage", accessible = false).invoke(view) as Boolean
                                }.getOrNull()
                                if (inCCMainPage != true) {
                                    param.args[0] = !isExpanded && parent.policy.outside
                                }
                            }
                        }
                    }
                }
                isVolumeViewHooked = true
            }
        }

        /**
         * Native label geometry and font with a locally selected material branch. Glass reuses
         * the shortcut button's complete calculation including light parameters; frost retains
         * theme-aware native blur. Global material mode never overrides this selection.
         */
        fun applyBadgeAppearance(thisObject: Any, forceMaterial: Boolean = false) {
            if (!enabled(thisObject)) return
            runCatching {

                val badgeBg = HostAccess.readDeclared(thisObject, "mSuperVolumeBg") as? View ?: return@runCatching
                val badgeText = HostAccess.readDeclared(thisObject, "mSuperVolume") as? TextView ?: return@runCatching
                val expanded = HostAccess.readDeclared(thisObject, "mExpanded") as Boolean
                updateNativeOuterGeometry(thisObject)
                if (forceMaterial) badgeStyler.invalidateAppearance(badgeBg)

                // Only the outer capsule paints the material; the text stays transparent.
                badgeStyler.detachTextBackground(badgeText)
                // Class.classLoader is annotated @RecentlyNullable; a live host instance always has one.
                badgeStyler.applyCapsule(badgeBg, expanded, requireNotNull(thisObject.javaClass.classLoader), parent.policy.popupStyle)
                badgeStyler.applyTextStyle(badgeText, parent.sameStyleEnabled)

                val badgeVisibility = if (parent.policy.outerVolume(expanded, inControlCenter(thisObject))) View.VISIBLE else View.GONE
                badgeBg.visibility = badgeVisibility
                badgeText.visibility = badgeVisibility
                if (badgeVisibility == View.VISIBLE) {
                    badgeBg.alpha = 1f
                    badgeText.alpha = 1f
                }

                hookSuperVolumeVisibilityFallback(thisObject)
            }.onFailure { t ->
                Log.e("HyperTweak", "Error applying volume badge appearance", t)
            }
        }

        renderBadge = { controller ->
            val active = HostAccess.readDeclared(controller, "mActiveStream") as? Int
            if (active != null) {
                HostAccess.declaredMethod(controller.javaClass, "updateSuperVolumeText", TextView::class.java, Int::class.javaPrimitiveType!!, accessible = true)
                    ?.let { method ->
                        val text = HostAccess.readDeclared(controller, "mSuperVolume") as? TextView
                        if (text != null) method.invoke(controller, text, active)
                    }
            }
            applyBadgeAppearance(controller)
        }

        // Helper: update badge text with the active stream's current percentage

        fun updateBadgeText(thisObject: Any, activeStream: Int) {
            if (!enabled(thisObject)) return
            runCatching {

                val mState = HostAccess.readDeclared(thisObject, "mState") ?: return

                val states = HostAccess.requireDeclaredField(mState.javaClass, "states", accessible = true).get(mState) ?: return
                val streamState = HostAccess.publicMethod(states.javaClass, "get", Int::class.javaPrimitiveType!!, accessible = false)?.invoke(states, activeStream) ?: return

                val level = (HostAccess.requireDeclaredField(streamState.javaClass, "level", accessible = true).get(streamState) as? Int) ?: 0
                val levelMin = (HostAccess.readDeclared(streamState, "levelMin") as? Int) ?: 0
                val levelMax = (HostAccess.requireDeclaredField(streamState.javaClass, "levelMax", accessible = true).get(streamState) as? Int) ?: 0
                val pct = volumePercent(level, levelMin, levelMax) ?: return@runCatching

                val mSuperVolume = HostAccess.readDeclared(thisObject, "mSuperVolume") as? TextView
                if (mSuperVolume != null) {
                    mSuperVolume.text = formatPercent(pct)
                }
            }.onFailure { t ->
                Log.e("HyperTweak", "Error updating badge text", t)
            }
        }

        // The host's own badge setup runs first (transparent capsule plus its material fallbacks),
        // then the module refines it so 柔光玻璃 / 清透磨砂 come from the panel's material tokens.
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "initSuperVolumeColor" }?.let { method ->
            method.hook {
                after { param ->
                    applyBadgeAppearance(param.thisObject, forceMaterial = true)
                }
            }
        }

        // 材质风格 switch: the host re-styles its panel surfaces here but never re-inits this badge,
        // so the capsule has to be repainted at the same boundary to follow the new material.
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "onMaterialModeChanged" }?.let { method ->
            method.hook {
                after { param ->
                    badgeThemeApplied = false
                    refreshSettings()
                }
            }
        }

        // Native configuration changes include UI_MODE; it may dismiss/recreate the dialog.
        // Repaint surviving views now, and the normal bind/show paths cover new instances.
        clzVolumeViewController?.declaredMethods?.singleOrNull {
            it.name == "onConfigChanged" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.let { method ->
            method.hook {
                after {
                    badgeThemeApplied = false
                    refreshSettings()
                }
            }
        }

        // Apply right before the dialog becomes visible — fixes first-press white-bg and cold start visibility bugs
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "showVolumePanelH" }?.let { method ->
            method.hook {
                before { param ->

                    val activeStream = HostAccess.readDeclared(param.thisObject, "mActiveStream") as? Int
                    if (activeStream != null) updateBadgeText(param.thisObject, activeStream)
                    badgeThemeApplied = false
                    applyBadgeAppearance(param.thisObject)
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull {
            it.name == "updateVolumeColumnH" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0].name.endsWith("VolumeColumn")
        }?.let { method ->
            method.hook {
                after { param ->
                    if (enabled(param.thisObject)) {
                        runCatching {
                            val thisObject = param.thisObject

                            val mState = HostAccess.readDeclared(thisObject, "mState") ?: return@runCatching
                            val mExpanded = HostAccess.readDeclared(thisObject, "mExpanded") as Boolean
                            val activeStream = HostAccess.readDeclared(thisObject, "mActiveStream") as Int
                            val column = param.args[0] ?: return@runCatching

                            // Cache VolumeColumn field refs
                            val colStreamField = HostAccess.declaredField(column.javaClass, "stream", accessible = true)
                            val colStreamGetter = if (colStreamField == null) HostAccess.publicMethod(column.javaClass, "getStream", accessible = false) else null
                            val stream = if (colStreamField != null) {
                                runCatching { colStreamField.get(column) as Int }.getOrDefault(-1)
                            } else if (colStreamGetter != null) {
                                runCatching { colStreamGetter.invoke(column) as Int }.getOrDefault(-1)
                            } else -1

                            if (stream >= 0) {
                                val states = HostAccess.requireDeclaredField(mState.javaClass, "states", accessible = true).get(mState) ?: return@runCatching
                                val streamState = HostAccess.publicMethod(states.javaClass, "get", Int::class.javaPrimitiveType!!, accessible = false)?.invoke(states, stream) ?: return@runCatching

                                val level = (HostAccess.requireDeclaredField(streamState.javaClass, "level", accessible = true).get(streamState) as? Int) ?: 0
                                val levelMin = (HostAccess.readDeclared(streamState, "levelMin") as? Int) ?: 0
                                val levelMax = (HostAccess.requireDeclaredField(streamState.javaClass, "levelMax", accessible = true).get(streamState) as? Int) ?: 0
                                val pct = volumePercent(level, levelMin, levelMax) ?: return@runCatching

                                val sameStyleVolume = parent.sameStyleEnabled
                                val colSuperVolField = HostAccess.declaredField(column.javaClass, "superVolume", accessible = true)
                                val columnSuperVolume = colSuperVolField?.get(column) as? TextView
                                if (columnSuperVolume != null) {
                                    setPercentIfChanged(columnSuperVolume, pct)
                                    val isControlCenter = (HostAccess.readDeclared(thisObject, "isControlCenterPanel") as? Boolean) ?: false
                                    val shouldShowInner = parent.policy.innerVolume(mExpanded, isControlCenter)
                                    columnSuperVolume.visibility = if (shouldShowInner) View.VISIBLE else View.INVISIBLE
                                    if (shouldShowInner) {
                                        stylePercentageText(columnSuperVolume, sameStyleVolume, inside = true, inCC = isControlCenter, expanded = mExpanded)
                                    } else {
                                        // Badge not visible — skip expensive theme work
                                        badgeThemeApplied = false
                                    }
                                }

                                if (!mExpanded && !inControlCenter(thisObject) && parent.policy.outside) {
                                    if (stream == activeStream) {
                                        val mSuperVolume = HostAccess.readDeclared(thisObject, "mSuperVolume") as? TextView
                                        if (mSuperVolume != null) {
                                            setPercentIfChanged(mSuperVolume, pct)
                                            mSuperVolume.visibility = View.VISIBLE
                                            badgeStyler.applyTextStyle(mSuperVolume, sameStyleVolume)
                                        }
                                        // Only re-apply badge theme if not already applied (avoids per-update overhead)
                                        if (!badgeThemeApplied) {
                                            applyBadgeAppearance(thisObject)
                                            badgeThemeApplied = true
                                        }
                                    }
                                }
                            }
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in updateVolumeColumnH hook", t)
                        }
                    }
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull {
            it.name == "updateSuperVolumeView" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0].name.endsWith("VolumeColumn")
        }?.let { method ->
            method.hook {
                after { param ->
                    if (enabled(param.thisObject)) {
                        runCatching {
                            val thisObject = param.thisObject
                            val column = param.args[0] ?: return@runCatching

                            val mExpanded = HostAccess.readDeclared(thisObject, "mExpanded") as Boolean

                            val colSuperVolField = HostAccess.declaredField(column.javaClass, "superVolume", accessible = true)
                            val superVolume = colSuperVolField?.get(column) as? TextView
                            if (superVolume != null) {
                                val sameStyleVolume = parent.sameStyleEnabled
                                val isControlCenter = (HostAccess.readDeclared(thisObject, "isControlCenterPanel") as? Boolean) ?: false
                                val shouldShowInner = parent.policy.innerVolume(mExpanded, isControlCenter)
                                superVolume.visibility = if (shouldShowInner) View.VISIBLE else View.INVISIBLE
                            }
                        }
                    }
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeText" }?.let { method ->
            method.hook {
                intercept { chain ->
                    val param = chain.args
                    val thisObject = chain.thisObject
                    if (enabled(thisObject)) {
                        val textView = param[0] as? TextView
                        if (textView != null) {
                            val skipped = runCatching {

                                val mState = HostAccess.readDeclared(thisObject, "mState") ?: return@runCatching false

                                val mColumns = HostAccess.readDeclared(thisObject, "mColumns") as? List<*> ?: return@runCatching false

                                var foundStream = -1
                                val mSuperVolume = HostAccess.readDeclared(thisObject, "mSuperVolume") as? TextView
                                
                                if (textView === mSuperVolume) {
                                    foundStream = HostAccess.readDeclared(thisObject, "mActiveStream") as Int
                                } else {
                                    val cached = textViewToStreamCache[textView]
                                    if (cached != null) {
                                        foundStream = cached
                                    } else {

                                        for (col in mColumns) {
                                            if (col != null) {
                                                val sv = HostAccess.readDeclared(col, "superVolume")
                                                if (sv === textView) {
                                                    val colStreamField = HostAccess.declaredField(col.javaClass, "stream", accessible = true)
                                                    val colStreamGetter = if (colStreamField == null) HostAccess.publicMethod(col.javaClass, "getStream", accessible = false) else null
                                                    foundStream = if (colStreamField != null) {
                                                        runCatching { colStreamField.get(col) as Int }.getOrDefault(-1)
                                                    } else if (colStreamGetter != null) {
                                                        runCatching { colStreamGetter.invoke(col) as Int }.getOrDefault(-1)
                                                    } else -1
                                                    break
                                                }
                                            }
                                        }
                                        if (foundStream >= 0) {
                                            textViewToStreamCache[textView] = foundStream
                                        }
                                    }
                                }

                                if (foundStream >= 0) {
                                    val states = HostAccess.requireDeclaredField(mState.javaClass, "states", accessible = true).get(mState) ?: return@runCatching false
                                    val streamState = HostAccess.publicMethod(states.javaClass, "get", Int::class.javaPrimitiveType!!, accessible = false)?.invoke(states, foundStream) ?: return@runCatching false

                                    val level = (HostAccess.requireDeclaredField(streamState.javaClass, "level", accessible = true).get(streamState) as? Int) ?: 0
                                    val levelMin = (HostAccess.readDeclared(streamState, "levelMin") as? Int) ?: 0
                                    val levelMax = (HostAccess.requireDeclaredField(streamState.javaClass, "levelMax", accessible = true).get(streamState) as? Int) ?: 0
                                    val pct = volumePercent(level, levelMin, levelMax) ?: return@runCatching false

                                    setPercentIfChanged(textView, pct)

                                    val mExpanded = HostAccess.readDeclared(thisObject, "mExpanded") as Boolean
                                    val sameStyleSuper = parent.sameStyleEnabled
                                    textView.visibility = if (textView === mSuperVolume) {
                                        if (parent.policy.outerVolume(mExpanded, inControlCenter(thisObject))) View.VISIBLE else View.GONE
                                    } else {
                                        if (parent.policy.innerVolume(mExpanded, inControlCenter(thisObject))) View.VISIBLE else View.INVISIBLE
                                    }
                                    stylePercentageText(textView, sameStyleSuper, inside = textView !== mSuperVolume, inCC = inControlCenter(thisObject), expanded = mExpanded)
                                    true
                                } else {
                                    false
                                }
                            }.getOrDefault(false)
                            if (skipped) {
                                return@intercept null
                            }
                        }
                    }
                    chain.proceed()
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeViewColor" }?.let { method ->
            method.hook {
                intercept { chain ->
                    if (!enabled(chain.thisObject)) return@intercept chain.proceed()
                    if (!badgeThemeApplied) {
                        applyBadgeAppearance(chain.thisObject)
                        badgeThemeApplied = true
                    }
                    null
                }
            }
        }

        // ─── MiuiVolumeDialogView ──────────────────────────────────────────────
        val clzVolumeDialogView = parent.resolveClass("com.android.systemui.miui.volume.MiuiVolumeDialogView")
        clzVolumeDialogView?.declaredMethods?.firstOrNull {
            it.name == "updateSuperVolumeVisibility" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.let { method ->
            method.hook {
                before { param ->
                    if (enabled(param.thisObject)) {
                        val view = param.thisObject
                        val isExpanded = runCatching {
                            HostAccess.requirePublicMethod(view.javaClass, "isExpanded", accessible = false).invoke(view) as Boolean
                        }.getOrDefault(false)
                        val inCCMainPage = runCatching {
                            HostAccess.requirePublicMethod(view.javaClass, "inCCMainPage", accessible = false).invoke(view) as Boolean
                        }.getOrNull()
                        if (inCCMainPage != true) {
                            param.args[0] = !isExpanded && parent.policy.outside
                        }
                    }
                }
            }
        }
    }
}
