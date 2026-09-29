# 部分要約（セクションの要約シート）

**状態:** Implemented — 稼働中。**質問候補・回答とクイズは撤去した（2026-09-30、→ §8 判断3）**。
余白メモのシートと1枚へまとめる予定は [この部分](margin_pane.md) が持つ
**最終検証:** 2026-08-11 / `c25bcea`（質問とクイズの撤去は、撤去のコミットの実装に合わせて書いた）
**関連コード:** `controller/SectionChatController.kt` / `ui/screen/SectionChatSheet.kt` / `controller/NoteSectionController.kt` / `domain/markdown/NoteSections.kt`
**関連テスト:** `SectionChatControllerTest` / `VigilithStatusDerivationTest` / `NoteSectionThreadingTest`
**正本:** この文書

**対象領域:** ノート読書中に、**いま読んでいる節**の要約を出す

---

## 1. 概要

読んでいる位置の節を対象に、ボトムシートで**その節の要約**を出す。
通常画面の入口はマスコットのタップ、全画面の入口は 💬 の最小AIインジケータである。

> クラス名の `SectionChat…` は、質問と回答を持っていた頃の名残である。
> 改名は影響が広いので、撤去とは分けて扱う。

## 2. ゴールと非ゴール

### ゴール
- 長いノートでも**入力をコンテキスト長に収める**
- 要約の根拠を「今見ている部分」に絞る
- **読書を止めずに**節の要点を確かめられる

### 非ゴール
- **質問に答えない。** 候補の質問も自由記述の質問も持たない（→ §8 判断3）
- **設問を作らない。** クイズは撤去した（→ §8 判断3）
- **要約を永続化しない**（シートを閉じても同じノートの間は残るが、端末には残さない）
- **ノート横断にしない**

## 3. 詳細機能一覧

| 詳細機能 | ユーザーから見える挙動 | 起動条件 |
|---|---|---|
| 節の追従 | スクロールすると対象の節が変わる | 常時 |
| 節の要約 | シートに今の節の要約が出る | 入口を押したとき |
| 再試行 | 出せなかった理由の隣から作り直せる | 要約が出せなかったとき |
| 再表示 | 閉じても同じノートの間は結果が残り、入口から再び開ける | セッションがあるとき |

## 4. 現在のユーザーフロー

1. 本文をスクロールすると、`firstVisibleItemIndex` から**直近の見出し**が対象の節になる
   - **見出しが無いノートはノート全体へフォールバック**する
2. 入口を押す → `open(section)`
   - **既にセッションがあれば、それを再表示するだけ**（→ §8 判断2）
3. `checkAvailability()` を見る
   - `Ready` → 4へ
   - それ以外 → `aiStatusNotice()` の説明を `summaryProblem` へ載せる。
     **文言も導線も `AiStatusNotice` が持つ**（message だけ取り出すと再試行できず、
     赤いエラー表示になる）。再試行は `retrySummary()` が受ける。**ここではDLしない** → §8 判断4
4. 節の要約を生成する
5. シートを閉じる → 要約は残るが、**セッションを明示終了すると破棄される**

## 5. 機能仕様

- **前提条件:** ノートが表示されていること
- **対象の節:** 直近の見出しから**次の同レベル以下の見出しの直前まで**
- **上限:**

  | 対象 | 値 | 定数 |
  |---|---|---|
  | プロンプトへ渡す節の本文 | **1500文字** | `NoteExcerptLimits.SECTION` |
  | 出力枠 | 256トークン | `genai-prompt` |

<!-- state-fields: SectionChatState -->
- **状態 `SectionChatState`:** `sectionTitle` / `sectionContext`（**LLMへ渡すだけで表示しない**）/
  `summary` / `isSummaryLoading` / `summaryProblem`
<!-- /state-fields -->
- **`GenerationFailed(message)`:** 生成が落ちた（タイムアウト・出力打ち切り）。赤で出す
- **`AiStatus(notice)`:** 端末AIが使えない。通常色で出す
  （→ [background_ai_ux](../system/background_ai_ux.md) §6）
- **再試行:** `retrySummary()` が開いているセクションのまま作り直す。要約が既にあるなら説明を畳むだけ
- **派生状態:** `sectionChatStatus` が `Idle` / `Working` / `Ready` / `Error` を導く。
  通常画面と全画面の 💬 が同じ導出を使う。**端末AIが使えないだけなら `Working` にも `Error` にもしない**
- **キャンセル:** ノート・Vault切替、セッションの開始・終了で `cancelAndClear()`

## 6. 状態とデータ

**UI状態:** `NoteUiState.sectionChat`（`null` ならセッション無し）＋ `isSectionChatSheetVisible`。
**シートの表示状態とセッションの存在を分けている** — 閉じても要約は残る。

**永続化しない。**

**契約2箇所への登録（ノート単位の状態）:**
`cancelNoteScopedJobs()` → `sectionChat.cancelAndClear()`、
`withNoteScopedReset()` → `sectionChat = null` / `isSectionChatSheetVisible = false`。**両方に登録済み。**

**セクション構造の解析は別 Controller が持つ。** `NoteSectionController` が
`Dispatchers.Default` で解析して `StateFlow<NoteSectionModel?>` を配る
（→ [architecture](../system/architecture.md)。**最大1MBの解析はMainで走らせない**）。

## 7. システム設計

```
本文（LazyColumn）── firstVisibleItemIndex ──> 対象の節の決定
 └─ 入口（通常画面はマスコット、全画面は 💬）
      └─ SectionChatSheet
           └─ SectionChatController.open()   ← セッション作成・節の要約

NoteSectionController（Dispatchers.Default）── NoteSectionModel ──> 上記
```

## 8. 設計判断と代替案

### 判断1: なぜ「節単位」なのか

長いノート全体を Nano に渡すと**コンテキスト長制限で後半が切り捨てられ**、要約の的確さも落ちる。
「今見ている部分だけ」を対象にすれば、長いノートでも入力が収まり、**要約の根拠も絞られる。**

**対象はユーザーに選ばせず、スクロール位置から自動追従する。** 選ばせると手数が増え、
「読んでいる場所を確かめる」という動機と噛み合わない。

### 判断2: セッションは開始時の節に固定する

既にセッションがあれば `open()` は**再表示するだけ**で、対象を作り直さない。
**スクロール先の別の節で重複生成しないため。** 固定しないと、
シートを閉じて少しスクロールして開き直すたびに Nano が走る。
複数の節の要約を持つ形は [この部分](margin_pane.md) §5.8 が設計している。

### 判断3: 質問とクイズを撤去した（オーナー判断・2026-09-28）

質問候補から回答を作る2段の生成と、形式を守った複数項目の生成（クイズ）は、
出力256トークン・1回数十秒の中で、**待ったうえで読んで得をする水準に届かなかった**（オーナーの体感）。
どちらも本文から選ぶのではない自由生成で、**結果はシートを閉じるかノートを替えると消える** —
「次の再会の材料として残るか」に答えない。**部分要約だけを残した。**
クイズの設計の記録は [quiz](quiz.md) に残してある。

### 判断4: ここではモデルDLを始めない

`NeedsDownload` のときは**文言で案内するだけ**で、ダウンロードを開始しない（自動の要約は始める）。

**したがって `AiNoticeAction.Download` を運ばない。** 共通変換へ
`canStartDownload = false` を渡し、「Gemini Nanoの準備ができると〜を使えます。」という
**待てば使えることだけを言う文**にする。運ぶと終端UIがコールバックを渡さず、
**ボタンが描かれないまま「開始してください」だけが残る** — 開始する操作が存在しない案内になる。

**読書中に開くシートなので、数分かかる処理をここから起こさない。**
DLの起点は自動生成される要約側に寄せてある（→ [architecture](../system/architecture.md) の比較表）。

### 撤退した試み: UIの初期案（6版の反復）

浮遊UIは**6版の反復**を経た。個々の版は [開発日誌](../../owner/journal/2026-07.md) が持つ。
残す結論は1つ。

> **浮遊UIは「本文と区別できる色」と「タップまでの手数最小」が生死を分ける。**
> 白基調はノートと同化し、タップ後の中間メニューは邪魔になる。

## 9. 品質要件

- **性能:** セクション解析は `Dispatchers.Default`（`NoteSectionThreadingTest` がソース走査で固定）
- **プライバシー:** 節の本文はプロンプトへ入るが端末外へ出ない
  （→ [ADR-0002](../decisions/ADR-0002-on-device-ai-only.md)）
- **端末制約:** Nano 非対応・モデル未準備は**シート内の文言**で伝える

## 10. 検証と受け入れ条件

- **JVMテスト:** `SectionChatControllerTest`（セッションの寿命・状態遷移・端末AIが使えないときの説明）/
  `VigilithStatusDerivationTest`（派生状態）/ `NoteSectionThreadingTest`
- **instrumentation:** `OnDeviceGenerationTest`（節の要約の実生成）
- **保証していないこと:**
  - **節の追従の精度を測っていない。** `firstVisibleItemIndex` 基準なので、
    画面上端の見出しが対象になる（読んでいる位置とずれうる）
  - **要約の質を測っていない**

## 11. 既知の制約・未解決事項

| | |
|---|---|
| 要約が残らない | ノートを替えると消える。数節分を持つ形は [この部分](margin_pane.md) が設計している |
| 対象の節の判定が画面上端基準 | 上記「保証していないこと」参照 |

## 12. 開発経緯

[開発日誌 2026-07](../../owner/journal/2026-07.md)
