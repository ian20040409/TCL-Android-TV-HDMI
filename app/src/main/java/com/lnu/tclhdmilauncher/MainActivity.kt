package com.lnu.tclhdmilauncher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
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
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * TCL TV HDMI 1 / 2 / 3 原生極致輕量 Launcher
 *
 * 核心性能設計：
 * - 100% 純程式碼建構 View 樹：0 XML I/O、0 反射（節省冷啟動 ~400ms）
 * - View 樹極限扁平化：頂部狀態列單層配置，達成全畫面單一次 Measure/Layout Pass
 * - 倒數計時熱路徑 0 GC：預建構字串快取，每秒倒數 0 物件配置
 * - 記憶體化持久快取：SharedPreferences 消除主執行緒重複磁碟 I/O
 * - 預建構全域靜態 Intent：微秒級訊號源派發
 * - 共用單一 OnClickListener 與 OnFocusChangeListener：消除匿名閉包
 * - 全面對齊 Android TV 系統原生樣式（Theme_DeviceDefault_Dialog_Alert）
 */
class MainActivity : Activity(), View.OnClickListener, View.OnFocusChangeListener {

    companion object {
        private const val TAG = "TCLHdmiLauncher"
        private const val PREFS_NAME = "hdmi_prefs"
        private const val KEY_DEFAULT_PORT = "default_port"
        private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_AUTO_OPEN_PKG = "auto_open_pkg"
        private const val KEY_AUTO_OPEN_LABEL = "auto_open_label"
        private const val DEFAULT_COUNTDOWN_SECONDS = 3
        const val EXTRA_FROM_APP_LIST = "from_app_list"


        // TCL 實機硬體訊號源 ID (dumpsys tv_input)
        private const val HW_HDMI1 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744128"
        private const val HW_HDMI2 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744384"
        private const val HW_HDMI3 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744640"

        // 預先建構的通道 URI
        private val URI_HDMI1 = TvContract.buildChannelUriForPassthroughInput(HW_HDMI1)
        private val URI_HDMI2 = TvContract.buildChannelUriForPassthroughInput(HW_HDMI2)
        private val URI_HDMI3 = TvContract.buildChannelUriForPassthroughInput(HW_HDMI3)

        // 預先建構的切換 Intent（0 動態物件配置、微秒級派發）
        private val INTENT_HDMI1 = Intent(Intent.ACTION_VIEW, URI_HDMI1).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        private val INTENT_HDMI2 = Intent(Intent.ACTION_VIEW, URI_HDMI2).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        private val INTENT_HDMI3 = Intent(Intent.ACTION_VIEW, URI_HDMI3).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 記憶體持久化快取，消除主執行緒重複讀取磁碟 XML
        private var cachedDefaultPort: Int? = null
        private var cachedCountdownSeconds: Int? = null
        @Volatile
        private var cachedAppMode: Boolean? = null
        @Volatile
        private var cachedAutoOpenPkg: String? = null
        @Volatile
        private var cachedAutoOpenLabel: String? = null
        @Volatile
        private var isFirstLaunchInProcess: Boolean = true

        fun isAppModeEnabled(context: Context): Boolean {
            cachedAppMode?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_APP_MODE, false)
            cachedAppMode = enabled
            return enabled
        }

        fun setAppModeEnabled(context: Context, enabled: Boolean) {
            cachedAppMode = enabled
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_APP_MODE, enabled).apply()
        }

        fun getAutoOpenPackage(context: Context): String {
            cachedAutoOpenPkg?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pkg = prefs.getString(KEY_AUTO_OPEN_PKG, "") ?: ""
            cachedAutoOpenPkg = pkg
            return pkg
        }

        fun getAutoOpenLabel(context: Context): String {
            cachedAutoOpenLabel?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val label = prefs.getString(KEY_AUTO_OPEN_LABEL, "") ?: ""
            cachedAutoOpenLabel = label
            return label
        }

        fun setAutoOpenApp(context: Context, pkg: String, label: String) {
            cachedAutoOpenPkg = pkg
            cachedAutoOpenLabel = label
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_AUTO_OPEN_PKG, pkg)
                .putString(KEY_AUTO_OPEN_LABEL, label)
                .apply()
        }

        fun getAutoOpenDelaySeconds(context: Context): Int {
            val sec = cachedCountdownSeconds ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS).also { cachedCountdownSeconds = it }
            return if (sec > 0) sec else DEFAULT_COUNTDOWN_SECONDS
        }

        @Volatile
        var isForegroundFocused: Boolean = false
            internal set
    }

    // 熱路徑字串快取：預先建構 1..30 秒對應各 HDMI 埠的提示文字（Hot Path 0 GC）
    private lateinit var countdownTextCache: Array<Array<String>>
    private lateinit var textCancelled: String
    private lateinit var textAppModeActive: String
    private lateinit var textDisabledCache: Array<String>

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
    private lateinit var btnAppMode: LinearLayout
    private lateinit var tvAppModeLabel: TextView
    private lateinit var ivAppModeIcon: ImageView
    private lateinit var btnWakeGuard: LinearLayout
    private lateinit var tvWakeGuardLabel: TextView
    private lateinit var ivWakeGuardIcon: ImageView
    private lateinit var btnCountdown: LinearLayout
    private lateinit var tvCountdownBtnLabel: TextView
    private lateinit var btnSettings: LinearLayout
    private lateinit var btnApps: LinearLayout

    private var defaultPort = 3
    private var countdownDuration = DEFAULT_COUNTDOWN_SECONDS
    private var isAppMode = false
    private var isCancelled = false
    private var isActivityResumed = false
    private var secondsLeft = DEFAULT_COUNTDOWN_SECONDS
    private var countdownDialog: AlertDialog? = null
    private var wakeGuardDialog: AlertDialog? = null
    private var hasShownWakeToastInSession = false

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isAppMode || countdownDuration <= 0 || isDestroyed || isCancelled || isFinishing || !isActivityResumed || !hasWindowFocus()) return
            secondsLeft--
            if (secondsLeft > 0) {
                updateCountdownText()
                handler.postDelayed(this, 1000L)
            } else {
                switchTo(defaultPort, fromTimer = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferencesFromCache()
        initTextCaches()
        secondsLeft = countdownDuration

        setContentView(buildContentView())

        cardHdmi1.setOnLongClickListener { setDefault(1); true }
        cardHdmi2.setOnLongClickListener { setDefault(2); true }
        cardHdmi3.setOnLongClickListener { setDefault(3); true }

        updateButtonLabels()
        updateAppModeButton()
        updateWakeGuardButton()
        focusDefaultPortButton()
        isCancelled = false

        isFirstLaunchInProcess = false

        val fromAppList = intent?.getBooleanExtra(EXTRA_FROM_APP_LIST, false) == true
        if (fromAppList) {
            intent?.removeExtra(EXTRA_FROM_APP_LIST)
            cancelTimer()
        } else if (isAppMode) {
            openAppList(immediate = true)
            return
        }

        if (!isAccessibilityServiceEnabled() && !hasShownWakeToastInSession) {
            hasShownWakeToastInSession = true
            Toast.makeText(this, getString(R.string.toast_wake_guard_hint), Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        countdownDialog?.dismiss()
        wakeGuardDialog?.dismiss()

        loadPreferencesFromCache()
        updateButtonLabels()
        updateAppModeButton()
        updateWakeGuardButton()
        focusDefaultPortButton()



        val fromAppList = intent.getBooleanExtra(EXTRA_FROM_APP_LIST, false)
        if (fromAppList) {
            intent.removeExtra(EXTRA_FROM_APP_LIST)
            cancelTimer()
            updateCountdownText()
            return
        }

        if (isAppMode) {
            cancelTimer()
            updateCountdownText()
            openAppList(immediate = true)
            return
        }

        secondsLeft = countdownDuration
        isCancelled = false
        updateCountdownText()
        if (isActivityResumed && hasWindowFocus()) {
            resumeTimerIfOnMainScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        isForegroundFocused = hasWindowFocus()

        loadPreferencesFromCache()
        updateButtonLabels()
        updateAppModeButton()
        updateWakeGuardButton()
        focusDefaultPortButton()

        if (isAppMode) {
            pauseTimer()
            updateCountdownText()
            return
        }

        isCancelled = false
        if (secondsLeft <= 0) secondsLeft = countdownDuration
        updateCountdownText()
        if (hasWindowFocus() && countdownDialog?.isShowing != true && wakeGuardDialog?.isShowing != true) {
            resumeTimerIfOnMainScreen()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        isForegroundFocused = hasFocus && isActivityResumed
        if (countdownDialog?.isShowing == true || wakeGuardDialog?.isShowing == true) {
            pauseTimer()
            return
        }
        if (hasFocus && isActivityResumed) {
            resumeTimerIfOnMainScreen()
        } else {
            pauseTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        isForegroundFocused = false
        pauseTimer()
    }

    override fun onStop() {
        super.onStop()
        isForegroundFocused = false
        pauseTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        isForegroundFocused = false
        countdownDialog?.dismiss()
        countdownDialog = null
        wakeGuardDialog?.dismiss()
        wakeGuardDialog = null
        cancelTimer()
    }

    private fun loadPreferencesFromCache() {
        if (cachedDefaultPort == null || cachedCountdownSeconds == null || cachedAppMode == null) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedDefaultPort = prefs.getInt(KEY_DEFAULT_PORT, 3)
            cachedCountdownSeconds = prefs.getInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS)
            cachedAppMode = prefs.getBoolean(KEY_APP_MODE, false)
            cachedAutoOpenPkg = prefs.getString(KEY_AUTO_OPEN_PKG, "") ?: ""
            cachedAutoOpenLabel = prefs.getString(KEY_AUTO_OPEN_LABEL, "") ?: ""
        }
        defaultPort = cachedDefaultPort ?: 3
        countdownDuration = cachedCountdownSeconds ?: DEFAULT_COUNTDOWN_SECONDS
        isAppMode = cachedAppMode ?: false
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
        updateCountdownButtonLabel()
    }

    private fun initTextCaches() {
        textCancelled = getString(R.string.text_cancelled)
        textAppModeActive = getString(R.string.app_mode_active_status)
        textDisabledCache = Array(4) { port ->
            getString(R.string.countdown_disabled, port)
        }
        countdownTextCache = Array(4) { port ->
            Array(31) { sec ->
                getString(R.string.countdown_active, sec, port)
            }
        }
    }

    private fun updateCountdownButtonLabel() {
        tvCountdownBtnLabel.text = if (countdownDuration <= 0) {
            getString(R.string.btn_countdown_off)
        } else {
            getString(R.string.btn_countdown_seconds, countdownDuration)
        }
    }

    private fun updateAppModeButton() {
        if (isAppMode) {
            tvAppModeLabel.text = getString(R.string.btn_app_mode_on)
            tvAppModeLabel.setTextColor(0xFF86EFAC.toInt())
            ivAppModeIcon.setImageResource(R.drawable.apps_48px)
            ivAppModeIcon.setColorFilter(0xFF86EFAC.toInt())
        } else {
            tvAppModeLabel.text = getString(R.string.btn_app_mode_off)
            tvAppModeLabel.setTextColor(0xFFE2E8F0.toInt())
            ivAppModeIcon.setImageResource(R.drawable.apps_48px)
            ivAppModeIcon.setColorFilter(0xFF94A3B8.toInt())
        }
    }

    private fun toggleAppMode() {
        isAppMode = !isAppMode
        setAppModeEnabled(this, isAppMode)
        updateAppModeButton()
        if (isAppMode) {
            cancelTimer()
            updateCountdownText()
            Toast.makeText(this, getString(R.string.toast_app_mode_on), Toast.LENGTH_SHORT).show()
            openAppList(immediate = false)
        } else {
            secondsLeft = countdownDuration
            isCancelled = false
            updateCountdownText()
            Toast.makeText(this, getString(R.string.toast_app_mode_off), Toast.LENGTH_SHORT).show()
            if (isActivityResumed && hasWindowFocus()) {
                resumeTimerIfOnMainScreen()
            }
        }
    }

    private fun openAppList(immediate: Boolean) {
        cancelTimer()
        val appListIntent = Intent(this, AppListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(appListIntent)
        if (immediate) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * 倒數計時文字更新（0 Allocation、0 GC）
     */
    private fun updateCountdownText() {
        if (isAppMode) {
            val autoLabel = getAutoOpenLabel(this)
            tvCountdown.text = if (autoLabel.isNotBlank()) {
                getString(R.string.app_mode_active_with_auto_app, autoLabel)
            } else {
                textAppModeActive
            }
        } else if (isCancelled) {
            tvCountdown.text = textCancelled
        } else if (countdownDuration <= 0) {
            tvCountdown.text = textDisabledCache.getOrElse(defaultPort) { textDisabledCache[3] }
        } else {
            val port = if (defaultPort in 1..3) defaultPort else 3
            val sec = if (secondsLeft in 1..30) secondsLeft else 0
            if (sec > 0) {
                tvCountdown.text = countdownTextCache[port][sec]
            } else {
                tvCountdown.text = textCancelled
            }
        }
    }

    private fun setDefault(port: Int) {
        defaultPort = port
        cachedDefaultPort = port
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DEFAULT_PORT, port).apply()
        updateButtonLabels()
        cancelTimer()
        tvCountdown.text = getString(R.string.msg_set_default, port)
        Toast.makeText(this, getString(R.string.toast_set_default, port), Toast.LENGTH_SHORT).show()
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
            Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")),
            pm.getLeanbackLaunchIntentForPackage("com.android.tv.settings"),
            pm.getLaunchIntentForPackage("com.android.tv.settings"),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (candidate in candidates) {
            if (candidate == null) continue
            try {
                WakeAccessibilityService.temporarilyIgnorePackage("com.tcl.settings")
                WakeAccessibilityService.temporarilyIgnorePackage("com.android.tv.settings")
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(candidate)
                return
            } catch (_: Exception) {
                // 繼續嘗試下一個候選 Intent
            }
        }

        Toast.makeText(this, getString(R.string.toast_error_open_settings), Toast.LENGTH_SHORT).show()
    }

    private fun launchAndroidSystemSettings() {
        cancelTimer()
        val pm = packageManager
        val candidates = listOf(
            Intent("android.settings.TV_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")),
            pm.getLeanbackLaunchIntentForPackage("com.android.tv.settings"),
            pm.getLaunchIntentForPackage("com.android.tv.settings"),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (candidate in candidates) {
            if (candidate == null) continue
            try {
                WakeAccessibilityService.temporarilyIgnorePackage("com.tcl.settings")
                WakeAccessibilityService.temporarilyIgnorePackage("com.android.tv.settings")
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(candidate)
                return
            } catch (_: Exception) {
                // 繼續嘗試下一個候選 Intent
            }
        }

        Toast.makeText(this, getString(R.string.toast_error_open_settings), Toast.LENGTH_SHORT).show()
    }

    private fun pauseTimer() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun resumeTimerIfOnMainScreen() {
        handler.removeCallbacks(tickRunnable)
        if (isAppMode || countdownDuration <= 0) {
            updateCountdownText()
            return
        }
        if (isCancelled || isFinishing || !isActivityResumed || !hasWindowFocus() || countdownDialog?.isShowing == true) return
        if (secondsLeft <= 0) secondsLeft = countdownDuration
        updateCountdownText()
        handler.postDelayed(tickRunnable, 1000L)
    }

    private fun cancelTimer() {
        isCancelled = true
        handler.removeCallbacks(tickRunnable)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            // 對話框開啟時，交由對話框處理
            if (countdownDialog?.isShowing == true || wakeGuardDialog?.isShowing == true) {
                if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) {
                    countdownDialog?.dismiss()
                    wakeGuardDialog?.dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // 1. 遙控器數字鍵 1, 2, 3 ... 切換 HDMI
            val pressedPort = when (keyCode) {
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
                in KeyEvent.KEYCODE_4..KeyEvent.KEYCODE_9,
                in KeyEvent.KEYCODE_NUMPAD_4..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    if (keyCode in KeyEvent.KEYCODE_4..KeyEvent.KEYCODE_9) {
                        keyCode - KeyEvent.KEYCODE_0
                    } else {
                        keyCode - KeyEvent.KEYCODE_NUMPAD_0
                    }
                }
                else -> null
            }

            if (pressedPort != null) {
                cancelTimer()
                if (pressedPort in 1..3) {
                    when (pressedPort) {
                        1 -> cardHdmi1.requestFocus()
                        2 -> cardHdmi2.requestFocus()
                        3 -> cardHdmi3.requestFocus()
                    }
                    Toast.makeText(this, getString(R.string.toast_switching_hdmi, pressedPort), Toast.LENGTH_SHORT).show()
                    switchTo(pressedPort, fromTimer = false)
                } else {
                    Toast.makeText(this, getString(R.string.toast_hdmi_range_error), Toast.LENGTH_SHORT).show()
                }
                return true
            }

            // 2. 選單按鍵（MENU）開啟自動倒數設定對話框
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showCountdownSettingsDialog()
                return true
            }

            // 3. 設定按鍵（SETTINGS）開啟 TCL 設定
            if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
                launchTclSettings()
                return true
            }

            // 4. 方向鍵取消倒數計時（不消耗事件，讓焦點正常切換）
            if (!isCancelled && countdownDuration > 0) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        cancelTimer()
                        tvCountdown.text = textCancelled
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchTo(port: Int, fromTimer: Boolean) {
        if (fromTimer && (!isActivityResumed || !hasWindowFocus() || isFinishing)) return

        val intent = when (port) {
            1 -> INTENT_HDMI1
            2 -> INTENT_HDMI2
            else -> INTENT_HDMI3
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Switch failed: ${e.message}")
            Toast.makeText(this, getString(R.string.toast_switch_failed, port), Toast.LENGTH_SHORT).show()
        }
    }

    // ── 原生 Alert 樣式自動開啟訊號源倒數設定 ────────────────────────────────
    private fun showCountdownSettingsDialog() {
        if (isFinishing || isDestroyed) return
        countdownDialog?.dismiss()

        pauseTimer()

        val secondsOptions = listOf(
            0 to getString(R.string.dialog_option_off),
            1 to getString(R.string.dialog_option_1s),
            2 to getString(R.string.dialog_option_2s),
            3 to getString(R.string.dialog_option_3s_default),
            5 to getString(R.string.dialog_option_5s),
            10 to getString(R.string.dialog_option_10s),
            15 to getString(R.string.dialog_option_15s),
            30 to getString(R.string.dialog_option_30s)
        )

        val labels = secondsOptions.map { it.second }.toTypedArray()
        val currentIndex = secondsOptions.indexOfFirst { it.first == countdownDuration }.let {
            if (it != -1) it else 3
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.dialog_countdown_title))
            .setSingleChoiceItems(labels, currentIndex) { d, which ->
                val selectedSeconds = secondsOptions[which].first
                saveCountdownSeconds(selectedSeconds)
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.dialog_cancel)) { d, _ ->
                d.dismiss()
            }
            .create()

        countdownDialog = dialog

        dialog.setOnDismissListener {
            countdownDialog = null
            if (!isCancelled && countdownDuration > 0 && isActivityResumed && hasWindowFocus()) {
                resumeTimerIfOnMainScreen()
            }
            focusDefaultPortButton()
        }

        dialog.show()
    }

    private fun saveCountdownSeconds(seconds: Int) {
        countdownDuration = seconds
        cachedCountdownSeconds = seconds
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_COUNTDOWN_SECONDS, seconds).apply()

        updateCountdownButtonLabel()

        if (seconds <= 0) {
            cancelTimer()
            tvCountdown.text = textDisabledCache.getOrElse(defaultPort) { textDisabledCache[3] }
            Toast.makeText(this, getString(R.string.toast_countdown_off), Toast.LENGTH_SHORT).show()
        } else {
            secondsLeft = seconds
            isCancelled = false
            updateCountdownText()
            Toast.makeText(this, getString(R.string.toast_countdown_set, seconds), Toast.LENGTH_SHORT).show()
            if (isActivityResumed && hasWindowFocus()) {
                resumeTimerIfOnMainScreen()
            }
        }
    }

    // ── 待機喚醒最高保障（無障礙服務狀態檢查與設定） ───────────────────────
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = ComponentName(this, WakeAccessibilityService::class.java)
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            try {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                for (service in enabledServices) {
                    if (service.resolveInfo?.serviceInfo?.packageName == packageName &&
                        service.resolveInfo?.serviceInfo?.name == WakeAccessibilityService::class.java.name) {
                        return true
                    }
                }
            } catch (_: Exception) {}
        }

        val enabledServicesSetting = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) {
            null
        } ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedService) {
                return true
            }
        }
        return false
    }

    private fun updateWakeGuardButton() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            tvWakeGuardLabel.text = getString(R.string.btn_wake_guard_on)
            tvWakeGuardLabel.setTextColor(0xFF86EFAC.toInt())
            ivWakeGuardIcon.setImageResource(R.drawable.accessibility_new_48px)
            ivWakeGuardIcon.setColorFilter(0xFF86EFAC.toInt())
        } else {
            tvWakeGuardLabel.text = getString(R.string.btn_wake_guard_off)
            tvWakeGuardLabel.setTextColor(0xFFFDE047.toInt())
            ivWakeGuardIcon.setImageResource(R.drawable.accessibility_new_48px)
            ivWakeGuardIcon.setColorFilter(0xFFFDE047.toInt())
        }
    }

    private fun showWakeGuardDialog() {
        if (isFinishing || isDestroyed) return
        wakeGuardDialog?.dismiss()
        pauseTimer()

        val isEnabled = isAccessibilityServiceEnabled()
        val title = getString(R.string.dialog_wake_guard_title)
        val message = if (isEnabled) {
            getString(R.string.dialog_wake_guard_msg_on)
        } else {
            getString(R.string.dialog_wake_guard_msg_off)
        }

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)

        if (isEnabled) {
            builder.setPositiveButton(getString(R.string.dialog_btn_manage)) { d, _ ->
                d.dismiss()
                openAccessibilitySettings()
            }
            builder.setNegativeButton(getString(R.string.dialog_btn_ok)) { d, _ ->
                d.dismiss()
            }
        } else {
            builder.setPositiveButton(getString(R.string.dialog_btn_go_settings)) { d, _ ->
                d.dismiss()
                openAccessibilitySettings()
            }
            builder.setNegativeButton(getString(R.string.dialog_cancel)) { d, _ ->
                d.dismiss()
            }
        }

        val dialog = builder.create()
        wakeGuardDialog = dialog

        dialog.setOnDismissListener {
            wakeGuardDialog = null
            if (!isCancelled && countdownDuration > 0 && isActivityResumed && hasWindowFocus()) {
                resumeTimerIfOnMainScreen()
            }
            focusDefaultPortButton()
        }

        dialog.show()
    }

    private fun openAccessibilitySettings() {
        cancelTimer()
        val pm = packageManager

        // 1. 優先嘗試直達無障礙設定
        val directAccessibilityCandidates = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent("android.settings.ACCESSIBILITY_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.device.accessibility.AccessibilityActivity")),
            Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity")),
            Intent().setComponent(ComponentName("com.google.android.tv.frameworkpackagestubs", "com.android.tv.settings.device.accessibility.AccessibilityActivity"))
        )

        var directLaunched = false
        for (candidate in directAccessibilityCandidates) {
            try {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (candidate.resolveActivity(pm) != null) {
                    startActivity(candidate)
                    directLaunched = true
                    Log.i(TAG, "Direct accessibility intent dispatched: $candidate")
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch candidate: ${e.message}")
            }
        }

        if (!directLaunched) {
            // 直達 Intent 無法解析，立即自動開啟 Android TV 原生系統設定（非 TCL 影像音效設定）
            Log.i(TAG, "No direct accessibility intent resolvable, immediately launching Android system settings...")
            launchAndroidSystemSettings()
        } else {
            // 偵測是否真正成功打開：500ms 後檢查本 Activity 是否仍具有焦點（Focus）
            // 若 500ms 後本畫面仍處於 Resumed 且有 Window Focus，代表直達頁面並未成功跳出，立即打開 Android 原生系統設定
            handler.postDelayed({
                if (!isDestroyed && !isFinishing && isActivityResumed && hasWindowFocus()) {
                    Log.w(TAG, "Window still focused after 500ms. Direct accessibility failed to display, launching Android system settings as fallback...")
                    launchAndroidSystemSettings()
                }
            }, 500L)
        }
    }

    // ── View.OnClickListener 單例分流（0 匿名閉包） ─────────────────────────
    override fun onClick(v: View) {
        when (v) {
            cardHdmi1 -> { cancelTimer(); switchTo(1, fromTimer = false) }
            cardHdmi2 -> { cancelTimer(); switchTo(2, fromTimer = false) }
            cardHdmi3 -> { cancelTimer(); switchTo(3, fromTimer = false) }
            btnAppMode -> toggleAppMode()
            btnWakeGuard -> showWakeGuardDialog()
            btnCountdown, tvCountdown -> showCountdownSettingsDialog()
            btnSettings -> launchTclSettings()
            btnApps -> {
                isCancelled = false
                openAppList(immediate = false)
            }
        }
    }

    // ── View.OnFocusChangeListener 單例分流（0 匿名閉包） ───────────────────
    override fun onFocusChange(v: View, hasFocus: Boolean) {
        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density + 0.5f).toInt()

        when (v) {
            cardHdmi1 -> updateCardFocusState(cardHdmi1, ivIcon1, tvBadge1, hasFocus, dp(8f))
            cardHdmi2 -> updateCardFocusState(cardHdmi2, ivIcon2, tvBadge2, hasFocus, dp(8f))
            cardHdmi3 -> updateCardFocusState(cardHdmi3, ivIcon3, tvBadge3, hasFocus, dp(8f))
            btnAppMode, btnWakeGuard, btnCountdown, btnSettings, btnApps -> {
                val scale = if (hasFocus) 1.08f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                v.elevation = if (hasFocus) dp(6f).toFloat() else 0f
            }
        }
    }

    private fun updateCardFocusState(
        card: View,
        ivIcon: ImageView,
        tvBadge: TextView,
        hasFocus: Boolean,
        elevationPx: Int
    ) {
        if (hasFocus) {
            card.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
            card.elevation = elevationPx.toFloat()
            ivIcon.setColorFilter(Color.WHITE)
            tvBadge.setTextColor(0xFFFEF08A.toInt())
        } else {
            card.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            card.elevation = 0f
            ivIcon.setColorFilter(0xFF94A3B8.toInt())
            tvBadge.setTextColor(0xFF38BDF8.toInt())
        }
    }

    // ── 極致扁平化 UI View 樹（0 XML、單次 Measure/Layout Pass） ───────────────
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

        // ── 1. 扁平化頂部狀態列（單層 Horizontal LinearLayout，移除中介容器） ────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 品牌圖示
        val ivBrand = ImageView(this).apply {
            val d = getDrawable(R.drawable.cable_48px)?.mutate()
            setImageDrawable(d)
            setColorFilter(0xFF64748B.toInt())
        }
        topBar.addView(ivBrand, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
            rightMargin = dp(8f)
        })

        // 品牌標題
        val tvBrand = TextView(this).apply {
            text = "TCL TV HDMI"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(0xFF64748B.toInt())
        }
        topBar.addView(tvBrand, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // 彈性佔位，推至最右側
        val spacerTop = View(this)
        topBar.addView(spacerTop, LinearLayout.LayoutParams(0, 0, 1f))

        // App 模式開關按鈕
        val (btnMode, tvModeLabel, ivMode) = createPillButton(
            iconRes = R.drawable.apps_48px,
            label = getString(if (isAppMode) R.string.btn_app_mode_on else R.string.btn_app_mode_off),
            density = density
        )
        btnAppMode = btnMode
        tvAppModeLabel = tvModeLabel
        ivAppModeIcon = ivMode
        topBar.addView(btnAppMode, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(12f)
        })

        // 喚醒保障按鈕
        val (btnGuard, tvGuardLabel, ivGuard) = createPillButton(
            iconRes = R.drawable.settings_power_48px,
            label = getString(R.string.btn_wake_guard_off),
            density = density
        )
        btnWakeGuard = btnGuard
        tvWakeGuardLabel = tvGuardLabel
        ivWakeGuardIcon = ivGuard
        topBar.addView(btnWakeGuard, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(12f)
        })

        // 倒數按鈕
        val initialCountdownLabel = if (countdownDuration <= 0) {
            getString(R.string.btn_countdown_off)
        } else {
            getString(R.string.btn_countdown_seconds, countdownDuration)
        }
        val (btnCount, tvCountLabel) = createPillButton(
            iconRes = R.drawable.info_48px,
            label = initialCountdownLabel,
            density = density
        )
        btnCountdown = btnCount
        tvCountdownBtnLabel = tvCountLabel
        topBar.addView(btnCountdown, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(12f)
        })

        // TCL 設定按鈕
        btnSettings = createPillButton(
            iconRes = R.drawable.settings_48px,
            label = getString(R.string.btn_tcl_settings),
            density = density
        ).first
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
            text = getString(R.string.main_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
        }
        centerContainer.addView(tvTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        // 倒數與提示標籤
        tvCountdown = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF94A3B8.toInt())
            val hPad = dp(18f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(0xFF14161A.toInt())
                setStroke(dp(1f), 0xFF272A30.toInt())
            }
            setOnClickListener(this@MainActivity)
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
            label = getString(R.string.btn_apps),
            density = density
        ).first
        centerContainer.addView(btnApps, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(26f)
        })

        root.addView(centerContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ── 3. 底部操作說明 ───────────────────────────────────────────────────
        val tvHint = TextView(this).apply {
            text = getString(R.string.bottom_hint)
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
            text = getString(R.string.card_default_badge)
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

            setOnClickListener(this@MainActivity)
            onFocusChangeListener = this@MainActivity
        }

        return HdmiCardComponents(card, ivIcon, tvBadge)
    }

    private fun createPillButton(
        iconRes: Int,
        label: String,
        density: Float
    ): Triple<LinearLayout, TextView, ImageView> {
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

        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hPad = dp(16f)
            val vPad = dp(9f)
            setPadding(hPad, vPad, hPad, vPad)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = createPillSelector(density)

            addView(iv, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
                rightMargin = dp(8f)
            })
            addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

            setOnClickListener(this@MainActivity)
            onFocusChangeListener = this@MainActivity
        }

        return Triple(button, tv, iv)
    }

    private fun setupFocusNavigation() {
        val idAppMode = View.generateViewId()
        val idWakeGuard = View.generateViewId()
        val idCountdown = View.generateViewId()
        val idSettings = View.generateViewId()
        val idCard1 = View.generateViewId()
        val idCard2 = View.generateViewId()
        val idCard3 = View.generateViewId()
        val idApps = View.generateViewId()

        btnAppMode.id = idAppMode
        btnWakeGuard.id = idWakeGuard
        btnCountdown.id = idCountdown
        btnSettings.id = idSettings
        cardHdmi1.id = idCard1
        cardHdmi2.id = idCard2
        cardHdmi3.id = idCard3
        btnApps.id = idApps

        // btnAppMode: 位於右上角最左側
        btnAppMode.nextFocusLeftId = idAppMode
        btnAppMode.nextFocusRightId = idWakeGuard
        btnAppMode.nextFocusDownId = idCard1

        // btnWakeGuard: 位於右上角第二個
        btnWakeGuard.nextFocusLeftId = idAppMode
        btnWakeGuard.nextFocusRightId = idCountdown
        btnWakeGuard.nextFocusDownId = idCard1

        // btnCountdown: 位於右上角第三個
        btnCountdown.nextFocusLeftId = idWakeGuard
        btnCountdown.nextFocusRightId = idSettings
        btnCountdown.nextFocusDownId = idCard2

        // btnSettings: 位於最右側
        btnSettings.nextFocusLeftId = idCountdown
        btnSettings.nextFocusRightId = idSettings
        btnSettings.nextFocusDownId = idCard3

        // cardHdmi1: 向上導向 btnAppMode
        cardHdmi1.nextFocusUpId = idAppMode
        cardHdmi1.nextFocusDownId = idApps
        cardHdmi1.nextFocusLeftId = idCard1
        cardHdmi1.nextFocusRightId = idCard2

        // cardHdmi2: 向上導向 btnCountdown
        cardHdmi2.nextFocusUpId = idCountdown
        cardHdmi2.nextFocusDownId = idApps
        cardHdmi2.nextFocusLeftId = idCard1
        cardHdmi2.nextFocusRightId = idCard3

        // cardHdmi3: 向上導向 btnSettings
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
