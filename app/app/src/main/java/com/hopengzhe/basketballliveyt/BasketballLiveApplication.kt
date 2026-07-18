package com.hopengzhe.basketballliveyt

import android.app.Application

/**
 * v0.12.2：自訂 Application——比任何 Activity 都更早啟動，用來盡早掛上全域當機記錄
 * （見 [CrashLogger.install]），確保不管當機發生在哪個畫面（直播中／設定頁／登入頁）都攔得到。
 * 除此之外不持有任何直播相關狀態，相機／推流／計分板生命週期仍完全由 [LiveActivity] 管理。
 */
class BasketballLiveApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // v0.15.5：清掉已移除功能殘留在 SharedPreferences 的死鍵（+3 特效／公牛動畫等），
        // 這些鍵已無任何程式讀寫，見 StreamPrefs.purgeDeprecatedKeys
        StreamPrefs.purgeDeprecatedKeys(this)
    }
}
