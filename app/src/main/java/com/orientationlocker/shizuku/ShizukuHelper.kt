package com.orientationlocker.shizuku

import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态与权限的统一封装。
 *
 * Shizuku 的三态：
 * 1. 未安装 / 服务未运行 → [isBinderAlive] = false
 * 2. 服务运行但本 App 未授权 → [hasPermission] = false
 * 3. 服务运行且已授权 → [isReady] = true
 */
object ShizukuHelper {

    const val PERMISSION_REQUEST_CODE = 1001

    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    /** Shizuku 服务是否可用。 */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** 是否已获得 Shizuku 授权。 */
    fun hasPermission(): Boolean = try {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 是否可以直接调用系统服务。 */
    fun isReady(): Boolean = isBinderAlive() && hasPermission()

    /** 是否处于「服务在跑但未授权」的状态（可弹授权框）。 */
    fun canRequestPermission(): Boolean = try {
        isBinderAlive() && !hasPermission() && !Shizuku.isPreV11()
    } catch (t: Throwable) {
        false
    }

    /** 发起授权请求。必须在 Shizuku 服务存活时调用。 */
    fun requestPermission() {
        try {
            if (isBinderAlive() && !hasPermission()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (t: Throwable) {
            // 忽略：由 UI 层根据状态提示用户
        }
    }

    /** Shizuku 版本号（诊断用）。 */
    fun serverVersion(): String = try {
        if (isBinderAlive()) "${Shizuku.getVersion()}" else "—"
    } catch (t: Throwable) {
        "—"
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        removePermissionListener()
        permissionListener = listener
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun removePermissionListener() {
        permissionListener?.let {
            try {
                Shizuku.removeRequestPermissionResultListener(it)
            } catch (t: Throwable) {
                // ignore
            }
        }
        permissionListener = null
    }

    @Suppress("unused")
    val isAtLeastU: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
