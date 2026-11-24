package com.takekazex.hypertweak.hook.rules.misettings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * Rows of MiSettings' "custom high refresh apps" page.
 *
 * The row holder's bind stores the app item, shows its icon, label and switch state, and gives
 * the row a click listener. That listener flips the item's state and reports `(item, checked)` to
 * the page; the switch only forwards to the row click.
 */
internal class AppRefreshRateRowsContract private constructor(
    val bind: MethodReference,
    val click: MethodReference,
    val item: FieldReference,
    val button: FieldReference,
    val row: FieldReference,
    val pkg: FieldReference,
    val label: FieldReference,
    val checked: FieldReference,
) {
    companion object {
        private const val BUTTON = "Lmiuix/slidingwidget/widget/SlidingButton;"
        private const val PERFORM_LISTENER = "$BUTTON->setOnPerformCheckedChangeListener(Landroid/widget/CompoundButton\$OnCheckedChangeListener;)V"
        private const val SET_CHECKED = "$BUTTON->setChecked(Z)V"
        private const val CLICK_LISTENER = "Landroid/view/View;->setOnClickListener(Landroid/view/View\$OnClickListener;)V"
        private const val ICON = "Landroid/widget/ImageView;->setImageBitmap(Landroid/graphics/Bitmap;)V"

        fun resolve(index: DexOperandIndex): AppRefreshRateRowsContract? = index.methods.filter { method ->
            method.returnType == "V" && method.parameterTypes.getOrNull(1)?.toString() == "Ljava/lang/Object;"
        }.mapNotNull { bind -> match(index, bind) }.singleOrNull()

        private fun match(index: DexOperandIndex, bind: MethodReference): AppRefreshRateRowsContract? {
            val code = index.code(bind)
            val descriptors = code.calls.map { DexOperandIndex.descriptor(it.method) }.toSet()
            if (!descriptors.containsAll(listOf(PERFORM_LISTENER, SET_CHECKED, CLICK_LISTENER, ICON))) return null
            val holder = bind.definingClass
            val item = code.writes.filter { (field, value) ->
                field.definingClass == holder && (value as? DexOperandIndex.Value.Argument)?.index == 1
            }.map { it.first }.distinctBy(::key).singleOrNull() ?: return null
            val button = code.reads.filter { it.definingClass == holder && it.type == BUTTON }.distinctBy(::key).singleOrNull() ?: return null
            val clicks = code.calls.filter { DexOperandIndex.descriptor(it.method) == CLICK_LISTENER }
            val clickSite = clicks.singleOrNull() ?: return null
            val row = (clickSite.arguments.getOrNull(0) as? DexOperandIndex.Value.Field)?.field
                ?.takeIf { it.definingClass == holder && it.type == "Landroid/view/View;" } ?: return null
            val listener = (clickSite.arguments.getOrNull(1) as? DexOperandIndex.Value.Allocation)?.type ?: return null
            val click = index.declared(listener).singleOrNull { method ->
                method.name == "onClick" && method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")
            } ?: return null
            // The listener reports (item, checked) to the page.
            if (index.code(click).calls.none { call ->
                    call.method.parameterTypes.map(CharSequence::toString) == listOf(item.type, "Z")
                }) return null
            val itemReads = code.reads.filter { it.definingClass == item.type }.distinctBy(::key)
            val itemWrites = code.writes.map { it.first }.filter { it.definingClass == item.type }.map(::key).toSet()
            val strings = itemReads.filter { it.type == "Ljava/lang/String;" }
            val pkg = strings.filter { key(it) !in itemWrites }.singleOrNull() ?: return null
            val label = strings.filter { key(it) in itemWrites }.singleOrNull() ?: return null
            val checked = itemReads.singleOrNull { it.type == "Z" } ?: return null
            return AppRefreshRateRowsContract(bind, click, item, button, row, pkg, label, checked)
        }

        private fun key(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"
    }
}
