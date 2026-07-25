# ソースコード解析書

**プロジェクト:** Vigilith AI（旧 Obsidian Mind。`strings.xml` の `app_name` を改称済み）

**解析日:** 2026-07-25

**対象ブランチ:** `feature/TimeCapcel_Improvement_Plus_Alpha`

**対象状態:** HEAD `41228a1`（ReadingTrace v1 / PR #34）＋ レビュー指摘4件の修正

**対象範囲:** `app/src/main`、`app/src/test`、Gradle設定

**検証結果:** レビュー指摘の高優先度4件（到達率・Activity lifecycle・Vault分離・検索フォールバック）をJVMテスト付きで解消し、全317ケースへ拡充。**本修正分のテスト実行と実機確認はAndroid Studio側で未実施**（開発機にJDKが無くCLIビルド不可）

---

## 1. エグゼクティブサマリー

Vigilith AI は、Android の Storage Access Framework（SAF）でユーザーが選択した Obsidian Vault を読み込み、Markdown ノートの閲覧・検索・関連ノート抽出・復習支援を行う Jetpack Compose アプリである。

AI機能はクラウドAPIではなく、ML Kit GenAI Prompt API を通じて端末内の Gemini Nano を使用する。現在実装されているAI機能は次のとおり。

- ノート全体の要約
- wikilink・ファイル名規則と組み合わせた関連ノート推薦
- 自然文によるノート選択（AIピッカー）
- 読書中セクションの周辺テキストからの適応出題Q&A（○×／3択／4択）
- ノートを成長させるためのAI補記メモ生成・Vaultへの保存
- 表示中セクションの要約、質問候補生成、セクション限定Q&A
- 蒸留（Distill）: AIが重要文を選び、ユーザー確認後に元ノートを `**太字**` へ書き換えるプログレッシブ要約支援
- ReadingTrace: 10秒以上読んだノートの最深到達点をサイドカーへ記録し、Rediscover時に「前回のあなた」カードと読み方の俯瞰要約を表示

Q&AとAI補記はバックグラウンド生成方式で、生成中もノート閲覧を継続でき、完了・エラーはSnackbarとAIタブのバッジで通知される。ReadingTraceは対照的に、AI未準備・生成失敗を通知せず、生の痕跡だけを先に表示して黙って劣化する。AI以外の補助機能として、当日分のみの閲覧履歴（さがすタブ「今日読んだノート」）を持つ。

アーキテクチャは「単一 Activity + Compose Navigation + 単一 ViewModel」を入口としつつ、肥大化を避けるため検索・セクションチャット・クイズ・補記・蒸留・読書痕跡を機能別 Controller（6つ）に分割している。ファイルI/Oは `NoteRepository`、AI判定を含む主要ロジックは UseCase、AI接続は `AiClient`、Markdown生成・応答パースは純粋ロジックへ分離されている。蒸留のVault書き戻しは `DistillWriteRepository` が専用の安全書き込み経路（ハッシュ照合・復旧レコード）を持ち、ReadingTraceは `_ReadingTraces` へのベストエフォートなサイドカー保存を持つ。

現時点の総評は次のとおり。

- 主要責務の分割、状態の一元管理、古いAI処理のキャンセル、生成タイムアウト、SAF走査キャッシュが実装され、継続的な機能追加に耐えやすい構造になっている。
- Markdownパーサー、補記Markdown生成、クイズ応答パーサー、蒸留の文分割・採点・太字挿入、ReadingTraceのJSON・Controller・相対パス走査など、壊れやすい純粋ロジックにはユニットテストが整備されている（317ケース）。
- クイズ・補記・蒸留はrequestId＋Job追跡で古い結果の混入を防いでいるが、検索は要求単位で追跡されず、連続操作時の競合余地が残る。
- SAF、Compose Navigation、Gemini Nano を組み合わせた統合テストはなく、実端末依存の動作はユニットテストだけでは保証されない。
- 「AI非対応時はキーワード一致で表示」という検索画面の文言と、候補40件以下で単純に先頭3件を返す実装には差がある。
- ReadingTraceは主要経路とJVMテストが揃い、レビューで見つかった高優先度3件（ブロック数基準の到達率、Activity停止・再開、Vault切替中の起動済み保存）も解消済みである。ただしSAF照合とActivity lifecycleの実挙動はJVMテストの範囲外なので、実端末確認が完了判定に要る。

---

## 2. プロジェクト規模と技術構成

### 2.1 コード規模

| 区分 | ファイル数 | 行数・件数 |
|---|---:|---:|
| 本番 Kotlin | 59ファイル | 10,926行 |
| ユニットテスト Kotlin | 36ファイル | 4,828行、317テスト |
| Androidモジュール | 1 | `:app` |

行数は空行・コメントを含む `wc -l` ベースであり、生成物とGradleスクリプトは含まない。

### 2.2 ビルド・プラットフォーム

| 項目 | 現在値 |
|---|---|
| Android Gradle Plugin | 9.1.1 |
| Kotlin Compose Plugin | 2.0.21 |
| compileSdk | Android 36.1 |
| targetSdk | Android 36 |
| minSdk | Android 26 |
| Java互換性 | Java 8 |
| Compose BOM | 2024.09.03 |
| Navigation Compose | 2.7.7 |
| Core SplashScreen | 1.0.1 |
| Lifecycle | 2.8.7 |
| Coroutines | 1.9.0 |
| ML Kit GenAI Prompt | 1.0.0-beta2 |
| JUnit | 4.13.2 |

### 2.3 外部依存の特徴

- UIは View/XML を使わず Jetpack Compose で構成する。
- Vaultアクセスは Android 標準の SAF と `DocumentsContract` を使用し、ストレージ権限を Manifest に要求しない。
- AI生成は `com.google.mlkit:genai-prompt` を通じて Gemini Nano を使用する。
- DIフレームワーク、データベース、HTTPクライアント、画像読み込みライブラリは導入していない。
- 本番コードは `AICoreClient` を直接生成する。`StubAiClient` は手動差し替え用として残されている。

---

## 3. 現在のファイル構成

```text
app/src/
├── main/
│   ├── AndroidManifest.xml
│   ├── java/com/example/newproject/
│   │   ├── MainActivity.kt                 # Activity、システムスプラッシュ／起動OP、Vault選択、NavHost、Snackbar通知、画面イベント接続
│   │   ├── NoteViewModel.kt                # 状態の統合、ノート読込、要約・関連の調停、履歴記録
│   │   ├── NoteUiState.kt                  # 全UI状態と各sealed state、通知イベントキー
│   │   ├── NoteRepository.kt               # SAF走査・読書き・メタデータ解析
│   │   ├── NoteSnapshot.kt                 # 蒸留用の原バイト保持・上限付き読込・UTF-8厳格判定
│   │   ├── NoteHistoryStore.kt             # 当日分のみの閲覧履歴（SharedPreferences）
│   │   ├── NoteTitleNormalizer.kt          # Obsidianタイトル正規化
│   │   ├── SearchController.kt             # フォルダ検索・スコープキャッシュ
│   │   ├── SectionChatController.kt        # セクション要約・質問・Q&A
│   │   ├── QuizController.kt               # 適応出題Q&Aのバックグラウンド生成・確認状態
│   │   ├── AnnotationController.kt         # AI補記の生成・保存・一覧・削除
│   │   ├── DistillController.kt            # 蒸留の候補提示・選択・保存・復旧の直列化
│   │   ├── DistillWriteRepository.kt       # 蒸留のSAF安全書き込み（二重ハッシュ照合・原子確定）
│   │   ├── DistillRecoveryStore.kt         # 中断復旧レコード（noBackupFilesDir）
│   │   ├── DistillHashing.kt               # SHA-256（原バイト／出力の照合用）
│   │   ├── ReadingTrace.kt                 # 読書痕跡モデル・上限・検証
│   │   ├── ReadingTraceController.kt       # 読書セッション・能動読書時間の積算・離脱/背面時保存・再会カード・AI俯瞰要約
│   │   ├── ReadingTraceJson.kt             # サイドカーJSON・canonical checksum
│   │   ├── ReadingTraceStore.kt            # 痕跡永続化境界・SAF Gateway・フォルダ索引・Vault照合
│   │   ├── SafDocuments.kt                 # SAF子要素列挙・ルート機能フォルダの探索/作成
│   │   ├── VaultPathTraversal.kt           # Vault相対パス付きBFS（Android非依存）
│   │   ├── AnnotationComposer.kt           # 補記Markdown検証・整形（純粋ロジック）
│   │   ├── QuizResponseParser.kt           # AIクイズ応答パース（純粋ロジック）
│   │   ├── QuizInputProfile.kt             # AI不使用の入力分類→出題形式決定（純粋ロジック）
│   │   ├── ai/
│   │   │   ├── AICoreClient.kt             # AiClient、Gemini Nano接続、Mutex、タイムアウト
│   │   │   └── PromptBuilder.kt            # 10種類のプロンプト構築
│   │   ├── domain/
│   │   │   ├── SummarizeUseCase.kt          # 要約ユースケース
│   │   │   ├── RelatedNotesUseCase.kt       # 規則ベース＋AI関連ノート抽出（多段パイプライン）
│   │   │   ├── RelatedCandidateOrdering.kt  # 採番プレフィックス抽出（extractHexPrefix・共用）
│   │   │   ├── RelatedCandidateScoring.kt   # タイトル話題スコア（文字bigram Dice＋採番近接）
│   │   │   ├── RelatedContextScoring.kt     # 本文シグナル再ランク（tags/snippet/title）
│   │   │   ├── RelatedCandidateRanking.kt   # 採点戦略注入の汎用ランキング（rankByScore）
│   │   │   ├── RelatedCandidateContext.kt   # 候補の本文肉付け・入力予算内への整形
│   │   │   ├── RelatedCandidateId.kt        # 一時ID(C01..)採番と応答からのID抽出
│   │   │   ├── KeyedMemoCache.kt            # 汎用LRUメモ化（成功時のみ格納）
│   │   │   ├── DistillSourceModel.kt        # 蒸留用の文分割（UTF-16オフセット保持・Markdown構造認識）
│   │   │   ├── DistillCandidateScoring.kt   # 蒸留候補のサリエンス採点・チャンク網羅（DistillLimits）
│   │   │   ├── DistillResponseParser.kt     # 蒸留AI応答からのID抽出（許可集合で検証）
│   │   │   ├── DistillTransformer.kt        # オフセット降順の `**` 挿入・太字比率上限
│   │   │   ├── SearchKeywordMatching.kt     # キーワード一致の採点・選抜（純関数）
│   │   │   ├── SearchPickerUseCase.kt       # 自然文検索による3件選定
│   │   │   └── AiResponseParsing.kt         # AI返却タイトルの共通正規化
│   │   └── ui/
│   │       ├── OpeningScreen.kt            # 起動時ブランドOP（Compose、純ロジックfractionBetweenで進行導出）
│   │       ├── AppScaffold.kt              # 5タブ、NavigationBar/Rail切替、AIタブバッジ、SnackbarHost
│   │       ├── NoteReaderTab.kt            # Markdown閲覧、全画面ルート（FullscreenNoteScreen）、セクションFAB
│   │       ├── SearchScreen.kt             # AI検索・ランダム抽出
│   │       ├── RelatedTab.kt               # 関連・AI推薦ノート一覧
│   │       ├── AiTab.kt                    # 要約、Q&A、AI補記の入口
│   │       ├── OptionsScreen.kt            # オプション入口
│   │       ├── QuizScreen.kt               # 4択問題UI
│   │       ├── AnnotationResultScreen.kt   # 生成した補記メモ表示
│   │       ├── AnnotationManagerScreen.kt  # 補記一覧・削除
│   │       ├── SectionChatSheet.kt         # セクションAIボトムシート
│   │       ├── ReadingProgressGeometry.kt  # 最終可視ブロックの可視割合・量子化（純関数）
│   │       ├── ReadingTraceCard.kt         # 「前回のあなた」カード・経過文面
│   │       ├── markdown/
│   │       │   ├── MarkdownParser.kt       # ブロック・インラインMarkdown解析
│   │       │   ├── MarkdownRenderer.kt     # Compose描画
│   │       │   └── NoteSections.kt         # 見出し単位セクションモデル
│   │       └── theme/AppColors.kt          # 色・グラデーション・ボタン役割
│   └── res/values/                         # app_name、テーマ、最低限の色定義
└── test/java/com/example/newproject/          # 34ファイル・282テスト（内訳は §13.1）
    ├── NoteRepositoryTest.kt / NoteSnapshotTest.kt
    ├── MarkdownParserTest.kt / InlineMarkdownTest.kt
    ├── QuizResponseParserTest.kt / QuizInputProfileTest.kt / QuizPromptBuilderTest.kt
    ├── SurroundingContextTest.kt
    ├── AnnotationComposerTest.kt
    ├── SectionChatControllerTest.kt / QuizControllerTest.kt / AnnotationControllerTest.kt
    ├── DistillControllerTest.kt / DistillWriteRepositoryTest.kt / DistillRecoveryStoreTest.kt
    ├── ReadingTraceControllerTest.kt / ReadingTraceJsonTest.kt / ReadingTraceStoreTest.kt
    ├── VaultPathTraversalTest.kt / ui/ReadingTraceHeadlineTest.kt / ui/ReadingProgressGeometryTest.kt
    ├── domain/SearchKeywordMatchingTest.kt
    ├── EventKeyTest.kt
    ├── ai/DistillPromptBuilderTest.kt
    ├── domain/Distill*Test.kt                 # SourceModel / CandidateScoring / ResponseParser / Transformer
    ├── domain/Related*Test.kt                 # CandidateContext / CandidateId / Ordering / Ranking / Scoring / ContextScoring
    ├── domain/KeyedMemoCacheTest.kt
    └── ui/AiTabBadgeStateTest.kt
```

---

## 4. アーキテクチャ

### 4.1 レイヤーと依存方向

```text
Compose UI / MainActivity
          │ ユーザーイベント、StateFlow購読
          ▼
     NoteViewModel
       ├── SearchController
       ├── SectionChatController
       ├── QuizController
       ├── AnnotationController
       ├── DistillController
       └── ReadingTraceController
          │
          ├──────────────► NoteRepository ──► SAF / DocumentsContract
          ├──────────────► DistillWriteRepository ──► SAF（安全書き込み）
          │                  └── DistillRecoveryStore ──► noBackupFilesDir
          ├──────────────► ReadingTraceStore ──► SAF / `_ReadingTraces`
          │
          └──────────────► UseCase ──► AiClient ──► ML Kit / Gemini Nano

純粋ロジック:
MarkdownParser / NoteSections / QuizResponseParser / QuizInputProfile /
AnnotationComposer / NoteTitleNormalizer / AiResponseParsing /
DistillSourceModel / DistillCandidateScoring / DistillResponseParser / DistillTransformer /
VaultPathTraversal / ReadingTraceJson / ReadingTraceHeadline
```

この構成は厳密なマルチモジュールClean Architectureではない。すべて同一 `:app` モジュール内にあり、Controller が `MutableStateFlow<NoteUiState>` を直接更新する。ただし、責務境界はファイル単位で明示されており、小規模アプリとしては理解しやすい構成である。

### 4.2 状態の単一ソース

`NoteViewModel` が `MutableStateFlow<NoteUiState>` を所有し、UIには読み取り専用の `StateFlow` として公開する。`MainActivity` は `collectAsStateWithLifecycle()` で購読する。

Controller は独自の Flow を作らず、共有された `_uiState` の担当フィールドだけを `copy()` で更新する。

| 担当 | 更新する主な状態 |
|---|---|
| `NoteViewModel` | `noteState`、`summaryState`、`relatedNotesState`、`wikilinkTitles`、`todayHistory` |
| `SearchController` | `folders`、`selectedFolder`、`searchState` |
| `SectionChatController` | `sectionChat` |
| `QuizController` | `quizState` |
| `AnnotationController` | `annotationState`、`annotationListState` |
| `DistillController` | `distillState` |
| `ReadingTraceController` | `readingTraceCard`（読書中Session自体はController内部） |

### 4.3 状態モデル

`NoteUiState` は次の状態を集約する。

- `vaultSelected`: Vault選択済み表示用フラグ
- `noteState`: Idle / Loading / Success / Empty / Error。`Success` は表示用のタイトル・本文に加え、蒸留の書き戻し用に `targetUri`・原バイトの `originalHash`・蒸留不可理由（`distillUnavailableReason`）を保持する
- `summaryState`: Idle / Loading / Success / Downloading / AiUnavailable / Error
- `relatedNotesState`: Idle / Loading / Success / Error
- `quizState`: Idle / Loading / Success / Error（Loading以降は `sourceTitle`、Success/Errorは `isViewed` を保持）
- `annotationState`: Idle / Loading / Success / Error（同上）
- `annotationListState`: Idle / Loading / Success / Error
- `distillState`: Idle / Analyzing / NeedsDownload / Downloading / Unavailable / Candidates / Saving / Saved / Conflict / RecoveryRequired / RecoveryResolved / Error（他機能より状態数が多いのは、AI生成に加えVault書き戻しの競合・中断復旧まで表現するため）
- `readingTraceCard`: Rediscoverで過去の痕跡が見つかった場合だけ入る「前回のあなた」カード。訪問回数・前回日時・最深セクション・到達率・AI俯瞰要約・読み込み中・現在表示中だけのdismiss状態を持つ
- `sectionChat`: シートを閉じている場合は `null`
- `folders`、`selectedFolder`、`searchState`: 検索タブ用
- `wikilinkTitles`: 現在ノートから抽出したリンク先タイトル
- `todayHistory`: 当日分の閲覧履歴（最大10件）

`quizState`/`annotationState` の `sourceTitle` は「どのノートの生成結果か」を、`isViewed` は「結果をユーザーがまだ確認していない」を表し、Snackbar通知とAIタブバッジの表示判定に使う。通知の発火判定キーは `toEventKey()` 拡張関数（`NoteUiState.kt`）が組み立てる。

ノートまたはVaultの切替時は `resetNoteScopedStates()` により、要約・関連・クイズ・補記結果・セクションチャット・再会カードを一括リセットする。検索状態と閲覧履歴はVault切替時だけ別途リセットする。

---

## 5. ナビゲーションと画面構成

### 5.1 ルート

| 種別 | route | 画面 |
|---|---|---|
| トップレベル | `note` | ノート閲覧 |
| トップレベル | `search` | さがす |
| トップレベル | `related` | 関連ノート |
| トップレベル | `ai` | AIアシスト |
| トップレベル | `options` | オプション |
| 全画面 | `note_fullscreen` | 全画面ノート閲覧（バー/レール非表示・システムバー没入） |
| 全画面 | `quiz` | 4択Q&A |
| 全画面 | `annotation` | AI補記生成結果 |
| 全画面 | `annotation_manager` | AI補記の削除管理 |

`navigateToTab()` は `popUpTo`、`saveState`、`restoreState`、`launchSingleTop` を使い、トップレベルタブのバックスタック増殖を抑えつつ状態を復元する。

### 5.2 画面幅対応

`AppScaffold` は `WindowSizeClass` を参照し、Expanded幅では左側 `NavigationRail`、それ以外では下部 `NavigationBar` を使用する。選択タブのインジケータは `Aqua`（Indigo地に埋もれないアクセント）。全画面ノート・クイズ・補記結果・補記管理の全画面ルートではタブUIを表示しない。全画面ノートは進入中にシステムバー（ナビ＋ステータス）も隠し、離脱時にナビバーのみ復元する（ステータスバーはアプリ全体仕様どおり隠したまま）。

### 5.3 画面ごとの責務

#### ノートタブ

- ランダム表示（Vault選択ボタンは未選択時のみ表示。切替はオプションから）
- Markdown本文の表示とテキスト選択
- 本文パネルのフェード＋スケール表示
- ⛶ボタンで全画面ルート（`note_fullscreen`）へ。バー/レール・システムバーを隠し、本文カラムは最大720dp中央寄せ。通常表示とスクロール位置を継承し、読書中も要約/クイズの合成状態を最小FABで表示（完了/エラー時のみラベルを数秒フラッシュ）
- スクロール位置から現在セクションを判定
- 最終可視Markdownブロックとその可視割合をReadingTraceへ報告し、Rediscover由来で過去痕跡があれば本文上に「前回のあなた」カードを表示
- ドラッグ可能な吹き出しからセクションAIを起動
- 吹き出しシート内の「この部分でクイズ」からフォーカス周辺クイズを起動
- 読書画面向けの低彩度グラデーションを使用

#### さがすタブ

- Vault第一階層のフォルダを横スクロールChipで選択
- 自然文クエリからAIが3件を選ぶ検索
- 選択スコープ内からAIを使わず3件ランダム抽出
- 結果からノートを開き、ノートタブへ移動
- 更新日を `yyyy/MM/dd` 形式で表示
- 下部に当日分の閲覧履歴「今日読んだノート」を表示（タップで開き直し）

#### 関連タブ

- 規則ベース関連ノートとAI推薦を別セクションで表示
- wikilink一致ノートに `linked` バッジを表示
- AI利用不可、モデル準備中、AIエラーを状態別に表示
- 結果からノートを開き、ノートタブへ移動

#### AIタブ

- 自動生成されたノート要約を表示
- AI補記メモのバックグラウンド生成の起点。ボタンラベルは状態に応じて「作る／作成中…／開く／エラーを確認／再試行」と変化する
- Reflect（蒸留）の起点。AIが選んだ重要文を候補リストで提示し、ユーザーが確認した文だけを元ノートへ `**太字**` として書き戻す（§6.10）
- 4択Q&Aの起点は読書画面の吹き出しシートへ移動した（フォーカス周辺クイズ）。AIタブにQ&Aボタンはない
- モデルダウンロード時は進捗を表示
- タブアイコンのバッジ対象は補記メモのみ。未確認の重要度順（エラー > 未確認完了 > 生成中）で1つだけ表示（`resolveAiTabBadgeState`）

#### オプション

- 「Vaultを変更」: フォルダ選択のやり直し（現在の選択状態をサブタイトル表示）
- 「AI補記メモを削除」: 補記一覧で1件削除・全件削除、削除前に確認ダイアログ

#### 横断: Snackbar通知

Q&A・補記の生成開始／完了／失敗は `MainActivity` の `LaunchedEffect` がSnackbarで通知する。完了・失敗の通知にはアクション（見る／詳細）が付き、タップで結果画面を開くと同時に `isViewed` を立てる。表示済みイベントキーを `rememberSaveable` に記録し、画面回転による再表示を抑止する。全画面ノート（`note_fullscreen`）表示中はSnackbarを抑制し、AIの状態は全画面の最小FABが担う。

---

## 6. 主要機能のデータフロー

### 6.1 Vault選択と復元

```text
OpenDocumentTree
  → 読み書き可能な永続URI権限を取得
  → SharedPreferencesへURI文字列を保存
  → 全体ノートキャッシュ・検索キャッシュ・閲覧履歴・旧状態を破棄
  → ランダムノートを1件読み込む
```

導線はノートタブ（未選択時のみ）とオプションの「Vaultを変更」の2つ。

次回起動時は SharedPreferences のURIを復元し、`vaultSelected = true` にする。起動直後にノートを自動読込する処理はなく、ユーザーがランダム表示するか検索結果を開くまで `noteState` は Idle のままである。

なお `MainActivity` は `setContent` 直後に、コールド起動時のみ `OpeningScreen`（起動OP）を本体の代わりに表示する。新規起動の判定は `savedInstanceState == null`（回転・Fold開閉・プロセス復元では非nullのため再生しない）。OP終端の背景は着地（Noteタブ）と同じ `ReadingGradient` に揃え、継ぎ目なく本体へ入れ替える。詳細は [design/opening_animation](design/opening_animation.md) を参照。

### 6.2 ランダムノート表示

```text
loadRandomNote()
  → 旧ノートに属するJobをキャンセル
  → noteState = Loading、ノート依存状態をリセット
  → Vault全体をBFS走査（60秒以内ならキャッシュ利用）
  → _AI補記フォルダを除いた .md から random()
  → UTF-8で本文読込
  → vault相対パス付きでReadingTrace Sessionを開始
  → noteState = Success
  → 閲覧履歴に記録（openNote も同様）
  ├── 過去の痕跡を照合し「前回のあなた」カードを表示（Rediscoverのみ）
  ├── fetchSummary()
  └── fetchRelatedNotes()
```

VaultにMarkdownがなければ `NoteState.Empty`、読み込み失敗は `NoteState.Error` となり、ノート画面では一般化したエラー文、同時に Toast で例外メッセージを表示する。

### 6.3 ノート要約とモデルダウンロード

`SummarizeUseCase` はAIの状態を確認する。

| AI状態 | 動作 |
|---|---|
| Available | 先頭1,200文字を含むプロンプトで2〜4文を生成 |
| NeedsDownload | モデルダウンロードを開始し進捗を `SummaryState.Downloading` へ反映 |
| Unavailable | `SummaryState.AiUnavailable`。現在のUIでは要約パネル自体を表示しない |
| 生成失敗 | `SummaryState.Error` |

ダウンロード完了後は保持していたタイトル・本文で要約と関連ノート検索を再実行する。

### 6.4 関連ノート

関連ノートは規則ベースとAIベースの2段構成である。

#### 規則ベース

1. 現在ノート自身を正規化タイトルで除外する。
2. 本文の `[[wikilink]]` と一致するノートを抽出する。
3. ファイル名先頭が4桁16進数の場合、上2桁が同じノートを同一グループとして抽出する。
4. wikilink一致を先、同一グループを後に連結し、URIで重複排除して最大5件返す。

#### AI推薦

候補の選定→肉付け→再ランク→ID応答の多段パイプラインである（設計と経緯は [related_notes_ai](design/related_notes_ai.md)）。

1. **タイトル話題スコアで全Vaultをランク**し上位40候補に絞る（`rankRelatedCandidates`）。スコアはタイトルの文字bigram Dice係数（主）＋採番プレフィックス近接の加点（従）。決定的チャンネルに出したタイトルは上限適用の前に除外する。
2. **候補本文を上限付き並列で読む**（`Semaphore(8)`）。各候補を本文冒頭スニペット・タグ・aliasesで肉付けし、`URI+lastModified` でキャッシュする（成功時のみ格納）。
3. **現在ノートの本文シグナルで40件を再ランク**する（`relatedContextScore`）。タグ一致（主）＋スニペット類似＋タイトル類似で並べ替え、件数は変えない。
4. 再ランク後の並びで一時ID（`C01..`）を採番し、候補を入力予算（3,500文字）内へ動的短縮して整形する。現在本文の先頭600文字とともにAIへ渡す。
5. **AIにはIDだけ返させ**、行頭付近のIDのみ抽出して実ノートへ解決する（`parseCandidateIds`）。決定的結果とのURI重複を除いて最大5件返す。

AIが利用不可またはモデル未準備でも、規則ベース結果は表示できる。AI生成で例外が起きても `RelatedNotesResult.Error` にはせず、規則ベース結果と `AiRecommendationStatus.Error` を返す設計である。個別候補の本文読込失敗（キャンセル以外）は該当候補のみタイトルで続行し、推薦全体を巻き添えにしない。

### 6.5 さがす（AIピッカー）

検索スコープは次の仕様である。

| 選択 | 対象 |
|---|---|
| ルート直下 | Vault直下の `.md` のみ。非再帰 |
| 第一階層フォルダ | 選択フォルダ以下を再帰走査 |

検索タブでは `_AI補記` を除外しないため、補記メモも検索・ランダム候補になり得る。

自然文検索では候補が40件を超える場合だけ、クエリとファイル名の文字bigram重複数で上位40件に絞る。その後AIへタイトル一覧を渡し、最大3件を取得する。AIが利用不可・未ダウンロードの場合はフォールバックする。

重要な現状仕様として、候補が40件以下の場合はbigram並べ替えを行わない。したがってAI非対応時のフォールバックは「キーワード一致上位」ではなく、SAFから得た候補リストの先頭3件である。画面文言の「キーワード一致で表示しています」とは厳密には一致しない。

ランダムモードはAIを使用せず、`shuffled().take(3)` で選ぶ。

### 6.6 セクションAI

ノート本文は一度Markdownブロックへパースし、描画とセクション判定で共有する。現在の `LazyColumn` 先頭可視ブロック以前にある最も近い見出しを現在セクションとする。

セクション範囲は、対象見出しから「同レベルまたは上位レベルの次の見出し」の直前までで、配下の小見出しを含む。見出しが存在しない位置ではノート全体を対象にする。

吹き出しを開くと次の順でAIを使用する。

1. セクション先頭1,500文字から要約を生成する。
2. 同じセクションから最大3件の質問候補を生成する。
3. ユーザーが候補をタップすると、セクション本文と会話履歴を渡して回答を生成する。

回答プロンプトは「セクションに書かれていない内容を推測しない」よう制約する。自由入力欄はなく、現在のUIではAIが生成した質問候補のタップだけが質問入力経路である。

シート下部の「この部分でクイズ」からフォーカス周辺クイズ（6.7）を起動できる。クイズはセクションチャットセッションに従属し、新しいセッションの開始時（`openSection`）とセッションの明示終了時（確認を終了）に破棄される。シートを閉じて同一セッションを再表示した場合は保持される。

### 6.7 適応出題Q&A（○×・3択・4択／フォーカス周辺クイズ）

`QuizController` がバックグラウンドで生成する。入口は読書画面の吹き出しシート（6.6）で、入力はノート全体ではなく「フォーカスセクションの周辺テキスト」である。

1. シートの「この部分でクイズ」タップで、シート対象セクションを `sectionModel` から同定し、`NoteSectionModel.surroundingContext()` が周辺テキスト（約1,200文字）を構築する。セクションを核に前後のブロックを交互に加えて広げる方式で、親セクションが子を内包する構造でも本文が重複しない。見出しなし・擬似セクションはノート先頭にフォールバックする。
2. 生成開始時に `QuizState.Loading(sourceTitle=セクション名)` を立てる（待機画面なし）。
3. `checkAvailability()` で分岐する。Unavailableはエラー、NeedsDownloadはモデルDL後に自動再開、Availableは即生成。
4. 周辺テキストを**AI不使用で分類**し（`QuizInputProfile`）、素材量に応じて出題形式を切り替える：コード比率45%以上→3択2問、本文180字未満または文シグナル2以下→○×2問、本文700字以上かつ文シグナル6以上→4択1問、それ以外→3択2問。○×・3択は解説なし・4択のみ短い解説を1文とし、問題／選択肢に文字数上限を指示する。これは、常に4択2問＋解説を要求すると出力上限（256トークン程度、8.3参照）を超えて `MAX_TOKENS` で全結果が破棄され、クイズ生成エラーになっていた問題への対策（詳細は [design/section_ai_chat.md](design/section_ai_chat.md)）。
5. `Q:` 行を問題開始として `parseQuizResponse(raw, format)` がフィールドを抽出する。○×は `TRUE`/`FALSE`/`○`/`×`/`正しい`/`誤り` 等を許容、多択は正解レターを**単語境界regex `\b[A-D]\b`** で抽出し `B.`・`(B)`・`B) 選択肢文`・`The answer is B` 等の崩れを救済する（単語内の文字は誤検出しない・範囲外の `D` 等は棄却）。選択肢数（3/4）は応答実体に合わせ、必須フィールド欠落や範囲外の正解記号は捨てる。
6. パース結果が0件なら `QuizState.Error`、あれば `QuizState.Success(isViewed=false)` とし、Snackbarで通知する（AIタブバッジの対象外）。
7. Q&A画面ではユーザー選択後に正誤、正解、解説を表示し、次の問題へ進む。

生成中の再タップはLoadingガードで無視する。requestIdによる `isCurrent()` チェックで、ノート切替後の古い結果混入を防ぐ。クイズの寿命はセクションチャットセッションに従属する（6.6）。

なお「もう2問」の追い生成（既出問題の除外リスト付き再生成）を一度実装したが、小型モデルには同一素材からの追加出題が難しく成功率が低かったため廃止した（経緯は [design/section_ai_chat.md](design/section_ai_chat.md)）。

### 6.8 AI補記メモ

1. 現在ノート、要約、関連ノート、AI推薦、wikilinkを入力にする。
2. AIへ固定選択肢による「粒度評価」と、具体的な「補記すべき内容」をMarkdownで生成させる。
3. `AnnotationComposer.hasAnnotationBody()` で必須見出し内に本文があるか確認する。
4. Source、Created、Generated by のメタ情報を付ける。
5. Vault直下の `_AI補記` フォルダを検索し、なければ作成する。
6. `{タイトル}__補記_{yyyyMMdd_HHmm}.md` としてUTF-8保存する。

タイトル中の SAF上不適切な記号は `_` へ置換する。生成本文で片方の必須見出しが欠落した場合は、保存時に空見出しを補完する。

補記一覧はファイル名の `__補記_` より後ろのタイムスタンプで降順に並べる。削除は `DocumentsContract.deleteDocument()` を使う。

補記生成もQ&Aと同じバックグラウンド方式で、生成開始後はノートタブへ戻り、完了・失敗はSnackbarとバッジで通知される。requestIdガード・Loadingガード・モデルDL自動再開の仕組みも共通の形をとる。Controllerは意図的に共通化せず相似形のまま維持する方針で、3機能目（`DistillController`）到達時に判断を実施し、**共通化しない結論**となった（共通なのはrequestIdガードの数行のみで、モデルDLポリシー・通知の有無・状態数が異なる。詳細は [architecture](design/architecture.md) の2026-07-24追記）。

### 6.9 当日閲覧履歴

`NoteHistoryStore` が SharedPreferences に日付キー付きJSONで保存する。読み出し時に保存日≠今日なら空を返すため、日付が変わると履歴は自然消滅する（翌日への持ち越しなし）。最大10件、同一URIは先頭へ移動。`loadRandomNote`/`openNote` の成功時に記録し、さがすタブの「今日読んだノート」から `openNote` で開き直せる。

### 6.10 蒸留（Distill）

Reflect（AIタブ）で、AIが重要文を**選び**（生成しない）、ユーザーが確認した文だけを元ノートで `**太字**` にする＝プログレッシブ要約支援。設計判断の全体は [design/reflect_distill.md](design/reflect_distill.md)。実装の骨格：

1. `buildDistillSourceModel`（`DistillSourceModel`）が本文を、UTF-16オフセット保持＋Markdown構造認識（コードフェンス・テーブル・frontmatter・見出しを除外、インラインコードは太字内許容）で文分割する。表示用 `NoteSectionModel` は親子重複・見出しなし0件のため流用しない。
2. 一段目（AI不使用）：`selectDistillCandidates` がサリエンス（タイトル別・直近見出し別のbigram Dice）＋構造的重み（段落先頭/末尾/見出し直下）でスコアし、チャンク網羅で候補を絞る。
3. 二段目（AI 1回）：`PromptBuilder.buildDistillPrompt` が候補（ID＋原文）を意図ベースで渡し、`parseDistillResponseIds` が境界regex `\b S\d{3} \b` でIDだけ抽出（許可集合＝実際に渡した候補のみ）。
4. `applyDistillBold` がオフセット降順で `**` を挿入（装飾記号の挿入のみ・削除なし）。累積太字上限は編集対象本文比率30%（既存太字も分母/分子に含む）、短文は最重要1文の例外あり。
5. 保存は `DistillWriteRepository`（`DistillPersistence`）：原バイトSHA-256の二重競合確認 → キャッシュ構築＋fsync → 復旧レコードを `noBackupFilesDir` に原子確定 → SAF `"wt"` 一気書き → 出力ハッシュ検証。中断時は起動時に4分岐で復旧判定し、v1最小復旧UI（現在維持／元へ復元／別ファイルへ書き出し）を出す。空き容量が不明/不足なら中断。
6. 保存後は `openNote()` を使わず**本文専用リロード**（`reloadNoteBody`／`withDistillBodyReloaded`）：要約・関連・補記は維持し、生Markdown文脈に結び付くセクションチャット・クイズは破棄する。`DistillController` が requestId で全フローを直列化し、ノート切替でキャンセルする。

### 6.11 ReadingTrace（読書痕跡）

全経路で読書位置を自動記録し、Rediscoverで同じノートを引いた時だけ過去の読み方を再会カードへ出す。設計判断は [design/reflect_reading_trace.md](design/reflect_reading_trace.md)。

```text
ノート表示前
  → vault相対パス・タイトル・documentIdでSession開始
  → Composeが最終可視Markdownブロックとその可視割合を報告（5%刻み）
  → Controllerは最深blockIndex・可視割合・sectionTitleをメモリ上で更新
  → ノート切替（flush＝セッション終了）またはActivity.onStop（pause＝計測停止・セッション保持）
  → 能動読書10秒以上かつ本文描画済みなら訪問を記録
  → 開いた時点のVaultキーを添えて `_ReadingTraces/<sha256(relativePath)>.json`へ保存
  → Activity.onStart（resume）後に読み進めたら、同じ訪問を差し替える

Rediscover
  → 相対パスのサイドカーを照合
  → 生の最終訪問をカードへ即表示
  → 2訪問以上かつキャッシュ要約が古ければGemini Nanoで俯瞰要約
  → 成功時だけカードとサイドカーへ追記
```

サイドカーはschemaVersion、UTF-8バイト上限、canonical payloadのchecksumで検証する。訪問は直近30件を保持し、AI要約には説明対象の訪問件数を併記して、訪問が増えた時だけ作り直す。書き込みはSAF `"wt"` のベストエフォートで、破損時はカードを出さず次回訪問で作り直す。

到達率は `(最深ブロックindex + そのブロックの可視割合) / 総ブロック数` の切り捨てで、100%は最終ブロックの末端が画面へ入った場合だけに成立する。読書時間は背面にいた分を除いた能動時間で測り、背面化で書いた訪問は復帰後の読み進めで差し替える（1回の閲覧＝1訪問）。保存・照合要求はノートを開いた時点のVaultキーを運び、Gatewayが書込直前に現在のVaultと照合して不一致なら捨てる。

実装レビューで見つかった高優先度3件は上記で解消済み。残る未解決事項は次の2件。

- フォルダ索引が外部同期で増えたファイルをプロセス再起動まで認識しない。
- 30件の保持上限と累計訪問回数を分離していない。

詳細は [current_issues.md](_wip/current_issues.md) 2-4〜2-5。

---

## 7. SAF・Vaultアクセス層

### 7.1 走査方式

`NoteRepository` は `queryChildren()` にカーソル処理を集約し、`ArrayDeque` を用いたBFSでフォルダを再帰走査する。再帰関数ではないため、深いフォルダでコールスタックを消費しない。

取得列は次の4項目。

- document ID
- display name
- MIME type
- last modified

`lastModified` がプロバイダから返らない場合は `null` とし、UIでは更新日を表示しない。

### 7.2 読み書き

- 読み込みは `openInputStream()` と UTF-8 `bufferedReader()`。
- 補記保存は `createDocument()` と `openOutputStream()`。
- ファイル操作は `Dispatchers.IO` 上で実行する。
- Vault URIは SharedPreferences に保存し、SAFの永続URI権限と組み合わせて再利用する。

### 7.3 メタデータ解析

`parseMeta()` は以下を抽出する。

- 先頭YAML frontmatterの `tags`
- 先頭YAML frontmatterの `aliases`
- 本文全体の `[[wikilink]]`

frontmatterは `[a, b]` のインライン形式と、インデントされた `- item` のブロック形式に限定した簡易解析であり、完全なYAMLパーサーではない。`wikilinkTitles` は決定的チャンネルと現在ノートのリンク判定に使う。`tags` と `aliases` はAI推薦の候補肉付け（プロンプトの補助情報）に使い、さらに `tags` は本文シグナル再ランクの主スコア（現在ノートと候補のタグ一致）に使う。

### 7.4 タイトル正規化

Obsidianリンクとの照合時は次を除去する。

- 前後空白
- `|表示名`
- `#見出し`
- `^ブロックID`
- フォルダパス
- `.md` 拡張子（大文字小文字を無視）

照合用にはさらに小文字化する。正規化後タイトルをMapキーにするため、異なるフォルダに同名ノートがある場合は後にMapへ入った一方だけがAI返却タイトルの解決先になる。

---

## 8. AI層

### 8.1 `AiClient`

```kotlin
interface AiClient {
    suspend fun checkAvailability(): AiAvailability
    suspend fun generate(prompt: String): String
    fun downloadModel(): Flow<DownloadStatus>
}
```

AI利用側はこのインターフェースに依存する。実装は本番用 `AICoreClient` と手動UI確認用 `StubAiClient` の2つ。

### 8.2 モデル設定

`AICoreClient` は `ModelPreference.FULL` を指定して `Generation` クライアントを遅延生成する。FULLは「速度より精度を優先」の指定であり、実際に動くモデル世代（nano-v2 / v3）は端末のAICoreが決める（Pixel 10系はnano-v3）。状態は次のようにアプリ内の3状態へ変換する。

| ML Kit状態 | アプリ状態 |
|---|---|
| AVAILABLE | Available |
| DOWNLOADABLE / DOWNLOADING | NeedsDownload |
| その他・状態確認例外 | Unavailable |

### 8.3 直列化とタイムアウト

`generate()` は companion object の `Mutex` で直列化される。要約、関連推薦、検索、クイズ、補記、セクションAI、ReadingTrace俯瞰要約が同時に要求されても、モデル生成は1件ずつ実行される。

タイムアウト60秒はMutex取得後から計測するため、ロック待ち時間はタイムアウトに含まれない。ML Kitの `TimeoutCancellationException` は `AiTimeoutException` に変換し、通常の画面エラーとして扱えるようにしている。

この設計はモデルへの同時生成を避ける一方、先行生成が長いと後続機能が待たされる。ユーザーから見ると、各機能の60秒に加えてロック待ち時間が発生し得る。

**出力の途切れ検知**: `generate()` は応答の `finishReason` を確認し、`MAX_TOKENS`（出力トークン上限で打ち切り）なら `AiTruncatedException` を投げる。途切れた文章をそのまま保存・表示せず、通常のエラー表示に乗せるためである。以前は補記メモが途中で切れたまま保存される問題があった。

**出力トークン上限の制約（genai-prompt 1.0.0-beta2）**: `GenerateContentRequest` の `maxOutputTokens` は1〜256しか受け付けず、超過値は `IllegalArgumentException` で全生成が失敗する（実機で確認済み）。このため上限は明示設定せずSDKデフォルトのまま運用し、各機能のプロンプト側で「256トークン程度に収まる出力要求」に絞る方針をとる（クイズ2問固定・補記3項目各1行など）。

### 8.4 プロンプト入力上限

| 機能 | 本文上限 | 候補上限・出力 |
|---|---:|---|
| 要約 | 1,200文字 | 2〜4文 |
| 関連ノート | 600文字 | 候補最大40件（`ID｜タイトル — 本文/タグ等`を予算3,500文字内へ動的短縮）、ID応答で5件要求 |
| AIピッカー | 本文なし | タイトル最大40件、3件要求 |
| クイズ | フォーカス周辺1,200文字 | 入力量に応じて ○×2問／3択2問／4択1問（解説は4択のみ1文） |
| 蒸留 | 本文なし（候補文のみ） | 候補最大24件を予算1,500文字内へ収め、ID応答で最大6件要求 |
| AI補記 | 1,500文字 | 必須2セクション（補記すべき内容は3項目・各1行固定） |
| セクション要約 | 1,500文字 | 2〜4文 |
| セクション質問・Q&A | 1,500文字 | 質問候補最大3件 |
| ReadingTrace俯瞰要約 | 本文なし | 直近10訪問、1〜2文 |

蒸留を除き、すべて `String.take()` による文字数上限であり、トークン数や意味境界では切っていない。長いノートの後半はAI入力に含まれない。蒸留だけは例外で、ノート全体を文単位へ分割し（最大400文）、チャンク網羅を条件にスコア上位を候補化するため、後半の文もAI入力に到達し得る。

---

## 9. Markdown解析・描画

### 9.1 対応ブロック

- 見出し H1〜H6
- 段落
- 箇条書き・番号付きリスト
- fenced code block
- 水平線
- 引用
- タスクリスト
- パイプテーブル

番号付きリストは解析時に番号情報を保持せず、描画では通常の箇条書きになる。コードフェンスの言語指定も保持しない。

### 9.2 対応インライン記法

- `***太字イタリック***`
- `**太字**`
- `*イタリック*`
- `~~打ち消し線~~`
- `` `インラインコード` ``
- `[[Obsidianリンク]]`
- `[ラベル](URL)`

リンクは色と下線で装飾するだけで、タップ遷移やURLオープンは実装していない。画像、埋め込み、脚注、HTML、数式などは専用対応していない。

### 9.3 防御的処理

- 先頭の閉じられたYAML frontmatterは描画対象から除外する。
- テーブル中間の空セルを保持して列ずれを防ぐ。
- 強調記号は中身が空でなく、先頭・末尾が空白でない場合だけ成立させる。
- `[label](url)` は最初の `]` の直後が `(` の場合だけリンクとみなし、`arr[0]` などの誤検出を防ぐ。
- CRLFをLFへ正規化する。

### 9.4 描画効率

ノート画面では `buildNoteSectionModel()` が作成した `MarkdownBlock` をレンダラーへ渡し、セクション解析と描画による二重パースを避ける。インラインの `AnnotatedString` もテキスト単位で `remember()` する。

通常表示と全画面表示はどちらも同じパース済みブロックから描画する。両者は別々の `LazyListState` を持つ（NavHost遷移中の同時コンポーズで単一stateを2つの `LazyColumn` へ装着すると例外になるため）が、全画面は進入時にタブ側の位置から開始し、離脱時（✕・システムバック・FAB）にタブ側へ書き戻すことでスクロール位置を継承する。

---

## 10. 並行処理・ライフサイクル・キャッシュ

### 10.1 Job管理

`NoteViewModel` は次のJobを保持する。

- `noteLoadJob`
- `summaryJob`
- `relatedNotesJob`

`SectionChatController` は `openJob` と `answerJob` を保持する。`QuizController` と `AnnotationController` は生成Job・モデルDL Jobに加えて requestId を採番し、suspend地点の後に `isCurrent()` を確認してから状態を更新する。`ReadingTraceController` は再会照合/要約の `revealJob` とrequestIdを持ち、ノート切替後に古いカードを出さない。Jobキャンセルだけに頼らないのは、モデルDLコールバック等でキャンセルをすり抜ける完了通知があるため。ノート切替・Vault切替時は各Controllerのキャンセル処理で一括破棄する。

一方、次の処理は要求単位のJobを保持していない。

- 検索・スコープ内ランダム
- 補記一覧・削除
- 要約側のモデルダウンロードJob

そのため、短時間に複数要求できる経路では完了順による状態上書きの余地がある。UIでLoading中のボタンを無効化している箇所もあるが、すべての経路を構造的に保護しているわけではない。

### 10.2 CancellationException

要約、関連ノート、セクションAI、クイズ、補記の主要経路では `CancellationException` を再throwし、キャンセルを一般エラーに変換しない。

一方、`SearchPickerUseCase` など一部は広い `Exception` で捕捉しており、キャンセル方針は機能間で完全には統一されていない。

### 10.3 キャッシュ

| キャッシュ | キー | TTL | 破棄 |
|---|---|---:|---|
| Vault全体ノート | 現在Vault単位で1件 | 60秒 | Vault切替 |
| 検索スコープノート | `documentId`、ルートはnull | 60秒 | Vault切替 |
| ReadingTraceフォルダ索引 | Vault URI | なし（プロセス中保持） | Vault URI変化・I/O例外 |

キャッシュによりランダム表示や検索のたびのSAF全走査を避ける。外部のObsidian同期・編集結果は最大60秒反映が遅れる。空リストは全体キャッシュで再利用されないため、MarkdownがないVaultでは操作ごとに再走査する。

ReadingTrace索引はTTLを持たず、外部同期で後から追加されたサイドカーをプロセス再起動まで認識しない。これは通常の60秒キャッシュとは別の既知課題である。

---

## 11. エラー処理とフォールバック

### 11.1 良い点

- ノート読込失敗、AI生成失敗、モデルダウンロード失敗をsealed stateでUIへ伝える。
- 関連ノートはAI失敗時も規則ベース結果を維持する。
- セクション質問候補の失敗は要約・Q&A本体を壊さない。
- AIの空応答は要約文・チャット回答・補記で一定の防御がある。
- 補記保存前に最低限の必須セクション検証を行う。

### 11.2 注意点

- フォルダ一覧取得失敗は握りつぶされ、ユーザーには通知されない。
- 補記削除は `deleteDocument()` の `Boolean` を確認せず一覧を再読込する。失敗時は対象が残ることで間接的に分かるが、明示エラーは出ない。
- 閲覧履歴のJSONパース失敗は空履歴として扱い、ユーザーには通知されない（実害は履歴消失のみ）。
- `checkAvailability()` 自体の例外は `Unavailable` にまとめるため、非対応端末と一時的な状態取得失敗を区別できない。
- `ContentResolver.openInputStream()` が `null` の場合は空文字を返し、読込失敗と空ノートを区別しない。

---

## 12. データ保護・プライバシー

- ノート本文はアプリ内で読み取り、AI生成は端末内 Gemini Nano を利用する設計である。
- クラウドAI API、独自サーバー、解析SDKへの送信コードは存在しない。
- 初回モデル取得にはML Kit側のダウンロードが必要になる。
- Vaultへの書き込みは、`_AI補記` フォルダの作成と補記Markdown保存・削除、蒸留による既存ノートの上書き（`**` の挿入のみ・削除なし）、`_ReadingTraces`への読書痕跡JSON保存がある。蒸留の上書きは原バイトSHA-256の二重照合と出力ハッシュ検証を通し、中断時は `noBackupFilesDir` の復旧レコードから起動時に復旧判定する。読書痕跡はユーザーの`.md`に触れないベストエフォート設計で、checksum破損検知はあるが復旧ファイルと原子更新は持たない。
- `android:allowBackup="true"` であり、SharedPreferencesのVault URI文字列はバックアップ対象になり得る。実際のSAFアクセス可否は端末側の永続URI権限に依存する。
- ログ出力コードはなく、ノート本文やAIプロンプトをLogcatへ明示出力していない。

---

## 13. テスト状況

### 13.1 ユニットテスト内訳

| テストファイル | ケース数 | 主な対象 |
|---|---:|---|
| `NoteRepositoryTest.kt` | 5 | Markdown判定、wikilink・タイトル正規化、補記ファイル名安全化 |
| `NoteSnapshotTest.kt` | 5 | 上限付きバイト読込、UTF-8厳格判定、ハッシュ |
| `MarkdownParserTest.kt` | 7 | frontmatter、テーブル空セル、見出し、コード、CRLF、引用 |
| `InlineMarkdownTest.kt` | 11 | 強調、リンク、コード、打ち消し、誤検出防止 |
| `QuizResponseParserTest.kt` | 14 | 改行揺れ、前置き、欠落項目、不正な正解、○×/3択/4択の形式別パース |
| `QuizInputProfileTest.kt` | 4 | 入力量・コード比率からの出題形式決定 |
| `QuizPromptBuilderTest.kt` | 3 | 形式別クイズプロンプトの出力契約 |
| `SurroundingContextTest.kt` | 6 | フォーカス周辺テキスト構築（親子重複の回避・フォールバック） |
| `AnnotationComposerTest.kt` | 8 | 必須セクション、Markdown組立、インデント混入防止 |
| `SectionChatControllerTest.kt` | 4 | セクションチャットの状態遷移・破棄 |
| `QuizControllerTest.kt` | 5 | バックグラウンド生成・確認状態・破棄 |
| `AnnotationControllerTest.kt` | 2 | 確認状態・ノート切替時の破棄 |
| `DistillControllerTest.kt` | 14 | 蒸留フローの直列化、requestIdガード、保存後の状態遷移・復旧分岐 |
| `DistillWriteRepositoryTest.kt` | 15 | 二重ハッシュ照合、原子確定、出力ハッシュ検証、中断・容量不足 |
| `DistillRecoveryStoreTest.kt` | 3 | 復旧レコードの書込・読出・破棄 |
| `ReadingTraceControllerTest.kt` | 50 | 能動読書10秒閾値、最深到達点（可視割合込み）、追記上限、後続bind、二重flush、pause/resumeと訪問の差し替え、Vaultキーの持ち回り、再会カード、AI要約・キャンセル |
| `ReadingTraceJsonTest.kt` | 24 | JSON往復、checksum、UTF-8、必須項目・上限、要約キャッシュ整合 |
| `ReadingTraceStoreTest.kt` | 15 | ハッシュキー、保存/読込、破損・パス不一致、フォルダ/書込失敗、Vaultキーの受け渡しと不一致時の拒否 |
| `VaultPathTraversalTest.kt` | 10 | 相対パス付きBFS、除外フォルダ、循環、同名階層、非Markdown除外 |
| `EventKeyTest.kt` | 5 | Snackbar通知の発火判定キー |
| `ai/DistillPromptBuilderTest.kt` | 4 | 候補件数・文字予算内への収容、プロンプト出力契約 |
| `domain/DistillSourceModelTest.kt` | 13 | 文分割、UTF-16オフセット、コード/テーブル/frontmatter除外 |
| `domain/DistillCandidateScoringTest.kt` | 5 | サリエンス採点、構造的重み、チャンク網羅 |
| `domain/DistillResponseParserTest.kt` | 3 | ID抽出、許可集合外の棄却 |
| `domain/DistillTransformerTest.kt` | 5 | オフセット降順の `**` 挿入、太字比率上限、短文例外 |
| `domain/KeyedMemoCacheTest.kt` | 5 | LRUメモ化（成功時のみ格納） |
| `domain/RelatedCandidateContextTest.kt` | 10 | 候補の本文肉付け・入力予算内への整形 |
| `domain/RelatedCandidateIdTest.kt` | 9 | 一時ID採番と応答からのID抽出 |
| `domain/RelatedCandidateOrderingTest.kt` | 3 | 採番プレフィックス抽出 |
| `domain/RelatedCandidateRankingTest.kt` | 5 | 採点戦略注入の汎用ランキング |
| `domain/RelatedCandidateScoringTest.kt` | 10 | タイトル話題スコア（bigram Dice＋採番近接） |
| `domain/RelatedContextScoringTest.kt` | 6 | 本文シグナル再ランク（tags/snippet/title） |
| `ui/AiTabBadgeStateTest.kt` | 3 | AIタブバッジの優先順位 |
| `ui/ReadingTraceHeadlineTest.kt` | 8 | 経過日・セクション・到達率・訪問回数のカード文面 |
| `ui/ReadingProgressGeometryTest.kt` | 8 | 最終可視ブロックの可視割合（完全/一部/画面外/高さ未確定）、5%刻みの量子化 |
| `domain/SearchKeywordMatchingTest.kt` | 10 | bigram採点、1文字クエリの部分一致、フォールバックの並び順と一致0件除外、再現率カットの0件保持 |
| **合計（34ファイル）** | **282** | |

なお `NoteHistoryStore` は `Uri`・`org.json` がAndroid実装依存のため、素のローカルユニットテストでは検証していない（Robolectric等の導入が前提になる）。

### 13.2 実行結果

```text
./gradlew testDebugUnitTest
BUILD SUCCESSFUL
```

2026-07-25にAndroid Studio同梱JBRを指定してCLI実行し、コンパイルと全282ケースが成功した（ReadingTrace v1時点）。その後のレビュー修正4件で35ケースを追加し317ケースになったが、**この追加分の実行は未実施**（開発機のJDKが利用できずCLIビルド不可のため、Android Studio側での実行待ち）。

### 13.3 未カバー領域

- `NoteViewModel` の状態遷移（Controllerは一部テスト済み、網羅はしていない）
- `RelatedNotesUseCase` のオーケストレーション本体（候補のスコアリング・並べ替え・整形・ID解決・キャッシュの純ロジックは `RelatedCandidate*` / `RelatedContextScoring` / `KeyedMemoCache` のテストで個別にカバー済み。`AiClient` とSAF読込を絡めた `findRelated` 全体の結線は未カバー）と `SearchPickerUseCase` のAI応答解釈（キーワード採点・フォールバックの選抜は `SearchKeywordMatchingTest` でカバー済み）
- `NoteHistoryStore` の日付判定・重複排除（Android依存のため素のユニットテスト不可）
- `PromptBuilder` の出力契約（クイズ・蒸留は `QuizPromptBuilderTest` / `DistillPromptBuilderTest` でカバー済み。要約・関連・ピッカー・補記・セクション系の5経路は未カバー）
- SAFのカーソル走査、ファイル作成、削除（蒸留の書き込み経路のみ `DistillWriteRepositoryTest` でフェイクを使って検証済み）
- ReadingTraceのCompose実レイアウト上の可視量（算出そのものは純関数として検証済み）、Activity lifecycleを通した pause/resume の実挙動、SAF Gatewayでの実Vault照合、外部同期によるSAFフォルダ索引の変更
- Gemini Nanoの利用可否、ダウンロード、生成、タイムアウト
- Compose UI、NavigationBar/Rail、全画面遷移
- 連続操作時のキャンセルと競合
- 実際のObsidian Vaultを使ったinstrumentation/E2Eテスト

現在の317テストは、Android依存の薄い純粋ロジックの回帰防止には有効だが、アプリ全体の動作保証範囲は限定的である。ReadingTraceの高優先度3件はこの境界外で見つかったものであり、修正後も**Android側の実挙動は実端末確認でしか担保できない**。

---

## 14. コード品質評価

### 14.1 強み

1. **責務分割が明確**

   旧来の巨大ViewModelにすべてを置かず、検索・セクションAI・クイズ・補記・蒸留・読書痕跡をControllerへ分離している。Vaultへの破壊的書き込みを伴う蒸留は、書き込み経路自体も `DistillWriteRepository` / `DistillRecoveryStore` へ切り出している。読書痕跡も `ReadingTraceStore` / `ReadingTraceDocumentGateway` でAndroid依存境界を隔離している。

2. **UI状態が一元化されている**

   Compose側は `NoteUiState` を読むだけで、画面ごとの状態追跡が分散しにくい。

3. **AI非依存の価値を残している**

   関連ノートはwikilinkとファイル名規則で動作し、検索にはランダムモードと限定的フォールバックがある。

4. **端末負荷への配慮がある**

   AI生成の直列化、60秒タイムアウト、SAF走査キャッシュ、Markdownパース再利用を実装している。

5. **壊れやすい文字列処理が純粋関数化されている**

   クイズパース、補記Markdown、タイトル正規化、Markdown解析をAndroid I/Oから分離し、ユニットテスト可能にしている。

6. **Obsidian固有仕様への配慮がある**

   `.md`、wikilinkの別名・見出し・ブロック参照、frontmatter、補記専用フォルダを扱う。

### 14.2 残る技術的注意点

優先度は「現時点で確認できる影響範囲」に基づく。直ちに障害が起きることを意味しない。

| 優先度 | 項目 | 現状と影響 |
|---|---|---|
| 中 | Job管理の不統一 | クイズ・補記・蒸留はrequestId＋Jobで保護済みだが、検索・補記一覧等は未保護。将来UI導線が増えると古い完了結果が上書きし得る |
| 中 | 統合テスト不足 | SAF・端末AI・Navigationの不具合はローカルユニットテストで検出できない |
| 中 | ReadingTrace同期索引 | 外部同期で追加されたサイドカーをプロセス再起動まで認識しない |
| 中 | ReadingTrace累計回数 | 直近30件の保持数を「これまで開いた回数」と表示し、31回目以降が不正確 |
| 中 | AI入力が先頭固定長 | 長文ノートの中心・結論が後半にある場合、要約・クイズ・補記の品質が落ちる |
| 低 | 同名ノートの曖昧性 | AI推薦は候補ごとの一時ID（`idToNote`）で解決するため、同名・別URIも別IDになり不定にならない（ID応答方式で解消済み）。決定的チャンネルや除外判定で使う正規化タイトル集合には同名畳み込みが残る |
| 低 | YAML解析が簡易 | 複雑なYAML、引用、ネスト、複数行値には対応しない。AI推薦で使う tags/aliases の取りこぼしにつながり得る |
| 低 | Markdownが限定実装 | ordered list番号、クリック可能リンク、画像、埋め込み、数式などは未対応 |
| 低 | 削除失敗の通知不足 | 補記削除失敗時に明示メッセージがない |
| 低 | 状態取得失敗と非対応の同一視 | AI状態確認の一時エラーも「利用不可」として扱われる |

---

## 15. 今後の改善候補

本節は現状解析から導かれる候補であり、今回の解析書更新では実装変更していない。

1. （実装済み・2026-07-25）ReadingTraceの到達率をブロック内可視量まで考慮する方式へ変えた。最終ブロック末端を見ていなければ100%にならない。
2. （実装済み・2026-07-25）Sessionの pause/resume を実装し、背面時間を10秒判定から除外した。背面化で書いた訪問は復帰後の読み進めで差し替え、閲覧回数が膨らまないようにした。
3. （実装済み・2026-07-25）ReadingTraceの保存要求へVaultキーを持たせ、書込直前の照合で旧痕跡が新Vaultへ到達しないようにした。
4. （実装済み・2026-07-25）`SearchPickerUseCase` のフォールバックを候補数に関係なくbigramスコア順にし、一致0件は返さないことでUI文言と一致させた。
5. 検索にもJobまたはrequest IDを導入し、最後の要求だけが状態を更新できるようにする（クイズ・補記・蒸留は導入済み）。
6. `CancellationException` の再throw方針を全非同期処理で統一する。
7. `PromptBuilder` の出力契約、`SearchPickerUseCase` のAI応答解釈、Controller状態遷移のユニットテストを追加する（`RelatedNotesUseCase` の候補選定・スコアリング・整形・ID解決・キャッシュの純ロジックは分離済み `RelatedCandidate*` / `RelatedContextScoring` / `KeyedMemoCache` で充足。残るは `findRelated` の結線）。
8. Fake `ContentResolver` またはinstrumentationテストで、Vault走査・補記保存・削除に加え、ReadingTraceのActivity lifecycle・Vault切替・外部同期を検証する。
9. ReadingTrace索引にミス時再走査またはTTLを設け、保持履歴30件とは別に累計訪問数を扱う。
10. AI入力を単純な先頭切り出しから、見出し・冒頭・末尾・重要語を考慮した抽出へ発展させる。
11. （実装済み）AI推薦の同名ノート解決に一意な候補ID（`C01..` / `idToNote`）を導入した。決定的チャンネル・除外判定の正規化タイトル集合は同名畳み込みが残るため、必要ならそちらも一意化する。
12. AI非対応・モデル未準備・一時エラーのUXを要約・検索・クイズ・補記で統一する。

---

## 16. 解析時の確認事項

- 本解析は現行ソースコードを基準にし、過去の設計書ではなく実装との突合を優先した。
- 2026-07-20の更新はdocs再構成（変更履歴表・design/の新設）と同時に実施した。変更の経緯は [change_history.md](change_history.md)、設計判断は [design/](design/) を参照。
- 2026-07-21の更新は関連ノートAI推薦のPhase 1〜3（PR #27/#28/#29）を反映した。多段パイプライン化（タイトル話題スコア→本文肉付け→本文再ランク→ID応答）、tags/aliasesの利用開始、同名曖昧性の解消を §6.4・§7.3・§8.4・§14・§15 に反映。設計と知見は [related_notes_ai](design/related_notes_ai.md) を参照。
- 2026-07-22の更新（コミット `48a121b`）は §6.7 のクイズ適応出題と §6.10 の蒸留v1を本文へ追記したが、§2.1 コード規模・§3 ファイル構成・§13 テスト状況の数値を更新しておらず、実装と乖離した状態が残っていた。
- 2026-07-24の更新は上記の乖離を実測値で解消した。本番Kotlin 33ファイル・約5,900行 → 50ファイル・9,377行、テスト 10ファイル・60（本文中は41と不整合）→ 29ファイル・192ケースへ訂正。あわせて §3 のファイル構成へ `Distill*` 8ファイル・`NoteSnapshot.kt`・`QuizInputProfile.kt` を追加し、§1・§4・§8.4・§12・§13・§14 に蒸留とクイズ適応出題を反映。アプリ名の `Obsidian Mind` → `Vigilith AI` 改称（コミット `be1c0e9`）も反映した。同日、Android Studio 側で `testDebugUnitTest` を実行し192ケース全グリーンを確認済み。
- 2026-07-25の更新は未コミットのReadingTrace v1を反映した。本番Kotlin 57ファイル・10,630行、テスト34ファイル・282ケースへ再測定し、CLIで`testDebugUnitTest`全件成功を確認した。一方、JVMテスト成功を完成判定にはせず、実装レビューで見つかった到達率・Activity lifecycle・Vault切替の高優先度3件と、同期索引・累計回数の中優先度2件を明記した。
- 2026-07-25（同日・2回目）の更新は、上記の高優先度3件＋検索フォールバックの計4件の修正を反映した。本番Kotlin 59ファイル・10,926行、テスト36ファイル・317ケースへ再測定。§6.11 のフロー・§13 のテスト表・§14 のリスク表・§15 の改善候補を更新し、**§14 の「高」は0件になった**。ただし本修正分のテスト実行と実機確認は未実施であり、SAF照合とActivity lifecycleの実挙動はJVMテストでは担保できないことを §13.3 に明記した。
- 数値の再測定手順（次回更新時に同じ値を再現するため）:

  ```bash
  find app/src/main -name "*.kt" | wc -l && find app/src/main -name "*.kt" -exec wc -l {} + | tail -1
  find app/src/test -name "*.kt" | wc -l && find app/src/test -name "*.kt" -exec wc -l {} + | tail -1
  grep -rh --include='*.kt' -c '^\s*@Test' app/src/test | awk '{s+=$1} END {print s}'
  ```
