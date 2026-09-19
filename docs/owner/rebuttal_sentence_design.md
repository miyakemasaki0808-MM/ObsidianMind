# 反証の一文 — 実装設計書

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-17 / 基準 `f2d1f18`
**状態:** Draft — 未実装。[idea_catalog](08_idea_catalog.md) の AI◯1「反証の一文」を、他のAI（Claude／Codex）が実装できる粒度へ起こしたもの
**最終検証:** —（未実装。器を整えただけで日付を進めない）
**関連コード（予定）:** `domain/RebuttalClaimScanner.kt` / `domain/RebuttalCandidateRanking.kt` / `domain/RelatedCandidateId.kt` / `model/state/RebuttalSentence.kt` / `model/RebuttalProtocol.kt` / `model/NoteExcerptLimits.kt` / `model/PromptLimits.kt` / `ai/PromptBuilder.kt` / `controller/RebuttalController.kt` / `controller/NoteSessionCoordinator.kt` / `NoteViewModel.kt` / `ui/screen/RelatedTab.kt`
**関連テスト（予定）:** `RebuttalClaimScannerTest` / `RebuttalCandidateRankingTest` / `RebuttalControllerTest` / 既存の走査・契約テスト群（→ §0.4）
**正本:** 実装時に `docs/dev/features/rebuttal_sentence.md` を起こし、そちらを正本にする。**本書は `owner/` の文書なので検査に載らず、実装後も更新しない**（→ [README](README.md)）

> **この文書が答える問い:** Fable 5.1 の推しアイデアのうち何を、なぜ選び、どう作るか。
> 読む順は §0 → §7 → §5。§0 は引き渡しの要点、§7 が構造、§5 が数値の正本。

---

## 0. 実装者への引き渡し

### 0.1 何を選んだか、なぜか

[idea_catalog](08_idea_catalog.md) §5 の本命3件のうち、**「封をした返事」は既にオーナー判断で
[sealed_reply](../dev/features/sealed_reply.md) として設計の下書きが起きている**。残る2件（反証の一文・外画面の扉）から
**反証の一文**を選んだ。理由は3つ。

| 観点 | 反証の一文 | 外画面の扉 |
|---|---|---|
| 北極星「もう一段深める」への直接性 | **似た意見ではなく反対の意見との再会**を作る。Reflect の核に効く | 眺める面と読む面の区別。体験は良いが深める機能ではない |
| このアプリの独自性（オンデバイスAI） | Nano を1回、**選ぶだけ**で使う。生成に賭けない | AIを使わない |
| 足場の確認結果（§0.2） | 関連ノートの経路・ID契約・世代照合が**そのまま使える** | Fold の外画面幅の扱いは `LAUNCH-05` の実機検証が進行中で、足場が動いている |

### 0.2 帳の記述と、ソースを読んで分かった差分

**帳の費用見積もりは当て直していない**（帳 §6 の注意どおり）。ソースを当てた結果、2点を訂正する。

| 帳の記述 | 実装の事実 | 本書の扱い |
|---|---|---|
| 「足場は関連ノートの再ランクが既に読んでいる8KBのスニペットで、**追加I/Oは無い**」 | 読むのは8KB（`NoteReadLimits.SNIPPET_MAX_BYTES`）だが、**保持するのは冒頭150字のスニペットとタグ・aliases だけ**（`CandidateContextData`）。本文は `loadContextOrEmpty` を抜けた時点で捨てている | 一文の候補は8KBの本文から要るので、**関連ノートとして画面に出た最大8件を、同じ読み出し口でもう一度読む**（→ §8 判断3）。追加I/Oは「最大8件×8KB」に閉じる |
| 「出力はスニペットの文IDで」 | 関連ノートのIDは `C01..`、再会カードは `R01..`、ピッカーは `P01..`。**接頭辞は経路ごとに分けて、ログで見分ける**規約がある | 接頭辞 `X` を新設し `parseCandidateIds(prefix = 'X')` で受ける |

**関連ノートの結果を書くのは Controller ではなく `NoteViewModel.fetchRelatedNotes`** である
（Uri を解決するため窓口側にあり、`cancelHostJobs` 経由で契約に登録されている）。新機能の起動点はここになる（→ §7）。

### 0.3 作るもの一覧

| 層 | 新規 | 変更 |
|---|---|---|
| `model` | `state/RebuttalSentence.kt`（状態1型）/ `RebuttalProtocol.kt`（`REBUTTAL_NONE_TOKEN` と判定） | `NoteUiState`（欄1つ）/ `NoteUiStateStore`（Writer と `withNoteScopedReset`）/ `NoteExcerptLimits.REBUTTAL` / `PromptLimits.REBUTTAL_CANDIDATES_CHARACTERS` |
| `domain` | `RebuttalClaimScanner.kt`（候補文の列挙・純関数）/ `RebuttalCandidateRanking.kt`（順位付け・純関数） | `RelatedCandidateId.kt`（`rebuttalCandidateId`） |
| `ai` | — | `PromptBuilder.kt`（`RebuttalCandidateLine` / `RebuttalSelectionPrompt` / `buildRebuttalPrompt`） |
| `controller` | `RebuttalController.kt` | `NoteSessionCoordinator.kt`（生成・`completeRelatedNotes`・`cancelNoteScopedJobs` への登録） |
| 窓口 | — | `NoteViewModel.fetchRelatedNotes` の成功枝 |
| `ui` | `RebuttalPanel`（`RelatedTab.kt` 内の internal Composable でよい） | `RelatedTab.kt` |
| 文書 | `docs/dev/features/rebuttal_sentence.md` / `docs/review/device_validation/rebuttal_sentence.md` | §12 の一覧 |

### 0.4 通さなければならない検査（既存の走査・契約テスト）

**このリポジトリで規則が守られるのは検査に載せたときだけ**（→ [lessons L29](../dev/lessons/L29.md)）。
新機能が触る面には既に検査が置いてある。**足したものが何を落とすか**を先に挙げる。

| 検査 | 新機能が落とす条件 | 対処 |
|---|---|---|
| `PromptIndentationTest` / `PromptBudgetTest` | `PromptSamples.all()` に builder を足していない | `PromptSamples` へ `buildRebuttalPrompt` を足す（戻り値名を builder 名と一致させる） |
| `PromptGenerationCoverageTest` | builder 件数が 13 から変わった／`OnDeviceGenerationTest` で分類されていない | 件数を 14 へ。`UNCOVERED_BUILDERS` へ `"buildRebuttalPrompt"` を足す（実生成を通すなら呼ぶ側へ） |
| `NoteExcerptThreadingTest` | `buildNoteExcerpt` の呼び出し箇所が `EXPECTED_CALL_COUNTS` に無い／`withContext(excerptDispatcher) { buildNoteExcerpt(` の形でない | `controller/RebuttalController.kt` を 1 で登録。コンストラクタに `excerptDispatcher: CoroutineDispatcher = Dispatchers.Default` を**この綴りで**置く |
| `NoteSessionCoordinatorTest`「リセット検査の入力は全フィールドが初期値と異なる」 | `NoteUiState` に欄を足して `fullyPopulatedState()` に入れていない | 欄を登録し、ノート切替で null に戻ることを既存の全欄検査に乗せる |
| `NoteUiStateStoreTest`「各Writerは担当スライスだけを更新する」 | Writer を足して検査に載せていない | 1ケース足す |
| `AiAvailabilityUsageTest` | `AiAvailability` を `==` で比較した／メンバー import した | 網羅 `when` で書く |
| `SourceCommentShapeTest` | 本番コメントに日付・レビュー番号・2連KDoc | 契約と理由だけを現在形で書く |
| `PackageDependencyTest` | `controller`/`domain`/`model` が `android.*` を import した | `readContent` はラムダで受ける（関連ノートと同じ） |
| `SourceDocSyncTest`「文書からソースへのリンクは行番号を持たず、名前で指す」 | **`docs/` 配下の全 `.md`** が `.kt` へ張ったリンクの先が無い／ラベルの識別子が無い | 本書は `.kt` へリンクを張らない。正本（`features/`）では名前で指す |
| `DesignDocStateNameTest` | `dev/features` `dev/system` に `Available` 等の消した名前を裸で書いた | 状態の variant 名に `Available` / `Unavailable` を使わない（→ §6） |
| `DeviceValidationDocsTest` | 実機ケース文書に `## 正本` `## 適用条件` `## 検証前` `## ケース` `## 後処理` `## 記録` のどれかが無い／`../../dev/` へのリンクが無い | 様式どおりに作る |
| `AdrShapeTest`（features の12節） | 正本の節が空 | 埋められない節は `> **未確認:**` か `> **該当なし:**` |

### 0.5 机上ゲートと完了の線

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest lintDebug --offline
```

`androidTest` を足したら `assembleDebugAndroidTest` も通す。**実機検証は Codex が行い、完了の線はオーナーが引く**
（→ [CLAUDE.md](../../CLAUDE.md)「作業の進め方」）。実装者は「別の目を通していないので未確定」と明示して渡す。

---

## 1. 概要

ノートを開くと関連タブに関連ノートが並ぶ。**その関連ノートの本文から、いま読んでいるノートの主張と
最も食い違う一文を1つだけ選び、関連タブの別枠に原文のまま出す。** タップすると出どころのノートへ移る。

関連ノートは「似ている」で並ぶ。思考をもう一段深めるのは似た意見ではなく反対の意見なので、
**同じ Vault の中の、過去の自分が書いた反対意見**を1文だけ差し出す。生成はしない。AIは選ぶだけ。
候補が無ければ何も出さず、AIが「食い違うものは無い」と答えても何も出さない。

出どころは [idea_catalog](08_idea_catalog.md) §1 の AI◯1。北極星（[roadmap](../_wip/roadmap.md) §0）の
「Rediscover→Reflect のループを濃くするか」に対して、**関連タブの既存の経路に選択を1回足すだけ**で応える。

## 2. ゴールと非ゴール

### ゴール
- 関連ノートの中から**反対の意見**を1文、原文のまま見せる
- Nano の呼び出しは**ノートあたり1回**、入力は既存の抜粋と候補ブロックの形をなぞる
- 失敗・非対応・空振りは**黙って劣化**する（自動起動の機能の一般則 → [background_ai_ux](../dev/system/background_ai_ux.md) §6 判断1）

### 非ゴール
- **生成しない。** 反証を書かせない。要約もしない。出すのは他ノートの原文1文だけ
- **現ノート側の「どの主張と食い違うか」は示さない**（→ §8 採らなかった案1）
- **正誤を判定しない。** 食い違いは「確認したくなる」までで、どちらが正しいかは言わない
- **本文へ書かない。** 太字化も脚注も足さない。**本文を書き換えるのは蒸留だけ**
- **モデルDLを始めない。** 未取得・DL中は黙って諦める（分野判定と同じ）
- **進捗を見せない。** 「探しています…」を出さない。結果があるときだけ枠が現れる
- **関連ノートの選び方には手を入れない。** `RelatedNotesUseCase` の候補・再ランク・プロンプトは変えない

## 3. 詳細機能一覧

| 詳細機能 | ユーザーから見える挙動 | 起動条件 |
|---|---|---|
| 反証の枠 | 関連タブの関連ノートの下に「反証の一文」の枠が出て、他ノートの一文が原文のまま載る | **関連ノートの結果が届いたとき**（自動）。Nano が `Ready` で、候補が1つ以上あり、AIが1件選んだ |
| 出どころへ移る | 枠の中のノート行をタップするとそのノートへ切り替わる（関連ノートのタップと同じ） | 枠のタップ |
| 枠が出ない | 何も出ない。理由も出ない | 候補ゼロ／AIが `NONE`／非対応・未取得・DL中・失敗／ノート切替で追い越された |

## 4. 現在のユーザーフロー

1. ノートを開く（Rediscover・冊子の「これを読む」・さがす・関連のいずれか）
2. 要約・分野判定・関連ノートが自動で始まる（既存）
3. 関連ノートの結果が届く → 関連タブに関連ノートが並ぶ（既存）
4. **同じ瞬間に反証の選定が始まる。** 画面には何も出ない
5. 関連ノートに出た最大8件の本文（先頭8KB）を読み、**断定の形をした文**を規則で列挙する
6. 現ノートの抜粋との語の重なりで候補を並べ、上位を `X01..` のIDで Nano に渡す
7. Nano がIDを1つ返す → 関連タブの下に枠が出る。`NONE` なら何も起きない
8. タップすると出どころのノートへ移り、1へ戻る（ノート単位の状態はここで消える）

**中断:** ノートを切り替えると 5〜7 は止まり、旧ノートの一文は新しいノートの画面に出ない。
**再実行:** モデルDLの完了で関連ノートが呼び戻されると、反証も同じ経路で呼び戻される。

## 5. 機能仕様

**この節が数値の正本。** §8 は理由だけを持つ。**すべて実機で当て直す前提の初期値**（関連ノートと同じ扱い）。

### 5.1 定数

| 定数 | 値 | 用途 | 置き場 |
|---|---:|---|---|
| `REBUTTAL_SOURCE_NOTE_LIMIT` | 8 | 本文を読み直す関連ノートの上限 | `RebuttalController` |
| `REBUTTAL_CLAIMS_PER_NOTE` | 3 | 1ノートから候補に残す文の上限 | `RebuttalCandidateRanking.kt` |
| `REBUTTAL_CANDIDATE_LIMIT` | 12 | Nano に渡す候補の総数 | 〃 |
| `REBUTTAL_CLAIM_MIN_CHARS` / `MAX_CHARS` | 12 / 120 | 候補にする文の長さ | `RebuttalClaimScanner.kt` |
| `NoteExcerptLimits.REBUTTAL` | 800 | 現ノート側の抜粋上限 | `model/NoteExcerptLimits.kt` |
| `PromptLimits.REBUTTAL_CANDIDATES_CHARACTERS` | 1,800 | 候補ブロックの取り分 | `model/PromptLimits.kt` |
| IDの接頭辞 | `X` | `X01..X12` | `RelatedCandidateId.kt` |
| `REBUTTAL_NONE_TOKEN` | `NONE` | 「食い違うものは無い」の表明語 | `model/RebuttalProtocol.kt` |

**検算（完成プロンプトの長さ）。** 指示文 約700字 ＋ タイトル 200字以内 ＋ 抜粋 800字＋注意書き226字 ＋
候補 1,800字以内 ＝ **約3,800字**。`PromptLimits.MAX_PROMPT_CHARACTERS`（6,000）を下回り、
`PromptBudget.assemble` の切り詰めは設計上起きない。関連ノート（約5,000字）が最大構成のままなので
`PromptBudgetTest` の「意図する最大構成」は動かない。

**検算（Nano の呼び出し回数）。** ノートを開くたびの自動呼び出しは 要約・分野判定・関連ノート の3回が
**4回**になる。Rediscover の2回目以降は再会カードが加わって4回が**5回**。**すべて Mutex 直列**なので、
反証は最後尾に並ぶ（→ §8 判断5・§11）。

### 5.2 候補の列挙（非AI・`scanRebuttalClaims`）

**規則で列挙し、選別だけをAIに任せる**（このアプリのAI機能に共通する型 → [feature_ideas](../_wip/feature_ideas.md) §0.5 型1）。
入力は関連ノート1件の本文（先頭8KB）。出力は文の列（原文順）。

| 手順 | 規則 | 既存の部品 |
|---|---|---|
| 1 | frontmatter とコードフェンスの中を落とす | `stripFrontmatter` / `withoutFencedCode` |
| 2 | 見出し行・表の区切り行・リンクだけの行を落とす | `BookletCoverLine.kt` の private な3判定と同じ。**`MarkdownPlainText.kt` へ引き上げてよい**（行の種類の判定は前処理であって選定規則ではない） |
| 3 | インラインコードは**記法だけ落として中身を残す** | 冊子の扉と同じ判断（`` `NoteViewModel` を分割した。`` の主語を消さない）。再会カードが中身ごと落とすのは、問いと前提を探す用途だから |
| 4 | 残りの記法を落とし、文へ割る | `stripMarkdownMarkers` / `splitIntoSentences`（括弧の内側では切らない） |
| 5 | 長さ 12〜120 字、文字か数字を含み、終止符（`。！？!?.`）で終わる | 再会カードと同じ「文の形」の要求。ラベル行を落とす |
| 6 | **疑問文を落とす** | 再会カードの疑問文判定（`[?？]` または `か／かな` ＋句点で終わる）と**同じ正規表現を使い、真なら捨てる**。問いは主張ではない |
| 7 | 同一の文を1つに畳む | — |

**選定規則は共有しない。** `MarkdownPlainText.kt` の方針どおり、前処理だけを共有し
「何を候補にするか」は本ファイルが持つ。再会カードや扉の規則を書き換えない。

### 5.3 順位付け（非AI・`rankRebuttalCandidates`）

候補の総数を `REBUTTAL_CANDIDATE_LIMIT` に絞り、**Nano の位置バイアスを味方につけて話題の近い文を先頭へ寄せる**
（関連ノートの再ランクと同じ狙い）。

```
score(claim) = diceCoefficient(textBigrams(現ノートの抜粋.text), textBigrams(claim))
```

1. ノートごとに score 降順で上位 `REBUTTAL_CLAIMS_PER_NOTE` 件。同点は原文順
2. 全ノートを混ぜて score 降順。同点は**関連ノートの並び順**（AI推薦 → 未リンクの決定的 → wikilink 済み。`RemarkController.selectCandidates` と同じ順）、次に原文順
3. 先頭 `REBUTTAL_CANDIDATE_LIMIT` 件。この順で `X01..` を採番する

**score が 0 でも捨てない。** 文字 bigram の Dice は日本語の短文で 0 になりやすく、閾値は測ってから決める（→ §11）。
`rankByScore` をそのまま使える（`scoreOf` を差し替えるだけ）。

**現ノート側の材料は、AIへ渡す抜粋と同じ文字列**にする。順位付けとAIが別の本文を見ると、
「近いから先頭に置いた」が成り立たない。

### 5.4 プロンプト（`buildRebuttalPrompt`）

- 戻り値は `RebuttalSelectionPrompt(text, candidates)` で、`validIds` は**実際に載せた候補のIDだけ**（`ReunionSelectionPrompt` と同じ契約）
- 候補行は `X01 | 文` の形（`RebuttalCandidateLine(id, text)`）。**出どころのタイトルは載せない** — 食い違いの判定に要らず、入力を増やすだけ
- 候補ブロックは `PromptLimits.REBUTTAL_CANDIDATES_CHARACTERS` で閉じ、超える行は**飛ばして先へ進む**（`buildPickerPrompt` と同じ詰め方）。飛ばした候補は `validIds` にも入れない
- 指示文は静的部分だけを `trimIndent()` し、可変部は後から連結する（`PromptIndentationTest` の契約）
- 抜粋は `NoteExcerpt` で受け、`renderForPrompt()` で注意書きを付ける

指示文の骨子（英語で書く。既存 builder と揃える）:

```text
You are helping someone think further about a note they wrote.
Each candidate below is one sentence taken from a DIFFERENT note in the same vault, listed as "ID | sentence".
Choose the ONE candidate that most directly conflicts with a claim made in the current note:
it asserts the opposite, denies a premise the current note relies on, or reaches a different conclusion about the same subject.
Prefer a clear conflict on the same subject over a sentence about a different subject.
Ignore candidates that agree with, restate, or merely add detail to the current note.
Return only the ID of the sentence you chose, on one line (for example: X01).
Do not return the sentence, an explanation, or more than one ID.
If no candidate conflicts with the current note, return exactly: NONE

Current note title: <title>
Current note content:
<excerpt>

Candidates:
X01 | ...
```

### 5.5 応答の契約

| 応答 | 扱い |
|---|---|
| `validIds` にあるID1つ（装飾・桁落ち・大小文字は `parseCandidateIds(prefix = 'X')` が吸収） | その候補の原文を、出どころの `RelatedNote` と組にして状態へ書く |
| `NONE`（前後の飾りは許す。`isReunionNone` と同じ判定） | 何も書かない。**失敗ではない** |
| 空・候補外のID・散文 | 何も書かない。**約束違反であって「無い」ではない**（再会カードの `Unavailable` 相当）。次にノートを開いたとき素直に試し直す |

**表示する文は手元の候補から引く。** 応答の本文は使わない（言い換え・翻訳が混ざると「本文にある1文」でなくなる）。

### 5.6 エラー／AI非対応／キャンセル時

- `AiAvailability` は網羅 `when`。**`Ready` 以外の4値はすべて「何もしない」**。`downloadModel()` を呼ばない
- 本文の読み直しに失敗した候補ノートは**そのノートだけ落として続行**（関連ノートの `loadContextOrEmpty` と同じ）
- 生成の例外（タイムアウト・途切れ・AICore の拒否）は**握って何も書かない**。理由を画面に出さない
- `CancellationException` は再throw。ノート切替後の後着は `requestId` ＋ `isCurrent()` で `update` 直前に1箇所で落とす
- 抑制（連続失敗の記録）は**持たない**。分野判定が持つのは全ノートで走るためで、反証は関連ノートの結果が届いた回にしか走らない

### 5.7 画面

- 場所は `RelatedTab` の `RelatedNotesPanel` の**直下**。関連ノートの下に、同じ `PanelBlue` の面で1枠
- 見出し「反証の一文」（`AccentText`・13sp・Bold。記号を付けるなら 🔗 と ✦ 以外を1つ）、
  添え書き「いま読んでいるノートと食い違う、別のノートの一文」（`OnSurfaceFaint`・11sp）
- 本文の一文（`OnSurface`・14sp・行間 19sp）
- 区切り（`PanelDivider`）の下に出どころのノート行。**`RelatedNoteItem(note, onClick)` をそのまま使う**
  （タップの挙動・`linked` 表示・更新日を関連ノートと揃える）
- **色を足さない・形を足さない。** 面の色は年代と分野が持ち、形は面の役割が持つ（→ [bearing_channels](../dev/system/bearing_channels.md)）。
  既存トークンの範囲で描く。`OnVibrant` を画面から直接使わない（`VibrantTextUsageTest`）
- **読み上げ:** 見出しは heading セマンティクス、ノート行は「〈タイトル〉を開く」。色以外の手がかりは見出しの文言と行の形が担う
- 枠は `rebuttalSentence` が null なら描かない。読み込み中の表示も、失敗の表示も無い

## 6. 状態とデータ

**ノート単位の状態を1つ足す。契約2箇所へ登録する**（→ [architecture](../dev/system/architecture.md) 判断2）。

| 項目 | 内容 |
|---|---|
| 状態型 | `model/state/RebuttalSentence.kt` — `data class RebuttalSentence(val text: String, val source: RelatedNote)` |
| `NoteUiState` の欄 | `val rebuttalSentence: RebuttalSentence? = null`。**null が「出さない」**（`readingTraceCard` と同じ形。Loading／Error の variant を持たない） |
| Writer | `RebuttalStateWriter { val current: RebuttalSentence?; fun update(transform) }`（`ReadingTraceStateWriter` と同形） |
| リセット契約 | `NoteUiStateStore` の `withNoteScopedReset()` に `rebuttalSentence = null` |
| ジョブ停止契約 | `NoteSessionCoordinator.cancelNoteScopedJobs()` に `rebuttal.cancelAndClear()`。`cancelAndClear` は `activeRequestId++`・Job キャンセル・状態 null |
| 世代 | **ノート単位のみ**（`activeRequestId`）。Vault 世代は使わない。二層の表の基本形であり、分野判定のような例外にしない |
| 永続化 | **しない。** 痕跡サイドカーにも端末内ストアにも書かない。開き直せば選び直す |
| 蒸留保存後の本文差し替え | **維持する。** `withDistillBodyReloaded` はこの欄に触れない（一文は他ノートの原文なので有効なまま） |
| `toEventKey` / バッジ / Snackbar | **無し。** 結果は関連タブを開けば見え、後から辿れるので未確認管理を持たない（→ [background_ai_ux](../dev/system/background_ai_ux.md) §4） |
| Vigilith の導出 | **足さない。** 読み込み中の状態が無いので `resolveVigilithPresentation` に渡す材料が無い |

**名前の制約。** 状態や結果の variant に `Available` / `Unavailable` を使わない（`DesignDocStateNameTest` の退役名）。
Controller 内部の結果型は `Selected` / `NoneChosen` / `Skipped` の3値にする。

**影響面監査の証拠（新しい欄を読む箇所）。** 読むのは `RelatedTab` の1箇所だけ。`MainActivity` は `uiState` を
そのまま渡す。`relatedNotesState` を読む既存3箇所（`RelatedTab`・`startRemark`・`openBooklet`）は**変えない** —
`RelatedNotesState` に欄を足さないので、編む冊子とひとことは無傷である。

## 7. システム設計

```
NoteViewModel.fetchRelatedNotes（既存・窓口のノート単位ジョブ）
 └─ RelatedNotesUseCase.findRelated → RelatedNotesResult.Success
      └─ session.completeRelatedNotes(title, content, result, readContent)   ← 新設・1手
           ├─ stateStore.setRelatedNotesState(Success(...))                     ← 既存と同じ
           └─ RebuttalController.select(title, content, related, ai, readContent)
                ├─ requestId = ++activeRequestId / job?.cancel()
                ├─ aiClient.checkAvailability() — Ready 以外は return（DLしない）
                ├─ 候補ノート: aiNotes → 未リンク → wikilink済み、参照で重複を畳み最大8件
                ├─ readContent(ref) を順に呼ぶ（8KB境界読み出し。失敗した件は落とす）
                ├─ withContext(excerptDispatcher) { buildNoteExcerpt(content, REBUTTAL) }
                ├─ withContext(excerptDispatcher) { scan → rank }               ← 純関数だが入力に比例
                ├─ 候補ゼロなら return
                ├─ PromptBuilder.buildRebuttalPrompt → aiClient.generate
                ├─ NONE / 候補外 / 例外 → return（何も書かない）
                └─ isCurrent(requestId) を確かめてから state.update { RebuttalSentence(text, source) }

RelatedTab
 └─ uiState.rebuttalSentence?.let { RebuttalPanel(it, onOpenNote) }
```

### 7.1 各ファイルの責務

| ファイル | 責務 | 依存してよい層 |
|---|---|---|
| `domain/RebuttalClaimScanner.kt` | §5.2 の列挙。`internal fun scanRebuttalClaims(content: String): List<String>` | `model`・同パッケージの前処理 |
| `domain/RebuttalCandidateRanking.kt` | §5.3 の順位付け。`internal data class RebuttalCandidate(val source: RelatedNote, val text: String)` と `rankRebuttalCandidates(excerptText, claimsBySource, perNote, total)` | `model` |
| `domain/RelatedCandidateId.kt` | `internal fun rebuttalCandidateId(index: Int) = "X" + 2桁` | — |
| `model/RebuttalProtocol.kt` | `REBUTTAL_NONE_TOKEN` と `isRebuttalNone(response)`。**`REUNION_NONE_TOKEN` と同じ理由で葉に置く**（`ai` と `domain` の両方が見る） | — |
| `ai/PromptBuilder.kt` | `RebuttalCandidateLine` / `RebuttalSelectionPrompt` / `buildRebuttalPrompt` | `model` |
| `controller/RebuttalController.kt` | 上の流れ。**Android 型を持たない。** `readContent: suspend (DocumentRef) -> String` はラムダで受ける | `model`・`domain`・`ai` |
| `controller/NoteSessionCoordinator.kt` | 生成（`excerptDispatcher = parseDispatcher` を渡す）・`completeRelatedNotes`・契約への登録 | — |
| `NoteViewModel.kt` | 成功枝で `session.completeRelatedNotes(...) { ref -> repository.readNoteSnippet(contentResolver, ref.toDocumentUri()) }` | — |
| `ui/screen/RelatedTab.kt` | `RebuttalPanel` | `model` |

### 7.2 Controller の骨子

```kotlin
class RebuttalController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: RebuttalStateWriter,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var job: Job? = null
    private var activeRequestId = 0L

    fun select(
        title: String,
        content: String,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        readContent: suspend (DocumentRef) -> String
    ) {
        val requestId = ++activeRequestId
        job?.cancel()
        val sources = orderSources(relatedNotes, aiNotes)   // AI → 未リンク → linked、ref で distinct、最大8
        if (sources.isEmpty()) return
        job = scope.launch {
            when (aiClient.checkAvailability()) {           // 網羅 when。== は使わない
                AiAvailability.Ready -> Unit
                AiAvailability.NeedsDownload, AiAvailability.Downloading,
                AiAvailability.Unsupported, is AiAvailability.TemporarilyUnavailable -> return@launch
            }
            val bodies = sources.mapNotNull { note -> readOrNull(note, readContent)?.let { note to it } }
            val excerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(content, NoteExcerptLimits.REBUTTAL)
            }
            val candidates = withContext(excerptDispatcher) {
                rankRebuttalCandidates(
                    excerptText = excerpt.text,
                    claimsBySource = bodies.map { (note, body) -> note to scanRebuttalClaims(body) },
                    perNote = REBUTTAL_CLAIMS_PER_NOTE,
                    total = REBUTTAL_CANDIDATE_LIMIT
                )
            }
            if (candidates.isEmpty()) return@launch
            val lines = candidates.mapIndexed { i, c -> RebuttalCandidateLine(rebuttalCandidateId(i), c.text) }
            val prompt = PromptBuilder.buildRebuttalPrompt(title, excerpt, lines)
            val outcome = try {
                val response = aiClient.generate(prompt.text).trim()
                when {
                    response.isBlank() -> Outcome.Skipped
                    isRebuttalNone(response) -> Outcome.NoneChosen
                    else -> parseCandidateIds(response, prompt.validIds, limit = 1, prefix = 'X')
                        .firstOrNull()?.let { id -> Outcome.Selected(candidates[lines.indexOfFirst { it.id == id }]) }
                        ?: Outcome.Skipped
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Outcome.Skipped
            }
            if (outcome !is Outcome.Selected || !isCurrent(requestId)) return@launch
            state.update { RebuttalSentence(outcome.candidate.text, outcome.candidate.source) }
        }
    }

    fun cancelAndClear() {
        activeRequestId++
        job?.cancel()
        job = null
        state.update { null }
    }
}
```

**読み直しは `readContent` を順に呼ぶ。** 8件なので並列の Semaphore は要らない。
`readContent` は SAF の IO を内側で切り替えるので、Controller は Dispatcher を選ばない。
**`buildNoteExcerpt` の `withContext` ブロックは、その1文だけを含む形にする**（走査テストの正規表現がその形を要求する）。

## 8. 設計判断と代替案

### 判断1: 関連ノートの1回とは別に、Nano を1回呼ぶ

関連ノートのプロンプトへ「ついでに反証のIDも返せ」と足す案は、呼び出しを増やさない。採らないのは、
**候補文は関連ノートが選ばれた後でないと8件に絞れない**（40候補ぶんの文を載せると入力予算 3,500 字が破綻する）ことと、
1つの出力枠（256トークン）に2種類の答えを求めると**両方が薄くなる**（ひとことが分類ラベルを同時に求めない判断と同じ）ため。

### 判断2: 起動契機は「関連ノートの完了」に鎖でつなぐ

ノートを開いた瞬間に独立して走らせない。関連ノートの結果を材料にするので、その完了を契機にすれば
**要求の世代・キャンセル・DL完了後の呼び戻し**を関連ノートと共有できる。
ロードマップの「留まったら生成」（自動起動を数秒遅らせる）が入れば、**関連ノートが門を通った後にだけ**反証が走るので、
本機能側に門を足す必要が無い。

### 判断3: 本文は読み直す。関連ノートのキャッシュを太らせない

`CandidateContextData` に候補文を持たせれば追加I/Oはゼロになるが、**40候補すべてで列挙を走らせ**、
LRU 300件のキャッシュが最大で数百KBに膨らむ。使うのは最大8件なので、**その8件だけを同じ入口
（8KB境界読み出し）で読み直す**ほうが CPU もメモリも小さい。読み直す口は関連ノートと同じ `readContent` で、
読む経路を2本にしない。

### 判断4: 候補は「断定の形をした文」を規則で列挙し、選別だけをAIに任せる

「反証かどうか」を文の形だけで決めることはできない。一方で「問いではない」「ラベルではない」「コードではない」は
規則で落とせる。**規則は取りこぼさない側へ、選別は落とす側へ寄せる**（再会カードと同じ配分）。
順位付けを非AIで持つのは、Nano の位置バイアスに対して**話題の近い候補を先頭へ置く**ためで、
関連ノートの再ランクと同じ理由である。

### 判断5: 失敗も空振りも黙る。抑制も記録も持たない

自動起動の機能は黙って劣化する（[background_ai_ux](../dev/system/background_ai_ux.md) §6 判断1）。
分野判定は全ノートで走るので連続失敗の抑制を持つが、反証は関連ノートの結果が届いた回にしか走らず、
**永続もしないので「永久停止」を作る材料が無い**。空振り（`NONE`）を記録しないのは、候補が関連ノートの選び方で変わるため、
同じノートでも次回は別の候補が並びうるから。

### 判断6: 出どころへの導線は関連ノートの行をそのまま使う

新しい行の部品を作らない。`RelatedNoteItem` を使えば、タップの先・`linked` の表示・更新日が関連ノートと揃い、
ひとつの画面に**同じ意味の行が2つの見た目で並ぶ**ことを避けられる。

### 採らなかった案1: 現ノート側の主張も対で示す

「現ノートのこの文と、あのノートのあの文が食い違う」と対で見せる案。分かりやすいが、
現ノートの候補文もプロンプトへ載せる必要があり**入力が倍になる**うえ、出力に2つのIDを求める。
まず片側だけで**実機の体感を見てから**判断する。

### 採らなかった案2: 再会カードの枠に出す

再会カードは排他1件で、帳 §6 が指摘するとおり枠を取り合う案が既に3つある。反証は Rediscover 以外の入口でも
成立する機能なので、**関連タブに置けば枠の取り合いに入らない**。

### 採らなかった案3: 反証の一文を痕跡サイドカーへ保存する

「前回はこの反証が出た」を残す価値はあるが、痕跡のスキーマを上げ、退避・復元・併合の表を直す費用が付く。
**開き直せば数秒で選び直せる**ので、v1 では持たない。

## 9. 品質要件

- **性能:** 追加のI/Oは最大8件の8KB境界読み出し。列挙・順位付け・抜粋は `Dispatchers.Default`。Nano は1回、最後尾
- **プライバシー:** 候補文と抜粋はプロンプトへ入るが端末外へ出ない（→ [ADR-0002](../dev/decisions/ADR-0002-on-device-ai-only.md)）
- **データ保護:** 本文にもサイドカーにも書かない。壊れてもユーザーのノートは失われない（ベストエフォート側）
- **アクセシビリティ:** 枠の見出しと行の読み上げ名。色だけで意味を持たせない
- **端末制約:** Nano 非対応端末では枠が一度も出ない。それ以外の画面は変わらない

## 10. 検証と受け入れ条件

### 10.1 JVMテスト（新規）

| テスト | 固定するもの |
|---|---|
| `RebuttalClaimScannerTest` | frontmatter・コードフェンス・見出し・表の区切り・リンクだけの行を落とす／インラインコードの中身を残す／括弧の内側で切らない／長さの上下限／終止符の要求／**疑問文を落とす**／同一文の畳み |
| `RebuttalCandidateRankingTest` | ノート内上位3件／総数12件／同点の順（並び順→原文順）／score 0 を捨てない／空入力 |
| `RelatedCandidateIdTest`（追記） | `X` 接頭辞で `C01` を受理しない／`X3` の桁落ち補正／`X01通信` を捨てる |
| `RebuttalControllerTest` | `Ready` で1回だけ生成し状態へ書く／`NONE`・空・候補外で書かない／候補ゼロで `generateCalls == 0`／**`Ready` 以外の4値で生成せず `downloadCalls == 0`**／読み直し失敗の候補だけ落として続行／生成中にノート切替（`cancelAndClear`）→ 後着で書かない／`CancellationException` を握らない／例外で書かない・状態は null のまま／`buildNoteExcerpt` が `excerptDispatcher` で走る（テストスケジューラで完了を待てる） |
| `NoteSessionCoordinatorTest`（追記） | `fullyPopulatedState()` へ登録／ノート切替で null／実物 Controller の Job が `onNoteChanged()` で止まる（ひとことの検査と同形）／**関連ノートの完了から反証が始まる**（`completeRelatedNotes` が1手であること） |
| `NoteUiStateStoreTest`（追記） | Writer が担当スライスだけを更新する |
| `PromptSamples` / `PromptIndentationTest` / `PromptBudgetTest`（追記） | builder を列挙に足す。字下げと上限は自動で乗る |
| `PromptGenerationCoverageTest`（更新） | 件数 14。`OnDeviceGenerationTest.UNCOVERED_BUILDERS` へ列挙 |
| `NoteExcerptThreadingTest`（更新） | `controller/RebuttalController.kt` を 1 で登録 |

**変異で確かめること**（→ [lessons L11](../dev/lessons.md#l11-テストが効いているかは変異させて確かめる)）:
`cancelNoteScopedJobs` から `rebuttal.cancelAndClear()` を消す／`withNoteScopedReset` から欄を消す／
`isCurrent` の照合を消す／`validIds` を `idToCandidate` の全鍵にする。**4つとも赤くなること。**

### 10.2 instrumentation

`RebuttalPanelTest`（`androidTest`）— 状態に一文があるとき枠と出どころの行が描かれ、null のとき描かれない。
**純関数だけでは配線を保証できない**（再会カードの `ReadingTraceCardPanelTest` と同じ理由）。
式本体の `runBlocking` は `runBlocking<Unit>` にする（`InstrumentationTestShapeTest`）。

### 10.3 実機確認（Codex・`docs/review/device_validation/rebuttal_sentence.md` に置く）

| ID | 入力・操作 | 期待 |
|---|---|---|
| `REBUT-01` | 反対の主張を書いた関連ノートを持つ一時Vaultでノートを開く | 関連タブの関連ノートの下に枠が出て、**本文にある文がそのまま**出る。タップで出どころへ移る |
| `REBUT-02` | 関連ノートはあるが食い違う文が無いノート | 枠が出ない。エラーも出ない |
| `REBUT-03` | 関連ノートが0件になる Vault（ノート1本） | 枠が出ない。生成が走らない（logcat でプロンプトが無い） |
| `REBUT-04` | 生成中にノートを切り替える | 旧ノートの一文が新しいノートの関連タブに出ない |
| `REBUT-05` | 枠が出た状態で蒸留を保存する | 枠が残る |
| `REBUT-06` | 枠が出た状態で回転・Fold 開閉 | 枠が残る（ViewModel の状態なので `LAUNCH-05` と同じ経路） |
| `REBUT-07` | Nano 非対応（エミュレータ） | 枠が一度も出ず、他の画面は変わらない |
| `REBUT-08` | `RebuttalPanelTest` を `am instrument` で実行 | 全件成功 |

**AIの選定の妥当性は判定しない。** 判定するのは候補集合・状態遷移・描画の配線まで（共通手順「アプリが保証する境界」）。

### 10.4 着手前に測る（推奨）

再会カードは着手前に `docs/` を代理コーパスにして非AI段を回し、**素朴な規則では成立しないことを見つけた**
（→ [reunion_card](../dev/features/reunion_card.md) §5）。同じ道具立てで `scanRebuttalClaims` を `docs/**/*.md` に当て、
次の2つを見る。**閾値は目安であって受け入れ条件ではない。**

- 1文以上の候補を返す文書の割合（目安 80% 以上。低ければ規則が厳しすぎる）
- 無作為に20文を抜いて、ラベル・表のセル・コード断片が混ざる割合（目安 20% 以下。高ければ規則が緩すぎる）

### 10.5 保証していないこと

- **選ばれた文が本当に「反証」かは測らない。** 似た話題の文が選ばれることは普通に起きる
- **`NONE` の頻度は未測定。** 多ければ「候補があるのに何も出ない機能」に見える
- **候補は本文の先頭8KBから拾う。** 長いノートの後半にある反対意見は永久に届かない
- **同点の並びと score の閾値は初期値。** 実データで当て直す
- **AICore の短い時間窓の回数制限**（12回成功後の13回目で拒否）に対して、呼び出しを1回増やす影響は測っていない

## 11. 既知の制約・未解決事項

| | |
|---|---|
| 自動呼び出しが1回増える | 要約・分野・関連の後に4本目として並ぶ。Mutex 直列なので枠が出るまでの時間は前3本の合計に依存する。「留まったら生成」が入るまでは、素早くノートを開き続けると AICore の回数制限に当たりやすくなる |
| 先頭8KBしか見ない | 判断3の読み直しは関連ノートと同じ入口を使うため。後半を見るなら入口を増やす判断が要る |
| 「前提の更新先」との区別が無い | 帳 AI◯5（時間による更新）と AI◯1（食い違い）は同じ候補集合から出る。プロンプトの基準で分けているだけで、実機で混ざるなら種別を持つ判断が要る |
| 文の言語が混ざる | 日本語のノートに英語の候補が並ぶと、bigram の重なりが 0 になり順位付けが効かない |
| 実装後は `_wip/current_issues.md` に「実機検証待ち」として起票し、検証まで消さない | CLAUDE.md「変更を終える前に」3 |

## 12. 実装の順序と文書の更新

**修正1件＝1コミット。** 層ごとに積むと、机上ゲートを各段で通せる。

1. `domain` — 列挙・順位付け・ID接頭辞＋テスト（純関数だけ。ここで §10.4 を回してもよい）
2. `model` — 状態型・Writer・契約への登録・定数・表明語＋Store と Coordinator の検査への登録
3. `ai` — builder＋`PromptSamples`＋件数と分類の更新
4. `controller` ＋ 窓口 — Controller・`completeRelatedNotes`・`cancelNoteScopedJobs`・ViewModel の成功枝＋テスト＋走査テストの登録
5. `ui` — `RebuttalPanel`＋`androidTest`（`assembleDebugAndroidTest` を通す）
6. 文書 — 下表

| 文書 | 直すこと |
|---|---|
| `docs/dev/features/rebuttal_sentence.md`（新規） | 本書 §1〜§11 を12節の様式へ写す。`最終検証` は実装と突き合わせた日にする。ソースは名前で指す |
| `docs/dev/system/architecture.md` | 判断1の系統図に `RebuttalController`。「Controller共通化はしない」の表へ列を足す（DL ✗ 黙って諦める／通知 ✗／失敗を見せる ✗／起動契機 関連ノートの完了） |
| `docs/dev/system/background_ai_ux.md` | §6 判断1 の「自動」の行に反証を加える |
| `docs/dev/system/ai_input_excerpt.md` | 本数の表（builder 14・抜粋経路 +1）と §9 の定数表に `REBUTTAL` |
| `docs/dev/document_map.md` | §2 の機能一覧と §5 の逆引き表 |
| `docs/dev/change_history.md` | 1行（100字以内） |
| `docs/_wip/current_issues.md` | 実機検証待ちの起票 |
| `docs/review/device_validation/rebuttal_sentence.md`（新規） | §10.3 のケース。様式は既存ケースと同じ6見出し |
| `docs/owner/` | **触らない**（オーナーの依頼があったときだけ） |

## 13. 開発経緯

出どころは [idea_catalog](08_idea_catalog.md) §1 AI◯1 と §5。着手後の経緯は [journal/](journal/) が持つ。
