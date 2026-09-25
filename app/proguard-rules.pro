# ── ProGuard / R8 全模式極致優化規則 ──────────────────────────────────────────
# 專案 0 反射、0 動態類別加載，採用積極壓縮與死碼剔除

# 保留 TvContract 與 Launcher 核心組件
-keep class android.media.tv.** { *; }
-keep class com.lnu.tclhdmilauncher.MainActivity { *; }
-keep class com.lnu.tclhdmilauncher.AppListActivity { *; }
-keep class com.lnu.tclhdmilauncher.BootAndWakeReceiver { *; }

# 徹底剝離所有除錯元資訊，極致縮減 DEX 大小
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable,!Annotation,!EnclosingMethod,!InnerClasses,!Signature,!Exceptions

# 忽略並剔除無用的警告與註解資訊
-dontwarn kotlin.**
-dontnote **

# 剔除生產環境日誌呼叫，節省呼叫開銷與字串池
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
