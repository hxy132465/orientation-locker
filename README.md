# Orientation Locker

> Android 10 全局屏幕方向锁定 App —— 低占用、免 root（基于 Shizuku）

一个在 Android 10 上可运行的**全局屏幕方向锁定**工具。通过 Shizuku 以 shell 身份调用系统原生 `IWindowManager.freezeRotation()`，锁定整台设备的屏幕方向，覆盖视频/游戏等强行定向的 App；无常驻服务、无轮询、无 WakeLock。

## 特性

- 全局锁定竖屏 / 横屏 / 反向竖屏 / 反向横屏
- 快捷设置磁贴点击弹出方向菜单，一键切换
- 覆盖强行指定自身方向的顽固 App
- 覆盖重启保持（系统级持久 + 开机兜底校验）
- 解锁时精确恢复用户锁定前的自动旋转开关状态
- 无 Shizuku 时自动降级到 `WRITE_SETTINGS` 路径
- 低占用：事件驱动、零常驻服务、无网络、无统计 SDK

## 技术栈

Kotlin · AGP 8.6 · Gradle 8.9 · JDK 21 · Shizuku API 13.1.5 · 反射调用隐藏 API · SharedPreferences

| 项 | 值 |
|----|----|
| minSdk | 24 (Android 7.0) |
| targetSdk / compileSdk | 34 |
| 测试目标真机 | Android 10 (API 29) |
| 包名 | `com.orientationlocker` |

## 文档

- [项目规划（v3 决策冻结版）](PLAN.md) —— 需求锁定卡、技术选型权衡、架构、Wave 计划、验证方案、风险
- [技术栈确认清单](TECH_STACK.md) —— 语言/构建/SDK/依赖版本锁定、隐藏 API 调用方式、组件架构、测试栈

## 已知限制

1. Android 10 激活 Shizuku 需 **ADB 电脑授权或 root**（无线调试激活是 Android 11+ 特性）。
2. 部分国产 ROM 可能重置 `freezeRotation` 状态，需真机验证 + 开机兜底。
3. 系统设置持久，卸载/解锁前务必 `thawRotation` 并恢复用户原值。

## 状态

**首版核心已完成并通过构建**（v1.0.0）：

| Wave | 内容 | 状态 |
|------|------|------|
| 1 | Gradle 工程骨架、Shizuku 依赖与授权引导 | ✅ |
| 2 | 核心锁定（反射 `freezeRotation`）+ 磁贴方向菜单 | ✅ |
| 3 | WRITE_SETTINGS 降级 + 开机兜底 + 解锁精确恢复 | ✅ |
| 4 | 构建产物：已签名 Release APK（2.5 MB） | ✅ |

源码结构见下方；构建方式见 [BUILD.md](BUILD.md)。

```
app/src/main/java/com/orientationlocker/
├── core/       Rotations / RotationController / FallbackController
│               LockStateRepository / LockManager
├── shizuku/    ShizukuHelper
├── tile/       OrientationTileService
├── boot/       BootRestoreReceiver
└── ui/         MainActivity / OrientationPickerActivity
```

> 悬浮球（原 Wave 4）按决策移出首版，留待后续迭代。
