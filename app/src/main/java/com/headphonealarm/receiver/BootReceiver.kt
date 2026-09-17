package com.headphonealarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.headphonealarm.AlarmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后重建全部闹钟（AlarmManager 中的闹钟不会跨重启保留）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in TRIGGER_ACTIONS) return
        val app = AlarmApp.from(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.alarmScheduler.rescheduleAll(app.alarmRepository)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val TRIGGER_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
