package com.lnu.tclhdmilauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 開機 / 睡眠喚醒時自動啟動 MainActivity。
 *
 * 覆蓋三種情境：
 *  - BOOT_COMPLETED        : 系統完整開機後
 *  - QUICKBOOT_POWERON     : 部分 TCL / 高通平台快速開機路徑
 *  - DREAMING_STOPPED      : 螢幕保護（待機）結束 → 等同 TV 從睡眠喚醒
 *
 * 零服務、零常駐：BroadcastReceiver 本身不佔記憶體，僅在收到廣播時短暫執行。
 */
class BootAndWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_DREAMING_STOPPED -> {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                )
            }
        }
    }
}
