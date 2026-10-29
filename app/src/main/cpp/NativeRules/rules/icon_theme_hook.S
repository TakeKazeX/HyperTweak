// SPDX-License-Identifier: Apache-2.0
// Both sites are inside original Dart frames. No managed allocation/call or retained object.
.text
.p2align 2
.global HyperTweakIconThemeSupportHook
.hidden HyperTweakIconThemeSupportHook
.type HyperTweakIconThemeSupportHook,%function
HyperTweakIconThemeSupportHook:
 bti c
 stp x16,x17,[sp,#-16]!
 adrp x16,g_icon_theme_unlock
 add x16,x16,:lo12:g_icon_theme_unlock
 ldar w17,[x16]
 cbz w17,1f
 // SDK >= 33 already passed. Change only the feature-local international value.
 add x1,x22,#0x20
1:
 ldp x16,x17,[sp],#16
 // x16 is dead at both contracts and is the inline-backend branch scratch.
 adrp x16,g_icon_theme_support_original
 ldr x16,[x16,:lo12:g_icon_theme_support_original]
 br x16
.size HyperTweakIconThemeSupportHook,.-HyperTweakIconThemeSupportHook

.global HyperTweakIconThemeRefreshHook
.hidden HyperTweakIconThemeRefreshHook
.type HyperTweakIconThemeRefreshHook,%function
HyperTweakIconThemeRefreshHook:
 bti c
 stp x16,x17,[sp,#-16]!
 adrp x16,g_icon_theme_refresh
 add x16,x16,:lo12:g_icon_theme_refresh
 ldar w17,[x16]
 cbz w17,1f
 // Canonical bools differ by bit 4. Force this comparison unequal, leaving Rx state intact.
 eor x3,x1,#0x10
1:
 ldp x16,x17,[sp],#16
 adrp x16,g_icon_theme_refresh_original
 ldr x16,[x16,:lo12:g_icon_theme_refresh_original]
 br x16
.size HyperTweakIconThemeRefreshHook,.-HyperTweakIconThemeRefreshHook
.section .note.GNU-stack,"",%progbits
