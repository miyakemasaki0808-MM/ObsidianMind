# ソースコード解析書

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**この文書が答える問い:** いまコードがどうなっているか。構成・状態・データの流れ・検証の状態を、コードを開かずに見渡すための地図である。
**測定日:** 2026-09-24。基準は `ea6385b` で、作業ツリーは clean。測った値は §0 に集め、ほかの章には日付を書かない

> **レビュー前である。** 2026-09-24 の書き直しは Codex のレビューをまだ通していない。
> 本文の事実はソースと突き合わせたが、別の目では確かめていないので未確定として読むこと。
> 同じ変更で、テストの全クラス一覧を [jvm_test_report](02_jvm_test_report.md) の付録へ移した。
>
> **設計文書との食い違いが1件ある。** §6.14 と §10.1 は、分野の索引が requestId だけで古い結果を捨てるという実装のとおりに書いた。
> 一方で [architecture](../dev/system/architecture.md) の判断4 と [note_field_color](../dev/features/note_field_color.md) は、`vaultGeneration` で照合すると書いている。
> 実装がレビュー中なので、どちらにもまだ手を入れていない。
>
> この囲みは、レビューが済んだら消す。

**ここに書くのは、コードを開けば確かめられることだけである。** 守っている約束は1行で書き、理由は正本へリンクする。
なぜそう作ったかは [dev/features/](../dev/features/) と [dev/system/](../dev/system/) が持ち、
いつ何が起きたかは [開発日誌](journal/) と [change_history](../dev/change_history.md) が持つ。

## 目次

- [0. いまの数字](#0-いまの数字)
- [1. エグゼクティブサマリー](#1-エグゼクティブサマリー)
- [2. プロジェクト規模と技術構成](#2-プロジェクト規模と技術構成)
- [3. 現在のファイル構成](#3-現在のファイル構成)
- [4. アーキテクチャ](#4-アーキテクチャ)
- [5. ナビゲーションと画面構成](#5-ナビゲーションと画面構成)
- [6. 主要機能のデータフロー](#6-主要機能のデータフロー)
  - [6.1 Vault選択と復元](#61-vault選択と復元) ・ [6.2 ランダムノート表示](#62-ランダムノート表示) ・ [6.3 ノート要約](#63-ノート要約)
  - [6.4 関連ノート](#64-関連ノート) ・ [6.5 さがす](#65-さがす) ・ [6.6 セクションAI](#66-セクションai) ・ [6.7 適応出題クイズ](#67-適応出題クイズ)
  - [6.8 余白メモ](#68-余白メモ) ・ [6.9 当日閲覧履歴](#69-当日閲覧履歴) ・ [6.10 蒸留](#610-蒸留) ・ [6.11 読書痕跡と再会カード](#611-読書痕跡と再会カード)
  - [6.12 読書痕跡の退避と読み戻し](#612-読書痕跡の退避と読み戻し) ・ [6.13 冊子](#613-冊子) ・ [6.14 分野判定と冊子の紙の色](#614-分野判定と冊子の紙の色)
- [7. SAF・Vaultアクセス層](#7-safvaultアクセス層)
- [8. AI層](#8-ai層)
- [9. Markdown解析・描画](#9-markdown解析描画)
- [10. 並行処理・ライフサイクル・キャッシュ](#10-並行処理ライフサイクルキャッシュ)
- [11. エラー処理とフォールバック](#11-エラー処理とフォールバック)
- [12. データ保護・プライバシー](#12-データ保護プライバシー)
- [13. テスト状況](#13-テスト状況)
- [14. コード品質評価](#14-コード品質評価)
- [15. ここに無いものと、この文書の更新](#15-ここに無いものとこの文書の更新)

---

## 0. いまの数字

### 0.1 規模

| 指標 | 前回 2026-09-20 | 今回 2026-09-24 | 増減 |
|---|---:|---:|---:|
| 本番コードのファイル数 | 166 | **166** | ±0 |
| 本番コードの行数 | 29,217 | **29,217** | ±0 |
| JVMテストのファイル数 | 142 | **142** | ±0 |
| JVMテストの行数 | 30,679 | **30,679** | ±0 |
| JVMテストの件数 | 1,508 | **1,508** | ±0 |
| instrumentation の件数 | 106 | **106** | ±0 |
| debug ソースセットのファイル数 | 4 | **4** | ±0 |

前回からの間のコミットは文書だけで、コードは1行も変わっていない。

行数は空行とコメントを含む `wc -l` で数え、生成物と Gradle スクリプトは含まない。
本番29,217行の内訳はコメント8,123行・空行2,091行・本体19,003行で、コメントの比率は28%。
テスト件数は行頭の `@Test` を数える。増え方の推移はこの文書の git 履歴にある。

```bash
find app/src/main -name "*.kt" | wc -l                      # 本番ファイル数
find app/src/main -name "*.kt" -exec cat {} + | wc -l       # 本番行数
grep -rhE '^[[:space:]]*@Test' app/src/test | wc -l         # JVMテスト件数
grep -rhE '^[[:space:]]*@Test' app/src/androidTest | wc -l  # instrumentation件数
```

### 0.2 検証状態

| | 結果 |
|---|---|
| `testDebugUnitTest` | **1,508件すべて成功。** 133クラス、failure 0、skip 0、約3秒 |
| `lintDebug` | **Error 0 / Warning 0。** 依存更新の催促は hint として出るがゲートに載せない。件数は Lint の最新版情報のキャッシュ次第で揺れる |
| Kotlin のコンパイル警告 | 0。警告があるとビルドが落ちる設定 |
| マージ後マニフェストの権限 | 期待する2件と一致する。AICore への接続と、自己定義の受信権限 |
| `assembleDebugAndroidTest` | CI で通す |
| instrumentation の実行 | 全106件を一度に通した実行は無い。着手した機能のケースだけを実機で走らせる形で、手順は `docs/review/device_validation/` が持つ。余白メモの4件と、範囲調整9件のうち自由範囲の分はまだ実機で走らせていない |

**実機でまだ確かめていないもの**

- 余白メモの全ケース。机上のゲートは通っている
- ひとことの導線がどこにも残っていないこと
- ランチャー再タップの起動ガードのうち、回転と Fold 開閉の1ケース
- ネットワーク権限を除いたあとのモデルDL。未DLの状態を作りにはいかず、契機を待つ
- Vigilith Phase 3 の目視
- 冊子の分割画面

未対応の課題は [_wip/current_issues.md](../_wip/current_issues.md) が正本で、測定日の時点で高4件・中8件・低5件・超低2件ある。
台帳の読み方と課題の出どころの分析は [wip_analysis](07_wip_analysis.md) が持つ。

---

## 1. エグゼクティブサマリー

Vigilith AI は、Android の Storage Access Framework でユーザーが選んだ Obsidian Vault を読み書きする Jetpack Compose のアプリである。
AIはクラウドを使わず、ML Kit GenAI Prompt API を通して端末内の Gemini Nano で動く。成果物はネットワーク権限を持たない。
何ができるかは [README](README.md) の機能表が持つので、ここでは構造だけを述べる。

- **入口は1つで、実装は14の Controller に分かれる。** 単一 Activity・Compose Navigation・単一 ViewModel を入口にする。
  `NoteViewModel` は `Uri`・`ContentResolver`・`SharedPreferences` を扱う Android 境界だけを持ち、
  Controller の生成と調停と状態の所有は、Android API を呼ばない `NoteSessionCoordinator` が持つ。
- **状態の持ち主は1つ。** `NoteUiStateStore` だけが状態全体を持ち、各 Controller には担当する欄の Writer だけを渡す。担当外の欄へはコンパイルの時点で書けない。
- **古い結果を書かない仕組みが2層ある。** ノート単位の要求は Controller ごとの requestId、Vault 単位の要求は共有の `vaultGeneration` で照合する。
- **ノート本文へ書くのは蒸留の太字化だけ。** 余白メモと読書痕跡は Vault 内の `_ReadingTraces/` に別ファイルとして置く。蒸留の書き戻しにはハッシュ照合と中断復旧の専用経路がある。
- **自動で走るAIは、ノートに続けて3秒留まってから始まる。** 要約・分野判定・再会カードの要約・関連ノートの推薦の4本が対象で、門番は調停側が1つ持つ。
- **境界はテストで固定してある。** パッケージの依存の向き、Android 非依存の3層、リセットの登録漏れ、見た目のチャネルの割り当ては JVM テストが落とす。

**総評。** 依存の循環、ViewModel をテストできないこと、状態の共有所有という構造の成長限界は解消されている。
壊れやすい純粋ロジックには1,508件の JVM テストがあり、instrumentation 106件が SAF・描画・画面遷移・画素・端末AIの一部を実機で覆う。
残る弱点は3つに絞られる。

1. **実機でしか分からないものが多い。** instrumentation は CI で実行されず、画面の佇まいと手触りはどのテストにも掛からない。判定は実機検証のケース表が持つ
2. **AIの出力の良し悪しは半分しか測れない。** 要約の採点器は語の重なりだけを見る。意味は人が読む
3. **リリースの構成が未完成。** R8 と署名が未設定で、依存の更新も実行していない

---

## 2. プロジェクト規模と技術構成

### 2.1 コード規模

| 区分 | ファイル数 | 行数と件数 |
|---|---:|---|
| 本番 Kotlin | 166 | 29,217行 |
| JVM テスト Kotlin | 142 | 30,679行・1,508件。テストクラスは133で、残り9本は共有のフェイクとヘルパ |
| instrumentation テスト Kotlin | 17 | 4,422行・106件 |
| debug ソースセット Kotlin | 4 | 826行。instrumentation 用の偽SAFプロバイダと要約の採点器。release には入らない |
| Android モジュール | 1 | `:app` |

### 2.2 ビルドとプラットフォーム

| 項目 | 現在値 |
|---|---|
| Android Gradle Plugin | 9.1.1 |
| Kotlin Compose Plugin | 2.0.21 |
| compileSdk | Android 36.1 |
| targetSdk | Android 36 |
| minSdk | Android 26 |
| applicationId | `com.vigilith.ai`。`namespace` は `com.example.newproject` のまま |
| Java 互換性 | Java 11。Kotlin の `jvmTarget` も 11 |
| buildTypes | `release` を定義。R8 は無効で、署名は未設定 |
| 権限 | マージ後マニフェストから `INTERNET`・`ACCESS_NETWORK_STATE` を `tools:node="remove"` で除く。`verify<Variant>ManifestPermissions` が期待する2件と増減の両方向で突き合わせる |
| 警告の扱い | Lint の `warningsAsErrors` と Kotlin の `allWarningsAsErrors`。依存更新の3チェックだけ `informational` |
| Compose BOM | 2024.09.03 |
| Navigation Compose | 2.7.7 |
| Core SplashScreen | 1.0.1 |
| Lifecycle | 2.8.7 |
| Coroutines | 1.9.0 |
| ML Kit GenAI Prompt | 1.0.0-beta2 |
| AndroidX Core KTX | 1.13.1。`edit {}` と `toUri()` を直接使うので明示している |
| JUnit | 4.13.2 |
| Compose UI Test / Espresso | BOM 準拠 / 3.7.0。`ext:junit` は 1.3.0 |

### 2.3 外部依存の特徴

- UI は View と XML を使わず、Jetpack Compose だけで組む
- Vault へは SAF と `DocumentsContract` で触れ、ストレージ権限を要求しない
- AI 生成は `com.google.mlkit:genai-prompt` 経由の Gemini Nano
- DI フレームワーク・データベース・HTTP クライアント・画像読み込みライブラリは入れていない
- 本番は `AICoreClient` を直接生成する。`StubAiClient` は手で差し替える用に残してある

---

## 3. 現在のファイル構成

本番コードだけを載せる。テストは §13 と [jvm_test_report](02_jvm_test_report.md)、CI は §13.3 にある。

```text
app/src/main/
├── AndroidManifest.xml                     # バックアップ除外ルールの指定を含む
├── java/com/example/newproject/
│   ├── MainActivity.kt                     # スプラッシュと起動OP、Vault選択、NavHost、Snackbar通知、テーマ適用
│   ├── NoteViewModel.kt                    # Android境界の窓口。Uri・ContentResolver・設定、ノート読込、関連ノート、走査キャッシュ
│   ├── NoteViewModelDependencies.kt        # 本番依存の組み立て。テストはここを差し替える
│   ├── AppNoteImageLoader.kt               # ui が宣言した画像読み込み口へ data の実装を差し込む
│   ├── ai/
│   │   ├── AICoreClient.kt                 # AiClient の実装。Gemini Nano への接続、Mutex、タイムアウト
│   │   ├── AiAvailabilityMapping.kt        # 端末の状態と例外を AiAvailability へ写す
│   │   ├── AiGenerationFailure.kt          # 回数制限で断られた失敗だけを見分ける
│   │   ├── GenerationRecordingAiClient.kt  # 要約の生成を呼ぶたび logcat へ1行出す。Debug APK だけ
│   │   ├── PromptBudget.kt                 # 完成プロンプトの入力上限を1箇所で当てる
│   │   └── PromptBuilder.kt                # 各機能のプロンプト。11本
│   ├── controller/
│   │   ├── NoteSessionCoordinator.kt       # 14 Controller の生成と横断調停、Vault世代、自動生成の門番
│   │   ├── SummaryController.kt            # ノート要約と、モデルDL後の再開
│   │   ├── SearchController.kt             # フォルダ検索とスコープのキャッシュ
│   │   ├── SectionChatController.kt        # セクションの要約・質問候補・Q&A
│   │   ├── QuizController.kt               # 適応出題クイズのバックグラウンド生成と確認状態
│   │   ├── MarginMemoController.kt         # 余白メモの読み出し・追記・削除。AIを呼ばない
│   │   ├── AnnotationController.kt         # 旧補記ファイルの一覧と削除だけ。Vault単位
│   │   ├── DistillController.kt            # 蒸留の候補提示・選択・保存・復旧
│   │   ├── ReadingTraceController.kt       # 読書セッション、能動読書時間、訪問と余白メモの保存
│   │   ├── ReunionCardController.kt        # 再会カードの照合・AI要約・「まだ考えたい」の印
│   │   ├── NoteDwellGate.kt                # 自動で走る生成の門番。本文表示から続けて3秒で開く
│   │   ├── ReadingTraceCleanupController.kt # 痕跡の孤児の洗い出しと削除。Vault単位
│   │   ├── ReadingTraceBackupController.kt # 痕跡の書き出し・下見・読み戻し・中止。Vault単位
│   │   ├── BookletController.kt            # 冊子の束、扉の遅延読込、編む束。Vault単位
│   │   ├── NoteFieldController.kt          # ノートの分野判定。ジョブはノート単位、索引はVault単位
│   │   ├── ReadingPauseReason.kt           # 読書時間を止めている理由。背面と冊子
│   │   └── NoteSectionController.kt        # 表示用のMarkdown解析をMainの外で1回だけ行う
│   ├── data/
│   │   ├── NoteRepository.kt               # SAF走査、読み書き、メタデータ解析
│   │   ├── VaultBrowser.kt                 # さがすと補記が使うVaultスコープの操作。ContentResolverを裏へ束ねる
│   │   ├── NoteImageGateway.kt             # 画像1枚の読み込み境界。寸法・復号・失敗理由
│   │   ├── VaultImageIndexStore.kt         # 画像索引のメモリキャッシュ、TTL、Vault世代
│   │   ├── AppPreferences.kt               # テーマとVault URIの永続化の境界
│   │   ├── VaultLocation.kt                # 選択中Vaultの共有参照
│   │   ├── NoteSnapshot.kt                 # 蒸留用の原バイト保持、上限付き読込、UTF-8厳格判定
│   │   ├── NoteHistoryStore.kt             # 当日分だけの閲覧履歴
│   │   ├── FileSummaryCache.kt             # 要約の保存。noBackupFilesDir に1件1ファイル、上限1000件
│   │   ├── NoteFieldStore.kt               # 分野の確定の永続。SharedPreferences に1件1キー
│   │   ├── SafDocuments.kt                 # SAFの子要素列挙、機能フォルダの探索と作成
│   │   ├── VaultPathTraversal.kt           # Vault相対パス付きの幅優先走査。Android非依存
│   │   ├── DistillWriteRepository.kt       # 蒸留のSAF安全書き込み。二重ハッシュ照合と原子確定
│   │   ├── DistillRecoveryStore.kt         # 中断復旧レコード。noBackupFilesDir
│   │   ├── DistillHashing.kt               # SHA-256。原バイトと出力の照合用
│   │   ├── ReadingTraceJson.kt             # サイドカーJSONと canonical checksum
│   │   ├── ReadingTraceBackupJson.kt       # 退避ファイルの形式
│   │   └── ReadingTraceStore.kt            # 痕跡の永続化境界、SAF Gateway、フォルダ索引、Vault照合
│   ├── domain/
│   │   ├── SummarizeUseCase.kt             # 要約。保存済みを引き、無ければ門番を通して生成
│   │   ├── SummaryCache.kt                 # 要約の保存の宣言。実装は data
│   │   ├── RelatedNotesUseCase.kt          # 規則ベースとAIの関連ノート抽出
│   │   ├── SearchPickerUseCase.kt          # 自然文検索で3件を選ぶ
│   │   ├── SearchKeywordMatching.kt        # キーワード一致の採点と選抜
│   │   ├── RelatedCandidateOrdering.kt     # 採番プレフィックスの抽出
│   │   ├── RelatedCandidateScoring.kt      # タイトル話題スコア。文字bigram Dice と採番の近さ
│   │   ├── RelatedContextScoring.kt        # 本文シグナルでの再ランク
│   │   ├── RelatedCandidateRanking.kt      # 採点戦略を注入する汎用ランキング
│   │   ├── RelatedCandidateContext.kt      # 候補の本文肉付けと、入力予算内への整形
│   │   ├── RelatedCandidateId.kt           # 一時ID C01.. の採番と応答からの抽出
│   │   ├── ReunionCandidateScanner.kt      # 再会カード候補の列挙と種別決定
│   │   ├── MarkdownPlainText.kt            # Markdownを人が読む文字列へ均す。再会候補と冊子の扉が共有
│   │   ├── BookletCoverLine.kt             # 冊子の扉にする代表文1行を本文から選ぶ
│   │   ├── KeyedMemoCache.kt               # 成功時だけ格納する汎用LRU
│   │   ├── ByteBudgetCache.kt              # バイト予算付きLRUと、同一キーの single-flight
│   │   ├── BoundedInputStream.kt           # 読み取り上限付きストリーム
│   │   ├── NoteExcerptBuilder.kt           # AI入力用の抜粋。見出し骨格と冒頭と末尾
│   │   ├── AiStatusNotices.kt              # AIの状態を、見せる1文と導線へ変換する
│   │   ├── NotePaperAge.kt                 # 放置期間をVault内の相対順位で紙の地色へ写す
│   │   ├── ReadingTraceOrphans.kt          # 孤児痕跡の割り出し。遮断器付き
│   │   ├── ReadingTraceMerge.kt            # 読み戻しの併合規則
│   │   ├── ReadingTraceBackupFileName.kt   # 退避ファイルの既定名
│   │   ├── DistillSourceModel.kt           # 蒸留用の文分割。UTF-16オフセットとMarkdown構造を保つ
│   │   ├── DistillCandidateScoring.kt      # 蒸留候補のサリエンス採点とチャンク網羅
│   │   ├── DistillResponseParser.kt        # 蒸留のAI応答からIDを抽出し、許可集合で検証する
│   │   ├── DistillTransformer.kt           # オフセット降順の ** 挿入と太字比率の上限
│   │   ├── DistillRangeAdjust.kt           # 太字範囲の3段プリセット、保護範囲、重なり解消
│   │   ├── DistillRangeSnap.kt             # 自由範囲の端を置ける位置へ寄せる
│   │   ├── MarginMemoComposer.kt           # 余白メモの入力整形。制御文字の除去と上限での切り
│   │   ├── QuizResponseParser.kt           # クイズ応答のパース
│   │   ├── QuizInputProfile.kt             # AIを使わない入力分類から出題形式を決める
│   │   ├── NoteTitleNormalizer.kt          # Obsidianのタイトル正規化
│   │   ├── AiResponseParsing.kt            # AIが返したタイトルの共通正規化
│   │   ├── LauncherEntry.kt                # ランチャー再タップの重複起動を判定する
│   │   ├── NoteFieldHint.kt                # パスから分野のヒントを作る辞書照合
│   │   ├── NoteFieldIndex.kt               # 索引Aへヒントを流し込む規則。確定を消さない
│   │   ├── NoteFieldAnswer.kt              # 分野判定の応答を読む。行の全体がIDのときだけ受理
│   │   ├── NoteFieldInputVersion.kt        # 入力指紋。AIへ渡したものすべてを含む
│   │   ├── image/
│   │   │   ├── ImageLinkParser.kt          # 画像参照の解析。![]() と ![[...]]
│   │   │   ├── ImageIndexMatching.kt       # 索引との照合。完全パス、次にファイル名
│   │   │   └── ImageDecodePolicy.kt        # 画像1枚にかける予算と復号可否
│   │   └── markdown/
│   │       ├── InlineSyntax.kt             # インライン記法の唯一の解釈器。表示と蒸留が共有
│   │       ├── MarkdownBlocks.kt           # ブロック解析
│   │       └── NoteSections.kt             # 見出し単位のセクションモデル
│   ├── model/                              # 依存グラフの葉。プロジェクト内の他パッケージも android.* も import しない
│   │   ├── NoteUiState.kt                  # 全UI状態を集める data class
│   │   ├── NoteUiStateStore.kt             # 状態の唯一の所有者、機能別 Writer、リセット契約
│   │   ├── NoteTypes.kt                    # NoteFile / NoteFolder / NoteMeta
│   │   ├── DocumentRef.kt                  # Vault内の1ドキュメントを指す不透明な参照
│   │   ├── HistoryEntry.kt                 # 当日履歴の1件
│   │   ├── RelatedNote.kt                  # 関連ノートとAI推薦の状態
│   │   ├── DistillModels.kt                # 蒸留の純データ
│   │   ├── BookletTypes.kt                 # 冊子の1ページ。参照・タイトル・扉。本文は持たない
│   │   ├── BookletWeave.kt                 # 編む束の中身と、トグルの3通りの見せ方
│   │   ├── NoteField.kt                    # 分野の固定リストと、未判定・暫定・確定の3値
│   │   ├── PromptLimits.kt                 # 完成プロンプトの上限と可変部の取り分
│   │   ├── NoteExcerpt.kt                  # 準備済みの抜粋を表す型
│   │   ├── NoteExcerptLimits.kt            # 用途別の抜粋上限
│   │   ├── ImageFileTypes.kt               # 画像として扱う拡張子
│   │   ├── NoteImageFailure.kt             # 画像を出せなかった理由
│   │   ├── NotePaperTone.kt                # 紙の地色の段階
│   │   ├── ReadingTraceOrphanTypes.kt      # 孤児判定の上限
│   │   ├── ReadingTraceBackupTypes.kt      # 退避ファイルの形式と上限
│   │   ├── ReadingTrace.kt                 # 読書痕跡のモデル、上限、検証、余白メモの配列。schema v7
│   │   ├── ReunionKind.kt                  # 再会カードの枠に出ている1件の種別
│   │   └── state/                          # 機能別の sealed state。一覧は §4.3
│   └── ui/
│       ├── AppScaffold.kt                  # 5タブ、NavigationBar と Rail の切替、SnackbarHost
│       ├── ReadingProgressGeometry.kt      # 最終可視ブロックの可視割合と量子化
│       ├── ReadingTraceCleanupText.kt      # 孤児整理の文言
│       ├── ReadingTraceBackupText.kt       # 退避と読み戻しの文言
│       ├── component/
│       │   ├── NoteComponents.kt           # タブと全画面の共用部品。読書位置の報告、IconPill、本文パネル
│       │   ├── GradientHeader.kt           # グラデーション直上に置く画面見出し
│       │   ├── OptionRow.kt                # 設定系の「押すと次の画面へ行く」1行
│       │   ├── AiStatusNoticeRow.kt        # AIの状態の説明と導線
│       │   └── ReadingTraceCard.kt         # 「前回のあなた」カードと経過の文面
│       ├── markdown/
│       │   ├── InlineMarkdown.kt           # 種別を色・太さ・下線へ写すだけ。解釈は domain/markdown
│       │   ├── NoteImage.kt                # ノート内画像1枚の描画
│       │   ├── NoteImageLoader.kt          # 画像読み込み口の宣言。実装は data
│       │   ├── NoteImageMeasurements.kt    # 表示寸法の算出
│       │   ├── NoteImageText.kt            # 失敗理由ごとの文言と代替テキスト
│       │   └── MarkdownRenderer.kt         # Compose 描画
│       ├── screen/
│       │   ├── OpeningScreen.kt            # 起動OP
│       │   ├── NoteReaderTab.kt            # ノートタブ本体
│       │   ├── FullscreenNoteScreen.kt     # 全画面読書ルート
│       │   ├── SearchScreen.kt             # AI検索とランダム抽出
│       │   ├── RelatedTab.kt               # 関連ノートとAI推薦の一覧
│       │   ├── AiTab.kt                    # 要約の表示と、蒸留の起点
│       │   ├── OptionsScreen.kt            # オプション。Vault選択、データ管理、ダークモード
│       │   ├── DataManagementScreen.kt     # 痕跡の退避と読み戻し、整理と旧補記の片付けへの入口
│       │   ├── ReadingTraceCleanupScreen.kt # 孤児痕跡の洗い出しと削除
│       │   ├── BookletScreen.kt            # 冊子ルート。10枚の天綴じ VerticalPager
│       │   ├── BookletSheet.kt             # 紙1枚の描画。扉・ノート名・縁
│       │   ├── BookletSheetGeometry.kt     # めくりと積み直りの幾何
│       │   ├── DistillRangeSheet.kt        # 太字範囲の調整シート
│       │   ├── QuizScreen.kt               # クイズ画面
│       │   ├── MarginMemoSheet.kt          # 余白メモのボトムシート
│       │   ├── AnnotationManagerScreen.kt  # 旧補記ファイルの一覧と削除
│       │   └── SectionChatSheet.kt         # セクションAIのボトムシート
│       ├── theme/
│       │   ├── AppShapes.kt                # 面の形の役割。読む面は角丸、眺める面はほぼ直角
│       │   ├── AppColors.kt                # ブランドパレット、明暗2組、役割トークン
│       │   └── AppTheme.kt                 # AppColorScheme・LocalAppColors・AppTheme
│       └── vigilith/
│           ├── VigilithHost.kt             # 5タブ共通の配置、ノート操作の文脈、ドラッグ
│           ├── VigilithState.kt            # Vigilith の配線
│           ├── VigilithMascot.kt           # アプリ内4状態のWebP、補助光、AI状態バッジ
│           ├── VigilithMascotMotion.kt     # 翼・レンズ・コア・カプセルのモーション
│           ├── VigilithMode.kt             # 既存の状態から表示状態を導く
│           ├── VigilithOpeningMotion.kt    # 起動OPのタイムライン
│           └── VigilithPlacement.kt        # 画面端・画面変更・予約領域を扱う配置計算
└── res/
    ├── values/                             # app_name、テーマ
    └── xml/                                # backup_rules / data_extraction_rules
```

---

## 4. アーキテクチャ

### 4.1 レイヤーと依存方向

```text
Compose UI / MainActivity
     │ イベントを送り、StateFlow を購読する。uiState と設定2つは別の Flow
     ▼
NoteViewModel ─────────────────── Android 境界。Uri・ContentResolver・設定
  │  └ NoteViewModelDependencies    依存の組み立て
  ▼
NoteSessionCoordinator ────────── 横断調停と状態の所有。Android API を呼ばない
  ├ NoteUiStateStore                機能別の Writer を配る
  ├ Controller 14個                 持ち主の一覧は §4.2
  ├ NoteDwellGate                   自動生成の門番
  │
  ├──► NoteRepository ────────────► SAF / DocumentsContract
  ├──► DistillWriteRepository ────► SAF の安全書き込み。復旧レコードは noBackupFilesDir
  ├──► ReadingTraceStore ─────────► SAF / _ReadingTraces
  └──► UseCase ──► AiClient ──────► ML Kit / Gemini Nano
```

パッケージはすべて同じ `:app` モジュールにあり、依存の向きは一方向だけを許す。
`model` が葉で、`model`・`domain`・`controller` の3層は `android.*` も import しない。
ルートの `MainActivity` と `NoteViewModel` はどの層からも参照されない。`PackageDependencyTest` が import を走査して固定する。
許す向きの表と理由は [architecture](../dev/system/architecture.md) の判断5 にある。

純粋ロジックは `domain/` と、`ui/` の `*Geometry`・`*Measurements`、`ui/vigilith/` の `*Motion`・`*Placement`・`VigilithMode` に置く。

### 4.2 状態の持ち主

`NoteUiStateStore` だけが `MutableStateFlow<NoteUiState>` を持ち、UI には読み取り専用の `StateFlow` を公開する。
Controller は独自の Flow を作らず、受け取った Writer で担当の欄だけを書く。

| Controller | 受け取る Writer | 書く欄 |
|---|---|---|
| `SummaryController` | `SummaryStateWriter` | `summaryState` |
| `SearchController` | `SearchStateWriter` | `folders`・`selectedFolder`・`foldersError`・`searchState` |
| `SectionChatController` | `SectionChatStateWriter` | `sectionChat`・`isSectionChatSheetVisible` |
| `QuizController` | `QuizStateWriter` | `quizState` |
| `MarginMemoController` | `MarginMemoStateWriter` | `marginMemoState`・`isMarginMemoSheetVisible` |
| `AnnotationController` | `AnnotationListStateWriter` | `annotationListState` |
| `DistillController` | `DistillStateWriter` | `distillState` |
| `ReunionCardController` | `ReadingTraceStateWriter` | `readingTraceCard` |
| `ReadingTraceCleanupController` | `ReadingTraceCleanupStateWriter` | `readingTraceCleanupState` |
| `ReadingTraceBackupController` | `ReadingTraceBackupStateWriter` | `readingTraceBackupState` |
| `BookletController` | `BookletStateWriter` | `bookletState` |
| `NoteFieldController` | `NoteFieldStateWriter` | `noteFields` |
| `ReadingTraceController` | なし | UIの状態は書かない。読書中のセッションは内部に持つ |
| `NoteSectionController` | なし | `NoteUiState` の外に `StateFlow<NoteSectionModel?>` を持つ |

Writer を持たない `noteState`・`relatedNotesState`・`wikilinkTitles`・`todayHistory`・`notePaperTone`・`vaultSelected` は、`NoteSessionCoordinator` 専用のメソッドで書く。
関連ノートは `Uri` を持つ走査キャッシュに依存しているので、`NoteViewModel` 側に残っていて Controller になっていない。

`NoteUiState` の外にあるのは3つである。

- `darkTheme` と `notePaperAging` は、`NoteViewModel` が独立の `StateFlow<Boolean>` で持つ。`MainActivity` はこの2つだけを `AppTheme` の外で購読する
- `NoteSectionModel` は `NoteSectionController` が持つ。解析の開始点は Coordinator の `setNoteState()` と `applyReloadedBody()` の2箇所

### 4.3 状態モデル

`NoteUiState` は23の欄を持つ。sealed state は `model/state/` に機能ごとのファイルで置く。

| 欄 | 取りうる状態と、持っている値 |
|---|---|
| `noteState` | Idle / Loading / Success / Empty / Error。Success は蒸留の書き戻し用に `targetUri`・`originalHash`・`distillUnavailableReason` を持つ |
| `summaryState` | Idle / Loading / Success / Downloading / AiUnavailable / Error |
| `relatedNotesState` | Idle / Loading / Success。失敗の枝は無い |
| `quizState` | Idle / Loading / Success / Error / AiNotice。`sourceTitle` を持ち、Success と Error は `isViewed` も持つ |
| `marginMemoState` | Idle / Loading / Ready / Error。Ready はメモの一覧、保存の状態、切り詰めたかどうかを持つ。保存の状態は None / Saving / Held / Saved / Failed / Full の1つ |
| `isMarginMemoSheetVisible` | 余白メモのシートを出しているか |
| `annotationListState` | Idle / Loading / Success / Error |
| `distillState` | Idle / Analyzing / AiNotice / Downloading / Unavailable / Candidates / Saving / Saved / Conflict / RecoveryRequired / RecoveryResolved / Error。AiNotice は端末AIの状態、Unavailable はノート側の理由。Candidates は候補ごとの範囲調整も持つ |
| `bookletState` | Idle / Loading / Open / Failed。Open は引く束と編む束の2つを持ち、それぞれがページ位置を持つ。編む側は種なし・編めない・編めるの3状態 |
| `noteFields` | 分野の索引A。`DocumentRef` ごとに未判定・暫定・確定の3値 |
| `readingTraceCleanupState` | Idle / Loading / Success / Blocked / Error |
| `readingTraceBackupState` | Idle / Working / Exported / Planned / Imported / Error |
| `notePaperTone` | 放置期間から決めた紙の地色の段階 |
| `readingTraceCard` | 再会カード。Rediscover で過去の痕跡が見つかったときだけ入る |
| `sectionChat` | セクションAIのセッション。無ければ `null` |
| `isSectionChatSheetVisible` | シートを出しているか。セッションの有無とは別に持つ |
| `folders`・`selectedFolder`・`foldersError`・`searchState` | さがすタブ |
| `wikilinkTitles` | 現ノートのリンク先タイトル |
| `todayHistory` | 当日の閲覧履歴。最大10件 |
| `vaultSelected` | Vault を選んだか |

**切替で何が落ちるか**

| 契機 | 落とすもの |
|---|---|
| ノート切替。`withNoteScopedReset()` | 紙の地色・要約・関連・クイズ・余白メモの一覧とシート・セクションチャット・再会カード |
| Vault 切替。`withVaultScopedReset()` を追加で当てる | 検索スコープ・閲覧履歴・痕跡の整理と退避・冊子の束・分野の索引 |
| 状態変換の外で落とすもの | 旧補記の一覧は `AnnotationController` が、蒸留の状態は `DistillController` が自分で扱う |
| どちらでも残すもの | `noteState` と `wikilinkTitles`。Vault 切替の直後に新しいノートで差し替わる |

ノート切替では、ジョブの停止と状態のリセットを必ず対で行う。`NoteSessionCoordinator.onNoteChanged()` がこの2つと Loading への遷移を1手で行う。
契約の全体と、分野判定だけが両方の単位にまたがる例外は [architecture](../dev/system/architecture.md) の判断2と判断4 にある。

---

## 5. ナビゲーションと画面構成

### 5.1 ルート

| 種別 | route | 画面 |
|---|---|---|
| タブ | `note` | ノート閲覧 |
| タブ | `search` | さがす |
| タブ | `related` | 関連ノート |
| タブ | `ai` | AIアシスト |
| タブ | `options` | オプション |
| 全画面 | `note_fullscreen` | 全画面ノート。バーとシステムバーを隠す |
| 全画面 | `booklet` | 冊子 |
| 全画面 | `quiz` | クイズ |
| 全画面 | `data_management` | 痕跡の書き出しと読み戻し、整理と旧補記の片付けへの入口 |
| 全画面 | `reading_trace_cleanup` | 孤児になった読書痕跡の洗い出しと削除 |
| 全画面 | `annotation_manager` | 旧補記ファイルの削除 |

タブ間は `navigateToTab()` が `popUpTo`・`saveState`・`restoreState`・`launchSingleTop` で移る。
冊子から「これを読む」で渡るときだけはこれを使わず、`note → booklet → note` と積む。
`BookletRouteContractTest` と `BookletNavigationTest` がこの形を固定する。
束はメモリにしか無いので、プロセス復元で冊子ルートだけが戻ったときはノートタブへ返す。
タブ遷移の設計は [tab_navigation](../dev/system/tab_navigation.md) にある。

### 5.2 画面幅

`AppScaffold` は `WindowSizeClass` を見て、Expanded 幅では左の `NavigationRail`、それ以外では下の `NavigationBar` を使う。
全画面ルートではタブを出さない。全画面ノートはシステムバーも隠し、離れるときにナビバーだけを戻す。

### 5.3 画面ごとの責務

**ノートタブ**

- ランダム表示。Vault の選択ボタンは未選択のときだけ出る
- 主ボタンの隣の `📖` で冊子へ行く
- Markdown 本文の表示とテキスト選択
- ヘッダ右の `✎` で余白メモのシートが上がる
- `⛶` で全画面へ行く。本文は最大720dpで中央に寄せ、スクロール位置を引き継ぐ。要約とクイズの状態は最小のFABで示す
- スクロール位置から現在のセクションを判定し、最終可視ブロックとその可視割合を読書痕跡へ報告する
- Rediscover で過去の痕跡があれば、本文の上に再会カードを出す
- ドラッグできる吹き出しからセクションAIを開き、そのシートから「この部分でクイズ」を始める

**さがすタブ**

- Vault 第一階層のフォルダを横スクロールのチップで選ぶ
- 自然文のクエリからAIが3件を選ぶ。AIを使わずに3件をランダムに選ぶこともできる
- 結果を開くとノートタブへ移る。更新日は `yyyy/MM/dd` で出す
- 下部に当日の閲覧履歴「今日読んだノート」を出す

**関連タブ**

- 規則ベースの関連ノートとAI推薦を別のセクションで出す。wikilink が一致したノートには `linked` の印を付ける
- AI が使えない、モデルの準備中、エラーをそれぞれ出し分ける

**AIタブ**

- 自動生成されたノート要約を出す
- 蒸留の起点。候補をタップすると範囲の調整シートが開く
- モデルのダウンロード中は進捗を出す
- クイズの起点は読書画面の吹き出しシートにあり、AIタブには無い。タブアイコンのバッジも無い

**オプション**

- 「Vaultを変更」。現在の選択をサブタイトルに出す
- 「データ管理」。痕跡の書き出しと読み戻しを本体に持ち、痕跡の整理と旧補記の片付けをここから開く
- 「ダークモード」。OS の設定には追従しない。`SharedPreferences` に保存し、再起動なしで反映する

**Snackbar 通知**

クイズの生成の開始・完了・失敗を `MainActivity` の `LaunchedEffect` が通知する。完了と失敗には「見る」「詳細」「もう一度」の操作が付く。
表示済みのイベントキーを `rememberSaveable` に控え、回転で再表示しない。全画面ノートの表示中は通知を出さず、全画面のFABが状態を示す。
余白メモは通知を持たない。

---

## 6. 主要機能のデータフロー

各機能を同じ形で書く。先頭に1文の説明、次に **入口**・**状態**・**書く先**・**AI**・**正本** の箇条、続けて **流れ** と **約束**。
当てはまらない項目は省く。**約束**は守っている不変条件を1行ずつ書き、理由は正本が持つ。

### 6.1 Vault選択と復元

Vault を選ぶと、旧 Vault に属する処理を止めてから新しい Vault を指す。

- **入口**: ノートタブの選択ボタン、オプションの「Vaultを変更」
- **状態**: `vaultSelected`
- **書く先**: `SharedPreferences` に Vault の URI
- **正本**: [architecture](../dev/system/architecture.md) の判断4、[saf_boundary_gateway](../dev/system/saf_boundary_gateway.md)

**流れ**

```text
OpenDocumentTree
  → 読み書きできる永続URI権限を取る
  → NoteSessionCoordinator.onVaultChanged()
      ① 記録中の読書セッションを保存せずに捨てる
      ② vaultGeneration を進める
      ③ 新しいVaultを指す。VaultLocation と保存URIを更新し、全体ノートと関連ノートのキャッシュを捨てる
      ④ 検索スコープのキャッシュと旧補記の一覧を捨て、ノート単位のジョブを止める
      ⑤ 閲覧履歴を捨て、状態をVault単位でリセットする
  → ランダムノートを1件読む
```

**約束**

- ①〜③の順序を変えない。変えると旧ノートの痕跡が新しい Vault へ入るか、旧世代の結果が素通りする。順序は調停側が持ち、`NoteViewModel` は URI の反映を `applyLocation` として渡すだけ
- 次回の起動では保存した URI を戻して `vaultSelected = true` にするだけで、ノートは自動で読まない
- 起動OP は `savedInstanceState == null` のコールド起動でだけ流す → [opening_animation](../dev/features/opening_animation.md)
- ランチャーの再タップは `onCreate` の先頭で畳む。受け取った Intent がランチャーのもので、タスクの最初の1枚でなければ `finish()` する。判定は `LauncherEntry.kt` の純関数で、`launchMode` を持たない前提は `LauncherEntryTest` がマニフェストで確かめる

### 6.2 ランダムノート表示

Vault の全 `.md` から1件を引いて開き、ノートに結び付く処理を始める。

- **状態**: `noteState`・`wikilinkTitles`・`todayHistory`
- **正本**: [rediscover](../dev/features/rediscover.md)

**流れ**

```text
loadRandomNote()
  → 旧ノートに属するジョブを止め、noteState を Loading にしてノート単位の状態を落とす
  → Vault全体を幅優先で走査する。60秒以内ならキャッシュを使う
  → _AI補記 と _ReadingTraces を除いた .md から1件を選ぶ
  → UTF-8で本文を読み、Vault相対パス付きで読書セッションを始める
  → noteState = Success、閲覧履歴に記録する。openNote も同じ
  ├── 過去の痕跡を照合して再会カードを出す。Rediscover のときだけ
  ├── fetchSummary()
  ├── fetchRelatedNotes()
  └── 分野判定。索引に現在の入力版の確定が無いときだけ
```

Vault に Markdown が無ければ `NoteState.Empty`、読み込みに失敗すれば `NoteState.Error` になる。エラーのときは画面に一般化した文を出し、Toast で例外のメッセージを出す。

### 6.3 ノート要約

開いたノートを2〜4文に要約する。同じ入力の要約は端末に保存してあり、開き直しても作り直さない。

- **状態**: `summaryState`
- **書く先**: `noBackupFilesDir/summary_cache/`。鍵は完成したプロンプトの SHA-256 で、1件1ファイル、上限1000件、最後に使った時刻が古いものから消す
- **AI**: 1回。本文の抜粋は1,200文字まで。ノートに続けて3秒留まるまで生成しない
- **正本**: [note_summary](../dev/features/note_summary.md)、[background_ai_ux](../dev/system/background_ai_ux.md) §7

**AIの状態ごとの動き**

| AIの状態 | 動作 |
|---|---|
| `Ready` | 保存済みがあればそれを出す。無ければ門番を待って生成する |
| `NeedsDownload` | モデルのダウンロードを始め、進捗を `SummaryState.Downloading` へ出す。完了したら保持していたタイトルと本文で要約と関連ノートをやり直す |
| `Downloading` | 何もしない。走っているダウンロードには合流できないので、次に開いたときに取り直す |
| `Unsupported` / `TemporarilyUnavailable` | `SummaryState.AiUnavailable`。要約パネル自体を出さない |
| 生成失敗 | `SummaryState.Error` |

**約束**

- 保存済みを引くところまでは門番の手前で行う。開き直したノートの要約は待たされない
- AICore が回数制限で断ったときは、SDK の文言ではなく「端末のAIが混み合っています。少し待ってからノートを開き直してください。」を出す。判定は `isAiCoreBusy` で、長期の利用枠の超過は含めない
- Debug APK では要約の生成を呼ぶたびに logcat へ1行出す。保存が当たったかどうかはファイルからは分からないため

### 6.4 関連ノート

規則で決まる候補と、AIが選ぶ候補を2段で出す。AIが失敗しても規則の候補は出る。

- **状態**: `relatedNotesState`。失敗の枝は持たない
- **AI**: 1回。候補のIDだけを返させる。ノートに続けて3秒留まるまで生成しない
- **正本**: [related_notes_ai](../dev/features/related_notes_ai.md)

**規則ベース**

1. 現ノート自身を正規化タイトルで除く
2. 本文の `[[wikilink]]` と一致するノートを拾う
3. ファイル名の先頭が4桁の16進数なら、上2桁が同じノートを同じグループとして拾う
4. wikilink の一致を先、同じグループを後に並べ、URI で重複を除いて最大5件を返す

**AI推薦**

1. タイトル話題スコアで全 Vault を並べ、上位40候補に絞る。スコアはタイトルの文字bigram Dice係数が主で、採番プレフィックスの近さを加点する。規則ベースに出たタイトルは先に除く
2. 候補の本文を同時8本までの並列で読み、冒頭のスニペット・タグ・aliases で肉付けする。`URI+lastModified` をキーに、成功したときだけ控える
3. 現ノートの本文シグナルで40件を並べ直す。タグの一致が主で、スニペットとタイトルの類似を足す。件数は変えない
4. 並べ直した順に一時ID `C01..` を振り、候補を3,500文字の予算内へ縮めて整形する。現ノートの本文は800文字の抜粋にして渡す。タグは抜粋とは別に `parseMeta()` から取る
5. AI にはIDだけを返させ、行頭付近のIDだけを実ノートへ解決する。規則ベースとの重複を除いて最大5件を返す

**約束**

- AI が使えない、モデルが未準備、生成で例外が出た、のどれでも規則ベースの結果だけを返す。自動で走る機能なので理由は見せない
- 候補1件の本文が読めなくても、その候補だけをタイトルで続ける。推薦全体を巻き添えにしない
- 規則ベースの結果も AI の応答と一緒に返るので、関連タブの表示はその分だけ遅れる

### 6.5 さがす

キーワードまたは自然文で3件を選ぶ。AIを使わずにランダムで3件を選ぶこともできる。

- **状態**: `folders`・`selectedFolder`・`foldersError`・`searchState`
- **AI**: 自然文検索で1回。タイトル一覧だけを渡し、本文は渡さない
- **正本**: [ai_picker](../dev/features/ai_picker.md)

| 選んだスコープ | 対象 |
|---|---|
| ルート直下 | Vault 直下の `.md` だけ。再帰しない |
| 第一階層のフォルダ | そのフォルダ以下を再帰で走査する |

**流れ**

1. 候補が40件を超えるときだけ、クエリとファイル名の文字bigramの重なりで上位40件に絞る
2. AI へタイトル一覧を渡して最大3件を取る
3. AI が使えないか未ダウンロードなら、bigramスコアの順で選ぶ。一致が0件の候補は返さない。1文字のクエリは部分一致で救う
4. ランダムは `shuffled().take(3)` で選ぶ

**約束**

- 画面の「キーワード一致で表示しています」が常に真になるよう、フォールバックは一致0件を返さない。AIへ渡す絞り込みのほうは0件も残す
- 検索とランダムは同じ `searchState` を書くので、`searchJob` 1本と requestId を共有し、新しい要求が前の要求を止める
- `SearchPickerUseCase` は結果型でエラーを返すが、`CancellationException` だけは畳まずに投げ直す
- 検索タブでは `_AI補記` を除かないので、旧補記ファイルも候補に出うる

### 6.6 セクションAI

いま読んでいる見出し単位で、要約・質問候補・Q&Aを出す。

- **入口**: 読書画面のドラッグできる吹き出し
- **状態**: `sectionChat`・`isSectionChatSheetVisible`
- **AI**: 開いたときに要約と質問候補で2回。候補をタップするたびに回答で1回。本文の抜粋は1,500文字まで
- **正本**: [section_ai_chat](../dev/features/section_ai_chat.md)

**流れ**

1. 本文を一度 Markdown ブロックへ解析し、描画とセクション判定で共有する。`LazyColumn` の先頭可視ブロックより前で最も近い見出しが現在のセクション
2. セクションの範囲は、その見出しから同じレベルか上位の次の見出しの直前まで。配下の小見出しを含む。見出しが無ければノート全体
3. 吹き出しを開くと、要約、続けて最大3件の質問候補を生成する
4. 候補をタップすると、セクション本文と会話履歴を渡して回答を生成する

**約束**

- 回答のプロンプトは、セクションに書かれていないことを推測しないよう制約する
- 自由入力の欄は無い。質問の入口は生成した候補のタップだけ
- シートを閉じても同じノートのうちは結果を持ち、吹き出しから再表示できる

### 6.7 適応出題クイズ

いま読んでいる周辺のテキストから、素材の量に合う形式で出題する。

- **入口**: セクションAIのシートの「この部分でクイズ」
- **状態**: `quizState`
- **AI**: 1回。周辺テキストの抜粋は1,200文字まで
- **正本**: [quiz](../dev/features/quiz.md)、[section_ai_chat](../dev/features/section_ai_chat.md)

**流れ**

1. シートの対象セクションを核に、前後のブロックを交互に足して約1,200文字の周辺テキストを作る。ブロック単位で足すので超えうる。プロンプトの直前で1,200文字の抜粋を通す
2. `QuizState.Loading` を立てる。待機画面は出さない
3. `checkAvailability()` で分ける。`Ready` なら生成し、`NeedsDownload` ならダウンロード後に自動で再開する。`Downloading` はダウンロードを始めずに待つ。`Unsupported` と `TemporarilyUnavailable` はエラーにせず `QuizState.AiNotice` にする
4. 周辺テキストを AI を使わずに分類し、形式を決める。コードの比率が45%以上なら3択2問。本文が180字未満か文のシグナルが2以下なら○×2問。本文が700字以上かつ文のシグナルが6以上なら4択1問。それ以外は3択2問。解説は4択だけに1文付ける
5. `Q:` 行を問題の始まりとして `parseQuizResponse` が読む。多択の正解記号は単語境界の正規表現 `\b[A-D]\b` で抜き、崩れた書き方を救う。必須の欄が欠けたものと範囲外の記号は捨てる
6. 読めた問題が0件なら `QuizState.Error`、あれば `QuizState.Success` にして Snackbar で知らせる

**約束**

- 生成中の再タップは無視する。ノート切替後の古い結果は requestId で捨てる
- クイズはセクションチャットのセッションに従属し、新しいセッションの開始か明示の終了で消える
- 出力を256トークン程度に収めるため、問題数と解説を形式ごとに絞っている

### 6.8 余白メモ

読んでいる最中に浮かんだ短い断片を、本文に触れずに何度でも置ける。AIを1回も呼ばない。

- **入口**: ノート本文ヘッダ右の `✎`。再会カードに `前回のメモを見る` が出ていればそこからも同じシートが開く
- **状態**: `marginMemoState`・`isMarginMemoSheetVisible`
- **書く先**: `_ReadingTraces/*.json` の `memos` 配列。schema v7。Vault の `.md` には触れない
- **AI**: 呼ばない。Nano 非対応の端末でも同じに動く
- **正本**: [reflect_margin_memo](../dev/features/reflect_margin_memo.md)。前身のひとことを畳んだ判断は [reflect_remark](../dev/features/reflect_remark.md)

**流れ**

1. `✎` を押すとボトムシートが上がる。本文はスクロール位置ごと背後に残る
2. 上段が入力欄、下段がこのノートの既存メモで、新しい順に並ぶ
3. 書いて `置く` を押すと一覧の先頭に増え、入力欄が空になる。シートは閉じない
4. 各行の `消す` は確認ダイアログを挟む。編集は無い
5. 後日 Rediscover で同じノートを引き、メモが1件以上あれば再会カードの行が `前回のメモを見る` になる

1件は本文・書いた日時・置いたときに見ていた見出しの3つを持つ。見出しは記録で、あとで改名しても解決し直さない。
全画面表示中は書けず、`✕` で通常表示へ戻ってから置く。

**上限**

| 対象 | 値 |
|---|---|
| メモ1件の保存 | 1,024バイト。日本語で約340字 |
| 静かに知らせる目安 | 200字。切りも拒否もしない |
| 1ノートの保持件数 | 20件。達したら置けない。古いものから捨てない |
| 置いた見出し | 512バイト |
| 痕跡ファイル全体 | 128KB |

**約束**

- 1,024バイトを超えたら切り、切ったことを必ず示す。20件に達して置けなかったときは入力欄の文字を消さない
- 保存の結果は Saved・Held・Failed の3値で、Held を「保存済み」と呼ばない。Full は Failed に畳まない。切り詰めたかどうかは成否と別に持つ
- 保存と削除は同じ錠で直列化する
- 訪問の保存で容量が足りないときは、メモに触れず訪問だけを書く
- 入力欄の中身は画面側が持つ。状態の受理件数が増えたときにだけ空にする
- 入力は保存前に改行とタブ以外の制御文字を落とす。上限どうしの整合は実シリアライザで測って固定する
- v6 以前のひとことの欄は、書かれた版の正規形で checksum を検証してから読み捨てる
- 2026-08-09 より前に作られた `_AI補記/*.md` は消さない。`AnnotationController` が一覧と削除だけを持つ

### 6.9 当日閲覧履歴

その日に開いたノートを最大10件、さがすタブに出す。

- **状態**: `todayHistory`
- **書く先**: `SharedPreferences` に日付キー付きのJSON

読み出したときに保存日が今日でなければ空を返すので、日付が変わると履歴は消える。
同じ URI は先頭へ移る。`loadRandomNote` と `openNote` の成功時に記録し、「今日読んだノート」から `openNote` で開き直せる。

### 6.10 蒸留

AI が原文の重要箇所を選び、ユーザーが確認した箇所だけを元ノートで `**太字**` にする。AI は文章を作らず、選ぶだけ。

- **入口**: AIタブ
- **状態**: `distillState`
- **書く先**: 元ノートの `.md`。`**` を挿すだけで、既存の文字は消さない
- **AI**: 1回。候補のIDだけを返させる
- **正本**: [reflect_distill](../dev/features/reflect_distill.md)

**流れ**

1. `buildDistillSourceModel` が本文を文へ分ける。UTF-16 のオフセットを保ち、コードフェンス・表・frontmatter・見出しを除く。コードスパン・リンク・斜体・太字斜体・打ち消し線といった保護範囲には境界を置かない。判定は表示と共有の `scanInlineSyntax` が返す
2. 同じ段で粒度を決める。60字を超える文は読点で句へ割り、鉤括弧の中身は語句の候補にする。各候補は親文の範囲を持つ
3. `selectDistillCandidates` が AI を使わずに採点する。タイトルと直近の見出しとの bigram Dice、段落の先頭と末尾と見出し直下の重みを使い、チャンク網羅で絞る。リンクだけの候補、端が保護範囲の内側に入る候補を外し、出口で候補を重ならない集合にする
4. `buildDistillPrompt` が候補のIDと原文を渡し、`parseDistillResponseIds` が境界付きの正規表現でIDだけを抜く。許すのは実際に渡した候補だけ
5. 候補をタップすると調整シートが開く。太字にする範囲を `語句`・`意味節`・`文全体` の3段から選ぶか、両端のつまみを引いて親文の内側で自由に決める。端を1境界ずつ動かす微調整ボタンもある。範囲を広げて他の選択と重なれば `resolveOverlaps` が相手の選択を外し、理由を印として残す
6. `applyDistillBold` がオフセットの降順で `**` を挿す。重なる範囲は `require` で拒む。太字の累積は本文の30%まで
7. `DistillWriteRepository` が保存する。原バイトの SHA-256 を二重に照合し、キャッシュを作って fsync、復旧レコードを原子的に確定、SAF の `"wt"` で一気に書き、出力のハッシュを検証する
8. 保存後は `openNote()` を使わずに本文だけを読み直す。要約・関連・余白メモは残し、セクションチャットとクイズは捨てる

**約束**

- 未調整なら、保存結果は範囲調整が無かったころと同じ。調整結果は永続しない
- 自由範囲の端を置ける位置は、親文の分を全部数えて決める。書記素の内側と、装飾の対を片側だけ割る位置には止まらない。書記素は `BreakIterator` を使わず自前で数える
- 中断したときは起動時に4通りで復旧を判定し、現在を維持・元へ戻す・別ファイルへ書き出すを選ばせる。空き容量が不明か不足なら書かない
- ノート切替では止めるが、復旧はノートをまたいで残す
- ハッシュ照合と書き込みの間の TOCTOU は完全には閉じられない。既知の制約

### 6.11 読書痕跡と再会カード

読書位置を自動で記録し、Rediscover で同じノートを引いたときだけ、前回の読み方を再会カードに出す。

- **状態**: `readingTraceCard`
- **書く先**: `_ReadingTraces/<sha256(相対パス)>.json`。SAF の `"wt"` でのベストエフォート
- **AI**: 再会カードの要約で最大1回。ノートに続けて3秒留まるまで呼ばない
- **正本**: [reflect_reading_trace](../dev/features/reflect_reading_trace.md)、[reunion_card](../dev/features/reunion_card.md)

持ち主は2つに分かれる。訪問の記録と余白メモの保存は `ReadingTraceController`、再会カードの照合・AI要約・印は `ReunionCardController`。
UIの状態を書くのは後者だけで、両者はサイドカーの read-modify-write を直列化する錠を共有する。

**流れ**

```text
ノート表示の前
  → Vault相対パス・タイトル・documentId でセッションを始める
  → Compose が最終可視ブロックとその可視割合を5%刻みで報告する
  → Controller は最深の blockIndex・可視割合・sectionTitle をメモリで更新する
  → ノート切替で flush してセッションを終える。onStop では計測を止めてセッションを保つ
  → 能動読書が10秒以上で本文を描いていれば、訪問を記録する
  → 開いた時点のVaultキーを添えて保存する
  → onStart の後に読み進めたら、同じ訪問を差し替える

Rediscover
  → 相対パスでサイドカーを照合し、生の最終訪問をカードにすぐ出す
  → 「まだ考えたい」の印があれば、保存した内容を再掲して終わる。生成しない
  → 2訪問以上で、前回の試行から訪問が増えていれば、原文全体から候補を規則で並べる
  → 種別を問い、古い前提、俯瞰要約の順で決め、門番を待って Nano を1回呼ぶ
  → 結果を3つに分ける
      Generated    1件決まった。カードとサイドカーへ載せる
      NoCandidate  AIが該当なしと答えた。枠は出さず、空振りを記録する
      Unavailable  呼べなかったか失敗した。何も記録せず、次に開いたとき試し直す
```

**約束**

- サイドカーは schemaVersion・UTF-8のバイト上限・canonical payload の checksum で検証する。壊れていればカードを出さず、次の訪問で作り直す
- 訪問は直近30件を持ち、延べ回数は `totalVisitCount` として別に積む。表示と要約の鮮度判定は延べ回数を使う
- 枠に出すものは、俯瞰要約・当時の問い・古い前提・印の4種類から1件だけ。種別は `aiSummaryKind` の欄で持つ
- 候補の列挙は抜粋ではなく原文全体に当てる
- 到達率は最深ブロックの index とその可視割合を総ブロック数で割り、切り捨てる。100%は最終ブロックの末端が画面に入ったときだけ
- 読書時間は背面にいた分を除いた能動時間で測る。計測を止める理由は `ReadingPauseReason` の集合で持ち、理由が1つも無いときだけ計測する。理由は背面と冊子
- 保存と照合の要求は開いた時点の Vault キーを運び、Gateway が書く直前に現在の Vault と照合して、違えば捨てる
- フォルダ索引は、照合したキーが索引に無いときだけ作り直す。外部同期で後から増えたファイルもこれで拾う

### 6.12 読書痕跡の退避と読み戻し

痕跡を1ファイルへ書き出し、そこから読み戻せる。ユーザーが書いた余白メモは再生成できないので、フォルダごと失ったときの退避先になる。

- **入口**: オプションの「データ管理」
- **状態**: `readingTraceBackupState`
- **書く先**: ユーザーが選んだ場所に1つのJSON。読み戻しでは `_ReadingTraces/`
- **正本**: [reading_trace_backup](../dev/features/reading_trace_backup.md)

**流れ**

```text
書き出し
  → Vault内の痕跡を全部走査し、1つのJSONにまとめて保存する
  → 中身は平文。Vault内へ置くと Obsidian の同期でクラウドへ渡ることを、押す前に伝える

読み戻し
  → まず下見を出す。何件増えるか、何件変わらないか、何が読めなかったか
  → ユーザーが適用を選んで初めて書く。適用中は中止できる
```

**約束**

- 併合は端末に無かった痕跡を受け入れる形で、既存を黙って上書きしない
- メモは配列なので欄ごとに合流する。合流後に20件を超えるノートは、端末側も退避側も変えずに保留し、成功として数えない。退避ファイルの中で同じノートが重複して畳めないときも、そのパスごと保留する
- 読めなかったものは件数と理由を出す
- 中止は要求であって完了ではない。停止を待つ間の再タップで件数を確定しない
- 走査と JSON 処理は Main の外で行う。`ReadingTraceBackupThreadingTest` が最大入力で固定する

### 6.13 冊子

ランダムを1回押して10枚の束を作り、上へ繰る。冊子の中ではAIも訪問の記録も走らない。

- **入口**: ノートタブの `📖`
- **状態**: `bookletState`。Vault 単位
- **AI**: 呼ばない。編む束も済んだAI推薦を使うだけ
- **正本**: [booklet_mode](../dev/features/booklet_mode.md)

**流れ**

```text
ノートタブ
 ├─ [1枚ひらく]      → 通常表示。要約・痕跡・履歴をここで始める
 └─ [📖 冊子をひらく] → 冊子ルート。AI・痕跡・履歴は動かさない
                          └─ これを読む → 通常表示。ここで初めて始める
```

1. `shuffled().take(min(10, ノート数))` で束を作る。束の中では重複させない。持つのは参照・タイトル・扉の1行だけで、本文は持たない
2. 扉は本文から選ぶ。生成しない。読むのは現在ページと前後1ページだけで、8KBの境界読み出しを使う。`selectCoverLine` が frontmatter・見出し・コードフェンス・表の区切り・罫線・リンクだけの行を落とし、最初の1文を全角40字で切る。選べる文が無ければタイトルを出す
3. 読めなかったページはそのページだけを失敗として見せる。扉の状態は Loading・Ready・Failed の3つ
4. 10枚を使い切ったら末尾に「もう10枚引く」ページを置く。自動では継ぎ足さない。直前の束と同じノートが出てよい
5. 「これを読む」で通常表示へ渡り、そこで初めて訪問の記録・要約・関連ノートが始まる

**編む束**

冊子へ入る直前に開いていたノートを種にして、済んだAI推薦から2つ目の束を作る。追加の Nano 呼び出しも I/O も無い。
並べ方は AI推薦・未リンク・wikilink 済みの順で、重複は参照で畳み、種自身は除く。候補が少なければ水増しせず、`min(10, 候補数)` をそのまま出す。
冊子の中のトグルで引く束と行き来し、両方のページ位置が残る。トグルは3通りで、種が無ければ出さず、編めなければ理由を1行添えて押せない形で出す。
編む側の終端は枚数だけを告げる紙で、「もう10枚編む」は置かない。

**紙面**

| 要素 | 冊子。眺める面 | 通常表示。読む面 |
|---|---|---|
| 角 | 2dp | 8dp |
| 面の広がり | 四辺に余白を見せ、下に地を残す | 全幅で、スクロールで続く |
| 背後 | 紙の縁が2枚覗く | 無し |

この3つは1つの役割トークンから引き、`BearingChannelTest` がどの面がどちらを引くかを固定する。
縁は束の10枚に位置によらず同じに出て、「もう10枚引く」のページにだけ無い。残りの枚数はページインジケータの文字が持つ。
紙の地色はノートの分野で塗り分ける。流れは §6.14。

綴じは天綴じで、紙は上端を蝶番にして倒れる。倒れるのは束を引き直した積み直りのときだけで、最大22度。
めくりは右下の角を持ち上げると折り目が左上へ斜めに走り、折り返した紙は折り目を鏡にした像として裏を見せる。
指を離したあとのスナップは620msで減速して終わる。OS の「アニメーションを無効」では、指に追従する変化はそのまま出て、時間で進む送りと積み直りは省く。

**約束**

- 束の寿命は Vault 単位。ノートから戻れば同じ10枚が同じページ位置で残る。消えるのはアプリの再起動・Vault 切替・「冊子をひらく」の押し直しだけ
- 別のタブを押して畳まれるのは冊子ルートで、束ではない
- ページ位置は束と同じ `BookletState.Open` に置く
- 冊子では、冊子の候補について新しいAI・痕跡・履歴を始めない。冊子へ入る前から走っている処理は止めない。読書時間の計測だけは止める
- 冊子へ戻ったときは、走行中の「これを読む」を `cancelBookletRead()` で取り消す

### 6.14 分野判定と冊子の紙の色

ノートを開いたときに、AI が固定リストから分野を1つ選ぶ。結果は冊子の紙の色になり、画面には進捗も失敗も出さない。

- **状態**: `noteFields`。分野の索引A。Vault 単位
- **書く先**: 確定を `SharedPreferences` に1件1キーで。鍵の先頭に Vault の名前空間を持ち、件数上限を超えたら新規は書かない。バックアップからは除外している
- **AI**: 1回。抜粋600文字とフォルダ由来のヒントを渡し、ID を1つ返させる。ノートに続けて3秒留まるまで呼ばない
- **正本**: [note_field_color](../dev/features/note_field_color.md)

**流れ**

```text
Vault走査
  → パスの各セグメントを一般語彙と辞書照合し、当たればヒントとして索引Aへ暫定で載せる。メモリだけ
  → 永続から読んだ確定は暫定を上書きする。入力版が失効した確定は補完材料から外す

ノートを開く
  → 索引Aに現在の入力版の確定があれば何もしない
  → 無ければ索引B、つまり入力指紋から答えへの表を引く。当たればAIを呼ばずに確定を戻す
  → どちらも無ければ門番を待ち、抜粋とヒントを添えてAIへ渡す
  → 応答は F1〜F6 か NONE。応答全体がIDの行だけのときに受け付ける
      分野を1つ返した   確定として索引Aへ永続し、索引Bにも載せる
      NONE             分野なしの確定として同じに扱う。無彩色
      失敗・非対応      暫定のまま。永続しない
      キャンセル        何も書かない

冊子を開く
  → 束10枚の紙を索引Aの現在値で塗る。未判定は無彩色。索引が届けば保持中の束にも色が届く
```

**約束**

- 色は6つで固定し、追加の語彙は親の色を継ぐ
- 分類は未判定・暫定・確定の3値で、同じ入力版の中では一方向にしか進まない。入力版が変われば確定でも判定し直し、古い確定はヒントの暫定へ落とす
- 入力指紋は AI へ渡したものすべてを含む。タイトル・抜粋・予算・ヒント・語彙の版・プロンプトの版
- 索引Aの書き手は Vault 走査・ノートを開いた経路・永続の読込の3つで、書き込みは1箇所へ直列化し、確定を消さない。有効な確定のときは索引Aへ書かない
- モデルのダウンロードを自動で始めない
- ジョブはノート単位の契約に登録して切替で止め、古い結果は requestId で捨てる。索引はノート切替では触らず、Vault 切替で落とす

---

## 7. SAF・Vaultアクセス層

### 7.1 走査

`NoteRepository` は `queryChildren()` にカーソル処理を集め、`ArrayDeque` を使った幅優先でフォルダを再帰的に走査する。再帰関数ではないので、深いフォルダでもコールスタックを消費しない。
取る列は document ID・表示名・MIME type・最終更新日の4つ。最終更新日が返らなければ `null` にし、UI では更新日を出さない。

### 7.2 読み書き

- 読み込みは `openInputStream()` から上限付きのバイト読込 `BoundedInputStream` で受け、`dropIncompleteUtf8Tail()` で末尾の欠けた多バイト文字を落としてから UTF-8 の文字列にする。用途ごとの読込予算は §8.4
- 旧補記フォルダは `queryChildren()` で列挙するだけで、書き出す経路は無い
- ファイル操作は `Dispatchers.IO` で行う
- Vault の URI は `SharedPreferences` に保存し、SAF の永続URI権限と組み合わせて使い回す

### 7.3 メタデータ解析

`parseMeta()` は先頭の YAML frontmatter から `tags` と `aliases` を、本文全体から `[[wikilink]]` を抜く。
frontmatter は `[a, b]` のインライン形式と、字下げした `- item` のブロック形式だけを読む簡易解析で、完全な YAML パーサではない。
`wikilinkTitles` は規則ベースの関連ノートとリンク判定に、`tags` と `aliases` は AI 推薦の候補の肉付けに使う。`tags` は本文シグナルでの再ランクの主スコアにもなる。

### 7.4 タイトル正規化

Obsidian のリンクと照合するときは、前後の空白・`|表示名`・`#見出し`・`^ブロックID`・フォルダパス・`.md` 拡張子を落とし、さらに小文字にする。
正規化後のタイトルを Map のキーにするので、別のフォルダに同名のノートがあると、後から入った一方だけが解決先になる。

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

AI を使う側はこのインターフェースに依存する。実装は本番用の `AICoreClient` と、手で UI を確かめる用の `StubAiClient` の2つ。

### 8.2 モデル設定と状態

`AICoreClient` は `ModelPreference.FULL` を指定して `Generation` クライアントを遅延生成する。FULL は速度より精度を優先する指定で、実際に動くモデルの世代は端末の AICore が決める。Pixel 10 系は nano-v3。
ML Kit の状態はアプリ内の5状態へ写す。判断の正本は [background_ai_ux](../dev/system/background_ai_ux.md) §6。

| ML Kit の状態 | アプリの状態 | 呼び出し側の次の行動 |
|---|---|---|
| AVAILABLE | `Ready` | 生成する |
| DOWNLOADABLE | `NeedsDownload` | `downloadModel()` を呼んでよい唯一の状態 |
| DOWNLOADING | `Downloading` | 待つ。`downloadModel()` は呼ばない |
| UNAVAILABLE で AICore が無い | `Unsupported` | 諦める |
| UNAVAILABLE で AICore が有る、未知の値、状態確認の例外 | `TemporarilyUnavailable(cause)` | 時間をおいて試し直す |

恒久的に非対応かどうかは `FeatureStatus` ではなく `GenAiUtils.isAiCoreCompatible` で決める。`UNAVAILABLE` は対応端末でも返るため。

### 8.3 直列化と生成の制約

- **直列化。** `generate()` は companion object の `Mutex` で1件ずつ実行する。要約・関連推薦・検索・クイズ・セクションAI・再会カード・分野判定が同時に来ても、モデルの生成は1本ずつ
- **タイムアウト。** 60秒で、Mutex を取った後から数える。ロックの待ち時間は含まないので、先の生成が長いと後の機能はその分だけ待つ。ML Kit の `TimeoutCancellationException` は `AiTimeoutException` に変えて、通常のエラー表示に乗せる
- **門番。** 自動で走る4本は、ノートの本文が出てから続けて3秒表示されるまで `generate()` を呼ばない。押して使う機能は待たせない → [background_ai_ux](../dev/system/background_ai_ux.md) §7
- **回数制限。** 錠の外側に AICore 自身の短期の回数制限がある。計測テストでは、連続で12回成功した直後の13回目が `ErrorCode 9 / BUSY` で断られた。窓の長さは AICore の内部にあり、固定の上限ではない → [ai_quality_measurement](../dev/system/ai_quality_measurement.md) 判断8
- **途切れの検知。** 応答の `finishReason` が `MAX_TOKENS` なら `AiTruncatedException` を投げ、途切れた文章を保存も表示もしない
- **出力トークンの上限。** genai-prompt 1.0.0-beta2 の `maxOutputTokens` は1〜256しか受け付けず、超えると `IllegalArgumentException` で全生成が落ちる。上限は設定せず SDK の既定のまま使い、各機能のプロンプト側で256トークン程度に収まる要求に絞る

### 8.4 プロンプトの入力上限

| 機能 | 本文の上限 | 候補の上限と出力 |
|---|---:|---|
| 要約 | 1,200文字 | 2〜4文 |
| 関連ノート | 800文字 | 候補最大40件を予算3,500文字内へ縮める。IDで5件を要求 |
| AIピッカー | 本文なし | タイトル最大40件を予算2,000文字内へ。切らずに行ごと落とす。3件を要求 |
| クイズ | 周辺1,200文字 | ○×2問、3択2問、4択1問のどれか。解説は4択だけ1文 |
| 蒸留 | 本文なし。候補の文だけ | 候補最大24件を予算1,500文字内へ。IDで最大6件を要求 |
| セクション要約 | 1,500文字 | 2〜4文 |
| セクションの質問と Q&A | 1,500文字 | 質問候補は最大3件 |
| 再会カードの俯瞰要約 | 本文なし | 直近10訪問を予算2,600文字内へ。古い訪問から落とす。1〜2文 |
| 再会カードの候補選別 | 本文なし。原文の1文ずつ | 種別ごとに最大10件を `ID｜原文` で出し、IDで1件。該当なしは `NONE` |
| 分野判定 | 600文字 | ヒントを添え、固定リスト6分野から IDで1件。該当なしは `NONE` |

上限はどれも UTF-16 の文字数で、トークン数や意味の境界では切っていない。切り方の設計は [ai_input_excerpt](../dev/system/ai_input_excerpt.md) にある。

- 本文を持つ経路は、呼び出し側が `buildNoteExcerpt(content, 上限)` で `NoteExcerpt` を作って渡す。`PromptBuilder` は切らない
- 予算内のノートは Markdown を解析せず、原文をそのまま渡す
- 超えたときだけ、レベル3までの見出しを全体から均等に選んだ骨格と、冒頭60%・末尾40%を、`## Note outline`・`## Beginning excerpt`・`(omitted)`・`## Ending excerpt` のラベル付きで組む。1つのブロックが枠を超えても捨てずに切り、コードブロックは閉じフェンスを戻す
- 超えたときは表示用のパーサを通すので、frontmatter が消え、コードフェンスの言語名が消え、段落内の改行が空白になり、箇条書きの記号が `-` に揃う。リストの番号と入れ子の段数は残る
- 抜粋したときだけ、226文字の注意書き `ABRIDGED_NOTICE_PREFIX` を本文の直前に置く。注意書きは上限の内側から払う
- 抜粋の生成は1MBのノートで約460msかかるので、`Dispatchers.Default` で行う
- 蒸留だけはこの経路に乗らない。ノート全体を最大400文へ分け、チャンク網羅を条件に上位を候補にする

**完成プロンプトの上限**

上の表は本文に由来する部分だけを閉じている。会話履歴・質問・候補名・見出しはその外にある。
`PromptBudget.assemble()` が最後に1回だけ `PromptLimits.MAX_PROMPT_CHARACTERS` の6,000文字を当て、11本すべての builder がここを通る。

| 部位 | 上限を超えたとき |
|---|---|
| 指示文。役割・出力形式・クイズの書式 | 削らない |
| 材料。タイトル・本文の抜粋・候補一覧・会話履歴 | 末尾から削り `(truncated)` を残す |
| 締め。新しい質問 | 削らない |

6,000は、意図する最大の構成である関連ノートの指示文・タイトル・抜粋800・候補3,500から決めた値で、この値ではどの経路の入力も短くならない。
部分の予算を上げると `PromptBudgetTest` が落ちる。実トークンでの余裕は [ai_input_excerpt](../dev/system/ai_input_excerpt.md) §13 にある。

---

## 9. Markdown解析・描画

### 9.1 対応ブロック

- 見出し H1〜H6
- 段落
- 箇条書き・番号付きリスト・タスクリスト。入れ子と番号を保ち、1つの `ListBlock` にまとめる
- fenced code block
- 水平線
- 引用
- パイプテーブル
- 画像。1行が `![[...]]` か `![](...)` だけのとき

リスト項目は `ListItem(depth, marker, text, checked)` で、入れ子の段数・番号の原文表記・タスクのチェック状態を持つ。
段数の出し方は CommonMark にも Obsidian にも従わない独自の寛容な規則で、設計は [markdown_rendering](../dev/system/markdown_rendering.md) にある。
箇条書き記号 `-`・`*`・`+` の違いと、コードフェンスの言語指定は持たない。

### 9.2 対応インライン記法

- `***太字イタリック***`
- `**太字**`
- `*イタリック*`
- `~~打ち消し線~~`
- `` `インラインコード` ``
- `[[Obsidianリンク]]`
- `[ラベル](URL)`

解釈するのは `domain/markdown/InlineSyntax.kt` だけで、蒸留も同じ関数を呼ぶ。描画側は種別を色・太さ・下線へ写すだけ。
記法を足すときは解釈器へ足す。描画側だけに足しても蒸留は守れない。

エスケープ `\*` は記号だけを描き、インラインコードは開いた数と同じバッククォートで閉じる。入れ子も描くので、`**A *B* C**` の内側の斜体は残る。
リンクは色と下線で飾るだけで、タップしても遷移しない。ノートの埋め込み・脚注・HTML・数式は専用の対応が無い。
リスト項目に字下げなしで続く本文行は、段落の遅延継続として扱わず、別のブロックになる。

### 9.3 防御的な処理

- 先頭の閉じた YAML frontmatter は描かない
- テーブルの途中の空セルを保ち、列がずれないようにする
- 強調記号は、中身が空でなく先頭と末尾が空白でないときだけ成り立つ
- `[label](url)` は最初の `]` の直後が `(` のときだけリンクとみなす。`arr[0]` を誤って拾わない
- CRLF を LF にそろえる

### 9.4 描画の効率

解析は `NoteSectionController` が `Dispatchers.Default` で1回だけ行い、`StateFlow<NoteSectionModel?>` を `MainActivity` が通常表示と全画面の両方へ配る。
セクション判定と描画は同じブロックを使い、インラインの `AnnotatedString` もテキストごとに `remember()` する。
結果が届くまでノート本文は描かない。描くと最大1MBの本文を Main の上で解析し直すことになる。

通常表示と全画面は別々の `LazyListState` を持つ。全画面は入るときにタブ側の位置から始め、`✕`・システムバック・FAB で離れるときにタブ側へ書き戻す。

### 9.5 画像

`![[...]]` は Vault 全体の画像索引で解決する。照合は完全パス、次にファイル名の順。
索引は `VaultImageIndexStore` がメモリに持ち、TTL を過ぎて解決に失敗したときだけ1回作り直す。`.obsidian` フォルダは走査しない。

1枚の読み込みは `NoteImageGateway` が行い、`ImageDecodePolicy` の予算を当てる。入力16MBまで、一辺20,000pxまで、復号後400万ピクセルまでで、キャッシュは32MB。
出せなかった理由は `NoteImageFailure` の8通りで、見つからない・確かめられない・候補が複数・外部URL・空・非対応の形式・大きすぎる・壊れている。文言は表示側が選ぶ。
設計は [note_image_rendering](../dev/features/note_image_rendering.md) にある。

---

## 10. 並行処理・ライフサイクル・キャッシュ

### 10.1 ジョブと、古い結果の照合

ジョブの停止はノート切替なら `NoteSessionCoordinator.cancelNoteScopedJobs()`、Vault 切替なら `onVaultChanged()` が一括で行う。
キャンセルだけに頼らず照合も置くのは、モデルのダウンロードのコールバックのようにキャンセルをすり抜ける完了通知があるため。

| 持ち主 | 追跡するジョブ | 古い結果の捨て方 |
|---|---|---|
| `NoteViewModel` | `noteLoadJob`・`relatedNotesJob` | Coordinator の `cancelHostJobs` から止まる |
| `SummaryController` | `summaryJob`・`downloadJob` | requestId |
| `QuizController` | `generateJob`・`downloadJob` | requestId |
| `SectionChatController` | `openJob`・`answerJob` | 書く直前に、いまのセッションがまだあるかを見る |
| `DistillController` | `job`・`recoveryJob` | requestId |
| `MarginMemoController` | `loadJob`・`writeJobs` | 自前の世代番号。保存と削除は錠で直列化する |
| `ReunionCardController` | `revealJob` | requestId |
| `NoteFieldController` | `job` | requestId。ノート切替でも Vault 切替でも `cancelAndClear()` が進める |
| `NoteSectionController` | `parseJob` | キャンセルだけ。requestId は持たない |
| `ReadingTraceController` | 持たない。保存は `persistScope` へ投げる | 開いた時点の Vault キーを書く直前に照合する |
| `SearchController` | `searchJob`・`foldersJob` | 検索とランダムは requestId を共有する。フォルダ一覧は `vaultGeneration` |
| `AnnotationController` | `listJob` | `vaultGeneration` |
| `ReadingTraceCleanupController` | `assessJob` | `vaultGeneration`。削除は洗い出した時点の Vault 識別子も照合する |
| `ReadingTraceBackupController` | `job` | `vaultGeneration` |
| `BookletController` | `drawJob`・ページごとの `coverJobs` | `vaultGeneration`。引き直しとすれ違った扉は新しい束へ書かない |
| `NoteSessionCoordinator` | `bookletReadJob` | 冊子へ戻ったときに取り消す |

`vaultGeneration` は `NoteSessionCoordinator` が持つ単調増加の `Long` で、`vaultUri` の比較では代用しない。
照合は `update` の直前の1箇所に集める。二層の判断は [architecture](../dev/system/architecture.md) の判断4 にある。

### 10.2 CancellationException

要約・関連ノート・セクションAI・クイズ・余白メモ・検索・蒸留の主要な経路で `CancellationException` を投げ直し、一般のエラーに変えない。
結果型でエラーを返す `SearchPickerUseCase` も、キャンセルだけは例外のまま通す。
蒸留の元本文の書き出しはキャンセルされてもエラーにせず、復旧レコードも消さない。

### 10.3 キャッシュ

| キャッシュ | キー | TTL | 捨てる契機 |
|---|---|---:|---|
| Vault 全体のノート | 現在の Vault で1件 | 60秒 | Vault 切替 |
| 検索スコープのノート | `documentId`。ルートは null | 60秒 | Vault 切替 |
| 読書痕跡のフォルダ索引 | Vault URI | なし | Vault URI の変化、I/O例外。キーが無いときは作り直す |
| 画像索引 | Vault 世代 | あり | Vault 切替。TTL を過ぎて解決に失敗したとき作り直す |
| 分野の索引A | `DocumentRef` | なし。確定は永続 | Vault 切替。暫定は走査で作り直す |
| 分野の索引B | 入力指紋 | なし。メモリだけ | 読み込みのたびに索引Aから作り直す |
| 要約の保存 | 完成したプロンプトの SHA-256 | なし。端末に残る | 1000件を超えたとき、最後に使った時刻が古い順。ノート切替でも Vault 切替でも捨てない |

キャッシュがあるので、ランダム表示や検索のたびに SAF を全部走査しない。代わりに、外部の Obsidian 同期や編集の結果は最大60秒遅れて届く。
空のリストは全体キャッシュで使い回さないので、Markdown が無い Vault では操作のたびに走査し直す。

---

## 11. エラー処理とフォールバック

### 11.1 備えているもの

- ノート読込の失敗、AI 生成の失敗、モデルのダウンロードの失敗を sealed state で UI へ伝える
- 関連ノートは AI が失敗しても規則ベースの結果を保つ
- セクションの質問候補が失敗しても、要約と Q&A 本体は壊れない
- AI の空応答には、要約文・チャットの回答・分野判定の応答読み取りで一定の備えがある
- プロンプトに書いた契約は検査に移す。分野判定は行の全体がIDのときだけ受け付け、蒸留は許可集合の外のIDを捨てる

### 11.2 注意点

- フォルダ一覧の取得に失敗しても、ユーザーには知らせない
- 補記の削除は `deleteDocument()` の結果を確かめずに一覧を読み直す。失敗すれば対象が残ることで分かるが、エラーは出ない
- 閲覧履歴の JSON が読めなければ空の履歴として扱う。失うのは履歴だけ
- `openInputStream()` が `null` なら空文字を返すので、読込の失敗と空のノートを見分けない

---

## 12. データ保護・プライバシー

- ノートの本文はアプリの中で読み、AI 生成は端末内の Gemini Nano で行う。クラウドAI API・独自サーバー・解析SDKへ送るコードは無い
- 成果物にネットワーク権限が無い。ML Kit GenAI が推移的に引く `transport-backend-cct` が持ち込む `INTERNET`・`ACCESS_NETWORK_STATE` を、マージ後マニフェストから除いている。モデルのダウンロードと生成は AICore アプリが別プロセスで行うので、こちら側に権限は要らない。初回のモデル取得には ML Kit 側のダウンロードが要る
- Vault へ書くのは3つだけ。蒸留による既存ノートへの `**` の挿入、`_ReadingTraces/` への痕跡JSONの保存、旧 `_AI補記` ファイルの削除
- 蒸留の上書きは原バイトの SHA-256 の二重照合と出力ハッシュの検証を通し、中断したら `noBackupFilesDir` の復旧レコードから起動時に判定する
- 読書痕跡はユーザーの `.md` に触れないベストエフォートの設計で、checksum で破損を検知するが、復旧ファイルと原子的な更新は持たない
- 痕跡の書き出しファイルは平文で、Vault 内へ保存すると Obsidian の同期でクラウドへ渡る。保存の前にそう伝える
- `android:allowBackup="true"` のまま、`random_note_prefs` はバックアップと端末移行の両方から除いている。中身は Vault の SAF URI・テーマ・当日の閲覧履歴・分野の確定。API 31以上は `dataExtractionRules`、API 30以下は `fullBackupContent` で覆う。要約の保存と蒸留の復旧レコードは `noBackupFilesDir` に置く
- 端末に残す置き場を足したら除外に載っていることを、`BackupExclusionTest` が走査で数える
- 本番にログ出力は無く、ノート本文やプロンプトを logcat へ出さない。例外は debug ソースセットの計測器と instrumentation で、固定コーパスの応答を logcat へ出す

---

## 13. テスト状況

### 13.1 JVMテスト

| 置き場 | ファイル | 件数 | 何を見ているか |
|---|---:|---:|---|
| `test/.../` の直下 | 47 | 733 | Controller・保存と読込・パーサ |
| `test/.../domain/` | 35 | 425 | 純関数。採点・抜粋・スコアリング・解析 |
| `test/.../ui/` | 20 | 189 | 表示ロジックを数値として固定する。色・幾何・派生状態 |
| `test/.../architecture/` | 25 | 87 | コードではなく規約と文書を守る検査 |
| `test/.../ai/` | 7 | 35 | プロンプトの組み立てと生成失敗の判定。端末AIは呼ばない |
| `test/.../testing/` | 5 | 39 | 要約の採点器そのものの検査 |
| `test/.../fakes/` | 3 | — | 共有のフェイク |
| **合計** | **142** | **1,508** | 133クラスと共有ヘルパ9本 |

どういう観点で確かめているかは [jvm_test_report](02_jvm_test_report.md) が持ち、全133クラスの件数と対象はその付録にある。
`NoteHistoryStore` は `Uri` と `org.json` の Android 実装に依存するので、素のJVMでは検証していない。

### 13.2 実行

```text
./gradlew testDebugUnitTest lintDebug --offline
BUILD SUCCESSFUL   133クラス・1,508件・failure 0・skip 0・約3秒／Lint 0 errors, 0 warnings
```

JBR は `/Applications` 直下ではなく `/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home` にあるので、`/usr/libexec/java_home` では見つからない。`JAVA_HOME` へ明示する。
`lintAnalyzeDebug` が UP-TO-DATE だと前回の XML が残るので、hint の件数を数えるときは `--rerun-tasks` を付けて解析が走ったことを確かめる。
走査テストは文書・`androidTest`・`res/xml`・`src/main/java` を Gradle の入力に載せてある。コメントだけを直してもテストが飛ばない。

### 13.3 自動実行 CI

`.github/workflows/ci.yml` が、`pull_request` と `main` への push で次を実行する。JDK は 21。

- `testDebugUnitTest`
- `lintDebug`
- マージ後マニフェストの権限検査。debug と release の両方で、release は名指しで呼ぶ
- `assembleDebugAndroidTest`

失敗したときの追跡用に、テストと Lint のレポートを artifact として残す。同じブランチへの連続 push では古い実行を打ち切る。

- feature ブランチへの push だけでは走らない。PR を作るまで自動の検査は掛からない
- `assembleDebugAndroidTest` が保証するのはテストAPKのコンパイルと組み立てまでで、instrumentation は実行しない。エミュレータのジョブは置かないと決めてあり、判断と再検討の条件は [instrumentation_testing](../dev/system/instrumentation_testing.md) の判断4 にある
- Lint は `warningsAsErrors` で警告があるとビルドが落ちる。依存更新の3チェック `GradleDependency`・`NewerVersionAvailable`・`AndroidGradlePluginVersion` だけは hint として報告する。件数はキャッシュ次第で揺れるので、回帰の基準値に使わない → [dependency_policy](../dev/system/dependency_policy.md)

### 13.4 覆っていないもの

- `NoteViewModel` 自体。`AndroidViewModel` と `Uri` を素のJVMで作れない。壊れやすい調停は `NoteSessionCoordinator` へ出して `NoteSessionCoordinatorTest` で見ており、残るのはノート読込・関連ノート・走査キャッシュといった `ContentResolver` に依存する経路
- `SearchController.onVaultChanged()` が落とすスコープのキャッシュとジョブ。観測できる副作用が `ContentResolver` を要する経路にしか無い
- `RelatedNotesUseCase` の結線全体と、`SearchPickerUseCase` の AI 応答の解釈。部品の純ロジックは個別に覆っている
- `NoteHistoryStore` の日付判定と重複排除
- `PromptBuilder` の出力契約のうち、要約・関連・ピッカー・セクション系。クイズと蒸留は覆っている
- 読書痕跡の Compose 実レイアウト上の可視量、Activity のライフサイクルを通した一時停止と再開、SAF Gateway での実 Vault の照合
- Gemini Nano のダウンロードとタイムアウト。生成は11本のプロンプトのうち5本だけ
- 端末AIの出力の意味。採点器は語の重なりしか測らず、実機の出力では忠実な文と誤りの文を閾値で分けられない
- 画面幅による Rail の切替
- 連続操作での競合。タブの連打テストは競合を作れていない
- 実際の Obsidian Vault を使った E2E。偽の Vault での経路は覆っている
- 画面の佇まい。面の見え方・束の中身・繰る手触りは、どのテストにも掛からない。形の役割の取り違えは走査で固定できるが、どう見えるかは実機検証のケース表が判定する

### 13.5 instrumentation の内訳

段階の定義と判断は [instrumentation_testing](../dev/system/instrumentation_testing.md) が持つ。全件を一度に通した実行は無い。

| テストクラス | 件数 | 対象 | JVMで書けない理由 |
|---|---:|---|---|
| `InstrumentationSetupTest` | 1 | 対象アプリの Context 取得 | Runner の疎通 |
| `ComposeRenderingSetupTest` | 1 | Compose テストルールの描画 | Compose の実行環境 |
| `ui/NoteReadingFlowTest` | 9 | 解析待ちの描画抑止、全画面への位置の引き継ぎ、進捗報告の整合、画像の表示 | レイアウトの実測と可視判定 |
| `data/VaultScanInstrumentationTest` | 9 | 走査の相対パス、読取失敗と不在の区別、補記の作成・一覧・削除、document の同一性 | 実物の `ContentResolver`・`DocumentsContract` |
| `data/NoteImageGatewayInstrumentationTest` | 13 | 復号と寸法読み、上限の内外、大きすぎると壊れているの切り分け、索引の世代と鮮度 | 実物の `BitmapFactory` |
| `ai/PromptTokenBudgetTest` | 5 | トークン計測と能力診断、完成プロンプトの実トークンの基準線 | 端末AI |
| `ai/OnDeviceGenerationTest` | 5 | 本番プロンプトでの実生成。要約・クイズ・関連ノート・セクション要約・分野判定 | 端末AI |
| `ai/SummaryCoverageBaselineTest` | 2 | 固定コーパスを本番の要約プロンプトで生成し、採点器に掛けて記録する。値は主張しない | 端末AI |
| `ui/ActivityRecreationTest` | 2 | Activity の再生成で OP を再生しない、繰り返しの再生成 | Activity のライフサイクル |
| `ui/TabNavigationTest` | 5 | タブの往復と巡回、戻る操作での履歴契約、遷移先での再生成 | `NavHost` のバックスタック |
| `ui/QuizActionSectionTest` | 2 | クイズが使えない理由が押した場所に描かれる | Composable の描画結果 |
| `ui/ReadingTraceCardPanelTest` | 6 | 再会カードの種別ごとの前置き、印の文言、空の枠でボタンを出さない | Composable の描画結果 |
| `ui/DistillRangeAdjustUiTest` | 9 | 範囲調整シートの操作。プリセットの切替、最初の範囲へ戻す、つまみのドラッグ、端の微調整、告知の出方 | 描画結果とシートの操作 |
| `ui/MarginMemoSheetUiTest` | 4 | 余白メモのシート。入力・一覧・削除の確認・保存中も入力が消えない | 描画結果とシートの操作 |
| `ui/BookletScreenTest` | 22 | 冊子の描画。扉・めくり・終端・0件・失敗ページ・読み上げ名、編む側のトグル3通りと終端 | Pager の実挙動と描画結果 |
| `ui/BookletNavigationTest` | 2 | 実 `NavHost` での `note → booklet → note` の往復とページ位置の復帰 | バックスタックと状態の復元 |
| `ui/BookletSheetPerspectiveTest` | 9 | 倒れた紙の投影の向き、枠への収まり、カメラ距離、めくりで現れる裏、積み直りとの同時進行 | 描いた画素を数えないと符号の意味が確かめられない |

土台は `src/debug` の `FakeVaultDocumentsProvider` で、`SafVaultBrowser` と `NoteRepository` を本番のまま動かす。
`androidTest` ではなくアプリ側の debug ソースセットに置くのは、別APKにすると tree URI の権限付与が要るため。release には入らない。
`@Test` の戻り値が `void` でなくなる書き方は、そのクラスのテストが全件起動しなくなるので `InstrumentationTestShapeTest` が JVM 側で禁じる。

### 13.6 instrumentation が保証していない範囲

件数が増えても、保護範囲が同じだけ広がるわけではない。主張が実際に試していることより広かった箇所を、次のとおり狭めてある。

- **タブの連打は試していない。** `performClick` は毎回 semantics を取り直して同期し、生の `MotionEvent` は Android 17 が instrumentation の UID からの注入を拒む。代わりにタブの履歴契約を「戻る」で観測する。`launchSingleTop` 単体の効果は、`restoreState` が肩代わりするので識別できていない
- **`ActivityScenario.recreate()` はプロセスの死亡ではない。** 同じプロセスの中で Activity を作り直すだけで、Application・静的な状態・プロセス内のキャッシュは生き残る。プロセス死亡への耐性は保証していない
- **端末AIの生成は11本のプロンプトのうち5本だけ。** 読書痕跡の要約・蒸留・検索ピッカー・再会カードの選別・セクションの候補・セクションのチャットの6本は未保証。`PromptGenerationCoverageTest` が、builder を足したら覆うか未保証と宣言するまで落とす

---

## 14. コード品質評価

### 14.1 強み

1. **責務の分割がはっきりしている。** 機能は14の Controller に分かれ、横断調停と状態の所有は `NoteSessionCoordinator`、Android 境界は `NoteViewModel` にある。Vault へ破壊的に書く蒸留は、書き込みの経路も `DistillWriteRepository` と `DistillRecoveryStore` へ切り出してある
2. **状態の所有権が型で守られている。** 書けるのは `NoteUiStateStore` だけで、Controller には担当の Writer しか渡らない
3. **境界が文書ではなくテストで守られている。** 依存の向きは `PackageDependencyTest`、切替時の一斉停止と一斉初期化は実物の14 Controller を束ねた `NoteSessionCoordinatorTest`、見た目のチャネルは `BearingChannelTest` が固定する。ノート単位と Vault 単位のリセット漏れは別々に検査する
4. **AI が無くても価値が残る。** 関連ノートは wikilink とファイル名の規則で動き、検索にはランダムとキーワード一致のフォールバックがある。余白メモと冊子は AI を呼ばない
5. **端末の負荷に配慮している。** 生成の直列化、60秒のタイムアウト、3秒の門番、要約の保存、SAF 走査のキャッシュ、Markdown 解析の使い回し
6. **壊れやすい文字列処理が純関数になっている。** クイズのパース、余白メモの入力整形、タイトル正規化、Markdown 解析、蒸留の文分割と範囲の決定が Android の I/O から離れている
7. **Obsidian 固有の仕様に合わせている。** wikilink の別名・見出し・ブロック参照、frontmatter、画像の埋め込み、機能用のフォルダを扱う

### 14.2 残る技術的な注意点

優先度は、いま確認できる影響の範囲で付けている。すぐに障害が起きるという意味ではない。未対応の課題の正本は [current_issues](../_wip/current_issues.md)。

| 優先度 | 項目 | 現状と影響 |
|---|---|---|
| 中 | 痕跡サイドカーの書き込みが原子的でない | `"wt"` で直接上書きするので、書いている途中でプロセスが死ぬと部分的に壊れたファイルが残り、復旧元も無い。SAF の `renameDocument()` はプロバイダによって動かないので割り切っている。壊れれば checksum で検知して孤立扱いにする |
| 中 | 画面の佇まいを判定する工程が実機検証にしか無い | 振る舞いは JVM と instrumentation が見ているが、見分けられるか・手触りがあるかはどのテストにも掛からない。テストで埋める種類の穴ではない |
| 低 | 回数制限の文言を置き換えているのは要約だけ | AICore の短期の回数制限で断られたとき、要約は日本語の案内を出すが、クイズなどの押して使う機能は例外のメッセージをそのまま出す |
| 低 | instrumentation は実機でしか走らず、変異で確かめられない | 変異を入れて落ちるかを見る工程が CI に無いので、緑であることしか分からない。観測点を JVM 側へ引き出せるときはそうするが、描画そのものは引き出せない → [lessons L53](../dev/lessons/L53.md) |
| 低 | YAML の解析が簡易 | 複雑な YAML・引用・ネスト・複数行の値に対応しない。AI 推薦で使う tags と aliases を取りこぼしうる |
| 低 | Markdown の未対応 | タップできるリンク、ノートの埋め込み、数式 |
| 低 | 同名ノートの曖昧さ | AI 推薦は一時IDで解決するので不定にならない。規則ベースと除外判定で使う正規化タイトルの集合には、同名の畳み込みが残る |
| 低 | R8 と署名が未設定 | R8 を有効にすると ML Kit GenAI のリフレクションで解決する部分が縮小で消え、release ビルドでだけ全AI機能が落ちる恐れがある。JVM テストは縮小前のクラスを見るので検出できず、実機検証とセットになる |
| 低 | コメントの密度が28% | 判断が読める強みの一方、読む量が増え、コメントも実装より古くなる。経緯を書かない規約と検査は置いてある |
| 低 | 依存の更新そのものが未着手 | Lint の3チェックを hint にして毎ビルドで見えるようにしてある。`genai-prompt` は beta2 から beta4 へ上げるとソースは互換だが動作は互換でないことが分かっている |

---

## 15. ここに無いものと、この文書の更新

| 知りたいこと | 見る先 |
|---|---|
| なぜそう作ったか、何を採らなかったか | [dev/features/](../dev/features/)・[dev/system/](../dev/system/)・[dev/decisions/](../dev/decisions/) |
| いつ何を変えたか | [change_history](../dev/change_history.md) |
| 何を読み違え、どう直したか | [開発日誌](journal/) |
| いま何が未対応か、何をどの順でやるか | [_wip/current_issues.md](../_wip/current_issues.md)・[_wip/roadmap.md](../_wip/roadmap.md) |
| テストの観点と全クラスの一覧 | [jvm_test_report](02_jvm_test_report.md) |
| 過去の解析書そのもの | git 履歴 |

**更新するときは章を部分的に直さず、通しで見直して測定日を1つにそろえる。** 目次も同じときに直す。

**本文に経緯を書かない。** 見直しのたびに「何が変わり、なぜそうしたか」を本文へ足すと、この文書はコードが減っても太り続ける。
変わったことは §0 の数字に出し、理由は正本へリンクする。§6 は機能ごとの形を崩さない。

検査には載せない。この文書はオーナーが読むための俯瞰で、継続して同期する台帳ではない → [README](README.md)。
