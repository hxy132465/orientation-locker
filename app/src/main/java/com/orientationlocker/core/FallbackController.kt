package com.orientationlocker.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings

/**
 * 降级路径：无 Shizuku 时，通过系统设置 `USER_ROTATION` + 关闭自动旋转 来锁定方向。
 *
 * 局限：无法覆盖「强行指定自身方向」的视频/游戏类 App，仅影响系统默认方向策略。
 * 优势：不需要 Shizuku，只需 `WRITE_SETTINGS` 特殊权限（用户到系统设置页手动授权）。
 */
object FallbackController {

    /**
     * 是否已获得 WRITE_SETTINGS 权限。
     *
     * 直接查 AppOps：WRITE_SETTINGS 在 API 23+ 是 appop 保护的权限，
     * 在清单里声明即「已授予」，真正能否写入取决于 appop 模式。
     * 只查 permission 会误判为可用，导致锁定操作静默失效。
     */
    fun canWrite(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return Settings.System.canWrite(context)
            when (appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), context.packageName
            )) {
                AppOpsManager.MODE_ALLOWED -> true
                // MODE_DEFAULT：尚未由用户显式设置，此时由权限授予状态决定
                AppOpsManager.MODE_DEFAULT ->
                    context.checkSelfPermission(Manifest.permission.WRITE_SETTINGS) ==
                        PackageManager.PERMISSION_GRANTED
                else -> false
            }
        } catch (t: Throwable) {
            // 个别 ROM 的 AppOps 实现异常时回退到系统 API
            try {
                Settings.System.canWrite(context)
            } catch (t2: Throwable) {
                false
            }
        }
    }

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

    /** 当前系统记录的 USER_ROTATION（0/1/2/3）。读不到时按竖屏处理。 */
    fun readUserRotation(context: Context): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, Rotations.PORTRAIT)
    } catch (t: Throwable) {
        Rotations.PORTRAIT
    }

    /** 恢复 USER_ROTATION 到锁定前的值，避免解锁后残留方向。 */
    fun restoreUserRotation(context: Context, value: Int): Boolean {
        if (!Rotations.isValid(value) || !canWrite(context)) return false
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, value)
            true
        } catch (t: Throwable) {
            false
        }
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
