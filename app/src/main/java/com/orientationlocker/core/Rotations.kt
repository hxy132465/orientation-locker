package com.orientationlocker.core

/**
 * 方向常量。数值与 [android.view.Surface.ROTATION_*] 保持一致，
 * 因为 Shizuku 调用 `IWindowManager.freezeRotation(int)` 时直接使用 Surface 的旋转值。
 */
object Rotations {
    /** 竖屏 */
    const val PORTRAIT = 0

    /** 横屏（顺时针 90°） */
    const val LANDSCAPE = 1

    /** 反向竖屏（180°） */
    const val REVERSE_PORTRAIT = 2

    /** 反向横屏（270°） */
    const val REVERSE_LANDSCAPE = 3

    /** 未锁定 / 自动旋转 */
    const val AUTO = -1

    fun isValid(rotation: Int): Boolean = rotation in 0..3

    fun isLocked(rotation: Int): Boolean = isValid(rotation)
}
