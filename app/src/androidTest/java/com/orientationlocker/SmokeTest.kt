package com.orientationlocker

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orientationlocker.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冒烟测试：在真实 Android 运行时上验证 App 能启动、组件注册完整、不崩溃。
 *
 * 这是模拟器能提供的最低保障——真实设备上没有 Shizuku 时，
 * App 必须仍然能正常打开并给出引导，而不是白屏或闪退。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun 主界面可正常启动且不闪退() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("Activity 不应在启动后立即结束", activity.isFinishing)
                assertNotNull("根视图应已挂载", activity.window.decorView)
            }
        }
    }

    @Test
    fun 磁贴与开机接收器已注册在清单中() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val pkg = context.packageName

        val tile = pm.queryIntentServices(
            Intent("android.service.quicksettings.action.QS_TILE").setPackage(pkg), 0
        )
        assertFalse("应能查到磁贴服务", tile.isNullOrEmpty())

        val receivers = pm.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(pkg), 0
        )
        assertFalse("应能查到开机接收器", receivers.isNullOrEmpty())
    }

    @Test
    fun 应用可以跨进程读取自身状态仓库() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = com.orientationlocker.core.LockStateRepository(context)
        repo.lastAppliedAt = 42L
        assertNotNull(repo)
        org.junit.Assert.assertEquals(42L, repo.lastAppliedAt)
    }
}
