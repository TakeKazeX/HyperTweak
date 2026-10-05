package com.takekazex.hypertweak.hook.rules.slider

import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HotReloadMode
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
    private val parent: SliderPercentageHooker
) : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    /** Owns the collapsed badge capsule's material/style; cleared with the hooker on hot reload. */
    private val badgeStyler = VolumeBadgeStyler(TAG)


    @Volatile
    private var isVolumeViewHooked = false

    // Cached field refs for VolumePanelViewController hooks (hot path: every volume state update)
    private var cachedVpcFieldsLoaded = false
    private var vpcField_mState: java.lang.reflect.Field? = null
    private var vpcField_mExpanded: java.lang.reflect.Field? = null
    private var vpcField_mActiveStream: java.lang.reflect.Field? = null
    private var vpcField_mSuperVolume: java.lang.reflect.Field? = null
    private var vpcField_mSuperVolumeBg: java.lang.reflect.Field? = null
    private var vpcField_mVolumeView: java.lang.reflect.Field? = null
    private var vpcField_isControlCenterPanel: java.lang.reflect.Field? = null
    private var vpcField_mColumns: java.lang.reflect.Field? = null

    // Cached field refs for stream state
    private var cachedStreamStateFieldsLoaded = false
    private var ssField_states: java.lang.reflect.Field? = null
    private var ssField_level: java.lang.reflect.Field? = null
    private var ssField_levelMin: java.lang.reflect.Field? = null
    private var ssField_levelMax: java.lang.reflect.Field? = null
    private var ssMethod_get: java.lang.reflect.Method? = null

    // Cached field refs for VolumeColumn
    private var cachedVolumeColumnField: java.lang.reflect.Field? = null
    private var cachedColumnStreamField: java.lang.reflect.Field? = null
    private var cachedColumnStreamGetter: java.lang.reflect.Method? = null

    // Cache textView → stream mapping to avoid expensive column search in updateSuperVolumeText
    private val textViewToStreamCache = java.util.WeakHashMap<TextView, Int>()

    override fun onPrepareHotReload() {
        isVolumeViewHooked = false
        cachedVpcFieldsLoaded = false
        vpcField_mState = null
        vpcField_mExpanded = null
        vpcField_mActiveStream = null
        vpcField_mSuperVolume = null
        vpcField_mSuperVolumeBg = null
        vpcField_mVolumeView = null
        vpcField_isControlCenterPanel = null
        vpcField_mColumns = null
        cachedStreamStateFieldsLoaded = false
        ssField_states = null
        ssField_level = null
        ssField_levelMin = null
        ssField_levelMax = null
        ssMethod_get = null
        cachedVolumeColumnField = null
        cachedColumnStreamField = null
        cachedColumnStreamGetter = null
        textViewToStreamCache.clear()
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
                            thisObject.javaClass.getMethod("isInCCMainPage").invoke(thisObject) as Boolean
                        }.getOrDefault(true)
                        
                        val shouldShow = VolumeBadgePolicy.visible(isExpanded, isInCCMainPage, sameStyle)
                        runCatching {
                            val superVolume = thisObject.javaClass.getDeclaredField("superVolume")
                                .apply { isAccessible = true }.get(thisObject) as? TextView ?: return@runCatching
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

        // Helper: resolve the VolumePanelViewController field handles once per process.
        fun loadVpcFields(thisObject: Any) {
            if (cachedVpcFieldsLoaded) return
            val clz = thisObject.javaClass
            // Resolve each field independently and always latch loaded=true: a missing field leaves
            // that ref null (every consumer null-checks) instead of throwing on — and re-running
            // getDeclaredField for — every volume event.
            fun field(name: String) = runCatching { clz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            vpcField_mState = field("mState")
            vpcField_mExpanded = field("mExpanded")
            vpcField_mActiveStream = field("mActiveStream")
            vpcField_mSuperVolume = field("mSuperVolume")
            vpcField_mSuperVolumeBg = field("mSuperVolumeBg")
            vpcField_mVolumeView = field("mVolumeView")
            vpcField_isControlCenterPanel = field("isControlCenterPanel")
            vpcField_mColumns = field("mColumns")
            cachedVpcFieldsLoaded = true
        }

        /**
         * Binds the dialog view's `updateSuperVolumeVisibility` once the volume view exists, so the
         * module's collapsed/CC visibility decision wins over the host's super-volume-only rule.
         * This resolves a view class that is only reachable from a live controller, hence the hook
         * installed at runtime instead of at registration time.
         */
        fun hookSuperVolumeVisibilityFallback(thisObject: Any) {
            if (isVolumeViewHooked) return
            runCatching {
                val mVolumeView = vpcField_mVolumeView?.get(thisObject) ?: return@runCatching
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
                                    view.javaClass.getMethod("isExpanded").invoke(view) as Boolean
                                }.getOrDefault(false)
                                val inCCMainPage = runCatching {
                                    view.javaClass.getMethod("inCCMainPage").invoke(view) as Boolean
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
                loadVpcFields(thisObject)
                val badgeBg = vpcField_mSuperVolumeBg?.get(thisObject) as? View ?: return@runCatching
                val badgeText = vpcField_mSuperVolume?.get(thisObject) as? TextView ?: return@runCatching
                val expanded = vpcField_mExpanded?.get(thisObject) as Boolean

                // Only the outer capsule paints the material; the text stays transparent.
                badgeStyler.detachTextBackground(badgeText)
                badgeStyler.applyCapsule(badgeBg, expanded, thisObject.javaClass.classLoader)
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
        fun loadStreamStateFields(mState: Any) {
            if (cachedStreamStateFieldsLoaded) return
            val stateClz = mState.javaClass
            ssField_states = stateClz.getDeclaredField("states").apply { isAccessible = true }
            // states can still be null before the first stream sync — return without latching so a
            // later call retries, instead of pinning ssMethod_get null and disabling the badge for good.
            val statesObj = ssField_states!!.get(mState) ?: return
            ssMethod_get = statesObj.javaClass.getMethod("get", Int::class.javaPrimitiveType ?: Int::class.java)
            // Level fields are initialized lazily from the first valid stream state.
            cachedStreamStateFieldsLoaded = true
        }

        fun updateBadgeText(thisObject: Any, activeStream: Int) {
            if (!parent.showPercentageEnabled) return
            runCatching {
                loadVpcFields(thisObject)
                val mState = vpcField_mState?.get(thisObject) ?: return
                loadStreamStateFields(mState)
                val states = ssField_states?.get(mState) ?: return
                val streamState = ssMethod_get?.invoke(states, activeStream) ?: return

                if (ssField_level == null || ssField_levelMax == null) {
                    ssField_level = streamState.javaClass.getDeclaredField("level").apply { isAccessible = true }
                    ssField_levelMin = runCatching { streamState.javaClass.getDeclaredField("levelMin").apply { isAccessible = true } }.getOrNull()
                    ssField_levelMax = streamState.javaClass.getDeclaredField("levelMax").apply { isAccessible = true }
                }

                val level = (ssField_level?.get(streamState) as? Int) ?: 0
                val levelMin = (ssField_levelMin?.get(streamState) as? Int) ?: 0
                val levelMax = (ssField_levelMax?.get(streamState) as? Int) ?: 0
                val pct = volumePercent(level, levelMin, levelMax) ?: return@runCatching

                val mSuperVolume = vpcField_mSuperVolume?.get(thisObject) as? TextView
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
                    loadVpcFields(param.thisObject)
                    val activeStream = vpcField_mActiveStream?.get(param.thisObject) as? Int
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
                            loadVpcFields(thisObject)
                            val mState = vpcField_mState?.get(thisObject) ?: return@runCatching
                            val mExpanded = vpcField_mExpanded?.get(thisObject) as Boolean
                            val activeStream = vpcField_mActiveStream?.get(thisObject) as Int
                            val column = param.args[0] ?: return@runCatching
                            loadStreamStateFields(mState)

                            // Cache VolumeColumn field refs
                            val colStreamField = cachedColumnStreamField ?: runCatching {
                                column.javaClass.getDeclaredField("stream").apply { isAccessible = true }.also { cachedColumnStreamField = it }
                            }.getOrNull()
                            val colStreamGetter = if (colStreamField == null && cachedColumnStreamGetter == null) {
                                runCatching { column.javaClass.getMethod("getStream").also { cachedColumnStreamGetter = it } }.getOrNull()
                            } else cachedColumnStreamGetter
                            val stream = if (colStreamField != null) {
                                runCatching { colStreamField.get(column) as Int }.getOrDefault(-1)
                            } else if (colStreamGetter != null) {
                                runCatching { colStreamGetter.invoke(column) as Int }.getOrDefault(-1)
                            } else -1

                            if (stream >= 0) {
                                val states = ssField_states?.get(mState) ?: return@runCatching
                                val streamState = ssMethod_get?.invoke(states, stream) ?: return@runCatching

                                if (ssField_level == null || ssField_levelMax == null) {
                                    ssField_level = streamState.javaClass.getDeclaredField("level").apply { isAccessible = true }
                                    ssField_levelMin = runCatching { streamState.javaClass.getDeclaredField("levelMin").apply { isAccessible = true } }.getOrNull()
                                    ssField_levelMax = streamState.javaClass.getDeclaredField("levelMax").apply { isAccessible = true }
                                }
                                val level = (ssField_level?.get(streamState) as? Int) ?: 0
                                val levelMin = (ssField_levelMin?.get(streamState) as? Int) ?: 0
                                val levelMax = (ssField_levelMax?.get(streamState) as? Int) ?: 0
                                val pct = volumePercent(level, levelMin, levelMax) ?: return@runCatching

                                val sameStyleVolume = parent.sameStyleEnabled
                                val colSuperVolField = cachedVolumeColumnField ?: column.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedVolumeColumnField = it }
                                val columnSuperVolume = colSuperVolField.get(column) as? TextView
                                if (columnSuperVolume != null) {
                                    setPercentIfChanged(columnSuperVolume, pct)
                                    val isControlCenter = (vpcField_isControlCenterPanel?.get(thisObject) as? Boolean) ?: false
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
                                        val mSuperVolume = vpcField_mSuperVolume?.get(thisObject) as? TextView
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
                            loadVpcFields(thisObject)
                            val mExpanded = vpcField_mExpanded?.get(thisObject) as Boolean

                            val colSuperVolField = cachedVolumeColumnField ?: column.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedVolumeColumnField = it }
                            val superVolume = colSuperVolField.get(column) as? TextView
                            if (superVolume != null) {
                                val sameStyleVolume = parent.sameStyleEnabled
                                val isControlCenter = (vpcField_isControlCenterPanel?.get(thisObject) as? Boolean) ?: false
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
                                loadVpcFields(thisObject)
                                val mState = vpcField_mState?.get(thisObject) ?: return@runCatching false
                                loadStreamStateFields(mState)
                                val mColumns = vpcField_mColumns?.get(thisObject) as? List<*> ?: return@runCatching false

                                var foundStream = -1
                                val mSuperVolume = vpcField_mSuperVolume?.get(thisObject) as? TextView
                                
                                if (textView === mSuperVolume) {
                                    foundStream = vpcField_mActiveStream?.get(thisObject) as Int
                                } else {
                                    val cached = textViewToStreamCache[textView]
                                    if (cached != null) {
                                        foundStream = cached
                                    } else {
                                        val colSuperVolField = cachedVolumeColumnField
                                        for (col in mColumns) {
                                            if (col != null) {
                                                val sv = colSuperVolField?.get(col) ?: col.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedVolumeColumnField = it }.get(col)
                                                if (sv === textView) {
                                                    val colStreamField = cachedColumnStreamField ?: runCatching {
                                                        col.javaClass.getDeclaredField("stream").apply { isAccessible = true }.also { cachedColumnStreamField = it }
                                                    }.getOrNull()
                                                    val colStreamGetter = if (colStreamField == null && cachedColumnStreamGetter == null) {
                                                        runCatching { col.javaClass.getMethod("getStream").also { cachedColumnStreamGetter = it } }.getOrNull()
                                                    } else cachedColumnStreamGetter
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
                                    val states = ssField_states?.get(mState) ?: return@runCatching false
                                    val streamState = ssMethod_get?.invoke(states, foundStream) ?: return@runCatching false
                                    if (ssField_level == null || ssField_levelMax == null) {
                                        ssField_level = streamState.javaClass.getDeclaredField("level").apply { isAccessible = true }
                                        ssField_levelMin = runCatching { streamState.javaClass.getDeclaredField("levelMin").apply { isAccessible = true } }.getOrNull()
                                        ssField_levelMax = streamState.javaClass.getDeclaredField("levelMax").apply { isAccessible = true }
                                    }
                                    val level = (ssField_level?.get(streamState) as? Int) ?: 0
                                    val levelMin = (ssField_levelMin?.get(streamState) as? Int) ?: 0
                                    val levelMax = (ssField_levelMax?.get(streamState) as? Int) ?: 0
                                    val pct = volumePercent(level, levelMin, levelMax) ?: return@runCatching false

                                    setPercentIfChanged(textView, pct)

                                    val mExpanded = vpcField_mExpanded?.get(thisObject) as Boolean
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
                            view.javaClass.getMethod("isExpanded").invoke(view) as Boolean
                        }.getOrDefault(false)
                        val inCCMainPage = runCatching {
                            view.javaClass.getMethod("inCCMainPage").invoke(view) as Boolean
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
