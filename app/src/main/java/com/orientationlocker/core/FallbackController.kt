package com.orientationlocker.core

import android.content.Context
import android.provider.Settings

/**
 * 降级路径：无 Shizuku 时，通过系统设置 `USER_ROTATION` + 关闭自动旋转 来锁定方向。
 *
 * 局限：无法覆盖「强行指定自身方向」的视频/游戏类 App，仅影响系统默认方向策略。
 * 优势：不需要 Shizuku，只需 `WRITE_SETTINGS` 特殊权限（用户到系统设置页手动授权）。
 */
object FallbackController {

    /** 是否已获得 WRITE_SETTINGS 权限。 */
    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /**
     * 锁定方向。
     * @return true 表示写入成功
     */
    fun lock(context: Context, rotation: Int): Boolean {
        if (!Rotations.isValid(rotation) || !canWrite(context)) return false
        return try {
            val cr = context.contentResolver
            // 先设目标方向，再关自动旋转
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, rotation)
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 当前系统记录的自动旋转开关状态（1=开，0=关）。读不到时默认按「开」处理。 */
    fun readAutoRotate(context: Context): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
    } catch (t: Throwable) {
        1
    }

    /** 恢复自动旋转开关到指定值。 */
    fun restoreAutoRotate(context: Context, value: Int): Boolean {
        if (!canWrite(context)) return false
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, value)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
