package com.orientationlocker.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.orientationlocker.core.LockManager
import com.orientationlocker.shizuku.ShizukuHelper

/**
 * 开机 / 应用升级后兜底：如果之前处于锁定状态，尝试重新应用。
 *
 * 说明：
 * - `freezeRotation` 理论上跨重启持久，但部分 ROM 会重置，因此做一次校验式重放。
 * - Shizuku 在开机时可能尚未就绪，这里只尝试一次；若失败则保持用户意图，
 *   待用户下次打开 App 或点击磁贴时再恢复（不做定时轮询，避免耗电）。
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pending = goAsync()
        try {
            val manager = LockManager(context.applicationContext)
            if (!manager.isLocked()) return

            if (ShizukuHelper.isReady() || com.orientationlocker.core.FallbackController.canWrite(context)) {
                val ok = manager.restoreIfNeeded()
                Log.i(TAG, "开机恢复锁定: $ok")
            } else {
                Log.i(TAG, "开机恢复跳过：无可用路径（Shizuku 未就绪 / 无写设置权限）")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "开机恢复失败", t)
        } finally {
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "BootRestore"
    }
}
