package com.takekazex.hypertweak.hook.rules.systemui.island.host

import android.view.View
import android.widget.ImageView
import java.lang.reflect.Proxy

/** Uses the expanded media card's animator from its own (SystemUI) classloader. */
internal class NativeMediaCoverContract(loader: ClassLoader, systemUiLoader: ClassLoader) {
    private val holder = loader.loadClass("miui.systemui.dynamicisland.module.IslandIconViewHolder")
    val bind = holder.declaredMethods.single {
        it.name == "setFixIcon" && it.returnType == Void.TYPE &&
            it.parameterTypes.map(Class<*>::getName) ==
            listOf("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData")
    }.apply { isAccessible = true }
    val caller = holder.declaredMethods.single {
        it.name == "bind" && it.returnType == Void.TYPE && it.parameterTypes.map(Class<*>::getName) == listOf(
            "miui.systemui.dynamicisland.model.IslandTemplate",
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData",
        )
    }.apply { isAccessible = true }
    val detach = NativeIslandMembers.named(holder, "onDetach", emptyList())
    val hidden = NativeIslandMembers.named(holder, "onHidden", emptyList())
    private val image = holder.getDeclaredField("fixIcon").apply {
        check(type == ImageView::class.java)
        isAccessible = true
    }
    private val picture = NativeIslandMembers.named(holder, "getPicInfo", emptyList())
    private val pictureKey = NativeIslandMembers.named(picture.returnType, "getPic", emptyList())
    private val utility = systemUiLoader.loadClass(UTILITY)
    private val constructor = utility.getDeclaredConstructor()
    private val start = NativeMediaFlipEntry.resolve(utility)
    private val listener = start.parameterTypes.last()
    private val nativeLoader = utility.classLoader!!
    private val clean = NativeIslandMembers.namedStatic(nativeLoader.loadClass("miuix.animation.Folme"),
        "clean", listOf(View::class.java))
    private val blur = NativeIslandMembers.namedStatic(nativeLoader.loadClass("miuix.core.util.MiuiBlurUtils"),
        "setSelfBlur", listOf(View::class.java, Int::class.javaPrimitiveType!!, java.util.ArrayList::class.java))
    private var tags: Pair<Int, Int>? = null

    private fun tags(view: View): Pair<Int, Int> = tags ?: run {
        val resources = view.context.packageManager.getResourcesForApplication("com.android.systemui")
        fun id(name: String) = resources.getIdentifier(name, "id", "com.android.systemui").also {
            check(it != 0) { "Missing native flip tag $name" }
        }
        (id("miuix_animation_tag_is_flip") to id("miuix_animation_tag_flip_rotation_y")).also { tags = it }
    }

    fun image(holder: Any): ImageView? = image.get(holder) as? ImageView
    fun isAlbum(holder: Any): Boolean = picture.invoke(holder)?.let { pictureKey.invoke(it) } == ALBUM
    fun animator(): Any = constructor.newInstance()
    fun flip(animator: Any, view: ImageView, swap: () -> Unit) {
        tags(view) // Resolve before the first mutation; R8 may remove the host R$id class.
        val callback = Proxy.newProxyInstance(nativeLoader, arrayOf(listener)) { proxy, method, args ->
            when (method.name) {
                "onFlip" -> { swap(); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "HyperTweakNativeCoverFlip"
                else -> null
            }
        }
        // The host retains its device capability and blur policy; no forced animation gate.
        start.invoke(animator, view, true, true, callback)
    }

    fun reset(view: ImageView) {
        try {
            clean.invoke(null, view)
        } finally {
            try {
                blur.invoke(null, view, 0, null)
            } finally {
                view.rotationY = 0f
                view.scaleX = 1f
                tags(view).let { (active, rotation) ->
                    view.setTag(active, null)
                    view.setTag(rotation, null)
                }
            }
        }
    }

    companion object {
        const val UTILITY = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaAlbumAnimationUtils"
        const val ALBUM = "miui_media_album_icon"
    }
}
