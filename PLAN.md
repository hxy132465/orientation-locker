# 屏幕方向锁定 App — 项目规划（v3 · 决策冻结版）

> 目标平台：Android 10 (API 29) ｜ 作用范围：全局 ｜ 核心诉求：低占用 ｜ 允许后台常驻 ｜ 允许 Shizuku

## 〇、最终确认决策（v3 冻结）

| 决策点 | 结论 |
|--------|------|
| 方向交互 | 磁贴点击弹出方向选择菜单（竖屏/横屏/反向竖屏/反向横屏/自动） |
| 悬浮球 Wave 4 | **暂不做**，先交付核心版（Wave 1-3 + 5），悬浮球留后续迭代 |
| 分发方式 | 不上架，自用/内测；仅签名 APK，targetSdk 34，无需隐私政策 |
| 解锁恢复 | 记住并恢复开启锁定前的自动旋转开关状态（精确还原用户原设置） |
| 包名 | `com.orientationlocker` |
| 方向选项集 | 竖屏、横屏、反向竖屏、反向横屏、自动（解锁） |
| Shizuku 版本 | API/Provider 13.1.5；用户侧 App 引导 v13.6.0（均已核实）|

> 注：因悬浮球暂不做，Wave 4 从首版范围移除；S3 中"前台服务 PSS < 25MB"指标随之不适用，低占用验证聚焦"零常驻服务、无轮询、无 WakeLock"。

## 一、需求锁定卡（v3）

| 槽位 | 内容 |
|------|------|
| S1 目标 | 全局锁定整台设备屏幕方向，低占用，Android 10 可运行；用 Shizuku 免 root 获得系统级控制 |
| S2 边界 | **做**：全局锁定竖/横/反向、解锁、磁贴弹菜单、开机保持、无 Shizuku 时降级；**不做**：按 App 记忆方向、悬浮球（首版）、iOS、强制修改 Shizuku 系统本身、上架商店 |
| S3 验收 | ① `dumpsys window` 显示 rotation 与选定方向一致；② 跨 App 保持、能覆盖强制定向的 App；③ 解锁 `thawRotation` 后恢复用户原自动旋转状态；④ 重启后方向保持；⑤ 零常驻服务、无轮询、无 WakeLock |
| S4 约束 | minSdk 24（Tile 下限）、targetSdk 34、Kotlin + AndroidX 最小依赖 + Shizuku API 13.1.5、无网络请求、无第三方统计、包名 com.orientationlocker |
| S5 数据与状态 | SharedPreferences：锁定方向枚举、是否曾锁定、原自动旋转值、上次生效时间；系统状态由 `IWindowManager.freezeRotation` 持有（不写 Settings） |
| S6 风险与回滚 | Shizuku 未激活/被杀需降级；Android 10 激活 Shizuku 需 ADB 或 root（无无线调试）；解锁即 `thawRotation` + 精确恢复原设置；卸载前提示恢复 |

## 二、技术选型：为何改用 Shizuku

方向控制四层，本项目主选**系统服务层（Shizuku + freezeRotation）**：

| 层 | 手段 | 权限 | 覆盖顽固 App | 持久 | 评价 |
|----|------|------|------------|------|------|
| 应用层 | `setRequestedOrientation()` | 无 | ❌ | 否 | 只锁自己 |
| 系统设置层 | `WRITE_SETTINGS` + `USER_ROTATION` | 特殊权限手动授权 | ❌ | 是 | 旧版主方案，体验差 |
| 悬浮窗覆盖层 | `TYPE_APPLICATION_OVERLAY` | 悬浮窗权限 | 部分 | 否（进程死即失效） | Hack，易残留 |
| **系统服务层** | **Shizuku → `IWindowManager.freezeRotation()`** | **ADB/root shell** | **✅** | **跨重启** | ✅ 主方案 |

关键结论：
- `freezeRotation(rotation)` / `thawRotation()` 是 WindowManagerPolicy 的**原生旋转锁**（系统"旋转锁定"用的就是它），需 `SET_ORIENTATION` 权限——普通 App 拿不到，但 **Shizuku 的 shell 身份可以**。
- 比系统设置层更强：能覆盖视频/游戏等**强行指定自身方向**的 App（这是上一版规划的明确短板，Shizuku 版补上）。
- 源码注释标明 **persists across reboots**，跨重启持久；但部分 ROM 实测有差异，仍需 BootReceiver 兜底校验（见风险）。
- 免 root：Shizuku 一次激活，通过 binder 调用，无后台轮询。

**架构权衡留痕**（高影响决策）：

| 候选 | 选择理由 / 反选理由 |
|------|---------------------|
| Shizuku + freezeRotation（**选**） | 选：唯一能全局强制且覆盖顽固 App、免 root、系统级持久、无常驻即可生效。反选代价：用户需一次性激活 Shizuku |
| WRITE_SETTINGS（**降为 fallback**） | 留作无 Shizuku 时的降级路径；反选主因：手动授权体验差、无法覆盖顽固 App |
| 悬浮窗覆盖（**弃**） | 反选：进程死即失效、易残留遮挡、MIUI 等 ROM 兼容差 |

**撤销条件**：若目标用户绝大多数无法激活 Shizuku（Android 10 需 ADB/root），则主方案回退到 WRITE_SETTINGS + 系统设置层，保留 Shizuku 为增强。

## 三、低占用设计（允许常驻后的取舍）

你已允许后台常驻，但仍要求低占用。核心洞察：**锁定的持久性由系统层保证，常驻服务只服务于悬浮球交互，不服务于锁定本身**——服务被系统杀掉，锁定不失效。

- **锁定路径（事件驱动、零依赖常驻）**：磁贴/悬浮球点击 → Shizuku binder 调用 `freezeRotation` → 结束。无轮询、无 WakeLock、无 Alarm 周期任务。
- **常驻路径（仅悬浮球开启时）**：一个前台服务维持悬浮球，目标 PSS < 25MB、无定时任务；用户可随时关闭 SvchOST 式保活。
- Shizuku 自身进程由 Shizuku App 承载，不计入本 App 内存。

## 四、技术架构

```
入口层   OrientationTileService(磁贴)  BootRestoreReceiver(开机兜底)  FloatBallService(悬浮球前台服务,可选)
   ↓
领域层   RotationController ★  Shizuku 进程内反射调用 IWindowManager.freezeRotation/thawRotation
         LockStateRepository(SharedPreferences)  FallbackController(WRITE_SETTINGS 降级)
   ↓
权限层   ShizukuHelper(激活引导/版本/权限检查)  OverlayHelper(悬浮窗引导,可选)  SettingsWriteHelper(降级引导)
   ↓
数据层   SharedPreferences
```

Shizuku 调用核心（在 Shizuku 进程中执行，反射拿隐藏 API）：
```
Shizuku.getBinder("window")
  → IWindowManager$Stub.asInterface(binder)   [反射]
  → freezeRotation(Surface.ROTATION_90/0) 或 thawRotation()
```

行为契约：

| 场景 | 输入 | 输出 | 异常 | 验收 |
|------|------|------|------|------|
| 磁贴锁定 | Shizuku 就绪 + 方向 | `freezeRotation` 生效 | 未激活→跳 Shizuku 引导 | `dumpsys window` rotation 正确、跨 App 保持 |
| 磁贴解锁 | 点击解锁 | `thawRotation` + 恢复原自动旋转 | Shizuku 断开→标记待同步 | 自动旋转恢复 |
| 无 Shizuku | 检测到未激活 | 降级到 WRITE_SETTINGS 路径 | 降级也失败→明确提示 | 不崩溃、用户可知 |
| 开机 | BOOT_COMPLETED | Shizuku 就绪时校验并重写锁定 | 未就绪→延迟到可用时重试 | 重启后方向保持 |
| 权限撤销 | Shizuku 权限被撤 | 标记未锁定、停止写入 | — | 不崩溃、提示用户 |

## 五、Wave 执行计划

| Wave | 内容 | 验证 |
|------|------|------|
| 1 骨架 | Gradle 工程、Shizuku 依赖、权限声明、MainActivity 激活引导、ShizukuHelper | 编译安装成功、Shizuku 激活状态可检测 |
| 2 核心锁定 ★ | RotationController（反射 IWindowManager）、LockStateRepository、OrientationTileService（点击弹方向菜单）| 磁贴弹菜单选方向、`dumpsys window` rotation 正确、能覆盖视频 App |
| 3 降级+兜底 | FallbackController（WRITE_SETTINGS）、BootRestoreReceiver、解锁精确恢复原状态 | 拔掉 Shizuku 后降级可用、重启方向保持、权限撤销不崩 |
| 4 测试打包 | 单元测试、Android 10 真机 + 国产 ROM 交叉验证、README、签名 APK | 测试通过、产物可安装、限制清单齐全 |

> 悬浮球 + 常驻通知（原 Wave 4）移出首版，后续迭代再做。首版零常驻服务。

## 六、验证方案（证据）

- 功能：`adb shell dumpsys window | grep -i rotation` 前后对比；`wm` 命令交叉验证。
- 顽固 App：锁定后打开强行横屏的视频/游戏，确认方向仍锁。
- 低占用：`adb shell dumpsys meminfo <pkg>` PSS；`dumpsys activity services` 确认无周期任务；`dumpsys power` 确认无本 App WakeLock。
- 兼容：Android 10 原生 + ≥1 国产 ROM 真机（重点测 Shizuku 激活、freezeRotation 是否被引擎重置）。
- 回归：锁定→解锁→再锁定；重启；Shizuku 断开/重连；权限撤销；卸载。

## 七、已知限制与风险

1. **Android 10 激活 Shizuku 需 ADB 电脑授权或 root**（无线调试激活是 Android 11+ 特性）——这是引入 Shizuku 的主要体验成本，需在引导页讲清。
2. 部分 ROM 可能重置 `freezeRotation` 状态，BootReceiver + 状态校验兜底；真机验证必须做。
3. Shizuku 依赖其 App 前台服务保活；被杀后本 App 降级到 WRITE_SETTINGS 路径。
4. 系统设置持久——卸载/解锁前务必 `thawRotation` 并恢复用户原值，避免长期影响设备。
5. 悬浮球前台服务开启后占用上升，UI 明示并保持 <25MB 目标。

## 八、交付材料

- 使用说明（Shizuku 激活路径、磁贴用法、悬浮球开关、解锁恢复）
- 构建说明（Gradle、签名、minSdk 24、Shizuku 版本锁定）
- 已知限制清单（ROM 兼容、激活成本、覆盖边界）
- 签名 APK（Wave 5 产出）
