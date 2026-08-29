# ソースコード解析書

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**この文書の位置づけ:** **コードを読まずに現状を把握するための技術俯瞰。**
オーナー（エンジニアでもある）が構成・設計・規模の成長を追うために置く。
**現在の設計判断そのものは [dev/features/](../dev/features/)・[dev/system/](../dev/system/) が正本**で、本書はその結果としての現況を述べる。
**そこへ至った経緯は [開発日誌](journal/) が持つ。**

**測定日:** 2026-08-29（統計は同日に現行ソースから再測定）

## 目次

- [0. 規模の推移](#0-規模の推移)
- [0.1 検証状態（2026-08-29 時点）](#01-検証状態2026-08-29-時点)
- [1. エグゼクティブサマリー](#1-エグゼクティブサマリー)
- [2. プロジェクト規模と技術構成](#2-プロジェクト規模と技術構成)
  - [2.1 コード規模](#21-コード規模)
  - [2.2 ビルド・プラットフォーム](#22-ビルドプラットフォーム)
  - [2.3 外部依存の特徴](#23-外部依存の特徴)
- [3. 現在のファイル構成](#3-現在のファイル構成)
- [4. アーキテクチャ](#4-アーキテクチャ)
  - [4.1 レイヤーと依存方向](#41-レイヤーと依存方向)
  - [4.2 状態の単一ソース](#42-状態の単一ソース)
  - [4.3 状態モデル](#43-状態モデル)
- [5. ナビゲーションと画面構成](#5-ナビゲーションと画面構成)
  - [5.1 ルート](#51-ルート)
  - [5.2 画面幅対応](#52-画面幅対応)
  - [5.3 画面ごとの責務](#53-画面ごとの責務)
- [6. 主要機能のデータフロー](#6-主要機能のデータフロー)
  - [6.1 Vault選択と復元](#61-vault選択と復元)
  - [6.2 ランダムノート表示](#62-ランダムノート表示)
  - [6.3 ノート要約とモデルダウンロード](#63-ノート要約とモデルダウンロード)
  - [6.4 関連ノート](#64-関連ノート)
  - [6.5 さがす（AIピッカー）](#65-さがすaiピッカー)
  - [6.6 セクションAI](#66-セクションai)
  - [6.7 適応出題Q&A（○×・3択・4択／フォーカス周辺クイズ）](#67-適応出題qa3択4択フォーカス周辺クイズ)
  - [6.8 ノートへのひとこと（旧「AI補記メモ」）](#68-ノートへのひとこと旧ai補記メモ)
  - [6.9 当日閲覧履歴](#69-当日閲覧履歴)
  - [6.10 蒸留（Distill）](#610-蒸留distill)
  - [6.11 ReadingTrace（読書痕跡）](#611-readingtrace読書痕跡)
  - [6.12 読書痕跡の退避と復元](#612-読書痕跡の退避と復元)
- [7. SAF・Vaultアクセス層](#7-safvaultアクセス層)
  - [7.1 走査方式](#71-走査方式)
  - [7.2 読み書き](#72-読み書き)
  - [7.3 メタデータ解析](#73-メタデータ解析)
  - [7.4 タイトル正規化](#74-タイトル正規化)
- [8. AI層](#8-ai層)
  - [8.1 `AiClient`](#81-aiclient)
  - [8.2 モデル設定](#82-モデル設定)
  - [8.3 直列化とタイムアウト](#83-直列化とタイムアウト)
  - [8.4 プロンプト入力上限](#84-プロンプト入力上限)
- [9. Markdown解析・描画](#9-markdown解析描画)
  - [9.1 対応ブロック](#91-対応ブロック)
  - [9.2 対応インライン記法](#92-対応インライン記法)
  - [9.3 防御的処理](#93-防御的処理)
  - [9.4 描画効率](#94-描画効率)
- [10. 並行処理・ライフサイクル・キャッシュ](#10-並行処理ライフサイクルキャッシュ)
  - [10.1 Job管理](#101-job管理)
  - [10.2 CancellationException](#102-cancellationexception)
  - [10.3 キャッシュ](#103-キャッシュ)
- [11. エラー処理とフォールバック](#11-エラー処理とフォールバック)
  - [11.1 良い点](#111-良い点)
  - [11.2 注意点](#112-注意点)
- [12. データ保護・プライバシー](#12-データ保護プライバシー)
- [13. テスト状況](#13-テスト状況)
  - [13.1 ユニットテスト内訳](#131-ユニットテスト内訳)
  - [13.2 実行結果](#132-実行結果)
  - [13.3 自動実行（CI）](#133-自動実行ci)
  - [13.4 未カバー領域](#134-未カバー領域)
  - [13.5 instrumentation の内訳（57件）](#135-instrumentation-の内訳57件)
  - [13.6 instrumentation が保証していない範囲](#136-instrumentation-が保証していない範囲)
- [14. コード品質評価](#14-コード品質評価)
  - [14.1 強み](#141-強み)
  - [14.2 残る技術的注意点](#142-残る技術的注意点)
- [15. 今後の改善候補](#15-今後の改善候補)
- [16. この文書の更新について](#16-この文書の更新について)

---
## 0. 規模の推移

| 指標 | 前回（2026-08-22） | 今回（2026-08-29） | 増減 |
|---|---:|---:|---:|
| 本番コード（ファイル） | 129 | **139** | +10 |
| 本番コード（行） | 22,183 | **24,449** | +2,266 |
| JVMテスト（ファイル） | 92 | **99** | +7 |
| JVMテスト（行） | 20,044 | **22,199** | +2,155 |
| JVMテスト（件数） | 1,039 | **1,147** | +108 |
| instrumentation（件数） | 57 | **57** | ±0 |

行数は空行・コメントを含む `wc -l` ベースで、生成物とGradleスクリプトは含まない。
テスト件数は `@Test` の出現数。

> **前回値の測定条件が今回と完全に一致する保証は無い**（当時の記録から引いている）。
> 桁と傾向を見るための表であって、差分そのものを厳密な指標として扱わない。
> **今回の値は再現できる** — 下記のコマンドで数えている。

```bash
find app/src/main -name "*.kt" | wc -l                      # 本番ファイル数
find app/src/main -name "*.kt" -exec cat {} + | wc -l       # 本番行数
grep -rhE '^[[:space:]]*@Test' app/src/test | wc -l         # JVMテスト件数
grep -rhE '^[[:space:]]*@Test' app/src/androidTest | wc -l  # instrumentation件数
```

**この1週間で増えたものの中身は、ほぼ1機能に集中している** — 読書痕跡の退避と復元（X-9）。
本番で増えた9ファイルはすべてそこに属する（`controller/ReadingTraceBackupController.kt`・
`data/ReadingTraceBackupJson.kt`・`domain/ReadingTraceBackupFileName.kt`・`domain/ReadingTraceMerge.kt`・
`model/ReadingTraceBackupTypes.kt`・`model/state/ReadingTraceBackupState.kt`・
`ui/ReadingTraceBackupText.kt`・`ui/component/OptionRow.kt`・`ui/screen/DataManagementScreen.kt`）。
**Controller は10個から11個になった。**

**08-29 の蒸留の修正で増えた1ファイルが `domain/markdown/InlineSyntax.kt`** —
表示と蒸留が**共有する唯一のインライン記法の解釈器**である。
記法の一覧を揃えるだけでは解釈規則（バッククォートの数え方・エスケープ・リンクの消費・ブロックの単位）が
食い違い、表示上の装飾の内側へ蒸留が候補境界を置ける状態が残っていたため、解釈器そのものを1つにした。
同じ日に N-14（太字範囲のユーザー調整）の設計と、そのレビュー10件の反映も行ったが、**そちらは文書だけ**である。

instrumentation は増えていない。この期間の追加はJVM側だけで済んだ
（退避は `domain` と `data` に寄せてあり、SAF実物を要する面は既存の走査テストが持つ）。


## 0.1 検証状態（2026-08-29 時点）

| | 結果 |
|---|---|
| `testDebugUnitTest` | **1,147ケース全件グリーン** |
| `lintDebug` | **Error 0 / Warning 0**（依存更新系は12 hints。ゲートに載せていない） |
| Kotlin コンパイル警告 | **0**（警告はビルドを落とす設定） |
| `assembleDebugAndroidTest` | 成功 |
| instrumentation 実行 | **全57件を一度に通した実行は無い。** 直近は機能単位で実行している — 画像22/22（08-21）・AI入力予算のBUDGET系（08-22）・再会カードの描画6/6（08-22）・痕跡の退避1/1（08-28）・蒸留の装飾保護 `DIST-19`/`DIST-20`（08-29）。いずれも Pixel 10 Pro Fold・Android 17 |

**実機確認が済んでいないもの:** Vigilith Phase 3 の目視。
**蒸留の装飾保護は 2026-08-29 に `DIST-20` で確認し、BOLD-1 を完了した**（→ [レビュー一覧](../review/README.md)）。
**未対応の課題は [_wip/current_issues.md](../_wip/current_issues.md) が正本。**

> **instrumentation が57件あることは、保証範囲が57件ぶん広いことを意味しない。**
> 主張が実際に試していることより広い箇所が3件ある（`ActivityScenario.recreate()` は
> プロセス死亡を覆わない／端末AI生成は12経路中4経路／連打の主張は撤回済み）。→ §13.6
>
> **実機検証の単位が「全件を一度に」から「機能ごとのケース表」へ移った。**
> `docs/review/device_validation/` に機能別ケースを置き、着手した機能のケースだけを通す。
> 全件同時実行を成功条件にしていないので、**上の表も機能単位で読む。**

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
- 蒸留（Distill）: AIが原文箇所（文・長文の句・括弧内語句）を選び、ユーザー確認後に元ノートを `**太字**` へ書き換えるプログレッシブ要約支援
- ReadingTrace: 10秒以上読んだノートの最深到達点をサイドカーへ記録し、Rediscover時に「前回のあなた」カードと読み方の俯瞰要約を表示

非AIの補助機能として、**読書痕跡の書き出しと読み戻し**（データ管理画面）を持つ。訪問記録・AI要約・ひとことと**ユーザーが書いた返事**を1ファイルへ退避でき、Vaultのフォルダが消えても読み戻せる（§6.12）。

Q&Aとひとことはバックグラウンド生成方式で、生成中もノート閲覧を継続でき、完了・エラーはSnackbarで通知される。**AIタブのバッジは「生成中」だけを示す** — ひとことの結果は専用画面で読むため、旧補記が持っていた「未確認」の概念（`isViewed`）ごと無くなった。ReadingTraceは対照的に、AI未準備・生成失敗を通知せず、生の痕跡だけを先に表示して黙って劣化する。AI以外の補助機能として、当日分のみの閲覧履歴（さがすタブ「今日読んだノート」）を持つ。

アーキテクチャは「単一 Activity + Compose Navigation + 単一 ViewModel」を入口としつつ、肥大化を避けるため要約・検索・セクションチャット・クイズ・ひとこと・旧補記ファイルの片付け・蒸留・読書痕跡・本文セクション解析・痕跡の整理を機能別 Controller（**11個**）に分割している。`NoteViewModel` は `Uri`・`ContentResolver`・`SharedPreferences` を扱うAndroid境界だけを担い、Controller間の調停と状態所有は Android API を呼ばない `NoteSessionCoordinator` が持つ。依存の組み立ては `NoteViewModelDependencies` へ外出しされている。ファイルI/Oは `NoteRepository`、AI判定を含む主要ロジックは UseCase、AI接続は `AiClient`、Markdown生成・応答パースは純粋ロジックへ分離されている。蒸留のVault書き戻しは `DistillWriteRepository` が専用の安全書き込み経路（ハッシュ照合・復旧レコード）を持ち、ReadingTraceは `_ReadingTraces` へのベストエフォートなサイドカー保存を持つ。**ひとことと返事もこのサイドカー（schema v6）へ入る** — 出力が1文になったので `.md` ファイルを作る形をやめた。

現時点の総評は次のとおり。

- 主要責務の分割、状態の一元管理、古いAI処理のキャンセル、生成タイムアウト、SAF走査キャッシュが実装され、継続的な機能追加に耐えやすい構造になっている。
- Markdownパーサー、**ひとことの応答検証**、クイズ応答パーサー、蒸留の文分割・採点・太字挿入、ReadingTraceのJSON・Controller・相対パス走査、Vigilith起動・表示状態・状態別モーション・配置計算、明暗トークンのコントラストなど、壊れやすい純粋ロジックにはユニットテストが整備されている（**1,147ケース**。内訳は §13.1）。
- ノート単位の Controller は requestId ＋ Job 追跡で古い結果の混入を防ぐ。Vault単位の要求（旧補記ファイルの一覧・削除・フォルダ一覧・痕跡の整理）は寿命が違うため、共有の `vaultGeneration` を `update` 直前に照合する二層構成になっている。**痕跡の削除だけは世代照合に加えて、洗い出した時点の Vault識別子を保持して照合する**（キーが相対パスのハッシュのため、別Vaultの同名パスと衝突しうる）。
- 状態は `NoteUiStateStore` だけが所有し、各Controllerへは機能別の `*StateWriter` を渡すため、担当外フィールドへの書き込みはコンパイル時に不可能である。ノート切替のジョブ停止と状態リセットは `onNoteChanged()` の1手に閉じている。
- パッケージ依存は `model` を葉とする一方向に整理され、`PackageDependencyTest` がimportを走査してCIで固定している。循環は残っていない。
- **SAF・画像復号・Compose描画・画面遷移・端末AI生成を実機で通す instrumentation が 57件そろっている**（→ §13.5）。土台は `src/debug` のテスト用 `DocumentsProvider` で、本番の `NoteRepository` / `SafVaultBrowser` をそのまま動かす。**ただし保証範囲は主張より狭い** — 連打の主張は撤回済み、`recreate()` はプロセス死亡を覆わず、端末AI生成は12経路中4経路だけである（→ §13.6）。**CIでは実行しない**判断を 2026-08-08 に確定した（→ §13.3）。
- ReadingTraceは主要経路とJVMテストが揃い、レビューで見つかった高優先度4件（ブロック数基準の到達率、Activity停止・再開、Vault切替中の起動済み保存、検索フォールバックの文言差）も解消済みである。ただしSAF照合とActivity lifecycleの実挙動はJVMテストの範囲外なので、実端末確認が完了判定に要る。
- 構造面の成長限界（依存の循環・ViewModelのテスト不能・状態の共有所有）は 2026-07-27 のB案で解消した。アクセシビリティとリリース構成は 2026-07-29〜30 のD案・E案で着手し、**ライトの文字トークンは実際に載る面すべてで4.5:1を満たす**ようになった。**下部ナビ帯の上でコントラストを取れなかったバッジ塗りは、ひとことの作り直しで対象ごと消えた**（未確認管理が不要になり、完了✓と失敗!の塗りが無くなった）。残る弱点は R8・署名が未設定であること、そして **instrumentation の実行がCIで担保されず「PR前に手で回す」運用のままであること**に移っている。**ユーザーが書いた返事の退避手段が無い**という弱点は、2026-08-23〜28 の書き出し／読み戻し（§6.12）で解消した。

---

## 2. プロジェクト規模と技術構成

### 2.1 コード規模

| 区分 | ファイル数 | 行数・件数 |
|---|---:|---:|
| 本番 Kotlin | 139ファイル | 24,449行 |
| ユニットテスト Kotlin | 99ファイル | 22,199行、1,147テスト（テストクラスは96。残り3つは共有フェイク・共有ヘルパ） |
| instrumentation テスト Kotlin | 11ファイル | 57テスト（**全件を一度に通した実行は無い。** 直近は機能単位で実行している。内訳は §13.5） |
| debug ソースセット Kotlin | 1ファイル | 320行（instrumentation 用の偽SAFプロバイダ。**release には入らない**） |
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
│   │   ├── AppNoteImageLoader.kt               # `ui` が宣言した画像読み込み口へ `data` の実装を差し込むアダプタ
│   │   ├── ai/
│   │   │   ├── AICoreClient.kt                 # AiClient、Gemini Nano接続、Mutex、タイムアウト
│   │   │   ├── AiAvailabilityMapping.kt        # 端末の状態問い合わせと例外を AiAvailability へ写す
│   │   │   ├── PromptBudget.kt                 # 完成プロンプトの入力上限を1箇所で強制
│   │   │   └── PromptBuilder.kt                # 各機能のプロンプト構築（12本）
│   │   ├── controller/
│   │   │   ├── NoteSessionCoordinator.kt       # 11 Controllerの生成と横断調停・Vault世代（Android API非依存）
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
│   │   │   ├── ReadingTraceBackupController.kt # 痕跡の書き出し・下見・読み戻し・中止（Vault単位）
│   │   │   └── NoteSectionController.kt        # 表示用Markdown解析をMainの外で1回だけ行う
│   │   ├── data/
│   │   │   ├── NoteRepository.kt               # SAF走査・読書き・メタデータ解析
│   │   │   ├── VaultBrowser.kt                 # さがす／補記が使うVaultスコープの操作（ContentResolverを裏へ束ねる）
│   │   │   ├── NoteImageGateway.kt             # 画像1枚の読み込み境界（寸法・復号・失敗理由）
│   │   │   ├── VaultImageIndexStore.kt         # 画像索引のメモリキャッシュ・TTL・Vault世代
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
│   │   │   ├── ReadingTraceBackupJson.kt       # 退避ファイルの形式（読めなかったものを黙って捨てない）
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
│   │   │   ├── ReunionCandidateScanner.kt      # 再会カード候補の列挙と種別決定（純関数）
│   │   │   ├── RelatedCandidateId.kt           # 一時ID(C01..)採番と応答からのID抽出
│   │   │   ├── KeyedMemoCache.kt               # 汎用LRUメモ化（成功時のみ格納）
│   │   │   ├── ByteBudgetCache.kt              # バイト予算つきLRU＋同一キーのsingle-flight
│   │   │   ├── BoundedInputStream.kt           # 読み取り上限つきストリーム（上限で終端として振る舞う）
│   │   │   ├── NoteExcerptBuilder.kt           # AI入力用の抜粋（見出し骨格＋冒頭＋末尾）を用途別予算へ収める
│   │   │   ├── AiStatusNotices.kt              # AI状態を、見せる1文と導線へ変換する
│   │   │   ├── NotePaperAge.kt                 # 放置期間をVault内の相対順位で紙の地色へ写す
│   │   │   ├── ReadingTraceOrphans.kt          # 孤児痕跡の割り出し（遮断器つき）
│   │   │   ├── ReadingTraceMerge.kt            # 読み戻しの併合規則（端末に無いものを受け入れる）
│   │   │   ├── ReadingTraceBackupFileName.kt   # 退避ファイルの既定名
│   │   │   ├── DistillSourceModel.kt           # 蒸留用の文分割（UTF-16オフセット保持・Markdown構造認識）
│   │   │   ├── DistillCandidateScoring.kt      # 蒸留候補のサリエンス採点・チャンク網羅
│   │   │   ├── DistillResponseParser.kt        # 蒸留AI応答からのID抽出（許可集合で検証）
│   │   │   ├── DistillTransformer.kt           # オフセット降順の `**` 挿入・太字比率上限
│   │   │   ├── RemarkComposer.kt               # ひとこと／映し返しの応答検証・リンク差し戻し（純粋ロジック）
│   │   │   ├── QuizResponseParser.kt           # AIクイズ応答パース（純粋ロジック）
│   │   │   ├── QuizInputProfile.kt             # AI不使用の入力分類→出題形式決定（純粋ロジック）
│   │   │   ├── NoteTitleNormalizer.kt          # Obsidianタイトル正規化
│   │   │   ├── AiResponseParsing.kt            # AI返却タイトルの共通正規化
│   │   │   ├── image/
│   │   │   │   ├── ImageLinkParser.kt          # 画像参照の解析（`![]()` と `![[...]]`）
│   │   │   │   ├── ImageIndexMatching.kt       # 索引との照合（完全パス→ファイル名の順）
│   │   │   │   └── ImageDecodePolicy.kt        # 画像1枚にかける予算と復号可否の判定
│   │   │   └── markdown/
│   │   │       ├── InlineSyntax.kt             # インライン記法の唯一の解釈器（表示と蒸留が共有）
│   │   │       ├── MarkdownBlocks.kt           # ブロック解析（Compose非依存の純粋ロジック）
│   │   │       └── NoteSections.kt             # 見出し単位セクションモデル
│   │   ├── model/                              # 依存グラフの葉（プロジェクト内の他パッケージをimportしない）
│   │   │   ├── NoteUiState.kt                  # 全UI状態の集約 data class・蒸留リロード時の保持規則
│   │   │   ├── NoteUiStateStore.kt             # 状態の唯一の所有者・機能別 *StateWriter・リセット契約
│   │   │   ├── NoteTypes.kt                    # NoteFile / NoteFolder / NoteMeta（層をまたぐ共有型）
│   │   │   ├── DocumentRef.kt                  # Vault内の1ドキュメントを指す不透明な参照（Uriを上位へ出さない）
│   │   │   ├── HistoryEntry.kt                 # 当日履歴の1件
│   │   │   ├── RelatedNote.kt                  # 関連ノートと AI推薦ステータス
│   │   │   ├── DistillModels.kt                # 蒸留の純データ（範囲・文・チャンク・候補・DistillLimits）
│   │   │   ├── PromptLimits.kt                 # 完成プロンプトの上限と可変部の取り分
│   │   │   ├── NoteExcerpt.kt                  # 準備済み抜粋の共有型（生のStringを渡さないための型）
│   │   │   ├── NoteExcerptLimits.kt            # 用途別の抜粋上限（UTF-16文字数）
│   │   │   ├── ImageFileTypes.kt               # 画像として扱う拡張子
│   │   │   ├── NoteImageFailure.kt             # 画像を出せなかった理由（表示側が文言を選ぶ語彙）
│   │   │   ├── NotePaperTone.kt                # 紙の地色の段階
│   │   │   ├── ReadingTraceOrphanTypes.kt      # 孤児判定の上限（既定は安全側）
│   │   │   ├── ReadingTraceBackupTypes.kt      # 退避ファイルの形式と上限
│   │   │   ├── ReadingTrace.kt                 # 読書痕跡モデル・上限・検証・Reflection（schema v6）
│   │   │   ├── ReunionKind.kt                  # 再会カードの枠に出ている1件の種別
│   │   │   ├── RemarkProtocol.kt               # ひとことの「出すものが無い」表明語（ai と domain の共有点）
│   │   │   └── state/                          # 機能別の sealed state（Note/Summary/RelatedNotes/Search/
│   │   │                                       #   Quiz/Remark/AnnotationList/Distill/SectionChat/ReadingTraceCard/
│   │   │                                       #   ReadingTraceCleanup/ReadingTraceBackup/AiStatusNotice）
│   │   └── ui/
│   │       ├── AppScaffold.kt                  # 5タブ、NavigationBar/Rail切替、AIタブバッジ、SnackbarHost
│   │       ├── ReadingProgressGeometry.kt      # 最終可視ブロックの可視割合・量子化（純関数）
│   │       ├── ReadingTraceCleanupText.kt      # 孤児整理の文言（「孤児は無かった」と読ませない）
│   │       ├── ReadingTraceBackupText.kt       # 退避・読み戻しの文言（適用だけ言い方を変える）
│   │       ├── component/
│   │       │   ├── NoteComponents.kt           # タブと全画面の共用部品（読書位置報告・IconPill・本文パネル）
│   │       │   ├── GradientHeader.kt           # グラデーション直上に置く画面見出し（背景を自分で持つ）
│   │       │   ├── OptionRow.kt                # 設定系の「押すと次の画面へ行く」1行
│   │       │   ├── AiStatusNoticeRow.kt        # AI状態の説明と導線を1箇所で描く
│   │       │   └── ReadingTraceCard.kt         # 「前回のあなた」カード・経過文面
│   │       ├── markdown/
│   │       │   ├── InlineMarkdown.kt           # 種別を色・太さ・下線へ写すだけ（解釈は domain/markdown が持つ）
│   │       │   ├── NoteImage.kt                # ノート内画像1枚の描画
│   │       │   ├── NoteImageLoader.kt          # 画像読み込み口の宣言（実装は `data`）
│   │       │   ├── NoteImageMeasurements.kt    # 表示寸法の算出（純関数）
│   │       │   ├── NoteImageText.kt            # 失敗理由ごとの文言と代替テキスト
│   │       │   └── MarkdownRenderer.kt         # Compose描画
│   │       ├── screen/
│   │       │   ├── OpeningScreen.kt            # Vigilith起動OP（Compose描画・スキップ・完了通知）
│   │       │   ├── NoteReaderTab.kt            # ノートタブ本体（Markdown閲覧、Vigilithセクション操作）
│   │       │   ├── FullscreenNoteScreen.kt     # 全画面読書ルート（システムバー没入・最小AIインジケータ）
│   │       │   ├── SearchScreen.kt             # AI検索・ランダム抽出
│   │       │   ├── RelatedTab.kt               # 関連・AI推薦ノート一覧
│   │       │   ├── AiTab.kt                    # 要約、Q&A、AI補記の入口
│   │       │   ├── OptionsScreen.kt            # オプション入口（Vault選択・データ管理・ダークモード切替）
│   │       │   ├── DataManagementScreen.kt     # 痕跡の退避／読み戻しと、整理・旧補記の片付けの入口
│   │       │   ├── ReadingTraceCleanupScreen.kt # 孤児痕跡の洗い出しと削除
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
├── test/java/com/example/newproject/           # 99ファイル・1,147テスト（内訳は §13.1）
│   ├── architecture/PackageDependencyTest.kt   # importを走査してパッケージ依存の向きを固定
│   └── ui/theme/VibrantTextUsageTest.kt        # 画面からのonVibrant直接使用と文字色のcopy(alpha)を禁じる
├── androidTest/java/com/example/newproject/    # 11ファイル・57テスト（内訳は §13.5）
│   ├── InstrumentationSetupTest.kt             # Runner起動・対象Contextのみ（Composeルールを持たない）
│   ├── ComposeRenderingSetupTest.kt            # Compose描画とEspressoのUI同期
│   ├── ai/PromptTokenBudgetTest.kt             # 端末AIのトークン計測と能力診断
│   ├── ai/OnDeviceGenerationTest.kt            # 本番プロンプトでの実生成（12経路中4経路）
│   ├── data/VaultScanInstrumentationTest.kt    # 実物SAFでの走査・補記CRUD・読取失敗の注入
│   ├── data/NoteImageGatewayInstrumentationTest.kt # 実物BitmapFactoryでの復号と上限の境界
│   ├── ui/NoteReadingFlowTest.kt               # 描画抑止・全画面への位置引き継ぎ・進捗報告
│   ├── ui/ReadingTraceCardPanelTest.kt         # 再会カードの描画（種別・前置き）
│   ├── ui/QuizActionSectionTest.kt             # クイズ操作部の描画
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
       ├── RemarkController
       ├── AnnotationController                 ← Vault単位
       ├── DistillController
       ├── NoteSectionController
       ├── ReadingTraceController
       ├── ReadingTraceCleanupController        ← Vault単位
       └── ReadingTraceBackupController         ← Vault単位
          │
          ├──────────────► NoteRepository ──► SAF / DocumentsContract
          ├──────────────► DistillWriteRepository ──► SAF（安全書き込み）
          │                  └── DistillRecoveryStore ──► noBackupFilesDir
          ├──────────────► ReadingTraceStore ──► SAF / `_ReadingTraces`
          │
          └──────────────► UseCase ──► AiClient ──► ML Kit / Gemini Nano

純粋ロジック:
MarkdownBlocks / NoteSections / QuizResponseParser / QuizInputProfile /
RemarkComposer / NoteTitleNormalizer / AiResponseParsing / AiStatusNotices /
DistillSourceModel / DistillCandidateScoring / DistillResponseParser / DistillTransformer /
NoteExcerptBuilder / NotePaperAge / ReunionCandidateScanner /
SearchKeywordMatching / RelatedCandidate* / RelatedContextScoring /
KeyedMemoCache / ByteBudgetCache / BoundedInputStream /
ImageLinkParser / ImageIndexMatching / ImageDecodePolicy /
VaultPathTraversal / ReadingTraceJson / ReadingTraceOrphans / ReadingTraceMerge /
ReadingTraceBackupFileName / ReadingProgressGeometry /
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

`NoteFile`・`NoteFolder`・`NoteMeta`・`HistoryEntry`・`RelatedNote`・蒸留の純データ型は、層をまたいで共有されるため葉の `model` に置かれている。**`model` はプロジェクト内の他パッケージにも `android.*` にも依存しない。** 以前は `android.net.Uri` だけを import していたが、素のJVMではスタブが例外を投げてその層のテストが書けないため、`DocumentRef` へ置き換えて外した（→ [system/saf_boundary_gateway.md](../dev/system/saf_boundary_gateway.md)）。

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
- `distillState`: Idle / Analyzing / AiNotice / Downloading / Unavailable / Candidates / Saving / Saved / Conflict / RecoveryRequired / RecoveryResolved / Error（他機能より状態数が多いのは、AI生成に加えVault書き戻しの競合・中断復旧まで表現するため）。`AiNotice` は端末AIの状態の説明、`Unavailable` は**ノート側の理由**（本文が大きすぎる等）で、別物である
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
- 「データ管理」: **アプリが管理している非表示データを人間が扱えるようにする**画面へ渡す。
  読書痕跡の書き出し／読み戻しを本体に持ち、痕跡の整理（孤児削除）と旧補記ファイルの片付けをここから開く。
  3つを1画面へまとめたのは、どれも「Vault内にあるがノートではないデータ」を相手にしていて、
  **単独の設定項目としては寿命が違いすぎる**ため（旧補記の片付けは移行が済めば価値を失う）
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

なお `MainActivity` は `setContent` 直後に、コールド起動時のみ `OpeningScreen`（起動OP）を本体の代わりに表示する。新規起動の判定は `savedInstanceState == null`（回転・Fold開閉・プロセス復元では非nullのため再生しない）。OP終端の背景は着地（Noteタブ）と同じ `ReadingGradient` に揃え、継ぎ目なく本体へ入れ替える。詳細は [design/opening_animation](../dev/features/opening_animation.md) を参照。

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
| `Ready` | 1,200文字以内の本文抜粋を含むプロンプトで2〜4文を生成（予算内は原文、超過時は骨格＋冒頭＋末尾。§8.4） |
| `NeedsDownload` | モデルダウンロードを開始し進捗を `SummaryState.Downloading` へ反映 |
| `Downloading` | **何もしない。** 走行中のDLへ合流できないので、次にノートを開いたときに取り直す |
| `Unsupported` / `TemporarilyUnavailable` | `SummaryState.AiUnavailable`。現在のUIでは要約パネル自体を表示しない |
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

候補の選定→肉付け→再ランク→ID応答の多段パイプラインである（設計と経緯は [related_notes_ai](../dev/features/related_notes_ai.md)）。

1. **タイトル話題スコアで全Vaultをランク**し上位40候補に絞る（`rankRelatedCandidates`）。スコアはタイトルの文字bigram Dice係数（主）＋採番プレフィックス近接の加点（従）。決定的チャンネルに出したタイトルは上限適用の前に除外する。
2. **候補本文を上限付き並列で読む**（`Semaphore(8)`）。各候補を本文冒頭スニペット・タグ・aliasesで肉付けし、`URI+lastModified` でキャッシュする（成功時のみ格納）。
3. **現在ノートの本文シグナルで40件を再ランク**する（`relatedContextScore`）。タグ一致（主）＋スニペット類似＋タイトル類似で並べ替え、件数は変えない。
4. 再ランク後の並びで一時ID（`C01..`）を採番し、候補を入力予算（3,500文字）内へ動的短縮して整形する。現在本文は600文字以内の抜粋（§8.4）にしてAIへ渡す。**現ノートのタグは抜粋とは別経路**で、`parseMeta()` から取って3の再ランクに使う（抜粋側では frontmatter が落ちるため）。
5. **AIにはIDだけ返させ**、行頭付近のIDのみ抽出して実ノートへ解決する（`parseCandidateIds`）。決定的結果とのURI重複を除いて最大5件返す。

AIが利用不可またはモデル未準備でも、規則ベース結果は表示できる。AI生成で例外が起きても `RelatedNotesResult.Error` にはせず、規則ベース結果だけを返す設計である（自動起動の機能なので理由は見せない）。個別候補の本文読込失敗（キャンセル以外）は該当候補のみタイトルで続行し、推薦全体を巻き添えにしない。

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
3. `checkAvailability()` で分岐する。`Ready` は即生成、`NeedsDownload` はモデルDL後に自動再開、`Downloading` は**DLを始めずに待つ**、`Unsupported` と `TemporarilyUnavailable` は `QuizState.AiNotice`（**エラーにしない**）。
4. 周辺テキストを**AI不使用で分類**し（`QuizInputProfile`）、素材量に応じて出題形式を切り替える：コード比率45%以上→3択2問、本文180字未満または文シグナル2以下→○×2問、本文700字以上かつ文シグナル6以上→4択1問、それ以外→3択2問。○×・3択は解説なし・4択のみ短い解説を1文とし、問題／選択肢に文字数上限を指示する。これは、常に4択2問＋解説を要求すると出力上限（256トークン程度、8.3参照）を超えて `MAX_TOKENS` で全結果が破棄され、クイズ生成エラーになっていた問題への対策（詳細は [features/section_ai_chat.md](../dev/features/section_ai_chat.md)）。
5. `Q:` 行を問題開始として `parseQuizResponse(raw, format)` がフィールドを抽出する。○×は `TRUE`/`FALSE`/`○`/`×`/`正しい`/`誤り` 等を許容、多択は正解レターを**単語境界regex `\b[A-D]\b`** で抽出し `B.`・`(B)`・`B) 選択肢文`・`The answer is B` 等の崩れを救済する（単語内の文字は誤検出しない・範囲外の `D` 等は棄却）。選択肢数（3/4）は応答実体に合わせ、必須フィールド欠落や範囲外の正解記号は捨てる。
6. パース結果が0件なら `QuizState.Error`、あれば `QuizState.Success(isViewed=false)` とし、Snackbarで通知する（AIタブバッジの対象外）。
7. Q&A画面ではユーザー選択後に正誤、正解、解説を表示し、次の問題へ進む。

生成中の再タップはLoadingガードで無視する。requestIdによる `isCurrent()` チェックで、ノート切替後の古い結果混入を防ぐ。クイズの寿命はセクションチャットセッションに従属する（6.6）。

なお「もう2問」の追い生成（既出問題の除外リスト付き再生成）を一度実装したが、小型モデルには同一素材からの追加出題が難しく成功率が低かったため廃止した（経緯は [features/section_ai_chat.md](../dev/features/section_ai_chat.md)）。

### 6.8 ノートへのひとこと（旧「AI補記メモ」）

**2026-08-09 に全面作り直した。** 旧補記は「4つの分類ラベル＋補記3行」をMarkdownファイルとして
Vaultへ保存していたが、**出力枠（256トークン）がゼロサムなのに、行動を変えないラベルが
価値のある側を圧迫していた**（→ [reflect_remark](../dev/features/reflect_remark.md)（作り直した理由））。
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
`_ReadingTraces/*.json`（schema v6）へ入る。**Vaultに `.md` は作らない。**

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

Reflect（AIタブ）で、AIが原文箇所を**選び**（生成しない）、ユーザーが確認した箇所だけを元ノートで `**太字**` にする＝プログレッシブ要約支援。短文は文、長文は句、鉤括弧内は語句が候補になる。設計判断の全体は [features/reflect_distill.md](../dev/features/reflect_distill.md)。実装の骨格：

1. `buildDistillSourceModel`（`DistillSourceModel`）が本文を、UTF-16オフセット保持＋Markdown構造認識（コードフェンス・テーブル・frontmatter・見出しを除外、インラインコードは太字内許容）で文分割する。**保護範囲（`protectedSpans`）に境界を置かない** — コードスパン・リンク・**斜体 `*…*`・太字斜体 `***…***`・打ち消し線 `~~…~~`**。判定は表示と共有の `scanInlineSyntax`（`domain/markdown/InlineSyntax.kt`）が返し、**走査の単位も表示側のブロック**（段落は連結、リスト項目と引用行は1行ずつ）に揃える。表示用 `NoteSectionModel` は親子重複・見出しなし0件のため流用しない。**同じ段で粒度も決める** — 60字超の文は `splitSentenceIntoClauses` が読点で句へ割り（下限15字へ届くまで前から積み、末尾の余りは直前の句へ吸収。読点は句に含めない）、鉤括弧の中身は `bracketedTermRanges` が語句候補にする。各候補は親文の範囲（`contextRange`）を保つ。
2. 一段目（AI不使用）：`selectDistillCandidates` がサリエンス（タイトル別・直近見出し別のbigram Dice）＋構造的重み（段落先頭/末尾/見出し直下）でスコアし、チャンク網羅で候補を絞る。**この段が5つの間引きを持つ** — `isLinkOnlyRange` でリンクだけの候補を外す（長さではなく、記号を除いた残りに文字が残るかで判定し、接続語はリンクに挟まれた断片でだけ剥がす）／上限160字は句ではなく親文へ掛ける／1文あたりの句は2件・語句は表層重複を落として2件まで／**端が保護範囲の内側に入る候補を外す**（装飾の対を片側だけ含む範囲へ `**` を挿すと、文字を1つも消さないまま記法の対応が変わる）／**出口で候補集合を非重複にし、重なれば細かい範囲を残す**。語句は位置の重みと短文ペナルティの対象外。
3. 二段目（AI 1回）：`PromptBuilder.buildDistillPrompt` が候補（ID＋原文）を意図ベースで渡し、`parseDistillResponseIds` が境界regex `\b S\d{3} \b` でIDだけ抽出（許可集合＝実際に渡した候補のみ）。
4. `applyDistillBold` がオフセット降順で `**` を挿入（装飾記号の挿入のみ・削除なし）。**重なる範囲は `require` で拒む**ので、非重複は 2. の出口が保証する。累積太字上限は編集対象本文比率30%（既存太字も分母/分子に含む）、短文は最重要1箇所の例外あり。候補には文・句・語句が混ざるため、画面の数え方は「箇所」で、状態名も単位を持たない（`isSingleCandidateException`・`Saved.changedCount`）。
5. 保存は `DistillWriteRepository`（`DistillPersistence`）：原バイトSHA-256の二重競合確認 → キャッシュ構築＋fsync → 復旧レコードを `noBackupFilesDir` に原子確定 → SAF `"wt"` 一気書き → 出力ハッシュ検証。中断時は起動時に4分岐で復旧判定し、v1最小復旧UI（現在維持／元へ復元／別ファイルへ書き出し）を出す。空き容量が不明/不足なら中断。
6. 保存後は `openNote()` を使わず**本文専用リロード**（`reloadNoteBody`／`withDistillBodyReloaded`）：要約・関連・ひとことは維持し、生Markdown文脈に結び付くセクションチャット・クイズは破棄する。`DistillController` が requestId で全フローを直列化し、ノート切替でキャンセルする。

2026-08-20に表示・候補境界・通常保存・外部編集競合・故障注入4地点・復旧4分岐・保存後状態を
Pixel実機のSAF経路で確認した。最大256KiBでは一段目685〜904ms、ヒープ増分約25.1MiB、
管理対象ファイルのピーク約512.3KiB、事前空き容量見積832KiBだった。残る既知制約は、
ハッシュ照合と書き込みの間のTOCTOUを完全には閉じられないことである。

**2026-08-29 に、既存の斜体・打ち消し線をまたいで太字化しうる欠陥を直した**（上記1・2の保護範囲）。
装飾の内側で文・句が割れ、その候補を保存すると装飾の対応が変わっていた。**文字は1つも消えないため、
「装飾記号の挿入のみ・既存文字を削除しない」という契約を満たしたまま壊れる**形で、句分割を使わない
文単位の候補でも起きた。**続く修正確認レビューで、記法の種類は揃っていても解釈規則（バッククォートの数え方・
エスケープ・リンクの消費・ブロックの単位）が表示側と違うことが分かり、解釈器そのものを1つにした。**
さらに再修正確認で、**入れ子の保持**（外側を太字にしても内側の装飾が消えないこと）と、
**走査を入力サイズに比例させること**（28,000リンクの最大サイズで 7,941ms → 19ms）が要ることが分かった。
続く再々修正確認では、**未閉じ開始記号の再探索**（250,000文字で 8,440ms → 11ms）と
**候補ごとの保護範囲走査**（装飾文32,000件で 5,156ms → 36ms）が残っていた。
さらに次の巡で、**長さの違う未閉じバッククォート**が位置ごとに検索キーと巨大文字列を作り、
96,002文字（上限の4割未満）で `OutOfMemoryError` になることが分かった。連なり単位の索引で 4ms。
JVMテストは計31本を足し、**24の変異**すべてで落ちることを確かめてある。
**2026-08-29 に `DIST-19`・`DIST-20` をPixel実機で確認して完了した** — 装飾の片側だけを含む候補は出ず、
保存後も斜体・コード・通常リンク・wikilinkの対象文字列と描画が維持され、Obsidian側の見え方も保存前と一致した。

### 6.11 ReadingTrace（読書痕跡）

全経路で読書位置を自動記録し、Rediscoverで同じノートを引いた時だけ過去の読み方を再会カードへ出す。設計判断は [features/reflect_reading_trace.md](../dev/features/reflect_reading_trace.md)。

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
  → 「まだ考えたい」の印があれば、保存済みを再掲して終わり（**生成しない**）
  → 2訪問以上かつ前回の試行から訪問が増えていれば、原文全体から候補を規則で列挙
  → 種別を決めて（問い ＞ 古い前提 ＞ 俯瞰要約）Gemini Nano を**1回だけ**呼ぶ
  → 結果を3つに分ける
      Generated       … 1件決まった。カードとサイドカーへ載せる
      NoCandidate     … AIが「該当なし」。枠は出さず、種別 Overview で空振りを記録
      Unavailable     … 呼べなかった／失敗。**何も記録しない**（次に開いたとき試し直す）
```

サイドカーはschemaVersion、UTF-8バイト上限、canonical payloadのchecksumで検証する。訪問は直近30件を保持し、**最後に生成を試みた時点の訪問件数**を併記して、訪問が増えた時だけ作り直す。

**枠へ出すものは4種類で、排他1件。** 俯瞰要約・当時の問い・古い前提・「まだ考えたい」の印が
同じ `aiSummary` の口を取り合う。種別は欄（`aiSummaryKind`）として持ち、前置きの文言はそこから決める
（文字列の中にしか無いと表示側が分岐できず検査も書けない）。候補の列挙は**抜粋ではなく原文全体**へ当てる —
抜粋へ当てると、長文で切り落とされた区間の問いが永久に届かない。
**印は内容ごと保存する**ので、再掲に生成は要らない。→ [features/reunion_card.md](../dev/features/reunion_card.md)書き込みはSAF `"wt"` のベストエフォートで、破損時はカードを出さず次回訪問で作り直す。

到達率は `(最深ブロックindex + そのブロックの可視割合) / 総ブロック数` の切り捨てで、100%は最終ブロックの末端が画面へ入った場合だけに成立する。読書時間は背面にいた分を除いた能動時間で測り、背面化で書いた訪問は復帰後の読み進めで差し替える（1回の閲覧＝1訪問）。保存・照合要求はノートを開いた時点のVaultキーを運び、Gatewayが書込直前に現在のVaultと照合して不一致なら捨てる。

実装レビューで見つかった高優先度3件は上記で解消済み。残る未解決事項は次の2件。

- フォルダ索引が外部同期で増えたファイルをプロセス再起動まで認識しない。
- 30件の保持上限と累計訪問回数を分離していない。

いずれも未解決。

### 6.12 読書痕跡の退避と復元

痕跡サイドカーは Vault 内の `_ReadingTraces/` にあり、**フォルダごと消えれば再生成できない文章**
（ひとことへの返事）ごと失われる。データ管理画面から1ファイルへ書き出し、そこから読み戻せるようにした。
設計判断は [features/reading_trace_backup.md](../dev/features/reading_trace_backup.md)。

```text
書き出し
  → Vault内の痕跡を全走査し、1つのJSONへまとめて任意の場所へ保存
  → 中身は平文。Vault内へ置くと Obsidian の同期でクラウドへ渡ることを、押す前に伝える

読み戻し
  → まず**下見**（何件増える／何件変わらない／読めなかったものは何か）を出す
  → ユーザーが適用を選んで初めて書く。適用中は中止できる
  → 併合規則は「端末に無かった痕跡を受け入れる」。既存を黙って上書きしない
```

- **読めなかったものを黙って捨てない** — 形式が違う・壊れているものは件数と理由を出す。
- **中止は要求であって完了ではない。** 停止を待つ間の再タップで、停止前の件数を確定してしまう欠陥が
  実機検証で出て、直してある（→ [lessons](../dev/lessons/L49.md) L49）。
- 走査とJSON処理はMainの外へ逃がす（最大入力で `ReadingTraceBackupThreadingTest` が固定）。

2026-08-28 に、選択の4境界・単発中止・停止待ち中の再タップまで Pixel 実機で確認済み。

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

`AICoreClient` は `ModelPreference.FULL` を指定して `Generation` クライアントを遅延生成する。FULLは「速度より精度を優先」の指定であり、実際に動くモデル世代（nano-v2 / v3）は端末のAICoreが決める（Pixel 10系はnano-v3）。状態は次のようにアプリ内の5状態へ変換する（2026-08-12。判断の正本は
[background_ai_ux](../dev/system/background_ai_ux.md) §6）。

| ML Kit状態 | アプリ状態 | 呼び出し側の次の行動 |
|---|---|---|
| AVAILABLE | `Ready` | 生成する |
| DOWNLOADABLE | `NeedsDownload` | **`downloadModel()` を呼んでよい唯一の状態** |
| DOWNLOADING | `Downloading` | 待つ。**`downloadModel()` は呼ばない**（合流できない） |
| UNAVAILABLE かつ AICore無し | `Unsupported` | 諦める（恒久） |
| UNAVAILABLE かつ AICore有り／未知の値／状態確認例外 | `TemporarilyUnavailable(cause)` | 時間をおいて再試行 |

恒久非対応の判定は `FeatureStatus` ではなく `GenAiUtils.isAiCoreCompatible`（AICoreアプリの
有無と最低バージョン）で行う。`UNAVAILABLE` は対応端末でも返るため、それだけでは恒久と断定できない。

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
| 関連ノート | 800文字 | 候補最大40件（`ID｜タイトル — 本文/タグ等`を予算3,500文字内へ動的短縮）、ID応答で5件要求 |
| AIピッカー | 本文なし | タイトル最大40件を予算2,000文字内へ（**切らずに行ごと落とす**）、3件要求 |
| クイズ | フォーカス周辺1,200文字 | 入力量に応じて ○×2問／3択2問／4択1問（解説は4択のみ1文） |
| 蒸留 | 本文なし（候補文のみ） | 候補最大24件を予算1,500文字内へ収め、ID応答で最大6件要求 |
| ひとこと | 1,500文字 | 1文（80〜120字）。候補ノート3件＋各80字。映し返しは返事を400字へ抜粋して渡す |
| セクション要約 | 1,500文字 | 2〜4文 |
| セクション質問・Q&A | 1,500文字 | 質問候補最大3件 |
| ReadingTrace俯瞰要約 | 本文なし | 直近10訪問を予算2,600文字内へ（**古い訪問から落とす**）、1〜2文 |
| 再会カードの候補選別 | 本文なし（原文の1文のみ） | 種別ごと最大10件を `ID｜原文` で提示し、**ID応答で1件**。該当なしは `NONE` |

上限はいずれも UTF-16 文字数で、トークン数や意味境界では切っていない。**ただし切り方は先頭固定長ではない**（2026-07-28 に変更。設計は [ai_input_excerpt](../dev/system/ai_input_excerpt.md)）。

- 上表の本文を持つ7経路は、`PromptBuilder` ではなく呼び出し側が `buildNoteExcerpt(content, 上限)` で `NoteExcerpt` を作って渡す。`PromptBuilder` に `take()` は残っていない。
- **予算内のノートはMarkdownを解析せず原文をそのまま渡す**（移行前のプロンプトと文字列単位で同一）。
- **超過時だけ**、level 3までの見出しを全体から均等選抜した骨格＋冒頭60%＋末尾40%を、`## Note outline` / `## Beginning excerpt` / `(omitted)` / `## Ending excerpt` の明示ラベル付きで構成する。単一ブロックが枠を超える場合も捨てずに切り、コードブロックは閉じフェンスを復元する。
- 超過時は表示用パーサ（`parseMarkdownBlocks`）を通すため、frontmatter除去・コードフェンス言語名の消失・段落内改行の空白化・箇条書き記号の `-` への統一が起きる。**リストの番号と入れ子段数は保持される**（2026-08-02）。
- 抜粋時のみ226文字の注意書き（`ABRIDGED_NOTICE_PREFIX`）を本文直前へ置く。**この注意書きは上限の内側から支払う**ため、AIへ渡る本文由来領域の合計が上限を超えない。
- 解析コストは1MBノートで約460ms（デスクトップJVM実測）あるため、抜粋生成のみ `Dispatchers.Default` で実行する（`excerptDispatcher`）。

蒸留だけはこの経路に乗らない。ノート全体を文単位へ分割し（最大400文）、チャンク網羅を条件にスコア上位を候補化する独自方式である。

#### 完成プロンプトの上限（2026-08-22）

**上表は本文由来の領域だけを閉じている。** 会話履歴・質問・候補名・見出しはその外側にあり、
抜粋を絞っても入力は伸びうる。実際に2つの経路が開いていた — セクションチャットは**会話履歴を全件**渡し、
関連候補は**タイトルだけで予算を超えても収まりを確かめ直さず**返していた。

`PromptBudget.assemble()` が最後に1回だけ上限（`PromptLimits.MAX_PROMPT_CHARACTERS` ＝ 6,000文字）を当てる。
**12本すべての builder がここを通る。**

| 部位 | 上限を超えたとき |
|---|---|
| 指示文（役割・出力形式・クイズの書式契約） | **削らない** |
| 材料（タイトル・本文抜粋・候補一覧・会話履歴） | 末尾から削り `(truncated)` を残す |
| 締め（新しい質問・ユーザーの返事） | **削らない** |

**6,000という数字はトークン計測から逆算していない。** 現行設計が意図する最大構成
（関連ノート＝指示文＋タイトル＋抜粋800＋候補3,500）から決めてあり、
**この値ではどの経路の入力も短くならない。** 閉じるのは意図しない伸びだけである。
部分予算を上げると `PromptBudgetTest` が落ちるので、上限も一緒に決め直すことになる。

実トークンでの余裕は端末とモデル世代に依存するので、ここでは主張しない。
2026-08-22 の実測（Pixel 10 Pro Fold / `nano-v3`）では上限4,352・出力予約256に対し、
12用途×2プロファイルの全24ケースが収まり、最小余裕は日本語の関連ノートで1,575トークンだった。
→ [ai_input_excerpt](../dev/system/ai_input_excerpt.md) §13

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

リスト項目は `ListItem(depth, marker, text, checked)` で、入れ子段数・番号（`1.` と `1)`、先頭ゼロを含む原文表記）・タスクのチェック状態を保持する。段数の算出はCommonMarkにもObsidianにも準拠しない独自の寛容規則で、設計は [system/markdown_rendering.md](../dev/system/markdown_rendering.md)。箇条書き記号 `-` / `*` / `+` の違いだけは意図的に落とす。コードフェンスの言語指定も保持しない。

### 9.2 対応インライン記法

- `***太字イタリック***`
- `**太字**`
- `*イタリック*`
- `~~打ち消し線~~`
- `` `インラインコード` ``
- `[[Obsidianリンク]]`
- `[ラベル](URL)`

**この一覧を解釈しているのは `domain/markdown/InlineSyntax.kt` で、蒸留も同じ関数を呼ぶ。**
描画側は種別を色・太さ・下線へ写すだけを担う。**解釈を2つ持つと書き込みが表示を壊す** —
実際に、記法の種類を揃えた後も解釈規則の食い違いが4件残った（→ §6.10・[lessons](../dev/lessons/L51.md) L51）。
**ここへ記法を足すときは解釈器へ足す。** 描画側だけに足しても蒸留は守れない。

エスケープ（`\*`）は記号だけを描き、インラインコードは開いた数と同じバッククォートで閉じる（Obsidianと同じ）。
**入れ子も描く** — `**A *B* C**` の内側の斜体は残る。捨てると、蒸留が文を太字にした瞬間に
ユーザーの装飾が表示から消えるため（2026-08-29 のレビューで指摘され、解釈器と描画の両方を再帰にした）。

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
- `ContentResolver.openInputStream()` が `null` の場合は空文字を返し、読込失敗と空ノートを区別しない。

---

## 12. データ保護・プライバシー

- ノート本文はアプリ内で読み取り、AI生成は端末内 Gemini Nano を利用する設計である。
- クラウドAI API、独自サーバー、解析SDKへの送信コードは存在しない。
- 初回モデル取得にはML Kit側のダウンロードが必要になる。
- Vaultへの書き込みは、`_AI補記` フォルダの作成と補記Markdown保存・削除、蒸留による既存ノートの上書き（`**` の挿入のみ・削除なし）、`_ReadingTraces`への読書痕跡JSON保存がある。蒸留の上書きは原バイトSHA-256の二重照合と出力ハッシュ検証を通し、中断時は `noBackupFilesDir` の復旧レコードから起動時に復旧判定する。読書痕跡はユーザーの`.md`に触れないベストエフォート設計で、checksum破損検知はあるが復旧ファイルと原子更新は持たない。**痕跡は書き出して読み戻せる**ので、フォルダごと失っても再生成できない文章（ひとことへの返事）を退避できる（§6.12）。**書き出したファイルは平文**で、Vault内へ保存すると Obsidian の同期でクラウドへ渡ることを保存前に明示する。
- `android:allowBackup="true"` だが、`random_note_prefs`（Vault の SAF URI・テーマ設定・当日分の閲覧履歴タイトル）はバックアップ・端末移行の両方から除外している。`dataExtractionRules`（`res/xml/data_extraction_rules.xml`／API 31以上）と `fullBackupContent`（`res/xml/backup_rules.xml`／API 30以下）を併記し、minSdk 26 の全レンジを覆う。API 31以上では `cloud-backup` に加え `device-transfer` からも除外する（SAFの永続URI権限は移行先端末で無効になり、復元しても壊れた参照が残るだけのため）。`allowBackup` 自体は true のまま残し、将来バックアップしたいデータが出た時に個別許可できるようにしている。
- ログ出力コードはなく、ノート本文やAIプロンプトをLogcatへ明示出力していない。

---

## 13. テスト状況

### 13.1 ユニットテスト内訳

| テストファイル | ケース数 | 主な対象 |
|---|---:|---|
| `AiAvailabilityContractTest.kt` | 12 | AI可用性の契約（4状態の意味と、呼び出し側が取る行動の対応） |
| `AnnotationControllerTest.kt` | 10 | 旧補記ファイルの一覧・削除、Vault世代照合、ハンドルの取り直し防止 |
| `BoundedNoteReadTest.kt` | 9 | 用途別の読込予算、上限到達の判定、多バイト文字の末尾切り |
| `DistillControllerTest.kt` | 27 | 蒸留フローの直列化、requestIdガード、保存後の状態遷移・復旧分岐 |
| `DistillRecoveryStoreTest.kt` | 3 | 復旧レコードの書込・読出・破棄 |
| `DistillWriteRepositoryTest.kt` | 15 | 二重ハッシュ照合、原子確定、出力ハッシュ検証、中断・容量不足 |
| `EventKeyTest.kt` | 5 | Snackbar通知の発火判定キー |
| `InlineMarkdownTest.kt` | 16 | 強調、リンク、コード、打ち消し、誤検出防止、**描画範囲が共有トークナイザーの答えと一致すること**、エスケープ |
| `MarkdownParserTest.kt` | 38 | frontmatter、テーブル空セル、見出し、コード、CRLF、引用、リストのマーカー保持（区切り記号・先頭ゼロ・巨大桁）、段数の算出規則5つ、タブの4列展開、タスク混在、`blocksToMarkdown` の往復 |
| `NoteRepositoryTest.kt` | 4 | Markdown判定、wikilink・タイトル正規化 |
| `NoteSectionControllerTest.kt` | 6 | 表示用Markdown解析のMain外退避と、本文差し替え時の再解析 |
| `NoteSessionCoordinatorTest.kt` | 17 | Vault/ノート切替の一斉停止と一斉初期化、リセット登録漏れ検出、旧結果の後着防止、Vault世代 |
| `NoteSnapshotTest.kt` | 5 | 上限付きバイト読込、UTF-8厳格判定、ハッシュ |
| `NoteUiStateStoreTest.kt` | 2 | 各Writerが担当スライスだけを更新すること、ノート読込開始の単一通知 |
| `QuizControllerTest.kt` | 9 | バックグラウンド生成・確認状態・破棄 |
| `QuizInputProfileTest.kt` | 4 | 入力量・コード比率からの出題形式決定 |
| `QuizPromptBuilderTest.kt` | 3 | 形式別クイズプロンプトの出力契約 |
| `QuizResponseParserTest.kt` | 14 | 改行揺れ、前置き、欠落項目、不正な正解、○×/3択/4択の形式別パース |
| `ReadingTraceBackupControllerTest.kt` | 30 | 痕跡の書き出し・下見・適用・中止、停止待ち中の再入、Vault世代 |
| `ReadingTraceBackupJsonTest.kt` | 10 | 退避ファイルの形式（外から見える生JSON・読めなかった件の扱い） |
| `ReadingTraceBackupTextTest.kt` | 11 | 退避・下見・適用・中止の文言（適用だけ言い方を変える） |
| `ReadingTraceCleanupControllerTest.kt` | 19 | 孤児痕跡の洗い出しと削除、Vault世代照合、削除直前の再走査 |
| `ReadingTraceControllerTest.kt` | 94 | 能動読書10秒閾値、最深到達点（可視割合込み）、追記上限、後続bind、二重flush、pause/resumeと訪問の差し替え、Vaultキーの持ち回り、再会カード、AI要約・キャンセル、**ひとこと／返事の保存と、書けなかったぶんの退避・再書き込み** |
| `ReadingTraceJsonTest.kt` | 54 | JSON往復、checksum、UTF-8、必須項目・上限、要約キャッシュ整合、**v1→v5 の各版からの読み込み互換**（旧版の正規形をテスト側に写し取って固定） |
| `ReadingTraceLimitsTest.kt` | 2 | 上限どうしの整合（全フィールドを上限まで詰めてもファイル読込上限に収まること） |
| `ReadingTraceMergeTest.kt` | 20 | 読み戻しの併合規則（端末に無いものを受け入れ、既存を黙って上書きしない） |
| `ReadingTraceStoreTest.kt` | 33 | ハッシュキー、保存/読込、破損・パス不一致、フォルダ/書込失敗、Vaultキーの受け渡しと不一致時の拒否 |
| `RemarkControllerTest.kt` | 27 | ひとことの生成・検証落ち・候補選定（3件＋抜粋・wikilink済みは後回し）・返事の保存結果3値・保存済みの読み戻し |
| `SearchControllerTest.kt` | 15 | スコープ切替時の結果破棄・同一スコープ再選択の保持 |
| `SectionChatCombinationTest.kt` | 16 | **共存しうる2処理の両方向**（要約の再試行×走行中の回答、クイズ×チャット） |
| `SectionChatControllerTest.kt` | 12 | セクションチャットの状態遷移・破棄 |
| `SummaryControllerTest.kt` | 7 | モデルDL待ちの要約がノート切替をすり抜けないこと、DL進捗の照合 |
| `SurroundingContextTest.kt` | 6 | フォーカス周辺テキスト構築（親子重複の回避・フォールバック） |
| `VaultImageIndexStoreTest.kt` | 23 | 画像索引のTTL・再走査の歯止め・Vault世代 |
| `VaultPathTraversalTest.kt` | 19 | 相対パス付きBFS、除外フォルダ、循環、同名階層、非Markdown除外 |
| `ai/AiAvailabilityMappingTest.kt` | 10 | `FeatureStatus` と例外から `AiAvailability` への写像 |
| `ai/DistillPromptBuilderTest.kt` | 4 | 候補件数・文字予算内への収容、プロンプト出力契約 |
| `ai/PromptBudgetTest.kt` | 8 | **完成プロンプトの入力上限**（材料だけを削る・質問と返事は残す・意図する最大構成で切り詰めが起きない） |
| `ai/PromptBuilderExcerptRegressionTest.kt` | 8 | 7プロンプトの出力文字列の固定、抜粋時だけ注意書きが出ること |
| `ai/PromptIndentationTest.kt` | 3 | **複数行の値を埋めても字下げが漏れないこと**を全builderで固定 |
| `architecture/AdrShapeTest.kt` | 7 | ADRの形（30行以内）と、`最終検証` が実在するコミットを指すこと |
| `architecture/AiAvailabilityUsageTest.kt` | 2 | `AiAvailability` の分岐が網羅されていることをソース走査で固定 |
| `architecture/AiClientDoubleTest.kt` | 1 | テスト用 `AiClient` が1本へ寄っていることをソース走査で固定 |
| `architecture/DesignDocStateNameTest.kt` | 3 | 状態・列挙の改名／削除が正本へ反映されていること |
| `architecture/DeviceValidationDocsTest.kt` | 3 | 実機検証の入口と機能別ケースの形（正本リンク・前後処理・記録） |
| `architecture/DistillProtectedScanTest.kt` | 1 | **保護範囲をカーソル越しにしか読まないことをソース走査で固定**（時間差が出ない二乗経路を形で縛る） |
| `architecture/DistillCandidateUnitCopyTest.kt` | 1 | **蒸留の画面文言が候補の単位を「文」と決めつけないことをソース走査で固定**（候補には句・語句が混ざる） |
| `architecture/InstrumentationTestShapeTest.kt` | 1 | **`@Test` の戻り値が `void` でなくなる書き方をソース走査で禁じる**（→ §13.5の脚注） |
| `architecture/NoteExcerptThreadingTest.kt` | 1 | 抜粋生成が本番7経路すべてで `Dispatchers.Default` 側にあること（呼び出し箇所の一覧ごとソース走査で固定） |
| `architecture/NoteSectionThreadingTest.kt` | 3 | 本文解析がMainのスコープから呼ばれていないことをソース走査で固定 |
| `architecture/PackageDependencyTest.kt` | 2 | パッケージ依存の向き（ルートパッケージ経由の抜け道を含む） |
| `architecture/PromptGenerationCoverageTest.kt` | 3 | 全プロンプトが実生成テストで覆われるか未保証として列挙されるか、件数の固定 |
| `architecture/ReadingTraceBackupThreadingTest.kt` | 5 | 退避のJSON処理がMainの外にあることをソース走査で固定（最大8MB） |
| `architecture/ReviewFindingsLedgerTest.kt` | 6 | 最新レビューの指摘が受付簿へ全件載ること、未解決の処遇だけであること、**受付行の課題が実在すること**、ID重複の拒否 |
| `architecture/SchemaVersionDocsTest.kt` | 2 | **文書が名指しする現行スキーマ版がコードの定数と一致すること** |
| `architecture/SourceDocSyncTest.kt` | 2 | 状態型の欄が正本の一覧に載ること、KDocの相対リンクが実在すること（3ソースセット） |
| `architecture/WipIssueReferenceTest.kt` | 2 | `_wip/` とコードが実在しない課題IDを参照していないこと |
| `domain/AiStatusNoticesTest.kt` | 10 | AI状態の説明文と再試行導線の出し分け |
| `domain/BoundedInputStreamTest.kt` | 13 | **上限の境界（-1／ちょうど／+1）を単一read・配列read・`skip`・混在で固定**、`len == 0` の契約、`available()` の丸め、先読みが1回だけであること |
| `domain/ByteBudgetCacheTest.kt` | 10 | バイト予算つきLRU（超過時の追い出し順・単一エントリ超過） |
| `domain/DistillCandidateScoringTest.kt` | 18 | サリエンス採点、構造的重み、チャンク網羅 |
| `domain/DistillResponseParserTest.kt` | 3 | ID抽出、許可集合外の棄却 |
| `domain/DistillSourceModelTest.kt` | 42 | 文分割、UTF-16オフセット、コード/テーブル/frontmatter除外、**装飾（斜体・太字斜体・打ち消し線）の対を割らないこと／過剰保護もしないこと** |
| `domain/DistillTransformerTest.kt` | 5 | オフセット降順の `**` 挿入、太字比率上限、短文例外 |
| `domain/markdown/InlineSyntaxTest.kt` | 9 | **インライン記法の唯一の解釈器**（種別・エスケープ・バッククォートrun・リンク消費・対の探索・空白規則・入れ子） |
| `domain/ImageDecodePolicyTest.kt` | 17 | 復号可否の拡張子判定、寸法・ピクセル数の上限、間引き倍率、**`TooLarge`/`Broken` の切り分け順序** |
| `domain/ImageLinkResolutionTest.kt` | 29 | 画像参照の解析と索引照合（完全パス→ファイル名の順、曖昧・外部URL・空） |
| `domain/KeyedMemoCacheTest.kt` | 5 | LRUメモ化（成功時のみ格納） |
| `domain/NoteExcerptBuilderTest.kt` | 18 | 抜粋の予算不変条件（注意書き・ラベル込み）、境界、見出しの均等選抜、単一巨大ブロック（段落・コード・表・リスト）、frontmatter除去、リストの番号と段数がモデルへ届くこと、記法増加後も全予算で上限を超えないこと、中略なしの連続レイアウト |
| `domain/NotePaperAgeTest.kt` | 15 | 相対四分位による紙の地色の段階決定 |
| `domain/ReadingTraceOrphansTest.kt` | 27 | 孤児判定の遮断器（フォルダ単位・読取失敗の伝播・**ルートと別サブツリーの混在**）、削除直前の三値再走査 |
| `domain/RelatedCandidateContextTest.kt` | 11 | 候補の本文肉付け・入力予算内への整形 |
| `domain/RelatedCandidateIdTest.kt` | 9 | 一時ID採番と応答からのID抽出 |
| `domain/RelatedCandidateOrderingTest.kt` | 3 | 採番プレフィックス抽出 |
| `domain/RelatedCandidateRankingTest.kt` | 5 | 採点戦略注入の汎用ランキング |
| `domain/RelatedCandidateScoringTest.kt` | 10 | タイトル話題スコア（bigram Dice＋採番近接） |
| `domain/RelatedContextScoringTest.kt` | 6 | 本文シグナル再ランク（tags/snippet/title） |
| `domain/RemarkComposerTest.kt` | 36 | ひとことの5検査（表明語・長さ・ID実在・原文根拠・リンクと問いの排他）、冒頭二人称の除去、映し返しの問い禁止、AI入力用の返事抜粋、保存上限との整合 |
| `domain/ReunionCandidateScannerTest.kt` | 19 | **再会候補の列挙規則**（終助詞「か」の問い・記録と古い前提の区別・版番号と計測値の区別・括弧内で切らない） |
| `domain/SearchKeywordMatchingTest.kt` | 10 | bigram採点、1文字クエリの部分一致、フォールバックの並び順と一致0件除外、再現率カットの0件保持 |
| `domain/SearchPickerBudgetTest.kt` | 2 | ピッカーの**提示集合＝許可集合**（予算で落ちた候補を応答で受理しない） |
| `ui/AiTabBadgeStateTest.kt` | 3 | AIタブバッジは生成中だけを示すこと（結果が出ても残らない） |
| `ui/NoteImageMeasurementsTest.kt` | 7 | 画像の表示寸法算出（原寸・上限・アスペクト保持） |
| `ui/NoteImageTextTest.kt` | 19 | 画像の失敗理由ごとの文言と代替テキスト |
| `ui/ReadingProgressGeometryTest.kt` | 13 | 最終可視ブロックの可視割合（完全/一部/画面外/高さ未確定）、5%刻みの量子化 |
| `ui/ReadingTraceCleanupTextTest.kt` | 6 | 孤児整理画面の文言（件数・削除の取り返しのつかなさ） |
| `ui/ReadingTraceHeadlineTest.kt` | 8 | 経過日・セクション・到達率・訪問回数のカード文面 |
| `ui/ReunionLeadTest.kt` | 4 | **種別ごとの前置き**（問い・古い前提・印あり・俯瞰要約には付けない） |
| `ui/VigilithAccessibilityTest.kt` | 2 | 状態・対象節をまとめたTalkBack文言、回答／要約の区別 |
| `ui/VigilithMascotMotionTest.kt` | 10 | Summaryの片翼案内、蒸留の断片収集・両翼保持・下線、Messengerの着地・一度だけの発光、入力clamp・出力範囲、Summarizing>Idleのレンズ輝度差 |
| `ui/VigilithModeTest.kt` | 10 | Idle/Summarizing/Distilling/Messengerの優先順位、蒸留3工程、モデル取得除外、カードdismiss、全画面・シート非表示 |
| `ui/VigilithOpeningMotionTest.kt` | 8 | ハロー・全身・名称の登場順、保持区間、退場、終端・範囲外入力 |
| `ui/VigilithPlacementTest.kt` | 6 | 四辺clamp、Fold再配置、ラベル寸法、Snackbar / IME予約領域、狭小画面 |
| `ui/VigilithStatusDerivationTest.kt` | 14 | セクションチャット／全画面AIの状態導出（要約×クイズの合成） |
| `ui/theme/AppColorContrastTest.kt` | 28 | 明暗の役割トークンのコントラスト比。文字は4.5:1・塗りと記号は3:1を**強制**する（**既知未達は解消済み** — 未達だったナビ帯上のバッジ塗りは 2026-08-09 に対象ごと消えた） |
| `ui/theme/VibrantTextUsageTest.kt` | 2 | 画面からの `onVibrant` 直接使用と、文字色への任意の `copy(alpha)` をソース走査で禁じる |
| **合計（96クラス）** | **1147** | |

> **今回も全96クラスを機械的に数え直した。** 前回まで「各行は 2026-08-10 時点のまま、
> 合計だけ更新」という状態が続き、行ごとの件数が目安にしかならなかったため。
> 件数は `@Test` の出現数で、説明は手で書いている（**説明の側は古くなりうる**）。

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
受付漏れ検査6件・テスト形状の検査1件を足した。2026-08-09 のひとことの作り直しで91ケース増え、70ファイル・844ケースとなった。
**2026-08-22 の AI入力の上限（X-5）と再会カード（X-2）で69ケース増え、92ファイル・1,039ケースとなった。**
**このうち複数は実機レビューが見つけた欠陥の回帰で、いずれも既存テストが緑のまま通り抜けていた**
（新しい単位と既存の読み手が交差する入力を通していなかった → [lessons](../dev/lessons/L41.md) L41）。

**2026-08-23〜28 の痕跡の退避（X-9）で76ケース、08-29 の蒸留の装飾保護で10ケース増え、
現在は97ファイル・1,126ケース全件グリーン。**
**08-29 の10ケースは、書いた直後に全部通ってしまった。** 変異を6つ当てたところ、
過剰保護を見るはずの4本が**1本も落ちなかった** — 装飾が文の内側で閉じるデータでは候補が変わらないためで、
偽の対が文境界を飲み込むデータへ作り直して初めて効いた（→ [lessons](../dev/lessons/L51.md) L51）。
**「テストが通ること」と「効いていること」が別だという実例が、また1つ増えた。**

**続く修正確認レビューで16ケースを足して1,142ケース、再修正確認でさらに1ケース増えて1,143ケース。**
解釈器を表示と共有した回で `InlineSyntaxTest`（解釈規則）と、**表示が本当にその答えを使っているかを
入力表で突き合わせる検査**を置いた。変異は17種すべてで落ちる。

**上限テストの作り方も2度変えた。** 文字だけの最大サイズ入力は記法spanを1つも持たず、
「記法数×文字数」で効く経路を通らないまま緑で通していた。リンク密・既存太字密を足したが、
**その2本とも「候補数×保護範囲数」を通っていなかった**（リンク密は句点が無いので候補1件、
太字密は保護範囲を増やさない）。いまは**文字数・記法数・候補数×保護範囲数**の3系統を持ち、
**閾値を置けるほど時間差が出ない経路はソース走査で形を縛る**（→ [lessons](../dev/lessons/L52.md) L52）。

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
指摘をゲートに載せず、かつ催促は消さないための設定である（→ [dependency_policy](../dev/system/dependency_policy.md)）。

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
（→ [instrumentation_testing](../dev/system/instrumentation_testing.md) 判断4）。
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
- Gemini Nanoの**ダウンロードとタイムアウト**（利用可否と生成は §13.5 が覆うが、**生成は12経路中4経路だけ**）
- ~~Compose UI、NavigationBar/Rail、全画面遷移~~ → **§13.5 が覆った**（読書画面の描画抑止・位置引き継ぎ・タブ遷移）。ただし画面幅による Rail 切替は未カバー
- 連続操作時のキャンセルと競合（**タブ連打テストはあるが競合を作れていない** → §13.6・TEST-4）
- 実際のObsidian Vaultを使ったE2Eテスト（偽Vaultでの経路は §13.5 が覆う。**実プロバイダ固有の挙動**は対象外）

現在の1,147テストは、Android依存の薄い純粋ロジックと、Controller間の調停の回帰防止に有効である。**instrumentation 57件が SAF・画像復号・Compose描画・画面遷移・端末AI生成の一部を実機で覆っている**が、**保証範囲は §13.6 のとおり主張より狭い**。ReadingTraceの高優先度3件はこの境界外で見つかったものであり、修正後も**Android側の実挙動は実端末確認でしか担保できない**。Vigilithも状態分離・モーション・配置範囲は純関数で検証しているが、実フレームの見え方、タップ／ドラッグの競合、Snackbar・IME・ReadingTraceとの視覚的な重なり、TalkBackは実機確認が必要。

---

### 13.5 instrumentation の内訳（57件）

段階の定義と判断は [instrumentation_testing](../dev/system/instrumentation_testing.md) が持つ。
**全件を一度に通した実行は無い**（→ §0.1）。直近の実行は機能単位である。

| テストクラス | 件数 | 対象 | JVMで書けない理由 |
|---|---:|---|---|
| `InstrumentationSetupTest` | 1 | 対象アプリのContext取得 | Runnerの疎通 |
| `ComposeRenderingSetupTest` | 1 | Composeテストルールの描画 | Compose実行環境 |
| `ui/NoteReadingFlowTest` | 9 | 解析待ちの描画抑止、全画面への位置引き継ぎ、進捗報告の整合、画像の表示 | レイアウト実測・可視判定 |
| `data/VaultScanInstrumentationTest` | 9 | 走査の相対パス、**読取失敗と不在の区別**、補記の作成/一覧/削除、document同一性 | 実物の `ContentResolver`・`DocumentsContract` |
| `data/NoteImageGatewayInstrumentationTest` | 13 | 復号・寸法読み、上限の内外、`TooLarge`/`Broken` の切り分け、索引の世代と鮮度確認 | 実物の `BitmapFactory` |
| `ai/PromptTokenBudgetTest` | 5 | トークン計測と能力診断、**完成プロンプトの実トークン基準線** | 端末AI（AICore） |
| `ai/OnDeviceGenerationTest` | 4 | 本番プロンプトでの実生成 | 端末AI（AICore） |
| `ui/ActivityRecreationTest` | 2 | Activity再生成でOPを再生しない、繰り返し再生成（**プロセス死亡は覆わない**） | Activityライフサイクル |
| `ui/TabNavigationTest` | 5 | タブの往復・巡回・**戻る操作での履歴契約**・遷移先での再生成 | `NavHost` のバックスタック |
| `ui/QuizActionSectionTest` | 2 | クイズが使えない理由が**押した場所に描かれる**こと | Composable の描画結果 |
| `ui/ReadingTraceCardPanelTest` | 6 | 再会カードの**種別ごとの前置き・印の文言・空枠でボタンを出さないこと** | Composable の描画結果 |

> **純関数を押さえても、Composableがそれを呼ぶことは観測できない。**
> 最後の2クラスはそのために置いてある。文言を決める純関数はJVM側で固定できるが、
> 「カードがその関数を呼ぶ」「押した場所に描く」は描画しないと分からない。
> **APKが組み立つことは、描画の受け入れ条件を代替しない。**

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
- **端末AIの生成は12経路中4経路だけ。** Nano依存9件の内訳は生成4件・計測/診断5件で、
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

   パッケージ依存の向きは `PackageDependencyTest` がimportを走査してCIで固定し、ノート/Vault切替の一斉停止と一斉初期化は `NoteSessionCoordinatorTest` が実物の11 Controllerを束ねて検証する。どちらも「後始末を1つ消すと落ちる」ことを変異確認で検証済みで、規約がKDocの口約束に留まっていない。

4. **AI非依存の価値を残している**

   関連ノートはwikilinkとファイル名規則で動作し、検索にはランダムモードとキーワード一致フォールバック（bigramスコア順・0件は返さない）がある。

5. **端末負荷への配慮がある**

   AI生成の直列化、60秒タイムアウト、SAF走査キャッシュ、Markdownパース再利用を実装している。

6. **壊れやすい文字列処理が純粋関数化されている**

   クイズパース、補記Markdown、タイトル正規化、Markdown解析をAndroid I/Oから分離し、ユニットテスト可能にしている。

7. **Obsidian固有仕様への配慮がある**

   `.md`、wikilinkの別名・見出し・ブロック参照、frontmatter、補記専用フォルダを扱う。

### 14.2 残る技術的注意点

**2026-08-12 に実装から作り直した。** 優先度は「現時点で確認できる影響範囲」に基づき、
直ちに障害が起きることを意味しない。**解消済みの項目はここに残さない**（記録は
[change_history](../dev/change_history.md)、未対応の課題は [current_issues](../_wip/current_issues.md) が持つ）。

| 優先度 | 項目 | 現状と影響 |
|---|---|---|
| 中 | **痕跡サイドカーの書き込みが原子的でない** | `"wt"` の直接上書きで、書込中にプロセスが死ぬと部分破損が残り復旧元もない。SAF の `renameDocument()` がプロバイダ非互換なため割り切っている。破損は checksum で検知して孤立扱い |
| 低 | **全57件を一度に通した実行は無い** | 実機検証の単位が機能ごとのケース表へ移ったため（→ `docs/review/device_validation/`）。着手した機能のケースは都度通しており、直近は画像22/22・再会カードの描画6/6・痕跡の退避1/1 |
| 低 | YAML解析が簡易 | 複雑なYAML・引用・ネスト・複数行値に対応しない。AI推薦で使う tags/aliases の取りこぼしにつながり得る |
| 低 | Markdownの未対応項目 | クリック可能リンク・埋め込み（`![[note]]`）・数式。リスト構造と画像は実装・実機確認済み |
| 低 | 同名ノートの曖昧性 | AI推薦は候補ごとの一時IDで解決するため不定にならない。ただし決定的チャンネルや除外判定で使う正規化タイトル集合には同名畳み込みが残る |
| 低 | R8・署名が未設定 | `release` は `isMinifyEnabled = false`・署名なし。R8を有効化すると ML Kit GenAI のリフレクション解決部分が縮小で消え、**全AI機能が release ビルドでだけ落ちる**可能性がある。JVMテストは縮小前のクラスを見るため検出できず、実機検証とセットになる |
| 低 | 依存の更新そのものが未着手 | 方針は確定済みで、Lint の3チェックを `informational` にして毎ビルド hint として見えるようにしてある。**残るのは更新の実行** — `genai-prompt` は beta2→beta4 で**ソース互換だが動作互換ではない**ことが調査済み |

> **解消済みとしてここから外した項目**（いずれも本書の他節と `change_history` に記録がある）:
> instrumentation の未実行（→ 機能ごとに実行する形へ移行）・孤児掃除の導線なし（→ 手動削除を実装）・
> 外部同期の索引（→ 不在を契機に作り直す方式で解消）・AI入力が先頭固定長（→ 骨格＋冒頭＋末尾の抜粋へ）・
> 画像が未対応（→ 実装・実機確認済み）・**蒸留が既存の斜体・打ち消し線をまたぐ**（→ 解釈器の共有と `DIST-20` の実機確認で解消）・
> **ユーザーが書いた返事に退避手段が無い**（→ 書き出し／読み戻しを実装、
> 2026-08-28 実機確認済み。§6.12）。

---

## 15. 今後の改善候補

> **この節は 2026-08-12 に廃止した。** 実装済みの項目を大量に抱えたまま
> `change_history`・`current_issues`・`roadmap` と競合していたため。
>
> | 知りたいこと | 見る文書 |
> |---|---|
> | いま何が未対応か | [_wip/current_issues.md](../_wip/current_issues.md) |
> | 何をどの順でやるか | [_wip/roadmap.md](../_wip/roadmap.md) |
> | まだ作っていない機能の候補 | [_wip/feature_ideas.md](../_wip/feature_ideas.md) |
> | 何をいつ変えたか | [dev/change_history.md](../dev/change_history.md) |

## 16. この文書の更新について

**過去の更新履歴はここに置かない。** 章ごとに違う基準日の記述が積み上がり、
「どこが現在でどこが当時か」が読み取れなくなるため、2026-08-10 に全削除した。

| 知りたいこと | 見る場所 |
|---|---|
| いつ何を変えたか | [change_history.md](../dev/change_history.md)（PR単位の索引） |
| なぜそう変えたか・何を読み違えたか | [開発日誌](journal/) |
| 過去の解析書そのもの | git 履歴 |

**更新するときは章を部分的に直さず、通しで見直して測定日を1つに揃える。**

**目次も同じタイミングで直す。** 見出しの複製なので放っておけばずれるが、
**検査には載せない** — 本書はオーナーが読むための俯瞰であって、継続的に同期する台帳ではない
（→ [README](README.md)）。ゲートに載せると、**古びてよいと決めた文書が
無関係な変更を止める**ことになる。ずれは通し見直しで直す。
