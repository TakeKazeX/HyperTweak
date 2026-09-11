# 🚀 HyperTweak

🌐 [English](README.md) | **简体中文**

<div align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" alt="HyperTweak Logo" width="96" height="96" style="border-radius: 20%;" onerror="this.src='app/src/main/kotlin/com/takekazex/hypertweak/ui/page/AboutPage.kt'; this.style.display='none';" />
  
  <p align="center">
    <strong>基于原生 libxposed (API 102) 构建的高性能、模块化 HyperOS 锁屏与系统定制优化框架</strong>
  </p>

  <p align="center">
    <a href="https://github.com/TakeKazeX/HyperTweak/actions"><img src="https://img.shields.io/github/actions/workflow/status/TakeKazeX/HyperTweak/ci.yml?branch=main&style=flat-square&logo=github-actions&logoColor=white&label=CI" alt="Build Status"></a>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4-blue?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-16%20%7C%2017-green?style=flat-square&logo=android&logoColor=white" alt="Android Support"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/TakeKazeX/HyperTweak?style=flat-square&color=orange" alt="License"></a>
  </p>
</div>

---

## 📖 简介

**HyperTweak** 是一款面向小米 HyperOS 的定制 Xposed 模块。基于原生的 **libxposed (API 102)** 构建。主界面使用现代化 **Miuix UI** Compose 架构编写，提供与 HyperOS 高度契合的系统级视觉质感与平滑交互体验。

---

## ✨ 核心特性

- **🖼️ 全屏息屏显示 (AOD)**：动态解除系统限制，使普通息屏样式支持全屏壁纸显示。
- **🔘 分场景隐藏屏下指纹**：可分别控制亮屏锁屏、息屏 AOD，以及支付/密码管理器/通行密钥等应用内认证弹窗的指纹图标/光圈，同时保留完整的指纹识别功能。
- **📊 状态栏图标调节器**：可隐藏数据活动、网络类型、漫游、VoLTE、VoWiFi 等图标，并支持图标左置、堆叠信号、合成图标与自定义信号图标。
- **🎛️ 控制中心与锁屏布局**：调整控制中心卡片、滑条、播控中心的尺寸与圆角，并提供锁屏充电详情、通知指纹避让与锁屏通知解锁。
- **📷 相机解锁**：解锁徕卡一瞬、智能构图、内容凭证、超高画质，支持街拍模式切换与自定义水印。
- **♻️ AOSP 还原**：将 HyperOS 组件还原为 AOSP 实现——包安装器、电源菜单、音量面板、剪贴板编辑器与键盘。
- **📥 下载管理**：屏蔽迅雷下载引擎的日志目录，并还原 AOSP 风格的下载界面入口。
- **🔒 隐私相关**：关闭小米风险监控、GuardProvider 应用列表上传与环境检测，以及 MiLink 外部文件移交。
- **🖼️ 媒体编辑与超级岛**：解锁媒体编辑器的水印分类，并移除超级岛的应用名单限制。
- **📞 视频彩铃**：在电话服务与 HyperPhone 页面关闭运营商视频彩铃 (CRBT) 能力。
- **⚡ 自适应刷新与隔空手势**：解锁被机型门槛隐藏的自适应刷新与 AON 视觉感知/隔空手势选项。
- **🌍 谷歌服务 (GMS) 解锁**：绕过国内版固件限制，支持安装并使用谷歌移动服务与 Google Play。
- **🔑 谷歌密钥 (Passkey) 解锁**：动态解锁系统级限制，支持在国内版 HyperOS 系统上激活使用谷歌密钥（Passkey）与第三方凭据管理器。
- **⚙️ 原生系统设置项注入**：将模块入口注入系统「设置」应用中。
- **⌨️ 小白条快捷操作**：长按或双击小白条，直达默认助手或即圈即搜。OS4 上不可用。
- **🙈 桌面图标动态隐藏**：支持隐藏桌面图标，可通过 LSPosed 管理器或系统设置内的注入入口随时唤起。

---

## 🚀 编译与部署

### 开发环境要求
- **Build Tool**: Gradle 9.7.1
- **Target SDK**: Android 17 (API 37) / HyperOS 4
- **Min SDK**: Android 16 (API 36) / HyperOS 3

### 本地编译
在项目根目录下，执行以下命令构建 Release 版本：
```bash
./gradlew assembleRelease
```
编译成功后，脚本会自动读取您的 Git 提交数作为编译的 `versionCode`，生成的安装包将存放于：
```
app/build/outputs/apk/release/HyperTweak-v<version>-release.apk
```

---

## 🤝 致谢

感谢以下优秀项目及库的启发与支持：

* **[libxposed](https://github.com/libxposed/api)** - 原生 Xposed API 102 框架标准。
* **[LSPosed](https://github.com/LSPosed/LSPosed)** - 主流 Xposed 框架运行环境实现。
* **[EzHookTool](https://github.com/lingqiqi5211/EzHookTool)** - lingqiqi5211 编写的 Kotlin Xposed 辅助库。
* **[DexKit](https://github.com/LuckyPray/DexKit)** - 强大的 Dex 动态分析与 Hook 点寻找工具。
* **[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)** - 绕过 Android 9+ 非 SDK 接口限制。
* **[Miuix UI](https://github.com/Yukonga/miuix)** - 现代化的 HyperOS 风格 Compose 组件库。
* **[InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)** - 启发了模块的主题与 UI/UX 布局设计。
* **[HyperOShape](https://github.com/xzakota/HyperOShape)** - 绕过指纹图标绘制的基本实现逻辑参考。
* **[XiaomiHelper](https://github.com/HowieHChen/XiaomiHelper) / [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)** - 系统设置项注入实现参考。
* **[HyperPasskey](https://github.com/howard20181/HyperPasskey)** - 绕过 OEM 凭据管理器限制 / 谷歌密钥解锁实现参考。
* **[HyperOS_FCM_Live](https://github.com/howard20181/HyperOS_FCM_Live)** - 澎湃系统谷歌推送限制解除 / FCM Live 实现参考，作者 howard20181。
* **[MiuiBackGestureHook](https://github.com/wxxsfxyzm/MiuiBackGestureHook)** - 返回手势兼容实现，按其 Apache-2.0 许可证整合。
* **[MiCTS](https://github.com/parallelcc/MiCTS)** - 小白条快捷操作所用 Android 15 上下文搜索服务路径的实现参考（GPL-3.0）。
* **[AOSP Package Installer](https://github.com/tehcneko/AospPackageInstaller)** - AOSP 包安装器还原方案，作者 tehcneko（GPL-3.0）。
* **[HyperTrust](https://github.com/StevenWin818/HyperTrust)** - Extend Unlock 信任状态修复，作者 StevenWin818（GPL-3.0）。
* **[HighLight Icons](https://t.me/HighLightIcons)** - 应用快捷方式图标，作者 [@GotohHitoriBocchi0221](https://t.me/GotohHitoriBocchi0221)。

---

## 📄 许可证

本项目基于 [GNU General Public License v3](LICENSE) 许可协议开源。
