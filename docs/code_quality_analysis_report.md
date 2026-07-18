# ソースコード品質分析報告書 — リファクタ候補・ロジック問題・処理効率

**日付:** 2026-07-19
**プロジェクト:** Obsidian Mind
**対象:** `main` ブランチ（PR #15 AIピッカーのマージ後、コミット `4de5cfd`）
**分析範囲:** `app/src/main/java` 全19ファイル（約3,900行）

---

## 総評

アーキテクチャの骨格（Repository / UseCase / ViewModel / Compose UI の分層、`AiClient` インターフェースによるAI抽象化）は健全で、機能追加のたびに既存の型を再利用する規律も保たれている。一方で、機能を重ねた結果として **(a) ViewModelの肥大化と状態リセット漏れ、(b) SAF走査の重複実行、(c) AI呼び出しの競合ガード不在** の3系統に問題が集中している。特に「重要度: 高」の4件は実際の操作で症状が出るものなので、次の改修サイクルでの対応を推奨する。

なお、H2・P4 など一部は直近のAIピッカー実装（PR #15）で持ち込まれた問題である。

---

## 1. ロジック問題 — 重要度: 高

### H1. さがすタブ経由でノートを開くと関連ノートが常に空になる

**場所:** [NoteViewModel.kt:670](app/src/main/java/com/example/newproject/NoteViewModel.kt) （`fetchRelatedNotes`）、[NoteViewModel.kt:151](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`cachedNotes`）

`fetchRelatedNotes` は候補一覧に `cachedNotes` を使うが、これを埋めるのは `loadRandomNote`（行189）だけ。アプリ起動 →（Vault復元）→ さがすタブで検索 → ノートを開く、という導線では `cachedNotes` が空のまま `openNote` → `fetchRelatedNotes` に到達し、**行670のガードにより関連ノートが黙って空の成功として表示される**。ランダム表示を一度でも押せば直るため気づきにくい。

**推奨:** `cachedNotes` が空なら `fetchRelatedNotes` 内で `collectNotes` を実行して埋める（もしくは `openNote` 側で保証する）。

### H2. Vault切り替え後、さがすタブに旧Vaultの状態が残る

**場所:** [NoteViewModel.kt:164-174](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`saveVault`）

`saveVault` は要約・関連・クイズ・補記の状態をリセットするが、AIピッカーで追加した `folders` / `selectedFolder` / `searchState` をリセットしていない。旧Vaultのフォルダchipsが表示され続けるうえ、**`selectedFolder` は旧ツリーの `documentId` を保持しているため、新Vaultで検索すると無効なURIへのクエリで失敗（または誤動作）する**。PR #15 の実装漏れ。

**推奨:** `saveVault` のリセット対象に `folders = emptyList(), selectedFolder = null, searchState = SearchState.Idle` を追加。

### H3. AI応答のステイル競合 — 素早くノートを切り替えると古い要約が上書きする

**場所:** [NoteViewModel.kt:641](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`fetchSummary`）、[NoteViewModel.kt:668](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`fetchRelatedNotes`）

ノートを開くたびに `viewModelScope.launch` で要約・関連ノート取得を投げるが、**前のノートの実行中ジョブをキャンセルしていない**。ノートA表示直後にノートBを開くと、Aの `generate()` が後から完了した場合にBの画面へAの要約が表示される（完了順は保証されない）。オンデバイスLLMは生成に数秒かかるため、この競合は現実的に発生し得る。同時に複数の `generate()` が並走すること自体も、Nano側の処理を無駄に食い合う。

**推奨:** `summaryJob?.cancel()` 方式で直前ジョブをキャンセルするか、リクエストにノートIDを紐付けて結果適用時に一致チェックする。

### H4. YAML frontmatter がノート本文としてそのまま描画される

**場所:** [MarkdownParser.kt:34](app/src/main/java/com/example/newproject/ui/markdown/MarkdownParser.kt)（`parseMarkdownBlocks`）、[RandomNoteScreen.kt:451](app/src/main/java/com/example/newproject/ui/RandomNoteScreen.kt)（`NoteContentPanel`）

`parseMeta` は frontmatter を解析するのに、描画側の `parseMarkdownBlocks` は frontmatter を除去しない。冒頭の `---` は**水平線**として、`tags: [...]` などのキーは**段落テキスト**として本文に表示される。このVaultは tags/aliases を使っているため、多くのノートで冒頭にノイズが出る。セクションチャットのLLM入力（`buildNoteSectionModel` 経由）にも混入する。

**推奨:** `parseMarkdownBlocks` の先頭で frontmatter ブロック（`---`〜`---`）をスキップする処理を追加（`parseMeta` と同じ判定ロジックを共有化）。

---

## 2. ロジック問題 — 重要度: 中

### M1. テーブルの空セルで列がズレる

**場所:** [MarkdownParser.kt:64-68](app/src/main/java/com/example/newproject/ui/markdown/MarkdownParser.kt)

`row.split("|").filter { it.isNotBlank() }` は**中間の空セルも捨てる**ため、`| a |  | c |` が2列に詰まり、以降の列が左にズレて表示される。先頭・末尾の空要素除去と中間空セル保持を区別する必要がある。

**推奨:** `split("|")` 後に先頭と末尾の空要素だけ `drop` し、中間は `trim` のみで保持する。

### M2. 補記メモ一覧が「新しい順」ではなく「タイトル逆順」

**場所:** [NoteRepository.kt:196](app/src/main/java/com/example/newproject/NoteRepository.kt)（`listAnnotationFiles` の `sortedByDescending { it.name }`）

ファイル名は `{タイトル}__補記_{タイムスタンプ}.md` 形式なので、名前降順ソートは**タイトルの辞書順が支配**し、作成日時順にならない。削除画面の並びが意図（新しい順）とズレる。

**推奨:** `parseAnnotationName` 相当のロジックでタイムスタンプ部を抽出してソートキーにする。

### M3. AI補記メモがランダム表示・関連ノート候補に混入する

**場所:** [NoteRepository.kt:30](app/src/main/java/com/example/newproject/NoteRepository.kt)（`collectNotes`）

`collectNotes` は `_AI補記` フォルダも再帰対象に含むため、ランダム表示で自動生成メモが引かれ、関連ノートのAI候補にも混じる。さがすタブでは「出す」を意図的に選択したが、**ランダム表示と関連ノートで含めるかは未判断のまま**動作している。設計判断として明示すべき点。

**推奨:** 方針を決めて明文化する。除外するなら `collectNotes` に除外フォルダ名の引数を追加（デフォルト除外）。

### M4. ノート切替時にセクションチャットの状態が残留する

**場所:** [NoteViewModel.kt:209](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`openNote`）

`openNote` / `loadRandomNote` は各状態をリセットするが `sectionChat` を残す。関連タブ・さがすタブから新ノートを開いた直後、**旧ノートのセクションを対象にしたチャットシートが再表示され得る**。

**推奨:** リセット対象に `sectionChat = null` を追加（H2と同じ「リセット漏れ」系。R5の共通化で同時に解消するのが効率的）。

### M5. AI呼び出しにタイムアウト・直列化がない

**場所:** [AICoreClient.kt:54](app/src/main/java/com/example/newproject/ai/AICoreClient.kt)（`generate`）

`generateContent` にタイムアウトがなく、Nanoが応答しない場合はUIが Loading のまま固まる。また複数機能（要約＋関連＋セクション要約＋質問候補）が同時に `generate()` を呼ぶ構造で、排他制御がない。H3の対応と合わせて、`withTimeout` と `Mutex`（または実行キュー）の導入を推奨。

### M6. クイズ応答のパースが `\n\n` 区切りに強依存

**場所:** [NoteViewModel.kt:530](app/src/main/java/com/example/newproject/NoteViewModel.kt) 付近（`parseQuizResponse`）

ブロック区切りを `split("\n\n")` に依存しており、モデルが空行を1つ挟まない・`\r\n` を返す等の揺れで問題が丸ごと欠落する。`Q:` 行を区切りとして走査する方式が頑健。

### M7. インラインMarkdownパーサの既知の限界

**場所:** [MarkdownParser.kt:154-234](app/src/main/java/com/example/newproject/ui/markdown/MarkdownParser.kt)（`inlineMarkdown`）

- 単独の `*`（乗算記号など）が後方の `*` と誤ペアリングしてイタリック化する
- `[` の処理が対応する `]` を確認せず `](` を前方検索するため、`[foo] bar [baz](url)` で誤マッチする

実害は限定的だが、技術ノート（コード断片を含む文）で顕在化しやすい。優先度は低めでよいが既知の制約として記録する。

### M8. 引用ブロックの縦バーが行折り返しで短くなる

**場所:** [MarkdownRenderer.kt:101-121](app/src/main/java/com/example/newproject/ui/markdown/MarkdownRenderer.kt)（`MarkdownBlockquote`）

バーの高さを `lines.size * 24dp` で固定計算しているため、長い引用行が画面幅で折り返すとテキストよりバーが短くなる。`Modifier.height(IntrinsicSize.Min)` を親Rowに適用し `fillMaxHeight()` にするのが正攻法。なお `with(LocalDensity.current)` はこの計算に不要。

---

## 3. 処理効率の問題

### P1. 同一ノートを2回フルパースしている

**場所:** [RandomNoteScreen.kt:117](app/src/main/java/com/example/newproject/ui/RandomNoteScreen.kt)（`buildNoteSectionModel`）と [MarkdownRenderer.kt:45](app/src/main/java/com/example/newproject/ui/markdown/MarkdownRenderer.kt)（`MarkdownNoteContent`）

ノート表示のたびに `parseMarkdownBlocks(content)` が **セクションモデル構築用と描画用で2回**実行される。長文ノートではコンポジション時のジャンク要因。`NoteSectionModel` にブロック列を持たせて描画側へ渡せば1回で済む。

### P2. さがす・ランダムのたびにSAF全走査が走る

**場所:** [NoteViewModel.kt:253](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`searchByKeyword`）、[NoteViewModel.kt:279](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`pickRandomInScope`）、[NoteViewModel.kt:177](app/src/main/java/com/example/newproject/NoteViewModel.kt)（`loadRandomNote`）

SAFの `query` は1フォルダごとにIPCが発生するため再帰走査は本質的に遅い。現在はボタンを押すたびに毎回フル走査しており、同じフォルダで検索→ランダム→検索と操作すると同一走査を3回繰り返す。`cachedNotes` という仕組みが既にあるのに、さがすタブは使っていない。

**推奨:** スコープ（フォルダ）単位の結果を セッション内キャッシュし、Vault切替時に破棄する。`loadRandomNote` も同様に `cachedNotes` を再利用できる（現在は毎タップ再走査）。

### P3. `inlineMarkdown` が再コンポジションごとに再構築される

**場所:** [MarkdownRenderer.kt:53-62](app/src/main/java/com/example/newproject/ui/markdown/MarkdownRenderer.kt) 配下の各ブロックComposable

`buildAnnotatedString` を伴う `inlineMarkdown(text)` が `remember` なしで毎回呼ばれる。LazyColumnのスクロールで頻繁に再実行されるため、`remember(text) { inlineMarkdown(text) }` でメモ化する価値がある。

### P4. bigram計算がソート比較のたびに再実行される

**場所:** [SearchPickerUseCase.kt:74-79](app/src/main/java/com/example/newproject/domain/SearchPickerUseCase.kt)（`keywordRecallCut`）

`sortedByDescending { note -> note.name.toBigrams()... }` はセレクタが**比較のたびに**評価されるため、bigram分解が O(n log n) 回走る。候補数十〜数百件なら実害は小さいが、`map { it to score }` で先にスコア計算してからソートするのが正しい形。PR #15 で持ち込んだ非効率。

### P5. 補記メモ保存のたびに `_AI補記` フォルダをBFS探索

**場所:** [NoteRepository.kt:253](app/src/main/java/com/example/newproject/NoteRepository.kt)（`findAnnotationFolder`）

保存・一覧のたびにVault全体をBFSして `_AI補記` を探している。フォルダは実質ルート直下固定（作成時もルート直下）なので、**ルート直下だけ見れば十分**。見つけたURIのセッション内キャッシュも有効。

---

## 4. リファクタリング推奨

### R1. `RandomNoteScreen.kt`（711行）の分割 — 名前と内容が乖離

3タブ（`NoteReaderTab` / `RelatedTab` / `AiTab`）＋共有パネル群が1ファイルに同居しており、ファイル名「RandomNoteScreen」はもはや実態を表していない。`NoteReaderTab.kt` / `RelatedTab.kt` / `AiTab.kt` / `SharedPanels.kt` への分割を推奨。

### R2. `NoteViewModel`（795行）の肥大

ノート表示・要約・関連・クイズ・補記・セクションチャット・検索の7責務が単一クラスに集中。当面は機能単位の `private` 領域整理で凌げるが、次に機能を足すなら分割（機能別ViewModelか、状態ホルダクラスの抽出）を検討すべき段階。

### R3. SAFカーソルループが5箇所に重複

`collectNotes` / `listTopLevelFolders` / `collectNotesInScope` / `findAnnotationFolder` / `listAnnotationFiles` がほぼ同一の `query + cursor while` ボイラープレートを持つ。`queryChildren(documentId): List<ChildDoc>` の様なプライベートヘルパに集約すれば、各メソッドは数行になり、projectionの不一致リスクも消える。**P5・M3の改修と同時に行うのが効率的。**

### R4. `cleanAiTitle` の重複定義

[RelatedNotesUseCase.kt:166](app/src/main/java/com/example/newproject/domain/RelatedNotesUseCase.kt) と [SearchPickerUseCase.kt:91](app/src/main/java/com/example/newproject/domain/SearchPickerUseCase.kt) に同一実装が2つ。AI応答パース共通処理として `domain` 内のトップレベル関数へ抽出する。

### R5. 状態リセットブロックの重複

`loadRandomNote` / `openNote` / `saveVault` がほぼ同じ `copy(...Idle)` を繰り返しており、**H2・M4のリセット漏れはこの重複が根本原因**。`private fun NoteUiState.resetNoteScopedStates(): NoteUiState` に一本化すれば、追加した状態のリセット漏れが構造的に起きなくなる。

### R6. グラデーション・色リテラルの重複

同一の Indigo→Aqua→Coral グラデーションが4ファイル（RandomNoteScreen / SearchScreen / AnnotationManagerScreen / AnnotationResultScreen）で個別定義。`0xFFF0F4FF`（薄青パネル）や `0xFFCC0000`（エラー赤）等のリテラルも多数散在。`theme/AppColors.kt` へ `AppGradient` / `ErrorRed` / `PanelBlue` として集約する。

### R7. UI文字列のハードコード（i18n不統一）

`NoteReaderTab` は `stringResource` を使うのに、他画面はすべて日本語直書き。現状は実害がないが、方針としてどちらかに統一すべき（単一言語アプリと割り切るなら strings.xml を廃してもよい）。

### R8. `Divider` の非推奨API

Material3 1.3.0 では `Divider` が deprecated（`HorizontalDivider` へ改名）。4ファイルで使用中。機械的置換で済む。

### R9. `StubAiClient` が未参照

[AICoreClient.kt:66](app/src/main/java/com/example/newproject/ai/AICoreClient.kt) の `StubAiClient` はどこからも参照されていない。開発用として残すなら、その旨のコメントと `@Suppress("unused")` を付けるか、テストソースセットへ移動する。

### R10. タブ遷移ラムダの重複

`MainActivity` の search / related 両ルートに同一の `navigate("note") { popUpTo... }` ブロックがある。`AppScaffold.kt` の `navigateToTab` と同型なので、公開ヘルパへの集約が可能。

---

## 5. 優先度付きアクションプラン

| 優先 | 項目 | 分類 | 工数目安 | 備考 |
|------|------|------|----------|------|
| 1 | H2 + M4 + R5: 状態リセットの一本化 | バグ修正 | 小 | 3件を1改修で解消。回帰リスク低 |
| 2 | H1: `cachedNotes` 未更新の解消 | バグ修正 | 小 | さがすタブの体験に直結 |
| 3 | H4: frontmatter除去 | バグ修正 | 小 | 表示品質への効果が大きい |
| 4 | H3 + M5: AIジョブのキャンセル＋タイムアウト | バグ修正 | 中 | 競合設計の見直しを伴う |
| 5 | P2: スコープ走査のキャッシュ | 効率 | 中 | 大きいVaultほど効く |
| 6 | R3 + P5 + M3: SAFヘルパ抽出と補記フォルダ扱いの整理 | リファクタ | 中 | まとめて1PRが適切 |
| 7 | P1 + P3: パース1回化とメモ化 | 効率 | 小 | 長文ノートのジャンク解消 |
| 8 | M1 + M2: テーブル列ズレ・補記ソート | バグ修正 | 小 | 独立して着手可 |
| 9 | R1 + R6 + R8: UI整理（分割・色集約・Divider） | リファクタ | 中 | 挙動変更なしの純リファクタ |
| 10 | M6 + M7 + M8 + R2 + R4 + R7 + R9 + R10 | 各種 | — | 余裕があるときに |

---

## 6. 分析方法と限界

- 全19ファイルを通読し、指摘箇所は行番号レベルで実コードを再確認した（推測による指摘はない）。
- 動的検証（プロファイリング・実機計測）は未実施。P1・P2の体感影響はVault規模に依存する。
- 開発環境にJDKがないためコンパイル・Lintは未実行。R8（deprecation）はバージョン表からの静的判断。
