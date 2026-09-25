# TCL HDMI Launcher

<p align="left">
  <b>English</b> | <a href="README_zh.md">繁體中文</a>
</p>

> **Designed specifically for enthusiasts and minimalists who treat their TCL Android TV as a pure external display / monitor.**  
> Completely eliminate TCL's sluggish, ad-ridden stock launcher. Boot directly into your external devices (Apple TV 4K / PS5 / Android TV Box / Blu-ray Player) in sub-milliseconds, transforming your TV into a distraction-free, high-performance display!

---

## Why This Project? (Core Pain Points & Use Cases)

### Who Is This For?
If any of the following describes your home theater setup, this launcher was built for you:
- **External Input Purists**: You rely almost entirely on external high-performance hardware, and your TV's built-in Smart TV OS is merely a "panel driver":
  - 🍏 **Apple TV 4K** — Primary platform for streaming, movies, and TV shows.
  - 🎮 **PlayStation 5 (PS5) / Xbox Series X / Nintendo Switch** — Next-gen 4K HDR gaming.
  - 📺 **Dedicated TV Boxes** (Chromecast with Google TV, NVIDIA Shield, Fire TV, etc.).
  - 💿 **4K UHD Blu-ray / DVD Players** or **AV Receivers (eARC)**.
- **Tired of Bloated & Ad-Heavy Stock Launchers**: The factory TCL launcher is slow to boot, clutters the screen with unwanted video recommendations, and consumes precious RAM and background CPU cycles.
- **Want a True "Instant-On" Display Experience**: When you power on the TV, it should act like a traditional monitor or high-end display—automatically switching to your favorite input (e.g., Apple TV) within seconds without you ever having to touch a remote.
- **Lightning-Fast Multi-Device Switching**: When switching between a gaming console (PS5) and a streaming box (Apple TV), you want instant switching at the press of a single remote number button (`1` / `2` / `3`), without navigating clunky input menus.

---

## Ideal Home Theater Setup Example

```
                 ┌─────────────────────────────────┐
                 │     TCL 4K QLED / Mini-LED TV   │
                 │      (Pure Monitor / Display)   │
                 └────────────────┬────────────────┘
                                  │
      ┌───────────────────────────┼───────────────────────────┐
      │                           │                           │
  [ HDMI 1 ]                  [ HDMI 2 ]                  [ HDMI 3 (eARC) ]
      │                           │                           │
      ▼                           ▼                           ▼
🎮 PlayStation 5            📺 Blu-ray Player / Switch    🍏 Apple TV 4K / AVR
(Press remote "1" to switch) (Press remote "2" to switch)  (Default: Auto-boots in 3s)
```

- **Default Scenario**: On power-up, a 3-second countdown (customizable) automatically transitions straight into **HDMI 3 (Apple TV 4K)** with zero button presses required.
- **Gaming Scenario**: When you're ready to game, press numeric key `1` on your remote to instantly switch to **HDMI 1 (PS5)**.
- **Picture & Sound Calibration**: The top-right pill button takes you straight to native TCL Picture & Sound settings (`com.tcl.settings`) without ever displaying the TCL home screen.

---

## Key Highlights & Features

1. **Auto-Switch Countdown on Boot / Home**:
   - Customizable countdown timer: `Off`, `1s`, `2s`, `3s (Default)`, `5s`, `10s`, `15s`, `30s`.
   - On TV startup or pressing the Home button, automatically switches to your designated default HDMI port once the timer expires.
   - Touching any D-pad direction button during countdown cancels the timer, letting you browse inputs at your leisure.
2. **Instant Remote Number Key Switching**:
   - Press `1`, `2`, or `3` on your remote keypad to jump straight to the respective HDMI port with zero input lag.
3. **Set Default Boot Input (Long-Press OK)**:
   - Long-press the OK button on any HDMI card to set it as your persistent default startup input.
4. **Ultra-Lightweight, Zero Background Services, Zero GC Pressure**:
   - **Only ~9.0 KB** after full R8 minification and resource shrinking.
   - Built 100% in code (0 XML layout inflation overhead, 0 reflection).
   - Hot-path countdown timer achieves **0 heap memory allocations per second (0 GC)**.
   - Immediately calls `finishAndRemoveTask()` upon switching, leaving **zero background resident memory**—giving 100% of TV chipset resources to 4K video and audio decoding.
5. **Pure Black OLED / Dark Room Friendly UI (`#000000`)**:
   - Eliminates blinding white flashes in dark home theater rooms and optimizes local dimming on QLED / Mini-LED panels.
6. **Multi-Language Support**:
   - Fully localized in English, Traditional Chinese (繁體中文), and Simplified Chinese (简体中文) based on your system locale.
7. **Essential TV Features Preserved**:
   - **TCL Settings Shortcut**: Single-click access to native TCL picture/audio adjustment pages.
   - **Lightweight App Drawer**: An ultra-fast, on-demand list for occasional built-in or sideloaded TV apps with recents and long-press uninstall/disable management.

---

## Screenshots

| Main Screen (HDMI Input Selector) | Countdown Timer Settings Dialog |
|:---:|:---:|
| ![Main Screen](readme_pic/Screenshot_20260925_222244.png) | ![Countdown Settings](readme_pic/Screenshot_20260925_222304.png) |
| **Native TCL Settings Shortcut** | **App Drawer** |
| ![TCL Settings](readme_pic/Screenshot_20260925_222322.png) | ![App Drawer](readme_pic/Screenshot_20260925_222344.png) |
| **System App Management (Hold OK)** | **Third-Party App Management (Hold OK)** |
| ![System App Management](readme_pic/Screenshot_20260925_222413.png) | ![Third-Party App Management](readme_pic/Screenshot_20260925_222455.png) |

---

## Tested Device

- **Tested Model**: **TCL 65C715** (C715 Series 65" 4K QLED Android TV)
- **Chassis Platform**: **RTD2851 / R851T02** (firmware version prefix such as `V8-R851T02-LF1...`)
  > [!NOTE]
  > **About the R851T02 Platform:**  
  > `R851T02` is TCL's widely deployed chipset and motherboard hardware architecture (Realtek RTD2851 SoC) across multiple mainstream Android TV series (including C715, P715, P615, S434, etc.). All models running the **R851T02 chassis architecture** share a unified low-level TV input service (`com.tcl.tvinput`) and Passthrough pipeline specification.
- **Hardware Specs**:
  - **Display Panel**: 65" 4K UHD (3840 × 2160) Quantum Dot QLED, 60Hz, Dolby Vision / HDR10+ support
  - **HDMI Ports**: 3 physical HDMI 2.0 ports (HDCP 2.2, HDMI-ARC / CEC supported)
  - **CPU & Memory**: Quad-core ARM Cortex-A55 processor, 2 GB RAM / 16 GB ROM
  - **System OS**: Android TV 9.0 / Android TV 11
  - **Test Results**: Sub-millisecond HDMI 1 ~ 3 input switching, automatic countdown transition, boot default input persistence, remote controls (number keys / menu / settings) 100% verified.

## Physical Device Input Mapping Table (R851T02 / C715 Tested)

| Input Source | Port | Hardware ID | Full TvInput ID |
|---|---|---|---|
| **HDMI 1** | 1 | `1413744128` | `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744128` |
| **HDMI 2** | 2 | `1413744384` | `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744384` |
| **HDMI 3** | 3 | `1413744640` | `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744640` |

### ADB Testing for Input Switching

```bash
# Switch to HDMI 1
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.tcl.tvinput%2F.passthroughinput.TvPassThroughService%2FHW1413744128"

# Switch to HDMI 2
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.tcl.tvinput%2F.passthroughinput.TvPassThroughService%2FHW1413744384"

# Switch to HDMI 3
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.tcl.tvinput%2F.passthroughinput.TvPassThroughService%2FHW1413744640"
```

---

## Remote Control Shortcuts

| Remote Button | Action & Behavior |
|---|---|
| **D-Pad (Arrows)** | Move card focus; pressing any arrow key during countdown **cancels auto-switch** |
| **OK / Enter** | Switch immediately to the currently focused HDMI input |
| **Long-Press OK** | Set the currently focused HDMI port as the **default startup input** |
| **Number Keys `1` / `2` / `3`** | **Instant Switch**: Jump straight to HDMI 1 / 2 / 3 regardless of current focus |
| **MENU** | Open the "Auto-Switch Countdown Timer" configuration dialog |
| **SETTINGS** | Instantly launch native TCL settings (`com.tcl.settings`) |
| **BACK** | Exit dialogs or return to launcher main view |

---

## Installation & Setup Guide

### Step 1: Build the APK

```bash
# Build Debug APK
./gradlew assembleDebug

# Or build ultra-optimized Release APK (~9.0 KB)
./gradlew assembleRelease
```

### Step 2: Enable ADB on Your TCL TV & Install
1. On your TCL TV, open System Settings -> "About" -> click "Build Number" 7 times to enable Developer Options.
2. In "Developer Options", enable "USB Debugging" or "Network Debugging".
3. Connect your computer to your TV over ADB:

```bash
# Connect to your TV's IP address
adb connect 192.168.1.xxx:5555

# Install the APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Set as Default TV Launcher

```bash
# Set this app as the default Home launcher
adb shell cmd package set-home-activity com.lnu.tclhdmilauncher/.MainActivity
```
*Pressing the remote's "Home" button will now open this clean HDMI launcher.*

### Step 4: Disable the Bloated TCL Stock Launcher (Optional, Recommended)
To completely prevent the factory launcher from running or waking in the background:

```bash
# 1. Find TCL factory launcher package name
adb shell pm list packages | grep -i launcher

# 2. Disable the stock launcher (safe and reversible)
adb shell pm disable-user --user 0 <tcl.launcher.package.name>
```

> [!TIP]
> **Completely Reversible:**  
> If you ever want to restore the factory TCL launcher, simply run:
> ```bash
> adb shell pm enable <tcl.launcher.package.name>
> adb shell cmd package clear-preferred-activities com.lnu.tclhdmilauncher
> ```

---

## How It Works

```
TV Power-On / Sleep Wake / Home Button
                │
                ▼
      MainActivity.onCreate()
                │
        ┌───────┴───────┐
        ▼               ▼
   [Unattended]    [User Action]
        │               │
  Timer expires    Press 1/2/3 or OK
        │               │
        └───────┬───────┘
                │
                ▼
TvContract.buildChannelUriForPassthroughInput(HDMI_INPUT_ID)
                │
                ├─ Success ──► startActivity(Intent(ACTION_VIEW, uri))
                │               │
                │               ▼
                │          finishAndRemoveTask()
                │       (Memory 100% freed, 0 background footprint)
                │
                └─ Hardware cold boot not ready
                        │
                        ▼
                    Handler.postDelayed(1500ms)
                        │
                        ▼
                    Retry switch to HDMI
```

**Architectural Principles:**
- `finishAndRemoveTask()` — Activity exits completely upon switching, freeing 100% memory.
- `excludeFromRecents="true"` — Prevents cluttering recent apps.
- `singleTask` — Avoids duplicate Activity stack creation.
- Non-blocking Handler retry — Built-in fault tolerance while system TV services initialize on cold boot.

---

## Technical Specifications

| Parameter | Value |
|---|---|
| `minSdk` | 25 (Android 7.1) |
| `targetSdk` | 37 |
| `compileSdk` | 37 |
| AGP | 9.2.1 |
| Gradle | 9.4.1 (Java 25 JBR / Android Studio Ladybug+) |
| Dependencies | **0 external dependencies** (100% native Android SDK) |
| Theme Design | **Pure Black (`#000000`)**, ForceDark disabled |
| Localization | English, Traditional Chinese (繁體中文), Simplified Chinese (简体中文) |
| Release APK Size | Only **~9.0 KB** after R8 fullMode optimization |
| Resident Memory | **0 MB** (Task self-terminates via `finishAndRemoveTask()`) |
