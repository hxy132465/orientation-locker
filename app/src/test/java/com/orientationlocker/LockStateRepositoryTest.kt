package com.orientationlocker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orientationlocker.core.LockStateRepository
import com.orientationlocker.core.Rotations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 状态仓库测试：验证持久化语义、默认值，以及损坏/异版本数据下的健壮性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LockStateRepositoryTest {

    private lateinit var context: Context
    private val prefsName = "orientation_lock_state"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `初始状态为未锁定`() {
        val repo = LockStateRepository(context)
        assertEquals(Rotations.AUTO, repo.lockedRotation)
        assertEquals(LockStateRepository.Mode.NONE, repo.activeMode)
        assertFalse(repo.isLocked)
        assertFalse(repo.hasAutoRotateSnapshot)
    }

    @Test
    fun `锁定额度可持久化并被新实例读到`() {
        val repo = LockStateRepository(context)
        repo.lockedRotation = Rotations.LANDSCAPE
        repo.activeMode = LockStateRepository.Mode.SHIZUKU
        repo.originalAutoRotate = 0
        repo.hasAutoRotateSnapshot = true
        repo.lastAppliedAt = 123456789L

        // 模拟进程重启：重新构造仓库
        val reopened = LockStateRepository(context)
        assertEquals(Rotations.LANDSCAPE, reopened.lockedRotation)
        assertEquals(LockStateRepository.Mode.SHIZUKU, reopened.activeMode)
        assertEquals(0, reopened.originalAutoRotate)
        assertTrue(reopened.hasAutoRotateSnapshot)
        assertEquals(123456789L, reopened.lastAppliedAt)
        assertTrue(reopened.isLocked)
    }

    @Test
    fun `clearLock 清意图但保留快照`() {
        val repo = LockStateRepository(context)
        repo.lockedRotation = Rotations.PORTRAIT
        repo.activeMode = LockStateRepository.Mode.FALLBACK
        repo.hasAutoRotateSnapshot = true
        repo.originalAutoRotate = 0

        repo.clearLock()

        assertFalse(repo.isLocked)
        assertEquals(LockStateRepository.Mode.NONE, repo.activeMode)
        assertTrue("快照需保留给解锁恢复用", repo.hasAutoRotateSnapshot)
        assertEquals(0, repo.originalAutoRotate)
    }

    @Test
    fun `clearSnapshot 只清快照`() {
        val repo = LockStateRepository(context)
        repo.hasAutoRotateSnapshot = true
        repo.lockedRotation = Rotations.PORTRAIT

        repo.clearSnapshot()

        assertFalse(repo.hasAutoRotateSnapshot)
        assertTrue("锁定意图不受影响", repo.isLocked)
    }

    /**
     * 健壮性：SharedPreferences 中若存在非法 mode 字符串（旧版本残留、手工改坏），
     * 不能因为 valueOf 抛 IllegalArgumentException 而让 App 启动即崩。
     */
    @Test
    fun `非法 mode 字符串不应崩溃`() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString("active_mode", "BROKEN_FROM_OLD_VERSION").commit()

        val repo = LockStateRepository(context)
        assertEquals(LockStateRepository.Mode.NONE, repo.activeMode)
    }
}
