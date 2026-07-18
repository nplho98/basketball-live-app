// 籃球直播 APP — 根專案 build 設定
// 這裡只宣告外掛版本，實際套用在各模組的 build.gradle.kts 內
plugins {
    id("com.android.application") version "8.5.2" apply false
    // v0.8.27：Kotlin 1.9.24 → 2.1.20——RootEncoder 2.6.1 以 Kotlin 2.1.20 編譯，
    // 1.9 編譯器讀不了 2.1 的 metadata；KGP 2.1.20 與 AGP 8.5.2／Gradle 8.9 相容，其餘不動
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}
