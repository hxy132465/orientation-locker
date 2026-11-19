package com.orientationlocker

import android.app.Application
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * 全局初始化：注册 Shizuku binder 存活/死亡监听，便于 UI 与磁贴及时刷新状态。
 *
 * 注意：这里不做任何轮询或后台任务，只注册回调，符合「低占用」目标。
 */
class OrientationLockerApp : Application() {

    var shizukuAlive: Boolean = false
        private set

    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        shizukuAlive = true
        notifyStateChanged()
    }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        shizukuAlive = false
        notifyStateChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        applyHiddenApiExemptions()
        try {
            shizukuAlive = Shizuku.pingBinder()
        } catch (t: Throwable) {
            shizukuAlive = false
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
            Shizuku.addBinderDeadListener(onBinderDead)
        } catch (t: Throwable) {
            // Shizuku 未安装时忽略
        }
    }

    /**
     * 反射 IWindowManager 属于隐藏 API，Android P+ 默认会拦截。
     * 仅豁免这两个类，范围最小，不影响其它行为。
     */
    private fun applyHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/IWindowManager;",
                "Landroid/view/IWindowManager\$Stub;"
            )
        } catch (t: Throwable) {
            // 豁免失败不阻断：后续调用会走 try/catch 降级
        }
    }

    private fun notifyStateChanged() {
        // 通过静态回调让磁贴/界面感知；此处仅置标志，避免持有 Activity 引用
        stateVersion++
    }

    companion object {
        @Volatile
        private var instance: OrientationLockerApp? = null

        /** 状态变更计数器，UI 可据此判断是否需要刷新。 */
        @Volatile
        var stateVersion: Int = 0
            private set

        fun get(): OrientationLockerApp? = instance
    }
}
