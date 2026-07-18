package com.hopengzhe.basketballliveyt

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35 起系統預設讓畫面延伸到系統列與瀏海底下，
 * 這裡把系統保留區（狀態列、導覽列、瀏海圓角）轉成根版面的內距，
 * 避免按鈕被手勢導覽列蓋住或被螢幕圓角裁掉。
 */
fun View.applySystemBarInsetsAsPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
