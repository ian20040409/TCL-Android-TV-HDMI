# ProGuard / R8 規則
# 此 App 無使用反射或動態載入，標準規則即可

# 保留 TvContract 相關類別（雖然是系統 API 但保險起見）
-keep class android.media.tv.** { *; }

# 保留 MainActivity（入口點）
-keep class com.example.tclhdmilauncher.MainActivity { *; }
