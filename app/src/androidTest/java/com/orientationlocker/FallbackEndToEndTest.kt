package com.orientationlocker

import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orientationlocker.core.FallbackController
import com.orientationlocker.core.LockManager
import com.orientationlocker.core.LockStateRepository
import com.orientationlocker.core.Rotations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器端到端测试：验证降级路径确实改变了系统状态，而不只是写了 SharedPreferences。
 *
 * 运行前提（由 adb 预置）：
 *   adb shell appops set com.orientationlocker android:write_settings allow
 *
 * 这是本 App 最核心的"是否真的生效"的证据：
 * 锁横屏后 USER_ROTATION 必须变成 1、自动旋转必须被关掉；
 * 解锁后自动旋转必须还原为测试前的值。
 */
@RunWith(AndroidJUnit4::class)
class FallbackEndToEndTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LockStateRepository(context)
        repo.clearLock()
        repo.clearSnapshot()
    }

    private fun readSystemRotation(): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, -1)

    private fun readAutoRotate(): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)

    @Test
    fun 锁定横屏后系统设置真的被改写() {
        assumeTrue("需要先通过 adb 授予 WRITE_SETTINGS", FallbackController.canWrite(context))

        val manager = LockManager(context)
        val outcome = manager.lock(Rotations.LANDSCAPE)

        assertTrue("锁定应成功，实际 $outcome", outcome is LockManager.Outcome.Success)
        assertEquals("USER_ROTATION 应为 1（横屏）", 1, readSystemRotation())
        assertEquals("自动旋转应被关闭", 0, readAutoRotate())
        assertTrue("状态仓库应记录为已锁定", manager.isLocked())
        assertEquals(Rotations.LANDSCAPE, manager.currentRotation())
    }

    @Test
    fun 解锁后自动旋转开关精确还原() {
        assumeTrue("需要先通过 adb 授予 WRITE_SETTINGS", FallbackController.canWrite(context))

        // 构造一个"原值为关"的场景，验证还原的是原值而不是无脑打开
        FallbackController.canWrite(context)
        Settings.System.putInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0
        )
        val original = readAutoRotate()
        val originalUserRotation = readSystemRotation()

        val manager = LockManager(context)
        manager.lock(Rotations.PORTRAIT)
        assertEquals(0, readAutoRotate())

        manager.unlock()

        assertEquals("解锁后应还原为原值 $original", original, readAutoRotate())
        assertEquals(
            "USER_ROTATION 也应还原，否则用户之后关掉自动旋转会突然转向",
            originalUserRotation,
            readSystemRotation()
        )
        assertFalse(manager.isLocked())

        // 收尾：恢复成自动旋转开，避免影响后续测试
        Settings.System.putInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
        )
    }

    @Test
    fun 四种方向都能写入() {
        assumeTrue("需要先通过 adb 授予 WRITE_SETTINGS", FallbackController.canWrite(context))

        val manager = LockManager(context)
        listOf(
            Rotations.PORTRAIT,
            Rotations.LANDSCAPE,
            Rotations.REVERSE_PORTRAIT,
            Rotations.REVERSE_LANDSCAPE
        ).forEach { rotation ->
            val outcome = manager.lock(rotation)
            assertTrue("rotation=$rotation 应成功，实际 $outcome", outcome is LockManager.Outcome.Success)
            assertEquals("USER_ROTATION 应为 $rotation", rotation, readSystemRotation())
        }
        manager.unlock()
    }

    @Test
    fun 失败路径不污染状态仓库() {
        // 不 assume 权限：若确实没有权限，这里就是失败路径
        if (FallbackController.canWrite(context)) {
            return // 有权限时该场景不成立，跳过
        }
        val manager = LockManager(context)
        val outcome = manager.lock(Rotations.LANDSCAPE)
        assertTrue(outcome is LockManager.Outcome.Unavailable)
        assertFalse("失败后不应记录为已锁定", manager.isLocked())
        assertFalse(
            "失败后不应留下自动旋转快照",
            LockStateRepository(context).hasAutoRotateSnapshot
        )
    }
}
