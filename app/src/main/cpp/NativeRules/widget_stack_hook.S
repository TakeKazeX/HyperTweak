// SPDX-License-Identifier: Apache-2.0
// The original CID comparisons remain outside both patches. Only AppWidgets
// enter these shims. Replay the flag read without changing model identity.
// No flags, Dart/native stack, non-scratch register or FP register is touched
// beyond the original output register. Disabled/partial installs run stock.
.text
.p2align 2
.macro STACK_GATE name, receiver, value, success, reject
.global \name
.hidden \name
.type \name, %function
\name:
    bti c
    adrp x16, hypertweak_widget_stack_field
    add x16, x16, :lo12:hypertweak_widget_stack_field
    ldar x16, [x16]
    ldr \value, [\receiver, x16]
    // Output is a compressed Dart bool, not a native bool.
.ifc \value,w0
    add x0, x0, x28, lsl #32
    tbz w0, #4, 1f
.else
    add x4, x4, x28, lsl #32
    tbz w4, #4, 1f
.endif
    adrp x16, hypertweak_widget_stack_requested
    add x16, x16, :lo12:hypertweak_widget_stack_requested
    ldar w16, [x16]
    cbz w16, 2f
    adrp x16, hypertweak_widget_stack_ready
    add x16, x16, :lo12:hypertweak_widget_stack_ready
    ldar w16, [x16]
    cbz w16, 2f
1:
    adrp x16, \success
    add x16, x16, :lo12:\success
    ldar x16, [x16]
    br x16
2:
    // Fourth stolen instruction, only on the native rejection path.
    ldr x0, [x26, #0x78]
    adrp x16, \reject
    add x16, x16, :lo12:\reject
    ldar x16, [x16]
    br x16
.size \name, .-\name
.endm
STACK_GATE HyperTweakWidgetStackDragHook, x1, w0, hypertweak_widget_stack_drag_success, hypertweak_widget_stack_drag_reject
STACK_GATE HyperTweakWidgetStackTargetHook, x0, w4, hypertweak_widget_stack_target_success, hypertweak_widget_stack_target_reject
.section .note.GNU-stack,"",%progbits
