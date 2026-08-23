# Google App "Ask about this screen" (针对屏幕内容提问) — 位置与控制链研究

> 研究目标：Google App（com.google.android.googlequicksearchbox，即圈即搜）里"针对屏幕内容提问"
>（Gemini "Ask about this screen"）功能在哪里、被什么控制，以及 HyperTweak 怎么像
> `GoogleAppLiveTranslateHooker` 那样把它打开。
>
> 依据：`/Users/ink/developer/reverse/cache/gapps-quicksearchbox-17.48.13/`
>（jadx 反编译缓存，与现有全屏翻译 hook 验证过的 17.48.13 同版本）。混淆类在 `sources/defpackage/`，
> 未混淆的 Robin UI 类在
> `sources/com/google/android/apps/search/assistant/surfaces/voice/robin/`。
> 本文件的类名/行号均为该版本实测锚点；R8 每次构建会改名，但字符串/数字字面量锚点不变。

---

## 1. 这个功能在哪里（表面地图）

**进程划分（关键）**：Google App 两个进程承载两个完全不同的表面——
- **OMNI 覆盖层（即圈即搜全屏界面，进程 `:googleapp`）**：`GatewayActivity` →
  `LensientActivity`（clmp/clmf）→ `dnrk`（OmniBoxView）+ `dljh`/`dmit` 消费方。
  底部动作栏只有 翻译/音乐搜索/滚动翻译/overflow 四个 `djxk` bean（dkbi/dlgt/dmwl），
  **没有 ask-about 入口**（枚举 `enht.OMNIENT_LIVE_ASK(10)` 存在但无 bean）。
  这里唯一的"针对屏幕内容"痕迹是搜索框零态提示文案
  `lens_lensient_searchbox_aim_text`（dopu.java:287-304）。
- **Robin / Gemini 表面（进程 `:search`）**：`MainActivity`/`FloatyActivity` →
  `azgm`（Gemini 聊天对话框）→ `ayzc`（CollapsedInputController）→ 附件面板
  `ayke`/`aypp`；floaty 由 `bipj` + `bkgo` 承载。"Ask about this screen" **在这里**。

现有全屏翻译 hook 的锚点（dnrk/dmkp/dmwn/dljh/dmit）全在 `:googleapp`，与 ask-about
**不在同一进程、同一表面、同一闸门体系**，其假设（同一覆盖层、invocation-intent
Optional、EXTRA_MEDIA_PROJECTION）**不能迁移**。新 hooker 面向 `:search` 进程
（同一包名 scope，LSPosed 按包挂所有进程；解析不到锚点时应静默降级）。

**实际渲染路径**：`aymq.k` 恒把输入模式置 3（aymq.java:137-145），因此**线上走的是
v3 Compose 面板**：`ayke` 充气 `assistant_robin_attachment_sheet_v3(_scrollable)`
并内嵌 Compose fragment `aypp`（`gemini_attachment_options`，AskAboutX 项在
aypp.java:309-334）；v2 View 布局（ayke.java:404 + 465-527 的按钮代码）只在
`azcn`（FloatyImageOnly 错误路径）下使用，且该路径从不置 `ayoz.p`，**基本是死代码**。
两条路径的配置仍是同一个 proto `ayoz`（经 `aymq.l` 构建，fragment 参数
`"TIKTOK_FRAGMENT_ARGUMENT"` 传递，wfq.fX / wfq.java:7143）。

附件面板的三个打开点（"AttachmentPickerBottomSheet"，azfa.java:171 "+" 按钮等）：
`ayyo`/`azdv`/`azfa` 状态机；floaty 侧由 `bioo`（"Navigate to attachment picker"）
直接 `aymq.k(accountId, aykb)`。

---

## 2. 完整控制链（实测锚点）

```
〔进程 :search —— Robin/Gemini 表面〕
iris-user 旗标 45779012 (com.google.android.apps.search.assistant.mobile.user)
   └─ wkt.dL() (wkt.java:3494)            ← 主开关（默认 false，注册于 glyn.java:21, fjas.b）
        └─ FloatyInputViewModel.r         (构造第 15 参，woo.java:785)
             └─ biqj 守卫: !zB || ((!zH && !r) || z || bgqp.b != null) → 类型=2(隐藏)
             （另有聊天输入侧旗标 45820414/45822451 内联读入 azew，wfq.java:8124-8126）
AskAboutX 启用类型列表（旗标驱动，babu.b() 协程计算）
   └─ babu.e (StateFlow<List>)            (babu.java, e 字段)
        └─ biqf.a() case2 → FloatyInputViewModel.y = gpir.Y(list)   (biqf.java:49)
             └─ y = { bafv.a=AskAboutScreen, bafs.a=Page, baft.a=Pdf, bafu.a=Place, bafw.a=Video, bafx.a=ShareScreenWithLive, ... }
                  └─ biqj.invokeSuspend 选类型 i3 (biqj.java:214-228):
                       y.contains(bafs)→4(page) / bafv→3(SCREEN) / bafw→5(video) / baft→6(pdf) / bafu→7(place) / else 2
                           └─ aykb.w = i3         (biqj.java:290 等，new aykb(..., i8, ...) 第 12 参=w)
                                └─ floatyInputViewModel.z.a(new biod(aykb))
                                     └─ bioo 消费 → aymq.k(AccountId, aykb)   (bioo.java:595-616)
                                          └─ aymq.l(aykb) 静态构建器 (aymq.java)  ── v2/v3 共享
                                               └─ ayoz.p = (w==1)?0 : w-2     (aymq.java:303)  →  w=3 ⇒ p=1
                                                    └─ 线上 v3 Compose: aypp.java:309-334  iDI = a.dI(ayoz.p)
                                                        iDI∈{3..7} → 发射 ayqq(iDI, 前台应用, new-badge) → "Ask this screen"
                                                       (v2 View: ayke.java:465-529，死代码)
```

各环细节：

### 2.1 主旗标：`wkt.dL()` = 45779012
- `wkt.java:3494`：`dL() { return fjam.a("com.google.android.apps.search.assistant.mobile.user", "45779012").f(); }`
  —— 与全屏翻译的 `wtz.lu()`（lens.user/45785436）同一套 iris-user 旗标机制。
- 默认值：`glyn.java:21` 用 `fjas.b` 注册（fjas.b = 值 false），即无服务端推送时关闭。
- `wkt` 是 `final boolean dL()`（包私有小方法，R8 可能重命名/内联——与现有 hook 的
  flag-leaf 处理方式相同：`methodsUsingString("45779012")` 定位 + 0 参 boolean 形状过滤）。
- 聊天输入侧还有两个**内联读取**的同类旗标：45820414 / 45822451
  （wfq.java:8124-8126，`fjam.a("...assistant.mobile.user", ...)` 直接读入 `azew`
  构造参数），语义未命名；作为候选主开关。
- 喂给 `wki`/`wkt`（FloatyInputViewModel 的旗标组）的完整候选清单（agent 2 收集，
  均属 `com.google.android.apps.search.assistant.mobile.user`）：
  45772127、45818682、45809343、45430585、45807257、45820962、45786680、
  45779012、45729286、45755945、45755949、45531663、45786750、45786751、
  45818000、45823745、45775330；另一路来源是 PDS（`asmi`/`eycu` 管道，`asms`
  proto 含 8 个 boolean）。**没有** `CONTEXTUAL_SEARCH_ASK_ABOUT` 系统特性
  （只有主开关 CONTEXTUAL_SEARCH 与 LIVE_TRANSLATE）。
- **结论（agent 2 收尾确认）：AskAboutX 启用列表没有单一 iris 旗标**——列表内容在
  `babu.b()`（未反编译）里按调用方 `AssistStateResult` + 前台应用（`baia`/`bahv`）
  + PDS 动态计算；`glyx.l()`（45649867，`baia.g` "use suspend path"）只是
  代码路径开关，不值得 hook。因此 P0 主 hook 落在 `aykb` 构造器（而非旗标）是
  正确选择；`biqf`/`babu.b` 可作为更早的 floaty-only 注入点（备选）。

### 2.2 启用类型集合：`FloatyInputViewModel.y`
- `FloatyInputViewModel` **未混淆**（完整包名
  `com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.ui.common.input.FloatyInputViewModel`）。
- `y` 是 `public Set`，初始 `gpix.a`（空），由收集器 `biqf`（case 2）在 `babu.e` 每次
  发射时整体替换：`floatyInputViewModel2.y = gpir.Y((List) obj)`（biqf.java:49）。
- 列表内容来自 `babu.b()`（babu.java 中 425 指令的未反编译协程），输入依赖：
  `arhg`（AssistData）、`baia`（前台应用解析器）、`brbs`（ScreenContextUtils）、
  `azyr`→`eycu`（旗标读取）、`anwv`（语言）。具体旗标号埋在 `eycu`/`eycw` 管道里，
  不必要（在更高层 hook 即可，见 §3）。

### 2.3 类型选择：`biqj`（协程，打开附件面板时运行）
`biqj.java`（`binv` case 12 里 `gpuy.t(..., new biqj(floatyInputViewModel, null), 2)` 触发）：
- 守卫（biqj.java:213）：`!zB || ((!zH && !r) || z || bgqp.b != null)` → i3=2（隐藏）
  - `zB = ahrj.b()` = `PowerManager.isInteractive() && !KeyguardManager.isKeyguardLocked()`
    —— 设备状态，亮屏解锁时恒真，无需 hook。
  - `zH = anti.H()` = `d.isEmpty() && e == null`（无活跃助手会话；空闲时真）。
  - `r = wkt.dL()`（旗标 45779012）——**主开关**；r 为假且 zH 为假时按钮必隐藏。
  - `z` = 会话里已存在 ask-about 类附件（`azuw.i`）；`bgqp.b` = 输入会话状态。
- 类型（biqj.java:214-228）：按 `y.contains(...)` 依次取 page/screen/video/pdf/place，否则 2。
  **要让按钮变成 "Ask this screen"，只需让 `y` 包含 `bafv.a` 且守卫通过。**

### 2.4 配置 proto：`aykb` → `aymq.l` → `ayoz`
- `biqj` 用 `i3` 构造 `aykb`（第 12 参 = `w`），发 `biod` 消息 →
  `aymq.l(aykb)`（aymq.java 静态构建器，返回 `ayoz`）。
- `ayoz.p = (w==1) ? 0 : w-2`（aymq.java:303），即 w=3 → p=1。
- `biod(aykb)` 消息由 floaty 命令运行器 `bioo` 消费（bioo.java:595-616，"Navigate to
  attachment picker"）：空间够时走 `aymq.k(accountId, aykb)` → `ayke` 底部面板
  （`AttachmentPickerBottomSheet`，v3 Compose，内部 `aymq.k` 也会调 `aymq.l` 构建
  `ayoz`）；空间不够时直接 `aymq.l(...)` → `ayoz` → `bios` 弹出
  （`RobinAttachmentOptionsPopupDialog`）。**两条路径都经过 `aymq.l`**，
  所以 hook `aymq.l` 能同时覆盖 floaty 的 View/Compose 两种渲染。
- `ayoz` 是 protobuf（gbfp），字段为 public 可直读（ayke 直接读 `ayozVar3.p`）。
- 同一 `ayoz` 也驱动聊天输入侧（`aypp`/`AttachmentOptionsViewModel`，aypp.java:309-330，
  同样的 `a.dI(ayozVar.p)` 判定，iDI∈{3..7} 时显示 AskAboutX 配置 `ayqq`）。

### 2.5 UI 渲染（线上 = v3 Compose 路径）
- **v3 Compose（线上主路径）**：`aypp`（gemini_attachment_options）在
  aypp.java:309-334 读 `ayoz.p`：`iDI = a.dI(ayozVar.p)`，iDI∈{0,1,2} → 不显示；
  iDI∈{3..7} → 发射 `ayqq(iDI, ayozVar.q, ayozVar.v)`（AskAboutX 配置，含前台应用
  组件与 new-badge）→ Compose 渲染 "Ask this screen" 项。
- **v2 View（死代码）**：ayke.java:465-529 的 `assistant_robin_screen_context_ask_about_x_button`
  分支只在 input mode ≠ 3（FloatyImageOnly 错误路径）可达，该路径不置 `ayoz.p`，
  本构建上不会触发。
- `a.dI(int)`（a.java:2817）：`0→2, 1→3, 2→4, 3→5, 4→6, 5→7, 其他→0`。
  p=0 → iDI=2 → 隐藏；p=1 → iDI=3 → "Ask this screen"。
- 点击项动作 id 映射（ayur.java:2099-2111）：SCREEN→304004、PAGE→304001、
  VIDEO→304005、PDF→304002、PLACE→304003。

### 2.6 点击后的流程（"怎么用"，以及国产 ROM 上的真正难点）
1. 点击分两条（分发汇合点相同）：
   - **线上 v3 Compose**：`ayqc`（附件选项点击分发）收到 `ayqq`（AskAboutX 配置）→
     `i = c - 2`，i==1 → `this.g.d()`（ayqc.java:164-175）——`g` 就是 `ayvd`；
   - **v2 View（死代码）**：`aykk` case 0（"#onClick - ask this screen"，aykk.java:30-43）：
     `new dpxa(fudl.TAP).a()` + `aykv.h.b(dpxc, dpwy)`（仅 GIL/VE 上报）+ `aykv.k.d()`。
2. `ayvd.d()`（ayvd.java:47）→ `fkoz.g(new aykd(aykc.j /* ASK_ABOUT_SCREEN */, ...), fragment)`
   → floaty 接收方 `bipk` → `biny.d` case 9（biny.java:243）→
   `binw` case 1 → **`baha.a()` = handleAskAboutScreen**（baha.java:48）。
3. `baha.a()` 之后（agent 深挖验证，锚点已核对）：
   - `badz.f(前台组件)` 按 `brdw` 白名单算出需要的上下文类型（仅截图 / 截图+URI）；
   - `babp.a(...)` → 协程 `babo`：先过**权限闸** `babpVar.f.b(...)`（`bahj` 接口；
     Android 16 实现 `bahf.b()` = `VoiceInteractionManager.canReadScreenContext()`，
     manager 为空/状态未知返回 false，bahf.java:47）→ 不过则 `azzv.b`
     MISSING_SCREEN_CONTEXT_PERMISSION；
   - 截图获取器 `babm` case 5 → `atpu` → `argk.e`（= `arhg`，Ma-Robin-AssistData）
     → 按需取图管道（`aqnj`/`aqnb` → `eddo.e("getScreenshot")` → `ebok.d`）→
     **`eboa` 会话截图缓存**；缓存只由平台 `VoiceInteractionSession` 回调填充：
     `ebjq.onHandleScreenshot(Bitmap)`（ebjq.java:272）→ `eboa.c`。
   - 成功后 `baah.d`（ScreenContext-Uploader）→ `AttachmentCreationDataService.U(...)`
     以 `UploadSource.ScreenContext` 上传成 Gemini 聊天附件；失败统一走
     `babl` ShowError 提示（`azzv.*` 错误码）+ `CONTEXT_NOT_LOADED_*` 埋点，**不崩溃**。
4. **与全屏翻译的本质区别**：
   - 翻译按钮点击有自愈兜底——intent 无 `EXTRA_MEDIA_PROJECTION` 时弹
     `MediaProjectionPermissionCheckerActivity`，用户授权一次即可用；
   - "Ask this screen" **没有**任何自愈兜底、也没有自采屏：截图必须来自
     **平台注入的会话缓存**（`onHandleScreenshot`），而 HyperOS 上 Google App 不是
     平台助手（HyperTweak 的 CTS bridge 只是启动服务），系统大概率不会推截图 →
     点击后链条跑完然后**优雅失败**（"ScreenContext failed" 错误提示）。
   - 即：**只让按钮显示 ≠ 功能可用**。要让功能真正可用，还需要绕过权限闸 +
     注入真实可上传的截图（见 §3 的 P0.5）。
   - 不需要 `EXTRA_MEDIA_PROJECTION`（该 token 只属于 live-translate 机制；
     `MediaProjectionPermissionCheckerActivity` 只被 `dmwj` 等引用）。
   - 前置依赖：Google 账号 + 网络 + Gemini 服务端（上传与回答）；前台应用白名单
     （`brdw`，YouTube 恒允许，非白名单应用只给截图项）。

---

## 3. 怎么控制（Hook 方案，对照 `GoogleAppLiveTranslateHooker`）

新增 `GoogleAppAskAboutScreenHooker`（`hook/rules/googleapp/`），在
`HookEntry` 的 `com.google.android.googlequicksearchbox` 分支挂载；新增偏好
`KEY_ASK_ABOUT_SCREEN = "circle_to_search_ask_about_screen"`（默认关，关时不装任何 hook）。
Google App 已是强制 scope，开关只翻偏好 + 重启 app，与全屏翻译一致。

**进程注意**：所有锚点在 `:search` 进程（Robin/Gemini 表面），hooker 在 `:googleapp`
进程解析不到时应静默跳过（现有 `GoogleAppLiveTranslateHooker` 同款降级模式）。
不要试图在 OMNI 覆盖层里加 ask-about 按钮——那是另一个进程/表面。

| 优先级 | Hook 目标 | 做法 | 依据 / 风险 |
|---|---|---|---|
| P0（主） | `aykb` 22 参 synthetic 构造器 | **before-hook**：`args[0]==2`（source=FLOATY）且 `args[12]==2`（w=none）→ `args[12]=3`（screen） | **构造器永不被内联**，单点覆盖整个 CTS floaty 路径（biqj 全部 3 个 `new aykb(...)` 调用点）。解析：`aykb.toString()` 含字符串锚点 `"screenContextType="`（`methodsUsingString` → materialize 类 → 找 22 参 `<init>`），或按 22 参形状匹配。只在 args[0]==2（FLOATY）时生效，不碰其他表面（azai/azae/ayzm 等） |
| P0（保险丝） | `wkt.dL()`（旗标 45779012） | `methodsUsingString("45779012")` + 0 参 boolean 形状，after 强制 true（照抄 `hookFlagLeaf`） | 主开关 r；同时覆盖 zH 为假的情形（`!zH && !r` 失效）。wkt 包私有小方法可能被 R8 内联 → `deoptimize`（现有 hook 已有此做法）。**没有** `CONTEXTUAL_SEARCH_ASK_ABOUT` 系统特性（只有 CONTEXTUAL_SEARCH 与 LIVE_TRANSLATE） |
| P0.5 | 权限闸绕过 | after-hook `babp.f`（`bahj.b(List)` 接口方法，虚调用）：返回 true；或 `bahf.b()`（`canReadScreenContext` 包装） | Android 16 新模型下 `canReadScreenContext()` 在非平台助手时返回 false → `azzv.b`。旧模型 `brbs.g()/h()`（`assist_screenshot_enabled`/`assist_structure_enabled`）在设置缺省时**默认放行**（brbs.java:86），不是主要障碍 |
| P0.5 | **截图注入（功能可用的硬前提）** | 让 `baha.a()` 链条拿到真实截图：方案 A——hook `atpu`/`arhg.e`（截图取回器）返回 `atop(new arhn(uri))`，uri 指向模块用 MediaProjection/SurfaceControl/root screencap 抓屏后写入的 content uri（必须真实可上传，否则后续 `azzv.e`/`azzv.k`）；方案 B——hook `eboa.c(Bitmap)`/`ebjq.onHandleScreenshot` 把模块抓屏塞进会话缓存 | 没有它，点击必然走 `azzv.d`（RESOURCE_UNAVAILABLE）/`azzv.a`（NOT_READY）优雅失败。**这是与全屏翻译 hook 最大的不同：翻译只需要 token 授权弹窗，ask-about 需要一个截图源** |
| P0.5 | 前台应用白名单绕过 | `biny.a()`/`babq.d(ComponentName)`（biny.java:113-134，`brdw` 白名单）强制 true | 非白名单应用只提供截图项且容易失败；强制后任意应用可用 |
| P1（双表面兜底） | `aymq.l(aykb)` 静态构建器 | after-hook：把返回的 `ayoz` 的 `p` 置 1（public 字段）并 `b |= 1024`（bitmask 也是 public） | 同时覆盖 floaty 与聊天输入两条渲染路径（v2/v3、floaty/聊天都经它）；几百指令的静态方法，R8 内联概率低。解析：从 `bioo`（日志 "Command: Navigate to attachment picker"）或 `ayke`（`0x7f0b112a`/`304004`）切入回溯，或按 `(aykb)→ayoz` 静态方法形状匹配。注意 fragment 参数直传 `ayoz` 的入口（`wfq.fX`）覆盖不到 |
| P2（可选，全路径兜底） | `a.dI(int)` 静态映射 | after-hook：arg==0（隐藏态）时返回 3（screen），用 **ThreadLocal 开关限定**——在 `ayke.onCreateView`（ayke.java:317）与 `aypp` 的 performCreate lambda（aypp.java:118）的 before/after 里 set/clear | 覆盖**客户端构建与服务端解析两条 ayoz 来源**。`a.dI` 是静态小方法（未来构建有内联风险，需 `deoptimize` 防御）；还被 `fuom.java:30`/`cfxo.java:96` 用于无关枚举映射，所以必须 ThreadLocal 限定作用域 |

不需要 hook：`ahrj.b()`（设备状态）、`anti.H()`（空闲态）、`brbs.g()/h()`
（截图/结构能力，Circle to Search 已在用）。

### 预期效果
- 开关打开 + Google App 重启后，即圈即搜浮层点 "+" 打开附件面板，
  "Ask this screen" 按钮出现（P0 双 hook）；
- 只做 P0：按钮出现，但点击后因缺截图源而**优雅失败**（"ScreenContext failed" 提示）——
  适合先验证按钮路径，不会崩溃；
- P0 + P0.5（权限绕过 + 截图注入 + 白名单绕过）：点击后截图进入 Gemini 会话，
  可正常提问；若同时上 P1，Gemini 聊天输入（非 floaty）的附件选项里也会出现
  AskAboutX 入口。

### 验证清单
1. `compileDebugKotlin` / `testDebugUnitTest` / `lintDebug` / `assembleDebug` 通过；
2. 装 debug 版，开开关重启 Google App，logcat 应有 `HOOK_OK`（aykb 构造器 /
   wkt.dL / babp.f / 截图注入点），无 `before hook failed`；
3. 真机分两步：先只验证按钮出现（P0）；再验证点击后截图成功上传并进入 Gemini 会话（P0.5）；
4. 回归：关掉开关后按钮消失（hook 不装）；全屏翻译按钮仍正常。

---

## 4. 未决 / 后续
- `babu.e` 列表的**具体旗标号**埋在 `babu.b()`（未反编译）与 `eycu`/`eycw` 管道里；
  本方案不需要它（P0 主 hook 直接改 `aykb` 构造器，绕开列表内容），但若想要
  "跟随 Google 服务端配置"的精细语义，可以后续从 `eycg(str,...)`/`fjup` 的 str
  参数或 §2.1 的候选旗标清单里挖。
- **截图注入的落地方案**待选：`eboa` 会话缓存（方案 B）vs 截图取回器返回值
  （方案 A，`atpu`/`arhg.e`）；需要真机确认 HyperOS 上 `VoiceInteractionSession`
  是否真的从不推截图（若系统其实推了，P0.5 的截图注入可降级为可选）。
- `anti`（anhc → anti）的 `d/e` 具体语义（活跃会话判定）未深挖；空闲态默认成立。
- 上传链路（`baah.d` → `AttachmentCreationDataService.U`）对 content uri 的要求
  （provider、mime、大小）需按模块抓屏产物核对，避免 `azzv.e`（上传失败）。
- R8 混淆随版本变化：锚点（"45779012"、`"screenContextType="`（aykb.toString）、
  "AskAboutScreen"、FloatyInputViewModel 全名、
  "Command: Navigate to attachment picker"、`0x7f0b112a`、`304004`）
  是字符串/数字/未混淆名，跨版本稳定；`aykb`/`biqf`/`wkt`/`aymq`/`bahf` 类名
  需用 DexKit 解析（现有 `GoogleAppLiveTranslateHooker` 同款模式，可复用
  `methodsUsingString` + `materializeClass`，类 matcher 用 `MethodMatcher.paramTypes`
  形状匹配——DexKit 2.2.0 无 `usingClass`）。

---

## 5. 对比上游 MiuiBackGestureHook commit 0f603b1d（Lensient "Ask about this screen" 搜索框）

上游在 2026-08-23 的 `0f603b1d`（"feat: add Google Lens 'Ask about this screen' contextual
searchbox"）实现了**同族但不同表面**的功能：OMNI 覆盖层里的 Lensient 搜索框
（`:googleapp` 进程），不是我们研究的 Robin 附件面板（`:search`）。它在 17.48.13
缓存里全部可对号入座：

### 5.1 上游的实现（对照本缓存实测）
- **解析链（DexKit，全部 fail-closed 唯一性校验）**：
  `findMethod { paramCount(0) && usingEqStrings("com.google.android.apps.search.lens.user", "45781832", "45765529") }`
  → 唯一命中 `wry.iX()`（wry.java:9590，0 参非 void，body 9634 内联读四个 lens.user 旗标）
  → 其 invokes 里返回类型==自身返回类型的构造器 = `doqf.<init>`（41 参）
  → 第 7 个参数类型（index 6）= `djyp`（"coordinator"）
  → `djyp.<init>`（8 参，唯一构造点 wri.java:430）里唯一的 0 参 boolean invoke =
  **`bydc.c()`**（djyp.java:27 `this.d = ((bydc) gpgxVar.hS()).c();`）
  → 对该方法 after-hook：pref 开时把 false 结果强制为 TRUE；deoptimize 该能力方法的
  全部调用者 + coordinator 构造器。
- **旗标关系**：`doqf.x` = 45781832（AIM 搜索框）、`doqf.y` = 45765529（AIM 屏幕上下文）
  ——只是**导航锚点**；真正被强制的是 `bydc.c()`，其字段 `c` 由
  `wtz.mD()` = 旗标 **45730537**（`com.google.android.libraries.search.googleapp.user`）
  喂入（wty.java:513 `new bydc(..., wtzVar17.mD(), wtzVar17.mE(), ...)`）。
  45781832/45765529 均以 `fjas.b` 注册（wvx.java:3499-3500，默认 false）。
- **能力闸的下游消费者**：`djyp.b() = d && dmjh.f()`、`d() = b() && i`（d = bydc.c()）；
  消费点包括 `dnrk.java:92`（OMNI 底部栏 `A = z5 && djyp.d() && !z4`）、
  `dopu.java:681` / `dopx.java:77`（Lensient 搜索框）、`dnpr.java:31`、`dnpk.java:4469`
  （`vidcip` 配置消费者，dnpk.java:4205 `encxVar.r.containsKey("vidcip")`）。
- **安全规则（AGENTS.md）**：只覆盖"成功返回 false"的结果；不造假缩略图、不伪造
  Build、不绕过 consent、不制造 capture/token 路径；解析必须唯一，歧义/缺失 fail-closed；
  hook id 作为热重载生命周期 key，DexKit 解析期间延迟热重载。

### 5.2 与我们的方案对比

| 维度 | 上游 0f603b1d | 我们的方案（本文档） |
|---|---|---|
| 目标表面 | OMNI 覆盖层 Lensient 搜索框（`doqf`/`djyp`/`bydc`，`:googleapp`） | Robin floaty/聊天附件面板（`aypp`/`ayke`，`:search`） |
| 功能形态 | 搜索框内 "Ask about this screen"（屏幕缩略图保留能力闸） | "+" 附件菜单里的 "Ask this screen" 按钮项 |
| 主 hook | `bydc.c()` after 强制 TRUE（能力布尔，虚调用，天然不可内联） | `aykb` 22 参构造器 before 改写 w=3（构造器永不被内联） |
| 锚点 | lens.user 45781832/45765529 + googleapp.user 45730537 | assistant.mobile.user 45779012 + "screenContextType=" + 304004 |
| 解析方式 | 字符串三连 + 构造器形状 + 唯一性校验（fail-closed） | `methodsUsingString` + 形状过滤（同款，可复用） |
| 截图源 | 不需要注入——OMNI 覆盖层本身就是 CTS 捕获会话（`cnph`/`cnpc`） | 需要 P0.5 截图注入——floaty 截图来自平台会话缓存，缺源优雅失败 |
| 生效开关 | `KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX` && Circle to Search 主开关；带 scope 审批弹窗（他们动态把 Google App 加进 LSPosed scope） | `KEY_ASK_ABOUT_SCREEN`；Google App 已是 HyperTweak 强制 scope，无需审批 |
| 热重载 | hook id `google_lens_aim_screen_capability`，`googleDexResolutionInFlight` 延迟重载 | 沿用 BaseHooker 同款模式 |

### 5.3 结论与启示
1. **两个"针对屏幕内容提问"入口，两个进程**：OMNI 搜索框（上游做的，`:googleapp`）
   和 Robin 附件面板（我们研究的，`:search`）。用户问的"即圈即搜里针对屏幕内容提问"
   很可能两个都是——建议 HyperTweak 两条都做：搜索框按上游 0f603b1d 的算法移植
   （与全屏翻译同进程，可共用解析基础设施），附件面板按本文档 P0 方案。
2. **上游解析链在 17.48.13 上可直接复现**（wry.iX → doqf.<init> → djyp → bydc.c()），
   锚点全部实测一致；比我们 P2 的 `a.dI` 更干净、更贴近原生能力闸。
3. **AGENTS.md 的 vidcip 规则是本构建不成立的**：上游要求"能力方法携带 vidcip 标记"，
   但 17.48.13 上唯一解析结果是 `bydc.c()`（无 vidcip 字面量；vidcip 只出现在 `dnpk`
   的配置检查里）。移植时必须按目标构建重新校验该规则，否则 fail-closed 会拒绝解析。
4. **纪律一致**：只翻转能力布尔、不造假数据路径、fail-closed、热重载隔离——与本文档
   的 P0.5 边界（不伪造截图、不绕过 consent）完全同构，可直接抄进 HyperTweak 的规则。
5. **语义差异提醒**：上游 hook 只解决 `bydc.c()` 一个闸；若 45781832/45765529
   （doqf.x/y）在国产 ROM 上也没被服务端推送，搜索框的 AIM 入口本身可能仍不显示
   ——移植时需真机验证是否需要一并强制 doqf.x/y（它们在 `wry.iX()` 里内联读取，
   是更小的 getter，可用同一 `hookFlagLeaf` 模式）。
---

## 6. 已实现（2026-08-23）

按上游 0f603b1d 的算法落地了 **Lensient 搜索框**那条（`:googleapp` 进程），开关放在
"即圈即搜内全屏翻译"下方：

- `hook/rules/googleapp/GoogleAppAskAboutScreenHooker.kt`（新）：DexKit 链
  `usingEqStrings(lens.user, 45781832, 45765529)` + 0 参非 void → `doqf.<init>` →
  param[6] `djyp` → `djyp.<init>` 唯一 0 参 boolean invoke = `bydc.c()` →
  after 强制 TRUE；deoptimize coordinator 构造器 + 能力方法全部调用者；全程 fail-closed。
- `Preferences.KEY_ASK_ABOUT_SCREEN = "circle_to_search_ask_about_screen"`（默认关）。
- `HookEntry` googlequicksearchbox 分支追加注册（与全屏翻译并列）。
- `MainActivity`：状态 + `handleAskAboutScreenChange`（同全屏翻译：flush → scope
  request → force-stop Google App，关时仅 force-stop）。
- `TweaksScreen`：开关置于 `tweaks_full_screen_translate_*` 下方；新增中英文案
  （`tweaks_ask_about_screen_*` + 通用 `google_feature_scope_*`）。
- 验证：`compileDebugKotlin` / `testDebugUnitTest` / `lintDebug` / `assembleDebug` 全过，
  `HyperTweak-v1.8.0-beta-debug.apk` 产出；lint 无新告警。
- 真机预期：开关开 + Google App 重启后 logcat 应见
  `AskAboutScreen: HOOK_OK Lensient screen capability ...`；搜索框"Ask about this screen"
  可用。若 AIM 搜索框入口仍不显示（45781832/45765529 即 doqf.x/y 未被服务端推送），
  后续再加 `hookFlagLeaf` 式强制（见 §5.3-5）。Robin 附件面板那条（P0/P0.5）未在本
  会话实现。
