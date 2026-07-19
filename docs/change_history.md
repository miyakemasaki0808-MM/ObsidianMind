# 変更履歴表

**プロジェクト:** Obsidian Mind

このファイルはPR単位の変更履歴を新しい順に記録する累積文書である。

- **何を**いつ変えたかはこの表、**今どうなって**いるかは [source_code_analysis.md](source_code_analysis.md)、**なぜそう**したかは [design/](design/) を参照する。
- 運用ルール: PRごとに1行追記する。設計判断・試行錯誤があった変更のみ design/ に対応ファイルを作成または追記し、この表からリンクする。

---

## 履歴（新しい順）

| 日付 | PR | 変更内容 | 設計メモ |
|------|----|----------|----------|
| 2026-07-20 | (作業中) | `feature/NewFunction_forUX`: PR #23のレビュー対応3件（デッドコード削除・Snackbarイベントキーのテスト可能化・画面回転での通知再表示抑止）／Vault選択をオプションへ移動／全画面✕ボタンの視認性修正／当日分の閲覧履歴「今日読んだノート」追加 | [background_ai_ux](design/background_ai_ux.md)・[tab_navigation](design/tab_navigation.md)・[ai_picker](design/ai_picker.md) |
| 2026-07-20 | #23 | Q&A・AI補記のバックグラウンド生成UX。待機画面への遷移を廃止し、Snackbar通知＋AIタブバッジ＋未確認（isViewed）管理に変更。`QuizController` 新設、requestIdによる古い結果の混入防止 | [background_ai_ux](design/background_ai_ux.md) |
| 2026-07-19 | #22 | AI要約の生成中もノート閲覧を継続可能に（要約待ちのブロッキング解消） | [background_ai_ux](design/background_ai_ux.md) |
| 2026-07-19 | #21 | 壁打ちUI改善: 読む画面の低彩度グラデーション（ReadingGradient）・ボタン配色3役ルールの明文化・ノート出現アニメーション・検索結果への更新日表示 | — |
| 2026-07-19 | #16〜#20 | コード品質改善活動。静的分析で27項目（重要度高4・中8・効率5・リファクタ10）を指摘し全件解消。`NoteViewModel` の機能Controller分割（906行→348行）、SAF走査キャッシュ、AIジョブのキャンセル/タイムアウト制御、ユニットテスト整備 | [architecture](design/architecture.md) |
| 2026-07-18 | #15 | AIピッカー「さがす🔎」タブ。フォルダスコープを共通土台に、自然文キーワード（Nano選定3件）とランダム（AI不使用3件）の2モード | [ai_picker](design/ai_picker.md) |
| 2026-07-17 | #14 | セクション単位AIチャット。浮遊吹き出しがスクロールに追従し、今見ているセクションの要約・質問候補・Q&Aを生成 | [section_ai_chat](design/section_ai_chat.md) |
| 2026-07-16 | #13 | AI補記メモの削除機能（オプション画面を新設） | — |
| 2026-07-16 | #12 | UIをタブ・ナビゲーション構成に再設計（Plan C）。Foldのボタン切れとダブルタップ全画面不発を構造的に解消 | [tab_navigation](design/tab_navigation.md) |
| 2026-06-19 | #11 | AI補記プロンプト改善・UI名称変更・表示整理 | — |
| 2026-06-19 | #10 | Android 17対応・ステータスバー非表示・UI調整 | — |
| 2026-05-31 | #9 | AI補記メモ機能の追加・AI推薦の改善 | — |
| 2026-05-31 | #8 | Q&Aフラッシュカード（オンデバイス4択生成）・グラフビュー・関連ノート改善 | — |
| 2026-05-30 | #7 | リファクタリング: Activity分割・ノート走査のスタック安全化 | — |
| 2026-05-30 | #6 | Fold展開時に関連ノートTop-5を左ペイン表示・タップで本文切替 | — |
| 2026-05-30 | #5 | Gemini Nano 4によるオンデバイスノート要約（ML Kit GenAI Prompt API） | — |
| 2026-05-30 | #4 | Markdownレンダリング強化（見出し・リスト・コード・引用・テーブル等） | — |
| 2026-05-11 | #2, #3 | アプリ名を「Obsidian Mind」へ変更、旧View系リソース整理、ソースコード解析書の整備 | — |
| 2026-05-10 | #1 | Jetpack Compose移行 | — |
| 2026-04-30〜05-10 | — | プロジェクト開始。Java実装→Kotlin化、MVVM＋SAF＋Coroutinesの基礎構築 | [project_origin.md](project_origin.md) |
