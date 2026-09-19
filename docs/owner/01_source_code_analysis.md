# ソースコード解析書

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**この文書の位置づけ:** **コードを読まずに現状を把握するための技術俯瞰。**
オーナー（エンジニアでもある）が構成・設計・規模の成長を追うために置く。
**現在の設計判断そのものは [dev/features/](../dev/features/)・[dev/system/](../dev/system/) が正本**で、本書はその結果としての現況を述べる。
**そこへ至った経緯は [開発日誌](journal/) が持つ。**

**測定日:** 2026-09-18。統計は同日に現行ソースから再測定した。基準は `03cc839` で、作業ツリーは clean

## 目次

- [0. 規模の推移](#0-規模の推移)
- [0.1 検証状態（2026-09-18 時点）](#01-検証状態2026-09-18-時点)
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
  - [6.13 冊子モード（10枚束ねてめくる）](#613-冊子モード10枚束ねてめくる)
  - [6.14 ノートの分野判定と冊子の紙の色](#614-ノートの分野判定と冊子の紙の色)
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
  - [13.5 instrumentation の内訳（101件）](#135-instrumentation-の内訳101件)
  - [13.6 instrumentation が保証していない範囲](#136-instrumentation-が保証していない範囲)
- [14. コード品質評価](#14-コード品質評価)
  - [14.1 強み](#141-強み)
  - [14.2 残る技術的注意点](#142-残る技術的注意点)
- [15. 今後の改善候補](#15-今後の改善候補)
- [16. この文書の更新について](#16-この文書の更新について)

---
## 0. 規模の推移

| 指標 | 前回 2026-09-18 | 今回 2026-09-19 | 増減 |
|---|---:|---:|---:|
| 本番コード（ファイル） | 165 | **167** | +2 |
| 本番コード（行） | 29,102 | **29,780** | +678 |
| JVMテスト（ファイル） | 140 | **143** | +3 |
| JVMテスト（行） | 30,662 | **31,297** | +635 |
| JVMテスト（件数） | 1,525 | **1,556** | +31 |
| instrumentation（件数） | 99 | **101** | +2 |
| debug ソースセット（ファイル） | 4 | **4** | ±0 |

行数は空行・コメントを含む `wc -l` で、生成物と Gradle スクリプトは含まない。テスト件数は `@Test` の出現数。
本番の29,780行の内訳はコメント 8,284・空行 2,132・本体 19,364で、コメントの比率は28%である。

> 前回値の測定条件が今回と完全に一致する保証は無い。桁と傾向を見るための表であって、差分そのものを厳密な指標として扱わない。
> **今回の値は再現できる** — 下記のコマンドで数えている。

```bash
find app/src/main -name "*.kt" | wc -l                      # 本番ファイル数
find app/src/main -name "*.kt" -exec cat {} + | wc -l       # 本番行数
grep -rhE '^[[:space:]]*@Test' app/src/test | wc -l         # JVMテスト件数
grep -rhE '^[[:space:]]*@Test' app/src/androidTest | wc -l  # instrumentation件数
```

**今回の増分は4本立てである。** 09-14 以降の65コミットのうちコードに触れたのは19件で、残り46件は文書だけだった。

1. **要約の保存。** 本番の新規は `domain/SummaryCache`・`data/FileSummaryCache`・`ai/AiGenerationFailure`・
   `ai/GenerationRecordingAiClient` の4本。完成したプロンプトのSHA-256を鍵に `noBackupFilesDir` へ1件1ファイルで置き、
   同じ入力では生成し直さない。AICore が回数制限で断ったときは SDK の英文ではなく開き直しを促す。
   JVMテストは保存9件・ユースケース12件・生成の記録4件・回数制限の判定3件で28件増えた。§6.3
2. **自動生成の門番。** `controller/NoteDwellGate` の1本。ノートの本文が出てから続けて3秒表示されるまで、
   自動で走る4本の生成が Nano を呼ばない。門番11件・関連ノート1件と、調停の5件が増えた。§6.3・§6.4・§6.11・§6.14
3. **移動だけの分割。** 再会カードを `ReadingTraceController` から `ReunionCardController` へ、
   `BookletScreen` を画面本体・紙1枚・めくりの幾何の3本へ分けた。痕跡のテスト103件のうち32件が再会カード側へ移っている。
4. **ピッカーのID契約とコメントの検査。** 候補をIDで提示してIDだけ受理する形に変え、
   経緯の検査が読む字句を、文字列リテラルの中と区別できるようにした。7件と17件が増えた。§6.5

## 0.1 検証状態（2026-09-18 時点）

| | 結果 |
|---|---|
| `testDebugUnitTest` | **1,556ケース全件グリーン。** 134テストクラス・failure 0・skip 0・3秒 |
| `lintDebug` | **Error 0 / Warning 0**。hint 12件は依存更新系の催促で、ゲートには載せない。`--offline` の件数は Lint の最新版情報のキャッシュ次第で4件に減る |
| Kotlin コンパイル警告 | **0。** 警告はビルドを落とす設定 |
| マージ後マニフェストの権限 | 期待2件と一致。AICore への接続と、自己定義の受信権限だけ |
| `assembleDebugAndroidTest` | CI で通す |
| instrumentation 実行 | **全101件を一度に通した実行は無い。** 直近は機能単位で、09-13 に要約の基準線9ケースと全27組、09-12 に分野色・返事の保存・権限除去後の生成13プロンプトと連続10分の観測と機内モード。いずれも Pixel 10 Pro Fold・Android 17 |
| 実機検証 | 09-18 に要約の保存7ケースと自動生成の門番を通した。門番は本文表示から生成開始まで約3.0秒、初回の要約完了まで10.7秒。**製品コードへ変更は入っていない**（検証は一時プローブで行い、終了時に削除した） |

**実機確認が済んでいないもの:** ランチャー再タップの起動ガードのうち回転と Fold 開閉の1ケース、権限除去後のモデルDL、
Vigilith Phase 3 の目視。モデルDLは契機待ちで、確かめるために未DL状態を作りにはいかない。
**未対応の課題は [_wip/current_issues.md](../_wip/current_issues.md) が正本**で、現在は高0件・中3件・低5件・超低2件。

> **課題の出どころは5種類に分かれ、机上レビューが見つけたのは1件である。** 実機を触ったオーナーの体感、外部レビューの総評、
> コードを読んだ強化点の洗い出し、オーナーの費用検討、実機の計測テストで観測した境界。
> ランチャー再タップは実機検証が2週間前に踏んでいたのに手順を変えて回避したため台帳へ来ず、
> オーナーの端末で表に出た。**実機で人が触るまで開かない面がある**という構造は冊子で書いたものと同じである。

> **instrumentation が101件あることは、保証範囲が101件ぶん広いことを意味しない。**
> 主張が実際に試していることより広い箇所が3件ある。`ActivityScenario.recreate()` はプロセス死亡を覆わない、
> 端末AI生成は13本のプロンプトのうち5本、連打の主張は撤回済み。→ §13.6
>
> **実機検証の単位は「全件を一度に」ではなく「機能ごとのケース表」である。**
> `docs/review/device_validation/` に機能別ケースを置き、着手した機能のケースだけを通す。
> 全件同時実行を成功条件にしていないので、上の表も機能単位で読む。

---

## 1. エグゼクティブサマリー

Vigilith AI は、Android の Storage Access Framework（SAF）でユーザーが選択した Obsidian Vault を読み込み、Markdown ノートの閲覧・検索・関連ノート抽出・復習支援を行う Jetpack Compose アプリである。

AI機能はクラウドAPIではなく、ML Kit GenAI Prompt API を通じて端末内の Gemini Nano を使用する。現在実装されているAI機能は次のとおり。

- ノート全体の要約。**同じ入力の要約は端末に保存してあり、開き直したときは生成し直さない**
- wikilink・ファイル名規則と組み合わせた関連ノート推薦
- 自然文によるノート選択（AIピッカー）
- 読書中セクションの周辺テキストからの適応出題Q&A（○×／3択／4択）
- ノートへのひとこと: ノートを読んだ相手役としてAIが**1文だけ**返し、ユーザーが返事を書き、
  それを受けてAIが**1往復だけ**応じる（旧「AI補記メモ」を 2026-08-09 に全面作り直し）
- 表示中セクションの要約、質問候補生成、セクション限定Q&A
- 蒸留（Distill）: AIが原文箇所（文・長文の句・括弧内語句）を選び、ユーザー確認後に元ノートを `**太字**` へ書き換えるプログレッシブ要約支援。**太字にする範囲は候補ごとに選び直せる** — `語句`／`意味節`／`文全体` の3段と、端のつまみを引く自由範囲がある
- ReadingTrace: 10秒以上読んだノートの最深到達点をサイドカーへ記録し、Rediscover時に「前回のあなた」カードと読み方の俯瞰要約を表示
- 分野判定: ノートを開いたときに、本文の抜粋とフォルダ由来のヒントから固定リスト6分野のうち1つをIDで選ぶ。結果は冊子の紙の色になり、進捗も失敗も画面に出さない

非AIの補助機能を2つ持つ。**読書痕跡の書き出しと読み戻し**（データ管理画面）では、訪問記録・AI要約・ひとことと**ユーザーが書いた返事**を1ファイルへ退避でき、Vaultのフォルダが消えても読み戻せる（§6.12）。**冊子モード**（2026-08-30〜09-02）はランダムを1回押して10枚の束を作り、上へ繰る独立ルートで、各ページには本文から**抽出した**代表文1行（扉）が出る。**冊子ではAIも訪問記録も走らせない** — 記録と生成が始まるのは「これを読む」で通常表示へ渡ったときだけである（§6.13）。冊子の紙は通常表示と**面の形で**分けてあり、断ち切った紙の角・四辺の余白・背後に控える紙の縁を持つ。

Q&Aとひとことはバックグラウンド生成方式で、生成中もノート閲覧を継続でき、完了・エラーはSnackbarで通知される。**AIタブのバッジは「生成中」だけを示す** — ひとことの結果は専用画面で読むため、旧補記が持っていた「未確認」の概念（`isViewed`）ごと無くなった。ReadingTraceは対照的に、AI未準備・生成失敗を通知せず、生の痕跡だけを先に表示して黙って劣化する。AI以外の補助機能として、当日分のみの閲覧履歴（さがすタブ「今日読んだノート」）を持つ。

アーキテクチャは「単一 Activity + Compose Navigation + 単一 ViewModel」を入口としつつ、肥大化を避けるため要約・検索・セクションチャット・クイズ・ひとこと・旧補記ファイルの片付け・蒸留・読書痕跡・本文セクション解析・痕跡の整理／退避・冊子・分野判定を機能別 Controller（**14個**）に分割している。`NoteViewModel` は `Uri`・`ContentResolver`・`SharedPreferences` を扱うAndroid境界だけを担い、Controller間の調停と状態所有は Android API を呼ばない `NoteSessionCoordinator` が持つ。依存の組み立ては `NoteViewModelDependencies` へ外出しされている。ファイルI/Oは `NoteRepository`、AI判定を含む主要ロジックは UseCase、AI接続は `AiClient`、Markdown生成・応答パースは純粋ロジックへ分離されている。蒸留のVault書き戻しは `DistillWriteRepository` が専用の安全書き込み経路（ハッシュ照合・復旧レコード）を持ち、ReadingTraceは `_ReadingTraces` へのベストエフォートなサイドカー保存を持つ。**ひとことと返事もこのサイドカー（schema v6）へ入る** — 出力が1文になったので `.md` ファイルを作る形をやめた。

現時点の総評は次のとおり。

- 主要責務の分割、状態の一元管理、古いAI処理のキャンセル、生成タイムアウト、SAF走査キャッシュが実装され、継続的な機能追加に耐えやすい構造になっている。
- Markdownパーサー、**ひとことの応答検証**、クイズ応答パーサー、蒸留の文分割・採点・太字挿入・**範囲プリセットと自由範囲のスナップと重なり解消**、ReadingTraceのJSON・Controller・相対パス走査、**冊子の扉の抽出規則**、Vigilith起動・表示状態・状態別モーション・配置計算、明暗トークンのコントラストなど、壊れやすい純粋ロジックにはユニットテストが整備されている（**1,525ケース**。内訳は §13.1）。
- **自動で走る生成は、ノートに続けて3秒留まってから始まる**（2026-09-18）。要約・分野判定・再会カードの要約・関連ノートのAI推薦の4本が対象で、門番は調停側が1つ持つ。保存済みの要約と生の再会カードは門番の手前で出るので遅れない。
- ノート単位の Controller は requestId ＋ Job 追跡で古い結果の混入を防ぐ。Vault単位の要求（旧補記ファイルの一覧・削除・フォルダ一覧・痕跡の整理と退避・冊子の束）は寿命が違うため、共有の `vaultGeneration` を `update` 直前に照合する二層構成になっている。**痕跡の削除だけは世代照合に加えて、洗い出した時点の Vault識別子を保持して照合する**（キーが相対パスのハッシュのため、別Vaultの同名パスと衝突しうる）。
- 状態は `NoteUiStateStore` だけが所有し、各Controllerへは機能別の `*StateWriter` を渡すため、担当外フィールドへの書き込みはコンパイル時に不可能である。ノート切替のジョブ停止と状態リセットは `onNoteChanged()` の1手に閉じている。
- **見た目のチャネルにも割り当てを決めた**（2026-09-02、[bearing_channels](../dev/system/bearing_channels.md)・ADR-0005）。色＝年代／形＝面の役割（眺める・読む）／位置と本数＝分類／書体と行間＝何も表さない／動き＝出来事の強度。**佇まいで伝える案が4件並び、どれも「もう1本、色か形か動きを足す」形で同じチャネルを取り合っていた**ため、意味を1対1に固定した。形は役割トークンとして持ち、`BearingChannelTest` がどの面がどちらを引くかを固定する。
- パッケージ依存は `model` を葉とする一方向に整理され、`PackageDependencyTest` がimportを走査してCIで固定している。循環は残っていない。
- **SAF・画像復号・Compose描画・画面遷移・画素・端末AI生成を実機で通す instrumentation が 101件そろっている**（→ §13.5）。土台は `src/debug` のテスト用 `DocumentsProvider` で、本番の `NoteRepository` / `SafVaultBrowser` をそのまま動かす。**ただし保証範囲は主張より狭い** — 連打の主張は撤回済み、`recreate()` はプロセス死亡を覆わず、端末AI生成は13本のプロンプトのうち5本だけである（→ §13.6）。**CIでは実行しない**判断を 2026-08-08 に確定した（→ §13.3）。
- **ネットワーク権限は成果物でも持たない**（2026-09-12）。ML Kit GenAI が推移的に引く依存が `INTERNET`・`ACCESS_NETWORK_STATE` を持ち込んでいたので、マージ後マニフェストから除いた。`verify<Variant>ManifestPermissions` が期待集合と双方向で突き合わせ、CI は release も名指しで呼ぶ。
- ReadingTraceは主要経路とJVMテストが揃い、レビューで見つかった高優先度4件（ブロック数基準の到達率、Activity停止・再開、Vault切替中の起動済み保存、検索フォールバックの文言差）も解消済みである。ただしSAF照合とActivity lifecycleの実挙動はJVMテストの範囲外なので、実端末確認が完了判定に要る。
- 構造面の成長限界（依存の循環・ViewModelのテスト不能・状態の共有所有）は 2026-07-27 のB案で解消した。アクセシビリティとリリース構成は 2026-07-29〜30 のD案・E案で着手し、**ライトの文字トークンは実際に載る面すべてで4.5:1を満たす**ようになった。**下部ナビ帯の上でコントラストを取れなかったバッジ塗りは、ひとことの作り直しで対象ごと消えた**（未確認管理が不要になり、完了✓と失敗!の塗りが無くなった）。残る弱点は R8・署名が未設定であること、そして **instrumentation の実行がCIで担保されず「PR前に手で回す」運用のままであること**に移っている。**ユーザーが書いた返事の退避手段が無い**という弱点は、2026-08-23〜28 の書き出し／読み戻し（§6.12）で解消した。**「構造では分けたのに画面がその分離を見せていない」という弱点は、2026-09-02 の紙面の形（§6.13）で解消した。** ただし**そこで露わになった穴のほうは残っている** — 振る舞いの検査が全緑でも佇まいは判定されない。テストで埋める種類ではないので、実機検証のケース表が引き受ける形にした。**機能の看板に掲げた狙いにチャネルの持ち主がいない**という別の穴は、動きの行を二分して手触り側を冊子へ渡すことで閉じた。**AIの品質を測る手段が無い**という弱点は、2026-09-13 に要約の採点器と固定コーパスが入って半分埋まった。語の重なりしか測らず意味は見ないので、残り半分は実機の出力を人が読む工程が持つ。

---

## 2. プロジェクト規模と技術構成

### 2.1 コード規模

| 区分 | ファイル数 | 行数・件数 |
|---|---:|---:|
| 本番 Kotlin | 167ファイル | 29,780行。うちコメント 8,284・空行 2,132・本体 19,364 |
| ユニットテスト Kotlin | 143ファイル | 31,297行、1,556テスト。テストクラスは134で、残り9つは共有フェイク・共有ヘルパ |
| instrumentation テスト Kotlin | 16ファイル | 4,202行、101テスト。**全件を一度に通した実行は無い。** 直近は機能単位で実行している。内訳は §13.5 |
| debug ソースセット Kotlin | 4ファイル | 826行。instrumentation 用の偽SAFプロバイダと、要約の採点器・抜粋の変種・実機の計画。**release には入らない** |
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
| 権限 | マージ後マニフェストから `INTERNET`・`ACCESS_NETWORK_STATE` を `tools:node="remove"` で除く。期待する権限は AICore への接続と自己定義の受信権限の2つだけで、`verify<Variant>ManifestPermissions` が増減の両方を数える |
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
│   │   │   ├── AiGenerationFailure.kt          # 回数制限で断られた失敗だけを見分ける（BUSY＝ErrorCode 9）
│   │   │   ├── GenerationRecordingAiClient.kt  # 要約の生成を呼ぶたび logcat へ1行。**Debug APK だけ・要約にだけ渡す**
│   │   │   ├── PromptBudget.kt                 # 完成プロンプトの入力上限を1箇所で強制
│   │   │   └── PromptBuilder.kt                # 各機能のプロンプト構築（13本）
│   │   ├── controller/
│   │   │   ├── NoteSessionCoordinator.kt       # 14 Controllerの生成と横断調停・Vault世代・自動生成の門番（Android API非依存）
│   │   │   ├── SummaryController.kt            # ノート要約とモデルDL待ちの再開
│   │   │   ├── SearchController.kt             # フォルダ検索・スコープキャッシュ・requestId／Job
│   │   │   ├── SectionChatController.kt        # セクション要約・質問・Q&A
│   │   │   ├── QuizController.kt               # 適応出題Q&Aのバックグラウンド生成・確認状態
│   │   │   ├── RemarkController.kt             # ひとことの生成・検証・返事の保存・映し返し
│   │   │   ├── AnnotationController.kt         # 旧補記ファイルの一覧・削除のみ（Vault単位。生成は持たない）
│   │   │   ├── DistillController.kt            # 蒸留の候補提示・選択・保存・復旧の直列化
│   │   │   ├── ReadingTraceController.kt       # 読書セッション・能動読書時間の積算・訪問の保存
│   │   │   │                                   #   ＋ひとこと／返事の保存と、書けなかったぶんの退避
│   │   │   ├── ReunionCardController.kt        # 再会カードの照合・AI要約・「まだ考えたい」の印（痕跡と錠を共有）
│   │   │   ├── NoteDwellGate.kt                # 自動で走る生成の門番。本文表示から続けて3秒で開く
│   │   │   ├── ReadingTraceCleanupController.kt # 痕跡の孤児の洗い出しと削除（Vault単位）
│   │   │   ├── ReadingTraceBackupController.kt # 痕跡の書き出し・下見・読み戻し・中止（Vault単位）
│   │   │   ├── BookletController.kt            # 冊子の束（10枚）と扉の遅延読込・編む束（Vault単位）
│   │   │   ├── NoteFieldController.kt          # ノートの分野判定。ジョブはノート単位・索引はVault単位（二層を両方使う唯一の例外）
│   │   │   ├── ReadingPauseReason.kt           # 読書時間を止めている理由（背面／冊子。真偽1つで持たない）
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
│   │   │   ├── FileSummaryCache.kt             # 要約の保存。noBackupFilesDir に1件1ファイル・上限1000件
│   │   │   ├── NoteFieldStore.kt               # 分野の確定の永続。SharedPreferences に1件1キー・Vault名前空間・件数上限
│   │   │   ├── SafDocuments.kt                 # SAF子要素列挙・ルート機能フォルダの探索/作成
│   │   │   ├── VaultPathTraversal.kt           # Vault相対パス付きBFS（Android非依存）
│   │   │   ├── DistillWriteRepository.kt       # 蒸留のSAF安全書き込み（二重ハッシュ照合・原子確定）
│   │   │   ├── DistillRecoveryStore.kt         # 中断復旧レコード（noBackupFilesDir）
│   │   │   ├── DistillHashing.kt               # SHA-256（原バイト／出力の照合用）
│   │   │   ├── ReadingTraceJson.kt             # サイドカーJSON・canonical checksum
│   │   │   ├── ReadingTraceBackupJson.kt       # 退避ファイルの形式（読めなかったものを黙って捨てない）
│   │   │   └── ReadingTraceStore.kt            # 痕跡永続化境界・SAF Gateway・フォルダ索引・Vault照合
│   │   ├── domain/
│   │   │   ├── SummarizeUseCase.kt             # 要約ユースケース。保存済みを引き、無ければ門番を通して生成
│   │   │   ├── SummaryCache.kt                 # 要約の保存の宣言（実装は `data`）
│   │   │   ├── RelatedNotesUseCase.kt          # 規則ベース＋AI関連ノート抽出（多段パイプライン）
│   │   │   ├── SearchPickerUseCase.kt          # 自然文検索による3件選定
│   │   │   ├── SearchKeywordMatching.kt        # キーワード一致の採点・選抜（純関数）
│   │   │   ├── RelatedCandidateOrdering.kt     # 採番プレフィックス抽出（extractHexPrefix・共用）
│   │   │   ├── RelatedCandidateScoring.kt      # タイトル話題スコア（文字bigram Dice＋採番近接）
│   │   │   ├── RelatedContextScoring.kt        # 本文シグナル再ランク（tags/snippet/title）
│   │   │   ├── RelatedCandidateRanking.kt      # 採点戦略注入の汎用ランキング（rankByScore）
│   │   │   ├── RelatedCandidateContext.kt      # 候補の本文肉付け・入力予算内への整形
│   │   │   ├── ReunionCandidateScanner.kt      # 再会カード候補の列挙と種別決定（純関数）
│   │   │   ├── MarkdownPlainText.kt            # Markdownを人が読む文字列へ均す前処理（再会候補と冊子の扉が共有。**選定規則は共有しない**）
│   │   │   ├── BookletCoverLine.kt             # 冊子の扉（代表文1行）を本文から選ぶ純関数（生成しない）
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
│   │   │   ├── DistillRangeAdjust.kt           # 太字範囲の3段プリセット導出・保護範囲の取り出し・重なり解消（純関数）
│   │   │   ├── DistillRangeSnap.kt             # 自由範囲の端を置ける位置へ寄せる（書記素・装飾の対・空白）
│   │   │   ├── RemarkComposer.kt               # ひとこと／映し返しの応答検証・リンク差し戻し（純粋ロジック）
│   │   │   ├── QuizResponseParser.kt           # AIクイズ応答パース（純粋ロジック）
│   │   │   ├── QuizInputProfile.kt             # AI不使用の入力分類→出題形式決定（純粋ロジック）
│   │   │   ├── NoteTitleNormalizer.kt          # Obsidianタイトル正規化
│   │   │   ├── AiResponseParsing.kt            # AI返却タイトルの共通正規化
│   │   │   ├── LauncherEntry.kt                # ランチャー再タップの重複起動を判定する純関数（SDK定数は呼び出し側が渡す）
│   │   │   ├── NoteFieldHint.kt                # パスから分野のヒントを作る辞書照合
│   │   │   ├── NoteFieldIndex.kt               # 索引Aへヒントを流し込む規則。確定を消さない
│   │   │   ├── NoteFieldAnswer.kt              # 分野判定の応答を読む。行の全体がIDのときだけ受理
│   │   │   ├── NoteFieldInputVersion.kt        # 入力指紋。AIへ実際に渡したものすべてを含む
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
│   │   │   ├── DistillModels.kt                # 蒸留の純データ（範囲・文・チャンク・候補・確定範囲・保護範囲・DistillLimits）
│   │   │   ├── BookletTypes.kt                 # 冊子の1ページ（参照・タイトル・扉の3状態）。**本文は持たない**
│   │   │   ├── BookletWeave.kt                 # 編む束の中身と、トグルの3通りの見せ方
│   │   │   ├── NoteField.kt                    # 分野の固定リストと3値の分類（未判定・暫定・確定）
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
│   │   │                                       #   ReadingTraceCleanup/ReadingTraceBackup/Booklet/AiStatusNotice）
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
│   │       │   ├── BookletScreen.kt            # 冊子ルート（10枚の天綴じ `VerticalPager`・扉・これを読む・もう10枚引く）
│   │       │   ├── BookletSheet.kt             # 紙1枚の描画（扉・ノート名・縁）
│   │       │   ├── BookletSheetGeometry.kt     # めくりと積み直りの幾何（純関数）
│   │       │   ├── DistillRangeSheet.kt        # 太字範囲の調整シート（親文の表示・3段プリセット・自由範囲のつまみと微調整）
│   │       │   ├── QuizScreen.kt               # クイズUI（○×／3択／4択）
│   │       │   ├── RemarkScreen.kt             # ひとこと・返事・映し返しの専用画面（非タブルート）
│   │       │   ├── AnnotationManagerScreen.kt  # 旧補記ファイルの一覧・削除
│   │       │   └── SectionChatSheet.kt         # セクションAIボトムシート
│   │       ├── theme/
│   │       │   ├── AppShapes.kt                # **面の形の役割**（読む面＝角丸／眺める面＝ほぼ直角）。値ではなく役割で持つ
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
├── test/java/com/example/newproject/           # 143ファイル・1,556テスト（内訳は §13.1）
│   ├── architecture/PackageDependencyTest.kt   # importを走査してパッケージ依存の向きを固定
│   ├── architecture/SourceCommentShapeTest.kt  # KDocの連続と、本番コメントの日付・レビュー番号を落とす
│   ├── architecture/BackupExclusionTest.kt     # 端末に残す置き場がバックアップ除外に載っていることを走査
│   ├── testing/SummaryCoverage*Test.kt         # 要約の採点器そのものの検査と較正（4本）
│   ├── architecture/BearingChannelTest.kt      # 面の形の役割（どの面がどちらを引くか）を関数の本体で固定
│   └── ui/theme/VibrantTextUsageTest.kt        # 画面からのonVibrant直接使用と文字色のcopy(alpha)を禁じる
├── androidTest/java/com/example/newproject/    # 16ファイル・101テスト（内訳は §13.5）
│   ├── InstrumentationSetupTest.kt             # Runner起動・対象Contextのみ（Composeルールを持たない）
│   ├── ComposeRenderingSetupTest.kt            # Compose描画とEspressoのUI同期
│   ├── ai/PromptTokenBudgetTest.kt             # 端末AIのトークン計測と能力診断
│   ├── ai/OnDeviceGenerationTest.kt            # 本番プロンプトでの実生成（13本中5本）
│   ├── ai/SummaryCoverageBaselineTest.kt       # 固定コーパスを本番プロンプトで生成させ、採点器に掛けて記録（値は主張しない）
│   ├── data/VaultScanInstrumentationTest.kt    # 実物SAFでの走査・補記CRUD・読取失敗の注入
│   ├── data/NoteImageGatewayInstrumentationTest.kt # 実物BitmapFactoryでの復号と上限の境界
│   ├── ui/NoteReadingFlowTest.kt               # 描画抑止・全画面への位置引き継ぎ・進捗報告
│   ├── ui/ReadingTraceCardPanelTest.kt         # 再会カードの描画（種別・前置き）
│   ├── ui/QuizActionSectionTest.kt             # クイズ操作部の描画
│   ├── ui/DistillRangeAdjustUiTest.kt          # 範囲調整シートの操作・つまみと微調整・告知の出方
│   ├── ui/BookletScreenTest.kt                 # 冊子の描画（扉・めくり・終端・0件・読み上げ名）
│   ├── ui/BookletNavigationTest.kt             # 実NavHostでの冊子往復（ページ位置の復帰）
│   ├── ui/BookletSheetPerspectiveTest.kt       # 倒れた紙の向き・枠への収まり・めくりの裏を画素で確かめる
│   ├── ui/ActivityRecreationTest.kt            # Activity再生成（プロセス死亡は覆わない → §13.6）
│   ├── ui/TabNavigationTest.kt                 # タブ履歴契約（連打の主張は撤回 → §13.6）
│   └── assets/ai_corpus/                       # 要約の品質を測る固定コーパス9本と、実機出力のラベル・観測値
└── debug/java/com/example/newproject/testing/  # **release には入らない**
    ├── FakeVaultDocumentsProvider.kt           # instrumentation 用の偽SAF
    ├── SummaryCoverage.kt                      # 要約の採点器。内容語の包含率で、AIは使わない
    ├── SummaryExcerptVariants.kt               # 抜粋の作り方を比べる変種
    └── SummaryBaselinePlan.kt                  # 実機で何を生成し何を使い回すかの計画（純関数）

.github/workflows/ci.yml                        # PR・mainへのpushで testDebugUnitTest / lintDebug / マージ後マニフェストの権限検査（debug・release） / assembleDebugAndroidTest
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
       ├── ReadingTraceBackupController         ← Vault単位
       ├── BookletController                    ← Vault単位
       └── NoteFieldController                  ← ジョブはノート単位・索引はVault単位（例外）
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
DistillRangeAdjust / DistillRangeSnap /
NoteExcerptBuilder / NotePaperAge / MarkdownPlainText / ReunionCandidateScanner / BookletCoverLine /
SearchKeywordMatching / RelatedCandidate* / RelatedContextScoring /
KeyedMemoCache / ByteBudgetCache / BoundedInputStream /
ImageLinkParser / ImageIndexMatching / ImageDecodePolicy /
VaultPathTraversal / ReadingTraceJson / ReadingTraceOrphans / ReadingTraceMerge /
ReadingTraceBackupFileName / ReadingProgressGeometry /
VigilithMode / VigilithMascotMotion / VigilithOpeningMotion / VigilithPlacement /
NoteFieldHint / NoteFieldIndex / NoteFieldAnswer / NoteFieldInputVersion / LauncherEntry / BookletWeave
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
| ルート（`MainActivity` / `NoteViewModel` / `NoteViewModelDependencies`） | すべて。ただし**どの層からも参照されない** |

さらに **`model` / `domain` / `controller` の3層は `android.*` も import しない**（2026-08-01〜02）。
プロジェクト内の向きと同じく `PackageDependencyTest` がCIで固定する。`ui` を対象にしないのは
Compose 自体が `androidx.*` だから。`data` とルート（`NoteViewModel` / `MainActivity`）は
SAF・`ContentResolver` を実際に扱う境界なので依存してよい。

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
| `AnnotationController` | `AnnotationListStateWriter` | `annotationListState`（**Vault単位**） |
| `DistillController` | `DistillStateWriter` | `distillState`（`noteState` は読み取り専用の `currentNote()` で参照） |
| `ReadingTraceController` | `ReadingTraceStateWriter` | `readingTraceCard`（読書中Session自体はController内部） |
| `ReadingTraceCleanupController` | `ReadingTraceCleanupStateWriter` | `readingTraceCleanupState` |
| `ReadingTraceBackupController` | `ReadingTraceBackupStateWriter` | `readingTraceBackupState` |
| `RemarkController` | `RemarkStateWriter` | `remarkState` |
| `BookletController` | `BookletStateWriter` | `bookletState`（引く束と編む束、それぞれのページ位置。**Vault単位**） |
| `NoteFieldController` | `NoteFieldStateWriter` | `noteFields`（分野の索引A。**Vault単位**。ノート切替では触らない） |

Writer を持たない `noteState`・`relatedNotesState`・`wikilinkTitles`・`todayHistory`・`notePaperTone`・`vaultSelected` は `NoteSessionCoordinator` 専用のメソッドで更新する。関連ノートは走査キャッシュ（`Uri` を持つ `NoteFile`）に依存して `NoteViewModel` 側に残っているため、Controller化されていないのがこの非対称の理由である。

表示テーマ（`darkTheme`）と**紙の経年表示（`notePaperAging`）**は `NoteUiState` に含めず、`NoteViewModel` が独立した `StateFlow<Boolean>` として持つ。`MainActivity` はこの2つだけを `AppTheme` の外で購読するので、他の状態が変わってもアプリ最上位までは再評価されない。

`NoteUiState` の外にあるのは設定2つと `NoteSectionModel` で、正本は本数ではなく理由で数える。設定は再コンポーズ範囲、`NoteSectionModel` はパッケージ境界が理由で、危ないのはノート単位の状態が外へ出ることだけである。「3つ目で見直す」という本数の合図は 2026-09-12 に撤回した。3つ目は既に出ていて、鳴っても拾われなかったからである。

### 4.3 状態モデル

`NoteUiState` は次の状態を集約する。

- `vaultSelected`: Vault選択済み表示用フラグ
- `noteState`: Idle / Loading / Success / Empty / Error。`Success` は表示用のタイトル・本文に加え、蒸留の書き戻し用に `targetUri`・原バイトの `originalHash`・蒸留不可理由（`distillUnavailableReason`）を保持する
- `summaryState`: Idle / Loading / Success / Downloading / AiUnavailable / Error
- `relatedNotesState`: Idle / Loading / Success。**失敗の枝は持たない** — 関連ノートはAIが失敗しても規則ベースの候補を返す設計なので、構築されない `Error` を 2026-09-14 に読み手の分岐ごと消した
- `quizState`: Idle / Loading / Success / Error（Loading以降は `sourceTitle`、Success/Errorは `isViewed` を保持）
- `annotationState`: Idle / Loading / Success / Error（同上）
- `annotationListState`: Idle / Loading / Success / Error
- `distillState`: Idle / Analyzing / AiNotice / Downloading / Unavailable / Candidates / Saving / Saved / Conflict / RecoveryRequired / RecoveryResolved / Error（他機能より状態数が多いのは、AI生成に加えVault書き戻しの競合・中断復旧まで表現するため）。`AiNotice` は端末AIの状態の説明、`Unavailable` は**ノート側の理由**（本文が大きすぎる等）で、別物である。`Candidates` は 2026-08-29 に**範囲調整**を抱えた — 候補ごとの親文・確定範囲・選べるプリセット（`Term`/`Clause`/`Sentence`）・調整済みフラグと、開いているシートの候補ID・重なり解消で外された候補IDを持つ
- `bookletState`: Idle / Loading / Open / Failed。**`Open` が引く束と編む束を `BookletBundle` として2つ持ち、それぞれがページ位置を抱える。** 編む側は種が無い・編めない・編めるの3状態で、トグルの見せ方がそのまま型に写っている。寿命が同じものを同じ状態に置く判断で、ページ位置を画面ローカルに置いていた版は実機の `冊子 → ノート → 戻る` でページ位置だけ失われた。空の束が「Vaultにノートが無い」で、別のvariantを作らない。**Vault単位なのでノート切替では消えない**
- `noteFields`: 分野の索引A。`DocumentRef` ごとに未判定・暫定・確定の3値を持つ。Vault走査がヒントを暫定として流し込み、ノートを開いたときのAI判定が確定へ昇格させる。**Vault単位**なのでノート切替では消えず、Vault切替でだけ落ちる
- `readingTraceCleanupState` / `readingTraceBackupState`: 孤児痕跡の洗い出し・削除と、痕跡の書き出し・下見・読み戻し・中止（どちらもVault単位）
- `notePaperTone`: 放置期間をVault内の相対順位で写した紙の地色の段階
- `readingTraceCard`: Rediscoverで過去の痕跡が見つかった場合だけ入る「前回のあなた」カード。訪問回数・前回日時・最深セクション・到達率・AI俯瞰要約・読み込み中・現在表示中だけのdismiss状態を持つ
- `sectionChat`: セクションAIのセッション。ノート内でセッションを持たない場合は `null`
- `isSectionChatSheetVisible`: シートの表示有無。セッションの有無と分離しており、閉じても同じノート内なら生成結果を保持して吹き出しから再表示できる
- `folders`、`selectedFolder`、`searchState`: 検索タブ用
- `wikilinkTitles`: 現在ノートから抽出したリンク先タイトル
- `todayHistory`: 当日分の閲覧履歴（最大10件）

表示テーマは `NoteUiState` の外に出ており、`NoteViewModel.darkTheme`（独立 `StateFlow<Boolean>`）が持つ。OS設定には追従せず、オプション画面での明示切替だけで変わる（`SharedPreferences` に永続化）。

`quizState`/`remarkState` の `sourceTitle` は「どのノートの生成結果か」を表し、Snackbar通知の判定に使う。`isViewed`（未確認管理）を持つのは `quizState` だけで、**ひとことは結果を専用画面で読むため持たない**。通知の発火判定キーは `toEventKey()` 拡張関数（`model/state/`）が組み立てる。

各 sealed state は `model/state/` 配下の機能別ファイルに分かれ、集約する `NoteUiState`（22フィールド）だけが `model/NoteUiState.kt` にある。

ノートまたはVaultの切替時は `NoteUiStateStore` の `withNoteScopedReset()` により、要約・関連・クイズ・ひとこと・セクションチャット・再会カードを一括リセットする。Vault切替時だけ `withVaultScopedReset()` が追加で走り、検索スコープ・閲覧履歴・旧補記一覧・痕跡の整理／退避・**冊子の束**・**分野の索引**を落とす。**冊子をノート単位に登録しないのは機能の目的そのもの** — 「これを読む」でノートへ渡って戻れば同じ10枚が同じページで残ることが冊子の定義だからである。`noteState` と `wikilinkTitles` はVault切替でも残す — 切替直後に `loadRandomNote` が走って差し替わるため、ここで落とすと画面が一瞬空白になるだけだからである。

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
| 全画面 | `booklet` | 冊子（10枚を上へ繰る。扉と「これを読む」） |
| 全画面 | `quiz` | クイズ（○×／3択／4択を入力量から自動選択） |
| 全画面 | `remark` | ひとこと・返事・映し返し |
| 全画面 | `data_management` | 痕跡の書き出し／読み戻しと、整理・旧補記の片付けの入口 |
| 全画面 | `reading_trace_cleanup` | 孤児になった読書痕跡の洗い出しと削除 |
| 全画面 | `annotation_manager` | 旧補記ファイルの削除管理 |

`navigateToTab()` は `popUpTo`、`saveState`、`restoreState`、`launchSingleTop` を使い、トップレベルタブのバックスタック増殖を抑えつつ状態を復元する。

**冊子から「これを読む」で渡るときだけ `navigateToTab()` を使わない。** 期待するバックスタックは
`note → booklet → note` で、タブ遷移で渡すと冊子ルートごと畳まれ、「戻れば同じ10枚」が成立しなくなる。
この形は `BookletRouteContractTest`（JVM・ソース走査）と `BookletNavigationTest`（実NavHost）の2本で固定してある。
**プロセス復元で冊子ルートだけが戻ったときはノートタブへ戻す** — 束はメモリ上にしかないので、
復元された冊子ルートには中身が無い。

### 5.2 画面幅対応

`AppScaffold` は `WindowSizeClass` を参照し、Expanded幅では左側 `NavigationRail`、それ以外では下部 `NavigationBar` を使用する。選択タブのインジケータは `Aqua`（Indigo地に埋もれないアクセント）。全画面ノート・冊子・クイズ・ひとこと・データ管理・痕跡の整理・旧補記管理の全画面ルートではタブUIを表示しない。全画面ノートは進入中にシステムバー（ナビ＋ステータス）も隠し、離脱時にナビバーのみ復元する（ステータスバーはアプリ全体仕様どおり隠したまま）。

### 5.3 画面ごとの責務

#### ノートタブ

- ランダム表示（Vault選択ボタンは未選択時のみ表示。切替はオプションから）
- 主ボタンの隣の副ボタン **📖** で冊子ルート（`booklet`）へ。10枚の束を作って上へ繰る（§6.13）
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
- Reflect（蒸留）の起点。AIが選んだ重要箇所を候補リストで提示し、ユーザーが確認した箇所だけを元ノートへ `**太字**` として書き戻す（§6.10）。候補をタップすると調整シートが開き、**太字にする範囲を3段のプリセットか、端のつまみを引いて選び直せる**
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

なお `MainActivity` は `setContent` 直後に、コールド起動時のみ `OpeningScreen`（起動OP）を本体の代わりに表示する。新規起動の判定は `savedInstanceState == null`（回転・Fold開閉・プロセス復元では非nullのため再生しない）。OP終端の背景は着地（Noteタブ）と同じ `ReadingGradient` に揃え、継ぎ目なく本体へ入れ替える。詳細は [opening_animation](../dev/features/opening_animation.md) を参照。

**ランチャーの再タップは `onCreate` の先頭で畳む**（2026-09-09）。`launchMode` を持たないので、タスクの基点 Intent が `MAIN`＋`LAUNCHER` 以外で作られていると、ランチャーが投げる Intent は既存タスクと一致せず新しい `MainActivity` が積まれていた。積まれるたびに OP が再生され、戻るボタンでアプリを抜けられなくなる。判定は `domain/LauncherEntry.kt` の純関数で、タスクの最初の1枚でなく受け取った Intent がランチャーのものなら `finish()` して既存タスクへ委ねる。`launchMode` は変えない。変えるとこのガードが呼ばれないまま死ぬので、`LauncherEntryTest` がマニフェストを見て前提を固定する。実機検証は `am start -n` を意図して残し、ガードが呼ばれる状態を作る。回転と Fold 開閉の1ケースだけ実機未確認。

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
  ├── fetchRelatedNotes()
  └── 分野判定。索引に現在の入力版の確定が無いときだけ（§6.14）
```

VaultにMarkdownがなければ `NoteState.Empty`、読み込み失敗は `NoteState.Error` となり、ノート画面では一般化したエラー文、同時に Toast で例外メッセージを表示する。

### 6.3 ノート要約とモデルダウンロード

`SummarizeUseCase` はAIの状態を確認する。

| AI状態 | 動作 |
|---|---|
| `Ready` | 1,200文字以内の本文抜粋を含むプロンプトで2〜4文を生成（予算内は原文、超過時は骨格＋冒頭＋末尾。§8.4）。**保存済みがあれば生成しない** |
| `NeedsDownload` | モデルダウンロードを開始し進捗を `SummaryState.Downloading` へ反映 |
| `Downloading` | **何もしない。** 走行中のDLへ合流できないので、次にノートを開いたときに取り直す |
| `Unsupported` / `TemporarilyUnavailable` | `SummaryState.AiUnavailable`。現在のUIでは要約パネル自体を表示しない |
| 生成失敗 | `SummaryState.Error` |

ダウンロード完了後は保持していたタイトル・本文で要約と関連ノート検索を再実行する。

**同じ入力の要約は端末に保存してある**（2026-09-17）。鍵は完成したプロンプトのSHA-256で、置き場は `noBackupFilesDir/summary_cache/`。1件1ファイル・上限1000件で、古いものから消える。生成は決定的なので、保存済みを出しても画面に出るものは変わらない。**変わるのは待ち時間と錠の占有だけ**である。当たったかどうかは保存のファイルでは判別できないので、Debug APK では要約の生成を呼ぶたび logcat へ1行出して数える（→ [note_summary](../dev/features/note_summary.md) §10）。

**生成は、ノートに続けて3秒留まってから始まる**（2026-09-18）。保存済みを引くところまでは門番の手前で行うので、開き直したノートの要約は待たされない。→ [background_ai_ux](../dev/system/background_ai_ux.md) §7

**AICore は短い時間窓の回数で要求を断ることがある。** 2026-09-13 の計測テストで、12回成功した直後の13回目が `ErrorCode 9 / BUSY` で拒否された。観測した境界であって固定の上限ではない。断られたときは SDK の英文ではなく「端末のAIが混み合っています。少し待ってからノートを開き直してください。」を出す。判定は `isAiCoreBusy` が持ち、長期の利用枠の超過は含めない（少し待っても通らないため）。

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
5. **ノートに続けて3秒留まるまで待ってから**（§6.3）、**AIにはIDだけ返させ**、行頭付近のIDのみ抽出して実ノートへ解決する（`parseCandidateIds`）。決定的結果とのURI重複を除いて最大5件返す。決定的チャンネルもこの応答と一緒に返るので、関連タブの表示はその分だけ遅れる。

AIが利用不可またはモデル未準備でも、規則ベース結果は表示できる。AI生成で例外が起きても規則ベース結果だけを返す設計で、失敗の枝そのものを持たない。自動起動の機能なので理由は見せない。宣言だけ残っていた失敗型は 2026-09-14 に、関連タブのエラー表示と冊子の「編めない理由」まで連鎖ごと消した。個別候補の本文読込失敗（キャンセル以外）は該当候補のみタイトルで続行し、推薦全体を巻き添えにしない。

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
4. **範囲調整（2026-08-29〜30、N-14 段階1）:** 候補は「重要か」と「どこからどこまで太字にするか」を分けて持つ。`presetRangesFor`（`DistillRangeAdjust`）が候補の親文から `語句` / `意味節` / `文全体` の3段を導出し、**存在する段だけ**をシートに出す（分割の無い文では意味節と文全体が1つに畳まれる）。すべてのプリセットは親文の範囲（`contextRange`）の内側に収まることを型の生成時点で強制する。範囲を広げると他の選択候補と重なりうるので、`resolveOverlaps` が**範囲変更時とチェック時の両方**で相手の選択を外し、外した理由を候補へ印として残す。**未調整なら保存結果は v1 と完全に同じ**で、調整は増える口であって増える手順ではない。**調整結果は永続化しない** — 覚えるのはノート本文に入った `**` だけである。
5. `applyDistillBold` がオフセット降順で `**` を挿入（装飾記号の挿入のみ・削除なし）。**重なる範囲は `require` で拒む**ので、非重複は 2. の出口と `resolveOverlaps` が保証する。累積太字上限は編集対象本文比率30%（既存太字も分母/分子に含む）、短文は最重要1箇所の例外あり。候補には文・句・語句が混ざるため、画面の数え方は「箇所」で、状態名も単位を持たない（`isSingleCandidateException`・`Saved.changedCount`）。
6. 保存は `DistillWriteRepository`（`DistillPersistence`）：原バイトSHA-256の二重競合確認 → キャッシュ構築＋fsync → 復旧レコードを `noBackupFilesDir` に原子確定 → SAF `"wt"` 一気書き → 出力ハッシュ検証。中断時は起動時に4分岐で復旧判定し、v1最小復旧UI（現在維持／元へ復元／別ファイルへ書き出し）を出す。空き容量が不明/不足なら中断。
7. 保存後は `openNote()` を使わず**本文専用リロード**（`reloadNoteBody`／`withDistillBodyReloaded`）：要約・関連・ひとことは維持し、生Markdown文脈に結び付くセクションチャット・クイズは破棄する。`DistillController` が requestId で全フローを直列化し、ノート切替でキャンセルする。

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

**続く範囲調整（段階1）は 2026-08-30 に `DIST-21`〜`DIST-24` の4件と instrumentation 6/6 を実機で確認した。**
このとき2つの欠陥が出ている — **同じ確定範囲を再適用すると重なり解消の理由が消える**（変更が無いとみなして印を落としていた）、
**外された候補のシートを開くと告知の主語がずれる**（自分が外した相手のことを言うべき場面で「ほか」と言っていた）。
どちらも「範囲は正しいが、なぜ外れたかが伝わらない」形で、**保存結果は正しいまま壊れる。**
テスト側は確定範囲の強調を**値として観測する**形（`DistillRangeHighlightTest`）へ直し、外す変異で落ちることを確かめてある。
**続く自由範囲は 2026-09-19 に実装した。** 確定範囲の両端につまみが出て、親文の内側なら任意の範囲を選べる。
3段は残してあり、自由範囲は置き換えではなく足した口である。**端を置いてよい位置は親文ぶんを全列挙して決める** —
押し出す・寄せる・空白を落とすを順に当てると、1つ目の補正が2つ目の禁止域へ落とす形が残るためで、
親文は最大160文字なので全列挙のほうが安く済む。止まれないのは書記素の内側と、装飾の対を片側だけ割る位置である。
**書記素の判定は自前で数えた。** `BreakIterator` は端末とデスクトップJVMで ICU の版が違い、
同じ入力へ別の答えを返すのでJVMテストで固定できない。
**指では1文字の精度が出ない**ので、端を1境界ずつ動かす微調整ボタンを併置した。
**実機は未確認で、つまみの掴みやすさとシートの縦送りとの取り合いが残っている。**
正本は [reflect_distill](../dev/features/reflect_distill.md) §5 で、
段階を分けて書いていた `distill_range_adjust` はそこへ畳んで削除した。

### 6.11 ReadingTrace（読書痕跡）

全経路で読書位置を自動記録し、Rediscoverで同じノートを引いた時だけ過去の読み方を再会カードへ出す。設計判断は [features/reflect_reading_trace.md](../dev/features/reflect_reading_trace.md)。

**持ち主は2つに分かれている**（2026-09-15）。訪問の記録・ひとことと返事の保存は `ReadingTraceController`、再会カードの照合・AI要約・印は `ReunionCardController` が持つ。**UI状態を書くのは後者だけ**で、両者はサイドカーの read-modify-write を直列化する錠を共有する。

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
  → 種別を決めて（問い ＞ 古い前提 ＞ 俯瞰要約）、ノートに3秒留まるのを待ってから Gemini Nano を**1回だけ**呼ぶ
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

到達率は `(最深ブロックindex + そのブロックの可視割合) / 総ブロック数` の切り捨てで、100%は最終ブロックの末端が画面へ入った場合だけに成立する。読書時間は背面にいた分を除いた能動時間で測り、背面化で書いた訪問は復帰後の読み進めで差し替える（1回の閲覧＝1訪問）。

**計測の停止は 2026-08-30〜31 に「理由の集合」へ変えた。** 止める契機が背面化（`onStop`）だけだった間は真偽1つで足りたが、冊子ルートも止める側に加わり、**冊子を開いたまま背面へ回ると2つが同時に成り立つ**。真偽1つのままだと**片方が解けただけで計測が再開する**ので、`ReadingPauseReason`（`AppBackground` / `Booklet`）の集合を持ち、**計測は「理由が1つも無いこと」から導出する**。同じ巡で、停止中に始まった読書セッションを計測開始済みとして扱っていた欠陥も直した。保存・照合要求はノートを開いた時点のVaultキーを運び、Gatewayが書込直前に現在のVaultと照合して不一致なら捨てる。

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

### 6.13 冊子モード（10枚束ねてめくる）

ランダムボタンを1回押したら10枚を用意し、上へ繰る。**自主制作の小冊子（ZINE）を繰る手触り**を狙った、
2026-08-30〜09-01 の追加機能である。起点は「毎回ボタンを押すのが面倒」というオーナーの実感。
設計判断は [features/booklet_mode.md](../dev/features/booklet_mode.md)。

```text
ノートタブ
 ├─ [1枚ひらく]      → 通常表示（要約・痕跡・履歴をここで開始）
 └─ [📖 冊子をひらく] → 冊子ルート（非タブ。AI・痕跡・履歴は動かさない）
                          └─ これを読む → 通常表示（**ここで初めて**開始）
```

1. `shuffled().take(min(10, ノート数))` で束を作る。**束の中では重複させない**（`random()` の10回呼びではない）。
   持つのは参照・タイトル・扉の1行だけで、**本文は持たない**（本文は最大1MBまで許容しているので10枚ぶんを抱えられない）。
2. 扉（代表文1行）は本文から**選ぶ**（抽出）。生成しないので即時に出せる。読み出しは**現在ページと前後1ページ**に限り、
   本文全体ではなく**8KBの境界読み出し**（関連ノートが最大40件を並列で読むのに使っている経路）を使う。
   抽出規則は `selectCoverLine` が持ち、frontmatter・見出し・コードフェンス・表の区切り・罫線・リンクだけの行を落とし、
   1行に複数の文があれば最初の1文だけを全角40字で切る。**選べる文が無ければタイトルを出す**（空の扉は作らない）。
3. 読めなかったページは**そのページだけ**を失敗として見せる（束は作り直さない）。
   扉の状態を `Loading` / `Ready` / `Failed` に分けているのはこのためで、分けないと消えたノートのページが永久に読み込み中で残る。
4. 10枚を使い切ったら末尾に「もう10枚引く」ページを置く。**自動では継ぎ足さない。**
   **直前の束と同じノートが出てよい** — 束をまたぐ重複を避けると「引きに条件を足す」ことになる。
5. 「これを読む」で通常のノート表示へ渡り、**そこで初めて**訪問記録・要約・関連ノートが始まる。

**束の寿命はVault単位。** ノートから戻れば同じ10枚が同じページ位置で残り、束が消えるのは
アプリ再起動・Vault切替・「冊子をひらく」の押し直し（作り直し）だけである。
**別タブを押したときに畳まれるのは冊子ルートであって束ではない**（`navigateToTab` の `popUpTo` による）。
プロセス復元で冊子ルートだけが戻ったときは、束が空なのでノートタブへ返す。
**ページ位置を束と同じ `BookletState.Open` に置いているのは寿命が同じだから** — 画面ローカルの
`rememberPagerState` に置いた版は、実機の `冊子 → ノート → 戻る` で**ページ位置だけが失われた**（2026-08-31）。
「戻れば同じ10枚が同じページ位置で残る」は1つの受け入れ条件なのに、それを寿命の違う2つの状態で
実現していたのが原因である（→ [lessons](../dev/lessons/L56.md) L56）。

**冊子では新しいAI・痕跡・履歴を開始しない。** 契約は「**冊子候補について**新しいAI・痕跡・履歴を開始しない」であって、
冊子へ入る前から走っている処理を止めるものではない。ただし**読書時間の計測だけは止める**
（冊子ルートが前面にある間はノートが表示されていないため）。これが §6.11 の `ReadingPauseReason.Booklet` にあたる。
冊子へ戻ったときは、走行中の「これを読む」を `cancelBookletRead()` で取り消す。

**紙面は通常表示と形で分けてある（2026-09-02）。** 実機検証で「冊子が通常表示の“中身が薄い版”に
見える」と分かったための追加で、**足したのは情報量ではなく形**である（本文の抜粋を増やすのは
「冊子を小さなリーダーにする」方向で、設計が明確に否定している）。

| 要素 | 冊子（眺める面） | 通常表示（読む面） |
|---|---|---|
| 角 | **2dp**（断ち切った紙） | 8dp（アプリのカード） |
| 面の広がり | 四辺の余白を見せ、**下に地を残す** | 全幅・スクロールで続く |
| 背後 | **紙の縁が2枚**覗く | 無し |

**この3つは1つの役割トークンから引く。** 値を直に書いていた間は、区別が
「揃えたほうが綺麗」の一言で消える状態にあった。役割の違いとして持てば走査で固定できる（→ §13.1）。
**縁が言うのは「この紙は束の1枚である」ことだけで、残り枚数ではない** — 束の10枚には位置によらず
同じ縁が出て、「もう10枚引く」ページにだけ縁が無い。残数はページインジケータの文字が持つ。
**新しい色トークンは作っていない**（縁は既存の一段沈む面を引く。明暗どちらでも紙より暗い側にある）。

**実機検証は 2026-09-01 に本体を、2026-09-02 に紙面の佇まいを完了した**（後者は明暗・全10枚／3枚境界・
終端・狭幅で5/5成功）。**分割画面だけ未実施。**

**綴じは天綴じ、めくりは折り目が斜めに走る（2026-09-04〜06、実機受理 09-08）。** 紙は上端を蝶番にして倒れるが、
倒れるのは束を引き直した積み直りのときだけで、最大22度である。めくりは角度を持たない。
右下の角を持ち上げると折り目が左上へ向かって斜めに走り、折り返した紙は折り目を鏡にした像として裏を見せる。
紙全体は動かず、折り目の向こうは1ピクセルも動かない。曲がって見えるのはめくれた角が落とす影による。
指を離したあとのスナップは 620ms で減速して終わる。ばねではなく時間で走らせるのは、どこで離しても同じ速さで走り切るほうが紙らしいためである。
OS の「アニメーションを無効」設定は経路で意味が違う。指に追従する変化はそのまま出て、時間で進む送りと積み直りは省かれる。
紙を弓なりに曲げる版を先に作って実装も検査も通したうえで否定された経緯は [開発日誌](journal/2026-09.md) が持つ。

**編む冊子（2026-09-07、実機受理 09-08）。** 冊子へ入る直前に開いていたノートを種に、
済んだAI推薦から2つ目の束を作る。追加のNano呼び出しもI/Oも無い。並べ方はAI推薦・未リンク・wikilink済みの順で、
重複は参照で畳み、種自身は外す。薄いときは水増しせず `min(10, 候補数)` をそのまま出す。
2つの束はそれぞれページ位置を持ち、行き来しても両方残る。冊子の中のトグルは3通りで、
種が無ければ出さず、種はあるが編めなければ理由を1行添えて押せない形で出し、編めれば両方押せる。
「まだ探しています」と「見つかりませんでした」は待てば変わるかどうかが違うので、1文に畳まない。
編む側の終端は枚数だけを告げる紙で、「もう10枚編む」は置かない。編みは決定的なので押しても同じ10枚が出る。

**紙の地色はノートの分野で塗り分ける（2026-09-10〜12）。** 色を使えるのは冊子の面だけで、読む面の地色は年代が持つ。
判定の流れは §6.14。

### 6.14 ノートの分野判定と冊子の紙の色

冊子の紙をノートの分野で塗り分ける。起点はオーナーの体感「冊子が真っ白になるのがなんか微妙」で、
設計判断は [features/note_field_color.md](../dev/features/note_field_color.md)。**色は6つで固定**し、追加語彙は親の色を継ぐ。

```text
Vault走査
  → パスの各セグメントを一般語彙と辞書照合し、当たればヒントとして索引Aへ暫定で載せる（メモリのみ）
  → 永続から読んだ確定は暫定を上書きする。ただし入力版が失効した確定は補完材料から外す

ノートを開く
  → 索引Aに現在の入力版の確定があれば何もしない
  → 無ければ索引B（入力指紋 → 答え）を引く。当たればAIを呼ばずに確定を復元する
  → どちらも無ければ、ノートに3秒留まるのを待ってから、抜粋（600文字）とヒントを添えてAIへ渡す
  → 応答は F1〜F6 か NONE。行の全体がIDで、応答全体がID行だけのときだけ受理する
      分野を1つ返した … 確定として索引Aへ永続し、索引Bにも載せる
      NONE            … 確定（分野なし）として同じ扱い。無彩色
      失敗・非対応     … 暫定のまま。永続しない。進捗も失敗も画面に出さない
      キャンセル       … 何も書かない

冊子を開く
  → 束10枚の紙が索引Aの現在値で塗られる。未判定は無彩色。索引が届けば保持中の束にも色が届く
```

**分類は3値で持つ。** 未判定・暫定・確定は同じ入力版の中では一方向で、本文・ヒント・語彙・プロンプト版のどれかが変われば
入力版が変わり、確定でも再判定する。失効を見つけた時点で古い確定はヒントの暫定へ落とす。
永続には触らないので、本文を元へ戻せば索引Bが当たって生成なしで確定へ戻る。

**入力指紋はAIへ実際に渡したものすべてを含む。** タイトル・抜粋・予算・ヒント・語彙の版・プロンプト版。
どれかを落とすと「変えても再判定されない」バグになる。タイトルを含めるのは、同じテンプレート本文を持つ異題ノートが
同じ鍵へ畳まれるのを防ぐためで、代償として改名すると再判定になる。
この鍵の設計は、要約への入力指紋キャッシュの先例として機能アイデアが参照している。

**同じ索引に書き手が3つある。** Vault走査・ノートを開いた経路・永続の読込。降格を直しただけでは走査が
永続から読んだ確定を次の走査で戻した（→ [lessons L66](../dev/lessons.md)）。判定できない側に判定させず、
判定できるノートを開いた経路が補完材料のほうを削る形で閉じた。書き込みは1箇所へ直列化し、確定を消さない。
有効な確定のときは索引Aへ書かない。書けば画面が再描画され、開くたびに色が一度ヒントへ点滅する。

**Controller は失敗をユーザーへ一切見せず、状態も持たない。** 結果は索引Aの色として出るだけで、「判定中」の表示すら無い。
モデルDLも自動で始めない。ジョブはノート単位の契約に登録して切替で止め、索引はVault単位なのでノート切替では触らない。
二層を両方使う唯一の例外として [architecture](../dev/system/architecture.md) 判断4 に書いてある。

**確定は `SharedPreferences` に1件1キーで永続する。** Vault の名前空間を鍵の先頭に持ち、件数上限を超えたら新規は書かない。
起動復元でも永続を読み、索引Bを作り直す。バックアップからは除外している。
分類精度と全表示幅は未保証で、Vault別の追加語彙は設計書 §11 が持つ。

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

- 読み込みは `openInputStream()` から**上限つきのバイト読込**（`BoundedInputStream`）で受け、
  `dropIncompleteUtf8Tail()` で末尾の欠けた多バイト文字を落としてから UTF-8 で文字列化する。
  **`bufferedReader()` で全文を読む経路はもう無い** — 用途ごとの読込予算（→ §8.4）が入った時点で置き換わった。
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

**錠の外側に、AICore 自身の回数制限がある。** 2026-09-13 の計測テストで、同じアプリから推論を連続で投げると12回成功した直後の13回目が `ErrorCode 9 / BUSY` で拒否された。拒否は直前の成功の32ms後で、モデルは動いていない。窓の長さは AICore の内部にあり特定しない。同じプロンプトを2度生成しないよう実機の計画を純関数で持ち、続きから採れるようにしてある（→ [ai_quality_measurement](../dev/system/ai_quality_measurement.md) 判断8）。

**錠の手前にも門番がある。** 自動で走る4本（要約・分野判定・再会カードの要約・関連ノートのAI推薦）は、ノートの本文が出てから続けて3秒表示されるまで `generate()` を呼ばない。すぐ捨てるノートで錠を取らないためで、押して使う機能は待たせない（→ [background_ai_ux](../dev/system/background_ai_ux.md) §7）。

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
| 分野判定 | 600文字 | フォルダ由来のヒントを添え、固定リスト6分野から **ID応答で1件**。該当なしは `NONE` |

上限はいずれも UTF-16 文字数で、トークン数や意味境界では切っていない。**ただし切り方は先頭固定長ではない**（2026-07-28 に変更。設計は [ai_input_excerpt](../dev/system/ai_input_excerpt.md)）。

- 上表の本文を持つ8経路は、`PromptBuilder` ではなく呼び出し側が `buildNoteExcerpt(content, 上限)` で `NoteExcerpt` を作って渡す。`PromptBuilder` に `take()` は残っていない。
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
**13本すべての builder がここを通る。**

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

Controller側は次のとおり。`SummaryController` は要約JobとモデルDL Jobに加えて requestId を持つ。`SectionChatController` は `openJob` と `answerJob`。`QuizController` と `AnnotationController` は生成Job・モデルDL Jobに加えて requestId を採番し、suspend地点の後に `isCurrent()` を確認してから状態を更新する。`ReadingTraceController` は再会照合/要約の `revealJob` とrequestIdを持ち、ノート切替後に古いカードを出さない。`BookletController` は束を作る `drawJob` と、ページごとの扉を読む `coverJobs`（ページindexをキーに1本ずつ）を持ち、Vault世代を書き込み直前に照合する（**引き直しとすれ違った扉は新しい束へ書かない**）。`NoteFieldController` は判定の `job` と requestId を持ち、索引には書く直前に照合する。`SearchController` は検索とスコープ内ランダムで `searchJob` 1本と requestId を共有し（同じ `searchState` を奪い合うため、検索⇄ランダムの切替でも前の要求を止める）、フォルダ列挙は寿命が違うので `foldersJob` を別に持つ。

Jobキャンセルだけに頼らないのは、モデルDLコールバック等でキャンセルをすり抜ける完了通知があるため。ノート切替時は `NoteSessionCoordinator.cancelNoteScopedJobs()` が一括で止める。

**世代は二層になっている。** ノート単位の要求（要約・DL・クイズ・ひとこと生成・チャット・蒸留・分野判定）は各Controllerが `activeRequestId` を自前で持ち、ノート切替で無効化する。Vault単位の要求（旧補記一覧・削除・フォルダ一覧・痕跡の整理と退避・**冊子の束と扉**・**分野の索引**）は `NoteSessionCoordinator.vaultGeneration` を共有し、Vault切替でだけ無効化する。混ぜられないのは、旧補記の管理画面がノートと無関係で、ノートを開き直しただけで一覧が消えるのは誤りだからである。冊子も同じ理由でVault単位に置く — ノートへ渡って戻るたびに束が消えるのは、機能の定義そのものに反する。`vaultUri` の比較で代用しないのは、A→B→A と選び直したときに同じ値になり検出できないため。

照合は `update` の直前1箇所に集約されている（`SummaryController` / `SearchController` の `setStateIfCurrent`、`AnnotationController.reloadList`）。呼び出し側に `if (!isCurrent) return` を重ねると、テストで検出できない等価な分岐が増えるためである。

**要求単位で未追跡の経路は残っていない。** 以前は旧補記の一覧・削除と要約側のモデルDL Jobが該当したが、いずれも 2026-07-26 に解消した。

### 10.2 CancellationException

要約、関連ノート、セクションAI、クイズ、ひとこと、検索の主要経路では `CancellationException` を再throwし、キャンセルを一般エラーに変換しない。

`SearchPickerUseCase` は 2026-07-26 まで広い `Exception` でキャンセルも捕捉し `PickerResult.Error` へ畳んでいた。**この形は呼び出し側の `catch` では防げない**（中断せず正常に戻るため、そのまま状態更新に到達する）。UseCase 側の再throwと、`SearchController` の requestId ガードの二重で塞いだ。UseCase が結果型でエラーを返す設計を採る場合は、キャンセルだけは例外のまま通す必要がある。

規約から外れていた2箇所を 2026-09-14 に直した。本文の読み直しはキャンセルが `false` に化け、蒸留の元本文の書き出しは画面の寿命で打ち切られると偽のエラーになっていた。どちらも再throwへ揃え、書き出し側はキャンセルでエラーにならず復旧レコードも消さないことをテストで固定した。書き出しをノート切替で止める案は採らない。蒸留の復旧はノートをまたいで残す設計のためである。

### 10.3 キャッシュ

| キャッシュ | キー | TTL | 破棄 |
|---|---|---:|---|
| Vault全体ノート | 現在Vault単位で1件 | 60秒 | Vault切替 |
| 検索スコープノート | `documentId`、ルートはnull | 60秒 | Vault切替 |
| ReadingTraceフォルダ索引 | Vault URI | なし（プロセス中保持） | Vault URI変化・I/O例外 |
| 画像索引 | Vault世代 | TTL付き | Vault切替。不在を契機に作り直す |
| 分野の索引A | `DocumentRef` | なし。確定は永続 | Vault切替。暫定は走査で作り直す |
| 分野の索引B | 入力指紋 | なし。メモリのみ | 読み込みのたびに索引Aから作り直す |
| 要約の保存 | 完成したプロンプトのSHA-256 | なし。端末に残る | 上限1000件を超えたとき、最後に使った時刻が古い順。**ノート切替でもVault切替でも捨てない** |

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
- **成果物にもネットワーク権限が無い**（2026-09-12）。ML Kit GenAI が推移的に引く `transport-backend-cct` が `INTERNET`・`ACCESS_NETWORK_STATE` を持ち込んでいたので、マージ後マニフェストから除いた。モデルDLと生成は AICore アプリが別プロセスで行うのでこちら側の権限は要らない。`verify<Variant>ManifestPermissions` が期待集合と双方向で突き合わせ、CI は release も名指しで呼ぶ。既存モデルでの生成・連続10分の観測・機内モードは実機確認済みで、モデルDLだけが契機待ち。
- 初回モデル取得にはML Kit側のダウンロードが必要になる。
- Vaultへの書き込みは、蒸留による既存ノートの上書き（`**` の挿入のみ・削除なし）、`_ReadingTraces` への読書痕跡JSON保存、旧 `_AI補記` ファイルの削除の3つである（**補記Markdownを書き出す経路はもう無い** — ひとことは痕跡サイドカーへ入る）。蒸留の上書きは原バイトSHA-256の二重照合と出力ハッシュ検証を通し、中断時は `noBackupFilesDir` の復旧レコードから起動時に復旧判定する。読書痕跡はユーザーの`.md`に触れないベストエフォート設計で、checksum破損検知はあるが復旧ファイルと原子更新は持たない。**痕跡は書き出して読み戻せる**ので、フォルダごと失っても再生成できない文章（ひとことへの返事）を退避できる（§6.12）。**書き出したファイルは平文**で、Vault内へ保存すると Obsidian の同期でクラウドへ渡ることを保存前に明示する。
- `android:allowBackup="true"` だが、`random_note_prefs`（Vault の SAF URI・テーマ設定・当日分の閲覧履歴タイトル）はバックアップ・端末移行の両方から除外している。`dataExtractionRules`（`res/xml/data_extraction_rules.xml`／API 31以上）と `fullBackupContent`（`res/xml/backup_rules.xml`／API 30以下）を併記し、minSdk 26 の全レンジを覆う。API 31以上では `cloud-backup` に加え `device-transfer` からも除外する（SAFの永続URI権限は移行先端末で無効になり、復元しても壊れた参照が残るだけのため）。`allowBackup` 自体は true のまま残し、将来バックアップしたいデータが出た時に個別許可できるようにしている。分野の確定も同じ prefs に入る。**端末に残す置き場を足したら除外に載っていることを `BackupExclusionTest` が走査で数える** — 忘れても何も起きない型の規則なので、人の注意に頼らない。
- 本番にログ出力コードはなく、ノート本文やAIプロンプトをLogcatへ明示出力していない。debug ソースセットの計測器と instrumentation は例外で、固定コーパスの応答を logcat へ出す。本番からは呼ばない。

---

## 13. テスト状況

### 13.1 ユニットテスト内訳

| テストファイル | ケース数 | 主な対象 |
|---|---:|---|
| `AiAvailabilityContractTest.kt` | 12 | AI可用性の契約（4状態の意味と、呼び出し側が取る行動の対応） |
| `AnnotationControllerTest.kt` | 10 | 旧補記ファイルの一覧・削除、Vault世代照合、ハンドルの取り直し防止 |
| `BookletControllerTest.kt` | 39 | 冊子の束（10枚・重複なし・0件・走査失敗）、扉の遅延読込（前後1ページ・二重読み防止・失敗の非再試行）、ページ位置の保持と引き直し、Vault世代のすれ違い、**編む束と引く束の寿命・切替・種の固定** |
| `BookletPagerAlignmentTest.kt` | 6 | ページャの引き直しで先頭へ付け替わること、束の世代が変わるまで付け替えないこと、束の切替で位置が戻ること |
| `BookletRestackTest.kt` | 10 | 積み直りの契機と持続、走行フラグが残らないこと、OS設定に従う経路の数 |
| `BookletWeaveTest.kt` | 13 | **編む束の中身**（AI推薦→未リンク→wikilink済みの順、重複は参照で畳む、種を外す、水増ししない）と、トグル3通りの型 |
| `BoundedNoteReadTest.kt` | 9 | 用途別の読込予算、上限到達の判定、多バイト文字の末尾切り |
| `DistillControllerTest.kt` | 44 | 蒸留フローの直列化、requestIdガード、保存後の状態遷移・復旧分岐、**元本文の書き出しがキャンセルでエラーにならず復旧レコードも消さないこと**、自由範囲の保存出力と重なり解消の両方向 |
| `DistillRecoveryStoreTest.kt` | 3 | 復旧レコードの書込・読出・破棄 |
| `DistillWriteRepositoryTest.kt` | 15 | 二重ハッシュ照合、原子確定、出力ハッシュ検証、中断・容量不足 |
| `EventKeyTest.kt` | 5 | Snackbar通知の発火判定キー |
| `FileSummaryCacheTest.kt` | 9 | 要約の保存（1件1ファイル・古い順の削除・大きさの上限・書きかけの片付け・置き場を作れないとき） |
| `InlineMarkdownTest.kt` | 16 | 強調、リンク、コード、打ち消し、誤検出防止、**描画範囲が共有トークナイザーの答えと一致すること**、エスケープ |
| `MarkdownParserTest.kt` | 38 | frontmatter、テーブル空セル、見出し、コード、CRLF、引用、リストのマーカー保持（区切り記号・先頭ゼロ・巨大桁）、段数の算出規則5つ、タブの4列展開、タスク混在、`blocksToMarkdown` の往復 |
| `NoteDwellGateTest.kt` | 11 | **自動生成の門番**（3秒で開く・離れたら取り消す・冊子と背面で数え直す・開いた門を次のノートへ持ち越さない） |
| `NoteFieldControllerTest.kt` | 24 | **分野判定のController**（索引B命中でAIを呼ばない・失効時にヒントへ降格・有効な確定では索引へ書かないことを回数で見る・キャンセルで何も書かない・切替で索引に触らない・永続の上限） |
| `NoteRepositoryTest.kt` | 4 | Markdown判定、wikilink・タイトル正規化 |
| `NoteSectionControllerTest.kt` | 6 | 表示用Markdown解析のMain外退避と、本文差し替え時の再解析 |
| `NoteSessionCoordinatorTest.kt` | 31 | Vault/ノート切替の一斉停止と一斉初期化、リセット登録漏れ検出（**ノート単位とVault単位の両方**）、旧結果の後着防止、Vault世代、冊子から始めた読込の取り消し、**分野の索引の復元と走査の配線** |
| `NoteSnapshotTest.kt` | 5 | 上限付きバイト読込、UTF-8厳格判定、ハッシュ |
| `NoteUiStateStoreTest.kt` | 2 | 各Writerが担当スライスだけを更新すること、ノート読込開始の単一通知 |
| `QuizControllerTest.kt` | 9 | バックグラウンド生成・確認状態・破棄 |
| `QuizInputProfileTest.kt` | 4 | 入力量・コード比率からの出題形式決定 |
| `QuizPromptBuilderTest.kt` | 3 | 形式別クイズプロンプトの出力契約 |
| `QuizResponseParserTest.kt` | 14 | 改行揺れ、前置き、欠落項目、不正な正解、○×/3択/4択の形式別パース |
| `ReadingTraceBackupControllerTest.kt` | 30 | 痕跡の書き出し・下見・適用・中止、停止待ち中の再入、Vault世代 |
| `ReadingTraceBackupJsonTest.kt` | 10 | 退避ファイルの形式（外から見える生JSON・読めなかった件の扱い） |
| `ReadingTraceBackupTextTest.kt` | 11 | 退避・下見・適用・中止の文言（適用だけ言い方を変える） |
| `ReadingTraceCleanupControllerTest.kt` | 26 | 孤児痕跡の洗い出しと削除、Vault世代照合、削除直前の再走査、削除の直列化と最新の一覧への反映 |
| `ReadingTraceControllerTest.kt` | 70 | 能動読書10秒閾値、最深到達点（可視割合込み）、追記上限、後続bind、二重flush、pause/resumeと訪問の差し替え、Vaultキーの持ち回り、ひとこと／返事の保存と退避、**返事の預け先を要求時点の所有者で照合すること** |
| `ReadingTraceJsonTest.kt` | 54 | JSON往復、checksum、UTF-8、必須項目・上限、要約キャッシュ整合、**v1→v5 の各版からの読み込み互換**（旧版の正規形をテスト側に写し取って固定） |
| `ReadingTraceLimitsTest.kt` | 2 | 上限どうしの整合（全フィールドを上限まで詰めてもファイル読込上限に収まること） |
| `ReadingTraceMergeTest.kt` | 20 | 読み戻しの併合規則（端末に無いものを受け入れ、既存を黙って上書きしない） |
| `ReadingTraceStoreTest.kt` | 33 | ハッシュキー、保存/読込、破損・パス不一致、フォルダ/書込失敗、Vaultキーの受け渡しと不一致時の拒否 |
| `RemarkControllerTest.kt` | 27 | ひとことの生成・検証落ち・候補選定（3件＋抜粋・wikilink済みは後回し）・返事の保存結果3値・保存済みの読み戻し |
| `ReunionCardControllerTest.kt` | 32 | 再会カードの照合と生成（種別の決定・空振りの記録・印の再掲・切替の後着・印の要求世代を痕跡ごとに数えること） |
| `SearchControllerTest.kt` | 15 | スコープ切替時の結果破棄・同一スコープ再選択の保持 |
| `SectionChatCombinationTest.kt` | 16 | **共存しうる2処理の両方向**（要約の再試行×走行中の回答、クイズ×チャット） |
| `SectionChatControllerTest.kt` | 12 | セクションチャットの状態遷移・破棄 |
| `SummaryControllerTest.kt` | 7 | モデルDL待ちの要約がノート切替をすり抜けないこと、DL進捗の照合 |
| `SummaryGenerationObservationTest.kt` | 4 | 保存済みの当たりと、同じ入力の再生成を生成の呼び出し回数で区別できること（再起動の数え方を含む） |
| `SurroundingContextTest.kt` | 6 | フォーカス周辺テキスト構築（親子重複の回避・フォールバック） |
| `VaultImageIndexStoreTest.kt` | 23 | 画像索引のTTL・再走査の歯止め・Vault世代 |
| `VaultPathTraversalTest.kt` | 19 | 相対パス付きBFS、除外フォルダ、循環、同名階層、非Markdown除外 |
| `ai/AiAvailabilityMappingTest.kt` | 10 | `FeatureStatus` と例外から `AiAvailability` への写像 |
| `ai/AiGenerationFailureTest.kt` | 3 | 回数制限で断られた失敗の判定（本物の `GenAiException` で組み立て、長期の利用枠の超過は含めない） |
| `ai/DistillPromptBuilderTest.kt` | 4 | 候補件数・文字予算内への収容、プロンプト出力契約 |
| `ai/PromptBudgetTest.kt` | 8 | **完成プロンプトの入力上限**（材料だけを削る・質問と返事は残す・意図する最大構成で切り詰めが起きない） |
| `ai/PromptBuilderExcerptRegressionTest.kt` | 8 | 7プロンプトの出力文字列の固定、抜粋時だけ注意書きが出ること |
| `ai/PromptIndentationTest.kt` | 3 | **複数行の値を埋めても字下げが漏れないこと**を全builderで固定 |
| `architecture/AdrShapeTest.kt` | 7 | ADRの形（30行以内）と、`最終検証` が実在するコミットを指すこと |
| `architecture/AiAvailabilityUsageTest.kt` | 2 | `AiAvailability` の分岐が網羅されていることをソース走査で固定 |
| `architecture/AiClientDoubleTest.kt` | 1 | テスト用 `AiClient` が1本へ寄っていることをソース走査で固定 |
| `architecture/BackupExclusionTest.kt` | 2 | **端末に残す置き場がバックアップ除外に載っていること**（prefs 名の定数を所有する型まで見て解き、除外XMLを解析して突き合わせる） |
| `architecture/BearingChannelTest.kt` | 8 | **面の形の役割**（2つの役割が別物であること・角の大小の順序・どの面がどちらを引くか・互いの役割を引かないこと・縁の色と呼び出し・縁の有無を種別で決めること） |
| `architecture/BookletRouteContractTest.kt` | 4 | 冊子ルートの契約をソース走査で固定（読書時間を止めて戻す・読込中の要求を取り消す・「これを読む」はタブ遷移ではなくルートを積む・先頭から開く） |
| `architecture/DesignDocStateNameTest.kt` | 3 | 状態・列挙の改名／削除が正本へ反映されていること |
| `architecture/DeviceProbeResidueTest.kt` | 1 | **使い捨ての一時テストが作業ツリーに残っていないこと** |
| `architecture/DeviceValidationDocsTest.kt` | 7 | 実機検証の入口と機能別ケースの形（正本リンク・前後処理・記録・ID重複）、ケース表が書く instrumentation の件数が実数と一致すること、**簡易版のスモークIDが実在すること** |
| `architecture/DistillCandidateUnitCopyTest.kt` | 1 | **蒸留の画面文言が候補の単位を「文」と決めつけないことをソース走査で固定**（候補には句・語句が混ざる） |
| `architecture/DistillProtectedScanTest.kt` | 2 | **保護範囲をカーソル越しにしか読まないことをソース走査で固定**（時間差が出ない二乗経路を形で縛る）。文書全体の保護範囲はモデルへ渡す1箇所でしか読まない |
| `architecture/DistillRangeRebuildCostTest.kt` | 1 | **段の導出を候補状態の作り直しから呼ばないことをソース走査で固定**（自由範囲のドラッグではフレームごとに走るため） |
| `architecture/InstrumentationTestShapeTest.kt` | 1 | **`@Test` の戻り値が `void` でなくなる書き方をソース走査で禁じる**（→ §13.5の脚注） |
| `architecture/KotlinCommentScannerTest.kt` | 17 | コメントの字句解析（文字列リテラルやエスケープの中の記号をコメントと誤認しないこと） |
| `architecture/NoteExcerptThreadingTest.kt` | 1 | 抜粋生成が本番の6ファイル9箇所すべてで `Dispatchers.Default` 側にあること（呼び出し箇所の一覧ごとソース走査で固定） |
| `architecture/NoteSectionThreadingTest.kt` | 3 | 本文解析がMainのスコープから呼ばれていないことをソース走査で固定 |
| `architecture/PackageDependencyTest.kt` | 2 | パッケージ依存の向き（ルートパッケージ経由の抜け道を含む） |
| `architecture/PromptGenerationCoverageTest.kt` | 3 | 全プロンプトが実生成テストで覆われるか未保証として列挙されるか（13本中5本が実生成）、件数の固定 |
| `architecture/ReadingTraceBackupThreadingTest.kt` | 5 | 退避のJSON処理がMainの外にあることをソース走査で固定（最大8MB） |
| `architecture/ReviewFindingsLedgerTest.kt` | 7 | 最新レビューの指摘が受付簿へ全件載ること、未解決の処遇だけであること、**受付行の課題が実在すること**、ID重複の拒否 |
| `architecture/SchemaVersionDocsTest.kt` | 2 | **文書が名指しする現行スキーマ版がコードの定数と一致すること** |
| `architecture/SourceCommentShapeTest.kt` | 2 | **KDocが2つ続いて宙に浮いていないこと。本番コメントに日付・レビューの指摘番号が無いこと**（3ソースセット） |
| `architecture/SourceDocSyncTest.kt` | 3 | 状態型の欄が正本の一覧に載ること、KDocの相対リンクが実在すること（3ソースセット）、**文書からソースへのリンクが行番号を持たず名前で指すこと** |
| `architecture/WipIssueReferenceTest.kt` | 2 | `_wip/` とコードが実在しない課題IDを参照していないこと |
| `domain/AiStatusNoticesTest.kt` | 10 | AI状態の説明文と再試行導線の出し分け |
| `domain/BookletCoverLineTest.kt` | 27 | **冊子の扉の抽出規則**（frontmatter・見出し・フェンス内・表の区切り・罫線・リンクだけの行を落とす、フェンスの開閉判定、最初の1文だけ、全角40字の上限と絵文字を割らない切り、選べなければタイトル） |
| `domain/BoundedInputStreamTest.kt` | 13 | **上限の境界（-1／ちょうど／+1）を単一read・配列read・`skip`・混在で固定**、`len == 0` の契約、`available()` の丸め、先読みが1回だけであること |
| `domain/ByteBudgetCacheTest.kt` | 10 | バイト予算つきLRU（超過時の追い出し順・単一エントリ超過） |
| `domain/DistillCandidateScoringTest.kt` | 18 | サリエンス採点、構造的重み、チャンク網羅 |
| `domain/DistillRangeAdjustTest.kt` | 15 | 太字範囲の3段プリセット導出（存在する段だけ出す・親文の内側に収まる）、重なり解消（広げた側が相手を外す・再チェックで非重複を保つ・未選択は誰も押し出さない）、文書全体の保護範囲と親文ぶんの取り出し |
| `domain/DistillRangeSnapTest.kt` | 13 | 自由範囲の端が置ける位置にしか止まらないこと。書記素（サロゲートペア・結合文字・異体字セレクタ・ZWJ・肌色修飾・国旗の対）、装飾の対、端の空白、反対の端、**倒す向き**（広げるなら外側・狭めるなら内側） |
| `domain/DistillResponseParserTest.kt` | 3 | ID抽出、許可集合外の棄却 |
| `domain/DistillSourceModelTest.kt` | 42 | 文分割、UTF-16オフセット、コード/テーブル/frontmatter除外、**装飾（斜体・太字斜体・打ち消し線）の対を割らないこと／過剰保護もしないこと** |
| `domain/DistillTransformerTest.kt` | 5 | オフセット降順の `**` 挿入、太字比率上限、短文例外 |
| `domain/ImageDecodePolicyTest.kt` | 17 | 復号可否の拡張子判定、寸法・ピクセル数の上限、間引き倍率、**`TooLarge`/`Broken` の切り分け順序** |
| `domain/ImageLinkResolutionTest.kt` | 29 | 画像参照の解析と索引照合（完全パス→ファイル名の順、曖昧・外部URL・空） |
| `domain/KeyedMemoCacheTest.kt` | 5 | LRUメモ化（成功時のみ格納） |
| `domain/LauncherEntryTest.kt` | 4 | ランチャー再タップの重複起動の判定。条件3つの真理値表を全部置き、マニフェストが `launchMode` を持たない前提も見る |
| `domain/NoteExcerptBuilderTest.kt` | 18 | 抜粋の予算不変条件（注意書き・ラベル込み）、境界、見出しの均等選抜、単一巨大ブロック（段落・コード・表・リスト）、frontmatter除去、リストの番号と段数がモデルへ届くこと、記法増加後も全予算で上限を超えないこと、中略なしの連続レイアウト |
| `domain/NoteFieldAnswerTest.kt` | 11 | 分野判定の応答を読む規則。行の全体がIDのときだけ受理し、「該当なし」と「読めなかった」を分ける |
| `domain/NoteFieldHintTest.kt` | 8 | パスから分野のヒントを作る辞書照合。当たらない3例が実際に区別されること |
| `domain/NoteFieldIndexTest.kt` | 9 | 索引Aへヒントを流し込む規則。再走査で確定をヒントへ戻さないこと |
| `domain/NoteFieldInputVersionTest.kt` | 5 | 入力指紋がAIへ渡したものすべてを含むこと。落とした項目はそのまま「変えても再判定されない」バグになる |
| `domain/NotePaperAgeTest.kt` | 15 | 相対四分位による紙の地色の段階決定 |
| `domain/ReadingTraceOrphansTest.kt` | 27 | 孤児判定の遮断器（フォルダ単位・読取失敗の伝播・**ルートと別サブツリーの混在**）、削除直前の三値再走査 |
| `domain/RelatedCandidateContextTest.kt` | 11 | 候補の本文肉付け・入力予算内への整形 |
| `domain/RelatedCandidateIdTest.kt` | 11 | 一時ID採番と応答からのID抽出 |
| `domain/RelatedCandidateOrderingTest.kt` | 3 | 採番プレフィックス抽出 |
| `domain/RelatedCandidateRankingTest.kt` | 5 | 採点戦略注入の汎用ランキング |
| `domain/RelatedCandidateScoringTest.kt` | 10 | タイトル話題スコア（bigram Dice＋採番近接） |
| `domain/RelatedContextScoringTest.kt` | 6 | 本文シグナル再ランク（tags/snippet/title） |
| `domain/RelatedNotesDwellTest.kt` | 1 | 関連ノートのAI推薦が、門番が開くまで生成を呼ばないこと |
| `domain/RemarkComposerTest.kt` | 36 | ひとことの5検査（表明語・長さ・ID実在・原文根拠・リンクと問いの排他）、冒頭二人称の除去、映し返しの問い禁止、AI入力用の返事抜粋、保存上限との整合 |
| `domain/ReunionCandidateScannerTest.kt` | 19 | **再会候補の列挙規則**（終助詞「か」の問い・記録と古い前提の区別・版番号と計測値の区別・括弧内で切らない） |
| `domain/SearchKeywordMatchingTest.kt` | 10 | bigram採点、1文字クエリの部分一致、フォールバックの並び順と一致0件除外、再現率カットの0件保持 |
| `domain/SearchPickerBudgetTest.kt` | 2 | ピッカーの**提示集合＝許可集合**（予算で落ちた候補を応答で受理しない） |
| `domain/SearchPickerIdContractTest.kt` | 7 | ピッカーのID契約（IDの直後に文字や数字が続くものをIDと読まない） |
| `domain/SummarizeUseCaseTest.kt` | 12 | 要約の保存の鍵＝AIへ渡したプロンプト、保存してよい結果、引いてよい状態、混雑時の文言 |
| `domain/markdown/InlineSyntaxTest.kt` | 9 | **インライン記法の唯一の解釈器**（種別・エスケープ・バッククォートrun・リンク消費・対の探索・空白規則・入れ子） |
| `testing/SummaryBaselinePlanTest.kt` | 11 | 実機で何を生成し何を使い回すかの計画。同じプロンプトを2度生成しない・失敗した組を含めて続きから再開できること |
| `testing/SummaryCoverageCalibrationTest.kt` | 5 | 採点器の閾値を固定コーパスで決める。人の参照では分離できること・**実機の出力では分離できないこと**の両方を固定 |
| `testing/SummaryCoverageTest.kt` | 18 | 採点器が「落としたこと」を検出できること。裏付けの弱い文と触れなかった節。誤判定3件の回帰 |
| `testing/SummaryExcerptVariantsTest.kt` | 5 | 抜粋の変種の形。予算超過・切り詰め・変種が実は同じ、を机上で落とす |
| `ui/AiTabBadgeStateTest.kt` | 3 | AIタブバッジは生成中だけを示すこと（結果が出ても残らない） |
| `ui/BookletPeelGeometryTest.kt` | 13 | **めくりの幾何**（折り目が右下から左上へ走る・表と裏の面積が合う・裏が枠から出ない・静止時に紙が欠けない） |
| `ui/BookletTurnGeometryTest.kt` | 19 | 紙の**置き方**（積み直りの傾き0〜22度・定位置への付け替え・遠い紙を引き寄せない・束の縁の在不在・影・カメラ距離・縮小率） |
| `ui/DistillRangeHandleTest.kt` | 3 | ドラッグで掴む端を**押下の1回で決める**（近いほうを採る・行が違えば横位置が近くても掴まない） |
| `ui/DistillRangeHighlightTest.kt` | 3 | **確定範囲の強調を値として観測する**（範囲の内側だけに太字と下線・親文の外へ出る指定は内側へ丸める） |
| `ui/DistillRangeNoticeTest.kt` | 5 | 重なり解消の告知の**主語**（解消を起こした側と外された側で言い分ける・件数の単位は「箇所」） |
| `ui/NoteImageMeasurementsTest.kt` | 7 | 画像の表示寸法算出（原寸・上限・アスペクト保持） |
| `ui/NoteImageTextTest.kt` | 19 | 画像の失敗理由ごとの文言と代替テキスト |
| `ui/ReadingProgressGeometryTest.kt` | 13 | 最終可視ブロックの可視割合（完全/一部/画面外/高さ未確定）、5%刻みの量子化 |
| `ui/ReadingTraceCleanupTextTest.kt` | 6 | 孤児整理画面の文言（件数・削除の取り返しのつかなさ） |
| `ui/ReadingTraceHeadlineTest.kt` | 8 | 経過日・セクション・到達率・訪問回数のカード文面 |
| `ui/ReunionLeadTest.kt` | 4 | **種別ごとの前置き**（問い・古い前提・印あり・俯瞰要約には付けない） |
| `ui/VigilithAccessibilityTest.kt` | 2 | 状態・対象節をまとめたTalkBack文言、回答／要約の区別 |
| `ui/VigilithMascotMotionTest.kt` | 10 | Summaryの片翼案内、蒸留の断片収集・両翼保持・下線、Messengerの着地・一度だけの発光、入力clamp・出力範囲、Summarizing>Idleのレンズ輝度差 |
| `ui/VigilithModeTest.kt` | 11 | Idle/Summarizing/Distilling/Messengerの優先順位、蒸留3工程、モデル取得除外、カードdismiss、全画面・シート非表示（**範囲調整シートを含む**） |
| `ui/VigilithOpeningMotionTest.kt` | 8 | ハロー・全身・名称の登場順、保持区間、退場、終端・範囲外入力 |
| `ui/VigilithPlacementTest.kt` | 6 | 四辺clamp、Fold再配置、ラベル寸法、Snackbar / IME予約領域、狭小画面 |
| `ui/VigilithStatusDerivationTest.kt` | 14 | セクションチャット／全画面AIの状態導出（要約×クイズの合成） |
| `ui/theme/AppColorContrastTest.kt` | 28 | 明暗の役割トークンのコントラスト比。文字は4.5:1・塗りと記号は3:1を**強制**する（**既知未達は解消済み** — 未達だったナビ帯上のバッジ塗りは 2026-08-09 に対象ごと消えた） |
| `ui/theme/NoteFieldPaletteTest.kt` | 5 | 分野の6色を値ではなく規則で固定（明度が揃い色相が等間隔）と、面としての合否1件 |
| `ui/theme/VibrantTextUsageTest.kt` | 2 | 画面からの `onVibrant` 直接使用と、文字色への任意の `copy(alpha)` をソース走査で禁じる |
| **合計（134クラス）** | **1556** | |

> **全134クラスを機械的に数え直した。** 件数は `testDebugUnitTest` のレポートから採り、説明は手で書いている。説明の側は古くなりうる。
> 行頭の `@Test` を数える §0 のコマンドとも一致する。**行頭に限らず数えると4件多く出る** — 文字列リテラルの中に `@Test` を書くテストがあるため。

なお `NoteHistoryStore` は `Uri`・`org.json` がAndroid実装依存のため、素のローカルユニットテストでは検証していない（Robolectric等の導入が前提になる）。

### 13.2 実行結果

```text
./gradlew testDebugUnitTest lintDebug --offline
BUILD SUCCESSFUL   134クラス・1,556件・failure 0・skip 0・3秒／Lint 0 errors, 0 warnings
```

2026-09-19 に Android Studio 同梱の JBR で実行した。**この回の hint は0件だった** — 依存更新の催促は Lint が持つ最新版情報のキャッシュ次第で出ないことがある。
JBR は `/Applications` 直下ではなく
`/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home` にあるため `/usr/libexec/java_home` では検出されない。`JAVA_HOME` へ明示指定する。

**件数の推移はここに積まない。** 282件から1,556件までの増え方は各PRの1行が [change_history](../dev/change_history.md) にあり、
節目ごとの数は本書の git 履歴が持つ。ここに残すのは、増え方から分かった性質だけである。

- **増分の性質は2つに分かれる。** 本番の欠陥を閉じるものと、テストや文書の運用そのものを検査に変えるもの。後者はプロダクトコードを1行も守らないが、守る仕組みが壊れたことを検出する
- **書いた直後に全部通るテストは疑う。** 蒸留の装飾保護では10件が書いた直後に通り、変異を6つ当てて4本が落ちなかった。データを作り直して初めて効いた → [L51](../dev/lessons/L51.md)
- **上限テストは長さだけでなく要素数も最大化する。** 文字数・記法数・候補数×保護範囲数の3系統を持つ → [L52](../dev/lessons/L52.md)
- **走査テストは名前があることでは書かない。** 名前が残る場所が3通りあり、いずれもビルドと検査を通る。関数の本体へ絞り、どの面のどの代入かで書く → [L55](../dev/lessons/L55.md)
- **値の検査は前提ごと間違える。** 紙の向きは27件がそろって同じ思い込みを写し、画素を数えて初めて分かった → [L61](../dev/lessons/L61.md)
- **1つの状態に書き手が複数いるなら、操作の直後の値だけでは足りない。** 降格の検査は緑のまま、走査が次の実行で戻した → [L66](../dev/lessons.md)
- **走査テストは Gradle の入力にも載せる。** 同じ行の中でコメントだけを直すとクラスファイルが変わらず、UP-TO-DATE で飛ぶ。文書・`androidTest`・`res/xml`・`src/main/java` を入力に足してある

### 13.3 自動実行（CI）

2026-07-26に `.github/workflows/ci.yml` を新設した。PR と `main` への push で `./gradlew testDebugUnitTest`・`./gradlew lintDebug`・マージ後マニフェストの権限検査（debug と release）・`./gradlew assembleDebugAndroidTest` を実行する（JDK 21／ローカルの Android Studio 同梱 JBR に合わせた）。release の権限検査を名指しで呼ぶのは、CI が release をビルドしないので後始末の契機が来ないためである。テストレポートと Lint レポートは失敗時の追跡用に artifact として保存し、同一ブランチへの連続pushでは古い実行を打ち切る。

現在 Lint は **Error 0件・Warning 0件**で、`lint { warningsAsErrors = true }` により警告でビルドが落ちる。
Kotlinコンパイラ側も `allWarningsAsErrors = true` を設定した（**Lintの設定はAndroid Lintにしか効かず、
これが無い間はテストコンパイル警告を何件でも追加できた**）。依存更新系の3チェック
（`GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion`）だけは `informational`（hint）へ降格してある。
素のまま有効化すると12件すべてが Error になり `lintDebug` タスクが失敗するが、`informational` なら
**「0 errors, 0 warnings, 12 hints」で成功し、指摘はレポートに残る**。
**件数は Lint の最新版情報のキャッシュ次第で変わる** — キャッシュが古いと Google Maven 側の8件が出ず4件になり、一度オンラインで解析すれば12件に戻る。
**「12」を回帰の基準値として使わない。**上流が新版を出すだけで生える
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
- Gemini Nanoの**ダウンロードとタイムアウト**（利用可否と生成は §13.5 が覆うが、**生成は13本のプロンプトのうち5本だけ**）
- **端末AIの出力の意味。** 採点器は語の重なりしか測らず、実機の出力では忠実な文と誤りの文を閾値で分離できないことが分かっている。良し悪しは人が読む
- ~~Compose UI、NavigationBar/Rail、全画面遷移~~ → **§13.5 が覆った**（読書画面の描画抑止・位置引き継ぎ・タブ遷移）。ただし画面幅による Rail 切替は未カバー
- 連続操作時のキャンセルと競合（**タブ連打テストはあるが競合を作れていない** → §13.6・TEST-4）
- 実際のObsidian Vaultを使ったE2Eテスト（偽Vaultでの経路は §13.5 が覆う。**実プロバイダ固有の挙動**は対象外）
- **画面の佇まい**（面の見え方・束の中身・繰る手触り）。振る舞いの検査は全緑のまま、ここだけが**どのテストにも掛からない**。実際に中の課題3件はいずれも机上レビューを素通りし、実機で画面を見たオーナーの体感でしか出なかった。**形の役割が取り違えられていないことは走査で固定できるが、それがどう見えるかは走査では分からない** — 判定は実機検証のケース表が持つ

現在の1,556テストは、Android依存の薄い純粋ロジックと、Controller間の調停の回帰防止に有効である。**instrumentation 101件が SAF・画像復号・Compose描画・画面遷移・画素・端末AI生成の一部を実機で覆っている**が、**保証範囲は §13.6 のとおり主張より狭い**。ReadingTraceの高優先度3件はこの境界外で見つかったものであり、修正後も**Android側の実挙動は実端末確認でしか担保できない**。Vigilithも状態分離・モーション・配置範囲は純関数で検証しているが、実フレームの見え方、タップ／ドラッグの競合、Snackbar・IME・ReadingTraceとの視覚的な重なり、TalkBackは実機確認が必要。

---

### 13.5 instrumentation の内訳（101件）

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
| `ai/OnDeviceGenerationTest` | 5 | 本番プロンプトでの実生成。要約・クイズ・関連ノート・セクション要約・**分野判定（応答がIDとして読めること）** | 端末AI（AICore） |
| `ai/SummaryCoverageBaselineTest` | 2 | 固定コーパスを本番の要約プロンプトで生成させ、採点器に掛けて logcat へ記録する。**値は主張しない。** 同じプロンプトは再利用し、続きから採れる | 端末AI（AICore） |
| `ui/ActivityRecreationTest` | 2 | Activity再生成でOPを再生しない、繰り返し再生成（**プロセス死亡は覆わない**） | Activityライフサイクル |
| `ui/TabNavigationTest` | 5 | タブの往復・巡回・**戻る操作での履歴契約**・遷移先での再生成 | `NavHost` のバックスタック |
| `ui/QuizActionSectionTest` | 2 | クイズが使えない理由が**押した場所に描かれる**こと | Composable の描画結果 |
| `ui/ReadingTraceCardPanelTest` | 6 | 再会カードの**種別ごとの前置き・印の文言・空枠でボタンを出さないこと** | Composable の描画結果 |
| `ui/DistillRangeAdjustUiTest` | 8 | 範囲調整シートの操作（プリセット切替・最初の範囲へ戻す・つまみのドラッグ・端の微調整）と**告知の出方** | Composable の描画結果・シートの操作 |
| `ui/BookletScreenTest` | 22 | 冊子の描画（扉・めくり・終端の「もう10枚引く」・0件・失敗ページ・読み上げ用のノート名）と、**編む側のトグル3通り・編む束の終端** | Pager の実挙動・描画結果 |
| `ui/BookletNavigationTest` | 2 | 実 `NavHost` での `note → booklet → note` 往復と**ページ位置の復帰** | NavHost のバックスタックと状態復元 |
| `ui/BookletSheetPerspectiveTest` | 9 | 倒れた紙の**投影の向き**・枠への収まり・カメラ距離・めくりで現れる裏・積み直りとの同時進行。本番の `BookletSheet` をそのまま描く | **描いた画素を数えないと符号の意味が確かめられない**（→ lessons L61） |

> **純関数を押さえても、Composableがそれを呼ぶことは観測できない。**
> 最後の5クラスはそのために置いてある。文言や範囲を決める純関数はJVM側で固定できるが、
> 「カードがその関数を呼ぶ」「押した場所に描く」「戻ったら同じページが開く」は描画しないと分からない。
> **APKが組み立つことは、描画の受け入れ条件を代替しない。**
>
> **ただし実機でしか走らないテストは、効いていることを確かめられない。**
> 変異を入れて落ちるかを見る工程がCIに無いため、`androidTest` に置いた契約は
> **緑であることしか分からない**。範囲調整では、この理由で観測点をJVM側へ引き出し直した
> （`DistillRangeHighlightTest` / `DistillRangeNoticeTest`）→ [lessons](../dev/lessons/L53.md) L53。
>
> **ソース走査で構造を縛るテストは、受理条件の代わりにならない。**
> `BookletRouteContractTest` は「読書時間を止める呼び出しがある」ことしか見ておらず、
> **止まった結果**は見ていない。役割を構造の固定に限定し、結果は別の検査が持つ
> → [lessons](../dev/lessons/L55.md) L55。

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
- **端末AIの生成は13本のプロンプトのうち5本だけ。** Nano依存12件の内訳は生成5件・計測と診断5件・要約の基準線2件で、
  読書痕跡要約・蒸留・検索picker・ひとこと・映し返し・再会カードの選別・セクション候補・セクションchatの**8本は未保証**。
  → 2026-08-08 に主張を代表経路へ狭め、未保証をテスト側へ列挙した。分野判定は 2026-09-10 に5本目として足した。
  **`PromptGenerationCoverageTest`（JVM）が、builder を足したら覆うか未保証と宣言するまで落ちる。**

偽Vaultの実体ファイル名が document ID の `/` を `_` へ潰して衝突していた件は、
2026-08-08 に SHA-256 の単射なファイル名へ変え、同一パス再投入の列挙重複も直した。
**区切りだけが違うパスと再投入の2ケースは instrumentation で固定してある。**

## 14. コード品質評価

### 14.1 強み

1. **責務分割が明確**

   旧来の巨大ViewModelにすべてを置かず、要約・検索・セクションAI・クイズ・ひとこと・蒸留・読書痕跡・痕跡の退避・冊子・分野判定をControllerへ分離している（13個）。さらに横断調停と状態所有を `NoteSessionCoordinator` へ出し、`NoteViewModel` にはAndroid境界だけを残した。Vaultへの破壊的書き込みを伴う蒸留は、書き込み経路自体も `DistillWriteRepository` / `DistillRecoveryStore` へ切り出している。読書痕跡も `ReadingTraceStore` / `ReadingTraceDocumentGateway` でAndroid依存境界を隔離している。

2. **UI状態が一元化され、所有権が型で守られている**

   Compose側は `NoteUiState` を読むだけで、画面ごとの状態追跡が分散しにくい。書き込み側は `NoteUiStateStore` だけが `MutableStateFlow` を持ち、各Controllerへは担当スライスの Writer しか渡さないため、担当外フィールドへの書き込みはコンパイルが通らない。

3. **境界が文書ではなくテストで守られている**

   パッケージ依存の向きは `PackageDependencyTest` がimportを走査してCIで固定し、ノート/Vault切替の一斉停止と一斉初期化は `NoteSessionCoordinatorTest` が実物の14 Controllerを束ねて検証する。**見た目のチャネル割り当ても検査に載せた** — 冊子と本文が同じ形の役割を引いていないことを `BearingChannelTest` が固定する（2026-09-02）。**ノート単位とVault単位のリセット漏れは別々に検査する** — 冊子を足したときに「ノート切替では消えないこと」を確かめる面が新しく要った。どちらも「後始末を1つ消すと落ちる」ことを変異確認で検証済みで、規約がKDocの口約束に留まっていない。

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
| 中 | **AICore の短期回数制限で、要約タブに SDK の英文が出うる** | ノートを開くたびに自動生成が最大3本走る。計測テストでは12回成功の直後の13回目が拒否された。アプリ本体ではまだ観測していない。要約キャッシュが入れば呼び出し自体が減る。直すか見送るかはオーナー判断 |
| 低 | **ランチャー再タップの重複起動ガードは、回転と Fold 開閉の1ケースだけ実機未確認** | ガードは `onCreate` の先頭に入り、素のコンポーネント指定からの再タップ・戻る・ランチャー起動・復帰の4ケースは実機で通した。起動経路そのものを触っているので、残りが済むまで課題台帳から消さない |
| 中 | **画面の佇まいを判定する工程が、実機検証にしか無い** | 振る舞いはJVM 1,556件と instrumentation 101件が見ているが、「見分けられるか」「手触りがあるか」はどのテストにも掛からない。2026-09-04 に手触りの10件まで実機ケースで判定できたが、机上のレビューでは依然として出ない。実際、冊子の向きと変形の不足も実機で触って初めて分かった。**テストで埋める種類の穴ではないので、工程側に置いたままになる** |
| 低 | **全101件を一度に通した実行は無い** | 実機検証の単位が機能ごとのケース表へ移ったため（→ `docs/review/device_validation/`）。着手した機能のケースは都度通しており、直近は 09-13 の要約の基準線と 09-12 の分野色・権限除去 |
| 低 | **実機でしか走らないテストは変異確認ができない** | `androidTest` は変異を入れて落ちるかを見る工程がCIに無く、**緑であることしか分からない**。観測点をJVM側へ引き出せる場合はそうする方針だが、描画そのものは引き出せない（→ [lessons L53](../dev/lessons/L53.md)） |
| 低 | YAML解析が簡易 | 複雑なYAML・引用・ネスト・複数行値に対応しない。AI推薦で使う tags/aliases の取りこぼしにつながり得る |
| 低 | Markdownの未対応項目 | クリック可能リンク・埋め込み（`![[note]]`）・数式。リスト構造と画像は実装・実機確認済み |
| 低 | 同名ノートの曖昧性 | AI推薦は候補ごとの一時IDで解決するため不定にならない。ただし決定的チャンネルや除外判定で使う正規化タイトル集合には同名畳み込みが残る |
| 低 | R8・署名が未設定 | `release` は `isMinifyEnabled = false`・署名なし。R8を有効化すると ML Kit GenAI のリフレクション解決部分が縮小で消え、**全AI機能が release ビルドでだけ落ちる**可能性がある。JVMテストは縮小前のクラスを見るため検出できず、実機検証とセットになる |
| 低 | **コメント密度が28%** | 判断が読める強みの一方、読む量が増え、コメントも実装より古くなる。経緯を書かない規約と検査は置いた。1,000行超2本の分割と、コメント行数上位10ファイルの整理が残る |
| 低 | 依存の更新そのものが未着手 | 方針は確定済みで、Lint の3チェックを `informational` にして毎ビルド hint として見えるようにしてある。**残るのは更新の実行** — `genai-prompt` は beta2→beta4 で**ソース互換だが動作互換ではない**ことが調査済み |

> **解消済みとしてここから外した項目**（いずれも本書の他節と `change_history` に記録がある）:
> instrumentation の未実行（→ 機能ごとに実行する形へ移行）・孤児掃除の導線なし（→ 手動削除を実装）・
> 外部同期の索引（→ 不在を契機に作り直す方式で解消）・AI入力が先頭固定長（→ 骨格＋冒頭＋末尾の抜粋へ）・
> 画像が未対応（→ 実装・実機確認済み）・**蒸留が既存の斜体・打ち消し線をまたぐ**（→ 解釈器の共有と `DIST-20` の実機確認で解消）・
> **冊子が通常表示と見分けられない**（→ 面の形で分け、2026-09-02 に `BOOK-26`〜`BOOK-30` で実機確認済み。§6.13）・
> **ユーザーが書いた返事に退避手段が無い**（→ 書き出し／読み戻しを実装、
> 2026-08-28 実機確認済み。§6.12）・**太字の範囲をユーザーが決められない**（→ 段階1のプリセットを実装、
> 2026-08-30 `DIST-21`〜`DIST-24` で実機確認済み。§6.10）・**ランチャー再タップの積み重なり**（→ ガードを実装。§6.1）・
> **成果物のネットワーク権限**（→ 除去と検査。§12）・**天綴じの手触りが実機未確認**（→ 折り目のめくりへ作り替え、2026-09-08 に受理。§6.13）。

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
