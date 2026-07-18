package com.hopengzhe.basketballliveyt

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.hopengzhe.basketballliveyt.databinding.ActivityLoginBinding

/**
 * 登入頁。
 *
 * 已登入且已授權 YouTube 權限範圍時，[onCreate] 會直接跳過此畫面進直播主畫面。
 * 「使用 Google 帳號登入」走標準 GoogleSignIn 帳號選擇＋YouTube 權限同意畫面；
 * 「切換其他 Google 帳號」先登出目前快取的帳號選擇，再重新叫出帳號選擇畫面。
 * 未登入也能開播：LiveActivity 開播時找不到已授權帳號會自動退回設定頁的手動串流金鑰模式。
 * v0.12.2：APP 唯一入口——[onCreate] 一開始呼叫 [CrashLogger.notifyIfNewCrashLogExists]，
 * 偵測到上次有未提示過的當機記錄就跳一次 Toast（不管接下來是留在本頁或直接跳轉直播主畫面都會提示）。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var googleSignInClient: GoogleSignInClient

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetsAsPadding()
        binding.root.clearAllButtonTints()

        // v0.12.2：APP 唯一入口，偵測上次是否有未提示過的當機記錄
        CrashLogger.notifyIfNewCrashLogExists(this)

        googleSignInClient = GoogleAuthManager.getClient(this)

        // 已登入過且授權過 YouTube 權限，直接略過登入頁進直播主畫面
        if (GoogleAuthManager.getAuthorizedAccount(this) != null) {
            goToLiveActivity()
            return
        }

        binding.btnGoogleLogin.setOnClickListener {
            signInLauncher.launch(googleSignInClient.signInIntent)
        }

        binding.btnSwitchAccount.setOnClickListener {
            // 先登出目前的帳號選擇快取，signInIntent 才會重新顯示完整帳號清單而非直接沿用上次選擇
            googleSignInClient.signOut().addOnCompleteListener {
                signInLauncher.launch(googleSignInClient.signInIntent)
            }
        }
    }

    private fun handleSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (GoogleSignIn.hasPermissions(account, GoogleAuthManager.YOUTUBE_SCOPE)) {
                goToLiveActivity()
            } else {
                Toast.makeText(this, getString(R.string.login_scope_denied_message), Toast.LENGTH_LONG).show()
            }
        } catch (e: ApiException) {
            Toast.makeText(
                this,
                getString(R.string.login_failed_format, e.statusCode),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun goToLiveActivity() {
        startActivity(Intent(this, LiveActivity::class.java))
        finish()
    }
}
