# TCL Android TV HDMI 3 Launcher

> 極簡、零負載的 Android TV 首頁啟動器。  
> 開機 / 按壓 Home 鍵時自動切換至 **HDMI 3**，切換後立即結束，不佔用任何常駐記憶體與 CPU。

---

## 專案結構

```
TCL Android TV HDMI/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/tclhdmilauncher/
│   │   │   └── MainActivity.kt
│   │   └── res/values/
│   │       └── strings.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── .gitignore
```

---

## 實體裝置資訊

| 欄位 | 值 |
|---|---|
| 設備 | TCL Android TV (Google TV / Android TV) |
| HDMI 3 Hardware ID | `1413744640` |
| HDMI Port | `hdmi_port=3` |
| 完整 TvInput ID | `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744640` |

### ADB 實測驗證指令

```bash
# 取得 TV Input 資訊
adb shell dumpsys tv_input

# 直接測試 HDMI 3 切換（驗證 Intent 是否正確）
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.tcl.tvinput%2F.passthroughinput.TvPassThroughService%2FHW1413744640"
```

**預期回應：**
```
Starting: Intent { act=android.intent.action.VIEW dat=content://android.media.tv/passthrough/... }
Warning: Activity not started, intent has been delivered to currently running top-most instance.
```

---

## 建置與部署

### 1. 建置 Debug APK

```bash
./gradlew assembleDebug
```

APK 輸出路徑：`app/build/outputs/apk/debug/app-debug.apk`

### 2. 安裝至 TCL TV

```bash
# 確認裝置已連線（USB 或 Wi-Fi ADB）
adb devices

# 安裝 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 設為預設 TV Launcher

```bash
# 將本 App 設為預設 Launcher（Home）
adb shell cmd package set-home-activity com.example.tclhdmilauncher/.MainActivity

# 或透過偏好設定選取器觸發（部分 TV 需要）
adb shell am start \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME
```

### 4. 停用 TCL 原生 Launcher（謹慎使用）

```bash
# 查詢 TCL 內建 Launcher 的套件名稱
adb shell pm list packages | grep -i launcher

# 停用（非解除安裝，可還原）
adb shell pm disable-user --user 0 <tcl.launcher.package.name>
```

### 5. 還原原生 Launcher

```bash
# 重新啟用 TCL 原生 Launcher
adb shell pm enable <tcl.launcher.package.name>

# 清除預設 Launcher 設定，讓系統再次詢問
adb shell cmd package clear-preferred-activities com.example.tclhdmilauncher
```

---

## 運作原理

```
開機 / Home 鍵
      │
      ▼
MainActivity.onCreate()
      │
      ▼
TvContract.buildChannelUriForPassthroughInput(HDMI3_INPUT_ID)
      │
      ├─ 成功 ──► startActivity(Intent(ACTION_VIEW, uri)) ──► finishAndRemoveTask()
      │
      └─ 失敗（冷開機底層未就緒）
            │
            ▼
        Handler.postDelayed(1500ms)
            │
            ▼
        重試切換 ──► finishAndRemoveTask()
```

**關鍵設計原則：**
- `Theme.NoDisplay` — 無畫面主題，避免黑色閃爍
- `finishAndRemoveTask()` — 切換後徹底移除 Task，不留後台痕跡
- `excludeFromRecents="true"` — 不出現在最近使用清單
- `singleTask` — 防止重複堆疊 Activity 實例
- Handler 非阻塞重試 — 冷開機容錯，不卡主執行緒

---

## 技術規格

| 項目 | 值 |
|---|---|
| `minSdk` | 23 (Android 6.0) |
| `targetSdk` | 35 |
| `compileSdk` | 35 |
| AGP | 9.2.1 |
| Gradle | 9.4.1 (相容 Android Studio 2026.1 / Java 25 JBR) |
| 依賴 | 0 依賴（純 Android SDK 原生呼叫） |
| 無 Compose | ✅ |
| 無 Leanback UI | ✅ |
| Release 混淆 | R8 fullMode + 資源瘦身 ✅ (APK 僅約 4.4KB) |

