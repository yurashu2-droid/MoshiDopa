# もしドパ / Black × Lime prototype

2026-09-07

## Direction

黒い計器、蛍光色の操作、白い給与明細。発光やグラフを増やさず、金額を主役にする。
Material 3 Expressiveの文字サイズ・形・選択状態の明確さを既存Android Viewsへ適用。
公式のExpressive Composeコンポーネントへの移行ではない。
apple-designスキルを使い、タッチダウン時のリップル、読みやすい不透明面、指に追従する既存ドラッグを採用した。

## Implemented

- 共通色 `ui/Brand.kt`。黒 #11120F、チャコール #24261F、ライム #D6FF3F。
- アプリ表示名「もしドパ」。applicationId、DB名、既存記録は変更しない。
- 初回は6段階：見本、単価、使用状況、対象アプリ、オーバーレイ、開始。
- 権限を起動直後に要求しない。各ページで理由を説明してからOS設定へ遷移。
- 未許可では次へ進まず、戻った際に実際の許可状態を再取得。途中状態と入力ドラフトは保存。
- 手動利用へのスキップを維持。既存ユーザーは設定から初回案内を再表示可能。
- 新規利用の初期単価は1226円。2026年9月7日時点の東京都最低賃金である旨を注記。
- 入力変更・月給選択時には最低賃金の注記を一般の換算説明に切り替える。
- 既存の単価を上書きせず、過去の記録も再計算しない。
- 金額は太字・等幅数字、小数部64%。数値更新は従来の100ms、実時間差計算を維持。
- オーバーレイは黒い不透明面・白い数字・ライムのLIVE。衝突時の赤は黒面でも見える濃度へ調整。
- 結果は黒い外枠＋白い明細。画像にもアプリ名・説明・取消線・免責を含める。
- 共有文にアプリ名とハッシュタグ。未公開のURLやQRコードは作らない。
- 既存の金額閾値・キュー・物理演算には変更しない。

## Verification

JDK: Android Studio bundled jbr

`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`

- 単体テスト22件成功。
- lint 0 errors / 47 warnings（プログラム生成UIの文字列など。警告ゼロとはしない）。
- Android 15専用AVDで4件成功：onboarding permission gates/resume、manual setup/stop/share、actual collision tint during updates、auto overlay/screen-off。
- 動作中の別AVDでは衝突描画テスト1件が失敗。専用AVDで成功。実機のフレーム負荷は未検証。
- `docs/screenshots/moshidopa-final/` に初回各ページ・値札・明細・共有画像。

## Before release

- 最低賃金は日付付きの固定初期値。リリース時に再確認・更新する。現時点でオンライン更新はない。
- 公開URL決定後に共有リンク・必要ならQRを追加する。
- 大きいシステムフォント、各社実機、横画面の追加確認。
- アイコンは既存のまま。今回の範囲は画面・共有画像のブランド変更。
- オンボーディング/共有の評価は実ユーザーではまだ行っていない。
