package com.hopengzhe.basketballliveyt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.hopengzhe.basketballliveyt.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 設定頁。
 *
 * 直播標題 / 隱私設定會在開播時交給 YouTube Data API 建立直播使用（見 LiveActivity）。
 * 解析度 / fps / 碼率與「串流金鑰（測試用）」存進 SharedPreferences，
 * 供直播主畫面開播時套用到 RootEncoder 編碼器；未登入 YouTube 帳號時會退回用此金鑰手動推流。
 * 「登出／切換 Google 帳號」是重新回到登入頁挑選帳號的唯一入口（因已登入時登入頁會自動略過）。
 * v0.7.0：新增「偵測網路並建議設定」按鈕（見 [setupNetworkDetection]）——實測目前手機的真實上傳
 * 速度（見 [NetworkSpeedTester]），依「不卡頓優先於畫質」原則自動套用建議的解析度／fps／碼率，
 * 套用後三個 Spinner 仍可手動個別再調整，不會被鎖定。
 * v0.8.20：測速改成連續 60 秒（見 [runNetworkDetection]），按鈕下方即時畫出速率曲線
 * （見 [NetworkSpeedChartView]），跑完取這 60 秒內最低的一次取樣值換算建議規格。
 * v0.8.23：新增「直播中碼率自動調整」開關（`switchBitrateAutoAdjust`）——開啟維持原本網路
 * 壅塞時自動降碼率的行為，關閉則直播全程固定用選定的碼率（見 LiveActivity.onNewBitrate）。
 * v0.10.0：新增「同步錄影備份」區塊（`switchRecordEnabled`/`spinnerRecordResolution`/
 * `spinnerRecordSaveMode`）——開關關閉時兩個 Spinner 反灰（見 [updateRecordSectionEnabled]）；
 * 存檔位置選「自訂資料夾…」時開系統資料夾選取器（[recordFolderPickerLauncher]），選定後
 * `takePersistableUriPermission` 永久記住授權並立即存檔（不等「儲存設定」按鈕，因授權必須在
 * 拿到 Uri 的當下取得）；錄影開關與解析度仍走現有 `loadSavedSettings()`/`btnSaveSettings` 流程。
 * 錄影實際流程見 LiveActivity 類別頂端 KDoc。
 * v0.12.1：一鍵測速時長 60 秒 → 10 秒（Boss 指示，開賽前不用等一分鐘）；取樣點變少，
 * 換算建議規格時排除的極端下探尖峰由 2 個改 1 個（見 [runNetworkDetection]）。
 * v0.12.2：修「測速顯示 0M／曲線前段空白／有時整條沒有」（bug 4，見 [NetworkSpeedTester] 類別頂端
 * KDoc 查證的根因）——[runNetworkDetection] 取樣點時間戳超出量測時長就不畫進曲線；換算建議規格
 * 前先篩掉「0 Mbps」的無效樣本（上傳失敗的記錄值，非真實網速），篩完沒有有效樣本就顯示測速失敗
 * （見 [applyWorstSampleOrShowFailure]），不再算出誤導性的 0M 建議。
 * v0.13.1：修「曲線後半段有時不見」——v0.12.2 的「時間戳超出量測時長就不畫進曲線」矯枉過正，
 * 網路中途變慢時最後幾個區塊會在 10 秒後才傳完，整點被丟掉導致曲線後半段空白；改成一律夾在
 * 0~量測時長內畫出（`elapsedMs.coerceIn(0, NETWORK_TEST_DURATION_MS)`，超時的點畫在圖表右
 * 邊界），數值仍照樣計入建議規格判斷（見 [runNetworkDetection]）。
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
        refreshAccountStatus()
        setupNetworkDetection()
        setupRecordSection()
        setupTitleTemplates()
        setupLowLatencyPreset()

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
            StreamPrefs.saveRecord720pExperimentEnabled(this, binding.switchRecord720pExperiment.isChecked)
            // v0.16.0：功能一——保存兩個標題模板與目前選用的模板（實際開播標題已由上面
            // StreamPrefs.save 的 streamTitle=etStreamTitle 存入，模板僅供切換帶入該欄）
            StreamPrefs.saveTitleTemplates(
                this,
                binding.etTitleTemplate1.text.toString(),
                binding.etTitleTemplate2.text.toString(),
                if (binding.radioTitleTemplate2.isChecked) 2 else 1
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
        binding.etStreamKey.setText(StreamPrefs.getStreamKey(this))
        binding.switchBitrateAutoAdjust.isChecked = StreamPrefs.isBitrateAutoAdjust(this)
        setSpinnerSelection(binding.spinnerPrivacy, R.array.privacy_options, StreamPrefs.getPrivacy(this))
        setSpinnerSelection(binding.spinnerResolution, R.array.resolution_options, StreamPrefs.getResolution(this))
        setSpinnerSelection(binding.spinnerFps, R.array.fps_options, StreamPrefs.getFps(this))
        setSpinnerSelection(binding.spinnerBitrate, R.array.bitrate_options, StreamPrefs.getBitrate(this))
        // v0.10.0：同步錄影備份
        binding.switchRecordEnabled.isChecked = StreamPrefs.isRecordEnabled(this)
        binding.switchRecord720pExperiment.isChecked = StreamPrefs.isRecord720pExperimentEnabled(this)
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

    private fun setSpinnerSelection(spinner: Spinner, arrayRes: Int, savedValue: String) {
        val options = resources.getStringArray(arrayRes)
        val index = options.indexOf(savedValue)
        if (index >= 0) spinner.setSelection(index)
    }

    /**
     * 一鍵測速：按下後連續實測 10 秒上傳速度（[NetworkSpeedTester.measureUploadCurve]），
     * 期間即時在 [NetworkSpeedChartView] 畫出速率曲線；跑完後排除掉最極端的一個下探尖峰，
     * 取**剩餘取樣中最低的一次**換算建議規格——比單純平均更貼近「現場網路較差時撐不
     * 撐得住」，但又不會被單一次瞬斷（例如 Wi-Fi 訊號打嗝）拉成過度保守的規格。
     * v0.12.1：量測時長由 60 秒縮短為 10 秒（Boss 指示）；取樣點跟著變少（快網路約 10 餘點、
     * 慢網路可能僅 4~5 點），排除極端值由 2 個改 1 個，避免刪掉太高比例的有效取樣。
     * 外層 [withTimeout] 設在 10 秒基礎上多留 20 秒緩衝，避免極端慢速網路下單一區塊耗時過久
     * 導致整體測速卡住超過預期。測速期間按鈕禁用＋顯示「測速中…」，避免重複點擊。
     * v0.12.2：修「曲線前段空白／有時整條沒有／0M 建議」——取樣點時間戳超出量測時長（慢網路
     * 單一區塊耗時過久）就不畫進 [NetworkSpeedChartView]（避免座標算到畫面外整條線消失），
     * 但數值仍計入下面的建議規格判斷；換算建議規格前先篩掉「0 Mbps」的樣本（那是區塊上傳
     * 失敗的記錄值，不是真實網速，見 [NetworkSpeedTester.measureUploadCurve]），篩完一個
     * 有效樣本都不剩就視為測速失敗，顯示 [R.string.settings_detect_network_all_failed_message]，
     * 不再拿失敗記錄的 0 去算出誤導性的 0M 建議。
     */
    private fun setupNetworkDetection() {
        binding.btnDetectNetwork.setOnClickListener {
            runNetworkDetection()
        }
    }

    private fun runNetworkDetection() {
        binding.btnDetectNetwork.isEnabled = false
        binding.btnDetectNetwork.text = getString(R.string.settings_detect_network_testing)
        binding.tvDetectNetworkResult.text = getString(R.string.settings_detect_network_testing_hint)
        binding.chartNetworkSpeed.visibility = View.VISIBLE
        binding.chartNetworkSpeed.reset(NETWORK_TEST_DURATION_MS)

        lifecycleScope.launch {
            val samples = try {
                withTimeout(NETWORK_TEST_TIMEOUT_MS) {
                    NetworkSpeedTester.measureUploadCurve(NETWORK_TEST_DURATION_MS) { elapsedMs, mbps ->
                        // v0.13.1：修「曲線後半段有時不見」——慢網路中途變慢時，最後幾個區塊的
                        // 時間戳會超出量測時長（10 秒後才傳完），舊寫法整點丟掉導致曲線後半段空白。
                        // 改成一律夾在 0~量測時長內畫出（超時的點畫在圖表右邊界），數值仍照樣
                        // 計入下面的 samples 供建議規格判斷，不受夾限影響。
                        binding.chartNetworkSpeed.addSample(
                            elapsedMs.coerceIn(0L, NETWORK_TEST_DURATION_MS), mbps
                        )
                        binding.tvDetectNetworkResult.text = getString(
                            R.string.settings_detect_network_progress_format,
                            (elapsedMs / 1000L).coerceAtMost(NETWORK_TEST_DURATION_MS / 1000L),
                            mbps
                        )
                    }
                }
            } catch (e: Exception) {
                null
            }

            if (samples == null) {
                // 逾時或未知例外（見上面 withTimeout 的 catch），跟「測完但沒有有效樣本」分開處理
                showNetworkDetectionFailed(getString(R.string.settings_detect_network_failed_message))
            } else {
                applyWorstSampleOrShowFailure(samples)
            }

            binding.btnDetectNetwork.isEnabled = true
            binding.btnDetectNetwork.text = getString(R.string.settings_detect_network_button)
        }
    }

    /**
     * v0.12.2：0 代表那個區塊上傳失敗（連線中斷等），並非「真的測到 0Mbps」——先篩掉這些無效
     * 樣本，剩餘一個有效樣本都不剩就視為測速失敗；否則排除掉最極端的一個下探尖峰（例如 Wi-Fi
     * 瞬間打嗝）後再取最低值，避免單一瞬斷讓其餘都很穩定的網路被拉成過度保守的建議規格
     * （v0.12.1：時長縮為 10 秒後取樣點變少，排除數由 2 改 1，避免刪掉太高比例的取樣）。
     */
    private fun applyWorstSampleOrShowFailure(samples: List<Double>) {
        val validSamples = samples.filter { it > 0.0 }
        val worstMbps = validSamples.takeIf { it.isNotEmpty() }?.sorted()?.let { sorted ->
            val dropCount = minOf(1, sorted.size - 1).coerceAtLeast(0)
            sorted.drop(dropCount).minOrNull()
        }

        if (worstMbps != null) {
            binding.tvDetectNetworkResult.text =
                getString(R.string.settings_detect_network_result_format, worstMbps)
            applyRecommendedSpec(NetworkSpeedTester.recommendSpec(worstMbps))
        } else {
            showNetworkDetectionFailed(getString(R.string.settings_detect_network_all_failed_message))
        }
    }

    private fun showNetworkDetectionFailed(message: String) {
        binding.tvDetectNetworkResult.text = message
        Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
    }

    /** 套用測速建議值到三個 Spinner；使用者之後仍可手動個別再改動任一欄位，不會被強制鎖定。 */
    private fun applyRecommendedSpec(recommendation: NetworkSpeedTester.Recommendation) {
        setSpinnerSelection(binding.spinnerResolution, R.array.resolution_options, recommendation.resolution)
        setSpinnerSelection(binding.spinnerFps, R.array.fps_options, recommendation.fps)
        setSpinnerSelection(binding.spinnerBitrate, R.array.bitrate_options, recommendation.bitrate)
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
     * 720P/30fps/2300Kbps（純更新 UI，與手動逐項調整完全等價，儲存設定後下次開播自然生效，
     * 不動推流邏輯），沿用測速建議 [applyRecommendedSpec] 的做法。三個值直接取自 StreamPrefs
     * 的既有預設常數（本就是 720p/30/2300），且與 strings.xml 的三個 string-array 項目文字一致。
     */
    private fun setupLowLatencyPreset() {
        binding.btnLowLatencyPreset.setOnClickListener {
            setSpinnerSelection(binding.spinnerResolution, R.array.resolution_options, StreamPrefs.DEFAULT_RESOLUTION)
            setSpinnerSelection(binding.spinnerFps, R.array.fps_options, StreamPrefs.DEFAULT_FPS)
            setSpinnerSelection(binding.spinnerBitrate, R.array.bitrate_options, StreamPrefs.DEFAULT_BITRATE)
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

    private companion object {
        // 一鍵測速連續量測時長：10 秒（v0.12.1：Boss 指示由 60 秒縮短，開賽前測速不用等一分鐘）
        const val NETWORK_TEST_DURATION_MS = 10_000L
        // 逾時上限：10 秒基礎上多留 20 秒緩衝（最後一個區塊可能還在傳輸中），超過仍測不完視為失敗
        const val NETWORK_TEST_TIMEOUT_MS = 30_000L
    }
}
