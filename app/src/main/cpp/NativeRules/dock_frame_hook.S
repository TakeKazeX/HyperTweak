.text
// AOT return observers: no Dart/C++ call, no heap writes, no trampoline/GC frame.
// Only copy primitive values from the verified native frame and write one nonblocking datagram.
.macro save_dock
 sub sp, sp, #0x100
 stp x0, x1, [sp, #0]
 stp x2, x8, [sp, #16]
 stp x12, x13, [sp, #32]
 stp x14, x15, [sp, #48]
 stp x16, x17, [sp, #64]
 mrs x17, nzcv
 str x17, [sp, #80]
.endm
.macro restore_dock
 ldr x17, [sp, #80]
 msr nzcv, x17
 ldp x0, x1, [sp, #0]
 ldp x2, x8, [sp, #16]
 ldp x12, x13, [sp, #32]
 ldp x14, x15, [sp, #48]
 ldp x16, x17, [sp, #64]
 add sp, sp, #0x100
.endm
.macro endpoint_check done
 adrp x16, g_dock_frame_enabled
 add x16, x16, :lo12:g_dock_frame_enabled
 ldar w17, [x16]
 cbz w17, \done
.endm
.macro send_dock kind=0
 adrp x16, g_dock_frame_sequence
 add x16, x16, :lo12:g_dock_frame_sequence
.Lsequence\@:
 ldxr x12, [x16]
 add x12, x12, #1
 stxr w13, x12, [x16]
 cbnz w13, .Lsequence\@
 str x12, [sp, #120]
 // Keep primitive samples for an endpoint/classloader replacement. One Dart
 // isolate writes each slot; a bounded seqlock read on the worker replays it.
 adrp x16, g_dock_frame_samples
 add x16, x16, :lo12:g_dock_frame_samples
 add x16, x16, #(\kind * 56)
 ldr x12, [x16]
 add x12, x12, #1
 stlr x12, [x16]
 dmb ishst
 ldp x13, x14, [sp, #128]
 stp x13, x14, [x16, #8]
 ldp x13, x14, [sp, #144]
 stp x13, x14, [x16, #24]
 ldr x13, [sp, #160]
 str x13, [x16, #40]
 ldr x13, [sp, #120]
 str x13, [x16, #48]
 add x12, x12, #1
 stlr x12, [x16]
 endpoint_check .Lsend_done\@
 mov x12, #0x444b
 movk x12, #\kind, lsl #16
 adrp x16, g_dock_frame_source_epoch
 add x16, x16, :lo12:g_dock_frame_source_epoch
 ldar w13, [x16]
 orr x12, x12, x13, lsl #32
 str x12, [sp, #96]
 adrp x16, g_dock_frame_tokens
 add x16, x16, :lo12:g_dock_frame_tokens
 ldar x12, [x16]
 ldr x13, [x16, #8]
 stp x12, x13, [sp, #104]
 adrp x16, g_dock_frame_fd
 add x16, x16, :lo12:g_dock_frame_fd
 ldar w0, [x16]
 add x1, sp, #96
 mov x2, #72
 mov x8, #64
 svc #0
.Lsend_done\@:
.endm
.p2align 2
.global HyperTweakDockVisibleReturn
.hidden HyperTweakDockVisibleReturn
.type HyperTweakDockVisibleReturn,%function
HyperTweakDockVisibleReturn:
 bti c
 stur w1, [x0, #0xb]
 save_dock
 adrp x16, g_dock_frame_offsets
 add x16, x16, :lo12:g_dock_frame_offsets
 ldr x12, [x16]
 ldr x17, [x29, x12]
 str x17, [sp, #160] // alpha
 ldr x12, [x16, #8]
 ldr x12, [x29, x12]
 ldur x13, [x12, #7]
 ldur x14, [x12, #15]
 stp x13, x14, [sp, #128] // translation
 ldr x12, [x16, #16]
 ldr x12, [x29, x12]
 cmp x12, x22
 b.eq .Lunit_scale
 ldur x13, [x12, #7]
 b .Lscale_done
.Lunit_scale:
 mov x13, #0x3ff0
 lsl x13, x13, #48
.Lscale_done:
 str x13, [sp, #144]
 ldr x12, [x16, #24]
 ldr x12, [x29, x12]
 ldur x13, [x12, #15]
 str x13, [sp, #152] // native Transform.scale origin.y
 send_dock
.Lvisible_end:
 restore_dock
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakDockVisibleReturn,.-HyperTweakDockVisibleReturn
.p2align 2
.global HyperTweakDockHiddenReturn
.hidden HyperTweakDockHiddenReturn
.type HyperTweakDockHiddenReturn,%function
HyperTweakDockHiddenReturn:
 bti c
 save_dock
 stp xzr, xzr, [sp, #128]
 mov x12, #0x3ff0
 lsl x12, x12, #48
 str x12, [sp, #144]
 stp xzr, xzr, [sp, #152] // origin, opacity: native returns an empty row.
 send_dock 7
.Lhidden_end:
 adrp x16, g_dock_frame_pool
 add x16, x16, :lo12:g_dock_frame_pool
 ldr x16, [x16]
 ldr x0, [x27, x16]
 str x0, [sp, #0] // Restore the displaced host pool load as well as the return frame.
 restore_dock
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakDockHiddenReturn,.-HyperTweakDockHiddenReturn
.macro controller_packet done
 stp xzr, xzr, [sp, #128]
 mov x12, #0x3ff0
 lsl x12, x12, #48
 str x12, [sp, #144]
 str xzr, [sp, #152]
 str x12, [sp, #160]
.endm
.p2align 2
.global HyperTweakDockControllerScaleReturn
.hidden HyperTweakDockControllerScaleReturn
.type HyperTweakDockControllerScaleReturn,%function
HyperTweakDockControllerScaleReturn:
 bti c
 mov x0, x22
 save_dock
 controller_packet .Lcontroller_scale_end
 ldur x12, [x29, #-16]
 tbz x12, #0, .Lcontroller_scale_end
 cmp x12, x22
 b.eq .Lcontroller_scale_end
 ldur x13, [x12, #-1]
 ubfx x13, x13, #12, #20
 adrp x16, g_dock_frame_double_cid
 add x16, x16, :lo12:g_dock_frame_double_cid
 ldr x16, [x16]
 cmp x13, x16
 b.ne .Lcontroller_scale_end
 ldur x12, [x12, #7]
 str x12, [sp, #144]
 send_dock 3
.Lcontroller_scale_end:
 restore_dock
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakDockControllerScaleReturn,.-HyperTweakDockControllerScaleReturn
.p2align 2
.global HyperTweakDockControllerAlphaReturn
.hidden HyperTweakDockControllerAlphaReturn
.type HyperTweakDockControllerAlphaReturn,%function
HyperTweakDockControllerAlphaReturn:
 bti c
 mov x0, x22
 save_dock
 controller_packet .Lcontroller_alpha_end
 ldur x12, [x29, #-24]
 str x12, [sp, #160]
 send_dock 4
.Lcontroller_alpha_end:
 restore_dock
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakDockControllerAlphaReturn,.-HyperTweakDockControllerAlphaReturn
.p2align 2
.global HyperTweakDockControllerSetReturn
.hidden HyperTweakDockControllerSetReturn
.type HyperTweakDockControllerSetReturn,%function
HyperTweakDockControllerSetReturn:
 bti c
 mov x0, x22
 save_dock
 controller_packet .Lcontroller_set_end
 ldur x12, [x29, #-40]
 str x12, [sp, #144]
 ldur x12, [x29, #-48]
 str x12, [sp, #160]
 send_dock 5
.Lcontroller_set_end:
 restore_dock
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakDockControllerSetReturn,.-HyperTweakDockControllerSetReturn
// A hidden/cached Widget cannot publish the Hotseat animation's evolving pose.
// Follow only the callback-owned controller/Rx fields decoded from its setters.
.macro local_scalar field, dest
 adrp x16, g_dock_tick_layout
 add x16, x16, :lo12:g_dock_tick_layout
 ldr x12, [x16]
 ldr x14, [x29, x12]
 ldr x12, [x16, #\field]
 ldr w12, [x14, x12]
 tbz w12, #0, .Llocal_end
 add x12, x12, x28, lsl #32
 cmp x12, x22
 b.eq .Llocal_end
 ldr x13, [x16, #24]
 ldr w12, [x12, x13]
 tbz w12, #0, .Llocal_end
 add x12, x12, x28, lsl #32
 cmp x12, x22
 b.eq .Llocal_end
 ldur x13, [x12, #-1]
 ubfx x13, x13, #12, #20
 adrp x16, g_dock_frame_double_cid
 add x16, x16, :lo12:g_dock_frame_double_cid
 ldr x16, [x16]
 cmp x13, x16
 b.ne .Llocal_end
 ldur x13, [x12, #7]
 str x13, [sp, #\dest]
.endm
.p2align 2
.global HyperTweakDockLocalTickReturn
.hidden HyperTweakDockLocalTickReturn
.type HyperTweakDockLocalTickReturn,%function
HyperTweakDockLocalTickReturn:
 bti c
 mov x0, x22
 save_dock
 controller_packet .Llocal_end
 local_scalar 8, 144
 local_scalar 16, 136
 send_dock 6
.Llocal_end:
 restore_dock
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakDockLocalTickReturn,.-HyperTweakDockLocalTickReturn
// Capture the listener's original enum argument; never infer editing from alpha.
.p2align 2
.global HyperTweakDockEditingReturn
.hidden HyperTweakDockEditingReturn
.type HyperTweakDockEditingReturn,%function
HyperTweakDockEditingReturn:
 bti c
 mov x0, x22
 save_dock
 stp xzr, xzr, [sp, #128]
 mov x13, #0x3ff0
 lsl x13, x13, #48
 str x13, [sp, #144]
 str xzr, [sp, #152]
 adrp x16, g_dock_edit_disabled_pool
 add x16, x16, :lo12:g_dock_edit_disabled_pool
 ldr x16, [x16]
 ldr x16, [x27, x16]
 ldr x12, [x29, #16]
 cmp w12, w16
 b.eq .Lediting_visible
 // Quick editing and shortcut menus are ordinary desktop interactions, not
 // the full-screen editor. Resolve by enum names, never by enum ordinals.
 tbz x12, #0, .Lediting_not_visible
 cmp x12, x22
 b.eq .Lediting_not_visible
 ldur x13, [x12, #-1]
 ldur x14, [x16, #-1]
 ubfx x13, x13, #12, #20
 ubfx x14, x14, #12, #20
 cmp x13, x14
 b.ne .Lediting_not_visible
 ldur w16, [x16, #15] // _Enum.name, compressed pointer.
 tbz w16, #0, .Lediting_not_visible
 add x16, x16, x28, lsl #32
 cmp x16, x22
 b.eq .Lediting_not_visible
 ldur x14, [x16, #7]
 cmp x14, #16 // Disabled's eight-character name proves the string ABI.
 b.ne .Lediting_not_visible
 ldur x14, [x16, #15]
 adrp x17, .Ldisabled_name
 add x17, x17, :lo12:.Ldisabled_name
 ldr x17, [x17]
 cmp x14, x17
 b.ne .Lediting_not_visible
 ldur x14, [x16, #-1]
 ubfx x14, x14, #12, #20
 ldur w12, [x12, #15]
 tbz w12, #0, .Lediting_not_visible
 add x12, x12, x28, lsl #32
 cmp x12, x22
 b.eq .Lediting_not_visible
 ldur x13, [x12, #-1]
 ubfx x13, x13, #12, #20
 cmp x13, x14
 b.ne .Lediting_not_visible
 ldur x13, [x12, #7]
 cmp x13, #10
 b.eq .Lquick_name
 cmp x13, #24
 b.ne .Lediting_not_visible
 ldur x13, [x12, #15]
 adrp x17, .Lshortcut_name
 add x17, x17, :lo12:.Lshortcut_name
 ldr x14, [x17]
 cmp x13, x14
 b.ne .Lediting_not_visible
 ldur w13, [x12, #23]
 ldr w14, [x17, #8]
 cmp w13, w14
 b.ne .Lediting_not_visible
 b .Lediting_interaction
.Lquick_name:
 ldur w13, [x12, #15]
 adrp x17, .Lquick_name_bytes
 add x17, x17, :lo12:.Lquick_name_bytes
 ldr w14, [x17]
 cmp w13, w14
 b.ne .Lediting_not_visible
 ldrb w13, [x12, #19]
 cmp w13, #'k'
 b.ne .Lediting_not_visible
.Lediting_interaction:
 mov x13, #0x3ff0
 lsl x13, x13, #48
 str x13, [sp, #152] // Typed edit packet: origin=1 means quick/menu interaction.
.Lediting_visible:
 mov x13, #0x3ff0
 lsl x13, x13, #48
 str x13, [sp, #160]
 b .Lediting_publish
.Lediting_not_visible:
 str xzr, [sp, #160]
.Lediting_publish:
 send_dock 2
.Lediting_end:
 restore_dock
 mov x15, x29
 ldp x29, x30, [x15], #16
 ret
.size HyperTweakDockEditingReturn,.-HyperTweakDockEditingReturn
.section .rodata
.p2align 3
.Ldisabled_name: .ascii "disabled"
.Lshortcut_name: .ascii "shortcutMenu"
.Lquick_name_bytes: .ascii "quick"
.section .note.GNU-stack,"",%progbits
