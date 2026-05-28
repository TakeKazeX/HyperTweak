# HyperTweak

一个基于原生 `libxposed` (API 101) 构建的高性能、模块化 HyperOS 锁屏与系统优化 Xposed 模块。

## 功能特性

- **Always-On Display (AOD) 全屏壁纸支持**：
  - 动态解除 MIUI/HyperOS 的限制，使普通息屏样式支持全屏壁纸显示。
  - 自动向 `com.miui.aod` 及系统设置注入拦截逻辑，使用户能在「息屏与锁屏」设置中看到并调整全屏 AOD 的专属配置。
- **锁屏指纹图标彻底隐藏**：
  - 采用双层拦截机制（Drawable 替换 + 悬浮 View 的 Alpha 重置为 `0f`），在锁屏切换或唤醒过渡时完美隐藏指纹光圈，同时保留完整的屏幕下指纹触摸解锁功能与响应。
- **免除国内版 GMS 限制**：
  - 绕过 HyperOS 国内版固件对谷歌移动服务 (GMS) 的安装限制，畅玩 Google Play 服务。
- **原生 Settings 设置项注入**：
  - 模块能够以无感注入的方式，将自身的入口（带澎湃风格板手图标）动态插入在系统「设置」的 Wi-Fi 列表下方，方便快速配置模块。
- **桌面图标动态隐藏**：
  - 支持隐藏桌面图标，避免主屏幕杂乱，隐藏后可以通过 LSPosed 管理器或系统设置的注入入口打开模块主界面。
- **澎湃 OS 质感 UI**：
  - 模块本体应用界面采用现代化的 **Miuix UI** 架构编写，原生卡片流设计，完全契合系统视觉。

## 架构设计

本项目完全摒弃了传统的 `YukiHookAPI` 框架，直接使用原生的 `libxposed` API 101 标准，采用松耦合的模块化设计：
- `HookEntry`：Xposed 初始化入口，管理多进程包加载生命周期。
- `Preferences`：基于 `libxposed` 进程间服务机制，安全且低延迟地同步用户开关数据。
- `rules/`：针对每一个注入特性编写了独立的类（如 `AODHooker`, `HideFingerprintIcon`, `SettingsHooker`），大幅降低了代码耦合度，易于后续的功能扩展。

## 编译运行

### 环境要求
- Android Studio Ladybug (或更高版本)
- Android SDK (API 35/37)
- Gradle 9.0+

### 本地编译
在项目根目录执行：
```bash
./gradlew assembleRelease
```
编译生成的 Release APK 会输出在 `app/build/outputs/apk/release/app-release.apk`。

## 致谢

- **libxposed** - 高效的轻量级 Xposed 开发接口标准。
- **KavaRef** - 简单好用的 Kotlin 强类型反射封装。
- **Miuix UI** - 精美的 HyperOS/MIUI 风格 Compose 组件库。
- **HyperOShape** - 提供部分指纹样式去除思路。
- **XiaomiHelper / HyperCeiler** - 提供系统设置项注入实现参考。

## 许可证

本项目基于 [MIT License](LICENSE) 许可协议开源。
