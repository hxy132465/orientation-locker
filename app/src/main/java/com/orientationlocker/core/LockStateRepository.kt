package com.orientationlocker.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 锁定状态仓库。只保存极少量标量，读写开销可忽略，符合"低占用"目标。
 *
 * 注意：这里只记录"用户意图"，真正的系统状态由 `IWindowManager.freezeRotation`
 * 或系统设置持有。因此即使进程被杀，锁定依然生效。
 */
class LockStateRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前锁定的方向；[Rotations.AUTO] 表示未锁定。 */
    var lockedRotation: Int
        get() = prefs.getInt(KEY_LOCKED_ROTATION, Rotations.AUTO)
        set(value) = prefs.edit().putInt(KEY_LOCKED_ROTATION, value).apply()

    /** 当前生效路径（决定解锁时用哪条通道恢复）。 */
    var activeMode: Mode
        get() = Mode.valueOf(
            prefs.getString(KEY_ACTIVE_MODE, Mode.NONE.name) ?: Mode.NONE.name
        )
        set(value) = prefs.edit().putString(KEY_ACTIVE_MODE, value.name).apply()

    /** 是否已经为当前锁定快照过用户的自动旋转开关（避免重复覆盖原值）。 */
    var hasAutoRotateSnapshot: Boolean
        get() = prefs.getBoolean(KEY_HAS_SNAPSHOT, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_SNAPSHOT, value).apply()

    /** 锁定发生前用户的自动旋转开关原值（1=开，0=关）。 */
    var originalAutoRotate: Int
        get() = prefs.getInt(KEY_ORIGINAL_AUTO_ROTATE, 1)
        set(value) = prefs.edit().putInt(KEY_ORIGINAL_AUTO_ROTATE, value).apply()

    /** 上次生效时间戳（仅用于诊断/展示）。 */
    var lastAppliedAt: Long
        get() = prefs.getLong(KEY_LAST_APPLIED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_APPLIED, value).apply()

    val isLocked: Boolean
        get() = Rotations.isLocked(lockedRotation)

    /** 清除锁定意图，但保留快照以便解锁时恢复。 */
    fun clearLock() {
        prefs.edit()
            .putInt(KEY_LOCKED_ROTATION, Rotations.AUTO)
            .putString(KEY_ACTIVE_MODE, Mode.NONE.name)
            .apply()
    }

    /** 完成解锁恢复后，彻底清理快照。 */
    fun clearSnapshot() {
        prefs.edit()
            .putBoolean(KEY_HAS_SNAPSHOT, false)
            .apply()
    }

    enum class Mode {
        /** 未锁定 */
        NONE,

        /** 通过 Shizuku 的 freezeRotation 生效 */
        SHIZUKU,

        /** 通过 WRITE_SETTINGS 降级生效 */
        FALLBACK
    }

    private companion object {
        const val PREFS_NAME = "orientation_lock_state"
        const val KEY_LOCKED_ROTATION = "locked_rotation"
        const val KEY_ACTIVE_MODE = "active_mode"
        const val KEY_HAS_SNAPSHOT = "has_auto_rotate_snapshot"
        const val KEY_ORIGINAL_AUTO_ROTATE = "original_auto_rotate"
        const val KEY_LAST_APPLIED = "last_applied_at"
    }
}
