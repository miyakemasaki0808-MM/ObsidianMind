# Android 開発 第一歩 報告書

**日付:** 2026-04-30  
**プロジェクト:** Random Note (Obsidian Vault ビューア)

---

## 1. 概要

Obsidian の Vault フォルダからランダムにノートを選んで表示する Android アプリを作成した。
ゼロから Android プロジェクトを立ち上げ、Kotlin 化・モダンアーキテクチャ適用まで一気に仕上げた。

---

## 2. 達成したこと

### 2-1. アプリの機能

| 機能 | 内容 |
|------|------|
| Vault 選択 | システムのフォルダピッカーで Obsidian Vault を指定 |
| 永続化 | 選択した Vault URI を SharedPreferences に保存、次回起動時に自動復元 |
| ランダム表示 | Vault を再帰走査して `.md` ファイルを収集し、ランダムに 1 件表示 |
| スクロール | 長いノートをスクロールして読める |
| ローディング表示 | 読み込み中は ProgressBar を表示しボタンを無効化 |

### 2-2. 技術スタック

| 項目 | 採用技術 |
|------|---------|
| 言語 | Kotlin 2.0.21 |
| 最小 SDK | API 23 (Android 6.0) |
| ターゲット SDK | API 36 (Android 16) |
| ビルドシステム | Gradle 9.4.1 / AGP 9.1.1 |
| アーキテクチャ | MVVM (ViewModel + Repository) |
| 非同期処理 | Kotlin Coroutines + Dispatchers.IO |
| 状態管理 | StateFlow |
| ファイルアクセス | Storage Access Framework (SAF) |

---

## 3. 開発ステップ

```
Step 1  Android Studio でプロジェクト新規作成
Step 2  Java で MainActivity を実装（Vault 選択・ランダム表示の基本動作）
Step 3  UI デザイン（グラデーション背景・カスタムボタン・ノートパネル）
Step 4  Kotlin 化（Java → Kotlin 完全移植）
Step 5  非推奨 API 解消（startActivityForResult → ActivityResult API）
Step 6  アーキテクチャ整理（NoteRepository / NoteViewModel に分離）
Step 7  バックグラウンド I/O 化（コルーチン + Dispatchers.IO）
Step 8  ユニットテスト追加
```

---

## 4. 直面した課題と解決

### 課題 1：AGP バージョン

`compileSdk { version = release(36) }` という DSL は AGP 9.x 固有の書き方。
当初 AGP `9.2.0` で設定したが、ビルドエラーが発生したため `9.1.1` に変更して解決。

### 課題 2：Kotlin プラグインの脱落

AGP バージョン変更時に Kotlin プラグイン (`org.jetbrains.kotlin.android`) の行が消え、
`.kt` ファイルが一切コンパイルできない状態になった。
ルートの `build.gradle.kts` にプラグインを追加し直して解決。

### 課題 3：非推奨 API

`startActivityForResult` / `onActivityResult` は API 33 で非推奨。
`registerForActivityResult(ActivityResultContracts.OpenDocumentTree())` に移行し、
`Activity` の継承元も `ComponentActivity` に変更することで対応。

### 課題 4：UI スレッドブロック

元の Java 実装では Vault 走査とファイル読み込みを UI スレッドで行っており、
大きな Vault では ANR リスクがあった。
Coroutines の `withContext(Dispatchers.IO)` でバックグラウンド処理に移行して解決。

---

## 5. 学び

- **Storage Access Framework (SAF)** を使えば、スコープ外のフォルダにも安全にアクセスできる。`takePersistableUriPermission` で再起動後もアクセス権を保持できる点が重要。
- **ViewModel** は画面回転などの構成変更を跨いで状態を保持する。Activity に状態を持たせると回転のたびにリセットされるため必須。
- **StateFlow** は `LiveData` の Kotlin 版にあたり、コルーチンと自然に統合できる。`repeatOnLifecycle(STARTED)` と組み合わせることでライフサイクルセーフな UI 更新が実現できる。
- **Gradle のプラグイン管理** はルートの `build.gradle.kts` で宣言し、各モジュールで `apply` する二段構成を理解することが大切。

---

## 6. 次のステップ候補

- [x] Markdown のレンダリング（見出し・太字・イタリック・コードブロック・引用・テーブル・タスクリスト・Obsidian リンク等）
- [x] Compose UI への移行
- [x] Gemini Nano 4 によるオンデバイスノート要約（ML Kit GenAI Prompt API）
- [x] Pixel 10 Pro Fold 展開時の Two-Pane レイアウト対応
- [ ] 最後に読んだノートの履歴表示
- [ ] ウィジェット対応（ホーム画面からワンタップでランダム表示）
- [ ] ダークモード対応
- [ ] ネストリスト対応
- [ ] 画像レンダリング対応（`![alt](path)`）
- [x] 関連ノート表示（wikilink ベース + AI 推薦の 2 段構成、タップで本文切り替え）
- [x] Q&A フラッシュカード生成（Gemini Nano によるオンデバイス多肢選択問題）
- [x] 補記メモ機能（AI が粒度評価・補記案を生成し `_AI補記/` フォルダに保存）
- [ ] ノート一覧画面の実装
