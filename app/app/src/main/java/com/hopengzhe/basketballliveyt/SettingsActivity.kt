package com.hopengzhe.basketballliveyt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import com.hopengzhe.basketballliveyt.databinding.ActivitySettingsBinding

/**
 * 設定頁。
 *
 * 直播標題 / 隱私設定會在開播時交給 YouTube Data API 建立直播使用（見 LiveActivity）。
 * 解析度 / fps / 碼率與「串流金鑰（測試用）」存進 SharedPreferences，
 * 供直播主畫面開播時套用到 RootEncoder 編碼器；未登入 YouTube 帳號時會退回用此金鑰手動推流。
 * 「登出／切換 Google 帳號」是重新回到登入頁挑選帳號的唯一入口（因已登入時登入頁會自動略過）。
 * v0.8.23：新增「直播中碼率自動調整」開關（`switchBitrateAutoAdjust`）——開啟維持原本網路
 * 壅塞時自動降碼率的行為，關閉則直播全程固定用選定的碼率（見 LiveActivity.onNewBitrate）。
 * v0.10.0：新增「同步錄影備份」區塊（`switchRecordEnabled`/`spinnerRecordResolution`/
 * `spinnerRecordSaveMode`）——開關關閉時兩個 Spinner 反灰（見 [updateRecordSectionEnabled]）；
 * 存檔位置選「自訂資料夾…」時開系統資料夾選取器（[recordFolderPickerLauncher]），選定後
 * `takePersistableUriPermission` 永久記住授權並立即存檔（不等「儲存設定」按鈕，因授權必須在
 * 拿到 Uri 的當下取得）；錄影開關與解析度仍走現有 `loadSavedSettings()`/`btnSaveSettings` 流程。
 * 錄影實際流程見 LiveActivity 類別頂端 KDoc。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // v0.10.0：Spinner 附加 onItemSelectedListener 後，Android 會立即對「目前已選中的項目」
    // 補發一次 onItemSelected（非使用者操作），這裡用旗標吃掉那一次，避免還原設定時
    // 若上次存的是「自訂資料夾…」，一進設定頁就誤跳資料夾選取器。
    private var recordSaveModeSpinnerReady = false

    private val recordFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> handleRecordFolderPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetsAsPadding()
        binding.root.clearAllButtonTints()

        loadSavedSettings()
        setupGuide()
        setupKeyLock()
        refreshAccountStatus()
        setupRecordSection()
        setupTitleTemplates()
        setupLowLatencyPreset()
        setupRosterSection()

        binding.btnSwitchAccount.setOnClickListener {
            GoogleAuthManager.getClient(this).signOut().addOnCompleteListener {
                Toast.makeText(this, getString(R.string.settings_signed_out_toast), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            StreamPrefs.save(
                context = this,
                streamTitle = binding.etStreamTitle.text.toString(),
                privacy = binding.spinnerPrivacy.selectedItem?.toString() ?: "",
                resolution = binding.spinnerResolution.selectedItem?.toString()
                    ?: StreamPrefs.DEFAULT_RESOLUTION,
                fps = binding.spinnerFps.selectedItem?.toString() ?: StreamPrefs.DEFAULT_FPS,
                bitrate = binding.spinnerBitrate.selectedItem?.toString()
                    ?: StreamPrefs.DEFAULT_BITRATE,
                bitrateAutoAdjust = binding.switchBitrateAutoAdjust.isChecked,
                streamKey = binding.etStreamKey.text.toString().trim(),
                recordEnabled = binding.switchRecordEnabled.isChecked,
                recordResolution = StreamPrefs.RECORD_RESOLUTION_FIXED_1080P30,
                recordSaveMode = StreamPrefs.RECORD_SAVE_MODE_DOWNLOADS
            )
            // v0.9.17：兩欄合併存成「第一行\n第二行」；第二行空白就只存第一行，
            // 第一行空白視同整個賽事名稱留空（第二行單獨有字也不顯示，避免燒入端出現孤行）
            val line1 = binding.etEventNameLine1.text.toString().trim()
            val line2 = binding.etEventNameLine2.text.toString().trim()
            val mergedEventName = when {
                line1.isEmpty() -> ""
                line2.isEmpty() -> line1
                else -> "$line1\n$line2"
            }
            StreamPrefs.saveEventName(this, mergedEventName)
            // v0.16.0：功能一——保存兩個標題模板與目前選用的模板（實際開播標題已由上面
            // StreamPrefs.save 的 streamTitle=etStreamTitle 存入，模板僅供切換帶入該欄）
            StreamPrefs.saveTitleTemplates(
                this,
                binding.etTitleTemplate1.text.toString(),
                binding.etTitleTemplate2.text.toString(),
                if (binding.radioTitleTemplate2.isChecked) 2 else 1
            )
            // v0.18.38：YouTube 兩組帳號（A 組金鑰仍由上面 StreamPrefs.save 的 streamKey 存）
            StreamPrefs.saveYouTubeProfiles(
                this,
                if (binding.radioYouTubeProfileB.isChecked) 2 else 1,
                binding.etYouTubeLabelA.text.toString().trim(),
                binding.etYouTubeLabelB.text.toString().trim(),
                binding.etYouTubeKeyB.text.toString().trim()
            )
            // v0.19.2：隊名（本頁是唯一入口，留空＝用預設「主隊／客隊」）
            StreamPrefs.saveTeamNames(
                this,
                binding.etTeamHomeName.text.toString().trim(),
                binding.etTeamAwayName.text.toString().trim()
            )
            // v0.18.17：FB／自訂各自的金鑰（YouTube 那格仍由上面 StreamPrefs.save 的 streamKey 存）
            StreamPrefs.savePlatformStreamKeys(
                this,
                binding.etStreamKeyFacebook.text.toString().trim(),
                binding.etFacebookServerUrl.text.toString().trim(),
                binding.etStreamKeyCustom.text.toString().trim()
            )
            // v0.18.16：直播平台（YouTube 帳號模式／Facebook 貼金鑰／自訂推流網址）
            StreamPrefs.saveLivePlatform(
                this,
                binding.spinnerLivePlatform.selectedItem?.toString() ?: StreamPrefs.PLATFORM_YOUTUBE
            )
            // v0.19.0：本場球員名單年級（名單內容本身在對話框按確定當下就已存檔）
            StreamPrefs.saveActiveRosterGrade(
                this,
                binding.spinnerRosterGrade.selectedItem?.toString() ?: StreamPrefs.DEFAULT_ROSTER_GRADE
            )
            // v0.13.0：功能 A 精彩時刻標記——標記回推秒數
            StreamPrefs.saveHighlightReboundSeconds(
                this,
                binding.spinnerHighlightReboundSeconds.selectedItem?.toString()
                    ?: StreamPrefs.DEFAULT_HIGHLIGHT_REBOUND_SECONDS
            )
            Toast.makeText(
                this,
                "設定已儲存，下次開播會套用最新的串流規格",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    /**
     * v0.18.40：金鑰欄位鎖定——鎖定時 YT 兩組金鑰與 FB 金鑰／伺服器網址不能編輯（文字照樣看得清楚，
     * 只是點不進去），避免比賽前手滑改壞。狀態存 [StreamPrefs]，下次進設定頁維持上次的鎖定狀態。
     */
    private fun setupKeyLock() {
        binding.btnLockKeys.setOnClickListener {
            val locked = !StreamPrefs.isKeyFieldsLocked(this)
            StreamPrefs.saveKeyFieldsLocked(this, locked)
            applyKeyLock(locked)
        }
        applyKeyLock(StreamPrefs.isKeyFieldsLocked(this))
    }

    private fun applyKeyLock(locked: Boolean) {
        listOf(
            binding.etStreamKey, binding.etYouTubeKeyB,
            binding.etStreamKeyFacebook, binding.etFacebookServerUrl
        ).forEach {
            it.isFocusable = !locked
            it.isFocusableInTouchMode = !locked
            it.isCursorVisible = !locked
            it.isLongClickable = !locked
        }
        binding.btnLockKeys.setText(
            if (locked) R.string.settings_keys_locked_button else R.string.settings_keys_unlocked_button
        )
    }

    /** 目前選用那組的金鑰欄位用高亮底圖，另一組維持一般底圖。 */
    private fun highlightSelectedYouTubeKey() {
        val useB = binding.radioYouTubeProfileB.isChecked
        binding.etStreamKey.setBackgroundResource(
            if (useB) R.drawable.bg_settings_field else R.drawable.bg_settings_field_active
        )
        binding.etYouTubeKeyB.setBackgroundResource(
            if (useB) R.drawable.bg_settings_field_active else R.drawable.bg_settings_field
        )
        // setBackgroundResource 會吃掉內距，補回來維持版面一致
        val pad = (10 * resources.displayMetrics.density).toInt()
        binding.etStreamKey.setPadding(pad, pad, pad, pad)
        binding.etYouTubeKeyB.setPadding(pad, pad, pad, pad)
    }

    /** v0.18.23：全功能使用教學——標題點一下展開／收起，預設收起，不佔設定頁版面。 */
    private fun setupGuide() {
        // v0.18.24：說明本文用 HTML（粗體小節標題＋縮排條列），純文字擠成一團看不出層次
        binding.tvGuideBody.text = androidx.core.text.HtmlCompat.fromHtml(
            getString(R.string.settings_guide_body), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
        )
        binding.tvGuideHeader.setOnClickListener {
            val show = binding.tvGuideBody.visibility != View.VISIBLE
            binding.tvGuideBody.visibility = if (show) View.VISIBLE else View.GONE
            binding.tvGuideHeader.setText(
                if (show) R.string.settings_guide_header_expanded else R.string.settings_guide_header_collapsed
            )
        }
    }

    /** 載入上次儲存的設定，還原到各輸入欄位／下拉選單。 */
    private fun loadSavedSettings() {
        binding.etStreamTitle.setText(StreamPrefs.getStreamTitle(this))
        // v0.16.0：功能一——還原兩個標題模板內容與目前選用的模板單選鈕
        binding.etTitleTemplate1.setText(StreamPrefs.getTitleTemplate1(this))
        binding.etTitleTemplate2.setText(StreamPrefs.getTitleTemplate2(this))
        if (StreamPrefs.getSelectedTitleTemplate(this) == 2) {
            binding.radioTitleTemplate2.isChecked = true
        } else {
            binding.radioTitleTemplate1.isChecked = true
        }
        // v0.9.17：賽事名稱拆兩欄——儲存格式仍是單一字串（換行符分隔），這裡拆回兩欄還原
        val eventNameLines = StreamPrefs.getEventName(this).split("\n")
        binding.etEventNameLine1.setText(eventNameLines.getOrElse(0) { "" })
        binding.etEventNameLine2.setText(eventNameLines.getOrElse(1) { "" })
        binding.etStreamKey.setText(StreamPrefs.getYouTubeKey(this, 1))
        binding.etYouTubeKeyB.setText(StreamPrefs.getYouTubeKey(this, 2))
        binding.etYouTubeLabelA.setText(StreamPrefs.getYouTubeLabel(this, 1))
        binding.etYouTubeLabelB.setText(StreamPrefs.getYouTubeLabel(this, 2))
        if (StreamPrefs.getYouTubeProfile(this) == 2) {
            binding.radioYouTubeProfileB.isChecked = true
        } else {
            binding.radioYouTubeProfileA.isChecked = true
        }
        // v0.18.39：選用中的那組金鑰欄位改成金框藍底，一眼看得出開播會用哪一把
        binding.radioGroupYouTubeProfile.setOnCheckedChangeListener { _, _ -> highlightSelectedYouTubeKey() }
        highlightSelectedYouTubeKey()
        binding.etTeamHomeName.setText(StreamPrefs.getTeamHomeName(this))
        binding.etTeamAwayName.setText(StreamPrefs.getTeamAwayName(this))
        binding.etStreamKeyFacebook.setText(StreamPrefs.getFacebookStreamKey(this))
        binding.etFacebookServerUrl.setText(StreamPrefs.getFacebookServerUrl(this))
        binding.etStreamKeyCustom.setText(StreamPrefs.getCustomStreamUrl(this))
        setSpinnerSelection(
            binding.spinnerLivePlatform, R.array.live_platform_options, StreamPrefs.getLivePlatform(this)
        )
        // v0.19.0：本場球員名單年級
        setSpinnerSelection(
            binding.spinnerRosterGrade, R.array.roster_grade_options, StreamPrefs.getActiveRosterGrade(this)
        )
        binding.switchBitrateAutoAdjust.isChecked = StreamPrefs.isBitrateAutoAdjust(this)
        setSpinnerSelection(binding.spinnerPrivacy, R.array.privacy_options, StreamPrefs.getPrivacy(this))
        setSpinnerSelection(binding.spinnerResolution, R.array.resolution_options, StreamPrefs.getResolution(this))
        setSpinnerSelection(binding.spinnerFps, R.array.fps_options, StreamPrefs.getFps(this))
        setSpinnerSelection(binding.spinnerBitrate, R.array.bitrate_options, StreamPrefs.getBitrate(this))
        // v0.10.0：同步錄影備份
        binding.switchRecordEnabled.isChecked = StreamPrefs.isRecordEnabled(this)
        setSpinnerSelection(
            binding.spinnerRecordResolution, R.array.record_resolution_options, StreamPrefs.getRecordResolution(this)
        )
        setSpinnerSelection(
            binding.spinnerRecordSaveMode, R.array.record_save_mode_options, StreamPrefs.getRecordSaveMode(this)
        )
        // v0.13.0：功能 A 精彩時刻標記——標記回推秒數
        setSpinnerSelection(
            binding.spinnerHighlightReboundSeconds,
            R.array.highlight_rebound_seconds_options,
            StreamPrefs.getHighlightReboundSeconds(this)
        )
    }

    /**
     * v0.19.0：球員名單編輯——「編輯目前年級名單」按鈕跳對話框，內容是一個多行輸入框，
     * 一行一位（[StreamPrefs.parseRoster] 會去空行、去頭尾空白、截到 12 位）。
     * 按確定當下就存進 [StreamPrefs]，不等「儲存設定」——否則切年級時上一份草稿會不見。
     * ponytail: 直播畫面鎖橫式、軟鍵盤一開沒剩多少高度，12 個獨立輸入格排不下，用單一多行框最省。
     */
    private fun setupRosterSection() {
        binding.btnEditRoster.setOnClickListener {
            val grade = binding.spinnerRosterGrade.selectedItem?.toString()
                ?: StreamPrefs.DEFAULT_ROSTER_GRADE
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            val editText = EditText(this).apply {
                setText(StreamPrefs.serializeRoster(StreamPrefs.getRoster(this@SettingsActivity, grade)))
                hint = getString(R.string.settings_roster_edit_hint)
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                minLines = 6
                maxLines = StreamPrefs.ROSTER_MAX_SIZE
                setSingleLine(false)
                setSelection(text.length)
            }
            val container = FrameLayout(this).apply {
                setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
                addView(editText)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_roster_edit_title, grade))
                .setView(container)
                .setPositiveButton(getString(R.string.dialog_confirm_button)) { _, _ ->
                    StreamPrefs.saveRoster(this, grade, editText.text.toString())
                    val saved = StreamPrefs.getRoster(this, grade).size
                    Toast.makeText(
                        this,
                        getString(R.string.settings_roster_saved_toast, grade, saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(getString(R.string.dialog_cancel_button), null)
                .show()
        }
    }

    private fun setSpinnerSelection(spinner: Spinner, arrayRes: Int, savedValue: String) {
        val options = resources.getStringArray(arrayRes)
        val index = options.indexOf(savedValue)
        if (index >= 0) spinner.setSelection(index)
    }

    /**
     * v0.16.0：功能一——點選標題模板時，把該模板目前輸入框的文字帶入「本次直播標題」欄
     * （etStreamTitle）供微調；一律以模板選擇為準覆蓋該欄（就算先前手動改過也覆蓋，避免狀態
     * 糾結，見計畫書功能一）。
     * v0.16.1：Boss 實測回饋——原本掛在 RadioGroup 的「選項變更」事件，點已選中的模板不會
     * 觸發（預設選中模板一時點模板一、或改完模板內容想重新套用都沒反應）。改掛兩顆單選鈕的
     * 點擊事件：每次點擊都立即套用，含重複點同一顆；單選狀態由 RadioGroup 照常維護。
     */
    private fun setupTitleTemplates() {
        binding.radioTitleTemplate1.setOnClickListener { selectTitleTemplate(1) }
        binding.radioTitleTemplate2.setOnClickListener { selectTitleTemplate(2) }
    }

    /**
     * v0.16.3：Boss 實測回饋——兩顆單選鈕會同時亮。根因：RadioButton 各包在一層 LinearLayout
     * （單選鈕＋模板輸入欄同列）裡才放進 RadioGroup，RadioGroup 只管理「直接子元件」的互斥，
     * 隔了一層就失效。改為點擊時手動互斥勾選（選中誰就只亮誰），不動版面結構。
     */
    private fun selectTitleTemplate(which: Int) {
        binding.radioTitleTemplate1.isChecked = which == 1
        binding.radioTitleTemplate2.isChecked = which == 2
        applyTitleTemplateToStreamTitle(which)
    }

    /** 把指定模板（1 或 2）目前輸入框的文字套用到「本次直播標題」欄，游標移到文末方便微調。 */
    private fun applyTitleTemplateToStreamTitle(which: Int) {
        val templateText = if (which == 2) {
            binding.etTitleTemplate2.text.toString()
        } else {
            binding.etTitleTemplate1.text.toString()
        }
        binding.etStreamTitle.setText(templateText)
        binding.etStreamTitle.setSelection(binding.etStreamTitle.text.length)
    }

    /**
     * v0.16.0：功能二——一鍵套「不延遲最低設定」：把解析度／影格率／碼率三個 Spinner 直接刷成
     * 720p/30fps/3300Kbps（純更新 UI，與手動逐項調整完全等價，儲存設定後下次開播自然生效，
     * 不動推流邏輯）。三個值直接取自 StreamPrefs
     * 的解析度／影格率預設常數與不延遲專屬碼率常數，且與 strings.xml 的三個 string-array 項目文字一致。
     */
    private fun setupLowLatencyPreset() {
        binding.btnLowLatencyPreset.setOnClickListener {
            setSpinnerSelection(binding.spinnerResolution, R.array.resolution_options, StreamPrefs.DEFAULT_RESOLUTION)
            setSpinnerSelection(binding.spinnerFps, R.array.fps_options, StreamPrefs.DEFAULT_FPS)
            setSpinnerSelection(binding.spinnerBitrate, R.array.bitrate_options, StreamPrefs.LOW_LATENCY_BITRATE)
            Toast.makeText(this, getString(R.string.settings_low_latency_applied_toast), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- v0.10.0：同步錄影備份——開關反灰／自訂資料夾 SAF 選取，見類別頂端 KDoc ----------

    /** 開關關閉時解析度／存檔位置兩個 Spinner 反灰（不可選），開啟則恢復可互動。 */
    private fun updateRecordSectionEnabled(enabled: Boolean) {
        binding.spinnerRecordResolution.isEnabled = enabled
        binding.spinnerRecordSaveMode.isEnabled = enabled
    }

    /**
     * 錄影開關的反灰連動＋存檔位置 Spinner 選到「自訂資料夾…」時開系統資料夾選取器。
     * `onItemSelectedListener` 掛上後系統會立即補發一次目前選中項目的事件（非使用者操作），
     * 用 [recordSaveModeSpinnerReady] 吃掉那一次，避免上次存的是「自訂資料夾…」時
     * 一進設定頁就誤跳選取器（見類別頂端 KDoc）。
     */
    private fun setupRecordSection() {
        updateRecordSectionEnabled(binding.switchRecordEnabled.isChecked)
        binding.switchRecordEnabled.setOnCheckedChangeListener { _, isChecked ->
            updateRecordSectionEnabled(isChecked)
        }
        refreshRecordFolderNameDisplay()

        binding.spinnerRecordSaveMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!recordSaveModeSpinnerReady) {
                    recordSaveModeSpinnerReady = true
                    return
                }
                val selected = parent?.getItemAtPosition(position)?.toString()
                if (selected == StreamPrefs.RECORD_SAVE_MODE_CUSTOM_FOLDER) {
                    recordFolderPickerLauncher.launch(null)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 資料夾選取器回呼：選定成功則 `takePersistableUriPermission` 永久記住授權並立即存檔
     * （不等「儲存設定」按鈕，因授權必須在拿到 Uri 的當下取得，見計畫書功能包①）；
     * 取消則已有舊資料夾就沿用，沒有就退回「相簿」選項，避免存檔位置卡在「自訂資料夾…」
     * 卻沒有實際授權 Uri 的不一致狀態。
     */
    private fun handleRecordFolderPicked(uri: Uri?) {
        if (uri == null) {
            if (StreamPrefs.getRecordTreeUri(this).isEmpty()) {
                setSpinnerSelection(
                    binding.spinnerRecordSaveMode, R.array.record_save_mode_options, StreamPrefs.RECORD_SAVE_MODE_GALLERY
                )
                Toast.makeText(
                    this, getString(R.string.settings_record_folder_cancelled_use_gallery_toast), Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this, getString(R.string.settings_record_folder_cancelled_keep_previous_toast), Toast.LENGTH_SHORT
                ).show()
            }
            refreshRecordFolderNameDisplay()
            return
        }
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        StreamPrefs.saveRecordTreeUri(this, uri.toString())
        refreshRecordFolderNameDisplay()
    }

    /** 顯示目前已選的自訂資料夾名稱；尚未選擇或讀取失敗則顯示「尚未選擇資料夾」。 */
    private fun refreshRecordFolderNameDisplay() {
        val treeUriString = StreamPrefs.getRecordTreeUri(this)
        val folderName = if (treeUriString.isEmpty()) {
            null
        } else {
            runCatching { DocumentFile.fromTreeUri(this, Uri.parse(treeUriString))?.name }.getOrNull()
        }
        binding.tvRecordFolderName.text = getString(
            R.string.settings_record_folder_current_format,
            folderName ?: getString(R.string.settings_record_folder_none)
        )
    }

    /** 顯示目前是否已登入 YouTube 帳號（GoogleAuthManager 只讀本機快取，不代表 token 一定沒過期）。 */
    private fun refreshAccountStatus() {
        val account = GoogleAuthManager.getAuthorizedAccount(this)
        binding.tvAccountStatus.text = if (account != null) {
            getString(R.string.settings_account_signed_in_format, account.email ?: account.displayName.orEmpty())
        } else {
            getString(R.string.settings_account_signed_out)
        }
    }

}
