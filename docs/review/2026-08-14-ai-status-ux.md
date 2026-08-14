# AI状態UX・机上再レビュー — 2026-08-14

**基準HEAD:** `6eacdcd`（`feature/Refactoring_Function_No.XX`）

**対象範囲:** merge-base `6460b668` から `6eacdcd` までの19コミット。AI状態の5値化、
8経路の分類・表示、セクションチャットの再試行分離、テストダブル統合、文書ドリフト検査、
影響面監査と共存テストを含む本番・テスト・設計差分72ファイル

**検証コマンド:**

```bash
env JAVA_HOME='/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest --offline --rerun-tasks
git diff --check 6460b66865b880a443fe1fe6b2a890dd5048420b...HEAD
```

**実測:** JVMテスト **908件成功・0 failure・0 skipped** / Lint **0 error・0 warning** /
androidTest APK組み立て成功 / `@Test` 宣言 **40件** / 差分検査成功。
instrumentation と実機のNano経路は本レビューでは実行していない。

> **位置づけ:** 本書は `6eacdcd` 時点の独立した机上評価である。過去レビューの要約と処遇は
> README・findings、原文はgit履歴に残る。

## 結論

直近3件（要約再試行による回答Jobの巻き添え、端末AI状態を生成中とする派生表示、
`reflect_remark.md` の旧状態記述）は、元の成立順序を閉じるコードとテストが入り**解消**している。
`SectionChatCombinationTest` も要約・回答の両方向と共存状態を固定している。

一方、表示面まで横展開すると、セクションチャットには**実行できないDL案内**と
**処理が無いのに残る質問準備中表示**がある。また、統合Fakeが明示する
`checkAvailability()` の契約違反側を、セクションチャットと自動要約だけは処理できず、
進行中状態が残る。文書・KDoc・テスト名にも、名前ベースの検査では拾えない意味上の旧契約が3箇所残る。

新規指摘は **P2 3件・P3 1件**。P1は確認しなかった。実機確認7項目へ進む前に、
P2-1〜P2-3の机上で閉じられる順序を先に閉じる必要がある。

## 1. 前回指摘の判定

| # | 前回の指摘 | 判定 | 根拠・残り |
|---|---|---|---|
| 2026-08-13 r5/P2-1 | 要約再試行が生成中の回答をキャンセルし、`isGenerating` が残る | **解消** | `retrySummary()` は `openJob` だけを止める。回答を保留したまま要約を再試行し、回答完了後にフラグが下がる単体・共存テストがある |
| 2026-08-13 r5/P2-2 | `AiStatus` が `Working` になり、生成していないのにインジケータが続く | **解消** | `sectionChatStatus()` は走行フラグを先に見た後、`summaryProblem != null` を `Idle` にする。`AiStatus` の `None` / `Retry` と生成失敗の別テストがある。シート内の別の偽進捗は新規P2-2で扱う |
| 2026-08-13 r5/P3-1 | `reflect_remark.md` と `retryAi()` KDocに旧契約が残る | **解消** | §9は `AiNotice` へ、KDocは `retrySummary()` / `retryAnswer()` へ更新され、指摘された記述は残っていない。別箇所の意味上のドリフトは新規P3-1で扱う |

判定は修正コミットの有無ではなく、前回に示された再現順序が閉じたかで行った。

## 2. 指摘

### P2-1. セクションチャットが `Download` action を運びながら実行できない案内だけを出す

- **該当箇所:** [`SectionChatController.kt:145`](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L145)、
  [`SectionChatSheet.kt:220`](../../app/src/main/java/com/example/newproject/ui/screen/SectionChatSheet.kt#L220)、
  [`AiStatusNoticeRow.kt:41`](../../app/src/main/java/com/example/newproject/ui/component/AiStatusNoticeRow.kt#L41)、
  [`section_ai_chat.md:137`](../dev/features/section_ai_chat.md#L137)
- **成立する順序:** ①シートを開いた時点で `checkAvailability()` が `NeedsDownload` を返す
  ②共通変換は「通信量を確認してから開始してください」＋ `AiNoticeAction.Download` を作る
  ③ `SectionChatProblemRow` は `onRetry` だけを渡し、`onDownload` は渡さない
  ④共通Composableはコールバックの無い `Download` ボタンを描かない
  ⑤画面には「開始してください」と出るが、開始する操作は存在しない。
  なお機能正本は「ここではモデルDLを始めない」と決めており、案内と設計も一致しない
- **影響:** ユーザーが明示的に開いた機能で、次に何をすればよいか分からない。
  ノート要約側の自動DLと競合した場合は、確認を求める文言の裏でDLが自動開始される可能性もあり、
  表示が実際の操作契約を表さない
- **修正方針:** 「シートからDLしない」という判断を維持するなら、`NeedsDownload` だけは
  実在する待ち方・移動先を説明するセクションチャット固有のnoticeへ変え、`Download` actionを持たせない。
  共通の `Download` noticeを使うなら、実際にDLを開始できるコールバックを最後まで配線する。
  どちらでも文言・action・コールバックを同じ契約にする
- **受理条件:**
  - `NeedsDownload` で、画面に出る主操作が実際に呼べるか、主操作を要求しない文言になる
  - 「ここではDLしない」を選ぶ場合、シート操作で `FakeAiClient.downloadCalls` が増えず、案内も「開始してください」にならない
  - `AiNoticeAction.Download` を持つnoticeを、`onDownload == null` のままユーザー操作起点の画面へ渡す変異をテストが検出する
- **規模感:** 小

### P2-2. AI状態または候補生成失敗の後も「質問候補を準備中…」が永久に残る

- **該当箇所:** [`SectionChatSheet.kt:142`](../../app/src/main/java/com/example/newproject/ui/screen/SectionChatSheet.kt#L142)、
  [`SectionChatController.kt:145`](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L145)、
  [`SectionChatController.kt:276`](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L276)
- **成立する順序:** ① `Unsupported` / `Downloading` / `TemporarilyUnavailable` 等でシートを開く
  ②Controllerは `isSummaryLoading = false` と `summaryProblem` を設定し、候補生成は始めない
  ③ `suggestions` は空のまま
  ④UIは `suggestions.isEmpty() && !isSummaryLoading` だけを「準備中」の条件にしているため、
  実行Jobが無くても「質問候補を準備中…」を出し続ける。
  `fetchSuggestions()` が例外を黙って捨てた場合や空リストを返した場合も同じ終点になる
- **影響:** 前回修正でVigilithのインジケータは止まる一方、同じシート内では処理中表示が残る。
  ユーザーは待てば候補が届くと誤認し、セッション終了か再試行の判断ができない
- **修正方針:** 候補の進行状態を `suggestions.isEmpty()` から推測しない。
  `isSuggestionsLoading` または候補結果のsealed状態を持ち、開始・成功（0件を含む）・失敗・AI状態で必ず終端へ移す。
  要約の問題があり候補Jobを始めない場合は、候補欄を隠すか安定した説明へする
- **受理条件:**
  - 候補生成をDeferredで保留している間だけ「質問候補を準備中…」が出る
  - `Unsupported` / `TemporarilyUnavailable` でControllerが落ち着いた後は同文言が出ない
  - 候補生成の例外と正常な0件のどちらでも、処理中表示が解除される
  - `sectionChatStatus == Idle` の状態と、シート内の処理中表示が同時に成立しない
- **規模感:** 小〜中

### P2-3. 状態確認例外がセクションチャットと自動要約だけで進行中状態を残す

- **該当箇所:** [`FakeAiClient.kt:35`](../../app/src/test/java/com/example/newproject/fakes/FakeAiClient.kt#L35)、
  [`SectionChatController.kt:107`](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L107)、
  [`SectionChatController.kt:188`](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L188)、
  [`SummarizeUseCase.kt:25`](../../app/src/main/java/com/example/newproject/domain/SummarizeUseCase.kt#L25)、
  [`SummaryController.kt:44`](../../app/src/main/java/com/example/newproject/controller/SummaryController.kt#L44)
- **成立する順序:** ①統合Fakeの `availabilityFailure` に `IllegalStateException` を設定する
  ②セクション要約は `isSummaryLoading = true`、回答は `isGenerating = true`、自動要約は `SummaryState.Loading` にしてから状態確認する
  ③3経路とも `checkAvailability()` が各 `try` の外にあるため例外がlaunchから脱出する
  ④既存の `GenerationFailed` / `SummaryResult.Error` へ到達せず、進行中状態が残る。
  Distill・Quiz・Remark・Search・Related・ReadingTraceは状態確認を含む外側で例外を処理しており、横断方針も揃っていない
- **影響:** 本番 `AICoreClient` は状態確認例外を `TemporarilyUnavailable` へ畳むため、現行Nano実装では通常到達しない。
  ただし `AiClient` は他実装を許す公開契約で、統合Fake自身も「契約違反側に呼び出し側が耐える」ための口だと明記している。
  Stub・将来実装・誤配線で例外が出ると、チャットと要約だけが永久待機になる
- **修正方針:** 状態確認から状態終端までを同じ例外境界に入れる。
  `CancellationException` は再throwし、その他は各起動契機の既存方針
  （明示操作は再試行可能な問題、自動要約は `Error` または黙る状態）へ必ず落とす。
  `availabilityFailure` をDistillだけでなく全 `checkAvailability()` 呼び出しへ横展開する契約テストにする
- **受理条件:**
  - セクション要約の状態確認例外後に `isSummaryLoading == false` となり、理由と再試行が残る
  - 回答の状態確認例外後に `isGenerating == false` となり、未回答の質問を積み直さず再試行できる
  - 自動要約の状態確認例外後に `SummaryState.Loading` が残らない
  - 3経路とも `CancellationException` はエラー状態へ変換されず再throwされる
  - `availabilityFailure` を全呼び出し経路へ当て、走行フラグが残らないことを一覧で追える
- **規模感:** 小〜中

### P3-1. 名前検査では拾えない旧契約がKDoc・テスト名・正本に残っている

- **該当箇所:** [`RemarkState.kt:8`](../../app/src/main/java/com/example/newproject/model/state/RemarkState.kt#L8)、
  [`AiStatusNoticesTest.kt:38`](../../app/src/test/java/com/example/newproject/domain/AiStatusNoticesTest.kt#L38)、
  [`background_ai_ux.md:162`](../dev/system/background_ai_ux.md#L162)
- **成立する順序:** ① `DesignDocStateNameTest` が旧識別子を検査して緑になる
  ② `RemarkState` KDocは、実際には専用画面で読む「ひとこと」を「読書画面へ直接1文が出る」と説明する
  ③DL中テストの表示名は、AAR確認後に否定した「DLへ合流する」を現在形で残す
  ④横断正本は `PickerResult.Error` と `RelatedNotesResult.Error` を「2件とも削除」とするが、
  両variantは宣言されたままで、前者は生成例外から実際に構築される
  ⑤識別子はいずれも現存するため、名前ベース検査は成功し続ける
- **影響:** 次の変更者が結果の置き場所、DL中のSDK契約、到達可能なエラー経路を逆に理解する。
  とくに「合流」は過去にP1を生んだ誤契約で、テスト一覧そのものが誤った保証名になる
- **修正方針:** 3箇所を現物へ合わせる。意味上のドリフトを汎用的な名前検査へ無理に載せず、
  今回追加した影響面監査で、状態の読者に加えてKDoc・テスト名・正本の成立文を確認対象にする
- **受理条件:**
  - `RemarkState` が「結果は専用画面、未確認を持たない理由は永続化され後から辿れるため」と説明する
  - DL中テスト名から「合流」が消え、「新しいDLを始めない」保証名になる
  - 正本が `PickerResult.Error` は生成例外で到達し、`RelatedNotesResult.Error` は宣言だけが残る現物を正しく記すか、実装側を記述どおり整理する
  - `読書画面へ直接1文` / `DL中は合流するだけ` / `到達不能な variant が2件` の旧文を戻す変異を、限定的な文書契約検査が検出する
- **規模感:** 小

## 3. 保証していない範囲

- 本レビューは机上確認であり、AI状態UXの実機確認7項目は未実施である。
- `assembleDebugAndroidTest` は40件のコンパイルとAPK組み立てまでで、instrumentationは実行していない。
- 実端末の `UNAVAILABLE`、AICoreアプリ能力判定、DL中の状態遷移、Nano経路の高速ノート切替は覆っていない。
- `SectionChatSheet` のnotice actionと質問準備表示を直接観測するCompose UIテストは無い。
- P2-3は、本番 `AICoreClient` が現在守る「状態確認で非キャンセル例外を投げない」経路では通常到達しない。
  `AiClient` の代替実装・契約違反へ耐えるという、Fakeと計画が明示した保証の不足である。
- beta2 AARの逆アセンブルは本レビューでは再実施していない。現行コード・既存のAAR確認記録・テストの整合を読んだ。
- AI状態UX以外の既存課題（TRACE-3、IMG-2、AI-3等）の再評価は対象外である。

## 4. 同型の再発

| 過去に扱った型 | 今回の対応関係 | 再発防止の観点 |
|---|---|---|
| `AiStatusNotice` を文字列へ潰すと導線が消える | 型は保持したが、`Download` のコールバックだけ終端UIへ渡さず、実質的に導線を再び落とした（P2-1） | action・文言・実コールバックを1組でテストし、型を運んだことだけを完了証拠にしない |
| 生成していないのに `Working` を導出する | Vigilithの派生状態は直したが、空リストから推測するシート文言が同じ偽進捗を残した（P2-2） | 同じ状態を読む全UI・派生状態をgrepし、「実行Jobがあるときだけ進行中」を面で固定する |
| Fakeに揺らす口を足しても、指摘実例へ当てなければ緑の意味は広がらない | `availabilityFailure` は統合したが、使用はDistillの2件だけで、SummaryとSectionChatの例外境界が空いた（P2-3） | 共通インターフェースの契約テストは全呼び出し箇所の表を持ち、少なくとも状態終端を横断する |
| 旧名の検査を足しても意味上の旧仕様は検出できない | 直近3巡で文書を直した後も、現存識別子を使った誤説明が3箇所残った（P3-1） | 状態変更の影響面監査に、読者コードだけでなくKDoc・テスト名・正本の主張を含める |

## 5. 課題起票

新規4件は、いずれも実機確認待ちのAI状態UXと同じ変更単位なので、既存の **AI-2へ統合**する。

- P2-1〜P2-3は実機確認の前に机上・JVM/Composeテストで成立順序を閉じる。
- P3-1は同じ修正内で現物へ同期し、実機確認項目とは分けて完了判定する。
- 修正後も、既存の実機確認7項目は省略しない。

本書は `6eacdcd` 時点のスナップショットとして後から書き換えない。
