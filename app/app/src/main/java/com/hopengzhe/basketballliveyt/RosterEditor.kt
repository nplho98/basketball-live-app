package com.hopengzhe.basketballliveyt

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * v0.22.3：名單編輯改成 [StreamPrefs.ROSTER_MAX_SIZE] 個獨立輸入格，取代原本的單一多行輸入框。
 *
 * 為什麼換掉多行框：設定頁也鎖橫式，多行框一次只看得到四五行，12～15 位永遠有一半在捲軸外，
 * 要改第 11 位得先捲下去找（Boss 2026-09-06 回報）。排成格子就一眼全在，要改誰點誰。
 *
 * v0.22.11：每一格是「號碼＋姓名」兩個輸入框（Boss 指定加背號），因此改排 3 欄 × 5 列——
 * 維持 5 欄的話一格要塞兩個框，會窄到打不了字。
 *
 * v0.22.7：不用 [EditorInfo.IME_FLAG_NO_EXTRACT_UI]——那個旗標本來是要擋掉橫式的全螢幕編輯，
 * 但在 CPH2525 的注音鍵盤上會讓鍵盤整個不出現（Boss 實機回報，直播畫面那份也已拿掉）。
 *
 * 格式契約收斂在 [StreamPrefs.saveRosterEntries]：姓名空白的整筆丟掉，後面的人自動往前遞補——
 * 刪掉第 3 位不必手動把後面的人一格一格搬上來。
 */
object RosterEditor {

    private const val COLUMNS = 3

    /** 建出可直接塞進 AlertDialog 的 View，以及讀出目前 15 格內容的函式。 */
    fun build(
        activity: Activity,
        entries: List<StreamPrefs.RosterEntry>
    ): Pair<View, () -> List<StreamPrefs.RosterEntry>> {
        val density = activity.resources.displayMetrics.density
        val paddingPx = (12 * density).toInt()
        val gapPx = (3 * density).toInt()
        val numberFields = ArrayList<EditText>(StreamPrefs.ROSTER_MAX_SIZE)
        val nameFields = ArrayList<EditText>(StreamPrefs.ROSTER_MAX_SIZE)

        fun field(initial: String, hintText: String, numeric: Boolean) = EditText(activity).apply {
            setText(initial)
            hint = hintText
            gravity = Gravity.CENTER
            setSingleLine(true)
            // 程式建的 EditText 沒設 inputType 時是 TYPE_NULL，系統當成「不收 IME 輸入」，
            // 點下去只有游標不跳鍵盤（Boss 2026-09-06 實機回報）
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setPadding(gapPx, gapPx, gapPx, gapPx)
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }

        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, gapPx, paddingPx, 0)
        }

        var row: LinearLayout? = null
        for (index in 0 until StreamPrefs.ROSTER_MAX_SIZE) {
            if (index % COLUMNS == 0) {
                row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row)
            }
            val entry = entries.getOrNull(index)
            val numberField = field(entry?.number ?: "", "#", numeric = true)
            // 空格的姓名欄顯示自己是第幾位，填了名字就看不到
            val nameField = field(entry?.name ?: "", (index + 1).toString(), numeric = false)
            numberFields.add(numberField)
            nameFields.add(nameField)
            row?.addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        numberField,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        nameField,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(gapPx, gapPx, gapPx, gapPx)
                }
            )
        }

        // 鍵盤升起後高度更吃緊，包一層才捲得動
        val scrollView = ScrollView(activity).apply { addView(grid) }
        val read = {
            nameFields.indices.map { index ->
                StreamPrefs.RosterEntry(
                    numberFields[index].text.toString(),
                    nameFields[index].text.toString()
                )
            }
        }
        return scrollView to read
    }
}
