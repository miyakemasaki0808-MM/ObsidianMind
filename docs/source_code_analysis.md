# ソースコード解析書

**プロジェクト:** Obsidian Mind  
**日付:** 2026-05-31  
**対象ブランチ:** feature/improve_AI_and_graphview

---

## 1. ファイル構成

```
app/src/
├── main/
│   ├── java/com/example/newproject/
│   │   ├── MainActivity.kt              # エントリポイント・NavHost でナビゲーション管理         108行
│   │   ├── NoteViewModel.kt             # 状態管理・ビジネスロジックの橋渡し                    523行
│   │   ├── NoteRepository.kt            # ファイルアクセス層・frontmatter/wikilink 解析・補記保存 182行
│   │   ├── NoteTitleNormalizer.kt       # Obsidian wikilink タイトル正規化ユーティリティ          18行
│   │   ├── ui/
│   │   │   ├── RandomNoteScreen.kt      # メイン画面 Composable 一式                           556行
│   │   │   ├── QuizScreen.kt            # Q&A フラッシュカード画面                              250行
│   │   │   ├── AnnotationResultScreen.kt# 補記メモ生成結果画面                                  188行
│   │   │   ├── markdown/
│   │   │   │   ├── MarkdownParser.kt    # Markdown ブロックパーサー・inlineMarkdown              234行
│   │   │   │   └── MarkdownRenderer.kt  # Markdown レンダリング Composable                      236行
│   │   │   └── theme/
│   │   │       └── AppColors.kt         # UI カラーパレット定数                                  16行
│   │   ├── ai/
│   │   │   ├── AICoreClient.kt          # Gemini Nano 4 接続ラッパー（AiClient インターフェース）  71行
│   │   │   └── PromptBuilder.kt         # 要約・関連ノート・クイズ・補記メモプロンプト構築         146行
│   │   └── domain/
│   │       ├── SummarizeUseCase.kt      # 要約ユースケース                                       29行
│   │       └── RelatedNotesUseCase.kt   # 関連ノートユースケース（deterministic + AI 推薦の 2 段） 182行
│   ├── res/
│   │   └── values/
│   │       ├── colors.xml               # indigo のみ（themes.xml から参照）
│   │       ├── strings.xml              # 文字列リソース
│   │       └── themes.xml               # Theme.ObsidianMind
│   └── AndroidManifest.xml
└── test/
    └── java/com/example/newproject/
        └── NoteRepositoryTest.kt        # ユニットテスト（5 ケース）                              59行
                                                                               ───────────────────
                                                                               合計（本番コード）2,739行
```

> `activity_main.xml` は Jetpack Compose 移行時に削除済み。  
> `GraphViewScreen.kt` はグラフビュー廃止に伴い削除済み。

---

## 2. アーキテクチャ概観

```
┌─────────────────────────────────────────────────┐
│                  MainActivity                   │
│  ・registerForActivityResult で Vault 選択       │
│  ・calculateWindowSizeClass() でフォルダブル判定 │
│  ・setContent { } で Compose UI を起動           │
│  ・collectAsStateWithLifecycle() で状態購読      │
└──────────────────┬──────────────────────────────┘
                   │ by viewModels()
                   ▼
┌─────────────────────────────────────────────────┐
│                 NoteViewModel                   │
│  ・vaultUri を保持（構成変更を跨いで生存）        │
│  ・StateFlow<NoteUiState> で状態を公開           │
│  ・ノート読込後に SummarizeUseCase を起動        │
│  ・展開時に RelatedNotesUseCase を起動           │
│  ・モデル未DL時に downloadModel() Flow を収集    │
└──────────┬───────────────────┬──────────────────┘
           │ suspend fun       │ suspend fun
           ▼                   ▼
┌──────────────────┐  ┌──────────────────────────────────────┐
│  NoteRepository  │  │  SummarizeUseCase / RelatedNotesUseCase│
│  ・collectNotes  │  │  ・AiClient.checkAvailability         │
│  ・readContent   │  │  ・PromptBuilder でプロンプト生成      │
│  ・parseMeta     │  │  ・AiClient.generate(prompt)          │
└──────────────────┘  └────────────┬─────────────────────────┘
                                   ▼
                        ┌─────────────────────┐
                        │    AICoreClient      │
                        │  Gemini Nano 4 Full  │
                        │  ML Kit GenAI API    │
                        └─────────────────────┘
```

**データフロー（ノート表示 + 要約 + 関連ノート）**

```
ユーザーがボタンをタップ
  → viewModel.loadRandomNote(contentResolver)
  → NoteState.Loading を emit
  → repository.collectNotes() → repository.readNoteContent()
  → NoteState.Success(title, content) を emit
  → fetchSummary(title, content) を起動（並行）
      → SummarizeUseCase.summarize()
          → AICoreClient.checkAvailability()
              AVAILABLE    → generate(prompt) → SummaryState.Success(summary)
              DOWNLOADABLE → startModelDownload()
                               → DownloadProgress emit → SummaryState.Downloading(n, total)
                               → DownloadCompleted    → fetchSummary() + fetchRelatedNotes() を再実行
              UNAVAILABLE  → SummaryState.AiUnavailable（パネル非表示）
  → fetchRelatedNotes(title, content) を起動（並行・常時）
      → repository.parseMeta(content) で wikilinkTitles 抽出
      → RelatedNotesUseCase.findRelated()
          ① deterministic: wikilink 一致 + 4桁16進プレフィックス同一グループのノートを最大5件
          ② AI: prefixFilter() で候補を絞り buildRelatedNotesPrompt() → generate()
             レスポンスを行分割・正規化 → allNotes と照合 → Top-5 抽出
          → RelatedNotesState.Success(relatedNotes, aiNotes, aiStatus) emit
  → RelatedNotesPanel に表示（常時表示・展開/折りたたみ問わず）
  → アイテムタップ → viewModel.openNote(RelatedNote) → ノート本文が切り替わる
```

**Q&A クイズ生成フロー**

```
[Q&Aを作る] ボタンをタップ
  → viewModel.generateQuiz(title, content)
  → QuizState.Loading を emit
  → PromptBuilder.buildQuizPrompt() → aiClient.generate(prompt)
  → parseQuizResponse() で Q/A/B/C/D/ANSWER/EXPLANATION をパース → List<QuizCard>
  → QuizState.Success(cards) emit
  → NavController で QuizScreen に遷移
```

**補記メモ生成フロー**

```
[補記メモ] ボタンをタップ
  → viewModel.createAnnotation(...)
  → AnnotationState.Loading を emit
  → aiClient.checkAvailability()
      AVAILABLE    → createAnnotationWithAvailableModel()
                       → PromptBuilder.buildAnnotationPrompt() → generate()
                       → hasAnnotationBody() でバリデーション
                       → repository.createAnnotationFile() で _AI補記/ に保存
                       → AnnotationState.Success(uri, fileName, content) emit
      DOWNLOADABLE → startAnnotationModelDownload() → 完了後に上記を実行
      UNAVAILABLE  → AnnotationState.Error emit
  → NavController で AnnotationResultScreen に遷移
```

---

## 3. 各ファイル詳細解析

### 3-1. `NoteRepository.kt`

- `collectNotes()` は再帰ではなく `ArrayDeque` を使った BFS ループで実装。深いディレクトリ構造でもスタックオーバーフローしない。



```kotlin
data class NoteFile(val name: String, val uri: Uri)

data class NoteMeta(
    val tags: List<String>,
    val aliases: List<String>,
    val wikilinkTitles: Set<String>
)
```

- `parseMeta(content)` で YAML frontmatter（tags/aliases）と `[[wikilink]]` を抽出。
- frontmatter はインライン形式 `tags: [a, b]` とブロック形式（`- item` 列挙）の両方に対応。

---

### 3-2. `ai/AICoreClient.kt`

#### インターフェース

```kotlin
interface AiClient {
    suspend fun checkAvailability(): AiAvailability
    suspend fun generate(prompt: String): String
    fun downloadModel(): Flow<DownloadStatus>
}
```

#### AICoreClient（本番）

```kotlin
class AICoreClient : AiClient {
    private val model by lazy {
        Generation.getClient(
            GenerationConfig.Builder().apply {
                modelConfig = ModelConfig.Builder().apply {
                    preference = ModelPreference.FULL  // Gemini Nano 4 Full (E4B)
                }.build()
            }.build()
        )
    }
}
```

- `Generation.getClient()` は Context 不要（ML Kit ContentProvider で自動初期化）。
- `ModelPreference.FULL` = Gemma 4 E4B ベースの高精度モデルを指定。
- `checkStatus()` の戻り値は `FeatureStatus` の Int 定数（`com.google.mlkit.genai.common`）。

#### DownloadStatus sealed class

| サブクラス | プロパティ | 意味 |
|---|---|---|
| `DownloadStarted` | `bytesToDownload: Long` | DL開始、総サイズ通知 |
| `DownloadProgress` | `totalBytesDownloaded: Long` | DL進捗 |
| `DownloadCompleted` | なし（singleton） | DL完了 |
| `DownloadFailed` | `e: GenAiException` | DL失敗 |

---

### 3-3. `domain/RelatedNotesUseCase.kt`

```kotlin
data class RelatedNote(val title: String, val uri: Uri, val isWikilinked: Boolean)

enum class AiRecommendationStatus { Ready, Unavailable, NeedsDownload, Error }

sealed class RelatedNotesResult {
    data class Success(
        val relatedNotes: List<RelatedNote>,  // deterministic（wikilink + プレフィックス）
        val aiNotes: List<RelatedNote>,       // AI 推薦
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready,
        val aiErrorMessage: String? = null
    ) : RelatedNotesResult()
    data class Error(val message: String) : RelatedNotesResult()
}
```

- `findRelated()` は 2 段構成: ① wikilink 一致 + プレフィックス一致の deterministic 抽出、② AI による推薦。
- `prefixFilter()` で AI に渡す候補を「兄弟グループ（上2桁一致）→ 同カテゴリ（上1桁一致）→ プレフィックスなし」の順に最大 40 件に絞る。
- `buildDeterministicRelatedNotes()` は AI 不要で常に結果を返す。AI エラー時もこちらで fallback。
- `cleanAiTitle()` で番号・箇条書き記号・`[linked]` マーカーを除去してから `allNotes` と照合。
- タイトル正規化ロジック（`.md` 除去・小文字化・パス・エイリアス処理）は `NoteTitleNormalizer.kt` に分離。

---

### 3-4. `ai/PromptBuilder.kt`

```kotlin
fun buildSummarizePrompt(title: String, content: String): String
fun buildRelatedNotesPrompt(currentTitle: String, currentContent: String,
                             allTitles: List<String>, wikilinkTitles: Set<String>): String
fun buildQuizPrompt(title: String, content: String): String
fun buildAnnotationPrompt(title: String, content: String, summary: String?,
                           relatedTitles: List<String>, aiRecommendedTitles: List<String>,
                           wikilinkTitles: Set<String>, createdAt: String): String
```

| 関数 | 使用文字数 | 出力形式 |
|---|---|---|
| `buildSummarizePrompt` | 先頭 1200 文字 | 2〜4 文のサマリー |
| `buildRelatedNotesPrompt` | 先頭 600 文字・候補最大 80 件 | タイトルのみ 1 行 1 件（5 件） |
| `buildQuizPrompt` | 先頭 1200 文字 | `Q:/A:/B:/C:/D:/ANSWER:/EXPLANATION:` 形式 × 5 問 |
| `buildAnnotationPrompt` | 先頭 2000 文字 | `## 粒度評価`〜`## 次の問い` の 5 セクション Markdown |

---

### 3-5. `domain/SummarizeUseCase.kt`

```kotlin
sealed class SummaryResult {
    data class Success(val summary: String) : SummaryResult()
    object AiUnavailable : SummaryResult()
    object AiNeedsDownload : SummaryResult()
    data class Error(val message: String) : SummaryResult()
}
```

- `AiNeedsDownload` を返した場合、ViewModel が `downloadModel()` を自動起動。

---

### 3-6. `NoteViewModel.kt`

#### 状態定義

```kotlin
sealed class NoteState { Idle / Loading / Success(title, content) / Empty / Error(message, id) }
sealed class SummaryState { Idle / Loading / Success(summary) / Downloading(downloaded, total) / AiUnavailable / Error(message) }
sealed class RelatedNotesState {
    Idle / Loading / Error(message)
    Success(relatedNotes: List<RelatedNote>, aiNotes: List<RelatedNote>,
            aiStatus: AiRecommendationStatus, aiErrorMessage: String?)
}
sealed class QuizState { Idle / Loading / Success(cards: List<QuizCard>) / Error(message) }
sealed class AnnotationState { Idle / Loading / Success(savedUri, fileName, content) / Error(message) }

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle,
    val summaryState: SummaryState = SummaryState.Idle,
    val relatedNotesState: RelatedNotesState = RelatedNotesState.Idle,
    val quizState: QuizState = QuizState.Idle,
    val wikilinkTitles: Set<String> = emptySet(),
    val annotationState: AnnotationState = AnnotationState.Idle
)
```

- `Downloading.downloaded = -1` はダウンロード開始前（不定量）を表す。
- DL 完了後は `pendingTitle` / `pendingContent` を使って要約・関連ノートを自動再実行。
- `RelatedNotesState.Success` は AI 状態（`aiStatus`）を内包し、AI 推薦とdeterministic 推薦を同一 emit で返す。
- `fetchRelatedNotes()` は `loadRandomNote()` / `openNote()` 完了後に常時呼び出される。
- `openNote(RelatedNote)` はタップされた `RelatedNote` の URI からノート本文を読み込む。

---

### 3-7. `MainActivity.kt`

#### フォルダブル対応（Two-Pane）

```kotlin
val windowSizeClass = calculateWindowSizeClass(this)
RandomNoteScreen(
    isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
    ...
)
```

- `EXPANDED`（Pixel 10 Pro Fold 展開時）: 左ペイン（操作）+ 右ペイン（ノート + SummaryPanel）
- `COMPACT` / `MEDIUM`（折りたたみ時）: 縦積みレイアウト + SummaryPanel を下部に表示

#### SummaryPanel の状態別表示

| SummaryState | 表示内容 |
|---|---|
| `Idle` / `AiUnavailable` | 非表示 |
| `Loading` | スピナー + 「要約を生成中…」 |
| `Downloading` | LinearProgressIndicator + DL進捗（MB表示） |
| `Success` | 要約テキスト（14sp） |
| `Error` | エラーメッセージ（赤文字） |

#### RelatedNotesPanel（常時表示）

| RelatedNotesState | 表示内容 |
|---|---|
| `Idle` | 非表示 |
| `Loading` | スピナー + 「関連ノートを検索中…」 |
| `Success` | 「関連ノート」セクション（wikilink + プレフィックス一致）+ 「AI推薦」セクション（AI 未対応・エラー時はステータステキストで代替） |
| `Error` | エラーメッセージ（赤文字） |

- 展開時（EXPANDED）は左ペイン下部に表示。折りたたみ時（COMPACT/MEDIUM）はノートコンテンツの下に表示。
- `isWikilinked = true` のノートには `linked` バッジを表示。
- アイテムタップで `onOpenNote(RelatedNote)` → `viewModel.openNote()` → ノート本文が切り替わる。

---

## 4. Markdown レンダリング

`MarkdownNoteContent` は `parseMarkdownBlocks()` でブロック単位に分解し、各 Composable に委譲する。外部ライブラリ不使用。

### 4-1. 対応ブロック

| ブロック型 | Markdown 記法 | Composable |
|---|---|---|
| `Heading` | `# H1` 〜 `###### H6` | `MarkdownHeading` |
| `Paragraph` | 通常テキスト | `MarkdownParagraph` |
| `ListBlock` | `- item` / `1. item` | `MarkdownList` |
| `CodeBlock` | ` ```...``` ` | `MarkdownCodeBlock` |
| `HorizontalRule` | `---` / `***` / `___` | `MarkdownHorizontalRule` |
| `Blockquote` | `> text` | `MarkdownBlockquote` |
| `TaskListBlock` | `- [ ] / - [x]` | `MarkdownTaskList` |
| `Table` | `\| col \| col \|` | `MarkdownTable` |

### 4-2. インライン装飾（`inlineMarkdown()`）

| 記法 | 効果 |
|---|---|
| `***text***` | 太字イタリック（`**` より先に評価） |
| `**text**` | 太字 |
| `*text*` | イタリック |
| `~~text~~` | 打ち消し線 |
| `` `code` `` | インラインコード（CodePanel 背景） |
| `[[ノート名]]` | Obsidian ウィキリンク（青下線、`[[label\|alias]]` 対応） |
| `[label](url)` | 通常リンク（青下線） |

### 4-3. 見出しサイズ

| レベル | フォントサイズ | 備考 |
|---|---|---|
| H1 | 24sp | |
| H2 | 21sp | |
| H3 | 19sp | |
| H4 | 17sp | |
| H5 | 15sp | グレー文字 |
| H6 | 14sp | グレー文字・イタリック |

---

## 5. UI カラーパレット

色は `ui/theme/AppColors.kt` に `internal` 定数として定義。

| 定数名 | HEX | 用途 |
|---|---|---|
| `Indigo` | `#4D3DFF` | グラデーション始点・AI パネルアクセント・補記メモボタン |
| `Aqua` | `#00C2FF` | グラデーション中間 |
| `Coral` | `#FF6B8A` | グラデーション終点・Q&A ボタン |
| `OnVibrant` | `#FFFFFF` | テキスト・ボタンラベル |
| `OnVibrantMuted` | `#EAF7FF` | Vault ステータステキスト |
| `OnSurface` | `#202124` | ノートパネル内テキスト |
| `Panel` | `#FDFEFF` | ノートパネル背景 |
| `PanelTinted` | `#F7F3FF` | 補記メモ粒度評価パネル背景（薄い紫） |
| `CodePanel` | `#F1F4F8` | コードブロック・インラインコード背景 |
| `LinkBlue` | `#2563EB` | リンク・Obsidian ウィキリンク文字色 |
| `ButtonPrimary` | `#FF3D71` | Random Note ボタン |
| `ButtonSecondary` | `#16B8A6` | Select Vault ボタン |

---

## 6. テスト

### `NoteRepositoryTest.kt`

| テストケース | 内容 |
|---|---|
| `markdown files are recognized` | `.md` / `.MD` / `.Md` が true を返す |
| `non-markdown files are rejected` | `.png` `.txt` `.zip` `null` `""` `.md.bak` が false を返す |
| `obsidian wikilinks are normalized to note titles` | `parseMeta()` が `[[Folder/Sub Note.md\|alias]]` などを正しく正規化して抽出する |
| `note title normalization handles paths anchors aliases and md extension` | `toNormalizedObsidianTitle()` がパス・アンカー・エイリアス・.md 拡張子を除去して小文字化する |
| `annotation file titles are sanitized for saf file creation` | `sanitizeAnnotationFileTitle()` がファイル名禁止文字を `_` に置換し、空文字は `untitled` にフォールバックする |

---

## 7. 依存ライブラリ一覧

| ライブラリ | バージョン | 用途 |
|---|---|---|
| `androidx.compose:compose-bom` | 2024.09.03 | Compose 全ライブラリのバージョン管理 |
| `androidx.compose.ui:ui` | BOM 管理 | Compose UI 基盤 |
| `androidx.compose.material3:material3` | BOM 管理 | Material3 コンポーネント |
| `androidx.compose.material3:material3-window-size-class` | BOM 管理 | WindowSizeClass（フォルダブル対応） |
| `androidx.window:window` | 1.3.0 | WindowMetrics（フォルダブル対応） |
| `androidx.activity:activity-compose` | 1.9.3 | `setContent {}`, `ComponentActivity` |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | ViewModel, viewModelScope |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.7 | `collectAsStateWithLifecycle()` |
| `com.google.mlkit:genai-prompt` | 1.0.0-beta2 | Gemini Nano 4 オンデバイス推論（AICore） |
| `kotlinx-coroutines-android` | 1.9.0 | Dispatchers.Main, コルーチン |
| `junit:junit` | 4.13.2 | ユニットテスト |
| `kotlinx-coroutines-test` | 1.9.0 | コルーチンのテスト用ユーティリティ |
