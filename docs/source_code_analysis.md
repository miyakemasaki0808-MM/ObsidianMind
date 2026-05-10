# ソースコード解析書

**プロジェクト:** Random Note  
**日付:** 2026-05-10  
**対象ブランチ:** feature/jetpack-compose-ui

---

## 1. ファイル構成

```
app/src/
├── main/
│   ├── java/com/example/newproject/
│   │   ├── MainActivity.kt       # エントリポイント・Compose UI
│   │   ├── NoteViewModel.kt      # 状態管理・ビジネスロジックの橋渡し
│   │   └── NoteRepository.kt     # ファイルアクセス層
│   ├── res/
│   │   ├── drawable/             # ※Compose 移行後は未使用（削除可）
│   │   │   ├── app_background.xml
│   │   │   ├── button_primary.xml
│   │   │   ├── button_secondary.xml
│   │   │   └── note_panel.xml
│   │   └── values/
│   │       ├── colors.xml        # ※色は MainActivity.kt に定数として定義済み
│   │       ├── strings.xml       # 文字列リソース（Compose からも参照）
│   │       └── themes.xml
│   └── AndroidManifest.xml
└── test/
    └── java/com/example/newproject/
        └── NoteRepositoryTest.kt # ユニットテスト
```

> `activity_main.xml` は Jetpack Compose 移行時に削除済み。

---

## 2. アーキテクチャ概観

```
┌─────────────────────────────────────────────────┐
│                  MainActivity                   │
│  ・registerForActivityResult で Vault 選択       │
│  ・setContent { } で Compose UI を起動           │
│  ・collectAsStateWithLifecycle() で状態購読      │
└──────────────────┬──────────────────────────────┘
                   │ by viewModels()
                   ▼
┌─────────────────────────────────────────────────┐
│                 NoteViewModel                   │
│  ・vaultUri を保持（構成変更を跨いで生存）        │
│  ・StateFlow<NoteUiState> で状態を公開           │
│  ・viewModelScope でコルーチンを起動             │
└──────────────────┬──────────────────────────────┘
                   │ suspend fun
                   ▼
┌─────────────────────────────────────────────────┐
│                NoteRepository                   │
│  ・collectNotes()    Vault を再帰走査            │
│  ・readNoteContent() ファイル内容を読み込み      │
│  ・すべて Dispatchers.IO で実行                  │
└─────────────────────────────────────────────────┘
```

**データフロー（ランダムノート表示）**

```
ユーザーがボタンをタップ
  → RandomNoteScreen の onRandomNote コールバック
  → viewModel.loadRandomNote(contentResolver)
  → NoteState.Loading を emit → Compose が自動再コンポーズ
  → (Dispatchers.IO) repository.collectNotes()
  → (Dispatchers.IO) repository.readNoteContent()
  → NoteState.Success(title, content) を emit → Compose が自動再コンポーズ
```

---

## 3. 各ファイル詳細解析

### 3-1. `NoteRepository.kt`

```kotlin
data class NoteFile(val name: String, val uri: Uri)

internal fun isMarkdownFile(name: String?): Boolean =
    name?.lowercase()?.endsWith(".md") == true
```

- `NoteFile` はファイル名と SAF の URI を束ねるシンプルなデータクラス。
- `isMarkdownFile` は `internal` 関数として切り出し、ユニットテストから直接参照できる。

```kotlin
suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
    withContext(Dispatchers.IO) {
        buildList {
            collectRecursive(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri), this)
        }
    }
```

- `withContext(Dispatchers.IO)` で呼び出し元のスレッドに関係なく I/O スレッドで実行。
- `buildList { }` で可変リストの露出を避けつつ効率よく構築。
- `DocumentsContract.getTreeDocumentId(vaultUri)` でツリーのルート document ID を取得し、再帰の起点にする。

```kotlin
private fun collectRecursive(...) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            when {
                mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> 再帰
                isMarkdownFile(name) -> result.add(NoteFile(name, childUri))
            }
        }
    }
}
```

- SAF の `buildChildDocumentsUriUsingTree` でディレクトリの子一覧 URI を生成。
- Cursor を `use { }` で確実に閉じる（Java の try-with-resources 相当）。
- サブフォルダは再帰で深掘り、`.md` ファイルだけ収集。

---

### 3-2. `NoteViewModel.kt`

#### 状態定義

```kotlin
sealed class NoteState {
    object Idle : NoteState()
    object Loading : NoteState()
    data class Success(val title: String, val content: String) : NoteState()
    object Empty : NoteState()
    data class Error(val message: String, val id: Long = System.currentTimeMillis()) : NoteState()
}

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle
)
```

- `sealed class` で取りうる状態を網羅的に定義。`when` 式で漏れなく処理できる。
- `NoteUiState` は Vault 選択状態とノート読み込み状態を分離して保持。
- `Error` に `id` を持たせることで、画面回転後に同じエラーの Toast が再表示されるのを防ぐ。

#### Vault の永続化

```kotlin
class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
```

- `AndroidViewModel` を継承することで `Application` コンテキストを安全に保持。
- Activity コンテキストを ViewModel に渡すとメモリリークになるため、`Application` を使う。

#### ランダムノート読み込み

```kotlin
fun loadRandomNote(contentResolver: ContentResolver) {
    val uri = vaultUri ?: return
    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(noteState = NoteState.Loading)
        _uiState.value = try {
            val notes = repository.collectNotes(contentResolver, uri)
            if (notes.isEmpty()) {
                _uiState.value.copy(noteState = NoteState.Empty)
            } else {
                val note = notes.random()
                val content = repository.readNoteContent(contentResolver, note.uri)
                _uiState.value.copy(noteState = NoteState.Success(note.name, content))
            }
        } catch (e: Exception) {
            _uiState.value.copy(noteState = NoteState.Error(e.message ?: "Unknown error"))
        }
    }
}
```

- `viewModelScope` は ViewModel が破棄されると自動キャンセルされる。画面回転中に処理が中断しない。
- `copy()` で既存の状態を保ちつつ `noteState` だけ差し替えるイミュータブルな更新。
- `ContentResolver` は Activity から受け取り、ViewModel 内には保持しない（Activity 参照の漏洩を防ぐ）。

---

### 3-3. `MainActivity.kt`

#### ActivityResult API

```kotlin
private val openVault = registerForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { uri ->
    uri ?: return@registerForActivityResult
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    contentResolver.takePersistableUriPermission(uri, flags)
    viewModel.saveVault(uri)
    viewModel.loadRandomNote(contentResolver)
}
```

- `registerForActivityResult` はクラス初期化時に登録し、`onCreate` 前から安全に保持できる。
- `takePersistableUriPermission` でアプリ再起動後もフォルダアクセス権を維持。
- `uri ?: return@registerForActivityResult` でユーザーがキャンセルした場合を安全に処理。

#### Compose UI の起動

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        RandomNoteScreen(
            uiState = uiState,
            onSelectVault = { openVault.launch(null) },
            onRandomNote = { ... }
        )
    }
}
```

- `setContent { }` で Compose ツリーを起動。`setContentView` は不要。
- `collectAsStateWithLifecycle()` でライフサイクルを考慮した状態購読。バックグラウンド時は自動停止。
- UI ロジックをコールバックとして渡すことで `RandomNoteScreen` を純粋な Composable に保つ。

#### エラー Toast の制御

```kotlin
LaunchedEffect((uiState.noteState as? NoteState.Error)?.id) {
    if (uiState.noteState is NoteState.Error) {
        Toast.makeText(context, uiState.noteState.message, Toast.LENGTH_SHORT).show()
    }
}
```

- `LaunchedEffect` のキーを `error.id` にすることで、同じエラーに対して Toast は一度だけ表示される。
- 画面回転しても `id` が変わらないため再表示されない。

#### Compose レイアウト構造

```
Column (fillMaxSize, グラデーション背景)
├── Text        : タイトル "Random Note"
├── Text        : Vault 選択状態
├── Row
│   ├── Button  : Select Vault（ButtonSecondary = ティール）
│   └── Button  : Random Note（ButtonPrimary = ピンク、Loading 中は disabled）
├── CircularProgressIndicator : Loading 中のみ表示
└── Surface (weight(1f), 角丸白パネル)
    └── Column
        ├── Text : ノートタイトル
        └── Text : ノート本文（verticalScroll）
```

- `weight(1f)` で Column の残り領域をすべて占有。`fillMaxSize` では上部の要素を押し出してしまうため不適切。

---

## 4. UI カラーパレット

色は `MainActivity.kt` にファイルプライベートな定数として定義。`colors.xml` は現在参照されていない。

| 定数名 | HEX | 用途 |
|---|---|---|
| `Indigo` | `#4D3DFF` | グラデーション始点 |
| `Aqua` | `#00C2FF` | グラデーション中間 |
| `Coral` | `#FF6B8A` | グラデーション終点 |
| `OnVibrant` | `#FFFFFF` | テキスト・ボタンラベル |
| `OnVibrantMuted` | `#EAF7FF` | Vault ステータステキスト |
| `OnSurface` | `#202124` | ノートパネル内テキスト |
| `Panel` | `#FDFEFF` | ノートパネル背景 |
| `ButtonPrimary` | `#FF3D71` | Random Note ボタン |
| `ButtonSecondary` | `#16B8A6` | Select Vault ボタン |

---

## 5. テスト

### `NoteRepositoryTest.kt`

| テストケース | 内容 |
|---|---|
| `markdown files are recognized` | `.md` / `.MD` / `.Md` が true を返す |
| `non-markdown files are rejected` | `.png` `.txt` `.zip` `null` `""` `.md.bak` が false を返す |

- Android 依存なし（JVM テスト）のため高速に実行できる。
- `isMarkdownFile` を `internal` 関数として公開しているのはテスト容易性のため。

---

## 6. 依存ライブラリ一覧

| ライブラリ | バージョン | 用途 |
|---|---|---|
| `androidx.compose:compose-bom` | 2024.09.03 | Compose 全ライブラリのバージョン管理 |
| `androidx.compose.ui:ui` | BOM 管理 | Compose UI 基盤 |
| `androidx.compose.material3:material3` | BOM 管理 | Material3 コンポーネント（Button, Surface 等） |
| `androidx.activity:activity-compose` | 1.9.3 | `setContent {}`, `ComponentActivity`, `viewModels()` |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | ViewModel, viewModelScope |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.7 | `collectAsStateWithLifecycle()` |
| `kotlinx-coroutines-android` | 1.9.0 | Dispatchers.Main, コルーチン |
| `junit:junit` | 4.13.2 | ユニットテスト |
| `kotlinx-coroutines-test` | 1.9.0 | コルーチンのテスト用ユーティリティ |
