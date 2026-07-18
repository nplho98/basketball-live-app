# Google API 憑證資訊（籃球直播 APP）

**建立日期**：2026-07-07
**Google Cloud 專案**：Basketball Live（專案 ID：basketball-live-501700）

## OAuth 用戶端（Android）

- 用戶端 ID：`708591042978-3i2v4j86hio42k4eifgup58chrg907n1.apps.googleusercontent.com`
- 套件名稱：`com.hopengzhe.basketballliveyt`
- SHA-1（debug 簽章）：`0E:84:94:4D:12:DE:14:27:B8:3F:9E:EC:96:40:AF:0E:49:12:40:D9`

> 用戶端 ID 不是機密（會隨 APP 發布），真正的驗證靠套件名稱＋簽章指紋比對。
> 之後若改用 release 簽章金鑰打包，要回 Google Auth Platform「用戶端」補登 release 的 SHA-1，否則正式版登入會失敗。

## 待辦狀態

- [x] OAuth 同意畫面（外部）
- [x] Android OAuth 用戶端建立
- [x] 目標對象加入測試使用者（nplho98@gmail.com，2026-07-07 完成）
- [x] YouTube Data API v3 已啟用（2026-07-07 確認）
