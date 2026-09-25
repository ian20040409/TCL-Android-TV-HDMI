package com.lnu.tclhdmilauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 應用程式全域 Application
 *
 * 註冊全域動態 BroadcastReceiver 監聽螢幕點亮 (ACTION_SCREEN_ON) 與喚醒。
 * 當電視從待機睡眠模式 (STR / Suspend-to-RAM) 喚醒時，即使未開啟無障礙服務，
 * 只要本 App 程序存活於記憶體中，便能即時偵測螢幕喚醒並強制拉回 Launcher。
 */
class TclHdmiApplication : Application() {

    companion object {
        private const val TAG = "TclHdmiApp"
        @Volatile
        private var lastBootOrWakeTime = 0L

        /**
         * 強制啟動並拉回 Launcher 至最前景（依 App Mode 設定進入 AppListActivity 或 MainActivity）
         */
        fun wakeToLauncher(context: Context) {
            val homeIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
            try {
                context.startActivity(homeIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MainActivity: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "Screen ON / User present detected in Application, waking to Launcher...")
                    // 1. 立即喚醒拉回（標記為待機喚醒事件）
                    wakeToLauncher(context)

                    // 2. 針對 TCL 等電視底層 TV 輸入服務的延遲初始化，於 400ms 與 800ms 進行重試奪回
                    val retryAction = object : Runnable {
                        var retriesLeft = 2
                        override fun run() {
                            val isFocused = MainActivity.isForegroundFocused || AppListActivity.isForegroundFocused
                            if (!isFocused && retriesLeft > 0) {
                                retriesLeft--
                                Log.i(TAG, "Launcher not yet focused, re-asserting focus...")
                                wakeToLauncher(context)
                                handler.postDelayed(this, 400L)
                            }
                        }
                    }
                    handler.postDelayed(retryAction, 400L)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }
}
