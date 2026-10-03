// SPDX-License-Identifier: Apache-2.0
.text
.p2align 2
.global TestFolderOpen
.type TestFolderOpen, %function
TestFolderOpen:
    stp x29, x30, [sp, #-32]!
    stp x26, x15, [sp, #16]
    mov x26, x0
    mov x15, x3
    cmp x1, x1
    bl HyperTweakFolderOpenHook
    ldp x26, x15, [sp, #16]
    ldp x29, x30, [sp], #32
    ret
.global TestFolderContinuation
.type TestFolderContinuation, %function
TestFolderContinuation:
    adrp x16, hypertweak_test_receiver
    add x16, x16, :lo12:hypertweak_test_receiver
    str x1, [x16]
    adrp x16, hypertweak_test_folder
    add x16, x16, :lo12:hypertweak_test_folder
    str x2, [x16]
    mrs x17, nzcv
    adrp x16, hypertweak_test_flags
    add x16, x16, :lo12:hypertweak_test_flags
    str x17, [x16]
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
.global TestFolderGetter
.type TestFolderGetter, %function
TestFolderGetter:
    stp x29, x30, [sp, #-32]!
    str x15, [sp, #16]
    mov x15, x1
    adr x17, .Lgetter_return
    stp x29, x17, [x15, #-16]!
    mov x29, x15
    b HyperTweakFolderColumnsHook
.Lgetter_return:
    ldr x15, [sp, #16]
    ldp x29, x30, [sp], #32
    ret
.section .note.GNU-stack,"",%progbits
