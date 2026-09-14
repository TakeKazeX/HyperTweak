// SPDX-License-Identifier: Apache-2.0
//
// Replacement for GridController.folderGridViewCol in MiuiHome's Dart AOT
// snapshot. Dart small integers are tagged as (value << 1), so the C++ side
// publishes 6, 8, or 10 for logical column counts 3, 4, or 5.
//
// These are raw AArch64 Dart replacements. x15 is Dart's stack pointer and
// x22/x26/x27/x28 are Dart-reserved registers; the replacements only touch
// the normal AAPCS64 scratch registers x16/x17 and never call C++ or a Dart
// trampoline.
    .text
    .p2align 2
    .global HyperTweakFolderColumnsHook
    .hidden HyperTweakFolderColumnsHook
    .type HyperTweakFolderColumnsHook, %function
HyperTweakFolderColumnsHook:
    bti c
    adrp x16, hypertweak_folder_columns_hits
    add x16, x16, :lo12:hypertweak_folder_columns_hits
    ldr w17, [x16]
    add w17, w17, #1
    str w17, [x16]
    adrp x16, hypertweak_folder_columns_smi
    add x16, x16, :lo12:hypertweak_folder_columns_smi
    ldar x0, [x16]
    // The hook is installed at the exact four-instruction return epilogue of
    // GridController.folderGridViewCol. The original getter body has already
    // run, including Xiaomi's state/cache reads used by folder animation.
    mov x15, x29
    ldp x29, x30, [x15], #0x10
    ret
    .size HyperTweakFolderColumnsHook, .-HyperTweakFolderColumnsHook

    // These two hooks replace only the exact return epilogues of
    // FolderLayout1x1.iconColumCount and itemsMaxCount. Their function bodies
    // (including static initialization checks) remain untouched:
    // iconColumCount -> 3, itemsMaxCount -> 9.
    .macro FOLDER_PREVIEW_RETURN_HOOK name, value
    .global \name
    .hidden \name
    .type \name, %function
\name:
    bti c
    mov x0, #\value
    mov x15, x29
    ldp x29, x30, [x15], #0x10
    ret
    .size \name, .-\name
    .endm

    FOLDER_PREVIEW_RETURN_HOOK HyperTweakFolderPreviewIconColumnsHook, 3
    FOLDER_PREVIEW_RETURN_HOOK HyperTweakFolderPreviewItemsMaxCountHook, 9

    // setPreviewItemInfos reads the preview static column field in the middle
    // of a long function. Replace that four-instruction read/check block with
    // the same local value (Dart SMI(3)), then resume at the already-proven
    // post-check continuation. No shared static field is mutated, and no
    // generic trampoline is entered while a Dart frame is active.
    .global HyperTweakFolderPreviewSetItemsHook
    .hidden HyperTweakFolderPreviewSetItemsHook
    .type HyperTweakFolderPreviewSetItemsHook, %function
HyperTweakFolderPreviewSetItemsHook:
    bti c
    mov x0, #6
    adrp x16, hypertweak_folder_preview_set_items_continuation
    add x16, x16, :lo12:hypertweak_folder_preview_set_items_continuation
    ldar x16, [x16]
    br x16
    .size HyperTweakFolderPreviewSetItemsHook, .-HyperTweakFolderPreviewSetItemsHook

    // Match the C++ objects' BTI/PAC/GCS GNU property declaration.
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
