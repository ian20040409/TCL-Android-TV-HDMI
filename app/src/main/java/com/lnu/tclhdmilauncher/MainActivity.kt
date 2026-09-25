package com.lnu.tclhdmilauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvContract
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * TCL TV HDMI 1 / 2 / 3 原生極致輕量 Launcher
 * - 100% 純程式碼建構 UI：消除 LayoutInflater XML 解析與 Java 反射開銷（省去 ~400ms）
 * - 禁用 Autofill IPC 檢查：消除 TextView 初始化時的跨程序通訊延遲
 * - 保留單一輕量視窗上下文（0 背景計時器、0 點陣圖）：避免每次按 Home 鍵重建 GPU HardwareRenderer
 * - 原生 Android TV 沉浸式焦點縮放動畫與現代化卡片排版
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "TCLHdmiLauncher"
        private const val PREFS_NAME = "hdmi_prefs"
        private const val KEY_DEFAULT_PORT = "default_port"

        // TCL 實機硬體訊號源 ID (dumpsys tv_input)
        private const val HW_HDMI1 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744128"
        private const val HW_HDMI2 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744384"
        private const val HW_HDMI3 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744640"

        // 預先建構的通道 URI（避免每次切換重複字串解析）
        private val URI_HDMI1 = TvContract.buildChannelUriForPassthroughInput(HW_HDMI1)
        private val URI_HDMI2 = TvContract.buildChannelUriForPassthroughInput(HW_HDMI2)
        private val URI_HDMI3 = TvContract.buildChannelUriForPassthroughInput(HW_HDMI3)
    }

    private lateinit var tvCountdown: TextView
    private lateinit var cardHdmi1: LinearLayout
    private lateinit var cardHdmi2: LinearLayout
    private lateinit var cardHdmi3: LinearLayout
    private lateinit var tvBadge1: TextView
    private lateinit var tvBadge2: TextView
    private lateinit var tvBadge3: TextView
    private lateinit var ivIcon1: ImageView
    private lateinit var ivIcon2: ImageView
    private lateinit var ivIcon3: ImageView
    private lateinit var btnSettings: LinearLayout
    private lateinit var btnApps: LinearLayout

    private var defaultPort = 3
    private var isCancelled = false
    private var isActivityResumed = false
    private var secondsLeft = 3

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            // 嚴格確保：只有在主畫面 (MainActivity) 處於 Resumed 且擁有最上層視窗焦點時才允許計時與切換
            if (isDestroyed || isCancelled || isFinishing || !isActivityResumed || !hasWindowFocus()) return
            secondsLeft--
            if (secondsLeft > 0) {
                tvCountdown.text = "${secondsLeft} 秒後自動進入 HDMI $defaultPort（按方向鍵取消）"
                handler.postDelayed(this, 1000L)
            } else {
                switchTo(defaultPort, fromTimer = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 提前讀取預設 port，讓 buildContentView() 初始化時即可使用正確的 defaultPort
        defaultPort = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_PORT, 3)

        // 純程式碼建立 View 樹：0 XML I/O、0 反射、0 重複背景 Overdraw（windowBackground 已為純黑）
        setContentView(buildContentView())

        cardHdmi1.setOnClickListener { cancelTimer(); switchTo(1, fromTimer = false) }
        cardHdmi2.setOnClickListener { cancelTimer(); switchTo(2, fromTimer = false) }
        cardHdmi3.setOnClickListener { cancelTimer(); switchTo(3, fromTimer = false) }

        cardHdmi1.setOnLongClickListener { setDefault(1); true }
        cardHdmi2.setOnLongClickListener { setDefault(2); true }
        cardHdmi3.setOnLongClickListener { setDefault(3); true }

        btnSettings.setOnClickListener { launchTclSettings() }
        btnApps.setOnClickListener {
            // 離開主畫面進入 App List 時：立即暫停計時（保留剩餘秒數），待退出 App List 回到主畫面時繼續計時
            isCancelled = false
            pauseTimer()
            startActivity(Intent(this, AppListActivity::class.java))
        }

        updateButtonLabels()
        focusDefaultPortButton()
        secondsLeft = 3
        isCancelled = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 按下遙控器 Home 鍵（或開機/喚醒廣播）回到主畫面時，重置為 3 秒倒數
        defaultPort = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_PORT, 3)
        updateButtonLabels()
        focusDefaultPortButton()
        secondsLeft = 3
        isCancelled = false
        if (isActivityResumed && hasWindowFocus()) {
            resumeTimerIfOnMainScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        defaultPort = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_PORT, 3)
        updateButtonLabels()
        focusDefaultPortButton()

        // 只要回到主畫面（例如從 AppListActivity 按返回鍵退出），恢復計時狀態
        isCancelled = false
        if (secondsLeft <= 0) secondsLeft = 3
        tvCountdown.text = "${secondsLeft} 秒後自動進入 HDMI $defaultPort（按方向鍵取消）"
        if (hasWindowFocus()) {
            resumeTimerIfOnMainScreen()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isActivityResumed) {
            // 主畫面真正取得最上層視窗焦點時，開始/繼續倒數計時
            resumeTimerIfOnMainScreen()
        } else {
            // 只要失去主畫面視窗焦點（打開 App 清單、彈出對話框、切換至其他 App），立刻停止計時！
            pauseTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        pauseTimer()
    }

    override fun onStop() {
        super.onStop()
        pauseTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimer()
    }

    private fun focusDefaultPortButton() {
        when (defaultPort) {
            1 -> cardHdmi1
            2 -> cardHdmi2
            else -> cardHdmi3
        }.requestFocus()
    }

    private fun updateButtonLabels() {
        tvBadge1.visibility = if (defaultPort == 1) View.VISIBLE else View.INVISIBLE
        tvBadge2.visibility = if (defaultPort == 2) View.VISIBLE else View.INVISIBLE
        tvBadge3.visibility = if (defaultPort == 3) View.VISIBLE else View.INVISIBLE
        btnApps.nextFocusUpId = when (defaultPort) {
            1 -> cardHdmi1.id
            2 -> cardHdmi2.id
            else -> cardHdmi3.id
        }
    }

    private fun setDefault(port: Int) {
        defaultPort = port
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DEFAULT_PORT, port).apply()
        updateButtonLabels()
        cancelTimer()
        tvCountdown.text = "已將 HDMI $port 設為預設訊號源"
        Toast.makeText(this, "已設 HDMI $port 為預設", Toast.LENGTH_SHORT).show()
    }

    private fun launchTclSettings() {
        cancelTimer()
        val pm = packageManager
        val candidates = listOf(
            pm.getLeanbackLaunchIntentForPackage("com.tcl.settings"),
            pm.getLaunchIntentForPackage("com.tcl.settings"),
            Intent(Intent.ACTION_MAIN).apply {
                setClassName("com.tcl.settings", "com.tcl.settings.MainActivity")
            },
            Intent(Intent.ACTION_MAIN).apply {
                `package` = "com.tcl.settings"
            },
            Intent("android.settings.TV_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (candidate in candidates) {
            if (candidate == null) continue
            try {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(candidate)
                return
            } catch (_: Exception) {
                // 繼續嘗試下一個候選 Intent
            }
        }

        Toast.makeText(this, "無法開啟系統設定", Toast.LENGTH_SHORT).show()
    }

    private fun pauseTimer() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun resumeTimerIfOnMainScreen() {
        handler.removeCallbacks(tickRunnable)
        if (isCancelled || isFinishing || !isActivityResumed || !hasWindowFocus()) return
        if (secondsLeft <= 0) secondsLeft = 3
        tvCountdown.text = "${secondsLeft} 秒後自動進入 HDMI $defaultPort（按方向鍵取消）"
        handler.postDelayed(tickRunnable, 1000L)
    }

    private fun cancelTimer() {
        isCancelled = true
        handler.removeCallbacks(tickRunnable)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
            launchTclSettings()
            return true
        }
        if (!isCancelled) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cancelTimer()
                    tvCountdown.text = "請選擇訊號源（長按 OK 可設為預設）"
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchTo(port: Int, fromTimer: Boolean) {
        // 若是由倒數計時器觸發，再次確認當前必須位在主畫面最上層，否則絕不切換
        if (fromTimer && (!isActivityResumed || !hasWindowFocus() || isFinishing)) return

        val uri = when (port) {
            1 -> URI_HDMI1
            2 -> URI_HDMI2
            else -> URI_HDMI3
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "切換失敗: ${e.message}")
            Toast.makeText(this, "切換 HDMI $port 失敗", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 純程式碼建構極輕量 UI（取代 XML 反射與屬性解析） ─────────────────────────
    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val padH = dp(36f)
            val padV = dp(20f)
            setPadding(padH, padV, padH, padV)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        }

        // ── 1. 頂部狀態列（左側品牌/標題，右上角 com.tcl.settings 按鈕） ──────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 左側品牌指示器
        val brandBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ivBrand = ImageView(this).apply {
            val d = getDrawable(R.drawable.cable_48px)?.mutate()
            setImageDrawable(d)
            setColorFilter(0xFF64748B.toInt())
        }
        brandBox.addView(ivBrand, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
            rightMargin = dp(8f)
        })
        val tvBrand = TextView(this).apply {
            text = "TCL TV HDMI"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(0xFF64748B.toInt())
        }
        brandBox.addView(tvBrand, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        topBar.addView(brandBox, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // 彈性佔位，將設定按鈕推至最右側
        val spacerTop = View(this)
        topBar.addView(spacerTop, LinearLayout.LayoutParams(0, 0, 1f))

        // 右上角 com.tcl.settings 按鈕
        btnSettings = createPillButton(
            iconRes = R.drawable.settings_48px,
            label = "設定",
            density = density
        )
        topBar.addView(btnSettings, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        root.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ── 2. 中間核心區（垂直置中） ──────────────────────────────────────────
        val centerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }

        // 主標題
        val tvTitle = TextView(this).apply {
            text = "HDMI 訊號源切換"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
        }
        centerContainer.addView(tvTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        // 倒數與提示標籤（精緻膠囊外觀）
        tvCountdown = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF94A3B8.toInt())
            val hPad = dp(18f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(0xFF14161A.toInt())
                setStroke(dp(1f), 0xFF272A30.toInt())
            }
        }
        centerContainer.addView(tvCountdown, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            bottomMargin = dp(28f)
        })

        // HDMI 卡片群組（橫向排列）
        val rowCards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        val cardWidth = dp(210f)
        val cardHeight = dp(136f)
        val cardMargin = dp(14f)

        val (c1, iv1, b1) = createHdmiCard(1, density)
        val (c2, iv2, b2) = createHdmiCard(2, density)
        val (c3, iv3, b3) = createHdmiCard(3, density)

        cardHdmi1 = c1; ivIcon1 = iv1; tvBadge1 = b1
        cardHdmi2 = c2; ivIcon2 = iv2; tvBadge2 = b2
        cardHdmi3 = c3; ivIcon3 = iv3; tvBadge3 = b3

        for (card in arrayOf(cardHdmi1, cardHdmi2, cardHdmi3)) {
            rowCards.addView(card, LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                setMargins(cardMargin, 0, cardMargin, 0)
            })
        }
        centerContainer.addView(rowCards, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // 應用程式快捷按鈕
        btnApps = createPillButton(
            iconRes = R.drawable.apps_48px,
            label = "應用程式",
            density = density
        )
        centerContainer.addView(btnApps, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(26f)
        })

        root.addView(centerContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ── 3. 底部操作說明 ───────────────────────────────────────────────────
        val tvHint = TextView(this).apply {
            text = "[OK] 立即切換   •   [長按 OK] 設為預設   •   [方向鍵] 取消倒數"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF475569.toInt())
        }
        root.addView(tvHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(6f)
        })

        setupFocusNavigation()

        return root
    }

    private data class HdmiCardComponents(
        val card: LinearLayout,
        val ivIcon: ImageView,
        val tvBadge: TextView
    )

    private fun createHdmiCard(
        port: Int,
        density: Float
    ): HdmiCardComponents {
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val ivIcon = ImageView(this).apply {
            val d = getDrawable(R.drawable.settings_input_hdmi_24px)?.mutate()
            setImageDrawable(d)
            setColorFilter(0xFF94A3B8.toInt())
        }

        val tvTitle = TextView(this).apply {
            text = "HDMI $port"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val tvBadge = TextView(this).apply {
            text = "● 預設"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF38BDF8.toInt())
            visibility = View.INVISIBLE
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val vPad = dp(14f)
            val hPad = dp(16f)
            setPadding(hPad, vPad, hPad, vPad)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = createCardSelector(density)

            addView(ivIcon, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
                bottomMargin = dp(8f)
            })
            addView(tvTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(tvBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(4f)
            })

            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                    v.elevation = dp(8f).toFloat()
                    ivIcon.setColorFilter(Color.WHITE)
                    tvBadge.setTextColor(0xFFFEF08A.toInt())
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    v.elevation = 0f
                    ivIcon.setColorFilter(0xFF94A3B8.toInt())
                    tvBadge.setTextColor(0xFF38BDF8.toInt())
                }
            }
        }

        return HdmiCardComponents(card, ivIcon, tvBadge)
    }

    private fun createPillButton(
        iconRes: Int,
        label: String,
        density: Float
    ): LinearLayout {
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val iv = ImageView(this).apply {
            val d = getDrawable(iconRes)?.mutate()
            setImageDrawable(d)
            setColorFilter(0xFF94A3B8.toInt())
        }

        val tv = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFE2E8F0.toInt())
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hPad = dp(20f)
            val vPad = dp(10f)
            setPadding(hPad, vPad, hPad, vPad)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = createPillSelector(density)

            addView(iv, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
                rightMargin = dp(8f)
            })
            addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                    v.elevation = dp(6f).toFloat()
                    iv.setColorFilter(Color.WHITE)
                    tv.setTextColor(Color.WHITE)
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    v.elevation = 0f
                    iv.setColorFilter(0xFF94A3B8.toInt())
                    tv.setTextColor(0xFFE2E8F0.toInt())
                }
            }
        }
    }

    private fun setupFocusNavigation() {
        val idSettings = View.generateViewId()
        val idCard1 = View.generateViewId()
        val idCard2 = View.generateViewId()
        val idCard3 = View.generateViewId()
        val idApps = View.generateViewId()

        btnSettings.id = idSettings
        cardHdmi1.id = idCard1
        cardHdmi2.id = idCard2
        cardHdmi3.id = idCard3
        btnApps.id = idApps

        // btnSettings: 位於右上角
        btnSettings.nextFocusDownId = idCard3
        btnSettings.nextFocusLeftId = idCard2

        // cardHdmi1
        cardHdmi1.nextFocusUpId = idSettings
        cardHdmi1.nextFocusDownId = idApps
        cardHdmi1.nextFocusLeftId = idCard1
        cardHdmi1.nextFocusRightId = idCard2

        // cardHdmi2
        cardHdmi2.nextFocusUpId = idSettings
        cardHdmi2.nextFocusDownId = idApps
        cardHdmi2.nextFocusLeftId = idCard1
        cardHdmi2.nextFocusRightId = idCard3

        // cardHdmi3
        cardHdmi3.nextFocusUpId = idSettings
        cardHdmi3.nextFocusDownId = idApps
        cardHdmi3.nextFocusLeftId = idCard2
        cardHdmi3.nextFocusRightId = idCard3

        // btnApps: 位於卡片下方
        btnApps.nextFocusUpId = when (defaultPort) {
            1 -> idCard1
            2 -> idCard2
            else -> idCard3
        }
        btnApps.nextFocusDownId = idApps
        btnApps.nextFocusLeftId = idApps
        btnApps.nextFocusRightId = idApps
    }

    private fun createCardSelector(density: Float): Drawable {
        val radius = 18f * density
        val strokeFocused = (3f * density + 0.5f).toInt()
        val strokeNormal = (1.5f * density + 0.5f).toInt()

        fun rect(fillColor: Int, strokeWidth: Int, strokeColor: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rect(0xFF2563EB.toInt(), strokeFocused, 0xFF93C5FD.toInt())
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rect(0xFF1D4ED8.toInt(), strokeFocused, 0xFFBFDBFE.toInt())
            )
            addState(
                intArrayOf(),
                rect(0xFF14161A.toInt(), strokeNormal, 0xFF272A30.toInt())
            )
        }
    }

    private fun createPillSelector(density: Float): Drawable {
        val radius = 24f * density
        val strokeFocused = (2.5f * density + 0.5f).toInt()
        val strokeNormal = (1.5f * density + 0.5f).toInt()

        fun rect(fillColor: Int, strokeWidth: Int, strokeColor: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rect(0xFF2563EB.toInt(), strokeFocused, 0xFF93C5FD.toInt())
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rect(0xFF1D4ED8.toInt(), strokeFocused, 0xFFBFDBFE.toInt())
            )
            addState(
                intArrayOf(),
                rect(0xFF18181B.toInt(), strokeNormal, 0xFF2E2E33.toInt())
            )
        }
    }
}
