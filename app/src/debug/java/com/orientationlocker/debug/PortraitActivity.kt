package com.orientationlocker.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * 仅在 debug 构建中存在：manifest 里写死 screenOrientation，用来复现
 * "目标 App 自己声明方向 -> 用户态方向锁被忽略"的场景。
 */
class PortraitActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "PORTRAIT ACTIVITY\n(manifest: screenOrientation=portrait)"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7C3AED"))
        })
    }
}

class LandscapeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "LANDSCAPE ACTIVITY\n(manifest: screenOrientation=landscape)"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0F766E"))
        })
    }
}
