package com.orientationlocker.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.orientationlocker.R
import com.orientationlocker.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主界面冒烟测试：确认去掉 AppCompat 后原生主题仍能正常 inflate，
 * 以及日间/夜间两套颜色资源都被正确解析。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainActivityTest {

    @Test
    fun `浅色模式：界面可创建且用浅色配色`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val allText = textsOf(activity.window.decorView)

        assertTrue("标题应存在", allText.contains(activity.getString(R.string.app_name)))
        assertTrue("应有快捷锁定分区", allText.contains(activity.getString(R.string.section_quick_lock)))
        assertEquals(0xFF0F172A.toInt(), activity.getColor(R.color.text_primary))
    }

    @Test
    @Config(qualifiers = "night")
    fun `夜间模式：界面可创建且切换到深色配色`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val allText = textsOf(activity.window.decorView)

        assertTrue("标题应存在", allText.contains(activity.getString(R.string.app_name)))
        assertEquals("夜间正文应为浅色", 0xFFF1F5F9.toInt(), activity.getColor(R.color.text_primary))
        assertEquals("夜间背景应为深色", 0xFF0A101C.toInt(), activity.getColor(R.color.surface))
    }

    private fun textsOf(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }
}
