package com.lnu.tclhdmilauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.ArrayMap
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.util.concurrent.Executors

/**
 * 原生極致輕量 App 清單啟動器
 * - 100% 純程式碼建構 View（消除 LayoutInflater 反射與 XML I/O）
 * - 移除 getInstalledApplications(GET_META_DATA) 重型 Binder IPC，直接由 queryIntentActivities 建構清單
 * - 快取 ComponentName：點擊啟動 App 時 0 PackageManager 查詢開銷
 * - 雙階段極速渲染：先載入文字清單（< 15ms 瞬間顯示），App 圖示依可視範圍背景非同步延遲載入（避免一次解碼 60+ 圖示造成 GC 卡頓）
 */
class AppListActivity : Activity() {

    companion object {
        private const val PREFS_RECENT = "app_list_recent"
        private const val KEY_RECENT_PKGS = "recent_pkgs"
        private const val RECENT_SEPARATOR = "|"
        private const val MAX_RECENT_COUNT = 8

        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_APP = 1
    }

    private sealed class ListItem {
        data class Section(val title: String) : ListItem()
        data class App(
            val label: String,
            val packageName: String,
            val componentName: ComponentName,
            val isLeanback: Boolean,
            val appInfo: ApplicationInfo,
            val isSystem: Boolean,
            val isDisableable: Boolean
        ) : ListItem()
    }

    private lateinit var listView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

    private val items = ArrayList<ListItem>(64)
    private val iconCache = ArrayMap<String, Drawable>(64)
    private val loadingIcons = HashSet<String>(32)
    private lateinit var adapter: AppListAdapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var isDestroyedFlag = false

    // Ordered list of recently launched package names (most recent first)
    private val recentPackages = ArrayDeque<String>(MAX_RECENT_COUNT)

    /** Load recent package list from SharedPreferences (background-safe). */
    private fun loadRecentPackages(): List<String> {
        val raw = getSharedPreferences(PREFS_RECENT, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_PKGS, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(RECENT_SEPARATOR).filter { it.isNotBlank() }
    }

    /** Push a package to the front of the recents list and persist it. */
    private fun saveRecentPackage(pkg: String) {
        recentPackages.remove(pkg)
        recentPackages.addFirst(pkg)
        while (recentPackages.size > MAX_RECENT_COUNT) recentPackages.removeLast()
        val serialized = recentPackages.joinToString(RECENT_SEPARATOR)
        getSharedPreferences(PREFS_RECENT, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT_PKGS, serialized).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        adapter = AppListAdapter()
        listView.adapter = adapter

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val item = items.getOrNull(pos)
            if (item is ListItem.App) launchApp(item)
        }

        listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val item = items.getOrNull(pos)
            if (item is ListItem.App) {
                showAppMenu(item)
                true
            } else {
                false
            }
        }

        loadApps()
    }

    override fun onDestroy() {
        isDestroyedFlag = true
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdownNow()
        iconCache.clear()
        loadingIcons.clear()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        listView.visibility = View.GONE
        tvEmpty.visibility = View.GONE

        // Read recents on the main thread (SharedPreferences is main-thread-safe)
        val savedRecents = loadRecentPackages()
        // Sync in-memory list from prefs (in case activity was recreated)
        recentPackages.clear()
        recentPackages.addAll(savedRecents)

        bgExecutor.execute {
            val pm = packageManager
            val selfPkg = packageName

            // 直接查詢 LEANBACK_LAUNCHER 與 LAUNCHER，省去 getInstalledApplications 全系統掃描 IPC
            val leanbackResolves = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
            )
            val mobileResolves = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )

            // 以 packageName 去重：優先保留 TV Leanback 入口
            val resolvedMap = ArrayMap<String, Pair<ResolveInfo, Boolean>>(64)
            for (ri in leanbackResolves) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg != selfPkg && !resolvedMap.containsKey(pkg)) {
                    resolvedMap[pkg] = Pair(ri, true)
                }
            }
            for (ri in mobileResolves) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg != selfPkg && !resolvedMap.containsKey(pkg)) {
                    resolvedMap[pkg] = Pair(ri, false)
                }
            }

            val tvUserApps = ArrayList<ListItem.App>(24)
            val mobileUserApps = ArrayList<ListItem.App>(16)
            val systemApps = ArrayList<ListItem.App>(32)
            // Map for quick recent-app lookup
            val pkgToApp = ArrayMap<String, ListItem.App>(resolvedMap.size)

            for (i in 0 until resolvedMap.size) {
                val (ri, isLeanback) = resolvedMap.valueAt(i)
                val actInfo = ri.activityInfo ?: continue
                val appInfo = actInfo.applicationInfo ?: continue
                val pkg = actInfo.packageName

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isPersistent = (appInfo.flags and ApplicationInfo.FLAG_PERSISTENT) != 0

                val label = try {
                    ri.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                        ?: pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg
                }

                val app = ListItem.App(
                    label = label,
                    packageName = pkg,
                    componentName = ComponentName(pkg, actInfo.name),
                    isLeanback = isLeanback,
                    appInfo = appInfo,
                    isSystem = isSystem,
                    isDisableable = isSystem && !isPersistent
                )

                pkgToApp[pkg] = app
                when {
                    isSystem -> systemApps.add(app)
                    isLeanback -> tvUserApps.add(app)
                    else -> mobileUserApps.add(app)
                }
            }

            val col = Collator.getInstance()
            val comp = Comparator<ListItem.App> { a, b -> col.compare(a.label, b.label) }
            tvUserApps.sortWith(comp)
            mobileUserApps.sortWith(comp)
            systemApps.sortWith(comp)

            // Build recent apps list (preserve recency order, skip stale packages)
            val recentApps = savedRecents.mapNotNull { pkgToApp[it] }

            val result = ArrayList<ListItem>(
                recentApps.size + tvUserApps.size + mobileUserApps.size + systemApps.size + 4
            )
            if (recentApps.isNotEmpty()) {
                result.add(ListItem.Section(getString(R.string.section_recent, recentApps.size)))
                result.addAll(recentApps)
            }
            if (tvUserApps.isNotEmpty()) {
                result.add(ListItem.Section(getString(R.string.section_tv_apps, tvUserApps.size)))
                result.addAll(tvUserApps)
            }
            if (mobileUserApps.isNotEmpty()) {
                result.add(ListItem.Section(getString(R.string.section_mobile_apps, mobileUserApps.size)))
                result.addAll(mobileUserApps)
            }
            if (systemApps.isNotEmpty()) {
                result.add(ListItem.Section(getString(R.string.section_system_apps, systemApps.size)))
                result.addAll(systemApps)
            }

            mainHandler.post {
                if (isDestroyedFlag || isFinishing) return@post
                items.clear()
                items.addAll(result)
                adapter.notifyDataSetChanged()

                progressBar.visibility = View.GONE
                if (items.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    listView.visibility = View.VISIBLE
                    listView.requestFocus()
                    val firstApp = items.indexOfFirst { it is ListItem.App }
                    if (firstApp >= 0) listView.setSelection(firstApp)
                }
            }
        }
    }


    private fun loadIconAsync(app: ListItem.App) {
        val pkg = app.packageName
        if (iconCache.containsKey(pkg) || !loadingIcons.add(pkg)) return

        bgExecutor.execute {
            if (isDestroyedFlag) return@execute
            val icon = try {
                packageManager.getApplicationIcon(app.appInfo)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (isDestroyedFlag || isFinishing) return@post
                if (icon != null) {
                    iconCache[pkg] = icon
                    updateVisibleRowIcon(pkg, icon)
                } else {
                    // 載入失敗時移除快取鍵，允許下次可見時重試
                    loadingIcons.remove(pkg)
                }
            }
        }
    }

    private fun updateVisibleRowIcon(pkg: String, icon: Drawable) {
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        for (pos in first..last) {
            val child = listView.getChildAt(pos - first) ?: continue
            val holder = child.tag as? AppViewHolder ?: continue
            if (holder.boundPackage == pkg) {
                holder.ivIcon.clearColorFilter()
                holder.ivIcon.setImageDrawable(icon)
            }
        }
    }

    private data class MenuOption(
        val title: String,
        val iconResId: Int,
        val iconTint: Int,
        val action: () -> Unit
    )

    private fun showAppMenu(app: ListItem.App) {
        val options = ArrayList<MenuOption>(4).apply {
            add(MenuOption(getString(R.string.menu_open), R.drawable.open_in_new_48px, 0xFF38BDF8.toInt()) {
                launchApp(app)
            })
            if (!app.isSystem) {
                add(MenuOption(getString(R.string.menu_uninstall), R.drawable.delete_48px, 0xFFF87171.toInt()) {
                    uninstallApp(app)
                })
            }
            if (app.isDisableable) {
                add(MenuOption(getString(R.string.menu_disable), R.drawable.settings_48px, 0xFFFBBF24.toInt()) {
                    disableApp(app)
                })
            }
            add(MenuOption(getString(R.string.menu_app_info), R.drawable.info_48px, 0xFF94A3B8.toInt()) {
                openAppInfo(app)
            })
        }

        val menuAdapter = object : BaseAdapter() {
            override fun getCount(): Int = options.size
            override fun getItem(position: Int): Any = options[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: LinearLayout
                val ivIcon: ImageView
                val tvText: TextView

                if (convertView == null) {
                    row = LinearLayout(this@AppListActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val hPad = dpToPx(20)
                        val vPad = dpToPx(14)
                        setPadding(hPad, vPad, hPad, vPad)
                    }
                    ivIcon = ImageView(this@AppListActivity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    row.addView(ivIcon, LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)).apply {
                        rightMargin = dpToPx(16)
                    })
                    tvText = TextView(this@AppListActivity).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        setTextColor(0xFFF1F5F9.toInt())
                    }
                    row.addView(tvText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                    row.tag = Pair(ivIcon, tvText)
                } else {
                    row = convertView as LinearLayout
                    @Suppress("UNCHECKED_CAST")
                    val tag = row.tag as Pair<ImageView, TextView>
                    ivIcon = tag.first
                    tvText = tag.second
                }

                val option = options[position]
                tvText.text = option.title
                ivIcon.setImageResource(option.iconResId)
                ivIcon.setColorFilter(option.iconTint)

                return row
            }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setAdapter(menuAdapter) { _, which ->
                options[which].action()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        // 從外部 App 返回時重新載入清單，確保「最近使用」區段即時更新
        loadApps()
    }

    override fun onStop() {
        super.onStop()
        // 進入背景時釋放圖示快取，確保外部 App 執行時 0 點陣圖常駐記憶體；
        // 且「不」呼叫 finish()，讓 AppListActivity 持續擋在 MainActivity 上方，
        // 確保外部 App 轉場或關閉時絕對不會誤喚醒底層的 MainActivity 計時器！
        iconCache.clear()
        loadingIcons.clear()
    }

    private fun launchApp(app: ListItem.App) {
        saveRecentPackage(app.packageName)
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(
                    if (app.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER
                    else Intent.CATEGORY_LAUNCHER
                )
                component = app.componentName
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent)
        } catch (_: Exception) {
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback != null) {
                try {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(fallback)
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.toast_cannot_launch, app.label), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_cannot_launch, app.label), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uninstallApp(app: ListItem.App) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
    }

    private fun disableApp(app: ListItem.App) {
        openAppInfo(app)
        Toast.makeText(this, getString(R.string.toast_disable_hint), Toast.LENGTH_LONG).show()
    }

    private fun openAppInfo(app: ListItem.App) {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${app.packageName}")
            )
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── 純程式碼建構介面（消除 XML LayoutInflater 與多餘背景重繪） ─────────────
    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        }

        // 頂部標題列
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ivTitleIcon = ImageView(this).apply {
            setImageResource(R.drawable.apps_48px)
            setColorFilter(0xFF60A5FA.toInt())
        }
        titleBox.addView(ivTitleIcon, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply {
            rightMargin = dp(12f)
        })
        val tvTitle = TextView(this).apply {
            text = getString(R.string.app_list_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
        }
        titleBox.addView(tvTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        header.addView(titleBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val tvHint = TextView(this).apply {
            text = getString(R.string.app_list_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF475569.toInt())
        }
        header.addView(tvHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 分隔線
        val dividerView = View(this).apply {
            setBackgroundColor(0xFF1E293B.toInt())
        }
        root.addView(dividerView, LinearLayout.LayoutParams(MATCH_PARENT, 1))

        // 載入中指示器
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(80f)
        })

        // 空清單提示
        tvEmpty = TextView(this).apply {
            text = getString(R.string.app_list_empty)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(tvEmpty, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(80f)
        })

        // App 清單 ListView
        listView = ListView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            divider = ColorDrawable(0xFF1E293B.toInt())
            dividerHeight = 1
            selector = createListSelector(density)
            isDrawSelectorOnTop = false
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        return root
    }

    private fun createListSelector(density: Float): Drawable {
        val strokeWidth = (2f * density + 0.5f).toInt()
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF1E3A5F.toInt())
            setStroke(strokeWidth, 0xFF2563EB.toInt())
        }
        val focusedPressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF1D4ED8.toInt())
            setStroke(strokeWidth, 0xFF93C5FD.toInt())
        }
        val pressed = ColorDrawable(0xFF1D4ED8.toInt())
        val transparent = ColorDrawable(Color.TRANSPARENT)

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused, android.R.attr.state_pressed),
                focusedPressed
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                focused
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                pressed
            )
            addState(intArrayOf(), transparent)
        }
    }

    inner class AppListAdapter : BaseAdapter() {

        override fun getCount() = items.size
        override fun getItem(pos: Int): Any? = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(pos: Int) =
            if (items[pos] is ListItem.Section) VIEW_TYPE_SECTION else VIEW_TYPE_APP

        override fun isEnabled(pos: Int) = items[pos] is ListItem.App

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View =
            when (val item = items[pos]) {
                is ListItem.Section -> getSectionView(item, convertView, parent)
                is ListItem.App -> getAppView(item, convertView)
            }

        private fun getSectionView(
            section: ListItem.Section,
            convertView: View?,
            parent: ViewGroup
        ): View {
            val tv = (convertView as? TextView) ?: TextView(parent.context).also { tv ->
                tv.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(8))
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                tv.setTextColor(0xFF64748B.toInt())
                tv.isAllCaps = true
                tv.letterSpacing = 0.08f
                tv.isFocusable = false
                tv.isClickable = false
            }
            tv.text = section.title
            return tv
        }

        private fun getAppView(app: ListItem.App, convertView: View?): View {
            val view: View
            val holder: AppViewHolder

            if (convertView == null || convertView.tag !is AppViewHolder) {
                holder = createAppRowViewHolder()
                view = holder.rootView
                view.tag = holder
            } else {
                view = convertView
                holder = convertView.tag as AppViewHolder
            }

            holder.bind(app)
            return view
        }
    }

    private fun createAppRowViewHolder(): AppViewHolder {
        val hPad = dpToPx(20)
        val iconSize = dpToPx(48)

        val row = LinearLayout(this).apply {
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, dpToPx(72))
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
            isFocusable = false
            isClickable = false
        }

        val ivIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(ivIcon, LinearLayout.LayoutParams(iconSize, iconSize))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), 0, 0, 0)
        }

        val tvLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(0xFFF1F5F9.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(tvLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val tvPkg = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF475569.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(tvPkg, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return AppViewHolder(row, ivIcon, tvLabel, tvPkg)
    }

    private inner class AppViewHolder(
        val rootView: View,
        val ivIcon: ImageView,
        val tvLabel: TextView,
        val tvPkg: TextView
    ) {
        var boundPackage: String = ""

        fun bind(app: ListItem.App) {
            boundPackage = app.packageName
            tvLabel.text = app.label
            tvPkg.text = app.packageName

            val cached = iconCache[app.packageName]
            if (cached != null) {
                ivIcon.clearColorFilter()
                ivIcon.setImageDrawable(cached)
            } else {
                ivIcon.setImageResource(R.drawable.apps_48px)
                ivIcon.setColorFilter(0xFF334155.toInt())
                loadIconAsync(app)
            }
        }
    }
}
