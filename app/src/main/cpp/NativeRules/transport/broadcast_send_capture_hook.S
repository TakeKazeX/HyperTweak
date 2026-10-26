    .text
    .p2align 2
    .globl MiuiHomeHyosBroadcastSendHook
    .hidden MiuiHomeHyosBroadcastSendHook
    .type MiuiHomeHyosBroadcastSendHook, %function
MiuiHomeHyosBroadcastSendHook:
    // private scene::send_broadcast receives the intent in x0 and a pointer
    // to the Shared<Runtime> holder in x1.  It returns a large Rust Result
    // through the indirect result pointer x8. Preserve every argument and
    // x8 while recording the holder's live object.
    bti c
    sub sp, sp, #0x60
    stp x0, x1, [sp, #0x00]
    stp x2, x3, [sp, #0x10]
    stp x4, x5, [sp, #0x20]
    stp x6, x7, [sp, #0x30]
    stp x8, x30, [sp, #0x40]
    ldr x0, [sp, #0x08]
    bl MiuiHomeHyosCaptureBroadcastRuntime
    ldp x0, x1, [sp, #0x00]
    ldp x2, x3, [sp, #0x10]
    ldp x4, x5, [sp, #0x20]
    ldp x6, x7, [sp, #0x30]
    ldp x8, x30, [sp, #0x40]
    adrp x16, g_original_broadcast_send
    add x16, x16, :lo12:g_original_broadcast_send
    ldar x16, [x16]
    add sp, sp, #0x60
    br x16
    .size MiuiHomeHyosBroadcastSendHook, .-MiuiHomeHyosBroadcastSendHook

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
