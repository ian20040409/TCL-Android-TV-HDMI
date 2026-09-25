package com.lnu.tclhdmilauncher

import android.content.Context

/**
 * dp → px 轉換工具（統一全 module 使用，消除各 Activity 重複實作）
 */
internal fun Context.dpToPx(dp: Float): Int =
    (dp * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.dpToPx(dp: Int): Int =
    (dp * resources.displayMetrics.density + 0.5f).toInt()
