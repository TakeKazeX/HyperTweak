    .text
    .p2align 2
    .globl MiuiHomeHyosBroadcastOptionsTailHook
    .hidden MiuiHomeHyosBroadcastOptionsTailHook
    .type MiuiHomeHyosBroadcastOptionsTailHook, %function
MiuiHomeHyosBroadcastOptionsTailHook:
    // Preserve x0-x8, x18, and the caller's SP exactly.  In launcher 4371 the
    // Rust caller stores Option<Bundle> at SP+0x58 and supplies the private
    // method's 16-byte Result destination in x8.
    bti c
    adrp x9, miui_home_hyos_broadcast_option_thread
    add x9, x9, :lo12:miui_home_hyos_broadcast_option_thread
    ldar x10, [x9]
    mrs x11, tpidr_el0
    cmp x10, x11
    b.ne .Ltail_original

    adrp x12, miui_home_hyos_broadcast_option_state
    add x12, x12, :lo12:miui_home_hyos_broadcast_option_state
    ldar w13, [x12]
    cbnz w13, .Ltail_original

    adrp x10, miui_home_hyos_broadcast_option_bundle
    add x10, x10, :lo12:miui_home_hyos_broadcast_option_bundle
    ldar x13, [x10]
    cbz x13, .Ltail_original

    ldr x10, [sp, #0x58]
    cbnz x10, .Lreject_existing_options
    str x13, [sp, #0x58]
    mov w13, #1
    stlr w13, [x12]
    b .Ltail_original

.Lreject_existing_options:
    mov w13, #2
    stlr w13, [x12]

.Ltail_original:
    adrp x16, miui_home_hyos_broadcast_original
    add x16, x16, :lo12:miui_home_hyos_broadcast_original
    ldar x16, [x16]
    br x16
    .size MiuiHomeHyosBroadcastOptionsTailHook, .-MiuiHomeHyosBroadcastOptionsTailHook

    // Raw assembly does not get Clang's -mbranch-protection property note
    // automatically.  Match the C++ object's BTI/PAC/GCS declaration so the
    // linked module retains Android's branch-protection advertisement.
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
