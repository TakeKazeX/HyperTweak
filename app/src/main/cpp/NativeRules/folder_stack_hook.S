// SPDX-License-Identifier: Apache-2.0
// Geometry/count/order observers preserve Dart state without Dart calls or GC.
// Frame/identity/return shims below scope a tail transfer through original Dart code.
.text
.p2align 2
.global HyperTweakFolderStackHook
.hidden HyperTweakFolderStackHook
.type HyperTweakFolderStackHook,%function
HyperTweakFolderStackHook:
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
 // The getter has completed its native list lookup. All frame slots are live.
 // Run even when disabled: restore existing cached metadata without retaining Dart pointers.
 ldur x0, [x29, #-0x10]
 mov x1, x28
 mov x2, x22
 bl HyperTweakUpdateFolderStack
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
 add sp, sp, #800
 // Replay the exact getter return epilogue; no Dart trampoline or native-origin Dart call.
 add x0, x0, x28, lsl #32
 mov x15, x29
 ldp x29, x30, [x15], #0x10
 ret
.size HyperTweakFolderStackHook,.-HyperTweakFolderStackHook

.global HyperTweakFolderStackCountHook
.hidden HyperTweakFolderStackCountHook
.type HyperTweakFolderStackCountHook,%function
HyperTweakFolderStackCountHook:
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
 // x0 is the generator closure; source list length is a tagged SMI at fp-8.
 ldur x1, [x29, #-8]
 mov x2, x28
 mov x3, x22
 bl HyperTweakFolderStackCount
 str x0, [sp, #16]
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
 add sp, sp, #800
 mov x3, x0
 ldur x0, [x29, #-8]
 stur x3, [x29, #-0x10]
 adrp x16, g_folder_stack_count_continue
 add x16, x16, :lo12:g_folder_stack_count_continue
 ldar x16, [x16]
 br x16
.size HyperTweakFolderStackCountHook,.-HyperTweakFolderStackCountHook

.global HyperTweakFolderStackOrderHook
.hidden HyperTweakFolderStackOrderHook
.type HyperTweakFolderStackOrderHook,%function
HyperTweakFolderStackOrderHook:
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
 ldur x0, [x29, #-0x10]
 mov x1, x4
 // x2 is the compiler-owned unboxed destination child count.
 mov x3, x28
 mov x4, x22
 bl HyperTweakFolderStackIndex
 str x0, [sp]
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
 add sp, sp, #800
 ldur x16, [x29, #-0x10]
 stp x0, x16, [x15]
 ldur x0, [x29, #-0x10]
 adrp x16, g_folder_stack_order_continue
 add x16, x16, :lo12:g_folder_stack_order_continue
 ldar x16, [x16]
 br x16
.size HyperTweakFolderStackOrderHook,.-HyperTweakFolderStackOrderHook
// Tail-transfer only after the compiler has finished transient frame callbacks.
// The original refresh body owns its real Dart frame, roots and stack maps. No
// BL from native/C++ and no fabricated Dart return PC is introduced here.
.global HyperTweakFolderStackFrameHook
.hidden HyperTweakFolderStackFrameHook
.type HyperTweakFolderStackFrameHook,%function
HyperTweakFolderStackFrameHook:
 bti c
 sub sp, sp, #96
 stp x9, x10, [sp]
 stp x11, x12, [sp, #16]
 stp x13, x14, [sp, #32]
 stp x16, x17, [sp, #48]
 mrs x9, nzcv
 str x9, [sp, #64]
 adrp x9, g_folder_stack_ready
 add x9, x9, :lo12:g_folder_stack_ready
 ldar w9, [x9]
 cbz w9, .Lstack_frame_return
 adrp x9, g_folder_stack_refresh_epoch
 add x9, x9, :lo12:g_folder_stack_refresh_epoch
 ldar x10, [x9]
 adrp x11, g_folder_stack_refresh_applied
 add x11, x11, :lo12:g_folder_stack_refresh_applied
 ldar x12, [x11]
 cmp x10, x12
 b.eq .Lstack_frame_return
 // Main-isolate model/DI must already exist. Retain the pending epoch when
 // startup is incomplete; never lazily create launcher state in another isolate.
 ldr x13, [x26, #0x78]
 ldr x16, [x27, #0x40]
 adrp x9, g_folder_stack_model_slot
 ldr w14, [x9, :lo12:g_folder_stack_model_slot]
 ldr x14, [x13, x14]
 cmp x14, x22
 b.eq .Lstack_frame_return
 cmp x14, x16
 b.eq .Lstack_frame_return
 tbz x14, #0, .Lstack_frame_return
 adrp x9, g_folder_stack_registry_slot
 ldr w14, [x9, :lo12:g_folder_stack_registry_slot]
 ldr x14, [x13, x14]
 cmp x14, x22
 b.eq .Lstack_frame_return
 cmp x14, x16
 b.eq .Lstack_frame_return
 tbz x14, #0, .Lstack_frame_return
 adrp x9, g_folder_stack_refresh_target
 add x9, x9, :lo12:g_folder_stack_refresh_target
 ldar x17, [x9]
 cbz x17, .Lstack_frame_return
 // Publication is completed only at the original refresh body's shared return.
 adrp x11, g_folder_stack_refresh_owner
 add x11, x11, :lo12:g_folder_stack_refresh_owner
 stlr x26, [x11]
 adrp x11, g_folder_stack_refresh_inflight
 add x11, x11, :lo12:g_folder_stack_refresh_inflight
 stlr x10, [x11]
 str x17, [sp, #72]
 ldr x9, [sp, #64]
 msr nzcv, x9
 ldp x9, x10, [sp]
 ldp x11, x12, [sp, #16]
 ldp x13, x14, [sp, #32]
 ldr x17, [sp, #56]
 ldr x16, [sp, #72]
 add sp, sp, #96
 mov x0, x22
 mov x15, x29
 ldp x29, x30, [x15], #16
 br x16
.Lstack_frame_return:
 ldr x9, [sp, #64]
 msr nzcv, x9
 ldp x9, x10, [sp]
 ldp x11, x12, [sp, #16]
 ldp x13, x14, [sp, #32]
 ldp x16, x17, [sp, #48]
 add sp, sp, #96
 mov x0, x22
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakFolderStackFrameHook,.-HyperTweakFolderStackFrameHook

// Replace only the original constant-ID load/store. The compiler still allocates
// the array/list and performs all stores/calls. Normal monochrome refresh keeps
// its original preview ID; our scoped UI-thread refresh uses the parent layout ID.
.global HyperTweakFolderStackIdentityHook
.hidden HyperTweakFolderStackIdentityHook
.type HyperTweakFolderStackIdentityHook,%function
HyperTweakFolderStackIdentityHook:
 bti c
 sub sp, sp, #48
 stp x9, x10, [sp]
 stp x11, x12, [sp, #16]
 mrs x9, nzcv
 str x9, [sp, #32]
 adrp x9, g_folder_stack_refresh_inflight
 add x9, x9, :lo12:g_folder_stack_refresh_inflight
 ldar x10, [x9]
 cbz x10, .Lstack_original_id
 adrp x9, g_folder_stack_refresh_owner
 add x9, x9, :lo12:g_folder_stack_refresh_owner
 ldar x10, [x9]
 cmp x10, x26
 b.ne .Lstack_original_id
 adrp x9, g_folder_stack_layout_id_pool
 ldr w10, [x9, :lo12:g_folder_stack_layout_id_pool]
 adrp x9, g_folder_stack_refresh_layouts
 add x9, x9, :lo12:g_folder_stack_refresh_layouts
 ldr x11, [x9]
 add x11, x11, #1
 stlr x11, [x9]
 b .Lstack_write_id
.Lstack_original_id:
 adrp x9, g_folder_stack_preview_id_pool
 ldr w10, [x9, :lo12:g_folder_stack_preview_id_pool]
.Lstack_write_id:
 ldr x16, [x27, x10]
 stur w16, [x0, #0xf]
 adrp x9, g_folder_stack_typeargs_pool
 ldr w10, [x9, :lo12:g_folder_stack_typeargs_pool]
 ldr x1, [x27, x10]
 ldr x9, [sp, #32]
 msr nzcv, x9
 ldp x9, x10, [sp]
 ldp x11, x12, [sp, #16]
 add sp, sp, #48
 adrp x17, g_folder_stack_identity_continue
 add x17, x17, :lo12:g_folder_stack_identity_continue
 ldar x17, [x17]
 br x17
.size HyperTweakFolderStackIdentityHook,.-HyperTweakFolderStackIdentityHook

.global HyperTweakFolderStackRefreshReturnHook
.hidden HyperTweakFolderStackRefreshReturnHook
.type HyperTweakFolderStackRefreshReturnHook,%function
HyperTweakFolderStackRefreshReturnHook:
 bti c
 sub sp, sp, #48
 stp x9, x10, [sp]
 stp x11, x12, [sp, #16]
 mrs x9, nzcv
 str x9, [sp, #32]
 adrp x9, g_folder_stack_refresh_owner
 add x9, x9, :lo12:g_folder_stack_refresh_owner
 ldar x10, [x9]
 cmp x10, x26
 b.ne .Lstack_refresh_return
 adrp x10, g_folder_stack_refresh_inflight
 add x10, x10, :lo12:g_folder_stack_refresh_inflight
 ldar x11, [x10]
 cbz x11, .Lstack_refresh_return
 adrp x12, g_folder_stack_refresh_applied
 add x12, x12, :lo12:g_folder_stack_refresh_applied
 stlr x11, [x12]
 stlr xzr, [x10]
 stlr xzr, [x9]
.Lstack_refresh_return:
 ldr x9, [sp, #32]
 msr nzcv, x9
 ldp x9, x10, [sp]
 ldp x11, x12, [sp, #16]
 add sp, sp, #48
 mov x0, x22
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakFolderStackRefreshReturnHook,.-HyperTweakFolderStackRefreshReturnHook

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
