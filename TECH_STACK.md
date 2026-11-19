# 屏幕方向锁定 App — 技术栈确认清单

> 对应 PLAN.md v2 ｜ 目标：Android 10 (API 29) ｜ Shizuku 主方案 + WRITE_SETTINGS 降级

## 一、语言与构建

| 项 | 选型 | 版本 | 理由 |
|----|------|------|------|
| 语言 | Kotlin | 2.0.x（稳定版锁定具体小版本） | 空安全、与 AndroidX/协程一等支持 |
| 构建系统 | Gradle + Kotlin DSL (`build.gradle.kts`) | Gradle 8.9（AGP 8.6 配套） | 与 AGP 8.6 兼容矩阵匹配；本地有 Gradle 9.3，但项目用包装器锁定 8.9，避免版本漂移 |
| Android Gradle Plugin | AGP | 8.6.x | 支持 JDK 21（本地 JDK 21.0.13.1）、targetSdk 34、稳定版 |
| JDK | OpenJDK | 21（本地已装） | AGP 8.6+ 支持；`JAVA_HOME` 已指向 21 |
| Gradle 包装器 | `gradlew` | 锁定 gradle-8.9-bin | 保证任何人构建版本一致 |

## 二、SDK 版本

| 项 | 值 | 理由 |
|----|----|------|
| minSdk | 24 (Android 7.0) | Quick Settings Tile 最低要求；Shizuku 要求 Android 6.0+ |
| targetSdk | 34 (Android 14) | 当前主流上架要求；避免-target过低限制 |
| compileSdk | 34 | 与 targetSdk 一致 |
| 测试目标真机 | Android 10 (API 29) | 用户明确要求 |

## 三、核心依赖（锁定具体版本，不用 `+` / `latest`）

| 依赖 | 坐标 | 用途 |
|------|------|------|
| Shizuku API | `dev.rikka.shizuku:api:13.1.5` | 拿 binder、权限、远程调用（Maven Central 最新稳定版，已核实） |
| Shizuku Provider | `dev.rikka.shizuku:provider:13.1.5` | 清单注册、权限声明 |
| Shizuku App（用户侧）| v13.6.0 | 官方 GitHub 最新稳定版（2025-05），引导用户安装此版 |
| AndroidX Core KTX | `androidx.core:core-ktx:1.13.x` | 基础扩展 |
| AndroidX AppCompat | `androidx.appcompat:appcompat:1.7.x` | 兼容主题/Activity（可选，可最小化） |
| 协程 | `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.x` | 异步 binder 调用（可选，视实现） |

> Shizuku 版本说明（已核实）：API/Provider 库锁定 Maven Central 最新稳定版 13.1.5；用户侧 Shizuku App 引导安装官方 GitHub 最新稳定版 v13.6.0。两者版本体系不同，属正常。

## 四、隐藏 API 调用方式

| 项 | 选型 | 理由 |
|----|------|------|
| 调用 `freezeRotation` | 反射 `IWindowManager$Stub.asInterface(Shizuku.getBinder("window"))` | 隐藏 API 无法直接编译引用；反射避免引入 hidden-api 编译依赖，保持低耦合 |
| 备选 | Shizuku 远程 binder call 直接调系统服务 | 如反射在 android 10 上受限，改用 Shizuku binder 远程调用 |
| 不采用 | 隐藏 API stub (android-hidden-api) | 体积大、维护成本高，不符合低占用 |

## 五、架构与组件

| 层 | 组件 |
|----|------|
| 入口 | `OrientationTileService`（磁贴）、`BootRestoreReceiver`（开机兜底）、`FloatBallService`（悬浮球前台服务，可选） |
| 领域 | `RotationController`（freezeRotation/thawRotation）、`LockStateRepository`（SharedPreferences）、`FallbackController`（WRITE_SETTINGS 降级） |
| 权限 | `ShizukuHelper`（激活/权限检查）、`OverlayHelper`（悬浮窗）、`SettingsWriteHelper`（降级引导） |
| 数据 | SharedPreferences（无数据库，零 ORM 依赖） |

## 六、测试栈

| 项 | 选型 |
|----|------|
| 单元测试 | JUnit 4 |
| AndroidX 测试 | `androidx.test:core` + `androidx.test.ext:junit`（可选） |
| 真机验证 | adb 命令（`dumpsys window` / `settings get`）+ 手动用例 |
| 不采用 | 重型 UI 自动化框架（超出本项目规模） |

## 七、当前环境缺口（开发前需补）

| 缺口 | 处理 |
|------|------|
| Android SDK 未设置 (`ANDROID_HOME` 空) | 用 Android Studio SDK Manager 或 `sdkmanager` 安装 platform 34 / build-tools 34，设置 `ANDROID_HOME` |
| adb 未安装 | 随 Android SDK platform-tools 安装 |
| Kotlin 编译器未单独装 | 由 Gradle 插件管理，无需单独安装 |
| 本地无 Shizuku 运行环境 | 真机测试时用户侧安装 Shizuku App 并激活 |

## 八、确认结论

技术栈已收敛：**Kotlin + AGP 8.6 + Gradle 8.9 + JDK 21 + Shizuku API 13.1.5 + 反射调用 IWindowManager + SharedPreferences，无数据库、无网络、无重型依赖**。

与低占用目标一致：运行时依赖仅 Shizuku API/provider + 最小 AndroidX，无 ORM、无网络库、无统计 SDK。
