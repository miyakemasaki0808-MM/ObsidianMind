# 変更管理表 — UI再設計（Plan C: タブ・ナビゲーション）

**日付:** 2026-07-16
**プロジェクト:** Obsidian Mind
**対象ブランチ:** main
**変更区分:** UI構成の再設計＋不具合修正
**関連報告書:** [plan_c_tab_navigation_report.md](plan_c_tab_navigation_report.md)

---

## 1. 変更サマリ

| 項目 | 内容 |
|------|------|
| 目的 | Foldでのボタン切れ／ダブルタップ全画面不発の解消、拡張性を見越したタブ構成化 |
| 影響範囲 | UI層（Compose）のみ。状態管理・ドメイン・データ層は変更なし |
| 新規依存 | なし |
| 後方互換 | ナビ構造は刷新（`random_note` → `note`/`related`/`ai`）。永続データ・保存形式は不変 |
| リスク | 低（ViewModel・Repository・AI連携は無改修） |

---

## 2. ファイル別 変更管理表

| # | ファイル | 区分 | 変更前 | 変更後 | 理由 |
|---|----------|------|--------|--------|------|
| 1 | `ui/AppScaffold.kt` | 新規 | — | `AppDestination` enum＋`AppScaffold`（下部バー↔サイドレールのアダプティブ殻） | タブUIの導入とFold幅での自動切替 |
| 2 | `ui/RandomNoteScreen.kt` | 改修 | 単一 `RandomNoteScreen`（Expanded/縦の二分岐＋ダブルタップ判定） | `NoteReaderTab`／`RelatedTab`／`AiTab` の3タブ画面。共有パネル（NoteContentPanel/SummaryPanel/RelatedNotesPanel等）は流用 | 平置き廃止・スクロール対応・ジェスチャ廃止 |
| 3 | `MainActivity.kt` | 改修 | `NavHost(startDestination="random_note")` に単一画面＋quiz/annotation | `AppScaffold` で包み、`note`/`related`/`ai`/`quiz`/`annotation` の5ルート構成。コールバックを各タブへ配線 | タブ・ナビゲーションへの移行 |

---

## 3. 主要な技術変更点

| # | 変更点 | 変更前 | 変更後 |
|---|--------|--------|--------|
| A | ナビゲーション | 単一画面＋push 2画面 | 3タブ（note/related/ai）＋push 2画面（quiz/annotation） |
| B | 画面幅対応 | Expanded時のみ2ペイン、他は縦積み（いずれも非スクロール） | Expanded=サイドレール／他=下部バー。各タブは `verticalScroll` 対応 |
| C | 全画面表示 | 本文のダブルタップで開閉（`awaitEachGesture`／Initialパス） | ⛶ ボタンで開き ✕ ボタンで閉じる（ジェスチャ廃止） |
| D | タブ状態保持 | — | `saveState`/`restoreState`/`launchSingleTop` で往復時に保持 |
| E | アイコン | （なし） | 絵文字（📄🔗✨／⛶✕）。`material-icons` 依存を回避 |

---

## 4. 無変更（影響なし）ファイル

| ファイル | 備考 |
|----------|------|
| `NoteViewModel.kt` / `NoteUiState` | 状態は集約済みのため改修不要 |
| `MarkdownRenderer.kt` / `MarkdownParser.kt` | 本文レンダリングは流用 |
| `QuizScreen.kt` / `AnnotationResultScreen.kt` | 全画面ルートとして従来どおり |
| `AndroidManifest.xml` | Fold姿勢変更時は既定のActivity再生成でViewModelが状態保持するため configChanges 追加不要 |
| `build.gradle.kts` | 新規依存なし |

---

## 5. 解決した不具合

| 不具合ID | 内容 | 対応ファイル | 対応内容 |
|----------|------|--------------|----------|
| BUG-FOLD-01 | Pixel Foldで操作ボタンが切れて見えない | `AppScaffold.kt` / `RandomNoteScreen.kt` | 単機能タブ化＋スクロール対応＋幅別ナビ切替 |
| BUG-GESTURE-02 | ノートのダブルタップで全画面が開かない | `RandomNoteScreen.kt` | ダブルタップ判定を削除し⛶/✕ボタンへ置換 |

---

## 6. 検証記録

| 項目 | 状態 | 備考 |
|------|------|------|
| 静的レビュー | 完了 | 旧参照・旧ジェスチャAPI残骸なし、import/ルート整合 |
| ビルド（assembleDebug） | 成功 | Android Studio 上で確認済み |
| 実機/エミュレータ動作確認 | 実施推奨 | 報告書「7. 検証状況」の動作確認項目を参照 |

---

## 7. ロールバック手順

本変更はUI層に限定されるため、対象3ファイル（`AppScaffold.kt` の削除、`RandomNoteScreen.kt` と `MainActivity.kt` の復元）を変更前リビジョンへ戻すだけで原状復帰可能。データ形式・永続化・依存関係に変更がないため、追加のマイグレーションは不要。
