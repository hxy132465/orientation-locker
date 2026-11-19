package com.orientationlocker.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.orientationlocker.R
import com.orientationlocker.core.LockManager
import com.orientationlocker.core.Rotations
import java.util.concurrent.Executors

/**
 * 方向选择面板：由磁贴点击拉起，以对话框形式浮在桌面之上。
 * 选完立即执行并关闭，无任何常驻组件。
 */
class OrientationPickerActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val manager = LockManager(this)
        val current = manager.currentRotation()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = resources.getDrawable(R.drawable.bg_panel, theme)
        }

        val title = TextView(this).apply {
            text = getString(R.string.picker_title)
            textSize = 16f
            setTextColor(Color.parseColor("#1F2937"))
            setPadding(dp(16), dp(14), dp(16), dp(8))
            gravity = Gravity.START
        }
        root.addView(title)

        addOption(root, R.string.orientation_portrait, Rotations.PORTRAIT, current, manager)
        addOption(root, R.string.orientation_landscape, Rotations.LANDSCAPE, current, manager)
        addOption(root, R.string.orientation_reverse_portrait, Rotations.REVERSE_PORTRAIT, current, manager)
        addOption(root, R.string.orientation_reverse_landscape, Rotations.REVERSE_LANDSCAPE, current, manager)

        addDivider(root)
        addUnlockOption(root, manager)

        setContentView(root)
    }

    private fun addOption(
        parent: LinearLayout,
        labelRes: Int,
        rotation: Int,
        current: Int,
        manager: LockManager
    ) {
        val selected = manager.isLocked() && current == rotation
        val row = buildRow(getString(labelRes), selected)
        row.setOnClickListener {
            applyLock(rotation)
        }
        parent.addView(row)
    }

    private fun addUnlockOption(parent: LinearLayout, manager: LockManager) {
        val row = buildRow(getString(R.string.orientation_auto), !manager.isLocked())
        row.setOnClickListener {
            applyUnlock()
        }
        parent.addView(row)
    }

    private fun addDivider(parent: LinearLayout) {
        val divider = android.view.View(this)
        divider.setBackgroundColor(Color.parseColor("#E5E7EB"))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        lp.setMargins(dp(16), dp(4), dp(16), dp(4))
        parent.addView(divider, lp)
    }

    private fun buildRow(text: String, selected: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))

            val label = TextView(this@OrientationPickerActivity).apply {
                this.text = text
                textSize = 15f
                setTextColor(if (selected) Color.parseColor("#2563EB") else Color.parseColor("#111827"))
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (selected) {
                val check = TextView(this@OrientationPickerActivity).apply {
                    this.text = "✓"
                    textSize = 16f
                    setTextColor(Color.parseColor("#2563EB"))
                }
                addView(check)
            }
        }
    }

    private fun applyLock(rotation: Int) {
        executor.execute {
            val outcome = LockManager(this).lock(rotation)
            runOnUiThread { handleOutcome(outcome, locked = true) }
        }
    }

    private fun applyUnlock() {
        executor.execute {
            val outcome = LockManager(this).unlock()
            runOnUiThread { handleOutcome(outcome, locked = false) }
        }
    }

    private fun handleOutcome(outcome: LockManager.Outcome, locked: Boolean) {
        when (outcome) {
            is LockManager.Outcome.Success -> {
                toast(if (locked) getString(R.string.toast_locked) else getString(R.string.toast_unlocked))
                finish()
            }
            is LockManager.Outcome.Unavailable -> {
                toast(getString(R.string.toast_need_permission))
                finish()
            }
            is LockManager.Outcome.Failed -> {
                toast(getString(R.string.toast_failed, outcome.reason))
                finish()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
