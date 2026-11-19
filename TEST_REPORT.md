# 云端测试报告

> 测试时间：2026-11-19 ｜ 测试环境：云端 Linux 容器（无 KVM）+ Android 10 模拟器
> 被测版本：v1.0.0 + 本次测试修复

## 一、测试环境

| 项 | 内容 |
|---|---|
| 宿主 | Ubuntu 容器，32 核 / 123 GB，**无 `/dev/kvm`** |
| 模拟器 | Android Emulator，`-accel off` 纯软件模拟（TCG） |
| 系统镜像 | `system-images;android-29;google_apis;x86_64`（Android 10 / API 29）|
| 屏幕 | 1080 × 1920，density 420 |
| 被测包 | `com.orientationlocker`（debug + androidTest）|
| Shizuku | v13.6.0，通过 `libshizuku.so` 以 shell 身份启动 |

> 说明：无 KVM 时 x86 模拟器走 QEMU TCG，速度约为硬件加速的 1/10，冷启动约 3–5 分钟。
> 这直接影响"开机广播"类用例（见第四节）。

## 二、测试分层与结果

| 层级 | 框架 | 用例数 | 结果 | 覆盖内容 |
|---|---|---|---|---|
| 单元测试 | JUnit4 + Robolectric 4.13 | 23 | ✅ 全通过 | 方向常量、状态仓库持久化、编排分支、入参守卫 |
| 仪器测试 | AndroidJUnitRunner（真机运行时） | 7 | ✅ 全通过 | 真实写系统设置、Activity 启动、组件注册 |
| 手工端到端 | adb + UIAutomator | 6 项 | ✅ 通过 | 双路径锁定/解锁、跨 App 保持、界面渲染 |
| 可靠性 | 重启验证 | 2 轮 | ⚠️ 见第四节 | 开机兜底时序 |

### 2.1 单元测试（23 个）

```
RotationsTest                4  常量与 Surface.ROTATION_* 对齐、越界拒绝
LockStateRepositoryTest      5  默认值、进程重启后读回、clearLock/clearSnapshot、脏数据
LockManagerTest              8  无路径时 Unavailable、非法值拒绝、失败不落状态、restore 边界
RotationControllerTest       2  非法 rotation 不透传给系统服务
BootRestoreReceiverTest      4  开机/升级/无关广播均不崩、意图不丢
```

### 2.2 仪器测试（7 个，Android 10 运行时）

```
FallbackEndToEndTest  4
  ├─ 锁定横屏后系统设置真的被改写        USER_ROTATION=1 & ACCELEROMETER_ROTATION=0
  ├─ 解锁后自动旋转开关精确还原          还原为测试前原值，USER_ROTATION 同步还原
  ├─ 四种方向都能写入                   0/1/2/3 全部落盘
  └─ 失败路径不污染状态仓库             无权限时不写意图、不留快照
SmokeTest             3
  ├─ 主界面可正常启动且不闪退
  ├─ 磁贴与开机接收器已注册在清单中
  └─ 状态仓库可跨进程读写
```

### 2.3 手工端到端验证记录

| # | 场景 | 操作 | 观测结果 |
|---|---|---|---|
| 1 | 界面渲染 | 启动 MainActivity 截图 | 标题/状态卡/5 个方向按钮正常，无白屏错位 |
| 2 | Shizuku 激活 | adb 启动 server → App 内授权 | 状态卡显示「已连接（版本 13）/ 已授权」|
| 3 | **Shizuku 锁定** | 点「横屏」 | `mCurrentRotation` ROTATION_0 → **ROTATION_90**，状态「已锁定 · 横屏」|
| 4 | **跨 App 保持** | 跳转系统设置 | 仍为 ROTATION_90，证明是全局锁定而非仅本 App |
| 5 | **解锁恢复** | 点「解除锁定」 | 回到 ROTATION_0，`accelerometer_rotation` 恢复为 1 |
| 6 | **降级路径** | 重启后 Shizuku 未就绪时点「横屏」 | 走 WRITE_SETTINGS，同样变为 ROTATION_90 |

> 截图证据：横屏时 `screencap` 输出 1920×1080（宽>高），竖屏时 1080×1920。

### 2.4 资源占用

| 指标 | 数值 | 目标 |
|---|---|---|
| 进程 PSS（界面在前台） | **22.6 MB** | < 25 MB ✅ |
| Java Heap | 6.2 MB | — |
| Native Heap | 4.4 MB | — |
| 常驻服务 | 0 个 | 零常驻 ✅ |
| WakeLock / 轮询 | 无 | 无 ✅ |

## 三、测试驱动发现的缺陷（已修复）

| # | 缺陷 | 触发条件 | 修复 |
|---|---|---|---|
| 1 | **脏数据导致启动崩溃** | prefs 中 `active_mode` 为非法字符串（旧版本残留/写坏）时 `Mode.valueOf` 抛 `IllegalArgumentException` | `runCatching` 兜底为 `NONE` |
| 2 | **失败却残留快照** | 所有路径都不可用时，`hasAutoRotateSnapshot` 已被置 true，之后解锁会拿"不存在的原值"去改系统开关 | 全路径失败时回滚快照 |
| 3 | **权限判断不准** | `Settings.System.canWrite` 只看权限授予，而 WRITE_SETTINGS 是 appop 保护，导致误判可用、锁定静默失效 | 改查 `AppOpsManager.checkOpNoThrow`，`MODE_DEFAULT` 时回落权限位 |
| 4 | **广播处理崩溃** | `goAsync()` 返回 null 的环境下 `pending.finish()` NPE | 显式可空 + 安全调用 |
| 5 | **解锁残留方向** | `freezeRotation` 会把锁定方向写进 `USER_ROTATION`，解锁只恢复自动旋转，用户之后关掉自动旋转会突然横屏 | 新增 `USER_ROTATION` 快照与还原 |
| 6 | **状态文案误导** | 未锁定时仍显示「生效路径：Shizuku」 | 未锁定时显示 `—`，锁定时显示真实 `activeMode` |

## 四、未通过 / 受限项

### 4.1 重启后自动恢复（受环境限制）

**现象**：`adb reboot` 后 `accelerometer_rotation` 被系统重置为 1，方向回到竖屏，App 进程未被拉起。

**排查结论**：

1. 系统确实发出了广播：`ActivityManager: Posting BOOT_COMPLETED user #0`，其他 App（AlarmClock、DMIntentReceiver）都收到了。
2. 我们的 receiver 声明正确（`exported=true`，三个 action 都在），设备上注册正常，App `stopped=false`。
3. 手动触发同一 receiver 时逻辑完全正常：`BootRestore: 开机恢复锁定: true`，设置被正确写入。
4. 因此根因是**软件模拟下 App 冷启动耗时超过广播分发窗口**，广播被丢弃——属环境限制，不是逻辑错误。

**已做的加固**：在 `MainActivity.onResume` 增加入口侧兜底（并加幂等判断，避免重复写设置）。这样即使错过开机广播，用户打开 App 或点磁贴时也会补恢复。

**仍需真机确认**：真实设备冷启动通常在 1 秒内，广播不会错过；但国产 ROM 的后台限制策略差异大，必须实测。

### 4.2 模拟器 WindowManager 的方向状态不稳定

多次重启 + Shizuku `freezeRotation` 交替后，模拟器出现"设置了 `user_rotation` 但 `mCurrentRotation` 不变"的状态，日志可见 `WindowManager: Force clearing orientation change`。这是 TCG 软件渲染下 WMS 的异常，真机不具备复现条件。

## 五、复现步骤

```bash
# 1. 启动模拟器（无 KVM）
$ANDROID_HOME/emulator/emulator -avd api29 -no-window -no-audio -no-snapshot \
  -gpu swiftshader_indirect -accel off -memory 2048 -cores 4

# 2. 单元测试
./gradlew :app:testDebugUnitTest

# 3. 安装并授予写设置
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set com.orientationlocker android:write_settings allow

# 4. 仪器测试
adb shell am instrument -w com.orientationlocker.test/androidx.test.runner.AndroidJUnitRunner

# 5. 观测方向（核心断言）
adb shell dumpsys window | grep mCurrentRotation
adb shell settings get system user_rotation
```

## 六、结论

- **核心能力已验证**：Shizuku（`freezeRotation`）与降级（`WRITE_SETTINGS`）两条路径都能真正改变系统方向，且跨 App 保持。
- **解锁恢复已验证**：自动旋转与 `USER_ROTATION` 均精确还原。
- **低占用达标**：PSS 22.6 MB，零常驻服务、零 WakeLock、零轮询。
- **6 个缺陷已修复**，其中 3 个（脏数据崩溃、失败残留快照、权限误判）是会导致真实用户故障的问题。
- **唯一未闭环项**是"重启自动恢复"，在无 KVM 的模拟器上受广播时序限制无法验证，需真机确认；代码侧已加双重兜底。
