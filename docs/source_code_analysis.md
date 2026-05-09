# ソースコード解析書

**プロジェクト:** Random Note  
**日付:** 2026-04-30  
**対象ブランチ:** main

---

## 1. ファイル構成

```
app/src/
├── main/
│   ├── java/com/example/newproject/
│   │   ├── MainActivity.kt       # エントリポイント・UI 制御
│   │   ├── NoteViewModel.kt      # 状態管理・ビジネスロジックの橋渡し
│   │   └── NoteRepository.kt     # ファイルアクセス層
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml # 単一画面レイアウト
│   │   ├── drawable/
│   │   │   ├── app_background.xml    # グラデーション背景
│   │   │   ├── button_primary.xml    # ピンク系ボタン（selector）
│   │   │   ├── button_secondary.xml  # ティール系ボタン（selector）
│   │   │   └── note_panel.xml        # ノート表示パネル
│   │   └── values/
│   │       ├── colors.xml
│   │       ├── strings.xml
│   │       └── themes.xml
│   └── AndroidManifest.xml
└── test/
    └── java/com/example/newproject/
        └── NoteRepositoryTest.kt # ユニットテスト
```

---

## 2. アーキテクチャ概観

```
┌─────────────────────────────────────────────────┐
│                  MainActivity                   │
│  ・レイアウト bind                               │
│  ・registerForActivityResult でVault 選択        │
│  ・StateFlow を collect して UI を更新           │
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
│  ・collectNotes()   Vault を再帰走査             │
│  ・readNoteContent() ファイル内容を読み込み      │
│  ・すべて Dispatchers.IO で実行                  │
└─────────────────────────────────────────────────┘
```

**データフロー（ランダムノート表示）**

```
ユーザーがボタンをタップ
  → MainActivity.randomNoteButton.onClick
  → viewModel.loadRandomNote(contentResolver)
  → NoteState.Loading を emit
  → (Dispatchers.IO) repository.collectNotes()
  → (Dispatchers.IO) repository.readNoteContent()
  → NoteState.Success(title, content) を emit
  → MainActivity.renderState() が UI を更新
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
    data class Error(val message: String) : NoteState()
}

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle
)
```

- `sealed class` で取りうる状態を網羅的に定義。`when` 式で漏れなく処理できる。
- `NoteUiState` は Vault 選択状態とノート読み込み状態を分離して保持。

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

#### ライフサイクルセーフな StateFlow 購読

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            renderState(state)
        }
    }
}
```

- `repeatOnLifecycle(STARTED)` でアプリがバックグラウンドに回ったとき自動で購読を停止し、フォアグラウンドに戻ったとき再開する。無駄な処理を防ぐ。

#### 状態に応じた UI 更新

```kotlin
private fun renderState(state: NoteUiState) {
    val isLoading = state.noteState is NoteState.Loading
    loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
    randomNoteButton.isEnabled = !isLoading

    when (val noteState = state.noteState) {
        is NoteState.Success -> { noteTitleText.text = noteState.title; ... }
        is NoteState.Error   -> { ...; Toast.makeText(this, noteState.message, ...).show() }
        ...
    }
}
```

- `when` の `is` チェックでスマートキャストが効き、`noteState.title` などを直接参照できる。
- ローディング中はボタンを無効化して多重タップを防止。

---

### 3-4. `activity_main.xml`

```
LinearLayout (vertical, グラデーション背景)
├── TextView  : タイトル "Random Note"
├── TextView  : Vault 選択状態
├── LinearLayout (horizontal)
│   ├── Button : Select Vault（secondary = ティール）
│   └── Button : Random Note（primary = ピンク）
├── ProgressBar : ローディング中のみ visible
└── LinearLayout (note_panel 背景 = 角丸白パネル)
    ├── TextView : ノートタイトル
    └── ScrollView
        └── TextView : ノート本文（textIsSelectable=true でコピー可）
```

---

## 4. UI カラーパレット

| 色名 | HEX | 用途 |
|------|-----|------|
| `indigo` | `#4D3DFF` | 背景グラデーション 始点、ステータスバー |
| `aqua` | `#00C2FF` | 背景グラデーション 中間 |
| `coral` | `#FF6B8A` | 背景グラデーション 終点 |
| `button_primary` | `#FF3D71` | Random Note ボタン |
| `button_secondary` | `#16B8A6` | Select Vault ボタン |
| `surface` | `#FFF8F2` | 画面背景（グラデーション下）|
| `panel` | `#FDFEFF` | ノートパネル背景 |

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
| `androidx.activity:activity-ktx` | 1.9.3 | ComponentActivity, registerForActivityResult, viewModels() |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | ViewModel, viewModelScope |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | lifecycleScope, repeatOnLifecycle |
| `kotlinx-coroutines-android` | 1.9.0 | Dispatchers.Main, コルーチン |
| `junit:junit` | 4.13.2 | ユニットテスト |
| `kotlinx-coroutines-test` | 1.9.0 | コルーチンのテスト用ユーティリティ |
