package com.takekazex.hypertweak.hook.rules.systemui.slider

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.SeekBar
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SliderPercentagePolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Brightness/volume percentage labels for one control-center plugin session. Owns the policy,
 * the shared label baselines and style caches; the two surfaces are hook components of this
 * hooker, so they share its lifecycle. Host classes are loaded by name from the plugin loader;
 * a missing class leaves that surface native.
 */
class SliderPercentageHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingPolicy by hookSetting(SliderPercentagePolicy()) {
        SliderPercentagePolicy.read(Preferences::getBoolean, Preferences::getInt)
    }

    @Volatile
    var policy = SliderPercentagePolicy()
        private set

    internal val labels = generationMainResource(PercentLabels()) { it.restoreAll() }
    internal val style by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { PercentLabelStyle(labels, classLoader) }

    private var controlCenter by generationState<ControlCenterPercentage?> { null }
    private var volumePanel by generationState<VolumePanelPercentage?> { null }

    override fun onHook() {
        policy = settingPolicy
        // Hooks stay for the session; every callback reads the policy, so enabling later needs
        // no new plugin session and disabling restores the labels in place.
        controlCenter = ControlCenterPercentage(this).also { it.install() }
        volumePanel = VolumePanelPercentage(this).also { it.install() }
        hostRecovery(HostRecoveryPhase.PRESENTATION) { scope ->
            HostAccess.onMain {
                recoverExisting(scope.views)
                controlCenter?.refresh()
                volumePanel?.refresh()
            }
        }
    }

    /** Accepted-snapshot refresh for the living plugin session. */
    fun refreshSettings() = HostAccess.onMain {
        val next = settingPolicy
        val changed = next != policy
        policy = next
        if (changed) {
            recoverExisting(windowViews())
            controlCenter?.refresh()
            volumePanel?.refresh()
        }
        DebugLog.i(TAG, "applied policy=$next changed=$changed")
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            policy = SliderPercentagePolicy()
            volumePanel?.retire()
            controlCenter?.retire()
            labels.restoreAll()
            style.clear()
        }
    }

    /** A living host from before this generation: each slider's change listener holds its owner. */
    private fun recoverExisting(views: List<View>) {
        val owners = listOf(ControlCenterPercentage.CC_BRIGHTNESS, ControlCenterPercentage.CC_VOLUME,
            ControlCenterPercentage.EXPANDED_DELEGATE, VolumePanelPercentage.CONTROLLER).mapNotNull { it.toClassOrNull() }
        if (owners.isEmpty()) return
        views.filterIsInstance<SeekBar>().forEach { slider ->
            runCatching {
                val listener = HostAccess.declaredField(SeekBar::class.java, "mOnSeekBarChangeListener", accessible = true)?.get(slider) ?: return@runCatching
                val field = HostAccess.cached(listener.javaClass, "slider.percentage.listenerOwner", Field::class.java) {
                    listener.javaClass.declaredFields.singleOrNull { it.type in owners && !Modifier.isStatic(it.modifiers) }
                        ?.let { HostAccess.declaredField(listener.javaClass, it.name, accessible = true) }
                } ?: return@runCatching
                val owner = field.get(listener) ?: return@runCatching
                if (owner.javaClass.name == VolumePanelPercentage.CONTROLLER) volumePanel?.captureController(owner)
                else controlCenter?.recover(owner)
            }.onFailure { DebugLog.w(TAG, "Cannot recover slider percentage host", it) }
        }
    }

    private fun windowViews(): List<View> = buildList {
        fun collect(view: View) {
            add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        WindowInspector.getGlobalWindowViews().forEach(::collect)
    }

    private companion object { const val TAG = "SliderPercentage" }
}
