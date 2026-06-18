# 🚀 HyperTweak

**English** | [简体中文](README_ZH.md)

<div align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" alt="HyperTweak Logo" width="96" height="96" style="border-radius: 20%;" onerror="this.src='app/src/main/kotlin/com/takekazex/hypertweak/ui/page/AboutPage.kt'; this.style.display='none';" />
  
  <p align="center">
    <strong>A high-performance, modular lock screen and system customization framework for Xiaomi HyperOS and MIUI, built on native libxposed (API 102)</strong>
  </p>

  <p align="center">
    <a href="https://github.com/TakeKazeX/HyperTweak/actions"><img src="https://img.shields.io/github/actions/workflow/status/TakeKazeX/HyperTweak/ci.yml?branch=main&style=flat-square&logo=github-actions&logoColor=white&label=CI" alt="Build Status"></a>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0-blue?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-15%20%7C%2016-green?style=flat-square&logo=android&logoColor=white" alt="Android Support"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/TakeKazeX/HyperTweak?style=flat-square&color=orange" alt="License"></a>
  </p>
</div>

---

## 📖 Introduction

**HyperTweak** is a customized Xposed module for HyperOS 3. Built on native **libxposed (API 102)**, its main interface is written using the modern **Miuix UI** Compose framework, providing a system-level visual texture and smooth interaction experience that fits perfectly with HyperOS.

---

## ✨ Core Features

- **🖼️ Fullscreen Always-On Display (AOD)**: Bypasses system limits to enable full-screen wallpaper support for standard AOD styles.
- **🔘 Lock Screen Fingerprint Hiding**: Automatically hides the under-display fingerprint icon/aura during wake-up transitions while maintaining complete touch unlocking functionality.
- **🌍 GMS Restriction Bypass**: Overrides limitations on domestic (China) ROMs to enable trouble-free Google Mobile Services and Play Store installation.
- **🔑 Google Passkey Unlock**: Bypasses system-level restrictions to enable Google Passkey and third-party Credential Managers on domestic HyperOS/MIUI ROMs.
- **⚙️ Settings Entry Injection**: Seamlessly injects the module configuration entry directly below the Wi-Fi listing in the system Settings app.
- **🙈 Launcher Icon Hiding**: Hides the module's app icon from the launcher home screen; access remains available through the LSPosed manager or system Settings.

---

## 🚀 Build & Deployment

### Development Environment Requirements
- **IDE**: Android Studio Ladybug (2024.2.1) or higher
- **Build Tool**: Gradle 9.5.1
- **Target SDK**: Android 16 (API 37)
- **Min SDK**: Android 15 (API 35)

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
* **[KavaRef](https://github.com/HighCapable/KavaRef)** - Elegant Kotlin reflection library.
* **[EzHookTool](https://github.com/lingqiqi5211/EzHookTool)** - Kotlin Xposed helper library by lingqiqi5211.
* **[DexKit](https://github.com/LuckyPray/DexKit)** - Powerful Dex analysis tool for finding hook points dynamically.
* **[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)** - Bypass restrictions on non-SDK interfaces in Android 9+.
* **[Miuix UI](https://github.com/Yukonga/miuix)** - Modern HyperOS/MIUI style Compose components.
* **[InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)** - Inspiration for themes and UI/UX layout.
* **[HyperOShape](https://github.com/xzakota/HyperOShape)** - Reference logic for fingerprint icon rendering bypass.
* **[XiaomiHelper](https://github.com/HowieHChen/XiaomiHelper) / [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)** - Reference implementations for Settings entry injection.
* **[HyperPasskey](https://github.com/howard20181/HyperPasskey)** - OEM Credential Manager override / Google Passkey unlock implementation.
* **[HighLight Icons](https://t.me/HighLightIcons)** - App shortcut icons by [@GotohHitoriBocchi0221](https://t.me/GotohHitoriBocchi0221).

---

## 📄 License

This project is licensed under the [GNU General Public License v3](LICENSE).
