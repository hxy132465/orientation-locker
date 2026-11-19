# 构建与使用说明

## 环境要求

| 项 | 版本 |
|----|------|
| JDK | 17 或 21（AGP 8.6 要求 17+） |
| Android SDK | platform 34、build-tools 34.0.0、platform-tools |
| Gradle | 由仓库内 `gradlew` 包装器自动锁定为 8.9，无需单独安装 |

## 构建

```bash
# 1. 指定 SDK 路径（或用 Android Studio 打开工程自动生成 local.properties）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 构建 Debug APK
./gradlew :app:assembleDebug

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装与激活（Android 10）

1. 安装 APK 到设备。
2. 安装并激活 **Shizuku**（Android 10 需通过电脑 ADB 或 root 激活，无线调试激活是 Android 11+ 特性）。
3. 打开本 App，点击「申请 Shizuku 授权」并允许。
4. 下拉通知栏 → 编辑快捷设置 → 添加「方向锁定」磁贴。
5. 点击磁贴 → 选择方向即可全局锁定；选「自动旋转」解锁。

## 无 Shizuku 时的降级

点击「授予修改系统设置权限」，到系统设置页允许后，可通过 `WRITE_SETTINGS` 锁定系统默认方向。
该路径**无法覆盖**强行指定自身方向的视频/游戏类 App。

## 验证

```bash
# 查看当前旋转方向
adb shell dumpsys window | grep -i rotation
# 或
adb shell wm size && adb shell settings get system user_rotation
```

## 注意事项

- 解锁会恢复你锁定前的自动旋转开关状态；卸载前请先解锁，避免方向设置残留。
- 部分国产 ROM 可能重置 `freezeRotation`，开机时由 `BootRestoreReceiver` 兜底重放。
