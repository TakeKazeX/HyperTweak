package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.settings.contract.SliderPercentagePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.SeekBar
import java.lang.reflect.Field
import java.lang.reflect.Modifier

import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import org.luckypray.dexkit.query.enums.StringMatchType

class SliderPercentageHooker(
    private val pluginContext: android.content.Context? = null,
    private val pluginApkPath: String = ""
) : DynamicHooker(), SliderSession {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingPolicy by hookSetting(SliderPercentagePolicy()) {
        SliderPercentagePolicy.read(Preferences::getBoolean, Preferences::getInt)
    }

    @Volatile
    override var policy = SliderPercentagePolicy()
        private set

    override val sameStyleEnabled: Boolean get() = policy.unifiedColor

    private var volumeHooker: VolumeSliderHooker? = null

    private val resolvedPluginClasses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (pluginContext == null || pluginApkPath.isEmpty()) emptyMap() else DexRuntime.resolveClasses(
            cacheDir = dexCacheDirectory,
            apkPath = pluginApkPath,
            classLoader = classLoader,
            queries = pluginClassQueries()
        )
    }

    override fun resolveClass(className: String, initialize: Boolean): Class<Any>? {
        className.toClassOrNull(initialize = initialize)?.let { return it }

        val resolvedClass = resolveViaDexKit(className)
        if (resolvedClass != null) {
            @Suppress("UNCHECKED_CAST")
            return resolvedClass as Class<Any>
        }
        return null
    }

    private fun resolveViaDexKit(className: String): Class<*>? {
        if (pluginContext == null) return null
        
        val key = pluginClassKey(className) ?: return null
        return resolvedPluginClasses[key]
    }

    private fun pluginClassQueries() = mapOf<String, (org.luckypray.dexkit.DexKitBridge) -> String?>(
            "BrightnessSliderController" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessSliderController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "VolumeSliderController" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("VolumeSliderController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "ToggleSliderViewHolder" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("ToggleSliderViewHolder", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "BrightnessPanelAnimator" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessPanelAnimator", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "BrightnessPanelSliderDelegate" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessPanelSliderDelegate", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "iconColorTransition" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn\$iconColorTransition\$2\$1", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "iconBlendColorTransition" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn\$iconBlendColorTransition\$2\$1", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "VolumeColumn" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "VolumePanelViewController" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumePanelViewController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
        )

    private fun pluginClassKey(className: String): String? = when (className) {
        "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController" -> "BrightnessSliderController"
        "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController" -> "VolumeSliderController"
        "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder" -> "ToggleSliderViewHolder"
        "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator" -> "BrightnessPanelAnimator"
        "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate" -> "BrightnessPanelSliderDelegate"
        "com.android.systemui.miui.volume.VolumeColumn\$iconColorTransition\$2\$1" -> "iconColorTransition"
        "com.android.systemui.miui.volume.VolumeColumn\$iconBlendColorTransition\$2\$1" -> "iconBlendColorTransition"
        "com.android.systemui.miui.volume.VolumeColumn" -> "VolumeColumn"
        "com.android.systemui.miui.volume.VolumePanelViewController" -> "VolumePanelViewController"
        else -> null
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            SliderHookHelper.policy = SliderPercentagePolicy()
            SliderHookHelper.applyCommittedState()
            SliderHookHelper.clearHotReloadCaches()
            volumeHooker = null
        }
    }

    private fun recoverExisting(views: List<View>) {
        val types = listOfNotNull(
            resolveClass("miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController"),
            resolveClass("miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"),
            resolveClass("miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate"),
            resolveClass("com.android.systemui.miui.volume.VolumePanelViewController")
        )
        views.filterIsInstance<SeekBar>().forEach { slider ->
            runCatching {
                val listener = HostAccess.declaredField(SeekBar::class.java,
                    "mOnSeekBarChangeListener", accessible = true)?.get(slider) ?: return@runCatching
                val ownerField = HostAccess.cached(listener.javaClass,
                    "slider.percentage.listenerOwner", Field::class.java) {
                    listener.javaClass.declaredFields.filter { it.type in types && !Modifier.isStatic(it.modifiers) }
                        .singleOrNull()?.let { HostAccess.declaredField(listener.javaClass, it.name, accessible = true) }
                } ?: return@runCatching
                val owner = ownerField.get(listener) ?: return@runCatching
                when {
                    owner.javaClass.name.endsWith("VolumePanelViewController") -> volumeHooker?.captureController(owner)
                    owner.javaClass.name.endsWith("BrightnessPanelSliderDelegate") -> SliderHookHelper.trackExpandedBrightness(owner)
                    else -> {
                        val holder = SliderHookHelper.findHolder(owner) ?: return@runCatching
                        val text = SliderHookHelper.getTopTextFromHolder(holder) ?: return@runCatching
                        SliderHookHelper.refreshViewCache(owner, holder, text)
                    }
                }
            }.onFailure { android.util.Log.w("HyperTweak", "Cannot recover slider percentage host", it) }
        }
    }

    private fun windowViews(): List<View> = buildList {
        fun collect(view: View) {
            add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        WindowInspector.getGlobalWindowViews().forEach(::collect)
    }

    /**
     * Accepted-snapshot refresh for the living plugin session. The child hooks are attached for
     * the session lifetime and every render reads these switches, so a first enable no longer
     * waits for the next plugin load and a disable releases the percentage rendering in place.
     */
    fun refreshSettings() = HostAccess.onMain {
        val next = settingPolicy
        val sameStyle = next.unifiedColor
        val changed = next != policy
        policy = next
        SliderHookHelper.policy = next
        SliderHookHelper.sameStyleEnabled = sameStyle
        if (changed) {
            recoverExisting(windowViews())
            SliderHookHelper.onSettingsChanged()
            // Existing holders: render the percentage now, or restore the host's own label.
            SliderHookHelper.applyCommittedState()
            volumeHooker?.refreshSettings()
        }
        android.util.Log.i("HyperTweak", "SliderPercentage: applied policy=$next sameStyle=$sameStyle changed=$changed")
    }

    override fun onHook() {
        policy = settingPolicy
        SliderHookHelper.policy = policy
        SliderHookHelper.sameStyleEnabled = sameStyleEnabled
        // The plugin session owns these hooks for its whole life; the master switch is read at
        // render time, so a later enable does not need a new plugin session or a restart.
        attach(BrightnessSliderHooker(this))
        volumeHooker = VolumeSliderHooker(this).also { attach(it) }
        attach(CommonSliderHooker(this))
        hostRecovery(HostRecoveryPhase.PRESENTATION) { scope ->
            HostAccess.onMain {
                recoverExisting(scope.views)
                SliderHookHelper.applyCommittedState()
                volumeHooker?.refreshSettings()
            }
        }
    }
}
