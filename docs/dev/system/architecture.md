# 設計思想 — アーキテクチャ（ViewModel分割・状態管理）

**状態:** 実装済み・稼働中。`model` / `domain` / `controller` の3層が Android 非依存としてCIで固定されている
**最終検証:** 2026-09-12 / `23dce6b`
**関連コード:** `NoteViewModel.kt` / `controller/NoteSessionCoordinator.kt` / `model/NoteUiStateStore.kt` / `controller/`（14 Controller）
**関連テスト:** `PackageDependencyTest` / `NoteSessionCoordinatorTest` / `NoteUiStateStoreTest` / `NoteExcerptThreadingTest` / `NoteSectionThreadingTest`
**正本:** この文書

**対象領域:** 横断的なコード構造・状態管理・並行処理の規約
**経緯:** [開発日誌 2026-07](../../owner/journal/2026-07.md)・[2026-08](../../owner/journal/2026-08.md)

---

## 背景

機能追加を重ねた結果 `NoteViewModel` が906行まで肥大化し、状態リセット処理の重複（3箇所）による
「リセット漏れ→旧状態の残留」バグが複数発生していた。静的分析で27項目を指摘し、同日中に全件解消した
活動の中核が本構造である。

## 判断1: 単一ViewModel＋機能Controller方式

マルチモジュール化や機能別ViewModel化ではなく、「`NoteViewModel` は窓口として残し、実装を機能Controllerへ委譲する」。

```
NoteViewModel（Android境界の窓口）
 └── NoteSessionCoordinator（横断調停・状態所有）
      ├── NoteUiStateStore（機能別Writerを配る）
      ├── SectionChatController
      ├── MarginMemoController        ← 余白メモ（**AIを呼ばない唯一のController**。**ジョブはノート単位・書きかけはVault単位 → 判断4**）
      ├── SearchController
      ├── DistillController
      ├── ReadingTraceController      ← 訪問の記録・余白メモの保存（3箇所の合流）
      ├── ReunionCardController       ← 再会カード（照合・前後の要約・問いの選別）
      ├── SummaryController
      ├── NoteSectionController       ← 表示用Markdown解析をMainの外へ
      ├── ReadingTraceCleanupController ← 痕跡の孤児掃除（**Vault単位**）
      ├── ReadingTraceBackupController  ← 痕跡の書き出し・読み戻し（**Vault単位**）
      ├── BookletController             ← 冊子（10枚の束と扉）（**Vault単位**）
      ├── NoteFieldController           ← ノートの分野判定（**ジョブはノート単位・結果はVault単位 → 判断4**）
      ├── SideReadingController         ← 並べ読み（関連ノートを右で眺めるだけ。**ノート単位**。今のノートにしない）
      └── CrystalController             ← 結晶（**ジョブはノート単位・一覧はVault単位 → 判断4**）
```

分割時点で 906行 → 348行・Controller 4つ。現在は Controller 14個である。

**行数は倍になったが、窓口の性質は変わっていない。** 70ある関数のうち**44は1行の委譲**で、
本体を持つものは **Android 型を受け取るもの**（`Uri`・`ContentResolver`・設定の読み書き）に偏っている。
**測るべきは行数ではなく「業務ロジックが戻ってきていないか」**で、そちらは戻っていない。
（分割の動機だった906行と比べる意味は薄い — あのときは業務ロジックが同居していた。）

- 各Controllerは実行スコープと機能別の `*StateWriter` を注入され、**担当フィールド以外は型として書けない**
- `NoteUiStateStore` だけが `MutableStateFlow<NoteUiState>` を所有し、UIには読み取り専用の `StateFlow` を公開する
- 状態の単一ソース（1つの `NoteUiState`）は維持する。**例外は下記「状態が `NoteUiState` の外に出るもの」の表に挙げたものだけ**

**機能追加の定型:** Controller 1ファイル＋状態1フィールド＋対応するWriter＋契約2箇所への登録＋**この系統図の更新**。
純粋ロジックは最初から別ファイルに切り、テストを同時に書く。
（系統図は定型から漏れやすい。実際に一度更新し損ねている → [lessons L25](../lessons/L25.md)）

## 判断2: 結合点を「明示契約」に変換する

ノート切替・Vault切替時の後始末は、各所に散らばせず2つの契約に集約する。

| 契約 | 役割 |
|------|------|
| `NoteSessionCoordinator.cancelNoteScopedJobs()`＋各Controllerの `cancelAndClear()` | 実行中AIジョブの停止（旧ノートの結果混入とMutexロックの占有継続を防ぐ） |
| `NoteUiStateStore` の `withNoteScopedReset()` | ノート単位状態の一括リセット（リセット漏れを構造的に防ぐ） |

`onNoteChanged()` がジョブ停止と `beginNoteLoad()` を1手で呼ぶ。呼び出し側へ2手を公開しないことで、
「状態だけ消したが旧ジョブは生きている」という中間状態を作らない。

**Vault単位のControllerはノート単位の契約に登録しない。**
`ReadingTraceCleanupController`・`ReadingTraceBackupController`・`BookletController`・`SearchController` の一部は
無効化の契機がVault切替だけなので、
どちらの契約にも載せない（ノートを開き直しただけで一覧が消えるのは誤り）。世代も `vaultGeneration` 側を使う。
**契約2箇所への登録は「ノート単位の状態を足したとき」の定型**であって、すべてのControllerが従うものではない。
**ジョブと結果で寿命が分かれる形もある**（分野判定・結晶 → 判断4 の3行目）。

## 判断3: 壊れやすいロジックは純関数に切り出す

`DistillResponseParser`・`parseMarkdownBlocks`・タイトル正規化などの文字列処理はAndroid I/Oから分離し、
素のJVMユニットテストで回帰を防ぐ。テスト設計の過程で実バグも発見された。
**テストは検証だけでなく発見の道具になる。**

**ただし「純粋」は「軽い」を意味しない。** 最大1MBの本文のMarkdown解析はデスクトップJVMでも約460msを要する。
Mainのスコープから呼ぶ純関数は**入力サイズに比例するかどうか**を必ず見て、比例するなら
`Dispatchers.Default` へ逃がす。**逃がしたら対で「元の同期経路が代わりに走らないか」を確認する**
（`precomputedBlocks ?: parse(content)` のようなフォールバックが残っていると退避の意味が消える）
→ [lessons L13](../lessons.md#l13-純粋と軽いは別)。これは `NoteExcerptThreadingTest` /
`NoteSectionThreadingTest` / `ReadingTraceBackupThreadingTest` がソース走査で固定している。
**走査は届く範囲しか守らない** — 2026-08-26 に退避のJSON（最大8MB）が3本目としてこの穴を踏んだ。

---

## 判断4: 非同期の世代IDは二層（Vault単位／ノート単位）

世代IDは二層で、Controller の形はその組み合わせで3つある。**新しい Controller はどれか1行を選び、その行の登録をすべて行う。**

| 形 | 対象 | ジョブを止める契機（照合） | 結果・状態を捨てる契機 | 契約への登録 |
|---|---|---|---|---|
| ノート単位 | 要約・DL・余白メモ・部分要約・蒸留・並べ読み | ノート切替（**各Controllerの `activeRequestId`**。並べ読みはジョブの取り消しと、失敗を書く前の生存確認） | ノート切替 | `cancelNoteScopedJobs()` と `withNoteScopedReset()` の**両方** |
| Vault単位 | フォルダ一覧・孤児掃除・痕跡の退避・冊子の束 | Vault切替（**`NoteSessionCoordinator.vaultGeneration`**） | Vault切替 | **どちらにも載せない**。Vault切替の後始末（`onVaultChanged()`）だけ |
| ジョブはノート単位・結果はVault単位 | 分野判定・結晶・余白メモの書きかけ | ノート切替（`activeRequestId`）。**Vault切替でも同じ requestId を進めて止める** | **Vault切替だけ** | `cancelNoteScopedJobs()` と `withVaultScopedReset()`。**結果は `withNoteScopedReset()` に載せない** |

**1行目と2行目は混ぜられない。** 痕跡の整理画面はノートと無関係なので、ノートを開き直しただけで候補が消えるのは誤り。
逆に要約をVault世代だけで守ると、同じVault内のノート切替を検出できない。**片方に寄せると必ずどちらかが壊れる。**

**3行目は「どちらでもよい」ではない。** 起動の契機がノートを開くことなのでジョブはノート単位で止め、
結果はVault全体の索引なので**ノートを切り替えただけで消してはいけない**。
分野判定（索引A）と結晶（一覧）が同じ形で、**AIの結果をVault単位の索引へ溜める**機能がこの行に入る。
**利用者の書きかけをVaultの間預かる**機能（余白メモ）も同じ形になる。
登録の列を省けないようにしてあるのは、行が増えると次の Controller が契約への登録を考えなくなるため。

- **分野判定**は照合を requestId だけで行う（Vault切替でも `clearVaultScoped()` が同じ requestId を進めるので、旧Vaultの結果は書かれない）
  → [note_field_color](../features/note_field_color.md)
- **結晶**は生成と保存の寿命を分ける。生成はこの行どおりノート単位で止め、**保存に入った結晶はノート切替で止めない**。
  一覧へ足すかは **`vaultGeneration`** で照合する（→ [reflect_crystal](../features/reflect_crystal.md) 判断9）。
  絞り込みに使う今のノートの相対パスだけはノート単位の状態で、`withNoteScopedReset()` に載せる
- **余白メモ**は読み書きのジョブと一覧をノート単位で止めて捨て、書きかけ（文字・書き込み先・未確定の送信）をVault単位で持つ。
  書きかけは `NoteUiState` の外（→ 下の表）なので、`withVaultScopedReset()` ではなく `onVaultChanged()` から
  `clearVaultScoped()` で捨てる。止めた保存が受理されたかは、戻って読んだ一覧と**送信そのもの（保存値と時刻）**で照合する
  → [margin_pane](../features/margin_pane.md) §6.1・§6.2

**Vault世代を `vaultUri` の比較で代用しない。** Vault を A→B→A と選び直すと `cachedNotes` も破棄されるため
無効化したいが、Uri比較では同じ値になって素通りする。単調増加する `Long` なら選び直しも1回の切替として数えられ、
副次的にAndroid依存が無いのでJVMテストでも扱える。

**照合は `update` の直前に1箇所へ集約する。** 呼び出し側に `if (!isCurrent) return` を重ねると
テストで検出できない等価な分岐が増える（実際に1件が変異テストで冗長と判明して削除された）。

**三層目は作らない。** 蒸留の復旧チェックはノートにもVaultにも紐づかないが、
**世代を増やさず専用の追跡Job1本**で足りた。取り下げの契機が「次の `checkRecovery()`」しかないため。
**層を足す前に「無効化の契機がいくつあるか」を数える。**

**世代照合は片方向にしか効かない。** 優先順位が状況で入れ替わる2つの非同期処理では、
「遅れて届いた側が勝つべき場合」に**その時点で相手を無効化する**ことを書いた側が明示する。

## 判断5: 依存・状態・パッケージの境界を型とテストで固定する

**1. 依存生成と横断調停を分ける。** `NoteViewModelDependencies` が本番依存を組み立て、
`NoteViewModel` の内部コンストラクタからテスト用依存と `CoroutineScope` を差し替えられる。
全 Controllerの生成・状態所有・ノート/Vault切替は `NoteSessionCoordinator` が持ち、
ViewModelには `Uri`・`ContentResolver`・`SharedPreferences` を扱うAndroid境界だけを残す。
DIライブラリは差し替え対象がこの1グラフだけなので導入しない。

**2. 状態の所有権を型で狭める。** `NoteUiStateStore` だけが全体の `MutableStateFlow` を持ち、
各Controllerへは担当スライスの `*StateWriter` だけを渡す。ノート単位のリセットは
`withNoteScopedReset()` を唯一の登録点とし、`beginNoteLoad()` ではリセットと `Loading` 遷移を
1回の `update` で原子的に行う。

**3. `model` を依存グラフの葉にする。**

| パッケージ | importしてよいプロジェクト内パッケージ | `android.*` |
|---|---|---|
| `model` | なし | **禁止** |
| `ai` | `model` | 可 |
| `domain` | `model`, `ai` | **禁止** |
| `data` | `model`, `domain` | 可 |
| `controller` | `model`, `data`, `domain`, `ai` | **禁止** |
| `ui` | `model`, `domain` | 可 |

`PackageDependencyTest` がimportを走査してCIで固定する。
**`model → data → domain → ai` という案は採らない** — `model` が上位実装を知って循環の起点になる。

> **「葉である」を向きだけで定義すると穴が空く。** 当初この検査は `com.example.newproject.*` の
> import しか見ておらず、`model` は「プロジェクト内の何も import しない」を満たしながら
> **`android.net.Uri` だけは import する**状態で残っていた。`Uri` は素のJVMではスタブが例外を投げるため、
> **これらの型を組み立てられずテストが書けない**。テスト容易性の観点では、プロジェクト内の依存も
> 外部フレームワークへの依存も等しく「その層を素のJVMで扱えなくする」ので、**同じ規則で数える**。
> → [saf_boundary_gateway](saf_boundary_gateway.md)

> **「引数として受け取って素通しする」は、依存を消したことにならない。** 型として残っている限り
> テストはその型を作らねばならず、作れなければその経路は検証できない。
> `controller` が Android 非依存になったのは、素通しをやめて `VaultBrowser` の裏へ束ねたときである。

**4. 置き場所は責務で決める。** 上の表は import を縛るだけで、どこに置くかは決めない。
純関数は `ui` にも `domain` にも置けるが、「Android 非依存の検査を受けられるから `domain` へ」では決めない
（2026-10-02、オーナー判断）。

- **画面の形を決める判定は `ui`。** 並べ方・寸法・幾何・入口の遷移。例: `readerLayoutFor`・`canShowMarginPane`
- **データや状態の意味を決める判定は `domain`。** 解析・採点・照合・整形。例: `composeMarginMemo`・`DistillResponseParser`・`sectionSummaryStatus`

`ui` の純関数も素のJVMでテストできる（`ReaderLayoutTest` など）。テストのしやすさは置き場所を動かす理由にならない。

## 判断6: AI本文の切り出し責務は呼び出し側に置く

依存方向は `ai → model` のみを許可し `ai → domain` を禁止しているため、`PromptBuilder` から
`domain.markdown` の解析器は呼べない。そこで共有型 `NoteExcerpt` と用途別文字数上限を葉の `model`、
見出し骨格＋冒頭＋末尾を作る純関数 `buildNoteExcerpt` を `domain` に置き、
**呼び出し側で抜粋を完成させてから `ai` へ渡す**。`PromptBuilder` は切り出さず、
抜粋状態に応じた注意書きとプロンプト整形だけを担う。

この型が保証するのは「生の `String` を誤って直接渡さない」ことまでで、
同一Gradleモジュール内の生成箇所を封じるものではない。
抜粋の中身は [ai_input_excerpt](ai_input_excerpt.md) が持つ。

## 状態が `NoteUiState` の外に出るもの

| 外に出ているもの | 理由 |
|---|---|
| 設定（`darkTheme`・`notePaperAging`） | 状態22項目の変更でアプリ最上位まで再評価されるのを避ける（**再コンポーズ範囲**） |
| `NoteSectionModel` | `domain.markdown` にあり振る舞いを持つため `model` へ移せない（**パッケージ境界**） |
| 並べ読みの本文の解析結果（`SideReadingController.blocks`） | `NoteSectionModel` と同じく `domain.markdown` の型を持つ（**パッケージ境界**）。読み込み中・読めた・失敗の状態は `NoteUiState` に置き、`withNoteScopedReset()` で落とす。本文はそれと一緒に `cancelNoteScopedJobs()` が消す（→ [margin_pane](../features/margin_pane.md) §6） |
| 余白メモの書きかけ（`ComposeMarginMemoDrafts`） | 入力中の文字を StateFlow 経由で描くと、日本語の変換中に入力が崩れやすい（**IME**）。文字・書き込み先・送信を一組で持つので、まとめて外へ出す。ViewModel が持ち、Controller は `MarginMemoDraftStore` の口だけを通す（→ 判断4の3行目） |

**本数は数えない**（設定が増えるだけなので危険と相関しない）。危ないのは
**ノート単位の状態が外へ出ること**で、そこは CLAUDE.md の必須原則が直接見ている。

`NoteSectionModel` の解析開始は Coordinator の2箇所（`setNoteState()` と `applyReloadedBody()`）へ集約する。
片方を落とすと「本文は新しいのにブロックは旧い」状態になる。
**`parse()` は現在値を null に戻さない**（戻すと蒸留の差し替えで本文が数百ミリ秒消える）。

---

## Controller共通化はしない（決着済み）

**再提案するなら、下記の再検討条件を満たすことを先に示すこと。**

2026-07-24 / 07-25 / 07-26 / 08-09 の4度の判定を経て「**共通化せず、相似のまま維持**」で決着した。
共有できるのは **requestId ガードの数行だけ**で、周囲は全部違う。

| 要素 | Distill | ReunionCard | Summary | NoteField | Crystal |
|---|---|---|---|---|---|
| requestId ＋ `isCurrent()` | ✓ | ✓ | ✓ | ✓ | ✓ |
| モデルDLを自動開始して完了後に自動再開 | **✗（明示タップ）** | **✗（黙って諦める）** | ✓ | **✗（黙って諦める）** | **✗（黙って諦める）** |
| 失敗をユーザーへ見せる | ✓ | **✗（黙って劣化）** | ✓ | **✗（状態すら持たない）** | **✗（一覧だけを持つ）** |
| 起動契機 | 明示操作 | **再会（Rediscover でノートを引いたとき）** | ノート表示 | ノート表示 | ノート表示（**要約と再会カードの後**） |

Snackbar通知＋`isViewed` の未確認管理を持つ Controller は**0件**である。

**5本目（分野判定）は結論を補強した。** 起動契機は Summary と同じ「ノート表示」なのに、
**見せ方は正反対**（要約は待たせて見せる／分野は進捗も失敗も出さず、状態すら持たない）。
**起動契機が同じでも共通化できない**ことの実例である。
6本目（結晶）も見せ方は分野判定と同じ（進捗も失敗も出さない）だが、材料・保存・表示がすべて違い、
再検討の条件にも当たらない。

**判定軸は「ユーザーへの見せ方」である。** バックグラウンドAI機能の共通性は生成処理そのものではなく
通知と失敗の見せ方に宿るため、そこが違えば処理が似ていても共通化できない。

**「後から結果へ辿り着けるか」が未確認管理の要否を決めている。** 旧補記が `markViewed()` を持っていたのは
結果が Vault 内の `.md` にあり、一覧を開くまで存在に気づけなかったからで、AI生成の性質から来ていたわけではなかった。
**辿り着ける結果に未確認管理は要らない** — 痕跡サイドカーへ永続化して専用の面を開くたび復元するものは、
見逃しても失われない（→ [background_ai_ux](background_ai_ux.md) §4）。
分けているのは置き場所ではなく辿り着きやすさである。

**再検討の条件:** **`markViewed()` と Snackbar 通知を持つ Controller が3つ目に現れたとき**、
「AI結果の未確認管理」だけを共通化する候補として再検討する（現状は0件）。
**件数はトリガーにしない** → [lessons L31](../lessons/L31.md)。生成・DL側の共通化は打ち切る。

## 並行処理の規約

- AI生成は `AiClient` 側のMutexで直列化し、60秒タイムアウトを設ける
- **同じファイルを read-modify-write する経路が2つ以上あるなら、錠は共有物として上から配る。**
  痕跡サイドカーは訪問の追記（`ReadingTraceController`）・選別結果の書き戻し（`ReunionCardController`）・
  読み戻しの適用（`ReadingTraceBackupController`）が同じ形で書くので、`NoteSessionCoordinator` が
  1つの `Mutex` を作って3つへ渡す。**クラスごとに錠を持つと「錠はあるのに守られない」**
  という、最も気づきにくい形になる
- **自動で走る生成は、生成の直前に調停側の門番（`NoteDwellGate`）を待つ。** 痕跡の錠と同じく
  `NoteSessionCoordinator` が1つ作って自動起動の機能にだけ配る（→ [background_ai_ux](background_ai_ux.md) §7）
- ノート・Vault単位のジョブは追跡してキャンセルする
- `CancellationException` は再throwし、一般エラーへ変換しない
- 完了通知がキャンセルをすり抜ける経路には requestId＋`isCurrent()` ガードを併用する
- **落ちるテストを書けないガードは削除の候補**。実際に3回この判定を行い3回とも削除した。
  **キャンセルと世代照合を両方置くと後者が死ぬ**のがこのコードベースの傾向
  → [lessons L11](../lessons.md#l11-テストが効いているかは変異させて確かめる)。
  **ただし「書けない」と判断する前に、偽物が取り消しに協調していないかを見る。** 同期の I/O は取り消しで止まらず、
  取り消した後に例外で終わると、その例外が取り消しより優先して `catch` へ届く。協調する偽物ではこの枝へ届かない
  （並べ読みで、失敗を書く前の生存確認を一度省いた → [margin_pane](../features/margin_pane.md) §11）

## 教訓: 重複が品質問題の温床だった

27項目の多くは「同じ形のコードを複数箇所に書いた」ことに起因していた
（状態リセット3重複・SAFカーソルループ5重複・タブ遷移2重複・AIタイトル整形2重複）。
**同じ形を2度書いたら共通化を検討する**を目安とする。
ただし**検討の結果「しない」も正当な結論**である（上記のController共通化がその実例）。
