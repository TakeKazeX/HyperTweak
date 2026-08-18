# 堆叠信号重构计划（基于 Flux Decor OS4 2.0.3）

> 状态：规划 + 已落代码。上游分析对象为
> `/Users/ink/developer/refrences/Flux_Decor_OS4-2.0.3.apk`（jadx 反编译到
> `reverse/cache/fluxdecor-2.0.3/jadx-out`，apktool 资源到 `apktool-out`，LSParanoid 字符串已
> 全部解码到 `/tmp/deobf/flux/*.decoded.java`）。OS4 基线核对用
> `cache/systemui-9af08c49ea6e412e`（= `MiuiSystemUI-OS4.0.0.15.XPMCNXM.apk`，SHA256
> `9af08c49ea6e412e...`）jadx + apktool smali。
>
> 结论：**放弃当前"强制 isStackable 原生槽位 + 自定义 getIcon() 渲染"的做法，改为 Flux Decor
> 的视图级拦截方案**——拦截 `ImageView.setImageResource` 与 `MiuiMobileIconBinder` 绑定流程，
> 用模块矢量 drawable 替换 `mobile_signal` 视图图标，并在同一容器内叠加第二个 SIM 的 ImageView，
> 用 VM 可见性 flow 隐藏非数据卡图标。真正的"堆叠"由 drawable 本身绘制（双排信号条），
> 系统只负责摆放、着色、布局。

## 一、现状与问题（为什么"全改烂了"）

当前实现走了两条路，都不可靠：

1. **HEAD（d19b155）**：`StackedSignalHooker` 强制 `MobileIconsViewModel.isStackable` = true 走
   OS4 原生 Compose 堆叠槽位，同时 hook 每个 `MobileIconViewModel.getIcon()` 返回模块自绘
   ALPHA_8 bitmap（`StackedSignalRender` + `assets/svg/*`）。自绘引擎要同时摆平 Compose 槽位、
   状态流、tint/深浅色、布局尺寸，链路太长，且 `getIcon()` 的调用方不止 ImageView 一处。
2. **工作区未提交版**：把渲染引擎删掉退回"原生槽位"（`isStackable` + 可见性 flow 抑制），
   **但 UI 仍保留全部 SVG 渲染选项**（样式/缩放/内边距/透明度/type 文本/单卡双卡/漫游），
   这些 key 已无人读取——开关和滑条全部失效，就是"改烂了"的直接观感。

另外 `IconManagerHooker` 在堆叠开启时把 `single_mobile_sim1/sim2` 加入 block list——这是给
"原生槽位"配套的；换视图级方案后**必须撤销**，否则数据卡自己的图标也会被 block 掉。

## 二、Flux Decor 2.0.3 机制分析（解码后）

### 2.1 目标与入口（`StatusBarDualSimSignalHook.register`）

| Hook 目标（OS4 字符串已解码） | 时机 | 作用 |
|---|---|---|
| `SystemUIApplication.onCreate` | after | 备好模块 context 后注册 drawable |
| `MobileUiAdapter.start` | after | 取 `mobileIconsViewModel`（Lazy）、`iconsVm` Map、`dataSubIdFlow`、javaAdapter，一次性挂 `MiuiCellularIconVM` 构造钩子 |
| `MiuiMobileIconBinder.bind` | after | `applyMobileGroupSpacing` + `rememberMobileRoot` + 非数据卡可见性 + `initMobileBind`（创建第二张 ImageView） |
| `MiuiMobileIconBinder$bind$1` | 构造 after | 把 `$mobile`（mobile_signal ImageView）与 bind 实例关联（`ClockViewInfo` key `bind1`），`appendTintView` |
| `MiuiStatusBarIconViewHelper.transformResId` | before | 只记录 `useTint` / `isLight` 参数（`lastUseTint`/`lastIsLight`） |
| `ImageView.setImageResource(int)` | before | 兜底主拦截：`mobile_signal` 视图的每次换图都从这里过 |
| `MiuiCellularIconVM` | 构造 after | 每个 SIM 的 VM 构造时挂信号级别流收集（`alwaysCollectFlow`） |

### 2.2 状态跟踪

- `subscriptionsData[3]` = `{dataSubId, 数据卡级别, 另一卡级别}`；`subIdLevels: SparseIntArray`。
- `dataSubId` 初值来自 `MobileIconsViewModel` 的 data-sub-id flow（`resolveDataSubIdFlow`
  沿 `activeMobileDataSubscriptionId` / `miuiIntsLazy`/`miuiInt.mobileConnectionsRepo` 链解析），
  之后由 `setupMobileUiAdapterFlows$lambda$15` 在 flow 每次变化时刷新。
- 信号级别两个来源：
  1. **资源名解析（主路径，`levelFromResId`）**：`signalResToLevelMap` 缓存
     `getResourceEntryName(resId)` 与正则 `stat_sys_signal_(\\d)` 的匹配 → 0..5 级。
     `interceptMobileImage` 在 `setImageResource` 前拿到即将设置的 resId，解析级别，
     `noteLevel(subId, level, iconsVm)` 更新状态。
  2. **flow 收集（`attachCellularSignal`）**：`MiuiCellularIconVM` 构造后读
     `originIconInteractor`（非空即视为收集对象；基线见 §3 修正），
     `javaAdapter.alwaysCollectFlow(obj, Consumer)` 每次发射一个 resId → `levelFromIconId`。
- `displayLevel(raw)`：把原始级别归一化到 0..5（按 `levelToStockSignalRes` 是否含 5 级图标
  决定是否允许 5）。

### 2.3 渲染（`interceptMobileImage` + `applyDualSimIcons`）

- 双卡堆叠模式（`isDualStackMode = enabled && !单卡模式`）：
  - **数据卡的 `mobile_signal` 视图**：`applyDualSimIcons` 查模块 drawable 名
    `statusbar_signal_1_{数据卡级别}{_样式}`，经 `MiuiStatusBarIconViewHelper.transformResId`
    换出深浅/tint 变体 resId，`mobile.setTag(baseResId); mobile.setImageResource(tintResId)`；
    同一父容器内的副卡 ImageView（id `SUBMOBILE_ID`）设
    `statusbar_signal_2_{另一卡级别}{_样式}`，并同步
    `alpha / visibility / imageTintList`。
  - **非数据卡的 `mobile_signal` 视图**：`skipOriginal`（`param.setResult(null)`），整体由
    可见性机制隐藏。
- 单卡模式：改回库存 `stat_sys_signal_{级别}`。
- `initMobileBind`（`MiuiMobileIconBinder.bind` 之后）：在 `mobile_signal` 同父容器内
  `new AlphaOptimizedImageView(context)`，`setId(SUBMOBILE_ID)`、`setAdjustViewBounds(true)`、
  `setLayoutParams(mobile.getLayoutParams())`，可选 `scaleX/Y = 0.1 * statusBarDualSimSignalScale`；
  把 `mobile_group` 的 `subId` 记到 `ClockViewInfo`（key `subId`）供 `resolveMobileSubId` 使用。
- `syncSubMobileTint`：`mobile_signal` 换图后把 `subMobile` 的 tint/alpha/visibility 对齐。

### 2.4 资源注入（`ModuleResourceHooks`）

- `fakeId(name) = (name.hashCode() & 0xFFFFFF) | 0x7E000000`（0x7E 是系统资源未用的 package id）。
- 钩 `Resources.getDrawable(int)` 与 `getDrawableForDensity(int,int,Theme)`（before）：
  `fakes[id]` 命中则返回模块资源 `module.getResources().getDrawable(模块resId)`。
- `registerSignalDrawables`：对 SIM=1,2 × level=0..5，按名
  `statusbar_signal_{sim}_{level}{_style}`（样式后缀 `_ios27` 等）解析模块 drawable，
  `addFakeDrawable(name, 模块id)` → 假 id；同时把
  `{base}+"_tint"` 塞进 `Icons.sTintIconMap[假base]`、`{base}+"_dark"` 塞进
  `Icons.sDarkIconMap[假base]`（`Icons` = `com.miui.systemui.statusbar.Icons`，OS4 字段确认在）。
  于是系统自己的 `transformResId(假base, useTint, isLight)` 就能换成变体假 id，
  再经 getDrawable 钩子换成模块 drawable——**深浅色机制完全复用系统链路**。
- 系统映射：`registerSignalDrawables$mapSys` 把 `stat_sys_signal_{level}`（及其
  `_dark`/`_tint`/… 变体名）的 resId 填进 `signalResToLevelMap`（level 索引）与
  `levelToStockSignalRes`（库存兜底图标）。

### 2.5 隐藏非数据卡（`StatusBarDualSimHideHook` + `applyNonDataSimVisibility`）

- `StatusBarDualSimHideHook`：`MiuiCellularIconVM` 构造 after，读 `isVisible` flow 与
  `subId`，用 `createVisibleFlow(finalVisible)` 替换 `isVisible` 字段
  （`finalVisible = enabled ? (原可见 && (dataSubId==-1 || subId==dataSubId)) : 原可见`）；
  `visibilityRefreshers` 在 dataSubId/可见性变化时 `setValue` 刷新。
- `applyNonDataSimVisibility`（bind 后 + 每次 `setImageResource` 拦截时）：读 `mobile_group`
  的 `subId` 字段，非数据卡 `forceGone`。
- `teardown`：遍历 `boundMobileRoots`（WeakReference 列表）`clearForceGone` +
  `applyOnMobileBind`，再 `removeAllSubMobileViews`（递归扫窗口树删 `SUBMOBILE_ID` 视图并
  还原 scale/translation）。

### 2.6 样式与设置项

- 模块资源：`statusbar_signal_{sim}_{level}`（light 白条）、`_dark`（黑条）、`_tint`
  （纯黑条+40%黑背景条）、可选 `_thick` 粗版；颜色 `light_mode_icon_color_single_tone` =
  `#e6ffffff`、`dark_mode_icon_color_single_tone` = `#bf000000`。
- drawable 几何：60×56 viewport，SIM1 条在上半区（y≈14..30，4 列 x=6/21/36/51，级数 N =
  前 N 列实色、余下透明，0 级全透明），SIM2 条在下半区（y≈40..45 短条，同样规则）。
  **两条 ImageView 叠在同一容器 → 视觉上形成"双排堆叠"图标，级别变化只换颜色不挪位置。**
- 设置项（HookPrefs）：`status_bar_dual_sim_*`（enabled / style / scale=10 固定 /
  left_margin_dp -3..5 / right_margin_dp -3..5 / vertical_offset_dp 0..16 / switch_single）。

## 三、OS4.0.0.15 基线核对结果

对 `systemui-9af08c49ea6e412e`（OS4.0.0.15.XPMCNXM）：

| 目标 | OS4 状态 |
|---|---|
| `MobileUiAdapter.start()`；字段 `mobileIconsViewModel`(Lazy)、`hdController` | ✓ |
| `MobileUiAdapter` 内 `javaAdapter.alwaysCollectFlow(Flow, Consumer)` 用法 | ✓（同文件 start 内多处调用） |
| `MobileIconsViewModel.activeMobileDataSubscriptionId`(StateFlow)、`miuiIntsLazy`(Lazy)、`reuseCache`(ConcurrentHashMap) | ✓ |
| `MiuiCellularIconVM`：`isVisible`/`signalIconId`/`inOutVisible` 等 ReadonlyStateFlow、`originIconInteractor`(MobileIconInteractor) | ✓（注意 §4.2 类型） |
| `MiuiMobileIconBinder.bind`（after 可拿 `mobile_group`/`mobile_signal`）、`MiuiMobileIconBinder$bind$1` | ✓ |
| **`access$setImageResWithTintLight` / `access$resetImageWithTintLight`** | ✗ **OS4.0.0.15 没有**（OS3 缓存 2c093617 有）。OS4 内联为 `imageView.setImageResource(transformResId(...))`，8 处调用点。→ 主拦截点必须是 `ImageView.setImageResource` |
| `MiuiStatusBarIconViewHelper.transformResId(int,boolean,boolean)` | ✓（正是 `Icons.sTintIconMap/sLightIconMap/sDarkIconMap` 查表） |
| `com.miui.systemui.statusbar.Icons` 三个静态 Map | ✓ |
| 布局 id `mobile_group` / `mobile_signal`（`status_bar_mobile_signal_group_inner.xml`，`mobile_signal` 在 `MobileSignalAnimatorView` 容器内，`status_bar_icon_height` 尺寸） | ✓ |
| `AlphaOptimizedImageView`（副卡 ImageView 基类） | ✓ |
| `SystemUIApplication.onCreate` | ✓ |

结论：**除 `access$*` 两个方法外全部目标在 OS4.0.0.15 存在**；主渲染拦截走
`ImageView.setImageResource`（OS4 唯一必经之路），`MiuiMobileIconBinder.bind` after 负责建副卡
视图与记住 root。

## 四、HyperTweak 移植方案

### 4.1 架构总览（相对上游的简化）

```
StackedSignalResources  —— 假资源注入：fakeId + Resources.getDrawable 钩子 + Icons 映射填充
StackedSignalHooker     —— 编排：
  ├─ onHook(): 读设置，安装 5 组钩子（幂等，只装一次）
  ├─ MobileUiAdapter.start after
  │    ├─ 解包 mobileIconsViewModel(Lazy) → iconsVm Map + dataSubId(初值/flow)
  │    └─ resolve javaAdapter（字段 mJavaAdapter/javaAdapter/hdController.javaAdapter）
  ├─ MiuiMobileIconBinder.bind after
  │    ├─ rememberMobileRoot(mobile_group)
  │    ├─ applyNonDataSimVisibility（forceGone 非数据卡）
  │    └─ initMobileBind：创建 SUBMOBILE_ID 副卡 ImageView + 缩放/间距
  ├─ ImageView.setImageResource(int) before（主拦截）
  │    └─ isMobileSignalView → 解析级别 → noteLevel → 数据卡:applyDualSimIcons
  │        非数据卡:skipOriginal + refreshDataSimIcons
  ├─ transformResId(3参) before：记录 lastUseTint/lastIsLight（变体选择）
  └─ 可见性守卫：View.setVisibility / MobileSignalAnimatorContainer.setChildVisible
       （TAG_FORCE_GONE 时强制隐藏）
```

### 4.2 与上游的关键差异（必须改）

1. **级别收集对象（已实现：不收集 flow）**：上游收 `originIconInteractor`（其目标构建上是
   flow）；OS4.0.0.15 上它是 `MobileIconInteractor`，不是 flow，`alwaysCollectFlow` 会抛
   （上游 catch 掉了=静默失效）。OS4 的 `MiuiCellularIconVM` 无构造器、字段由工厂在
   `invoke()` 内赋值，级别收集不可靠。**最终实现完全靠 `setImageResource(int)` 的
   before 钩子解析 `stat_sys_signal_N` 资源名取级别**——binder 是 flow 驱动的，每个 SIM
   的视图即使被 GONE 也会持续收到 `setImageResource` 调用，无需 VM 级 flow 收集；
   工厂钩子与 `inOutVisible` 字段写入（值类型为 Pair 有风险）均不采用。
2. **无 `access$*` 方法**：不挂 `access$setImageResWithTintLight`；
   深浅/tint 变体完全靠 `transformResId(假id,...)` + `Icons` 映射 + getDrawable 钩子，
   `setImageResource` before 钩子里 `ThreadLocal` 防重入（`applyingIcons`）。
3. **dataSubId 解析**：直接读 `MobileIconsViewModel.activeMobileDataSubscriptionId` 初值 +
   `alwaysCollectFlow` 刷新（不用 `Icons`/`CentralSurfaces` 依赖链兜底，保持简单）。
4. **Icons 映射填充**：`Icons.sTintIconMap/sDarkIconMap` 直接 `put`（静态 final Map，
   put 不涉及 final 字段写入，无需 Unsafe）。light 用假 base id 本身（transformResId
   查不到 light map 时返回原 id）。
5. **副卡 ImageView id**：用固定魔数 `0x7F000001`（与假资源同区段，避免撞真实 id）。
6. **单卡模式**：默认"跟随系统"（`isDualStackMode` 才堆叠）；不做上游"单卡切回库存图标"
   的语义，保留一个 `icon_stacked_single_mode`（单卡设备也显示自定义图标）开关可选。

### 4.3 设置项与 UI（替换失效的 SVG 渲染选项）

P0 已落代码（`IconTunerPage.kt` StackedSignalSection 只剩两项，旧 SVG 渲染选项全部删除）：

| Key | 类型/默认 | 说明 |
|---|---|---|
| `icon_stacked_enabled`（保留） | Bool / false | 总开关（堆叠信号） |
| `icon_stacked_scale`（保留名） | Float / 1.0 | 图标缩放（0.5–1.5，0.1 步） |

删除：`icon_stacked_svg_single/stacked`、`padding_start/end`、`alpha_fg/bg/error`、
`type_size/weight/position`、`show_single/stacked/roaming`（及对应 strings，四语言）。

P2（待定，不设死 UI）：多风格（`_ios27` 等样式后缀）、`_thick` 粗版、左右边距、纵向偏移、
单卡模式开关。堆叠模式固定隐藏非数据卡（与上游一致），不单独设"隐藏非数据卡"开关。

### 4.4 `IconManagerHooker` 修正

堆叠开启时**不再** block `single_mobile_sim1/sim2`（视图级方案靠 flow/forceGone 隐藏非数据
卡，block list 会把数据卡自己的图标也杀掉）；`stacked_mobile_icon/type` 槽位保持系统默认
（原生槽位不再启用）。

### 4.5 模块资源

`res/drawable/statusbar_signal_{1|2}_{0..5}(_dark|_tint).xml` × 36 + 颜色
`light/dark_mode_icon_{color|transparent}_single_tone`（沿用上游几何：60×56 viewport，
SIM1 上半区 / SIM2 下半区，级数=实色列数）。drawable 引用模块颜色（在模块 Resources 内解析）。

### 4.6 分阶段

1. **P0（本次）**：`StackedSignalResources` + 新 `StackedSignalHooker` + drawable 资源 +
   UI/key 清理 + `IconManagerHooker` 修正 + 死代码删除；编译/单测/lint/assemble 通过。
2. **P1（真机）**：OS4 设备验证——数据卡堆叠显示、非数据卡隐藏、深浅色/tint 切换、
   副卡位置/缩放、重启与热重载后副卡视图清理。
3. **P2（可选）**：多风格（iOS/thick）、单卡模式、副卡 type 文本、漫游角标联动。

### 4.7 风险

- 级别收集仅靠 `setImageResource` 资源名解析：若某 ROM 的 binder 不再为每个 SIM 调用
  `setImageResource`（例如换成自定义绘制），级别会停更——OS4.0.0.15 实测 binder 仍走
  `setImageResource(transformResId(...))`。
- `mobile_group` 的 `subId` 字段若不在 View 祖先上：`resolveMobileSubId` 逐层探测，
  找不到则视为未知卡（数据卡未知时全走库存渲染，安全回退）。
- 深浅色：若某 ROM 的 `transformResId` 走别的查表（如 `sLightIconMap` 已含假 id 冲突），
  用 `applyingIcons` ThreadLocal + `isModuleFakeRes` 防护可自愈。
- 热重载：`onPrepareHotReload` 只复位状态；副卡视图靠 `boundMobileRoots` + 重启清理
  （`RESTART_RECOMMENDED` 与现状一致）。