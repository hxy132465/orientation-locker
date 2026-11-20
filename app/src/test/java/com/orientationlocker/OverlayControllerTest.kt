package com.orientationlocker

import android.content.pm.ActivityInfo
import com.orientationlocker.core.OverlayController
import com.orientationlocker.core.Rotations
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮窗路径的方向映射。
 *
 * 这里只测纯函数：窗口本身依赖 WindowManager，Robolectric 无法真实合成。
 * 映射写错是最容易犯又最难在真机上定位的错（表现为"锁了反向横屏结果正着"），
 * 因此单列一组用例钉住。
 */
class OverlayControllerTest {

    @Test
    fun `竖屏映射为 PORTRAIT`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OverlayController.toScreenOrientation(Rotations.PORTRAIT)
        )
    }

    @Test
    fun `横屏映射为 LANDSCAPE`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OverlayController.toScreenOrientation(Rotations.LANDSCAPE)
        )
    }

    @Test
    fun `反向竖屏映射为 REVERSE_PORTRAIT`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            OverlayController.toScreenOrientation(Rotations.REVERSE_PORTRAIT)
        )
    }

    @Test
    fun `反向横屏映射为 REVERSE_LANDSCAPE`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            OverlayController.toScreenOrientation(Rotations.REVERSE_LANDSCAPE)
        )
    }

    @Test
    fun `未锁定映射为 UNSPECIFIED 不影响应用方向`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OverlayController.toScreenOrientation(Rotations.AUTO)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OverlayController.toScreenOrientation(99)
        )
    }

    @Test
    fun `反向取值不得与正向重复`() {
        // 回归保护：曾把 REVERSE_LANDSCAPE 写成 LANDSCAPE，导致"反向横屏"实际是正向
        val all = listOf(
            Rotations.PORTRAIT, Rotations.LANDSCAPE,
            Rotations.REVERSE_PORTRAIT, Rotations.REVERSE_LANDSCAPE
        ).map { OverlayController.toScreenOrientation(it) }
        assertEquals(all.size, all.toSet().size)
    }
}
