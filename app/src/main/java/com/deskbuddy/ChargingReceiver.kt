package com.deskbuddy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 充电连接时启动桌面服务，断开时停止。
 */
class ChargingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_BOOT_COMPLETED -> startService(context)
            Intent.ACTION_POWER_DISCONNECTED -> context.stopService(Intent(context, DeskService::class.java))
        }
    }

    private fun startService(context: Context) {
        // 开机广播时也要确认当前确实在充电
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (!charging) return

        // 充电广播属于后台启动前台服务的豁免场景，允许启动
        DeskService.start(context)
    }
}
