# ソースコード解析書

**プロジェクト:** Vigilith AI
**この文書が答える問い:** いまコードがどうなっているか。構成・状態・データの流れ・検証の状態を、コードを開かずに見渡すための地図である。
**測定日:** 2026-10-11。基準は `a3661274` で、作業ツリーは clean。測った値は §0 に集め、ほかの章には日付を書かない

> **レビュー前である。** 本文の事実はソースと突き合わせたが、別の目では確かめていないので未確定として読むこと。
> この囲みは、レビューが済んだら消す。

> **`owner/` の文書に出る規模と検証の数字は、この §0 だけが持つ。** ほかの文書は数字を書き写さず、ここへリンクする。

**ここに書くのは、コードを開けば確かめられることだけである。** 守っている約束は1行で書き、理由は正本へリンクする。
なぜそう作ったかは [dev/features/](../dev/features/) と [dev/system/](../dev/system/) が持ち、
いつ何が起きたかは [開発日誌](journal/) と [change_history](../dev/change_history.md) が持つ。

## 目次

- [0. いまの数字](#0-いまの数字) ・ [1. エグゼクティブサマリー](#1-エグゼクティブサマリー) ・ [2. 技術構成](#2-技術構成) ・ [3. 現在のファイル構成](#3-現在のファイル構成)
- [4. アーキテクチャ](#4-アーキテクチャ) ・ [5. ナビゲーションと画面構成](#5-ナビゲーションと画面構成)
- [6. 主要機能のデータフロー](#6-主要機能のデータフロー)
  - [6.1 Vault選択と復元](#61-vault選択と復元) ・ [6.2 ランダムノート表示](#62-ランダムノート表示) ・ [6.3 ノート要約](#63-ノート要約) ・ [6.4 関連ノート](#64-関連ノート) ・ [6.5 さがす](#65-さがす)
  - [6.6 部分要約](#66-部分要約) ・ [6.7 余白メモと「この部分」](#67-余白メモとこの部分) ・ [6.8 当日閲覧履歴](#68-当日閲覧履歴) ・ [6.9 蒸留](#69-蒸留) ・ [6.10 読書痕跡と再会カード](#610-読書痕跡と再会カード)
  - [6.11 読書痕跡の退避と読み戻し](#611-読書痕跡の退避と読み戻し) ・ [6.12 冊子](#612-冊子) ・ [6.13 分野判定](#613-分野判定) ・ [6.14 結晶](#614-結晶) ・ [6.15 ノート内のリンク](#615-ノート内のリンク)
- [7. SAF・Vaultアクセス層](#7-safvaultアクセス層) ・ [8. AI層](#8-ai層) ・ [9. Markdown解析・描画](#9-markdown解析描画)
- [10. 並行処理・ライフサイクル・キャッシュ](#10-並行処理ライフサイクルキャッシュ) ・ [11. エラー処理とフォールバック](#11-エラー処理とフォールバック) ・ [12. データ保護・プライバシー](#12-データ保護プライバシー)
- [13. テスト状況](#13-テスト状況) ・ [14. この文書の更新](#14-この文書の更新)

---

## 0. いまの数字

### 0.1 規模

| 指標 | 値 |
|---|---:|
| 本番コードのファイル数 | **193** |
| 本番コードの行数 | **33,218** |
| JVMテストのファイル数 | **172** |
| JVMテストの行数 | **36,554** |
| JVMテストの件数 | **1,877** |
| instrumentation のファイル数 | **19** |
| instrumentation の行数 | **7,188** |
| instrumentation の件数 | **188** |
| debug ソースセット | **4ファイル・825行** |

行数は空行とコメントを含む `wc -l` で数え、生成物と Gradle スクリプトは含まない。
本番33,218行の内訳はコメント9,698行・空行2,471行・本体21,049行で、コメントの比率は29%。
テスト件数は行頭の `@Test` を数える。ファイルは `find app/src/main -name "*.kt"` のように置き場ごとに数える。

### 0.2 検証状態

| | 結果 |
|---|---|
| `testDebugUnitTest` | **1,877件すべて成功。** 160クラス、failure 0、skip 0 |
| `lintDebug` | **Error 0 / Warning 0。** 依存更新の催促は hint として出るがゲートに載せない。件数は Lint の最新版情報のキャッシュ次第で揺れる |
| Kotlin のコンパイル警告 | 0。警告があるとビルドが落ちる設定 |
| マージ後マニフェストの権限 | 期待する2件と一致する。AICore への接続と、自己定義の受信権限 |
| `assembleDebugAndroidTest` | CI で通す |
| instrumentation の実行 | 全188件を一度に通した実行は無い。着手した機能のケースだけを実機で走らせ、手順は `docs/review/device_validation/` が持つ |

実機で確かめていない範囲は、各機能の正本の §10 が持つ。

未対応の課題は [_wip/current_issues.md](../_wip/current_issues.md) が正本で、測定日の時点で中2件・低12件・超低1件ある。高は無い。

---

## 1. エグゼクティブサマリー

Vigilith AI は、Obsidian の Vault を読み、AI をすべて端末内の Gemini Nano で動かす Android アプリである。
ネットワーク権限を持たず、ノート本文へ書くのは蒸留の太字化だけ。何ができるかは [README](README.md) の機能表が持つ。

- **構造は健全。** 単一の ViewModel を窓口に、実装は14の Controller へ分かれ、状態は1か所だけが持つ。依存の向きや状態の後始末の漏れは JVM テストが落とす
- **検証は厚い。** 純粋なロジックは JVM テスト、描画・SAF・端末AIの一部は実機の instrumentation が見る。件数は §0
- **残る弱点は3つ。** 見た目と手触りは実機検証でしか判定できない。AI の出力の良し悪しは語の重なりまでしか測れない。R8 と署名が未設定で、配布できる成果物がまだ無い

---

## 2. 技術構成

規模の数字は §0 にある。モジュールは `:app` の1つで、debug ソースセットには instrumentation 用の偽 SAF プロバイダと要約の採点器を置く。どちらも release には入らない。

| 項目 | 現在値 |
|---|---|
| Android Gradle Plugin / Kotlin Compose Plugin | 9.1.1 / 2.0.21 |
| SDK | compile 36.1・target 36・min 26 |
| applicationId | `com.vigilith.ai`。`namespace` は `com.example.newproject` のまま |
| Java | 11。Kotlin の `jvmTarget` も 11 |
| buildTypes | `release` を定義。R8 は無効で、署名は未設定 |
| 権限 | `INTERNET`・`ACCESS_NETWORK_STATE` をマージ後マニフェストから除き、`verify<Variant>ManifestPermissions` が期待する2件と増減の両方向で突き合わせる |
| 警告 | Lint の `warningsAsErrors` と Kotlin の `allWarningsAsErrors`。依存更新の3チェックだけ `informational` |
| 主な依存 | Compose BOM 2024.09.03・Navigation 2.7.7・Lifecycle 2.8.7・Coroutines 1.9.0・WindowManager 1.3.0・ML Kit GenAI Prompt 1.0.0-beta2 |
| テスト | JUnit 4.13.2・Compose UI Test・Espresso 3.7.0 |

- UI は Jetpack Compose だけで組み、View と XML は使わない
- Vault へは SAF と `DocumentsContract` で触れ、ストレージ権限を要求しない
- DI フレームワーク・データベース・HTTP クライアント・画像読み込みライブラリは入れていない
- AI は `AICoreClient` を直接生成する。`StubAiClient` は手で確かめる用に残してある

---

## 3. 現在のファイル構成

本番コードを `app/src/main/java/com/example/newproject/` の下のパッケージ単位でまとめる。テストは §13 と [jvm_test_report](02_jvm_test_report.md) にある。

| パッケージ | 責務 | 境目になる主なクラス |
|---|---|---|
| ルート | 画面の入口と Android 境界 | `MainActivity`：NavHost・起動OP・Vault選択・テーマ。`NoteViewModel`：`Uri`・`ContentResolver`・設定を扱う窓口。`NoteViewModelDependencies`：本番依存の組み立て。`NoteScanCache`：Vault全体のノート一覧とリンクの索引 |
| `ai/` | 端末AIへの接続とプロンプト | `AICoreClient`：Gemini Nano・`Mutex`・タイムアウト。`PromptBuilder`：9本のプロンプト。`PromptBudget`：完成プロンプトの上限 |
| `controller/` | 機能の実装と横断調停。Android API を呼ばない | `NoteSessionCoordinator`：14の Controller の生成・切替の調停・Vault世代・門番。Controller は要約・検索・部分要約・並べ読み・余白メモ・蒸留・読書痕跡・再会カード・痕跡の整理・痕跡の退避・冊子・分野判定・結晶・本文の解析の14本。`NoteDwellGate`：自動生成の3秒の門番。`NoteReadGate`：結晶の材料の30秒の線 |
| `data/` | SAF と端末内の保存 | `NoteRepository`：走査と読み書き。`VaultBrowser`：Vault スコープの操作を `ContentResolver` の裏へ束ねる。`DistillWriteRepository`：蒸留の安全な書き込み。`ReadingTraceStore`：痕跡のサイドカー。`CrystalStore`：結晶のファイル。`FileSummaryCache`：要約の保存 |
| `domain/` | Android 非依存の純関数 | 採点・抜粋・解析・照合。`markdown/` の `InlineSyntax` がインライン記法の唯一の解釈器で、表示と蒸留が共有する。`NoteSections` が見出し単位の節を作る |
| `model/` | 依存グラフの葉。他パッケージも `android.*` も import しない | `NoteUiState`：全UI状態。`NoteUiStateStore`：状態の唯一の所有者・機能別の Writer・リセット契約。`state/` に機能ごとの sealed state |
| `ui/` | Compose の画面 | `AppScaffold`：タブとレール。`screen/` に各画面、`markdown/` に本文の描画、`theme/` に配色と形の役割、`vigilith/` に起動OP |
| `res/xml/` | バックアップ除外 | `backup_rules`・`data_extraction_rules` |

---

## 4. アーキテクチャ

### 4.1 レイヤーと依存方向

```text
Compose UI / MainActivity
     │ イベントを送り、StateFlow を購読する。uiState と設定3つは別の Flow
     ▼
NoteViewModel ─────────────────── Android 境界。Uri・ContentResolver・設定
  │  └ NoteViewModelDependencies    依存の組み立て
  ▼
NoteSessionCoordinator ────────── 横断調停と状態の所有。Android API を呼ばない
  ├ NoteUiStateStore                機能別の Writer を配る
  ├ Controller 14個                 持ち主の一覧は §4.2
  ├ NoteDwellGate                   自動生成の門番
  ├ NoteReadGate                    結晶の材料を控える線
  │
  ├──► NoteRepository ────────────► SAF / DocumentsContract
  ├──► DistillWriteRepository ────► SAF の安全書き込み。復旧レコードは noBackupFilesDir
  ├──► ReadingTraceStore ─────────► SAF / _ReadingTraces
  ├──► CrystalStore ──────────────► SAF / _ReadingTraces/crystals
  └──► UseCase ──► AiClient ──────► ML Kit / Gemini Nano
```

パッケージはすべて同じ `:app` モジュールにあり、依存の向きは一方向だけを許す。
`model` が葉で、`model`・`domain`・`controller` の3層は `android.*` も import しない。
ルートの `MainActivity` と `NoteViewModel` はどの層からも参照されない。`PackageDependencyTest` が import を走査して固定する。
許す向きの表と理由は [architecture](../dev/system/architecture.md) の判断5 にある。

純粋ロジックは `domain/` と、`ui/` の `*Geometry`・`*Measurements`、`ui/screen/` の `ReaderLayout`・`MarginSurface` に置く。
画面の形を決める判定は `ui`、データや状態の意味を決める判定は `domain` に置く。置き場所を決めるのは責務で、テストのしやすさではない → [architecture](../dev/system/architecture.md) 判断5の4。

### 4.2 状態の持ち主

`NoteUiStateStore` だけが `MutableStateFlow<NoteUiState>` を持ち、UI には読み取り専用の `StateFlow` を公開する。
Controller は独自の Flow を作らず、受け取った Writer で担当の欄だけを書く。

| Controller | 受け取る Writer | 書く欄 |
|---|---|---|
| `SummaryController` | `SummaryStateWriter` | `summaryState` |
| `SearchController` | `SearchStateWriter` | `folders`・`selectedFolder`・`foldersError`・`searchState` |
| `SectionChatController` | `SectionChatStateWriter` | `sectionChat` |
| `MarginMemoController` | `MarginMemoStateWriter` | `marginMemoState`・`isMarginMemoSheetVisible` |
| `DistillController` | `DistillStateWriter` | `distillState` |
| `ReunionCardController` | `ReadingTraceStateWriter` | `readingTraceCard` |
| `ReadingTraceCleanupController` | `ReadingTraceCleanupStateWriter` | `readingTraceCleanupState` |
| `ReadingTraceBackupController` | `ReadingTraceBackupStateWriter` | `readingTraceBackupState` |
| `BookletController` | `BookletStateWriter` | `bookletState` |
| `NoteFieldController` | `NoteFieldStateWriter` | `noteFields` |
| `CrystalController` | `CrystalStateWriter` | `crystalLog`・`crystalNotePath` |
| `SideReadingController` | `SideReadingStateWriter` | `sideReading`。本文の解析結果は `NoteUiState` の外の `StateFlow` に持つ |
| `ReadingTraceController` | なし | UIの状態は書かない。読書中のセッションは内部に持つ |
| `NoteSectionController` | なし | `NoteUiState` の外に `StateFlow<NoteSectionModel?>` を持つ |

Writer を持たない `noteState`・`relatedNotesState`・`wikilinkTitles`・`todayHistory`・`notePaperTone`・`vaultSelected` は、`NoteSessionCoordinator` 専用のメソッドで書く。
関連ノートは `Uri` を持つ走査キャッシュに依存しているので、`NoteViewModel` 側に残っていて Controller になっていない。

`NoteUiState` の外にあるのは次のものである。

- 設定の `darkTheme`・`notePaperAging`・`marginPaneOpen` は、`NoteViewModel` が独立の `StateFlow<Boolean>` で持つ。変わったときにアプリの最上位まで組み直さないため
- `NoteSectionModel` は `NoteSectionController` が持つ。解析の開始点は Coordinator の `setNoteState()` と `applyReloadedBody()` の2箇所
- 並べ読みで右に出す本文の解析結果は `SideReadingController.blocks` が持つ。`NoteSectionModel` と同じく `domain` の型なので外に出し、読み込み中・読めた・失敗の状態だけを `NoteUiState` の `sideReading` に置く
- 余白メモの書きかけは、`NoteViewModel` が `ComposeMarginMemoDrafts` に Compose の状態で持つ。文字・書き込み先・未確定の送信を一組にしてノートごとに持ち、`MarginMemoController` は `MarginMemoDraftStore` の口だけを通す。入力中の文字を StateFlow 経由で描くと、日本語の変換中に入力が崩れやすいため
- `knownNotes` は走査の結果にあるノートの相対パスと参照の対応で、`NoteScanCache` から配る。結晶の一覧で、根拠のノート名を押せるかを決め、宝石を塗る分野を引く

### 4.3 状態モデル

`NoteUiState` は23の欄を持つ。sealed state は `model/state/` に機能ごとのファイルで置く。

| 欄 | 取りうる状態と、持っている値 |
|---|---|
| `noteState` | Idle / Loading / Success / Empty / Error。Success は蒸留の書き戻し用に `targetUri`・`originalHash`・`distillUnavailableReason` を持つ。本文は先頭の BOM を外した文字列で、`originalHash` はファイルの原バイト列から取る |
| `summaryState` | Idle / Loading / Success / Downloading / AiUnavailable / Error |
| `relatedNotesState` | Idle / Loading / Success。失敗の枝は無い |
| `marginMemoState` | Idle / Loading / Ready / Error。Ready はメモの一覧、保存の状態、切り詰めたかどうか、前回の訪問を持つ。保存の状態は None / Saving / Held / Saved / Full / Failed / Unconfirmed の1つ |
| `isMarginMemoSheetVisible` | 「この部分」のシートを出しているか。余白メモと部分要約で1枚 |
| `distillState` | Idle / Analyzing / AiNotice / Downloading / Unavailable / Candidates / Saving / Saved / Conflict / RecoveryRequired / RecoveryResolved / Error。AiNotice は端末AIの状態、Unavailable はノート側の理由。Candidates は候補ごとの範囲調整も持つ |
| `bookletState` | Idle / Loading / Open / Failed。Open は引く束と編む束の2つを持ち、それぞれがページ位置を持つ。編む側は種なし・編めない・編めるの3状態 |
| `noteFields` | 分野の索引A。`DocumentRef` ごとに未判定・暫定・確定の3値 |
| `crystalLog` | NotLoaded / Loading / Loaded。Loaded はこの Vault の結晶を新しい順に持つ |
| `crystalNotePath` | ✨タブで結晶を絞り込む、今のノートの相対パス |
| `readingTraceCleanupState` | Idle / Loading / Success / Blocked / Error |
| `readingTraceBackupState` | Idle / Working / Exported / Planned / Imported / Error |
| `notePaperTone` | 放置期間から決めた紙の地色の段階 |
| `readingTraceCard` | 再会カード。Rediscover で過去の痕跡が見つかったときだけ入る |
| `sectionChat` | 部分要約。最近作った3節分を持ち、1節ごとに節・要求の番号・見出し名・要約・生成中か・出せなかった理由と、面を出さずに頼んで理由をまだ見せていないかを持つ |
| `sideReading` | 並べ読み。Idle / Loading / Ready / Failed で、右で眺めている関連ノートを持つ |
| `folders`・`selectedFolder`・`foldersError`・`searchState` | さがすタブ |
| `wikilinkTitles` | 現ノートのリンク先タイトル |
| `todayHistory` | 当日の閲覧履歴。最大10件 |
| `vaultSelected` | Vault を選んだか |

**切替で何が落ちるか**

| 契機 | 落とすもの |
|---|---|
| ノート切替。`withNoteScopedReset()` | 紙の地色・要約・関連・余白メモの一覧とシート・部分要約・並べ読み・再会カード・結晶の絞り込みのパス |
| Vault 切替。`withVaultScopedReset()` を追加で当てる | 検索スコープ・閲覧履歴・痕跡の整理と退避・冊子の束・分野の索引・結晶の一覧 |
| 状態変換の外で落とすもの | 蒸留の状態は `DistillController` が自分で扱う。余白メモの書きかけは `NoteUiState` の外にあるので、Vault 切替でだけ `MarginMemoController.clearVaultScoped()` が捨てる。ノートを替えても書きかけは残る。並べ読みの本文の解析結果は、ジョブを止める `cancelNoteScopedJobs()` が一緒に消す |
| どちらでも残すもの | `noteState` と `wikilinkTitles`。Vault 切替の直後に新しいノートで差し替わる |

ノート切替では、ジョブの停止と状態のリセットを必ず対で行う。`NoteSessionCoordinator.onNoteChanged()` がこの2つと Loading への遷移を1手で行う。
契約の全体と、ジョブはノート単位で結果は Vault 単位という分野判定・結晶・余白メモの書きかけの形は [architecture](../dev/system/architecture.md) の判断2と判断4 にある。

---

## 5. ナビゲーションと画面構成

### 5.1 ルート

| 種別 | route | 画面 |
|---|---|---|
| タブ | `note`・`search`・`related`・`ai`・`options` | ノート・さがす・関連・AI・オプション |
| 全画面 | `note_fullscreen` | 全画面ノート。バーとシステムバーを隠す |
| 全画面 | `booklet` | 冊子 |
| 全画面 | `crystal_list` | 結晶の一覧 |
| 全画面 | `data_management`・`reading_trace_cleanup` | 痕跡の書き出しと読み戻し、孤児になった痕跡の整理 |

タブ間は `navigateToTab()` で移る。冊子から「これを読む」で渡るときだけ `note → booklet → note` と積み、`BookletRouteContractTest` と `BookletNavigationTest` が固定する。
束はメモリにしか無いので、プロセス復元で冊子ルートだけが戻ったらノートタブへ返す → [tab_navigation](../dev/system/tab_navigation.md)。

### 5.2 並べ方

`AppScaffold` は Expanded 幅で左のレール、それ以外で下のバーを出し、全画面ルートではタブを出さない。本文はどの形でも最初の子として組むので、Fold の開閉で作り直しても保存値が同じ位置へ戻る。

ノートタブの並べ方は `readerLayoutFor` が上から決める。

| 並べ方 | いつ | 中身 |
|---|---|---|
| 左右2列 | 低い横長 | 左に見出し・操作・再会カード、右に本文 |
| 余白ペイン | ペインの設定が開いていて、縦の折り目があるか、横幅が Expanded | 左に本文、右に「この部分」。ペインが出ている間は机の画面になる |
| 縦積み | それ以外。横の折り目も | 見出し・操作・再会カード・本文 |

並べ方は端末の種類ではなく窓の大きさで決め、キーボードを除いた大きさを使う。折り目は `FoldingFeature` から取り、本文領域の座標へ直す。

**机の画面。** 余白ペインが出ている間は、レール・見出し・操作の行をしまい、本文とペインを同じ大きさで並べる。
操作はつまみを下へ払って出す帯に入り、横に払うと片側を1画面にできる。操作の判定と動きは [margin_pane](../dev/features/margin_pane.md) §5.1、面の契約は [fold_experience](../dev/system/fold_experience.md) が持つ。

### 5.3 画面ごとの責務

- **ノート**: ランダム表示と `📖` で冊子。本文と文字の選択。見出しの右に `💬`・`✎`・`⛶`、机の画面では帯の中。本文の見出しの脇にメモの件数の印。Rediscover で痕跡があれば再会カード。本文のリンクを押して開く。読書位置を痕跡へ報告する
- **さがす**: 第一階層のフォルダのチップ、AI で3件かランダムで3件、今日読んだノート
- **関連**: 規則の候補と AI 推薦を別の節に出し、AI の状態を出し分ける
- **AI**: 要約、今のノートを根拠に含む結晶と一覧への入口、蒸留の起点、モデルのダウンロードの進捗
- **オプション**: Vault の変更、データ管理、ダークモード。ダークモードは OS に追従しない
- **通知**: Snackbar で知らせる機能は無い。バックグラウンドの AI は記号と面の中身で静かに示す

---

## 6. 主要機能のデータフロー

各機能を同じ形で書く。先頭に1文、次に **入口**・**状態**・**書く先**・**AI**・**正本**、続けて **流れ** と **約束**。当てはまらない項目は省く。
**約束**はデータの整合・直列化・世代の照合に関わる不変条件だけを書き、表示の細かい規則と理由は正本が持つ。

### 6.1 Vault選択と復元

Vault を選ぶと、旧 Vault に属する処理を止めてから新しい Vault を指す。

- **入口**: ノートタブの選択ボタン、オプションの「Vaultを変更」
- **状態**: `vaultSelected`。**書く先**: `SharedPreferences` に Vault の URI
- **正本**: [architecture](../dev/system/architecture.md) の判断4、[saf_boundary_gateway](../dev/system/saf_boundary_gateway.md)

```text
OpenDocumentTree → 読み書きできる永続URI権限を取る → NoteSessionCoordinator.onVaultChanged()
  ① 記録中の読書セッションを保存せずに捨てる
  ② vaultGeneration を進める
  ③ 新しい Vault を指し、全体ノートと関連ノートのキャッシュを捨てる
  ④ Vault 単位の状態とジョブを捨て、ノート単位のジョブを止める
→ ランダムノートを1件読む
```

- ①〜③の順序を変えない。変えると旧ノートの痕跡が新しい Vault へ入るか、旧世代の結果が素通りする
- 次回の起動は保存した URI を戻すだけで、ノートは自動で読まない。起動OP はコールド起動でだけ流し、ランチャーの再タップは `LauncherEntry` が畳む

### 6.2 ランダムノート表示

Vault の全 `.md` から1件を引いて開き、ノートに結び付く処理を始める。

- **状態**: `noteState`・`wikilinkTitles`・`todayHistory`
- **正本**: [rediscover](../dev/features/rediscover.md)

```text
loadRandomNote()
  → 旧ノートのジョブを止め、Loading への遷移とノート単位の状態のリセットを1手で行う
  → Vault 全体を走査する。60秒以内ならキャッシュを使う
  → _AI補記 と _ReadingTraces を除いた .md から1件を選び、先頭の BOM を外して読む
  → 読書セッションを始め、noteState = Success、閲覧履歴に記録する
  ├── 再会カードの照合。Rediscover のときだけ
  ├── 要約・関連ノート・分野判定・結晶
```

`openNote` も同じ経路を通る。Markdown が無ければ `Empty`、読めなければ `Error` になる。

### 6.3 ノート要約

開いたノートを2〜4文に要約する。同じ入力の要約は端末に残し、開き直しても作り直さない。

- **状態**: `summaryState`
- **書く先**: `noBackupFilesDir/summary_cache/`。鍵は完成したプロンプトの SHA-256 で、1件1ファイル、古い順に消す
- **AI**: 1回。3秒の門番の後
- **正本**: [note_summary](../dev/features/note_summary.md)、[background_ai_ux](../dev/system/background_ai_ux.md) §7

**流れ**: `Ready` なら保存済みを出すか、門番を待って生成する。`NeedsDownload` ならダウンロードを始めて進捗を出し、終わったら要約と関連ノートをやり直す。
`Downloading` は何もしない。`Unsupported`・`TemporarilyUnavailable` は `AiUnavailable` で、要約パネルを出さない。失敗は `Error`。

- 保存済みを引くところまでは門番の手前で行う。開き直したノートの要約は待たされない
- 古い要求の結果は requestId で捨てる

### 6.4 関連ノート

規則で決まる候補と、AI が選ぶ候補を2段で出す。AI が失敗しても規則の候補は出る。

- **状態**: `relatedNotesState`。失敗の枝は持たない。走査キャッシュに依存するので `NoteViewModel` に残っていて、Controller になっていない
- **AI**: 1回。候補のIDだけを返させる。3秒の門番の後
- **正本**: [related_notes_ai](../dev/features/related_notes_ai.md)

**流れ**: 規則は、本文の wikilink に一致するノートと、ファイル名の採番が同じグループのノートを最大5件拾う。
AI はタイトルの話題で全 Vault から40候補に絞り、本文で肉付けして並べ直し、一時ID `C01..` を振って予算内へ縮めて渡す。返ったIDを実ノートへ解決し、規則と重複を除いて最大5件を返す。

- AI が使えない、未準備、失敗、のどれでも規則の結果だけを返す。自動で走る機能なので理由は見せない
- 候補1件の本文が読めなくても、その候補だけをタイトルで続ける。推薦全体を巻き添えにしない
- 開いた Fold のペインの「このノートの関連」も同じ候補を使い、そこから AI を呼ばない

### 6.5 さがす

キーワードか自然文で3件を選ぶ。AI を使わずにランダムで3件も選べる。

- **状態**: `folders`・`selectedFolder`・`foldersError`・`searchState`
- **AI**: 自然文検索で1回。タイトルの一覧だけを渡し、本文は渡さない
- **正本**: [ai_picker](../dev/features/ai_picker.md)

**流れ**: ルート直下のスコープは再帰せず、第一階層のフォルダは再帰で走査する。候補が多ければ文字の重なりで絞ってから AI が3件を選び、AI が使えなければ文字の重なりの順で選ぶ。

- 検索とランダムは同じ `searchState` を書くので、ジョブ1本と requestId を共有し、新しい要求が前を止める
- `CancellationException` は結果型に畳まずに投げ直す

### 6.6 部分要約

いま読んでいる節を2〜4文に要約し、「この部分」の面に出す。最近作った3節分を持つ。

- **入口**: 見出しの右の `💬`、面の中の「この節を要約」。全画面には置かない
- **状態**: `sectionChat`。入口の記号は `sectionSummaryStatus` が4つの状態から導く
- **AI**: 押したときに1回。節の本文は1,500文字まで
- **正本**: [section_ai_chat](../dev/features/section_ai_chat.md)、[margin_pane](../dev/features/margin_pane.md)

**流れ**: 節は、スクロールが止まったときに先頭に見えているブロックの節である。`💬` を押すと、面が出ていれば要約の行へ送り、出ていなければ面を出さずに裏で作る。
端末AIが `Ready` なら生成し、それ以外は説明と導線を要約の行に出す。面を出さずに頼んで理由が届いたときは、その節を本文が映したときに面を出して一度だけ見せる。

- 要求ごとに番号を振り、番号が一致する要約にだけ書く。生成は一度に1本で、別の節を頼むと前を取り消す
- ノート切替・Vault 切替と、蒸留で本文を読み直したときに全部捨てる

### 6.7 余白メモと「この部分」

本文の隣の「この部分」に、今の節のメモ・前回の読書の跡・部分要約・書く欄を集める。
開いた Fold ではペインとして本文の右に置き、そのあいだノートタブは机の画面になる。それ以外の窓と全画面では本文と併存するシートになる。中身は1つの部品が組み、置き場所だけが変わる。

- **入口**: `✎`、全画面の右下の `✎`、本文の見出しの脇の印、再会カードの「前回のメモを見る」、`💬`
- **状態**: `marginMemoState`・`isMarginMemoSheetVisible`・`sideReading`。書きかけは `NoteUiState` の外の `ComposeMarginMemoDrafts`、ペインの開閉は設定の `marginPaneOpen`
- **書く先**: `_ReadingTraces/*.json` の `memos` 配列。Vault の `.md` には触れない
- **AI**: 余白メモと並べ読みは呼ばない。呼ぶのは同じ面に載る部分要約の行だけ
- **正本**: [margin_pane](../dev/features/margin_pane.md)、[reflect_margin_memo](../dev/features/reflect_margin_memo.md)

```text
ノートを表示した後に1回だけ読む。永続・保存待ち・預かりの3箇所を合流したメモと、前回の訪問を返す
書き始めた瞬間の本文の節が書き込み先になり、文字がある間は動かない
置く → 送信を書きかけに控えてから保存を頼む
  保存・預かり → 受理       満杯・失敗 → 未受理。文字を残す
  結果を待たずにノートを離れたら、戻って読んだ一覧と送信そのものを照合する
```

**並べ読み。** ペインの「このノートの関連」から選ぶと、右がそのノートの本文に替わる。右は眺めるだけで、訪問・履歴・要約・分野判定・メモのどれにも渡さない。
`SideReadingController` が今のノートを前提にする Controller から切り離して持ち、「このノートへ移る」で普通に開き直す。

- 1回の送信で保存されるのは1件だけ。受理は件数ではなく、送信の保存値と時刻の組で照合する
- 書きかけはノートごとに持ち、同じ Vault の間はノートを替えても残す。Vault を替えると捨てる
- メモと訪問は書いたときの見出し名だけを持ち、表示のたびに `HeadingIndex` で今の見出しと照合する
- 保存と削除は同じ錠で直列化する。訪問の保存で容量が足りないときは、メモに触れず訪問だけを書く

| 上限 | 値 |
|---|---|
| メモ1件 | 1,024バイト。超えたら切り、切ったことを必ず示す |
| 1ノートのメモ | 20件。達したら置けない。古いものから捨てない |
| 痕跡ファイル全体 | 128KB |
| 未確定の送信 | 1ノート1件 |

### 6.8 当日閲覧履歴

その日に開いたノートを最大10件、さがすタブに出す。状態は `todayHistory`、書く先は `SharedPreferences` の日付キー付きの JSON。
日付が変わると消え、同じノートは先頭へ移る。

### 6.9 蒸留

AI が原文の重要箇所を選び、ユーザーが確認した箇所だけを元ノートで `**太字**` にする。AI は文章を作らず、選ぶだけ。

- **入口**: AIタブ。**状態**: `distillState`
- **書く先**: 元ノートの `.md`。`**` を挿すだけで、既存の文字は消さない
- **AI**: 1回。候補のIDだけを返させる
- **正本**: [reflect_distill](../dev/features/reflect_distill.md)

```text
本文を文へ分ける。UTF-16 のオフセットを保ち、保護範囲には境界を置かない
  → AI を使わずに採点して候補を絞る → AI が候補のIDを返す。渡した候補の外のIDは捨てる
  → 調整シートで範囲を選ぶ → オフセットの降順で ** を挿す
  → DistillWriteRepository が保存し、本文だけを読み直す
```

- 保存は原バイトの SHA-256 を二重に照合し、復旧レコードを原子的に確定してから書き、出力のハッシュを確かめる。元のファイルの BOM は戻す
- 中断したら起動時に復旧を判定する。復旧はノートをまたいで残す
- インライン記法の解釈は表示と同じ `scanInlineSyntax` を使う

### 6.10 読書痕跡と再会カード

読書位置を自動で記録し、Rediscover で同じノートを引いたときだけ、前回どこまで読んだかとその中身を再会カードに出す。

- **状態**: `readingTraceCard`。枠に何を出すかは、カードと `summaryState` から純関数 `reunionSlot` が決める
- **書く先**: `_ReadingTraces/<sha256(相対パス)>.json`。ベストエフォートで書く。前後の要約だけは `noBackupFilesDir` に置く
- **AI**: 再会1回につき多くて1回。3秒の門番の後
- **正本**: [reflect_reading_trace](../dev/features/reflect_reading_trace.md)、[reunion_card](../dev/features/reunion_card.md)

訪問の記録と余白メモの保存は `ReadingTraceController`、照合・前後の要約・問いの選別は `ReunionCardController` が持ち、サイドカーの錠を共有する。

```text
読んでいる間  最後に見えたブロックと割合を報告し、最も深い位置をメモリで更新する
離れたとき    能動読書が10秒以上なら訪問を記録し、開いた時点の Vault キーを添えて保存する
Rediscover    同じ痕跡への保存が走っていれば待つ → 見出しの1文だけのカードをすぐ出す
  途中まで    読み進めたところの前後を、保存済みの要約か Nano で2文に要約する
  読了        当時の問い・古い前提の候補があれば Nano が1件選ぶ。無ければノートの要約の先頭1文
```

- サイドカーは schemaVersion・バイト上限・checksum で検証し、壊れていればカードを出さない
- 訪問は直近30件を持ち、延べ回数は別に積む
- 1回目の再会から枠を出す。照合が待つのは同じ痕跡への保存だけで、別の痕跡の保存が止まっていても待たない
- 問いと古い前提の候補は、抜粋ではなく原文全体から規則で並べる。候補が無ければ Nano を呼ばない
- 読書時間は背面と冊子の間を数えない。止める理由は `ReadingPauseReason` の集合で持つ
- 保存と照合の要求は開いた時点の Vault キーを運び、書く直前に今の Vault と照合する

### 6.11 読書痕跡の退避と読み戻し

痕跡を1ファイルへ書き出し、そこから読み戻せる。書いた余白メモは作り直せないので、フォルダごと失ったときの退避先になる。

- **入口**: オプションの「データ管理」。**状態**: `readingTraceBackupState`
- **書く先**: 選んだ場所に1つの JSON。読み戻しでは `_ReadingTraces/`
- **正本**: [reading_trace_backup](../dev/features/reading_trace_backup.md)

**流れ**: 書き出しは痕跡を全部まとめて1つの JSON にする。読み戻しは下見を出し、ユーザーが適用を選んで初めて書く。適用中は中止できる。

- 端末に無かった痕跡を受け入れる形で併合し、既存を黙って上書きしない。メモは欄ごとに合流し、上限を超えるノートは保留する
- 走査と JSON の処理は Main の外で行う

### 6.12 冊子

ランダムを1回押して10枚の束を作り、上へ繰る。冊子の中では AI も訪問の記録も走らない。

- **入口**: ノートタブの `📖`。**状態**: `bookletState`。Vault 単位
- **AI**: 呼ばない
- **正本**: [booklet_mode](../dev/features/booklet_mode.md)

**流れ**: 束は参照・タイトル・扉の1行だけを持ち、本文は持たない。扉は今のページと前後1ページだけを境界付きで読み、本文から代表の1行を選ぶ。生成はしない。
「これを読む」で通常表示へ渡ったときに、初めて訪問の記録・要約・関連ノートが始まる。
**編む束**は、冊子へ入る直前のノートを種に、済んだ AI 推薦から2つ目の束を作る。追加の AI 呼び出しも I/O も無い。

- 束の寿命は Vault 単位で、ノートから戻れば同じ束が同じページ位置で残る
- 紙の形の役割は役割トークンから引き、`BearingChannelTest` がどの面がどの役割を引くかを固定する。紙の地色は分野が持つ → §6.13
- 冊子へ戻ったときは、走っている「これを読む」を取り消す

### 6.13 分野判定

ノートを開いたときに、AI が固定リストの6分野から1つを選ぶ。冊子の紙と結晶の宝石の色になり、画面には進捗も失敗も出さない。

- **状態**: `noteFields`。分野の索引A。Vault 単位
- **書く先**: 確定を `SharedPreferences` に1件1キーで。バックアップからは除く
- **AI**: 1回。抜粋とフォルダ由来のヒントを渡し、IDを1つ返させる。3秒の門番の後
- **正本**: [note_field_color](../dev/features/note_field_color.md)

```text
走査      フォルダ名を辞書と照合し、当たればヒントを暫定として索引Aへ載せる。メモリだけ
開いたとき 現在の入力版の確定があれば何もしない
          → 無ければ索引B、つまり入力指紋から答えへの表を引く
          → それも無ければ AI。分野か NONE を確定として永続する。失敗は暫定のまま、キャンセルは何も書かない
```

- 未判定・暫定・確定の3値は、同じ入力版の中では一方向にしか進まない。入力指紋は AI へ渡したものすべてを含む
- 索引Aへの書き込みは1箇所に直列化し、確定を消さない
- ジョブはノート単位、索引は Vault 単位。モデルのダウンロードを自動で始めない

### 6.14 結晶

読んできたノートの要約から、別々のノートに共通する筋を AI が1文にまとめ、Vault に溜める。

- **入口**: ノートを開いて相対パスが確定したとき。✨タブと結晶の一覧
- **状態**: `crystalLog` は Vault 単位、`crystalNotePath` はノート単位
- **書く先**: `_ReadingTraces/crystals/` に1件1ファイルで、既存は書き換えない。材料の控えは `noBackupFilesDir`
- **AI**: 1日に多くて1回。今のノートと他の最大5件の要約の断片を渡す
- **正本**: [reflect_crystal](../dev/features/reflect_crystal.md)

```text
冊子と背面を除いて合計30秒読むのを待つ（NoteReadGate）→ 要約を待つ → 材料を控える
  → 今日まだ試しておらず、まだ渡していない材料が3件ある → 再会カードの照合を待つ → Nano
  → 選択と1文が読めたら保存を依頼する。保存はノート切替で止めない
  → 保存が成功し、Vault の世代が同じなら一覧へ1回だけ足す
```

**一覧**は月の棚に宝石を新しい順に並べ、押した宝石の段の直下に中身を開く。宝石は根拠のノートの分野の色で、2分野なら左右半々に塗る。
分野は冊子と同じ索引を走査結果の参照から引くので、新しい AI 呼び出しは無い。開いた中身が画面からはみ出すときは、一覧の側が送る。

- 試す条件の判定・送信・記録は、予算で落とした後の同じ候補の集合で行う
- 生成は requestId、一覧への反映は `vaultGeneration` で照合する。守るものが違う
- 一覧の読み込みは、その Vault への保存が終わるのを待ってから列挙する
- 生成中も失敗も画面に出さず、モデルのダウンロードを始めない

### 6.15 ノート内のリンク

本文の `[[ノート]]` と `[文字](ノート.md)` を押すと、そのノートを開く。見出しつきのリンクはその見出しから開く。

- **入口**: 通常表示と全画面の本文のリンク。補助の面で眺めているノートの中のリンクは押せない
- **状態**: 持たない。始まりの位置は画面の保存値
- **正本**: [note_links](../dev/features/note_links.md)

```text
軽く押す。長押しの後に指を離しても開かない
  → 押せるかは記法だけで決まっている → 走査結果のリンクの索引から1件を引く
      補助の面を出せる窓   補助の面に出す      それ以外と全画面   今のノートとして開く
      同じノート           見出しへ送る        見つからない       知らせだけを出す
```

- 同じ名前のノートは、今のノートと同じフォルダ、パスの短いもの、辞書順の順で1つに決める
- リンクの索引は本文を書き換えても捨てず、Vault 切替でだけ捨てる

---

## 7. SAF・Vaultアクセス層

- **走査。** `NoteRepository` は `queryChildren()` にカーソル処理を集め、`ArrayDeque` の幅優先でフォルダを辿る。再帰しないので深いフォルダでもスタックを使わない。取る列は document ID・表示名・MIME type・最終更新日
- **読み込み。** `BoundedInputStream` で上限付きに読み、末尾の欠けた多バイト文字を落としてから UTF-8 にする。復号は `NoteSnapshot.kt` に集め、先頭の BOM を1つ外す。表示は寛容に、蒸留は不正な UTF-8 を例外にして読む
- ファイル操作は `Dispatchers.IO` で行う。Vault の URI は `SharedPreferences` と SAF の永続URI権限で使い回す
- **メタデータ。** `parseMeta()` が frontmatter の `tags`・`aliases` と、本文の `[[wikilink]]` を抜く。frontmatter はインラインとブロックの2形式だけを読む簡易解析で、完全な YAML パーサではない
- **タイトルの正規化。** 空白・`|表示名`・`#見出し`・`^ブロックID`・フォルダ・`.md` を落とし、小文字にする。関連ノートは正規化したタイトルを Map のキーにするので、同名なら後から入った一方が残る。ノート内のリンクは別の索引で引く → §6.15

---

## 8. AI層

### 8.1 `AiClient`

AI を使う側は `AiClient` に依存する。役目は3つで、使えるかの確認・生成・モデルのダウンロードの進捗である。
実装は本番の `AICoreClient` と、手で UI を確かめる用の `StubAiClient` の2つ。

### 8.2 モデルと状態

`AICoreClient` は `ModelPreference.FULL` で `Generation` クライアントを遅延生成する。実際に動くモデルの世代は端末の AICore が決める。
ML Kit の状態はアプリ内の5状態へ写す → [background_ai_ux](../dev/system/background_ai_ux.md) §6。

| ML Kit の状態 | アプリの状態 | 呼び出し側の次の行動 |
|---|---|---|
| AVAILABLE | `Ready` | 生成する |
| DOWNLOADABLE | `NeedsDownload` | `downloadModel()` を呼んでよい唯一の状態 |
| DOWNLOADING | `Downloading` | 待つ |
| UNAVAILABLE で AICore が無い | `Unsupported` | 諦める |
| UNAVAILABLE で AICore が有る、未知の値、状態確認の例外 | `TemporarilyUnavailable(cause)` | 時間をおいて試し直す |

恒久的に非対応かどうかは `GenAiUtils.isAiCoreCompatible` で決める。`UNAVAILABLE` は対応端末でも返るため。

### 8.3 生成の制約

- **直列化。** `generate()` は共有の `Mutex` で1件ずつ実行する。どの機能が同時に来ても、生成は1本ずつ
- **タイムアウト。** 60秒で、錠を取った後から数える。先の生成が長いと、後の機能はその分だけ待つ
- **門番。** 自動で走る4本は、本文が出てから続けて3秒表示されるまで生成を呼ばない。結晶はその代わりに、冊子と背面を除いて合計30秒読む線を待つ。押して使う機能は待たせない → [background_ai_ux](../dev/system/background_ai_ux.md) §7
- **回数制限。** 錠の外側に AICore 自身の短期の回数制限がある。断られたときに日本語の案内へ置き換えるのは要約だけで、部分要約と蒸留は例外のメッセージをそのまま出す
- **途切れ。** 応答が `MAX_TOKENS` で終わったら `AiTruncatedException` を投げ、途切れた文章を保存も表示もしない
- **出力の上限。** `maxOutputTokens` は1〜256しか受け付けないので設定せず、各プロンプトの側で256トークン程度に収まる要求にする

### 8.4 プロンプトの入力上限

| 機能 | 本文の上限 | 候補の上限と出力 |
|---|---:|---|
| 要約 | 1,200文字 | 2〜4文 |
| 関連ノート | 800文字 | 候補最大40件を3,500文字内へ。IDで5件 |
| AIピッカー | 本文なし | タイトル最大40件を2,000文字内へ。3件 |
| 蒸留 | 候補の文だけ | 候補最大24件を1,500文字内へ。IDで最大6件 |
| 部分要約 | 1,500文字 | 2〜4文 |
| 再会カードの前後の要約 | 読み進めたところの前後で1,200文字 | 前と後を1文ずつ |
| 再会カードの候補選別 | 原文の1文ずつ | 種別ごとに最大10件から IDで1件。該当なしは `NONE` |
| 分野判定 | 600文字 | 6分野から IDで1件。該当なしは `NONE` |
| 結晶 | 要約から作った240文字の断片 | 最大6件を2,700文字内へ。選んだIDと1文。該当なしは `NONE` |

上限は UTF-16 の文字数で数える。本文を持つ経路は、呼び出し側が `buildNoteExcerpt` で抜粋を作って渡し、`PromptBuilder` は切らない。
上限を超えたノートは、見出しの骨格と冒頭・末尾をラベル付きで組み、注意書きを添える。抜粋の作り方は [ai_input_excerpt](../dev/system/ai_input_excerpt.md) にある。
最後に `PromptBudget.assemble()` が完成プロンプト全体へ6,000文字の上限を1回だけ当て、9本すべての builder がここを通る。

---

## 9. Markdown解析・描画

- **ブロック。** 見出し・段落・リスト・コード・水平線・引用・パイプテーブル・1行だけの画像。リストは入れ子の段数・番号の原文・タスクのチェックを持つ。段数の出し方は独自の寛容な規則 → [markdown_rendering](../dev/system/markdown_rendering.md)
- **インライン。** 太字・斜体・打ち消し線・インラインコード・`[[wikilink]]`・`[ラベル](URL)`。解釈するのは `domain/markdown/InlineSyntax.kt` だけで、蒸留も同じ関数を呼ぶ。記法を足すときは解釈器へ足す
- **リンク。** ノートと見出しへのリンクだけが押せる → §6.15。外部URLは押せない。ノートの埋め込み・脚注・HTML・数式は専用の対応が無い
- **防御。** 先頭の frontmatter は描かない。表の空セルを保ち、エスケープした縦棒でセルを割らない。強調は中身が空でなく両端が空白でないときだけ成り立つ。CRLF は LF にそろえる
- **描画。** 解析は `NoteSectionController` が Main の外で1回だけ行い、通常表示と全画面が共有する。結果が届くまで本文は描かない。描くと最大1MBの本文を Main の上で解析し直すことになる
- **位置。** 通常表示と全画面は別々の `LazyListState` を持つ。全画面は入るときにタブ側の位置から始め、離れるときにタブ側へ書き戻す
- **画像。** `![[...]]` は Vault 全体の画像索引で、完全パス、次にファイル名の順で解決する。1枚ごとに入力と復号の予算を当て、出せない理由は8通りに分けて表示側が文言を選ぶ → [note_image_rendering](../dev/features/note_image_rendering.md)

---

## 10. 並行処理・ライフサイクル・キャッシュ

### 10.1 ジョブと、古い結果の照合

ジョブの停止はノート切替なら `NoteSessionCoordinator.cancelNoteScopedJobs()`、Vault 切替なら `onVaultChanged()` が一括で行う。
キャンセルだけに頼らず照合も置くのは、モデルのダウンロードのコールバックのようにキャンセルをすり抜ける完了通知があるため。

| 持ち主 | 追跡するジョブ | 古い結果の捨て方 |
|---|---|---|
| `NoteViewModel` | `noteLoadJob`・`relatedNotesJob`・`crystalListScanJob` | 前の2つは Coordinator の `cancelHostJobs` から止まる。結晶の一覧の走査は Vault 切替で止め、走査の結果は `NoteScanCache` が公開の直前に `vaultGeneration` で照合する |
| `SummaryController` | `summaryJob`・`downloadJob` | requestId |
| `SectionChatController` | `job` | 要求の番号。結果は番号が一致する要約にだけ書く。別の節の要求は前の生成を取り消す |
| `SideReadingController` | `job` | 取り消しと、失敗を書く前の生存確認。番号は持たない。右の読み出しは同期の I/O で、取り消した後の失敗が取り消しより先に届くことがあるため |
| `DistillController` | `job`・`recoveryJob` | requestId |
| `MarginMemoController` | `loadJob`・`writeJobs` | 自前の世代番号。保存と削除は錠で直列化する。保存の結果は書きかけへ、送信そのものとの照合で届ける |
| `ReunionCardController` | `revealJob` | requestId。照合の前に同じ痕跡への訪問の保存を待つ |
| `NoteFieldController` | `job` | requestId。ノート切替でも Vault 切替でも `cancelAndClear()` が進める |
| `CrystalController` | `generateJob`・`loadJob`・保存ごとの Job | 生成は requestId で、保存へ渡す直前に照合する。保存はノート切替で止めず、一覧へ足すかを `vaultGeneration` で照合する。一覧の読み込みも `vaultGeneration` |
| `NoteSectionController` | `parseJob` | キャンセルだけ。requestId は持たない |
| `ReadingTraceController` | 訪問の保存を保存先ごとに控える。保存そのものは `persistScope` へ投げる | 開いた時点の Vault キーを書く直前に照合する。記録を頼んだ訪問は読書ごとに控え、前回の読書を選ぶときに足す |
| `SearchController` | `searchJob`・`foldersJob` | 検索とランダムは requestId を共有する。フォルダ一覧は `vaultGeneration` |
| `ReadingTraceCleanupController` | `assessJob`・`deleteJobs` | `vaultGeneration`。削除は洗い出した時点の Vault 識別子も照合する |
| `ReadingTraceBackupController` | `job` | `vaultGeneration` |
| `BookletController` | `drawJob`・ページごとの `coverJobs` | `vaultGeneration`。引き直しとすれ違った扉は新しい束へ書かない |
| `NoteSessionCoordinator` | `bookletReadJob` | 冊子へ戻ったときに取り消す |

`vaultGeneration` は `NoteSessionCoordinator` が持つ単調増加の `Long` で、`vaultUri` の比較では代用しない。
照合は `update` の直前の1箇所に集める。二層の判断は [architecture](../dev/system/architecture.md) の判断4 にある。

### 10.2 CancellationException

要約・関連ノート・部分要約・余白メモ・検索・蒸留の主要な経路で `CancellationException` を投げ直し、一般のエラーに変えない。
結果型でエラーを返す `SearchPickerUseCase` も、キャンセルだけは例外のまま通す。
蒸留の元本文の書き出しはキャンセルされてもエラーにせず、復旧レコードも消さない。

### 10.3 キャッシュ

| キャッシュ | キー | TTL | 捨てる契機 |
|---|---|---:|---|
| Vault 全体のノート。`NoteScanCache` | 現在の Vault で1件 | 60秒 | Vault 切替と本文の書き換え。走査の間に Vault が切り替わったら結果を公開しない |
| リンクの索引。`NoteScanCache` | 現在の Vault で1件 | 走査と同じ時点で替わる | Vault 切替だけ。本文の書き換えでは残す |
| 検索スコープのノート | `documentId`。ルートは null | 60秒 | Vault 切替 |
| 読書痕跡のフォルダ索引 | Vault URI | なし | Vault URI の変化、I/O例外。キーが無いときは作り直す |
| 画像索引 | Vault 世代 | あり | Vault 切替。TTL を過ぎて解決に失敗したとき作り直す |
| 分野の索引A | `DocumentRef` | なし。確定は永続 | Vault 切替。暫定は走査で作り直す |
| 分野の索引B | 入力指紋 | なし。メモリだけ | 読み込みのたびに索引Aから作り直す |
| 要約の保存 | 完成したプロンプトの SHA-256 | なし。端末に残る | 1000件を超えたとき、最後に使った時刻が古い順。ノート切替でも Vault 切替でも捨てない |
| 再会カードの前後の要約の保存 | 完成したプロンプトの SHA-256 | なし。端末に残る | 要約の保存と同じ仕組みを別の置き場で持つ。上限と捨て方も同じ |
| 結晶の一覧 | 現在の Vault で1件 | なし。メモリだけ | Vault 切替。他の端末が作った結晶は次の読み込みで届く |
| 結晶の材料の控え | Vault の名前空間 | なし。端末に残る | 控えは12件、渡した材料の記録は60件で古い順に押し出す。壊れていたら空から始める |

キャッシュがあるので、ランダム表示や検索のたびに SAF を全部走査しない。代わりに、外部の Obsidian 同期や編集の結果は最大60秒遅れて届く。
空のリストは全体キャッシュで使い回さないので、Markdown が無い Vault では操作のたびに走査し直す。

---

## 11. エラー処理とフォールバック

- ノート読込の失敗、AI 生成の失敗、モデルのダウンロードの失敗は sealed state で UI へ伝える
- 関連ノートは AI が失敗しても規則ベースの結果を保つ
- AI の空応答は、要約文・部分要約・分野判定の応答読み取りで受け止める
- プロンプトに書いた契約は検査に移す。分野判定は行の全体がIDのときだけ受け付け、蒸留は許可集合の外のIDを捨てる
- フォルダ一覧の取得に失敗したときは知らせない。閲覧履歴の JSON が読めなければ空の履歴として扱う
- `openInputStream()` が `null` なら空文字として読む。読込の失敗と空のノートは同じに見える

---

## 12. データ保護・プライバシー

- ノートの本文はアプリの中で読み、AI 生成は端末内の Gemini Nano で行う。クラウドAI API・独自サーバー・解析SDKへ送るコードは無い
- 成果物にネットワーク権限が無い。ML Kit GenAI が推移的に引く `transport-backend-cct` が持ち込む `INTERNET`・`ACCESS_NETWORK_STATE` を、マージ後マニフェストから除いている。モデルのダウンロードと生成は AICore アプリが別プロセスで行うので、こちら側に権限は要らない。初回のモデル取得には ML Kit 側のダウンロードが要る
- Vault へ書くのは3つだけ。蒸留による既存ノートへの `**` の挿入、`_ReadingTraces/` への痕跡JSONの保存、`_ReadingTraces/crystals/` への結晶JSONの追加。結晶のファイルには読んだノートの名前と要約が入り、Vault の同期先へそのまま出る
- 蒸留の上書きは原バイトの SHA-256 の二重照合と出力ハッシュの検証を通し、中断したら `noBackupFilesDir` の復旧レコードから起動時に判定する
- 読書痕跡はユーザーの `.md` に触れないベストエフォートの設計で、checksum で破損を検知するが、復旧ファイルと原子的な更新は持たない
- 痕跡の書き出しファイルは平文で、Vault 内へ保存すると Obsidian の同期でクラウドへ渡る。保存の前にそう伝える
- `android:allowBackup="true"` のまま、`random_note_prefs` はバックアップと端末移行の両方から除いている。中身は Vault の SAF URI・テーマ・余白ペインの開閉・当日の閲覧履歴・分野の確定。API 31以上は `dataExtractionRules`、API 30以下は `fullBackupContent` で覆う。要約と再会カードの前後の要約の保存、結晶の材料の控え、蒸留の復旧レコードは `noBackupFilesDir` に置く
- 端末に残す置き場を足したら除外に載っていることを、`BackupExclusionTest` が走査で数える
- 本番にログ出力は無く、ノート本文やプロンプトを logcat へ出さない。例外は debug ソースセットの計測器と instrumentation で、固定コーパスの応答を logcat へ出す

---

## 13. テスト状況

### 13.1 JVMテスト

| 置き場 | ファイル | 件数 | 何を見ているか |
|---|---:|---:|---|
| `test/.../` の直下 | 50 | 856 | Controller・保存と読込・パーサ |
| `test/.../domain/` | 47 | 585 | 純関数。採点・抜粋・スコアリング・解析・照合 |
| `test/.../ui/` | 27 | 266 | 表示ロジックを数値として固定する。色・幾何・派生状態・余白の面と机の画面の判定 |
| `test/.../architecture/` | 30 | 98 | コードではなく規約と文書を守る検査 |
| `test/.../ai/` | 8 | 33 | プロンプトの組み立てと生成失敗の判定。端末AIは呼ばない |
| `test/.../testing/` | 5 | 39 | 要約の採点器そのものの検査 |
| `test/.../fakes/` | 5 | — | 共有のフェイク |

観点と全クラスの一覧は [jvm_test_report](02_jvm_test_report.md) が持つ。

### 13.2 実行と CI

手元では `testDebugUnitTest lintDebug --offline` を通す。JDK は Android Studio 同梱の JBR を `JAVA_HOME` へ明示する。
Lint の hint の件数を数えるときは、`--rerun-tasks` を付けて解析が走ったことを確かめる。走査テストは文書・`androidTest`・`res/xml`・`src/main/java` を入力に載せてあるので、コメントだけを直してもテストが飛ばない。
CI は PR と `main` への push で、JVM テスト・Lint・マージ後マニフェストの権限検査・`assembleDebugAndroidTest` を走らせる。
instrumentation は CI で実行せず、実機で走らせる → [instrumentation_testing](../dev/system/instrumentation_testing.md) の判断4。

### 13.3 instrumentation

| 領域 | 件数 | 主なクラス | なぜ実機か |
|---|---:|---|---|
| UI・Compose の描画と操作 | 143 | `NoteReadingFlowTest`・`MarginMemoSheetUiTest`・`BookletScreenTest`・`BookletSheetPerspectiveTest`・`NoteLinkTapUiTest`・`CrystalShelfUiTest` ほか | レイアウトの実測・可視判定・押下の配送・画素 |
| SAF と画像 | 19 | `VaultScanInstrumentationTest`・`NoteImageGatewayInstrumentationTest` | 実物の `ContentResolver`・`DocumentsContract`・`BitmapFactory` |
| 端末AI | 13 | `OnDeviceGenerationTest`・`PromptTokenBudgetTest`・`SummaryCoverageBaselineTest` | Gemini Nano の実生成とトークン計測 |
| ライフサイクルとナビゲーション | 13 | `ActivityRecreationTest`・`AppScaffoldStateRestorationTest`・`TabNavigationTest`・`BookletNavigationTest` ほか | Activity の作り直しと `NavHost` のバックスタック |

土台は debug ソースセットの `FakeVaultDocumentsProvider` で、`NoteRepository` と `SafVaultBrowser` を本番のまま動かす。
`connectedDebugAndroidTest` は対象アプリを消すので実機では使わず、APK を入れて `am instrument` で直接走らせる → [device_validation](../review/device_validation/README.md)。

---

## 14. この文書の更新

- **通しで見直し、測定日を1つにそろえる。** 章を部分的に直さない。目次も同じときに直す。数字は §0 だけに置く
- **経緯と理由を書かない。** 理由は正本へリンクし、経緯は [change_history](../dev/change_history.md) と [開発日誌](journal/) が持つ。§6 は機能ごとの形を崩さない
- **検査に載せない。** オーナーが読むための俯瞰で、継続して同期する台帳ではない → [README](README.md)
