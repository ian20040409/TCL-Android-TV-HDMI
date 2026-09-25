plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace  = "com.lnu.tclhdmilauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lnu.tclhdmilauncher"
        minSdk        = 25
        targetSdk     = 36
        versionCode   = 7
        versionName   = "2.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled   = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ── 記憶體 / APK 體積極致最佳化 ───────────────────────────────────────────
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }
}

// 零外部依賴：完全依賴 Android 系統原生的 Activity, Intent, TvContract, Handler
// 不常駐記憶體、不消耗背景 CPU
dependencies { }
