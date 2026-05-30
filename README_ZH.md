# 🚀 HyperTweak

🌐 [English](README.md) | **简体中文**

<div align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" alt="HyperTweak Logo" width="96" height="96" style="border-radius: 20%;" onerror="this.src='app/src/main/kotlin/com/takekazex/hypertweak/ui/page/AboutPage.kt'; this.style.display='none';" />
  
  <p align="center">
    <strong>基于原生 libxposed (API 101) 构建的高性能、模块化 HyperOS 锁屏与系统定制优化框架</strong>
  </p>

  <p align="center">
    <a href="https://github.com/TakeKazeX/HyperTweak/actions"><img src="https://img.shields.io/github/actions/workflow/status/TakeKazeX/HyperTweak/ci.yml?branch=main&style=flat-square&logo=github-actions&logoColor=white&label=CI" alt="Build Status"></a>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0-blue?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-15%20%7C%2016-green?style=flat-square&logo=android&logoColor=white" alt="Android Support"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/TakeKazeX/HyperTweak?style=flat-square&color=orange" alt="License"></a>
  </p>
</div>

---

## 📖 简介

**HyperTweak** 是一款 HyperOS 3 定制的 Xposed 模块。基于原生的 **libxposed (API 101)** 构建。主界面使用现代化 **Miuix UI** Compose 架构编写，提供与 HyperOS 高度契合的系统级视觉质感与平滑交互体验。

---

## ✨ 核心特性

- **🖼️ 全屏息屏显示 (AOD)**：动态解除系统限制，使普通息屏样式支持全屏壁纸显示。
- **🔘 智能隐藏锁屏指纹**：唤醒过渡时隐藏屏幕下指纹图标/光圈，同时保留完整的指纹解锁功能。
- **🌍 谷歌服务 (GMS) 解锁**：绕过国内版固件限制，支持安装并使用谷歌移动服务与 Google Play。
- **⚙️ 原生系统设置项注入**：以无感注入的方式，将模块入口动态插入在系统「设置」的 Wi-Fi 列表下方。
- **🙈 桌面图标动态隐藏**：支持隐藏桌面图标，可通过 LSPosed 管理器或系统设置内的注入入口随时唤起。

---

## 🚀 编译与部署

### 开发环境要求
- **IDE**: Android Studio Ladybug (2024.2.1) 或更高版本
- **Build Tool**: Gradle 9.5.1
- **Target SDK**: Android 16 (API 37)
- **Min SDK**: Android 15 (API 35)

### 本地编译
在项目根目录下，执行以下命令构建 Release 版本：
```bash
./gradlew assembleRelease
```
编译成功后，脚本会自动读取您的 Git 提交数作为编译的 `versionCode`，生成的安装包将存放于：
```
app/build/outputs/apk/release/HyperTweak-v1.3.1-release.apk
```

---

## 🤝 致谢

感谢以下优秀项目及库的启发与支持：

* **[libxposed](https://github.com/libxposed/api)** - 原生 Xposed API 101 框架标准。
* **[LSPosed](https://github.com/LSPosed/LSPosed)** - 主流 Xposed 框架运行环境实现。
* **[KavaRef](https://github.com/HighCapable/KavaRef)** - Kotlin 强类型反射库。
* **[EzHookTool](https://github.com/lingqiqi5211/EzHookTool)** - lingqiqi5211 编写的 Kotlin Xposed 辅助库。
* **[DexKit](https://github.com/LuckyPray/DexKit)** - 强大的 Dex 动态分析与 Hook 点寻找工具。
* **[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)** - 绕过 Android 9+ 非 SDK 接口限制。
* **[Miuix UI](https://github.com/Yukonga/miuix)** - 现代化的 HyperOS/MIUI 风格 Compose 组件库。
* **[InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)** - 启发了模块的主题与 UI/UX 布局设计。
* **[HyperOShape](https://github.com/xzakota/HyperOShape)** - 绕过指纹图标绘制的基本实现逻辑参考。
* **[XiaomiHelper](https://github.com/HowieHChen/XiaomiHelper) / [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)** - 系统设置项注入实现参考。

---

## 📄 许可证

本项目基于 [GNU General Public License v3](LICENSE) 许可协议开源。
