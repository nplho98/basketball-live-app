package com.hopengzhe.basketballliveyt

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.util.TypedValue
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputFilter
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Surface
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.youtube.YouTube
import com.hopengzhe.basketballliveyt.databinding.ActivityLiveBinding
import com.pedro.common.ConnectChecker
import com.pedro.common.socket.base.SocketType
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.base.Camera2Base
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.RenderErrorCallback
import com.pedro.library.util.BitrateAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直播主畫面（橫屏鎖定）。
 *
 * 此階段已串接推流核心與四個可收合面板：
 * - 相機預覽為 RootEncoder 的 OpenGlView 真實畫面（需先取得 CAMERA/RECORD_AUDIO 權限）
 * - 開播/收播、分享移到左側欄；曝光 +/-、定焦、設定入口移到右側欄
 * - 頂部操作列：主隊犯規/節數/客隊犯規；底部計分列：兩隊隊名/分數/節數（永遠顯示，不收合）
 * - v0.8.0：主隊 -1/+1/+2/+3、客隊 +1/+2/+3/-1 改為固定顯示於畫面左右邊緣的獨立按鈕群
 *   （`leftScoreButtonsContainer`/`rightScoreButtonsContainer`，不屬於任何可收合面板，
 *   永遠顯示），原本在底部計分列的收合機制（收合箭頭＋展開把手）一併移除
 * - 上/左/右三個面板皆可獨立收合／展開（滑動動畫 + 邊緣把手），詳見 [setupCollapsiblePanels]
 * - 犯規次數（0～4）不再用畫面兩側常駐標籤顯示，改直接燒入計分板（v0.6.0 起改為 4 格燈號，
 *   見 [drawFoulLights]）；頂部犯規 +/- 按下時同步重繪燒入濾鏡（見 [changeFoulHome]/[changeFoulAway]）
 * - 計分板（隊名/分數/節數/犯規）透過 [ImageObjectFilterRender] 燒入直播影像，
 *   任何分數/節數/隊名/犯規變動都會重繪 Bitmap 並更新濾鏡（見 [refreshScoreboardOverlay]）
 * - 隊名可點擊底部計分列的隊名文字編輯（見 [showTeamNameEditDialog]），存 SharedPreferences 下次開啟仍記得
 * - v0.5.0：相機控制真實生效（見 [setupExposureControls]/[setupFocusLock]/[onKeyDown]）——
 *   曝光 +/- 呼叫 RootEncoder 2.4.9 `Camera2Base.setExposure/getMinExposure/getMaxExposure`
 *   （底層對應 `CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION`，函式庫內部已依
 *   `CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE` 夾限範圍）；定焦鎖定呼叫
 *   `Camera2Base.disableAutoFocus/enableAutoFocus`（對應 `CaptureRequest.CONTROL_AF_MODE`
 *   OFF／CONTINUOUS_PICTURE，回傳 false 代表裝置不支援，繁中提示不當機）；音量鍵變焦攔截
 *   `onKeyDown` 呼叫 `Camera2Base.setZoom/getZoomRange`，畫面右上角 tvZoomValue 顯示目前倍率
 * - 開播：已登入 YouTube 帳號時呼叫 YouTube Data API 自動建立直播（liveBroadcasts.insert →
 *   liveStreams.insert → liveBroadcasts.bind，enableAutoStart/enableAutoStop 皆為 true），
 *   取得推流位址後才真正呼叫 [RtmpCamera2.startStream]；未登入或建立失敗時退回設定頁手動串流金鑰模式
 * - 分享按鈕：開播成功且取得 YouTube 直播連結後才會開啟系統分享面板，否則提示尚未開播
 * - v0.4.4：修復直播中按分享／進設定頁返回後本機預覽黑屏（Surface 重建時用 [RtmpCamera2.replaceView]
 *   重新接回渲染管線，見建構子內 [SurfaceHolder.Callback] 註解）；直播中鎖定設定入口按鈕，
 *   分享按鈕維持任何時候可點（見 [setLiveUiState]/[setupSettingsEntry]）
 * - v0.4.5：返回鍵改攔截（見 [setupBackPressHandling]）——直播中按返回鍵會先跳確認框，
 *   確定才收播並離開，取消則留在直播畫面；左側欄新增「關閉軟體」按鈕（見 [setupCloseAppButton]），
 *   鎖定邏輯與設定按鈕一致（直播中鎖定＋降透明度），未直播時按下呼叫 finishAffinity() 完整結束 APP
 * - v0.5.1：對焦手勢改為點觸／長按（見 [setupFocusGestures]/[handleFocusGesture]）——
 *   `GestureDetector` 掛在 `openGlView` 上：單點＝以觸點為中心呼叫 `RtmpCamera2.tapToFocus`
 *   （對應 RootEncoder 2.4.9 `Camera2ApiManager.tapToFocus(MotionEvent)`，內部設定
 *   `CaptureRequest.CONTROL_AF_REGIONS`＋`CONTROL_AF_MODE_AUTO`＋`CONTROL_AF_TRIGGER_START`）
 *   觸發對焦，約 500ms 後（函式庫未開放 `CONTROL_AF_STATE` capture callback 可監聽收斂狀態，
 *   此為近似延遲做法）呼叫 `enableAutoFocus()` 恢復連續對焦（AF_REGIONS 不會被重置，
 *   之後仍偏向剛才點擊處）；長按＝同樣先 tapToFocus 觸發對焦。
 *   【v0.5.3 修正】長按鎖定原本在等待收斂後改呼叫 `disableAutoFocus()`
 *   （`CONTROL_AF_MODE_OFF`），結果畫面反而變模糊。查證 RootEncoder 2.4.9
 *   `Camera2ApiManager.disableAutoFocus()` 原始碼（`encoder/src/main/java/com/pedro/encoder/
 *   input/video/Camera2ApiManager.java`）證實：該函式只把 `CONTROL_AF_MODE` 設成 `OFF`，
 *   完全沒有同時設定 `CaptureRequest.LENS_FOCUS_DISTANCE` 保留剛才對焦到的距離
 *   （函式庫內另有 `setFocusDistance(float)` 才會設定這個欄位，`disableAutoFocus()` 沒呼叫它），
 *   導致切到手動對焦模式的當下，共用的 `CaptureRequest.Builder` 對該欄位回退到從未被設定過的
 *   預設值（近似對焦於無限遠），鏡頭因此直接跳焦到別的距離、瞬間變模糊。函式庫也沒有開放
 *   任何公開 API 可讀出目前鏡頭實際對焦到的距離（`CaptureResult.LENS_FOCUS_DISTANCE` 只能透過
 *   `CameraCaptureSession.CaptureCallback` 取得，而函式庫內部只有在 `enableFaceDetection()`
 *   開啟人臉偵測時才會掛上這個 callback，一般情況下完全沒有 callback 可監聽），因此無法「先讀出
 *   目前對焦距離、再手動指定同一個距離鎖定」。改採 Android Camera2 規格本身的行為：
 *   `CONTROL_AF_MODE_AUTO` 是觸發式對焦模式，鏡頭只在收到 `CONTROL_AF_TRIGGER_START` 時才移動，
 *   掃描完成後會停在 `FOCUSED_LOCKED`／`NOT_FOCUSED_LOCKED` 其中一個「鎖定」狀態，之後鏡頭不會
 *   再自己漂移，直到下一次觸發／取消／切換模式為止——這本身就已經是一種鎖定，不需要再額外切到
 *   `CONTROL_AF_MODE_OFF`。因此長按鎖定現在**不再呼叫** `disableAutoFocus()`，等對焦收斂延遲
 *   （近似值，理由同上）過後只更新 UI 狀態（`isFocusLocked=true`＋顯示鎖定圓框），實際對焦距離
 *   維持 tapToFocus() 收斂後的狀態不變、不會跳焦；解除鎖定時仍呼叫 `enableAutoFocus()` 切回
 *   `CONTINUOUS_PICTURE` 連續對焦（此函式有正確依裝置支援模式切換，未受影響）。
 *   殘餘限制：仍無法用公開 API 驗證「鎖定當下對焦是否真的準焦」（`AF_TRIGGER_START` 掃描結果
 *   若是 `NOT_FOCUSED_LOCKED`，鏡頭仍會停在掃描失敗當下的位置，跟舊版單點對焦本來就有的限制
 *   相同，並非本次修正引入的新問題）；少數相機 HAL 若未完全遵守 `CONTROL_AF_MODE_AUTO` 規格，
 *   理論上仍可能有極少數裝置的鎖定行為不如預期，此為 Android 相機 HAL 相容性層級的既有風險。
 *   觸點座標到感光元件座標（`CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE`）僅用 View
 *   寬高比例線性換算，假設預覽無黑邊且未做感光元件方向旋轉補償（已知誤差，見開發回報）。
 *   畫面上的對焦圈動畫見 [FocusIndicatorView]；右側欄「定焦鎖定」按鈕改為純顯示/解除鎖定用途
 *   （見 [setupFocusLock]），不再直接鎖住當下畫面
 * - v0.6.0：燒入計分板改版為轉播風格橫幅（見 [buildScoreboardOverlayBitmap]）——
 *   左側區塊改畫可自訂的賽事名稱（設定頁新增欄位，存 [StreamPrefs.getEventName]/
 *   [StreamPrefs.saveEventName]，空字串則該區塊留白不顯示）與現有節數；右側主區塊為
 *   「隊名／犯規燈號／比分／犯規燈號／隊名」。犯規呈現方式由文字數字改為 4 格燈號
 *   （見 [drawFoulLights]，未犯規＝淺灰未點亮，每犯規一次點亮一格紅燈），犯規上限同步由 5
 *   改為 4（`MAX_FOUL_COUNT`）；左側欄「分數清零」按鈕擴大範圍，連同兩隊犯規次數一起歸零
 *   （見 [setupResetScoresButton]）
 * - v0.7.0：直播中碼率自動調整（見 [bitrateAdapter]）——查證 RootEncoder 2.4.9
 *   `com.pedro.library.util.BitrateAdapter` 原始碼與官方範例（`CameraFragment.kt`）確認正確用法：
 *   建構子帶 `Listener`（`onBitrateAdapted(bitrate)` 呼叫 [RtmpCamera2.setVideoBitrateOnFly] 真正
 *   套用新碼率），開播時 `setMaxBitrate()` 設為使用者當下選擇的碼率設定值（調整上限，不會超過使用者
 *   選的碼率）；[ConnectChecker] 繼承自 `BitrateChecker`，其 `onNewBitrate(bitrate: Long)` 預設方法
 *   於推流中定期回呼目前實際送出的碼率，這裡覆寫後呼叫 `adaptBitrate(bitrate, hasCongestion)`
 *   （`StreamClient.hasCongestion()` 依函式庫預設 20% 佇列使用率門檻判斷是否壅塞，官方範例同樣採此
 *   多載版本）。只在 `isStreaming` 期間呼叫；收播後呼叫 `reset()` 清空平均值狀態。全程只有碼率會
 *   自動升降，解析度與 fps 維持使用者開播前的選擇不變（函式庫技術限制，無法直播中平滑切換）。
 * - v0.8.18：定焦鎖定中，點觸／長按畫面一律不處理（見 [handleFocusGesture] 開頭的
 *   `isFocusLocked` 短路判斷）——鎖定後唯一解鎖方式是按右側欄「定焦鎖定」按鈕（見 [setupFocusLock]），
 *   避免直播中誤觸畫面導致鎖定被意外解除
 * - v0.8.19：「● 直播中」提示移到畫面左下角並淡化（`android:alpha="0.6"`），與右下角變焦倍率
 *   顯示對稱，避免遮擋畫面中央（見 activity_live.xml `tvLiveIndicator`）
 * - v0.8.23：設定頁新增「直播中碼率自動調整」開關（見 [onNewBitrate]、
 *   `StreamPrefs.isBitrateAutoAdjust`）——關閉時完全不呼叫 [bitrateAdapter]，全程固定用開播
 *   當下選的碼率，不受網路壅塞影響自動調降；開啟則維持 v0.7.0 原本的自適應行為。
 * - v0.8.24：新增「網路壅塞，碼率調降中」小提示（[tvBitrateCongestion]，疊在「直播中」正上方）
 *   ——`onNewBitrate` 每次回呼都讀 `hasCongestion()`，true 就顯示、false 就隱藏，讓操作者能
 *   即時知道現在是不是正在因應網路犧牲畫質，不用等觀眾回報才知道。
 * - v0.8.27：RootEncoder 2.4.9 → 2.6.1——修「點觸／長按對焦鎖定了畫面仍糊」（實機對照驗證：
 *   同位置手機原生相機能對焦、本 APP 不能；診斷數值排除座標換算問題後，查證 2.4.9 原始碼確認
 *   根因是 tapToFocus() 把 `AF_TRIGGER_START` 放進 repeating request 每幀重送，對焦掃描不停被
 *   重啟永遠收斂不了；2.6.0 起官方重寫為單次 capture 觸發＋完成後重設 IDLE）。新版 tapToFocus
 *   改為 `tapToFocus(View, MotionEvent)` 且內建 View→感光元件座標換算，上述 v0.5.1 所述的
 *   本 APP 手工座標換算已整段移除（該段 KDoc 保留當歷史紀錄）。升級連帶：Kotlin 外掛
 *   1.9.24 → 2.1.20（2.6.1 以 Kotlin 2.1 編譯，metadata 相容需要）；選 2.6.1 不直上 2.7.5 的
 *   理由與逐 API 查證結果見 app/build.gradle.kts 依賴註解。已重新查證本檔其他處標註「2.4.9」的
 *   行為描述在 2.6.1 仍一致：OpenGlView surfaceDestroyed 一律 stop()（黑屏修復的 replaceView
 *   邏輯照舊需要）、BitrateAdapter 用法、hasCongestion() 預設 20% 門檻、曝光/變焦/replaceView
 *   等公開 API 簽名皆未變；enableAutoFocus() 在 2.6.1 會優先選 CONTINUOUS_VIDEO（錄影用連續
 *   對焦，對焦微調不打斷幀輸出），比 2.4.9 固定 CONTINUOUS_PICTURE 更適合直播場景。
 * - v0.9.0：燒入計分板配色改版為「經典轉播藏青金」（Boss 從六款樣圖提案選定樣式02）——
 *   藏青直向漸層底（微透明）＋金色細邊框＋頂緣金色飾線＋金色分隔線；文字改暖白、
 *   比分方塊改金色漸層底配深藏青數字、犯規燈號由紅燈改金燈（未點亮維持半透明白）。
 *   版面排列、欄位比例、字級縮放邏輯全部不動，只換配色與材質（見 [buildScoreboardOverlayBitmap]
 *   /[drawScoreBox]/[drawFoulBars]）。之後配色本身又經多輪調整（比分方塊金底黑字／黑底白字
 *   來回試過幾版，最終 v0.9.14 定案回金底黑字，詳見各函式內 KDoc 與 commit 歷史）。
 * - v0.9.14：節數從左欄（原「賽事名稱／節數」上下兩行）搬到右側主區塊兩個比分方塊正中間、
 *   跟犯規燈號同一排（Boss 指定新位置，見 [drawMainScoreRow]）；左欄現在只剩賽事名稱一行，
 *   整欄置中（見 [drawEventNameAndPeriodColumn]）。
 * - v0.9.16：賽事名稱改自動換行——每行上限 6 個字，超過自動換第 2 行（設定頁輸入框同步改回
 *   單行輸入＋maxLength 12，v0.9.15 曾短暫做成使用者手動換行的多行輸入框，因兩行間距抓得
 *   不對看起來斷開太遠而撤回）；兩行一起當一個區塊，用固定行距
 *   （字級 × [EVENT_NAME_LINE_SPACING_RATIO]）緊貼置中在整欄高度內。
 * - v0.9.17：賽事名稱在設定頁拆成兩個輸入欄（第一行／第二行各上限 8 字、hint 標註），
 *   燒入端改回依欄位內容分行（v0.9.16 的 6 字自動切行取消）；儲存格式仍為單一字串
 *   「第一行\n第二行」，第一行留空＝整塊不顯示。
 * - v0.10.0：新增「同步錄影備份」（見 [beginRtmpStreaming]/[stopLiveStream]/[startRecordIfEnabled]）
 *   ——錄影生命週期＝直播 session（開播即錄、收播即停），就算 RTMP 一直連不上錄影照跑；設定頁可
 *   開關並選錄影解析度（與直播相同＝共用編碼器近零耗電，或獨立 720p/1080p＝呼叫 RootEncoder 2.6.1
 *   `Camera2Base.prepareVideo` 11 參數完整多載帶入 recordWidth/recordHeight/recordBitrate，內部
 *   自動啟第二顆編碼器，畫質與網路壅塞降碼率脫鉤，見 [applyStreamSettingsAndStartPreview]）與存檔
 *   位置（相簿走 MediaStore＋`IS_PENDING`／自訂資料夾走 SAF `DocumentFile.createFile`，見
 *   [startRecordToGallery]/[startRecordToCustomFolder]）。鐵律：錄影任何環節失敗（`startRecord`
 *   拋例外、[RecordController.Listener.onError]）只跳 Toast，絕不中斷或影響直播（見
 *   [handleRecordFailure]）；`RecordController.Listener` 回呼來自背景執行緒，比照 [onNewBitrate]
 *   慣例一律 `runOnUiThread`。
 * - v0.10.1：右上角改一橫排「電池溫度→變焦倍率→版本號」（見 [updateTemperatureDisplay]，
 *   溫度分三段上色：38 以下藍「健康」／38~42 含 42 黃「請降溫」／超過 42 紅「效能已下降」，
 *   轉紅那一刻跳一次 Toast；資料來源＝ACTION_BATTERY_CHANGED 黏性廣播，onStart/onStop 配對
 *   註冊）；左上角「直播中」下方新增實際/設定碼率顯示（見 [updateBitrateStatusDisplay]，
 *   直播中才顯示，達標白字/低於九成黃字/低於七成紅字；顯示更新不受「碼率自動調整」開關
 *   影響，[onNewBitrate] 已把「顯示」與「調整」兩段邏輯拆開），網路壅塞提示順移到它下方。
 * - v0.12.0：穩定性三項（計畫書 `計畫書_穩定性三項_2026-07-16.md`）——
 *   1. 前景服務＋勿擾提醒：新增 [LiveForegroundService]，開播成功（[onConnectionSuccess]）
 *      啟動、收播（[stopLiveStream]）停止，切背景久放不再被系統殺進程斷播；開播前勿擾
 *      提醒訊息窗已於 v0.16.5 依 Boss 指示移除。背景時 OpenGlView 的
 *      surfaceDestroyed／replaceView 接回邏輯沿用 v0.4.4 既有機制，成因不限於分享/設定頁
 *      切換，切背景造成的 Surface 重建同樣適用，未額外修改。
 *   2. AE/AF 同步鎖定：函式庫（2.6.1）未開放 AE_LOCK／AE_REGIONS 公開 API，反射存取
 *      `Camera2Base` 私有欄位 `cameraManager`（`Camera2ApiManager` 實例）與其私有欄位
 *      `builderInputSurface`（`CaptureRequest.Builder`，`RtmpCamera2.tapToFocus` 剛設定好的
 *      `CONTROL_AF_REGIONS` 就在這顆 builder 上），點擊對焦時把該值複製到
 *      `CONTROL_AE_REGIONS`（見 [syncAeRegionsToFocusPoint]）、長按鎖定收斂後加
 *      `CONTROL_AE_LOCK=true`（見 [setAutoExposureLock]），套用一律呼叫 Kotlin 私有成員
 *      對外可見的 synthetic 橋接方法 `access$applyRequest`（見 [applyAeCaptureRequest]）。
 *      裝置不支援（`CONTROL_AE_LOCK_AVAILABLE`/`CONTROL_MAX_REGIONS_AE`，見
 *      [isAeLockSupported]）或反射任一步失敗，一律靜默退回原本「只鎖對焦」行為＋一次性
 *      Toast（[warnAeSyncFailedOnce]），絕不呼叫函式庫的 `disableAutoExposure()`
 *      （`AE_MODE_OFF` 陷阱，亮度會亂跳，已於函式庫原始碼查證）。
 *   3. 斷線重連強化：[RECONNECT_MAX_RETRIES] 由 10 改 `Int.MAX_VALUE`（實質無限，直到手動
 *      收播）；重連等待改由 APP 自己的 coroutine 掌控（見 [scheduleReconnect]／
 *      [performReconnectAttempt]），才能在 `ConnectivityManager.registerDefaultNetworkCallback`
 *      偵測到網路恢復時提前結束等待、立即重試（不乾等滿 5 秒週期，見
 *      [registerNetworkCallbackForReconnect]）；[YouTubeLiveRepository] 的 `enableAutoStop`
 *      改 `false`，收播才由 APP 主動呼叫 `liveBroadcasts.transition(complete)` 正式結束
 *      （見 [endYouTubeBroadcastIfNeeded]），失敗會在錯誤訊息明講請到 YouTube 工作室手動
 *      結束，不靜默；重連中前景服務通知文字同步換成「重新連線中」。
 * - v0.12.2：bug 修復四項（計畫書 `計畫書_bug修復與標記休息輪播遙控_2026-07-16.md` 第一波）——
 *   1. 收播後 YouTube 卡「直播中」：查證 [endYouTubeBroadcastIfNeeded] 本身邏輯正確（`transition
 *      (complete)` 有確實呼叫、broadcastId 生命週期正常），真正根因是**呼叫時機**——
 *      [showExitDuringLiveConfirmDialog]（返回鍵跳確認框後按確定）在呼叫 `stopLiveStream()` 的
 *      同一個 callback 內緊接著呼叫 `finish()`，而 `endYouTubeBroadcastIfNeeded()` 內部用
 *      `lifecycleScope.launch` 送出的網路呼叫（`liveBroadcasts.transition`）是非阻塞、非同步的；
 *      `finish()` 觸發的 `onDestroy()` 會取消 `lifecycleScope`，這個網路呼叫還沒送達／還沒收到
 *      回應就被連帶取消，YouTube 端因此完全沒收到 complete 請求、長時間卡在「直播中」（手動按
 *      收播按鈕的路徑因為沒有緊接 `finish()`，Activity 仍存活，反而通常不受影響）。改法：呼叫
 *      `lifecycleScope.launch(NonCancellable)` 讓這段收尾網路呼叫不受 Activity 銷毀連帶取消
 *      （coroutine 仍會在 APP 進程還活著的期間跑完，`finish()` 本身不會殺進程）；失敗自動重試
 *      一次（隔 [END_BROADCAST_RETRY_DELAY_MS] 5 秒），仍失敗才跳明確 Toast 提示手動到 YouTube
 *      工作室結束；成功也跳一次 Toast 告知（見 [endYouTubeBroadcastIfNeeded]）。
 *   2. 直播中當掉無記錄：新增 [CrashLogger]——`Thread.setDefaultUncaughtExceptionHandler` 在
 *      [BasketballLiveApplication.onCreate] 盡早掛上，攔截到當機先把完整堆疊寫進 APP 外部檔案區
 *      `crashlog/`（檔名帶時間戳），寫完一律交還原本的預設 handler（不吞錯、維持正常閃退）；
 *      下次啟動由 [LoginActivity]（APP 唯一入口）偵測到新檔跳 Toast 提示。順手審查 v0.12.0
 *      新增碼：前景服務啟停／通知更新皆已在 [onConnectionSuccess]／[scheduleReconnect] 等處包在
 *      `runOnUiThread`（`ConnectChecker`／`BitrateAdapter`／`RecordController.Listener` 回呼皆來自
 *      背景執行緒，既有寫法已正確）；AE/AF 反射同步（[syncAeRegionsToFocusPoint]/
 *      [setAutoExposureLock]）任一步失敗皆已包在 try/catch 安靜退回，且 release 版
 *      `isMinifyEnabled = false`（見 app/build.gradle.kts），排除 R8 obfuscation 導致反射
 *      欄位/方法名對不上的疑慮；背景切換 Surface 重接流程沿用 v0.4.4 既有機制，本輪未發現
 *      單一明確的當機根因，維持計畫書「這輪先裝黑盒子，真正根因待下次當機記錄」的預期。
 *   3. +3 動畫連按撞出螢幕：查證 RootEncoder 2.6.1 `BaseObjectFilterRender.setPosition()`
 *      內部呼叫 `Sprite.translate()` 是絕對值覆寫（非疊加，已反編譯 aar bytecode 確認），
 *      原本每次觸發動畫已經有呼叫 `setPosition(xStartPct, yPct)` 歸位；真正問題出在**連按時的
 *      並發競態**——舊版取消機制只有「新場次 cancelAndJoin 上一場」，快速連按 3 次以上會形成
 *      鏈式等待（第 3 場只等第 2 場，若第 2 場自己也正在等第 1 場時被第 3 場取消而提前放棄等待），
 *      可能留下一個沒被真正 join 到的孤兒 coroutine 繼續跑它自己的 while 迴圈，跟最新場次同時
 *      寫入同一個 `bullFilter`，造成畫面位置/影像交錯閃跳。改法：新增播放序號機制
 *      （`bullAnimRunId`）——每次觸發先佔用新序號，迴圈內每一步寫入前都核對序號是否仍是最新，
 *      不是就整段略過，徹底堵住孤兒 coroutine 的殘留寫入；濾鏡掛載狀態改用 `isBullFilterAttached`
 *      追蹤，只有仍是最新序號時才會真正 addFilter/removeFilter，避免 remove/add 因非同步佇列
 *      （`GlStreamInterface.filterQueue`）處理順序被打亂而重覆疊加或誤刪（見
 *      `playBullChargeAnimation`，此套機制已於 v0.14.1 隨公牛動畫整段移除）。
 *   4. 測速顯示 0M／曲線前段空白：[NetworkSpeedTester.CURVE_CHUNK_BYTES] 512KB → 128KB
 *      （取樣密度×4）；新增暖身塊（`WARMUP_CHUNK_BYTES` 64KB，結果不計入量測，讓 TLS 握手／TCP
 *      slow start 不拖慢第一個正式取樣塊）；[SettingsActivity.runNetworkDetection] 取樣點時間戳
 *      超出量測時長就不畫進曲線（避免慢網路單一區塊耗時過久，座標算到畫面外整條線消失），數值
 *      仍照樣計入建議規格判斷；有效（非 0）樣本為 0 個時視為測速失敗，顯示新字串
 *      `settings_detect_network_all_failed_message`，不再拿 0（上傳失敗的記錄值，並非真實網速）
 *      去算出誤導性的建議規格。
 * - v0.13.0：三項新功能（計畫書 `計畫書_bug修復與標記休息輪播遙控_2026-07-16.md` 第二波），皆採
 *   「UI 空殼先行」原則，核心引擎先做最簡可動版——
 *   1. 精彩時刻標記：⭐鈕（[setupHighlightMarkButton]，左側欄）按下記「目前直播經過時間（以
 *      [liveStartElapsedMs] 為基準）－設定頁『標記回推秒數』」＋當下節數/比分（[HighlightMarker]）；
 *      存 JSON（[HighlightStore]，APP 外部檔案區 `highlights/`，檔名帶場次時間戳，收播不清除）；
 *      收播流程結束跳「精彩清單」對話框（[showHighlightListDialog]，程式化建構列表比照
 *      [showTeamNameEditDialog] 做法，不新增 layout/adapter），可 ±5 秒微調／刪除／複製章節格式
 *      （`mm:ss 第N節 主X-客Y`，見 [HighlightMarker.toDisplayLine]）到剪貼簿。
 *   2. 休息畫面＝精華輪播：「休息畫面」鈕（[setupBreakScreenButton]，右側欄）切現場/回放模式。
 *      取「進入時間點往回最近 10 個標記」（[BREAK_MAX_CLIP_COUNT]，順序舊到新——Boss 未拍板項
 *      預設值），`MediaMetadataRetriever` 從 v0.10.0 本地錄影檔抽幀（[playBreakClips]，縮 480x270、
 *      12fps，`OPTION_CLOSEST_SYNC` 取關鍵幀非精確幀換取即時性），每球播「標記點起 8 秒」，走獨立的
 *      第三層 [ImageObjectFilterRender]（[breakFilter]／[breakPlaybackRunId]，序號機制沿用 v0.12.2
 *      bug 3 的作廢寫法但與公牛動畫的 `bullFilter`/`bullAnimRunId` 完全獨立，兩套動畫互不干擾——
 *      公牛動畫已於 v0.14.1 整段移除，`breakFilter` 不受影響）疊全畫面；
 *      播完一輪或無標記時停在靜態圖（[buildBreakStaticBitmap]，直接重用 [buildScoreboardOverlayBitmap]
 *      放大置中，另一 Boss 未拍板項預設值），回放期間聲音維持現場收音（未動音訊管線）。**已知限制**
 *      （v0.13.1 已修復，見下方 v0.13.1 條目）：`RecordController` 底層用 `android.media.MediaMuxer`
 *      （v0.10.0 KDoc 已查證），moov 索引依標準行為在 `stop()` 收尾才寫入，直播進行中的整場 mp4
 *      容器索引通常不完整，`MediaMetadataRetriever` 極可能讀不到任何一幀；本輪（v0.13.0）未能在
 *      實機環境驗證，只做了「運行時偵測＋優雅退回」（讀不到直接顯示靜態圖＋Toast，不硬繞）。
 *   3. 遙控計分：`LiveControlServer`（`ServerSocket`＋`Thread` 手寫，不加依賴）開播時啟動、收播
 *      停止；控制頁為單一內嵌 HTML（`assets/remote_control.html`），第二支手機瀏覽器開
 *      `http://IP:埠/?code=四位配對碼`，按鈕觸發與畫面按鈕相同的計分邏輯，HTTP 執行緒一律
 *      `runOnUiThread` 轉主執行緒；直播畫面顯示「IP:埠＋配對碼」小字，點一下展開/收合。
 *      【已於 v0.14.1 移除】Boss 拍板整個功能連同 `LiveControlServer.kt`／
 *      `assets/remote_control.html` 一起拆除，見下方 v0.14.1 條目。
 * - v0.13.1：bug 修復兩項（計畫書 `計畫書_bug修復與標記休息輪播遙控_2026-07-16.md` 加開，7/19
 *   實戰前最後修復）——
 *   1. 休息畫面回放讀不到進行中的錄影：v0.13.0 已知限制的根因已實機驗證確認（`MediaMetadataRetriever`
 *      讀不到進行中 mp4 的任何一幀，畫面只停在靜態圖）。走「回放專用滾動分段暫存」（不動主錄影
 *      既有邏輯，計畫書修法①）——反編譯本機 `library-2.6.1.aar` 位元碼查證 `RtmpCamera2` 實際繼承的
 *      `com.pedro.library.base.Camera2Base`（純 Java 舊路線，注意不是同一個 aar 內同時存在的另一顆
 *      Kotlin 類別 `StreamBase`，一開始誤查到後者繞了一次彎，見 [TeeRecordController] 類別頂端 KDoc
 *      查證澄清）開放 `public void setRecordController(BaseRecordController)` 可整顆替換錄影控制器，
 *      編碼器 callback 在推流期間對 `recordController.recordVideo/recordAudio` 一律呼叫、不看主錄影
 *      開關（詳細查證脈絡見 [TeeRecordController] 類別頂端 KDoc）——RecordController tee 這條路確認
 *      可行，不需要退回方案 B（分段主錄影）。新增 [TeeRecordController]（掛進
 *      `rtmpCamera2.setRecordController()`，見 `onCreate`）把每幀轉呼叫給既有 [mainRecordController]
 *      （`AndroidMuxerRecordController`，行為與 v0.10.0 完全一致）與新增的 [replayBufferController]
 *      （[ReplayBufferRecordController]）兩邊；後者在 `cacheDir/replaybuffer/` 持續切 30 秒一段的
 *      小 mp4，每段寫滿就收尾（moov 完整、可讀）再開下一段（比照框架 `Camera2Base.startRecord()` 的
 *      「先開檔、立刻 `requestKeyFrame()`」模式，注意大寫 F，確保新分段第一幀是關鍵幀，見
 *      [ReplayBufferRecordController] 類別頂端 KDoc），只保留最近 20 段（約 10
 *      分鐘）、收播（[stopLiveStream]）全部清掉；不論主錄影開關是否開啟都會運作（[enterBreakMode]
 *      舊有「需開啟錄影備份」前提已整段拿掉）。休息畫面回放（[playBreakClips]）改讀「已收尾完成的
 *      分段」（[openSegmentRetriever]／[ReplayBufferRecordController.findSegmentCovering]），標記
 *      時間點換算落在哪一段、跨段的球（8 秒片段剛好卡在 30 秒段落交界）逐幀偵測自動換開下一段的
 *      retriever 接續；既有 480x270/12fps 抽幀與 `breakFilter` 疊層機制完全沿用不動。任何分段
 *      開檔／收尾／清理／關鍵幀要求失敗皆 try-catch 吞掉，不得影響直播與主錄影（比照 v0.10.0
 *      錄影鐵律）。
 *   2. 測速曲線後半段有時不見：v0.12.2 的「時間戳超出量測時長就不畫進曲線」矯枉過正——網路中途
 *      變慢時，最後幾個區塊會在 10 秒後才傳完，整點被丟掉導致曲線後半段空白（見
 *      [SettingsActivity.runNetworkDetection]）。改成 `elapsedMs.coerceIn(0, NETWORK_TEST_DURATION_MS)`
 *      一律畫出（超時的點畫在圖表右邊界），數值仍照樣計入建議規格判斷。
 * - v0.14.0：一項新功能＋兩項修復（7/19 實戰前最後一輪）——
 *   1. 主隊 +3 慶祝演出下拉（`playHomePlus3Celebration`）：設定頁新增四選項（存
 *      `StreamPrefs.getHomePlus3Celebration`）取代 v0.11.3 頂部操作列的簡易開關——「衝撞影片」
 *      沿用既有 `playBullChargeAnimation`；「睡覺款圖」／「怒目款圖」為新增靜態圖片演出
 *      （`playHomeCelebrationAnimation`，素材 `assets/celebration/celebration_sleep.webp`／
 *      `celebration_angry.webp`，來源「各隊隊徽/睡覺去背1.png」「睡覺去背2.png」裁上緣
 *      68%／70%＋縮寬 400px 轉 WebP，睡覺款無字故另用 Python/Pillow 補畫金色 THREE! 字樣後才裁切）；
 *      「關閉」完全不播。走獨立的第三層 `ImageObjectFilterRender`（`celebrationFilter`／
 *      `celebrationAnimRunId`），序號作廢機制沿用 v0.12.2 bug 3／v0.13.0 breakFilter 的既有寫法，
 *      與 `bullFilter`/`breakFilter` 三套動畫互不干擾；圖片款是靜態圖非逐幀動畫，改用直接更新
 *      `setPosition`/`setRotation`/`setAlpha` 做「探出→晃動→縮回」，不像公牛動畫需要逐幀解碼。
 *      客隊 +3 一律無演出（沿用既有行為不變）。
 *      【已於 v0.14.1 移除】見下方 v0.14.1 條目。
 *   2. 開播按鈕過渡期狀態不明（Boss 實機回報）：原本 [setLiveUiState] 在 [beginRtmpStreaming]
 *      尾端就直接呼叫變紅字「收播」，但當下 RTMP 可能都還沒真正連上。改為三態流轉——按下開播
 *      確認立即呼叫 [setLiveButtonPending]（灰底不可按，文字「建立直播中…」）；真正連線成功
 *      （[onConnectionSuccess]）才呼叫 `setLiveUiState(true)` 變紅底白字「收播」可按；建立失敗或
 *      無備援退回（[startLiveStreamViaYouTubeApi] 的 `finally` 判斷 `!rtmpCamera2.isStreaming`、
 *      [startLiveStreamWithManualKey] 金鑰為空）則呼叫 `setLiveUiState(false)` 恢復可按的開播狀態。
 *      此三態流轉本身是 UX 功能非特效，v0.14.1 全數保留不動。
 *   3. 開播過渡期播 +3 動畫閃退（Boss 實機重現：按開播→直播建立中按 +3→動畫播放中直播正式開始→
 *      APP 閃退）：反編譯本機 RootEncoder 2.6.1 `library-2.6.1.aar`／`encoder-2.6.1.aar` 位元碼查證
 *      根因——`applyStreamSettingsAndStartPreview` 每次開播前都會 `stopPreview()`+`startPreview()`
 *      重新套用設定頁最新規格，內部呼叫到 `Camera2Base.stopCamera()`→`OpenGlView.stop()`；此函式
 *      在**呼叫端執行緒（Main thread）同步直接**呼叫 `MainRender.release()`，把 `filterRenders`
 *      （`java.util.ArrayList`，非執行緒安全）整個 `clear()` 並逐一釋放 GL 資源，完全沒有过 GL
 *      渲染執行緒的 `filterQueue` 佇列、也沒有任何鎖。同一支 `filterRenders` 同時被 GL 渲染執行緒
 *      （`OpenGlView` 內部單執行緒 `executor`）的 `draw()`→`MainRender.drawFilters()` 疊代讀取，
 *      若此時 +3 動畫（`bullFilter`／`celebrationFilter`）正在跑（透過 `addFilter`/`removeFilter`
 *      頻繁送 `ADD`/`REMOVE` 進 `filterQueue`，每幀 `draw()` 消化一筆），兩個執行緒同時碰同一個
 *      非執行緒安全 `ArrayList`——`stop()` 的 `clear()` 與 GL 執行緒疊代同時發生就會丟出
 *      `ConcurrentModificationException` 之類的例外；此例外發生在 GL 渲染執行緒、沒有任何 try-catch
 *      接住會一路重新拋出（`OpenGlView`/`GlStreamInterface` 的 `draw()` 包裝 lambda 只有
 *      `onRenderError` 回呼通知一次後仍 `throw e`），導致整個 APP 行程被系統判定未捕捉例外而閃退。
 *      平常 `filterRenders` 只有計分板濾鏡一個、變動頻率低，撞上這個窄競態視窗機率極低，
 *      因此只有「+3 動畫正在跑（filterRenders 頻繁增減）」同時「開播過渡期觸發這段 stop/restart」
 *      才會踩到。第三方函式庫二進位（aar）內部設計，不修改原始碼；治本＝呼叫
 *      `applyStreamSettingsAndStartPreview` 前先 `cancelAndJoin` 任何仍在跑的動畫
 *      （`cancelActiveCelebrationAnimations`，其 `finally` 用 `NonCancellable` 保證
 *      `removeFilter` 真正送出後才返回），確保 `stop()` 執行當下本 APP 這一側不會再有並發的
 *      `filterRenders` 增刪動作（`applyStreamSettingsAndStartPreview` 因此改為 `suspend fun`，
 *      呼叫端一併改走 `lifecycleScope.launch`）；治標＝開播過渡期（[isLiveTransitionPending]）
 *      +3 一律不觸發動畫（見上方修復 2、[changeScoreHome]），從源頭避免撞見這個視窗，兩層防護
 *      並存（`cancelActiveCelebrationAnimations` 仍保留給「非過渡期、動畫恰好在播放又觸發
 *      Surface 重建」等其餘會呼叫 `stop()` 的情境）。
 *      【已於 v0.14.1 移除】`cancelActiveCelebrationAnimations`／`bullFilter`／`celebrationFilter`
 *      連同治本/治標兩層防護整段拆除（查證確認 `cancelActiveCelebrationAnimations` 只服務這兩套
 *      動畫、breakFilter 從未依賴此函式，故無需保留任何部分，見下方 v0.14.1 條目）；
 *      `applyStreamSettingsAndStartPreview`／`beginRtmpStreaming`／`startLiveStreamWithManualKey`
 *      的 `suspend fun` 簽名維持不動（非本次任務範圍，改回同步簽名需連動多處呼叫點，風險大於效益）。
 * - v0.14.1：Boss 拍板兩項全部移除（本輪唯一任務）——
 *   1. **主隊 +3 特效全拆**：v0.11.0 公牛衝撞逐幀動畫（`playBullChargeAnimation`／`bullFilter`／
 *      `bullAnimRunId`／`isBullFilterAttached`／撞擊震動 `startScoreboardShake`／`shakeOffsetX`
 *      `shakeOffsetY`／`buildShakePaddedOverlayBitmap`／`SHAKE_PAD_X_RATIO`／`SHAKE_PAD_Y_RATIO`／
 *      `assets/bull/` 99 幀 WebP）與 v0.14.0 睡覺款/怒目款圖片慶祝演出（`playHomePlus3Celebration`／
 *      `playHomeCelebrationAnimation`／`loadCelebrationBitmap`／`celebrationFilter`／
 *      `celebrationAnimRunId`／`isCelebrationFilterAttached`／`cachedCelebrationBitmaps`／
 *      `interpolateKeyframes`／`stepKeyframe`／`computeHomeTeamNameCenterXPct`／各 `CELEBRATION_*`
 *      常數／`assets/celebration/`）整段刪除（不是註解掉）；設定頁「主隊 +3 慶祝演出」下拉選單
 *      （layout `spinnerHomePlus3Celebration`、`StreamPrefs.getHomePlus3Celebration`/
 *      `saveHomePlus3Celebration`/`KEY_HOME_PLUS3_CELEBRATION`/`CELEBRATION_CHARGE`等四常數、
 *      strings.xml `settings_home_plus3_celebration_label`/`home_plus3_celebration_options`）
 *      一併移除。`refreshScoreboardOverlay`/`applyScoreboardOverlayFilter` 改回直接呼叫
 *      `buildScoreboardOverlayBitmap()`（不再包一層震動 padding）。`changeScoreHome` 現在只單純
 *      加分，`delta==3` 不再觸發任何演出。`cancelActiveCelebrationAnimations` 整段移除（查證
 *      該函式只 `cancelAndJoin` `bullAnimJob`/`celebrationAnimJob` 兩個 Job，從未觸碰
 *      `breakPlaybackJob`/`breakFilter`，休息輪播並未依賴此函式，故無需保留任何部分）；
 *      `isLiveTransitionPending` 保留——它同時服務開播按鈕三態流轉（UX 功能非特效），只拿掉
 *      `changeScoreHome` 內「過渡期不觸發動畫」那段判斷式。+3/+2/+1/−1 計分本體、燒入計分板數字
 *      更新完全不動。
 *   2. **HTTP 遙控計分整個移除**（Boss 追加拍板，不是改成純加分而已）：`LiveControlServer.kt`／
 *      `assets/remote_control.html` 兩檔整支刪除；`LiveActivity` 內 `liveControlServer`／
 *      `remoteControlPairingCode`／`isRemoteControlInfoExpanded`／`setupRemoteControlInfo`／
 *      `refreshRemoteControlInfoDisplay`／`startRemoteControlServer`／`stopRemoteControlServer`／
 *      `remoteControlListener`／`getLocalIpAddress` 整段刪除，`beginRtmpStreaming`/`stopLiveStream`
 *      內對應的啟停掛鉤一併拿掉；直播畫面左上角 🎮 連線資訊小字（`tvRemoteControlInfo`）從
 *      `activity_live.xml` 移除；strings.xml `remote_control_info_format`/
 *      `remote_control_info_collapsed`/`remote_control_unavailable_message` 一併刪除。
 * - v0.15.0：Boss 拍板功能收斂＋兩項小改（7/17）——
 *   1. **休息畫面整組移除**：`setupBreakScreenButton`／`enterBreakMode`／`exitBreakMode`／
 *      `playBreakClips`／`openSegmentRetriever`／`showBreakStaticImage`／`buildBreakStaticBitmap`／
 *      `breakFilter` 等欄位／`BREAK_*` 常數／layout `btnBreakScreen`／break_screen 系列字串整段
 *      刪除；連同只服務休息回放的滾動分段暫存機制（`TeeRecordController.kt`／
 *      `ReplayBufferRecordController.kt` 兩檔刪除、onCreate 的 `setRecordController` tee 掛載與
 *      `mainRecordController` 一併拿掉），錄影控制器回歸框架內建預設（行為與 v0.10.0 一致）。
 *      精彩標記（功能 A）不受影響，照樣存檔供收播清單用。
 *   2. **精彩清單加「分享LINE」**（[shareHighlightsToLine]）：AlertDialog 第三顆 Neutral 鈕，
 *      章節文字（[buildHighlightChapterText]，與複製共用）以 ACTION_SEND 指定 LINE 包名直開
 *      （Manifest `<queries>` 宣告套件可見性）；LINE 未安裝退回系統分享面板。
 *   3. **頂部面板拉出時蓋過上方狀態文字**：`topPanelContainer.bringToFront()`（見
 *      [setupCollapsiblePanels]），溫度/變焦/版本號等 TextView 在 layout 宣告較晚、原本繪製在
 *      面板之上；文字不隱藏，拉出時被面板遮住即可。
 *   4. 另確認藍牙遙控從未實作（v0.13.0 計畫即拍板不做藍牙版），HTTP 遙控已於 v0.14.1 刪除，無殘留。
 *   5. **直播中閃退根因修復**（v0.12.2 bug 2 黑盒子開花結果）：adb 撈出 crashlog 6 筆
 *      （2026-07-16，跨 v0.13.0/v0.14.0）全部同一堆疊——GL 渲染執行緒
 *      `SurfaceTexture.updateTexImage` 丟 `IllegalStateException: Unable to update texture
 *      contents`（`OpenGlView.onFrameAvailable` lambda → `draw()`），與 +3 動畫無關（拆掉特效
 *      的 v0.14.x 也會發生）。反編譯 aar 查證該 lambda 的 catch(RuntimeException) 分支：
 *      `renderErrorCallback` 為 null 直接 athrow（執行緒未捕捉→整個 APP 閃退＝舊行為）；掛上
 *      回呼即改走通知、不再拋出。修法＝onCreate 掛 [RenderErrorCallback]（函式庫官方掛鉤），
 *      壞一幀跳過一幀：寫 render_ 前綴記錄檔（[CrashLogger.writeRenderErrorLog]，30 秒節流）＋
 *      一場一次 Toast，直播/錄影不中斷；[CrashLogger.notifyIfNewCrashLogExists] 只認 crash_
 *      前綴，渲染記錄不會誤報成當機。
 * - v0.15.1：孤兒直播回收（Boss 回報：APP 當掉後 YouTube 端一直掛「直播中」）——`enableAutoStop
 *   =false`（v0.12.0 為避免重連被 YT 收台）的代價是 APP 死掉時沒人送結束指令。修法：開播成功把
 *   broadcastId 落地（[StreamPrefs.savePendingBroadcastId]），只有 YouTube 確認收到
 *   `transition(complete)` 才清除；每次進直播頁（onCreate）由 [endOrphanBroadcastIfAny] 檢查
 *   殘留 ID 並補送結束指令（成功 Toast／YT 明確拒絕靜默清掉／網路失敗留待下次啟動再試）。
 * - v0.15.2：章節文字（複製／分享LINE 共用，[buildHighlightChapterText]）開頭固定補
 *   「00:00 開場」——YouTube 章節規定第一行必須 00:00，貼上說明欄直接生效。
 * - v0.15.3：⭐標記鈕從左側欄移到右下客隊計分區——跟 −1 同一排（⭐在最內側＝+1 正上方、
 *   −1 維持貼齊右緣），常駐顯示不再跟著左側欄收合，直播中隨手可按（layout 改動，程式邏輯不變）。
 * - v0.15.4：⭐標記鈕加寬 42→88dp（＝+1 左緣到 +2 右緣的跨度，橫跨 +1／+2 上方），高度與 −1
 *   同排對齊，Boss 指定尺寸（layout 改動）。
 * - v0.15.5：清掉已移除功能殘留在 SharedPreferences 的死鍵（`home_plus3_celebration`／
 *   `bull_anim_enabled`，現存程式碼已無讀寫）——啟動時 [StreamPrefs.purgeDeprecatedKeys]
 *   （由 [BasketballLiveApplication.onCreate] 呼叫）移除，不影響任何行為。
 * - v0.15.6：移除設定頁「使用位元變動率（VBR）」開關——反編譯 `BitrateManager` 查證該開關實質
 *   只調碼率「顯示」的指數平滑係數（`setBitrateExponentialFactor`），碰不到編碼器、對畫質／
 *   錄影零影響，名不副實，Boss 拍板拿掉。`setBitrateExponentialFactor` 固定 0.5f 維持原顯示觀感；
 *   `switchVbr`／`settings_vbr_label`／`StreamPrefs.isVbr`／`KEY_VBR` 全刪，`vbr` 鍵併入死鍵清理。
 * - v0.16.0：三項新功能（計畫書 `計畫書_標題模板_一鍵低延遲_休息畫面_2026-07-18_v1.1.md`）——
 *   1. 直播標題雙模板（設定頁）：不影響本檔，開播標題仍取自 [StreamPrefs.getStreamTitleOrDefault]。
 *   2. 不延遲最低設定一鍵（設定頁）：不影響本檔。
 *   3. **休息畫面（黑底四節計分表，樣式09）**：左下「休息畫面」鈕（[setupBreakScreenButton]，與右下
 *      ⭐鈕鏡像對稱），僅直播中可用。按下 [enterBreakMode]：把全螢幕休息畫面濾鏡
 *      （[breakScreenFilter]，Canvas 繪黑底＋樣式09 面板，做法同計分板 overlay，見 [buildBreakScreenBitmap]）
 *      addFilter 疊在最上層蓋住鏡頭與計分板，開 `OpenGlView.setForceRender(true, fps)` 讓 GL 在相機停後
 *      仍以固定 fps 續送幀（推流不中斷），再反射呼叫 `Camera2ApiManager.closeCamera(false)` **只停相機
 *      擷取降溫**——不能用公開 `stopCamera()`，反編譯 library-2.6.1.aar 查證它會連帶 `glInterface.stop()`
 *      停掉整個 GL 送幀（RTMP 會斷）；`closeCamera(false)` 保留 surfaceEncoder 不重建，供 [resumeCameraAfterBreak]
 *      依框架 `reOpenCamera` 既有序列（`prepareCamera(surfaceEncoder, fps)`＋`openCameraId(cameraId)`，因
 *      closeCamera 後 cameraDevice 為 null 無法直接呼叫 reOpenCamera，故複刻其內部序列）恢復。反射任一步
 *      失敗一律 try/catch 退回「只遮畫面、相機不停」（順位3，無降溫但 force render 仍保推流不斷，
 *      v0.13.0 已實戰驗證此保底路線可行）＋ replaceView 兜底。再按同鈕 [exitBreakMode] 反向恢復。
 *      各節分數在切節數當下自動結算（[changePeriod]／[settleQuarterOnAdvance]／[unsettleQuarterOnRewind]），
 *      [StreamPrefs] 持久化（[quarterScoresHome]/[quarterScoresAway]），開播勾「重置計分板」一併清空。
 *      注意 memory 技術備忘：加/刪 filter 走 filterQueue（BlockingQueue，執行緒安全），避開 stop()/clearFilters
 *      主執行緒 clear filterRenders 的 ConcurrentModificationException 坑。
 * - v0.16.2：休息畫面依 Boss 實測回饋三項修正——1. 計分表由樣式09 改**樣式06 中央對決式**（兩隊
 *   隊名＋大總分金方塊左右對決、金色 VS 居中、四節明細小表在下，原左欄賽事名稱欄取消，見
 *   [buildBreakScreenBitmap]）；2. 「休息畫面」鈕改成**預覽時也能切**（原本僅直播中可用，Boss 在家
 *   預覽測試按了只跳提示以為壞掉）——預覽切休息只遮畫面不停相機（無恢復風險），真正直播中才反射停
 *   相機降溫；3. 預覽切著休息時重啟預覽（進出設定頁等）先 [exitBreakMode] 防濾鏡鏈重建後狀態不一致。
 * - v0.16.3：Boss 直播實測——**順位2 停相機宣告失敗、先停用**：停相機後畫面凍結在按下當下的最後
 *   一幀（預覽切休息正常、直播中失效的差異點就在停相機），`setForceRender` 在 Camera2Base 舊管線
 *   沒有真的驅動 GL 續送幀，濾鏡佇列不被消化、休息畫面套不上，中場久了 YT 還會判定斷流。
 *   [BREAK_STOP_CAMERA_ENABLED]=false 一律走順位3 只遮畫面（無降溫，Boss 已知情），停/恢復
 *   程式碼保留待日後查出正確用法再開回。另修設定頁模板單選鈕兩顆同亮（RadioButton 隔了一層
 *   LinearLayout，RadioGroup 互斥失效，改手動互斥，見 SettingsActivity.selectTitleTemplate）＋
 *   休息鈕文字自動縮字強制單行（大字體手機 4 字被裁成「休息畫」）。
 */
class LiveActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var rtmpCamera2: RtmpCamera2

    private var scoreHome = 0
    private var scoreAway = 0
    private var foulHome = 0
    private var foulAway = 0
    private var period = 1
    private var teamHomeName = ""
    private var teamAwayName = ""
    // v0.6.0：燒入計分板左側區塊的賽事名稱，設定頁編輯／存檔（見 StreamPrefs.getEventName/saveEventName）
    private var eventName = ""

    private var exposureValue = 0
    private var isLive = false
    private var isFocusLocked = false
    private var hasCameraPermissions = false
    private var currentZoomLevel = 1f

    // v0.5.1：點觸／長按對焦手勢（見 setupFocusGestures/handleFocusGesture）
    private lateinit var focusGestureDetector: GestureDetector
    private var pendingFocusSettleJob: Job? = null

    // 開播流程狀態：建立 YouTube 直播中避免重複點擊；已建立成功才有分享連結
    private var isCreatingBroadcast = false
    private var currentWatchUrl: String? = null
    // v0.12.0：帳號模式建立直播成功後記住 broadcastId／youtube 服務物件，收播時呼叫
    // endYouTubeBroadcastIfNeeded 正式結束該場直播（見類別頂端 KDoc／YouTubeLiveRepository）
    private var currentBroadcastId: String? = null
    private var currentYouTubeService: YouTube? = null

    // 燒入直播畫面的計分板濾鏡；分數/節數/隊名變動時重繪 Bitmap 更新此濾鏡即可，不需重建。
    private val scoreboardOverlayFilter = ImageObjectFilterRender()
    private var streamWidthForOverlay = 0
    private var streamHeightForOverlay = 0

    // v0.14.0：工作2——開播按鈕過渡期（按下開播確認～onConnectionSuccess／建立失敗退回之間），
    // 控制 btnLiveToggle 的灰色不可按狀態（見 setLiveButtonPending/setLiveUiState）。UX 功能，
    // v0.14.1 移除 +3 特效後仍保留（原本工作3 治標「此期間 +3 不觸發動畫」的用途已隨特效移除）。
    private var isLiveTransitionPending = false

    // v0.7.0：直播中碼率自動調整，見類別頂端 KDoc 與 [onNewBitrate]。
    // Listener 只在真正串流中才呼叫 setVideoBitrateOnFly，避免收播後殘留回呼誤觸底層 API。
    private val bitrateAdapter = BitrateAdapter { adaptedBitrate ->
        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.setVideoBitrateOnFly(adaptedBitrate)
            // v0.17.0（第一階段第3項）：記錄 adapter 目標與實際傳入 setVideoBitrateOnFly 的值（此處相同）。
            // 純觀測，不改 BitrateAdapter 本身。
            DiagLogger.log(this, "BITRATE", "adapter 目標=$adaptedBitrate → setVideoBitrateOnFly=$adaptedBitrate")
        } else {
            DiagLogger.log(this, "BITRATE", "adapter 目標=$adaptedBitrate（未串流，略過 setVideoBitrateOnFly）")
        }
    }
    // v0.17.2（Phase 2）：每次 RTMP 連線成功後建立獨立的 adapter 暖機 epoch；期間仍保留
    // raw/UI/hasCongestion/診斷，只略過 adaptBitrate，避免連線初期的暫態壅塞連續砍低碼率。
    private val bitrateAdaptationWarmup = BitrateAdaptationWarmup(BITRATE_ADAPTATION_WARMUP_MS)
    private val reconnectRecoveryGate = ReconnectRecoveryGate(
        RECONNECT_RECOVERY_TIMEOUT_MS, RECONNECT_RECOVERY_HEALTHY_RATIO, RECONNECT_RECOVERY_HEALTHY_SAMPLES
    )
    private var reconnectRecoveryWatchdogJob: Job? = null
    private var currentRtmpUrl: String? = null
    private var isStreamPipelineRebuilding = false

    // v0.10.0：同步錄影備份狀態，見類別頂端 KDoc 與 [startRecordIfEnabled]
    private var isRecordingActive = false
    private var currentRecordDestination = RecordDestination.NONE
    private var currentRecordFilePathOrName: String? = null
    // 相簿 MediaStore 錄影用的待清 pending Uri（收播／onDestroy 都要清，見計畫書已知風險段）
    private var pendingGalleryRecordUri: Uri? = null
    // 相簿(API29+)／自訂資料夾都經由 ParcelFileDescriptor 交給 startRecord(fd,...)，
    // MediaMuxer 不會自己關閉底層 fd（已查證 AndroidMuxerRecordController 原始碼），
    // 呼叫端要自行在收尾時 close()
    private var openRecordFileDescriptor: ParcelFileDescriptor? = null

    // v0.13.0：功能 A 精彩時刻標記——見類別頂端 KDoc、HighlightMarker.kt
    private val highlightMarkers = mutableListOf<HighlightMarker>()
    private var highlightSessionTimestamp: String = HighlightStore.newSessionTimestamp()
    // 本場直播開始的 SystemClock.elapsedRealtime()，標記／回放定位皆以此為基準換算「直播經過時間」
    private var liveStartElapsedMs: Long = 0L

    // v0.16.0：功能三——休息畫面四節計分表。各節已結算分數（-1＝該節尚未結算），
    // 切節數當下結算（見 changePeriod），StreamPrefs 持久化防閃退／重開恢復。
    private var quarterScoresHome = IntArray(StreamPrefs.QUARTER_COUNT) { -1 }
    private var quarterScoresAway = IntArray(StreamPrefs.QUARTER_COUNT) { -1 }
    // 休息畫面狀態：是否休息中、全螢幕休息畫面濾鏡（做法同計分板 overlay，見 buildBreakScreenBitmap）
    private var isBreakMode = false
    private val breakScreenFilter = ImageObjectFilterRender()
    // 休息中是否成功停了相機（順位2）；恢復時據此決定是否要重開相機（見 stopCameraForBreak/resumeCameraAfterBreak）
    private var breakCameraStopped = false
    private var breakSavedCameraId: String? = null

    // v0.15.0：GL 渲染錯誤（見 onCreate 的 setRenderErrorCallback）——記錄節流時間戳只在
    // OpenGlView 單一執行緒 executor 上讀寫，不需鎖；Toast 一場只跳一次
    private var lastRenderErrorLogMs = 0L
    private var hasShownRenderErrorToast = false

    // v0.12.0：AE/AF 同步鎖定——裝置是否支援 AE 鎖定/測光區域，第一次用到時查一次並快取
    // （同一台裝置的相機硬體能力不會變，見 isAeLockSupported）；反射任一步失敗只提示一次。
    // v0.17.0（第一階段第1項）：AE 鎖定能力與 AE 測光區域能力拆開快取——支援 AE 鎖定但不支援測光
    // 區域的裝置，曝光鎖仍要生效（原本綁在一起會害這種裝置整組跳過），見 isAeLockSupported/isAeRegionSupported。
    private var aeLockSupported: Boolean? = null
    private var aeRegionSupported: Boolean? = null
    private var hasWarnedAeSyncFailed = false

    // v0.17.0（第一階段第4/5項）：碼率警告狀態與 raw 數值拆開——raw 照常顯示，警告狀態改用 EWMA＋
    // 連續樣本判定。進出休息都重設 epoch；離開休息後前兩筆有效樣本顯示「恢復中」；連線成功前顯示
    // 「連線中」不把暖機低碼率當正式異常。有效樣本＝直播中、非休息、actualBps>0（見 updateBitrateStatusDisplay）。
    private var bitrateEwmaBps = 0.0
    private var bitrateValidSampleCount = 0
    private var bitrateShowRecoveringGrace = false
    private var isConnectionEstablished = false
    // v0.17.1（第一階段審查第4項）：警告狀態連續樣本遲滯——已提交顯示的狀態與待切換狀態＋連續筆數，
    // 需連續 BITRATE_WARNING_STABLE_SAMPLES 筆一致才換色，避免 EWMA 在門檻附近反覆閃色。
    private var bitrateWarningState = ""
    private var bitratePendingWarningState = ""
    private var bitratePendingWarningCount = 0

    // v0.12.0：斷線重連強化——等待中的重連 coroutine／狀態，見 scheduleReconnect／
    // performReconnectAttempt／registerNetworkCallbackForReconnect（類別頂端 KDoc）
    private var reconnectJob: Job? = null
    private var reconnectAttemptCount = 0
    private var isWaitingToReconnect = false
    private var lastDisconnectReason = ""
    private var reconnectNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager: ConnectivityManager? by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    /**
     * v0.10.0：`RecordController.Listener` 回呼可能來自背景執行緒（`recordVideo()`/`recordAudio()`
     * 由編碼器回呼觸發，比照 [onNewBitrate] 慣例，見類別頂端 KDoc）。`onStatusChange` 這裡不需要
     * 額外處理（此介面沒有預設實作，僅為滿足抽象方法簽名）；`onError` 才是鐵律「錄影死掉絕不能
     * 拖垮直播」的實際落點——只跳 Toast＋拿掉指示燈「｜REC」，直播完全不受影響。
     */
    private val recordControllerListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {}

        override fun onError(e: Exception) {
            runOnUiThread {
                handleRecordFailure(e.message ?: getString(R.string.record_unknown_error_message))
            }
        }
    }

    // v0.10.1：右上角電池溫度顯示——ACTION_BATTERY_CHANGED 是黏性廣播（sticky），
    // onStart 註冊當下就會立刻收到最近一次的電池狀態，之後溫度變化時系統會再推播，
    // 不需要自己開計時器輪詢；只在畫面前景期間註冊（onStart/onStop 配對）。
    private var hasShownOverheatToast = false
    private val batteryTempReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tenthsOfCelsius = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tenthsOfCelsius <= 0) return
            updateTemperatureDisplay(tenthsOfCelsius / 10.0)
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.CAMERA] == true &&
            results[Manifest.permission.RECORD_AUDIO] == true
        if (granted) {
            onCameraPermissionsGranted()
        } else {
            hasCameraPermissions = false
            binding.tvCameraPlaceholder.visibility = View.VISIBLE
            Toast.makeText(
                this,
                "需要相機與麥克風權限才能直播，請至系統設定開啟權限",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // v0.12.0：Android 13+ 通知權限（POST_NOTIFICATIONS）——只影響 LiveForegroundService
    // 常駐通知是否顯示，不影響相機/麥克風/推流功能，被拒也不擋開播，因此獨立於上面相機/麥克風
    // 必要權限之外，用另一個 launcher 靜默請求，見 requestNotificationPermissionIfNeeded。
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 被拒也不影響直播功能，前景服務仍會啟動，只是沒有通知可看，不需額外處理 */ }

    // Google 授權需要使用者互動確認時（例如 OAuth 測試模式 7 天到期）導向系統授權畫面；
    // 使用者處理完回到本畫面後，再按一次開播即可（不在此自動重試，避免無窮迴圈）。
    private val authRecoveryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetsAsPadding()
        binding.root.clearAllButtonTints()
        binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"

        teamHomeName = StreamPrefs.getTeamHomeName(this).ifEmpty { getString(R.string.team_home_default) }
        teamAwayName = StreamPrefs.getTeamAwayName(this).ifEmpty { getString(R.string.team_away_default) }
        eventName = StreamPrefs.getEventName(this)
        binding.tvTeamHomeName.text = teamHomeName
        binding.tvTeamAwayName.text = teamAwayName

        rtmpCamera2 = RtmpCamera2(binding.openGlView, this)
        rtmpCamera2.getStreamClient().setReTries(RECONNECT_MAX_RETRIES)
        // v0.17.7：RootEncoder FpsListener 計算直播編碼器每秒實際輸出幀數。純觀測，不限幀、不改參數。
        rtmpCamera2.setFpsListener { actualFps ->
            DiagLogger.log(
                this, "FPS-STREAM",
                "encodedFps=$actualFps target=${StreamPrefs.getFps(this)} recording=${rtmpCamera2.isRecording}"
            )
        }
        // v0.15.0：根治直播中閃退（crashlog 6 筆全同一堆疊：GL 渲染執行緒 SurfaceTexture.updateTexImage
        // 丟 IllegalStateException「Unable to update texture contents」）——反編譯 library-2.6.1.aar
        // 查證 OpenGlView.onFrameAvailable 的 draw() 有 try-catch(RuntimeException)：renderErrorCallback
        // 為 null 才 athrow（＝之前的閃退路徑）；掛上回呼後改走通知、【不再拋出】，壞一幀跳過一幀，
        // 下一幀照常渲染（紋理競態是暫態，通常自行恢復）。回呼在 GL 執行緒上，Toast 要轉主執行緒。
        // ponytail: 只記錄＋跳過；若實測出現「連續壞幀畫面凍結」再升級成自動重啟預覽
        binding.openGlView.setRenderErrorCallback(object : RenderErrorCallback {
            override fun onRenderError(e: RuntimeException) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRenderErrorLogMs >= RENDER_ERROR_LOG_THROTTLE_MS) {
                    lastRenderErrorLogMs = now
                    CrashLogger.writeRenderErrorLog(this@LiveActivity, Thread.currentThread(), e)
                }
                if (!hasShownRenderErrorToast) {
                    hasShownRenderErrorToast = true
                    runOnUiThread {
                        Toast.makeText(this@LiveActivity, getString(R.string.render_error_skipped_toast), Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        // 相機一定要等預覽表面（Surface）就緒才能啟動，否則權限已授權的情況下
        // 二次開啟 APP 會因為搶在表面建立前開相機而閃退。
        //
        // 【直播中黑屏修復】OpenGlView 本身在建構子就已 addCallback(this) 掛了自己的
        // SurfaceHolder.Callback（見 RootEncoder 2.4.9 原始碼 OpenGlView.java）：
        // - surfaceDestroyed() 一律呼叫內部 stop()：釋放 mainRender/EGL Surface、關閉渲染執行緒，
        //   且完全不管 Camera2Base 是否還在直播中（stop() 內沒有檢查 streaming 狀態）。
        // - surfaceChanged() 只更新 previewWidth/previewHeight，「不會」重新呼叫 start() 接回新 Surface。
        // 因此分享面板、設定頁等造成系統重建 Surface 的情境下，直播中會變成：
        // 渲染管線已經停了、卻沒有人叫它接回新 Surface → 本機預覽黑屏（RTMP 連線與 streaming 旗標
        // 完全沒被動到，YouTube 端仍持續收到最後畫面，才會看起來像「只有本地看不到」）。
        // 解法採用 Camera2Base 公開的 replaceView(OpenGlView)：內部 replaceGlInterface() 只要
        // isStreaming()/isOnPreview() 任一為真，就會關閉相機→重新走 prepareGlView()（重新抓
        // getHolder().getSurface() 目前這顆有效 Surface 並 start()、重新掛上編碼器輸入 Surface）
        // →重新開相機；全程不呼叫 stopStreamImp()/startStreamImp()，RTMP 連線不中斷、不必重連。
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!hasCameraPermissions) return
                if (rtmpCamera2.isStreaming) {
                    // 只有在 OpenGlView 內部渲染管線真的被系統停掉時才需要重接，
                    // 避免正常運作中的直播被無謂地關開相機一次造成閃爍。
                    if (!rtmpCamera2.getGlInterface().isRunning) {
                        lifecycleScope.launch {
                            rtmpCamera2.replaceView(binding.openGlView)
                            // replaceView 內部會釋放 mainRender（含濾鏡鏈），計分板燒入濾鏡要重新掛回去，
                            // 否則畫面接回來後計分板會消失。
                            applyScoreboardOverlayFilter()
                        }
                    }
                } else if (!rtmpCamera2.isOnPreview) {
                    lifecycleScope.launch { applyStreamSettingsAndStartPreview() }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (!rtmpCamera2.isStreaming && rtmpCamera2.isOnPreview) {
                    rtmpCamera2.stopPreview()
                }
                // 直播中：刻意不呼叫 stopPreview／stopCamera，讓 Camera2Base 的 streaming/onPreview
                // 狀態維持不變；OpenGlView 自己的 callback 會處理內部渲染管線的釋放，
                // 之後交給上面 surfaceChanged 的 replaceView 邏輯重新接回。
            }
        })

        setupScoreButtons()
        setupTopOperationButtons()
        setupTeamNameEditing()
        setupLiveToggle()
        setupShareButton()
        setupResetScoresButton()
        setupExposureControls()
        setupFocusLock()
        setupFocusGestures()
        setupSettingsEntry()
        setupCloseAppButton()
        setupCollapsiblePanels()
        setupBackPressHandling()
        setupHighlightMarkButton()
        setupBreakScreenButton()
        // v0.16.0：功能三——還原上次各節結算分數（閃退／重開恢復，見 StreamPrefs）
        quarterScoresHome = StreamPrefs.getQuarterScoresHome(this)
        quarterScoresAway = StreamPrefs.getQuarterScoresAway(this)
        // v0.15.1：上一場若因當機/被殺沒收播，補送 YouTube 結束指令（見 endOrphanBroadcastIfAny）
        endOrphanBroadcastIfAny()

        checkAndRequestCameraPermissions()
    }

    override fun onStart() {
        super.onStart()
        // v0.10.1：黏性廣播——註冊當下就會收到最近一次電池狀態，溫度顯示立即有值
        registerReceiver(batteryTempReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // 設定頁只有在未直播時才能進入，回到本畫面時重新讀取賽事名稱，
        // 確保剛在設定頁存的賽事名稱能立即反映在燒入計分板上。
        eventName = StreamPrefs.getEventName(this)
        if (hasCameraPermissions && !rtmpCamera2.isStreaming && !rtmpCamera2.isOnPreview) {
            lifecycleScope.launch { applyStreamSettingsAndStartPreview() }
        } else {
            refreshScoreboardOverlay()
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(batteryTempReceiver)
        } catch (e: Exception) {
            // 重複解除註冊不影響流程
        }
        // 未在直播中才釋放相機，避免離開設定頁再回來時鏡頭被占用；直播中則保持不動。
        if (!rtmpCamera2.isStreaming && rtmpCamera2.isOnPreview) {
            rtmpCamera2.stopPreview()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // v0.10.0：App 中途被殺也要清 MediaStore pending／關閉錄影 fd（計畫書已知風險段）
        if (isRecordingActive) {
            try {
                rtmpCamera2.stopRecord()
            } catch (e: Exception) {
                // 收尾失敗不影響結束流程
            }
            finalizeRecordAfterStop()
            isRecordingActive = false
        }
        if (rtmpCamera2.isStreaming) rtmpCamera2.stopStream()
        if (rtmpCamera2.isOnPreview) rtmpCamera2.stopPreview()
        // v0.12.0：斷線重連——App 結束時一併收掉還在等待的重連 coroutine 與網路callback，避免洩漏
        reconnectJob?.cancel()
        unregisterNetworkCallbackForReconnect()
    }

    // ---------- 相機權限 ----------

    private fun checkAndRequestCameraPermissions() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && audioGranted) {
            onCameraPermissionsGranted()
            // v0.12.0：相機/麥克風已授權過的舊使用者（例如升級版本後重開）也要補問一次通知權限
            requestNotificationPermissionIfNeeded()
        } else {
            // v0.12.0：首次請求一併帶入通知權限（Android 13 以下系統會自動視為已授予，不會多跳提示框）
            val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    /**
     * v0.12.0：Android 13+ 通知權限——只影響 [LiveForegroundService] 常駐通知是否顯示，
     * 拒絕不影響直播功能，因此獨立靜默請求一次，不重複打擾（已授權過就直接跳過）。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun onCameraPermissionsGranted() {
        hasCameraPermissions = true
        binding.tvCameraPlaceholder.visibility = View.GONE
        lifecycleScope.launch { applyStreamSettingsAndStartPreview() }
    }

    // ---------- 串流規格套用 / 相機預覽 ----------

    /**
     * 從設定頁讀出解析度/fps/碼率，套用到 RootEncoder 編碼器，並啟動相機預覽。
     * 直播中呼叫會被忽略，避免中斷正在進行的直播畫面。
     * v0.14.0 工作3 曾改為 suspend fun 是為了在此開頭 cancelAndJoin 任何仍在跑的 +3 動畫
     * （避免清空濾鏡鏈時撞上並發增刪的競態，詳見類別頂端 KDoc）；v0.14.1 移除 +3 特效後該呼叫
     * 已拿掉，`suspend` 簽名維持不動（非本次任務範圍，改回同步簽名需連動多處呼叫點）。
     */
    private suspend fun applyStreamSettingsAndStartPreview() {
        if (!hasCameraPermissions) return
        if (rtmpCamera2.isStreaming) return
        // v0.16.2：預覽切著休息畫面時重啟預覽（進出設定頁／開播前重套規格）會重建濾鏡鏈，
        // 先正常退出休息畫面，避免 isBreakMode 掛著但濾鏡已被清掉的不一致狀態
        if (isBreakMode) exitBreakMode()
        // 表面尚未就緒時先不啟動，等 surfaceChanged 回呼再啟動
        if (!binding.openGlView.holder.surface.isValid) return

        if (rtmpCamera2.isOnPreview) {
            rtmpCamera2.stopPreview()
        }

        val (width, height) = StreamPrefs.parseResolution(StreamPrefs.getResolution(this))
        val fps = StreamPrefs.parseFps(StreamPrefs.getFps(this))
        val bitrate = StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this))

        // v0.15.6：VBR 開關已移除（實質只是碼率「顯示」的指數平滑係數，反編譯確認碰不到編碼器，
        // 名不副實故 Boss 拍板拿掉）——固定用 0.5f 維持左上角實際碼率數字的平滑觀感（沿用原
        // VBR 開啟時的預設值，不改變既有顯示行為）。此係數只影響 BitrateManager 回報數字，
        // 對畫質／錄影／實際編碼零影響。
        rtmpCamera2.getStreamClient().setBitrateExponentialFactor(0.5f)
        // v0.17.4：斷網復線改用 Java socket。實機證據顯示預設 Ktor CIO socket 在網路恢復後
        // 反覆發生讀寫 SocketTimeoutException；只替換 socket 實作，保留既有 reTry 與暖機狀態機。
        rtmpCamera2.getStreamClient().setSocketType(SocketType.JAVA)

        // v0.18.10：手機端錄影正式固定 1080p30／20 Mbps；直播 FPS 仍沿用直播設定。
        val recordingEnabled = StreamPrefs.isRecordEnabled(this)
        val prepared = try {
            if (recordingEnabled) {
                val captureSize = android.util.Size(maxOf(width, RECORD_WIDTH), maxOf(height, RECORD_HEIGHT))
                val supportedFps = runCatching {
                    rtmpCamera2.getSupportedFps(captureSize, rtmpCamera2.cameraFacing)
                }.getOrElse { emptyList() }
                DiagLogger.log(
                    this, "RECORD-CONFIG",
                    "fixed=${RECORD_WIDTH}x${RECORD_HEIGHT} fps=$RECORD_FPS bitrate=$RECORD_BITRATE_BPS " +
                        "codec=H264 liveFps=$fps capture=$captureSize supportedFps=$supportedFps"
                )
                val videoPrepared = rtmpCamera2.prepareVideo(width, height, fps, bitrate, 0)
                val recordPrepared = videoPrepared && prepareFixedRecordEncoder()
                if (recordPrepared) setStreamEncoderReportedFps(RECORD_FPS)
                recordPrepared && rtmpCamera2.prepareAudio(AUDIO_BITRATE, AUDIO_SAMPLE_RATE, true)
            } else {
                rtmpCamera2.prepareVideo(width, height, fps, bitrate, 0) &&
                    rtmpCamera2.prepareAudio(AUDIO_BITRATE, AUDIO_SAMPLE_RATE, true)
            }
        } catch (e: Exception) {
            DiagLogger.log(this, "RECORD-CONFIG", "準備失敗：${e.javaClass.simpleName}:${e.message}")
            false
        }
        if (!prepared) {
            Toast.makeText(
                this,
                "相機或麥克風設定失敗，請確認裝置支援目前選擇的串流規格",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 串流解析度確定後才知道計分板燒入濾鏡該用多大比例繪製，這裡建立並掛上濾鏡。
        streamWidthForOverlay = width
        streamHeightForOverlay = height
        applyScoreboardOverlayFilter()

        if (!rtmpCamera2.isOnPreview) {
            rtmpCamera2.startPreview()
            if (recordingEnabled) restoreLiveEncoderReportedFps(fps)
            lifecycleScope.launch {
                delay(1_000L)

                logVideoPipelineDiagnostics(fps)
            }
        }
    }

    /**
     * v0.10.0：獨立錄影解析度的固定碼率（YAGNI：不另開選項，見計畫書功能包①）。
     * v0.10.4：新增 2K（[RECORD_BITRATE_2K_BPS]）與 4K（[RECORD_BITRATE_4K_BPS]）。
     */
    /**
     * v0.17.7：記錄 Camera2 實際 fps/range、GL 類型，以及直播／錄影兩顆編碼器的準備參數。
     * 反射欄位均已用 RootEncoder 2.6.1 AAR 查證；失敗只寫診斷，不影響直播。
     */
    private fun logVideoPipelineDiagnostics(requestedFps: Int) {
        runCatching {
            val baseClass = Camera2Base::class.java
            val cameraManager = baseClass.getDeclaredField("cameraManager").apply { isAccessible = true }
                .get(rtmpCamera2)
            val cameraClass = cameraManager.javaClass
            val cameraFps = cameraClass.getDeclaredField("fps").apply { isAccessible = true }
                .getInt(cameraManager)
            val builder = cameraClass.getDeclaredField("builderInputSurface").apply { isAccessible = true }
                .get(cameraManager) as? CaptureRequest.Builder
            val appliedRange = builder?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
            val differentRecord = baseClass.getDeclaredField("differentRecordResolution").apply { isAccessible = true }
                .getBoolean(rtmpCamera2)
            val streamEncoder = baseClass.getDeclaredField("videoEncoder").apply { isAccessible = true }
                .get(rtmpCamera2) as com.pedro.encoder.video.VideoEncoder
            val recordEncoder = baseClass.getDeclaredField("videoEncoderRecord").apply { isAccessible = true }
                .get(rtmpCamera2) as com.pedro.encoder.video.VideoEncoder
            DiagLogger.log(
                this, "VIDEO-PIPELINE",
                "requestedFps=$requestedFps cameraFps=$cameraFps appliedRange=$appliedRange " +
                    "differentRecord=$differentRecord gl=${rtmpCamera2.glInterface.javaClass.simpleName} " +
                    "stream=${streamEncoder.width}x${streamEncoder.height}@${streamEncoder.fps}/${streamEncoder.bitRate} " +
                    "record=${recordEncoder.width}x${recordEncoder.height}@${recordEncoder.fps}/${recordEncoder.bitRate}"
            )
        }.onFailure {
            DiagLogger.log(this, "VIDEO-PIPELINE", "診斷反射失敗：${it.javaClass.simpleName}:${it.message}")
        }
    }
    /** 獨立準備錄影編碼器並把 Surface 加入既有 GL 管線，不重設直播編碼器 Surface。 */
    private fun prepareFixedRecordEncoder(): Boolean {
        val baseClass = Camera2Base::class.java
        val recordEncoder = baseClass.getDeclaredField("videoEncoderRecord").apply { isAccessible = true }
            .get(rtmpCamera2) as com.pedro.encoder.video.VideoEncoder
        val prepared = recordEncoder.prepareVideoEncoder(
            RECORD_WIDTH, RECORD_HEIGHT, RECORD_FPS, RECORD_BITRATE_BPS,
            0, 2, com.pedro.encoder.video.FormatVideoEncoder.SURFACE
        )
        if (!prepared) return false
        baseClass.getDeclaredField("differentRecordResolution").apply { isAccessible = true }
            .setBoolean(rtmpCamera2, true)
        rtmpCamera2.glInterface.addMediaCodecSurface(recordEncoder.inputSurface)
        return true
    }

    /** 只暫時改 Camera2 開啟時讀取的 FPS，不 reset、不更換直播 Surface。 */
    private fun setStreamEncoderReportedFps(fps: Int) {
        val field = Camera2Base::class.java.getDeclaredField("videoEncoder").apply { isAccessible = true }
        val encoder = field.get(rtmpCamera2) as com.pedro.encoder.video.VideoEncoder
        encoder.setFps(fps)
        encoder.setForceFps(fps)
    }

    private fun restoreLiveEncoderReportedFps(liveFps: Int) {
        runCatching { setStreamEncoderReportedFps(liveFps) }.onFailure {
            DiagLogger.log(this, "RECORD-CONFIG", "還原直播 FPS 失敗：${it.message}")
        }
    }

    /**
     * 曾用於 1080p60 技術尖峰：將一般 Camera2 Session 換成 constrained high-speed Session。
     * 僅替換相機擷取 Session，OpenGL 與直播／錄影兩顆編碼器 Surface 均保持不變。
     */
    private fun enableConstrainedHighSpeedCapture() {
        runCatching {
            val baseClass = Camera2Base::class.java
            val manager = baseClass.getDeclaredField("cameraManager").apply { isAccessible = true }
                .get(rtmpCamera2)
            val managerClass = manager.javaClass
            val characteristics = managerClass.getMethod("getCameraCharacteristics").invoke(manager)
                as android.hardware.camera2.CameraCharacteristics
            val map = characteristics.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: error("找不到相機串流設定")
            val size = android.util.Size(RECORD_WIDTH, RECORD_HEIGHT)
            val ranges = map.getHighSpeedVideoFpsRangesFor(size).toList()
            // 此 OPPO 的 1080p 高速表只提供可變 30~120 與固定 120；可變範圍實測會掉到低幀率，
            // 因此使用固定高速範圍，再由 GL 將相機輸入限制成錄影所需的 60 FPS。
            val targetRange = ranges.firstOrNull { it.lower == it.upper && it.upper >= RECORD_FPS }
                ?: error("裝置未提供固定高速範圍，ranges=$ranges")
            (rtmpCamera2.glInterface as com.pedro.library.view.OpenGlView).forceFpsLimit(RECORD_FPS)
            val cameraDevice = managerClass.getDeclaredField("cameraDevice").apply { isAccessible = true }
                .get(manager) as android.hardware.camera2.CameraDevice
            val surface = managerClass.getDeclaredField("surfaceEncoder").apply { isAccessible = true }
                .get(manager) as android.view.Surface
            val builder = managerClass.getDeclaredField("builderInputSurface").apply { isAccessible = true }
                .get(manager) as android.hardware.camera2.CaptureRequest.Builder
            val sessionField = managerClass.getDeclaredField("cameraCaptureSession").apply { isAccessible = true }
            val oldSession = sessionField.get(manager) as? android.hardware.camera2.CameraCaptureSession
            val handler = managerClass.getDeclaredField("cameraHandler").apply { isAccessible = true }
                .get(manager) as android.os.Handler

            builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)
            oldSession?.stopRepeating()
            oldSession?.close()
            cameraDevice.createConstrainedHighSpeedCaptureSession(
                listOf(surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        runCatching {
                            val highSpeed = session as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                            sessionField.set(manager, highSpeed)
                            val burst = highSpeed.createHighSpeedRequestList(builder.build())
                            highSpeed.setRepeatingBurst(burst, null, handler)
                            DiagLogger.log(
                                this@LiveActivity, "HIGH-SPEED",
                                "啟用成功 size=$size range=$targetRange burst=${burst.size}"
                            )
                        }.onFailure {
                            DiagLogger.log(this@LiveActivity, "HIGH-SPEED", "啟用失敗：${it.javaClass.simpleName}:${it.message}")
                        }
                    }

                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        DiagLogger.log(this@LiveActivity, "HIGH-SPEED", "Session configure failed")
                    }
                },
                handler
            )
            DiagLogger.log(this, "HIGH-SPEED", "建立中 size=$size ranges=$ranges")
        }.onFailure {
            DiagLogger.log(this, "HIGH-SPEED", "不支援：${it.javaClass.simpleName}:${it.message}")
        }
    }
    private fun recordBitrateForResolution(recordHeight: Int): Int = when {
        recordHeight >= 2160 -> RECORD_BITRATE_4K_BPS
        recordHeight >= 1440 -> RECORD_BITRATE_2K_BPS
        recordHeight >= 1080 -> RECORD_BITRATE_1080P_BPS
        else -> RECORD_BITRATE_720P_BPS
    }

    // ---------- 開播 / 收播 ----------

    private fun setupLiveToggle() {
        binding.btnLiveToggle.setOnClickListener {
            if (!hasCameraPermissions) {
                Toast.makeText(this, "尚未取得相機／麥克風權限，無法開播", Toast.LENGTH_SHORT).show()
                checkAndRequestCameraPermissions()
                return@setOnClickListener
            }
            if (!rtmpCamera2.isStreaming) {
                showStartLiveConfirmDialog()
            } else {
                showStopLiveConfirmDialog()
            }
        }
    }

    /** 直播中按收播先確認，避免操作計分或鏡頭控制時誤觸而直接中止直播與錄影。 */
    private fun showStopLiveConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.stop_live_confirm_title))
            .setMessage(getString(R.string.stop_live_confirm_message))
            .setPositiveButton(getString(R.string.stop_live_confirm_ok)) { _, _ ->
                stopLiveStream(showToast = true)
            }
            .setNegativeButton(getString(R.string.dialog_cancel_button), null)
            .show()
    }

    /**
     * 避免手滑誤觸就真的開播，按下開播先跳確認框，選「是」才真正呼叫 [startLiveStream]。
     * v0.9.3：確認框加「重置計分板」勾選項（預設打勾）——賽前開播直接按開播即自動歸零
     * 分數/犯規/節數；比賽中途斷線重開直播時取消勾選，比分完整保留不會被誤清。
     * 用 setMultiChoiceItems 放單一勾選項（AlertDialog 原生支援，不需自訂版面），
     * 與 setMessage 互斥，確認訊息併入標題。
     * v0.10.0：錄影開啟時標題追加一行空間預估（見 [buildStartLiveConfirmTitle]），
     * 只提醒不阻擋開播（計畫書已知風險段：空間耗盡由 [RecordController.Listener.onError] 路徑接手）。
     */
    private fun showStartLiveConfirmDialog() {
        val resetChecked = booleanArrayOf(true)
        AlertDialog.Builder(this)
            .setTitle(buildStartLiveConfirmTitle())
            .setMultiChoiceItems(
                arrayOf(getString(R.string.start_live_reset_scoreboard_checkbox)),
                resetChecked
            ) { _, _, isChecked -> resetChecked[0] = isChecked }
            .setPositiveButton(getString(R.string.start_live_confirm_ok)) { _, _ ->
                if (resetChecked[0]) resetScoreboardForNewGame()
                startLiveStream()
            }
            .setNegativeButton(getString(R.string.dialog_cancel_button), null)
            .show()
    }

    /** 開播前重置計分板：兩隊分數與犯規歸零、節數回第 1 節，並重繪燒入濾鏡。 */
    private fun resetScoreboardForNewGame() {
        scoreHome = 0
        scoreAway = 0
        foulHome = 0
        foulAway = 0
        period = 1
        // v0.16.0：功能三——開播勾「重置計分板」一併清空各節結算分數（見計畫書「各節分數自動結算」）
        quarterScoresHome = IntArray(StreamPrefs.QUARTER_COUNT) { -1 }
        quarterScoresAway = IntArray(StreamPrefs.QUARTER_COUNT) { -1 }
        StreamPrefs.clearQuarterScores(this)
        refreshScoreboardOverlay()
        Toast.makeText(this, getString(R.string.start_live_scoreboard_reset_toast), Toast.LENGTH_SHORT).show()
    }

    /**
     * v0.10.0：錄影未開啟時原樣回傳確認訊息；開啟時追加一行空間預估，
     * 可用空間低於預估整場需求（以 [RECORD_SPACE_WARNING_HOURS] 小時計）時該行轉紅字警告，
     * 但只提醒不阻擋開播（計畫書已知風險段）。AlertDialog.setTitle 接受 CharSequence，
     * 多行 Spannable 可正常顯示與上色。
     */
    private fun buildStartLiveConfirmTitle(): CharSequence {
        val baseMessage = getString(R.string.start_live_confirm_message)
        if (!StreamPrefs.isRecordEnabled(this)) return baseMessage

        val estimate = calculateRecordSpaceEstimate()
        val recordLine = getString(
            R.string.start_live_record_estimate_format, estimate.gbPerHour, estimate.availableGb
        )
        val builder = SpannableStringBuilder(baseMessage).append("\n").append(recordLine)
        if (estimate.isLowSpace) {
            val start = baseMessage.length + 1
            builder.setSpan(ForegroundColorSpan(Color.RED), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    private data class RecordSpaceEstimate(val gbPerHour: Double, val availableGb: Double, val isLowSpace: Boolean)

    /** 依目前錄影解析度設定換算每小時檔案大小（含音訊碼率），並讀取裝置目前可用空間。 */
    private fun calculateRecordSpaceEstimate(): RecordSpaceEstimate {
        val videoBitrateBps = RECORD_BITRATE_BPS
        val bytesPerHour = (videoBitrateBps + AUDIO_BITRATE).toLong() * 3600L / 8L
        val availableBytes = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE // 讀取失敗時不誤判為空間不足，只是不轉紅字警告
        }
        val neededBytes = bytesPerHour * RECORD_SPACE_WARNING_HOURS
        return RecordSpaceEstimate(
            gbPerHour = bytesPerHour / 1_000_000_000.0,
            availableGb = availableBytes / 1_000_000_000.0,
            isLowSpace = availableBytes < neededBytes
        )
    }

    private fun startLiveStream() {
        if (isCreatingBroadcast) return
        // v0.14.0：工作2——按下開播確認的當下就先鎖住按鈕（灰底不可按，文字「建立直播中…」），
        // 避免 YouTube 建立直播／RTMP 連線過渡期按鈕狀態不明；onConnectionSuccess 才真正變紅字
        // 收播（見 setLiveButtonPending、類別頂端 KDoc）
        setLiveButtonPending()
        val account = GoogleAuthManager.getAuthorizedAccount(this)
        if (account != null) {
            startLiveStreamViaYouTubeApi(account)
        } else {
            // startLiveStreamWithManualKey 改 suspend fun（工作3 治本連帶），這裡是唯一非既有
            // coroutine 內的呼叫點，包一層 launch
            lifecycleScope.launch { startLiveStreamWithManualKey() }
        }
    }

    /**
     * 已登入 YouTube 帳號：呼叫 YouTube Data API 自動建立直播（見 [YouTubeLiveRepository]），
     * 取得推流位址後才真正呼叫 [beginRtmpStreaming]。失敗時一律退回手動金鑰模式或提示錯誤，不閃退。
     */
    private fun startLiveStreamViaYouTubeApi(account: GoogleSignInAccount) {
        isCreatingBroadcast = true
        // v0.14.0：按鈕灰底不可按狀態已在 startLiveStream() 呼叫 setLiveButtonPending() 設過，
        // 這裡不再重複設 isEnabled=false（原本寫在這裡，統一搬到單一入口管理）
        binding.tvLiveIndicator.text = getString(R.string.live_creating_broadcast)
        binding.tvLiveIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val youtube = GoogleAuthManager.buildYouTubeService(this@LiveActivity, account)
                val title = StreamPrefs.getStreamTitleOrDefault(this@LiveActivity)
                val privacy = StreamPrefs.mapPrivacyToApiValue(StreamPrefs.getPrivacy(this@LiveActivity))
                val resolution = StreamPrefs.getResolution(this@LiveActivity)
                val fps = StreamPrefs.parseFps(StreamPrefs.getFps(this@LiveActivity))

                val result = YouTubeLiveRepository.createLiveBroadcastAndStream(
                    youtube, title, privacy, resolution, fps
                )

                currentWatchUrl = result.watchUrl
                // v0.12.0：記住 broadcastId／youtube 服務物件，收播時呼叫 endYouTubeBroadcastIfNeeded
                // 正式結束該場直播（enableAutoStop 已改 false，見 YouTubeLiveRepository 類別頂端 KDoc）
                currentBroadcastId = result.broadcastId
                currentYouTubeService = youtube
                // v0.15.1：孤兒直播回收——先落地存檔，APP 中途當掉/被殺時下次啟動才有線索補收播
                StreamPrefs.savePendingBroadcastId(this@LiveActivity, result.broadcastId)
                beginRtmpStreaming(result.fullRtmpUrl)
                Toast.makeText(
                    this@LiveActivity,
                    getString(R.string.live_broadcast_created_toast),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: UserRecoverableAuthIOException) {
                // 常見於 OAuth 測試模式 7 天授權到期：導向系統授權畫面讓使用者重新確認，不強迫整個重新登入
                Toast.makeText(this@LiveActivity, getString(R.string.live_reauth_needed_message), Toast.LENGTH_LONG).show()
                try {
                    authRecoveryLauncher.launch(e.intent)
                } catch (launchError: Exception) {
                    fallbackToManualKeyOrShowError(getString(R.string.live_reauth_failed_message))
                }
            } catch (e: GoogleJsonResponseException) {
                fallbackToManualKeyOrShowError(mapYouTubeApiErrorMessage(e))
            } catch (e: IOException) {
                fallbackToManualKeyOrShowError(getString(R.string.live_network_error_message))
            } catch (e: Exception) {
                fallbackToManualKeyOrShowError(e.message ?: getString(R.string.live_network_error_message))
            } finally {
                isCreatingBroadcast = false
                // v0.14.0：工作2——只有「這次真的沒能進入 RTMP 連線流程」才需要恢復可按的開播狀態；
                // beginRtmpStreaming() 已呼叫過的話 isStreaming 已是 true（Camera2Base.startStream()
                // 第一行就同步設定，不等連線結果），此時維持灰色「建立直播中…」，等 onConnectionSuccess
                // 才變紅字收播（見類別頂端 KDoc）
                if (!rtmpCamera2.isStreaming) {
                    binding.tvLiveIndicator.visibility = View.INVISIBLE
                    setLiveUiState(false)
                }
            }
        }
    }

    /** 把 YouTube API 的 JSON 錯誤原因轉成繁中提示：配額用盡／頻道未開通直播／其他錯誤代碼。 */
    private fun mapYouTubeApiErrorMessage(e: GoogleJsonResponseException): String {
        val reason = e.details?.errors?.firstOrNull()?.reason.orEmpty()
        return when {
            reason.contains("quota", ignoreCase = true) -> getString(R.string.live_quota_error_message)
            reason.contains("liveStreamingNotEnabled", ignoreCase = true) ->
                getString(R.string.live_channel_not_enabled_message)
            e.statusCode == 401 -> getString(R.string.live_reauth_needed_message)
            else -> getString(
                R.string.live_unknown_api_error_format,
                e.statusCode,
                reason.ifEmpty { e.statusMessage.orEmpty() }
            )
        }
    }

    /** 未登入、或帳號模式建立直播失敗時的備援：設定頁有填手動串流金鑰才改用該金鑰推流，否則提示錯誤原因。 */
    private suspend fun fallbackToManualKeyOrShowError(reasonMessage: String) {
        val streamKey = StreamPrefs.getStreamKey(this).trim()
        if (streamKey.isEmpty()) {
            Toast.makeText(
                this, getString(R.string.live_fallback_no_manual_key_format, reasonMessage), Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            this, getString(R.string.live_fallback_to_manual_format, reasonMessage), Toast.LENGTH_LONG
        ).show()
        startLiveStreamWithManualKey(streamKey)
    }

    /**
     * 未登入時的手動金鑰推流入口（開發測試用途保留當備援）。
     * v0.14.0：改 suspend fun（工作3 治本連帶，見 [applyStreamSettingsAndStartPreview] KDoc）。
     */
    private suspend fun startLiveStreamWithManualKey(streamKeyOverride: String? = null) {
        val streamKey = streamKeyOverride ?: StreamPrefs.getStreamKey(this).trim()
        if (streamKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.live_no_account_no_key_message), Toast.LENGTH_LONG).show()
            // v0.14.0：工作2——未能進入任何連線流程，恢復可按的開播狀態；若是從
            // fallbackToManualKeyOrShowError 呼叫過來（外層 startLiveStreamViaYouTubeApi 的
            // finally 同樣會因 isStreaming 仍為 false 再呼叫一次），重複呼叫無副作用
            setLiveUiState(false)
            return
        }
        currentWatchUrl = null
        // v0.12.0：手動金鑰模式沒有走 YouTube API 建立直播，不存在 broadcastId 可結束
        currentBroadcastId = null
        currentYouTubeService = null
        beginRtmpStreaming("$YOUTUBE_RTMP_BASE_URL$streamKey")
    }

    /**
     * 套用最新串流規格、更新 UI 狀態並實際呼叫 RootEncoder 開始推流（帳號模式與手動金鑰模式共用）。
     * v0.10.0：`startStream()` 之後呼叫 [startRecordIfEnabled]——錄影生命週期＝直播 session，
     * 就算 RTMP 一直連不上，錄影照跑（見類別頂端 KDoc）。
     * v0.14.0：改 suspend fun（工作3 治本連帶，applyStreamSettingsAndStartPreview 本身也是 suspend）。
     */
    private suspend fun beginRtmpStreaming(rtmpUrl: String) {
        // 開播前重新套用最新的串流規格設定，確保設定頁的修改能即時生效
        applyStreamSettingsAndStartPreview()
        // v0.7.0：碼率自動調整上限＝使用者當下選擇的碼率設定值，調整過程不會超過此值
        bitrateAdapter.setMaxBitrate(StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this)))
        binding.tvLiveIndicator.text = "● 連線中…"
        binding.tvLiveIndicator.visibility = View.VISIBLE
        // v0.17.0（第一階段第2項）：重連狀態初始化改在 startStream() **之前**——防禦性排序，避免
        // startStream 後、狀態尚未歸零前就收到極快的 onConnectionFailed 回呼讀到上一場殘留狀態。
        // （此為排序整潔化，不宣稱單獨修復反覆重連。）
        reconnectAttemptCount = 0
        isWaitingToReconnect = false
        reconnectJob?.cancel()
        registerNetworkCallbackForReconnect()
        // v0.17.0（第一階段第4/5項）：連線尚未建立，碼率警告 epoch 重設；暖機期碼率顯示「連線中」不當異常
        isConnectionEstablished = false
        resetBitrateWarningEpoch(armRecovering = false)
        DiagLogger.startSession(this, "開播 target=${StreamPrefs.getBitrate(this)} fps=${StreamPrefs.getFps(this)}")
        DiagLogger.log(this, "CONN", "startStream 送出")
        currentRtmpUrl = rtmpUrl
        rtmpCamera2.startStream(rtmpUrl)
        isRecordingActive = false
        currentRecordDestination = RecordDestination.NONE
        currentRecordFilePathOrName = null
        startRecordIfEnabled()

        // v0.13.0：功能 A——本場精彩標記重新起算（新場次時間戳，收播不清除舊檔，見 HighlightMarker.kt）
        highlightMarkers.clear()
        highlightSessionTimestamp = HighlightStore.newSessionTimestamp()
        liveStartElapsedMs = SystemClock.elapsedRealtime()
        // v0.10.1：直播中才顯示實際/設定碼率，第一筆數字等 onNewBitrate 回報後更新
        binding.tvBitrateStatus.text = getString(
            R.string.bitrate_status_format, 0.0, StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this)) / 1_000_000.0
        )
        binding.tvBitrateStatus.setTextColor(Color.WHITE)
        binding.tvBitrateStatus.visibility = View.VISIBLE
        // v0.14.0：工作2——不在這裡就變紅字「收播」，維持 setLiveButtonPending() 設下的灰色過渡狀態，
        // 真正連線成功（onConnectionSuccess）才呼叫 setLiveUiState(true)（見類別頂端 KDoc）
    }

    /**
     * v0.10.0：收播先 [RtmpCamera2.stopRecord] 再 [RtmpCamera2.stopStream]，確保 MP4 正常收尾
     * （見計畫書功能包②）；`stopRecord()` 內部若偵測到 `!streaming` 才會自己呼叫 `stopStream()`，
     * 這裡呼叫當下 streaming 仍為 true，不會重複觸發。
     */
    private fun stopLiveStream(showToast: Boolean) {
        // v0.16.0：功能三——收播前先退出休息畫面（恢復相機、關 force render、移除濾鏡），
        // 確保收播走既有正常路徑，不留下停住的相機或殘留濾鏡
        if (isBreakMode) exitBreakMode()
        val wasRecording = isRecordingActive
        // v0.12.0：斷線重連強化——收播（含重連放棄時的終止收播）一律先收掉還在等待的重連
        // coroutine 與網路監聽，避免收播後背景還跑著一次遲來的重連把已停止的推流重新啟動
        reconnectJob?.cancel()
        reconnectRecoveryWatchdogJob?.cancel()
        reconnectRecoveryGate.clear()
        currentRtmpUrl = null
        isWaitingToReconnect = false
        unregisterNetworkCallbackForReconnect()
        if (isRecordingActive) {
            try {
                rtmpCamera2.stopRecord()
            } catch (e: Exception) {
                // 收尾失敗不影響收播流程本身，pending 清理仍照常執行
            }
            finalizeRecordAfterStop()
            isRecordingActive = false
        }
        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.stopStream()
        }
        // v0.7.0：收播後重置碼率自動調整狀態，避免下次開播沿用上次殘留的平均值
        bitrateAdapter.reset()
        bitrateAdaptationWarmup.clear()
        // v0.17.0（第一階段第4/5項）：收播回到未建立連線狀態，碼率警告 epoch 一併重設
        isConnectionEstablished = false
        resetBitrateWarningEpoch(armRecovering = false)
        DiagLogger.log(this, "CONN", "收播 stopLiveStream")
        // v0.12.0：enableAutoStop 已改 false，YouTube 端不會自動結束直播，改由這裡主動呼叫
        // liveBroadcasts.transition(complete)（見 endYouTubeBroadcastIfNeeded／類別頂端 KDoc）
        endYouTubeBroadcastIfNeeded()
        currentWatchUrl = null
        binding.tvLiveIndicator.visibility = View.INVISIBLE
        binding.tvBitrateCongestion.visibility = View.GONE
        binding.tvBitrateStatus.visibility = View.GONE
        // v0.12.0：前景服務只在真正推流期間存在，收播即停止（見類別頂端 KDoc）
        LiveForegroundService.stop(this)
        setLiveUiState(false)
        if (showToast) {
            Toast.makeText(this, "已收播", Toast.LENGTH_SHORT).show()
        }
        if (wasRecording) {
            Toast.makeText(this, recordSavedToastMessage(), Toast.LENGTH_LONG).show()
        }
        // v0.13.0：功能 A——收播流程結束後跳「精彩清單」，讓操作者立即整理／複製章節格式
        if (highlightMarkers.isNotEmpty()) {
            showHighlightListDialog()
        }
    }

    /**
     * v0.12.0：`YouTubeLiveRepository.enableAutoStop` 改 `false` 後（理由見該檔類別頂端 KDoc——
     * 避免重連期間被 YouTube 誤判為結束），收播時才由 APP 主動呼叫 `liveBroadcasts.transition
     * (complete)` 正式結束該場直播。手動金鑰模式（[currentBroadcastId] 為 null）不需要這一步。
     * 呼叫失敗（配額用盡／網路異常／直播已被其他管道結束等）不靜默吞掉，明講「請到 YouTube
     * 工作室手動結束直播」（見計畫書項目 3）。非阻塞——不等待這個網路呼叫完成就讓收播流程繼續。
     *
     * v0.12.2：查證「收播後 YouTube 卡直播中」——[showExitDuringLiveConfirmDialog] 按返回鍵確認框
     * 「確定」會 `stopLiveStream()` 後緊接呼叫 `finish()`；`finish()` 觸發的 `onDestroy()` 會連帶
     * 取消 `lifecycleScope`，這裡原本用一般 `launch` 送出的網路呼叫還沒完成就被中途取消，
     * YouTube 端因此完全沒收到 complete 請求（手動按收播按鈕不會 `finish()`，Activity 沒被銷毀，
     * 通常不受影響，這也是為何本 bug 主要在「按返回鍵離開」時出現）。改用
     * `lifecycleScope.launch(NonCancellable)`：這段收尾網路呼叫不再受 Activity 銷毀連帶取消
     * （APP 進程本身在 `finish()` 當下並不會被殺，coroutine 仍會繼續跑完）；失敗自動重試一次
     * （隔 [END_BROADCAST_RETRY_DELAY_MS]），仍失敗才跳明確 Toast；成功也跳一次 Toast 告知。
     */
    /**
     * v0.15.1：孤兒直播回收——APP 直播中當掉/被系統殺掉時，[endYouTubeBroadcastIfNeeded] 沒機會
     * 執行，YouTube 端（`enableAutoStop=false`）會一直掛「直播中」。開播成功時 broadcastId 已
     * 落地存檔（[StreamPrefs.savePendingBroadcastId]），只有 YouTube 確認收到結束指令才清除；
     * 這裡在每次進直播頁時檢查殘留 ID，有就補送 `transition(complete)`。
     * - 成功：清 ID＋Toast 告知已自動收掉上一場。
     * - YouTube 有回應但拒絕（[GoogleJsonResponseException]，多半是那場已被手動結束/自然逾時）：
     *   再重試也不會變，清 ID 靜默放行。
     * - 網路類失敗：保留 ID，下次啟動再試，不打擾操作。
     * ponytail: 回收還沒跑完就立刻開新播會覆寫殘留 ID（新場次照常收播，只剩舊場次沒收到）——
     * 視窗只有幾秒且結果與現況相同（YT 留一場未收），不另做佇列。
     */
    private fun endOrphanBroadcastIfAny() {
        val orphanId = StreamPrefs.getPendingBroadcastId(this) ?: return
        val account = GoogleAuthManager.getAuthorizedAccount(this) ?: return
        lifecycleScope.launch {
            try {
                val youtube = GoogleAuthManager.buildYouTubeService(this@LiveActivity, account)
                YouTubeLiveRepository.endLiveBroadcast(youtube, orphanId)
                StreamPrefs.clearPendingBroadcastId(this@LiveActivity)
                Toast.makeText(
                    applicationContext, getString(R.string.live_orphan_broadcast_ended_toast), Toast.LENGTH_LONG
                ).show()
            } catch (e: GoogleJsonResponseException) {
                StreamPrefs.clearPendingBroadcastId(this@LiveActivity)
            } catch (e: Exception) {
                // 保留 ID 下次再試
            }
        }
    }

    private fun endYouTubeBroadcastIfNeeded() {
        val broadcastId = currentBroadcastId ?: return
        val youtube = currentYouTubeService ?: return
        currentBroadcastId = null
        currentYouTubeService = null
        lifecycleScope.launch(NonCancellable) {
            attemptEndYouTubeBroadcast(youtube, broadcastId, isRetry = false)
        }
    }

    /** [endYouTubeBroadcastIfNeeded] 的實際執行：失敗時遞迴重試一次，見該函式 KDoc。 */
    private suspend fun attemptEndYouTubeBroadcast(youtube: YouTube, broadcastId: String, isRetry: Boolean) {
        try {
            YouTubeLiveRepository.endLiveBroadcast(youtube, broadcastId)
            // v0.15.1：YouTube 確認收到結束指令才清掉落地的 ID；兩次都失敗則保留，
            // 由下次啟動的 endOrphanBroadcastIfAny 接手補收播
            StreamPrefs.clearPendingBroadcastId(applicationContext)
            Toast.makeText(
                applicationContext, getString(R.string.live_end_broadcast_success_message), Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            if (!isRetry) {
                delay(END_BROADCAST_RETRY_DELAY_MS)
                attemptEndYouTubeBroadcast(youtube, broadcastId, isRetry = true)
            } else {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.live_end_broadcast_failed_format, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * v0.14.0：工作2——開播按鈕三態流轉中「已確定成功／已恢復可按開播」的那兩態，
     * 過渡中的灰色態改由 [setLiveButtonPending] 負責（見類別頂端 KDoc）。
     * 兩態都會解除過渡期旗標＋恢復按鈕可點擊（灰底過渡態的 isEnabled=false 到此一律清掉）。
     */
    private fun setLiveUiState(live: Boolean) {
        isLive = live
        isLiveTransitionPending = false
        binding.btnLiveToggle.isEnabled = true
        if (live) {
            binding.btnLiveToggle.text = getString(R.string.live_stop_button)
            binding.btnLiveToggle.setBackgroundResource(R.drawable.bg_round_button_stop)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            binding.btnLiveToggle.text = getString(R.string.live_start_button)
            binding.btnLiveToggle.setBackgroundResource(R.drawable.bg_round_button_start)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // 直播中鎖定設定入口／關閉軟體（降低透明度顯示鎖定狀態），分享按鈕不受影響、任何時候都能按
        binding.btnSettingsEntry.alpha = if (live) LIVE_LOCKED_BUTTON_ALPHA else 1f
        binding.btnCloseApp.alpha = if (live) LIVE_LOCKED_BUTTON_ALPHA else 1f
    }

    /**
     * v0.14.0：工作2——按下開播確認後立即呼叫，按鈕灰底不可按＋文字「建立直播中…」，
     * 並標記進入連線過渡期（[isLiveTransitionPending]）。呼叫端見 [startLiveStream]。
     */
    private fun setLiveButtonPending() {
        isLiveTransitionPending = true
        binding.btnLiveToggle.isEnabled = false
        binding.btnLiveToggle.text = getString(R.string.live_button_pending)
        binding.btnLiveToggle.setBackgroundResource(R.drawable.bg_round_button_pending)
    }

    // ---------- v0.10.0：同步錄影備份——鐵律：錄影任何環節失敗只跳 Toast，絕不中斷或影響直播 ----------

    /**
     * 依設定頁「錄影存檔位置」啟動錄影：先試自訂資料夾（若選了該模式），開檔失敗（資料夾被刪／
     * SD 卡拔除／授權被系統收回）就自動退回相簿模式續錄＋Toast 告知；相簿模式失敗（最後一道防線）
     * 才視為真正的錄影失敗，交給 [handleRecordFailure] 處理。開關關閉時直接跳出，不做任何事。
     */
    private fun startRecordIfEnabled() {
        if (!StreamPrefs.isRecordEnabled(this)) return
        val fileName = buildRecordFileName()
        DiagLogger.log(this, "RECORD", "開始建立 Downloads/$fileName")
        val galleryResult = runCatching { startRecordToGallery(fileName) }
        galleryResult.exceptionOrNull()?.let {
            DiagLogger.log(this, "RECORD", "啟動例外 ${it.javaClass.simpleName}:${it.message}")
        }
        if (galleryResult.getOrDefault(false)) {
            isRecordingActive = true
            DiagLogger.log(this, "RECORD", "錄影已啟動 destination=$currentRecordDestination file=$currentRecordFilePathOrName")
            updateLiveIndicatorRecSuffix()
        } else {
            val reason = galleryResult.exceptionOrNull()?.message ?: getString(R.string.record_unknown_error_message)
            handleRecordFailure(reason)
        }
    }

    private fun buildRecordFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())
        return "籃球直播_$timestamp.mp4"
    }

    /**
     * 鐵律落點：錄影死掉絕不能拖垮直播——只跳 Toast＋拿掉指示燈「｜REC」，直播完全不受影響。
     * v0.10.0 驗收補強：錄影「中途」失敗（例如空間耗盡觸發 onError）也要收掉底層控制器並
     * 清 pending／關 fd（[finalizeRecordAfterStop]），否則 MediaStore 檔案永遠停在 pending
     * 狀態（相簿看不到已錄的部分）、fd 洩漏到 App 結束。
     */
    private fun handleRecordFailure(reason: String) {
        DiagLogger.log(this, "RECORD", "錄影失敗：$reason")
        isRecordingActive = false
        try {
            rtmpCamera2.stopRecord()
        } catch (e: Exception) {
            // 控制器可能已因錯誤自行停止，收尾失敗不影響直播
        }
        finalizeRecordAfterStop()
        updateLiveIndicatorRecSuffix()
        Toast.makeText(this, getString(R.string.record_failed_message_format, reason), Toast.LENGTH_LONG).show()
    }

    /**
     * tvLiveIndicator 目前顯示的文字（連線中…／直播中／重新連線中…）依 [isRecordingActive]
     * 加上或移除「｜REC」後綴，讓操作者一眼確認備份有沒有在跑（見計畫書功能包②第 6 點）。
     */
    private fun updateLiveIndicatorRecSuffix() {
        val current = binding.tvLiveIndicator.text.toString()
        val base = current.removeSuffix(RECORD_INDICATOR_SUFFIX)
        binding.tvLiveIndicator.text = if (isRecordingActive) "$base$RECORD_INDICATOR_SUFFIX" else base
    }

    /**
     * 存到「相簿」：API 29+ 走 MediaStore＋`IS_PENDING` 流程取得 FileDescriptor（收播後清 pending，
     * 見 [finalizeRecordAfterStop]）；API 26~28 天花板退回免權限的 App 專屬 Movies 目錄路徑版
     * （`// ponytail:` 標明，此區間裝置佔比極低不為它引入權限流程，見計畫書已知風險段）。
     * 例外不在此內部吞掉，交由呼叫端 [startRecordIfEnabled] 的 `runCatching` 統一處理。
     */
    private fun startRecordToGallery(fileName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
                DiagLogger.log(this, "RECORD", "MediaStore insert 回傳 null path=${Environment.DIRECTORY_DOWNLOADS}")
                return false
            }
            DiagLogger.log(this, "RECORD", "MediaStore 建檔 uri=$uri")
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: run {
                contentResolver.delete(uri, null, null)
                return false
            }
            // v0.10.0 驗收補強：欄位先記再 startRecord——startRecord 若拋例外，
            // 呼叫端 handleRecordFailure 的 finalizeRecordAfterStop 才清得到這組 pending/fd
            openRecordFileDescriptor = pfd
            pendingGalleryRecordUri = uri
            currentRecordDestination = RecordDestination.GALLERY_MEDIA_STORE
            rtmpCamera2.startRecord(pfd.fileDescriptor, recordControllerListener)
            return true
        }
        // ponytail: API 26~28 天花板——退回免權限的 App 專屬 Movies 目錄路徑版，此區間裝置佔比極低不引入權限流程
        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return false
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val file = File(moviesDir, fileName)
        rtmpCamera2.startRecord(file.absolutePath, recordControllerListener)
        currentRecordDestination = RecordDestination.LEGACY_PATH
        currentRecordFilePathOrName = file.absolutePath
        return true
    }

    /**
     * 存到「自訂資料夾」：用設定頁已記住的 SAF 授權 Uri 在該資料夾 `DocumentFile.createFile` 建檔，
     * 再 `openFileDescriptor` 交給 `startRecord(fd, listener)`。任何一步失敗（授權消失／
     * 資料夾被刪／SD 卡拔除）都回傳 false，由呼叫端 [startRecordIfEnabled] 自動退回相簿模式。
     */
    private fun startRecordToCustomFolder(fileName: String): Boolean {
        val treeUriString = StreamPrefs.getRecordTreeUri(this)
        if (treeUriString.isEmpty()) return false
        val treeUri = Uri.parse(treeUriString)
        val treeDoc = DocumentFile.fromTreeUri(this, treeUri) ?: return false
        val newFile = treeDoc.createFile("video/mp4", fileName) ?: return false
        val pfd = contentResolver.openFileDescriptor(newFile.uri, "w") ?: return false
        // v0.10.0 驗收補強：欄位先記再 startRecord，理由同 startRecordToGallery
        openRecordFileDescriptor = pfd
        rtmpCamera2.startRecord(pfd.fileDescriptor, recordControllerListener)
        currentRecordDestination = RecordDestination.CUSTOM_FOLDER
        currentRecordFilePathOrName = newFile.name ?: fileName
        return true
    }

    /**
     * 收播／onDestroy 都要清 MediaStore 的 `IS_PENDING`（v0.10.0：計畫書已知風險段——App 中途被殺
     * 會留下 pending 殘檔，此為既知邊角，不做開機掃描回收）；並關閉錄影用的 ParcelFileDescriptor
     * （MediaMuxer 不會自己關閉底層 fd，已查證原始碼，呼叫端要自行 close）。
     */
    private fun finalizeRecordAfterStop() {
        pendingGalleryRecordUri?.let { uri ->
            try {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                // 清 pending 失敗不影響直播，殘檔留待使用者手動處理（既知邊角，見計畫書已知風險段）
            }
        }
        pendingGalleryRecordUri = null
        try {
            openRecordFileDescriptor?.close()
        } catch (e: Exception) {
            // 關閉失敗不影響直播
        }
        openRecordFileDescriptor = null
    }

    /** 收播成功後的錄影儲存位置提示（見計畫書功能包②第 8 點）。 */
    private fun recordSavedToastMessage(): String = when (currentRecordDestination) {
        RecordDestination.LEGACY_PATH, RecordDestination.CUSTOM_FOLDER ->
            getString(R.string.record_saved_path_format, currentRecordFilePathOrName ?: "")
        else -> getString(R.string.record_saved_gallery_toast)
    }

    // ---------- 分享：開播成功並取得 YouTube 直播連結後才開啟系統分享面板 ----------

    private fun setupShareButton() {
        binding.btnShare.setOnClickListener {
            val watchUrl = currentWatchUrl
            if (watchUrl.isNullOrEmpty()) {
                Toast.makeText(this, getString(R.string.share_link_not_ready_message), Toast.LENGTH_LONG).show()
            } else {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, watchUrl)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_button)))
            }
        }
    }

    // ---------- ConnectChecker：推流連線狀態回呼 ----------

    override fun onConnectionStarted(url: String) {
        DiagLogger.log(this, "CONN", "onConnectionStarted")
    }

    /**
     * v0.12.0：前景服務掛在「開播成功」這個還在前景的時間點啟動（見 [LiveForegroundService]
     * 類別頂端 KDoc、計畫書項目 1）；重連期間仍會再進來一次（每次重連成功都會回呼），
     * 用同一個通知 ID 更新文字即可從「重新連線中」換回「直播進行中」。
     * v0.14.0：工作2——真正連線成功才呼叫 [setLiveUiState] 變紅底白字「收播」可按（見類別頂端 KDoc）；
     * 重連成功再次進來屬冪等呼叫（isLive 已是 true），不會有副作用。
     */
    override fun onConnectionSuccess() {
        runOnUiThread {
            setLiveUiState(true)
            binding.tvLiveIndicator.text = "● 直播中"
            // v0.10.0：錄影與 RTMP 連線脫鉤，連線狀態文字換了要重新補回「｜REC」後綴
            updateLiveIndicatorRecSuffix()
            Toast.makeText(this, "已連線，開始推流", Toast.LENGTH_SHORT).show()
            // v0.12.0：重連成功，重置重連次數顯示；已在等待中的重連 job 理論上不會同時存在，
            // 保險起見一併取消
            reconnectAttemptCount = 0
            reconnectJob?.cancel()
            isWaitingToReconnect = false
            // v0.17.2（Phase 2）：每次首次連線／重連成功都從使用者目標建立乾淨起點，並開啟
            // 獨立 10 秒暖機 epoch。只暫停 adapter 餵入，不動實際壅塞偵測與 UI。
            val targetBitrate = StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this))
            bitrateAdapter.reset()
            bitrateAdapter.setMaxBitrate(targetBitrate)
            rtmpCamera2.setVideoBitrateOnFly(targetBitrate)
            bitrateAdaptationWarmup.start(SystemClock.elapsedRealtime())
            reconnectRecoveryGate.start(SystemClock.elapsedRealtime())
            startReconnectRecoveryWatchdog()
            DiagLogger.log(
                this, "BITRATE-WARMUP",
                "連線成功：adapter reset，恢復 target=$targetBitrate，暖機=${BITRATE_ADAPTATION_WARMUP_MS}ms"
            )
            // v0.17.0（第一階段第4/5項）：連線正式建立——碼率警告從此開始判定，暖機低碼率不再算異常；
            // epoch 重設（不掛「恢復中」寬限，那只給離開休息用）讓 EWMA 從真正推流值起算，不被暖機值汙染。
            isConnectionEstablished = true
            resetBitrateWarningEpoch(armRecovering = false)
            DiagLogger.log(this, "CONN", "onConnectionSuccess（連線建立）")
            LiveForegroundService.start(this, getString(R.string.foreground_service_status_live))
        }
    }

    /**
     * v0.12.0：斷線重連強化改由 [scheduleReconnect] 接手（見類別頂端 KDoc）——不再直接呼叫
     * `StreamClient.reTry()` 帶入真正的等待時間，改成 APP 自己排一個 coroutine 等待，才能在
     * [registerNetworkCallbackForReconnect] 偵測到網路恢復時提前結束等待、立即重試。
     */
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            if (isStreamPipelineRebuilding) {
                DiagLogger.log(this, "RECOVERY", "pipeline 重建期間忽略舊連線失敗 reason=$reason")
                return@runOnUiThread
            }
            // v0.17.0（第一階段第3項）：保留 callback 原始失敗原因供時間軸核對
            DiagLogger.log(this, "CONN", "onConnectionFailed reason=$reason")
            scheduleReconnect(reason)
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            if (isStreamPipelineRebuilding) {
                DiagLogger.log(this, "RECOVERY", "pipeline 重建期間忽略舊連線 onDisconnect")
                return@runOnUiThread
            }
            // v0.17.1（第一階段審查第3項）：確定失去連線——一律清連線建立旗標＋重設碼率 epoch，
            // 避免殘留 bitrate 回呼被當有效直播樣本顯示達標/偏低/不足（應回到「連線中」）。
            // 冪等：與 onConnectionFailed→scheduleReconnect／stopLiveStream 重複設 false／重設皆無副作用。
            isConnectionEstablished = false
            resetBitrateWarningEpoch(armRecovering = false)
            bitrateAdaptationWarmup.clear()
            DiagLogger.log(this, "CONN", "onDisconnect（清連線旗標＋重設碼率 epoch）")
            binding.tvLiveIndicator.visibility = View.INVISIBLE
            Toast.makeText(this, "已中斷連線", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthError() {
        runOnUiThread {
            Toast.makeText(this, "推流驗證失敗，請確認串流金鑰是否正確", Toast.LENGTH_LONG).show()
            stopLiveStream(showToast = false)
        }
    }

    override fun onAuthSuccess() {
        runOnUiThread {
            Toast.makeText(this, "串流金鑰驗證成功", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- v0.12.0：斷線重連強化（見類別頂端 KDoc） ----------

    /**
     * 斷線後排一次重連等待：`RECONNECT_DELAY_MS`（5 秒）後呼叫 [performReconnectAttempt]；
     * 期間若 [registerNetworkCallbackForReconnect] 偵測到網路恢復會提前取消這個等待、立即重試
     * （見該函式）。畫面文字顯示已重試次數，前景服務通知文字同步換成「重新連線中」。
     */
    private fun scheduleReconnect(reason: String) {
        lastDisconnectReason = reason
        reconnectJob?.cancel()
        isWaitingToReconnect = true
        reconnectAttemptCount++
        // v0.17.0（第一階段第5項）：連線已掉，回到未建立狀態——重連暖機碼率同樣顯示「連線中」不當異常
        // v0.17.1（審查第4項）：重連時一併重設碼率 epoch＋清警告遲滯計數
        isConnectionEstablished = false
        resetBitrateWarningEpoch(armRecovering = false)
        bitrateAdaptationWarmup.clear()
        DiagLogger.log(this, "RECONNECT", "排程重連 attempt=$reconnectAttemptCount reason=$reason")
        binding.tvLiveIndicator.text = getString(R.string.live_reconnecting_format, reconnectAttemptCount)
        updateLiveIndicatorRecSuffix()
        Toast.makeText(this, "連線中斷，將自動重新連線", Toast.LENGTH_SHORT).show()
        LiveForegroundService.start(this, getString(R.string.foreground_service_status_reconnecting))
        reconnectJob = lifecycleScope.launch {
            delay(RECONNECT_DELAY_MS)
            performReconnectAttempt()
        }
    }

    /**
     * 實際觸發函式庫重連：`delayMs` 固定傳 0——真正的等待時間已經由 APP 自己的 coroutine
     * （[scheduleReconnect] 的 delay，或被 [registerNetworkCallbackForReconnect] 提前結束）
     * 掌控完畢，這裡呼叫 `reTry()` 只是「現在」立刻觸發函式庫重新連線，delay 參數對函式庫而言
     * 只是它內部排程用的延遲值。[RECONNECT_MAX_RETRIES] 已設為 `Int.MAX_VALUE`（實質無限），
     * 這裡的 `false` 分支理論上不會被觸發，只在函式庫内部真的耗盡重試或反射／呼叫例外時才會
     * 走到，視為真正的終止收播並提示。
     */
    private fun performReconnectAttempt() {
        if (!isWaitingToReconnect) return
        isWaitingToReconnect = false
        DiagLogger.log(this, "RECONNECT", "觸發 reTry attempt=$reconnectAttemptCount")
        val willRetry = try {
            rtmpCamera2.getStreamClient().reTry(0L, lastDisconnectReason)
        } catch (e: Exception) {
            false
        }
        if (!willRetry) {
            Toast.makeText(this, "推流失敗：$lastDisconnectReason", Toast.LENGTH_LONG).show()
            stopLiveStream(showToast = false)
        }
        // willRetry=true 時函式庫已非同步觸發重連，結果會回到 onConnectionSuccess/onConnectionFailed
    }

    /**
     * 體育館網路閃斷常見情境：Wi-Fi/行動網路中斷後在 5 秒等待週期內就恢復了，此時不用乾等
     * 剩餘的等待時間，網路一恢復（`onAvailable`）就立刻觸發重連。只在真的處於等待重連狀態
     * （[isWaitingToReconnect]）時才動作，避免正常推流期間網路本來就有的正常事件被誤觸發。
     * 註冊失敗（極少數裝置/系統限制）不影響既有「5 秒週期重連」的既有機制，只是少了這個加速。
     */
    private fun registerNetworkCallbackForReconnect() {
        unregisterNetworkCallbackForReconnect()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isWaitingToReconnect) return
                runOnUiThread {
                    DiagLogger.log(this@LiveActivity, "NET", "網路恢復 onAvailable，提前觸發重連")
                    reconnectJob?.cancel()
                    performReconnectAttempt()
                }
            }
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            reconnectNetworkCallback = callback
        } catch (e: Exception) {
            // 註冊失敗不影響直播，只是少了「網路恢復立即重試」的加速，仍會走 5 秒週期重連
        }
    }

    private fun unregisterNetworkCallbackForReconnect() {
        val callback = reconnectNetworkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // 已經解除註冊過或系統例外，忽略即可
        }
        reconnectNetworkCallback = null
    }

    /**
     * v0.7.0：`ConnectChecker` 繼承自 `BitrateChecker`，此為推流中定期回呼「目前實際送出碼率」的
     * 預設方法（非本 APP 自訂）。只在真正推流中才餵給 [bitrateAdapter] 運算，避免收播後的殘留回呼
     * 誤觸底層調整。
     * v0.8.24：新增判斷壅塞狀態並更新畫面小提示（見 [tvBitrateCongestion]）——此回呼來自推流的
     * 背景執行緒（並非本 APP 自訂方法，來源同上），現在會碰觸 View，改用 `runOnUiThread`。
     */
    override fun onNewBitrate(bitrate: Long) {
        if (!rtmpCamera2.isStreaming) return
        // v0.10.1：實際/設定碼率顯示不受「碼率自動調整」開關影響——這個回呼本來就是引擎
        // 定期回報實際送出碼率，開關只決定要不要「調整」，顯示一律更新
        runOnUiThread { updateBitrateStatusDisplay(bitrate) }

        // 復線健康判定與「碼率自動調整」是兩件事；固定碼率模式也必須餵健康樣本。
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val targetBitrate = StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this))
        if (!isBreakMode && reconnectRecoveryGate.observe(bitrate, targetBitrate)) {
            reconnectRecoveryWatchdogJob?.cancel()
            DiagLogger.log(this, "RECOVERY", "健康碼率連續達標 raw=$bitrate target=$targetBitrate")
        }

        // 固定碼率模式不餵 BitrateAdapter，但上面的連線健康判定仍照常執行。
        if (!StreamPrefs.isBitrateAutoAdjust(this)) return

        val hasCongestion = rtmpCamera2.getStreamClient().hasCongestion()
        DiagLogger.log(this, "BITRATE", "raw=$bitrate hasCongestion=$hasCongestion")
        when {
            isBreakMode -> {
                // v0.17.3：休息畫面會讓 raw 自然下降，不能把它當成網路不足餵給 adapter；
                // raw/UI/hasCongestion/診斷照常，只略過調整。
                DiagLogger.log(
                    this, "BITRATE-BREAK",
                    "休息中略過 adaptBitrate raw=$bitrate hasCongestion=$hasCongestion"
                )
            }
            bitrateAdaptationWarmup.isActive(nowElapsedMs) -> {
                DiagLogger.log(
                    this, "BITRATE-WARMUP",
                    "略過 adaptBitrate raw=$bitrate hasCongestion=$hasCongestion remaining=${bitrateAdaptationWarmup.remainingMs(nowElapsedMs)}ms"
                )
            }
            reconnectRecoveryGate.isActive -> DiagLogger.log(
                this, "RECOVERY", "等待健康碼率，略過 adaptBitrate raw=$bitrate target=$targetBitrate"
            )
            else -> bitrateAdapter.adaptBitrate(bitrate, hasCongestion)
        }
        runOnUiThread {
            binding.tvBitrateCongestion.visibility = if (hasCongestion) View.VISIBLE else View.GONE
        }
    }

    private fun startReconnectRecoveryWatchdog() {
        reconnectRecoveryWatchdogJob?.cancel()
        reconnectRecoveryWatchdogJob = lifecycleScope.launch {
            delay(RECONNECT_RECOVERY_TIMEOUT_MS)
            if (rtmpCamera2.isStreaming && reconnectRecoveryGate.isTimedOut(SystemClock.elapsedRealtime())) {
                // 低碼率不等於 RTMP 已斷線；真正斷線交由 onConnectionFailed 的 reTry 流程。
                reconnectRecoveryGate.clear()
                DiagLogger.log(this@LiveActivity, "RECOVERY", "健康碼率逾時，不破壞編碼管線；等待 RTMP 斷線回呼")
            }
        }
    }

    private fun rebuildStreamPipelineForRecovery() {
        val url = currentRtmpUrl ?: return
        if (isStreamPipelineRebuilding) return
        isStreamPipelineRebuilding = true
        reconnectRecoveryGate.clear()
        bitrateAdaptationWarmup.clear()
        bitrateAdapter.reset()
        DiagLogger.log(this, "RECOVERY", "健康碼率逾時，重建 RTMP pipeline（錄影保持）")
        runCatching { if (rtmpCamera2.isStreaming) rtmpCamera2.stopStream() }
        lifecycleScope.launch {
            delay(RECONNECT_PIPELINE_RESTART_DELAY_MS)
            val target = StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this@LiveActivity))
            bitrateAdapter.setMaxBitrate(target)
            rtmpCamera2.setVideoBitrateOnFly(target)
            isStreamPipelineRebuilding = false
            DiagLogger.log(this@LiveActivity, "RECOVERY", "重新 startStream target=$target")
            runCatching { rtmpCamera2.startStream(url) }
                .onFailure { scheduleReconnect("pipeline restart failed: ${it.message}") }
        }
    }
    // ---------- v0.10.1：右上角電池溫度顯示＋左上角實際/設定碼率顯示 ----------

    /**
     * 電池溫度分三段上色（Boss 定案，38~42 含 42）：38 以下藍字「健康」、38~42 黃字「請降溫」、
     * 超過 42 紅字「效能已下降」；轉紅那一刻跳一次 Toast（降回 42 以下重置，下次再轉紅會再提醒）。
     * 電池溫度是整機發熱的可靠參考；處理器精確溫度一般 App 無權限讀取（系統限制）。
     */
    private fun updateTemperatureDisplay(tempCelsius: Double) {
        val (color, statusText) = when {
            tempCelsius < TEMP_HEALTHY_UPPER_CELSIUS ->
                TEMP_COLOR_HEALTHY to getString(R.string.temp_status_healthy)
            tempCelsius <= TEMP_COOLDOWN_UPPER_CELSIUS ->
                TEMP_COLOR_COOLDOWN to getString(R.string.temp_status_cooldown)
            else -> TEMP_COLOR_THROTTLED to getString(R.string.temp_status_throttled)
        }
        binding.tvTempStatus.setTextColor(color)
        binding.tvTempStatus.text = getString(R.string.temp_display_format, tempCelsius, statusText)

        val isThrottled = tempCelsius > TEMP_COOLDOWN_UPPER_CELSIUS
        if (isThrottled && !hasShownOverheatToast) {
            hasShownOverheatToast = true
            Toast.makeText(this, getString(R.string.temp_overheat_toast), Toast.LENGTH_LONG).show()
        } else if (!isThrottled) {
            hasShownOverheatToast = false
        }
    }

    /**
     * v0.17.0（第一階段第4/5項）：碼率警告狀態與 raw 數值拆開。
     * - **raw 數值照常顯示**：文字永遠是本次回呼的真實瞬時碼率（不做平滑），達標比率仍以 raw 附註。
     * - **警告狀態（文字顏色）改用 EWMA＋連續樣本判定**，不再被單筆瞬時值嚇到閃色：
     *   - 連線建立前（[isConnectionEstablished]=false）＝「連線中」中性白字，暖機低碼率不當異常（第5項）。
     *   - 有效樣本（直播中、非休息、actualBps>0）才更新 EWMA 與計數；離開休息後前
     *     [BITRATE_RECOVERING_SAMPLES] 筆顯示「恢復中」青字（epoch 由 [exitBreakMode] 掛旗標），
     *     之後才用 EWMA/target 比率判定白/黃/紅（第4項）。
     *   - 非有效樣本（休息中、0 值）不更新 EWMA、維持中性白字。
     * 進出休息都會呼叫 [resetBitrateWarningEpoch] 重設 epoch（見 enter/exitBreakMode）。
     * 註：本函式只讀 raw 值、不碰 [bitrateAdapter] 與 hasCongestion 的真實壅塞判定與輸入。
     */
    private fun updateBitrateStatusDisplay(actualBps: Long) {
        val targetBps = StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this))
        if (targetBps <= 0) return

        val isValidSample = isConnectionEstablished && !isBreakMode && actualBps > 0
        val state: String = when {
            !isConnectionEstablished -> "連線中"
            !isValidSample -> "中性"      // 休息中或 0 值：不更新 EWMA，維持中性
            else -> {
                bitrateValidSampleCount++
                bitrateEwmaBps = if (bitrateValidSampleCount == 1) {
                    actualBps.toDouble()
                } else {
                    BITRATE_EWMA_ALPHA * actualBps + (1 - BITRATE_EWMA_ALPHA) * bitrateEwmaBps
                }
                if (bitrateShowRecoveringGrace && bitrateValidSampleCount <= BITRATE_RECOVERING_SAMPLES) {
                    "恢復中"
                } else {
                    bitrateShowRecoveringGrace = false
                    // v0.17.1（審查第4項）：EWMA 跨門檻得出瞬時判定，再經連續 N 筆遲滯才提交切色
                    stabilizeWarningState(instantWarningState(bitrateEwmaBps / targetBps))
                }
            }
        }

        // v0.17.1（審查第2項）：raw 數值照常顯示；「連線中」「恢復中」如實顯示在 UI（附在碼率後），
        // 達標/偏低/不足維持只以顏色表示（避免畫面過雜）。
        val suffix = when (state) {
            "連線中" -> getString(R.string.bitrate_state_connecting)
            "恢復中" -> getString(R.string.bitrate_state_recovering)
            else -> null
        }
        val baseText = getString(R.string.bitrate_status_format, actualBps / 1_000_000.0, targetBps / 1_000_000.0)
        binding.tvBitrateStatus.text = if (suffix != null) {
            getString(R.string.bitrate_status_with_state_format, baseText, suffix)
        } else {
            baseText
        }

        binding.tvBitrateStatus.setTextColor(
            when (state) {
                "連線中", "中性", "達標" -> Color.WHITE
                "恢復中" -> BITRATE_COLOR_RECOVERING
                "偏低" -> TEMP_COLOR_COOLDOWN
                else -> TEMP_COLOR_THROTTLED   // 不足
            }
        )

        // 逐筆核對用：raw／EWMA／有效樣本序號／狀態／target（第4項驗收）
        DiagLogger.log(
            this, "BITRATE-UI",
            "raw=$actualBps ewma=${bitrateEwmaBps.toLong()} validN=$bitrateValidSampleCount state=$state target=$targetBps"
        )
    }

    /** EWMA/target 比率的瞬時警告判定（尚未經遲滯）。 */
    private fun instantWarningState(ratio: Double): String = when {
        ratio >= BITRATE_OK_RATIO -> "達標"
        ratio >= BITRATE_LOW_RATIO -> "偏低"
        else -> "不足"
    }

    /**
     * v0.17.1（第一階段審查第4項）：警告狀態連續樣本遲滯。第一筆有效判定立即提交；之後要切換到不同
     * 狀態必須連續 [BITRATE_WARNING_STABLE_SAMPLES] 筆一致才提交，避免 EWMA 在 0.9／0.7 門檻附近抖動
     * 反覆閃色。計數在進出休息/重連/收播（[resetBitrateWarningEpoch]）時一併清除。
     */
    private fun stabilizeWarningState(instant: String): String {
        if (bitrateWarningState.isEmpty() || instant == bitrateWarningState) {
            bitrateWarningState = instant
            bitratePendingWarningState = ""
            bitratePendingWarningCount = 0
            return bitrateWarningState
        }
        if (instant == bitratePendingWarningState) {
            bitratePendingWarningCount++
        } else {
            bitratePendingWarningState = instant
            bitratePendingWarningCount = 1
        }
        if (bitratePendingWarningCount >= BITRATE_WARNING_STABLE_SAMPLES) {
            bitrateWarningState = instant
            bitratePendingWarningState = ""
            bitratePendingWarningCount = 0
        }
        return bitrateWarningState
    }

    /**
     * v0.17.0（第一階段第4項）：重設碼率警告 epoch。EWMA 與有效樣本計數歸零；`armRecovering=true`
     * 時（僅 [exitBreakMode] 使用）武裝「恢復中」寬限，讓離開休息後前 [BITRATE_RECOVERING_SAMPLES]
     * 筆有效樣本顯示「恢復中」。進入休息（[enterBreakMode]）、開播（[beginRtmpStreaming]）、連線建立
     * （[onConnectionSuccess]）、斷線（[onDisconnect]）、重連（[scheduleReconnect]）、收播皆以 `false`
     * 呼叫：只清 EWMA、不武裝寬限。
     * v0.17.1（審查第4項）：一併清除警告狀態遲滯計數。
     */
    private fun resetBitrateWarningEpoch(armRecovering: Boolean) {
        bitrateEwmaBps = 0.0
        bitrateValidSampleCount = 0
        bitrateShowRecoveringGrace = armRecovering
        bitrateWarningState = ""
        bitratePendingWarningState = ""
        bitratePendingWarningCount = 0
    }

    // ---------- 左右固定計分按鈕列：分數 +1/+2/+3/-1（分數不可低於 0，v0.8.0 起改為
    //            固定顯示於畫面左右邊緣，不再放在底部計分列內、不參與任何面板收合） ----------

    private fun setupScoreButtons() {
        binding.btnHomePlus1.setOnClickListener { changeScoreHome(1) }
        binding.btnHomePlus2.setOnClickListener { changeScoreHome(2) }
        binding.btnHomePlus3.setOnClickListener { changeScoreHome(3) }
        binding.btnHomeMinus1.setOnClickListener { changeScoreHome(-1) }

        binding.btnAwayPlus1.setOnClickListener { changeScoreAway(1) }
        binding.btnAwayPlus2.setOnClickListener { changeScoreAway(2) }
        binding.btnAwayPlus3.setOnClickListener { changeScoreAway(3) }
        binding.btnAwayMinus1.setOnClickListener { changeScoreAway(-1) }
    }

    private fun changeScoreHome(delta: Int) {
        // 比分方塊固定以 3 位數（SCORE_BOX_WIDTH_REFERENCE_TEXT）當寬度基準，分數夾限在此範圍內避免溢出
        // v0.14.1：Boss 拍板移除主隊 +3 慶祝演出（v0.11.0 公牛衝撞動畫／v0.14.0 圖片款演出），
        // 加分本體不變，純粹只加分不再觸發任何演出（見類別頂端 KDoc v0.14.1 條目）。
        scoreHome = (scoreHome + delta).coerceIn(0, MAX_SCORE)
        refreshScoreboardOverlay()
    }

    private fun changeScoreAway(delta: Int) {
        scoreAway = (scoreAway + delta).coerceIn(0, MAX_SCORE)
        refreshScoreboardOverlay()
    }

    // ---------- 頂部操作列：主隊犯規/節數/客隊犯規（犯規 0～4；節數最低第 1 節） ----------

    private fun setupTopOperationButtons() {
        binding.btnFoulHomeMinus.setOnClickListener { changeFoulHome(-1) }
        binding.btnFoulHomePlus.setOnClickListener { changeFoulHome(1) }
        binding.btnPeriodMinus.setOnClickListener { changePeriod(-1) }
        binding.btnPeriodPlus.setOnClickListener { changePeriod(1) }
        binding.btnFoulAwayMinus.setOnClickListener { changeFoulAway(-1) }
        binding.btnFoulAwayPlus.setOnClickListener { changeFoulAway(1) }
    }

    /** 犯規次數已改為燒入計分板 4 格燈號顯示，這裡只更新變數並重繪濾鏡，不再更新畫面上的犯規標籤。 */
    private fun changeFoulHome(delta: Int) {
        foulHome = (foulHome + delta).coerceIn(0, MAX_FOUL_COUNT)
        refreshScoreboardOverlay()
    }

    private fun changeFoulAway(delta: Int) {
        foulAway = (foulAway + delta).coerceIn(0, MAX_FOUL_COUNT)
        refreshScoreboardOverlay()
    }

    private fun changePeriod(delta: Int) {
        val oldPeriod = period
        period = (period + delta).coerceAtLeast(1)
        // 已在下限（第 1 節）再按減，節數沒變＝不做任何結算，避免誤收回不存在的結算
        if (period == oldPeriod) return
        // v0.16.0：功能三——切節數當下自動結算各節分數（見計畫書「各節分數自動結算」）
        if (delta > 0) settleQuarterOnAdvance(oldPeriod)
        else settleQuarterOnRewind(period)
        persistQuarterScores()
        refreshScoreboardOverlay()
    }

    /**
     * v0.16.0：功能三——節數 +1 當下把剛打完那一節（[finishedPeriod]）結算：
     * 該節分數＝目前總分 − 已結算各節加總（兩隊各自）。固定 4 節，超過第 4 節不再結算（見計畫書「不做加時」）。
     */
    private fun settleQuarterOnAdvance(finishedPeriod: Int) {
        val index = finishedPeriod - 1
        if (index !in 0 until StreamPrefs.QUARTER_COUNT) return
        val settledHome = quarterScoresHome.filter { it >= 0 }.sum()
        val settledAway = quarterScoresAway.filter { it >= 0 }.sum()
        quarterScoresHome[index] = (scoreHome - settledHome).coerceAtLeast(0)
        quarterScoresAway[index] = (scoreAway - settledAway).coerceAtLeast(0)
    }

    /**
     * v0.16.0：功能三——節數 −1（誤切回退）當下對稱收回：清掉「回退後要重新打的那一節」（[newPeriod]）
     * 的結算，分數回到未結算狀態（顯示「–」），與 [settleQuarterOnAdvance] 對稱。
     */
    private fun settleQuarterOnRewind(newPeriod: Int) {
        val index = newPeriod - 1
        if (index !in 0 until StreamPrefs.QUARTER_COUNT) return
        quarterScoresHome[index] = -1
        quarterScoresAway[index] = -1
    }

    private fun persistQuarterScores() {
        StreamPrefs.saveQuarterScores(this, quarterScoresHome, quarterScoresAway)
    }

    // ---------- 隊名編輯：點底部計分列的隊名跳出對話框，空白則還原預設 ----------

    private fun setupTeamNameEditing() {
        binding.tvTeamHomeName.setOnClickListener { showTeamNameEditDialog(isHome = true) }
        binding.tvTeamAwayName.setOnClickListener { showTeamNameEditDialog(isHome = false) }
    }

    private fun showTeamNameEditDialog(isHome: Boolean) {
        val currentName = if (isHome) teamHomeName else teamAwayName
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val editText = EditText(this).apply {
            setText(currentName)
            hint = getString(R.string.edit_team_name_hint)
            setSelection(text.length)
            // 隊名燒入計分板時會依可用寬度自動縮字，字數上限避免縮到底仍溢出方塊
            filters = arrayOf(InputFilter.LengthFilter(TEAM_NAME_MAX_LENGTH))
        }
        val container = FrameLayout(this).apply {
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle(
                if (isHome) getString(R.string.edit_team_home_name_title) else getString(R.string.edit_team_away_name_title)
            )
            .setView(container)
            .setPositiveButton(getString(R.string.dialog_confirm_button)) { _, _ ->
                val defaultName = if (isHome) getString(R.string.team_home_default) else getString(R.string.team_away_default)
                val newName = editText.text.toString().trim().ifEmpty { defaultName }
                if (isHome) {
                    teamHomeName = newName
                    binding.tvTeamHomeName.text = newName
                } else {
                    teamAwayName = newName
                    binding.tvTeamAwayName.text = newName
                }
                StreamPrefs.saveTeamNames(this, teamHomeName, teamAwayName)
                refreshScoreboardOverlay()
            }
            .setNegativeButton(getString(R.string.dialog_cancel_button), null)
            .show()
    }

    // ---------- 左側欄：分數清零（比賽中誤按代價高，先跳確認框） ----------

    /**
     * v0.6.0：清零範圍擴大——按下「是」後兩隊分數與犯規次數一起歸零，
     * 燒入計分板的 4 格犯規燈號同步變回白／未點亮（見 [refreshScoreboardOverlay]）。
     */
    private fun setupResetScoresButton() {
        binding.btnResetScores.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_scores_confirm_title))
                .setMessage(getString(R.string.reset_scores_confirm_message))
                .setPositiveButton(getString(R.string.reset_scores_confirm_ok)) { _, _ ->
                    scoreHome = 0
                    scoreAway = 0
                    foulHome = 0
                    foulAway = 0
                    refreshScoreboardOverlay()
                    Toast.makeText(this, getString(R.string.reset_scores_done_toast), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.dialog_cancel_button), null)
                .show()
        }
    }

    // ---------- 計分板燒入直播畫面：以 Bitmap 繪製後掛上 ImageObjectFilterRender ----------

    /**
     * 串流解析度確定（或改變）時呼叫：建立初始計分板 Bitmap，
     * 設定濾鏡的預設縮放與位置（畫面下方正中央），再掛到 OpenGlView 的濾鏡鏈上。
     */
    private fun applyScoreboardOverlayFilter() {
        if (streamWidthForOverlay <= 0 || streamHeightForOverlay <= 0) return
        scoreboardOverlayFilter.setImage(buildScoreboardOverlayBitmap())
        scoreboardOverlayFilter.setDefaultScale(streamWidthForOverlay, streamHeightForOverlay)
        scoreboardOverlayFilter.setPosition(TranslateTo.BOTTOM)
        rtmpCamera2.getGlInterface().setFilter(scoreboardOverlayFilter)
    }

    /**
     * 分數/節數/隊名變動時呼叫：只重繪 Bitmap 換圖，縮放與位置維持不變。
     * v0.14.1：移除 v0.11.0 撞擊震動 padding 機制（原本只服務公牛衝撞特效，見類別頂端 KDoc），
     * 改回直接使用 [buildScoreboardOverlayBitmap] 產出的原始尺寸 Bitmap。
     */
    private fun refreshScoreboardOverlay() {
        if (streamWidthForOverlay <= 0 || streamHeightForOverlay <= 0) return
        scoreboardOverlayFilter.setImage(buildScoreboardOverlayBitmap())
        // v0.16.0：功能三——休息中時分數／節數／隊名變動同步重繪休息畫面（各節分數即時反映）
        if (isBreakMode) breakScreenFilter.setImage(buildBreakScreenBitmap())
    }

    /**
     * v0.6.0：燒入計分板改版為轉播風格橫幅，取代原本單行純文字版面。整體分成兩區塊：
     * - 左側區塊（[drawEventNameAndPeriodColumn]）：賽事名稱，置中對齊；留空則不畫任何文字或
     *   預留框線，整欄留白（v0.9.14 起節數不在此欄，見下）
     * - 右側主區塊（[drawMainScoreRow]）：主隊名／主隊犯規 4 格燈號／比分／客隊犯規 4 格燈號／客隊名；
     *   節數（v0.9.14 起）置中疊在兩個比分方塊正中間、跟犯規燈號同一排
     * 兩區塊中間畫一條半透明分隔線；犯規燈號見 [drawFoulLights]（未犯規＝淺灰未點亮，
     * 每犯規一次由左至右點亮一格紅燈，上限 4 格＝加罰）。半透明深色底、白字，
     * Bitmap 尺寸依目前串流解析度等比例縮放，確保各解析度下比例一致。
     */
    private fun buildScoreboardOverlayBitmap(): Bitmap {
        val bitmapWidth = (streamWidthForOverlay * OVERLAY_WIDTH_RATIO).toInt().coerceAtLeast(1)
        val bitmapHeight = (streamHeightForOverlay * OVERLAY_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // v0.8.6：Boss 要求改回黑底帶點透明，取消 v0.7.4 的鈦金屬灰漸層底／亮藍描邊
        // v0.9.0：Boss 從六款樣圖選定「經典轉播藏青金」——藏青直向漸層底（微透明）＋金色細邊框
        // ＋頂緣金色飾線，配色對照樣圖提案（scoreboard-styles artifact 樣式02）
        val cornerRadius = bitmapHeight * 0.18f
        val panelRect = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
        // v0.9.11：淡一點（v0.9.10）、深綠（未發布即撤回）都試過，Boss 決定改回 v0.9.0 原始藏青色
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, bitmapHeight.toFloat(),
                Color.argb(215, 23, 56, 104), Color.argb(215, 13, 33, 64),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, backgroundPaint)

        // 金色細邊框（低透明度，襯出面板輪廓不搶戲）
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (bitmapHeight * 0.02f).coerceAtLeast(1f)
            color = Color.argb(56, 255, 214, 140)
        }
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, borderPaint)

        // 頂緣金色飾線：左右內縮圓角半徑不頂到彎角，兩端往透明淡出、中段金色漸層
        val accentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cornerRadius, 0f, bitmapWidth - cornerRadius, 0f,
                intArrayOf(
                    Color.TRANSPARENT, Color.rgb(243, 207, 127), Color.rgb(224, 168, 63),
                    Color.rgb(243, 207, 127), Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            strokeWidth = (bitmapHeight * 0.045f).coerceAtLeast(2f)
        }
        val accentLineY = bitmapHeight * 0.05f
        canvas.drawLine(cornerRadius, accentLineY, bitmapWidth - cornerRadius, accentLineY, accentLinePaint)

        val leftColumnWidth = bitmapWidth * LEFT_COLUMN_WIDTH_RATIO
        drawEventNameAndPeriodColumn(canvas, leftColumnWidth, bitmapHeight)

        // 左右分隔線：只在中間留出上下邊距，不頂到圓角邊緣（v0.9.0 改金色調）
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 243, 207, 127)
            strokeWidth = (bitmapHeight * 0.02f).coerceAtLeast(1f)
        }
        val dividerMarginV = bitmapHeight * 0.15f
        canvas.drawLine(
            leftColumnWidth, dividerMarginV, leftColumnWidth, bitmapHeight - dividerMarginV, dividerPaint
        )

        drawMainScoreRow(canvas, leftColumnWidth, bitmapWidth.toFloat(), bitmapHeight)

        return bitmap
    }

    /**
     * 左側區塊：只剩賽事名稱，整欄置中對齊（v0.9.14 節數搬到 [drawMainScoreRow] 的
     * 兩個比分方塊中間、犯規燈號那一排，原本這裡「上：賽事名稱／下：節數」兩行的排版取消）。
     * 賽事名稱為空字串時不繪製任何文字或框線，整欄留白。
     * v0.9.17：設定頁改成兩個輸入欄（各上限 8 字），存檔格式為「第一行\n第二行」，
     * 這裡依換行符分行繪製（取代 v0.9.16 的 6 字自動切行）。兩行一起當一個區塊
     * 緊貼置中在整欄高度內（行距用字級的固定倍率，不是把整欄高度硬除以 2），
     * 避免兩行中間留一大段空白；只有 1 行時維持原本單行置中。
     */
    private fun drawEventNameAndPeriodColumn(canvas: Canvas, columnWidth: Float, bitmapHeight: Int) {
        if (eventName.isEmpty()) return
        val lines = eventName.split("\n").filter { it.isNotEmpty() }.take(2)
        if (lines.isEmpty()) return
        val centerX = columnWidth / 2f
        val maxEventNameWidth = columnWidth * 0.9f

        fun makePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(246, 239, 224)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        // 2 行時字級比單行略縮小，讓兩行加行距後仍在整欄高度內
        val baseTextSize = bitmapHeight * EVENT_NAME_TEXT_SIZE_RATIO * if (lines.size > 1) 0.62f else 1f
        val paints = lines.map { line ->
            val paint = makePaint()
            var textSize = baseTextSize
            paint.textSize = textSize
            while (paint.measureText(line) > maxEventNameWidth && textSize > 8f) {
                textSize -= 1f
                paint.textSize = textSize
            }
            paint
        }

        // 行距＝字級的固定倍率（貼緊排版，非把整欄高度平均分配給每行）
        val lineSpacings = paints.map { it.textSize * EVENT_NAME_LINE_SPACING_RATIO }
        val totalBlockHeight = lineSpacings.sum()
        var lineTop = bitmapHeight / 2f - totalBlockHeight / 2f
        lines.forEachIndexed { index, line ->
            val paint = paints[index]
            val spacing = lineSpacings[index]
            val lineCenterY = lineTop + spacing / 2f
            val lineY = lineCenterY - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(line, centerX, lineY, paint)
            lineTop += spacing
        }
    }

    /**
     * 右側主區塊：上排「主隊名／主隊比分方塊／客隊比分方塊／客隊名」置中對齊；
     * 比分方塊為鈦金屬淺色漸層＋深色粗體數字（見 [drawScoreBox]），非純文字；
     * 下排犯規長條號誌，各自置中對齊在自己隊名的正下方（見 [drawFoulBars]）。
     * 整段內容過寬時自動縮小隊名／比分字級，確保不超出可用寬度。
     */
    private fun drawMainScoreRow(canvas: Canvas, leftEdge: Float, bitmapWidth: Float, bitmapHeight: Int) {
        // v0.8.4：Boss 要求隊名文字置中對齊（原本 LEFT 對齊會讓文字貼齊分數方塊那一側）
        val teamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(246, 239, 224)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        // v0.9.14：v0.9.12 的黑底白字撤回，比分方塊底色改回金色漸層、數字改黑色
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val homeScoreText = scoreHome.toString()
        val awayScoreText = scoreAway.toString()
        val gap = bitmapHeight * MAIN_ROW_GAP_RATIO
        val scoreBoxPadding = bitmapHeight * SCORE_BOX_PADDING_RATIO

        var textSize = bitmapHeight * MAIN_TEXT_SIZE_RATIO
        val availableWidth = (bitmapWidth - leftEdge) * 0.92f

        fun applySizes() {
            // v0.9.2：隊名比基準字級小 10%，分數維持基準字級（過寬自動縮字時兩者等比例一起縮）
            teamPaint.textSize = textSize * TEAM_NAME_TEXT_SIZE_RATIO
            scorePaint.textSize = textSize * SCORE_TEXT_SIZE_RATIO
        }

        // v0.8.1：兩隊比分方塊寬度固定以「3 位數」為基準（籃球比賽分數上看百分都算得到），
        // 不再依實際分數位數變動，避免比賽過程中分數從個位數跳到兩位數/三位數時方塊忽大忽小。
        fun scoreBoxWidth(): Float = scorePaint.measureText(SCORE_BOX_WIDTH_REFERENCE_TEXT) + scoreBoxPadding * 2

        fun totalWidth(): Float =
            teamPaint.measureText(teamHomeName) + gap +
                scoreBoxWidth() + gap * 0.5f +
                scoreBoxWidth() + gap +
                teamPaint.measureText(teamAwayName)

        applySizes()
        while (totalWidth() > availableWidth && textSize > 10f) {
            textSize -= 1f
            applySizes()
        }

        val rowCenterY = bitmapHeight * MAIN_ROW_Y_RATIO
        val teamTextY = rowCenterY - (teamPaint.descent() + teamPaint.ascent()) / 2f
        val boxHeight = textSize * SCORE_TEXT_SIZE_RATIO + scoreBoxPadding * 1.4f
        val sharedBoxWidth = scoreBoxWidth()
        var x = leftEdge + (bitmapWidth - leftEdge - totalWidth()) / 2f

        val teamHomeWidth = teamPaint.measureText(teamHomeName)
        val teamHomeCenterX = x + teamHomeWidth / 2f
        canvas.drawText(teamHomeName, teamHomeCenterX, teamTextY, teamPaint)
        x += teamHomeWidth + gap

        val homeBoxWidth = sharedBoxWidth
        drawScoreBox(canvas, x, rowCenterY, homeBoxWidth, boxHeight, homeScoreText, scorePaint)
        val homeBoxRight = x + homeBoxWidth
        x = homeBoxRight + gap * 0.5f

        val awayBoxWidth = sharedBoxWidth
        val awayBoxLeft = x
        drawScoreBox(canvas, x, rowCenterY, awayBoxWidth, boxHeight, awayScoreText, scorePaint)
        x += awayBoxWidth + gap

        val teamAwayWidth = teamPaint.measureText(teamAwayName)
        val teamAwayCenterX = x + teamAwayWidth / 2f
        canvas.drawText(teamAwayName, teamAwayCenterX, teamTextY, teamPaint)

        val barRowY = bitmapHeight * FOUL_BAR_ROW_Y_RATIO
        val barWidth = bitmapHeight * FOUL_BAR_WIDTH_RATIO
        val barHeight = bitmapHeight * FOUL_BAR_HEIGHT_RATIO

        // v0.9.14：節數搬到這裡——兩個比分方塊正中間、跟犯規燈號同一排（原本在左欄，
        // 見類別頂端 KDoc／[drawEventNameAndPeriodColumn]）。中間空隙比犯規燈號窄，
        // 用比犯規燈號小一階的字級，超出兩方塊中間縫隙一點也不會撞到燈號（燈號分別在
        // 兩隊隊名正下方，離中間還有一段距離）。
        val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(246, 239, 224)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        periodPaint.textSize = barHeight * 1.9f
        val periodCenterX = (homeBoxRight + awayBoxLeft) / 2f
        val periodY = (barRowY + barHeight / 2f) - (periodPaint.descent() + periodPaint.ascent()) / 2f
        canvas.drawText(getString(R.string.period_format, period), periodCenterX, periodY, periodPaint)
        val barSpacing = bitmapHeight * FOUL_BAR_SPACING_RATIO
        drawFoulBars(canvas, teamHomeCenterX, barRowY, foulHome, barWidth, barHeight, barSpacing)
        drawFoulBars(canvas, teamAwayCenterX, barRowY, foulAway, barWidth, barHeight, barSpacing)
    }

    /**
     * 比分方塊：近黑實色底＋白色粗體數字（v0.9.12 定案；v0.9.0～v0.9.11 曾是金色金屬漸層底配
     * 藏青／藍色數字，中間 v0.9.4 也短暫試過同款黑底白字又於 v0.9.5 撤回），
     * 左緣 X 座標為 [left]，垂直置中對齊 [centerY]。
     */
    private fun drawScoreBox(
        canvas: Canvas,
        left: Float,
        centerY: Float,
        width: Float,
        height: Float,
        text: String,
        textPaint: Paint
    ) {
        val rect = RectF(left, centerY - height / 2f, left + width, centerY + height / 2f)
        // v0.9.14：v0.9.12 的近黑實色底撤回，改回金色金屬漸層底（配上面 scorePaint 的黑字）
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                Color.rgb(247, 223, 160), Color.rgb(207, 159, 69),
                Shader.TileMode.CLAMP
            )
        }
        val cornerRadius = height * 0.22f
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)
        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, left + width / 2f, textY, textPaint)
    }

    /**
     * 犯規長條號誌：由左至右依序點亮，未犯規＝半透明白（未點亮），
     * 已犯規次數對應格數點亮紅燈（v0.9.1：藏青金樣式的金燈改回紅色——犯規是警示訊息，
     * 紅色語意更直覺，也不會跟比分方塊的金色混在一起）；
     * 固定畫滿 [MAX_FOUL_COUNT] 格，整排以 [centerX] 為中心置中，畫在隊名正下方。
     */
    private fun drawFoulBars(
        canvas: Canvas,
        centerX: Float,
        rowY: Float,
        foulCount: Int,
        barWidth: Float,
        barHeight: Float,
        barSpacing: Float
    ) {
        val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 40, 40) }
        val unlitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        val cornerRadius = barHeight / 2.4f
        val totalWidth = MAX_FOUL_COUNT * barWidth + (MAX_FOUL_COUNT - 1) * barSpacing
        var left = centerX - totalWidth / 2f
        for (i in 0 until MAX_FOUL_COUNT) {
            val rect = RectF(left, rowY, left + barWidth, rowY + barHeight)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, if (i < foulCount) litPaint else unlitPaint)
            left += barWidth + barSpacing
        }
    }

    // ---------- 曝光 / 定焦 / 變焦：v0.5.0 真實接上 RootEncoder 2.4.9 相機控制 API ----------

    /**
     * 相機控制（曝光/定焦/變焦）共用的就緒判斷：權限已授權且相機正在預覽或推流中才呼叫底層 API，
     * 避免相機尚未開啟時呼叫 Camera2Base 造成 NPE 或無意義的操作。
     */
    private fun isCameraReadyForControl(): Boolean =
        hasCameraPermissions && (rtmpCamera2.isStreaming || rtmpCamera2.isOnPreview)

    /**
     * 曝光補償 +/-：呼叫 [RtmpCamera2.setExposure]（繼承自 Camera2Base，底層對應
     * `CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION`）。[RtmpCamera2.getMinExposure]/
     * [RtmpCamera2.getMaxExposure] 讀出裝置實際支援的 `CONTROL_AE_COMPENSATION_RANGE`，
     * 超出範圍就不再呼叫並提示已達上/下限；min==max==0 視為裝置不支援曝光調整或相機尚未就緒。
     */
    private fun setupExposureControls() {
        binding.btnExposureUp.setOnClickListener { changeExposure(1) }
        binding.btnExposureDown.setOnClickListener { changeExposure(-1) }
    }

    private fun changeExposure(delta: Int) {
        if (!isCameraReadyForControl()) {
            Toast.makeText(this, getString(R.string.camera_not_ready_message), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val minExposure = rtmpCamera2.minExposure
            val maxExposure = rtmpCamera2.maxExposure
            if (minExposure == 0 && maxExposure == 0) {
                Toast.makeText(this, getString(R.string.exposure_not_supported_message), Toast.LENGTH_SHORT).show()
                return
            }
            val newValue = (exposureValue + delta).coerceIn(minExposure, maxExposure)
            if (newValue == exposureValue) {
                val limitMessage = if (delta > 0) {
                    R.string.exposure_limit_upper_message
                } else {
                    R.string.exposure_limit_lower_message
                }
                Toast.makeText(this, getString(limitMessage), Toast.LENGTH_SHORT).show()
                return
            }
            rtmpCamera2.setExposure(newValue)
            exposureValue = newValue
            binding.tvExposureValue.text = exposureValue.toString()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.exposure_adjust_failed_format, e.message ?: ""), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * v0.5.1：右側欄「定焦鎖定」按鈕改為純顯示/解除鎖定用途的輔助按鈕——
     * 已鎖定時按下＝解除鎖定（呼叫 [RtmpCamera2.enableAutoFocus] 恢復連續對焦、移除鎖定圓框）；
     * 尚未鎖定時按下不做任何相機操作，只提示「請長按畫面對焦後鎖定」，
     * 不再保留舊版「不管三七二十一直接鎖住當下模糊畫面」的行為（真正的鎖定改由
     * [handleFocusGesture] 長按流程處理，會等對焦收斂完成才鎖定）。
     * v0.12.0：解鎖同時呼叫 [setAutoExposureLock] 恢復自動測光（見類別頂端 KDoc 項目 2）。
     */
    private fun setupFocusLock() {
        binding.btnFocusLock.setOnClickListener {
            if (!isCameraReadyForControl()) {
                Toast.makeText(this, getString(R.string.camera_not_ready_message), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isFocusLocked) {
                Toast.makeText(this, getString(R.string.focus_lock_hint_message), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                rtmpCamera2.enableAutoFocus()
            } catch (e: Exception) {
                Toast.makeText(
                    this, getString(R.string.focus_lock_failed_format, e.message ?: ""), Toast.LENGTH_SHORT
                ).show()
            }
            setAutoExposureLock(false)
            isFocusLocked = false
            updateFocusLockButtonUi()
            binding.focusIndicatorView.hide()
        }
    }

    private fun updateFocusLockButtonUi() {
        binding.btnFocusLock.text = if (isFocusLocked) {
            getString(R.string.focus_unlock_button)
        } else {
            getString(R.string.focus_lock_button)
        }
    }

    /**
     * v0.5.1：點觸／長按對焦手勢——掛在 `openGlView` 上的 [GestureDetector]，
     * 單點觸發 [handleFocusGesture]（lockAfterFocus=false，對焦完成後恢復連續對焦）；
     * 長按觸發 [handleFocusGesture]（lockAfterFocus=true，對焦完成後鎖定）。
     * `onDown` 必須回傳 true，GestureDetector 才會繼續收到後續事件判斷單擊/長按。
     * 觸控監聽只掛在 openGlView 本身（不含四個可收合面板的按鈕區域），
     * 面板按鈕在 ConstraintLayout 中屬於後宣告、疊在上層的兄弟 View，
     * 命中測試會優先交給面板按鈕處理，不會被這裡搶走事件。
     */
    private fun setupFocusGestures() {
        focusGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleFocusGesture(e, lockAfterFocus = false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleFocusGesture(e, lockAfterFocus = true)
            }
        })
        binding.openGlView.setOnTouchListener { _, event ->
            focusGestureDetector.onTouchEvent(event)
            true
        }
    }

    /**
     * 點觸／長按對焦共用邏輯：
     * 1. 【v0.8.27 升級 RootEncoder 2.6.1 後大幅簡化】直接把原始 [MotionEvent] 交給
     *    [RtmpCamera2.tapToFocus]（簽名改為 `tapToFocus(View, MotionEvent)`）——函式庫內部
     *    自行做 View→感光元件座標換算並設定 `CONTROL_AF_REGIONS`＋`CONTROL_AF_MODE_AUTO`
     *    ＋`CONTROL_AF_TRIGGER_START`，本 APP 原本那段手工感光元件座標換算整段移除。
     *    2.4.9 的舊版 tapToFocus 有致命 bug：`AF_TRIGGER_START` 被放進 repeating request
     *    每一幀重送，對焦掃描不停被重新啟動、永遠無法收斂（實機驗證：鎖定黃框顯示了、畫面
     *    仍然是糊的）；2.6.1 重寫為單次 `capture()` 觸發＋完成後把觸發旗標重設回 IDLE，
     *    對焦掃描可以正常跑完收斂。
     * 2. 函式庫仍未開放 `CaptureResult.CONTROL_AF_STATE` 的 capture callback 可監聽對焦是否收斂，
     *    這裡維持用 [FOCUS_SETTLE_DELAY_MS] 延遲近似「已完成對焦」，等待期滿才決定恢復連續對焦或標記鎖定。
     * 3. 【v0.5.3 修正，2.6.1 仍適用】長按鎖定不呼叫函式庫的 `disableAutoFocus()`（只切
     *    `CONTROL_AF_MODE_OFF` 不保留對焦距離，會跳焦變模糊，詳見類別頂端 KDoc）。改利用
     *    `CONTROL_AF_MODE_AUTO` 本身「觸發一次收斂後鏡頭停在鎖定狀態、不會再自己漂移」的
     *    規格行為當作鎖定，等待收斂延遲後只更新 UI 狀態即可。
     */
    private fun handleFocusGesture(event: MotionEvent, lockAfterFocus: Boolean) {
        // v0.8.18：鎖定中一律不理會點觸／長按，只能靠右側欄「定焦鎖定」按鈕解除（見 setupFocusLock）
        if (isFocusLocked) return
        if (!isCameraReadyForControl()) {
            Toast.makeText(this, getString(R.string.camera_not_ready_message), Toast.LENGTH_SHORT).show()
            return
        }
        val viewX = event.x
        val viewY = event.y

        val triggered = try {
            rtmpCamera2.tapToFocus(binding.openGlView, event)
        } catch (e: Exception) {
            false
        }
        if (!triggered) {
            Toast.makeText(this, getString(R.string.focus_tap_failed_message), Toast.LENGTH_SHORT).show()
            return
        }

        binding.focusIndicatorView.showScanning(viewX, viewY)
        // v0.12.0：AE 測光跟隨點擊處——把函式庫剛設定好的 CONTROL_AF_REGIONS 借來設成
        // CONTROL_AE_REGIONS（見 syncAeRegionsToFocusPoint／類別頂端 KDoc 項目 2），
        // 不需要等對焦收斂延遲，點下去就先讓測光跟上（畫面增亮反應更即時）。
        syncAeRegionsToFocusPoint()

        pendingFocusSettleJob?.cancel()
        pendingFocusSettleJob = lifecycleScope.launch {
            delay(FOCUS_SETTLE_DELAY_MS)
            if (lockAfterFocus) {
                // v0.5.3 修正：刻意不呼叫 rtmpCamera2.disableAutoFocus()——見類別頂端 KDoc 與
                // handleFocusGesture 函式註解，該函式庫呼叫會導致鏡頭跳焦變模糊。
                // CONTROL_AF_MODE_AUTO 觸發一次收斂後鏡頭本身就會停在鎖定狀態不再漂移，
                // 這裡只需標記 UI 狀態，不需要再多一次相機 API 呼叫。
                isFocusLocked = true
                updateFocusLockButtonUi()
                binding.focusIndicatorView.showLocked(viewX, viewY)
                // v0.12.0：對焦收斂後才鎖 AE（見計畫書項目 2「現有收斂流程結尾加 AE_LOCK」），
                // 焦點與亮度一起凍結。
                // v0.17.0（第一階段第1項）：依實際是否鎖到曝光如實顯示——鎖成功「曝光與對焦已鎖」，
                // 裝置不支援 AE 鎖定或反射失敗則「僅鎖焦」，不再讓使用者誤以為亮度已凍結。
                val aeLocked = setAutoExposureLock(true)
                Toast.makeText(
                    this@LiveActivity,
                    getString(if (aeLocked) R.string.focus_locked_with_ae else R.string.focus_locked_focus_only),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                try {
                    rtmpCamera2.enableAutoFocus()
                } catch (e: Exception) {
                    // 恢復連續對焦失敗不影響操作流程，僅圓框正常淡出
                }
                binding.focusIndicatorView.hide()
            }
        }
    }

    // ---------- v0.12.0：AE/AF 同步鎖定——RootEncoder 2.6.1 未開放 AE_LOCK／AE_REGIONS 公開
    //            API（已反編譯查證，見計畫書項目 2 與類別頂端 KDoc），全段皆用反射，任一步
    //            失敗一律安靜退回「只鎖對焦」不動 AE，並只提示一次 ----------

    /**
     * v0.17.0（第一階段第1項）：裝置是否支援 **AE 鎖定**（`CONTROL_AE_LOCK_AVAILABLE`），查一次後
     * 快取在 [aeLockSupported]。原本這裡把「AE 鎖定」與「AE 測光區域」兩個獨立能力綁在一起（要
     * `maxAeRegions > 0` 才回 true），害只支援鎖定、不支援測光區域的裝置連曝光鎖都被跳過。現在拆開：
     * 曝光鎖（[setAutoExposureLock]）只看這一項，測光區域跟隨（[syncAeRegionsToFocusPoint]）另看
     * [isAeRegionSupported]。`getCameraCharacteristics()` 是公開 API，不需反射；失敗（相機尚未開啟等）視為不支援。
     */
    private fun isAeLockSupported(): Boolean {
        aeLockSupported?.let { return it }
        val supported = try {
            val characteristics = rtmpCamera2.cameraCharacteristics
            characteristics?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        } catch (e: Exception) {
            false
        }
        aeLockSupported = supported
        return supported
    }

    /**
     * v0.17.0（第一階段第1項）：裝置是否支援 **AE 測光區域**（`CONTROL_MAX_REGIONS_AE > 0`），查一次後
     * 快取在 [aeRegionSupported]。只有「測光跟隨點擊處」（[syncAeRegionsToFocusPoint]）需要它；
     * 曝光鎖本身不需要測光區域。
     */
    private fun isAeRegionSupported(): Boolean {
        aeRegionSupported?.let { return it }
        val supported = try {
            val characteristics = rtmpCamera2.cameraCharacteristics
            (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
        } catch (e: Exception) {
            false
        }
        aeRegionSupported = supported
        return supported
    }

    /**
     * 反射取出函式庫內部共用的 `CaptureRequest.Builder`：`Camera2Base`（[RtmpCamera2] 父類別）
     * 私有欄位 `cameraManager`（`Camera2ApiManager` 實例）→ 其私有欄位 `builderInputSurface`。
     * `tapToFocus()` 剛設定好的 `CONTROL_AF_REGIONS` 就在這顆 builder 上，借它的座標換算結果
     * 才不用自己重算一次感光元件座標。任一步失敗回傳 null，呼叫端一律當作「這次不動 AE」處理。
     */
    private fun getCaptureRequestBuilderForAe(): CaptureRequest.Builder? {
        val cameraManagerField = Camera2Base::class.java.getDeclaredField("cameraManager")
        cameraManagerField.isAccessible = true
        val camera2ApiManager = cameraManagerField.get(rtmpCamera2) ?: return null

        val builderField = camera2ApiManager.javaClass.getDeclaredField("builderInputSurface")
        builderField.isAccessible = true
        return builderField.get(camera2ApiManager) as? CaptureRequest.Builder
    }

    /**
     * 反射呼叫 Kotlin 編譯器為原本 private 的 `applyRequest()` 產生的 synthetic 橋接方法
     * `access$applyRequest`（public static）——這是函式庫內部真正把 builder 套用到
     * repeating request 的入口，不呼叫這一步，前面對 builder 的 set() 只是改了記憶體裡的值，
     * 相機不會真的套用。
     */
    private fun applyAeCaptureRequest(builder: CaptureRequest.Builder): Boolean {
        val cameraManagerField = Camera2Base::class.java.getDeclaredField("cameraManager")
        cameraManagerField.isAccessible = true
        val camera2ApiManager = cameraManagerField.get(rtmpCamera2) ?: return false

        val applyMethod = camera2ApiManager.javaClass.getDeclaredMethod(
            "access\$applyRequest", camera2ApiManager.javaClass, CaptureRequest.Builder::class.java
        )
        applyMethod.isAccessible = true
        return applyMethod.invoke(null, camera2ApiManager, builder) as? Boolean ?: false
    }

    /**
     * 點擊對焦時呼叫：把 builder 裡剛被 `tapToFocus()` 設定好的 `CONTROL_AF_REGIONS` 複製到
     * `CONTROL_AE_REGIONS`，測光跟著點擊處走（點暗處＝畫面增亮）。裝置不支援或反射失敗
     * 一律安靜略過（呼叫 [warnAeSyncFailedOnce] 只提示一次），不影響既有對焦流程。
     */
    private fun syncAeRegionsToFocusPoint() {
        // v0.17.0（第一階段第1項）：測光跟隨需要「AE 測光區域」能力，改用 isAeRegionSupported()
        // （不再借用綁死的 isAeLockSupported）——只支援鎖定不支援測光區域的裝置在此正確略過測光跟隨，
        // 但曝光鎖（setAutoExposureLock）仍會生效。
        if (!isAeRegionSupported()) return
        try {
            val builder = getCaptureRequestBuilderForAe() ?: return
            val afRegions = builder.get(CaptureRequest.CONTROL_AF_REGIONS)
            if (afRegions == null || afRegions.isEmpty()) return
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, afRegions)
            if (!applyAeCaptureRequest(builder)) warnAeSyncFailedOnce()
        } catch (e: Exception) {
            warnAeSyncFailedOnce()
        }
    }

    /**
     * 長按鎖定收斂完成後（[handleFocusGesture]）加 `CONTROL_AE_LOCK=true`，焦點與亮度一起凍結；
     * 解鎖（[setupFocusLock]）呼叫 `locked=false` 恢復自動測光。嚴禁改呼叫函式庫的
     * `disableAutoExposure()`（`AE_MODE_OFF` 陷阱，亮度會亂跳，已於函式庫原始碼查證，
     * 見類別頂端 KDoc）。裝置不支援或反射失敗一律安靜退回，只提示一次。
     */
    private fun setAutoExposureLock(locked: Boolean): Boolean {
        if (!isAeLockSupported()) return false
        return try {
            val builder = getCaptureRequestBuilderForAe() ?: return false
            builder.set(CaptureRequest.CONTROL_AE_LOCK, locked)
            val applied = applyAeCaptureRequest(builder)
            if (!applied) warnAeSyncFailedOnce()
            applied
        } catch (e: Exception) {
            warnAeSyncFailedOnce()
            false
        }
    }

    /** 反射失敗只提示一次，避免長按/點擊對焦時反覆跳 Toast 打擾操作者。 */
    private fun warnAeSyncFailedOnce() {
        if (hasWarnedAeSyncFailed) return
        hasWarnedAeSyncFailed = true
        Toast.makeText(this, getString(R.string.ae_sync_unsupported_message), Toast.LENGTH_LONG).show()
    }

    /**
     * 音量鍵＝變焦：攔截後不呼叫 super，系統音量條不會跳出來（此 APP 音量鍵功能已改為變焦）。
     * 呼叫 [RtmpCamera2.setZoom]（底層依 API 等級對應 `CaptureRequest.CONTROL_ZOOM_RATIO` 或
     * `SCALER_CROP_REGION` 裁切變焦），[RtmpCamera2.getZoomRange] 讀出裝置實際支援的變焦範圍夾限。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // v0.17.0（第一階段第3項）：記錄按鍵事件與 repeatCount（區分快速短按/稍長短按/持續長按），
                // 供日後決定是否攔截 repeat。本階段禁止改變行為——照舊每個事件都 changeZoom。
                DiagLogger.log(this, "ZOOM", "VOLUME_UP repeatCount=${event?.repeatCount ?: -1}")
                changeZoom(ZOOM_STEP)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                DiagLogger.log(this, "ZOOM", "VOLUME_DOWN repeatCount=${event?.repeatCount ?: -1}")
                changeZoom(-ZOOM_STEP)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun changeZoom(delta: Float) {
        if (!isCameraReadyForControl()) {
            DiagLogger.log(this, "ZOOM", "delta=$delta 相機未就緒，略過")
            return
        }
        try {
            val zoomRange = rtmpCamera2.zoomRange
            val before = currentZoomLevel
            val newZoom = (currentZoomLevel + delta).coerceIn(zoomRange.lower, zoomRange.upper)
            // v0.17.0（第一階段第3項）：記錄 zoom range／前後值／套用結果。本階段不改步距/比例（禁止擴張）。
            if (newZoom == currentZoomLevel) {
                DiagLogger.log(this, "ZOOM", "range=[${zoomRange.lower},${zoomRange.upper}] before=$before delta=$delta 已達端點未變")
                Toast.makeText(this, getString(R.string.zoom_limit_reached_message), Toast.LENGTH_SHORT).show()
                return
            }
            rtmpCamera2.setZoom(newZoom)
            currentZoomLevel = newZoom
            binding.tvZoomValue.text = getString(R.string.zoom_value_format, currentZoomLevel)
            DiagLogger.log(this, "ZOOM", "range=[${zoomRange.lower},${zoomRange.upper}] before=$before delta=$delta after=$newZoom 已套用")
        } catch (e: Exception) {
            DiagLogger.log(this, "ZOOM", "delta=$delta 例外：${e.message}")
            Toast.makeText(
                this, getString(R.string.zoom_adjust_failed_format, e.message ?: ""), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 直播中禁止進入設定頁（避免中途誤改串流規格），收播後恢復正常可點擊。 */
    private fun setupSettingsEntry() {
        binding.btnSettingsEntry.setOnClickListener {
            if (isLive) {
                Toast.makeText(this, getString(R.string.settings_locked_while_live_message), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ---------- 左側欄：關閉軟體（鎖定邏輯與設定按鈕一致，見 setLiveUiState） ----------

    /** 直播中禁止關閉軟體（避免誤觸中斷直播），收播後恢復正常可點擊；按下直接完整結束整個 APP。 */
    private fun setupCloseAppButton() {
        binding.btnCloseApp.setOnClickListener {
            if (isLive) {
                Toast.makeText(this, getString(R.string.close_app_locked_while_live_message), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.close_app_confirm_title))
                .setMessage(getString(R.string.close_app_confirm_message))
                .setPositiveButton(getString(R.string.close_app_confirm_ok)) { _, _ -> finishAffinity() }
                .setNegativeButton(getString(R.string.dialog_cancel_button), null)
                .show()
        }
    }

    // ---------- 返回鍵：直播中先跳確認框，避免誤按整個結束直播離開 ----------

    /**
     * 直播中攔截返回鍵：跳確認框，按「確定」才真正收播＋離開；按「取消」留在直播畫面，直播不受影響。
     * 未直播時維持原本預設行為（直接 finish 離開，不跳確認框）。
     */
    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this) {
            if (isLive) {
                showExitDuringLiveConfirmDialog()
            } else {
                finish()
            }
        }
    }

    private fun showExitDuringLiveConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.back_press_confirm_title))
            .setMessage(getString(R.string.back_press_confirm_message))
            .setPositiveButton(getString(R.string.back_press_confirm_ok)) { _, _ ->
                stopLiveStream(showToast = false)
                finish()
            }
            .setNegativeButton(getString(R.string.dialog_cancel_button), null)
            .show()
    }

    // ---------- 三個可收合面板（上/左/右）：各自獨立收合／展開，收起後留半透明邊緣把手；
    //            v0.8.0 起底部計分列改永遠顯示，不再收合 ----------

    // v0.8.0：底部計分列改為永遠顯示的固定列，不再參與收合，故 PanelEdge 移除 BOTTOM，
    // 只保留上/左/右三個方向；bottomScoreRow 本身也不再有收合箭頭／把手。
    private enum class PanelEdge { TOP, START, END }

    /**
     * 面板本身不切換 visibility（避免影響其他 View 的 ConstraintLayout 相對定位），
     * 只用 translationX/Y 平移到畫面外；把手則用 VISIBLE/INVISIBLE 切換顯示。
     */
    private fun setupCollapsiblePanel(
        container: View,
        collapseButton: View,
        handleButton: View,
        edge: PanelEdge
    ) {
        var collapsed = false

        collapseButton.setOnClickListener {
            if (collapsed) return@setOnClickListener
            collapsed = true
            // 平移距離＝面板本身尺寸＋面板在該方向上的外距＋展開把手尺寸，
            // 確保面板（含收合箭頭）完全滑出畫面外，不會與固定於邊緣的展開把手重疊。
            val marginParams = container.layoutParams as? ViewGroup.MarginLayoutParams
            val distance = when (edge) {
                PanelEdge.TOP ->
                    container.height + (marginParams?.topMargin ?: 0) + handleButton.height
                PanelEdge.START ->
                    container.width + (marginParams?.marginStart ?: 0) + handleButton.width
                PanelEdge.END ->
                    container.width + (marginParams?.marginEnd ?: 0) + handleButton.width
            }.toFloat()
            val translationTarget = when (edge) {
                PanelEdge.TOP -> -distance
                PanelEdge.START -> -distance
                PanelEdge.END -> distance
            }
            val animator = container.animate().setDuration(PANEL_ANIM_DURATION_MS)
            if (edge == PanelEdge.TOP) {
                animator.translationY(translationTarget)
            } else {
                animator.translationX(translationTarget)
            }
            animator.withEndAction { handleButton.visibility = View.VISIBLE }.start()
        }

        handleButton.setOnClickListener {
            if (!collapsed) return@setOnClickListener
            collapsed = false
            handleButton.visibility = View.INVISIBLE
            val animator = container.animate().setDuration(PANEL_ANIM_DURATION_MS)
            if (edge == PanelEdge.TOP) {
                animator.translationY(0f)
            } else {
                animator.translationX(0f)
            }
            animator.start()
        }
    }

    // v0.10.0：同步錄影備份實際存到哪裡，收播時決定 Toast 內容（見 [recordSavedToastMessage]）
    private enum class RecordDestination { NONE, GALLERY_MEDIA_STORE, LEGACY_PATH, CUSTOM_FOLDER }

    private fun setupCollapsiblePanels() {
        setupCollapsiblePanel(
            binding.topPanelContainer, binding.btnCollapseTop, binding.btnHandleTop, PanelEdge.TOP
        )
        // v0.15.0：頂部面板拉出時要蓋在上方狀態文字（溫度/變焦/版本號/碼率壅塞提示等）之上——
        // 那些 TextView 在 layout 裡宣告得比面板晚、繪製順序在面板之上，把面板提到最後繪製即可
        //（文字不隱藏，被面板遮住是預期行為；面板收合後只剩把手，不影響文字平時顯示）
        binding.topPanelContainer.bringToFront()
        setupCollapsiblePanel(
            binding.leftPanelContainer, binding.btnCollapseLeft, binding.btnHandleLeft, PanelEdge.START
        )
        setupCollapsiblePanel(
            binding.rightPanelContainer, binding.btnCollapseRight, binding.btnHandleRight, PanelEdge.END
        )
    }

    // ==================== v0.13.0：功能 A 精彩時刻標記（見類別頂端 KDoc） ====================

    /** ⭐鈕：只在直播中可按，記「目前直播經過時間－回推秒數」＋當下節數/兩隊比分。 */
    private fun setupHighlightMarkButton() {
        binding.btnHighlightMark.setOnClickListener {
            if (!isLive) {
                Toast.makeText(this, getString(R.string.highlight_only_while_live_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val reboundSeconds = StreamPrefs.parseHighlightReboundSeconds(StreamPrefs.getHighlightReboundSeconds(this))
            val elapsedMs = SystemClock.elapsedRealtime() - liveStartElapsedMs
            val markTimestampMs = (elapsedMs - reboundSeconds * 1000L).coerceAtLeast(0L)
            highlightMarkers.add(HighlightMarker(markTimestampMs, period, scoreHome, scoreAway))
            persistHighlightMarkers()
            Toast.makeText(this, getString(R.string.highlight_marked_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistHighlightMarkers() {
        HighlightStore.save(this, highlightSessionTimestamp, highlightMarkers)
    }

    /**
     * 精彩清單：程式化建構列表（不新增 layout/adapter，比照 [showTeamNameEditDialog] 的做法），
     * 每筆可 ±5 秒微調、可刪除，改動即時存檔（[persistHighlightMarkers]）並重繪列表本身。
     * 「複製章節格式」把目前清單（依時間排序）整份轉成多行文字放進剪貼簿。
     */
    private fun showHighlightListDialog() {
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val scrollView = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
        }
        scrollView.addView(container)

        fun rebuildRows() {
            container.removeAllViews()
            val sorted = highlightMarkers.sortedBy { it.timestampMs }
            if (sorted.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = getString(R.string.highlight_list_empty)
                    setTextColor(getColor(R.color.text_secondary))
                })
                return
            }
            sorted.forEach { marker -> container.addView(buildHighlightRow(marker) { rebuildRows() }) }
        }
        rebuildRows()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.highlight_list_dialog_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.highlight_copy_chapter_button)) { _, _ -> copyHighlightsAsChapterFormat() }
            .setNeutralButton(getString(R.string.highlight_share_line_button)) { _, _ -> shareHighlightsToLine() }
            .setNegativeButton(getString(R.string.highlight_close_button), null)
            .show()
    }

    /** 精彩清單單一列：時間文字＋−5秒/＋5秒/刪除三顆小按鈕，改動後呼叫 [onChanged] 觸發列表重繪。 */
    private fun buildHighlightRow(marker: HighlightMarker, onChanged: () -> Unit): android.view.View {
        val paddingPx = (6 * resources.displayMetrics.density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, paddingPx, 0, paddingPx)
        }
        val label = TextView(this).apply {
            text = marker.toDisplayLine()
            setTextColor(getColor(R.color.white))
            textSize = 13f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        fun adjustButton(text: String, deltaMs: Long): android.widget.Button = android.widget.Button(this).apply {
            this.text = text
            textSize = 10f
            setOnClickListener {
                val index = highlightMarkers.indexOf(marker)
                if (index < 0) return@setOnClickListener
                val newTimestamp = (marker.timestampMs + deltaMs).coerceAtLeast(0L)
                highlightMarkers[index] = marker.copy(timestampMs = newTimestamp)
                persistHighlightMarkers()
                onChanged()
            }
        }
        row.addView(adjustButton(getString(R.string.highlight_adjust_minus5), -5000L))
        row.addView(adjustButton(getString(R.string.highlight_adjust_plus5), 5000L))

        val deleteButton = android.widget.Button(this).apply {
            text = getString(R.string.highlight_delete_button)
            textSize = 10f
            setOnClickListener {
                highlightMarkers.remove(marker)
                persistHighlightMarkers()
                onChanged()
            }
        }
        row.addView(deleteButton)
        return row
    }

    /**
     * v0.15.2：開頭固定補「00:00 開場」——YouTube 章節硬性規定第一行必須是 00:00（另兩條：
     * 至少 3 個時間點、每段至少 10 秒），補了貼上說明欄就直接生效，Boss 不用手動加。
     */
    private fun buildHighlightChapterText(): String =
        (listOf(getString(R.string.highlight_chapter_opening_line)) +
            highlightMarkers.sortedBy { it.timestampMs }.map { it.toDisplayLine() })
            .joinToString("\n")

    private fun copyHighlightsAsChapterFormat() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("highlight_chapters", buildHighlightChapterText()))
        Toast.makeText(this, getString(R.string.highlight_copy_chapter_toast), Toast.LENGTH_SHORT).show()
    }

    /**
     * v0.15.0：精彩清單「分享LINE」——章節文字直接以 ACTION_SEND 指定 LINE 套件包名開啟
     * （Manifest 已加 `<queries>` 宣告 LINE 包名，Android 11+ 套件可見性規定）；
     * LINE 未安裝（或不可見）拋 ActivityNotFoundException，退回系統分享面板讓 Boss 自選。
     */
    private fun shareHighlightsToLine() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildHighlightChapterText())
            `package` = LINE_PACKAGE_NAME
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            intent.`package` = null
            startActivity(Intent.createChooser(intent, getString(R.string.highlight_share_line_button)))
        }
    }

    // ==================== v0.16.0：功能三 休息畫面（黑底四節計分表，樣式09） ====================

    /**
     * 「休息畫面」鈕（左下，與右下⭐鈕鏡像對稱）：切換休息／返回直播。
     * v0.16.2：原本只在直播中可按（非直播跳提示），Boss 在家預覽測試按了以為壞掉——改成
     * 預覽時也能切（方便賽前驗收畫面）；停相機降溫仍只在真正直播中做（見 [enterBreakMode]）。
     */
    private fun setupBreakScreenButton() {
        binding.btnBreakScreen.setOnClickListener {
            if (!isBreakMode) enterBreakMode() else exitBreakMode()
        }
        // v0.16.4：⭐標記鈕（3 字）文字大小同步成休息鈕（4 字）自動縮字後的實際字級——
        // 兩顆鈕左右鏡像對稱，字級不同會一大一小（Boss 指定改相同）。post 等 autoSize 量測完才讀值
        binding.btnBreakScreen.post {
            binding.btnHighlightMark.setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.btnBreakScreen.textSize)
        }
    }

    /**
     * 進入休息畫面：全螢幕休息畫面濾鏡疊到最上層蓋住鏡頭與計分板 → 開強制算圖（相機停後 GL 仍以
     * 固定 fps 續送幀，推流不中斷）→ 反射停相機擷取降溫（順位2；失敗退順位3只遮畫面、相機不停）。
     * 見類別頂端 KDoc v0.16.0 條目。
     */
    private fun enterBreakMode() {
        if (isBreakMode) return
        if (streamWidthForOverlay <= 0 || streamHeightForOverlay <= 0) return
        isBreakMode = true
        // v0.17.0（第一階段第4項）：進入休息重設碼率警告 epoch（不武裝「恢復中」寬限）
        resetBitrateWarningEpoch(armRecovering = false)
        DiagLogger.log(this, "BREAK", "進入休息，碼率警告 epoch 重設")
        // 1. 全螢幕休息畫面濾鏡疊到最上層（addFilter 走 filterQueue，執行緒安全，避開 stop() 的競態坑）
        breakScreenFilter.setImage(buildBreakScreenBitmap())
        breakScreenFilter.setScale(100f, 100f)
        breakScreenFilter.setPosition(TranslateTo.CENTER)
        rtmpCamera2.getGlInterface().addFilter(breakScreenFilter)
        // 2.+3. 停相機降溫（順位2）＋強制算圖：v0.16.3 Boss 直播實測——停相機後畫面凍結在按下
        //    當下的最後一幀，setForceRender 在 Camera2Base 舊管線上並沒有真的驅動 GL 續送幀
        //    （濾鏡佇列也因此不被消化，休息畫面根本沒套上；中場久了 YT 還會判定斷流）。
        //    順位2 先停用（BREAK_STOP_CAMERA_ENABLED=false）、一律走順位3「只遮畫面、相機不停」
        //    （v0.13.0 實戰驗證的保底路線，無降溫效果，Boss 已知情）。
        //    ponytail: 若日後查出這條舊管線正確的強制算圖用法，把旗標開回即可，停/恢復程式碼保留
        if (BREAK_STOP_CAMERA_ENABLED) {
            binding.openGlView.setForceRender(true, StreamPrefs.parseFps(StreamPrefs.getFps(this)))
            // 只在真正直播中停相機——預覽切休息只是驗收畫面，不停相機省去恢復風險（v0.16.2）
            breakCameraStopped = if (isLive) stopCameraForBreak() else false
        } else {
            breakCameraStopped = false
        }
        binding.btnBreakScreen.text = getString(R.string.break_return_button)
        Toast.makeText(this, getString(R.string.break_entered_toast), Toast.LENGTH_SHORT).show()
    }

    /** 返回直播：恢復相機擷取 → 關強制算圖回相機幀驅動 → 移除休息畫面濾鏡露出鏡頭畫面。 */
    private fun exitBreakMode() {
        if (!isBreakMode) return
        isBreakMode = false
        // v0.17.0（第一階段第4項）：離開休息重設 epoch 並武裝「恢復中」寬限——前 BITRATE_RECOVERING_SAMPLES
        // 筆有效樣本顯示「恢復中」，避免恢復瞬間 I-frame burst 的碼率尖峰被誤判成警告。
        resetBitrateWarningEpoch(armRecovering = true)
        // v0.17.3：休息期間 adapter 已暫停；離開時建立乾淨起點、恢復使用者目標並重新暖機，
        // 避免恢復畫面的暫態 raw／I-frame 波動再次觸發誤降。
        if (rtmpCamera2.isStreaming && StreamPrefs.isBitrateAutoAdjust(this)) {
            val targetBitrate = StreamPrefs.parseBitrate(StreamPrefs.getBitrate(this))
            bitrateAdapter.reset()
            bitrateAdapter.setMaxBitrate(targetBitrate)
            rtmpCamera2.setVideoBitrateOnFly(targetBitrate)
            bitrateAdaptationWarmup.start(SystemClock.elapsedRealtime())
            reconnectRecoveryGate.start(SystemClock.elapsedRealtime())
            startReconnectRecoveryWatchdog()
            DiagLogger.log(
                this, "BITRATE-BREAK",
                "離開休息：adapter reset，恢復 target=$targetBitrate，暖機=${BITRATE_ADAPTATION_WARMUP_MS}ms"
            )
        }
        DiagLogger.log(this, "BREAK", "離開休息，碼率警告 epoch 重設＋武裝恢復中寬限")
        // 1.+2. 恢復相機擷取＋關強制算圖（只有先前真的停成功才需要；順位2 停用時恆為 false，
        //    見 enterBreakMode 的 BREAK_STOP_CAMERA_ENABLED 註解）
        if (breakCameraStopped) {
            resumeCameraAfterBreak()
            breakCameraStopped = false
            binding.openGlView.setForceRender(false)
        }
        // 3. 移除休息畫面濾鏡，露出鏡頭畫面（removeFilter 走 filterQueue，執行緒安全）
        try {
            rtmpCamera2.getGlInterface().removeFilter(breakScreenFilter)
        } catch (e: Exception) {
            // 濾鏡已不在鏈上（例如 replaceView 重建過）時忽略即可
        }
        binding.btnBreakScreen.text = getString(R.string.break_screen_button)
        Toast.makeText(this, getString(R.string.break_exited_toast), Toast.LENGTH_SHORT).show()
    }

    /**
     * 反射取 [Camera2Base] 私有欄位 `cameraManager`（[com.pedro.encoder.input.video.Camera2ApiManager]
     * 實例，與 v0.12.0 AE/AF 同步鎖定用的同一顆），release 版 `isMinifyEnabled=false` 保證欄位名不被
     * 混淆（見 app/build.gradle.kts）。
     */
    private fun getCameraApiManager(): Any {
        val field = Camera2Base::class.java.getDeclaredField("cameraManager")
        field.isAccessible = true
        return field.get(rtmpCamera2) ?: throw IllegalStateException("cameraManager is null")
    }

    /**
     * 順位2 停相機：反射呼叫 `Camera2ApiManager.closeCamera(false)`——只關 CaptureSession／CameraDevice
     * 降溫，不重建 surfaceEncoder、不動 GL 管線（公開 `stopCamera()` 會連帶 `glInterface.stop()` 停掉
     * 整個送幀，推流會斷，反編譯 library-2.6.1.aar 查證，見類別頂端 KDoc）。成功回 true；反射任一步
     * 失敗回 false，呼叫端據此退回順位3（只遮畫面、相機不停，無降溫但 force render 仍保推流不斷）。
     */
    private fun stopCameraForBreak(): Boolean {
        return try {
            breakSavedCameraId = rtmpCamera2.currentCameraId
            val cameraManager = getCameraApiManager()
            val closeCamera = cameraManager.javaClass.getMethod("closeCamera", Boolean::class.javaPrimitiveType)
            closeCamera.invoke(cameraManager, false)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 恢復相機：複刻框架 `reOpenCamera` 內部序列——因 `closeCamera(false)` 後 `cameraDevice` 為 null，
     * 無法直接呼叫 `reOpenCamera`（它有 `cameraDevice != null` 前置判斷會整段略過），故反射自行執行
     * `prepareCamera(surfaceEncoder, fps)`（把 `isPrepared` 設回 true、沿用原本那顆 GL 輸入 surface）＋
     * `openCameraId(cameraId)` 重新開相機到同一渲染管線。任一步失敗則用 replaceView 兜底重建渲染管線
     * （v0.4.4 既有黑屏修復路徑，會重開相機並保推流）。
     */
    private fun resumeCameraAfterBreak() {
        try {
            val cameraManager = getCameraApiManager()
            val cls = cameraManager.javaClass
            val surfaceField = cls.getDeclaredField("surfaceEncoder").apply { isAccessible = true }
            val fpsField = cls.getDeclaredField("fps").apply { isAccessible = true }
            val surfaceEncoder = surfaceField.get(cameraManager) as Surface
            val fps = fpsField.getInt(cameraManager)
            val prepareCamera = cls.getMethod("prepareCamera", Surface::class.java, Int::class.javaPrimitiveType)
            prepareCamera.invoke(cameraManager, surfaceEncoder, fps)
            val cameraId = breakSavedCameraId ?: rtmpCamera2.currentCameraId
            val openCameraId = cls.getMethod("openCameraId", String::class.java)
            openCameraId.invoke(cameraManager, cameraId)
        } catch (e: Exception) {
            // 反射恢復失敗最後手段：replaceView 重建渲染管線（會重開相機、計分板濾鏡要重新掛回）
            try {
                lifecycleScope.launch {
                    rtmpCamera2.replaceView(binding.openGlView)
                    applyScoreboardOverlayFilter()
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 樣式06（中央對決式）休息畫面：整張黑底（依直播解析度）＋藏青金面板置中。上半部兩隊對決——
     * 隊名（暖白粗體）在上、大總分金底黑字方塊在下（重用 [drawScoreBox]，值＝當下計分板總分
     * [scoreHome]/[scoreAway]）、金色 VS 居中；下半部四節明細小表（隊名＋第1～4節，未結算顯示
     * 半透明暖白「–」）。配色沿用計分板藏青金定案（同 [buildScoreboardOverlayBitmap]）。做法同計分板
     * overlay：Canvas 繪 Bitmap 再掛 [breakScreenFilter]。樣品參照 樣式06 區塊。
     * （v0.16.2：Boss 拍板由樣式09 改樣式06，原左欄賽事名稱欄取消）
     */
    private fun buildBreakScreenBitmap(): Bitmap {
        val frameWidth = streamWidthForOverlay.coerceAtLeast(1)
        val frameHeight = streamHeightForOverlay.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val panelWidth = frameWidth * BREAK_PANEL_WIDTH_RATIO
        val panelHeight = frameHeight * BREAK_PANEL_HEIGHT_RATIO
        val panelLeft = (frameWidth - panelWidth) / 2f
        val panelTop = (frameHeight - panelHeight) / 2f
        val panelRect = RectF(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight)
        val cornerRadius = panelHeight * 0.08f

        // 藏青漸層底＋金細邊框＋頂緣金飾線（與計分板同一套材質，見 buildScoreboardOverlayBitmap）
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, panelTop, 0f, panelTop + panelHeight,
                Color.argb(215, 23, 56, 104), Color.argb(215, 13, 33, 64),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, backgroundPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (panelHeight * 0.01f).coerceAtLeast(2f)
            color = Color.argb(56, 255, 214, 140)
        }
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, borderPaint)
        val accentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                panelLeft + cornerRadius, 0f, panelLeft + panelWidth - cornerRadius, 0f,
                intArrayOf(
                    Color.TRANSPARENT, Color.rgb(243, 207, 127), Color.rgb(224, 168, 63),
                    Color.rgb(243, 207, 127), Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            strokeWidth = (panelHeight * 0.018f).coerceAtLeast(2f)
        }
        val accentLineY = panelTop + panelHeight * 0.06f
        canvas.drawLine(
            panelLeft + cornerRadius, accentLineY, panelLeft + panelWidth - cornerRadius, accentLineY, accentLinePaint
        )

        // 上半部：兩隊對決（隊名在上、大總分金方塊在下、金色 VS 置中）
        val duelTop = panelTop + panelHeight * 0.13f
        val duelBottom = panelTop + panelHeight * 0.56f
        drawBreakDuelBlock(canvas, panelLeft, panelWidth, duelTop, duelBottom, panelHeight)

        // 下半部：四節明細小表
        val padHorizontal = panelWidth * 0.12f
        val tableTop = duelBottom + panelHeight * 0.05f
        val tableBottom = panelTop + panelHeight * 0.92f
        drawBreakQuarterTable(
            canvas, panelLeft + padHorizontal, panelLeft + panelWidth - padHorizontal, tableTop, tableBottom, panelHeight
        )
        return bitmap
    }

    /**
     * 樣式06 上半部兩隊對決：主隊（左）／客隊（右）各自「隊名在上、大總分金底黑字方塊在下」，
     * 金色 VS 置中在兩個總分方塊之間；隊名過寬自動縮字。總分方塊重用 [drawScoreBox]。
     */
    private fun drawBreakDuelBlock(
        canvas: Canvas, panelLeft: Float, panelWidth: Float, top: Float, bottom: Float, panelHeight: Float
    ) {
        val blockHeight = bottom - top
        val nameCenterY = top + blockHeight * 0.20f
        val chipCenterY = top + blockHeight * 0.66f

        fun drawSide(teamName: String, totalScore: Int, centerX: Float) {
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(246, 239, 224)
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            var nameTextSize = panelHeight * 0.095f
            namePaint.textSize = nameTextSize
            while (namePaint.measureText(teamName) > panelWidth * 0.30f && nameTextSize > 8f) {
                nameTextSize -= 1f
                namePaint.textSize = nameTextSize
            }
            canvas.drawText(teamName, centerX, nameCenterY - (namePaint.descent() + namePaint.ascent()) / 2f, namePaint)

            val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = panelHeight * 0.125f
            }
            val boxWidth = panelWidth * 0.17f
            val boxHeight = blockHeight * 0.52f
            drawScoreBox(
                canvas, centerX - boxWidth / 2f, chipCenterY, boxWidth, boxHeight,
                totalScore.coerceAtMost(MAX_SCORE).toString(), scorePaint
            )
        }
        drawSide(teamHomeName, scoreHome, panelLeft + panelWidth * 0.30f)
        drawSide(teamAwayName, scoreAway, panelLeft + panelWidth * 0.70f)

        val vsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(243, 207, 127)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = panelHeight * 0.07f
        }
        canvas.drawText("VS", panelLeft + panelWidth / 2f, chipCenterY - (vsPaint.descent() + vsPaint.ascent()) / 2f, vsPaint)
    }

    /**
     * 樣式06 下半部四節明細小表：3 列（表頭／主隊／客隊）× 5 欄（隊名＋第1～4節）。表頭金色小字、
     * 表頭下金色細分隔線；各節數字暖白，未結算顯示半透明暖白「–」。總分不在此表（上半部對決區
     * 的大金方塊就是總分，見 [drawBreakDuelBlock]）。
     */
    private fun drawBreakQuarterTable(
        canvas: Canvas, tableLeft: Float, tableRight: Float, top: Float, bottom: Float, panelHeight: Float
    ) {
        val tableWidth = tableRight - tableLeft
        val teamColumnWidth = tableWidth * 0.26f
        val numericColumnWidth = (tableWidth - teamColumnWidth) / StreamPrefs.QUARTER_COUNT
        fun numericCenterX(columnIndex: Int) = tableLeft + teamColumnWidth + numericColumnWidth * (columnIndex + 0.5f)

        val totalHeight = bottom - top
        val headerHeight = totalHeight * 0.30f
        val teamRowHeight = (totalHeight - headerHeight) / 2f
        val headerCenterY = top + headerHeight / 2f
        val homeCenterY = top + headerHeight + teamRowHeight / 2f
        val awayCenterY = top + headerHeight + teamRowHeight * 1.5f

        // 表頭：金色小字（第1~4節）
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(243, 207, 127)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = panelHeight * 0.052f
        }
        val headerBaseline = headerCenterY - (headerPaint.descent() + headerPaint.ascent()) / 2f
        for (quarter in 0 until StreamPrefs.QUARTER_COUNT) {
            canvas.drawText(getString(R.string.break_quarter_header_format, quarter + 1), numericCenterX(quarter), headerBaseline, headerPaint)
        }

        // 表頭下金色細分隔線
        val headerDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 243, 207, 127)
            strokeWidth = (panelHeight * 0.008f).coerceAtLeast(1f)
        }
        val headerDividerY = top + headerHeight
        canvas.drawLine(tableLeft, headerDividerY, tableRight, headerDividerY, headerDividerPaint)

        // v0.16.6：進行中那一節（尚未結算）即時帶入目前累積分數（總分−已結算各節加總），
        // 不再等切到下一節才看得到本節比分（Boss 回饋：第二節休息時第二節欄位是空的）
        val liveIndex = period - 1
        val homeRow = quarterScoresHome.copyOf()
        val awayRow = quarterScoresAway.copyOf()
        if (liveIndex in 0 until StreamPrefs.QUARTER_COUNT) {
            if (homeRow[liveIndex] < 0) {
                homeRow[liveIndex] = (scoreHome - homeRow.filter { it >= 0 }.sum()).coerceAtLeast(0)
            }
            if (awayRow[liveIndex] < 0) {
                awayRow[liveIndex] = (scoreAway - awayRow.filter { it >= 0 }.sum()).coerceAtLeast(0)
            }
        }
        drawBreakTeamRow(canvas, teamHomeName, homeRow, tableLeft, teamColumnWidth, homeCenterY, ::numericCenterX, panelHeight)
        drawBreakTeamRow(canvas, teamAwayName, awayRow, tableLeft, teamColumnWidth, awayCenterY, ::numericCenterX, panelHeight)
    }

    /** 樣式06 明細表單一隊列：隊名（暖白粗體、靠右貼齊數字欄，過寬自動縮字）＋各節分數／「–」。 */
    private fun drawBreakTeamRow(
        canvas: Canvas,
        teamName: String,
        quarterScores: IntArray,
        tableLeft: Float,
        teamColumnWidth: Float,
        centerY: Float,
        numericCenterX: (Int) -> Float,
        panelHeight: Float
    ) {
        // 隊名：暖白粗體、靠右對齊貼近數字欄（樣品樣式06 的 .team 對齊方式），過寬自動縮字
        val teamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(246, 239, 224)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }
        var teamTextSize = panelHeight * 0.062f
        teamPaint.textSize = teamTextSize
        while (teamPaint.measureText(teamName) > teamColumnWidth * 0.92f && teamTextSize > 8f) {
            teamTextSize -= 1f
            teamPaint.textSize = teamTextSize
        }
        val teamBaseline = centerY - (teamPaint.descent() + teamPaint.ascent()) / 2f
        canvas.drawText(teamName, tableLeft + teamColumnWidth * 0.92f, teamBaseline, teamPaint)

        // 各節分數：暖白；未結算（-1）顯示半透明暖白「–」
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(246, 239, 224)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = panelHeight * 0.062f
        }
        val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 246, 239, 224)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = panelHeight * 0.062f
        }
        val numberBaseline = centerY - (numberPaint.descent() + numberPaint.ascent()) / 2f
        for (quarter in 0 until StreamPrefs.QUARTER_COUNT) {
            val settled = quarterScores.getOrElse(quarter) { -1 }
            if (settled >= 0) {
                canvas.drawText(settled.toString(), numericCenterX(quarter), numberBaseline, numberPaint)
            } else {
                canvas.drawText(getString(R.string.break_empty_cell), numericCenterX(quarter), numberBaseline, dimPaint)
            }
        }
    }

    private companion object {
        const val YOUTUBE_RTMP_BASE_URL = "rtmp://a.rtmp.youtube.com/live2/"
        const val AUDIO_BITRATE = 128 * 1000
        const val AUDIO_SAMPLE_RATE = 44100
        // v0.12.0：10 → Int.MAX_VALUE——重試上限改為實質無限，直到手動收播才會真正停止
        // （見類別頂端 KDoc／scheduleReconnect），重試間隔維持 5 秒
        const val RECONNECT_MAX_RETRIES = Int.MAX_VALUE
        const val RECONNECT_DELAY_MS = 5000L
        // v0.12.2：收播結束 YouTube 直播失敗時，隔多久自動重試一次（見 endYouTubeBroadcastIfNeeded）
        const val END_BROADCAST_RETRY_DELAY_MS = 5000L
        const val PANEL_ANIM_DURATION_MS = 220L
        // v0.6.0：犯規上限由 5 改為 4（燒入計分板改用 4 格燈號，滿 4 格＝加罰）
        const val MAX_FOUL_COUNT = 4
        // 比分方塊固定以 3 位數寬度繪製（見 SCORE_BOX_WIDTH_REFERENCE_TEXT），分數上限同步夾在 3 位數內避免溢出
        const val MAX_SCORE = 999
        // 隊名輸入字數上限（燒入計分板寬度有限，太長縮字也會溢出）
        // v0.9.6：8 → 4——計分按鈕群移到畫面下方跟計分板同排後，計分板寬度必須固定可控
        const val TEAM_NAME_MAX_LENGTH = 4

        // v0.5.0：音量鍵每次按下的變焦增量（RootEncoder setZoom 單位＝倍率，1.0＝無變焦）
        const val ZOOM_STEP = 0.15f

        // v0.5.1：點觸／長按對焦——近似等待對焦收斂完成的延遲（函式庫未開放 AF_STATE 監聽，見開發回報）
        const val FOCUS_SETTLE_DELAY_MS = 500L

        // 燒入計分板 Bitmap 尺寸＝目前串流解析度的比例（各解析度皆為 16:9，比例維持一致）
        // v0.4.1：Boss 實測後縮小為原本的 2/3（0.62→0.41、0.16→0.107），文字隨 Bitmap 自動等比縮小
        // v0.6.0：改版為左右分區橫幅（左：賽事名稱/節數，右：隊名/犯規燈號/比分），
        // 內容較舊版單行文字複雜，寬高比例同步微調（0.41→0.46、0.107→0.125）
        const val OVERLAY_WIDTH_RATIO = 0.46f
        const val OVERLAY_HEIGHT_RATIO = 0.125f

        // v0.6.0：計分板左側區塊（賽事名稱，v0.9.14 起節數搬離此欄）佔整體寬度比例
        const val LEFT_COLUMN_WIDTH_RATIO = 0.24f
        // v0.6.0：賽事名稱文字大小比例（相對 Bitmap 高度）
        const val EVENT_NAME_TEXT_SIZE_RATIO = 0.26f
        // v0.9.16：2 行時的行距＝字級的這個倍率（1.0 會讓上下行緊貼在一起）
        // v0.9.19：1.15 → 1.3；v0.9.20：1.3 → 1.45（Boss 逐步微調）
        const val EVENT_NAME_LINE_SPACING_RATIO = 1.45f

        // v0.6.0：右側主區塊（隊名/比分）初始文字大小比例，過寬時會自動縮小
        // v0.7.1：Boss 要求隊名字體更大更粗，比例由 0.4 提高到 0.46
        // v0.8.1：Boss 要求隊名（連帶比分，兩者共用此比例）縮小 1/4，0.46→0.345
        const val MAIN_TEXT_SIZE_RATIO = 0.345f
        // v0.7.7：Boss 要求比分跟隊名一樣大，比例由 1.15 改回 1.0
        const val SCORE_TEXT_SIZE_RATIO = 1.0f
        // v0.9.2：Boss 要求主客隊名再小 10%（相對基準字級 MAIN_TEXT_SIZE_RATIO），分數不動
        const val TEAM_NAME_TEXT_SIZE_RATIO = 0.9f
        // 隊名/比分之間的間距比例（相對 Bitmap 高度）
        const val MAIN_ROW_GAP_RATIO = 0.12f
        // v0.7.1：隊名/比分文字列的垂直位置（改為上排，下排讓給犯規長條號誌）
        const val MAIN_ROW_Y_RATIO = 0.38f
        // v0.7.4：鈦金屬比分方塊左右內距比例（相對 Bitmap 高度），數字置中在方塊裡
        const val SCORE_BOX_PADDING_RATIO = 0.09f
        // v0.8.1：比分方塊固定寬度的量測基準（3 位數），比分本身仍照實際位數置中顯示
        const val SCORE_BOX_WIDTH_REFERENCE_TEXT = "888"

        // v0.7.1：犯規改為長條號誌（長條型，非圓點），放在各隊隊名正下方
        // 單條寬度／高度／格間距比例（相對 Bitmap 高度），寬 > 高呈現扁長條狀
        const val FOUL_BAR_WIDTH_RATIO = 0.32f
        const val FOUL_BAR_HEIGHT_RATIO = 0.075f
        const val FOUL_BAR_SPACING_RATIO = 0.045f
        // 犯規長條號誌整排的垂直位置（矩形頂端 Y 座標）
        const val FOUL_BAR_ROW_Y_RATIO = 0.72f

        // v0.4.4：直播中鎖定設定入口按鈕時的透明度（視覺上看得出不可點擊）
        // v0.4.5：關閉軟體按鈕比照同一套鎖定手法，共用同一個透明度常數
        const val LIVE_LOCKED_BUTTON_ALPHA = 0.4f

        // v0.10.0：同步錄影備份——獨立解析度的固定碼率（YAGNI：不另開選項，見計畫書功能包①）
        const val RECORD_BITRATE_720P_BPS = 4000 * 1000
        const val RECORD_BITRATE_1080P_BPS = 8000 * 1000
        const val RECORD_WIDTH = 1920
        const val RECORD_HEIGHT = 1080
        const val RECORD_FPS = 30
        const val RECORD_BITRATE_BPS = 20000 * 1000
        // v0.17.6：4K H.264 由 20 Mbps 提升至 50 Mbps（約 22.5GB／小時），改善高動態畫面壓縮模糊
        const val RECORD_BITRATE_2K_BPS = 12000 * 1000
        const val RECORD_BITRATE_4K_BPS = 50000 * 1000
        // 開播確認框的空間預估：以此小時數換算「需要多少可用空間才不轉紅字警告」
        const val RECORD_SPACE_WARNING_HOURS = 2L
        // tvLiveIndicator 錄影中的文字後綴（見 [updateLiveIndicatorRecSuffix]）
        const val RECORD_INDICATOR_SUFFIX = "｜REC"

        // v0.10.1：電池溫度三段門檻（Boss 定案：38 以下藍、38~42 含 42 黃、超過 42 紅）
        const val TEMP_HEALTHY_UPPER_CELSIUS = 38.0
        const val TEMP_COOLDOWN_UPPER_CELSIUS = 42.0
        val TEMP_COLOR_HEALTHY = Color.rgb(100, 181, 246)   // 藍
        val TEMP_COLOR_COOLDOWN = Color.rgb(255, 193, 7)    // 黃
        val TEMP_COLOR_THROTTLED = Color.rgb(255, 82, 82)   // 紅
        // v0.10.1：實際/設定碼率的達標門檻（≥九成白字、九成~七成黃字、低於七成紅字）
        const val BITRATE_OK_RATIO = 0.9
        const val BITRATE_LOW_RATIO = 0.7
        // v0.17.0（第一階段第4項）：碼率警告狀態的 EWMA 平滑係數與「恢復中」寬限樣本數。
        // alpha 越大越跟隨瞬時值；0.3 兼顧反應與抗抖動。離開休息後前 2 筆有效樣本顯示「恢復中」。
        const val BITRATE_EWMA_ALPHA = 0.3
        const val BITRATE_RECOVERING_SAMPLES = 2
        // v0.17.1（審查第4項）：警告狀態切換的連續樣本遲滯門檻——保守佔位 N=3，待實機直播資料校準
        const val BITRATE_WARNING_STABLE_SAMPLES = 3
        val BITRATE_COLOR_RECOVERING = Color.rgb(79, 195, 247)  // 青（恢復中／連線穩定前的中性提示）
        // v0.17.2（Phase 2）：RTMP 連線成功後暫停餵入 BitrateAdapter 的時間；實機採證後可單點校準。
        const val BITRATE_ADAPTATION_WARMUP_MS = 10_000L
        const val RECONNECT_RECOVERY_TIMEOUT_MS = 20_000L
        const val RECONNECT_RECOVERY_HEALTHY_RATIO = 0.7
        const val RECONNECT_RECOVERY_HEALTHY_SAMPLES = 3
        const val RECONNECT_PIPELINE_RESTART_DELAY_MS = 500L

        // v0.15.0：LINE 官方套件包名——精彩清單「分享LINE」直接指定開啟（見 shareHighlightsToLine）
        const val LINE_PACKAGE_NAME = "jp.naver.line.android"

        // v0.15.0：GL 渲染錯誤記錄節流——暫態競態可能連續多幀觸發，30 秒最多寫一檔避免灌爆 crashlog/
        const val RENDER_ERROR_LOG_THROTTLE_MS = 30_000L

        // v0.16.0：功能三——休息畫面面板佔整張畫面（黑底）的寬／高比例（16:9 置中）
        // v0.16.2：樣式09 改樣式06（上下兩層：對決區＋四節明細表），面板改窄改高
        const val BREAK_PANEL_WIDTH_RATIO = 0.60f
        const val BREAK_PANEL_HEIGHT_RATIO = 0.62f

        // v0.16.3：休息畫面「停相機降溫」（順位2）總開關——Boss 直播實測停相機後畫面凍結
        // （setForceRender 在 Camera2Base 舊管線沒真的驅動 GL 續送幀），先停用一律走順位3
        // 只遮畫面；日後查出正確用法再開回（見 enterBreakMode）
        const val BREAK_STOP_CAMERA_ENABLED = false
    }
}
