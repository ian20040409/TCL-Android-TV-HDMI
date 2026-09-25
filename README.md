# TCL Android TV HDMI 1 / 2 / 3 GUI Launcher

> 專為 TCL Android TV 設計的極簡、零負載 HDMI 訊號源切換器與 Launcher。  
> 提供美觀暗色電視大螢幕 GUI、右上角 TCL 原生設定快捷鍵 (`com.tcl.settings`)、高質感 HDMI 卡片與向量圖示，支援遙控器方向鍵焦點縮放、OK 鍵切換、長按設為開機預設，並內建 3 秒防誤觸倒數自動跳轉。

---

## 實體裝置訊號源對照表（實測驗證）

| 訊號源 | Port | Hardware ID | 完整 TvInput ID |
|---|---|---|---|
| **HDMI 1** | 1 | `1413744128` | `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744128` |
| **HDMI 2** | 2 | `1413744384` | `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744384` |
| **HDMI 3** | 3 | `1413744640` | `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744640` |

### ADB 測試各訊號源切換

```bash
# 切換 HDMI 1
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.tcl.tvinput%2F.passthroughinput.TvPassThroughService%2FHW1413744128"

# 切換 HDMI 2
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.tcl.tvinput%2F.passthroughinput.TvPassThroughService%2FHW1413744384"

# 切換 HDMI 3
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.tcl.tvinput%2F.passthroughinput.TvPassThroughService%2FHW1413744640"
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
adb shell cmd package set-home-activity com.lnu.tclhdmilauncher/.MainActivity

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
adb shell cmd package clear-preferred-activities com.lnu.tclhdmilauncher
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
| 依賴 | 0 依賴（100% Android SDK 原生呼叫） |
| 主題限制 | **強制 Pure Black Dark Mode**（純黑 `#000000`，禁用 ForceDark） |
| 按鈕樣式 | 原生 XML Rounded Corner Shape (20dp 圓角) |
| Release 體積 | R8 fullMode 混淆後僅約 **9.0 KB** |
| 記憶體開銷 | 進入立即響應，跳轉後呼叫 `finishAndRemoveTask()` 背景 0 常駐 |

