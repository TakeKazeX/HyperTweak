package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.hook.base.HostAccess
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

    override fun onPrepareHotReload() {
        badgeStyler.clear()
    }

    override fun onHook() {
        val clzVolumeSlider = parent.resolveClass("miui.systemui.controlcenter.panel.main.volume.VolumeSliderController")

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.showPercentageEnabled) {
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
                    if (parent.showPercentageEnabled) {
                        updatePercentageText(param.thisObject, "VolumeSliderController")
                    }
                }
            }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "syncSystemVolume" }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.showPercentageEnabled) {
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
                    if (parent.showPercentageEnabled) {
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
        clzVolumeColumn?.declaredMethods?.firstOrNull { method ->
            method.name == "initColumn" && method.parameterTypes.size == 6
        }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.showPercentageEnabled) {
                        val sameStyle = parent.sameStyleEnabled
                        val isExpanded = param.args[5] as? Boolean ?: false
                        val thisObject = param.thisObject
                        val isInCCMainPage = runCatching {
                            HostAccess.requirePublicMethod(thisObject.javaClass, "isInCCMainPage", accessible = false).invoke(thisObject) as Boolean
                        }.getOrDefault(true)
                        
                        val shouldShow = VolumeBadgePolicy.visible(isExpanded, isInCCMainPage, sameStyle)
                        runCatching {
                            val superVolume = HostAccess.requireDeclaredField(thisObject.javaClass, "superVolume", accessible = true).get(thisObject) as? TextView ?: return@runCatching
                            superVolume.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
                            badgeStyler.applyTextStyle(superVolume, sameStyle)
                        }
                    }
                }
            }
        }

        // ─── VolumePanelViewController ─────────────────────────────────────────
        val clzVolumeViewController = parent.resolveClass("com.android.systemui.miui.volume.VolumePanelViewController")

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
                            if (parent.showPercentageEnabled) {
                                val view = param.thisObject
                                val isExpanded = runCatching {
                                    HostAccess.requirePublicMethod(view.javaClass, "isExpanded", accessible = false).invoke(view) as Boolean
                                }.getOrDefault(false)
                                val inCCMainPage = runCatching {
                                    HostAccess.requirePublicMethod(view.javaClass, "inCCMainPage", accessible = false).invoke(view) as Boolean
                                }.getOrNull()
                                if (inCCMainPage != true) {
                                    param.args[0] = !isExpanded
                                }
                            }
                        }
                    }
                }
                isVolumeViewHooked = true
            }
        }

        /**
         * Applies the badge's appearance from the current system material state: the capsule is
         * painted by [badgeStyler] with the volume panel's own material tokens (柔光玻璃 /
         * 清透磨砂), the percentage text follows 统一风格, and both views follow the expanded state.
         * Host material state is read on every call, so a 材质风格 switch is picked up on the next
         * panel show and at the host's material-change boundary.
         */
        fun applyBadgeAppearance(thisObject: Any) {
            if (!parent.showPercentageEnabled) return
            runCatching {

                val badgeBg = HostAccess.readDeclared(thisObject, "mSuperVolumeBg") as? View ?: return@runCatching
                val badgeText = HostAccess.readDeclared(thisObject, "mSuperVolume") as? TextView ?: return@runCatching
                val expanded = HostAccess.readDeclared(thisObject, "mExpanded") as Boolean

                // Only the outer capsule paints the material; the text stays transparent.
                badgeStyler.detachTextBackground(badgeText)
                // Class.classLoader is annotated @RecentlyNullable; a live host instance always has one.
                badgeStyler.applyCapsule(badgeBg, expanded, requireNotNull(thisObject.javaClass.classLoader))
                badgeStyler.applyTextStyle(badgeText, parent.sameStyleEnabled)

                val badgeVisibility = if (expanded) View.GONE else View.VISIBLE
                badgeBg.visibility = badgeVisibility
                badgeText.visibility = badgeVisibility

                hookSuperVolumeVisibilityFallback(thisObject)
            }.onFailure { t ->
                Log.e("HyperTweak", "Error applying volume badge appearance", t)
            }
        }

        // Helper: update badge text with the active stream's current percentage

        fun updateBadgeText(thisObject: Any, activeStream: Int) {
            if (!parent.showPercentageEnabled) return
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
                    applyBadgeAppearance(param.thisObject)
                }
            }
        }

        // 材质风格 switch: the host re-styles its panel surfaces here but never re-inits this badge,
        // so the capsule has to be repainted at the same boundary to follow the new material.
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "onMaterialModeChanged" }?.let { method ->
            method.hook {
                after { param ->
                    badgeThemeApplied = false
                    applyBadgeAppearance(param.thisObject)
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
                    if (parent.showPercentageEnabled) {
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
                                    val shouldShowInner = VolumeBadgePolicy.visible(mExpanded, isControlCenter, sameStyleVolume)
                                    columnSuperVolume.visibility = if (shouldShowInner) View.VISIBLE else View.INVISIBLE
                                    if (shouldShowInner) {
                                        badgeStyler.applyTextStyle(columnSuperVolume, sameStyleVolume)
                                    } else {
                                        // Badge not visible — skip expensive theme work
                                        badgeThemeApplied = false
                                    }
                                }

                                if (!mExpanded) {
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
                    if (parent.showPercentageEnabled) {
                        runCatching {
                            val thisObject = param.thisObject
                            val column = param.args[0] ?: return@runCatching

                            val mExpanded = HostAccess.readDeclared(thisObject, "mExpanded") as Boolean

                            val colSuperVolField = HostAccess.declaredField(column.javaClass, "superVolume", accessible = true)
                            val superVolume = colSuperVolField?.get(column) as? TextView
                            if (superVolume != null) {
                                val sameStyleVolume = parent.sameStyleEnabled
                                val isControlCenter = (HostAccess.readDeclared(thisObject, "isControlCenterPanel") as? Boolean) ?: false
                                val shouldShowInner = VolumeBadgePolicy.visible(mExpanded, isControlCenter, sameStyleVolume)
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
                    if (parent.showPercentageEnabled) {
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
                                        if (mExpanded && sameStyleSuper) View.GONE else View.VISIBLE
                                    } else {
                                        if (mExpanded) View.VISIBLE else View.INVISIBLE
                                    }
                                    badgeStyler.applyTextStyle(textView, sameStyleSuper)
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
                    if (parent.showPercentageEnabled) {
                        val view = param.thisObject
                        val isExpanded = runCatching {
                            HostAccess.requirePublicMethod(view.javaClass, "isExpanded", accessible = false).invoke(view) as Boolean
                        }.getOrDefault(false)
                        val inCCMainPage = runCatching {
                            HostAccess.requirePublicMethod(view.javaClass, "inCCMainPage", accessible = false).invoke(view) as Boolean
                        }.getOrNull()
                        if (inCCMainPage != true) {
                            param.args[0] = !isExpanded
                        }
                    }
                }
            }
        }
    }
}
