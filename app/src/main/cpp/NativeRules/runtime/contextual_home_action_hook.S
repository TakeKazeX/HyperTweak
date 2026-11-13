    .text
    .p2align 2
    .global MiuiHomeHyosDartContextualActionHook
    .hidden MiuiHomeHyosDartContextualActionHook
    .type MiuiHomeHyosDartContextualActionHook, %function

// Home has already recognized the long press and awaited its action setting.
// Change only the resolved value for NavLongPress. No calls, allocation, input,
// timers or native setting writes run on the Dart stack. The resolver proves
// the displaced instructions and the next instruction's unconditional x16 write.
MiuiHomeHyosDartContextualActionHook:
    bti c
    sub sp, sp, #0x60
    stp x0, x1, [sp, #0x00]
    stp x2, x8, [sp, #0x10]
    stp x12, x13, [sp, #0x20]
    stp x14, x15, [sp, #0x30]
    stp x16, x17, [sp, #0x40]
    mrs x17, nzcv
    str x17, [sp, #0x50]
    adrp x16, g_contextual_home_action_active_count
    add x16, x16, :lo12:g_contextual_home_action_active_count
.Lhome_enter:
    ldaxr w17, [x16]
    add w17, w17, #1
    stlxr w12, w17, [x16]
    cbnz w12, .Lhome_enter
    adrp x16, g_contextual_home_action_retiring
    add x16, x16, :lo12:g_contextual_home_action_retiring
    ldar w17, [x16]
    cbnz w17, .Lhome_leave
    adrp x16, g_contextual_home_action_ready
    add x16, x16, :lo12:g_contextual_home_action_ready
    ldar w17, [x16]
    cbz w17, .Lhome_leave
    adrp x16, g_contextual_home_preference
    add x16, x16, :lo12:g_contextual_home_preference
    ldar w17, [x16]
    cbz w17, .Lhome_leave
    adrp x16, g_contextual_home_action_owner_epoch
    add x16, x16, :lo12:g_contextual_home_action_owner_epoch
    ldar x12, [x16]
    adrp x16, g_dart_state_owner_epoch
    add x16, x16, :lo12:g_dart_state_owner_epoch
    ldar x13, [x16]
    cmp x12, x13
    b.ne .Lhome_leave
    adrp x16, g_contextual_home_context_frame_offset
    ldr x16, [x16, :lo12:g_contextual_home_context_frame_offset]
    ldr x2, [x29, x16]
    ldur w12, [x2, #0xf]
    add x12, x12, x28, lsl #32
    tbz x12, #0, .Lhome_leave
    cmp w12, w22
    b.eq .Lhome_leave
    // The existing snapshot family's String equality also proves this tagged
    // OneByteString layout. Check all twelve bytes of the exact source name.
    ldur x13, [x12, #-1]
    ubfx x13, x13, #12, #20
    cmp x13, #94
    b.ne .Lhome_leave
    ldur w13, [x12, #7]
    cmp w13, #24
    b.ne .Lhome_leave
    adrp x14, .Lnav_long_press
    add x14, x14, :lo12:.Lnav_long_press
    ldr x17, [x14]
    ldur x13, [x12, #15]
    cmp x13, x17
    b.ne .Lhome_leave
    ldr w17, [x14, #8]
    ldur w13, [x12, #23]
    cmp w13, w17
    b.ne .Lhome_leave
    adrp x16, g_contextual_home_cts_pool_offset
    ldr x16, [x16, :lo12:g_contextual_home_cts_pool_offset]
    ldr x12, [x27, x16]
    tbz x12, #0, .Lhome_leave
    ldur x13, [x12, #-1]
    ubfx x13, x13, #12, #20
    cmp x13, #94
    b.ne .Lhome_leave
    str x12, [sp, #0x00]
    adrp x16, g_contextual_home_action_count
    add x16, x16, :lo12:g_contextual_home_action_count
.Lhome_count:
    ldxr w17, [x16]
    add w17, w17, #1
    stxr w12, w17, [x16]
    cbnz w12, .Lhome_count
.Lhome_leave:
    adrp x16, g_contextual_home_action_active_count
    add x16, x16, :lo12:g_contextual_home_action_active_count
.Lhome_exit:
    ldaxr w17, [x16]
    sub w17, w17, #1
    stlxr w12, w17, [x16]
    cbnz w12, .Lhome_exit
    ldr x17, [sp, #0x50]
    msr nzcv, x17
    ldp x0, x1, [sp, #0x00]
    ldp x2, x8, [sp, #0x10]
    ldp x12, x13, [sp, #0x20]
    ldp x14, x15, [sp, #0x30]
    ldp x16, x17, [sp, #0x40]
    add sp, sp, #0x60
    // Replay the four displaced operations with the derived frame location.
    mov x1, x0
    adrp x16, g_contextual_home_result_frame_offset
    ldr x16, [x16, :lo12:g_contextual_home_result_frame_offset]
    str x1, [x29, x16]
    ldur x0, [x1, #-1]
    ubfx x0, x0, #12, #20
    adrp x16, g_contextual_home_action_resume
    ldr x16, [x16, :lo12:g_contextual_home_action_resume]
    br x16
    .size MiuiHomeHyosDartContextualActionHook, .-MiuiHomeHyosDartContextualActionHook

    .section .rodata
    .p2align 3
.Lnav_long_press:
    .ascii "NavLongPress"
    .section .note.GNU-stack,"",%progbits
