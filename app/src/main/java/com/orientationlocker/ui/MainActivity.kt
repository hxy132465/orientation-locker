package com.orientationlocker.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import com.orientationlocker.R
import com.orientationlocker.core.FallbackController
import com.orientationlocker.core.LockManager
import com.orientationlocker.core.LockStateRepository
import com.orientationlocker.core.OverlayController
import com.orientationlocker.core.Rotations
import com.orientationlocker.shizuku.ShizukuHelper
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 主界面：状态卡 + 三条运行环境 + 2×2 方向网格。
 *
 * 视觉上刻意做减法：原生控件、单一强调色、圆角卡片、克制的动效。
 * 动效只做三件事——入场分层淡入、按压缩放、状态点呼吸；
 * 全部在 onPause 停掉，不在后台烧电。
 *
 * 线程模型：Shizuku ping、AppOps 查询、SharedPreferences 读取全部在
 * 单线程 executor 上完成，主线程只负责最后一次性渲染。
 */
class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val manager by lazy { LockManager(applicationContext) }

    private lateinit var statusCard: View
    private lateinit var statusDot: View
    private lateinit var statusHeadline: TextView
    private lateinit var statusDetail: TextView
    private lateinit var modeChip: TextView
    private lateinit var shizukuState: TextView
    private lateinit var shizukuAction: TextView
    private lateinit var overlayState: TextView
    private lateinit var overlayAction: TextView
    private lateinit var settingsState: TextView
    private lateinit var settingsAction: TextView
    private lateinit var unlockButton: TextView
    private lateinit var pageContent: View

    /** rotation → 方格。选中态靠 View.isSelected 驱动三套 selector。 */
    private val tiles = LinkedHashMap<Int, View>()

    private var pulse: ObjectAnimator? = null
    private var last: Status? = null

    /** 一次采集的完整状态，供主线程一次性渲染。 */
    private data class Status(
        val shizukuAlive: Boolean,
        val shizukuReady: Boolean,
        val shizukuVersion: String,
        val canWrite: Boolean,
        val canOverlay: Boolean,
        val locked: Boolean,
        val mode: LockStateRepository.Mode,
        val rotation: Int,
        val ignoresApp: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        animateEntrance()

        ShizukuHelper.addPermissionListener { requestCode, _ ->
            if (requestCode == ShizukuHelper.PERMISSION_REQUEST_CODE) {
                runOnUiThread { refreshStatus() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 入口侧兜底：开机广播在慢速冷启动场景下可能错过。
        submit {
            manager.restoreIfNeeded()
            val status = readStatus()
            runOnUiThread { render(status) }
        }
    }

    override fun onPause() {
        // 呼吸动效只在前台跑，避免无谓的后台绘制
        stopPulse()
        super.onPause()
    }

    private fun bindViews() {
        pageContent = findViewById(R.id.pageContent)
        statusCard = findViewById(R.id.statusCard)
        statusDot = findViewById(R.id.statusDot)
        statusHeadline = findViewById(R.id.statusHeadline)
        statusDetail = findViewById(R.id.statusDetail)
        modeChip = findViewById(R.id.modeChip)
        shizukuState = findViewById(R.id.shizukuState)
        shizukuAction = findViewById(R.id.shizukuAction)
        overlayState = findViewById(R.id.overlayState)
        overlayAction = findViewById(R.id.overlayAction)
        settingsState = findViewById(R.id.settingsState)
        settingsAction = findViewById(R.id.settingsAction)
        unlockButton = findViewById(R.id.unlockButton)

        tiles[Rotations.PORTRAIT] = findViewById(R.id.tilePortrait)
        tiles[Rotations.LANDSCAPE] = findViewById(R.id.tileLandscape)
        tiles[Rotations.REVERSE_PORTRAIT] = findViewById(R.id.tileReversePortrait)
        tiles[Rotations.REVERSE_LANDSCAPE] = findViewById(R.id.tileReverseLandscape)
    }

    private fun bindActions() {
        findViewById<View>(R.id.rowShizuku).apply {
            pressable()
            setOnClickListener { onShizukuClicked() }
        }
        findViewById<View>(R.id.rowOverlay).apply {
            pressable()
            setOnClickListener { openOverlaySettings() }
        }
        findViewById<View>(R.id.rowSettings).apply {
            pressable()
            setOnClickListener { openWriteSettings() }
        }
        tiles.forEach { (rotation, tile) ->
            tile.pressable(0.96f)
            tile.setOnClickListener { applyLock(rotation) }
        }
        unlockButton.pressable(0.98f)
        unlockButton.setOnClickListener { applyUnlock() }
    }

    // ---------- 动效 ----------

    /** 入场：状态卡与分组自上而下依次淡入。只跑一次，不阻塞首帧。 */
    private fun animateEntrance() {
        val blocks = listOf(
            statusCard,
            findViewById<View>(R.id.rowShizuku),
            findViewById<View>(R.id.rowOverlay),
            findViewById<View>(R.id.rowSettings),
            unlockButton
        )
        blocks.forEachIndexed { index, v ->
            v.alpha = 0f
            v.translationY = dp(14).toFloat()
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay(70L + index * 55L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        // 标题与说明直接呈现，避免整屏都在动
        pageContent.alpha = 1f
    }

    /** 按压反馈：轻微缩小 + 回弹。返回 false 以保证点击仍然触发。 */
    private fun View.pressable(scale: Float = 0.97f) {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(90)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(190)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
            }
            false
        }
    }

    /** 状态点呼吸：锁定时才闪，表示"正在生效"。 */
    private fun startPulse() {
        stopPulse()
        pulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.3f).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        statusDot.alpha = 1f
    }

    // ---------- 渲染 ----------

    private fun refreshStatus() {
        submit {
            val status = readStatus()
            runOnUiThread { render(status) }
        }
    }

    private fun readStatus(): Status {
        val shizuku = ShizukuHelper.snapshot()
        return Status(
            shizukuAlive = shizuku.alive,
            shizukuReady = shizuku.ready,
            shizukuVersion = shizuku.version,
            canWrite = FallbackController.canWrite(applicationContext),
            canOverlay = OverlayController.canDraw(applicationContext),
            locked = manager.isLocked(),
            mode = manager.activeMode(),
            rotation = manager.currentRotation(),
            ignoresApp = manager.ignoresAppOrientation()
        )
    }

    private fun render(s: Status) {
        if (isFinishing || isDestroyed) return
        val previous = last
        last = s

        renderStatusCard(s, previous)
        renderPermissions(s)
        renderTiles(s)
    }

    private fun renderStatusCard(s: Status, previous: Status?) {
        statusHeadline.text = if (s.locked) {
            getString(R.string.status_headline_locked, labelOf(s.rotation))
        } else {
            getString(R.string.status_headline_unlocked)
        }

        modeChip.visibility = if (s.locked) View.VISIBLE else View.GONE
        modeChip.text = when (s.mode) {
            LockStateRepository.Mode.SHIZUKU -> getString(R.string.chip_shizuku)
            LockStateRepository.Mode.OVERLAY -> getString(R.string.chip_overlay)
            LockStateRepository.Mode.FALLBACK -> getString(R.string.chip_fallback)
            LockStateRepository.Mode.NONE -> getString(R.string.chip_none)
        }

        statusDetail.text = when {
            !s.locked -> getString(R.string.status_detail_idle)
            s.ignoresApp -> getString(R.string.status_detail_cover)
            else -> getString(R.string.status_detail_limited)
        }

        statusDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (s.locked) R.color.success else R.color.neutral_dot)
        )
        if (s.locked) startPulse() else stopPulse()

        // 锁定状态翻转时给卡片一个很轻的"呼吸"回应
        if (previous != null && previous.locked != s.locked) {
            statusCard.animate().scaleX(1.012f).scaleY(1.012f)
                .setDuration(110)
                .withEndAction {
                    statusCard.animate().scaleX(1f).scaleY(1f)
                        .setDuration(170)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                }
                .start()
        }
    }

    private fun renderPermissions(s: Status) {
        shizukuState.text = when {
            !s.shizukuAlive -> getString(R.string.state_shizuku_off)
            s.shizukuReady -> getString(R.string.state_shizuku_ok)
            else -> getString(R.string.state_shizuku_no_perm)
        }
        shizukuAction.text = when {
            !s.shizukuAlive -> getString(R.string.action_install)
            !s.shizukuReady -> getString(R.string.action_grant)
            else -> getString(R.string.action_ready)
        }
        shizukuAction.isEnabled = s.shizukuAlive && !s.shizukuReady
        shizukuAction.alpha = if (shizukuAction.isEnabled) 1f else 0.55f

        overlayState.text = getString(
            if (s.canOverlay) R.string.state_overlay_ok else R.string.state_overlay_off
        )
        overlayAction.text = getString(
            if (s.canOverlay) R.string.state_granted else R.string.action_open
        )
        overlayAction.alpha = if (s.canOverlay) 0.55f else 1f

        settingsState.text = getString(
            if (s.canWrite) R.string.state_settings_ok else R.string.state_settings_off
        )
        settingsAction.text = getString(
            if (s.canWrite) R.string.state_granted else R.string.action_open
        )
        settingsAction.alpha = if (s.canWrite) 0.55f else 1f
    }

    private fun renderTiles(s: Status) {
        tiles.forEach { (rotation, tile) ->
            val selected = s.locked && s.rotation == rotation
            if (tile.isSelected != selected) {
                tile.isSelected = selected
                if (selected) {
                    tile.animate().scaleX(0.95f).scaleY(0.95f)
                        .setDuration(80)
                        .withEndAction {
                            tile.animate().scaleX(1f).scaleY(1f)
                                .setDuration(200)
                                .setInterpolator(OvershootInterpolator(1.8f))
                                .start()
                        }
                        .start()
                }
            }
        }
        unlockButton.alpha = if (s.locked) 1f else 0.45f
        unlockButton.isEnabled = s.locked
    }

    private fun labelOf(rotation: Int): String = when (rotation) {
        Rotations.PORTRAIT -> getString(R.string.orientation_portrait)
        Rotations.LANDSCAPE -> getString(R.string.orientation_landscape)
        Rotations.REVERSE_PORTRAIT -> getString(R.string.orientation_reverse_portrait)
        Rotations.REVERSE_LANDSCAPE -> getString(R.string.orientation_reverse_landscape)
        else -> getString(R.string.orientation_auto)
    }

    // ---------- 交互 ----------

    private fun onShizukuClicked() {
        if (!ShizukuHelper.isBinderAlive()) {
            val opened = try {
                startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                true
            } catch (t: Throwable) {
                false
            }
            if (!opened) {
                toast(getString(R.string.toast_shizuku_not_installed), Toast.LENGTH_LONG)
            }
            return
        }
        if (ShizukuHelper.canRequestPermission()) {
            ShizukuHelper.requestPermission()
        } else {
            toast(getString(R.string.toast_permission_pending), Toast.LENGTH_SHORT)
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
            toast(getString(R.string.toast_no_settings_page), Toast.LENGTH_SHORT)
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            toast(getString(R.string.toast_no_settings_page), Toast.LENGTH_SHORT)
        }
    }

    private fun applyLock(rotation: Int) {
        // 先给出即时反馈，避免等待跨进程调用期间界面"没反应"
        tiles.forEach { (r, tile) -> tile.isSelected = r == rotation }
        submit {
            val outcome = manager.lock(rotation)
            runOnUiThread {
                when (outcome) {
                    is LockManager.Outcome.Success -> {
                        toast(getString(R.string.toast_locked), Toast.LENGTH_SHORT)
                        refreshStatus()
                    }
                    is LockManager.Outcome.Unavailable -> {
                        toast(getString(R.string.toast_need_permission), Toast.LENGTH_LONG)
                        refreshStatus()
                    }
                    is LockManager.Outcome.Failed -> {
                        toast(getString(R.string.toast_failed, outcome.reason), Toast.LENGTH_SHORT)
                        refreshStatus()
                    }
                }
            }
        }
    }

    private fun applyUnlock() {
        submit {
            val outcome = manager.unlock()
            runOnUiThread {
                when (outcome) {
                    is LockManager.Outcome.Success -> {
                        toast(getString(R.string.toast_unlocked), Toast.LENGTH_SHORT)
                        refreshStatus()
                    }
                    is LockManager.Outcome.Failed -> {
                        toast(getString(R.string.toast_failed, outcome.reason), Toast.LENGTH_SHORT)
                        refreshStatus()
                    }
                    else -> refreshStatus()
                }
            }
        }
    }

    /**
     * 提交后台任务。不用 shutdownNow：中断正在执行的锁定流程会让窗口操作半途而废。
     */
    private fun submit(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (t: RejectedExecutionException) {
            // Activity 已销毁，忽略
        }
    }

    private fun toast(msg: String, duration: Int) =
        Toast.makeText(this, msg, duration).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        ShizukuHelper.removePermissionListener()
        stopPulse()
        executor.shutdown()
        super.onDestroy()
    }
}
