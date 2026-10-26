    .text
    .p2align 2
    .global MiuiHomeHyosInputMonitorPilferHook
    .hidden MiuiHomeHyosInputMonitorPilferHook
    .type MiuiHomeHyosInputMonitorPilferHook, %function
MiuiHomeHyosInputMonitorPilferHook:
    bti c
    mov x1, x30
    b MiuiHomeHyosInputMonitorPilferImpl
    .size MiuiHomeHyosInputMonitorPilferHook, .-MiuiHomeHyosInputMonitorPilferHook

    // Match the C++ object's BTI/PAC/GCS GNU property declaration.
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
