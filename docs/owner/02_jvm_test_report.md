# JVMテスト俯瞰 — 何を、どういう観点で確かめているか

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-07 / **更新:** 2026-09-20。ブランチ `feature/ReNewual_Reflect_Function_No.02`、`77b29d0` で計測
**位置づけ:** テストスイートを読むための地図。`owner/` の他文書と同じく、
指示があったときに通しで見直す読み物である → [README](README.md)。
正本ではない。個々の判断理由は各テストのKDocと `dev/` が持つ。ここはその入口。

---

## 0. 3行でいうと

- JVMテストは **133クラス・1,508件**あり、**3秒**で終わる。端末もエミュレータも要らない
- 中身は「機能が正しいか」だけではない。**半分近くが「構造・規約・文書が壊れていないか」を見ている**
- 見ている観点は6種類に分けられる。A 純関数、B 非同期と状態、C 組み合わせ、D 見た目の数値化、E 構造と文書、F 計測器そのもの

---

## 1. 数字で見る全体像

| 置き場 | ファイル | テスト件数 | 何を見ているか |
|---|---:|---:|---|
| `test/.../`（直下） | 47 | 733 | Controller・保存と読込・パーサ。アプリの動きの本体。共有フェイク `FakeVault.kt` を含む |
| `test/.../domain/` | 35 | 425 | 純関数。採点・抜粋・スコアリング・解析・分野判定・余白メモの入力整形。`markdown/` 1本を含む |
| `test/.../ui/` | 20 | 189 | 表示ロジックを数値として固定。色・幾何・派生状態。`theme/` 3本を含む |
| `test/.../architecture/` | 25 | 87 | コードではなく規約と文書を守る検査 |
| `test/.../ai/` | 7 | 35 | プロンプト組み立てと生成失敗の判定。端末AIは呼ばない。共有サンプル `PromptSamples.kt` を含む |
| `test/.../testing/` | 5 | 39 | **要約の採点器そのものの検査。** 共有コーパス `FixedCorpus.kt` を含む |
| `test/.../fakes/` | 3 | — | `FakeAiClient.kt`・保存のフェイク・門番を置かない印。本体ではなく道具 |
| **合計** | **142** | **1,508** | 実行 **3秒**。クラス実行時間の合算は4.3秒。133クラス＋共有ヘルパ9本 |

> 参考: 実機で走らせる instrumentation テストは別に **17ファイル・106件**ある。
> JVMは「端末に触らずに分かること」だけを引き受け、残りを実機へ渡すという分担になっている。
>
> **前回から JVM は48件減った。** 増減を機能で見ると、「ノートへのひとこと」の撤去で63件が消え、
> 「余白メモ」の新設で24件が入っている。instrumentation は余白メモのシートで4件増えた。
> **件数は保証範囲の代理にならない** — 機能を畳めば、その機能を守っていた検査も一緒に消える。

**依存ライブラリは3つだけ。**

```
junit:junit:4.13.2
org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0
org.json:json:20240303          ← Android の org.json をJVM側で代替
```

Robolectric も mockk も使っていない。代わりに `fakes/` の3本と `FakeVault.kt` を手で書いている。
これは横着ではなく設計と対になっている。`model`・`domain`・`controller` の3層は
Android非依存であることをCIで固定してあるので → [architecture](../dev/system/architecture.md) 判断5、
そもそもAndroidを模す仕掛けが要らない。**「テストが素直に書けること」を層の設計で買っている。**

---

## 2. そもそもJVMテストとは。instrumentation との境界

| | JVMテスト。本書の対象 | instrumentation テスト |
|---|---|---|
| 走る場所 | PCのJVM上。`app/src/test/` | 実機・エミュレータ。`app/src/androidTest/` |
| 起動時間 | 秒 | 分。端末の準備を含む |
| 見られるもの | 値・状態遷移・文字列・構造 | 実際の描画・タップ・SAF・端末AI・画素 |
| コマンド | `./gradlew testDebugUnitTest` | `./gradlew connectedDebugAndroidTest` |

**この境界は「できる／できない」ではなく「効いていると言えるか」で引いている。**
このリポジトリでは、テストが効いていることの根拠を変異させて落ちるかに置いている → [L11](../dev/lessons.md)。
実機テストは手元で変異を回せないので、同じ主張を守るならJVM側へ観測点を引き出す、という判断を何度かしている → [L53](../dev/lessons/L53.md)。

例: 蒸留の「確定範囲は太字＋下線で示す」は、もともと `androidTest` が親文の表示だけを見ていて、
強調を丸ごと外しても緑のまま通った。いまは `DistillRangeHighlightTest`・`DistillRangeNoticeTest` がJVM側で持っている。

**逆に、画素を数えないと分からないものは実機へ残す。** 冊子の紙が手前へ倒れる向きは、
値の検査27件がそろって同じ思い込みを写していた。符号の意味は値に現れないので、
`BookletSheetPerspectiveTest` が描いた画素で確かめる → [L61](../dev/lessons/L61.md)。

---

## 3. 6つの観点

### 観点A — 純関数: 入力を入れて出力を見る

いちばん教科書的な形。壊れやすい文字列処理・パース・採点をAndroidから切り離して置いてあるのは、
そのまま素のJVMでテストするためである → CLAUDE.md 必須原則。

代表:

| テスト | 守っている性質 |
|---|---|
| `QuizResponseParserTest` | AIの揺れた応答を救済する。`多択の回答は末尾記号や括弧や余分な語があっても救済する` |
| `MarkdownParserTest`・`InlineMarkdownTest` | 見出し・引用・リンクの解釈。`配列表記の角括弧はリンクにならない` |
| `domain/DistillCandidateScoringTest` | 候補の選び方。`unique final conclusion survives first stage` |
| `domain/NoteExcerptBuilderTest` | AIへ渡す抜粋の作り方。`目標長が大きければノート全体まで広がる` |
| `domain/NoteFieldAnswerTest` | 分野判定の応答は行の全体がIDのときだけ受け付ける。「該当なし」と「読めなかった」を分ける |
| `domain/LauncherEntryTest` | ランチャー再タップの重複起動の判定。条件3つの真理値表を全部置く |
| `domain/BoundedInputStreamTest` | 上限を超える入力で落ちないこと |
| `domain/MarginMemoComposerTest` | 余白メモの入力整形。`改行とタブ以外の制御文字は落とす` `目安を超えても切らない` |

観点は「正常系が通ること」ではなく、ほぼ全部が異常系と境界である。
`本文が空でも落ちない` `異常な寸法でも倍率の上限で止まる` `プラス記号は空白へ変換しない` のように、
**「起きたら困ること」を名前にして並べてある。**

### 観点B — 非同期と状態: 「遅れて届いた結果」を作りにいく

このアプリの事故はほぼここで起きる。AI生成もSAF読み書きも非同期で、
その最中にユーザーはノートを切り替えたりVaultを選び直したりする。

守るのは3つ。

1. 切替時に走行中のジョブが止まる。`cancelNoteScopedJobs()`
2. 止まりきらずに戻ってきた結果を、状態へ書かない。世代ID＋`isCurrent()`
3. どの経路を通っても「走っていないのに走行中フラグが残る」状態にならない

やり方が特徴的で、**「すれ違い」をテストから意図的に作れる穴を fake に開けてある。**

```kotlin
// FakeVault.kt — suspend 関数が戻り値を作る「直前」に呼ばれる
var beforeEachCall: () -> Unit = {}
```

```kotlin
// BookletControllerTest.kt
@Test
fun `扉が戻る直前にVaultが変わったら書き込まない`() = runTest {
    var generation = 0L
    val handle = FakeVaultHandle(snippets = { "本文である。" }, beforeEachCall = { generation++ })
    ...
    assertEquals(BookletCover.Loading, entry(state, 0).cover)   // 旧結果は書かれていない
}
```

`cancel()` では止められない経路、つまり読み出しはもう戻ってきている状況を再現するための穴で、
ここがなければ世代照合が効いているかを確かめられない。「テストを書けるようにするための設計」の実例。

**容量の上限は、計算ではなく実シリアライザで測って固定する。** 余白メモを足したとき、
各欄の上限を足し合わせる形ではファイル上限を保証できないことが分かった。
JSONは `"` と `\` を2バイトへ、制御文字を6バイトへ広げるので、**全欄が上限内のまま128KBを超えられる。**
`ReadingTraceLimitsTest` は本番のエンコーダへ通して測る。**上限を足したり上げたりしたら、測る対象へ必ず加える。**

**今週の1件は「操作 → その直後の値」で終わっていた検査が見逃した。** 分野の確定をヒントへ降格させる処理は
単体では緑だったが、同じ索引には Vault 走査という別の書き手がいて、次の走査で古い確定を戻していた。
検査は「操作 → 状態を戻しうる経路 → 再確認」まで連ねる → [L66](../dev/lessons.md)。

**待たせる側にも同じやり方が要る。** 2026-09-18 に入れた自動生成の門番は「本文が出てから続けて3秒」で開くので、
`advanceTimeBy()` で時計を進めて、開く前と開いた後の両方を作る。門番を外す変異を15通り当てて、
すべてどれかのテストが落ちることを確かめてある → `NoteDwellGateTest`・`NoteSessionCoordinatorTest`。

時間は `StandardTestDispatcher`＋`advanceUntilIdle()` で手で進める。実時間を待たない。

### 観点C — 組み合わせ: 片方ずつのテストでは永久に空く面

`SectionChatCombinationTest` が専用クラスとして独立している理由がそのまま観点になっている。

> `SectionChatController` は独立した2つのJob、要約と回答を持ち、それぞれに
> 「実行中／失敗／端末AIが使えない」の3状態がある。機能ごとのテストは各Jobを単独で通すので、
> 片方が走っている最中にもう片方を操作する経路が丸ごと空く。

実際にそこで2件の欠陥が出た。要約の再試行が走行中の回答を巻き添えにして「生成中…」が永久に残る、など。
**共存しうる2つの処理があるなら、掛け合わせを1クラスとして立てる。** これはこのリポジトリの型になっている。

### 観点D — 見た目を数値へ引き出す

Composeの描画そのものはJVMで見られない。そこで判断の部分だけを純関数へ出して、値として固定する。

| テスト | 観点 |
|---|---|
| `ui/theme/AppColorContrastTest` | WCAG のコントラスト比を計算し、AA基準を割らないことを28件で固定 |
| `ui/theme/NoteFieldPaletteTest` | 分野の6色を値ではなく関係で固定。明度が揃い、色相が等間隔であること |
| `ui/BookletTurnGeometryTest`・`BookletPeelGeometryTest` | 紙の置き方と、折り目がどこを走るか。表と裏の面積が合うこと |
| `ui/ReadingProgressGeometryTest` | `block scrolled above the viewport counts from its top` |
| `ui/VigilithStatusDerivationTest` | 派生状態。`エラーやダウンロード待ちは動作中として演じない` |
| `ui/ReunionLeadTest`・`ReadingTraceHeadlineTest` | 画面に出す文言の選び方 |

**見え方そのもの、重さ・速さ・気持ちよさは、ここでは見ないと明記してある。**
それは時間の中にしかないので実機検証のケース表が持つ → [bearing_channels](../dev/system/bearing_channels.md)。
「見ないもの」を書いておくのが、この層のテストの作法になっている。

### 観点E — 構造と文書: コードの正しさではなく、規約が守られていることを見る

ここが他所ではあまり見ない部分で、25ファイル・87件ある。発想は一貫している。

> このリポジトリで規則が守られたのは検査に載せたときだけ、というのが実績である。
> 文書に書くだけの規則は増やさない。 — `AdrShapeTest` のKDocより

見ている対象は3つに分かれる。

**(1) コードの構造**

| テスト | 守るもの |
|---|---|
| `PackageDependencyTest` | 層の依存が許可した向きだけを向く。`model` は葉 |
| `NoteExcerptThreadingTest`・`NoteSectionThreadingTest`・`ReadingTraceBackupThreadingTest` | 入力サイズに比例する処理をMainスレッドから呼ばない。抜粋は6ファイル9箇所 |
| `AiAvailabilityUsageTest` | `AiAvailability` を等値比較しない。網羅 `when` を強制するので、状態が増えたらコンパイルエラーで気づける |
| `AiClientDoubleTest` | テストダブルを `fakes/` の外に作らせない |
| `DistillProtectedScanTest` | 入力サイズの二乗になる書き方を新しく足させない |
| `SourceCommentShapeTest` | **KDocが2つ続いて宙に浮いていない。本番コメントに日付とレビューの指摘番号が無い。** 2026-09-14 に新設 |
| `BackupExclusionTest` | **端末に残す置き場を足したら、バックアップ除外に載っている。** 忘れても何も起きない型の規則 |

**(2) コードと文書の同期**

| テスト | 守るもの |
|---|---|
| `SourceDocSyncTest` | 状態型に足した欄が、設計書の一覧にも載っている。KDocの相対リンクが実在する。文書がソースを行番号で指さない |
| `DesignDocStateNameTest` | 消した型・値の名前を、文書が現役のように書いていない |
| `SchemaVersionDocsTest` | 文書が名指しする「現行スキーマ版」がコードの定数と一致する |
| `WipIssueReferenceTest` | 課題台帳から消したIDを、他の文書が参照し続けていない |

**(3) 開発プロセスそのもの**

| テスト | 守るもの |
|---|---|
| `ReviewFindingsLedgerTest` | 外部レビューの指摘が1件も取りこぼされずに受付簿へ載っている |
| `AdrShapeTest` | ADRが30行以内。機能仕様が12節を持つ。最終検証が実在するコミットを指す |
| `PromptGenerationCoverageTest` | プロンプトを足したら「実生成で通す」か「未保証と明示する」かを必ず選ばせる。11本中5本が実生成、6本が未保証と宣言 |
| `InstrumentationTestShapeTest` | 実機テストが起動すらしない書き方、`runBlocking` の戻り値型を禁じる |
| `DeviceValidationDocsTest` | 実機検証手順が必須項目を持っている。簡易版が実在しないケースを指していない |
| `DeviceProbeResidueTest` | 実機検証の使い捨て一時テストが作業ツリーに残っていない |

`ReviewFindingsLedgerTest` のKDocに、なぜスクリプトではなくテストなのかが書いてある。

> スクリプトだと「走らせる」という手動契約が新しく生まれ、同じ罠を1段ずらすだけになる。
> テストなら `./gradlew testDebugUnitTest` と CI に自動で載る。

**31ファイルがソースや文書をファイルとして読んでいる。** `walkTopDown`・`readText` を使う。
テストがリポジトリ自身を入力にしているわけで、これがこのスイートのいちばんの特色である。

**ただし走査テストには固有の穴がある。** 同じ行の中でコメントだけを直すとクラスファイルが1バイトも変わらず、
Gradle が UP-TO-DATE と判定して走査テストが飛ぶ。2026-09-14 にコメント規約の検査を置いたとき変異確認で見つけ、
`src/main/java` をテストタスクの入力に足した。文書・`androidTest`・`res/xml` も同じ理由で入力に載せてある。
**走査で守る規則は、Gradle の入力にも載せて初めて守られる。**

### 観点F — 計測器そのものを検査する

2026-09-13 に増えた種類。要約が原文のどこを落としたかを採点する道具を `src/debug` に置き、
その道具が正しく測れることをJVMで固定している → [ai_quality_measurement](../dev/system/ai_quality_measurement.md)。

| テスト | 守るもの |
|---|---|
| `testing/SummaryCoverageTest` | 採点器が「落としたこと」を検出できる。要約の良し悪しではない |
| `testing/SummaryCoverageCalibrationTest` | 閾値を勘ではなく固定コーパスで決める。人が書いた参照と実機の出力の2系統。**実機の出力では正誤を閾値で分離できない**という事実も固定する |
| `testing/SummaryExcerptVariantsTest` | 実機で回す前に、抜粋の変種の形だけを机上で固定する |
| `testing/SummaryBaselinePlanTest` | 実機で何を生成し何を使い回すかを、端末を使わずに固定する。同じプロンプトを2度生成しない |

**採点にAIを使わない。** 検査が被検査と同じ弱点を持つためで、内容語の包含率だけで測る。
**この道具が判断をしないこと**も観点である。出すのは数値と一覧だけで、どれが痛いかは人が見る。

---

## 4. 読むときに効く作法。学習の要点

**1. テスト名が仕様書になっている。** 日本語の平叙文で不変条件を書く。

```
`ノート切替後に旧ノートの再会カードが後着しない`
`要約が成功した後にクイズだけ一時的に使えないなら理由と再試行が出る`
`痕跡が1件も無いなら書き出さない`
`扉が戻る直前にVaultが変わったら書き込まない`
```

`shouldReturnTrue` 系の名前は1つも無い。名前を読めば、何が壊れたのかがそのまま分かる。

**2. KDocに「なぜ要るか／見ているもの／見ていないもの」を書く。**
特に3つ目が重要で、テストの主張範囲を自分から狭めている。
主張だけが検査の顔をしている状態を防ぐため。実際に起きた。

**3. 効いていることは、変異させて確かめる。**
ガードを外してテストが落ちなければ、そのガードかそのテストのどちらかが要らない。
実際に3回この判定をして3回ともガードを削除している → [L11](../dev/lessons.md)。

**4. 走査テストは呼び出しの存在しか見ない。**
`navigate("note")` と書いてあることと、戻ったとき同じ状態が復元されることは別。
変異させた場所が「呼び出しの有無」だけなら、確かめたのは呼び出しの有無でしかない → [L55](../dev/lessons/L55.md)。
だから `BookletRouteContractTest` は、振る舞いを見ている別テストを表にして自分のKDocへ書いてある。

**5. 値ではなく回数で見ることがある。** 分野の確定が有効なときに索引へ書かないことは、
値を見ても分からない。書けば画面が再描画され、開くたびに色が一度点滅する。書き込み回数で検査する。

---

## 5. このスイートが見ていないもの

- **実際の描画・タップ・スクロールの手触り** → `androidTest` 17ファイル106件と、Codexの実機検証
- **端末AIの実生成** → `OnDeviceGenerationTest`。Nano非対応端末では `Assume` で skip。11本のプロンプトのうち5本
- **SAFの実挙動。権限・Uri・実ファイル** → 実機検証
- **文言の自然さ・配色の好み** → レビューとオーナー判断。テストは「読めるか」まで
- **意味の正しさ。** `SourceDocSyncTest` は欄が一覧に載っているかは見るが、説明が正しいかは見ない
- **要約の良し悪し。** 採点器は語の重なりしか測らず、意味は見ていない

---

## 6. 動かし方

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest lintDebug --offline
```

- テスト実行そのものは3秒程度。ビルドから通しても20秒に収まるので、コード変更のたびに通すのが前提の速さになっている
- 失敗したテストの詳細は `app/build/reports/tests/testDebugUnitTest/index.html`
- `androidTest` を触ったときは `assembleDebugAndroidTest` も通す。上のコマンドはコンパイルしない
- マージ後マニフェストの権限は `verifyDebugManifestPermissions` と `verifyReleaseManifestPermissions` が数える。
  debug は `lintDebug` の途中で自動で走るが、release は CI が名指しで呼ぶ
