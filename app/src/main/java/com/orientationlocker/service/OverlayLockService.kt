package com.orientationlocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.orientationlocker.R
import com.orientationlocker.core.LockStateRepository
import com.orientationlocker.core.OverlayController
import com.orientationlocker.core.Rotations
import com.orientationlocker.ui.MainActivity

/**
 * 方向接管窗口的「保活壳」。
 *
 * 这个服务自己不画任何 UI：窗口由 [OverlayController] 以同步方式添加。
 * 它唯一的职责是把进程提升到前台服务优先级——窗口与进程同生共死，
 * 进程被 LMK 回收窗口就没了，锁定随之失效。
 *
 * 为了不让「保活」变成「占内存」，这里刻意做减法：
 * - 无 binder、无线程池、无监听器、无轮询、无定时器；
 * - 通知渠道 IMPORTANCE_MIN + 静音，用户几乎无感；
 * - 解锁立即 stopSelf，进程回落到普通 cached 状态。
 *
 * 只有「没有 Shizuku」时才走这条路；有 Shizuku 时这条路径完全不参与，
 * 连服务都不会被创建。
 */
class OverlayLockService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 进程被系统为本服务拉起时，先于 onStartCommand 恢复窗口。
     *
     * 部分系统（实测 Android 10）会在「应用处于 idle」时停止 sticky 重启的服务，
     * 从拉起进程到服务被停只有一两秒。把恢复动作放在 onCreate 能抢到这个窗口，
     * 即使服务随后被停，窗口也已经挂在 WMS 上——只要进程还活着就仍然有效
     * （见 [onDestroy] 为何不在这里移除窗口）。
     */
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val repo = LockStateRepository(this)
        if (repo.isLocked && Rotations.isValid(repo.lockedRotation)) {
            val rotation = repo.lockedRotation
            startForegroundCompat(rotation)
            ensureOverlay(rotation)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            else -> {
                val rotation = resolveRotation(intent)
                if (!Rotations.isValid(rotation)) {
                    // 没有有效方向可维持（可能解锁后被系统拉起）→ 直接收摊
                    shutdown()
                    return START_NOT_STICKY
                }
                startForegroundCompat(rotation)
                ensureOverlay(rotation)
                // sticky：进程被回收后系统会重启服务，我们据此重建窗口（见 resolveRotation）。
                // 这比"锁着锁着方向自己跑了"要好；服务本身极薄，重建代价可忽略。
                return START_STICKY
            }
        }
    }

    /**
     * 注意：这里**不**移除接管窗口。
     *
     * 服务被系统停掉不等于锁定要结束——窗口挂在 WindowManager 上，只要进程还活着
     * 就继续生效。主动解锁走 [shutdown]，那里会显式移除；进程被回收时窗口由系统
     * 一并清理。若在这里不加区分地 detach，会导致「服务被 idle 停掉 → 锁定莫名失效」。
     */
    override fun onDestroy() {
        super.onDestroy()
    }

    /** 优先用启动参数，其次读持久化的锁定意图（进程被系统重建时走这里）。 */
    private fun resolveRotation(intent: Intent?): Int {
        val fromIntent = intent?.getIntExtra(EXTRA_ROTATION, Rotations.AUTO) ?: Rotations.AUTO
        if (Rotations.isValid(fromIntent)) return fromIntent
        val repo = LockStateRepository(this)
        return if (repo.isLocked) repo.lockedRotation else Rotations.AUTO
    }

    private fun ensureOverlay(rotation: Int) {
        if (!OverlayController.canDraw(this)) {
            Log.w(TAG, "悬浮窗权限已失效，停止保活")
            shutdown()
            return
        }
        if (!OverlayController.isAttached() && !OverlayController.attach(this)) {
            Log.w(TAG, "接管窗口创建失败，停止保活")
            shutdown()
            return
        }
        OverlayController.apply(rotation)
    }

    private fun shutdown() {
        OverlayController.detach()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "移除前台通知失败", t)
        }
        stopSelf()
    }

    private fun startForegroundCompat(rotation: Int) {
        val notification = buildNotification(rotation)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务受限时可能抛异常。
            // 此时窗口仍然有效，只是稳定性下降——不算致命，不能因此闪退。
            Log.w(TAG, "前台服务启动失败，仅靠窗口存活", t)
        }
    }

    private fun buildNotification(rotation: Int): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, OverlayLockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 原生 Builder，不引入 androidx.core：通知本身的字段在 API 24+ 已够用，
        // 静音交给渠道（IMPORTANCE_MIN + 无声音）而不是 Compat 的 setSilent。
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(R.string.action_unlock),
            stop
        ).build()
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, labelOf(rotation)))
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(openApp)
            .addAction(stopAction)
            .apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    setPriority(Notification.PRIORITY_MIN)
                }
            }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            // MIN：不出横幅、不响铃、不亮屏，只在通知栏底部静默存在
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)
    }

    private fun labelOf(rotation: Int): String = when (rotation) {
        Rotations.PORTRAIT -> getString(R.string.orientation_portrait)
        Rotations.LANDSCAPE -> getString(R.string.orientation_landscape)
        Rotations.REVERSE_PORTRAIT -> getString(R.string.orientation_reverse_portrait)
        Rotations.REVERSE_LANDSCAPE -> getString(R.string.orientation_reverse_landscape)
        else -> getString(R.string.tile_label)
    }

    companion object {
        private const val TAG = "OverlayLock"
        private const val CHANNEL_ID = "orientation_lock_keepalive"
        private const val NOTIF_ID = 0x4F52
        private const val REQ_OPEN = 10
        private const val REQ_STOP = 11

        const val ACTION_STOP = "com.orientationlocker.action.OVERLAY_STOP"
        private const val EXTRA_ROTATION = "rotation"

        /** 启动保活。窗口本身由调用方同步添加，这里只负责把进程提到前台优先级。 */
        fun start(context: Context, rotation: Int) {
            val intent = Intent(context.applicationContext, OverlayLockService::class.java)
                .putExtra(EXTRA_ROTATION, rotation)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "无法启动保活服务", t)
            }
        }

        /**
         * 停止保活并移除窗口。
         *
         * 用 stopService 而非再发一次 ACTION_STOP：stopService 不受后台启动限制，
         * 且服务不在运行时是空操作，不会为了"停"而冷启动一个进程。
         */
        fun stop(context: Context) {
            val app = context.applicationContext
            OverlayController.detach()
            try {
                app.stopService(Intent(app, OverlayLockService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止保活服务失败", t)
            }
        }
    }
}
