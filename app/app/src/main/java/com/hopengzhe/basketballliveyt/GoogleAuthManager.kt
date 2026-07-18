package com.hopengzhe.basketballliveyt

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes

/**
 * 集中管理 Google 登入（GoogleSignIn）與 YouTube Data API 授權相關邏輯。
 *
 * 採用「GoogleSignIn 取得帳號＋YouTube 權限範圍」→「GoogleAccountCredential 包裝該帳號」
 * →「交給 YouTube.Builder 建立 API 客戶端」的經典組合（google-api-client-android）。
 * 登入本身（帳號選擇、同意畫面）交給 GoogleSignIn 處理，這裡只負責組出可呼叫 YouTube API 的物件。
 */
object GoogleAuthManager {

    /**
     * 建立/管理直播（liveBroadcasts、liveStreams 寫入操作）需要能寫入帳號資料的範圍，
     * 唯讀範圍（YOUTUBE_READONLY）不足以呼叫這些寫入 API。
     * v0.8.25：改用 [YouTubeScopes.YOUTUBE_FORCE_SSL] 取代原本的完整 [YouTubeScopes.YOUTUBE]——
     * 這支 APP 只用到 liveBroadcasts／liveStreams，force-ssl 範圍已足夠涵蓋，是 Google 官方
     * 文件推薦給一般讀寫用途的範圍，比完整 YOUTUBE 範圍窄，上架審核時代表的權限請求也更精準。
     * 兩者在 Google 的分類上都屬於「受限範圍」，公開上架仍需走完整的 OAuth 驗證流程，
     * 這裡只是把請求的權限縮到剛好夠用，不代表能跳過驗證。
     */
    val YOUTUBE_SCOPE: Scope = Scope(YouTubeScopes.YOUTUBE_FORCE_SSL)

    private fun buildSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(YOUTUBE_SCOPE)
            .build()

    /** 取得可用於發動登入畫面／登出的 GoogleSignInClient。 */
    fun getClient(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context, buildSignInOptions())

    /**
     * 已登入且已授權 YouTube 權限範圍才視為「可直接進直播主畫面」。
     * 注意：此檢查只讀本機快取的授權紀錄，不代表 token 一定還沒過期
     * （測試模式 7 天限制過期時，實際呼叫 API 才會拋出需要重新授權的例外）。
     */
    fun getAuthorizedAccount(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(account, YOUTUBE_SCOPE)) account else null
    }

    /**
     * 用已授權帳號建立 YouTube Data API 客戶端。
     * GoogleAccountCredential 會在每次 API 呼叫時透過 Google Play 服務自動取得／更新存取權杖，
     * 若權杖需要使用者重新同意授權，會拋出 [com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException]，
     * 呼叫端可用其 getIntent() 導向系統授權畫面解決。
     */
    fun buildYouTubeService(context: Context, account: GoogleSignInAccount): YouTube {
        val credential = GoogleAccountCredential.usingOAuth2(
            context.applicationContext,
            listOf(YouTubeScopes.YOUTUBE_FORCE_SSL)
        )
        credential.selectedAccountName = account.email
        return YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(context.getString(R.string.app_name))
            .build()
    }
}
