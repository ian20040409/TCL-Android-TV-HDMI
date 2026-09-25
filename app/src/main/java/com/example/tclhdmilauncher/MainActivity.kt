package com.example.tclhdmilauncher

import android.app.Activity
import android.content.Intent
import android.media.tv.TvContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * TCL HDMI 3 Launcher — 極簡零負載首頁啟動器
 *
 * 生命週期策略：
 *  onCreate → 嘗試切換 HDMI 3 → finish()
 *  若第一次切換失敗（冷開機底層 TvInput 未就緒），
 *  在 1.5 秒後非阻塞重試一次，之後仍立即 finish()。
 *
 * 平常不常駐記憶體，不消耗任何 CPU。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "TCLHdmiLauncher"

        /**
         * TCL TV 的 HDMI 3 Passthrough TvInput ID
         * 由 `adb shell dumpsys tv_input` 取得，Hardware ID = 1413744640
         */
        private const val HDMI3_INPUT_ID =
            "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744640"

        /** 冷開機時底層 TvInput 初始化延遲，實測建議 1~2 秒 */
        private const val COLD_BOOT_RETRY_DELAY_MS = 1500L

        /** 最多重試次數（第 0 次 = 首次嘗試，第 1 次 = 延遲重試） */
        private const val MAX_ATTEMPTS = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var attemptCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate — 嘗試切換至 HDMI 3")
        trySwitchToHdmi3()
    }

    /**
     * 建立並發送切換 HDMI 3 的 Intent。
     * 使用 [TvContract.buildChannelUriForPassthroughInput] 產生標準的
     * `content://android.media.tv/passthrough/…` URI，與 ADB 實測路徑完全一致。
     */
    private fun trySwitchToHdmi3() {
        attemptCount++
        Log.i(TAG, "切換嘗試 #$attemptCount：$HDMI3_INPUT_ID")

        try {
            val passthroughUri = TvContract.buildChannelUriForPassthroughInput(HDMI3_INPUT_ID)

            val intent = Intent(Intent.ACTION_VIEW, passthroughUri).apply {
                // 必須加 FLAG_ACTIVITY_NEW_TASK，因為 Launcher 本身在 Task 根部
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(intent)
            Log.i(TAG, "Intent 發送成功，準備 finish()")

        } catch (e: Exception) {
            // 冷開機時 TvInput 可能尚未就緒，ActivityNotFoundException 或其他例外
            Log.w(TAG, "切換失敗（嘗試 #$attemptCount）：${e.message}")

            if (attemptCount < MAX_ATTEMPTS) {
                Log.i(TAG, "排程 ${COLD_BOOT_RETRY_DELAY_MS}ms 後重試…")
                // 非阻塞延遲重試，不佔用主執行緒
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        trySwitchToHdmi3()
                        finishAndRemoveTask()
                    }
                }, COLD_BOOT_RETRY_DELAY_MS)
                // 先 return，等 Handler 回呼後再 finish
                return
            } else {
                Log.e(TAG, "已達最大重試次數，放棄切換")
            }
        }

        // 正常路徑或最終失敗路徑皆立即結束，不留駐後台
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 確保 handler 回呼不會在 Activity 銷毀後繼續執行
        handler.removeCallbacksAndMessages(null)
    }
}
