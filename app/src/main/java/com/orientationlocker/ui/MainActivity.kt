package com.orientationlocker.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.orientationlocker.R
import com.orientationlocker.core.FallbackController
import com.orientationlocker.core.LockManager
import com.orientationlocker.core.Rotations
import com.orientationlocker.shizuku.ShizukuHelper
import java.util.concurrent.Executors

/**
 * 主界面：展示权限/状态，提供激活引导与快捷锁定入口。
 * 纯原生控件，无第三方 UI 库，保持包体与内存最小。
 */
class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var shizukuButton: Button
    private lateinit var settingsButton: Button
    private lateinit var lockButtons: List<Pair<Int, Button>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ShizukuHelper.addPermissionListener { requestCode, grantResult ->
            if (requestCode == ShizukuHelper.PERMISSION_REQUEST_CODE) {
                runOnUiThread { refreshStatus() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        // 标题
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(Color.parseColor("#111827"))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.app_subtitle)
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(6), 0, dp(18))
        })

        // 状态卡
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#374151"))
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(statusText, matchWrap().apply { bottomMargin = dp(16) })

        // Shizuku 按钮
        shizukuButton = Button(this).apply {
            text = getString(R.string.action_shizuku)
            setOnClickListener { onShizukuClicked() }
        }
        root.addView(shizukuButton, matchWrap().apply { bottomMargin = dp(10) })

        // 设置权限按钮（降级）
        settingsButton = Button(this).apply {
            text = getString(R.string.action_write_settings)
            setOnClickListener { openWriteSettings() }
        }
        root.addView(settingsButton, matchWrap().apply { bottomMargin = dp(20) })

        // 快捷锁定
        root.addView(TextView(this).apply {
            text = getString(R.string.section_quick_lock)
            textSize = 14f
            setTextColor(Color.parseColor("#111827"))
            setPadding(0, 0, 0, dp(10))
        })

        val lockDefs = listOf(
            R.string.orientation_portrait to Rotations.PORTRAIT,
            R.string.orientation_landscape to Rotations.LANDSCAPE,
            R.string.orientation_reverse_portrait to Rotations.REVERSE_PORTRAIT,
            R.string.orientation_reverse_landscape to Rotations.REVERSE_LANDSCAPE
        )
        lockButtons = lockDefs.map { (labelRes, rotation) ->
            val btn = Button(this).apply {
                text = getString(labelRes)
                setOnClickListener { applyLock(rotation) }
            }
            root.addView(btn, matchWrap().apply { bottomMargin = dp(8) })
            rotation to btn
        }

        val unlockBtn = Button(this).apply {
            text = getString(R.string.action_unlock)
            setOnClickListener { applyUnlock() }
        }
        root.addView(unlockBtn, matchWrap().apply { topMargin = dp(4) })

        // 说明
        root.addView(TextView(this).apply {
            text = getString(R.string.hint_footer)
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, dp(22), 0, 0)
            gravity = Gravity.START
        })
    }

    private fun refreshStatus() {
        val shizukuReady = ShizukuHelper.isReady()
        val shizukuAlive = ShizukuHelper.isBinderAlive()
        val canWrite = FallbackController.canWrite(this)
        val manager = LockManager(this)

        val modeLabel = when {
            shizukuReady -> getString(R.string.mode_shizuku)
            canWrite -> getString(R.string.mode_fallback)
            else -> getString(R.string.mode_none)
        }
        val lockLabel = if (manager.isLocked()) {
            getString(R.string.status_locked, labelOf(manager.currentRotation()))
        } else {
            getString(R.string.status_unlocked)
        }

        statusText.text = buildString {
            append(getString(R.string.status_shizuku, if (shizukuAlive) "已连接" else "未连接", ShizukuHelper.serverVersion()))
            append("\n")
            append(getString(R.string.status_permission, if (shizukuReady) "已授权" else "未授权"))
            append("\n")
            append(getString(R.string.status_write_settings, if (canWrite) "已授予" else "未授予"))
            append("\n")
            append(getString(R.string.status_current, lockLabel))
            append("\n")
            append(getString(R.string.status_mode, modeLabel))
        }

        shizukuButton.text = when {
            !shizukuAlive -> getString(R.string.action_install_shizuku)
            !shizukuReady -> getString(R.string.action_request_shizuku)
            else -> getString(R.string.action_shizuku_ready)
        }
        shizukuButton.isEnabled = shizukuAlive && !shizukuReady
        settingsButton.visibility = if (canWrite) Button.GONE else Button.VISIBLE
    }

    private fun labelOf(rotation: Int): String = when (rotation) {
        0 -> getString(R.string.orientation_portrait)
        1 -> getString(R.string.orientation_landscape)
        2 -> getString(R.string.orientation_reverse_portrait)
        3 -> getString(R.string.orientation_reverse_landscape)
        else -> getString(R.string.orientation_auto)
    }

    private fun onShizukuClicked() {
        if (!ShizukuHelper.isBinderAlive()) {
            // 未安装/未运行 → 尝试打开 Shizuku App
            val opened = try {
                startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                true
            } catch (t: Throwable) {
                false
            }
            if (!opened) {
                Toast.makeText(this, getString(R.string.toast_shizuku_not_installed), Toast.LENGTH_LONG).show()
            }
            return
        }
        if (ShizukuHelper.canRequestPermission()) {
            ShizukuHelper.requestPermission()
        } else {
            Toast.makeText(this, getString(R.string.toast_permission_pending), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWriteSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.toast_no_settings_page), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyLock(rotation: Int) {
        executor.execute {
            val outcome = LockManager(this).lock(rotation)
            runOnUiThread {
                when (outcome) {
                    is LockManager.Outcome.Success -> {
                        toast(getString(R.string.toast_locked))
                        refreshStatus()
                    }
                    is LockManager.Outcome.Unavailable -> toast(getString(R.string.toast_need_permission))
                    is LockManager.Outcome.Failed -> toast(getString(R.string.toast_failed, outcome.reason))
                }
            }
        }
    }

    private fun applyUnlock() {
        executor.execute {
            val outcome = LockManager(this).unlock()
            runOnUiThread {
                when (outcome) {
                    is LockManager.Outcome.Success -> {
                        toast(getString(R.string.toast_unlocked))
                        refreshStatus()
                    }
                    is LockManager.Outcome.Failed -> toast(getString(R.string.toast_failed, outcome.reason))
                    else -> refreshStatus()
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        ShizukuHelper.removePermissionListener()
        executor.shutdownNow()
        super.onDestroy()
    }
}
