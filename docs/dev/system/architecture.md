# 設計思想 — アーキテクチャ（ViewModel分割・状態管理）

**状態:** 実装済み・稼働中。`model` / `domain` / `controller` の3層が Android 非依存としてCIで固定されている — 実装済み・稼働中。`model` / `domain` / `controller` の3層が Android 非依存としてCIで固定されている。
**最終検証:** 2026-08-11 / `9af63ee`（**ヘッダと参照先の実在のみ確認。本文は未突合**）
**関連コード:** `NoteViewModel.kt` / `controller/NoteSessionCoordinator.kt` / `model/NoteUiStateStore.kt` / `controller/`（10 Controller）
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
      ├── QuizController
      ├── AnnotationController        ← 旧補記ファイルの片付けのみ（**Vault単位**）
      ├── RemarkController            ← ノートへのひとこと
      ├── SearchController
      ├── DistillController
      ├── ReadingTraceController
      ├── SummaryController
      ├── NoteSectionController       ← 表示用Markdown解析をMainの外へ
      └── ReadingTraceCleanupController ← 痕跡の孤児掃除（**Vault単位**）
```

分割時点で 906行 → 348行・Controller 4つ。現在は Controller 10個で、**窓口の肥大化は再発していない**。

- 各Controllerは実行スコープと機能別の `*StateWriter` を注入され、**担当フィールド以外は型として書けない**
- `NoteUiStateStore` だけが `MutableStateFlow<NoteUiState>` を所有し、UIには読み取り専用の `StateFlow` を公開する
- 状態の単一ソース（1つの `NoteUiState`）は維持する。**例外は2つだけ**（下記「状態がUiStateの外に出る例外」）

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

**Vault単位のControllerはノート単位の契約に登録しない。** `AnnotationController`・
`ReadingTraceCleanupController`・`SearchController` の一部は無効化の契機がVault切替だけなので、
どちらの契約にも載せない（ノートを開き直しただけで一覧が消えるのは誤り）。世代も `vaultGeneration` 側を使う。
**契約2箇所への登録は「ノート単位の状態を足したとき」の定型**であって、すべてのControllerが従うものではない。

## 判断3: 壊れやすいロジックは純関数に切り出す

`QuizResponseParser`・`MarkdownParser`・タイトル正規化などの文字列処理はAndroid I/Oから分離し、
素のJVMユニットテストで回帰を防ぐ。テスト設計の過程で実バグも発見された。
**テストは検証だけでなく発見の道具になる。**

**ただし「純粋」は「軽い」を意味しない。** 最大1MBの本文のMarkdown解析はデスクトップJVMでも約460msを要する。
Mainのスコープから呼ぶ純関数は**入力サイズに比例するかどうか**を必ず見て、比例するなら
`Dispatchers.Default` へ逃がす。**逃がしたら対で「元の同期経路が代わりに走らないか」を確認する**
（`precomputedBlocks ?: parse(content)` のようなフォールバックが残っていると退避の意味が消える）
→ [lessons L13](../lessons.md#l13-純粋と軽いは別)。これは `NoteExcerptThreadingTest` /
`NoteSectionThreadingTest` がソース走査で固定している。

---

## 判断4: 非同期の世代IDは二層（Vault単位／ノート単位）

| スコープ | 対象 | 無効化の契機 | 持ち主 |
|---|---|---|---|
| ノート単位 | 要約・DL・クイズ・ひとこと・チャット・蒸留 | ノート切替（`cancelNoteScopedJobs()`） | **各Controllerの `activeRequestId`** |
| Vault単位 | 補記一覧・補記削除・フォルダ一覧・孤児掃除 | Vault切替（`saveVault()`） | **`NoteSessionCoordinator.vaultGeneration`** |

**混ぜられない。** 補記管理画面はノートと無関係なので、ノートを開き直しただけで一覧が消えるのは誤り。
逆に要約をVault世代だけで守ると、同じVault内のノート切替を検出できない。**片方に寄せると必ずどちらかが壊れる。**

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
10 Controllerの生成・状態所有・ノート/Vault切替は `NoteSessionCoordinator` が持ち、
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

## 判断6: AI本文の切り出し責務は呼び出し側に置く

依存方向は `ai → model` のみを許可し `ai → domain` を禁止しているため、`PromptBuilder` から
`domain.markdown` の解析器は呼べない。そこで共有型 `NoteExcerpt` と用途別文字数上限を葉の `model`、
見出し骨格＋冒頭＋末尾を作る純関数 `buildNoteExcerpt` を `domain` に置き、
**呼び出し側で抜粋を完成させてから `ai` へ渡す**。`PromptBuilder` は切り出さず、
抜粋状態に応じた注意書きとプロンプト整形だけを担う。

この型が保証するのは「生の `String` を誤って直接渡さない」ことまでで、
同一Gradleモジュール内の生成箇所を封じるものではない。
抜粋の中身は [ai_input_excerpt](ai_input_excerpt.md) が持つ。

## 状態が `NoteUiState` の外に出る例外は2つだけ

| 例外 | 理由 |
|---|---|
| テーマ（`StateFlow<Boolean>`） | 状態17項目の変更でアプリ最上位まで再評価されるのを避ける（**再コンポーズ範囲**） |
| `NoteSectionModel`（`StateFlow<NoteSectionModel?>`） | `domain.markdown` にあり振る舞いを持つため `model` へ移せない（**パッケージ境界**） |

**3つ目を作るときは、`model` を葉に保つ判断自体を見直す合図と考える。**

`NoteSectionModel` の解析開始は Coordinator の2箇所（`setNoteState()` と `applyReloadedBody()`）へ集約する。
片方を落とすと「本文は新しいのにブロックは旧い」状態になる。
**`parse()` は現在値を null に戻さない**（戻すと蒸留の差し替えで本文が数百ミリ秒消える）。

---

## Controller共通化はしない（決着済み）

**再提案するなら、下記の再検討条件を満たすことを先に示すこと。**

2026-07-24 / 07-25 / 07-26 / 08-09 の4度の判定を経て「**共通化せず、相似のまま維持**」で決着した。
共有できるのは **requestId ガードの数行だけ**で、周囲は全部違う。

| 要素 | Quiz | Distill | ReadingTrace | Summary |
|---|---|---|---|---|
| requestId ＋ `isCurrent()` | ✓ | ✓ | ✓ | ✓ |
| モデルDLを自動開始して完了後に自動再開 | ✓ | **✗（明示タップ）** | **✗（黙って諦める）** | ✓ |
| Snackbar通知＋`isViewed` の未確認管理 | ✓ | ✗ | ✗ | ✗ |
| 失敗をユーザーへ見せる | ✓ | ✓ | **✗（黙って劣化）** | ✓ |
| 起動契機 | 明示操作 | 明示操作 | **ノート表示・離脱** | ノート表示 |

**判定軸は「ユーザーへの見せ方」である。** バックグラウンドAI機能の共通性は生成処理そのものではなく
通知と失敗の見せ方に宿るため、そこが違えば処理が似ていても共通化できない。

**「後から結果へ辿り着けるか」が未確認管理の要否を決めている。** 旧補記が `markViewed()` を持っていたのは
結果が Vault 内の `.md` にあり、一覧を開くまで存在に気づけなかったからで、AI生成の性質から来ていたわけではなかった。
ひとことは結果を痕跡サイドカーへ永続化し、専用画面を開くたび必ず復元するので `isViewed` を持たない
（→ [background_ai_ux](background_ai_ux.md) §4）。**どちらも結果は専用画面にある** — 分けているのは置き場所ではなく辿り着きやすさ。

**再検討の条件:** **`markViewed()` と Snackbar 通知を持つ Controller が3つ目に現れたとき**、
「AI結果の未確認管理」だけを共通化する候補として再検討する（現状はクイズ1件のみ）。
**件数はトリガーにしない** → [lessons L31](../lessons/L31.md)。生成・DL側の共通化は打ち切る。

## 並行処理の規約

- AI生成は `AiClient` 側のMutexで直列化し、60秒タイムアウトを設ける
- ノート・Vault単位のジョブは追跡してキャンセルする
- `CancellationException` は再throwし、一般エラーへ変換しない
- 完了通知がキャンセルをすり抜ける経路には requestId＋`isCurrent()` ガードを併用する
- **落ちるテストを書けないガードは削除の候補**。実際に3回この判定を行い3回とも削除した。
  **キャンセルと世代照合を両方置くと後者が死ぬ**のがこのコードベースの傾向
  → [lessons L11](../lessons.md#l11-テストが効いているかは変異させて確かめる)

## 教訓: 重複が品質問題の温床だった

27項目の多くは「同じ形のコードを複数箇所に書いた」ことに起因していた
（状態リセット3重複・SAFカーソルループ5重複・タブ遷移2重複・AIタイトル整形2重複）。
**同じ形を2度書いたら共通化を検討する**を目安とする。
ただし**検討の結果「しない」も正当な結論**である（上記のController共通化がその実例）。
