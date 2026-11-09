# Root 设置调试与诊断包

## 权限与入口

调试页 → **允许 root 设置调试**：默认关闭，每次开启授权 30 分钟；关闭立即阻止后续操作，重启或 APK 更新后失效。独立本地授权不进入设置备份/恢复，也不能经远端接口开启或续期。已开始提交的事务返回真实结果，关闭权限不撤销已经提交的设置。

Provider：`content://com.takekazex.hypertweak.debug`。所有方法校验真实 Binder UID 0，普通 adb shell、system UID 和其他应用均被拒绝。`status` 在关闭时仅返回授权剩余时间、模块版本和后端连接状态；设置枚举/读取/修改和采集控制均需要有效会话。身份先在 Binder 入口捕获，然后清除调用身份访问模块自己的服务，结束时恢复。没有网络监听、任意命令或文件路径执行接口。

这是应用层的访问控制；设备 root 本身可以绕过应用数据权限。原有 `.logdump` 只读日志接口继续保持原来的 root/shell 策略。

## 无线 ADB

调试页 → **调试操作** → **无线 ADB**。**连接方式**可选传统 TCP 或 Android 原生配对。原生配对由系统开发者选项管理，使用 TLS、系统随机端口和系统可信网络设置。切到原生模式会停止 HyperTweak 的传统监听，切回传统模式前须关闭系统无线调试。系统监听启动后，本页也会显示可长按复制的地址。

传统 TCP 保留 ADB 密钥授权和自定义端口，传输内容未加密。**手动开关**可以单次开启或关闭当前监听。手动开启时校验当前 Wi-Fi 的 SSID 和 BSSID，断开或接入点变化时结束这次监听。手动关闭后，当前连接暂停自动开启，直到检测到离开该网络。**监听状态**单独显示，长按可复制 `IP:端口`。

**重启后保持**有三种模式，开机解锁后会应用保存的选择：

- **关闭（仅单次打开）**：只通过手动开关开启，重启时不恢复。
- **仅绑定 Wi-Fi**：当前连接的 SSID 和 BSSID 同时匹配才自动开启。使用 **绑定 Wi-Fi** 下拉栏保存当前接入点时，需要强生物识别，并会在验证结束后重新核对两项标识。
- **始终保持开启（随机端口）**：连接任意 Wi-Fi 后自动开启，每次启动随机选择空闲端口。接入点变更时关闭原监听，再核验新接入点并开启。断开 Wi-Fi 时关闭监听。

关闭自动开启且没有监听时，后台不查询 Wi-Fi。自动开启模式等待连接时约每 10 秒读取一次系统连接状态，监听期间约每秒校验一次。查询不会发起 Wi-Fi 扫描。传统模式让 adbd 使用本机 Unix 套接字，对外由中继绑定已验证的 Wi-Fi 接口和 IPv4 地址。守护进程只向内存文件系统续期，约 4 秒未续期时中继关闭监听和已有连接。传统模式需要系统 ADB 密钥授权。

前两种模式默认使用随机端口，也可在监听关闭时指定 1–65535 内的端口。电脑端使用 `adb connect IP:端口` 连接。首次切换到私有套接字时会重启 `adbd`，已有连接会短暂中断。后续启停只控制中继。旧版持久端口和旧防火墙规则在迁移时清除。清除应用数据或恢复默认设置会撤销绑定。系统配对式无线调试由开发者选项独立管理。

## AI / adb 客户端

首选仓库内的 `.github/scripts/hypertweak_debug.py`，依赖 Python 3 与 adb。默认通过 `su -c` 执行；已经 `adb root` 的环境加 `--root-adbd`。多设备指定 `--serial`，多用户指定 `--user`，不要默认为当前前台用户安装了模块。

```sh
python3 .github/scripts/hypertweak_debug.py status
python3 .github/scripts/hypertweak_debug.py list picker
python3 .github/scripts/hypertweak_debug.py get native_photo_picker
python3 .github/scripts/hypertweak_debug.py set native_photo_picker true
python3 .github/scripts/hypertweak_debug.py --expected-revision 123 reset native_photo_picker
python3 .github/scripts/hypertweak_debug.py patch /absolute/path/patch.json
```

`patch.json`：

```json
{"values":{"native_photo_picker":true,"native_file_picker":false},"remove":[]}
```

布尔/数值/字符串/字符串数组按 JSON 类型输入；字符串参数例如 `'"leica"'`。CLI 对两层 shell 参数分别转义，不执行参数中的引号、反引号或 `$()`。接口失败返回非零退出码。

原始 content call 使用 `--method` 和 `--arg`：

```sh
adb shell su -c 'content call --uri content://com.takekazex.hypertweak.debug --method status'
```

| 方法 | arg |
| --- | --- |
| `status` | 无 |
| `list` / `describe` | 可选 key 子串 |
| `get` | 完整 key |
| `set` | `{"protocol":1,"values":{"key":true}}`，恰好一个设置 |
| `patch` | `{"protocol":1,"values":{},"remove":[]}`，合计 1–64 项 |
| `reset` | `{"protocol":1,"remove":["key"]}` |
| `collect` | `module` 或 `system` |
| `collection_status` / `collection_cancel` | 无 |

响应 Bundle 的 `data` 字段是 JSON，`protocol=1`，`ok` 表示方法结果。不要解析预览文本或把返回文本拼成 shell 命令。写请求最多 64 KiB，字符串最多 16384 字符，集合最多 256 项；类型、有限浮点数、已声明范围、native/截图/手势/Dock 快照合同在提交前检查。未知或退休 key、内部 revision/epoch、设备无线属性镜像不可写。

`expected_revision` 是设置运行时快照的乐观锁。省略时按共享队列顺序提交；提供时冲突拒绝，不自动重试覆盖。它不是宿主回执，也不跟踪原有 UI 对未注册为运行时设置的本地元数据更新。

## 提交、迁移与 UI

调试事务与 UI 共用 Preferences、串行 writer 和 RuntimeSettingsSignal，不直接编辑 XML/LSPosed 数据库。远端确认后更新本地镜像；拒绝时回滚远端及 shadow，保持上次已确认快照。本地镜像单独失败时返回 `local_mirror=false`，不把远端成功误报为失败。排队超时可取消尚未开始的事务，开始后等待真实提交回执。

读取分别返回 `present`、`stored`、`effective`、`source`。Picker、指纹分项、电源键、相机模式、省电分项、左图标和叠加信号的迁移/依赖单独投影；普通设置返回显式值或登记默认值。退休设置默认值未知，不能因备份里存在而变成可写项。`effective` 是配置语义，不证明宿主已应用；宿主内部归一化和可用性仍以功能实现及人工验收为准。

修改电源键/相机模式时清除被替代的旧 key；修改独立省电功能时保留其他分项的实际旧选择并打开旧 master。native 修改生成新的 native revision。Launcher alias 和应用快捷方式通过应用侧管理器执行副作用，结果在 `app_side_effects` 中单独返回。

提交后刷新应用根设置，并重建导航条目的设置缓存；保留返回栈和主分页。已打开页面的临时控件/滚动状态可能因外部配置刷新重置。正常导航、旋转与诊断采集不触发这个刷新。

`state=committed` 表示后端提交，`runtime_snapshot` 表示设置快照确认。`host_applied=null`：当前协议没有宿主逐进程应用回执。有运行时分组不等于任何条件下都立即改变界面。无需重启、下次请求生效或需重启的边界以各功能文档为准。接口不自动重启宿主。

审计写入 `files/logs/debug-settings-audit.txt`，有界保存 key、操作和提交结果，不记录设置值或请求正文。

## 诊断采集

调试页可直接生成诊断包，任务属于应用进程，离开/重建页面不会重复启动。设置远控开关无需打开；页面是本机用户操作。AI 经 Provider 触发采集则需要有效 root 会话。

- **模块范围（默认）**：模块应用日志、结构化设置快照、设置操作审计、声明 scope 和版本元数据。不会主动申请 root；敏感设置值脱敏，日志本身仍可能包含隐私。
- **系统范围（主动选择）**：在模块范围上整合 v2 的 LSPosed、系统日志、DropBox、ANR、tombstone、pstore、内核和重启证据。需要用户给应用 root；可能包含其他模块和其他应用的日志。

每个包包含机器可读 manifest；模块 manifest 保存各来源状态，系统 manifest 指向 `extra-status.txt` 的逐命令退出码/字节数和各项 error 文件。缺失数据不是成功，也不证明没有故障。重启摘要只报告线索，当前 boot、旧 boot 和持久日志可能混杂，不能凭关键词断言上次重启原因。

系统脚本固定总时限 180 秒，命令通常最多 15 秒；包装前拒绝超过 160 MiB 的采集目录，每项 stdout 流只保留末尾 8 MiB、stderr 只保留末尾 64 KiB，再按来源裁剪；不设置命令本身的文件大小 ulimit。应用包含 root 授权等待的总等待最多 195 秒。取消停止应用等待并终止启动器，root 子任务最迟受脚本总时限终止；部分文件留待清理，不应把取消理解为瞬时终止所有 su 子进程。

采集只读系统状态，不修改开关、不清空日志、不自动上传。Provider 日志导出可能启动应用并创建模块自己的日志文件。输出保存在应用私有 `files/diagnostics/job-UUID`，FileProvider 仅对用户选中的分享 URI 授予读权限。下一次采集清理超过 24 小时的旧任务；卸载会清除。系统任务的私有 0700 父目录隔离其子文件，最终归档权限为 0600。

## 维护与检查

- 设置清单由 `.github/scripts/generate_debug_catalog.py` 生成到 `settings/contract/debug/DebugSettingsCatalog.kt`。它扫描现有类型化读取，生成静态表，不在设备上解析源码或反射猜 key。
- 增加/迁移设置后重新生成并审查类型、默认值和 helper override；特殊有效值与副作用更新 DebugSettingsService，约束更新 DebugSetting。生成器不能推导任意 helper 的业务语义；未知/退休项保持不可写。
- CI 的 `--check` 阻止生成文件漂移。不要通过手改生成文件解决差异。动态 slot 取自 UI 的固定清单，运行时归属复用 RuntimeSettingsGroups。
- `app/src/main/assets/diagnostics/collector.sh` 是内置与独立运行的唯一脚本源，可直接取出交给其他用户。v3 基于 Downloads 的 HyperTweak-Diagnostics-V2.sh；原始文件 SHA-256 见下文。修改采集规则时同步 collector/schema 与本文，保持旧来源缺失时可降级。
- 修改设置事务跑共享设置测试；修改协议/权限跑 DebugSetting、DebugAccessSession、DebugClient 和 SettingsMutationWriter 回归；UI 跑 lint；交付检查 release/R8 中 Provider、脚本资源与协议键。设备验证需要分别记录：拒绝权限、读写/reset/版本冲突、快照发布、日志、诊断包完整性。人工验收界面，不采集设备截图。

原始 v2 SHA-256：08b3a47bf5dbbf8e2e0c577610b5cf1c9d31543e5030302a3be6f35282968afe。
