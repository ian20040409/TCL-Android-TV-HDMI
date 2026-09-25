package com.lnu.tclhdmilauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 開機 / 睡眠喚醒時自動啟動 MainActivity。
 *
 * 覆蓋多種開機情境：
 *  - BOOT_COMPLETED          : 系統完整開機後
 *  - LOCKED_BOOT_COMPLETED   : Direct Boot 鎖定狀態下開機
 *  - QUICKBOOT_POWERON       : 部分 TCL / 高通平台快速開機路徑
 *  - com.htc.intent.action.QUICKBOOT_POWERON : 額外快開廣播
 *  - DREAMING_STOPPED        : 螢幕保護（待機）結束 → 等同 TV 從睡眠喚醒
 *
 * 零常駐：BroadcastReceiver 本身不佔記憶體，在收到廣播時短暫執行後轉交。
 */
class BootAndWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_DREAMING_STOPPED -> {
                TclHdmiApplication.wakeToLauncher(context)
            }
        }
    }
}
