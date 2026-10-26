    // Replacement for RecentsPageState._insertClearButtonOverlay in the
    // launcher's Dart AOT snapshot (libapp.so).
    //
    // The original inserts the ClearBtnOverlay that hosts the recents
    // "clean up background apps" button. Making the entry return immediately
    // removes the button without needing to know anything about the Dart bool
    // objects that gate the other recents overlays.
    //
    // Register discipline: this runs inside Dart AOT code, where x15 is Dart's
    // own stack pointer and x22/x26/x27/x28 are Dart reserved registers. x15 in
    // particular is *caller-saved* under AAPCS64, so calling into C here would
    // let the compiler clobber it and destroy the Dart stack. This replacement
    // therefore never leaves assembly: it only touches x16/x17, which the
    // caller has already declared dead at a call site.
    //
    // The single call site ignores the result, so x0 keeps whatever the caller
    // passed in. Nothing is pushed onto either stack, so a bare ret is a
    // complete, balanced return.
    .text
    .p2align 2
    .global HyperTweakRecentsClearButtonInsertHook
    .hidden HyperTweakRecentsClearButtonInsertHook
    .type HyperTweakRecentsClearButtonInsertHook, %function
HyperTweakRecentsClearButtonInsertHook:
    bti c
    adrp x16, hypertweak_clear_button_hits
    add x16, x16, :lo12:hypertweak_clear_button_hits
    ldr w17, [x16]
    add w17, w17, #1
    str w17, [x16]
    ret
    .size HyperTweakRecentsClearButtonInsertHook, .-HyperTweakRecentsClearButtonInsertHook

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
