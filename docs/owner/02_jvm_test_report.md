# JVMテスト俯瞰 — 何を、どういう観点で確かめているか

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-07 / **更新:** 2026-09-20。ブランチ `feature/ReNewual_Reflect_Function_No.02`、`77b29d0` で計測
**付録の追加:** 2026-09-24。解析書から全133クラスの一覧を移した。本文は 09-20 のまま
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

---

## 付録. 全133クラスの一覧

本文は観点ごとに代表を挙げている。ここは全クラスを1行ずつ引くための索引で、件数は `testDebugUnitTest` のレポートから採った。
説明は手で書いているので、件数より先に古くなりうる。行頭の `@Test` を数えると同じ1,508件になる。
行頭に限らず数えると4件多く出るのは、文字列リテラルの中に `@Test` を書くテストがあるため。

| テストファイル | ケース数 | 主な対象 |
|---|---:|---|
| `AiAvailabilityContractTest.kt` | 11 | AI可用性の契約。4状態の意味と、呼び出し側9件が取る行動の対応。**分野判定は走行状態を持たないので観測対象から外す** |
| `AnnotationControllerTest.kt` | 10 | 旧補記ファイルの一覧・削除、Vault世代照合、ハンドルの取り直し防止 |
| `BookletControllerTest.kt` | 39 | 冊子の束（10枚・重複なし・0件・走査失敗）、扉の遅延読込（前後1ページ・二重読み防止・失敗の非再試行）、ページ位置の保持と引き直し、Vault世代のすれ違い、**編む束と引く束の寿命・切替・種の固定** |
| `BookletPagerAlignmentTest.kt` | 6 | ページャの引き直しで先頭へ付け替わること、束の世代が変わるまで付け替えないこと、束の切替で位置が戻ること |
| `BookletRestackTest.kt` | 10 | 積み直りの契機と持続、走行フラグが残らないこと、OS設定に従う経路の数 |
| `BookletWeaveTest.kt` | 13 | **編む束の中身**（AI推薦→未リンク→wikilink済みの順、重複は参照で畳む、種を外す、水増ししない）と、トグル3通りの型 |
| `BoundedNoteReadTest.kt` | 9 | 用途別の読込予算、上限到達の判定、多バイト文字の末尾切り |
| `DistillControllerTest.kt` | 45 | 蒸留フローの直列化、requestIdガード、保存後の状態遷移・復旧分岐、**元本文の書き出しがキャンセルでエラーにならず復旧レコードも消さないこと**、自由範囲の保存出力と重なり解消の両方向 |
| `DistillRecoveryStoreTest.kt` | 3 | 復旧レコードの書込・読出・破棄 |
| `DistillWriteRepositoryTest.kt` | 15 | 二重ハッシュ照合、原子確定、出力ハッシュ検証、中断・容量不足 |
| `EventKeyTest.kt` | 4 | Snackbar通知の発火判定キー |
| `FileSummaryCacheTest.kt` | 9 | 要約の保存（1件1ファイル・古い順の削除・大きさの上限・書きかけの片付け・置き場を作れないとき） |
| `InlineMarkdownTest.kt` | 16 | 強調、リンク、コード、打ち消し、誤検出防止、**描画範囲が共有トークナイザーの答えと一致すること**、エスケープ |
| `MarginMemoControllerTest.kt` | 16 | 余白メモの読み出し・追記・削除。保存結果3値の見せ分け、満杯で一覧へ足さないこと、**保存と削除が互いを取り消さないこと**、受理の件数で入力欄を空にすること、長い見出しを保存できる長さへ切ってから渡すこと |
| `MarkdownParserTest.kt` | 38 | frontmatter、テーブル空セル、見出し、コード、CRLF、引用、リストのマーカー保持（区切り記号・先頭ゼロ・巨大桁）、段数の算出規則5つ、タブの4列展開、タスク混在、`blocksToMarkdown` の往復 |
| `NoteDwellGateTest.kt` | 11 | **自動生成の門番**（3秒で開く・離れたら取り消す・冊子と背面で数え直す・開いた門を次のノートへ持ち越さない） |
| `NoteFieldControllerTest.kt` | 24 | **分野判定のController**（索引B命中でAIを呼ばない・失効時にヒントへ降格・有効な確定では索引へ書かないことを回数で見る・キャンセルで何も書かない・切替で索引に触らない・永続の上限） |
| `NoteRepositoryTest.kt` | 4 | Markdown判定、wikilink・タイトル正規化 |
| `NoteSectionControllerTest.kt` | 6 | 表示用Markdown解析のMain外退避と、本文差し替え時の再解析 |
| `NoteSessionCoordinatorTest.kt` | 30 | Vault/ノート切替の一斉停止と一斉初期化、リセット登録漏れ検出（**ノート単位とVault単位の両方**）、旧結果の後着防止、Vault世代、冊子から始めた読込の取り消し、**分野の索引の復元と走査の配線** |
| `NoteSnapshotTest.kt` | 5 | 上限付きバイト読込、UTF-8厳格判定、ハッシュ |
| `NoteUiStateStoreTest.kt` | 2 | 各Writerが担当スライスだけを更新すること、ノート読込開始の単一通知 |
| `QuizControllerTest.kt` | 9 | バックグラウンド生成・確認状態・破棄 |
| `QuizInputProfileTest.kt` | 4 | 入力量・コード比率からの出題形式決定 |
| `QuizPromptBuilderTest.kt` | 3 | 形式別クイズプロンプトの出力契約 |
| `QuizResponseParserTest.kt` | 14 | 改行揺れ、前置き、欠落項目、不正な正解、○×/3択/4択の形式別パース |
| `ReadingTraceBackupControllerTest.kt` | 31 | 痕跡の書き出し・下見・適用・中止、停止待ち中の再入、Vault世代 |
| `ReadingTraceBackupJsonTest.kt` | 10 | 退避ファイルの形式（外から見える生JSON・読めなかった件の扱い） |
| `ReadingTraceBackupTextTest.kt` | 9 | 退避・下見・適用・中止の文言（適用だけ言い方を変える） |
| `ReadingTraceCleanupControllerTest.kt` | 26 | 孤児痕跡の洗い出しと削除、Vault世代照合、削除直前の再走査、削除の直列化と最新の一覧への反映 |
| `ReadingTraceControllerTest.kt` | 65 | 能動読書10秒閾値、最深到達点（可視割合込み）、追記上限、後続bind、二重flush、pause/resumeと訪問の差し替え、Vaultキーの持ち回り、**余白メモの読み出し・追記・削除と、書けなかったぶんの退避** |
| `ReadingTraceJsonTest.kt` | 46 | JSON往復、checksum、UTF-8、必須項目・上限、要約キャッシュ整合、**v1→v6 の各版からの読み込み互換**。旧版の正規形をテスト側に写し取って固定し、**v7 で読み捨てる欄を版ごとの窓で照合すること**まで見る |
| `ReadingTraceLimitsTest.kt` | 2 | 上限どうしの整合（全フィールドを上限まで詰めてもファイル読込上限に収まること） |
| `ReadingTraceMergeTest.kt` | 18 | 読み戻しの併合規則。端末に無いものを受け入れ、既存を黙って上書きしない。**メモは欄ごとに合流し、20件を超えるノートは無変更で保留する** |
| `ReadingTraceStoreTest.kt` | 34 | ハッシュキー、保存/読込、破損・パス不一致、フォルダ/書込失敗、Vaultキーの受け渡しと不一致時の拒否 |
| `ReunionCardControllerTest.kt` | 34 | 再会カードの照合と生成（種別の決定・空振りの記録・印の再掲・切替の後着・印の要求世代を痕跡ごとに数えること）、**メモがあるときだけ入口の行を出すこと** |
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
| `ai/PromptBuilderExcerptRegressionTest.kt` | 7 | 6プロンプトの出力文字列の固定、抜粋時だけ注意書きが出ること |
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
| `architecture/InstrumentationTestShapeTest.kt` | 1 | **`@Test` の戻り値が `void` でなくなる書き方をソース走査で禁じる**（→ [解析書](01_source_code_analysis.md) §13.5） |
| `architecture/KotlinCommentScannerTest.kt` | 17 | コメントの字句解析（文字列リテラルやエスケープの中の記号をコメントと誤認しないこと） |
| `architecture/NoteExcerptThreadingTest.kt` | 1 | 抜粋生成が本番の6ファイル9箇所すべてで `Dispatchers.Default` 側にあること（呼び出し箇所の一覧ごとソース走査で固定） |
| `architecture/NoteSectionThreadingTest.kt` | 3 | 本文解析がMainのスコープから呼ばれていないことをソース走査で固定 |
| `architecture/PackageDependencyTest.kt` | 2 | パッケージ依存の向き（ルートパッケージ経由の抜け道を含む） |
| `architecture/PromptGenerationCoverageTest.kt` | 3 | 全プロンプトが実生成テストで覆われるか未保証として列挙されるか（11本中5本が実生成）、件数の固定 |
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
| `domain/DistillRangeSnapTest.kt` | 20 | 自由範囲の端が置ける位置にしか止まらないこと。書記素（サロゲートペア・結合文字・異体字セレクタ・ZWJ・肌色修飾・国旗の対）、装飾の対、端の空白、反対の端、**倒す向き**（広げるなら外側・狭めるなら内側） |
| `domain/DistillResponseParserTest.kt` | 3 | ID抽出、許可集合外の棄却 |
| `domain/DistillSourceModelTest.kt` | 42 | 文分割、UTF-16オフセット、コード/テーブル/frontmatter除外、**装飾（斜体・太字斜体・打ち消し線）の対を割らないこと／過剰保護もしないこと** |
| `domain/DistillTransformerTest.kt` | 5 | オフセット降順の `**` 挿入、太字比率上限、短文例外 |
| `domain/ImageDecodePolicyTest.kt` | 17 | 復号可否の拡張子判定、寸法・ピクセル数の上限、間引き倍率、**`TooLarge`/`Broken` の切り分け順序** |
| `domain/ImageLinkResolutionTest.kt` | 29 | 画像参照の解析と索引照合（完全パス→ファイル名の順、曖昧・外部URL・空） |
| `domain/KeyedMemoCacheTest.kt` | 5 | LRUメモ化（成功時のみ格納） |
| `domain/LauncherEntryTest.kt` | 4 | ランチャー再タップの重複起動の判定。条件3つの真理値表を全部置き、マニフェストが `launchMode` を持たない前提も見る |
| `domain/MarginMemoComposerTest.kt` | 8 | 余白メモの入力整形。前後の空白と制御文字を落とし、改行とタブは残す。上限で切ったことを持ち帰り、**目安では切らない** |
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
| `ui/BookletPeelGeometryTest.kt` | 13 | **めくりの幾何**（折り目が右下から左上へ走る・表と裏の面積が合う・裏が枠から出ない・静止時に紙が欠けない） |
| `ui/BookletTurnGeometryTest.kt` | 19 | 紙の**置き方**（積み直りの傾き0〜22度・定位置への付け替え・遠い紙を引き寄せない・束の縁の在不在・影・カメラ距離・縮小率） |
| `ui/DistillRangeHandleTest.kt` | 6 | ドラッグで掴む端を**押下の1回で決める**（近いほうを採る・行が違えば横位置が近くても掴まない） |
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
| `ui/theme/AppColorContrastTest.kt` | 28 | 明暗の役割トークンのコントラスト比。文字は4.5:1・塗りと記号は3:1を**強制**する |
| `ui/theme/NoteFieldPaletteTest.kt` | 5 | 分野の6色を値ではなく規則で固定（明度が揃い色相が等間隔）と、面としての合否1件 |
| `ui/theme/VibrantTextUsageTest.kt` | 2 | 画面からの `onVibrant` 直接使用と、文字色への任意の `copy(alpha)` をソース走査で禁じる |
| **合計。133クラス** | **1,508** | |
