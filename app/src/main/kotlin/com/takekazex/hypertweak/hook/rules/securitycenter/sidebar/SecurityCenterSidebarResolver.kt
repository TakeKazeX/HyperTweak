package com.takekazex.hypertweak.hook.rules.securitycenter.sidebar

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.MethodReference

/** Resolve the handle from its sampling behavior, then follow its drawable geometry. */
internal class SecurityCenterSidebarResolver(private val index: DexOperandIndex) {
    private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
    private fun MethodReference.key() = DexOperandIndex.descriptor(this)
    fun resolve(): Map<String, String> {
        val start = index.strings("startRegionSampling, isSmaplingOnce:").single {
            it.params() == listOf("Z") && it.returnType == "V"
        }
        val owner = start.definingClass
        var parent = index.classDefinition(owner)?.superclass
        val ancestors = mutableSetOf<String>()
        while (parent != null && ancestors.add(parent) && parent != "Landroid/widget/ImageView;") {
            parent = index.classDefinition(parent)?.superclass
        }
        check(parent == "Landroid/widget/ImageView;")
        val members = index.declared(owner)
        val constructor = members.single { it.name == "<init>" &&
            it.params() == listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;") }
        val helper = index.code(start).calls.single { it.method.params() == listOf("Landroid/graphics/Rect;") && it.method.returnType == "V" }.method
        check(index.code(constructor).calls.any { it.method.name == "<init>" && it.method.definingClass == helper.definingClass &&
            it.method.parameterTypes.firstOrNull()?.toString() == "Landroid/view/View;" })
        val touch = members.single { it.name == "dispatchTouchEvent" && it.params() == listOf("Landroid/view/MotionEvent;") && it.returnType == "Z" }
        check(index.code(touch).calls.any { it.method.name == "dispatchTouchEvent" && it.method.definingClass == "Landroid/view/View;" })
        val bounds = index.strings("updateSamplingRect view samplingBounds: ").single { it.definingClass == owner }
        val geometry = index.code(bounds).calls.map { it.method }.distinctBy { it.key() }.single {
            it.params().isEmpty() && it.returnType == "Landroid/graphics/RectF;"
        }
        val drawable = geometry.definingClass
        check(index.classDefinition(drawable)?.superclass == "Landroid/graphics/drawable/Drawable;")
        val draw = index.declared(drawable).single { it.name == "draw" && it.params() == listOf("Landroid/graphics/Canvas;") && it.returnType == "V" }
        check(index.code(draw).calls.any { it.method.definingClass == "Landroid/graphics/Canvas;" && it.method.name == "drawPath" })
        return mapOf("view" to owner, "touch" to touch.key(), "draw" to draw.key())
    }
}
