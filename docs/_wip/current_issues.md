# 直近課題抽出

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**抽出日:** 2026-07-22（最終更新: 2026-07-26）
**基準:** ReadingTrace v1（実機確認待ち）・Vigilith Phase 3（最終実機確認済み）・
ダークモード（実機確認済み）・A案（実機確認待ち） / `feature/Improvement_Issue_No.2`（`511c311` 時点）
**直近の入力:** 2026-07-26 のソースコード品質総評（[source_code_quality_review.md](../source_code_quality_review.md)）。
全指摘を実コードで突合して起票し、うち軽量なものから順次解消している。
同日の再突合で、総評が拾えていなかった3件を追加起票した（うち要約DLジョブの実害はA案で解消済み。2-12・3-14 は残存）。
**目的:** 次の一手を決めるための、現時点で確認できる課題の棚卸し。ロードマップ（[roadmap.md](roadmap.md)）の入力とする。

> 何をいつ変えたかは [change_history.md](../change_history.md)、今どうなっているかは [source_code_analysis.md](../source_code_analysis.md)、なぜそうしたかは [design/](../design/) を参照。本ファイルは「まだ手を付けていない/追いついていない」課題のみを新しい順の観点で集約する。**解消した課題はこのファイルから削除する**（記録は上記3文書に残るので、ここに残すと未対応課題が埋もれる）。
>
> **番号はこのファイル内だけの見出し。** 恒久文書からは参照させない（`_wip/` はリリース時に廃棄するため、恒久側が番号に依存すると全部リンク切れになる）。したがって振り直しても再利用しても構わない。欠番の一覧も持たない。いつ何を解消したかは [change_history.md](../change_history.md) を見る。

---

## 0. サマリー（優先度マップ）

> 実施計画は **[§5 改善活動（B案・C案）](#5-改善活動b案c案)** を参照。下表の「計画」列がどの案で解消するかを示す。
> **A案（非同期の境界）は 2026-07-26 に完了**したため、対象だった課題は本ファイルから削除した。

| 優先度 | 課題 | 種別 | 影響 | 計画 |
|---|---|---|---|:--:|
| 中 | 2-2 AI入力が先頭固定長切り出し | AI品質 | 長文ノートの後半が無視される | — |
| 中 | 2-3 読書痕跡の孤児ファイルが掃除されない／move・rename に追従しない | 保守 | 長期運用でファイル数が単調増加 | — |
| 中 | 2-4 `_ReadingTraces`の索引が外部同期で追加されたファイルをプロセス再起動まで認識しない | 同期/キャッシュ | 再会カードの見逃し・重複作成余地 | — |
| 中 | 2-5 30件上限後も保持件数を「これまで開いた回数」と表示する | 正確性/UX | 31回目以降も「30回」と誤表示。**AI俯瞰要約も更新停止** | C |
| 中 | **2-12 読書痕跡の保存が `viewModelScope` に載っており、終了時に失われる** | 信頼性 | アプリ終了で確定済みの訪問が消える。保存失敗も沈黙 | C |
| 中 | **2-13 Markdown本文の無制限読込がメモリ上限を無効化する** | 性能 | 巨大ノート混在時に入力上限より前にメモリ/IOを消費 | C |
| 中 | **2-9 パッケージ間の依存が循環している（4組）** | 構造 | 機能追加時の変更範囲が読めない | B |
| 中 | **2-10 `NoteViewModel` が依存を内部生成しておりテスト不能** | テスト容易性 | Controller間の調停が391件の検証範囲外 | B |
| 中 | **2-11 単一 UiState を7 Controller が共有所有している** | 状態管理 | 担当外フィールドを書ける／全体再評価 | B |
| 低 | 3-1 統合テスト不足（SAF・端末AI・Navigation） | テスト | 実端末依存の回帰を検出できない | — |
| 低 | 3-3 AI状態の一時エラーと非対応の同一視 | UX | 原因の区別ができない | — |
| 低 | 3-4 YAML/Markdownの限定実装・キャッシュTTL 60秒 | 機能限定 | 取りこぼし・反映遅延 | — |
| 低 | 3-8 **ライトの弱い文字6色＋緑ボタンがAA未達** | a11y | 2.46〜4.43。ダークは全て基準内 | — |
| 低 | 3-9 無彩色グレーが5段階あり意味的な区別がない | リファクタ | ダーク値を5つ決める必要が出る | — |
| 低 | **3-12 `applicationId` が `com.example.newproject` のまま** | リリース | 公開後は変更不可 | — |
| 低 | **3-13 Lint warning 28件** | 保守 | 依存更新・KTX移行の積み残し | — |
| 低 | **3-14 `buildTypes` ブロックが無く、リリースビルド構成が未定義** | リリース | R8・署名・androidTest基盤がすべて未設定 | — |

**B案・C案でも動かない軸:** アクセシビリティ（3-8・3-9）と開発・リリース運用（3-12〜3-14）。
どちらも独立した小タスクなので、案の枠外として別立てにする（→ §5 末尾）。

---

## 1. 高優先度

**現在なし。**（検索の世代管理・Job管理の不統一・エラー通知の握りつぶしは 2026-07-26 に解消）

> ReadingTrace v1 で 2026-07-25 に解消した4件（到達率・Lifecycle・Vault分離・検索フォールバック）は
> SAF実装のVault照合と Activity lifecycle を通した pause/resume が JVMテストの範囲外のため、
> **実端末確認は未実施**。完了判定は [roadmap.md](roadmap.md) N-1 の「完了の定義」で追跡する。

---

## 2. 中優先度

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
- **見た目だけの問題ではない（2026-07-26 追記）:** [`needsAiSummary`](../../app/src/main/java/com/example/newproject/model/ReadingTrace.kt#L79) は `aiSummaryVisitCount != visits.size` で再生成を判定する。訪問が30件で頭打ちになると `visits.size` は永久に30固定になるため、**31回目以降どれだけ読んでもAI俯瞰要約が二度と更新されない**。カード側の出し分け（[`ReadingTraceController` L360](../../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L360)）も同じ式なので、古い要約が「最新」として出続ける。根本原因は本課題と同一（保持件数を累計として使っている）で、`totalVisitCount` へ判定を移せば同時に直る。
- **対応候補:** → **[C案](#c案-入出力に用途別の予算と結果の扱いを持たせる)**。`totalVisitCount`を別フィールドとして累積し、`visits`は傾向分析用の直近30件として維持する。既存schemaからの移行方針も合わせて決める。簡易対応なら文言を「記録に残っている直近30回」に限定する。
- **規模感:** 小〜中。schemaVersion更新を伴う場合は移行テストが必要。

### 2-9. パッケージ間の依存が循環している
- **現状:** 2026-07-26 のレビューで、レイヤー別パッケージ整理（PR #37）後も**実コード上の循環が4組残っている**ことを確認した（当初3組と記載していたが、再突合で `data ⇄ domain` を追加）。

| 循環 | 具体例 |
|---|---|
| `model ⇄ data` | [`NoteUiState`](../../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L3) が `NoteFile`/`NoteFolder`/`HistoryEntry` を実使用（L170・L229-233）。逆に [`ReadingTraceJson`](../../app/src/main/java/com/example/newproject/data/ReadingTraceJson.kt#L3) が `model.ReadingTrace` を参照 |
| `model ⇄ domain` | `NoteUiState` が `RelatedNote`/`AiRecommendationStatus` を実使用（L86-88・L99-100）。逆に [`QuizResponseParser`](../../app/src/main/java/com/example/newproject/domain/QuizResponseParser.kt#L3) が `model.QuizCard`/`QuizFormat` を参照 |
| `data ⇄ domain` | [`NoteRepository`](../../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L5) が `domain.toObsidianNoteTitle`/`DistillLimits` を参照。逆に [`RelatedNotesUseCase`](../../app/src/main/java/com/example/newproject/domain/RelatedNotesUseCase.kt#L4) が `data.NoteFile`/`NoteMeta` を参照 |
| `domain ⇄ ai` | `RelatedNotesUseCase`・`SearchPickerUseCase`・`SummarizeUseCase`・`RelatedCandidateContext` が `ai` を import。逆に [`PromptBuilder`](../../app/src/main/java/com/example/newproject/ai/PromptBuilder.kt#L3) が `domain.DistillCandidate`/`DistillLimits` を参照 |

- **問題:** パッケージは整理されたが**依存はレイヤー化されていない**ため、機能追加時にどこまで影響が及ぶかが読めない。将来のマルチモジュール化はこの状態では着手できない。
- **対応候補:** → **[B案](#b案-noteviewmodel-の依存を外出しし状態スライスと依存方向を型とciで守る)**。まず**許可する依存方向を決めて明文化する**（実装より先。例: `ui → controller → domain → model`、`data` は `domain` が定義したインターフェースの実装側に置く）。そのうえで `NoteUiState` が抱える `data`/`domain` 型の所属を見直し、**importを走査する純JVMテストで方向をCIに固定する**。全面的な作り直しは不要。
- **規模感:** 中〜大（型の移動を伴う）。ただし**方向の合意だけなら小**で、それだけでも以降の判断が速くなる。

### 2-10. `NoteViewModel` が依存を内部生成しておりテスト不能
- **現状:** [`NoteViewModel` L60-88](../../app/src/main/java/com/example/newproject/NoteViewModel.kt#L60) が `NoteHistoryStore`・`NoteRepository`・`AICoreClient`・3 UseCase・7 Controller・`DistillWriteRepository` を**すべて内部で直接生成**している。依存生成・Vault/テーマ/キャッシュ・7 Controllerの調停・ノート読込・要約/関連/モデルDL・状態リセット・Lifecycle連携を担う。
- **問題:** 差し替え口が無いため、**JVMテスト391件が1件も `NoteViewModel` を通っていない**。Controller間の調停ロジック（蒸留保存後の状態維持・Vault切替時の一斉リセット等）は最も壊れやすい部分なのに無検証。
- **対応候補:** → **[B案](#b案-noteviewmodel-の依存を外出しし状態スライスと依存方向を型とciで守る)**。`ViewModelProvider.Factory` かコンストラクタ引数へ依存生成を出す。DIライブラリの導入までは不要。
- **前提関係:** **これを先にやらないと 3-1 の ViewModel 統合テストが書けない。** A案（2026-07-26 完了）で Controller 単体の世代照合は固定できたが、`loadFolders` と補記一覧・削除は非nullの `Uri` を要するためJVMテストで固定できていない。**それを ViewModel 越しに検証するにはここが前提**になる。
- **規模感:** 中。

### 2-11. 単一 UiState を7 Controller が共有所有している
- **現状:** [`NoteUiState`](../../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L212) は17フィールドを持つ単一の `data class` で、同じ `MutableStateFlow<NoteUiState>` を7 Controller 全員が `update` する。[`MainActivity` L83](../../app/src/main/java/com/example/newproject/MainActivity.kt#L83) は `darkTheme` 判定のため `AppTheme` の外で全体を購読している。
- **問題:**
  1. **Controllerが担当外フィールドも書ける。** 制約はKDocの「〜の更新のみを行う」という口約束だけで、コンパイラは何も止めない。
  2. **17フィールドのどれが変わっても最上位から再評価が走る。**
- **対応候補:** → **[B案](#b案-noteviewmodel-の依存を外出しし状態スライスと依存方向を型とciで守る)**。段階1として `NoteUiState.kt` を機能別ファイルへ分割（ファイルサイズより「誰が何を持つか」を見えるようにするのが目的）。段階2として、Controllerへ渡すものを **`MutableStateFlow<NoteUiState>` そのものではなく機能別の更新インターフェース**（例 `SearchStateWriter`）に替え、担当外フィールドを書けないことを型で保証する。なお **②だけなら `darkTheme` を別Flowへ出せば単独で解消できる**。
- **注意:** **ファイル分割だけでは①も②も直らない。** 所有権と再評価範囲を変えるのは段階2のほう。
- **規模感:** 段階1は小、段階2は中。

### 2-12. 読書痕跡の保存が `viewModelScope` に載っており、アプリ終了時に失われる（2026-07-26 追加）
- **現状:** [`recordVisit()`](../../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L259) の保存は `scope.launch { withContext(ioDispatcher) { ... } }` で、`scope` は `viewModelScope`。
- **問題:**
  1. **終了経路で訪問が消える。** タスクスワイプ／Activity finish では `onStop()` → [`pauseReadingTrace()`](../../app/src/main/java/com/example/newproject/MainActivity.kt#L382) の直後に `onCleared()` が走り、**IOへディスパッチされる前のコルーチンはキャンセルされる**。「背面化で書いた訪問は失われない」という同ファイルKDocの設計意図が、終了経路では成立していない（背面化のみなら成立する）。
  2. **保存失敗が沈黙し、再試行もされない。** [`active.dirty = false` と `recordedVisit = visit` を保存の起動前に確定](../../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L253)させたうえで、`persistence.save()` の戻り値 `ReadingTraceSaveResult` を[捨てている](../../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L283)（[`persistSummary`](../../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L402) も同様）。失敗するとそのセッションの訪問は恒久的に消える。
- **「ベストエフォート」の範囲内か:** ReadingTrace は「シンプル最優先・失われるのは痕跡だけ」（横断テーマ2）の方針であり、**破損時に諦めること自体は設計どおり**。ただし本件は破損ではなく「書きにいく機会そのものを失う」ため、方針の範囲外として扱う。ユーザーのノート(.md)には影響しない。
- **対応候補:** → **[C案](#c案-入出力に用途別の予算と結果の扱いを持たせる)**。保存をアプリ寿命のスコープ（`ProcessLifecycleOwner` か `Application` スコープ）へ移す。`ReadingTraceSaveResult` を受け、失敗時は `dirty` を戻して次の契機で再試行する。
- **規模感:** 小〜中。スコープ移動はテスト用に `CoroutineScope` を注入する形（2-10 と同じ方向）で入れる。

### 2-13. Markdown本文の無制限読込がメモリ上限を無効化する（2026-07-26 追加）
- **現状:** 蒸留用の [`readNoteSnapshot()`](../../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L141) には256KB基準と境界検査があるが、汎用の [`readNoteContent()`](../../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L133) は `readText()` でEOFまで読む。この無制限経路は、**大きすぎるノート・不正UTF-8からの表示フォールバック**（[`loadNoteForDistill()`](../../app/src/main/java/com/example/newproject/NoteViewModel.kt#L448)）と**関連ノート候補の本文読込**（[`fetchRelatedNotes()`](../../app/src/main/java/com/example/newproject/NoteViewModel.kt#L518)）で使われる。
- **問題:** 関連ノート処理は候補40件・最大8並列まで制御しているが、[`RelatedNotesUseCase`](../../app/src/main/java/com/example/newproject/domain/RelatedNotesUseCase.kt#L93) が**各本文を丸ごと読んでから短いスニペットへ縮める**。巨大ファイルが混ざると、AI入力の文字数上限（2-2）が効くより前にメモリとI/Oを消費する。皮肉なことに、蒸留できないほど大きいノートほど無制限経路へ落ちる。
- **対応候補:** → **[C案](#c案-入出力に用途別の予算と結果の扱いを持たせる)**。表示用・スニペット用・蒸留用で読込APIと上限を分ける。候補抽出は必要な先頭バイトだけをストリーム処理する。
- **規模感:** 中。

---

## 3. 低優先度（顕在化していないが記録）

### 3-1. 統合テスト不足
- SAF走査・補記保存/削除・Gemini Nano・Compose Navigation・全画面遷移・画面回転/プロセス再生成・連続操作時の競合を絡めたテストがない。`app/src/androidTest` ソースセット自体が存在しない。ローカルユニットテスト391件は純粋ロジックの回帰防止には有効だが、実端末依存の動作は保証範囲外。
- **土台から無い（2026-07-26 追記）:** `androidTest` は「書いていない」のではなく**書ける状態ですらない**。[app/build.gradle.kts](../../app/build.gradle.kts) に `androidTestImplementation` が1行も無く、`compose-ui-test-junit4`・`espresso`・`androidx.test.runner`・`testInstrumentationRunner` がすべて未設定（→ 3-14）。着手時はまず依存追加から始まる。
- 対応候補: Fake `ContentResolver` または instrumentation テストで Vault走査・保存・削除を検証。（解析書 §13.4 / §15-5）
- **前提:** ViewModelを絡めたテストは 2-10（B案）の解消が先。instrumentation は 3-14 が先。

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
- **規模感:** 小。CI（2026-07-26 導入済み）で警告数を固定すると再発しない。ただし `lint { }` ブロック自体が無いため、baseline も `warningsAsErrors` も**現状は設定する場所が無い**（→ 3-14）。

### 3-14. `buildTypes` ブロックが無く、リリースビルド構成が未定義（2026-07-26 追加）
- **現状:** [app/build.gradle.kts](../../app/build.gradle.kts) に **`buildTypes { }` 自体が存在しない**。3-12 は `applicationId` 単体の課題として起票していたが、リリース可能性のギャップはもっと広い。

| 未設定 | 影響 |
|---|---|
| `release { isMinifyEnabled / shrinkResources / proguardFiles }` | R8なしで配布される（難読化・未使用コード削除なし） |
| `signingConfig` | リリース署名の手順が決まっていない |
| `lint { }` | baseline も `warningsAsErrors` も置き場が無い（→ 3-13） |
| `androidTestImplementation` / `testInstrumentationRunner` | instrumentation テストが書けない（→ 3-1） |
| `compileOptions` が Java 8 | `minSdk 26` なら 11/17 へ上げられる |

- **問題:** 単体ではどれも小さいが、**3-1（instrumentation）と 3-13（Lint固定）の前提**になっているため、これらに着手する時点で必ずぶつかる。
- **対応候補:** 3-12（正式 `applicationId` の決定）とまとめて1回で入れる。R8を有効にする場合は Compose・ML Kit GenAI の keep ルール確認が要るため、まず `isMinifyEnabled = false` の `release` を明示するところから始めてもよい。
- **規模感:** 小（R8有効化まで踏み込むなら中。実機での動作確認が要る）。

---

## 4. 横断テーマ（課題の背景にある構造）

1. **Nano制約が全設計を規定している** — 出力256トークン上限・Mutex直列化・60秒タイムアウトが、要約字数・クイズ形式・補記項目数・ReadingTraceの「生の痕跡を先に出して要約は裏で」まで決めている。新機能はこの制約と必ず衝突するため、判断軸として明文化する価値がある。
2. **機能ごとに堅牢性レベルを変える方針への転換** — 蒸留（重厚な原子性・復旧）→ ReadingTrace（シンプル最優先・ベストエフォート）。この使い分けは健全だが、どの機能をどのレベルで守るかの基準を残すと再現性が上がる。
3. **文言と実装の乖離** — 検索フォールバックが代表例（画面は「キーワード一致」と言うのに実装は列挙順の先頭3件だった。2026-07-25 に解消）。機能追加が続く中で、小さいうちに潰すのが得策。
4. **AIの役割を「創作」から「要約」へ落とすと導線が要らなくなる** — ReadingTrace で得た知見。ユーザーの操作を増やさずに価値を出せるかは、**既にある行動の痕跡で足りないか**を先に問うと判断しやすい。→ [reflect_reading_trace](../design/reflect_reading_trace.md) §2
5. **「意識させない機能」は他の機能と作法が違う** — 通知しない・失敗を見せない・自動DLしない、が仕様になる。AI状態UXの統一（3-3）を進める際、ReadingTrace は横展開の対象外として扱う必要がある。
6. **「対策を入れた」と「対策が効いている」は別** — Vault分離（2026-07-25）は、方針が正しかったぶん実装が効いていなくても文書・コミットメッセージが通ってしまった。修正の主張は、修正コードを別の目で読むまで確定させない。→ [bugfix_reports](../bugfix_reports.md) #4
7. **「整理した」と「境界が効いている」も別**（2026-07-26 追加） — パッケージのレイヤー別整理（PR #37）は正しい方向だったが、依存の**向き**は制約されないままで循環が4組残った（2-9）。同様に、コントラストテストが全緑でもAA準拠は意味しない（3-8）し、テスト391件が全緑でも ViewModel は1行も通っていない（2-10）。**「何を保証していないか」を測定と同じ場所に書く**と、この型の誤読を防げる。
8. **横展開は「最後の1本」を取り残す**（2026-07-26 追加・A案で解消） — モデルDLの世代管理は `QuizController`・`AnnotationController`・`DistillController` の3つに入っているのに、**Controller化されなかった `NoteViewModel` 直書きの1本だけが取り残されていた**。パターンを横展開するときは、**Controller一覧ではなく「同じAPIを呼ぶ全箇所」を検索対象にする**（`grep downloadModel` で4箇所目が出る）。同じ型の見落としは 2-12（`viewModelScope` に載ったままの保存）にもある。

9. **テストが「効いている」かは変異させて確かめる**（2026-07-26 追加） — A案の `SummaryControllerTest` は初版が `cancelAndClear()` 経由で書かれていたため、`downloadJob` が止まって requestId ガードに到達せず、**ガードを外しても落ちなかった**。テーマ6の「対策を入れた≠効いている」はテスト自身にも当てはまる。**新しいガードを入れたら、そのガードを1行消してテストが落ちることを確認する**。落ちなければ、テストが検証しているのは別の機構か、そのガードが等価な冗長コードのどちらか（今回は両方あった）。

---

## 5. 改善活動（B案・C案）

2026-07-26 の品質総評（7.2 / 10）を受けた実施計画。**残る2案とも「実装より先に境界を決める」点で共通**しており、
B案＝依存と状態の境界、C案＝入出力の境界にあたる。

> **A案（非同期の境界）は 2026-07-26 に完了した。** Vault世代の採番・全4経路の世代照合・
> 削除失敗件数の表示まで入り、対象課題は本ファイルから削除済み。判断の記録は
> [architecture.md](../design/architecture.md) の「非同期の世代IDを二層にする」節にある。
> **実機確認は未実施**（→ §6 ①）。

**推奨順は C → B。** Cは実害のある不具合の修正、Bは土台の整理なので、
Bを先に置くと不具合を残したまま大きな差分を積むことになる。

### B案. `NoteViewModel` の依存を外出しし、状態スライスと依存方向を型とCIで守る

- **対象課題:** 2-10 → 2-11 → 2-9（この順で進める）、3-1 の前提
- **やること:**
  1. **依存注入:** `ViewModelProvider.Factory` かテスト用コンストラクタで Repository / AiClient / UseCase / 永続化 / `CoroutineScope` を注入可能にする。これで**A案でJVMテストに乗らなかった経路（`loadFolders`・補記一覧/削除）の検証を `NoteViewModel` 越しに書ける**ようになり、Vault切替とController調停の統合テストが初めて成立する（現在391件中0件）
  2. **状態スライス:** 17フィールドの共有 `MutableStateFlow<NoteUiState>` をやめ、Controllerには機能別の更新インターフェースだけを渡す。担当外フィールドを書けないことを型で保証する
  3. **依存方向:** `model → data → domain → ai` の許可方向を明文化し、**importを走査する純JVMテストをCIに載せて**4組の循環を固定・段階解消する（マルチモジュール化は不要）
- **完了の定義:** ①Vault切替時に7 Controller の状態が一斉に初期化されることを ViewModel 越しに検証するテストが通る ②依存方向テストが CI で走り、新しい循環を追加できない
- **上がる軸:** テスト容易性 7.5 → 8.5、保守性 6.5 → 7.5、拡張性 6.5 → 7.5、可読性 7.5 → 8.0
- **規模感:** 大（3段階。1段階ずつ独立してマージできる）。

### C案. 入出力に「用途別の予算」と「結果の扱い」を持たせる

3件とも原因は同じで、**入口で量を決めず、出口で結果を見ていない**こと。

- **対象課題:** 2-13（読込予算）、2-5（累計と保持数の分離）、2-12（保存スコープと保存結果）
- **やること:**
  - `readNoteContent()` の `readText()` を廃し、`readForDisplay(maxBytes)` / `readSnippet(maxBytes)` / `readNoteSnapshot()` の3系統へ分ける。関連ノートの候補抽出は先頭バイトだけをストリーム読みする
  - `ReadingTrace` に `totalVisitCount` を追加して schema v2 へ上げる。v1 は `visits.size` を初期値として移行し、`needsAiSummary` と `cardOf()` を累計基準へ切り替える
  - 痕跡の保存をアプリ寿命のスコープへ移し、`ReadingTraceSaveResult` を受けて失敗時は `dirty` を戻す
- **完了の定義:** 31回目の訪問でAI要約が再生成される回帰テスト／v1→v2 の移行テスト／保存失敗時に `dirty` が戻ることのテスト
- **上がる軸:** 性能 6.5 → 8.0、信頼性 8.0 → 8.5（A案完了後の値から）
- **規模感:** 中。

### 合計効果

「A案後」はコード上の変更が入った時点の見込み値で、**実機確認前なので確定ではない**。

| 評価軸 | 当初 | A案後（見込み） | 残り2案後 | 効く案 |
|---|---:|---:|---:|---|
| 可読性 | 7.5 | 7.5 | 8.0 | B |
| 保守性 | 6.5 | 6.5 | 7.5 | B |
| 拡張性 | 6.5 | 6.5 | 7.5 | B |
| 信頼性 | 7.0 | 8.0 | 8.5 | A, C |
| テスト容易性 | 7.5 | 7.8 | 8.5 | A, B |
| 並行処理 | 7.0 | 8.5 | 8.5 | A |
| 性能 | 6.5 | 6.5 | 8.0 | C |
| セキュリティ・プライバシー | 8.5 | 8.5 | 8.5 | — |
| アクセシビリティ | 6.5 | 6.5 | 6.5 | **なし** |
| 開発・リリース運用 | 7.0 | 7.0 | 7.0 | **なし** |
| ドキュメント | 8.5 | 8.5 | 8.5 | — |
| **総合** | **7.2** | **約7.5** | **約8.0** | |

### 枠外（別立ての小タスク）

B案・C案では**アクセシビリティ 6.5 と開発・リリース運用 7.0 が動かない**。どちらも独立して進められる。

- **D. ライト配色のAA是正**（3-8 → 3-9）— グレー階調の整理と同時に暗い側へ寄せる。見出し2色は彩度を落として明度を下げる
- **E. リリース構成の整備**（3-14 → 3-12 → 3-13）— `buildTypes` を定義し、正式 `applicationId` を決め、Lint 警告数を CI で固定する。**3-1（instrumentation）に着手するなら先にここ**

---

## 6. 次アクションの選択肢（改善活動に含まれないもの）

> **品質の底上げは §5 の B案・C案（＋枠外のD・E）に集約した。**
> ここに残すのは、品質軸の点数では測れない「実機確認」と「機能側の判断」だけ。
> 記号が §5 と衝突しないよう ①〜④ で振る。

- **① 実機確認（A案 と ReadingTrace）** — どちらもJVMテストの範囲外なので同じ端末セッションでまとめて確認する。
  - **A案（2026-07-26 完了分）**: ⓐさがすタブで検索実行中に別フォルダのchipをタップ → 前の結果が出ないこと
    ⓑVault切替直後にさがすタブへ入り、旧Vaultのフォルダchipsが出ないこと
    ⓒ補記管理画面を開いた直後にVault切替 → 旧Vaultの補記が並ばないこと
    ⓓ（モデル未DL状態を作れれば）DL中にノート切替 → 旧ノートの要約・Downloading表示が出ないこと。
    **ⓑとⓒは非nullの `Uri` が要るためJVMテストで固定できていない**ので、ここだけが担保手段になる。
    ⓓは `SummaryControllerTest` でフル検証済みなので、未DL状態を作るのが難しければ省略してよい。
  - **ReadingTrace（2026-07-25 完了分）**: 到達率・Lifecycle・Vault分離・検索フォールバックの4件。
    これが済むまで roadmap N-1 は完了扱いにしない。
    **C案（2-12）で保存スコープを変えるなら、その後にまとめて確認するほうが手戻りが少ない。**
- **② 読書痕跡の保守性**（2-3 孤児ファイル・2-4 索引の無効化契機）— C案が触る 2-5 / 2-12 とは別系統で、
  どちらも同期プロバイダ上での実機確認が要る。①と同じ端末セッションで扱える。
- **③ AI入力の切り出し方を見直す**（2-2）— 品質改善だが Nano の入力予算とのトレードオフがあり、
  関連ノートでフォーカスセクション文脈を採る場合は自動起動との噛み合わせが論点。**B案・C案とは独立。**
- **④ 「意図して問いを残す」の検討** — ReadingTraceで回収できなかった思考の連続性（→ roadmap X-2）。唯一の新機能側の選択肢。

→ 中長期の進め方は [roadmap.md](roadmap.md) に整理する。
