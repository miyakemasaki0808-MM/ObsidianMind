# 変更管理表 — セクション単位AIチャット機能

**日付:** 2026-07-17
**プロジェクト:** Obsidian Mind
**対象ブランチ:** feature/Additional_Function
**変更区分:** 新機能追加（AIチャット・浮遊吹き出し）
**関連報告書:** [section_ai_chat_report.md](section_ai_chat_report.md)

---

## 1. 変更サマリ

| 項目 | 内容 |
|------|------|
| 目的 | ノートの「今見ている部分」に対する要約・質問をローカルLLMで実現 |
| 影響範囲 | UI層（Compose）・ViewModel層・プロンプト層。データ層（NoteRepository）は無変更 |
| 新規依存 | なし（`LazyColumn`/`ModalBottomSheet`はmaterial3コア） |
| 後方互換 | `MarkdownNoteContent`は`listState`をデフォルト引数化し既存呼び出しに影響なし |
| リスク | 中（本文表示を`LazyColumn`化したためテキスト選択挙動の回帰確認が必要） |

---

## 2. ファイル別 変更管理表

| # | ファイル | 区分 | 変更前 | 変更後 | 理由 |
|---|----------|------|--------|--------|------|
| 1 | `ui/markdown/NoteSections.kt` | 新規 | — | `NoteSection`／`NoteSectionModel`／`buildNoteSectionModel`／`blocksToMarkdown` | 見出し単位でノート本文を切り出す純粋ロジックの追加 |
| 2 | `ui/markdown/MarkdownRenderer.kt` | 改修 | `Column(verticalScroll)`で本文描画 | `LazyColumn`＋`listState`（デフォルト引数）に変更 | 可視ブロックindexを取得し対象セクション判定に使うため |
| 3 | `ai/PromptBuilder.kt` | 改修 | 要約／関連ノート／クイズ／補記の4種プロンプト | セクション用の要約／質問候補／チャットの3種を追加 | セクション単位でAI呼び出しを行うため |
| 4 | `NoteViewModel.kt` | 改修 | `NoteUiState`に補記関連の状態のみ | `SectionChatState`／`openSection`／`sendSectionMessage`／`closeSectionChat`を追加 | セクションチャットの状態管理とAI呼び出し制御 |
| 5 | `ui/SectionChatSheet.kt` | 新規 | — | 要約（上）／質問候補タップ（下）のボトムシートUI | 自由記述を廃した簡潔なチャットUI |
| 6 | `ui/RandomNoteScreen.kt` | 改修 | 本文パネルのみ表示 | 浮遊FAB（ドラッグ可・半透明ガラス調）＋シート表示を追加 | セクションチャットの入口UI |
| 7 | `MainActivity.kt` | 改修 | ノートタブに`onSelectVault`/`onRandomNote`のみ配線 | `onOpenSection`／`onSuggestionTap`／`onCloseSectionChat`を追加配線 | ViewModelとUIの接続 |

---

## 3. 主要な技術変更点

| # | 変更点 | 変更前 | 変更後 |
|---|--------|--------|--------|
| A | 本文の描画方式 | `Column` + `verticalScroll` | `LazyColumn`（`listState`公開） |
| B | 対象セクション判定 | なし | `firstVisibleItemIndex`から直近見出しを解決（見出し無しはノート全体にフォールバック） |
| C | チャット入口 | なし | 浮遊FAB（ドラッグ移動・半透明ガラス調・タップで直接シート表示） |
| D | チャットUI構成 | なし | 自由記述欄なし。要約（上）／質問候補タップ（下）の縦構成 |
| E | AI呼び出し範囲 | ノート全文（要約・関連ノート等） | セクション本文のみ（範囲外は「記載なし」と回答） |
| F | 会話状態の永続性 | — | シートを開いている間のみ保持（揮発） |

---

## 4. デザイン反復による変更履歴（同一機能内）

| 版 | 内容 | 変更理由 |
|----|------|----------|
| v1 | 見出しごとの個別チップ表示 | ユーザーイメージ（浮遊ボタン）と不一致のため破棄 |
| v2 | 画面右下の浮遊吹き出し＋ドラッグ移動 | 採用・以降の土台 |
| v3 | タップ後にメニュー（要約／質問）を表示 | 「自由記述欄が不要」「メニューを挟まず直接開きたい」の要望で撤去 |
| v4 | シートを要約（上）／質問候補（下）の縦構成に変更、余白拡大 | 「場所が狭い」との指摘対応 |
| v5 | FABの配色を白基調→Indigo/Coralの半透明グラデーションに変更 | 「白はノートと同化するため避けたい」との指摘対応 |
| v6（最終） | シートタイトルから「（配下を含む）」の文言を削除 | 表示の簡潔化 |

---

## 5. 無変更（影響なし）ファイル

| ファイル | 備考 |
|----------|------|
| `NoteRepository.kt` | データアクセス層は本機能と無関係 |
| `AndroidManifest.xml` | 権限・設定の変更なし |
| `build.gradle.kts` | 新規依存なし |
| `QuizScreen.kt` / `AnnotationResultScreen.kt` / `OptionsScreen.kt` / `AnnotationManagerScreen.kt` | 本機能のスコープ外 |

---

## 6. 検証記録

| 項目 | 状態 | 備考 |
|------|------|------|
| 静的レビュー | 完了 | クロスファイル参照解決・未使用import除去・削除シンボル残存なしを確認 |
| ビルド（assembleDebug） | 未実施 | 開発環境にJDKが無く実行不可。Android Studio側での実施が必要 |
| 実機/エミュレータ動作確認 | 未実施 | 報告書「5. 検証状況」の推奨確認項目を参照 |
| テキスト選択の回帰確認 | 要確認 | `LazyColumn`化に伴う`SelectionContainer`の挙動変化の可能性あり |

---

## 7. ロールバック手順

本変更は7ファイル（新規2・改修5）に限定される。ロールバックする場合は、対象ファイルを本コミット以前のリビジョンへ戻すことで原状復帰可能。データ形式・永続化・依存関係への変更がないため、追加のマイグレーションは不要。
