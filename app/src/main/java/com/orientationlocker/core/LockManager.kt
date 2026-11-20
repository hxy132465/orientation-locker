package com.orientationlocker.core

import android.content.Context
import com.orientationlocker.service.OverlayLockService
import com.orientationlocker.shizuku.ShizukuHelper

/**
 * 锁定/解锁的统一编排层。
 *
 * 职责：
 * 1. 按优先级选择生效路径：Shizuku → 悬浮窗接管 → 系统设置。
 * 2. 在首次锁定时快照用户的自动旋转开关，解锁时精确还原。
 * 3. 维护 [LockStateRepository] 中的「用户意图」。
 *
 * 路径选择遵循「代价从低到高」：Shizuku 完全不产生常驻组件；悬浮窗需要一个
 * 极薄的前台服务保活；系统设置最轻但压不住写死方向的 App。能用上一档就不
 * 落到下一档，保证有 Shizuku 的用户不为降级方案付任何内存代价。
 *
 * 说明：所有方法都应在后台线程调用（涉及 binder IPC / 窗口操作），UI 层负责切线程。
 */
class LockManager(context: Context) {

    private val appContext = context.applicationContext
    private val repo = LockStateRepository(appContext)

    sealed class Outcome {
        /** 成功，附带生效路径。 */
        data class Success(val mode: LockStateRepository.Mode) : Outcome()

        /** 没有任何可用路径。 */
        data class Unavailable(
            val needShizuku: Boolean,
            val needWriteSettings: Boolean,
            val needOverlay: Boolean = false
        ) : Outcome()

        /** 已生效路径调用失败。 */
        data class Failed(val reason: String) : Outcome()
    }

    /** 当前是否有可用路径。 */
    fun hasUsablePath(): Boolean =
        ShizukuHelper.isReady() ||
            OverlayController.canDraw(appContext) ||
            FallbackController.canWrite(appContext)

    /**
     * 锁定到指定方向。
     */
    fun lock(rotation: Int): Outcome {
        if (!Rotations.isValid(rotation)) return Outcome.Failed("非法方向值")

        // 1. 首次锁定时快照用户原始自动旋转开关
        //    （必须在真正写入前读取：降级路径会顺手关掉自动旋转）
        val tookSnapshot = !repo.hasAutoRotateSnapshot
        if (tookSnapshot) {
            repo.originalAutoRotate = FallbackController.readAutoRotate(appContext)
            repo.originalUserRotation = FallbackController.readUserRotation(appContext)
            repo.hasAutoRotateSnapshot = true
        }

        // 2. 依次尝试 Shizuku → 悬浮窗 → 系统设置
        val outcome = applyLock(rotation)

        // 3. 所有路径都失败时回滚快照：
        //    否则用户从未成功锁定，后续 unlock 却会拿一个"并不存在的原值"去改系统开关。
        if (outcome !is Outcome.Success && tookSnapshot) {
            repo.clearSnapshot()
        }
        return outcome
    }

    /** 按优先级尝试各条生效路径。 */
    private fun applyLock(rotation: Int): Outcome {
        // 1. Shizuku：系统级、不产生常驻组件，最省资源
        if (ShizukuHelper.isReady()) {
            when (val r = RotationController.freezeRotation(rotation)) {
                is RotationController.Result.Success -> {
                    // 关键一步：只有 freezeRotation 时，前台 Activity 若在 manifest 里
                    // 写死了 screenOrientation，WMS 会优先满足它，锁定被静默忽略
                    // （典型现象：锁了横屏，进某个 App 又变回竖屏）。
                    // 开启「忽略应用方向请求」后，display 的固定方向才具备最终决定权。
                    val ignore = RotationController.setIgnoreAppOrientation(true)
                    repo.ignoreAppOrientation = ignore is RotationController.Result.Success
                    repo.lockedRotation = rotation
                    repo.activeMode = LockStateRepository.Mode.SHIZUKU
                    repo.lastAppliedAt = System.currentTimeMillis()
                    return Outcome.Success(LockStateRepository.Mode.SHIZUKU)
                }
                is RotationController.Result.Failure -> {
                    // Shizuku 可用但调用失败 → 继续往下降级，不直接报错
                }
            }
        }

        // 2. 悬浮窗接管：无需 Shizuku，能压过写死方向的 App
        if (applyOverlay(rotation)) {
            return Outcome.Success(LockStateRepository.Mode.OVERLAY)
        }

        // 3. 系统设置：最轻，但压不住写死方向的 App
        if (applyFallback(rotation)) {
            return Outcome.Success(LockStateRepository.Mode.FALLBACK)
        }

        return Outcome.Unavailable(
            needShizuku = !ShizukuHelper.isBinderAlive(),
            needWriteSettings = !FallbackController.canWrite(appContext),
            needOverlay = !OverlayController.canDraw(appContext)
        )
    }

    /**
     * 解锁并恢复用户原始自动旋转状态。
     */
    fun unlock(): Outcome {
        val previousMode = repo.activeMode

        // 1. 先尝试解除 Shizuku 的旋转锁（无论上次走哪条路径都安全调用一次）
        if (ShizukuHelper.isReady()) {
            RotationController.thawRotation()
            // 恢复「尊重应用自身方向声明」，否则解锁后所有 App 仍被强行拉成锁定方向
            if (repo.ignoreAppOrientation) {
                RotationController.setIgnoreAppOrientation(false)
            }
        }

        // 2. 悬浮窗路径：移除窗口并停掉保活服务，让进程立刻回落到普通优先级
        if (previousMode == LockStateRepository.Mode.OVERLAY) {
            OverlayLockService.stop(appContext)
        }
        // 兜底：无论记录的模式是什么，都确保没有残留窗口
        OverlayController.detach()

        // 3. 若上次走的是降级/悬浮窗路径，需要把自动旋转开关还原
        if (previousMode == LockStateRepository.Mode.FALLBACK ||
            previousMode == LockStateRepository.Mode.OVERLAY ||
            repo.hasAutoRotateSnapshot
        ) {
            if (FallbackController.canWrite(appContext)) {
                // 先还原 USER_ROTATION，再开自动旋转：顺序反了会在过渡瞬间闪一下错误方向
                FallbackController.restoreUserRotation(appContext, repo.originalUserRotation)
                FallbackController.restoreAutoRotate(appContext, repo.originalAutoRotate)
            }
        }

        // 4. 清理意图与快照
        repo.clearLock()
        repo.clearSnapshot()
        return Outcome.Success(LockStateRepository.Mode.NONE)
    }

    /** 当前锁定状态。 */
    fun currentRotation(): Int = repo.lockedRotation

    fun isLocked(): Boolean = repo.isLocked

    fun activeMode(): LockStateRepository.Mode = repo.activeMode

    /** 当前是否已接管「应用自身方向声明」（仅 Shizuku 路径可能为 true）。 */
    fun ignoresAppOrientation(): Boolean = repo.ignoreAppOrientation

    /**
     * 开机/升级/回到前台后恢复：如果之前处于锁定状态且有可用路径，重新应用锁定。
     * @return true 表示已恢复
     */
    fun restoreIfNeeded(): Boolean {
        if (!repo.isLocked) return false
        val rotation = repo.lockedRotation
        if (!Rotations.isValid(rotation)) return false

        // Shizuku 可用时优先走系统级（不占常驻内存）
        if (ShizukuHelper.isReady()) {
            val r = RotationController.freezeRotation(rotation)
            if (r is RotationController.Result.Success) {
                val ignore = RotationController.setIgnoreAppOrientation(true)
                repo.ignoreAppOrientation = ignore is RotationController.Result.Success
                repo.activeMode = LockStateRepository.Mode.SHIZUKU
                repo.lastAppliedAt = System.currentTimeMillis()
                return true
            }
        }

        // 上次走悬浮窗路径：必须判断「窗口在不在」，不能只看系统设置是否已对——
        // 窗口随进程消失，而 settings 里的 USER_ROTATION 还在，光看设置会误判为已恢复。
        if (repo.activeMode == LockStateRepository.Mode.OVERLAY && OverlayController.canDraw(appContext)) {
            if (OverlayController.isAttached()) return true
            return applyOverlay(rotation)
        }

        // 系统设置路径：已在目标状态就不重复写，避免无谓写入与用户手动调整打架
        val alreadyApplied = FallbackController.readUserRotation(appContext) == rotation &&
            FallbackController.readAutoRotate(appContext) == 0
        if (alreadyApplied) return true

        if (OverlayController.canDraw(appContext) && applyOverlay(rotation)) return true
        if (FallbackController.canWrite(appContext)) {
            if (FallbackController.lock(appContext, rotation)) {
                repo.activeMode = LockStateRepository.Mode.FALLBACK
                repo.lockedRotation = rotation
                repo.lastAppliedAt = System.currentTimeMillis()
                return true
            }
        }
        return false
    }

    /**
     * 悬浮窗接管路径。
     *
     * 窗口同步添加（状态可立即确认），随后启动一个极薄的前台服务保活。
     * 若还能写系统设置，再补一次 USER_ROTATION：窗口只表达「用哪个方向」，
     * 横向的具体角度（90°/270°）仍由 USER_ROTATION 决定；同时关掉自动旋转
     * 可防止传感器把方向拉回去。
     */
    private fun applyOverlay(rotation: Int): Boolean {
        if (!OverlayController.canDraw(appContext)) return false
        if (!OverlayController.attach(appContext)) return false
        if (!OverlayController.apply(rotation)) {
            OverlayController.detach()
            return false
        }

        OverlayLockService.start(appContext, rotation)
        FallbackController.lock(appContext, rotation)

        repo.lockedRotation = rotation
        repo.activeMode = LockStateRepository.Mode.OVERLAY
        repo.ignoreAppOrientation = true
        repo.lastAppliedAt = System.currentTimeMillis()
        return true
    }

    private fun applyFallback(rotation: Int): Boolean {
        if (!FallbackController.canWrite(appContext)) return false
        val ok = FallbackController.lock(appContext, rotation)
        if (ok) {
            repo.lockedRotation = rotation
            repo.activeMode = LockStateRepository.Mode.FALLBACK
            repo.lastAppliedAt = System.currentTimeMillis()
        }
        return ok
    }
}
