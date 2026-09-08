# TIME COST — Android MVP

最新の追加仕様: [アプリ離脱時の給与明細・品物の節目演出](docs/payslip-feedback.md)。以下の初期MVP記述と異なる場合は、この追補を優先してください。

自分の時間に、リアルタイムで値札をつける。Android Studioでこのフォルダーを開き、`app`をRunしてください。

## 最初に試すこと

1. 初回のセットアップダッシュボードで、時給または月給と月間労働時間を入力。
2. 同じ画面の「対象アプリを選ぶ」でYouTubeなどを選択。
3. 同じ画面から「使用状況へのアクセス」「他のアプリの上に表示」を順に許可。通知も許可すると停止操作がしやすくなります。設定画面から戻ると状態が更新されます。
4. 「保存して TIME COST をはじめる」を押してホームへ進み、「START AUTO」。対象アプリを開くと、今日の累計金額が表示されます。
5. 値札はドラッグで移動、タップでTIME COSTへ。通知またはアプリのSTOPで終了。
6. 履歴の行をタップするとレシート画像を共有できます。

スマホ外の活動はホーム下部のSPEND / INVESTを選び、活動名を入力してSTART。STOPすると結果を表示します。自動と手動は同時に起動できず、二重計測を防ぎます。

## 今回の構成

- Kotlin / Android標準View。既存の非Composeひな形を維持し、大規模な依存更新は行っていません。
- minSdk 28。compileSdk 36.1 / targetSdk 36 / AGP 9.0.1は既存設定。
- `domain/Cost.kt`: 給与換算、経過時間からの金額算出、表示形式。
- `domain/DaySegments.kt`: 自動記録のローカル日付分割。
- `tracking/TrackingService.kt`: 明示的に開始するForeground Service、UsageEvents取得、単一の計測状態。
- `overlay/PriceTag.kt`: TYPE_APPLICATION_OVERLAY、ドラッグ移動、位置保存。
- `data/Store.kt`: SQLiteによる記録保存、SharedPreferencesによる設定保存。
- `MainActivity.kt`: 初期設定、ホーム、計測、結果、履歴、設定。
- `share/ReceiptRenderer.kt`: 1080×1350 PNGとFileProviderによるAndroid共有シート。

## 計測の仕様

- 金額は`経過ミリ秒 ÷ 3,600,000 × 計測開始時の時給`。100msごとの足し算ではありません。
- 継続中の時間は`SystemClock.elapsedRealtime()`を基準に算出。表示は約100ms、アプリイベント確認は約1秒。
- UsageEventsの時刻を使って切り替わりを確定。短いイベント配信遅延に備えて3秒分を重複取得し、同じイベントは再処理しません。
- 自動は選択アプリのみSPENDとして記録。対象外アプリ・ホーム・画面OFF・ロックでは計測しません。バックグラウンド音声も対象外。
- 自動の値札はメインに今回のセッション金額、その下にアプリ別の「今日のトータル」を表示します。再訪すると新しいセッションとして計測し、今日のトータルは維持します。日付は端末のタイムゾーンに従います。
- 自動計測の対象アプリを閉じると、通知とTIME COSTの結果画面に今回の時間・金額と比較データを表示します。ライブ値札では、今回の金額が10〜90円なら10円ごと、100〜900円なら100円ごと、1000円以降なら200円ごとに、駄菓子・チョコレート・コーヒーを表示します。落下中に次の金額へ到達した場合は、落下完了後に順番に表示します。これは経過時間の演出で、購入個数や終了明細とは別です。結果画面にはMaterial Design IconsのコーヒーSVG（Apache-2.0）を使用しています。
- 手動は画面OFFでも経過時間を含めます。サービスが終了されるまで計測。手動記録は一つの活動として保持します。
- 計測中の単価・対象アプリ変更は禁止。過去の記録は当時の時給を保持。
- 約5秒ごとに途中保存。プロセス強制終了・再起動後は最後に保存した時点までを履歴に残し、自動再開しません。通常の画面ON時は直近約5秒、Doze等で実行が止まった場合はそれ以上の未保存部分が失われる可能性があります。未計測の停止期間を後から加算しません。
- 通信権限なし。閲覧内容・入力内容は読まず、端末内で処理。給与・利用履歴はバックアップ対象から除外。
- 共有画像はキャッシュに生成。共有先を選ぶ操作はユーザーが行います。時間を隠す選択肢あり。時間と金額を公開すると単価を逆算できる点を表示。

## 検証

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Android Studio付属のJDK 21を利用。APKは`app/build/outputs/apk/debug/app-debug.apk`。
UIテストは専用エミュレーター向けで、テスト用にアプリの設定を変更し、Usage AccessとOverlayのAppOpsを許可します。個人データのある実機では実行しないでください。自動計測テストはGoogle Clock (`com.google.android.deskclock`) のあるAndroid 15 AVDを使用します。

テスト範囲: 時給/月給換算、0.1秒単価、無効入力、負の経過時間、長時間表示、日付またぎと夏時間、初期設定→手動計測→結果→画像共有、自動の別アプリ検出・値札ウィンドウ・累計維持・画面OFF停止。

2026-09-05検証結果: デバッグAPK生成成功、独自単体テスト10件成功（ほかにひな形の1件）、Android 15 AVDの主要操作テスト2件成功。Lintは0 errors / 17 warnings（既存依存の更新案、KTX利用推奨等）。実際の画面は`docs/screenshots/`に保存しています。値札はOSのウィンドウ存在だけでなくスクリーンショットでも確認しました。

## まだ保証しないこと / 次の実機検証

- YouTube Shorts単体の判別は未対応。YouTube全体の使用時間です。
- OS設定やセキュリティ保護されたアプリは重ねて表示を禁止する場合があります。制限を回避しません。
- 分割画面・PiP・他の通知パネル・アプリ内Activity遷移はOSイベントの性質で区切りや誤差が生じます。通常の単一アプリ全画面で試してください。
- 3秒を超えるイベント配信遅延、計測中の端末時刻・タイムゾーンの手動変更は未検証。
- バッテリー消費、長時間Doze、各メーカーのサービス終了制限、再起動、API28実機、大きな文字設定・TalkBackは追加検証が必要。
- 履歴一覧は新しい1000区間まで。日次集約表示、記録の修正・削除、一時停止、アプリ別INVEST分類は未実装。
- 手動レシートと自動の区間レシートを共有可能。1日分の複数活動をまとめたレシートは今後。
- Play公開には`specialUse` Foreground Serviceの用途申告・審査、プライバシーポリシー、署名、正式なapplicationIdなどが必要。公開可能と確認済みの状態ではありません。

参考: [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)、[Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)。
