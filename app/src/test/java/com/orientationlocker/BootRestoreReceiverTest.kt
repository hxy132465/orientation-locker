package com.orientationlocker

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.orientationlocker.boot.BootRestoreReceiver
import com.orientationlocker.core.LockStateRepository
import com.orientationlocker.core.Rotations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 开机接收器测试：重点是「不论什么状态都不能崩」。
 *
 * 开机广播时机很早，Shizuku 往往还没起来、权限也可能缺失，
 * 这里抛异常会直接变成 ANR/崩溃反馈，必须在任何分支都安全返回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BootRestoreReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("orientation_lock_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // 还原真实前提：WRITE_SETTINGS 未被用户授权（Robolectric 默认放行）
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        shadowOf(appOps).setMode(
            android.app.AppOpsManager.OPSTR_WRITE_SETTINGS,
            android.os.Process.myUid(),
            context.packageName,
            android.app.AppOpsManager.MODE_ERRORED
        )
    }

    @Test
    fun `未锁定时收到开机广播安全返回`() {
        BootRestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `已锁定但 Shizuku 不可用时安全返回且保留意图`() {
        val repo = LockStateRepository(context)
        repo.lockedRotation = Rotations.PORTRAIT
        repo.activeMode = LockStateRepository.Mode.SHIZUKU

        BootRestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val after = LockStateRepository(context)
        assertTrue("无路径时不应丢掉锁定意图", after.isLocked)
    }

    @Test
    fun `无关 action 直接忽略`() {
        BootRestoreReceiver().onReceive(context, Intent("android.intent.action.SOMETHING_ELSE"))
        assertFalse(LockStateRepository(context).isLocked)
    }

    @Test
    fun `包升级广播也能处理`() {
        BootRestoreReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
    }
}
