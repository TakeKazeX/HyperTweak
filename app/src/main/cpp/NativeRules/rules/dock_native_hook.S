// SPDX-License-Identifier: Apache-2.0
// Each gate preserves the original Dart frame and resumes a verified host
// continuation. Only the idle hook can publish a new active owner.
.text
.p2align 2

.macro dock_load out, symbol
    adrp \out, \symbol
    add \out, \out, :lo12:\symbol
    ldar \out, [\out]
.endm

.macro dock_branch symbol
    dock_load x16, \symbol
    br x16
.endm

.macro dock_owner target
    dock_load x16, g_native_dock_owner
    eor x16, x16, x26
    cbz x16, \target
.endm

.macro dock_entry name
    .global \name
    .hidden \name
    .type \name, %function
\name:
    bti c
.endm

dock_entry HyperTweakNativeDockGlassBranch
    // Displaced: ldur w1, [x0, #flag]; add x1, x1, heap, lsl #32;
    // tbnz w1, #4, skip; ldur x3, [fp, #-0x18]. The owner forces the phone
    // Hotseat to build its own background child.
    dock_owner .Lglass_show
    dock_load x16, g_native_dock_glass_field
    ldr w1, [x0, x16]
    add x1, x1, x28, lsl #32
    tbz w1, #4, .Lglass_show
    dock_branch g_native_dock_glass_skip
.Lglass_show:
    ldur x3, [x29, #-0x18]
    dock_branch g_native_dock_glass_continue
.size HyperTweakNativeDockGlassBranch, .-HyperTweakNativeDockGlassBranch

// Field offsets of NativeDockGlassFrames (dock_native_rule.cpp).
.equ GLASS_RESUME_CONTINUE, 0x00
.equ GLASS_RESUME_LIST, 0x08
.equ GLASS_RESUME_RESULT, 0x10
.equ GLASS_RESUME_LENGTH, 0x18
.equ GLASS_CONSTRAINTS_ARG, 0x20
.equ GLASS_MAX_HEIGHT, 0x28
.equ GLASS_INSETS_ENTRY, 0x30
.equ GLASS_INSETS_FRAME, 0x38
.equ GLASS_INSETS_LEFT, 0x40
.equ GLASS_INSETS_TOP, 0x48
.equ GLASS_INSETS_RIGHT, 0x50
.equ GLASS_INSETS_BOTTOM, 0x58
.equ GLASS_INSETS_CID, 0x60
.equ GLASS_PADDING_RETURN, 0x68
.equ GLASS_PADDING_FRAME, 0x70
.equ GLASS_PADDING_SLOT, 0x78
.equ GLASS_PADDING_CID, 0x80
.equ GLASS_HEIGHT, 0x88
.equ GLASS_MARGIN, 0x90
.equ GLASS_BOTTOM, 0x98
.equ GLASS_RADIUS, 0xa0
.equ GLASS_LIMIT, 0xa8
.equ GLASS_STAGE, 0xb0
.equ GLASS_THREAD, 0xb8
.equ GLASS_OBSERVED, 0xc0
.equ GLASS_BUILD_FRAME, 0xc8
.equ GLASS_BUILD_CHILD, 0xd0
.equ GLASS_CONTROLLER, 0xd8
.equ GLASS_CENTER_CALL, 0xe0
.equ GLASS_CENTER_RETURN, 0xe8
.equ GLASS_CENTER_ITEM, 0xf0
.equ GLASS_CENTER_SLOT, 0xf8
.equ GLASS_CENTER_FIELD, 0x100
.equ GLASS_CENTER_CONTINUE, 0x108
.equ GLASS_WRAP_POSITION, 0x110
.equ GLASS_WRAP_ENTRY, 0x118
.equ GLASS_TITLE_SLOT, 0x120
.equ GLASS_TITLE_FIELD, 0x128
.equ GLASS_TITLE_POOL, 0x130
.equ GLASS_TITLE_CONTINUE, 0x138
.equ GLASS_OBSERVED_CENTER, 0x140
.equ GLASS_FIXED_FIELD, 0x150
.equ GLASS_ADAPTIVE, 0x158
.equ GLASS_ITEMS_SLOT, 0x160
.equ GLASS_ITEMS_FIELD, 0x168
.equ GLASS_MAX_WIDTH, 0x170
.equ GLASS_COLUMNS, 0x178
.equ GLASS_OBSERVED_WIDTH, 0x180
.equ GLASS_OBSERVED_ITEMS, 0x188
.equ GLASS_OBSERVED_FIRST, 0x190
.equ GLASS_OBSERVED_LAST, 0x198
.equ GLASS_OBSERVED_BUILT, 0x1a0
.equ GLASS_STATIC_TABLE, 0x1a8
.equ GLASS_SENTINEL_POOL, 0x1b0
.equ GLASS_FLAG_STATIC, 0x1b8
.equ GLASS_FLAG_FIELD, 0x1c0
.equ GLASS_CONFIG_STATIC, 0x1c8
.equ GLASS_CONFIG_RX, 0x1d0
.equ GLASS_RX_VALUE, 0x1d8
.equ GLASS_CELL_FIELD, 0x1e0
.equ GLASS_ALT_CELL_FIELD, 0x1e8
.equ GLASS_CELL_EXTRA, 0x1f0
.equ GLASS_OBSERVED_CELL, 0x1f8

.macro glass_owned_frame fail
    // Only the item builder frame this module opened returns to the Hotseat
    // call site; a host frame of the same function returns elsewhere.
    ldr x9, [x16, #GLASS_THREAD]
    cmp x9, x26
    b.ne \fail
    ldr x9, [x29, #8]
    ldr x10, [x16, #GLASS_RESUME_CONTINUE]
    sub x10, x10, #16
    cmp x9, x10
    b.ne \fail
.endm

.macro glass_frames out
    adrp \out, g_native_dock_glass_frames
    add \out, \out, :lo12:g_native_dock_glass_frames
.endm

// Open a host frame of \size bytes whose saved PC is x30, with every slot null.
.macro glass_frame size
    stp x29, x30, [x15, #-16]!
    mov x29, x15
    sub x15, x15, \size
    mov x9, x15
1:
    cmp x9, x29
    b.hs 2f
    str x22, [x9], #8
    b 1b
2:
.endm

dock_entry HyperTweakNativeDockGlassFactory
    // Only the verified Hotseat call site is redirected. The stock body is a
    // single allocation, so the stub returns straight to the host caller.
    dock_load x16, g_native_dock_glass_return
    eor x16, x16, x30
    cbnz x16, .Lglass_stock
    dock_owner .Lglass_native
.Lglass_stock:
    dock_branch g_native_dock_glass_stub
.Lglass_native:
    // Insets inside the LayoutBuilder area: equal side margins, the bottom
    // offset, and a top inset that leaves the configured height.
    glass_frames x16
    ldr x9, [x16, #GLASS_CONSTRAINTS_ARG]
    ldr x10, [x29, x9]
    ldr x9, [x16, #GLASS_MAX_HEIGHT]
    ldr d0, [x10, x9]
    str d0, [x16, #GLASS_OBSERVED]
    ldr d1, [x16, #GLASS_HEIGHT]
    ldr d2, [x16, #GLASS_BOTTOM]
    ldr d3, [x16, #GLASS_MARGIN]
    movi d5, #0
    fadd d4, d1, d2
    fsub d4, d0, d4
    fcmp d1, d5
    b.ne 3f
    fmov d4, d5                     // height 0: fill above the bottom offset
3:
    fmaxnm d4, d4, d5
    ldr d6, [x16, #GLASS_LIMIT]
    fcmp d4, d6
    b.lt 4f
    fmov d4, d5                     // unbounded constraints: no top inset
4:
    // Adaptive width, read while x29 is still the Hotseat builder frame: the
    // icons keep the host Dock cell width and stay centered, so each missing
    // icon narrows the glass by one cell, half on each side; an empty row keeps
    // one cell. The item list must have the builder's own child-list class.
    str xzr, [x16, #GLASS_OBSERVED_BUILT]
    ldr x9, [x16, #GLASS_MAX_WIDTH]
    ldr d6, [x10, x9]
    str d6, [x16, #GLASS_OBSERVED_WIDTH]
    ldr x9, [x16, #GLASS_ADAPTIVE]
    cbz x9, 6f
    ldr d16, [x16, #GLASS_COLUMNS]
    fcmp d16, d5
    b.le 6f
    ldr d17, [x16, #GLASS_LIMIT]
    fcmp d6, d17                    // bounded and ordered: N set only for less
    b.pl 6f
    fcmp d6, d5
    b.le 6f
    ldr x9, [x16, #GLASS_ITEMS_SLOT]
    ldr x11, [x29, x9]
    tbz w11, #0, 6f
    cmp x11, x22
    b.eq 6f
    ldr x9, [x16, #GLASS_ITEMS_FIELD]
    ldr w12, [x11, x9]
    tbz w12, #0, 6f
    add x12, x12, x28, lsl #32
    cmp x12, x22
    b.eq 6f
    ldr x9, [x16, #GLASS_RESUME_LIST]
    ldr x13, [x29, x9]
    tbz w13, #0, 6f
    ldur w14, [x13, #-1]
    ldur w9, [x12, #-1]
    eor w14, w14, w9
    lsr w14, w14, #12               // same class id as the child list
    cbnz w14, 6f
    ldr x9, [x16, #GLASS_RESUME_LENGTH]
    ldr w14, [x12, x9]
    tbnz w14, #0, 6f                // the length is a Smi
    sbfx x14, x14, #1, #31
    str x14, [x16, #GLASS_OBSERVED_ITEMS]
    cmp x14, #1
    b.ge 5f
    mov x14, #1                     // an empty row keeps one cell
5:
    scvtf d7, x14
    fcmp d7, d16
    b.ge 6f
    // Cell width as the item builder computes it: a flag on one static
    // singleton picks config.alt or config.cell + extra, where config is the
    // Rx value on another. Uninitialized statics or values keep the margin.
    ldr x9, [x16, #GLASS_STATIC_TABLE]
    ldr x11, [x26, x9]
    cbz x11, 6f
    ldr x9, [x16, #GLASS_SENTINEL_POOL]
    ldr x13, [x27, x9]
    ldr x9, [x16, #GLASS_FLAG_STATIC]
    ldr x12, [x11, x9]
    tbz w12, #0, 6f
    cmp x12, x22
    b.eq 6f
    cmp x12, x13
    b.eq 6f
    ldr x9, [x16, #GLASS_FLAG_FIELD]
    ldr w12, [x12, x9]
    add x12, x12, x28, lsl #32
    ldr x9, [x16, #GLASS_CONFIG_STATIC]
    ldr x11, [x11, x9]
    tbz w11, #0, 6f
    cmp x11, x22
    b.eq 6f
    cmp x11, x13
    b.eq 6f
    ldr x9, [x16, #GLASS_CONFIG_RX]
    ldr w11, [x11, x9]
    tbz w11, #0, 6f
    add x11, x11, x28, lsl #32
    cmp x11, x22
    b.eq 6f
    ldr x9, [x16, #GLASS_RX_VALUE]
    ldr w11, [x11, x9]
    tbz w11, #0, 6f
    add x11, x11, x28, lsl #32
    cmp x11, x22
    b.eq 6f
    cmp x11, x13
    b.eq 6f
    tbnz w12, #4, 7f                // the host's own bool test: false picks alt
    ldr x9, [x16, #GLASS_CELL_FIELD]
    ldr d17, [x11, x9]
    ldr d18, [x16, #GLASS_CELL_EXTRA]
    fadd d17, d17, d18
    b 8f
7:
    ldr x9, [x16, #GLASS_ALT_CELL_FIELD]
    ldr d17, [x11, x9]
8:
    str d17, [x16, #GLASS_OBSERVED_CELL]
    fcmp d17, d5
    b.le 6f
    fcmp d17, d6                    // ordered and narrower than the area
    b.pl 6f
    fsub d7, d16, d7                // missing cells
    fmul d7, d7, d17
    fmov d17, #0.5
    fmadd d3, d7, d17, d3
    fmul d17, d6, d17
    fminnm d3, d3, d17              // never past the center
6:
    // EdgeInsets arithmetic frame: its four unboxed slots become the result.
    ldr x11, [x16, #GLASS_INSETS_FRAME]
    glass_frame x11
    ldr x9, [x16, #GLASS_INSETS_LEFT]
    str d3, [x29, x9]
    ldr x9, [x16, #GLASS_INSETS_TOP]
    str d4, [x29, x9]
    ldr x9, [x16, #GLASS_INSETS_RIGHT]
    str d3, [x29, x9]
    ldr x9, [x16, #GLASS_INSETS_BOTTOM]
    str d2, [x29, x9]
    str x26, [x16, #GLASS_THREAD]
    mov x9, #1
    str x9, [x16, #GLASS_STAGE]
    ldr x16, [x16, #GLASS_INSETS_ENTRY]
    br x16
.size HyperTweakNativeDockGlassFactory, .-HyperTweakNativeDockGlassFactory

dock_entry HyperTweakNativeDockGlassReturn
    // The Hotseat background call returns here. Stage 1 receives EdgeInsets
    // and chains Padding around the glass. Stage 2 receives that Padding and
    // wraps it in the Hotseat's unlock animation widget. Anything else,
    // including the finished wrapper, is the result and resumes unchanged.
    glass_frames x16
    ldr x17, [x16, #GLASS_STAGE]
    cbz x17, .Lglass_resume
    ldr x9, [x16, #GLASS_THREAD]
    cmp x9, x26
    b.ne .Lglass_resume
    str xzr, [x16, #GLASS_STAGE]
    tbz w0, #0, .Lglass_resume
    cmp x0, x22
    b.eq .Lglass_resume
    ldur w9, [x0, #-1]
    lsr w9, w9, #12
    cmp x17, #2
    b.eq .Lglass_padding
    cmp x17, #1
    b.ne .Lglass_resume
    ldr x10, [x16, #GLASS_INSETS_CID]
    cmp x9, x10
    b.ne .Lglass_resume
    // Padding builder frame, returning to the Hotseat caller. Its padding
    // slot is live at the child call, so the collector sees the insets.
    ldr x30, [x16, #GLASS_RESUME_CONTINUE]
    sub x30, x30, #16
    ldr x11, [x16, #GLASS_PADDING_FRAME]
    glass_frame x11
    ldr x9, [x16, #GLASS_PADDING_SLOT]
    str x0, [x29, x9]
    // Glass builder frame, returning into the Padding builder's child call.
    ldr x30, [x16, #GLASS_PADDING_RETURN]
    mov x11, #0x20
    glass_frame x11
    ldr d0, [x16, #GLASS_RADIUS]
    stur d0, [x29, #-0x20]
    mov x9, #2
    str x9, [x16, #GLASS_STAGE]
    dock_branch g_native_dock_glass_entry
.Lglass_padding:
    ldr x10, [x16, #GLASS_PADDING_CID]
    cmp x9, x10
    b.ne .Lglass_resume
    // Item builder frame, returning to the Hotseat caller, suspended at the
    // Dock center call. Its child slot keeps the Padding live; every other
    // tagged slot is null.
    ldr x9, [x16, #GLASS_CONTROLLER]
    ldr x10, [x29, x9]
    ldr x30, [x16, #GLASS_RESUME_CONTINUE]
    sub x30, x30, #16
    ldr x11, [x16, #GLASS_BUILD_FRAME]
    glass_frame x11
    ldr x9, [x16, #GLASS_BUILD_CHILD]
    str x0, [x29, x9]
    str x10, [x15]                  // receiver argument
    mov x0, x10
    mov x4, #0
    mov x9, #3
    str x9, [x16, #GLASS_STAGE]
    ldr x9, [x16, #GLASS_CENTER_CALL]
    ldr x30, [x16, #GLASS_CENTER_RETURN]
    add x16, x27, x9
    ldp x17, x5, [x16]
    br x17
.Lglass_resume:
    // Displaced: mov x2, x0; ldur x0, [fp, #list]; stur x2, [fp, #result];
    // ldur w1, [x0, #length].
    mov x2, x0
    ldr x9, [x16, #GLASS_RESUME_LIST]
    ldr x0, [x29, x9]
    ldr x9, [x16, #GLASS_RESUME_RESULT]
    str x2, [x29, x9]
    ldr x9, [x16, #GLASS_RESUME_LENGTH]
    ldr w1, [x0, x9]
    ldr x16, [x16, #GLASS_RESUME_CONTINUE]
    br x16
.size HyperTweakNativeDockGlassReturn, .-HyperTweakNativeDockGlassReturn

dock_entry HyperTweakNativeDockGlassCenter
    // After the item builder's Dock center call; x3 holds the result.
    // Displaced: ldur x2, [fp, #item]; stur x3, [fp, #center];
    // ldur w0, [x2, #field]; add x0, x0, heap, lsl #32.
    glass_frames x16
    ldr x17, [x16, #GLASS_STAGE]
    cmp x17, #3
    b.ne .Lcenter_stock
    glass_owned_frame .Lcenter_stock
    tbz w3, #0, .Lcenter_abort
    cmp x3, x22
    b.eq .Lcenter_abort
    ldur d0, [x3, #7]
    str d0, [x16, #GLASS_OBSERVED_CENTER]
    ldur d0, [x3, #0xf]
    str d0, [x16, #GLASS_OBSERVED_CENTER + 8]
    // The center becomes the wrapper position; the info slot stays null.
    ldr x9, [x16, #GLASS_WRAP_POSITION]
    str x3, [x29, x9]
    mov x9, #4
    str x9, [x16, #GLASS_STAGE]
    ldr x16, [x16, #GLASS_WRAP_ENTRY]
    br x16
.Lcenter_abort:
    // No center: return the plain Padding, without the unlock animation.
    str xzr, [x16, #GLASS_STAGE]
    ldr x9, [x16, #GLASS_BUILD_CHILD]
    ldr x0, [x29, x9]
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
.Lcenter_stock:
    ldr x9, [x16, #GLASS_CENTER_ITEM]
    ldr x2, [x29, x9]
    ldr x9, [x16, #GLASS_CENTER_SLOT]
    str x3, [x29, x9]
    ldr x9, [x16, #GLASS_CENTER_FIELD]
    ldr w0, [x2, x9]
    add x0, x0, x28, lsl #32
    ldr x16, [x16, #GLASS_CENTER_CONTINUE]
    br x16
.size HyperTweakNativeDockGlassCenter, .-HyperTweakNativeDockGlassCenter

dock_entry HyperTweakNativeDockGlassWrap
    // After the wrapper's field stores; x2 holds the wrapper. Displaced:
    // ldur x1, [fp, #title]; stur w1, [x2, #title]; x1 = pool[next type].
    glass_frames x16
    ldr x9, [x16, #GLASS_TITLE_SLOT]
    ldr x1, [x29, x9]
    ldr x9, [x16, #GLASS_TITLE_FIELD]
    str w1, [x2, x9]
    ldr x17, [x16, #GLASS_STAGE]
    cmp x17, #4
    b.ne .Lwrap_stock
    glass_owned_frame .Lwrap_stock
    // Our wrapper is no item cell: clearing the flag makes the unlock animation
    // treat it as on the current screen and start from its own position. The
    // wrapper was just allocated and false is immortal, so no barrier applies.
    ldr x9, [x16, #GLASS_FIXED_FIELD]
    add x10, x22, #0x30             // false
    str w10, [x2, x9]
    // Drop the item builder frame and return the wrapper to the Hotseat.
    str xzr, [x16, #GLASS_STAGE]
    mov x0, x2
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
.Lwrap_stock:
    // Diagnostics only: the host item's unlock center x (Offset.dx of the
    // wrapper position stored at 0xf), first and last since the last factory.
    // Integer registers only; x9 and x10 are dead at the allocation call.
    ldr w9, [x2, #0xf]
    tbz w9, #0, 7f
    add x9, x9, x28, lsl #32
    cmp x9, x22
    b.eq 7f
    ldur x9, [x9, #7]
    ldr x10, [x16, #GLASS_OBSERVED_BUILT]
    cbnz x10, 8f
    str x9, [x16, #GLASS_OBSERVED_FIRST]
8:
    str x9, [x16, #GLASS_OBSERVED_LAST]
    add x10, x10, #1
    str x10, [x16, #GLASS_OBSERVED_BUILT]
7:
    ldr x9, [x16, #GLASS_TITLE_POOL]
    ldr x1, [x27, x9]
    ldr x16, [x16, #GLASS_TITLE_CONTINUE]
    br x16
.size HyperTweakNativeDockGlassWrap, .-HyperTweakNativeDockGlassWrap

dock_entry HyperTweakNativeDockIdle
    // Even a disabled rule may have one pending restoration. Skip native
    // calls when no setting transition or isolate recovery is required.
    adrp x16, g_native_dock_ready
    add x16, x16, :lo12:g_native_dock_ready
    ldar w16, [x16]
    cbz w16, .Lidle_stock
    adrp x16, g_native_dock_requested
    add x16, x16, :lo12:g_native_dock_requested
    ldar w16, [x16]
    cbz w16, .Lidle_disable
    dock_owner .Lidle_selected
    b .Lidle_check
.Lidle_selected:
    // A geometry change asks the selected owner for one rebuild.
    adrp x16, g_native_dock_recovery
    add x16, x16, :lo12:g_native_dock_recovery
    ldar w16, [x16]
    cbz w16, .Lidle_stock
    b .Lidle_check
.Lidle_disable:
    dock_load x16, g_native_dock_owner
    cbnz x16, .Lidle_check
    adrp x16, g_native_dock_recovery
    add x16, x16, :lo12:g_native_dock_recovery
    ldar w16, [x16]
    cbz w16, .Lidle_stock
.Lidle_check:
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
    stp x18, x30, [sp, #144]
    stp q0, q1, [sp, #160]
    stp q2, q3, [sp, #192]
    stp q4, q5, [sp, #224]
    stp q6, q7, [sp, #256]
    stp q8, q9, [sp, #288]
    stp q10, q11, [sp, #320]
    stp q12, q13, [sp, #352]
    stp q14, q15, [sp, #384]
    stp q16, q17, [sp, #416]
    stp q18, q19, [sp, #448]
    stp q20, q21, [sp, #480]
    stp q22, q23, [sp, #512]
    stp q24, q25, [sp, #544]
    stp q26, q27, [sp, #576]
    stp q28, q29, [sp, #608]
    stp q30, q31, [sp, #640]
    mrs x16, nzcv
    str x16, [sp, #672]
    mrs x16, fpcr
    str x16, [sp, #680]
    mrs x16, fpsr
    str x16, [sp, #688]
    mov x0, x26
    mov x1, x29
    mov x2, x28
    mov x3, x22
    mov x4, x27
    bl HyperTweakNativeDockAtIdle
    str x0, [sp, #696]
    ldr x16, [sp, #688]
    msr fpsr, x16
    ldr x16, [sp, #680]
    msr fpcr, x16
    ldr x16, [sp, #672]
    msr nzcv, x16
    ldp q0, q1, [sp, #160]
    ldp q2, q3, [sp, #192]
    ldp q4, q5, [sp, #224]
    ldp q6, q7, [sp, #256]
    ldp q8, q9, [sp, #288]
    ldp q10, q11, [sp, #320]
    ldp q12, q13, [sp, #352]
    ldp q14, q15, [sp, #384]
    ldp q16, q17, [sp, #416]
    ldp q18, q19, [sp, #448]
    ldp q20, q21, [sp, #480]
    ldp q22, q23, [sp, #512]
    ldp q24, q25, [sp, #544]
    ldp q26, q27, [sp, #576]
    ldp q28, q29, [sp, #608]
    ldp q30, q31, [sp, #640]
    ldp x0, x1, [sp, #0]
    ldp x2, x3, [sp, #16]
    ldp x4, x5, [sp, #32]
    ldp x6, x7, [sp, #48]
    ldp x8, x9, [sp, #64]
    ldp x10, x11, [sp, #80]
    ldp x12, x13, [sp, #96]
    ldp x14, x15, [sp, #112]
    ldr x17, [sp, #136]
    ldp x18, x30, [sp, #144]
    ldr x16, [sp, #696]
    add sp, sp, #800
    cbz x16, .Lidle_stock
    // Transfer with the original caller PC, after removing the original
    // Dart frame. The host's Element.reassemble supplies its own stack maps.
    mov x1, x16
    mov x0, x22
    mov x15, x29
    ldp x29, x30, [x15], #16
    dock_branch g_native_dock_reassemble
.Lidle_stock:
    mov x0, x22
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
.size HyperTweakNativeDockIdle, .-HyperTweakNativeDockIdle

.section .note.GNU-stack,"",%progbits
