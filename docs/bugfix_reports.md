# BugFix レポート集

**プロジェクト:** Obsidian Mind

**AI（最新モデルを含む）が生成しがちで、通常フローでは表面化しない潜在バグ**を、再現経路・根本原因・修正・教訓つきで記録する累積文書。当初は状態遷移（画面遷移・Fold開閉によるActivity再生成）をまたいだときに **UIのタップ導線が意図した処理に到達しない** 型を中心に集めたが、#3 で **AI応答の解釈が正常系だけを想定し、崩れた出力で破綻する** 型も収録した。

- **なぜ独立文書にするか**: これらは特定機能の設計判断（→ [design/](design/)）でも、単なる変更履歴（→ [change_history.md](change_history.md)）でもなく、「**AI（最新モデルを含む）が生成しがちで、通常フローでは表面化しない潜在バグ**」という横断的な知見だから。同じ轍を踏まないための備忘録。
- **共通パターン**: どちらも「①最初から潜んでいた潜在バグ」が「②別画面へ遷移して戻る／折り畳む、という状態遷移」で初めて発火し、「③ボタンが無反応」という同じ症状で現れた。Composeの再コンポーズ／再生成の性質に起因する。

---

## 目次

| No | 症状 | 根本原因の型 | 発火トリガ | 混入者 | 発見者 |
|----|------|--------------|-----------|--------|--------|
| [#1](#1-吹き出しボタンがクイズ画面から戻ると無反応になる古いクロージャ固定) | 吹き出しタップが完全無反応・シートもラベルも出ない | 古いクロージャの固定（stale closure） | クイズ画面へ遷移して戻る | Claude Fable 5 | ユーザー |
| [#2](#2-折りたたみ解除後に全画面ノートを閉じられない閉じる処理のsuspend依存) | ✕もシステムバックも無反応で全画面を解除できない | 閉じる処理が完了しないsuspendに直列依存 | Fold開閉（Activity再生成） | Claude Opus（PR #31 の修正①） | ユーザー |
| [#3](#3-多択クイズの正解解釈が崩れた応答で失敗する防御的パースの非対称) | 多択クイズが「生成結果を読み取れません」で失敗する | 防御的パースの非対称（○×だけ緩く、多択は完全一致） | 小型モデルが答えを崩した応答（`B.`／`(B)` 等） | Codex（クイズ改善時） | Claude Sonnet 5（レビュー） |

---

## #1 吹き出しボタンがクイズ画面から戻ると無反応になる（古いクロージャ固定）

**日付:** 2026-07（Claude Fable 5 のコーディングで混入）
**該当:** `SectionFab`（[NoteReaderTab.kt](../app/src/main/java/com/example/newproject/ui/NoteReaderTab.kt) の `SectionFab` / `pointerInput(Unit)` + `detectTapGestures`）
**関連設計:** [design/section_ai_chat.md](design/section_ai_chat.md)

### 症状

浮遊吹き出し（SectionFab）を特定手順のあとにタップすると、**シートが全く出ず・ラベルも変化せず・完全な無反応**になる。

### 再現経路

1. 吹き出し → 要約 → クイズ生成 → **クイズを解く** → 確認終了 → 吹き出しタップ
2. → 無反応（本来は新しいセッションが始まり「要約生成中…」シートが出るはず）

クイズ画面を経由しない通常フロー（吹き出し→要約→確認終了→再タップ）では**たまたま正常に見えていた**のが厄介な点。

### 根本原因 — `pointerInput(Unit)` が初回クロージャを固定する

吹き出しのタップは `pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }` で捕捉していた。キーが `Unit` のため、**初回コンポーズ時の `onTap` クロージャが固定**され、再コンポーズで新しい `onTap` に差し替わらない。

「クイズをとく」が決定打になる仕組み:

1. クイズ画面への遷移で読書画面は一旦破棄され、戻ったときに**セッションが生きている状態で再生成**される
2. このとき吹き出しが固定するクロージャは「`activeChat ≠ null`」を抱き込んだ版 — 「タップ＝既存シートを再表示（`showSheet`）」の分岐しか通らない
3. その後「確認終了」でセッションを破棄しても、吹き出しは**古いクロージャのまま**
4. タップ → `showSheet()` → セッションが `null` なので早期リターン ＝ 完全な無反応

通常フロー（クイズ画面を経由しない）では初回クロージャが「`activeChat = null`」版で、その分岐は新規/既存どちらも処理できるため**偶然正常に見えていた**。バグ自体はクイズ機能とは無関係で最初から潜んでいた。

### 修正

`rememberUpdatedState` で常に最新の `onTap` を参照させる（この問題の定石）。

```kotlin
val currentOnTap by rememberUpdatedState(onTap)
// ...
.pointerInput(Unit) {
    detectTapGestures(onTap = { currentOnTap() })
}
```

`pointerInput` のキーは `Unit` のまま（ジェスチャ検出器を張り替えない）で、呼び出しだけを最新にするのがポイント。

### 教訓

- `pointerInput(key)` / `LaunchedEffect(key)` などキー付きエフェクトの中で**ラムダを直接参照する**と、キーが変わらない限り古いクロージャを掴み続ける。長命なジェスチャ検出器から可変のコールバックを呼ぶときは `rememberUpdatedState` を挟む。
- 「別画面へ遷移して戻る」と読書画面が**セッション生存中に再生成**され、悪い状態のクロージャが固定される。全画面ルート（補記メモ結果画面など）から戻った場合も理論上同じ条件を踏むため、この修正で一緒に潰れている。

---

## #2 折りたたみ解除後に全画面ノートを閉じられない（閉じる処理のsuspend依存）

**日付:** 2026-07-21（PR #31 の修正①で混入 → 本セッションで修正）
**該当:** `FullscreenNoteScreen` の `leaveWith`（[NoteReaderTab.kt](../app/src/main/java/com/example/newproject/ui/NoteReaderTab.kt)）
**関連設計:** [design/note_fullscreen.md](design/note_fullscreen.md)

### 症状

折りたたみ（Fold）状態で全画面ノートを開き、**折りたたみを解除（展開）した後**、✕ボタンもシステムバックも**無反応**で全画面表示を解除できなくなる。

### 再現経路

1. 折りたたみ状態でノートを開き ⛶ で全画面表示
2. デバイスを展開（＝非折りたたみ状態へ遷移）
3. ✕ または システムバック → **どちらも無反応**、全画面のまま

### 根本原因 — 「閉じる」を完了しない `suspend` に直列依存させていた

✕・システムバック・FABタップはすべて `leaveWith(action)` を経由する。PR #31 の修正①で、離脱時のスクロール書き戻し（フリング中に消える問題）を防ぐため、`action()`（＝閉じる処理 `popBackStack`）を **`suspend` の `scrollToItem` の完了後**に呼ぶ形にしていた。

```kotlin
// 修正前（PR #31 修正①）
scope.launch {
    tabListState.scrollToItem(...)  // ← ここが完了しないと
    action()                        // ← 閉じる処理に到達しない
}
```

`AndroidManifest.xml` の `MainActivity` に `android:configChanges` が無いため、**Fold開閉は screenLayout/screenSize の変化でActivityを再生成**する。再生成直後、書き戻し先の `tabListState`（＝タブ側 `noteListState`）が再アタッチ／再measureのはざまに入り、`scrollToItem` の `scroll{}` が mutex を取れず／remeasureされず**完了しない**。結果、後続の `action()` に到達せず、**同じ `leaveWith` を通る✕とバックが同時に無反応**になる（両導線が一点に集約されていた点も症状と一致）。

通常フロー（Fold再生成を挟まない）では `scrollToItem` が即完了するため**正常に見えていた**。

### 修正

「閉じる」をスクロール書き戻しから**切り離す**。非suspendの `requestScrollToItem`（foundation 1.7で安定・BOM 2024.09.03 で利用可）で保留位置を積むだけにし、`action()` を即実行する。

```kotlin
// 修正後
val leaveWith: (() -> Unit) -> Unit = { action ->
    tabListState.requestScrollToItem(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset
    )
    action()  // 必ず即実行
}
```

コルーチンを使わないため、PR #31 修正①が心配していた「pop→scopeキャンセルで書き戻しが消える」問題も**原理的に発生しない**（#31①の意図を保ったままの上位互換）。未使用になった `rememberCoroutineScope` / `kotlinx.coroutines.launch` の import も削除。

### 教訓

- **ユーザーが必ず実行できるべき操作（閉じる・戻る）を、完了保証のない `suspend`（スクロール・アニメ・mutex獲得）に直列依存させない。** 副作用（書き戻し）はベストエフォートにして、主導線から切り離す。
- 「フリング中に書き戻しが消える」を直すために `action()` を後ろへ移した #31① は、別の状態遷移（Fold再生成）で「そもそも閉じられない」という**より重い不具合**を生んだ。片方の競合状態を潰す修正が、別の競合状態を作っていないか要注意。
- `LazyListState` へのスクロール指示は、UI応答性が要る場面では suspend の `scrollToItem` ではなく非suspendの `requestScrollToItem` を検討する。
- `android:configChanges` の無いActivityでは、Fold開閉・回転が**再生成**を伴う。状態遷移バグの検証項目に「Fold開閉」を必ず入れる。

---

## #3 多択クイズの正解解釈が崩れた応答で失敗する（防御的パースの非対称）

**日付:** 2026-07-22（Codex のクイズ改善で混入 → 本セッションのレビューで修正）
**該当:** `parseQuizResponse` の多択パス（[QuizResponseParser.kt](../app/src/main/java/com/example/newproject/QuizResponseParser.kt)）
**関連設計:** [design/section_ai_chat.md](design/section_ai_chat.md)

### 症状

○×・3択・4択の適応出題を入れてクイズ生成エラーを減らした直後の版で、**多択（3択・4択）だけが「Q&Aの生成結果を読み取れませんでした。」で失敗する**ケースが残っていた。特に4択は1問構成のため、その1問の解釈が失敗すると即エラーになる。

### 再現経路

1. リッチな本文で4択1問を生成 → モデルが正解を `ANSWER: B.` や `ANSWER: (B)`、`ANSWER: B) 選択肢文` のように出力
2. → 正解レターが取れずブロックが破棄され、結果0件 → エラー

プロンプトは `ANSWER: <A or B or C>` と裸のレターを指示しているため、**モデルが素直に `ANSWER: B` と返す通常フローでは正常に見えていた**。

### 根本原因 — ○×パスは防御的、多択パスは完全一致

クイズ改善で ○×（TrueFalse）パスは末尾記号除去＋同義語許容という防御的解釈だったのに対し、多択パスは完全一致のままだった。

```kotlin
// ○×パス（防御的）: 末尾記号を除去し同義語も許容
answer.trim().trimEnd('.', '。').uppercase()  // "TRUE"/"T"/"A"/"○"/"正しい" …

// 多択パス（非対称に厳格）: 完全一致のみ
labels.take(choices.size).indexOf(answer.trim().uppercase())  // "B." は一致せず -1 → ブロック破棄
```

**守ろうとしている相手＝指示に完全には従わない小型モデル**なのに、○×だけ防御して多択を素通しにしていた。これが「クイズ生成エラーを減らす」という当初の狙いを部分的に自ら削いでいた。

### 修正

多択の正解レター抽出を、**単語境界付き正規表現**へ変更（○×パスと同等の防御性に統一）。

```kotlin
val maxLabel = 'A' + (choices.size - 1)              // 3択なら C、4択なら D
val answerLetter = Regex("\\b[A-$maxLabel]\\b", RegexOption.IGNORE_CASE)
    .find(answer)?.value?.uppercase()
val correctIndex = answerLetter?.let { labels.take(choices.size).indexOf(it) } ?: -1
```

`\b[A-D]\b`（単語境界）とすることで `B.`／`(B)`／`B) 選択肢文`／`The answer is B` を救済しつつ、**単語内の文字（`ANSWER` の A、`BEST` の B）は誤検出しない**。範囲上限（`maxLabel`）で3択時の `D` は正しく弾く。

> **実装中の自己修正**: 初版は `firstOrNull { it in 'A'..'D' }` としたが、これは `The answer is B` で先頭の `ANSWER` の A を拾い、**不正解を正解扱いする silent bug** を生む。パース失敗（エラー）より悪いため、単語境界regexへ切り替えた。

### 教訓

- **防御的パースは全分岐で対称にする。** 片方（○×）だけ「崩れた出力」を許容し、もう片方（多択）を完全一致にすると、同じモデルの同じ崩れ方で片方だけ落ちる。
- **「入力を緩く解釈する」修正は、緩くしすぎると別種の誤りを生む。** 単純な文字包含（`firstOrNull`）は自由文の中の文字を誤検出しうる。境界（`\b`／トークン単位）を持たせて「意図した1つ」だけを取る。
- 正常系（`ANSWER: B`）で動くことを合格にせず、**モデルが崩す代表パターン（末尾記号・括弧・余計な語）を必ずテストに入れる**（`QuizResponseParserTest` に追加）。

---

## まとめ — AIが生成しがちな潜在バグの見つけ方

- 最新モデルが混入させる潜在バグには、少なくとも2つの型がある。**(A) 状態遷移型**（#1・#2）＝「別画面へ遷移して戻る」「折り畳む／展開する」を挟むとボタンが無反応になる。**(B) 応答解釈型**（#3）＝AIの出力が正常系から崩れると解釈が破綻する。混入者は #1 Claude Fable 5・#2 Claude Opus・#3 Codex と横断的。
- **共通DNA は「通常フローでは表面化しない」こと。** (A)は遷移を挟まなければ、(B)はモデルが素直に返せば、正常に見えてしまう。だから「通常フローで動く」を合格にしてはいけない。
- 検証の勘所：(A)は**遷移をまたいだ後**（クイズ→戻る／Fold開閉→戻る）に主要導線を1回叩く。(B)は**モデルが崩す代表パターン**（末尾記号・括弧・余計な語・前置き）をテストに入れ、防御的解釈を**全分岐で対称**にする。
