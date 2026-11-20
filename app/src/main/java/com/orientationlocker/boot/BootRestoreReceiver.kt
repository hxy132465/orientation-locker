package com.orientationlocker.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.orientationlocker.core.LockManager

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

        // 显式声明为可空：goAsync() 在少量运行环境（如部分测试/无调度器场景）可能返回 null，
        // 不能让 finally 里的 finish() 把"恢复失败"变成"广播处理崩溃"。
        val pending: PendingResult? = goAsync()
        val appContext = context.applicationContext
        // 恢复流程包含 binder 调用，Shizuku 路径还可能 fork 一个 shell 子进程。
        // 这些都不能放在 onReceive（主线程）里做：开机时系统繁忙，超过 10 秒
        // 就会 ANR。goAsync 的作用正是让我们把工作挪到后台线程后再 finish。
        Thread({
            try {
                val manager = LockManager(appContext)
                if (!manager.isLocked()) return@Thread

                if (manager.hasUsablePath()) {
                    val ok = manager.restoreIfNeeded()
                    Log.i(TAG, "开机恢复锁定: $ok")
                } else {
                    Log.i(TAG, "开机恢复跳过：无可用路径（Shizuku 未就绪 / 无悬浮窗与写设置权限）")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "开机恢复失败", t)
            } finally {
                pending?.finish()
            }
        }, "boot-restore").start()
    }

    private companion object {
        const val TAG = "BootRestore"
    }
}
