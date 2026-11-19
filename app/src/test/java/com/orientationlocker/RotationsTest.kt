package com.orientationlocker

import com.orientationlocker.core.Rotations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向常量与判定的纯逻辑测试（无需 Android 运行时）。
 *
 * 重点：常量值必须与 android.view.Surface.ROTATION_* 完全一致，
 * 因为 Shizuku 调 freezeRotation 时直接透传这些值，错一位方向就反。
 */
class RotationsTest {

    @Test
    fun `常量值与 Surface ROTATION 对齐`() {
        assertEquals(0, Rotations.PORTRAIT)
        assertEquals(1, Rotations.LANDSCAPE)
        assertEquals(2, Rotations.REVERSE_PORTRAIT)
        assertEquals(3, Rotations.REVERSE_LANDSCAPE)
        assertEquals(-1, Rotations.AUTO)
    }

    @Test
    fun `0 到 3 是合法方向`() {
        (0..3).forEach { assertTrue("rotation=$it 应合法", Rotations.isValid(it)) }
    }

    @Test
    fun `越界值与 AUTO 都不是合法锁定方向`() {
        listOf(-1, 4, 5, 90, -90, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
            assertFalse("rotation=$it 不应合法", Rotations.isValid(it))
        }
    }

    @Test
    fun `isLocked 与 isValid 一致`() {
        (-2..5).forEach { assertEquals(Rotations.isValid(it), Rotations.isLocked(it)) }
    }
}
