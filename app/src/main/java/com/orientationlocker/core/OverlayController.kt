package com.orientationlocker.core

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 「无 Shizuku」时的系统级接管：一个 1×1 透明悬浮窗冒充方向请求。
 *
 * ## 为什么它能压过前台 App
 *
 * `WindowManagerService.getOrientationFromWindowsLocked()` 从窗口栈顶向下遍历，
 * 取第一个「可见且声明了 screenOrientation」的窗口作为屏幕方向；只有碰到带
 * `appToken` 的 Activity 窗口时才停止。`TYPE_APPLICATION_OVERLAY` 位于 Activity
 * 之上且没有 appToken，因此它的 screenOrientation 会被优先采用——这就是
 * 「App 自己写死竖屏也抢不走屏幕」的原因。
 *
 * ## 内存代价如何压到最低
 *
 * - 视图是裸 [View]：无布局、无背景、无资源 inflate，1×1 像素不产生有效绘制；
 * - 不注册任何监听器、广播、定时器：方向只由外部一次调用更新，零轮询；
 * - **不用 AccessibilityService**。同类 App（如 Rotation）用它做 per-app 识别，
 *   那才是它们内存高的主因；我们做的是全局锁定，根本不需要知道前台是谁；
 * - 无 binder、无子进程、无网络。
 */
object OverlayController {

    private const val TAG = "Overlay"

    /** 1×1。像素数越少，窗口的合成开销越接近 0。 */
    private const val SIZE_PX = 1

    @Volatile
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null

    /** 是否有权绘制悬浮窗（用户在系统设置里授予「显示在其他应用上层」）。 */
    fun canDraw(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (t: Throwable) {
        false
    }

    /** 窗口当前是否已挂到 WindowManager 上。 */
    fun isAttached(): Boolean = view?.isAttachedToWindow == true

    /**
     * 添加接管窗口。已在则直接返回 true（幂等）。
     *
     * 同步完成：调用返回后窗口即已进入 WMS 的窗口栈，方向请求立即生效，
     * 不需要等待服务回调，UI 可以马上读系统状态确认。
     */
    fun attach(context: Context): Boolean {
        if (isAttached()) return true
        if (!canDraw(context)) return false
        return runOnUiThread(false) { attachInternal(context) }
    }

    private fun attachInternal(context: Context): Boolean {
        if (isAttached()) return true
        // 残留引用：窗口可能已被系统清理（例如服务被判 idle 后 WMS 移除了它），
        // 但 View 对象还在。此时必须先丢掉旧引用，否则 addView 会重复添加。
        if (view != null) detachInternal()

        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val lp = newParams()
        val v = View(app)

        // 窗口被系统移除时（而非我们主动 detach），同步清空静态引用。
        // 否则 isAttached() 会一直为 true，恢复逻辑直接短路，锁定再也回不来。
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(detached: View) {
                if (view === detached) {
                    view = null
                    params = null
                    wm = null
                }
            }
        })

        return try {
            manager.addView(v, lp)
            view = v
            params = lp
            wm = manager
            true
        } catch (t: Throwable) {
            Log.w(TAG, "添加方向接管窗口失败", t)
            false
        }
    }

    /**
     * 更新窗口的方向请求。
     *
     * @param rotation 取值见 [Rotations]
     * @return true 表示系统已接受更新
     */
    fun apply(rotation: Int): Boolean {
        if (!isAttached()) return false
        return runOnUiThread(false) { applyInternal(rotation) }
    }

    private fun applyInternal(rotation: Int): Boolean {
        val v = view ?: return false
        val lp = params ?: return false
        val manager = wm ?: return false

        lp.screenOrientation = toScreenOrientation(rotation)
        return try {
            manager.updateViewLayout(v, lp)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "更新窗口方向失败", t)
            false
        }
    }

    /** 移除窗口。解锁时调用，进程退出前也由服务兜底调用。 */
    fun detach() {
        if (view == null) return
        runOnUiThread(Unit) { detachInternal() }
    }

    private fun detachInternal() {
        val v = view ?: return
        val manager = wm
        view = null
        params = null
        wm = null
        try {
            manager?.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "移除窗口失败", t)
        }
    }

    /**
     * 窗口操作全部要求调用线程有 Looper（[ViewRootImpl] 内部要建 Handler），
     * 而锁定流程跑在后台线程，直接调用会抛
     * `Can't create handler inside thread that has not called Looper.prepare()`。
     * 这里统一抛到主线程执行，并同步等待结果，保持 [attach]/[apply] 的同步语义。
     */
    private fun <T> runOnUiThread(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference(fallback)
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                Log.w(TAG, "窗口操作异常", t)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return fallback
        return try {
            // 超时给足：模拟器/低端机上主线程可能正忙于绘制
            if (latch.await(5, TimeUnit.SECONDS)) result.get() else fallback
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            fallback
        }
    }

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 参数组合来自实测验证过的最小侵入档位：
     * 不可触摸（不抢输入）、不可获焦（不干扰输入法）、锁屏也保持（避免解锁瞬间方向跳动）。
     */
    private fun newParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        return WindowManager.LayoutParams(
            SIZE_PX,
            SIZE_PX,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 初始为「不指定」，锁定前不影响任何 App 的方向
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * Surface 旋转值 → ActivityInfo 方向常量。
     *
     * 注意 90°/270° 的区分：`SCREEN_ORIENTATION_LANDSCAPE` 只表达「某个横屏」，
     * 具体角度由系统的 USER_ROTATION 决定；反向横竖屏用 8/9 可以精确指定。
     * [LockManager] 在窗口生效后还会补写一次 USER_ROTATION 来消除歧义。
     */
    fun toScreenOrientation(rotation: Int): Int = when (rotation) {
        Rotations.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Rotations.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Rotations.REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Rotations.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
