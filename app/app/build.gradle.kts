plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hopengzhe.basketballliveyt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hopengzhe.basketballliveyt"
        minSdk = 26
        targetSdk = 35
        versionCode = 129
        versionName = "0.18.11"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // google-api-client-android 系列的依賴（google-auth-library 等）常見的 META-INF 重複檔案衝突，排除即可。
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // v0.10.0：同步錄影備份——自訂資料夾走 SAF（ACTION_OPEN_DOCUMENT_TREE），
    // DocumentFile.createFile 建檔需要此依賴，專案原本沒有引入
    implementation("androidx.documentfile:documentfile:1.0.1")

    // 推流引擎：RootEncoder
    // v0.8.27：2.4.9 → 2.6.1——2.4.9 的 tapToFocus() 把 AF_TRIGGER_START 塞進 repeating request
    // 每幀重送，對焦掃描不停被重啟永遠收斂不了（實機驗證：黃框鎖定了畫面仍糊）；2.6.0 起官方重寫為
    // 單次 capture 觸發＋完成後重設 IDLE，並內建 View→感光元件座標換算。選 2.6.1 而非最新 2.7.5：
    // 2.6.1 是 compileSdk 35＋Kotlin 2.1 的最後一版（2.6.2 起要 compileSdk 36、2.7.x 要 Kotlin 2.3），
    // 工具鏈升級代價最小；已逐一查證本 APP 用到的全部 API 在 2.6.1 簽名/行為一致（tapToFocus 除外，改為
    // tapToFocus(View, MotionEvent)）。
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.1")

    // Google 登入（GoogleSignIn，要求 YouTube 權限範圍）
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // YouTube Data API v3：GoogleAccountCredential + YouTube.Builder 經典組合。
    // google-api-client-android 舊版會拉到 org.apache.httpcomponents:httpclient，
    // 與 Android 內建的 legacy http client 有重複類別的已知衝突，兩個依賴都排除該 module 防呆。
    implementation("com.google.api-client:google-api-client-android:2.7.2") {
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
    }
    implementation("com.google.apis:google-api-services-youtube:v3-rev20241010-2.0.0") {
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
    }
    implementation("com.google.http-client:google-http-client-gson:1.45.0") {
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
    }

    // YouTube API 為同步阻塞呼叫，一律丟到背景執行緒；lifecycleScope 需要 lifecycle-runtime-ktx。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
