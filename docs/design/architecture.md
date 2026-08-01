# 設計思想 — アーキテクチャ（ViewModel分割・状態管理）

**対象領域:** 横断的なコード構造・状態管理・並行処理の規約
**初版:** 2026-07-19（品質改善活動 PR #16〜#20）
**状態:** 実装済み。Controller共通化は2026-07-26の5件目再判定まで含めて決着（**共通化せず、相似のまま維持**。以後は件数をトリガーにしない）。非同期の世代IDは二層（Vault単位＝共有／ノート単位＝Controller自前）で確定。2026-07-27に依存生成・状態所有・パッケージ依存の境界を型とJVMテストで固定し、AI本文の切り出し責務も同じ依存方向に沿って呼び出し側へ移した。2026-08-01〜02に、この依存固定が `android.*` を数えていなかったこと（`model` に `Uri` が残る）を明らかにし、対処は別文書へ切り出した。現在は `model` / `domain` / `controller` の3層が Android 非依存としてCIで固定されている。

---

## 背景

機能追加を重ねた結果、`NoteViewModel` が906行まで肥大化し、状態リセット処理の重複（3箇所）による「リセット漏れ→旧状態の残留」バグが複数発生していた。静的分析で27項目を指摘し、同日中に全件解消した活動の中核が本構造である。

## 判断1: 単一ViewModel＋機能Controller方式

マルチモジュール化や機能別ViewModel化ではなく、「`NoteViewModel` は窓口として残し、実装を機能Controllerへ委譲する」方式を採った。

```
NoteViewModel（Android境界の窓口）
 └── NoteSessionCoordinator（横断調停・状態所有）
      ├── NoteUiStateStore（機能別Writerを配る）
      ├── SectionChatController
      ├── QuizController
      ├── AnnotationController
      ├── SearchController
      ├── DistillController        ← PR #32 で追加
      ├── ReadingTraceController   ← ReadingTrace v1 で追加
      └── SummaryController        ← 2026-07-26（ViewModel直書きから切り出し）
```

分割時点では 906行 → 348行・Controller 4つ。現在は機能追加を経て Controller 7つで、**窓口の肥大化は再発していない**（追加分はController側に載っている）。要約だけは分割時に取り残されて `NoteViewModel` 直書きのまま残り、そこだけ世代管理が抜けて実害になった（→ 下記「2026-07-26」の2節）。

- 各Controllerは実行スコープと機能別の `*StateWriter` を注入され、**担当フィールド以外は型として書けない**
- `NoteUiStateStore` だけが `MutableStateFlow<NoteUiState>` を所有し、UIには読み取り専用の `StateFlow` を公開する
- 公開APIと `uiState` の形を維持したため、UI層の変更ゼロで移行できた
- 状態の単一ソース（1つの `NoteUiState`）は維持。テーマだけは独立した `StateFlow<Boolean>` とし、状態17項目の変更でアプリ最上位まで再評価されないようにした

## 判断2: 結合点を「明示契約」に変換する

ノート切替・Vault切替時の後始末は、各所に散らばせず2つの契約に集約した。

| 契約 | 役割 |
|------|------|
| `NoteSessionCoordinator.cancelNoteScopedJobs()`＋各Controllerの `cancelAndClear()` | 実行中AIジョブの停止（旧ノートの結果混入と、Mutexロックの占有継続を防ぐ） |
| `NoteUiStateStore` の `withNoteScopedReset()` | ノート単位状態の一括リセット（リセット漏れを構造的に防ぐ） |

`NoteSessionCoordinator.onNoteChanged()` がジョブ停止と `beginNoteLoad()` を1手で呼ぶ。呼び出し側へ2手を公開しないことで、「状態だけ消したが旧ジョブは生きている」という中間状態を作らない。

**機能追加の定型**: Controller 1ファイル＋状態1フィールド＋対応するWriter＋この契約2箇所への登録。純粋ロジックは最初から別ファイルに切り、テストを同時に書く。

## 判断3: 壊れやすいロジックは純関数に切り出す

`QuizResponseParser`・`AnnotationComposer`・`MarkdownParser`・タイトル正規化などの文字列処理はAndroid I/Oから分離し、素のJVMユニットテストで回帰を防ぐ。テスト設計の過程で実バグ（補記Markdownの字下げ混入）も発見された。**テストは検証だけでなく発見の道具になる。**

## 教訓: 重複が品質問題の温床だった

27項目の多くは「同じ形のコードを複数箇所に書いた」ことに起因していた（状態リセット3重複・SAFカーソルループ5重複・タブ遷移2重複・AIタイトル整形2重複）。**同じ形を2度書いたら共通化を検討する**を目安とする。

ただし共通化は早すぎても失敗する。QuizとAnnotationのController相似形は2件の段階では意図的に共通化せず、3件目が現れてパターンが確定してから抽出する方針とした（[background_ai_ux](background_ai_ux.md) 参照）。→ **この判断は下記「追記 2026-07-24」で決着済み（結論: 共通化しない）。**

## 並行処理の規約

- AI生成は `AiClient` 側のMutexで直列化し、60秒タイムアウトを設ける
- ノート・Vault単位のジョブは追跡してキャンセルする
- `CancellationException` は再throwし、一般エラーへ変換しない
- 完了通知がキャンセルをすり抜ける経路には requestId＋`isCurrent()` ガードを併用する

---

## 追記

### 2026-07-20 — 教訓2件（AI補記の途切れ調査〜フォーカス周辺クイズ実装より）

**1. SDKの制約は公式ドキュメントでなくバイナリで確認する。** genai-prompt beta2 の `maxOutputTokens` に1024を設定したところ、SDK内部のバリデーション「1〜256」に弾かれ全AI生成が失敗した（要約エラーとして発覚）。公式ドキュメントの「256トークン超の出力は避けるべき」は推奨ではなくAPIレベルの強制だった。ローカルのGradleキャッシュからAARを展開しclassファイルの文字列を調べれば、バリデーション文言・メソッド有無・定数を確実に確認できる。実装前にこれをやっていれば1往復防げた。

**2. `pointerInput(Unit)` は初回コンポーズ時のクロージャを固定する。** 吹き出しのタップ処理が古い状態（セッション有無）を抱き込んだクロージャを呼び続け、「クイズ画面から戻る→確認終了→タップ」で完全な無反応になった。画面遷移から戻ると「その時点の状態」でコンポジションが再生成されるため、初回＝素の状態という思い込みは通用しない。**外から渡されたラムダを `pointerInput` / `LaunchedEffect` 等の長寿命ブロック内から呼ぶときは `rememberUpdatedState` を通す**を定石とする。

### 2026-07-24 — Controller共通化の判断（結論: 共通化せず、相似のまま維持）

`DistillController`（PR #32）が3件目の Controller となり、「3件目が現れたら共通パターンを抽出する」という保留の期限が来た。実装を突き合わせた結果、**共通化しない**と決めた。

**共通しているのは requestId ガードだけだった。**

| 要素 | Quiz | Annotation | Distill |
|---|---|---|---|
| `++activeRequestId` で採番 | ✓ | ✓ | ✓ |
| suspend地点の後の `isCurrent()` 確認 | ✓ | ✓ | ✓（`ifCurrent {}` 形） |
| `cancelAndClear()` | ✓ | ✓ | ✓ |
| モデルDLを**自動**開始して完了後に自動再開 | ✓ | ✓ | **✗（明示タップ）** |
| Snackbar通知＋`isViewed` の未確認管理 | ✓ | ✓ | **✗** |
| 責務の形 | 生成して終わり | 生成して保存して終わり | **候補提示→ユーザー選択→Vault書込→検証→復旧判定** |

- **DLポリシーが逆**: Quiz/Annotation は `NeedsDownload` を検知すると `pending` に積んで自動DL→自動再開する。Distill は `DistillState.NeedsDownload` で停止し、ユーザーが明示的にタップして初めて `downloadModelAndResume()` が走る。これは「未ダウンロードは明示確認・自動DLしない」という [reflect_distill](reflect_distill.md) の意図的な設計判断であり、共通基底に吸収すると設計意図が消える。
- **状態数と寿命が違う**: Quiz/Annotation は Idle/Loading/Success/Error の4状態。Distill は11状態（AI生成に加えVault書き戻しの競合・中断復旧まで表現する）。
- **したがって抽出できるのは「requestId採番＋`isCurrent()`」の数行だけ**で、`background_ai_ux` が想定していた「バックグラウンドAI機能の共通基底」にはならない。数行のために継承/委譲の1階層を増やすのは、判断1（窓口は薄く、実装はControllerへ）の見通しを下げるだけと判断した。

**教訓の更新**: 「同じ形を2度書いたら共通化を検討する」は維持する。ただし**検討の結果「しない」も正当な結論**であり、3件目の到達は共通化の実行トリガーではなく**判断のトリガー**である。今回のように「共通なのは3行、周りは全部違う」と分かったこと自体が、保留していた価値の回収にあたる。

**再検討の条件**: 4件目が現れ、かつそれが Quiz/Annotation 型（自動DL＋Snackbar＋`isViewed`）に**そのまま乗る**場合。2026-07-25に4件目の `ReadingTraceController` で再判定したが、未DL時は黙って諦め、Snackbarも`isViewed`も持たないため、この型には乗らなかった（次節参照）。

### 2026-07-25 — 4件目（ReadingTraceController）での再判定: やはり共通化しない

上記の再検討条件に沿って、4件目である `ReadingTraceController`（[reflect_reading_trace](reflect_reading_trace.md)）を Quiz/Annotation 型と突き合わせた。結論は**乗らない**ので、共通化のトリガーにはならなかった。

| 要素 | Quiz / Annotation | ReadingTrace |
|---|---|---|
| モデルDLを自動開始して完了後に自動再開 | ✓ | **✗（未DLなら黙って諦める）** |
| Snackbar通知＋`isViewed` の未確認管理 | ✓ | **✗（通知しない）** |
| 失敗をユーザーへ見せる | ✓ | **✗（黙って劣化する）** |
| 起動契機 | ユーザーの明示操作 | **ノート表示・離脱（ユーザーは操作しない）** |

**「ユーザーが意識しない機能」であることが、共通化を拒む本質だった。** Quiz/Annotation の共通部分は「生成中/完了/失敗をどう通知するか」に集約されているが、ReadingTrace はそのすべてを**出さない**のが仕様である。共通基底に載せると、まず通知経路を無効化する分岐を足すことになり、抽象が薄まるだけになる。

一方で ReadingTrace 固有の関心事（読書セッションのスナップショット、離脱時の1回だけの書き込み、read-modify-write の直列化）は他の4つに存在しない。**共有できるのは依然として requestId ガードの数行だけ**で、2026-07-24 の判断がそのまま維持される。

**教訓の更新**: 「4件目が同じ型なら共通化」という条件設定は有効だったが、判定は**通知と失敗の見せ方**を軸に見るべきだった。バックグラウンドAI機能の共通性は生成処理そのものではなく「ユーザーへの見せ方」に宿るため、そこが違えば処理が似ていても共通化できない。

### 2026-07-26 — 非同期の世代IDを二層にする（A案）

ノート・Vault切替後に旧要求の結果が画面へ後着する経路が4つ残っていた（補記一覧・補記削除・フォルダ一覧・要約のモデルDL）。塞ぐにあたって、**壊れている4経路はスコープが2種類ある**ことが分かった。

| スコープ | 対象 | 無効化の契機 | 持ち主 |
|---|---|---|---|
| ノート単位 | 要約・DL・クイズ・補記生成・チャット・蒸留 | ノート切替（`cancelNoteScopedJobs()`） | **各Controllerの `activeRequestId`**（従来どおり） |
| Vault単位 | 補記一覧・補記削除・フォルダ一覧 | Vault切替（`saveVault()`） | **`NoteSessionCoordinator.vaultGeneration`**（A案ではViewModelに新設、B案で調停とともに移動） |

**混ぜられない理由がはっきりしている。** 補記管理画面はノートと無関係なので、ノートを開き直しただけで一覧が消えるのは誤りになる。既存の `cancelAndClear()` はノート切替で呼ばれるため、Vault単位の要求をそこに相乗りさせられない。逆に要約をVault世代だけで守ると、同じVault内のノート切替を検出できない。**したがって層を分けるのが正しく、片方に寄せると必ずどちらかが壊れる。**

**Vault世代を `vaultUri` の比較で代用しない。** Vault を A→B→A と選び直すと `cachedNotes` もスコープキャッシュも破棄されるため無効化したいが、Uri比較では同じ値になって素通りする。単調増加する `Long` なら選び直しも1回の切替として数えられる。副次的に、Android依存が無いのでJVMテストでも扱える。

**照合は `update` の直前に1箇所へ集約する。** `SearchController.setStateIfCurrent` / `SummaryController.setStateIfCurrent` / `AnnotationController.reloadList` が唯一の書き込み口になっている。呼び出し側に `if (!isCurrent) return` を重ねるとテストで検出できない等価な分岐が増える（実際、DL進捗に重ねた1件は変異テストで冗長と判明して削除した）。

### 2026-07-26 — 5件目（SummaryController）での再判定: やはり共通化しない

要約のモデルDL経路を `SummaryController` として切り出した。これで Controller は7つになり、**5件目として上記の再検討条件（Quiz/Annotation 型にそのまま乗るか）を再び満たすか判定した。結論は乗らない。**

| 要素 | Quiz / Annotation | Summary |
|---|---|---|
| `activeRequestId` ＋ Job 追跡 | ✓ | ✓ |
| モデルDLを自動開始して完了後に自動再開 | ✓ | **✓（初めて完全に一致した）** |
| Snackbar通知＋`isViewed` の未確認管理 | ✓ | **✗** |
| 起動契機 | ユーザーの明示操作 | **ノート表示（自動）** |

**自動DLの型には初めて完全に乗ったが、通知の型に乗らなかった。** 要約は画面の要約欄に直接出るため「見たかどうか」を管理する必要がなく、`markViewed()` に相当する概念が無い。2026-07-25 に「共通性は生成処理ではなく**ユーザーへの見せ方**に宿る」と更新した判定軸をそのまま当てると、ここでも共通化には届かない。共有できるのは依然として requestId ガードの数行だけである。

**再検討条件を更新する。** 「自動DL＋Snackbar＋`isViewed` が揃った4件目」という条件は、5件中3件が部分一致するだけで一度も揃わなかった。今後は件数をトリガーにせず、**`markViewed()` と Snackbar 通知を持つ Controller が3つ目に現れたとき**に「AI結果の未確認管理」だけを共通化する候補として再検討する（現状は Quiz と Annotation の2件）。生成・DL側の共通化は打ち切る。

### 2026-07-27 — 依存・状態・パッケージの境界を実効化する（B案）

単一 `NoteViewModel` と7 Controllerの構成は維持しつつ、境界をKDoc上の約束から型とテストへ移した。

**1. 依存生成と横断調停を分ける。** `NoteViewModelDependencies` が本番依存を組み立て、`NoteViewModel` の内部コンストラクタからテスト用依存と `CoroutineScope` を差し替えられるようにした。7 Controllerの生成・状態所有・ノート/Vault切替は `NoteSessionCoordinator` へ移し、ViewModelには `Uri`・`ContentResolver`・`SharedPreferences` を実際に扱うAndroid境界だけを残した。DIライブラリは、差し替え対象がこの1グラフだけで自動解決を要しないため導入しない。

当初の完了条件は「ViewModel越しのJVM統合テスト」だったが、`AndroidViewModel` と `Uri` は素のJVMではスタブが例外を投げ、直接生成には Robolectric またはモック依存が要る。追加依存を避け、壊れやすい調停そのものをAndroid APIを**呼ばない**Coordinatorへ出し、実物の7 Controllerを束ねた状態で直接検証する形へ読み替えた。`ContentResolver` は下位へ素通しできるが、Coordinator内部でAndroid APIを呼ばないことをテスト可能性の境界とする。

**2. 状態の所有権を型で狭める。** `NoteUiStateStore` だけが全体の `MutableStateFlow` を持ち、各Controllerへは担当スライスの `*StateWriter` だけを渡す。フラットな `NoteUiState` とUI APIは維持したまま、担当外フィールドの書き換えをコンパイル時に不可能にした。ノート単位の状態リセットはStore内の `withNoteScopedReset()` を唯一の登録点とし、`beginNoteLoad()` ではリセットと `Loading` 遷移を1回の `update` で原子的に行う。

**3. `model` を依存グラフの葉にする。** 許可する向きは次のとおり。

| パッケージ | importしてよいプロジェクト内パッケージ |
|---|---|
| `model` | なし |
| `ai` | `model` |
| `domain` | `model`, `ai` |
| `data` | `model`, `domain` |
| `controller` | `model`, `data`, `domain`, `ai` |
| `ui` | `model`, `domain` |

`NoteFile`・`HistoryEntry`・`RelatedNote`・蒸留候補など、層をまたいで共有する純データ型を `model` へ移し、4組の循環を除去した。蒸留候補は `DistillSentence` を参照するため、候補だけでなく同じ純データ群（範囲・文・チャンク・入力モデル）も一緒に移した。`model → data → domain → ai` という案は、`model` が上位実装を知って循環の起点になるため採らない。`PackageDependencyTest` がimportを走査して上表をCIで固定し、`model → data` を意図的に混入させる変異確認でも失敗することを確認した。

### 2026-07-27 — AI本文の切り出し責務を呼び出し側へ置く

要約・補記・関連ノート・セクション3経路・クイズの本文上限は、従来 `ai/PromptBuilder.kt` 内の `String.take()` が黙って適用していた。これは長いノートの後半を捨てるだけでなく、どこで入力が欠落したかを呼び出し側から見えなくしていた。

依存方向は `ai → model` のみを許可し、`ai → domain` を禁止しているため、`PromptBuilder` から `domain.markdown` の解析器は呼べない。そこで共有型 `NoteExcerpt` と用途別文字数上限を葉の `model`、見出し骨格＋冒頭＋末尾を作る純関数 `buildNoteExcerpt` を `domain` に置き、`domain` / `controller` の各呼び出し側で抜粋を完成させてから `ai` へ渡す構成にした。`PromptBuilder` は切り出さず、抜粋状態に応じた注意書きとプロンプト整形だけを担う。

この型が保証するのは「生の `String` を誤って直接渡さない」ことまでで、同一Gradleモジュール内の生成箇所を封じるものではない。壊れやすい文字列処理をAndroid I/Oから分離してJVMテストで固定する形になっており、判断3の横展開にあたる。

**純関数化は「安く」を意味しない。** レビュー時の実測で、最大1MBの本文のMarkdown解析はデスクトップJVMでも約460msを要し、呼び出し元の `viewModelScope`（Main）を占有することが分かった。従来の `take()` が実質ゼロコストだったため、切り出しを賢くした結果として新しい負荷が持ち込まれている。抜粋生成だけを `Dispatchers.Default` へ移し、本番既定値が `Default` の `excerptDispatcher` を各クラスへ持たせた（JVMテストではテストスケジューラへ差し替える）。**Mainのスコープから呼ばれる純関数は、純粋であることと軽いことが別問題になる**ため、入力サイズに比例するものは境界を明示する。呼び出しが再びMainへ戻らないことは、Controller一覧ではなく「その関数を呼ぶ全箇所」を対象にしたソース走査テストで固定する。

抜粋の中身（捨てる場所の選び方・見出し骨格・予算配分・正規化の副作用・モデルへの伝え方）は独立した設計領域なので、[ai_input_excerpt](ai_input_excerpt.md) が持つ。

### 2026-07-31 — 表示用Markdownも同じ境界へ載せる（F-1）＋ 状態がUiStateの外に出る2例目

前節でAI入力の抜粋生成を `Dispatchers.Default` へ逃がしたが、**同じパーサを使う表示経路はMain上に残っていた**。`NoteReaderTab` と `FullscreenNoteScreen` がそれぞれの `remember` で `buildNoteSectionModel()` を同期実行し、表示フォールバックは最大1MBまで許可されている。つまり長文を開く瞬間と全画面へ入る瞬間の2回、UIスレッドが止まり得た。Composable が別なので結果も共有されず、同じ本文を2回解析していた。

**これはテーマ8（横展開は最後の1本を取り残す）そのもの。** 前節の教訓は「Controller一覧ではなく同じAPIを呼ぶ全箇所を検索対象にする」だったが、当時の走査対象は `buildNoteExcerpt` だけで、**同じ重さを持つ `buildNoteSectionModel` は数えていなかった**。「入力サイズに比例する純関数をMainから呼ばない」という性質で引くべきところを、関数名で引いていた。

**1. 状態を `NoteUiState` へ入れない（テーマに次ぐ2例目）。** 依存方向の規約で `model` は葉であり何も import できない。`NoteSectionModel` は `domain.markdown` にあり、`surroundingContext()` のような振る舞いを持つため純データ型として `model` へ移すこともできない。したがって `NoteUiState` には入れられず、独立した `StateFlow<NoteSectionModel?>` を `NoteSectionController` が持ち、`MainActivity` が `noteListState` と同じように両画面へ配る。**「状態の単一ソース」の例外は、テーマ（再コンポーズ範囲が理由）とこれ（パッケージ境界が理由）の2つ**になった。3つ目を作るときは、`model` を葉に保つ判断自体を見直す合図と考える。

**2. 本文が変わる経路は2つで、両方に解析開始を置く。** `setNoteState()`（通常の読込）と `applyReloadedBody()`（蒸留保存後の差し替え）。呼び出し側へ配らず Coordinator のこの2箇所へ集約したのは、片方を落とすと「本文は新しいのにブロックは旧い」状態になるため。停止は `cancelNoteScopedJobs()` へ登録する（契約どおり）。**`parse()` は現在値を null に戻さない** — 戻すと蒸留の差し替えで本文が数百ミリ秒消える。ノート切替では契約側の `cancelAndClear()` が先に走るので、旧ブロックが新しいノートへ残ることはない。

**3. 非同期化には「描かない」ガードが対で要る。** `MarkdownNoteContent` は `precomputedBlocks ?: parseMarkdownBlocks(content)` というフォールバックを持つ。解析待ちの間に本文を描くと**このフォールバックが働き、結局最大1MBをMain上で解析し直す**。Main外へ出した意味が消えるどころか、退避ぶんだけ遅くなる。そこでノート本文は解析結果が届くまで描かず、タイトルと枠だけ先に出す（プレースホルダ文言は数十文字なのでフォールバックのままでよい）。**重い処理を別スレッドへ移すときは、移した先の結果が来るまで「元の同期経路が代わりに走らないか」を必ず見る。**

**4. requestIdガードは書いた上で消した。** 他のControllerに倣って `activeRequestId` ＋ `isCurrent()` を実装したが、1行消す変異確認で**どのテストも落ちなかった**。状態を書き換える経路が `parse` と `cancelAndClear` の2つしかなく、どちらも必ず先に Job をキャンセルするため、世代照合に到達する経路が存在しない。2026-07-26 にDL進捗の重複ガードを削除したのと同じ判断で、冗長と結論して削除した。**テーマ9の運用はこの向きでも使える** — 「落ちるテストを書けないガード」は、テストが弱いか、ガードが要らないかのどちらかで、今回は後者だった。KDocに経緯を残し、再追加するなら先に落ちるテストを書くよう明記してある。

**5. 非同期の世代は三層目を作らなかった（2026-07-31・F-2）。** 蒸留の復旧チェックだけがJob管理の外にあり、遅れて届いた復旧警告を走行中の分析が上書きし得た。塞ぐにあたって「Vault単位／ノート単位」の二層のどちらにも**乗らない**ことが分かった — 復旧レコードはアプリ内部ストレージの未解決1件で、ノートにもVaultにも紐づかない。ノート単位に載せると確認中にユーザーが蒸留を始めただけで警告が捨てられ、Vault単位に載せてもVault切替と無関係に発生する。そこで**世代を増やさず、専用の追跡Job1本だけを足した**。取り下げの契機が「次の `checkRecovery()`」しかないため、世代番号で数える必要がない。**層を足す前に「無効化の契機がいくつあるか」を数えると、Jobだけで足りるか世代が要るかが決まる。**

さらに、復旧結果を反映する側で**分析の世代を進めて表示の順序を確定させた**。「遅れて届く側を捨てる」だけでは足りず、**遅れて届いた側が勝つべき場合は、その時点で相手を無効化する**必要がある。世代照合は片方向にしか効かないので、優先順位が状況で入れ替わる2つの非同期処理では、どちらが勝つかを書いた側が明示する。

### 2026-08-01 — 「葉である」を向きだけで定義したのが穴だった

2026-07-27 に `PackageDependencyTest` でパッケージ依存の向きをCIに固定したが、
見ているのは `com.example.newproject.*` の import だけで **`android.*` は対象外**だった。

そのため `model` は「プロジェクト内の何も import しない」を満たしながら、
**`android.net.Uri` だけは import する**状態で残っている（`NoteFile`・`RelatedNote`・
`HistoryEntry`・`state/AnnotationState` の4型）。`domain` にも同じ依存が1ファイル残る（`RelatedNotesUseCase`）。
`Uri` は素のJVMではスタブが例外を投げるため、これらの型を組み立てられず、
**検索実行・補記保存の世代照合は実機確認だけが担保**になっている。

**葉であることを「向き」だけで定義したのが穴だった。** テスト容易性の観点では、
プロジェクト内の依存も外部フレームワークへの依存も等しく「その層を素のJVMで扱えなくする」ため、
**同じ規則で数える必要がある**。不透明な参照型への置き換えと、`model` / `domain` からの
`android.*` 禁止をテストへ足す設計は [saf_boundary_gateway](saf_boundary_gateway.md) が持つ。

### 2026-08-01 — `controller` も Android 非依存になった

`VaultBrowser` が `ContentResolver` と Vault ルートを束ねたことで、
`SearchController` / `AnnotationController` から最後の `Uri` が消え、
`NoteSessionCoordinator` が受け取っていた `vaultUri: () -> Uri?` も未使用になって落ちた。
結果として **`controller` パッケージ全体が `android.*` を1つも import しない**状態になっている。

これは新しい制約ではなく、**もともとKDocに書いてあった約束**である。
2026-07-27 に Coordinator へ「Android API を呼ばない。`Uri` や `ContentResolver` を
引数として受け取り下位へ素通しするのは構わない」と書いたが、
**素通しを許した時点で `Uri` は残り続け、約束は import からは読めなかった。**
素通しをやめたことで、約束が「import が無い」という観測可能な事実になったので、
`PackageDependencyTest` の Android 非依存リストへ `controller` を加えた。

**「引数として受け取って素通しする」は、依存を消したことにならない。**
型として残っている限りテストはその型を作らねばならず、作れなければその経路は検証できない。
→ [saf_boundary_gateway](saf_boundary_gateway.md) §段階7
