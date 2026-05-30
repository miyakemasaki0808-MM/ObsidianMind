# ソースコード解析書

**プロジェクト:** Obsidian Mind  
**日付:** 2026-05-30  
**対象ブランチ:** feature/Top-5_function

---

## 1. ファイル構成

```
app/src/
├── main/
│   ├── java/com/example/newproject/
│   │   ├── MainActivity.kt           # エントリポイント・Compose UI・Markdown 表示
│   │   ├── NoteViewModel.kt          # 状態管理・ビジネスロジックの橋渡し
│   │   ├── NoteRepository.kt         # ファイルアクセス層・frontmatter/wikilink 解析
│   │   ├── ai/
│   │   │   ├── AICoreClient.kt       # Gemini Nano 4 接続ラッパー（AiClient インターフェース）
│   │   │   └── PromptBuilder.kt      # 要約・関連ノートプロンプト構築
│   │   └── domain/
│   │       ├── SummarizeUseCase.kt   # 要約ユースケース
│   │       └── RelatedNotesUseCase.kt # 関連ノート Top-5 ユースケース
│   ├── res/
│   │   └── values/
│   │       ├── colors.xml            # indigo のみ（themes.xml から参照）
│   │       ├── strings.xml           # 文字列リソース
│   │       └── themes.xml            # Theme.ObsidianMind
│   └── AndroidManifest.xml
└── test/
    └── java/com/example/newproject/
        └── NoteRepositoryTest.kt     # ユニットテスト
```

> `activity_main.xml` は Jetpack Compose 移行時に削除済み。

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

**データフロー（ノート表示 + 要約）**

```
ユーザーがボタンをタップ
  → viewModel.loadRandomNote(contentResolver)
  → NoteState.Loading を emit
  → repository.collectNotes() → repository.readNoteContent()
  → NoteState.Success(title, content) を emit
  → fetchSummary(title, content) を起動
      → SummarizeUseCase.summarize()
          → AICoreClient.checkAvailability()
              AVAILABLE    → generateContent(prompt) → SummaryState.Success(summary)
              DOWNLOADABLE → startModelDownload()
                               → DownloadProgress emit → SummaryState.Downloading(n, total)
                               → DownloadCompleted    → fetchSummary() を再実行
              UNAVAILABLE  → SummaryState.AiUnavailable（パネル非表示）

  ※展開時（isExpanded=true）のみ fetchRelatedNotes() も並行起動
  → RelatedNotesUseCase.findRelated()
      → AICoreClient.generate(buildRelatedNotesPrompt())
      → レスポンスを行分割・正規化 → allNotes と照合 → Top-5 抽出
      → RelatedNotesState.Success(notes) emit
  → 左ペイン下部の RelatedNotesPanel に表示
  → アイテムタップ → viewModel.openNote() → 右ペインの本文が切り替わる
```

---

## 3. 各ファイル詳細解析

### 3-1. `NoteRepository.kt`

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

sealed class RelatedNotesResult {
    data class Success(val notes: List<RelatedNote>) : RelatedNotesResult()
    object AiUnavailable : RelatedNotesResult()
    object AiNeedsDownload : RelatedNotesResult()
    data class Error(val message: String) : RelatedNotesResult()
}
```

- `findRelated()` でAIに Top-5 を選出させ、レスポンスを `allNotes` と照合して `RelatedNote` に変換。
- タイトルの正規化（`.md` 除去・小文字化）により、AIの出力ゆれに対応。
- `cleanAiTitle()` で番号・箇条書き記号・`[linked]` マーカーを除去してから照合。
- `wikilinkTitles` に含まれるノートは `isWikilinked = true` としてバッジ表示に利用。

---

### 3-4. `ai/PromptBuilder.kt`

```kotlin
fun buildSummarizePrompt(title: String, content: String): String
fun buildRelatedNotesPrompt(
    currentTitle: String,
    currentContent: String,
    allTitles: List<String>,
    wikilinkTitles: Set<String>
): String
```

- `buildSummarizePrompt`: 先頭 1200 文字を使用。ノートと同言語で 2〜4 文。
- `buildRelatedNotesPrompt`: 先頭 600 文字を使用。候補タイトルは最大 80 件。`wikilinkTitles` に含まれる候補に `[linked]` を付与してブースト。5件のタイトルのみを1行1件で返すよう指示。

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
sealed class SummaryState {
    object Idle : SummaryState()
    object Loading : SummaryState()
    data class Success(val summary: String) : SummaryState()
    data class Downloading(val downloaded: Long, val total: Long) : SummaryState()
    object AiUnavailable : SummaryState()
    data class Error(val message: String) : SummaryState()
}

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle,
    val summaryState: SummaryState = SummaryState.Idle
)
```

- `Downloading.downloaded = -1` はダウンロード開始前（不定量）を表す。
- DL完了後は `pendingTitle` / `pendingContent` を使って要約を自動再実行。

```kotlin
sealed class RelatedNotesState {
    object Idle : RelatedNotesState()
    object Loading : RelatedNotesState()
    data class Success(val notes: List<RelatedNote>) : RelatedNotesState()
    object AiUnavailable : RelatedNotesState()
    object AiNeedsDownload : RelatedNotesState()
    data class Error(val message: String) : RelatedNotesState()
}
```

- `fetchRelatedNotes()` は `loadRandomNote()` / `openNote()` 完了後に呼び出される。
- `openNote()` はタップされた `RelatedNote` の URI からノート本文を読み込み、右ペインを切り替える。

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

#### RelatedNotesPanel（展開時のみ・左ペイン下部）

| RelatedNotesState | 表示内容 |
|---|---|
| `Idle` / `AiUnavailable` / `AiNeedsDownload` | 非表示 |
| `Loading` | スピナー + 「関連ノートを検索中…」 |
| `Success` | タイトルリスト最大5件。`isWikilinked=true` なら `linked` バッジ表示 |
| `Error` | エラーメッセージ（赤文字） |

- アイテムタップで `onOpenNote(RelatedNote)` → `viewModel.openNote()` → 右ペインの本文が切り替わる
- **折りたたみ時（COMPACT/MEDIUM）は表示しない**

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

色は `MainActivity.kt` にファイルプライベートな定数として定義。

| 定数名 | HEX | 用途 |
|---|---|---|
| `Indigo` | `#4D3DFF` | グラデーション始点・AI パネルアクセント |
| `Aqua` | `#00C2FF` | グラデーション中間 |
| `Coral` | `#FF6B8A` | グラデーション終点 |
| `OnVibrant` | `#FFFFFF` | テキスト・ボタンラベル |
| `OnVibrantMuted` | `#EAF7FF` | Vault ステータステキスト |
| `OnSurface` | `#202124` | ノートパネル内テキスト |
| `Panel` | `#FDFEFF` | ノートパネル背景 |
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
