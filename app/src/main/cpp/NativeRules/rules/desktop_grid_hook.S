// SPDX-License-Identifier: Apache-2.0
// ComputePipeline owns the real grid. Only primitive counts are overridden;
// all allocations, layout calculation and object publication remain in Dart.
.text
.p2align 2
.global HyperTweakDesktopGridHandlerHook
.hidden HyperTweakDesktopGridHandlerHook
.type HyperTweakDesktopGridHandlerHook,%function
HyperTweakDesktopGridHandlerHook:
 bti c
 adrp x16,hypertweak_grid_geometry
 add x16,x16,:lo12:hypertweak_grid_geometry
 ldar w17,[x16]
 cbz w17,.Lhandler_original
 sub sp,sp,#16
 str x9,[sp]
 and x9,x17,#0xff
 adrp x16,hypertweak_grid_context_x
 add x16,x16,:lo12:hypertweak_grid_context_x
 ldar w16,[x16]
 add x16,x2,x16
 str x9,[x16]
 lsr x9,x17,#8
 adrp x16,hypertweak_grid_context_y
 add x16,x16,:lo12:hypertweak_grid_context_y
 ldar w16,[x16]
 add x16,x2,x16
 str x9,[x16]
 ldr x9,[sp]
 add sp,sp,#16
.Lhandler_original:
 stp x29,x30,[x15,#-16]!
 mov x29,x15
 sub x15,x15,#0x20
 stur x22,[x29,#-8]
 adrp x16,hypertweak_grid_handler_continue
 add x16,x16,:lo12:hypertweak_grid_handler_continue
 ldar x16,[x16]
 br x16
.size HyperTweakDesktopGridHandlerHook,.-HyperTweakDesktopGridHandlerHook

.global HyperTweakDesktopGridVariableHook
.hidden HyperTweakDesktopGridVariableHook
.type HyperTweakDesktopGridVariableHook,%function
HyperTweakDesktopGridVariableHook:
 bti c
 adrp x16,hypertweak_grid_geometry
 add x16,x16,:lo12:hypertweak_grid_geometry
 ldar w17,[x16]
 cbz w17,.Lvariable_original
 adrp x16,hypertweak_grid_fixed_entry
 add x16,x16,:lo12:hypertweak_grid_fixed_entry
 ldar x16,[x16]
 br x16
.Lvariable_original:
 stp x29,x30,[x15,#-16]!
 mov x29,x15
 sub x15,x15,#0x38
 stur x1,[x29,#-8]
 adrp x16,hypertweak_grid_variable_continue
 add x16,x16,:lo12:hypertweak_grid_variable_continue
 ldar x16,[x16]
 br x16
.size HyperTweakDesktopGridVariableHook,.-HyperTweakDesktopGridVariableHook

// A variable-layout ComputeInput can have no fixed-row hint. Override the loaded
// primitive before the stock null check/unboxing, keeping its genuine return PCs.
.global HyperTweakDesktopGridRowsHook
.hidden HyperTweakDesktopGridRowsHook
.type HyperTweakDesktopGridRowsHook,%function
HyperTweakDesktopGridRowsHook:
 bti c
 adrp x16,hypertweak_grid_geometry
 add x16,x16,:lo12:hypertweak_grid_geometry
 ldar w17,[x16]
 cbz w17,.Lrows_original
 lsr x3,x17,#8
 lsl x3,x3,#1
 cmp w3,w22
 b .Lrows_continue
.Lrows_original:
 adrp x16,hypertweak_grid_input_rows
 add x16,x16,:lo12:hypertweak_grid_input_rows
 ldar w16,[x16]
 add x16,x0,x16
 ldr w3,[x16]
 add x3,x3,x28,lsl #32
 cmp w3,w22
 b.eq .Lrows_null
.Lrows_continue:
 adrp x16,hypertweak_grid_rows_continue
 add x16,x16,:lo12:hypertweak_grid_rows_continue
 ldar x16,[x16]
 br x16
.Lrows_null:
 adrp x16,hypertweak_grid_rows_null
 add x16,x16,:lo12:hypertweak_grid_rows_null
 ldar x16,[x16]
 br x16
.size HyperTweakDesktopGridRowsHook,.-HyperTweakDesktopGridRowsHook

.global HyperTweakDesktopGridClampHook
.hidden HyperTweakDesktopGridClampHook
.type HyperTweakDesktopGridClampHook,%function
HyperTweakDesktopGridClampHook:
 bti c
 mov x2,#5
 adrp x16,hypertweak_grid_geometry
 add x16,x16,:lo12:hypertweak_grid_geometry
 ldar w17,[x16]
 cbz w17,.Lmaximum
 mov x2,#6
.Lmaximum:
 adrp x16,hypertweak_grid_maximum_field
 add x16,x16,:lo12:hypertweak_grid_maximum_field
 ldar w16,[x16]
 add x16,x1,x16
 str x2,[x16]
 mov x3,#-1
 adrp x16,hypertweak_grid_unset_field
 add x16,x16,:lo12:hypertweak_grid_unset_field
 ldar w16,[x16]
 add x16,x1,x16
 str x3,[x16]
 adrp x16,hypertweak_grid_clamp_continue
 add x16,x16,:lo12:hypertweak_grid_clamp_continue
 ldar x16,[x16]
 br x16
.size HyperTweakDesktopGridClampHook,.-HyperTweakDesktopGridClampHook
.section .note.GNU-stack,"",%progbits
