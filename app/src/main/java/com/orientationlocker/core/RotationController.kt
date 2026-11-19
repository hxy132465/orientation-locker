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

    private fun obtainWindowManager(): Any? {
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

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return root.javaClass.simpleName + ": " + (root.message ?: "")
    }
}
