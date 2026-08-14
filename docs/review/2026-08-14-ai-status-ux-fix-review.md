# AI状態UX・修正確認レビュー — 2026-08-14

**基準HEAD:** `9837ea9`（`feature/Refactoring_Function_No.XX`）

**対象範囲:** 前回基準 `6eacdcd` から `9837ea9` までの5コミット。
前回P2 3件・P3 1件に対する本番修正、契約・共存テスト、意味上の文書検査、
受付簿とAI-2の処遇更新

**検証コマンド:**

```bash
env JAVA_HOME='/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest --offline --rerun-tasks
git diff --check 6eacdcd...HEAD
```

**実測:** JVMテスト **922件成功・0 failure・0 skipped** / Lint **0 error・0 warning** /
androidTest APK組み立て成功 / `@Test` 宣言 **40件** / 差分検査成功。
instrumentation と実機のNano経路は実行していない。

**追加の変異確認:**

- `SummarizeUseCase` の状態確認から `CancellationException` 専用catchを外して
  `AiUnavailable` へ畳ませても、`AiAvailabilityContractTest` は**全件成功した**
- 候補生成をDeferredで保留する既存テストへ
  `sectionChatStatus == Working` を追加すると、実値 `Ready` で**失敗した**

> **位置づけ:** 本書は `9837ea9` 時点の独立した机上評価である。過去レビューの要約と処遇は
> README・findings、原文はgit履歴に残る。

## 結論

前回4件の**元の成立順序はすべて閉じた**。開始できないDLを求める文言は消え、
質問候補の偽進捗は明示フラグで終端し、状態確認例外は3経路とも走行状態を下ろす。
意味上の旧契約3件も現物へ同期された。

一方、新設した `isSuggestionsLoading` が派生表示へ渡らず、候補生成中に
Vigilith/FABが `Ready`（完了）を示す。契約テストも「全呼び出し経路」「キャンセル再throw」を
主張する範囲より狭く、実際に自動要約の再throwを外す変異を緑で通した。
正本文書の状態一覧とKDocリンクにも2件の同期漏れがある。

新規指摘は **P2 2件・P3 1件**。P1は確認しなかった。
実機確認へ移る前に、机上で閉じられる3件を先に閉じる必要がある。

## 1. 前回指摘の判定

| # | 前回の指摘 | 判定 | 根拠・残り |
|---|---|---|---|
| 2026-08-14/P2-1 | セクションチャットが実行できないDL案内を出す | **解消** | `canStartDownload = false` で `Download` actionと「開始してください」を外し、要約・回答の両経路でDLを呼ばないテストがある |
| 2026-08-14/P2-2 | AI状態・候補失敗後も「質問候補を準備中…」が残る | **解消** | `isSuggestionsLoading` と `suggestionsDisplay()` で開始・成功0件・例外・AI状態を分け、元の偽進捗は消えた。追加フィールドの別派生漏れは新規P2-1で扱う |
| 2026-08-14/P2-3 | 状態確認例外でセクションチャットと自動要約が走行状態を残す | **解消** | 3経路とも状態確認を例外境界へ入れ、非キャンセル例外後の終端状態を直接テストした。キャンセル分岐の検査不足は新規P2-2で扱う |
| 2026-08-14/P3-1 | KDoc・テスト名・正本に意味上の旧契約が残る | **解消** | 指摘した3文は現物へ同期され、`RETIRED_CLAIMS` がKDoc・テスト名・正本を走査する。今回追加した別の文書漏れは新規P3-1で扱う |

## 2. 指摘

### P2-1. 質問候補の生成中に派生表示が `Ready` を返す

- **該当箇所:** [`SectionChatState.kt:37`](../../app/src/main/java/com/example/newproject/model/state/SectionChatState.kt#L37)、
  [`VigilithMode.kt:32`](../../app/src/main/java/com/example/newproject/ui/vigilith/VigilithMode.kt#L32)、
  [`SectionChatCombinationTest.kt:268`](../../app/src/test/java/com/example/newproject/SectionChatCombinationTest.kt#L268)
- **成立する順序:** ①セクション要約が完了する ②候補生成だけをDeferredで保留する
  ③ `isSuggestionsLoading == true` かつ `suggestionsDisplay() == Loading` になる
  ④ `sectionChatStatus()` は新フィールドを読まず、`summary != null` で `Ready` を返す
  ⑤シートは「質問候補を準備中…」、Vigilith/FABは「完了」を同時に示す
- **影響:** 同じ処理について進行中と完了が同時に表示される。全画面側では
  `Ready` が「AI生成完了。タップで開く」と読まれるため、要約だけでなくAI処理全体が終わったように見える
- **修正方針:** `sectionChatStatus()` の走行判定へ `isSuggestionsLoading` を含める。
  もし状態を「要約だけの完了」と定義するなら、関数KDoc・全画面文言・型名をその狭い意味へ揃える
- **受理条件:**
  - 候補生成を保留中は `suggestionsDisplay == Loading` と `sectionChatStatus == Working` が同時に成立する
  - 候補生成完了後は `Ready` になる
  - AI状態で候補Jobを始めていない場合は、従来どおり `Idle` のままになる
  - 既存のDeferredテストへ上記assertを足し、`isSuggestionsLoading` を走行判定から外す変異で失敗する
- **規模感:** 小

### P2-2. 契約テストが「全経路」とキャンセル再throwを保証していない

- **該当箇所:** [`AiAvailabilityContractTest.kt:41`](../../app/src/test/java/com/example/newproject/AiAvailabilityContractTest.kt#L41)、
  [`AiAvailabilityContractTest.kt:146`](../../app/src/test/java/com/example/newproject/AiAvailabilityContractTest.kt#L146)、
  [`SummarizeUseCase.kt:29`](../../app/src/main/java/com/example/newproject/domain/SummarizeUseCase.kt#L29)
- **成立する順序:** ①本番には `checkAvailability()` の呼び出し式が10箇所ある
  ②新テストの例外表は6経路で、Search・Related・ReadingTrace・ひとこと映し返しの4呼び出しを実行しない
  ③キャンセルテストはその6経路のうち、ひとこと生成とセクション回答も実行しない
  ④自動要約から `catch (CancellationException) { throw e }` を外す
  ⑤広いcatchがキャンセルを `AiUnavailable` へ変えるが、テストは
  「`SummaryState.Error` ではない」しか見ないため、`AiAvailabilityContractTest` 全件が成功する
- **影響:** 実装は現時点で正しいが、CLAUDE.mdの必須原則であるキャンセル再throwを戻す退行と、
  未実行4経路の例外処理退行を、横断契約テストが緑のまま通す。「全呼び出し経路」の保証名が実測より広い
- **修正方針:** `SummarizeUseCase.summarize()` を直接呼び、投入した
  `CancellationException` と同じインスタンスがthrowされることを検査する。
  セクション回答・ひとこともキャンセル側へ含める。10呼び出しを本当に横断するか、
  対象を「走行状態を持つ6経路」へ狭めて、除外4経路の既存テストを対応表で示す
- **受理条件:**
  - 自動要約の専用catchを外して `AiUnavailable` へ畳む変異で失敗する
  - セクション回答とひとこと生成でも、キャンセルが問題・Errorへ変換されないことを検査する
  - 「全呼び出し経路」を名乗る場合は本番10呼び出しとテストの対応が1対1で追える
  - 6経路へ限定する場合はKDoc・テスト名・現行課題の主張を同じ範囲へ狭める
- **規模感:** 小〜中

### P3-1. 新しい状態欄が正本から抜け、修正したKDocリンクも切れている

- **該当箇所:** [`section_ai_chat.md:72`](../dev/features/section_ai_chat.md#L72)、
  [`RemarkState.kt:13`](../../app/src/main/java/com/example/newproject/model/state/RemarkState.kt#L13)
- **成立する順序:** ① `SectionChatState` に `isSuggestionsLoading` を追加する
  ②機能正本の状態一覧は旧7欄のままで、新しい欄と `SuggestionsDisplay` の意味を記録しない
  ③ `RemarkState` KDocは正しい説明へ直すが、相対リンクの `..` が2階層不足する
  ④リンクはリポジトリの `docs/` ではなく、存在しない `app/src/docs/` を指す
- **影響:** 影響面監査が要求する「状態欄を変えたら正本文書も直す」が完了証拠を持たず、
  KDocから判断の正本へ辿れない。コードの意味は正しくても次の変更者が根拠を確認できない
- **修正方針:** 状態一覧へ `isSuggestionsLoading` と表示導出を追記し、KDocリンクを実在する
  `docs/dev/system/background_ai_ux.md` へ直す。可能ならKDoc内相対リンクもリンク検査の対象へ入れる
- **受理条件:**
  - 正本の `SectionChatState` 一覧が実装の全フィールドを含む
  - `RemarkState` KDocのリンクをソース位置から解決した実ファイルが存在する
  - 新しい状態欄を正本から落とす変異、またはKDocリンクを旧パスへ戻す変異を検査が検出する
- **規模感:** 小

## 3. 保証していない範囲

- 本レビューは机上確認であり、AI状態UXの実機確認7項目は未実施である。
- `assembleDebugAndroidTest` は40件のコンパイルとAPK組み立てまでで、instrumentationは実行していない。
- 実端末のNano状態遷移・AICore能力判定・高速ノート切替は覆っていない。
- P2-2の本番コードは現時点で再throwしている。指摘は、その退行を契約テストが検出できない点である。
- AI状態UX以外の既存課題は再評価していない。

## 4. 同型の再発

| 過去に扱った型 | 今回の対応関係 | 再発防止の観点 |
|---|---|---|
| 状態欄を足した直後、別の派生状態が古いまま残る | `isSuggestionsLoading` はシート表示だけへ配線され、Vigilith/FABの派生が抜けた（P2-1） | CLAUDE.mdの影響面監査どおり、欄名を読む全UI・派生状態・正本をgrep結果で列挙する |
| テストの説明が実際の観測より広い | 6経路を「全呼び出し経路」、4経路のキャンセルを「どの経路も」と記した（P2-2） | 対応表の母数を本番呼び出し式から作り、変異が落ちることまで完了証拠にする |
| 文書修正後に別の文書・リンクが残る | 意味上の旧3文は直したが、新しい状態一覧と修正箇所自身のリンクを確認しなかった（P3-1） | 主張の文言だけでなく、フィールド一覧とリンク解決も影響面監査へ含める |

## 5. 課題起票

新規3件はAI状態UXの修正確認で見つかったため、既存の **AI-2へ統合**する。
P2-1・P2-2・P3-1を机上で閉じた後も、既存の実機確認7項目は省略しない。

本書は `9837ea9` 時点のスナップショットとして後から書き換えない。
