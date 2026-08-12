# クイズ（Q&A）

**状態:** Implemented — 稼働中。**未確認管理（`isViewed`）を持つ唯一の機能**
**最終検証:** 2026-08-11 / `c25bcea`（入口・通知先・`isViewed` の消費先を実装から起こし直した）
**関連コード:** `controller/QuizController.kt` / `domain/QuizInputProfile.kt` / `domain/QuizResponseParser.kt` / `model/state/QuizState.kt` / `ui/screen/QuizScreen.kt` / `ui/screen/SectionChatSheet.kt`（**入口**）/ `ui/vigilith/VigilithMode.kt`
**関連テスト:** `QuizControllerTest` / `QuizResponseParserTest` / `QuizInputProfileTest` / `QuizPromptBuilderTest` / `VigilithStatusDerivationTest`
**正本:** この文書

**対象領域:** 読んだノートから設問を作り、専用画面で解かせるまで

---

## 1. 概要

開いているノートから**選択式の設問を生成**し、専用画面（非タブルート `quiz`）で解く。
読んだ内容を「思い出せるか」で確かめる、Reflect 系の中で唯一**能動的に試す**機能。

## 2. ゴールと非ゴール

### ゴール
- 読んだ直後に**思い出す機会**を作る
- **ノートの中身に合った出題形式**を選ぶ（コード主体のノートに4択を出さない）

### 非ゴール
- **成績を記録しない。** 正答率の蓄積・間隔反復は持たない（→ §11）
- **自動生成しない。** 明示ボタンでのみ走る
- **設問を永続化しない。** ノートを切り替えると消える

## 3. 詳細機能一覧

| 詳細機能 | ユーザーから見える挙動 | 起動条件 |
|---|---|---|
| 設問生成 | AIが複数の設問を作る | **吹き出しシートの「📝 この部分でクイズ」**（AIタブに入口は無い） |
| 出題形式の自動選択 | ○×／3択／4択のいずれかになる | 生成時（**AIではなく本文の構造で決める**） |
| 解答画面 | 専用ルート `quiz` で解く | Snackbar の「見る」／シートの「✓ クイズを開く」 |
| 未確認の表現 | **シートのラベル**が `✓ クイズを開く` `! エラーを確認` になり、**Vigilith が反応する** | 結果を**まだ開いていない**あいだ |
| モデルDL | 進捗を出し、完了後に**自動で再開** | Nano のモデルが未取得のとき |

## 4. 現在のユーザーフロー

1. 吹き出しシートの **「📝 この部分でクイズ」** を押す → `QuizController.create(title, content)`
   - **対象は「いま読んでいるセクションの周辺」**（→ [section_ai_chat](section_ai_chat.md)）
   - **生成中の再タップは無視する**（同じ要求として扱い、Mutex の順番待ちを重複させない）
2. **本文の構造から出題形式を決める**（AIを使わない → §8 判断1）
3. `requestId` を採番して `Loading(sourceTitle, format)` にする
4. `checkAvailability()` を見る
   - `Ready` → 5へ
   - `NeedsDownload` → モデルDLを開始し、完了後に**自動で再開**
   - `Downloading` → **DLは始めない**（走行中のDLへ合流できない）。説明を出して待たせる
   - `Unsupported` / `TemporarilyUnavailable` → `QuizState.AiNotice`。
     **`Error` へ畳まない** — 非対応に再試行導線が付くのを避ける
     （→ [background_ai_ux](../system/background_ai_ux.md) §6）
5. 抜粋を作ってプロンプトを組み、`AiClient.generate()`
6. 応答をパースして `QuizCard` の列にする（→ §8 判断3）
7. `Success(cards, isViewed = false)` — **未確認のあいだシートのラベルが変わり、Vigilith が反応する**
8. Snackbar の「見る」またはシートの「✓ クイズを開く」で `quiz` ルートへ遷移する
9. **遷移と同時に `markViewed()`** が走り、未確認の表示が消える

**中断:** ノート・Vault切替、およびセクションチャットの開始・終了で `cancelAndClear()` が走る。

## 5. 機能仕様

- **前提条件:** ノートが表示されていること
- **入力:** ノートのタイトルと、**いま読んでいるセクションの周辺本文**（→ [section_ai_chat](section_ai_chat.md)）
- **出力:** `QuizCard` の列（設問・選択肢・正解の位置・解説・形式）
- **出題形式:** `○×問題` / `3択問題` / `4択問題` の3種
- **上限:**

  | 対象 | 値 | 定数 |
  |---|---|---|
  | プロンプトへ渡す抜粋 | **1200文字** | `NoteExcerptLimits.QUIZ` |
  | コード主体と判定する比率 | 0.45 | `CODE_DOMINANT_RATIO` |
  | 文脈が短いと判定する文字数 | 180文字 | `SHORT_CONTEXT_CHARACTERS` |
  | 文脈が短いと判定する文の数 | 2文 | `SHORT_CONTEXT_SENTENCES` |
  | 出力枠 | 256トークン | `genai-prompt` |

- **状態:** `Idle` / `Loading(sourceTitle, format)` / `Success(sourceTitle, cards, isViewed)` /
  `Error(message, sourceTitle, isViewed)`
- **エラー／AI非対応／キャンセル時:**
  - AI非対応 → `Error`（**要約と違い、エラーとして見せる** — 明示操作への応答なので無反応にできない）
  - 生成失敗・DL失敗 → `Error`。**`isViewed` を持つので、確認するまで未確認の表示が残る**
  - ノート切替・セクション文脈の切替 → `cancelAndClear()`

## 6. 状態とデータ

**UI状態:** `QuizState`（`NoteUiState.quizState`）。**永続化しない。**

**`Success` と `Error` の両方が `isViewed` を持つ。** 失敗も「まだ見ていない」を持つのは、
**失敗こそ気づかれないと再試行されない**ため。

**`isViewed` の消費先は3つで、AIタブのバッジは含まれない。**

| 消費先 | 未確認のときの振る舞い |
|---|---|
| Snackbar（`MainActivity`） | 未確認のときだけ出す |
| 吹き出しシートのラベル | `✓ クイズを開く` / `! エラーを確認`（確認後は `↻ クイズを再試行`） |
| **Vigilith（マスコット）** | 未確認のときだけ `Ready` / `Error` に反応する。**確認済みの結果では動かない** |

`resolveAiTabBadgeState` は `remarkState` しか受け取らないので、**クイズはAIタブのバッジを持たない。**

**契約2箇所への登録（ノート単位の状態）:**
`cancelNoteScopedJobs()` → `quiz.cancelAndClear()`、
`withNoteScopedReset()` → `quizState = QuizState.Idle`。**両方に登録済み。**

## 7. システム設計

```
SectionChatSheet（📝 この部分でクイズ）
 └─ QuizController.create()
      ├─ profileQuizInput(content)     ← **非AI**。本文の構造だけで形式を決める
      ├─ AiClient.generate()  @generateMutex
      ├─ parseQuizResponse()           ← 純関数・JVMテスト
      └─ startModelDownload()          ← 完了で create() を自動再開（isCurrent のときだけ）

quiz ルート（非タブ）
 └─ QuizScreen  ← 遷移時に markViewed()
```

## 8. 設計判断と代替案

### 判断1: 出題形式はAIに選ばせず、本文の構造で決める

`profileQuizInput()` は **AIを使わず**、実際にクイズへ渡す文脈の構造だけを見て形式を決める。

- **コードが支配的**（0.45超）なら4択の選択肢を作れない → 形式を落とす
- **文脈が短い**（180文字未満／2文以下）なら選択肢の材料が足りない → ○×へ寄せる

**AIに選ばせない理由は、選択肢を作れない入力で4択を要求すると出力が破綻するから。**
形式の決定は生成の**前提条件**であって、生成の一部ではない。

**これは「非AIで絞ってからAI」の型**で、Nano を1回しか使わずに済む。

### 判断2: 生成中の再タップを無視する

`state.current is QuizState.Loading` なら即 return する。
**Mutex の順番待ちを重複させない** — 押した回数だけ生成が積まれると、
待ち時間が倍々になり、しかも最後の1回以外は捨てられる。

### 判断3: 応答のパースは寛容だが、構造契約は守らせる

`parseQuizResponse` は行頭の番号（`1.` `1)`）や `**太字**` の揺れを吸収する一方、
**フィールド名と選択肢の数は期待する形式と照合する。**

**寛容さと厳格さを取り違えない** — 揺れる**書式**は吸収し、欠けている**中身**は弾く。
（防御的パースは全分岐で対称に、という教訓が別途ある → [lessons](../lessons.md) L36）

### 判断4: 未確認管理を持つ（この機能だけ）

**結果が専用画面にあり、しかも「同じノートを開いているあいだ」しか存在しない。**
見逃すとノート切替で消えるので、**「まだ見ていない」を状態として持ち続ける必要がある。**

**ただし表現の場所はAIタブではない。** 入口が吹き出しシートなので、未確認は
**シートのラベルと Vigilith の反応**で伝える。**通知は入口の近くに置く**という形になっている。

ひとことが `isViewed` を捨てられたのは結果をサイドカーへ永続化しているからで、
クイズはそうではない。**判定軸は「後から結果へ辿り着けるか」**
（→ [background_ai_ux](../system/background_ai_ux.md) §4）。

> **未確認管理を持つ Controller はこれ1つだけ。** [architecture](../system/architecture.md) の
> Controller共通化の再検討条件は「3つ目が現れたとき」なので、**現状は遠い。**

### 判断5: セクションチャットの切替でも止める

`cancelAndClear()` の契機はノート・Vault切替だけでなく、
**セクションチャットの開始・終了**も含む。文脈が変われば設問の前提も変わるため。

## 9. 品質要件

- **性能:** Mutex 直列で1回数十秒。**明示ボタンなので待つ前提に立てる**
- **プライバシー:** 本文はプロンプトへ入るが端末外へ出ない（→ [ADR-0002](../decisions/ADR-0002-on-device-ai-only.md)）
- **アクセシビリティ:** シートのラベルは記号（✓ / !）と文言の両方を持ち、色だけに頼らない
  （→ [ui_design_principles](../system/ui_design_principles.md) の 1.4.1）
- **端末制約:** Nano 非対応端末では `Error` として明示する

## 10. 検証と受け入れ条件

- **JVMテスト:** `QuizControllerTest`（状態遷移・世代照合・再タップ無視）/
  `QuizResponseParserTest`（書式の揺れと構造契約）/ `QuizInputProfileTest`（形式の決定）/
  `QuizPromptBuilderTest` / `VigilithStatusDerivationTest`（未確認のときだけマスコットが反応する）
- **instrumentation:** `OnDeviceGenerationTest`（実端末での生成）
- **保証していないこと:**
  - **設問の正しさを検証していない。** 正解が本当に正解かは確かめていない
  - **同じノートで同じ設問にならない。** Nano の出力は揺らぐ
  - 抜粋で切り落とされた区間からは出題されない

## 11. 既知の制約・未解決事項

| | |
|---|---|
| **成績を残さない** | 正答率の蓄積・間隔反復（「そろそろ復習」）は未実装。ローカルDBの候補として [_wip/feature_ideas.md](../../_wip/feature_ideas.md) にある |
| 設問がノート切替で消える | 永続化していないため。上記の成績記録を入れるなら同時に解ける |
| キャッシュが無い | 同じノートで押し直すと再生成になる |
| 設問の質を測る手段が無い | 上記「保証していないこと」参照 |

## 12. 開発経緯

[開発日誌 2026-07](../../owner/journal/2026-07.md)
