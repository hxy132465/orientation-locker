package com.orientationlocker.core

import android.content.Context
import com.orientationlocker.shizuku.ShizukuHelper

/**
 * 锁定/解锁的统一编排层。
 *
 * 职责：
 * 1. 选择生效路径：Shizuku 就绪 → freezeRotation；否则可写系统设置 → Fallback。
 * 2. 在首次锁定时快照用户的自动旋转开关，解锁时精确还原。
 * 3. 维护 [LockStateRepository] 中的「用户意图」。
 *
 * 说明：所有方法都应在后台线程调用（涉及 binder IPC），UI 层负责切线程。
 */
class LockManager(context: Context) {

    private val appContext = context.applicationContext
    private val repo = LockStateRepository(appContext)

    sealed class Outcome {
        /** 成功，附带生效路径。 */
        data class Success(val mode: LockStateRepository.Mode) : Outcome()

        /** Shizuku 不可用且降级也不可用。 */
        data class Unavailable(val needShizuku: Boolean, val needWriteSettings: Boolean) : Outcome()

        /** 已生效路径调用失败。 */
        data class Failed(val reason: String) : Outcome()
    }

    /** 当前是否有可用路径。 */
    fun hasUsablePath(): Boolean = ShizukuHelper.isReady() || FallbackController.canWrite(appContext)

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

        // 2. 依次尝试 Shizuku → 降级
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
        // 优先 Shizuku
        if (ShizukuHelper.isReady()) {
            when (val r = RotationController.freezeRotation(rotation)) {
                is RotationController.Result.Success -> {
                    repo.lockedRotation = rotation
                    repo.activeMode = LockStateRepository.Mode.SHIZUKU
                    repo.lastAppliedAt = System.currentTimeMillis()
                    return Outcome.Success(LockStateRepository.Mode.SHIZUKU)
                }
                is RotationController.Result.Failure -> {
                    // Shizuku 调用失败 → 尝试降级
                    if (applyFallback(rotation)) {
                        return Outcome.Success(LockStateRepository.Mode.FALLBACK)
                    }
                    return Outcome.Failed(r.reason)
                }
            }
        }

        // 降级到 WRITE_SETTINGS
        if (applyFallback(rotation)) {
            return Outcome.Success(LockStateRepository.Mode.FALLBACK)
        }

        return Outcome.Unavailable(
            needShizuku = !ShizukuHelper.isBinderAlive(),
            needWriteSettings = !FallbackController.canWrite(appContext)
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
        }

        // 2. 若上次走的是降级路径，需要把自动旋转开关还原
        if (previousMode == LockStateRepository.Mode.FALLBACK || repo.hasAutoRotateSnapshot) {
            if (FallbackController.canWrite(appContext)) {
                // 先还原 USER_ROTATION，再开自动旋转：顺序反了会在过渡瞬间闪一下错误方向
                FallbackController.restoreUserRotation(appContext, repo.originalUserRotation)
                FallbackController.restoreAutoRotate(appContext, repo.originalAutoRotate)
            }
        }

        // 3. 清理意图与快照
        repo.clearLock()
        repo.clearSnapshot()
        return Outcome.Success(LockStateRepository.Mode.NONE)
    }

    /** 当前锁定状态。 */
    fun currentRotation(): Int = repo.lockedRotation

    fun isLocked(): Boolean = repo.isLocked

    fun activeMode(): LockStateRepository.Mode = repo.activeMode

    /**
     * 开机/升级后恢复：如果之前处于锁定状态且有可用路径，重新应用锁定。
     * @return true 表示已恢复
     */
    fun restoreIfNeeded(): Boolean {
        if (!repo.isLocked) return false
        val rotation = repo.lockedRotation
        if (!Rotations.isValid(rotation)) return false

        // 已在目标状态就不重复写系统设置：MainActivity 每次 onResume 都会调这里，
        // 无谓写入既费电也会和用户手动调整打架。
        val alreadyApplied = FallbackController.readUserRotation(appContext) == rotation &&
            FallbackController.readAutoRotate(appContext) == 0
        if (alreadyApplied && !ShizukuHelper.isReady()) return true

        if (ShizukuHelper.isReady()) {
            val r = RotationController.freezeRotation(rotation)
            if (r is RotationController.Result.Success) {
                repo.activeMode = LockStateRepository.Mode.SHIZUKU
                repo.lastAppliedAt = System.currentTimeMillis()
                return true
            }
        }
        if (FallbackController.canWrite(appContext)) {
            if (FallbackController.lock(appContext, rotation)) {
                repo.activeMode = LockStateRepository.Mode.FALLBACK
                repo.lastAppliedAt = System.currentTimeMillis()
                return true
            }
        }
        return false
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
