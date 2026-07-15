# UI再設計（タブ・ナビゲーション）実装報告書

**日付:** 2026-07-16
**プロジェクト:** Obsidian Mind
**対象ブランチ:** main
**採用プラン:** Plan C（タブ・ナビゲーション）

---

## 1. 背景・目的

Google Pixel Fold で操作ボタンが画面外に切れて見えなくなる不具合と、ノート本文のダブルタップで全画面表示が開かなくなる不具合が発生していた。いずれも「操作・本文・AI補助を1画面に平置きで詰め込む」従来構成に起因する。

今後の機能拡張を見越し、画面を機能単位のタブに分割する **Plan C** を採用。各画面を単機能・スクロール前提に再構成することで、詰め込みとクリップを構造的に排除し、Fold展開時は同じタブ構成をサイドレールへ自動で切り替える。

---

## 2. 原因分析

| # | 不具合 | 根本原因 |
|---|--------|---------|
| 1 | Foldでボタンが切れる | `RandomNoteScreen` のどのレイアウト分岐も `verticalScroll` を持たず、タイトル＋Vault＋最大4ボタン＋要約＋関連を1画面に平置き。画面高を超えると末尾がクリップされる。 |
| 2 | ダブルタップで全画面が開かない | コミット `4f32a57` で標準の `detectTapGestures` を `Initial` パスの自作 `awaitEachGesture` 判定へ置換。本文の `verticalScroll` が指の微動を先取り consume するため、1タップ目が「キャンセル」扱いになり2打目に到達しない。 |

---

## 3. 実装方針

- **新規依存の追加なし。** `material3-window-size-class` は導入済み、`NavigationBar`/`NavigationRail` は material3 コアに含まれる。タブアイコンは絵文字を使用し `material-icons` 依存も回避。
- **`NoteViewModel` / `NoteUiState` は無変更。** 状態は既に集約済みで、各タブは同一 `uiState` の別スライスを表示するだけ。
- **UIは全面 Jetpack Compose。** View システム（XMLレイアウト・Fragment）は不使用。

---

## 4. 実装内容

### 4-1. 画面構成

3つのトップレベルタブが同一の `NoteViewModel` を共有する。

| タブ | route | 内容 |
|------|-------|------|
| ノート 📄 | `note` | Vault状態＋「Vaultを選択／別のノート」操作＋本文パネル＋全画面（⛶）ボタン |
| 関連 🔗 | `related` | リンク由来＋AI推薦の関連ノート一覧（スクロール可）。項目タップで本文を開きノートタブへ遷移 |
| AI ✨ | `ai` | AI要約パネル＋「Q&Aを作る／AI補記メモ」起動。ノート未読み込み時は誘導文を表示 |

Q&A（`quiz`）と補記結果（`annotation`）は従来のフルスクリーン画面のまま、AIタブから push 遷移。

### 4-2. アダプティブ切替

`WindowWidthSizeClass` を判定し、UIを出し分ける。

| 画面幅クラス | 該当例 | ナビゲーションUI |
|--------------|--------|------------------|
| Expanded | Pixel Fold 展開 | 左サイドの `NavigationRail` |
| Compact / Medium | スマホ縦・折りたたみ時 | 下部の `NavigationBar` |

タブ切替は `saveState` / `restoreState` / `launchSingleTop` の標準構成で、タブ往復後もスクロール位置などの画面内状態を保持する。

### 4-3. 全画面表示のジェスチャ廃止

不安定だったダブルタップ判定（`awaitEachGesture` 2箇所）を**完全削除**。ノートタブ右上の **⛶ ボタン**で全画面を開き、全画面時の右上 **✕ ボタン**で閉じる。スクロールとジェスチャの競合余地そのものを排除した。

---

## 5. 成果物（ファイル）

| ファイル | 種別 | 行数 | 概要 |
|----------|------|------|------|
| `ui/AppScaffold.kt` | 新規 | 121 | `AppDestination` enum＋アダプティブなタブ殻（下部バー↔サイドレール） |
| `ui/RandomNoteScreen.kt` | 大幅改修 | 582 | 単一画面 → `NoteReaderTab`／`RelatedTab`／`AiTab` に再編。ダブルタップ廃止・⛶/✕ボタン化。共有パネルは流用 |
| `MainActivity.kt` | 改修 | 151 | NavHostをタブ構成（note/related/ai/quiz/annotation）に。`AppScaffold` で包み、コールバックを各タブへ配線 |

**無変更:** `NoteViewModel.kt` / `NoteUiState` / `MarkdownRenderer.kt` / `MarkdownParser.kt` / `QuizScreen.kt` / `AnnotationResultScreen.kt` / `AndroidManifest.xml` / `build.gradle.kts`

---

## 6. 不具合への対応結果

| # | 不具合 | 対応 |
|---|--------|------|
| 1 | Foldでボタンが切れる | 1画面平置きを廃し、各タブを単機能化。関連タブ・AIタブは `verticalScroll` 対応で画面高を超えても切れない。Compact/Medium=下部バー、Expanded=サイドレールでFoldを正しく出し分け。 |
| 2 | ダブルタップ不発 | ジェスチャ判定を削除し ⛶／✕ の明示ボタンに置換。判定自体が不要になり再発余地を消去。 |

---

## 7. 検証状況

- **静的レビュー:** 完了（旧 `RandomNoteScreen` 参照なし、旧ジェスチャAPI残骸なし、import・ルート整合を確認）。
- **ビルド:** Android Studio で `./gradlew assembleDebug` 成功を確認済み。
- **推奨動作確認項目（Foldableエミュレータ／実機）:**
  1. 折りたたみ＝下部バー、展開＝左レールへの切替
  2. ノートタブ：別のノート → ⛶ で全画面 → ✕ で復帰
  3. 関連タブ：項目タップ → ノートタブに切り替わり当該ノートが開く
  4. AIタブ：Q&A／補記メモの全画面遷移が従来どおり動作
  5. タブ往復・折りたたみ↔展開トグル後もノート状態が保持されること

---

## 8. 今後の拡張余地

- タブ構成のため、設定・ノート履歴・一覧などの画面を新タブとして追加しやすい土台が整った。
- ノートタブ見出しは既存文字列リソース `random_note_title`（"Random AI Note"）を流用。日本語UIへ統一する場合は `strings.xml` の調整が別途必要。
