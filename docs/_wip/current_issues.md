# 直近課題抽出

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**抽出日:** 2026-07-22（最終更新: 2026-07-26）
**基準:** ReadingTrace v1（実機確認待ち）・Vigilith Phase 3（最終実機確認済み）・
ダークモード（実機確認済み） / `feature/result_source_analys`
**直近の入力:** 2026-07-26 のソースコード品質総評（[source_code_quality_review.md](../source_code_quality_review.md)）。
全指摘を実コードで突合し、新規課題として 1-1・2-9〜2-11・3-10〜3-13 を追加した。
**目的:** 次の一手を決めるための、現時点で確認できる課題の棚卸し。ロードマップ（[roadmap.md](roadmap.md)）の入力とする。

> 何をいつ変えたかは [change_history.md](../change_history.md)、今どうなっているかは [source_code_analysis.md](../source_code_analysis.md)、なぜそうしたかは [design/](../design/) を参照。本ファイルは「まだ手を付けていない/追いついていない」課題のみを新しい順の観点で集約する。**解消した課題はこのファイルから削除する**（記録は上記3文書に残るので、ここに残すと未対応課題が埋もれる）。
>
> **番号は再利用しない**（design/・roadmap から番号で参照されているため、振り直すとリンクが別の課題を指す）。**欠番の一覧は持たない** — 番号が無い＝解消済み、で足りる。いつ何を解消したかは [change_history.md](../change_history.md) を見る。

---

## 0. サマリー（優先度マップ）

| 優先度 | 課題 | 種別 | 影響 |
|---|---|---|---|
| **高** | **1-1 検索の非同期に世代管理がない** | 並行処理 | 古い検索結果が新しい結果を上書きし得る |
| 中 | 2-1 Job管理の不統一（補記一覧/削除・モデルDLが未保護） | 並行処理 | 連続操作時の競合余地 |
| 中 | 2-2 AI入力が先頭固定長切り出し | AI品質 | 長文ノートの後半が無視される |
| 中 | 2-3 読書痕跡の孤児ファイルが掃除されない／move・rename に追従しない | 保守 | 長期運用でファイル数が単調増加 |
| 中 | 2-4 `_ReadingTraces`の索引が外部同期で追加されたファイルをプロセス再起動まで認識しない | 同期/キャッシュ | 再会カードの見逃し・重複作成余地 |
| 中 | 2-5 30件上限後も保持件数を「これまで開いた回数」と表示する | 正確性/UX | 31回目以降も「30回」と誤表示 |
| 中 | **2-9 パッケージ間の依存が循環している** | 構造 | 機能追加時の変更範囲が読めない |
| 中 | **2-10 `NoteViewModel` が依存を内部生成しておりテスト不能** | テスト容易性 | Controller間の調停が379件の検証範囲外 |
| 中 | **2-11 単一 UiState を6 Controller が共有所有している** | 状態管理 | 担当外フィールドを書ける／全体再評価 |
| 低 | 3-1 統合テスト不足（SAF・端末AI・Navigation） | テスト | 実端末依存の回帰を検出できない |
| 低 | 3-2 エラー通知の握りつぶし（フォルダ取得失敗・補記削除失敗等） | 堅牢性 | 無言の失敗 |
| 低 | 3-3 AI状態の一時エラーと非対応の同一視 | UX | 原因の区別ができない |
| 低 | 3-4 YAML/Markdownの限定実装・キャッシュTTL 60秒 | 機能限定 | 取りこぼし・反映遅延 |
| 低 | 3-8 **ライトの弱い文字6色＋緑ボタンがAA未達** | a11y | 2.46〜4.43。ダークは全て基準内 |
| 低 | 3-9 無彩色グレーが5段階あり意味的な区別がない | リファクタ | ダーク値を5つ決める必要が出る |
| 低 | **3-12 `applicationId` が `com.example.newproject` のまま** | リリース | 公開後は変更不可 |
| 低 | **3-13 Lint warning 28件** | 保守 | 依存更新・KTX移行の積み残し |

---

## 1. 高優先度

### 1-1. 検索の非同期に世代管理がない
- **現状:** [`SearchController`](../../app/src/main/java/com/example/newproject/controller/SearchController.kt#L60) の `searchByKeyword()` と `pickRandomInScope()` が無条件に `scope.launch` する。Job も requestId も持たない。**Controller 6本のうち、世代管理を持たないのはここだけ**（Annotation / Distill / Quiz / ReadingTrace / SectionChat はいずれも `activeRequestId` か `Job?.cancel()` を実装済み）。
- **問題（2つあり、分割不可）:**
  1. **古い結果が新しい結果を上書きする。** 連続検索だけでなく、検索⇄ランダム切替でも競合する。SAF走査は重く、スコープキャッシュを跨ぐと所要時間が大きく変わるため完了順は保証されない。
  2. **`catch (e: Exception)` が `CancellationException` も通常エラーへ変換する**（[L77](../../app/src/main/java/com/example/newproject/controller/SearchController.kt#L77)・[L97](../../app/src/main/java/com/example/newproject/controller/SearchController.kt#L97)）。ただし現状は明示的な `cancel()` がどこにも無いため、キャンセルが起きるのは `viewModelScope` 破棄時＝画面消滅時だけで、**実害はほぼ出ていない**。①を直す（前Jobを `cancel()` する）と**この瞬間に「キャンセルがエラー表示に化ける」バグが顕在化する**ため、①②は必ず同時に直す。
- **対応候補:** 他5 Controller と同じ `activeRequestId` ＋ `Job?.cancel()` 方式へ揃え、`catch` から `CancellationException` を除外する。あわせて `scopeNotesCache`（素の `mutableMapOf`）が Main 単一スレッド前提で成立していることをコメントで明文化する。
- **規模感:** 小。既存パターンの横展開で、新しい設計判断が要らない。

> ReadingTrace v1 で 2026-07-25 に解消した4件（到達率・Lifecycle・Vault分離・検索フォールバック）は
> SAF実装のVault照合と Activity lifecycle を通した pause/resume が JVMテストの範囲外のため、
> **実端末確認は未実施**。完了判定は [roadmap.md](roadmap.md) N-1 の「完了の定義」で追跡する。

---

## 2. 中優先度

### 2-1. Job管理の不統一（残り）
- **現状:** 検索は 1-1 として切り出した。残る未保護は **補記一覧の読み込み／削除／全削除**（[`AnnotationController.loadList` / `delete` / `deleteAll`](../../app/src/main/java/com/example/newproject/controller/AnnotationController.kt#L133)）と、**要約側のモデルDL Job**（[`NoteViewModel.startModelDownload`](../../app/src/main/java/com/example/newproject/NoteViewModel.kt#L582)）。いずれも裸の `scope.launch` で要求単位に追跡されない。補記の生成・DL（`createJob` / `downloadJob`）、要約・関連・ノート読み込み（`summaryJob` / `relatedNotesJob` / `noteLoadJob`）は保護済み。
- **問題:** 短時間に複数要求できる経路で、完了順による状態上書きの余地が残る。削除→再読込の順序が逆転すると消したはずの項目が戻って見える。
- **対応候補:** 1-1 と同じ形へ揃える。（解析書 §10.1 / §14.2 / §15-2）
- **規模感:** 小〜中。

### 2-2. AI入力の先頭固定長切り出し（関連ノートは設計書との乖離も含む）
- **現状:** 要約・関連・補記・セクションチャットいずれも `String.take()` による文字数上限で、意味境界やトークンで切っていない（[`PromptBuilder`](../../app/src/main/java/com/example/newproject/ai/PromptBuilder.kt#L41) 内に7箇所）。長いノートの後半（結論が末尾にある等）はAI入力に含まれない。クイズと蒸留はこの型から抜けている（クイズ＝フォーカス周辺、蒸留＝全文を文分割してスコア選抜）。
- **関連ノート固有の問題:** `design/related_notes_ai.md` の Phase 1 に「フォーカスセクション文脈で推薦し、先頭切り出しを解消」と書かれていたが、**2026-07-24 の実装突合で未実装と判明**（`fetchRelatedNotes` はノート全文を渡し、プロンプト側で先頭600文字に切る。履歴上も一度も入っていない）。設計書・change_history は訂正済みで、**課題としてはここに残る**。
- **対応候補:** 見出し・冒頭・末尾・重要語を考慮した抽出へ発展させる。ただしNanoの入力予算とのトレードオフあり。関連ノートでフォーカスセクション文脈を採る場合は、**自動起動（ノートを開いた瞬間に実行）とスクロール追従するフォーカスの噛み合わせ**をどう決めるかが論点（クイズは「吹き出しから明示起動」で回避したが、同じ手は使えない）。
- **規模感:** 中〜大。品質改善だが検証が要る。

### 2-3. 読書痕跡の孤児ファイルと move/rename 追従
- **現状:** `_ReadingTraces/<相対パスのSHA-256>.json` は相対パスでしか引き当てない。ノートを削除・改名すると対応する痕跡は**誰も掃除せず**、新しいパスで別ファイルが作られる。掃除APIもオプション画面の導線も存在しない。
- **問題:** 容量は問題ない（1ファイル最大3.3KB・2,000ノートで約1.4MB）。効いてくるのは**ファイル数に比例する処理**で、年単位の運用で孤児が単調増加する。
- **v1で見送った理由:** 自動削除は、同期途中や一時的な読み取り失敗でノートが走査結果に現れないときに**生きている痕跡を消す**危険がある。move/rename 追従も、引き当てミスが未読ノートで常時起きるためフォルダ全走査のコストが恒常化する。
- **対応候補:** オプション画面に手動の一括削除を置く（`_AI補記` の `AnnotationManagerScreen` と同じ発想）／痕跡に「最後に確認できた日時」を持たせ、長期間見つからないものだけ落とす。（design/reflect_reading_trace.md §8・roadmap X-3）
- **規模感:** 小〜中。

### 2-4. `_ReadingTraces`索引が外部同期の追加を認識しない
- **現状:** [`ReadingTraceStore`](../../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L210) はVaultごとにフォルダの子一覧を1回だけ読み、key→Uriの `FolderIndex` をプロセス内に保持する。自分で作ったファイルは追記するが、TTLも無効化契機も無く、Google Drive等の外部同期で後から増えたファイルを再走査する契機がない。
- **問題:** 別端末で作られた痕跡が同期されても、現在プロセスではカードが出ない。さらに未存在と判断して保存すると、プロバイダによっては同じkeyの重複ファイルを作り、次回索引時にどちらを採るかが列挙順依存になる。
- **対応候補:** 短いTTL、Rediscover照合でミスした場合だけの再走査、またはContentObserver相当の無効化を検討する。毎回全走査は避けつつ、外部追加を最終的に認識できる設計にする。
- **規模感:** 中。同期プロバイダ上での実機確認が必要。

### 2-5. 訪問保持上限と累計回数の混同
- **現状:** [`withVisit()`](../../app/src/main/java/com/example/newproject/model/ReadingTrace.kt#L71) は訪問を最大30件へ切り詰める。一方、カード（[`ReadingTraceController` L365](../../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L365)）とAIプロンプト（[`PromptBuilder` L74](../../app/src/main/java/com/example/newproject/ai/PromptBuilder.kt#L74) `Times opened:`）は `visits.size` を「これまで開いた回数」として使う。`totalVisitCount` 相当のフィールドは存在しない。
- **問題:** 31回目以降も常に「これまで30回」と表示・要約される。保存履歴数と累計訪問数は異なる概念。
- **対応候補:** `totalVisitCount`を別フィールドとして累積し、`visits`は傾向分析用の直近30件として維持する。既存schemaからの移行方針も合わせて決める。簡易対応なら文言を「記録に残っている直近30回」に限定する。
- **規模感:** 小〜中。schemaVersion更新を伴う場合は移行テストが必要。

### 2-9. パッケージ間の依存が循環している
- **現状:** 2026-07-26 のレビューで、レイヤー別パッケージ整理（PR #37）後も**実コード上の循環が3組残っている**ことを確認した。

| 循環 | 具体例 |
|---|---|
| `model ⇄ data` | [`NoteUiState`](../../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L3) が `NoteFile`/`NoteFolder`/`HistoryEntry` を実使用（L170・L229-233）。逆に [`ReadingTraceJson`](../../app/src/main/java/com/example/newproject/data/ReadingTraceJson.kt#L3) が `model.ReadingTrace` を参照 |
| `model ⇄ domain` | `NoteUiState` が `RelatedNote`/`AiRecommendationStatus` を実使用（L86-88・L99-100） |
| `domain ⇄ ai` | `RelatedNotesUseCase`・`SearchPickerUseCase`・`SummarizeUseCase`・`RelatedCandidateContext` が `ai` を import。逆に [`PromptBuilder`](../../app/src/main/java/com/example/newproject/ai/PromptBuilder.kt#L3) が `domain.DistillCandidate`/`DistillLimits` を参照 |

- **問題:** パッケージは整理されたが**依存はレイヤー化されていない**ため、機能追加時にどこまで影響が及ぶかが読めない。将来のマルチモジュール化はこの状態では着手できない。
- **対応候補:** まず**許可する依存方向を決めて明文化する**（実装より先。例: `ui → controller → domain → model`、`data` は `domain` が定義したインターフェースの実装側に置く）。そのうえで `NoteUiState` が抱える `data`/`domain` 型の所属を見直す。全面的な作り直しは不要。
- **規模感:** 中〜大（型の移動を伴う）。ただし**方向の合意だけなら小**で、それだけでも以降の判断が速くなる。

### 2-10. `NoteViewModel` が依存を内部生成しておりテスト不能
- **現状:** [`NoteViewModel` L60-88](../../app/src/main/java/com/example/newproject/NoteViewModel.kt#L60) が `NoteHistoryStore`・`NoteRepository`・`AICoreClient`・3 UseCase・6 Controller・`DistillWriteRepository` を**すべて内部で直接生成**している。631行で、依存生成・Vault/テーマ/キャッシュ・6 Controllerの調停・ノート読込・要約/関連/モデルDL・状態リセット・Lifecycle連携を担う。
- **問題:** 差し替え口が無いため、**JVMテスト379件が1件も `NoteViewModel` を通っていない**。Controller間の調停ロジック（蒸留保存後の状態維持・Vault切替時の一斉リセット等）は最も壊れやすい部分なのに無検証。
- **対応候補:** `ViewModelProvider.Factory` かコンストラクタ引数へ依存生成を出す。DIライブラリの導入までは不要。
- **前提関係:** **これを先にやらないと 3-1 の ViewModel 統合テストが書けない。**
- **規模感:** 中。

### 2-11. 単一 UiState を6 Controller が共有所有している
- **現状:** [`NoteUiState`](../../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L212) は17フィールドを持つ単一の `data class` で、同じ `MutableStateFlow<NoteUiState>` を6 Controller 全員が `update` する。[`MainActivity` L83](../../app/src/main/java/com/example/newproject/MainActivity.kt#L83) は `darkTheme` 判定のため `AppTheme` の外で全体を購読している。
- **問題:**
  1. **Controllerが担当外フィールドも書ける。** 制約はKDocの「〜の更新のみを行う」という口約束だけで、コンパイラは何も止めない。
  2. **17フィールドのどれが変わっても最上位から再評価が走る。**
- **対応候補:** 段階1として `NoteUiState.kt` を機能別ファイルへ分割（ファイルサイズより「誰が何を持つか」を見えるようにするのが目的）。段階2として状態スライスまたは機能別Flowを検討。なお **②だけなら `darkTheme` を別Flowへ出せば単独で解消できる**。
- **規模感:** 段階1は小、段階2は中。

---

## 3. 低優先度（顕在化していないが記録）

### 3-1. 統合テスト不足
- SAF走査・補記保存/削除・Gemini Nano・Compose Navigation・全画面遷移・画面回転/プロセス再生成・連続操作時の競合を絡めたテストがない。`app/src/androidTest` ソースセット自体が存在しない。ローカルユニットテスト379件は純粋ロジックの回帰防止には有効だが、実端末依存の動作は保証範囲外。
- 対応候補: Fake `ContentResolver` または instrumentation テストで Vault走査・保存・削除を検証。（解析書 §13.4 / §15-5）
- **前提:** ViewModelを絡めたテストは 2-10 の解消が先。

### 3-2. エラー通知の握りつぶし
- フォルダ一覧取得失敗はユーザーに通知されない（[`SearchController` L49](../../app/src/main/java/com/example/newproject/controller/SearchController.kt#L49) が空の `catch`）。補記削除は [`deleteDocument()` の戻り値を確認せず](../../app/src/main/java/com/example/newproject/controller/AnnotationController.kt#L158)一覧を再読込する。閲覧履歴のJSONパース失敗は空履歴扱い。
- 対応候補: 少なくとも削除失敗・フォルダ取得失敗に明示エラーを出す。（解析書 §11.2）

### 3-3. AI状態の一時エラーと非対応の同一視
- [`checkAvailability()` 自体の例外を `Unavailable` にまとめる](../../app/src/main/java/com/example/newproject/ai/AICoreClient.kt#L67)ため、非対応端末と一時的な状態取得失敗を区別できない。
- 対応候補: AI非対応・モデル未準備・一時エラーのUXを要約・検索・クイズ・補記で統一する。（解析書 §11.2 / §15-8）

### 3-4. その他の既知の限定実装
- YAML解析が簡易（[`parseMeta`](../../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L217) は `---` 囲みの先頭ブロックから `tags`/`aliases` を単純抽出するだけ。ネスト・複数行値は未対応 → 取りこぼし）
- Markdownが限定実装（ordered list番号・クリック可能リンク・画像・埋め込み・数式は未対応）
- キャッシュTTL 60秒（`NOTES_CACHE_TTL_MS`）により外部同期・編集結果の反映が最大60秒遅れる

### 3-8. ライトの弱い文字が軒並みAA未達（ダークは基準内）
- ダークモード実装時に明暗の全トークンを実測した結果、**ライトの文字トークン12件中6件が基準4.5:1を割っていた**。
  ダーク側は設計時に測って決めたため全て基準内で、**同じ画面がライトでだけ読みにくい**という逆転が起きている。

| トークン | 値 | 明色パネル上 | 使われている場所 |
|---|---|---|---|
| `aiHeading` | `#16B8A6` | **2.46** | 「AI推薦」の見出し |
| `onSurfaceDisabled` | `#999999` | **2.82** | 要約前の「—」 |
| `onSurfaceMetaBlue` | `#8A90A8` | **3.13** | 一覧の更新日時 |
| `onSurfaceHint` | `#888888` | **3.51** | 注記・完了済みタスク・打ち消し線 |
| `relatedHeading` | `#7B6FFF` | **3.74** | 「関連ノート」の見出し |
| `onSurfaceFaint` | `#777777` | **4.43** | 空状態の案内 |

- あわせて**緑ボタンの塗り** `#16B8A6` も白パネル上で **2.46**（非文字基準3:1未満）。ラベルは黒で8.44と読める。
- **AIタブの成功バッジ**（`AppScaffold`）も緑の塗りに白の「✓」で **2.49**。明暗どちらでも同じ。
  エラーバッジ側はダークで新たに読めなくなっていたため塗り用トークンで修正済みだが、
  こちらは明暗共通の既存問題なので、ライトの見た目を変えないため未修正。
- **現状:** 値を変えるとライトの見た目が変わるため未修正。[`AppColorContrastTest`](../../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L127) が既知未達7件の実測値を「失敗させず記録する」形で固定しており、悪化しても改善しても検出できる。
  **裏を返すと、テスト379件が全緑であることは全配色AA準拠を意味しない。**
- 対応候補: グレー階調の整理（3-9）と同時に、暗い側へ寄せる。見出し2色は彩度を落として明度を下げる。

### 3-9. 無彩色グレーが5段階あり意味的な区別がない
- `OnSurfaceMuted/Subtle/Faint/Hint/Disabled`（`#555555`〜`#999999`）は、実装の実態に合わせて5つに分けたもので、
  役割の違いは薄い。R-4 では**見た目を変えないことを優先**して統合を見送った。
- **問題:** ダークモード実装でも対応する5値を持つため、意味の区別が薄いまま明暗10値を保守することになる。
- 対応候補: 3段階（本文に次ぐ／弱い／最も弱い）へ統合する。実行するとライトの見た目がわずかに変わるため、
  ライト側の弱い文字（3-8）を是正するタイミングでまとめて判断する。

### 3-12. `applicationId` が初期値のまま
- **`applicationId = "com.example.newproject"`**（[app/build.gradle.kts L15](../../app/build.gradle.kts#L15)、`namespace` も同じ）。**Play Storeへ公開すると変更不可**になるため、未公開の今のうちに正式IDを決めるのが安全。
- **規模感:** 小（名前さえ決まれば設定変更のみ）。ただし `namespace` も併せて変えるとimport全書き換えになるため、`applicationId` だけ先に変える判断もあり得る。
- **バックアップ側は 2026-07-26 に解消:** `dataExtractionRules`／`fullBackupContent` で `random_note_prefs` をクラウドバックアップと端末移行の両方から除外した。

### 3-13. Lint warning 28件
- Error 0件・Warning 28件。内訳: `UseKtx` 9／`GradleDependency` 7／`UnusedResources` 3／`NewerVersionAvailable` 3／`UsableSpace` 2／`ConstantLocale` 2／`ObsoleteSdkInt` 1／`AndroidGradlePluginVersion` 1。
- 依存更新系11件は方針（いつ・どこまで上げるか）を決めていないことが根本。`ConstantLocale` は `SimpleDateFormat` の Locale 固定で、[`AnnotationComposer`](../../app/src/main/java/com/example/newproject/domain/AnnotationComposer.kt#L10) が「メインスレッド専用」前提で持つ箇所と関係する。
- **規模感:** 小。CI（2026-07-26 導入済み）で警告数を固定すると再発しない。

---

## 4. 横断テーマ（課題の背景にある構造）

1. **Nano制約が全設計を規定している** — 出力256トークン上限・Mutex直列化・60秒タイムアウトが、要約字数・クイズ形式・補記項目数・ReadingTraceの「生の痕跡を先に出して要約は裏で」まで決めている。新機能はこの制約と必ず衝突するため、判断軸として明文化する価値がある。
2. **機能ごとに堅牢性レベルを変える方針への転換** — 蒸留（重厚な原子性・復旧）→ ReadingTrace（シンプル最優先・ベストエフォート）。この使い分けは健全だが、どの機能をどのレベルで守るかの基準を残すと再現性が上がる。
3. **文言と実装の乖離** — 検索フォールバックが代表例（画面は「キーワード一致」と言うのに実装は列挙順の先頭3件だった。2026-07-25 に解消）。機能追加が続く中で、小さいうちに潰すのが得策。
4. **AIの役割を「創作」から「要約」へ落とすと導線が要らなくなる** — ReadingTrace で得た知見。ユーザーの操作を増やさずに価値を出せるかは、**既にある行動の痕跡で足りないか**を先に問うと判断しやすい。→ [reflect_reading_trace](../design/reflect_reading_trace.md) §2
5. **「意識させない機能」は他の機能と作法が違う** — 通知しない・失敗を見せない・自動DLしない、が仕様になる。AI状態UXの統一（3-3）を進める際、ReadingTrace は横展開の対象外として扱う必要がある。
6. **「対策を入れた」と「対策が効いている」は別** — Vault分離（2026-07-25）は、方針が正しかったぶん実装が効いていなくても文書・コミットメッセージが通ってしまった。修正の主張は、修正コードを別の目で読むまで確定させない。→ [bugfix_reports](../bugfix_reports.md) #4
7. **「整理した」と「境界が効いている」も別**（2026-07-26 追加） — パッケージのレイヤー別整理（PR #37）は正しい方向だったが、依存の**向き**は制約されないままで循環が3組残った（2-9）。同様に、コントラストテストが全緑でもAA準拠は意味しない（3-8）し、テスト379件が全緑でも ViewModel は1行も通っていない（2-10）。**「何を保証していないか」を測定と同じ場所に書く**と、この型の誤読を防げる。

---

## 5. 次アクションの選択肢

- **A. ReadingTraceの実機確認**（解消済み4件を同じ端末で確認。これが済むまで roadmap N-1は完了扱いにしない）
- **B. 読書痕跡の保守性に手を入れる**（2-3〜2-5）
- **C. 非同期の世代管理を揃える**（1-1 → 2-1。1-1 は小さく効果が確実なので、他の何を選ぶにせよ先に片付けてよい）
- **D. 「意図して問いを残す」の検討**（ReadingTraceで回収できなかった思考の連続性 → roadmap X-2）
- **G. 構造の土台を固める**（2-9 → 2-10 → 3-1）
  依存方向・状態の所有者・ViewModelのテスト可能性の3点。地ならしの 3-10（未使用import削除）は 2026-07-26 に完了し、`domain → ui` と `domain → controller` の依存は消えた。残る 2-9 は「方向を決める」だけなら小。2-10 を通すと初めて ViewModel を絡めたテスト（3-1）が書けるようになる。
- **H. 開発運用の最低ライン**（残り: 3-12 applicationId → 3-13 Lint）
  CI と backup 除外は 2026-07-26 に完了。残るのは正式 applicationId の決定と Lint warning 28件で、
  警告数を CI で固定すれば再発しない。

→ 中長期の進め方は [roadmap.md](roadmap.md) に整理する。
