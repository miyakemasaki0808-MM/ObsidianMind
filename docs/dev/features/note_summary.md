# ノート要約

**状態:** Implemented — 稼働中。**主軸のAI機能**（毎回使う唯一の「Nano税ペイ」機能）
**最終検証:** 2026-08-11 / `c25bcea`（状態遷移・抜粋上限・モデルDLの自動再開を実装から起こした）
**関連コード:** `controller/SummaryController.kt` / `domain/SummarizeUseCase.kt` / `model/state/SummaryState.kt` / `ui/screen/AiTab.kt`（`SummaryPanel`）
**関連テスト:** `SummaryControllerTest` / `NoteExcerptBuilderTest` / `PromptGenerationCoverageTest`
**正本:** この文書

**対象領域:** 開いたノートの要約を自動生成し、AIタブへ出すまで

---

## 1. 概要

ノートを開くと**自動で**要約の生成を始め、AIタブのパネルへ2〜4文で出す。

**アプリで唯一「毎回走る」AI機能**であり、他のAI候補はこの周回軌道に置く、と `§0 のフィルター`
（[_wip/feature_ideas.md](../../_wip/feature_ideas.md)）が決めている。

## 2. ゴールと非ゴール

### ゴール
- 開いたノートが**何について書かれているか**を、読む前に把握させる
- **待たせない** — 生成中も本文は読める

### 非ゴール
- **要約のために読書を止めない。** 待機画面へ遷移しない
- **キャッシュしない**（現状）。同じノートを開き直すたび再生成する → §11
- **ユーザーが要約を編集・保存する導線は持たない**

## 3. 詳細機能一覧

| 詳細機能 | ユーザーから見える挙動 | 起動条件 |
|---|---|---|
| 自動要約 | AIタブに2〜4文の要約が出る | **ノートを開いたとき**（Rediscover / 関連 / さがす いずれも） |
| モデルDL | 進捗（ダウンロード済/全体）を出す | Nano のモデルが未取得のとき |
| DL後の自動再開 | **完了すると自動で要約を作り直す** | ダウンロード完了時 |
| AI非対応 | 要約欄を出さない | 端末が Nano 非対応 |

## 4. 現在のユーザーフロー

1. ノートを開く → `fetchSummary(title, content)` が自動で走る（**ユーザー操作は不要**）
2. `requestId` を採番し、`Loading` にする
3. `AiClient.checkAvailability()` を見る
   - `Ready` → 4へ
   - `NeedsDownload` → `Downloading` にしてモデルDLを開始する
   - それ以外（非対応・一時的な不可・**DL実行中**）→ `AiUnavailable`（**要約欄そのものを出さない**）。
     DL中にDLを始めないのは、走行中のDLへ合流できないため
     （→ [background_ai_ux](../system/background_ai_ux.md) §6 判断2）
4. 本文から抜粋を作る（**`Dispatchers.Default`** — 最大1MBの解析でMainを塞がない）
5. プロンプトを組み、`AiClient.generate()`（`generateMutex` で直列・60秒タイムアウト）
6. `Success(summary)` を AIタブの `SummaryPanel` へ出す

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

- **抜粋のしかた:** 見出し骨格＋冒頭＋末尾（→ [ai_input_excerpt](../system/ai_input_excerpt.md)）。
  **先頭固定長ではない** — 長文の後半が丸ごと落ちるのを避けるため
- **状態:** `Idle` / `Loading` / `Downloading(downloaded, total)` / `Success` / `AiUnavailable` / `Error`
- **エラー／AI非対応／キャンセル時:**
  - AI非対応 → `AiUnavailable`。**エラーとして見せない**（端末の性質であって失敗ではない）
  - DL失敗 → `Error("モデルのダウンロードに失敗しました: …")`
  - 生成失敗 → `Error`
  - ノート切替 → `cancelAndClear()`。`CancellationException` は再throwする

## 6. 状態とデータ

**UI状態:** `SummaryState`（`NoteUiState.summaryState`）。**永続化しない。**

**`Downloading` の `total` は直前の状態から引き継ぐ。** `DownloadProgress` は総量を持たないため。
**照合は `setStateIfCurrent` が一手に引き受ける** — 呼び出し側に `if (!isCurrent) return` を重ねると
テストで検出できない等価な分岐が増える（→ [architecture](../system/architecture.md) 判断4）。

**契約2箇所への登録（ノート単位の状態）:**
`cancelNoteScopedJobs()` → `summary.cancelAndClear()`、
`withNoteScopedReset()` → `summaryState = SummaryState.Idle`。**両方に登録済み。**

## 7. システム設計

```
ノートを開く（Rediscover / openNote）
 └─ NoteSessionCoordinator.fetchSummary()
      └─ SummaryController.fetch()                  ← requestId を採番
           ├─ SummarizeUseCase.summarize()
           │    ├─ checkAvailability()
           │    ├─ buildNoteExcerpt()  @Dispatchers.Default   ← 1200字
           │    ├─ PromptBuilder.buildSummarizePrompt()
           │    └─ AiClient.generate()  @generateMutex        ← 60秒
           └─ startModelDownload()                  ← NeedsDownload のとき
                └─ 完了で fetch() を自動再開（isCurrent のときだけ）
```

## 8. 設計判断と代替案

### 判断1: 起動は自動（明示ボタンにしない）

**主軸の機能なので、押させない。** ノートを開いた時点で走らせる。
これは他のAI機能（蒸留・クイズ・ひとこと＝いずれも明示操作）と**意図的に違う**。

代償は **Mutex の占有**で、開くたびに1本の生成が待ち行列へ入る。
入力指紋キャッシュが入れば同じノートの再生成が消えるので、この代償は下がる（→ §11）。

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

## 9. 品質要件

- **性能:** 抜粋の生成は `Dispatchers.Default`。**最大1MBの本文解析はMainで走らせない**
  （→ [architecture](../system/architecture.md) 判断3・`NoteExcerptThreadingTest` がソース走査で固定）
- **プライバシー:** 本文はプロンプトへ入るが端末外へ出ない（→ [ADR-0002](../decisions/ADR-0002-on-device-ai-only.md)）
- **端末制約:** Nano 非対応端末では機能ごと出さない

## 10. 検証と受け入れ条件

- **JVMテスト:** `SummaryControllerTest`（状態遷移・世代照合・DL再開）/ `NoteExcerptBuilderTest`（抜粋）/
  `NoteExcerptThreadingTest`（Main外で解析することをソース走査で固定）
- **instrumentation:** `OnDeviceGenerationTest`（実端末での生成）/ `PromptTokenBudgetTest`（トークン余裕）
- **保証していないこと:**
  - **要約の品質を機械的に測っていない。** 原文の何を落としたかを見る道具が無い
    （→ [_wip/feature_ideas.md](../../_wip/feature_ideas.md) のカバレッジ検査の候補）
  - **同じ入力で同じ出力にならない。** Nano の出力は揺らぐ
  - 抜粋で切り落とされた区間の内容は要約に現れない

## 11. 既知の制約・未解決事項

| | |
|---|---|
| **キャッシュが無い** | 同じノートを開き直すたび Nano が数十秒走り、その間 Mutex を占有する。入力指紋キャッシュの候補が [_wip/feature_ideas.md](../../_wip/feature_ideas.md) にある |
| 優先度が無い | 自動生成が先に入ると、ユーザーが押した操作が後ろで待つ |
| 品質の測定手段が無い | 上記「保証していないこと」参照 |

## 12. 開発経緯

[開発日誌 2026-07](../../owner/journal/2026-07.md)・[2026-08](../../owner/journal/2026-08.md)
