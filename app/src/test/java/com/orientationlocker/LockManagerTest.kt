package com.orientationlocker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orientationlocker.core.LockManager
import com.orientationlocker.core.LockStateRepository
import com.orientationlocker.core.Rotations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 编排层测试。运行环境无 Shizuku（binder 不存活）、无 WRITE_SETTINGS 授权，
 * 因此覆盖的是「无可用路径」这条最重要的降级边界——它决定了 App 在裸装状态下
 * 会不会误以为自己锁成功了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LockManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("orientation_lock_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Robolectric 默认所有 AppOps 都放行，而真机上 WRITE_SETTINGS 需要用户
        // 手动去系统设置页开启。这里显式置为拒绝，还原"裸装 App"的真实前提。
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        shadowOf(appOps).setMode(
            android.app.AppOpsManager.OPSTR_WRITE_SETTINGS,
            android.os.Process.myUid(),
            context.packageName,
            android.app.AppOpsManager.MODE_ERRORED
        )
    }

    @Test
    fun `裸装状态下没有可用路径`() {
        val manager = LockManager(context)
        assertFalse("无 Shizuku、无写设置权限时不应报告可用", manager.hasUsablePath())
    }

    @Test
    fun `非法方向值直接失败且不落状态`() {
        val manager = LockManager(context)
        val outcome = manager.lock(99)

        assertTrue("应为 Failed，实际 $outcome", outcome is LockManager.Outcome.Failed)
        assertFalse("失败不应写入锁定意图", manager.isLocked())
        assertEquals(Rotations.AUTO, manager.currentRotation())
    }

    @Test
    fun `无可用路径时 lock 返回 Unavailable 并提示所需权限`() {
        val manager = LockManager(context)
        val outcome = manager.lock(Rotations.LANDSCAPE)

        assertTrue("应为 Unavailable，实际 $outcome", outcome is LockManager.Outcome.Unavailable)
        val u = outcome as LockManager.Outcome.Unavailable
        assertTrue("应提示需要 Shizuku", u.needShizuku)
        assertTrue("应提示需要写设置权限", u.needWriteSettings)
    }

    @Test
    fun `锁定失败后不应残留锁定意图`() {
        val manager = LockManager(context)
        manager.lock(Rotations.PORTRAIT)
        assertFalse(manager.isLocked())
    }

    /**
     * 失败路径不应留下快照：否则用户从未成功锁定，之后 unlock 却会去「恢复」
     * 一个并不存在的原值，可能把用户的自动旋转开关改错。
     */
    @Test
    fun `锁定失败后不应残留自动旋转快照`() {
        val manager = LockManager(context)
        manager.lock(Rotations.LANDSCAPE)

        val repo = LockStateRepository(context)
        assertFalse("失败路径不应记录快照", repo.hasAutoRotateSnapshot)
    }

    @Test
    fun `未锁定时 unlock 也安全返回`() {
        val manager = LockManager(context)
        val outcome = manager.unlock()
        assertNotNull(outcome)
        assertTrue(outcome is LockManager.Outcome.Success)
        assertFalse(manager.isLocked())
        assertEquals(LockStateRepository.Mode.NONE, manager.activeMode())
    }

    @Test
    fun `未锁定时 restoreIfNeeded 不做任何事`() {
        val manager = LockManager(context)
        assertFalse(manager.restoreIfNeeded())
    }

    @Test
    fun `已锁定但无可用路径时 restoreIfNeeded 返回 false 且保留意图`() {
        val repo = LockStateRepository(context)
        repo.lockedRotation = Rotations.REVERSE_LANDSCAPE
        repo.activeMode = LockStateRepository.Mode.SHIZUKU

        val manager = LockManager(context)
        assertFalse(manager.restoreIfNeeded())
        assertTrue("恢复失败不应丢掉用户意图", manager.isLocked())
        assertEquals(Rotations.REVERSE_LANDSCAPE, manager.currentRotation())
    }
}
