package com.orientationlocker

import com.orientationlocker.core.RotationController
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RotationController 的入参守卫测试。
 *
 * 真正的 binder 调用（freezeRotation/thawRotation）需要 Shizuku 服务，
 * 无法在 JVM 上验证，只能靠真机。这里确保「非法输入不会走到反射」，
 * 避免把错误值透传给系统服务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RotationControllerTest {

    @Test
    fun `非法 rotation 被拒绝且不触碰系统服务`() {
        listOf(-5, 4, 99).forEach { bad ->
            val r = RotationController.freezeRotation(bad)
            assertTrue("rotation=$bad 应返回 Failure，实际 $r", r is RotationController.Result.Failure)
        }
    }

    @Test
    fun `Failure 携带可读原因`() {
        val r = RotationController.freezeRotation(42) as RotationController.Result.Failure
        assertTrue("原因应包含非法值提示", r.reason.contains("42"))
    }
}
