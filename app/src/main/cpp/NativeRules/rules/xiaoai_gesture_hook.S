// SPDX-License-Identifier: Apache-2.0
// Native-only observer: preserve the entire Dart machine state; no Dart call/GC.
.text
.p2align 2
.global HyperTweakXiaoaiGestureHook
.hidden HyperTweakXiaoaiGestureHook
.type HyperTweakXiaoaiGestureHook,%function
HyperTweakXiaoaiGestureHook:
 bti c
 sub sp, sp, #800
 stp x0, x1, [sp, #0]
 stp x2, x3, [sp, #16]
 stp x4, x5, [sp, #32]
 stp x6, x7, [sp, #48]
 stp x8, x9, [sp, #64]
 stp x10, x11, [sp, #80]
 stp x12, x13, [sp, #96]
 stp x14, x15, [sp, #112]
 stp x16, x17, [sp, #128]
 stp x18, x19, [sp, #144]
 stp x20, x21, [sp, #160]
 stp x22, x23, [sp, #176]
 stp x24, x25, [sp, #192]
 stp x26, x27, [sp, #208]
 stp x28, x29, [sp, #224]
 str x30, [sp, #240]
 stp q0, q1, [sp, #256]
 stp q2, q3, [sp, #288]
 stp q4, q5, [sp, #320]
 stp q6, q7, [sp, #352]
 stp q8, q9, [sp, #384]
 stp q10, q11, [sp, #416]
 stp q12, q13, [sp, #448]
 stp q14, q15, [sp, #480]
 stp q16, q17, [sp, #512]
 stp q18, q19, [sp, #544]
 stp q20, q21, [sp, #576]
 stp q22, q23, [sp, #608]
 stp q24, q25, [sp, #640]
 stp q26, q27, [sp, #672]
 stp q28, q29, [sp, #704]
 stp q30, q31, [sp, #736]
 mrs x16, nzcv
 str x16, [sp, #768]
 mrs x16, fpcr
 str x16, [sp, #776]
 mrs x16, fpsr
 str x16, [sp, #784]
 adrp x16, g_xiaoai_gesture_enabled
 add x16, x16, :lo12:g_xiaoai_gesture_enabled
 ldar w16, [x16]
 str wzr, [sp, #792]
 cbz w16, 1f
 ldr x0, [x15]
 ldr x1, [x15, #8]
 mov x2, x28
 mov x3, x27
 bl HyperTweakAllowXiaoaiGesture
 str w0, [sp, #792]
 cbz w0, 1f
 ldr x0, [sp, #176]
 add x0, x0, #0x20
 str x0, [sp]
1:
 ldr x16, [sp, #784]
 msr fpsr, x16
 ldr x16, [sp, #776]
 msr fpcr, x16
 ldr x16, [sp, #768]
 msr nzcv, x16
 ldp q0, q1, [sp, #256]
 ldp q2, q3, [sp, #288]
 ldp q4, q5, [sp, #320]
 ldp q6, q7, [sp, #352]
 ldp q8, q9, [sp, #384]
 ldp q10, q11, [sp, #416]
 ldp q12, q13, [sp, #448]
 ldp q14, q15, [sp, #480]
 ldp q16, q17, [sp, #512]
 ldp q18, q19, [sp, #544]
 ldp q20, q21, [sp, #576]
 ldp q22, q23, [sp, #608]
 ldp q24, q25, [sp, #640]
 ldp q26, q27, [sp, #672]
 ldp q28, q29, [sp, #704]
 ldp q30, q31, [sp, #736]
 ldp x0, x1, [sp, #0]
 ldp x2, x3, [sp, #16]
 ldp x4, x5, [sp, #32]
 ldp x6, x7, [sp, #48]
 ldp x8, x9, [sp, #64]
 ldp x10, x11, [sp, #80]
 ldp x12, x13, [sp, #96]
 ldp x14, x15, [sp, #112]
 ldp x16, x17, [sp, #128]
 ldp x18, x19, [sp, #144]
 ldp x20, x21, [sp, #160]
 ldp x22, x23, [sp, #176]
 ldp x24, x25, [sp, #192]
 ldp x26, x27, [sp, #208]
 ldp x28, x29, [sp, #224]
 ldr x30, [sp, #240]
 ldr w16, [sp, #792]
 add sp, sp, #800
 cbz w16, 2f
 ret
2:
 adrp x16, g_xiaoai_gesture_original
 ldr x16, [x16, :lo12:g_xiaoai_gesture_original]
 br x16
.size HyperTweakXiaoaiGestureHook,.-HyperTweakXiaoaiGestureHook
.section .note.gnu.property,"a",%note
.p2align 3
.long 4
.long 16
.long 5
.asciz "GNU"
.long 0xc0000000
.long 4
.long 7
.long 0
.section .note.GNU-stack,"",%progbits
