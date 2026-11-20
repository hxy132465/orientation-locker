package com.orientationlocker.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.orientationlocker.R
import com.orientationlocker.core.LockManager
import com.orientationlocker.core.Rotations
import java.util.concurrent.Executors

/**
 * 方向选择面板：由磁贴唤起，圆角浮层 + 逐行淡入 + 选中勾淡入。
 *
 * 选完立即执行并关闭，无任何常驻组件。点击后刻意延迟 ~90ms 再 finish，
 * 让按压回弹与勾选变化被眼睛捕捉到，而不是"点完就消失"。
 */
class OrientationPickerActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val manager by lazy { LockManager(applicationContext) }

    private lateinit var optionList: LinearLayout
    private var current = Rotations.AUTO
    private var locked = false
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_picker)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // onCreate 阶段窗口尚未 attach，直接 setLayout 会被忽略；等 decor 布局完成后再定宽。
        window?.decorView?.post {
            val width = (resources.displayMetrics.widthPixels * 0.9f).toInt()
            window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        optionList = findViewById(R.id.optionList)
        current = manager.currentRotation()
        locked = manager.isLocked()

        buildOptions()
        animateRowsIn()
    }

    private fun buildOptions() {
        addRow(
            iconRes = R.drawable.ic_orientation_portrait,
            labelRes = R.string.orientation_portrait,
            rotation = Rotations.PORTRAIT,
            selected = locked && current == Rotations.PORTRAIT
        )
        addRow(
            iconRes = R.drawable.ic_orientation_landscape,
            labelRes = R.string.orientation_landscape,
            rotation = Rotations.LANDSCAPE,
            selected = locked && current == Rotations.LANDSCAPE
        )
        addRow(
            iconRes = R.drawable.ic_orientation_portrait,
            labelRes = R.string.orientation_reverse_portrait,
            rotation = Rotations.REVERSE_PORTRAIT,
            selected = locked && current == Rotations.REVERSE_PORTRAIT,
            rotateIcon = true
        )
        addRow(
            iconRes = R.drawable.ic_orientation_landscape,
            labelRes = R.string.orientation_reverse_landscape,
            rotation = Rotations.REVERSE_LANDSCAPE,
            selected = locked && current == Rotations.REVERSE_LANDSCAPE,
            rotateIcon = true
        )

        addDivider()

        addRow(
            iconRes = R.drawable.ic_rotate,
            labelRes = R.string.orientation_auto,
            rotation = Rotations.AUTO,
            selected = !locked
        )
    }

    private fun addRow(
        iconRes: Int,
        labelRes: Int,
        rotation: Int,
        selected: Boolean,
        rotateIcon: Boolean = false
    ) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_picker_row, optionList, false)
        row.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageResource(iconRes)
            if (rotateIcon) this.rotation = 180f
        }
        row.findViewById<TextView>(R.id.rowLabel).setText(labelRes)
        row.findViewById<ImageView>(R.id.rowCheck).alpha = if (selected) 1f else 0f
        row.isSelected = selected
        row.pressable()

        row.setOnClickListener {
            if (closing) return@setOnClickListener
            markSelected(row)
            applyChoice(rotation, row)
        }
        optionList.addView(row)
    }

    /** 选中态切换 + 勾号淡入，给点击一个明确的落点。 */
    private fun markSelected(chosen: View) {
        for (i in 0 until optionList.childCount) {
            val child = optionList.getChildAt(i)
            val isChosen = child === chosen
            if (child is ViewGroup && child.findViewById<ImageView>(R.id.rowCheck) != null) {
                child.isSelected = isChosen
                child.findViewById<ImageView>(R.id.rowCheck)
                    .animate()
                    .alpha(if (isChosen) 1f else 0f)
                    .setDuration(140)
                    .start()
            }
        }
    }

    private fun addDivider() {
        val divider = View(this)
        divider.setBackgroundColor(getColor(R.color.divider))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        lp.setMargins(dp(16), dp(4), dp(16), dp(4))
        optionList.addView(divider, lp)
    }

    /** 逐行淡入上移，营造面板"展开"的层次。 */
    private fun animateRowsIn() {
        var index = 0
        for (i in 0 until optionList.childCount) {
            val child = optionList.getChildAt(i)
            if (child is ViewGroup && child.findViewById<ImageView>(R.id.rowCheck) == null) continue
            child.alpha = 0f
            child.translationY = dp(8).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setStartDelay(40L + index * 32L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            index++
        }
    }

    private fun applyChoice(rotation: Int, row: View) {
        closing = true
        row.animate().alpha(0.75f).setDuration(80).start()
        executor.execute {
            val outcome = if (rotation == Rotations.AUTO) manager.unlock() else manager.lock(rotation)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (outcome) {
                    is LockManager.Outcome.Success -> {
                        val msg = if (rotation == Rotations.AUTO) {
                            getString(R.string.toast_unlocked)
                        } else {
                            getString(R.string.toast_locked)
                        }
                        toast(msg)
                        row.postDelayed({ finish() }, 90)
                    }
                    is LockManager.Outcome.Unavailable -> {
                        toast(getString(R.string.toast_need_permission))
                        row.animate().alpha(1f).setDuration(120).start()
                        closing = false
                    }
                    is LockManager.Outcome.Failed -> {
                        toast(getString(R.string.toast_failed, outcome.reason))
                        row.animate().alpha(1f).setDuration(120).start()
                        closing = false
                    }
                }
            }
        }
    }

    private fun View.pressable(scale: Float = 0.98f) {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(80)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(170)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
            }
            false
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
