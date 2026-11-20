package com.orientationlocker.core

import android.content.Context
import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 通过 Shizuku（shell 身份）调用系统原生旋转锁。
 *
 * 原理：`IWindowManager.freezeRotation(rotation)` / `thawRotation()` 是系统
 * 「旋转锁定」按钮使用的同一套 API，需要 `SET_ORIENTATION` 权限。普通 App
 * 拿不到，但 Shizuku 以 shell 身份转发调用即可。
 *
 * 实现要点：
 * - 用 [SystemServiceHelper] 拿 "window" 系统服务的 binder；
 * - 用 [ShizukuBinderWrapper] 包一层，使实际 IPC 在 Shizuku 进程（shell 身份）执行；
 * - `IWindowManager` 是隐藏类，通过反射 `Stub.asInterface` 得到代理对象；
 * - 不同 Android 版本 freezeRotation 签名不同，按方法名动态匹配。
 */
object RotationController {

    private const val WINDOW_SERVICE = Context.WINDOW_SERVICE

    sealed class Result {
        data object Success : Result()
        data class Failure(val reason: String, val cause: Throwable? = null) : Result()
    }

    /**
     * 锁定到指定方向。
     * @param rotation 取值见 [Rotations]（0/1/2/3，对应 Surface.ROTATION_*）
     */
    fun freezeRotation(rotation: Int): Result {
        if (!Rotations.isValid(rotation)) {
            return Result.Failure("非法方向值: $rotation")
        }
        return try {
            val wm = obtainWindowManager() ?: return Result.Failure("无法获取 WindowManager 服务")
            val method = wm.javaClass.methods
                .filter { it.name == "freezeRotation" }
                .minByOrNull { it.parameterCount }
                ?: return Result.Failure("当前系统未找到 freezeRotation 方法")

            // freezeRotation 的第一个 int 参数即 rotation，其余 int 参数补 0（displayId/reason）
            method.invoke(wm, *buildIntArgs(method.parameterTypes) { index ->
                if (index == 0) rotation else 0
            })
            Result.Success
        } catch (t: Throwable) {
            Result.Failure(describe(t), t)
        }
    }

    /**
     * 是否支持「忽略应用自身方向声明」。
     *
     * 只做 freezeRotation 时，前台 Activity 若在 manifest 里写死
     * `android:screenOrientation`（大量 App 的首页是 portrait、视频页是 landscape），
     * WMS 会优先满足 Activity 的请求，桌面级方向锁被静默忽略——表现就是
     * 「锁了横屏，进某个 App 又变回竖屏」。
     *
     * Android 10(Q) 起 WMS 提供了忽略入口：Q 上是 `setFixedToUserRotation(int)`，
     * R+ 改名 `setIgnoreOrientationRequest(boolean)`。两者都需要系统权限，
     * 只能经 Shizuku 调用。
     */
    fun supportsIgnoreAppOrientation(): Boolean = try {
        val wm = obtainWindowManager() ?: return false
        wm.javaClass.methods.any {
            it.name == "setFixedToUserRotation" || it.name == "setIgnoreOrientationRequest"
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * 开启/关闭「忽略应用自身方向声明」。
     *
     * @param enabled true 时系统只认 display 的固定方向，App 的 screenOrientation 失效
     */
    fun setIgnoreAppOrientation(enabled: Boolean): Result {
        // 首选：直接反射调用（无进程开销）
        val direct = trySetIgnoreDirect(enabled)
        if (direct is Result.Success) return direct

        // 回退：`setFixedToUserRotation` / `setIgnoreOrientationRequest` 在 WMS 里
        // 带 MANAGE_APP_TOKENS 检查，而 shell 没有该签名权限；`wm` 命令是在
        // system_server 进程内执行的，绕过 binder 权限校验。因此用 Shizuku 以
        // shell 身份 fork 一个 `cmd window ...` 子进程，效果等同 `adb shell wm`。
        return tryShellCommand(enabled).let { result ->
            if (result is Result.Success) result else direct
        }
    }

    private fun trySetIgnoreDirect(enabled: Boolean): Result {
        return try {
            val wm = obtainWindowManager() ?: return Result.Failure("无法获取 WindowManager 服务")

            // Android 11+ : setIgnoreOrientationRequest(boolean)
            val boolMethod = wm.javaClass.methods
                .firstOrNull { it.name == "setIgnoreOrientationRequest" }
            if (boolMethod != null) {
                boolMethod.invoke(wm, *buildArgs(boolMethod.parameterTypes) { enabled })
                return Result.Success
            }

            // Android 10 : setFixedToUserRotation(int)，1=ENABLED，0=DEFAULT
            val intMethod = wm.javaClass.methods
                .firstOrNull { it.name == "setFixedToUserRotation" }
                ?: return Result.Failure("当前系统不支持忽略应用方向")
            intMethod.invoke(wm, *buildArgs(intMethod.parameterTypes) { if (enabled) 1 else 0 })
            Result.Success
        } catch (t: Throwable) {
            Result.Failure(describe(t), t)
        }
    }

    private fun tryShellCommand(enabled: Boolean): Result {
        return try {
            val sub = if (android.os.Build.VERSION.SDK_INT >= 30) {
                "set-ignore-orientation-request $enabled"
            } else {
                "set-fix-to-user-rotation ${if (enabled) "enabled" else "disabled"}"
            }
            val process = shizukuNewProcess(arrayOf("/system/bin/sh", "-c", "cmd window $sub"))
            val code = process.waitFor()
            if (code == 0) {
                Result.Success
            } else {
                val err = process.errorStream.bufferedReader().use { it.readText() }.trim()
                Result.Failure("cmd 退出码 $code${if (err.isEmpty()) "" else ": $err"}")
            }
        } catch (t: Throwable) {
            Result.Failure(describe(t), t)
        }
    }

    /** 解除旋转锁，恢复系统自动旋转。 */
    fun thawRotation(): Result {
        return try {
            val wm = obtainWindowManager() ?: return Result.Failure("无法获取 WindowManager 服务")
            val method = wm.javaClass.methods
                .filter { it.name == "thawRotation" }
                .minByOrNull { it.parameterCount }
                ?: return Result.Failure("当前系统未找到 thawRotation 方法")

            // thawRotation 参数是 displayId（通常无参或传 0），不是 rotation
            method.invoke(wm, *buildIntArgs(method.parameterTypes) { 0 })
            Result.Success
        } catch (t: Throwable) {
            Result.Failure(describe(t), t)
        }
    }

    // ---------- 内部实现 ----------

    /**
     * 隐藏 API 豁免，懒加载。
     *
     * `addHiddenApiExemptions` 会反序列化整张 ART 方法表，在模拟器上占约 11 MB
     * native 堆。放在 Application.onCreate 里等于让每个用户——包括只用悬浮窗
     * 或系统设置路径、根本不反射的用户——都白付这笔内存。这里改成首次真正
     * 需要反射 IWindowManager 时才执行一次。
     */
    @Volatile
    private var exemptionsReady = false

    private fun ensureHiddenApiExemptions() {
        if (exemptionsReady) return
        exemptionsReady = true // 先置位：并发下最多重复一次，代价远小于加锁
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/IWindowManager;",
                "Landroid/view/IWindowManager\$Stub;"
            )
        } catch (t: Throwable) {
            // 豁免失败不阻断：后续调用会走 try/catch 降级
        }
    }

    private fun obtainWindowManager(): Any? {
        ensureHiddenApiExemptions()
        val binder: IBinder = SystemServiceHelper.getSystemService(WINDOW_SERVICE) ?: return null
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.view.IWindowManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, wrapped)
    }

    /**
     * 按参数类型补默认值：遇到 int 参数时调用 [intValueForIndex]（传入参数下标）取具体值，
     * 非 int 参数补 0/false/null。这样能兼容 (int) / (int,int) / (int,int,int) 等不同签名。
     */
    private inline fun buildIntArgs(
        parameterTypes: Array<Class<*>>,
        intValueForIndex: (Int) -> Int
    ): Array<Any?> = parameterTypes.mapIndexed { index, type ->
        when {
            type == Int::class.javaPrimitiveType || type == Integer::class.java -> intValueForIndex(index)
            type == Boolean::class.javaPrimitiveType -> false
            else -> null
        }
    }.toTypedArray()

    /**
     * 通用参数填充：int 参数取 lambda 里的 Int，boolean 参数取 lambda 里的 Boolean，
     * 其余补 null。
     */
    private inline fun buildArgs(
        parameterTypes: Array<Class<*>>,
        valueForIndex: (Int) -> Any
    ): Array<Any?> = parameterTypes.mapIndexed { index, type ->
        val value = valueForIndex(index)
        when {
            type == Int::class.javaPrimitiveType || type == Integer::class.java ->
                (value as? Int) ?: 0
            type == Boolean::class.javaPrimitiveType ->
                (value as? Boolean) ?: false
            else -> null
        }
    }.toTypedArray()

    /**
     * 以 Shizuku 的 shell 身份 fork 子进程。
     *
     * `Shizuku.newProcess` 在 13.x 被收成 private（仅供 rish 使用），这里用反射取回；
     * 若不这么做，就只能自己写 UserService，代价高得多。
     */
    private fun shizukuNewProcess(cmd: Array<String>): Process {
        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as Process
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return root.javaClass.simpleName + ": " + (root.message ?: "")
    }
}
