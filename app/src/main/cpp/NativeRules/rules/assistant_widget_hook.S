// SPDX-License-Identifier: Apache-2.0
// Replay the four stolen Dart instructions; only the ordinary AppWidget CID
// may bypass the MIUI boolean and size eligibility gates. Its model/Bundle
// flag and dimensions remain unchanged; every other type follows stock checks.
// x16/x17 are AAPCS scratch, as in the existing Dart mid-function hooks.
    .text
    .p2align 2
    .global HyperTweakAssistantWidgetGateHook
    .hidden HyperTweakAssistantWidgetGateHook
    .type HyperTweakAssistantWidgetGateHook, %function
HyperTweakAssistantWidgetGateHook:
    bti c
    sub sp, sp, #16
    mrs x16, nzcv
    str x16, [sp]
    adrp x16, hypertweak_assistant_widget_field
    add x16, x16, :lo12:hypertweak_assistant_widget_field
    ldar x16, [x16]
    ldr w0, [x2, x16]
    add x0, x0, x28, lsl #32
    // Actual MIUI providers retain their stock dimension policy.
    tbz w0, #4, .Lwidget_continue
    ldur x16, [x2, #-1]
    ubfx x16, x16, #12, #20
    adrp x17, hypertweak_assistant_widget_cid
    add x17, x17, :lo12:hypertweak_assistant_widget_cid
    ldar w17, [x17]
    cmp x16, x17
    b.ne .Lwidget_stock
    adrp x16, hypertweak_assistant_widget_hits
    add x16, x16, :lo12:hypertweak_assistant_widget_hits
    ldr w17, [x16]
    add w17, w17, #1
    str w17, [x16]
    // Skip only this AppWidget call-site's dimension eligibility helper.
    // The shared helper remains stock for MIUI/MAML/service cards.
    mov x1, x4
    mov x2, x6
    add x0, x22, #0x20
    ldr x16, [sp]
    msr nzcv, x16
    add sp, sp, #16
    adrp x16, hypertweak_assistant_widget_accepted
    add x16, x16, :lo12:hypertweak_assistant_widget_accepted
    ldar x16, [x16]
    br x16
.Lwidget_stock:
    tbnz w0, #4, .Lwidget_reject
.Lwidget_continue:
    mov x1, x4
    ldr x16, [sp]
    msr nzcv, x16
    add sp, sp, #16
    adrp x16, hypertweak_assistant_widget_continue
    add x16, x16, :lo12:hypertweak_assistant_widget_continue
    ldar x16, [x16]
    br x16
.Lwidget_reject:
    ldr x16, [sp]
    msr nzcv, x16
    add sp, sp, #16
    adrp x16, hypertweak_assistant_widget_reject
    add x16, x16, :lo12:hypertweak_assistant_widget_reject
    ldar x16, [x16]
    br x16
    .size HyperTweakAssistantWidgetGateHook, .-HyperTweakAssistantWidgetGateHook
