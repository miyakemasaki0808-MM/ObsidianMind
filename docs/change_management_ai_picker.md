# 変更管理表 — AIピッカー（さがすタブ）

**日付:** 2026-07-18
**プロジェクト:** Obsidian Mind
**対象ブランチ:** feature/Additoinal_Function
**変更区分:** 新機能追加（フォルダスコープ型ノートピッカー／キーワード＋ランダム）
**関連報告書:** [ai_picker_report.md](ai_picker_report.md)

---

## 1. 変更サマリ

| 項目 | 内容 |
|------|------|
| 目的 | フォルダを選んで「言葉でさがす（AI）」か「ランダムに引く」新しいノート選択導線 |
| 影響範囲 | データ層（NoteRepository）・UseCase層（新規）・プロンプト層・ViewModel層・UI層・ナビゲーション |
| 新規依存 | なし |
| 後方互換 | 既存機能は無改修。追加はいずれも新規シンボル／新規フィールドで、既存フローに影響なし |
| リスク | 低〜中（新規タブの追加が中心。既存 `collectNotes`/`openNote` は変更せず流用） |

---

## 2. ファイル別 変更管理表

| # | ファイル | 区分 | 変更前 | 変更後 | 理由 |
|---|----------|------|--------|--------|------|
| 1 | `NoteRepository.kt` | 改修 | `collectNotes`（全md・フラット）のみ | `NoteFolder` 型／`listTopLevelFolders`／`collectNotesInScope` を追加 | フォルダ列挙とスコープ収集のため（既存 `collectNotes` は無変更） |
| 2 | `domain/SearchPickerUseCase.kt` | 新規 | — | `pick(query, scopeNotes)`／40超のbigram再現率カット／AI選定／フォールバック | クエリ起点の候補選定（`RelatedNotesUseCase` のフォーク） |
| 3 | `ai/PromptBuilder.kt` | 改修 | 要約／関連／クイズ／補記／セクション系 | `buildPickerPrompt(query, candidateTitles)` を追加 | ピッカー用の「タイトル3件のみ」出力プロンプト |
| 4 | `NoteViewModel.kt` | 改修 | さがす関連の状態なし | `SearchState`／`folders`・`selectedFolder`・`searchState`／`loadFolders`・`selectSearchFolder`・`searchByKeyword`・`pickRandomInScope` を追加 | さがすタブの状態管理とAI/ランダム呼び出し |
| 5 | `ui/SearchScreen.kt` | 新規 | — | `SearchTab`（フォルダchips／入力欄／2ボタン／結果パネル） | さがすタブのUI |
| 6 | `ui/AppScaffold.kt` | 改修 | 4タブ（ノート/関連/AI/オプション） | `Search("search","さがす","🔎")` を追加し5タブに | 新タブのナビゲーション登録 |
| 7 | `MainActivity.kt` | 改修 | `composable("search")` なし | ルート追加・`loadFolders` 起動・`SearchTab` 配線・結果タップで `openNote`→note遷移 | ViewModelとUIの接続 |

---

## 3. 主要な技術変更点

| # | 変更点 | 変更前 | 変更後 |
|---|--------|--------|--------|
| A | 探索範囲の指定 | ランダム（Vault全体） | フォルダ選択（第一階層／ルート直下）を人間が指定 |
| B | フォルダ情報 | `collectNotes` が破棄 | `listTopLevelFolders`／`collectNotesInScope` で保持・活用 |
| C | 候補の絞り込み | プレフィックス（現在ノート起点） | 40超のときのみ bigram 再現率カット（クエリ起点） |
| D | AI出力の形式 | 関連ノート: タイトルのみ | ピッカー: タイトルのみ（選定理由なし／既存パーサ流用） |
| E | ランダム | ノート1件を全体から | スコープ内から3件（AI不使用・`shuffled().take(3)`） |
| F | AI非対応時 | — | キーワードカット結果で3件を返し、注記を表示 |

---

## 4. スコープ確定（設計セッションの結論）

| 版/論点 | 決定 | 理由 |
|---------|------|------|
| モード数 | キーワード＋ランダムの2つ | 入力が面倒な場面用にランダムも欲しいとの要望 |
| 件数 | 両モードとも3件 | 1件は既存ランダム機能と重複するため |
| フォルダ階層 | 第一階層のみ（案A） | 最小コスト優先。中身は再帰収集で拾えるため後方互換で拡張可 |
| `_AI補記` | スコープに出す | 除外ロジックを書かず実装を軽くする |
| 選定理由 | 表示しない | 既存プロンプト/パーサをそのまま流用でき軽量化 |

---

## 5. 無変更（影響なし）ファイル

| ファイル | 備考 |
|----------|------|
| `domain/RelatedNotesUseCase.kt` | フォーク元だが本体は無変更 |
| `AndroidManifest.xml` | 権限・設定の変更なし |
| `build.gradle.kts` | 新規依存なし |
| `QuizScreen.kt` / `AnnotationResultScreen.kt` / `OptionsScreen.kt` / `SectionChatSheet.kt` | 本機能のスコープ外 |

---

## 6. 検証記録

| 項目 | 状態 | 備考 |
|------|------|------|
| 静的レビュー | 完了 | クロスファイル参照解決・シンボル存在・Material3(1.3.0)API整合を確認 |
| ビルド（assembleDebug） | 未実施 | 開発環境にJDKが無く実行不可。Android Studio側での実施が必要 |
| 実機/エミュレータ動作確認 | 未実施 | 報告書「5. 推奨確認項目」を参照 |

---

## 7. ロールバック手順

本変更は7ファイル（新規2・改修5）に限定される。新規2ファイル（`SearchScreen.kt`／`SearchPickerUseCase.kt`）の削除と、改修5ファイルの当該追加分の除去（`AppDestination` の `Search` 行、`NavHost` の `composable("search")`、`NoteRepository`/`NoteViewModel`/`PromptBuilder` の追加メンバ）で原状復帰可能。データ形式・永続化・依存関係への変更がないため、追加のマイグレーションは不要。
