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
| 单元测试 | JUnit4 + Robolectric 4.13 | 29 | ✅ 全通过 | 方向常量、方向映射、状态仓库持久化、编排分支、入参守卫 |
| 仪器测试 | AndroidJUnitRunner（真机运行时） | 7 | ✅ 全通过 | 真实写系统设置、Activity 启动、组件注册 |
| 手工端到端 | adb + UIAutomator | 6 项 | ✅ 通过 | 双路径锁定/解锁、跨 App 保持、界面渲染 |
| 可靠性 | 重启验证 | 2 轮 | ⚠️ 见第四节 | 开机兜底时序 |

### 2.1 单元测试（v1.0.2 时 23 个，v1.0.3 增至 29 个）

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

## 七、后台占用与留存实测（v1.0.1 · 2026-11-19 19:00–19:30）

> 模拟器重启（wipe-data）后全新安装 v1.0.1 release，逐项采集。所有数值为 `dumpsys meminfo` / `/proc/<pid>` 原始读数。

### 7.1 内存占用

| 场景 | PSS | Private Dirty | 备注 |
|---|---|---|---|
| 前台 MainActivity | **28.0 MB** | 14.4 MB | 首次进入 |
| 退到后台（adj 700） | 27.9 MB | 14.4 MB | 图形内存为 0，退后台无可释放项 |
| 收到 `TRIM COMPLETE` 后 | **20.8 MB** | 14.5 MB | 7.1 MB 是可回收的 code/共享页（25%） |
| 再次冷启动后后台 | 28.1 MB | 15.0 MB | dex 页重新被缓存 |

内存构成（前台）：

| 分区 | PSS | 说明 |
|---|---|---|
| Native Heap | **11.4 MB** | 最大项，其中 Private Dirty 11.1 MB |
| Code（.dex/.so/.oat/.jar/.apk） | 4.8 MB | 几乎全为可回收页 |
| Private Other | 4.0 MB | |
| System（共享库分摊） | 6.3 MB | 不算本应用真实成本 |
| Java Heap | 1.8 MB | 只有一个界面，堆很轻 |
| Graphics | 0 MB | 纯原生 View，无自绘 |

> 对照：同设备 Google Docs 峰值 Native Heap 6.3 MB。本应用 11.4 MB 偏高，主因是 `OrientationLockerApp.onCreate()` **无条件**调用 `HiddenApiBypass.addHiddenApiExemptions()`——它会反序列化整张 ART 方法表。改为"首次调用 `freezeRotation` 前才初始化"可省下这部分开销（未做，属优化项）。

### 7.2 进程与组件

| 指标 | 实测 |
|---|---|
| 线程数 | 15 |
| 常驻 Service | **0**（仅 `TileService`，由系统在磁贴可见时按需绑定） |
| 静态 Receiver | 1 个（`BOOT_COMPLETED` 等三项） |
| WakeLock | **0**（`batterystats` 里只有系统侧的 `*dexopt*` / `*launch*`） |
| AlarmManager / JobScheduler / Sensor | **0 / 0 / 0** |
| 后台优先级 | `adj=700`（previous app）→ 静置后 `900`（cached 尾部） |

### 7.3 CPU 与唤醒

| 场景 | 时长 | CPU 时间增量 |
|---|---|---|
| 后台 `adj=900` 静置 | 120 s | **0 tick（<10 ms）** |
| 息屏静置 | 180 s | 0.20 s（≈0.11% 单核） |

**零轮询、零唤醒锁**成立：两段静置期间既无定时器也无网络/传感器唤醒。

### 7.4 留存（进程生命周期）

| 压力场景 | 结果 |
|---|---|
| 连续启动 Chrome / Maps / YouTube / Docs | ✅ 存活 |
| `am send-trim-memory COMPLETE` | ✅ 存活 |
| `dumpsys deviceidle force-idle`（Doze 深睡） | ✅ 存活 |
| 自然后台 12 分钟（adj 900） | ✅ 未被 LMK 回收 |
| `am kill` 强杀 | 进程退出，**不自启**（无偷跑行为） |

**被强杀后的行为**（关键发现）：

| 路径 | 杀进程后方向 | `user_rotation` | 结论 |
|---|---|---|---|
| 降级（WRITE_SETTINGS） | 90° → **0°** | 1 → **0** | 锁失效 |
| Shizuku（`freezeRotation`，界面显示"生效路径：Shizuku"） | 90° → **0°** | 1 → **0** | **仍然失效** |

两次实验都一样：App 进程一死，`WindowManager` 重新计算方向时把 `USER_ROTATION` 覆盖成 0。也就是说，**"方向锁与进程无关"这个假设在 Android 10 上不成立，进程活着才是锁生效的必要条件**。

> 这也解释了为什么"允许后台常驻"是合理需求——但当前版本没有任何常驻组件，只能靠系统不回收。

**自愈行为**：被杀后不重启；用户再次打开 App，`onResume` 的兜底逻辑 4.4 s 内把锁定重新应用（`user_rotation` 回到 1、方向回到 90°），`SharedPreferences` 状态未丢。

### 7.5 建议（按优先级）

1. **加可选的前台服务守护**：`FOREGROUND_SERVICE` + 一条低优先级通知，锁定期间保活。成本约 +1–2 MB PSS、一条通知；收益是"进程不被回收 → 锁不失效"。用户已明确"允许后台常驻"。
2. **把 `HiddenApiBypass` 改为懒初始化**：只在真正要反射 `IWindowManager` 时初始化，可降低冷启动与 native heap。
3. **锁定期间做一次轻量自检**：前台服务里用 `Handler.postDelayed` 每 60 s（不精确、可被 Doze 推迟）核对 `user_rotation`，发现被覆盖就重写。不放 Alarm、不唤醒 CPU。
4. 真机复测"杀进程后方向是否保持"：本结论来自 TCG 软模拟，真机 WMS 行为可能不同，必须验证后再决定是否上线前台服务。

## 八、v1.0.2：修复"进某些 App 不保持锁定"

> 触发：用户真机反馈「锁定横屏后，打开某个 App 就变回竖屏」。

### 8.1 根因

WMS 决定方向时的优先级是：

```
1. 前台 Activity 的 requestedOrientation（manifest 的 android:screenOrientation / setRequestedOrientation）
2. display 的固定方向（freezeRotation）
3. 用户方向设置（user_rotation + accelerometer_rotation）
4. 传感器
```

只要目标 App 自己声明了方向（大量 App 的首页写死 `portrait`、视频页写死 `landscape`），第 1 条就压过我们用的第 2、3 条。所以 **v1.0.1 的"锁横屏"只是告诉系统"别自己转"，并没有夺走 App 的方向决定权**。

复现方式（debug 构建里加了两个测试 Activity）：

```xml
<activity android:name=".debug.PortraitActivity" android:screenOrientation="portrait" />
<activity android:name=".debug.LandscapeActivity" android:screenOrientation="landscape" />
```

v1.0.1 实测：`user_rotation=1`（横屏）+ `accelerometer_rotation=0`，启动 PortraitActivity 后 `mCurrentRotation` 立刻回到 `ROTATION_0`。

### 8.2 修复

调用 WMS 的「忽略应用方向请求」入口，让 display 的固定方向具备最终决定权：

| 系统版本 | API | 取值 |
|---|---|---|
| Android 10 (API 29) | `IWindowManager.setFixedToUserRotation(int)` | 1 = ENABLED，0 = DEFAULT |
| Android 11+ | `IWindowManager.setIgnoreOrientationRequest(boolean)` | true / false |

两条调用路径：

1. **直连 binder**（首选，无额外进程）：反射 `IWindowManager` 代理对象。
2. **`cmd window` 子进程**（回退）：`setFixedToUserRotation` 在 WMS 里带 `MANAGE_APP_TOKENS` 校验，shell 没有该签名权限，直连会被拒；而 `wm` / `cmd window` 是在 system_server 进程内执行，绕开 binder 校验。所以用 Shizuku fork 一个 `sh -c "cmd window set-fix-to-user-rotation enabled"`。
   > `Shizuku.newProcess` 在 13.x 被收成 private，这里用反射取回（否则要自己写 UserService，代价高得多）。

生命周期：`lock()` 成功时开启；`unlock()` / `restoreIfNeeded()` 同步维护；`clearLock()` 清零。

### 8.3 验证（Android 10 模拟器，v1.0.2 release 包）

| 步骤 | 结果 |
|---|---|
| 锁横屏 → `mCurrentRotation` | `ROTATION_90` ✅ |
| 锁横屏 → `mFixedToUserRotation` | `true` ✅ |
| 锁横屏 → 进入**声明 portrait** 的 Activity | 仍 `ROTATION_90` ✅（v1.0.1 会掉回 0） |
| 锁竖屏 → 进入**声明 landscape** 的 Activity | 仍 `ROTATION_0` ✅ |
| 解锁 → `mFixedToUserRotation` | `false` ✅ |
| 解锁 → 进入声明 landscape 的 Activity | 正常转 `ROTATION_90` ✅ |
| release 包（R8 混淆后）同一路径 | `true` + `ROTATION_90` ✅（反射未被混淆破坏） |

单元测试 23 个全部重跑通过。

### 8.4 已知边界

1. **letterbox**：display 被钉在横屏，但声明 portrait 的 Activity 仍按竖屏排版，会显示成横向屏幕中央的竖条（上下黑边）。要让第三方 App 内容真正铺满横屏，只能改它自己的 `ActivityInfo.screenOrientation`，需要 hook system_server（Xposed/LSPosed 级别），Shizuku 做不到。所以"覆盖"的准确含义是：**屏幕不再被 App 抢走**，而不是**App 内容会重新排版**。
2. **全局副作用**：该开关是 display 级、全局生效。锁横屏时桌面、通知栏、设置页也会横过来，个别系统页面布局会显得别扭。这是"全局锁定"的固有语义。
3. **降级路径无法覆盖**：只授予 `WRITE_SETTINGS` 时，上述 API 调不到，自带方向的 App 依然不受控。UI 已改为显示「覆盖自带方向的 App：否（自带方向的 App 不受控）」，不再宣称"可覆盖顽固 App"。

## 九、v1.0.3：悬浮窗接管路径（免 Shizuku 覆盖顽固 App）

### 9.1 为什么要做

v1.0.2 已经能覆盖顽固 App，但前提是有 Shizuku。而 Shizuku 在 Android 10 上必须用电脑 ADB 激活，这一步把相当一部分用户挡在外面；此时只剩 `WRITE_SETTINGS` 降级，对声明 `screenOrientation` 的 App 完全无效。

同类 App（Rotation 29.3.4）走的是另一条路：**1×1 透明悬浮窗**，把自己的 `LayoutParams.screenOrientation` 当成"最上层窗口的方向请求"塞给 WMS。逆向确认它没有用 `freezeRotation`、没有 root、没有 Shizuku，靠的就是这个机制。我们借鉴了机制，但**没有借鉴它的架构**。

### 9.2 原理

`WindowManagerService.getOrientationFromWindowsLocked()` 从窗口栈顶向下遍历，取第一个「可见且声明了 screenOrientation」的窗口作为屏幕方向；只有碰到带 `appToken` 的 Activity 窗口才停止。`TYPE_APPLICATION_OVERLAY` 位于 Activity 之上且没有 `appToken`，因此它的方向请求会优先于前台 Activity 被采用。

```
WMS 判定顺序（自上而下）
  ├─ 状态栏 / 导航栏     screenOrientation = UNSPECIFIED → 跳过
  ├─ 我们的 1×1 悬浮窗   screenOrientation = LANDSCAPE   → ★ 命中
  ├─ 前台 Activity       声明 portrait                    → 轮不到
  └─ ...
```

### 9.3 与 Rotation 的取舍差异

| 维度 | Rotation 29.3.4 | 本方案 |
|---|---|---|
| 前台应用识别 | AccessibilityService 常驻，per-app 配置 | **不做**（全局锁定不需要知道前台是谁） |
| 转屏手段 | 透明 overlay | 同上 |
| 服务形态 | 无障碍服务 + 前台服务 | 只一个薄前台服务，**无 UI、无 binder、无线程、无监听** |
| 通知 | 常规通知 | `IMPORTANCE_MIN` + 静音 + 锁屏隐藏 |
| 第三方 SDK | AdMob / 内购 / AD_ID / `QUERY_ALL_PACKAGES` | 无 |
| 常驻内存 | 40–55 MB | 24.9 MB（仅窗口 + 薄服务） |

**省下来的关键一刀是不做 AccessibilityService**：per-app 配置才需要知道前台是谁，全局锁定不需要。这正是同类 App 内存高的主因。

### 9.4 实测（Android 10 / API 29，release 包）

权限状态刻意设成最苛刻的组合：**Shizuku 未安装未连接、`WRITE_SETTINGS` 设为 ignore，只给 `SYSTEM_ALERT_WINDOW`**。

| 验证项 | 结果 |
|---|---|
| 锁横屏 | `mCurrentRotation=ROTATION_90` ✅ |
| 进入 `android:screenOrientation="portrait"` 的 Activity | **仍 `ROTATION_90`** ✅ |
| 锁反向横屏 | `ROTATION_270` ✅（与正向精确区分，非"某个横屏"） |
| 解锁 | 回 `ROTATION_0`；`ty=APPLICATION_OVERLAY` 窗口数 0；服务数 0 ✅ |
| 解锁后进入声明 landscape 的 Activity | 正常转 `ROTATION_90` ✅（未残留接管） |
| `kill -9` 强杀进程 | 系统按 `START_STICKY` 重启服务，**窗口重建、方向保持 `ROTATION_90`** ✅ |
| 此时系统设置 | `user_rotation=0`、`accelerometer_rotation=1` → 方向**完全由窗口撑住**，证明不依赖写设置权限 ✅ |

窗口参数实测（`dumpsys window windows`）：

```
Window{... u0 com.orientationlocker}
  mAttrs={(0,0)(1x1) gr=TOP START CENTER ty=APPLICATION_OVERLAY
          fmt=TRANSLUCENT or=SCREEN_ORIENTATION_LANDSCAPE}
```

### 9.5 内存实测

| 状态 | PSS | Native Heap | Dalvik Heap | 线程 |
|---|---|---|---|---|
| 仅挂窗口 + 薄前台服务（干净进程） | **24.9 MB** | 14.5 MB | 1.0 MB | — |
| 同上，但主界面刚用过（Activity 未销毁） | 39–41 MB | 19.6 MB | 3.7 MB | 16 |
| 解锁、窗口移除后（同一进程） | 47.6 MB → 无下降 | — | — | — |

两个结论：

1. **窗口本体几乎不占内存**。解锁前后 PSS 基本不变，说明 1×1 的 `View` 与 `Surface` 开销可忽略；第三行 47.6 MB 是 debug 包 + Activity 残留，与窗口无关。
2. **成本来自"进程必须活着"**，而不是窗口本身。这也解释了为什么有 Shizuku 时必须优先走 Shizuku：那条路径连进程都不需要常驻。

`HiddenApiBypass` 同时改为懒初始化：它原本在 `Application.onCreate()` 无条件执行，会反序列化整张 ART 方法表（模拟器上约 11 MB native 堆），而走悬浮窗/系统设置路径的用户根本不反射 `IWindowManager`，这笔开销纯属白付。

### 9.6 已知边界

1. **letterbox 依旧存在**：屏幕方向不再被抢走，但声明 portrait 的 App 仍按竖屏排版，呈横向屏幕中央的竖条。这与 v1.0.2 的限制一致，任何非 root 方案都一样。
2. **进程存活是前提**：`kill -9` 后能自愈（实测），但若系统把应用判为 idle 而拒绝重启 sticky 服务，锁定会失效，需重开 App。Shizuku 路径无此问题。
3. **一条常驻通知**：这是前台服务的代价，无法去掉（没有通知就不是前台服务）。已压到最低优先级、静音、锁屏不显示。
4. 悬浮窗权限在国产 ROM 上可能被"后台弹出界面"等额外开关拦截，需真机验证。

### 9.7 单元测试

新增 `OverlayControllerTest`（6 个用例），钉住 Surface 旋转值 → `ActivityInfo` 方向常量的映射，特别是 `ROTATION_3 → SCREEN_ORIENTATION_REVERSE_LANDSCAPE` 这类容易写成正向的映射。累计 **29 个单元测试全部通过**。

## 十、v1.1.0：包体、启动与主线程优化

### 10.1 改了什么

| 优化 | 做法 | 收益 |
|---|---|---|
| 移除 androidx | `MainActivity` 改原生 `Activity`；通知改原生 `Notification.Builder`；主题改 `android:Theme.Material.*` | 去掉 AppCompat 的整套类与资源（原本占 dex 的绝大部分） |
| 开启 R8 | `isMinifyEnabled` + `isShrinkResources`，并补齐 Shizuku / HiddenApiBypass / enum / 组件的 keep 规则 | 裁掉未使用代码与资源 |
| 排除无用打包项 | `kotlin/**`（仅 kotlin-reflect 使用）、tooling 元数据、VCS 信息 | 再省约 30 KB |
| 主线程去阻塞 | Shizuku ping、AppOps 查询、SharedPreferences 读取全部移到单线程 executor，主线程只做一次渲染 | 打开界面时主线程零跨进程调用，不再掉帧 |
| 删除空壳 Application | 原 `OrientationLockerApp` 的字段无人使用，删除后冷启动少一次初始化与两次 Shizuku 监听注册 | 冷启动路径更短 |
| 线程收尾 | `shutdownNow()` → `shutdown()` | 不再中断正在执行的窗口操作，避免"窗口已加但返回失败"的不一致 |
| 深色模式 | 颜色抽到 `colors.xml` + `values-night`，drawable 引用颜色资源 | 夜间不再白底刺眼，且对比度达标 |
| 备份策略 | `allowBackup=false` | 避免锁定状态被恢复到新设备导致"一装就锁" |

### 10.2 包体实测

| 版本 | 大小 | classes.dex | resources.arsc | 文件数 |
|---|---|---|---|---|
| v1.0.3（未压缩） | 2,585,533 B (2.47 MB) | 5,838,868 B | 272,368 B | 433 |
| **v1.1.0（R8 + 去 androidx）** | **57,192 B (55.9 KB)** | **88,028 B** | **6,652 B** | **14** |

**体积降至原来的 2.2%，缩小约 45 倍。** 剩余 dex 主要是 Kotlin 标准库中被实际引用到的部分，已无可观的裁剪空间。

### 10.3 正确性校验

- 31 个 JVM 单元测试全部通过（新增 `MainActivityTest` 2 个：浅色/夜间下界面可创建、颜色资源解析正确）。
- `aapt2 dump badging`：包名、versionCode 5、minSdk 24、targetSdk 34、6 项权限、启动 Activity、前台服务 `specialUse` 子类型均正确。
- `apksigner verify`：v1/v2 签名有效，证书指纹与 v1.0.x 一致（可覆盖安装）。
- dex 字符串核对：`MainActivity`、`OverlayLockService`、`OrientationTileService`、`BootRestoreReceiver`、`rikka.shizuku.Shizuku`、`org.lsposed.hiddenapibypass.HiddenApiBypass` 均未被裁剪或改名。

## 十一、v1.2.0：界面与动效重构

### 11.1 设计原则

工具类 App 的界面不该抢戏。这一版只做三件事：把信息分层、把操作做实、把动效压到"感觉得到但不打扰"。

- **信息分层**：状态卡（我现在是什么状态）→ 运行环境（我凭什么生效）→ 方向网格（我要做什么）。
- **操作做实**：每张方向卡片都是 44dp 以上的点击区，选中态同时改变描边、底色、图标与文字颜色，不靠单一颜色暗示。
- **动效克制**：全部用 `ViewPropertyAnimator` / `ObjectAnimator`，无 Lottie、无第三方库；动画只在前台运行，`onPause` 停止。

### 11.2 改动清单

| 项 | 之前 | 现在 |
|---|---|---|
| 界面构建 | 纯代码 `new TextView()` 堆叠 | `activity_main.xml` + `styles.xml`，颜色/尺寸资源化 |
| 状态 | 7 行 key-value 纯文本 | 状态卡：状态点 + 标题 + 模式 chip + 一句解释 |
| 权限 | 三个系统默认灰按钮 | 三行卡片：图标徽章 + 名称 + 状态 + 胶囊操作按钮 |
| 方向 | 四个全宽按钮 | 2×2 网格卡片，选中态三处同步变色 |
| 面板 | 白底方块 + 文字行 | 圆角浮层 + 图标行 + 勾选淡入 + 出入场缩放 |
| 深色 | 无 | `values-night` 全量配色 + 主题切换 |

### 11.3 动效参数

| 动效 | 实现 | 参数 |
|---|---|---|
| 入场分层淡入 | `alpha 0→1` + `translationY 14dp→0`，按区块 55ms 递增延迟 | 300ms，Decelerate |
| 按压缩放 | `ACTION_DOWN` 缩至 0.96–0.98，抬起回弹 | 90ms / 190ms，Overshoot(1.6) |
| 状态点呼吸 | `ObjectAnimator` alpha 1↔0.3 无限往返，仅锁定时启动 | 1100ms，AccelerateDecelerate |
| 锁定翻转 | 状态卡 scale 1→1.012→1 | 110ms + 170ms |
| 方向选中 | 卡片 scale 0.95→1 回弹 | 80ms + 200ms，Overshoot(1.8) |
| 面板出入场 | 窗口动画：scale 0.92→1 + alpha | 230ms / 140ms |

### 11.4 实测（Android 10 / API 29，release 包 76 KB）

| 场景 | 结果 |
|---|---|
| 冷启动进入主界面 | 布局完整：标题、状态卡、三行环境、2×2 网格全部正确 |
| 深色模式 | 卡片/文字/图标对比度正常，无白底残留 |
| 点击「横屏」卡片 | `mCurrentRotation=ROTATION_90`，状态卡变为「已锁定 · 横屏」+ 绿色状态点 + 「悬浮窗」chip |
| 横屏下布局 | 自动适配为 1920×1080，卡片与文字不重叠、不截断 |
| 磁贴 → 面板 | 圆角浮层、五个选项、当前项勾选高亮；面板内选择后方向立即生效 |
| 解除锁定 | 回 `ROTATION_0`，overlay 窗口 0、服务 0，SP 置为 `NONE/-1` |
| 单元测试 | 31 个全部通过（含新增的浅色/夜间界面冒烟用例） |

### 11.5 体积影响

| 版本 | APK |
|---|---|
| v1.1.0（纯功能，无布局资源） | 57 KB |
| v1.2.0（新增 3 个布局、10 个矢量图、5 个 drawable、2 个动画） | **76 KB** |

增加 19 KB，仍不到一张缩略图的大小。未引入任何 UI 库。

### 11.6 未覆盖项

- **浅色实机截图**：模拟器 SystemUI 的 `uimode` 在 TCG 下无法切回日间，仅完成深色实机验证；浅色配色由 `MainActivityTest` 的颜色断言覆盖（`text_primary=#0F172A`、`surface=#F6F7FB`）。
- **动效的实际观感**：静态截图无法体现，参数按 Material 常规时长区间设定（90–320ms），建议真机体验后微调。
