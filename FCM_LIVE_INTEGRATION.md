# FCM Live Integration - COMPLETED

## 概述

成功将 **HyperOS_FCM_Live** 功能集成到 HyperTweak v1.6.0，作为可选模块移除 HyperOS 对 Google Cloud Messaging (FCM/GCM) 的限制。

## 已完成的工作

### 1. UI 集成
- Tweaks 页 "System Core" 部分新增 "Fix Google Push (FCM Live)" 开关
- 包含电池使用警告提示
- 状态管理完整连接：MainActivity → HyperTweakNavContainer → MainPagerScreen → TweaksScreen

### 2. 配置与架构
- 添加 `Preferences.KEY_FCM_LIVE_ENABLED` 配置项
- 将 `com.miui.powerkeeper` 添加到 `scope.list`
- 在 `HookEntry` 中注册两个 hooker：
  - `FcmLiveSystemHooker` (system server)
  - `FcmLivePowerKeeperHooker` (powerkeeper process)

### 3. 核心 Hooker 实现

#### FcmLiveSystemHooker (System Server)
实现了 7 个关键 hook：

1. **GreezeManagerService**
   - `isAllowBroadcast` - 允许 FCM 广播
   - `deferBroadcastForMiui` - 不延迟 FCM 广播
   - `triggerGMSLimitAction` - 禁用 GMS 限制

2. **DomesticPolicyManager**
   - `deferBroadcast` - 返回 false

3. **ListAppsManager**
   - 从黑名单中移除 GMS
   - 将 GMS 添加到白名单

4. **BroadcastQueueModernStubImpl**
   - `checkApplicationAutoStart` - 允许 GMS 自启动

5. **ProcessPolicy**
   - `getWhiteList` - 将 GMS 添加到白名单

6. **AwareResourceControl**
   - 从网络黑名单中移除 GMS

7. **ActivityManagerService**
   - `broadcastIntent*` - 添加 GMS 到临时允许列表，添加 FLAG_INCLUDE_STOPPED_PACKAGES
   - 支持 Android S/TIRAMISU/更高版本的不同方法签名

#### FcmLivePowerKeeperHooker (PowerKeeper Process)
实现了 2 个 hook：

1. **NetdExecutor & GmsObserver**
   - `initGmsChain` - 设置为 "ACCEPT"
   - `updateGmsAlarm` - 设为 false
   - `updateGmsNetWork` - 设为 false
   - `updateGoogleReletivesWakelock` - 设为 false

2. **GlobalFeatureConfigureHelper**
   - `getDozeWhiteListApps` - 将 GMS 添加到 Doze 白名单

### 4. 技术实现细节

- 使用 `object` + `StaticHooker` 模式（单例）
- 使用 `toClassOrNull()` 进行类解析
- 使用 EzHookTool DSL: `hook { before/after }`
- 反射访问 PowerExemptionManager (隐藏 API)
- 当前 minSdk 35，因此直接使用 Android S/TIRAMISU+ 相关方法签名
- 完整的错误处理和日志记录

### 5. 编译与构建
- ✅ 成功编译通过
- ✅ 生成 APK: `HyperTweak-v1.6.0-beta-debug.apk` (69MB)

## 📋 使用说明

### 启用 FCM Live

1. 打开 HyperTweak 设置
2. 进入 "Features" / "System Core"
3. 开启 "Fix Google Push (FCM Live)" 开关
4. 重启设备或软重启以重新加载 system hooks，并重启 PowerKeeper 进程

### 预期效果

**启用后**：
- ✅ Google 推送通知（FCM/GCM）可靠接收
- ✅ GMS 后台活动不受 HyperOS 限制
- ⚠️ 可能增加电池消耗

**禁用后**：
- ❌ 推送通知可能延迟或丢失
- ✅ 更好的电池续航
- ✅ 保持 HyperOS 原始限制

### 受影响的作用域

- `system` (system_server)
- `com.miui.powerkeeper`

注意：应用内 "Restart Scoped Apps" 只暴露应用进程重启，不提供直接重启 `system_server` 的入口。

## 🔍 调试

### 查看日志

```bash
# 过滤 FCM Live 相关日志
adb logcat | grep "FcmLive"

# 查看 hook 注册情况
adb logcat | grep "hooks registered"

# 查看 HyperTweak 完整日志
adb logcat | grep "HyperTweak"
```

### 预期日志输出

```
FcmLiveSystem: GreezeManagerService hooks registered
FcmLiveSystem: DomesticPolicyManager hooks registered
FcmLiveSystem: ListAppsManager hooks registered
FcmLiveSystem: BroadcastQueueModernStubImpl hooks registered
FcmLiveSystem: ProcessPolicy hooks registered
FcmLiveSystem: AwareResourceControl hooks registered
FcmLiveSystem: ActivityManagerService hooks registered
FcmLivePowerKeeper: GmsObserver hooks registered
FcmLivePowerKeeper: GlobalFeatureConfigureHelper hooks registered
```

## 🧪 测试建议

1. **功能测试**
   - 安装带有推送通知的应用（如 Gmail、Telegram）
   - 发送测试推送
   - 验证通知是否及时到达

2. **电池测试**
   - 启用前后对比电池统计
   - 监控 GMS 的电池使用

3. **稳定性测试**
   - 检查系统日志是否有异常
   - 验证 Hot Reload 是否正常工作

## 📚 技术参考

### 原始项目
- [HyperOS_FCM_Live by howard20181](https://github.com/howard20181/HyperOS_FCM_Live)

### 关键技术
- **EzHookTool**: Kotlin Xposed helper library
- **StaticHooker**: HyperTweak 的静态 hooker 基类
- **toClassOrNull()**: 安全的类加载扩展函数
- **PowerExemptionManager**: Android S+ 的电源管理 API (隐藏)

### HyperOS 限制机制

HyperOS 通过以下组件限制 GMS：
1. **GreezeManagerService** - 冻结管理，控制应用活动
2. **DomesticPolicyManager** - 国内版本策略
3. **ListAppsManager** - 黑白名单管理
4. **ProcessPolicy** - 进程策略
5. **AwareResourceControl** - 资源控制
6. **PowerKeeper** - 电源管理

本模块通过 hook 这些组件，选择性地为 GMS 解除限制。

## ⚠️ 注意事项

1. **电池消耗**: 解除 GMS 限制后，电池消耗可能增加
2. **系统稳定性**: Hook 系统核心服务，需要充分测试
3. **版本兼容性**: 已支持 Android S/TIRAMISU+，其他版本可能需要调整
4. **Hot Reload**: 修改配置后需要重启相关进程才能生效

## 🎉 总结

FCM Live 功能已完整集成到 HyperTweak v1.6.0，提供了一个可选的解决方案来改善 HyperOS 设备上的 Google 推送通知体验。用户可以根据自己的需求在推送可靠性和电池续航之间做出权衡。
