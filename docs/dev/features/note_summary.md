# ノート要約

**状態:** Implemented — 稼働中。**主軸のAI機能**（毎回使う唯一の「Nano税ペイ」機能）。要約の保存（判断6〜8）と混雑時の文言（判断9）は実機未確認
**最終検証:** 2026-08-11 / `c25bcea`（判断6〜9 は未突合）
**関連コード:** `controller/SummaryController.kt` / `domain/SummarizeUseCase.kt` / `domain/SummaryCache.kt` / `data/FileSummaryCache.kt` / `ai/AiGenerationFailure.kt` / `ai/GenerationRecordingAiClient.kt` / `model/state/SummaryState.kt` / `ui/screen/AiTab.kt`（`SummaryPanel`）
**関連テスト:** `SummaryControllerTest` / `SummarizeUseCaseTest` / `FileSummaryCacheTest` / `SummaryGenerationObservationTest` / `AiGenerationFailureTest` / `NoteExcerptBuilderTest` / `PromptGenerationCoverageTest`
**正本:** この文書

**対象領域:** 開いたノートの要約を自動生成し、AIタブへ出すまで

---

## 1. 概要

ノートを開くと**自動で**要約の生成を始め、AIタブのパネルへ2〜4文で出す。
**同じ入力の要約は端末に保存してあり、開き直したときは生成し直さない。**

**アプリで唯一「毎回走る」AI機能**であり、他のAI候補はこの周回軌道に置く、と `§0 のフィルター`
（[_wip/feature_ideas.md](../../_wip/feature_ideas.md)）が決めている。

## 2. ゴールと非ゴール

### ゴール
- 開いたノートが**何について書かれているか**を、読む前に把握させる
- **待たせない** — 生成中も本文は読める。開き直したノートでは生成そのものを待たせない

### 非ゴール
- **要約のために読書を止めない。** 待機画面へ遷移しない
- **要約を作り直す導線を持たない**（→ §8 判断8）
- **ユーザーが要約を編集・保存する導線は持たない**

## 3. 詳細機能一覧

| 詳細機能 | ユーザーから見える挙動 | 起動条件 |
|---|---|---|
| 自動要約 | AIタブに2〜4文の要約が出る | **ノートを開いたとき**（Rediscover / 関連 / さがす いずれも） |
| 保存済みの要約 | **生成を待たずに要約が出る** | AIへ渡すプロンプトが、前に要約したときと同じ（→ 判断6） |
| モデルDL | 進捗（ダウンロード済/全体）を出す | Nano のモデルが未取得のとき |
| DL後の自動再開 | **完了すると自動で要約を作り直す** | ダウンロード完了時 |
| AI非対応 | 要約欄を出さない | 端末が Nano 非対応 |
| 混雑 | 「端末のAIが混み合っています。少し待ってからノートを開き直してください。」 | AICore が回数制限で要求を断ったとき（→ 判断9） |

## 4. 現在のユーザーフロー

1. ノートを開く → `fetchSummary(title, content)` が自動で走る（**ユーザー操作は不要**）
2. `requestId` を採番し、`Loading` にする
3. `AiClient.checkAvailability()` を見る
   - `Unsupported` → `AiUnavailable`（**要約欄そのものを出さない**。抜粋も作らない）
   - `NeedsDownload` → `Downloading` にしてモデルDLを開始する
   - `Ready`・DL実行中・一時的な不可 → 4へ
4. 本文から抜粋を作り（**`Dispatchers.Default`** — 最大1MBの解析でMainを塞がない）、プロンプトを組む
5. **保存済みの要約を引く。** 当たれば `Success(summary)`（**生成しない。`Ready` でなくても出す** → 判断6）
6. 当たらなければ、`Ready` のときだけ `AiClient.generate()`（`generateMutex` で直列・60秒タイムアウト）。
   それ以外（DL実行中・一時的な不可）は `AiUnavailable`。
   DL中にDLを始めないのは、走行中のDLへ合流できないため
   （→ [background_ai_ux](../system/background_ai_ux.md) §6 判断2）
7. 空でない要約を保存し、`Success(summary)` を AIタブの `SummaryPanel` へ出す

**モデルDL完了時は自動で 4 から再開する。** ただし**ノートが切り替わっていたら再開しない**（→ §8 判断3）。

**中断:** ノートを切り替えると `cancelAndClear()` が走り、`activeRequestId` が進んで
以降の結果は捨てられる。

## 5. 機能仕様

- **前提条件:** ノートが表示されていること。Vault未選択では走らない
- **入力:** ノートのタイトルと本文（**抜粋してから渡す**）
- **出力:** 2〜4文の要約
- **上限:**

  | 対象 | 値 | 定数 |
  |---|---|---|
  | プロンプトへ渡す抜粋 | **1200文字** | `NoteExcerptLimits.SUMMARY` |
  | 出力枠 | 256トークン（Nano の上限＝既定値） | `genai-prompt` |
  | 生成のタイムアウト | 60秒 | `AiClient` |
  | 保存する要約の件数 | **1000件**（最後に使った時刻が古いものから消す） | `FileSummaryCache.MAX_ENTRIES` |
  | 保存する要約1件の大きさ | 16KB（超えるものは保存も読み込みもしない） | `FileSummaryCache.MAX_ENTRY_BYTES` |

- **抜粋のしかた:** 見出し骨格＋冒頭＋末尾（→ [ai_input_excerpt](../system/ai_input_excerpt.md)）。
  **先頭固定長ではない** — 長文の後半が丸ごと落ちるのを避けるため
- **状態:** `Idle` / `Loading` / `Downloading(downloaded, total)` / `Success` / `AiUnavailable` / `Error`
- **エラー／AI非対応／キャンセル時:**
  - AI非対応 → `AiUnavailable`。**エラーとして見せない**（端末の性質であって失敗ではない）
  - DL失敗 → `Error("モデルのダウンロードに失敗しました: …")`
  - 生成が回数制限で断られた → `Error("端末のAIが混み合っています。…")`（→ 判断9）
  - それ以外の生成失敗 → `Error`（例外の文言のまま）
  - **失敗と空の要約は保存しない**
  - ノート切替 → `cancelAndClear()`。`CancellationException` は再throwする

## 6. 状態とデータ

**UI状態:** `SummaryState`（`NoteUiState.summaryState`）。**永続化しない。**

**`Downloading` の `total` は直前の状態から引き継ぐ。** `DownloadProgress` は総量を持たないため。
**照合は `setStateIfCurrent` が一手に引き受ける** — 呼び出し側に `if (!isCurrent) return` を重ねると
テストで検出できない等価な分岐が増える（→ [architecture](../system/architecture.md) 判断4）。

**契約2箇所への登録（ノート単位の状態）:**
`cancelNoteScopedJobs()` → `summary.cancelAndClear()`、
`withNoteScopedReset()` → `summaryState = SummaryState.Idle`。**両方に登録済み。**

**保存済みの要約（端末内）:**

| 項目 | 中身 |
|---|---|
| 置き場 | `noBackupFilesDir/summary_cache/`。**Vault には書かず、自動バックアップにも読書痕跡の退避にも載らない** |
| 1件 | 1ファイル。ファイル名は**完成したプロンプトのSHA-256**、中身は要約（UTF-8） |
| 最後に使った時刻 | ファイルの更新時刻。**当たったら更新時刻だけを書き換える** |
| 捨てる契機 | 上限を超えたとき（古い順）だけ。**ノート切替でもVault切替でも捨てない** |

**保存はノート単位の状態ではないので、契約2箇所には載せない。** Vault 単位でもない —
鍵が入力そのものなので、別Vaultの同じタイトル・同じ抜粋のノートが同じ要約を引いても正しい（判断6）。

## 7. システム設計

```
ノートを開く（Rediscover / openNote）
 └─ NoteSessionCoordinator.fetchSummary()
      └─ SummaryController.fetch()                  ← requestId を採番
           ├─ SummarizeUseCase.summarize()
           │    ├─ checkAvailability()
           │    ├─ buildNoteExcerpt()  @Dispatchers.Default   ← 1200字
           │    ├─ PromptBuilder.buildSummarizePrompt()
           │    ├─ SummaryCache.find(prompt)                  ← 当たれば生成しない
           │    ├─ AiClient.generate()  @generateMutex        ← Ready のときだけ・60秒
           │    └─ SummaryCache.save(prompt, summary)         ← 空でない成功だけ
           └─ startModelDownload()                  ← NeedsDownload のとき
                └─ 完了で fetch() を自動再開（isCurrent のときだけ）
```

`SummaryCache` は `domain` のインターフェースで、実装の `FileSummaryCache` は `data` にある
（`domain` は `data` を import できない → [architecture](../system/architecture.md) 判断5）。
組み立ては `NoteViewModelDependencies` の1箇所。**Debug APK では、要約に渡す `AiClient` だけを
`GenerationRecordingAiClient` で包み、生成を呼ぶたびに logcat へ1行出す**（→ §10）。

## 8. 設計判断と代替案

### 判断1: 起動は自動（明示ボタンにしない）

**主軸の機能なので、押させない。** ノートを開いた時点で走らせる。
これは他のAI機能（蒸留・クイズ・ひとこと＝いずれも明示操作）と**意図的に違う**。

代償は **Mutex の占有**で、開くたびに1本の生成が待ち行列へ入る。
開き直したノートではこの代償を払わない（→ 判断6）。

### 判断2: 待機画面へ遷移しない

**生成はAIの都合、読書はユーザーの都合。** 要約待ちで本文の閲覧をブロックしない。
詳細は [background_ai_ux](../system/background_ai_ux.md)。

### 判断3: モデルDL完了後は自動で再開する。ただし世代を照合する

要約は**自動起動の機能なので、DL完了後も自動で再開する**（蒸留は明示タップ、読書痕跡は黙って諦める
— [architecture](../system/architecture.md) の比較表がこの差を持つ）。

**ただし `isCurrent(requestId)` を必ず見る。** ここを素通りさせると、
**旧ノートの本文で要約と関連ノートが走り、新しいノートの画面へ書き戻される。**
DL完了は数分後に届きうるので、**`cancel()` だけでは足りない** — キャンセルがすり抜ける経路である。

### 判断4: 抜粋は `ai` ではなく呼び出し側で作る

依存方向が `ai → domain` を禁じているため、`PromptBuilder` から `domain.markdown` の解析器は呼べない。
**`SummarizeUseCase`（`domain`）が抜粋を完成させてから `ai` へ渡す**。
`PromptBuilder` は整形だけを担う（→ [architecture](../system/architecture.md) 判断6）。

### 判断5: AI非対応はエラーではない

`AiUnavailable` を `Error` と分けているのは、**端末の性質であって失敗ではない**から。
エラーとして見せると再試行を促すことになるが、再試行しても変わらない。

### 判断6: 同じ入力なら生成し直さない — 鍵は完成したプロンプト

同じノートを開き直すたびに Nano が数十秒走り、その間 Mutex を占有していた（判断1の代償）。

**保存済みを出しても、画面に出るものは変わらない。** 生成は決定的で、SDK の既定値は
`temperature=0`・`seed=0` である（`GenerateContentRequest.Builder.build()` の逆アセンブルで確認。
実機でも同じプロンプトからバイト単位で同じ要約が返った → [ai_quality_measurement](../system/ai_quality_measurement.md) 判断8）。
**変わるのは待ち時間と錠の占有だけ**なので、`Ready` でない状態（DL実行中・一時的な不可）でも保存済みは出してよい。

**鍵は部品の組ではなく、完成したプロンプトそのもののハッシュにする。**
分野判定（[note_field_color](note_field_color.md) 判断9）はタイトル・抜粋・予算・ヒント・語彙の版・プロンプト版を
部品として並べ、**プロンプト版の定数を人が上げる**形を取った。要約はその形を写さない。
完成したプロンプトにはタイトル・抜粋・省略の注記・予算による切り詰め・指示文がすべて載っているので、
**どれを変えても鍵が変わる。版の定数が無いので、上げ忘れが起きない。**
`SummarizeUseCaseTest` が「保存の鍵＝AIへ渡したプロンプト」の等式を固定している。

- **抜粋の外（本文の真ん中）だけを直したときは生成し直さない。** AIへ渡るものが変わっていないので正しい
- **鍵に入らないもの:** 生成設定（`AICoreClient` のリクエスト）・SDKの版・端末のモデルの版。
  変わっても保存済みの要約は**同じ入力の要約として誤りではない**ので失効させない。
  分野判定と違い、語彙のIDのように**結果の読み方そのものが変わる部品**が無いためである。
  新しいモデルの要約へ入れ替わるのは、本文かタイトルを変えたときか、古い順に消えたとき
- **非対応とモデル未取得では保存済みを引かない。** 非対応端末でノートを開くたびに抜粋を作らないため。
  未取得のときはDL完了後の再開（判断3）で引くので、DLの契機を変えない

**効果の検算:** 冊子から「これを読む」で開いて戻り、同じノートをもう一度開く。
これまでは2回とも生成し、2回目も数十秒錠を持っていた。**2回目は生成0回**になり、
その間にユーザーが押した操作は要約の後ろで待たなくなる。

### 判断7: 置き場は `noBackupFilesDir`、1件1ファイル、上限1000件

**設定の prefs に相乗りしない。** 分野判定は短いIDなので `random_note_prefs` に置いたが、
要約は本文から作った数百字の文章である。prefs は開いた時点で全件をメモリへ読み、
書くたびにファイル全体を書き直すので、**テーマを切り替えるたびに要約の束まで書き直す**ことになる。

**`noBackupFilesDir` に置く。** 本文から作った文章なので端末の外へ出さない（→ [ADR-0002](../decisions/ADR-0002-on-device-ai-only.md)）。
`filesDir` に置いて除外規則で守ると、**規則を1つ書き忘れた瞬間に漏れ、しかも何も起きない**。
守りを規則ではなく置き場の性質に持たせる（`BackupExclusionTest` が「性質として除外」と数える置き場）。

**1件1ファイルにする。** 1ファイルにまとめると、生成のたびに全件を書き直す。
1件ずつなら書くのは今の1件だけで、壊れても失うのはその1件だけである。
書き込みは一時ファイルから置き換えるので、**書きかけのファイルを要約として読まない**。

**上限は1000件、最後に使った時刻が古いものから消す。**
分野判定の「上限を超えたら新しい確定を保存しない」は写せない。あちらは鍵がパスなので件数がノート数で頭打ちになるが、
こちらは**鍵が入力なので、本文を直すたびに使われない鍵が残る**。保存しない方式だと、上限に届いた時点で二度と効かなくなる。
1000件は、オーナーのVault（645本、[note_field_color](note_field_color.md) 判断4 の実測）を全部1回ずつ開いても溢れない数で、
上限いっぱいでも数MBに収まる。

**最後に使った時刻はファイルの更新時刻で持つ。** 当たるたびに中身を書き直さずに済む。
更新時刻を書き換えられない保存領域では、古い順が「書いた順」へ劣化するだけで壊れはしない。

### 判断8: 要約を作り直す導線は持たない（2026-09-17、オーナー判断）

保存を入れると「開き直すと作り直される」が無くなる。**それで失うものは無い** —
生成は決定的なので、開き直しても同じ要約しか返っていなかった（判断6）。

要約は読む前の見取り図であって、引き直して良いものを選ぶ作品ではない。
「作り直す」ボタンは、AIを相手役に留める北極星に対して操作を1つ増やすだけになる。
**本文かタイトルを直せば鍵が変わり、要約も作り直される。**

### 判断9: AICore に回数制限で断られたら、英文を出さず開き直しを促す（2026-09-17、オーナー判断）

AICore は短い時間窓の回数で要求を断る（→ [background_ai_ux](../system/background_ai_ux.md) §6）。
要約は生成の例外文をそのまま出していたので、断られると SDK の英文が要約欄に出た。

**黙らせず、日本語の案内に差し替える。** 黙って `AiUnavailable` にすると要約欄ごと消え、
次に何をすればよいかが残らない。断られたときモデルは動いておらず、**少し待てば同じ要求が通りうる**ので、
「少し待ってからノートを開き直してください」と出す。**失敗は保存しないので、開き直せば生成し直す。**

- **判定は `GenAiException.getErrorCode()` が `BUSY`（9）のときだけ。** SDK は内部コード28も
  `getErrorCode()` で9へ読み替えて返す（genai-common 1.0.0-beta3 の逆アセンブルで確認。
  `AiGenerationFailureTest` が本物の例外で固定している）
- **長期の利用枠の超過（27）は含めない。** 少し待っても通らないので、同じ案内は誤りになる
- **それ以外の失敗の文言は変えていない**（→ §11）

## 9. 品質要件

- **性能:** 抜粋の生成は `Dispatchers.Default`。**最大1MBの本文解析はMainで走らせない**
  （→ [architecture](../system/architecture.md) 判断3・`NoteExcerptThreadingTest` がソース走査で固定）。
  保存の読み書きは `Dispatchers.IO`
- **プライバシー:** 本文はプロンプトへ入るが端末外へ出ない（→ [ADR-0002](../decisions/ADR-0002-on-device-ai-only.md)）。
  保存する要約も `noBackupFilesDir` に置き、自動バックアップへ載せない（判断7）
- **端末制約:** Nano 非対応端末では機能ごと出さない

## 10. 検証と受け入れ条件

- **JVMテスト:** `SummaryControllerTest`（状態遷移・世代照合・DL再開）/
  `SummarizeUseCaseTest`（保存の鍵・保存してよい結果・引いてよい状態・混雑時の文言）/
  `FileSummaryCacheTest`（1件1ファイル・古い順の削除・大きさの上限・書きかけの片付け・置き場を作れないとき）/
  `SummaryGenerationObservationTest`（生成の記録が、保存済みの当たりと同じ入力の再生成を区別できること）/
  `AiGenerationFailureTest`（回数制限の判定）/ `NoteExcerptBuilderTest`（抜粋）/
  `NoteExcerptThreadingTest`（Main外で解析することをソース走査で固定）/
  `SummaryCoverageTest`・`SummaryCoverageCalibrationTest`（出力を採点する物差しと、その閾値）
- **instrumentation:** `OnDeviceGenerationTest`（実端末での生成）/ `PromptTokenBudgetTest`（トークン余裕）
- **実機:** [要約の実機検証ケース](../../review/device_validation/note_summary.md)。
  **当たったかは保存のファイルではなく、要約の生成の記録の行数で判定する。**
  同じプロンプトで生成し直しても同じ名前のファイルが置き換わって更新時刻が進むので、
  ファイルを見る判定では**毎回生成する回帰を合格にしてしまう。** そこで Debug APK だけ、
  要約に渡す `AiClient` を `GenerationRecordingAiClient` で包み、生成を呼ぶたびに
  `VigilithSummaryGen: generate` を1行出す（本文もタイトルも載せない）。
  - **要約に渡すクライアントだけを包む。** 全機能で共有する `AiClient` を包むと、分野判定などの生成まで数え込む
  - **呼んだ時点で数える。** 失敗した生成も1行になるので、生成の途中で切り替えた試行も数えられる
  - **0行は、同じ回に初めて開いたノートの1行と組にしてだけ合格にする。** 記録が出ていないことと当たったことを区別するため
  - **再起動の試行は、全ノートを保存済みにしてから強制停止し、起動の前から数える。** 起動直後に出たノートの要約の後から数えると、
    失われた保存をその生成が補った後になり、開き直したノートが当たって0行で合格してしまう。
    全ノートを保存済みにしてあるので、起動から数えた行は**どのノートのものでも**保存が再起動をまたがなかった証拠として不合格にする
  - 区別できることは `SummaryGenerationObservationTest` が、**保存を書くが引かない経路**（毎回生成する回帰の代わり）と並べて固定する。
    その経路でもファイル名・更新時刻・出る要約は当たりの経路と同じになる。
    再起動の数え方も同じテストが、保存を失わせた対照と、起動直後の要約の後から数え始めた場合の見逃しと並べて固定する
- **保証していないこと:**
  - **品質の正しさは保証していない。** [ai_quality_measurement](../system/ai_quality_measurement.md) に
    全27組を分割取得した探索用の基準線がある。語彙指標では正誤を分離できず、
    計測の完走を要約の正しさや方式の優劣の保証とはしない
  - **語の重なりで測る以上、意味は見ていない。** 言い換えた正しい要約は低く出る
  - **JVMの生成回数を、実機で生成しなかった証拠にはしない。** 実機の判定は上の記録の行数で行う
  - **モデルが変わっても保存済みの要約を出し続ける。** 同じ入力で同じ出力になるのは同じモデルのあいだだけで、
    端末のモデルが更新されても、本文かタイトルを変えるか古い順に消えるまで前のモデルの要約が出る（判断6）
  - 抜粋で切り落とされた区間の内容は要約に現れない

## 11. 既知の制約・未解決事項

| | |
|---|---|
| 優先度が無い | 自動生成が先に入ると、ユーザーが押した操作が後ろで待つ。**開き直したノートでは生成しないので、待つのは初めて開いたノートだけになった** |
| 回数制限以外の生成失敗は例外の文言のまま | SDK の英文が出うる。自動機能は例外本文を出さない方針（[background_ai_ux](../system/background_ai_ux.md) §6）とずれている |
| 品質の正しさを判定できない | [探索用の実機基準線](../system/ai_quality_measurement.md) は取得済みだが、語彙指標だけでは正誤や方式の優劣を判定できない |

## 12. 開発経緯

[開発日誌 2026-07](../../owner/journal/2026-07.md)・[2026-08](../../owner/journal/2026-08.md)
