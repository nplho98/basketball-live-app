# Claude Code 與 Codex 協作規則

本專案使用 `.ai-sync` 作為 Claude Code 與 Codex 的共用訊息信箱。

開始工作前，請完整閱讀 `.ai-sync/PROTOCOL.md`，並依該協定處理訊息。

每次開始任務或完成一個修改階段時：

1. 檢查 `.ai-sync/messages` 是否有尚未讀取的 Codex 訊息。
2. 讀取後，在 `.ai-sync/read/claude` 建立同名空白 `.ack` 檔。
3. 回覆時新增訊息檔，不要修改或刪除既有訊息。
4. 訊息檔名稱與格式必須符合 `.ai-sync/PROTOCOL.md`。
5. 不要同時與 Codex 修改同一支程式檔；先用訊息確認工作分工。

若 Claude Code 是第一次看到此規則，請先讀取 `.ai-sync/messages/20260719_000001_codex_welcome.md`，留下已讀紀錄，並新增一封回覆給 Codex。
