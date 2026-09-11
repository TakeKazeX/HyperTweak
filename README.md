# 🚀 HyperTweak

**English** | [简体中文](README_ZH.md)

<div align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" alt="HyperTweak Logo" width="96" height="96" style="border-radius: 20%;" onerror="this.src='app/src/main/kotlin/com/takekazex/hypertweak/ui/page/AboutPage.kt'; this.style.display='none';" />
  
  <p align="center">
    <strong>A high-performance, modular lock screen and system customization framework for Xiaomi HyperOS, built on native libxposed (API 102)</strong>
  </p>

  <p align="center">
    <a href="https://github.com/TakeKazeX/HyperTweak/actions"><img src="https://img.shields.io/github/actions/workflow/status/TakeKazeX/HyperTweak/ci.yml?branch=main&style=flat-square&logo=github-actions&logoColor=white&label=CI" alt="Build Status"></a>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4-blue?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-16%20%7C%2017-green?style=flat-square&logo=android&logoColor=white" alt="Android Support"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/TakeKazeX/HyperTweak?style=flat-square&color=orange" alt="License"></a>
  </p>
</div>

---

## 📖 Introduction

**HyperTweak** is a customized Xposed module for Xiaomi HyperOS. Built on native **libxposed (API 102)**, its main interface is written using the modern **Miuix UI** Compose framework, providing a system-level visual texture and smooth interaction experience that fits perfectly with HyperOS.

---

## ✨ Core Features

- **🖼️ Fullscreen Always-On Display (AOD)**: Bypasses system limits to enable full-screen wallpaper support for standard AOD styles.
- **🔘 Scoped Under-display Fingerprint Hiding**: Independently hides the fingerprint icon/halo on the interactive lockscreen, AOD, or in-app authentication prompts such as payments, password managers, and passkeys while preserving fingerprint authentication.
- **📊 Status Bar Icon Tuner**: Hides data activity, network type, roaming, VoLTE and VoWiFi indicators, and controls icon placement, stacked signals, compound icons and custom signal artwork.
- **🎛️ Control Center & Lockscreen Layout**: Resizes control-center cards, sliders and the media player, adjusts their corner radius, and adds charging detail, notification fingerprint avoidance and lock-screen notification unlocks.
- **📷 Camera Unlocks**: Reveals Leica Moment, smart composition, content credentials and Ultra image quality, plus Street mode switching and custom watermarks.
- **♻️ AOSP Restoration**: Hands HyperOS components back to their AOSP implementations — package installer, power menu, volume panel, clipboard editor and keyboard.
- **📥 Download Manager**: Blocks the Xunlei download-engine log directory and restores the AOSP-style download UI entries.
- **🔒 Privacy Hooks**: Turns off Xiaomi's risk monitoring, GuardProvider app-list upload and environment checks, and MiLink external-file hand-off.
- **🖼️ Media Editor & Super Island**: Unlocks the media editor's watermark categories and removes Super Island app-list restrictions.
- **📞 Video Ringback**: Disables the carrier video ringback (CRBT) capability on the phone service and HyperPhone pages.
- **⚡ Adaptive Refresh & Air Gestures**: Unlocks the adaptive-refresh and AON visual-perception/air-gesture options hidden by model gates.
- **🌍 GMS Restriction Bypass**: Overrides limitations on domestic (China) ROMs to enable trouble-free Google Mobile Services and Play Store installation.
- **🔑 Google Passkey Unlock**: Bypasses system-level restrictions to enable Google Passkey and third-party Credential Managers on domestic HyperOS ROMs.
- **⚙️ Settings Entry Injection**: Injects the module configuration entry directly into the system Settings app.
- **⌨️ Gesture Bar Shortcuts**: Long-press or double-tap the gesture bar to open the default assistant or Circle to Search. Unavailable on OS4.
- **🙈 Launcher Icon Hiding**: Hides the module's app icon from the launcher home screen; access remains available through the LSPosed manager or system Settings.

---

## 🚀 Build & Deployment

### Development Environment Requirements
- **Build Tool**: Gradle 9.7.1
- **Target SDK**: Android 17 (API 37) / HyperOS 4
- **Min SDK**: Android 16 (API 36) / HyperOS 3

### Local Compilation
Run the following command in the root directory to compile a release build:
```bash
./gradlew assembleRelease
```
The build script automatically retrieves the git commit count as the `versionCode` at build-time. The generated APK will be outputted to:
```
app/build/outputs/apk/release/HyperTweak-v<version>-release.apk
```

---

## 🤝 Acknowledgements

Special thanks to the following open-source projects for their support and inspiration:

* **[libxposed](https://github.com/libxposed/api)** - The native Xposed API 102 specification standard.
* **[LSPosed](https://github.com/LSPosed/LSPosed)** - The mainstream Xposed framework runtime environment.
* **[EzHookTool](https://github.com/lingqiqi5211/EzHookTool)** - Kotlin Xposed helper library by lingqiqi5211.
* **[DexKit](https://github.com/LuckyPray/DexKit)** - Powerful Dex analysis tool for finding hook points dynamically.
* **[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)** - Bypass restrictions on non-SDK interfaces in Android 9+.
* **[Miuix UI](https://github.com/Yukonga/miuix)** - Modern HyperOS style Compose components.
* **[InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)** - Inspiration for themes and UI/UX layout.
* **[HyperOShape](https://github.com/xzakota/HyperOShape)** - Reference logic for fingerprint icon rendering bypass.
* **[XiaomiHelper](https://github.com/HowieHChen/XiaomiHelper) / [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)** - Reference implementations for Settings entry injection.
* **[HyperPasskey](https://github.com/howard20181/HyperPasskey)** - OEM Credential Manager override / Google Passkey unlock implementation.
* **[HyperOS_FCM_Live](https://github.com/howard20181/HyperOS_FCM_Live)** - Google Push / FCM Live implementation reference by howard20181.
* **[MiuiBackGestureHook](https://github.com/wxxsfxyzm/MiuiBackGestureHook)** - Back gesture compatibility implementation, integrated under its Apache-2.0 license.
* **[MiCTS](https://github.com/parallelcc/MiCTS)** - Reference for the Android 15 contextual-search service path used by Gesture Bar Shortcuts (GPL-3.0).
* **[AOSP Package Installer](https://github.com/tehcneko/AospPackageInstaller)** - AOSP package-installer restoration approach by tehcneko (GPL-3.0).
* **[HyperTrust](https://github.com/StevenWin818/HyperTrust)** - Extend Unlock trust-state repair by StevenWin818 (GPL-3.0).
* **[HighLight Icons](https://t.me/HighLightIcons)** - App shortcut icons by [@GotohHitoriBocchi0221](https://t.me/GotohHitoriBocchi0221).

---

## 📄 License

This project is licensed under the [GNU General Public License v3](LICENSE).
