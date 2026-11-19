package com.orientationlocker.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.orientationlocker.core.LockManager
import com.orientationlocker.ui.OrientationPickerActivity

/**
 * 快速设置磁贴。点击后弹出方向选择面板（[OrientationPickerActivity]）。
 *
 * 低占用：本服务无状态、无后台线程，只有系统在面板展开时回调 onStartListening。
 */
class OrientationTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, OrientationPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 要求 PendingIntent + startActivityAndCollapse
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "无法打开方向选择面板", t)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val manager = LockManager(this)
        val locked = manager.isLocked()

        tile.state = if (locked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (locked) labelFor(manager.currentRotation()) else getString(com.orientationlocker.R.string.tile_label)
        tile.contentDescription = tile.label
        tile.updateTile()
    }

    private fun labelFor(rotation: Int): String = when (rotation) {
        0 -> getString(com.orientationlocker.R.string.orientation_portrait)
        1 -> getString(com.orientationlocker.R.string.orientation_landscape)
        2 -> getString(com.orientationlocker.R.string.orientation_reverse_portrait)
        3 -> getString(com.orientationlocker.R.string.orientation_reverse_landscape)
        else -> getString(com.orientationlocker.R.string.tile_label)
    }

    private companion object {
        const val TAG = "OrientationTile"
    }
}
