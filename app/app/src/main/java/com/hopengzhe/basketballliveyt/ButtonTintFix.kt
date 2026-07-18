package com.hopengzhe.basketballliveyt

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton

/**
 * v0.8.2：不管是主題層級的 `buttonStyle` 覆寫（v0.7.8）還是逐一按鈕加上
 * `android:backgroundTint="@null"`（v0.7.9），Boss 實機測試都證實按鈕仍被染成橘色，
 * 代表問題比單純的 XML/主題屬性覆寫更頑固（可能是 `style="?android:attr/borderlessButtonStyle"`
 * 等個別按鈕自訂樣式又蓋回染色行為，或 AppCompat 的 tint 處理在 XML 屬性解析階段有其他優先序）。
 * 改在程式執行時期直接、強制清空每顆 Button／ImageButton 的 `backgroundTintList`，這一步發生在
 * 畫面顯示前、且不受任何 XML/主題/樣式鏈影響，是最後一道保證按鈕顯示原本設定顏色的手段。
 * ImageButton（例如設定按鈕）另外用 `app:tint` 控制圖示本身顏色，跟 `backgroundTintList`（背景底色）
 * 是不同屬性，這裡清除背景染色不會影響圖示顏色。
 */
fun View.clearAllButtonTints() {
    if (this is Button || this is ImageButton) {
        backgroundTintList = null
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).clearAllButtonTints()
        }
    }
}
