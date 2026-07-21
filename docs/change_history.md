# 変更履歴表

**プロジェクト:** Obsidian Mind

このファイルはPR単位の変更履歴を新しい順に記録する累積文書である。

- **何を**いつ変えたかはこの表、**今どうなって**いるかは [source_code_analysis.md](source_code_analysis.md)、**なぜそう**したかは [design/](design/) を参照する。
- 運用ルール: PRごとに1行追記する。設計判断・試行錯誤があった変更のみ design/ に対応ファイルを作成または追記し、この表からリンクする。

---

## 履歴（新しい順）

| 日付 | PR | 変更内容 | 設計メモ |
|------|----|----------|----------|
| 2026-07-21 | #30 | UIUX改善。**②スケルトンUI**: 要約生成中の表示をスピナー＋文言から自前shimmerの3行スケルトンへ（accompanist非推奨のため`Brush.linearGradient`＋`infiniteRepeatable`で自作）。**④スクリム**: `ModalBottomSheet` のスクリムを既定より濃く（`alpha=0.5f`）し背景グラデーションの透けを抑制。**タイトル体系の統一**: 各タブH1を機能説明から英語コンセプト語へ（ランダムAIノート→Rediscover／さがす→Explore／関連ノート→Connect／AIアシスト→Reflect）。「英語H1＝概念／日本語サブ＝意味補助／日本語ボタン＝操作」の3層役割分担でブランド(Obsidian Mind)-概念-機能タブ(日本語)の階層を明確化。主ボタン文言も「動詞＋を＋名詞」へ統一。**ナビ選択ピル**: 下バー/レールの選択インジケータを `Panel@22%`（Indigo地に埋もれ視認性不足）から `Aqua` へ変更し選択状態を明確化（タブは絵文字アイコンのため色指定が効くのはピルのみ・ラベルはIndigo地上のため白を維持）。**全画面ノートの独立ルート化**: `content` 内オーバーレイ（バー/レールを覆えず・システムバー残存・額縁・スクロール先頭復帰）を非タブルート `note_fullscreen` へ移行。進入時に `systemBars()` 没入・離脱時ナビバーのみ復元、ノート色で全ブリード＋本文720dp中央寄せ、通常表示とスクロール位置を継承（遷移中の同時コンポーズを避けるため専用state＋開始継承/離脱書き戻し）。読書中も要約＋クイズの合成状態を最小FABで表示、全画面中はSnackbar抑制 | [note_fullscreen](design/note_fullscreen.md) |
| 2026-07-21 | #29 | 関連ノートAI推薦の Phase 3。**3a**: 採番順の並べ替えを全Vaultのタイトル話題スコアへ置換（文字bigram Dice係数＋採番近接の加点）。**3b**: 上位40件を現ノートの tags/snippet/title 類似で再ランクする二段ランキング（件数維持・並べ替えのみ／Nanoの位置バイアス対策）。採点戦略を注入する汎用ランキング基盤 `rankByScore` を分離し3a/3bで共有。実機で「jetpackcompose」ノートの関連が全てKotlin系で揃うことを確認 | [related_notes_ai](design/related_notes_ai.md) |
| 2026-07-20 | #28 | 関連ノートAI推薦の Phase 2。候補をタイトルに加え本文スニペット・タグ・aliasesで肉付けし入力予算内へ動的短縮（2a）／候補本文を `URI+lastModified` でキャッシュ（成功時のみ格納）し `Semaphore(8)` 上限付き並列で読込（2b） | [related_notes_ai](design/related_notes_ai.md) |
| 2026-07-20 | #27 | 関連ノートAI推薦の Phase 1。フォーカスセクション文脈での推薦（長ノートの先頭切り出し解消）／候補に一時ID(C01..)を採番しNanoにはID応答させるID応答方式（言い換え・同名衝突での解決失敗を解消） | [related_notes_ai](design/related_notes_ai.md) |
| 2026-07-20 | #26 | アプリ起動時のブランドOPアニメーションを追加。システムスプラッシュ（`core-splashscreen`）＋Compose OP（`OpeningScreen.kt`）の2層構成。背景を着地画面と同色（`ReadingGradient`）で終端し継ぎ目を解消／`savedInstanceState==null` で新規起動時のみ再生／アダプティブランチャーアイコン新設／レビュー対応（TalkBack二重読み上げ回避・発光色を`AppColors`へ集約） | [opening_animation](design/opening_animation.md) |
| 2026-07-20 | #25 | AI補記の途切れ対策（finishReason検知・出力要求の絞り込み）／クイズを「フォーカスセクション周辺から2問」へ再設計し入口を吹き出しシートへ移動（AIタブのQ&Aボタン廃止・バッジ補記のみ）／クイズのセッション従属化／吹き出しタップ無反応の修正（pointerInputのstale closure） | [section_ai_chat](design/section_ai_chat.md)・[background_ai_ux](design/background_ai_ux.md)・[architecture](design/architecture.md) |
| 2026-07-20 | #24 | PR #23のレビュー対応3件（デッドコード削除・Snackbarイベントキーのテスト可能化・画面回転での通知再表示抑止）／Vault選択をオプションへ移動／全画面✕ボタンの視認性修正／当日分の閲覧履歴「今日読んだノート」追加 | [background_ai_ux](design/background_ai_ux.md)・[tab_navigation](design/tab_navigation.md)・[ai_picker](design/ai_picker.md) |
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
