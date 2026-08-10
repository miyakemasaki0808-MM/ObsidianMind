# ソースコード解析書

**プロジェクト:** Vigilith AI（旧 Obsidian Mind。`strings.xml` の `app_name` を改称済み）

**解析日:** 2026-08-09（**「AI補記メモ」→「ノートへのひとこと」の作り直しに伴い、§1・§3・§5・§6.8・§8・§10・§11・§13 を更新**。§13.5 の instrumentation 内訳は 08-08 のまま。**他章は 07-27〜08-02 時点のまま**）

**対象ブランチ:** `feature/New_Function_No.2`（基準 `5c443d6`）

**対象実装:** 改善活動A案（非同期の境界）・C案（入出力の境界）・B案（依存と状態の境界）・
AI入力の抜粋化・D案（ライト配色のAA是正）・E案（リリース構成の整備）・
F-1（表示用Markdownの非同期化）・F-2（蒸留の復旧チェックと補記の後始末）・依存更新方針・
N-7（SAF境界の gateway 化）・N-3（ノート内画像）・
2026-08-05〜08 の外部レビュー対応（上限つきストリームの境界・遮断器の包含判定・受付漏れ検査）と
instrumentation の全段階に加えて、
**2026-08-09 の「ノートへのひとこと」（旧AI補記メモの全面作り直し・実機5巡）**まで。
文書のみのコミットは対象外

**対象範囲:** `app/src/main`、`app/src/test`、**`app/src/androidTest`**、**`app/src/debug`**、Gradle設定

**検証結果:** 2026-08-09 に `testDebugUnitTest` / `lintDebug` / `assembleDebugAndroidTest` をCLI実行し、
**JVM 844ケース全件グリーン・Lint Error 0 / Warning 0（更新系は12 hints）・Kotlinコンパイル警告 0** を確認済み。
**instrumentation は 37/37 成功・0 skipped**（Pixel 10 Pro Fold / Android 17・実機）。
警告はLintとKotlinの両方でビルドを落とす設定になった（§13.3）。
ダークモードは 2026-07-26 に実機で一巡し問題なし。**D案は 2026-07-31 にエミュレータで5画面＋ダークモードを一巡済み**で、
その過程で暗幕がカードに見える問題と操作ボタンの配置を是正した（ダーク側は据え置きのとおり変化なし）。
**E案は 2026-07-31 に確認済み**（`applicationId` の変更どおり別アプリとしてインストールされる。旧パッケージは端末に残る）。
**ReadingTrace v1 は 2026-07-31 にクローズ済み**（→ [reflect_reading_trace](../dev/design/reflect_reading_trace.md) §12）。
**「ノートへのひとこと」は 2026-08-09 に実機5巡を終えてクローズ済み**
（→ [reflect_remark](../dev/design/reflect_remark.md)。旧補記の課題 ANNOT-1〜3 と A11Y-1 も同時にクローズ）。
Vigilith Phase 3・A案／B案／C案の実機一巡は未実施

> **注意:** instrumentation が37件になったことは、**保護範囲が37件ぶん広がったことを意味しない。**
> 2026-08-08 の外部レビューで、**主張が実際に試していることより広い箇所が3件**指摘されている
> （連打テストが操作間で同期している／`ActivityScenario.recreate()` はプロセス死亡ではない／
> 端末AI生成は10経路中4経路）。→ §13.5・§13.6

---

## 1. エグゼクティブサマリー

Vigilith AI は、Android の Storage Access Framework（SAF）でユーザーが選択した Obsidian Vault を読み込み、Markdown ノートの閲覧・検索・関連ノート抽出・復習支援を行う Jetpack Compose アプリである。

AI機能はクラウドAPIではなく、ML Kit GenAI Prompt API を通じて端末内の Gemini Nano を使用する。現在実装されているAI機能は次のとおり。

- ノート全体の要約
- wikilink・ファイル名規則と組み合わせた関連ノート推薦
- 自然文によるノート選択（AIピッカー）
- 読書中セクションの周辺テキストからの適応出題Q&A（○×／3択／4択）
- ノートへのひとこと: ノートを読んだ相手役としてAIが**1文だけ**返し、ユーザーが返事を書き、
  それを受けてAIが**1往復だけ**応じる（旧「AI補記メモ」を 2026-08-09 に全面作り直し）
- 表示中セクションの要約、質問候補生成、セクション限定Q&A
- 蒸留（Distill）: AIが重要文を選び、ユーザー確認後に元ノートを `**太字**` へ書き換えるプログレッシブ要約支援
- ReadingTrace: 10秒以上読んだノートの最深到達点をサイドカーへ記録し、Rediscover時に「前回のあなた」カードと読み方の俯瞰要約を表示

Q&Aとひとことはバックグラウンド生成方式で、生成中もノート閲覧を継続でき、完了・エラーはSnackbarで通知される。**AIタブのバッジは「生成中」だけを示す** — ひとことの結果は専用画面で読むため、旧補記が持っていた「未確認」の概念（`isViewed`）ごと無くなった。ReadingTraceは対照的に、AI未準備・生成失敗を通知せず、生の痕跡だけを先に表示して黙って劣化する。AI以外の補助機能として、当日分のみの閲覧履歴（さがすタブ「今日読んだノート」）を持つ。

アーキテクチャは「単一 Activity + Compose Navigation + 単一 ViewModel」を入口としつつ、肥大化を避けるため要約・検索・セクションチャット・クイズ・ひとこと・旧補記ファイルの片付け・蒸留・読書痕跡・本文セクション解析・痕跡の整理を機能別 Controller（**10個**）に分割している。`NoteViewModel` は `Uri`・`ContentResolver`・`SharedPreferences` を扱うAndroid境界だけを担い、Controller間の調停と状態所有は Android API を呼ばない `NoteSessionCoordinator` が持つ。依存の組み立ては `NoteViewModelDependencies` へ外出しされている。ファイルI/Oは `NoteRepository`、AI判定を含む主要ロジックは UseCase、AI接続は `AiClient`、Markdown生成・応答パースは純粋ロジックへ分離されている。蒸留のVault書き戻しは `DistillWriteRepository` が専用の安全書き込み経路（ハッシュ照合・復旧レコード）を持ち、ReadingTraceは `_ReadingTraces` へのベストエフォートなサイドカー保存を持つ。**ひとことと返事もこのサイドカー（schema v5）へ入る** — 出力が1文になったので `.md` ファイルを作る形をやめた。

現時点の総評は次のとおり。

- 主要責務の分割、状態の一元管理、古いAI処理のキャンセル、生成タイムアウト、SAF走査キャッシュが実装され、継続的な機能追加に耐えやすい構造になっている。
- Markdownパーサー、**ひとことの応答検証**、クイズ応答パーサー、蒸留の文分割・採点・太字挿入、ReadingTraceのJSON・Controller・相対パス走査、Vigilith起動・表示状態・状態別モーション・配置計算、明暗トークンのコントラストなど、壊れやすい純粋ロジックにはユニットテストが整備されている（**844ケース**。内訳は §13.1）。
- ノート単位の Controller は requestId ＋ Job 追跡で古い結果の混入を防ぐ。Vault単位の要求（旧補記ファイルの一覧・削除・フォルダ一覧・痕跡の整理）は寿命が違うため、共有の `vaultGeneration` を `update` 直前に照合する二層構成になっている。**痕跡の削除だけは世代照合に加えて、洗い出した時点の Vault識別子を保持して照合する**（キーが相対パスのハッシュのため、別Vaultの同名パスと衝突しうる）。
- 状態は `NoteUiStateStore` だけが所有し、各Controllerへは機能別の `*StateWriter` を渡すため、担当外フィールドへの書き込みはコンパイル時に不可能である。ノート切替のジョブ停止と状態リセットは `onNoteChanged()` の1手に閉じている。
- パッケージ依存は `model` を葉とする一方向に整理され、`PackageDependencyTest` がimportを走査してCIで固定している。循環は残っていない。
- **SAF・画像復号・Compose描画・画面遷移・端末AI生成を実機で通す instrumentation が 37件そろい、2026-08-08 に 37/37 成功した**（→ §13.5）。土台は `src/debug` のテスト用 `DocumentsProvider` で、本番の `NoteRepository` / `SafVaultBrowser` をそのまま動かす。**ただし保証範囲は主張より狭い** — 連打の主張は撤回済み、`recreate()` はプロセス死亡を覆わず、端末AI生成は10経路中4経路だけである（→ §13.6）。**CIでは実行しない**判断を 2026-08-08 に確定した（→ §13.3）。
- ReadingTraceは主要経路とJVMテストが揃い、レビューで見つかった高優先度4件（ブロック数基準の到達率、Activity停止・再開、Vault切替中の起動済み保存、検索フォールバックの文言差）も解消済みである。ただしSAF照合とActivity lifecycleの実挙動はJVMテストの範囲外なので、実端末確認が完了判定に要る。
- 構造面の成長限界（依存の循環・ViewModelのテスト不能・状態の共有所有）は 2026-07-27 のB案で解消した。アクセシビリティとリリース構成は 2026-07-29〜30 のD案・E案で着手し、**ライトの文字トークンは実際に載る面すべてで4.5:1を満たす**ようになった。**下部ナビ帯の上でコントラストを取れなかったバッジ塗りは、ひとことの作り直しで対象ごと消えた**（未確認管理が不要になり、完了✓と失敗!の塗りが無くなった）。残る弱点は R8・署名が未設定であること、**ユーザーが書いた返事の退避手段が無いこと**、そして **instrumentation の実行がCIで担保されず「PR前に手で回す」運用のままであること**に移っている。

---

## 2. プロジェクト規模と技術構成

### 2.1 コード規模

| 区分 | ファイル数 | 行数・件数 |
|---|---:|---:|
| 本番 Kotlin | 121ファイル | 19,787行 |
| ユニットテスト Kotlin | 73ファイル | 15,151行、844テスト（テストクラスは72。残り1つは共有フェイク `FakeVault.kt`） |
| instrumentation テスト Kotlin | 9ファイル | 40テスト（**2026-08-08 に実機で 37/37 成功・0 skipped**。その後ひとことの作り直しで旧補記の作成テストを一覧・削除の検証へ差し替え、3件増えた。内訳は §13.5） |
| debug ソースセット Kotlin | 1ファイル | 301行（instrumentation 用の偽SAFプロバイダ。**release には入らない**） |
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
| applicationId | `com.vigilith.ai`（`namespace` は `com.example.newproject` のまま据え置き） |
| Java互換性 | Java 11（Kotlin の `jvmTarget` も 11 を明示） |
| buildTypes | `release` を定義。**R8は未有効**（`isMinifyEnabled = false`）・署名未設定 |
| 警告の扱い | Lint `warningsAsErrors` ＋ Kotlin `allWarningsAsErrors`。依存更新系3チェックのみ `informational`（落とさず hint として報告） |
| Compose BOM | 2024.09.03 |
| Navigation Compose | 2.7.7 |
| Core SplashScreen | 1.0.1 |
| Lifecycle | 2.8.7 |
| Coroutines | 1.9.0 |
| ML Kit GenAI Prompt | 1.0.0-beta2 |
| JUnit | 4.13.2 |
| AndroidX Core KTX | 1.13.1（従来は推移的。`edit {}` / `toUri()` を直接使うため明示） |
| Compose UI Test / Espresso | BOM準拠 / 3.7.0（instrumentation の土台。`ext:junit` は 1.3.0） |

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
│   ├── AndroidManifest.xml                     # allowBackup除外ルールの指定を含む
│   ├── java/com/example/newproject/
│   │   ├── MainActivity.kt                     # Activity、システムスプラッシュ／起動OP、Vault選択、NavHost、Snackbar通知、テーマ適用
│   │   ├── NoteViewModel.kt                    # Android境界の窓口（Uri・ContentResolver・prefs）、ノート読込、関連ノート、走査キャッシュ
│   │   ├── NoteViewModelDependencies.kt        # 本番依存の組み立て（差し替え口。DIライブラリは使わない）
│   │   ├── ai/
│   │   │   ├── AICoreClient.kt                 # AiClient、Gemini Nano接続、Mutex、タイムアウト
│   │   │   └── PromptBuilder.kt                # 各機能のプロンプト構築
│   │   ├── controller/
│   │   │   ├── NoteSessionCoordinator.kt       # 10 Controllerの生成と横断調停・Vault世代（Android API非依存）
│   │   │   ├── SummaryController.kt            # ノート要約とモデルDL待ちの再開
│   │   │   ├── SearchController.kt             # フォルダ検索・スコープキャッシュ・requestId／Job
│   │   │   ├── SectionChatController.kt        # セクション要約・質問・Q&A
│   │   │   ├── QuizController.kt               # 適応出題Q&Aのバックグラウンド生成・確認状態
│   │   │   ├── RemarkController.kt             # ひとことの生成・検証・返事の保存・映し返し
│   │   │   ├── AnnotationController.kt         # 旧補記ファイルの一覧・削除のみ（Vault単位。生成は持たない）
│   │   │   ├── DistillController.kt            # 蒸留の候補提示・選択・保存・復旧の直列化
│   │   │   ├── ReadingTraceController.kt       # 読書セッション・能動読書時間の積算・再会カード・AI俯瞰要約
│   │   │   │                                   #   ＋ひとこと／返事の保存と、書けなかったぶんの退避
│   │   │   ├── ReadingTraceCleanupController.kt # 痕跡の孤児の洗い出しと削除（Vault単位）
│   │   │   └── NoteSectionController.kt        # 表示用Markdown解析をMainの外で1回だけ行う
│   │   ├── data/
│   │   │   ├── NoteRepository.kt               # SAF走査・読書き・メタデータ解析
│   │   │   ├── AppPreferences.kt               # テーマ・VaultURIの永続化境界（SharedPreferences実装）
│   │   │   ├── VaultLocation.kt                # 選択中Vaultの共有参照（ViewModelと痕跡Gatewayが同じ実体を見る）
│   │   │   ├── NoteSnapshot.kt                 # 蒸留用の原バイト保持・上限付き読込・UTF-8厳格判定
│   │   │   ├── NoteHistoryStore.kt             # 当日分のみの閲覧履歴（SharedPreferences）
│   │   │   ├── SafDocuments.kt                 # SAF子要素列挙・ルート機能フォルダの探索/作成
│   │   │   ├── VaultPathTraversal.kt           # Vault相対パス付きBFS（Android非依存）
│   │   │   ├── DistillWriteRepository.kt       # 蒸留のSAF安全書き込み（二重ハッシュ照合・原子確定）
│   │   │   ├── DistillRecoveryStore.kt         # 中断復旧レコード（noBackupFilesDir）
│   │   │   ├── DistillHashing.kt               # SHA-256（原バイト／出力の照合用）
│   │   │   ├── ReadingTraceJson.kt             # サイドカーJSON・canonical checksum
│   │   │   └── ReadingTraceStore.kt            # 痕跡永続化境界・SAF Gateway・フォルダ索引・Vault照合
│   │   ├── domain/
│   │   │   ├── SummarizeUseCase.kt             # 要約ユースケース
│   │   │   ├── RelatedNotesUseCase.kt          # 規則ベース＋AI関連ノート抽出（多段パイプライン）
│   │   │   ├── SearchPickerUseCase.kt          # 自然文検索による3件選定
│   │   │   ├── SearchKeywordMatching.kt        # キーワード一致の採点・選抜（純関数）
│   │   │   ├── RelatedCandidateOrdering.kt     # 採番プレフィックス抽出（extractHexPrefix・共用）
│   │   │   ├── RelatedCandidateScoring.kt      # タイトル話題スコア（文字bigram Dice＋採番近接）
│   │   │   ├── RelatedContextScoring.kt        # 本文シグナル再ランク（tags/snippet/title）
│   │   │   ├── RelatedCandidateRanking.kt      # 採点戦略注入の汎用ランキング（rankByScore）
│   │   │   ├── RelatedCandidateContext.kt      # 候補の本文肉付け・入力予算内への整形
│   │   │   ├── RelatedCandidateId.kt           # 一時ID(C01..)採番と応答からのID抽出
│   │   │   ├── KeyedMemoCache.kt               # 汎用LRUメモ化（成功時のみ格納）
│   │   │   ├── DistillSourceModel.kt           # 蒸留用の文分割（UTF-16オフセット保持・Markdown構造認識）
│   │   │   ├── DistillCandidateScoring.kt      # 蒸留候補のサリエンス採点・チャンク網羅
│   │   │   ├── DistillResponseParser.kt        # 蒸留AI応答からのID抽出（許可集合で検証）
│   │   │   ├── DistillTransformer.kt           # オフセット降順の `**` 挿入・太字比率上限
│   │   │   ├── RemarkComposer.kt               # ひとこと／映し返しの応答検証・リンク差し戻し（純粋ロジック）
│   │   │   ├── QuizResponseParser.kt           # AIクイズ応答パース（純粋ロジック）
│   │   │   ├── QuizInputProfile.kt             # AI不使用の入力分類→出題形式決定（純粋ロジック）
│   │   │   ├── NoteTitleNormalizer.kt          # Obsidianタイトル正規化
│   │   │   ├── AiResponseParsing.kt            # AI返却タイトルの共通正規化
│   │   │   └── markdown/
│   │   │       ├── MarkdownBlocks.kt           # ブロック解析（Compose非依存の純粋ロジック）
│   │   │       └── NoteSections.kt             # 見出し単位セクションモデル
│   │   ├── model/                              # 依存グラフの葉（プロジェクト内の他パッケージをimportしない）
│   │   │   ├── NoteUiState.kt                  # 全UI状態の集約 data class・蒸留リロード時の保持規則
│   │   │   ├── NoteUiStateStore.kt             # 状態の唯一の所有者・機能別 *StateWriter・リセット契約
│   │   │   ├── NoteTypes.kt                    # NoteFile / NoteFolder / NoteMeta（層をまたぐ共有型）
│   │   │   ├── HistoryEntry.kt                 # 当日履歴の1件
│   │   │   ├── RelatedNote.kt                  # 関連ノートと AI推薦ステータス
│   │   │   ├── DistillModels.kt                # 蒸留の純データ（範囲・文・チャンク・候補・DistillLimits）
│   │   │   ├── ReadingTrace.kt                 # 読書痕跡モデル・上限・検証・Reflection（schema v5）
│   │   │   ├── RemarkProtocol.kt               # ひとことの「出すものが無い」表明語（ai と domain の共有点）
│   │   │   └── state/                          # 機能別の sealed state（Note/Summary/RelatedNotes/Search/
│   │   │                                       #   Quiz/Remark/AnnotationList/Distill/SectionChat/ReadingTraceCard）
│   │   └── ui/
│   │       ├── AppScaffold.kt                  # 5タブ、NavigationBar/Rail切替、AIタブバッジ、SnackbarHost
│   │       ├── ReadingProgressGeometry.kt      # 最終可視ブロックの可視割合・量子化（純関数）
│   │       ├── component/
│   │       │   ├── NoteComponents.kt           # タブと全画面の共用部品（読書位置報告・IconPill・本文パネル）
│   │       │   └── ReadingTraceCard.kt         # 「前回のあなた」カード・経過文面
│   │       ├── markdown/
│   │       │   ├── InlineMarkdown.kt           # インライン装飾のAnnotatedString生成
│   │       │   └── MarkdownRenderer.kt         # Compose描画
│   │       ├── screen/
│   │       │   ├── OpeningScreen.kt            # Vigilith起動OP（Compose描画・スキップ・完了通知）
│   │       │   ├── NoteReaderTab.kt            # ノートタブ本体（Markdown閲覧、Vigilithセクション操作）
│   │       │   ├── FullscreenNoteScreen.kt     # 全画面読書ルート（システムバー没入・最小AIインジケータ）
│   │       │   ├── SearchScreen.kt             # AI検索・ランダム抽出
│   │       │   ├── RelatedTab.kt               # 関連・AI推薦ノート一覧
│   │       │   ├── AiTab.kt                    # 要約、Q&A、AI補記の入口
│   │       │   ├── OptionsScreen.kt            # オプション入口（Vault選択・ダークモード切替）
│   │       │   ├── QuizScreen.kt               # クイズUI（○×／3択／4択）
│   │       │   ├── RemarkScreen.kt             # ひとこと・返事・映し返しの専用画面（非タブルート）
│   │       │   ├── AnnotationManagerScreen.kt  # 旧補記ファイルの一覧・削除
│   │       │   └── SectionChatSheet.kt         # セクションAIボトムシート
│   │       ├── theme/
│   │       │   ├── AppColors.kt                # ブランドパレット・明暗2組の実体・役割トークン（@Composableの窓口）
│   │       │   └── AppTheme.kt                 # AppColorScheme／LocalAppColors／AppTheme（ダークモード切替）
│   │       └── vigilith/
│   │           ├── VigilithHost.kt             # 5タブ共通配置・Note操作文脈・ドラッグ
│   │           ├── VigilithState.kt            # MainActivityから切り出したVigilithの配線（rememberVigilithState）
│   │           ├── VigilithMascot.kt           # アプリ内4状態WebP・補助光・AI状態バッジ
│   │           ├── VigilithMascotMotion.kt     # 翼・レンズ・コア・カプセルの純粋モーション
│   │           ├── VigilithMode.kt             # 既存状態からVigilith表示状態・AI操作4状態を導出する純関数
│   │           ├── VigilithOpeningMotion.kt    # ハロー→全身→名称→退場の純粋タイムライン
│   │           └── VigilithPlacement.kt        # clamp・画面変更・予約領域を扱う純粋配置計算
│   └── res/
│       ├── values/                             # app_name、テーマ（システムバーは透明・色はCompose側）
│       └── xml/                                # backup_rules / data_extraction_rules（バックアップ除外）
├── test/java/com/example/newproject/           # 70ファイル・844テスト（内訳は §13.1）
│   ├── architecture/PackageDependencyTest.kt   # importを走査してパッケージ依存の向きを固定
│   └── ui/theme/VibrantTextUsageTest.kt        # 画面からのonVibrant直接使用と文字色のcopy(alpha)を禁じる
├── androidTest/java/com/example/newproject/    # 9ファイル・37テスト（内訳は §13.5）
│   ├── InstrumentationSetupTest.kt             # Runner起動・対象Contextのみ（Composeルールを持たない）
│   ├── ComposeRenderingSetupTest.kt            # Compose描画とEspressoのUI同期
│   ├── ai/PromptTokenBudgetTest.kt             # 端末AIのトークン計測と能力診断
│   ├── ai/OnDeviceGenerationTest.kt            # 本番プロンプトでの実生成（10経路中4経路）
│   ├── data/VaultScanInstrumentationTest.kt    # 実物SAFでの走査・補記CRUD・読取失敗の注入
│   ├── data/NoteImageGatewayInstrumentationTest.kt # 実物BitmapFactoryでの復号と上限の境界
│   ├── ui/NoteReadingFlowTest.kt               # 描画抑止・全画面への位置引き継ぎ・進捗報告
│   ├── ui/ActivityRecreationTest.kt            # Activity再生成（プロセス死亡は覆わない → §13.6）
│   └── ui/TabNavigationTest.kt                 # タブ履歴契約（連打の主張は撤回 → §13.6）
└── debug/java/com/example/newproject/testing/
    └── FakeVaultDocumentsProvider.kt           # instrumentation 用の偽SAF。**release には入らない**

.github/workflows/ci.yml                        # PR・mainへのpushで testDebugUnitTest / lintDebug / assembleDebugAndroidTest
```

---

## 4. アーキテクチャ

### 4.1 レイヤーと依存方向

```text
Compose UI / MainActivity
          │ ユーザーイベント、StateFlow購読（uiState と darkTheme は別Flow）
          ▼
     NoteViewModel                        ← Android境界のみ（Uri / ContentResolver / prefs）
       │   └── NoteViewModelDependencies  ← 依存の組み立て（差し替え口）
       ▼
     NoteSessionCoordinator               ← 横断調停・状態所有（Android API を呼ばない）
       ├── NoteUiStateStore ──► 機能別 *StateWriter を各Controllerへ配る
       ├── SummaryController
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
MarkdownBlocks / NoteSections / QuizResponseParser / QuizInputProfile /
AnnotationComposer / NoteTitleNormalizer / AiResponseParsing /
DistillSourceModel / DistillCandidateScoring / DistillResponseParser / DistillTransformer /
SearchKeywordMatching / RelatedCandidate* / RelatedContextScoring / KeyedMemoCache /
VaultPathTraversal / ReadingTraceJson / ReadingProgressGeometry /
VigilithMode / VigilithMascotMotion / VigilithOpeningMotion / VigilithPlacement
```

この構成は厳密なマルチモジュールClean Architectureではない。すべて同一 `:app` モジュール内にある。ただしパッケージ間の依存は次の一方向だけを許可し、`PackageDependencyTest` がimportを走査してCIで固定している（違反があればビルドが落ちる）。

| パッケージ | importしてよいプロジェクト内パッケージ |
|---|---|
| `model` | なし（葉） |
| `ai` | `model` |
| `domain` | `model`, `ai` |
| `data` | `model`, `domain` |
| `controller` | `model`, `data`, `domain`, `ai` |
| `ui` | `model`, `domain` |

さらに **`model` / `domain` / `controller` の3層は `android.*` も import しない**（2026-08-01〜02）。
プロジェクト内の向きと同じく `PackageDependencyTest` がCIで固定する。`ui` を対象にしないのは
Compose 自体が `androidx.*` だから。`data` とルート（`NoteViewModel` / `MainActivity`）は
SAF・`ContentResolver` を実際に扱う境界なので依存してよい。
| ルート（`MainActivity` / `NoteViewModel` / `NoteViewModelDependencies`） | すべて。ただし**どの層からも参照されない** |

ルートを層として明示しているのは、そこを経由すれば任意の循環を作れてしまうため。層に含めないと、ファイルをルートへ移すだけで検査を回避できる抜け道になる。生成クラス `R` は層構造の一部ではないので対象外。

`NoteFile`・`NoteFolder`・`NoteMeta`・`HistoryEntry`・`RelatedNote`・蒸留の純データ型は、層をまたいで共有されるため葉の `model` に置かれている。`model` は `android.net.Uri` に依存するが、プロジェクト内の他パッケージには依存しない。

### 4.2 状態の単一ソース

`NoteUiStateStore` が `MutableStateFlow<NoteUiState>` を所有し、`NoteSessionCoordinator` を経由してUIには読み取り専用の `StateFlow` として公開する。`MainActivity` は `collectAsStateWithLifecycle()` で購読する。

Controller は独自の Flow を作らず、`NoteUiStateStore` から受け取った**機能別 Writer** の担当スライスだけを更新する。担当外フィールドは型として渡されないため、コンパイル時に書けない。

| 担当 | 受け取るWriter | 更新する状態 |
|---|---|---|
| `SummaryController` | `SummaryStateWriter` | `summaryState` |
| `SearchController` | `SearchStateWriter` | `folders`、`selectedFolder`、`foldersError`、`searchState` |
| `SectionChatController` | `SectionChatStateWriter` | `sectionChat`、`isSectionChatSheetVisible` |
| `QuizController` | `QuizStateWriter` | `quizState` |
| `AnnotationController` | `AnnotationStateWriter` | `annotationState`、`annotationListState` |
| `DistillController` | `DistillStateWriter` | `distillState`（`noteState` は読み取り専用の `currentNote()` で参照） |
| `ReadingTraceController` | `ReadingTraceStateWriter` | `readingTraceCard`（読書中Session自体はController内部） |

Writer を持たない `noteState`・`relatedNotesState`・`wikilinkTitles`・`todayHistory`・`vaultSelected` は `NoteSessionCoordinator` 専用のメソッドで更新する。関連ノートは走査キャッシュ（`Uri` を持つ `NoteFile`）に依存して `NoteViewModel` 側に残っているため、Controller化されていないのがこの非対称の理由である。

表示テーマ（`darkTheme`）は `NoteUiState` に含めず、`NoteViewModel` が独立した `StateFlow<Boolean>` として持つ。`MainActivity` はこれだけを `AppTheme` の外で購読するので、他の状態が変わってもアプリ最上位までは再評価されない。

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
- `sectionChat`: セクションAIのセッション。ノート内でセッションを持たない場合は `null`
- `isSectionChatSheetVisible`: シートの表示有無。セッションの有無と分離しており、閉じても同じノート内なら生成結果を保持して吹き出しから再表示できる
- `folders`、`selectedFolder`、`searchState`: 検索タブ用
- `wikilinkTitles`: 現在ノートから抽出したリンク先タイトル
- `todayHistory`: 当日分の閲覧履歴（最大10件）

表示テーマは `NoteUiState` の外に出ており、`NoteViewModel.darkTheme`（独立 `StateFlow<Boolean>`）が持つ。OS設定には追従せず、オプション画面での明示切替だけで変わる（`SharedPreferences` に永続化）。

`quizState`/`remarkState` の `sourceTitle` は「どのノートの生成結果か」を表し、Snackbar通知の判定に使う。`isViewed`（未確認管理）を持つのは `quizState` だけで、**ひとことは結果を専用画面で読むため持たない**。通知の発火判定キーは `toEventKey()` 拡張関数（`model/state/`）が組み立てる。

各 sealed state は `model/state/` 配下の機能別ファイルに分かれ、集約する `NoteUiState`（16フィールド）だけが `model/NoteUiState.kt` にある。

ノートまたはVaultの切替時は `NoteUiStateStore` の `withNoteScopedReset()` により、要約・関連・クイズ・ひとこと・セクションチャット・再会カードを一括リセットする。検索スコープと閲覧履歴はVault切替時だけ別途リセットする（`resetVaultScoped()`）。`noteState` と `wikilinkTitles` はVault切替でも残す — 切替直後に `loadRandomNote` が走って差し替わるため、ここで落とすと画面が一瞬空白になるだけだからである。

ノート切替では**ジョブ停止と状態リセットが必ず対**になる。`NoteSessionCoordinator.onNoteChanged()` がこの2つと `Loading` への遷移を1手で行い、呼び出し側へ分解した形を公開しない。分けて公開すると「状態だけ消したが旧ジョブは生きている」中間状態を作れてしまい、実際にそれが旧ノートのAI結果が後着する不具合の形だった。

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
| 全画面 | `quiz` | クイズ（○×／3択／4択を入力量から自動選択） |
| 全画面 | `remark` | ひとこと・返事・映し返し |
| 全画面 | `annotation_manager` | 旧補記ファイルの削除管理 |

`navigateToTab()` は `popUpTo`、`saveState`、`restoreState`、`launchSingleTop` を使い、トップレベルタブのバックスタック増殖を抑えつつ状態を復元する。

### 5.2 画面幅対応

`AppScaffold` は `WindowSizeClass` を参照し、Expanded幅では左側 `NavigationRail`、それ以外では下部 `NavigationBar` を使用する。選択タブのインジケータは `Aqua`（Indigo地に埋もれないアクセント）。全画面ノート・クイズ・ひとこと・旧補記管理の全画面ルートではタブUIを表示しない。全画面ノートは進入中にシステムバー（ナビ＋ステータス）も隠し、離脱時にナビバーのみ復元する（ステータスバーはアプリ全体仕様どおり隠したまま）。

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
- ひとことの入口。ボタンは**常に専用画面へ渡す**（ラベルは「ノートへのひとこと／考えています…」の2種のみ）
- Reflect（蒸留）の起点。AIが選んだ重要文を候補リストで提示し、ユーザーが確認した文だけを元ノートへ `**太字**` として書き戻す（§6.10）
- クイズの起点は読書画面の吹き出しシートへ移動した（フォーカス周辺クイズ）。AIタブにQ&Aボタンはない
- モデルダウンロード時は進捗を表示
- タブアイコンのバッジは**生成中だけ**を示す（`resolveAiTabBadgeState`）。結果は専用画面で読むため未確認管理を持たず、旧補記の完了✓・失敗!の塗りバッジは対象ごと消えた

#### オプション

- 「Vaultを変更」: フォルダ選択のやり直し（現在の選択状態をサブタイトル表示）
- 「AI補記メモを削除」: 旧補記ファイルの一覧で1件削除・全件削除、削除前に確認ダイアログ
- 「ダークモード」: 明暗テーマのトグル。OS設定には追従せず、ここでの明示切替だけで変わる。`SharedPreferences` に保存し、プロセス再起動なしで即時反映する

#### 横断: Snackbar通知

Q&A・ひとことの生成開始／完了／失敗は `MainActivity` の `LaunchedEffect` がSnackbarで通知する。完了・失敗の通知にはアクション（見る／詳細／もう一度）が付き、タップで結果画面を開く。**ひとことは `isViewed` を持たない**（結果を読む場所が入口と同じなので、確認済みを追う必要がない）。表示済みイベントキーを `rememberSaveable` に記録し、画面回転による再表示を抑止する。全画面ノート（`note_fullscreen`）表示中はSnackbarを抑制し、AIの状態は全画面の最小FABが担う。

---

## 6. 主要機能のデータフロー

### 6.1 Vault選択と復元

```text
OpenDocumentTree
  → 読み書き可能な永続URI権限を取得
  → NoteSessionCoordinator.onVaultChanged() へ入る
      ① 記録中の読書セッションを保存せず破棄（旧ノートの痕跡が新Vaultへ書かれるのを防ぐ）
      ② vaultGeneration を進める（走行中のVault単位要求を無効化）
      ③ ここで初めて新しいVaultを指す（VaultLocation更新・SharedPreferencesへURI保存・
        全体ノートキャッシュと関連ノートキャッシュを破棄）
      ④ 検索スコープキャッシュ・旧補記一覧を破棄し、ノート単位ジョブを一括停止
      ⑤ 閲覧履歴を破棄し、状態をVaultスコープでリセット
  → ランダムノートを1件読み込む
```

**①〜③の順序が要点で、逆にすると壊れる。** 新しいVaultを指してから記録中セッションを捨てると旧ノートの痕跡が新Vaultへ入り、世代を進める前に指し替えると既に結果を持ち帰っている要求が旧世代のまま素通りする。この順序は調停クラス側が持ち、`NoteViewModel` はURIの反映処理を `applyLocation` として渡すだけになっている。

導線はノートタブ（未選択時のみ）とオプションの「Vaultを変更」の2つ。

次回起動時は SharedPreferences のURIを復元し、`vaultSelected = true` にする。起動直後にノートを自動読込する処理はなく、ユーザーがランダム表示するか検索結果を開くまで `noteState` は Idle のままである。

なお `MainActivity` は `setContent` 直後に、コールド起動時のみ `OpeningScreen`（起動OP）を本体の代わりに表示する。新規起動の判定は `savedInstanceState == null`（回転・Fold開閉・プロセス復元では非nullのため再生しない）。OP終端の背景は着地（Noteタブ）と同じ `ReadingGradient` に揃え、継ぎ目なく本体へ入れ替える。詳細は [design/opening_animation](../dev/design/opening_animation.md) を参照。

### 6.2 ランダムノート表示

```text
loadRandomNote()
  → 旧ノートに属するJobをキャンセル
  → noteState = Loading、ノート依存状態をリセット
  → Vault全体をBFS走査（60秒以内ならキャッシュ利用）
  → _AI補記・_ReadingTraces を除いた .md から random()
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
| Available | 1,200文字以内の本文抜粋を含むプロンプトで2〜4文を生成（予算内は原文、超過時は骨格＋冒頭＋末尾。§8.4） |
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

候補の選定→肉付け→再ランク→ID応答の多段パイプラインである（設計と経緯は [related_notes_ai](../dev/design/related_notes_ai.md)）。

1. **タイトル話題スコアで全Vaultをランク**し上位40候補に絞る（`rankRelatedCandidates`）。スコアはタイトルの文字bigram Dice係数（主）＋採番プレフィックス近接の加点（従）。決定的チャンネルに出したタイトルは上限適用の前に除外する。
2. **候補本文を上限付き並列で読む**（`Semaphore(8)`）。各候補を本文冒頭スニペット・タグ・aliasesで肉付けし、`URI+lastModified` でキャッシュする（成功時のみ格納）。
3. **現在ノートの本文シグナルで40件を再ランク**する（`relatedContextScore`）。タグ一致（主）＋スニペット類似＋タイトル類似で並べ替え、件数は変えない。
4. 再ランク後の並びで一時ID（`C01..`）を採番し、候補を入力予算（3,500文字）内へ動的短縮して整形する。現在本文は600文字以内の抜粋（§8.4）にしてAIへ渡す。**現ノートのタグは抜粋とは別経路**で、`parseMeta()` から取って3の再ランクに使う（抜粋側では frontmatter が落ちるため）。
5. **AIにはIDだけ返させ**、行頭付近のIDのみ抽出して実ノートへ解決する（`parseCandidateIds`）。決定的結果とのURI重複を除いて最大5件返す。

AIが利用不可またはモデル未準備でも、規則ベース結果は表示できる。AI生成で例外が起きても `RelatedNotesResult.Error` にはせず、規則ベース結果と `AiRecommendationStatus.Error` を返す設計である。個別候補の本文読込失敗（キャンセル以外）は該当候補のみタイトルで続行し、推薦全体を巻き添えにしない。

### 6.5 さがす（AIピッカー）

検索スコープは次の仕様である。

| 選択 | 対象 |
|---|---|
| ルート直下 | Vault直下の `.md` のみ。非再帰 |
| 第一階層フォルダ | 選択フォルダ以下を再帰走査 |

検索タブでは `_AI補記` を除外しないため、作り直す前に生成された旧補記ファイルも検索候補になり得る。

自然文検索では候補が40件を超える場合だけ、クエリとファイル名の文字bigram重複数で上位40件に絞る（再現率カット）。その後AIへタイトル一覧を渡し、最大3件を取得する。AIが利用不可・未ダウンロードの場合はフォールバックする。

フォールバックは候補数に依らず bigramスコア順で選び、**一致0件は返さない**（0件時は画面が「見つかりませんでした。」になる）。これにより画面文言の「キーワード一致で表示しています」が常に真になる。採点・選抜は `domain/SearchKeywordMatching.kt` の純関数が持ち、フォールバック（0件は落とす）と再現率カット（Nanoへ渡すので0件も残す）で戻り値の扱いを分けている。1文字クエリはbigramを作れないため部分一致で救済する。

ランダムモードはAIを使用せず、`shuffled().take(3)` で選ぶ。

検索とランダムは同じ `searchState` を更新するため、`SearchController` は `searchJob` 1本と `activeRequestId` を共有し、新しい要求が前の要求をキャンセルする。`SearchPickerUseCase` は結果型（`PickerResult`）でエラーを返す設計だが、`CancellationException` だけは畳まず再throwする（畳むと中断せず正常に戻り、追い越された古い要求がエラー表示になるため）。

### 6.6 セクションAI

ノート本文は一度Markdownブロックへパースし、描画とセクション判定で共有する。現在の `LazyColumn` 先頭可視ブロック以前にある最も近い見出しを現在セクションとする。

セクション範囲は、対象見出しから「同レベルまたは上位レベルの次の見出し」の直前までで、配下の小見出しを含む。見出しが存在しない位置ではノート全体を対象にする。

吹き出しを開くと次の順でAIを使用する。

1. セクション本文の1,500文字以内の抜粋（§8.4）から要約を生成する。セクションは通常この予算に収まるが、親セクションが子を内包する構造では超えることがある。
2. 同じセクションから最大3件の質問候補を生成する。
3. ユーザーが候補をタップすると、セクション本文と会話履歴を渡して回答を生成する。

回答プロンプトは「セクションに書かれていない内容を推測しない」よう制約する。自由入力欄はなく、現在のUIではAIが生成した質問候補のタップだけが質問入力経路である。

シート下部の「この部分でクイズ」からフォーカス周辺クイズ（6.7）を起動できる。クイズはセクションチャットセッションに従属し、新しいセッションの開始時（`openSection`）とセッションの明示終了時（確認を終了）に破棄される。シートを閉じて同一セッションを再表示した場合は保持される。

### 6.7 適応出題Q&A（○×・3択・4択／フォーカス周辺クイズ）

`QuizController` がバックグラウンドで生成する。入口は読書画面の吹き出しシート（6.6）で、入力はノート全体ではなく「フォーカスセクションの周辺テキスト」である。

1. シートの「この部分でクイズ」タップで、シート対象セクションを `sectionModel` から同定し、`NoteSectionModel.surroundingContext()` が周辺テキスト（約1,200文字）を構築する。**これは目標値であり上限ではない**（ブロック単位で足すため超過し得る）ので、プロンプト直前で1,200文字の抜粋（§8.4）を通す。セクションを核に前後のブロックを交互に加えて広げる方式で、親セクションが子を内包する構造でも本文が重複しない。見出しなし・擬似セクションはノート先頭にフォールバックする。
2. 生成開始時に `QuizState.Loading(sourceTitle=セクション名)` を立てる（待機画面なし）。
3. `checkAvailability()` で分岐する。Unavailableはエラー、NeedsDownloadはモデルDL後に自動再開、Availableは即生成。
4. 周辺テキストを**AI不使用で分類**し（`QuizInputProfile`）、素材量に応じて出題形式を切り替える：コード比率45%以上→3択2問、本文180字未満または文シグナル2以下→○×2問、本文700字以上かつ文シグナル6以上→4択1問、それ以外→3択2問。○×・3択は解説なし・4択のみ短い解説を1文とし、問題／選択肢に文字数上限を指示する。これは、常に4択2問＋解説を要求すると出力上限（256トークン程度、8.3参照）を超えて `MAX_TOKENS` で全結果が破棄され、クイズ生成エラーになっていた問題への対策（詳細は [design/section_ai_chat.md](../dev/design/section_ai_chat.md)）。
5. `Q:` 行を問題開始として `parseQuizResponse(raw, format)` がフィールドを抽出する。○×は `TRUE`/`FALSE`/`○`/`×`/`正しい`/`誤り` 等を許容、多択は正解レターを**単語境界regex `\b[A-D]\b`** で抽出し `B.`・`(B)`・`B) 選択肢文`・`The answer is B` 等の崩れを救済する（単語内の文字は誤検出しない・範囲外の `D` 等は棄却）。選択肢数（3/4）は応答実体に合わせ、必須フィールド欠落や範囲外の正解記号は捨てる。
6. パース結果が0件なら `QuizState.Error`、あれば `QuizState.Success(isViewed=false)` とし、Snackbarで通知する（AIタブバッジの対象外）。
7. Q&A画面ではユーザー選択後に正誤、正解、解説を表示し、次の問題へ進む。

生成中の再タップはLoadingガードで無視する。requestIdによる `isCurrent()` チェックで、ノート切替後の古い結果混入を防ぐ。クイズの寿命はセクションチャットセッションに従属する（6.6）。

なお「もう2問」の追い生成（既出問題の除外リスト付き再生成）を一度実装したが、小型モデルには同一素材からの追加出題が難しく成功率が低かったため廃止した（経緯は [design/section_ai_chat.md](../dev/design/section_ai_chat.md)）。

### 6.8 ノートへのひとこと（旧「AI補記メモ」）

**2026-08-09 に全面作り直した。** 旧補記は「4つの分類ラベル＋補記3行」をMarkdownファイルとして
Vaultへ保存していたが、**出力枠（256トークン）がゼロサムなのに、行動を変えないラベルが
価値のある側を圧迫していた**（→ [reflect_remark](../dev/design/reflect_remark.md) §0）。
枠を1文へ集中させ、保存先も痕跡サイドカーへ移した。

**生成（`RemarkController`）**

1. 現在ノートの抜粋（1,500文字）と、候補ノート**3件＋各80文字の本文スニペット**を入力にする。
   候補は AI推薦を先、既にwikilink済みのものを最後に置き、現ノート自身は除く。
   スニペットは関連ノートAIが再ランクで既に読んだ値を通すだけで、**追加のI/Oは無い**。
2. AIへ**1文だけ**（80〜120字）出させる。問い**か**関連ノート接続のどちらか一方で、
   出力言語は日本語に固定する（ノート本文がコードだけでも日本語で返す）。
3. 候補ノートは `[[C03]]` のIDで参照させ、`composeRemark()` が実タイトルへ差し戻す
   （蒸留・関連ノートと同じID契約）。
4. `composeRemark()` が5つの検査を通す。**指示ではなく検査で守るのが要点。**

| 検査 | 落ちるもの |
|---|---|
| `NothingToSay` | `NONE` 表明・空応答 |
| `TooShort` / `TooLong` | 15字未満・160字超 |
| `UnknownLink` | 候補集合に無いIDを `[[ ]]` で参照 |
| `NotGrounded` | **リンクを除いた地の文**が原文と4文字も一致しない（一般論） |
| `LinkedQuestion` | リンクを含むのに文末が問い・勧誘（「か」／「ましょう」） |

`NothingToSay` だけが「本当に出すものが無い」で、残りは**再試行が効く**（`isModelFailure`）。
UIは前者を `Empty`、後者を `Unusable` として別の文言で出す。
冒頭の「あなた」は後処理で剥がす（**捨てずに剥がす** — 文体の好みで文ごと捨てると空振りが増える）。

**返事と映し返し**

5. ユーザーが返事を書く（保存 **8,000字**まで完全保存。2,000字超は静かに注記するだけで切らない）。
6. 返事を**先に**保存してから、AIが**1往復だけ**応じる（`mirrored`）。問いは検査で禁じる。
   AIへ渡す返事は先頭＋末尾で400字へ抜粋する（**保存とAI入力の予算は別物**）。

**保存（`ReadingTraceController`）**

`Reflection(remark, remarkedAt, reply, repliedAt, mirrored)` の1組として
`_ReadingTraces/*.json`（schema v5）へ入る。**Vaultに `.md` は作らない。**

- **ひとことは離脱時の書き込みへ相乗りさせる。** 痕跡ファイルは離脱・背面化でしか作られず、
  検証は訪問1件以上を要求するので、初読で「生成できたら保存」と書くと必ず黙って失われる。
- **返事だけは即時保存する。** 生成物は作り直せるが、書いた言葉は作り直せない。
  結果は `Saved` / `Held`（預かった）/ `Lost`（どこにも無い）の3値で、
  **`Held` を「保存済み」と呼ばない**（離脱時の書き込みで確定する）。
- **書けなかった痕跡は Controller 側へ退避する**（Vault＋相対パスをキー・上限8件）。
  `flush()` は保存を起動した直後にセッションを捨てるので、セッションへ戻しても誰も読まない。
  退避するのは返事ではなく**完成済みの `ReadingTrace`** — 返事だけでは「既存へ載せ直す」しかできず、
  **痕跡の新規作成が失敗した回を復旧できない**。
- **返事を預かっているときは離脱時の門番（10秒・1ブロック）を通す。**
  書いた事実はスクロールより強い関与で、通さないと画面に「保存中」と出たまま消える。

**表示**

結果は AIタブではなく専用画面（`RemarkScreen`・非タブルート）で読む。
AIタブのボタンは**常に**この画面へ渡す（Idleでも）— 状態に依存した入口にすると、
ノート切替で `Idle` に戻った後に保存済みへ辿れなくなる。
保存済みの読み込みは**この画面を開いたときだけ**行い、ノート表示の経路にSAF読みを増やさない。
Rediscover の再会カードには「前回の返事を見る」の1行だけ置き、中身は画面側で読む。

**旧補記ファイルは消さない。** 作り直す前に生成された `_AI補記/*.md` はVaultに残るので、
`AnnotationController` は**一覧と削除だけ**を持って残っている（「作らないが、片付けられる」）。
`AnnotationComposer` / `AnnotationFileWriter` / `createAnnotationFile` は撤去済み。


### 6.9 当日閲覧履歴

`NoteHistoryStore` が SharedPreferences に日付キー付きJSONで保存する。読み出し時に保存日≠今日なら空を返すため、日付が変わると履歴は自然消滅する（翌日への持ち越しなし）。最大10件、同一URIは先頭へ移動。`loadRandomNote`/`openNote` の成功時に記録し、さがすタブの「今日読んだノート」から `openNote` で開き直せる。

### 6.10 蒸留（Distill）

Reflect（AIタブ）で、AIが重要文を**選び**（生成しない）、ユーザーが確認した文だけを元ノートで `**太字**` にする＝プログレッシブ要約支援。設計判断の全体は [design/reflect_distill.md](../dev/design/reflect_distill.md)。実装の骨格：

1. `buildDistillSourceModel`（`DistillSourceModel`）が本文を、UTF-16オフセット保持＋Markdown構造認識（コードフェンス・テーブル・frontmatter・見出しを除外、インラインコードは太字内許容）で文分割する。表示用 `NoteSectionModel` は親子重複・見出しなし0件のため流用しない。
2. 一段目（AI不使用）：`selectDistillCandidates` がサリエンス（タイトル別・直近見出し別のbigram Dice）＋構造的重み（段落先頭/末尾/見出し直下）でスコアし、チャンク網羅で候補を絞る。
3. 二段目（AI 1回）：`PromptBuilder.buildDistillPrompt` が候補（ID＋原文）を意図ベースで渡し、`parseDistillResponseIds` が境界regex `\b S\d{3} \b` でIDだけ抽出（許可集合＝実際に渡した候補のみ）。
4. `applyDistillBold` がオフセット降順で `**` を挿入（装飾記号の挿入のみ・削除なし）。累積太字上限は編集対象本文比率30%（既存太字も分母/分子に含む）、短文は最重要1文の例外あり。
5. 保存は `DistillWriteRepository`（`DistillPersistence`）：原バイトSHA-256の二重競合確認 → キャッシュ構築＋fsync → 復旧レコードを `noBackupFilesDir` に原子確定 → SAF `"wt"` 一気書き → 出力ハッシュ検証。中断時は起動時に4分岐で復旧判定し、v1最小復旧UI（現在維持／元へ復元／別ファイルへ書き出し）を出す。空き容量が不明/不足なら中断。
6. 保存後は `openNote()` を使わず**本文専用リロード**（`reloadNoteBody`／`withDistillBodyReloaded`）：要約・関連・ひとことは維持し、生Markdown文脈に結び付くセクションチャット・クイズは破棄する。`DistillController` が requestId で全フローを直列化し、ノート切替でキャンセルする。

### 6.11 ReadingTrace（読書痕跡）

全経路で読書位置を自動記録し、Rediscoverで同じノートを引いた時だけ過去の読み方を再会カードへ出す。設計判断は [design/reflect_reading_trace.md](../dev/design/reflect_reading_trace.md)。

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

いずれも未解決。

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
- 旧補記フォルダの列挙は `queryChildren()`。**書き出す経路はもう無い**（ひとことは痕跡サイドカーへ入る）。
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

`generate()` は companion object の `Mutex` で直列化される。要約、関連推薦、検索、クイズ、ひとこと（＋映し返し）、セクションAI、ReadingTrace俯瞰要約が同時に要求されても、モデル生成は1件ずつ実行される。

タイムアウト60秒はMutex取得後から計測するため、ロック待ち時間はタイムアウトに含まれない。ML Kitの `TimeoutCancellationException` は `AiTimeoutException` に変換し、通常の画面エラーとして扱えるようにしている。

この設計はモデルへの同時生成を避ける一方、先行生成が長いと後続機能が待たされる。ユーザーから見ると、各機能の60秒に加えてロック待ち時間が発生し得る。

**出力の途切れ検知**: `generate()` は応答の `finishReason` を確認し、`MAX_TOKENS`（出力トークン上限で打ち切り）なら `AiTruncatedException` を投げる。途切れた文章をそのまま保存・表示せず、通常のエラー表示に乗せるためである。以前は旧補記メモが途中で切れたまま保存される問題があった。

**出力トークン上限の制約（genai-prompt 1.0.0-beta2）**: `GenerateContentRequest` の `maxOutputTokens` は1〜256しか受け付けず、超過値は `IllegalArgumentException` で全生成が失敗する（実機で確認済み）。このため上限は明示設定せずSDKデフォルトのまま運用し、各機能のプロンプト側で「256トークン程度に収まる出力要求」に絞る方針をとる（クイズ2問固定・ひとこと1文など）。**ひとことはこの制約を設計の中心に据えている** — 枠がゼロサムなので、分類ラベルを同時に出させると価値のある側が削られる（→ §6.8）。

### 8.4 プロンプト入力上限

| 機能 | 本文上限 | 候補上限・出力 |
|---|---:|---|
| 要約 | 1,200文字 | 2〜4文 |
| 関連ノート | 600文字 | 候補最大40件（`ID｜タイトル — 本文/タグ等`を予算3,500文字内へ動的短縮）、ID応答で5件要求 |
| AIピッカー | 本文なし | タイトル最大40件、3件要求 |
| クイズ | フォーカス周辺1,200文字 | 入力量に応じて ○×2問／3択2問／4択1問（解説は4択のみ1文） |
| 蒸留 | 本文なし（候補文のみ） | 候補最大24件を予算1,500文字内へ収め、ID応答で最大6件要求 |
| ひとこと | 1,500文字 | 1文（80〜120字）。候補ノート3件＋各80字。映し返しは返事を400字へ抜粋して渡す |
| セクション要約 | 1,500文字 | 2〜4文 |
| セクション質問・Q&A | 1,500文字 | 質問候補最大3件 |
| ReadingTrace俯瞰要約 | 本文なし | 直近10訪問、1〜2文 |

上限はいずれも UTF-16 文字数で、トークン数や意味境界では切っていない。**ただし切り方は先頭固定長ではない**（2026-07-28 に変更。設計は [ai_input_excerpt](../dev/design/ai_input_excerpt.md)）。

- 上表の本文を持つ7経路は、`PromptBuilder` ではなく呼び出し側が `buildNoteExcerpt(content, 上限)` で `NoteExcerpt` を作って渡す。`PromptBuilder` に `take()` は残っていない。
- **予算内のノートはMarkdownを解析せず原文をそのまま渡す**（移行前のプロンプトと文字列単位で同一）。
- **超過時だけ**、level 3までの見出しを全体から均等選抜した骨格＋冒頭60%＋末尾40%を、`## Note outline` / `## Beginning excerpt` / `(omitted)` / `## Ending excerpt` の明示ラベル付きで構成する。単一ブロックが枠を超える場合も捨てずに切り、コードブロックは閉じフェンスを復元する。
- 超過時は表示用パーサ（`parseMarkdownBlocks`）を通すため、frontmatter除去・コードフェンス言語名の消失・段落内改行の空白化・箇条書き記号の `-` への統一が起きる。**リストの番号と入れ子段数は保持される**（2026-08-02）。
- 抜粋時のみ226文字の注意書き（`ABRIDGED_NOTICE_PREFIX`）を本文直前へ置く。**この注意書きは上限の内側から支払う**ため、AIへ渡る本文由来領域の合計が上限を超えない。
- 解析コストは1MBノートで約460ms（デスクトップJVM実測）あるため、抜粋生成のみ `Dispatchers.Default` で実行する（`excerptDispatcher`）。

蒸留だけはこの経路に乗らない。ノート全体を文単位へ分割し（最大400文）、チャンク網羅を条件にスコア上位を候補化する独自方式である。

---

## 9. Markdown解析・描画

### 9.1 対応ブロック

- 見出し H1〜H6
- 段落
- 箇条書き・番号付きリスト・タスクリスト（**入れ子と番号を保持**。1つの `ListBlock` にまとまる）
- fenced code block
- 水平線
- 引用
- パイプテーブル

リスト項目は `ListItem(depth, marker, text, checked)` で、入れ子段数・番号（`1.` と `1)`、先頭ゼロを含む原文表記）・タスクのチェック状態を保持する。段数の算出はCommonMarkにもObsidianにも準拠しない独自の寛容規則で、設計は [design/markdown_rendering.md](../dev/design/markdown_rendering.md)。箇条書き記号 `-` / `*` / `+` の違いだけは意図的に落とす。コードフェンスの言語指定も保持しない。

### 9.2 対応インライン記法

- `***太字イタリック***`
- `**太字**`
- `*イタリック*`
- `~~打ち消し線~~`
- `` `インラインコード` ``
- `[[Obsidianリンク]]`
- `[ラベル](URL)`

リンクは色と下線で装飾するだけで、タップ遷移やURLオープンは実装していない。画像、埋め込み、脚注、HTML、数式などは専用対応していない。また段落の遅延継続（リスト項目に続く字下げなしの本文行）にも対応せず、別ブロックへ分かれる。

### 9.3 防御的処理

- 先頭の閉じられたYAML frontmatterは描画対象から除外する。
- テーブル中間の空セルを保持して列ずれを防ぐ。
- 強調記号は中身が空でなく、先頭・末尾が空白でない場合だけ成立させる。
- `[label](url)` は最初の `]` の直後が `(` の場合だけリンクとみなし、`arr[0]` などの誤検出を防ぐ。
- CRLFをLFへ正規化する。

### 9.4 描画効率

ノート画面では `buildNoteSectionModel()` が作成した `MarkdownBlock` をレンダラーへ渡し、セクション解析と描画による二重パースを避ける。インラインの `AnnotatedString` もテキスト単位で `remember()` する。

通常表示と全画面表示はどちらも同じパース済みブロックから描画する（2026-07-31 以降は実際に共有している。それ以前は各Composableが自前の `remember` で同期解析しており、この記述だけが先行していた）。パースは `NoteSectionController` が `Dispatchers.Default` で1回だけ行い、`StateFlow<NoteSectionModel?>` を `MainActivity` が両画面へ配る。**結果が届くまでノート本文は描かない** — 描くと `MarkdownNoteContent` のフォールバックが最大1MBをMain上で解析し直すため。両者は別々の `LazyListState` を持つ（NavHost遷移中の同時コンポーズで単一stateを2つの `LazyColumn` へ装着すると例外になるため）が、全画面は進入時にタブ側の位置から開始し、離脱時（✕・システムバック・FAB）にタブ側へ書き戻すことでスクロール位置を継承する。

---

## 10. 並行処理・ライフサイクル・キャッシュ

### 10.1 Job管理

`NoteViewModel` は `noteLoadJob` と `relatedNotesJob`（どちらも `Uri` 解決を伴うため窓口側に残る）を保持する。この2本も `NoteSessionCoordinator` の `cancelHostJobs` フック経由で同じ契約から止まるので、停止処理が2箇所に分かれることはない。

Controller側は次のとおり。`SummaryController` は要約JobとモデルDL Jobに加えて requestId を持つ。`SectionChatController` は `openJob` と `answerJob`。`QuizController` と `AnnotationController` は生成Job・モデルDL Jobに加えて requestId を採番し、suspend地点の後に `isCurrent()` を確認してから状態を更新する。`ReadingTraceController` は再会照合/要約の `revealJob` とrequestIdを持ち、ノート切替後に古いカードを出さない。`SearchController` は検索とスコープ内ランダムで `searchJob` 1本と requestId を共有し（同じ `searchState` を奪い合うため、検索⇄ランダムの切替でも前の要求を止める）、フォルダ列挙は寿命が違うので `foldersJob` を別に持つ。

Jobキャンセルだけに頼らないのは、モデルDLコールバック等でキャンセルをすり抜ける完了通知があるため。ノート切替時は `NoteSessionCoordinator.cancelNoteScopedJobs()` が一括で止める。

**世代は二層になっている。** ノート単位の要求（要約・DL・クイズ・ひとこと生成・チャット・蒸留）は各Controllerが `activeRequestId` を自前で持ち、ノート切替で無効化する。Vault単位の要求（旧補記一覧・削除・フォルダ一覧）は `NoteSessionCoordinator.vaultGeneration` を共有し、Vault切替でだけ無効化する。混ぜられないのは、旧補記の管理画面がノートと無関係で、ノートを開き直しただけで一覧が消えるのは誤りだからである。`vaultUri` の比較で代用しないのは、A→B→A と選び直したときに同じ値になり検出できないため。

照合は `update` の直前1箇所に集約されている（`SummaryController` / `SearchController` の `setStateIfCurrent`、`AnnotationController.reloadList`）。呼び出し側に `if (!isCurrent) return` を重ねると、テストで検出できない等価な分岐が増えるためである。

**要求単位で未追跡の経路は残っていない。** 以前は旧補記の一覧・削除と要約側のモデルDL Jobが該当したが、いずれも 2026-07-26 に解消した。

### 10.2 CancellationException

要約、関連ノート、セクションAI、クイズ、ひとこと、検索の主要経路では `CancellationException` を再throwし、キャンセルを一般エラーに変換しない。

`SearchPickerUseCase` は 2026-07-26 まで広い `Exception` でキャンセルも捕捉し `PickerResult.Error` へ畳んでいた。**この形は呼び出し側の `catch` では防げない**（中断せず正常に戻るため、そのまま状態更新に到達する）。UseCase 側の再throwと、`SearchController` の requestId ガードの二重で塞いだ。UseCase が結果型でエラーを返す設計を採る場合は、キャンセルだけは例外のまま通す必要がある。

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
- AIの空応答は要約文・チャット回答・ひとことで一定の防御がある。
- **ひとことは5つの検査（表明語・長さ・ID実在・原文根拠・リンクと問いの排他）を通してから保存する。** プロンプトに書いた契約は検査へ移すのが原則で、旧補記が「守られたか誰も確認していない」状態だったことの是正にあたる。

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
- `android:allowBackup="true"` だが、`random_note_prefs`（Vault の SAF URI・テーマ設定・当日分の閲覧履歴タイトル）はバックアップ・端末移行の両方から除外している。`dataExtractionRules`（`res/xml/data_extraction_rules.xml`／API 31以上）と `fullBackupContent`（`res/xml/backup_rules.xml`／API 30以下）を併記し、minSdk 26 の全レンジを覆う。API 31以上では `cloud-backup` に加え `device-transfer` からも除外する（SAFの永続URI権限は移行先端末で無効になり、復元しても壊れた参照が残るだけのため）。`allowBackup` 自体は true のまま残し、将来バックアップしたいデータが出た時に個別許可できるようにしている。
- ログ出力コードはなく、ノート本文やAIプロンプトをLogcatへ明示出力していない。

---

## 13. テスト状況

### 13.1 ユニットテスト内訳

| テストファイル | ケース数 | 主な対象 |
|---|---:|---|
| `NoteRepositoryTest.kt` | 4 | Markdown判定、wikilink・タイトル正規化 |
| `NoteSnapshotTest.kt` | 5 | 上限付きバイト読込、UTF-8厳格判定、ハッシュ |
| `BoundedNoteReadTest.kt` | 9 | 用途別の読込予算、上限到達の判定、多バイト文字の末尾切り |
| `MarkdownParserTest.kt` | 22 | frontmatter、テーブル空セル、見出し、コード、CRLF、引用、リストのマーカー保持（区切り記号・先頭ゼロ・巨大桁）、段数の算出規則5つ、タブの4列展開、タスク混在、`blocksToMarkdown` の往復 |
| `InlineMarkdownTest.kt` | 11 | 強調、リンク、コード、打ち消し、誤検出防止 |
| `QuizResponseParserTest.kt` | 14 | 改行揺れ、前置き、欠落項目、不正な正解、○×/3択/4択の形式別パース |
| `QuizInputProfileTest.kt` | 4 | 入力量・コード比率からの出題形式決定 |
| `QuizPromptBuilderTest.kt` | 3 | 形式別クイズプロンプトの出力契約 |
| `SurroundingContextTest.kt` | 6 | フォーカス周辺テキスト構築（親子重複の回避・フォールバック） |
| `domain/RemarkComposerTest.kt` | 36 | ひとことの5検査（表明語・長さ・ID実在・原文根拠・リンクと問いの排他）、冒頭二人称の除去、映し返しの問い禁止、AI入力用の返事抜粋、保存上限との整合 |
| `SectionChatControllerTest.kt` | 4 | セクションチャットの状態遷移・破棄 |
| `QuizControllerTest.kt` | 5 | バックグラウンド生成・確認状態・破棄 |
| `AnnotationControllerTest.kt` | 10 | 旧補記ファイルの一覧・削除、Vault世代照合、ハンドルの取り直し防止 |
| `RemarkControllerTest.kt` | 22 | ひとことの生成・検証落ち・候補選定（3件＋抜粋・wikilink済みは後回し）・返事の保存結果3値・保存済みの読み戻し |
| `SummaryControllerTest.kt` | 6 | モデルDL待ちの要約がノート切替をすり抜けないこと、DL進捗の照合 |
| `SearchControllerTest.kt` | 4 | スコープ切替時の結果破棄・同一スコープ再選択の保持 |
| `NoteSessionCoordinatorTest.kt` | 16 | Vault/ノート切替の一斉停止と一斉初期化、リセット登録漏れ検出、旧結果の後着防止、Vault世代 |
| `NoteUiStateStoreTest.kt` | 2 | 各Writerが担当スライスだけを更新すること、ノート読込開始の単一通知 |
| `DistillControllerTest.kt` | 14 | 蒸留フローの直列化、requestIdガード、保存後の状態遷移・復旧分岐 |
| `DistillWriteRepositoryTest.kt` | 15 | 二重ハッシュ照合、原子確定、出力ハッシュ検証、中断・容量不足 |
| `DistillRecoveryStoreTest.kt` | 3 | 復旧レコードの書込・読出・破棄 |
| `ReadingTraceControllerTest.kt` | 78 | 能動読書10秒閾値、最深到達点（可視割合込み）、追記上限、後続bind、二重flush、pause/resumeと訪問の差し替え、Vaultキーの持ち回り、再会カード、AI要約・キャンセル、**ひとこと／返事の保存と、書けなかったぶんの退避・再書き込み** |
| `ReadingTraceJsonTest.kt` | 49 | JSON往復、checksum、UTF-8、必須項目・上限、要約キャッシュ整合、**v1→v5 の各版からの読み込み互換**（旧版の正規形をテスト側に写し取って固定） |
| `ReadingTraceLimitsTest.kt` | 2 | 上限どうしの整合（全フィールドを上限まで詰めてもファイル読込上限に収まること） |
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
| `ui/AiTabBadgeStateTest.kt` | 3 | AIタブバッジは生成中だけを示すこと（結果が出ても残らない） |
| `ui/ReadingTraceHeadlineTest.kt` | 8 | 経過日・セクション・到達率・訪問回数のカード文面 |
| `ui/ReadingProgressGeometryTest.kt` | 8 | 最終可視ブロックの可視割合（完全/一部/画面外/高さ未確定）、5%刻みの量子化 |
| `ui/VigilithOpeningMotionTest.kt` | 8 | ハロー・全身・名称の登場順、保持区間、退場、終端・範囲外入力 |
| `ui/VigilithModeTest.kt` | 10 | Idle/Summarizing/Distilling/Messengerの優先順位、蒸留3工程、モデル取得除外、カードdismiss、全画面・シート非表示 |
| `ui/VigilithMascotMotionTest.kt` | 10 | Summaryの片翼案内、蒸留の断片収集・両翼保持・下線、Messengerの着地・一度だけの発光、入力clamp・出力範囲、Summarizing>Idleのレンズ輝度差 |
| `ui/VigilithPlacementTest.kt` | 6 | 四辺clamp、Fold再配置、ラベル寸法、Snackbar / IME予約領域、狭小画面 |
| `ui/VigilithAccessibilityTest.kt` | 2 | 状態・対象節をまとめたTalkBack文言、回答／要約の区別 |
| `ui/VigilithStatusDerivationTest.kt` | 11 | セクションチャット／全画面AIの状態導出（要約×クイズの合成） |
| `ui/theme/AppColorContrastTest.kt` | 24 | 明暗の役割トークンのコントラスト比。文字は4.5:1・塗りと記号は3:1を**強制**する（**既知未達は解消済み** — 未達だったナビ帯上のバッジ塗りは 2026-08-09 に対象ごと消えた） |
| `domain/SearchKeywordMatchingTest.kt` | 10 | bigram採点、1文字クエリの部分一致、フォールバックの並び順と一致0件除外、再現率カットの0件保持 |
| `architecture/PackageDependencyTest.kt` | 1 | パッケージ依存の向き（ルートパッケージ経由の抜け道を含む） |
| `domain/NoteExcerptBuilderTest.kt` | 18 | 抜粋の予算不変条件（注意書き・ラベル込み）、境界、見出しの均等選抜、単一巨大ブロック（段落・コード・表・リスト）、frontmatter除去、リストの番号と段数がモデルへ届くこと、記法増加後も全予算で上限を超えないこと、中略なしの連続レイアウト |
| `ai/PromptBuilderExcerptRegressionTest.kt` | 8 | 7プロンプトの出力文字列の固定、抜粋時だけ注意書きが出ること |
| `architecture/NoteExcerptThreadingTest.kt` | 1 | 抜粋生成が本番7経路すべてで `Dispatchers.Default` 側にあること（呼び出し箇所の一覧ごとソース走査で固定） |
| `ui/theme/AppColorContrastTest.kt` | （上記に含む） | トークン×**実際に載る面**の総当たり、半透明面の実効色、グラデーション停止色との比、明暗の反転 |
| `ui/theme/VibrantTextUsageTest.kt` | 2 | 画面からの `onVibrant` 直接使用と、文字色への任意の `copy(alpha)` をソース走査で禁じる |
| `domain/ImageLinkResolutionTest.kt` | 29 | 画像参照の解析と索引照合（完全パス→ファイル名の順、曖昧・外部URL・空） |
| `domain/ImageDecodePolicyTest.kt` | 17 | 復号可否の拡張子判定、寸法・ピクセル数の上限、間引き倍率、**`TooLarge`/`Broken` の切り分け順序** |
| `domain/BoundedInputStreamTest.kt` | 13 | **上限の境界（-1／ちょうど／+1）を単一read・配列read・`skip`・混在で固定**、`len == 0` の契約、`available()` の丸め、先読みが1回だけであること |
| `VaultImageIndexStoreTest.kt` | 12 | 画像索引のTTL・再走査の歯止め・Vault世代 |
| `ui/NoteImageTextTest.kt` | 19 | 画像の失敗理由ごとの文言と代替テキスト |
| `domain/ReadingTraceOrphansTest.kt` | 27 | 孤児判定の遮断器（フォルダ単位・読取失敗の伝播・**ルートと別サブツリーの混在**）、削除直前の三値再走査 |
| `architecture/ReviewFindingsLedgerTest.kt` | 6 | レビュー指摘が受付簿へ全件載ること、処遇の語彙、**起票の参照先が実在すること**、ID重複の拒否 |
| `architecture/InstrumentationTestShapeTest.kt` | 1 | **`@Test` の戻り値が `void` でなくなる書き方をソース走査で禁じる**（→ §13.5の脚注） |
| **合計（72クラス）** | **844** | |

なお `NoteHistoryStore` は `Uri`・`org.json` がAndroid実装依存のため、素のローカルユニットテストでは検証していない（Robolectric等の導入が前提になる）。

### 13.2 実行結果

```text
./gradlew testDebugUnitTest
BUILD SUCCESSFUL
```

2026-07-25にAndroid Studio同梱JBRを指定してCLI実行し、コンパイルと全282ケースが成功した（ReadingTrace v1時点）。その後もVigilithの起動・表示状態・状態別モーションを追加し、2026-07-26のPhase 3では配置計算6件とアクセシビリティ文言2件を追加した。**352ケース全件グリーン**、`assembleDebug`、Pixel 10 Pro Foldへの上書きインストール成功を確認した（Phase 3時点）。

その後、テーマ基盤リファクタで状態導出11件とコントラスト15件、Vigilithの輝度差1件を追加。さらに改善活動A案（要約Controllerの世代管理・検索スコープ）、C案（用途別の読込予算・累計回数）、B案（切替の一斉停止と一斉初期化・状態Writer・パッケージ依存）で計46件を追加して49ファイル・425ケースとなった。2026-07-28のAI入力の抜粋化で25件を追加して450ケース。2026-07-29〜30のD案・E案でコントラスト検証を作り直し（面の総当たり・半透明の実効色・停止色との比・明暗の反転）、使用箇所の禁止テストを新設して9件増え53ファイル・459ケースとなった。2026-07-31 の F-1（表示用Markdownの非同期化）で12件、F-2（蒸留の復旧チェック・補記の後始末）で15件を追加して56ファイル・486ケース。2026-08-01 の N-7 段階1〜6（`DocumentRef` 化）で1件、同段階7（`VaultBrowser`）で**さがす／補記の世代照合・検索実行・走査キャッシュ・削除失敗の件数・ハンドル取得の契約を21件追加**し、**57ファイル・508ケースとなった**。2026-08-02 の Markdownリスト構造（入れ子・番号・タスク混在）で17件、バッジ記号の基準見直しで2件を追加し、59ファイル・583ケースとなった。
その後 N-3（ノート内画像）で画像の参照解決・復号方針・索引・文言を追加し、
**2026-08-05〜08 の外部レビュー対応**で上限つきストリームの境界13件・遮断器の混在ケース・
受付漏れ検査6件・テスト形状の検査1件を足した。**2026-08-09 のひとことの作り直しで91ケース増え、現在は70ファイル・844ケース全件グリーン**。

**この期間に増えた分は、性質が2つに分かれる。** 片方は本番の欠陥を閉じるもの
（`BoundedInputStreamTest` の境界、`ReadingTraceOrphansTest` のルート混在、
`ImageDecodePolicyTest` の判定順序）。もう片方は**テストや文書の運用そのものを検査に変えるもの**で、
`ReviewFindingsLedgerTest`（レビュー指摘の取りこぼし）と
`InstrumentationTestShapeTest`（`@Test` の戻り値）がこれにあたる。
後者は**プロダクトコードを1行も守らないが、守る仕組みが壊れたことを検出する。**

**コントラスト系のテストは2度作り直している。** 初版は全色を最も明るい `panel` の上で測り、2版目も
グラデーション上の文字を最も有利な停止色でだけ測っていた。どちらも全緑のまま基準を割っており、
**「テストが通ること」と「正しい対象を測っていること」は別**であることが実際に2度起きた。
現在は停止色を `AppColorScheme` の単一ソースから読んで総当たりし、表に載らない書き方は
`VibrantTextUsageTest` がソース走査で禁じる。

JBRは `/Applications` 直下ではなく `/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home` にあるため `/usr/libexec/java_home` では検出されない。`JAVA_HOME` へ明示指定して `./gradlew testDebugUnitTest --offline` で実行する。

### 13.3 自動実行（CI）

2026-07-26に `.github/workflows/ci.yml` を新設した。PR と `main` への push で `./gradlew testDebugUnitTest`・`./gradlew lintDebug`・`./gradlew assembleDebugAndroidTest` を実行する（JDK 21／ローカルの Android Studio 同梱 JBR に合わせた）。テストレポートと Lint レポートは失敗時の追跡用に artifact として保存し、同一ブランチへの連続pushでは古い実行を打ち切る。

現在 Lint は **Error 0件・Warning 0件**で、`lint { warningsAsErrors = true }` により警告でビルドが落ちる。
Kotlinコンパイラ側も `allWarningsAsErrors = true` を設定した（**Lintの設定はAndroid Lintにしか効かず、
これが無い間はテストコンパイル警告を何件でも追加できた**）。依存更新系の3チェック
（`GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion`）だけは `informational`（hint）へ降格してある。
素のまま有効化すると12件すべてが Error になり `lintDebug` タスクが失敗するが、`informational` なら
**「0 errors, 0 warnings, 12 hints」で成功し、指摘はレポートに残る**。上流が新版を出すだけで生える
指摘をゲートに載せず、かつ催促は消さないための設定である（→ design/dependency_policy.md）。

`assembleDebugAndroidTest` が保証するのは**テストAPKのコンパイルと組み立てまで**で、
Runnerの起動もCompose描画も実行しない。instrumentation の実行には実端末かエミュレータが要る。

**2026-07-31 に初めて Android 16 エミュレータで実行したときは 0 success / 2 failure だった**
（`NoSuchMethodException: android.hardware.input.InputManager.getInstance []`）。失敗はテスト本体へ
入る前のアイドル待機初期化で起き、Contextを見るだけのテストにもクラス共通の `createComposeRule()` が
効いていたため2件とも巻き添えになった。対策としてクラスを2つへ分離し、`espresso-core` を 3.6.1 → 3.7.0、
`ext:junit` を 1.2.1 → 1.3.0 へ限定更新した結果、**2026-08-01 の再実行で 2/2 成功した**
（Pixel_10_Pro_Fold AVD・Android 16 / API 36）。**クラス分離と依存更新を同時に入れたため、
どちらが効いたかは切り分けていない** — 実害が消えたので追わない判断とした。

**CIは組み立てまでなので、instrumentation の失敗は今もCIを素通りする。**
2026-08-08 にエミュレータジョブの追加を検討し、**見送りで確定した**
（→ [instrumentation_testing](../dev/design/instrumentation_testing.md) 判断4）。
最も実機固有な端末AI依存の9件はエミュレータでは `Assume` で skip されるため実機確認が残り、
確認が二重になること、無料でも保守（不安定な赤・KVM・system image・除外クラス）が残ることによる。
**再検討の条件は件数ではなく実害へ置いた** — 実行忘れで実害が出た／複数人になった／
頻度で明確に苦痛になった／配布ゲートが要る。

**CIのトリガーを正確に書いておく。** `pull_request` と **`main` への push だけ**で、
**featureブランチへの単独pushでは走らない**（PRが開いていればその更新で走る）。
したがって**PRを作るまでの間、自動検査は一度も掛からない。**

### 13.4 未カバー領域

- `NoteViewModel` 自体（`AndroidViewModel` と `Uri` が素のJVMでは生成できないため。壊れやすい調停は `NoteSessionCoordinator` へ出して `NoteSessionCoordinatorTest` で検証しており、残るのはノート読込・関連ノート・走査キャッシュといった `ContentResolver` 依存の経路）
- `SearchController.onVaultChanged()` が落とすスコープ走査キャッシュと検索/フォルダ列挙Job（観測できる副作用が `ContentResolver` を要する経路でしか作れない。実機確認で担保する）
- `RelatedNotesUseCase` のオーケストレーション本体（候補のスコアリング・並べ替え・整形・ID解決・キャッシュの純ロジックは `RelatedCandidate*` / `RelatedContextScoring` / `KeyedMemoCache` のテストで個別にカバー済み。`AiClient` とSAF読込を絡めた `findRelated` 全体の結線は未カバー）と `SearchPickerUseCase` のAI応答解釈（キーワード採点・フォールバックの選抜は `SearchKeywordMatchingTest` でカバー済み）
- `NoteHistoryStore` の日付判定・重複排除（Android依存のため素のユニットテスト不可）
- `PromptBuilder` の出力契約（クイズ・蒸留は `QuizPromptBuilderTest` / `DistillPromptBuilderTest` でカバー済み。要約・関連・ピッカー・補記・セクション系の5経路は未カバー）
- ~~SAFのカーソル走査、ファイル作成、削除~~ → **§13.5 の `VaultScanInstrumentationTest` が実物のSAFで覆った**（走査の相対パス・読取失敗と不在の区別・補記の作成/一覧/削除）。蒸留の書き込み経路は引き続き `DistillWriteRepositoryTest` がフェイクで検証する
- ReadingTraceのCompose実レイアウト上の可視量（算出そのものは純関数として検証済み）、Activity lifecycleを通した pause/resume の実挙動、SAF Gatewayでの実Vault照合、外部同期によるSAFフォルダ索引の変更
- Gemini Nanoの**ダウンロードとタイムアウト**（利用可否と生成は §13.5 が覆うが、**生成は10経路中4経路だけ**）
- ~~Compose UI、NavigationBar/Rail、全画面遷移~~ → **§13.5 が覆った**（読書画面の描画抑止・位置引き継ぎ・タブ遷移）。ただし画面幅による Rail 切替は未カバー
- 連続操作時のキャンセルと競合（**タブ連打テストはあるが競合を作れていない** → §13.6・TEST-4）
- 実際のObsidian Vaultを使ったE2Eテスト（偽Vaultでの経路は §13.5 が覆う。**実プロバイダ固有の挙動**は対象外）

現在の844テストは、Android依存の薄い純粋ロジックと、Controller間の調停の回帰防止に有効である。**2026-08-08 に instrumentation 34件が加わり、SAF・画像復号・Compose描画・画面遷移・端末AI生成の一部が実機で覆われた**が、**保証範囲は §13.6 のとおり主張より狭い**。ReadingTraceの高優先度3件はこの境界外で見つかったものであり、修正後も**Android側の実挙動は実端末確認でしか担保できない**。Vigilithも状態分離・モーション・配置範囲は純関数で検証しているが、実フレームの見え方、タップ／ドラッグの競合、Snackbar・IME・ReadingTraceとの視覚的な重なり、TalkBackは実機確認が必要。

---

### 13.5 instrumentation の内訳（37件）

2026-08-08 に段階1〜4cを実装し、**実機で 37/37 成功・0 skipped**（Pixel 10 Pro Fold / Android 17）。
段階の定義と判断は [instrumentation_testing](../dev/design/instrumentation_testing.md) が持つ。

| テストクラス | 件数 | 対象 | JVMで書けない理由 |
|---|---:|---|---|
| `InstrumentationSetupTest` | 1 | 対象アプリのContext取得 | Runnerの疎通 |
| `ComposeRenderingSetupTest` | 1 | Composeテストルールの描画 | Compose実行環境 |
| `NoteReadingFlowTest` | 4 | 解析待ちの描画抑止、全画面への位置引き継ぎ、進捗報告の整合 | レイアウト実測・可視判定 |
| `VaultScanInstrumentationTest` | 8 | 走査の相対パス、**読取失敗と不在の区別**、補記の作成/一覧/削除、document同一性 | 実物の `ContentResolver`・`DocumentsContract` |
| `NoteImageGatewayInstrumentationTest` | 7 | 復号・寸法読み、上限の内外、`TooLarge`/`Broken` の切り分け | 実物の `BitmapFactory` |
| `PromptTokenBudgetTest` | 5 | トークン計測と能力診断 | 端末AI（AICore） |
| `OnDeviceGenerationTest` | 4 | 本番プロンプトでの実生成 | 端末AI（AICore） |
| `ActivityRecreationTest` | 2 | Activity再生成でOPを再生しない、繰り返し再生成（**プロセス死亡は覆わない**） | Activityライフサイクル |
| `TabNavigationTest` | 5 | タブの往復・巡回・**戻る操作での履歴契約**・遷移先での再生成 | `NavHost` のバックスタック |

**土台は `src/debug` の `FakeVaultDocumentsProvider`。** 実物のSAF経路を通すために
テスト用 `DocumentsProvider` をアプリ側（debug ソースセット）へ置き、
`SafVaultBrowser` と `NoteRepository` は**本番のまま**動かす。
`androidTest` へ置くと別APK・別UIDになり tree URI の権限付与が要るため、`src/debug` を選んだ。
**release ビルドには入らない**（`processReleaseMainManifest` の出力で確認済み）。

> **`@Test` の戻り値には検査を置いてある。** Kotlin の `fun x() = runBlocking { ... }` は
> ブロック末尾の式の型が戻り値になるため、末尾が `Log.i()`（`Int`）だと JUnit4 の `void` 要求を
> 満たさず、**そのクラスのテストが全件起動しない**。実際に4件が丸ごと止まった。
> コンパイルは通り、失敗は赤ではなく**件数の減少**として現れる。
> `InstrumentationTestShapeTest` が `runBlocking<Unit>` を強制する（→ [lessons L30](../dev/lessons.md)）。

### 13.6 instrumentation が保証していない範囲

**件数の増加を保護範囲の拡大と読み替えない。** 2026-08-08 の外部レビューで、
**主張が実際に試していることより広い箇所が3件**指摘されている（TEST-4〜6として起票済み）。

- **タブ連打は試していない（主張を撤回した）。** `performClick` は毎回 semantics を取り直して同期し、
  生の `MotionEvent` は **Android 17 が instrumentation のUIDからの注入を拒否する**。
  代わりに**タブ履歴契約を「戻る」で観測する** — `popUpTo` を外す変異で落ちることは実機で確認済み。
  ただし **`launchSingleTop` 単体の効果は識別できていない**（`restoreState` が肩代わりする）。
- **`ActivityScenario.recreate()` はプロセス死亡ではない。** 同一プロセス内でActivityを作り直すだけで、
  Application・静的状態・プロセス内キャッシュは生き残る。
  **全件成功からプロセス死亡耐性は結論できない。**
  → 2026-08-08 に `ActivityRecreationTest` へ改名し、KDoc・設計書・解析書の主張を
  「同一プロセス内のActivity再生成」へ狭めた。**プロセス死亡の保証は未着手のまま。**
- **端末AIの生成は10経路中4経路だけ。** Nano依存9件の内訳は生成4件・計測/診断5件で、
  読書痕跡要約・蒸留・検索picker・補記・セクション候補・セクションchatの**6経路は未保証**。
  → 2026-08-08 に主張を代表4経路へ狭め、未保証6経路をテスト側へ列挙した。
  **`PromptGenerationCoverageTest`（JVM）が、builder を足したら覆うか未保証と宣言するまで落ちる。**

偽Vaultの実体ファイル名が document ID の `/` を `_` へ潰して衝突していた件は、
2026-08-08 に SHA-256 の単射なファイル名へ変え、同一パス再投入の列挙重複も直した。
**区切りだけが違うパスと再投入の2ケースは instrumentation で固定してある。**

## 14. コード品質評価

### 14.1 強み

1. **責務分割が明確**

   旧来の巨大ViewModelにすべてを置かず、要約・検索・セクションAI・クイズ・補記・蒸留・読書痕跡をControllerへ分離している。さらに横断調停と状態所有を `NoteSessionCoordinator` へ出し、`NoteViewModel` にはAndroid境界だけを残した。Vaultへの破壊的書き込みを伴う蒸留は、書き込み経路自体も `DistillWriteRepository` / `DistillRecoveryStore` へ切り出している。読書痕跡も `ReadingTraceStore` / `ReadingTraceDocumentGateway` でAndroid依存境界を隔離している。

2. **UI状態が一元化され、所有権が型で守られている**

   Compose側は `NoteUiState` を読むだけで、画面ごとの状態追跡が分散しにくい。書き込み側は `NoteUiStateStore` だけが `MutableStateFlow` を持ち、各Controllerへは担当スライスの Writer しか渡さないため、担当外フィールドへの書き込みはコンパイルが通らない。

3. **境界が文書ではなくテストで守られている**

   パッケージ依存の向きは `PackageDependencyTest` がimportを走査してCIで固定し、ノート/Vault切替の一斉停止と一斉初期化は `NoteSessionCoordinatorTest` が実物の9 Controllerを束ねて検証する。どちらも「後始末を1つ消すと落ちる」ことを変異確認で検証済みで、規約がKDocの口約束に留まっていない。

4. **AI非依存の価値を残している**

   関連ノートはwikilinkとファイル名規則で動作し、検索にはランダムモードとキーワード一致フォールバック（bigramスコア順・0件は返さない）がある。

5. **端末負荷への配慮がある**

   AI生成の直列化、60秒タイムアウト、SAF走査キャッシュ、Markdownパース再利用を実装している。

6. **壊れやすい文字列処理が純粋関数化されている**

   クイズパース、補記Markdown、タイトル正規化、Markdown解析をAndroid I/Oから分離し、ユニットテスト可能にしている。

7. **Obsidian固有仕様への配慮がある**

   `.md`、wikilinkの別名・見出し・ブロック参照、frontmatter、補記専用フォルダを扱う。

### 14.2 残る技術的注意点

優先度は「現時点で確認できる影響範囲」に基づく。直ちに障害が起きることを意味しない。

| 優先度 | 項目 | 現状と影響 |
|---|---|---|
| 中 | 統合テスト不足 | SAF・端末AI・Navigationの不具合はローカルユニットテストで検出できない。土台（依存・Runner・スモークテスト・CIでのコンパイル）は整えたが、**一度も実行していない** |
| 中 | 読書痕跡の孤児ファイル | ノートの削除・改名で対応する痕跡が残り続け、掃除する導線もない。長期運用でファイル数が単調増加する |
| 中 | ReadingTrace同期索引 | 外部同期で追加されたサイドカーをプロセス再起動まで認識しない |
| 中 | AI入力が先頭固定長 | 長文ノートの中心・結論が後半にある場合、要約・クイズ・補記の品質が落ちる |
| 低 | 同名ノートの曖昧性 | AI推薦は候補ごとの一時ID（`idToNote`）で解決するため、同名・別URIも別IDになり不定にならない（ID応答方式で解消済み）。決定的チャンネルや除外判定で使う正規化タイトル集合には同名畳み込みが残る |
| 低 | YAML解析が簡易 | 複雑なYAML、引用、ネスト、複数行値には対応しない。AI推薦で使う tags/aliases の取りこぼしにつながり得る |
| 低 | Markdownが限定実装 | クリック可能リンク、画像、埋め込み、数式などは未対応（リストの番号・入れ子・タスク混在は 2026-08-02 に対応済み） |
| 低 | 状態取得失敗と非対応の同一視 | AI状態確認の一時エラーも「利用不可」として扱われる |
| 中 | ユーザーが書いた返事に退避手段が無い | ひとことの返事と映し返しは痕跡サイドカーに入るが、書き出し／読み戻しの経路が無い。Vaultのフォルダが消えれば**再生成できない文章**ごと失われる。保存失敗時の退避もプロセスメモリにしか無い |
| 低 | R8・署名が未設定 | `release` は定義したが `isMinifyEnabled = false`・署名なし。R8を有効化すると ML Kit GenAI のリフレクション解決部分が縮小で消え、**全AI機能が release ビルドでだけ落ちる**可能性がある。JVMテストは縮小前のクラスを見るため検出できず、実機検証とセットになる |
| 低 | 依存が12件古い | 更新の方針は 2026-08-01 に確定し（7グループ・棚卸しの契機・確認範囲）、3チェックは `informational` にして毎ビルド hint として見えるようにした。**残るのは更新そのもの** — ML Kit GenAI が beta2→beta4 で、SDK制約がバイナリを見ないと分からない前例がある |

---

## 15. 今後の改善候補

本節は現状解析から導かれる候補であり、今回の解析書更新では実装変更していない。

1. （実装済み・2026-07-25）ReadingTraceの到達率をブロック内可視量まで考慮する方式へ変えた。最終ブロック末端を見ていなければ100%にならない。
2. （実装済み・2026-07-25）Sessionの pause/resume を実装し、背面時間を10秒判定から除外した。背面化で書いた訪問は復帰後の読み進めで差し替え、閲覧回数が膨らまないようにした。
3. （実装済み・2026-07-25）ReadingTraceの保存要求へVaultキーを持たせ、書込直前の照合で旧痕跡が新Vaultへ到達しないようにした。
4. （実装済み・2026-07-25）`SearchPickerUseCase` のフォールバックを候補数に関係なくbigramスコア順にし、一致0件は返さないことでUI文言と一致させた。
5. （実装済み・2026-07-26）`SearchController` に `activeRequestId` と `searchJob` を導入し、最後の要求だけが `searchState` を更新するようにした。検索とランダムはJobを共有する。
6. （実装済み・2026-07-26）`SearchPickerUseCase` の `CancellationException` 握りつぶしを解消した。結果型でエラーを返すUseCaseは、キャンセルだけ例外のまま通さないと呼び出し側の `catch` では防げない。
7. （実装済み・2026-07-26）補記一覧／削除とフォルダ一覧をVault世代で、要約側のモデルDLを `SummaryController` の requestId で保護した。要求単位で未追跡の経路は無くなった。
8. （実装済み・2026-07-26）ノート本文の読込を用途別の予算（表示1MB／候補スニペット8KB／蒸留256KB）へ分け、`totalVisitCount` を追加して累計訪問数を保持件数30と分離した（サイドカー schema v2）。
9. （実装済み・2026-07-27）`NoteViewModel` の依存生成を `NoteViewModelDependencies` へ出し、Controller間の調停を `NoteSessionCoordinator` へ分離してJVMテスト可能にした。状態は `NoteUiStateStore` の機能別Writerで所有権を型に落とし、パッケージ依存の向きを `PackageDependencyTest` でCI固定した。
10. `PromptBuilder` の出力契約（要約・関連・ピッカー・補記・セクション）と `SearchPickerUseCase` のAI応答解釈のテストを追加する（`RelatedNotesUseCase` の候補選定・スコアリング・整形・ID解決・キャッシュの純ロジックは分離済み `RelatedCandidate*` / `RelatedContextScoring` / `KeyedMemoCache` で充足。残るは `findRelated` の結線）。
11. （土台は実装済み・2026-07-29）`buildTypes { }`・`lint { }`・`androidTestImplementation` 一式・`testInstrumentationRunner` を用意し、土台スモークテストを置いてCIでコンパイルまで通した。**残るは実行**で、Vault走査・補記保存・削除、ReadingTraceのActivity lifecycle・Vault切替・外部同期を実端末で検証する。
12. ReadingTrace索引にミス時再走査またはTTLを設ける。あわせて孤児サイドカーの手動一括削除をオプション画面へ置く。
13. AI入力を単純な先頭切り出しから、見出し・冒頭・末尾・重要語を考慮した抽出へ発展させる。
14. （実装済み）AI推薦の同名ノート解決に一意な候補ID（`C01..` / `idToNote`）を導入した。決定的チャンネル・除外判定の正規化タイトル集合は同名畳み込みが残るため、必要ならそちらも一意化する。
15. AI非対応・モデル未準備・一時エラーのUXを要約・検索・クイズ・補記で統一する。
16. （実装済み・2026-07-29〜30）ライト配色のAA未達を是正した。無彩色グレーを3段階へ統合し、基準面を `panelChip` に統一。見出し2色と更新日時は色相を保ったまま明度を下げ、グラデーション直上の文字は共通部品が背景ごと持ち、ボタンは輪郭線で境界を出す。検証は「トークン×実際に載る面」の総当たりへ移し、使用箇所の禁止をソース走査で強制した。
17. （実装済み・2026-07-29）`applicationId` を `com.vigilith.ai` に確定した（`namespace` は据え置き）。
18. R8を有効化して keep ルールを確認し、リリース署名の手順を決める。7つのAI経路を release ビルドで一巡する必要がある。
19. （実装済み・2026-08-08）instrumentation の中身を37件書き、実機で 37/37 成功した（→ §13.5）。**外部レビューで「主張が観測より広い」と指摘された3件は、いずれも主張を狭める形で対応した**（プロセス死亡・全AI経路・入力競合）。実行がCIで担保されない点は、見送りで確定している。
20. 依存を実際に上げる。単位と契機は確定済み（→ design/dependency_policy.md）で、まず ML Kit GenAI beta4 の変更点をAARで確認する。**Compose BOM は Lint の検出から漏れる**ので手で見る。
21. 読書痕跡の退避・復元（書き出し／読み戻し）。ひとことの返事が入ったことで、失われたときの損失が跳ね上がっている。

---

## 16. 解析時の確認事項

- 本解析は現行ソースコードを基準にし、過去の設計書ではなく実装との突合を優先した。
- 2026-07-20の更新はdocs再構成（変更履歴表・design/の新設）と同時に実施した。変更の経緯は [change_history.md](../dev/change_history.md)、設計判断は [design/](../dev/design/) を参照。
- 2026-07-21の更新は関連ノートAI推薦のPhase 1〜3（PR #27/#28/#29）を反映した。多段パイプライン化（タイトル話題スコア→本文肉付け→本文再ランク→ID応答）、tags/aliasesの利用開始、同名曖昧性の解消を §6.4・§7.3・§8.4・§14・§15 に反映。設計と知見は [related_notes_ai](../dev/design/related_notes_ai.md) を参照。
- 2026-07-22の更新（コミット `48a121b`）は §6.7 のクイズ適応出題と §6.10 の蒸留v1を本文へ追記したが、§2.1 コード規模・§3 ファイル構成・§13 テスト状況の数値を更新しておらず、実装と乖離した状態が残っていた。
- 2026-07-24の更新は上記の乖離を実測値で解消した。本番Kotlin 33ファイル・約5,900行 → 50ファイル・9,377行、テスト 10ファイル・60（本文中は41と不整合）→ 29ファイル・192ケースへ訂正。あわせて §3 のファイル構成へ `Distill*` 8ファイル・`NoteSnapshot.kt`・`QuizInputProfile.kt` を追加し、§1・§4・§8.4・§12・§13・§14 に蒸留とクイズ適応出題を反映。アプリ名の `Obsidian Mind` → `Vigilith AI` 改称（コミット `be1c0e9`）も反映した。同日、Android Studio 側で `testDebugUnitTest` を実行し192ケース全グリーンを確認済み。
- 2026-07-25の更新は未コミットのReadingTrace v1を反映した。本番Kotlin 57ファイル・10,630行、テスト34ファイル・282ケースへ再測定し、CLIで`testDebugUnitTest`全件成功を確認した。一方、JVMテスト成功を完成判定にはせず、実装レビューで見つかった到達率・Activity lifecycle・Vault切替の高優先度3件と、同期索引・累計回数の中優先度2件を明記した。
- 2026-07-25（同日・2回目）の更新は、上記の高優先度3件＋検索フォールバックの計4件の修正を反映した。本番Kotlin 59ファイル・10,926行、テスト36ファイル・317ケースへ再測定。§6.11 のフロー・§13 のテスト表・§14 のリスク表・§15 の改善候補を更新し、**§14 の「高」は0件になった**。ただし本修正分のテスト実行と実機確認は未実施であり、SAF照合とActivity lifecycleの実挙動はJVMテストでは担保できないことを §13.3 に明記した。
- 2026-07-25（同日・3回目）の更新は、Vigilith起動OPへの移行を反映した。本番Kotlin 60ファイル・11,044行、テスト37ファイル・324ケースへ再測定し、`testDebugUnitTest`全件成功と`assembleDebug`成功を確認。接続実機へのAPKインストールは成功したが、端末の認証ロックによりOPの目視確認は未完了。
- 2026-07-25（同日・4回目）の更新は、アプリ内Vigilith Phase 1を反映した。本番Kotlin 62ファイル・11,215行、テスト38ファイル・331ケースへ再測定。NoteタブのAI吹き出しを3ポーズのVigilithへ置換し、状態優先順位の7ケース、`testDebugUnitTest`、`assembleDebug`、接続実機へのAPKインストール成功を確認した。認証ロックによりアプリ画面の目視確認は未完了。
- 2026-07-25（同日・5回目）の更新は、アプリ内Vigilith Phase 2を反映した。本番Kotlin 64ファイル・11,575行、テスト39ファイル・343ケースへ再測定。`AppScaffold`直下の共通Hostによる5タブ常駐に加え、Summaryの正面レンズ動作とDistillingの横向き文章面・候補探索・指示停止・保存下線を分離した。`testDebugUnitTest`全件成功、`assembleDebug`成功、接続実機へのAPKインストール成功を確認した。認証ロックによりアプリ画面の目視確認は未完了。
- 2026-07-26の更新は、新しいキャラクター資料を正として目と2つの機能ポーズを修正した。目は全ポーズ・ランチャー・起動OPで分割ベゼル／Aqua虹彩／濃色瞳孔／左上キャッチライトへ統一。Summaryは片翼の案内、Distillingは正面で断片を中央へ集め両翼で保持して下線を確定する動作へ変更し、旧横向き指示ポーズを削除した。`testDebugUnitTest`全343件、`assembleDebug`、接続実機へのAPKインストールに成功した。端末が認証ロック中のため画面の目視確認は未完了。
- 2026-07-26（同日・2回目）の更新は、アプリ内Idleを透過WebPへ移行した。丸い体形、多面体の陰影、Aqua虹彩と濃色瞳孔を76×93dpでも維持し、Composeのレンズ／コア呼吸光は素材内の実測座標へ補正した。Summary／Distilling／MessengerとAdaptive Iconは既存ベクターを維持する。`testDebugUnitTest`全343件、`assembleDebug`、接続実機へのAPKインストールに成功した。
- 2026-07-26（同日・3回目）の更新は、Summary／Distilling／Messengerも専用の透過ロスレスWebPへ移行し、起動OPもIdle WebPへ統一した。各素材は生成原寸から約749〜802×936px、260〜300KBへロスレス最適化。4状態の虹彩・コア・カプセル位置を個別に補正し、蒸留の候補収集／保存下線とMessenger登場発光はCompose Canvasで維持した。旧アプリ内ポーズVectorDrawable 5ファイルを削除。`testDebugUnitTest`全343件、`assembleDebug`、接続実機へのAPKインストールに成功した。
- 2026-07-26（同日・4回目）の更新は、Summaryを正面＋片翼ポーズから、胴体と足を三分の二横向きにして頭をこちらへ戻す自然な案内姿勢へ置換した。起動OPは先行点灯用の目レイヤーを本体登場時に消し、退場時に目だけ残って見える現象を解消。引き継ぎテスト1件を追加し、`testDebugUnitTest`全344件、`assembleDebug`、接続実機へのAPKインストールに成功した。
- 2026-07-26（同日・5回目）の更新は、起動OPの目専用Canvasレイヤーと対応する`eyeAlpha`／焦点／パルス状態を完全削除した。演出をハロー→完成WebP全身→名称へ簡潔化し、目だけが残る余地を構造的になくした。テスト8件は新タイムラインの登場順・保持・退場へ置換し、全344件成功と`assembleDebug`成功を確認した。
- 2026-07-26（同日・6回目）の更新は、アプリ内Vigilith Phase 3を反映した。ドラッグ位置を配置可能領域内の相対座標で保存し、四辺clamp、Fold開閉・回転・状態ラベル変更後の再配置、NavigationBar / Rail、Snackbar、IMEの予約領域を実装。TalkBackは可視ラベルとの二重フォーカスをなくし、76×93dpの本体ボタンへ状態・操作・対象節を集約した。本番Kotlin 65ファイル・11,723行、テスト41ファイル・352ケースへ再測定し、全テスト・`assembleDebug`・Pixel 10 Pro FoldへのAPKインストールに成功した。端末の認証ロックによりアプリ画面の最終目視は未完了。
- 2026-07-26（同日・7回目）の更新は、ダークモード実装・テーマ基盤リファクタ（R-1〜R-4）・パッケージのレイヤー別整理（PR #37）・軽量課題5件・検索の世代管理までを反映した。**§3 のファイル構成が PR #37 前のフラット構成のまま**で、`SearchController.kt` や `NoteUiState.kt` をルート直下に記載するなど実態と大きく乖離していたため、実ファイル一覧と突合して全面的に書き直した（70ファイル全件が一致することを機械的に確認）。また §6.5 に PR #35 で解消済みの旧フォールバック仕様（「候補40件以下では先頭3件を返すため画面文言と一致しない」）が残っていたため実装へ合わせた。§4.3 に `darkTheme` と `isSectionChatSheetVisible` の2フィールドが欠けていた点、§5.3 のオプション画面にダークモード切替が無かった点も補った。本番Kotlin 70ファイル・12,535行、テスト43ファイル・379ケースへ再測定し、`testDebugUnitTest` と `lintDebug` の成功を確認した（Lint 0 error / 28 warning）。CIは §13.3 を参照。
- 2026-07-30の更新は、D案（ライト配色のAA是正）とE案（リリース構成の整備）を反映した。**この2案はレビューで2度差し戻されており、解析書もその経緯ごと記録する。** 1度目は全色を最も明るい `panel` の上で測っていたため、実際に載る `panelBlue` で 4.19〜4.41・グラデーション直上のボタンで 1.21〜1.84 と割ったまま「準拠」と判定していた。2度目は対応表を作った後も、グラデーション上の白文字を最も有利な `Indigo` 停止色でだけ測り、`copy(alpha)` 派生と `onVibrant` を覆えていなかった。現在は停止色を `AppColorScheme` の単一ソースから読んで総当たりし、表に載らない書き方は `VibrantTextUsageTest` がソース走査で禁じる。見出しは暗幕（`LogoNavy` α=0.42）を実機で見たうえで白ヘイズ（`Panel` α=0.35＋濃色の文字）へ反転した — **基準を満たす実装と成立しているデザインは別で、前者だけでは差し戻される**。E案は `buildTypes`・`lint {}`・androidTest基盤・Java 11・`applicationId` を入れ、Lint警告に加えKotlinコンパイラ警告もビルドを落とす設定にした（`lint { warningsAsErrors }` はAndroid Lintにしか効かない）。本番Kotlin 93ファイル・14,400行、テスト53ファイル・459ケースへ再測定し、`testDebugUnitTest` / `lintDebug` / `assembleDebugAndroidTest` / `assembleRelease` の成功を確認した（Lint 0/0・Kotlin警告0）。§14.2 から「ライト配色のAA未達」「`applicationId` が初期値」「リリースビルド構成が未定義」の3行が消え、代わりに「バッジ塗りがナビ帯で未達」「R8・署名が未設定」「依存更新の方針が無い」が入った。**instrumentation はコンパイルまでで一度も実行していない**点は §13.3 に明記した。
- 2026-07-27の更新は、改善活動A案（非同期の境界）・C案（入出力の境界）・B案（依存と状態の境界）を反映した。**解析書は `25ec429`（検索の世代管理まで）で止まっており、A案・C案の完了分も未反映だった**ため、3案まとめて突合した。主な書き換えは §1 サマリー、§3 ファイル構成（`NoteSessionCoordinator` / `NoteUiStateStore` / `model/state/` / 共有データ型の `model` 移動）、§4.1 依存方向の許可表、§4.2 機能別Writerの担当表、§4.3 リセット契約の名称と原子性、§10.1 二層の世代管理、§13 テスト内訳と未カバー領域、§14 リスク表、§15 改善候補。本番Kotlin 89ファイル・13,660行、テスト49ファイル・425ケースへ再測定し、`testDebugUnitTest` と `lintDebug` の成功を確認した（Lint 0 error / 28 warning）。§14.2 から「Job管理の不統一」「パッケージ間の依存が循環」「`NoteViewModel` がテスト不能」「単一 UiState の共有所有」「削除失敗の通知不足」「ReadingTrace累計回数」の6行が消え、残る中優先度は統合テスト不足・痕跡の保守性・AI入力の切り出し方になった。**A案／B案／C案はいずれも実機確認が未実施**であり、コード上の完了と動作確認は別である。
- 2026-08-01の更新は、F-1（表示用Markdownの非同期化）・F-2（蒸留の復旧チェックと補記の後始末）・AndroidX Test の限定更新・依存更新方針の確定を反映した。**この更新は「解析書が実装より遅れている」こと自体の是正でもある** — 解析日は 07-30／対象ブランチは No.5 のまま据え置かれ、テスト件数（459）・Controller数（7）・Espresso版（3.6.1）・Lintの更新系設定（`disable`）がすべて実態とずれていた。§2 のコード規模、§3 のツリー（`ComposeRenderingSetupTest` の追加）、§7 の補記保存手順（`AnnotationFileWriter` / `AnnotationDocumentGateway`）、§13.1 のテスト表、§13.3 の CI と instrumentation 初回実行の失敗、§14.2 のリスク行、§15 の改善候補19・20 を書き換えた。本番Kotlin 95ファイル・14,753行、テスト56ファイル・486ケースへ再測定し、`testDebugUnitTest` と `lintDebug` の成功を確認した（Lint 0 error / 0 warning、更新系は12 hints）。同日中に `connectedDebugAndroidTest` を Android 16 エミュレータで実行し、**2/2 成功**を確認したので
§13.3 と §14.2 の instrumentation 関連の記述も「未実行」から「土台は実証済み・中身は未着手」へ改めた。
- 2026-08-01の更新は、N-7（SAF境界の gateway 化）の全段階完了を反映した。ドキュメント参照を `DocumentRef` へ移し（段階1〜6）、さがす／補記の `ContentResolver` と Vault ルートを `VaultBrowser` / `VaultHandle` の裏へ束ねた（段階7）。**`android.net.Uri` を import するファイルは 17→8** で、残る8は `data` 7・`NoteViewModel` 1 といずれも境界そのもの。`model` / `domain` / `controller` の3層が Android 非依存として `PackageDependencyTest` に固定された。**この移行の主目的はテスト容易性**で、素のJVMで書けなかった「さがす／補記の世代照合・検索実行・走査キャッシュ・削除失敗の件数」を21件追加できた（5つのガードを削る変異で確認済み）。`NoteSessionCoordinatorTest` のリフレクション番兵も、キャッシュ破棄の分は実挙動テストへ置き換わった。本番Kotlin 97ファイル・14,905行、テスト57ファイル・508ケースへ再測定し、`testDebugUnitTest` と `lintDebug` の成功を確認した（Lint 0 error / 0 warning、更新系は12 hints）。**段階7の実機確認は未実施。**（**その後 2026-08-01 に実機確認済み**）
- 2026-08-09の更新は、**「AI補記メモ」から「ノートへのひとこと」への全面作り直し**を反映した。
  出発点はオーナーの実感（補記の評価内容がわかりにくい）で、git履歴まで遡ると
  **起票済み3件が1本の因果**だった — 出力枠256トークンがゼロサムなのに、
  2026-07-20 の途切れ対策が可変費（補記3行）だけを削り、**分類ラベル4行は実装時から1文字も
  変わっていなかった**。削る対象を価値ではなく削りやすさで選んでいたことになる。
  枠を1文へ集中させ、保存先をVaultの `.md` から痕跡サイドカー（schema v3→v5）へ移し、
  返事と映し返しを足して**読む→問われる→答える→映し返される**の一周にした。
  **実機確認は5巡**あり、そのたびに設計判断を撤回している（表示場所・再送禁止・返事の上限）。
  最も重かったのは**ユーザーが書いた返事を失う経路**で、保存結果を Boolean へ畳んでいたこと・
  退避が単一スロットだったこと・退避するものが「返事」で新規作成の失敗を復旧できなかったことを
  順に直した。プロンプトに書いた契約（一般論の禁止・リンクと問いの排他）は検査へ移し、
  **指示のまま残っていた最後の1つ**も実機で踏んでから塞いだ。
  §1・§2.1・§3・§5・§6.8・§7・§8・§10・§11・§13・§14・§15 を書き換え、
  本番Kotlin 121ファイル・19,787行、テスト73ファイル・15,151行・844ケースへ再測定した。
  `testDebugUnitTest` / `lintDebug` / `assembleDebugAndroidTest` の成功を確認済み
  （Lint 0 error / 0 warning、更新系は12 hints）。→ [reflect_remark](../dev/design/reflect_remark.md)
- 2026-08-02の更新は、Markdownのリスト構造とバッジ記号の基準見直しを反映した。`ListBlock` が `items: List<String>` の単一型だったため**入れ子段数・番号・種別の3つが同時に落ちて**おり、同じパーサが `buildNoteExcerpt` 経由でAI入力にも使われるため**手順書の順序がモデルへ届いていなかった**。`ListItem(depth, marker, text, checked)` と `ListMarker` を導入し、番号は原文表記のまま保持（`String` で持つのは区切り記号 `.`/`)` と先頭ゼロを失わないため）、箇条書き記号は正規化する非対称にした。段数は**CommonMarkにもObsidianにも準拠しない寛容規則**（幅の絶対値を見ず相対的な深浅だけで判定）で、規則5つを1規則1テストで固定した。`TaskListBlock` は項目の属性 `checked` へ統合（別ブロックのままだと混在ネストで段数の追跡が切れる）。あわせて**バッジの記号に当てる基準を 4.5:1 から 3:1 へ改めた** — WCAG は実装ではなく機能で分類するので、バッジの ✓ は読む文字（1.4.3）ではなく状態を示す記号（1.4.11）である。`onStatusBadge` を新設し、同じ塗りの上でラベル（黒・4.5:1）と記号（白・3:1）を分けた。本番Kotlin 103ファイル・16,373行、テスト59ファイル・583ケースへ再測定し、`testDebugUnitTest` と `lintDebug` の成功を確認した（Lint 0 error / 0 warning、更新系は12 hints）。**リスト構造は実機確認済み、バッジ記号は実機確認済み。**
- 数値の再測定手順（次回更新時に同じ値を再現するため）:

  ```bash
  find app/src/main -name "*.kt" | wc -l && find app/src/main -name "*.kt" -exec wc -l {} + | tail -1
  find app/src/test -name "*.kt" | wc -l && find app/src/test -name "*.kt" -exec wc -l {} + | tail -1
  grep -rh --include='*.kt' -c '^\s*@Test' app/src/test | awk '{s+=$1} END {print s}'
  ```

- 2026-08-08の更新は、**テスト基盤の拡張と外部レビューの結果**を反映した。
  2026-08-05〜08 に上限つきストリームの境界・遮断器の包含判定・受付漏れ検査を直し、
  instrumentation を段階1〜4cまで実装して**実機 34/34 成功・0 skipped**を確認した。
  §2.1 のコード規模（本番118ファイル・18,396行／テスト69ファイル・13,139行・748ケース／
  androidTest 9ファイル・1,544行・34件／debug 1ファイル・301行）、§3 のツリー、
  §13.1 のテスト表、§13.2 の実行結果、§13.3 の CI（**エミュレータ実行は見送りで確定**・
  トリガーは `pull_request` と `main` への push だけ）、§13.4 の未カバー領域を書き換え、
  **§13.5（instrumentation の内訳）と §13.6（保証していない範囲）を新設**した。
  **§13.6 を分けたのが今回の要点である** — 34件という総数が
  「連打の競合」「プロセス死亡」「全AI経路」の保証へ読み替えられていたことが
  外部レビューで指摘され、TEST-4〜7・DOC-1 として起票した。
  **件数は保護範囲を語らない**ので、内訳と非保証範囲を同じ章に並べて置く。
  他章（§4〜§12・§14）は 07-27〜08-02 時点のまま。
