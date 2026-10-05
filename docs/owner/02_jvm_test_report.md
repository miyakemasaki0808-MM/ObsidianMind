# JVMテスト俯瞰 — 何を、どういう観点で確かめているか

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-07 / **更新:** 2026-10-06。ブランチ `feature/Maintenance_Check_No.01`、`7a2ac616` で計測。本文と付録の両方を同じ時点にそろえた
**位置づけ:** テストスイートを読むための地図。`owner/` の他文書と同じく、
指示があったときに通しで見直す読み物である → [README](README.md)。
正本ではない。個々の判断理由は各テストのKDocと `dev/` が持つ。ここはその入口。

---

## 0. 3行でいうと

- JVMテストは **148クラス・1,740件**あり、**3秒**ほどで終わる。端末もエミュレータも要らない
- 中身は「機能が正しいか」だけではない。**半分近くが「構造・規約・文書が壊れていないか」を見ている**
- 見ている観点は6種類に分けられる。A 純関数、B 非同期と状態、C 組み合わせ、D 見た目の数値化、E 構造と文書、F 計測器そのもの

---

## 1. 数字で見る全体像

| 置き場 | ファイル | テスト件数 | 何を見ているか |
|---|---:|---:|---|
| `test/.../`（直下） | 49 | 828 | Controller・保存と読込・パーサ。アプリの動きの本体。共有フェイク `FakeVault.kt` と、読書痕跡と余白メモの足場を含む |
| `test/.../domain/` | 45 | 552 | 純関数。採点・抜粋・スコアリング・解析・分野判定・余白メモの入力整形と送信の照合・見出しの照合・前回の読書の跡。`markdown/` 2本を含む |
| `test/.../ui/` | 19 | 192 | 表示ロジックを数値として固定。色・幾何・派生状態・余白の面と並べ読みの判定。`theme/` 3本を含む |
| `test/.../architecture/` | 29 | 96 | コードではなく規約と文書を守る検査。コメントを読む道具 `KotlinCommentScanner.kt` を含む |
| `test/.../ai/` | 8 | 33 | プロンプト組み立てと生成失敗の判定。端末AIは呼ばない。共有サンプル `PromptSamples.kt` を含む |
| `test/.../testing/` | 5 | 39 | **要約の採点器そのものの検査。** 共有コーパス `FixedCorpus.kt` を含む |
| `test/.../fakes/` | 4 | — | `FakeAiClient.kt`・保存のフェイク・門番を置かない印・結晶の置き場と控えのフェイク。本体ではなく道具 |
| **合計** | **159** | **1,740** | 148クラス＋共有ヘルパ11本 |

> 参考: 実機で走らせる instrumentation テストは別に **17ファイル・150件**ある。
> JVMは「端末に触らずに分かること」だけを引き受け、残りを実機へ渡すという分担になっている。
>
> **前回から JVM は15件増えた。** 機能を足さない保守の回の分である。
> 旧補記の片付けを撤去したので、その Controller の検査10件が消えた。
> 増えたのは、先頭に BOM があるノートの14件、冊子の扉の9件、画面見出しの余白の2件である。
> BOM の14件は、復号と書き戻しの6件、ファイルのバイト列を読み込みから各機能の解析まで通す5件、蒸留の書き戻しでファイルが元の形を保つ3件に分かれる。
> instrumentation は3件減った。旧補記ファイルを作って一覧し、削除する実 SAF の検査である。

**依存ライブラリは3つだけ。**

```
junit:junit:4.13.2
org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0
org.json:json:20240303          ← Android の org.json をJVM側で代替
```

Robolectric も mockk も使っていない。代わりに `fakes/` の4本と `FakeVault.kt` を手で書いている。
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
| `domain/MarginMemoSectionsTest` | 書いたときの見出し名を今の見出しと照合する。`切り口の直前が空白の長い見出しのメモは、切り口と同じ短い見出しへ取り違えない` |
| `MarkdownParserTest`・`InlineMarkdownTest` | 見出し・引用・リンクの解釈。`配列表記の角括弧はリンクにならない` |
| `domain/DistillCandidateScoringTest` | 候補の選び方。`unique final conclusion survives first stage` |
| `domain/NoteExcerptBuilderTest` | AIへ渡す抜粋の作り方。`単一の超長文段落は冒頭と末尾の両方を残す` |
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

**書いた言葉は、件数ではなく送信そのもので照合する。** 余白メモは、保存の結果が返る前にノートを離れられる。
受理件数はノートの画面の状態なので、戻ると0に戻っていて数えられない。そこで送信に保存値と時刻を持たせ、
戻って読んだ一覧に同じ組があるかで受理を決める。`MarginMemoControllerTest` と `NoteSessionCoordinatorTest` は、
保存の途中でノートや Vault を行き来する順序を作り、読めないときは「未保存」と言い切らずに未確定のまま持つことまで見る。

**例外が1つある。書き込みの途中で止める交錯は、実スレッドで作る。** 結晶の保存は SAF のブロッキングI/Oなので、
「書き込みに入った後・終わる前」にノートや Vault を切り替える順序は、単一スレッドのスケジューラでは作れない。
`CrystalSaveInterleavingTest` は画面側と I/O 側を別スレッドにし、偽の置き場を書き込みの中でラッチに待たせる。
**「起きないこと」を確かめる検査は時間で打ち切る。** 保存が終わる前に一覧を列挙しないことは、
300ミリ秒待っても列挙が始まらないことで見る。これを入れる前は、待たない実装を入れても、
スレッドの順番しだいで緑のまま通ることがあった。

**取り消しに協調する偽物では、届かない枝がある。** 並べ読みの右の本文は、普通に開くときと同じく SAF の同期の I/O で読む。
同期の I/O は取り消しでは止まらず、取り消した後に例外で終わると、その例外が取り消しより優先して一般の `catch` へ届く。
最初の検査は読み出しを `CompletableDeferred` で作っていた。これは取り消しに素直に従うので、後から失敗させても `catch` の枝へ一度も届かず、
閉じた面へ古い候補の失敗が書かれる不具合を緑のまま通した。いまは読み出しを `withContext(NonCancellable)` で包んだ偽物も使い、
余白へ戻る・選び直す・ノートを替える・Vault を替えるの4つの契機それぞれで、成功と失敗の後着を作る → `SideReadingControllerTest`・`NoteSessionCoordinatorTest`。
**取り消しの1行を消すと落ちることと、取り消しをすり抜ける経路を覆っていることは別である。**

### 観点C — 組み合わせ: 片方ずつのテストでは永久に空く面

機能ごとのテストは、それぞれの処理を単独で通す。だから**片方が走っている最中にもう片方を操作する経路が丸ごと空く。**

この型の最初の実例だった `SectionChatCombinationTest` は、部分要約から質問と回答を外したときに役目を終えて消えた。
要約と回答の2つの Job が互いを巻き添えにする欠陥を2件捕まえていた。

いまの代表は `CrystalSaveInterleavingTest` で、結晶の保存の書き込みの途中でノートや Vault を切り替える。
余白メモでは、保存と削除で1本の Job を共有すると「保存中に削除すると保存中のまま残る」「削除中に保存すると消えていないメモが画面から消える」の両方向で壊れたので、
`MarginMemoControllerTest` が両方向を見る。
**共存しうる2つの処理があるなら、両方向の掛け合わせを立てる。** これはこのリポジトリの型になっている → CLAUDE.md の影響面監査。

### 観点D — 見た目を数値へ引き出す

Composeの描画そのものはJVMで見られない。そこで判断の部分だけを純関数へ出して、値として固定する。

| テスト | 観点 |
|---|---|
| `ui/theme/AppColorContrastTest` | WCAG のコントラスト比を計算し、AA基準を割らないことを26件で固定 |
| `ui/theme/NoteFieldPaletteTest` | 分野の6色を値ではなく関係で固定。明度が揃い、色相が等間隔であること |
| `ui/BookletTurnGeometryTest`・`BookletPeelGeometryTest` | 紙の置き方と、折り目がどこを走るか。表と裏の面積が合うこと |
| `ui/ReadingProgressGeometryTest` | `block scrolled above the viewport counts from its top` |
| `ui/MarginSurfaceTest`・`ui/ReaderLayoutTest` | ✎ の動きと、折り目のある窓での並べ方。`本文領域の中の折り目で割れなければ、中央へ逃げず縦に積む` |
| `ui/ReunionLeadTest`・`ReadingTraceHeadlineTest` | 画面に出す文言の選び方 |

**見え方そのもの、重さ・速さ・気持ちよさは、ここでは見ないと明記してある。**
それは時間の中にしかないので実機検証のケース表が持つ → [bearing_channels](../dev/system/bearing_channels.md)。
「見ないもの」を書いておくのが、この層のテストの作法になっている。

### 観点E — 構造と文書: コードの正しさではなく、規約が守られていることを見る

ここが他所ではあまり見ない部分で、28ファイル・94件ある。発想は一貫している。

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
| `AppScaffoldContentSlotTest` | **外殻が本文をどの形でも同じ位置で組み立てる。** 崩れると Fold の開閉で書きかけの保存値が形ごとに別々に戻る |
| `ReadToEndJudgmentTest` | 「最後まで読んだ」を到達率の比較で直に書かせない。再会カードと前回の読書の跡が同じ判定を使う |
| `GradientHeaderPaddingTest` | **見出しを置く画面が、見出しの面を広げる幅と同じ定数から余白を取る。** 書き写した余白は片方だけ変わりうる |

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
| `PromptGenerationCoverageTest` | プロンプトを足したら「実生成で通す」か「未保証と明示する」かを必ず選ばせる。9本中6本が実生成、3本が未保証と宣言 |
| `InstrumentationTestShapeTest` | 実機テストが起動すらしない書き方、`runBlocking` の戻り値型を禁じる |
| `DeviceValidationDocsTest` | 実機検証手順が必須項目を持っている。簡易版が実在しないケースを指していない |
| `DeviceProbeResidueTest` | 実機検証の使い捨て一時テストが作業ツリーに残っていない |

`ReviewFindingsLedgerTest` のKDocに、なぜスクリプトではなくテストなのかが書いてある。

> スクリプトだと「走らせる」という手動契約が新しく生まれ、同じ罠を1段ずらすだけになる。
> テストなら `./gradlew testDebugUnitTest` と CI に自動で載る。

**35ファイルがソースや文書をファイルとして読んでいる。** `walkTopDown`・`readText` を使う。
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
`前回の保存に失敗した後、今の読書を背面へ回して読み直しても同じ前回を返す`
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

- **実際の描画・タップ・スクロールの手触り** → `androidTest` 17ファイル150件と、Codexの実機検証
- **端末AIの実生成** → `OnDeviceGenerationTest`。Nano非対応端末では `Assume` で skip。9本のプロンプトのうち6本
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

## 付録. 全148クラスの一覧

本文は観点ごとに代表を挙げている。ここは全クラスを1行ずつ引くための索引で、件数は `testDebugUnitTest` のレポートから採った。
説明は手で書いているので、件数より先に古くなりうる。行頭の `@Test` を数えると同じ1,740件になる。
行頭に限らず数えると4件多く出るのは、文字列リテラルの中に `@Test` を書くテストがあるため。

| テストファイル | ケース数 | 主な対象 |
|---|---:|---|
| `AiAvailabilityContractTest.kt` | 9 | AI可用性の契約。4状態の意味と、呼び出し側が取る行動の対応。状態確認の例外で走行状態を残さないこと、キャンセルを畳まないこと。**分野判定は走行状態を持たないので観測対象から外す** |
| `BookletControllerTest.kt` | 39 | 冊子の束（10枚・重複なし・0件・走査失敗）、扉の遅延読込（前後1ページ・二重読み防止・失敗の非再試行）、ページ位置の保持と引き直し、Vault世代のすれ違い、**編む束と引く束の寿命・切替・種の固定** |
| `BookletPagerAlignmentTest.kt` | 6 | ページャの引き直しで先頭へ付け替わること、束の世代が変わるまで付け替えないこと、束の切替で位置が戻ること |
| `BookletRestackTest.kt` | 10 | 積み直りの契機と持続、走行フラグが残らないこと、OS設定に従う経路の数 |
| `BookletWeaveTest.kt` | 13 | **編む束の中身**（AI推薦→未リンク→wikilink済みの順、重複は参照で畳む、種を外す、水増ししない）と、トグル3通りの型 |
| `BoundedNoteReadTest.kt` | 9 | 用途別の読込予算、上限到達の判定、多バイト文字の末尾切り |
| `CrystalControllerTest.kt` | 13 | **結晶のController**（条件がそろうと1回生成して保存し一覧へ足す・門番待ち・要約待ち・生成中の切替で書かず数えない・生成中のVault切替・失敗と空振りと不正も試行に数える・Readyでなければ生成もDLもしない・再会カードの照合を待つ・一覧が読めなければ未読込へ戻す） |
| `CrystalSaveInterleavingTest.kt` | 5 | **保存の書き込みの途中で止める**。実スレッドと偽の置き場で、書き込み中のノート切替・A→B・A→B→A・保存失敗・読み込み中の保存完了を交錯させ、一覧が実ファイルと一致すること |
| `CrystalStoreTest.kt` | 10 | 結晶のファイル形式（往復・checksum・未知の版）、読めない1件だけを飛ばすこと、作成時刻の名前と実際の名前、上限超過を書かないこと、端末内の控えの往復と壊れたときの空 |
| `DistillControllerTest.kt` | 45 | 蒸留フローの直列化、requestIdガード、保存後の状態遷移・復旧分岐、**元本文の書き出しがキャンセルでエラーにならず復旧レコードも消さないこと**、自由範囲の保存出力と重なり解消の両方向 |
| `DistillRecoveryStoreTest.kt` | 3 | 復旧レコードの書込・読出・破棄 |
| `DistillWriteRepositoryTest.kt` | 18 | 二重ハッシュ照合、原子確定、出力ハッシュ検証、中断・容量不足。**元のファイルの BOM を戻し、二重の BOM も含めてバイト単位で元の形を保つこと** |
| `FileSummaryCacheTest.kt` | 9 | 要約の保存（1件1ファイル・古い順の削除・大きさの上限・書きかけの片付け・置き場を作れないとき） |
| `InlineMarkdownTest.kt` | 16 | 強調、リンク、コード、打ち消し、誤検出防止、**描画範囲が共有トークナイザーの答えと一致すること**、エスケープ |
| `MarginMemoControllerTest.kt` | 33 | 余白メモの読み出し・追記・削除と書きかけ。保存結果の見せ分け、満杯で一覧へ足さないこと、**保存と削除が互いを取り消さないこと**、**送信の照合**（保存の結果と、戻って読んだ一覧の2つの道で受理を決め、確認できない間は元の送信を確かめる）、パスが分かる前の読み込みを待つこと、前回の読書を一覧と一緒に持つこと、長い見出しを保存できる長さへ切ってから渡すこと |
| `MarkdownParserTest.kt` | 38 | frontmatter、テーブル空セル、見出し、コード、CRLF、引用、リストのマーカー保持（区切り記号・先頭ゼロ・巨大桁）、段数の算出規則5つ、タブの4列展開、タスク混在、`blocksToMarkdown` の往復 |
| `NoteByteOrderMarkTest.kt` | 5 | **BOM で始まるファイルを読み込みの復号から通す。** 表示と節の一覧の最初の見出し、前付けとタグ、冊子の扉、要約の抜粋の見出し、再会カードの問い |
| `NoteDwellGateTest.kt` | 11 | **自動生成の門番**（3秒で開く・離れたら取り消す・冊子と背面で数え直す・開いた門を次のノートへ持ち越さない） |
| `NoteFieldControllerTest.kt` | 24 | **分野判定のController**（索引B命中でAIを呼ばない・失効時にヒントへ降格・有効な確定では索引へ書かないことを回数で見る・キャンセルで何も書かない・切替で索引に触らない・永続の上限） |
| `NoteRepositoryTest.kt` | 4 | Markdown判定、wikilink・タイトル正規化 |
| `NoteScanCacheTest.kt` | 3 | **走査キャッシュ**。Aの走査を止めたままBへ切り替えてもBのまま、A→B→Aでも最初の走査が上書きしない、TTL内は走査し直さない |
| `NoteSectionControllerTest.kt` | 6 | 表示用Markdown解析のMain外退避と、本文差し替え時の再解析 |
| `NoteSessionCoordinatorTest.kt` | 59 | Vault/ノート切替の一斉停止と一斉初期化、リセット登録漏れ検出（**ノート単位とVault単位の両方**）、旧結果の後着防止、Vault世代、冊子から始めた読込の取り消し、**分野の索引の復元と走査の配線**、**結晶が再会カードの照合を待つ順序**（保存待ち・生成中・保存済み・失敗・切替）、**余白メモの書きかけと送信の照合の配線**（ノートを行き来しても書き込み先ごと戻る・Vault を行き来しても古い送信が新しい書きかけを消さない・ノートを表示すると面を出さなくてもメモを読む・パス未確定のノート・戻って読めない間は未確定のまま持つ）、**部分要約の要求と本文の解析し直し**、**並べ読みの失効**（ノート切替・Vault 切替で本文を消す・取り消しに従わない読み出しの後着を書かない・右で眺めても今のノートと記録が変わらない・部分要約やメモの保存と両方向） |
| `NoteSnapshotTest.kt` | 11 | 上限付きバイト読込、UTF-8厳格判定、ハッシュ。**ノートの復号で先頭の BOM を1つだけ外すこと、書き戻しで戻すと元のバイト列になること** |
| `NoteUiStateStoreTest.kt` | 2 | 各Writerが担当スライスだけを更新すること、ノート読込開始の単一通知 |
| `ReadingTraceBackupControllerTest.kt` | 31 | 痕跡の書き出し・下見・適用・中止、停止待ち中の再入、Vault世代 |
| `ReadingTraceBackupJsonTest.kt` | 10 | 退避ファイルの形式（外から見える生JSON・読めなかった件の扱い） |
| `ReadingTraceBackupTextTest.kt` | 11 | 退避・下見・適用・中止の文言（適用だけ言い方を変える）。守っているものの名前を挙げ、廃止した欄を復元できると言わないこと |
| `ReadingTraceCleanupControllerTest.kt` | 26 | 孤児痕跡の洗い出しと削除、Vault世代照合、削除直前の再走査、削除の直列化と最新の一覧への反映 |
| `ReadingTraceControllerTest.kt` | 75 | 能動読書10秒閾値、最深到達点（可視割合込み）、追記上限、後続bind、二重flush、pause/resumeと訪問の差し替え、Vaultキーの持ち回り、**余白メモの読み出し・追記・削除と、書けなかったぶんの退避**、読み込みの確かさ（読めた・無いと確かめた・確かめられない）、**前回の読書の選び方**（今の読書の訪問を数えない・離れてすぐ戻っても数える・前回の保存失敗の後に背面化しても押し出さない・別のノートとVaultに混ざらない）、長い見出しの訪問を保存できること |
| `ReadingTraceJsonTest.kt` | 44 | JSON往復、checksum、UTF-8、必須項目・上限、要約キャッシュ整合、**v1→v6 の各版からの読み込み互換**。旧版の正規形をテスト側に写し取って固定し、**v7 で読み捨てる欄を版ごとの窓で照合すること**まで見る |
| `ReadingTraceKeyTest.kt` | 4 | 痕跡の索引のキー。フォルダ（結晶の `crystals/`）と64文字未満の名前を載せないこと |
| `ReadingTraceLimitsTest.kt` | 2 | 上限どうしの整合（全フィールドを上限まで詰めてもファイル読込上限に収まること） |
| `ReadingTraceMergeTest.kt` | 18 | 読み戻しの併合規則。端末に無いものを受け入れ、既存を黙って上書きしない。**メモは欄ごとに合流し、20件を超えるノートは無変更で保留する** |
| `ReadingTraceStoreTest.kt` | 34 | ハッシュキー、保存/読込、破損・パス不一致、フォルダ/書込失敗、Vaultキーの受け渡しと不一致時の拒否 |
| `ResumeImageMeasurementTest.kt` | 7 | **続きから読むで飛び越した画像の測定**（飛び越した画像だけを選ぶ・測り終えると後ろの進捗が残る・送っただけで100%にしない・測定待ちで画面が替わっても移った先が測り終える） |
| `ReunionCardControllerTest.kt` | 46 | 再会カードの照合と生成。途中までは前後の要約と続きから読むの送り先、読了は問いの選別と要約への退避、保存済みの種別を今回の分岐で使えるかの表、**後から届いた結果をいまのカードへ合流すること**、境目の印の除去、**同じ痕跡への訪問の保存だけを待つこと**、保存された印を出さずに欄を残すこと、メモがあるときだけ入口の行を出すこと |
| `ReunionPassageTest.kt` | 23 | **読み進めたところの算出と前後の切り出し**（節の範囲へ収める・同名の見出し・長い1ブロックを読んだ割合で割る・文の区切りへ手前寄せ・続きから読むの送り先）。記録した見出し名はメモと同じ照合で引く（上限で切った長い見出し・切り口の直前の空白・整える前の生の見出し名） |
| `SearchControllerTest.kt` | 15 | スコープ切替時の結果破棄・同一スコープ再選択の保持 |
| `SideReadingControllerTest.kt` | 10 | **並べ読みの読み込み**（選び直し・余白へ戻る・解析の途中で戻るの後着を書かない、取り消しに従わない読み出しが成功でも失敗でも終わったときに閉じた面と新しい候補を覆わない、右だけの失敗と開き直し、取り消しを失敗として書かない） |
| `SectionChatControllerTest.kt` | 18 | 部分要約の状態遷移・再試行・破棄。モデルの未取得では開始を求めずDLも始めないこと、端末AIが使えないだけなら派生状態を Working にも Error にもしないこと。**3節分の保持**（別の節を頼むと前の生成を取り消す・遅れて届いた結果は番号が一致する要約にだけ書く・準備待ちの説明は頼み直すと確かめ直す・中止） |
| `SummaryControllerTest.kt` | 7 | モデルDL待ちの要約がノート切替をすり抜けないこと、DL進捗の照合 |
| `SummaryGenerationObservationTest.kt` | 4 | 保存済みの当たりと、同じ入力の再生成を生成の呼び出し回数で区別できること（再起動の数え方を含む） |
| `VaultImageIndexStoreTest.kt` | 23 | 画像索引のTTL・再走査の歯止め・Vault世代 |
| `VaultPathTraversalTest.kt` | 19 | 相対パス付きBFS、除外フォルダ、循環、同名階層、非Markdown除外 |
| `ai/AiAvailabilityMappingTest.kt` | 10 | `FeatureStatus` と例外から `AiAvailability` への写像 |
| `ai/AiGenerationFailureTest.kt` | 3 | 回数制限で断られた失敗の判定（本物の `GenAiException` で組み立て、長期の利用枠の超過は含めない） |
| `ai/DistillPromptBuilderTest.kt` | 4 | 候補件数・文字予算内への収容、プロンプト出力契約 |
| `ai/PromptBudgetTest.kt` | 6 | **完成プロンプトの入力上限**（どの入力でも上限を超えない・意図する最大構成で切り詰めが起きない・前後の要約の上限・結晶の最大構成と古い候補から落とすこと・切り詰めたら印を残す） |
| `ai/PromptBuilderExcerptRegressionTest.kt` | 4 | 3プロンプト（要約・関連ノート・部分要約）の出力文字列の固定、抜粋時だけ注意書きが出ること |
| `ai/PromptIndentationTest.kt` | 3 | **複数行の値を埋めても字下げが漏れないこと**を全builderで固定 |
| `ai/ReunionPassagePromptTest.kt` | 3 | 前後の要約のプロンプト。**「止まった」と言わない**・境目の印を前と後のあいだに1つだけ置く |
| `architecture/AdrShapeTest.kt` | 7 | ADRの形（30行以内）と、`最終検証` が実在するコミットを指すこと |
| `architecture/AiAvailabilityUsageTest.kt` | 2 | `AiAvailability` の分岐が網羅されていることをソース走査で固定 |
| `architecture/AiClientDoubleTest.kt` | 1 | テスト用 `AiClient` が1本へ寄っていることをソース走査で固定 |
| `architecture/AppScaffoldContentSlotTest.kt` | 2 | 外殻が本文を**どの形でも同じ位置で**組み立てること。本文を呼ぶ場所は1か所で、レールとバーより先に組む。Fold の開閉で保存値が同じ場所へ戻る前提を構造で固定する |
| `architecture/BackupExclusionTest.kt` | 2 | **端末に残す置き場がバックアップ除外に載っていること**（prefs 名の定数を所有する型まで見て解き、除外XMLを解析して突き合わせる） |
| `architecture/BearingChannelTest.kt` | 8 | **面の形の役割**（2つの役割が別物であること・角の大小の順序・どの面がどちらを引くか・互いの役割を引かないこと・縁の色と呼び出し・縁の有無を種別で決めること） |
| `architecture/BookletRouteContractTest.kt` | 4 | 冊子ルートの契約をソース走査で固定（読書時間を止めて戻す・読込中の要求を取り消す・「これを読む」はタブ遷移ではなくルートを積む・先頭から開く） |
| `architecture/CrystalWiringOrderTest.kt` | 1 | 引いたノートでは再会カードの照合を要求してから結晶を試す呼び出し順 |
| `architecture/DesignDocStateNameTest.kt` | 3 | 状態・列挙の改名／削除が正本へ反映されていること |
| `architecture/DeviceProbeResidueTest.kt` | 1 | **使い捨ての一時テストが作業ツリーに残っていないこと** |
| `architecture/DeviceValidationDocsTest.kt` | 7 | 実機検証の入口と機能別ケースの形（正本リンク・前後処理・記録・ID重複）、ケース表が書く instrumentation の件数が実数と一致すること、**簡易版のスモークIDが実在すること** |
| `architecture/DistillCandidateUnitCopyTest.kt` | 1 | **蒸留の画面文言が候補の単位を「文」と決めつけないことをソース走査で固定**（候補には句・語句が混ざる） |
| `architecture/DistillProtectedScanTest.kt` | 2 | **保護範囲をカーソル越しにしか読まないことをソース走査で固定**（時間差が出ない二乗経路を形で縛る）。文書全体の保護範囲はモデルへ渡す1箇所でしか読まない |
| `architecture/DistillRangeRebuildCostTest.kt` | 1 | **段の導出を候補状態の作り直しから呼ばないことをソース走査で固定**（自由範囲のドラッグではフレームごとに走るため） |
| `architecture/GradientHeaderPaddingTest.kt` | 2 | **見出しを置く画面が `screenContentPadding()` で余白を取ること**と、見出しの面を広げる幅と左右の余白が同じ定数であることをソース走査で固定 |
| `architecture/InstrumentationTestShapeTest.kt` | 1 | **`@Test` の戻り値が `void` でなくなる書き方をソース走査で禁じる**（→ [解析書](01_source_code_analysis.md) §13.5） |
| `architecture/KotlinCommentScannerTest.kt` | 17 | コメントの字句解析（文字列リテラルやエスケープの中の記号をコメントと誤認しないこと） |
| `architecture/NoteExcerptThreadingTest.kt` | 1 | 抜粋生成が本番の6ファイル9箇所すべてで `Dispatchers.Default` 側にあること（呼び出し箇所の一覧ごとソース走査で固定） |
| `architecture/NoteSectionThreadingTest.kt` | 5 | 本文解析がMainのスコープから呼ばれていないことをソース走査で固定。ブロック解析を呼ぶ場所の一覧と、並べ読みの Controller が `parseDispatcher` の中でだけ解析すること |
| `architecture/PackageDependencyTest.kt` | 2 | パッケージ依存の向き（ルートパッケージ経由の抜け道を含む） |
| `architecture/PromptGenerationCoverageTest.kt` | 3 | 全プロンプトが実生成テストで覆われるか未保証として列挙されるか（12本中7本が実生成）、件数の固定 |
| `architecture/ReadToEndJudgmentTest.kt` | 2 | 「最後まで読んだ」を到達率の比較で直に書かせない。判定は `isReadToEnd` の1つで、再会カードと前回の読書の跡が共有する |
| `architecture/ReadingTraceBackupThreadingTest.kt` | 5 | 退避のJSON処理がMainの外にあることをソース走査で固定（最大8MB） |
| `architecture/ReviewFindingsLedgerTest.kt` | 7 | 最新レビューの指摘が受付簿へ全件載ること、未解決の処遇だけであること、**受付行の課題が実在すること**、ID重複の拒否 |
| `architecture/SchemaVersionDocsTest.kt` | 2 | **文書が名指しする現行スキーマ版がコードの定数と一致すること** |
| `architecture/SourceCommentShapeTest.kt` | 2 | **KDocが2つ続いて宙に浮いていないこと。本番コメントに日付・レビューの指摘番号が無いこと**（3ソースセット） |
| `architecture/SourceDocSyncTest.kt` | 3 | 状態型の欄が正本の一覧に載ること、KDocの相対リンクが実在すること（3ソースセット）、**文書からソースへのリンクが行番号を持たず名前で指すこと** |
| `architecture/WipIssueReferenceTest.kt` | 2 | `_wip/` とコードが実在しない課題IDを参照していないこと |
| `domain/AiStatusNoticesTest.kt` | 10 | AI状態の説明文と再試行導線の出し分け |
| `domain/BookletCoverLineTest.kt` | 36 | **冊子の扉の抽出規則**（frontmatter・見出し・フェンス内・表の区切り・罫線・リンクだけの行・表の見出し行・値が日付だけの行・🧭 のナビの行を落とす。説明の括弧が付いた日付・値の読めるラベル行・表の本文の行は残す、フェンスの開閉判定、最初の1文だけ、全角40字の上限と絵文字を割らない切り、選べなければタイトル） |
| `domain/BoundedInputStreamTest.kt` | 13 | **上限の境界（-1／ちょうど／+1）を単一read・配列read・`skip`・混在で固定**、`len == 0` の契約、`available()` の丸め、先読みが1回だけであること |
| `domain/ByteBudgetCacheTest.kt` | 10 | バイト予算つきLRU（超過時の追い出し順・単一エントリ超過） |
| `domain/CrystalCandidatesTest.kt` | 20 | **結晶の試す条件**（異なる3件で初回・同じ内容の再訪で0回・同じ1件は1件・候補の外の未提示材料では同じ6件を渡さない・予算で落とした後の集合で判定）、断片の切り方、候補の並び、絞り込み |
| `domain/CrystalResponseParserTest.kt` | 14 | 結晶の応答の検証。2行の採用、NONEの揺れ、今のノート抜け・提示外ID・件数の外れ、2文・疑問文・長さ、**選択欄が読むどの表記でも本文のIDは不正** |
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
| `domain/MarginMemoDraftRulesTest.kt` | 17 | **書きかけと送信の規則**（書き始めた節を書き込み先にして動かさない・送信は整えた保存値と時刻を持つ・1024バイトの切り詰め・受理／未受理／確認できないの照合・受理したら原文のままの入力だけを空にする・未確定の間の編集では新しく置かない） |
| `domain/MarginMemoSectionsTest.kt` | 15 | **メモと今の見出しの照合**（一意・候補が複数・見つからない・見出し無し、長い見出しの切り口、整えると同じになる見出し、生の見出し名）と、今の節とほかの節への振り分け、同名の候補の組と重ならない件数、印と飛ぶ先の一致 |
| `domain/NoteExcerptBuilderTest.kt` | 18 | 抜粋の予算不変条件（注意書き・ラベル込み）、境界、見出しの均等選抜、単一巨大ブロック（段落・コード・表・リスト）、frontmatter除去、リストの番号と段数がモデルへ届くこと、記法増加後も全予算で上限を超えないこと、中略なしの連続レイアウト |
| `domain/NoteFieldAnswerTest.kt` | 11 | 分野判定の応答を読む規則。行の全体がIDのときだけ受理し、「該当なし」と「読めなかった」を分ける |
| `domain/NoteFieldHintTest.kt` | 8 | パスから分野のヒントを作る辞書照合。当たらない3例が実際に区別されること |
| `domain/NoteFieldIndexTest.kt` | 9 | 索引Aへヒントを流し込む規則。再走査で確定をヒントへ戻さないこと |
| `domain/NoteFieldInputVersionTest.kt` | 5 | 入力指紋がAIへ渡したものすべてを含むこと。落とした項目はそのまま「変えても再判定されない」バグになる |
| `domain/NotePaperAgeTest.kt` | 15 | 相対四分位による紙の地色の段階決定 |
| `domain/PreviousReadingTest.kt` | 11 | **前回の読書の跡**（今の読書より前の最新1件・今の読書の訪問を数えない・最後まで読んだ回なら遡らない・一意に一致した節だけ・同名と見つからないと見出し無しでは出さない・長い見出しと生の見出し名） |
| `domain/ReadingTraceOrphansTest.kt` | 27 | 孤児判定の遮断器（フォルダ単位・読取失敗の伝播・**ルートと別サブツリーの混在**）、削除直前の三値再走査 |
| `domain/RelatedCandidateContextTest.kt` | 11 | 候補の本文肉付け・入力予算内への整形 |
| `domain/RelatedCandidateIdTest.kt` | 11 | 一時ID採番と応答からのID抽出 |
| `domain/RelatedCandidateOrderingTest.kt` | 3 | 採番プレフィックス抽出 |
| `domain/RelatedCandidateRankingTest.kt` | 5 | 採点戦略注入の汎用ランキング |
| `domain/RelatedCandidateScoringTest.kt` | 10 | タイトル話題スコア（bigram Dice＋採番近接） |
| `domain/RelatedContextScoringTest.kt` | 6 | 本文シグナル再ランク（tags/snippet/title） |
| `domain/RelatedNotesDwellTest.kt` | 1 | 関連ノートのAI推薦が、門番が開くまで生成を呼ばないこと |
| `domain/ReunionCandidateScannerTest.kt` | 19 | **再会候補の列挙規則**（終助詞「か」の問い・記録と古い前提の区別・版番号と計測値の区別・括弧内で切らない） |
| `domain/ReunionPassageOutputTest.kt` | 7 | **前後の要約から境目の印を落とす**（括弧・大小文字の揺れ・和文と英単語の詰め方・本文が同じ語を含むときは消さない） |
| `domain/ReunionSlotTest.kt` | 10 | **再会カードの枠に何を出すかの純関数**（途中と読了の全行・待ち表示・要約の先頭1文） |
| `domain/SearchKeywordMatchingTest.kt` | 10 | bigram採点、1文字クエリの部分一致、フォールバックの並び順と一致0件除外、再現率カットの0件保持 |
| `domain/SearchPickerBudgetTest.kt` | 2 | ピッカーの**提示集合＝許可集合**（予算で落ちた候補を応答で受理しない） |
| `domain/SearchPickerIdContractTest.kt` | 7 | ピッカーのID契約（IDの直後に文字や数字が続くものをIDと読まない） |
| `domain/SectionSummariesTest.kt` | 5 | **部分要約を3節分持つ規則**（同じ節は置き換え・生成中の別の節は外す・4節目で最も古いものを落とす・番号の一致した要約だけを書き換える・頼み直しで見せたままにするか） |
| `domain/SectionSummaryStatusTest.kt` | 10 | 部分要約の4状態の導出（エラー優先・生成中・要約済み・端末AIが使えないだけならエラーにしない）と、要約ボタンの記号と読み上げ名 |
| `domain/SummarizeUseCaseTest.kt` | 12 | 要約の保存の鍵＝AIへ渡したプロンプト、保存してよい結果、引いてよい状態、混雑時の文言 |
| `domain/markdown/InlineSyntaxTest.kt` | 9 | **インライン記法の唯一の解釈器**（種別・エスケープ・バッククォートrun・リンク消費・対の探索・空白規則・入れ子） |
| `domain/markdown/SectionRefTest.kt` | 9 | 節を見出し名と同名の中での順番で指すこと。見出しより前・見出しの無いノート、名前と順番での引き直し。部分要約へ渡す本文の範囲（見出しより前だけ・全体・引いた節・今の本文に無い節は渡さない） |
| `testing/SummaryBaselinePlanTest.kt` | 11 | 実機で何を生成し何を使い回すかの計画。同じプロンプトを2度生成しない・失敗した組を含めて続きから再開できること |
| `testing/SummaryCoverageCalibrationTest.kt` | 5 | 採点器の閾値を固定コーパスで決める。人の参照では分離できること・**実機の出力では分離できないこと**の両方を固定 |
| `testing/SummaryCoverageTest.kt` | 18 | 採点器が「落としたこと」を検出できること。裏付けの弱い文と触れなかった節。誤判定3件の回帰 |
| `testing/SummaryExcerptVariantsTest.kt` | 5 | 抜粋の変種の形。予算超過・切り詰め・変種が実は同じ、を机上で落とす |
| `ui/BookletPeelGeometryTest.kt` | 13 | **めくりの幾何**（折り目が右下から左上へ走る・表と裏の面積が合う・裏が枠から出ない・静止時に紙が欠けない） |
| `ui/BookletTurnGeometryTest.kt` | 19 | 紙の**置き方**（積み直りの傾き0〜22度・定位置への付け替え・遠い紙を引き寄せない・束の縁の在不在・影・カメラ距離・縮小率） |
| `ui/CrystalTextTest.kt` | 3 | 結晶の根拠の行・一覧への入口・日付の文言 |
| `ui/DistillRangeHandleTest.kt` | 6 | ドラッグで掴む端を**押下の1回で決める**（近いほうを採る・行が違えば横位置が近くても掴まない） |
| `ui/DistillRangeHighlightTest.kt` | 3 | **確定範囲の強調を値として観測する**（範囲の内側だけに太字と下線・親文の外へ出る指定は内側へ丸める） |
| `ui/DistillRangeNoticeTest.kt` | 5 | 重なり解消の告知の**主語**（解消を起こした側と外された側で言い分ける・件数の単位は「箇所」） |
| `ui/MarginMemoCaptionTest.kt` | 3 | メモの添え書き。節ごとに並べたときは節の名前を繰り返さず、同名に共通ならそう添え、見つからないメモには控えた見出し名を添える |
| `ui/MarginSurfaceTest.kt` | 28 | **余白の面の判定**（✎ の動き・窓の切り替わりで面を移す規則・節の呼び名・書き込み先の知らせ・印の行き先・書いている間に畳むか・上の操作を隠すか・シートが覆う高さ・前回の跡の行の有無と文言・要約の入口とこのノートの間だけのペイン）と**並べ読みの判定**（関連の候補を後着でも入れ替えず下へ足す・持ち主の違う保存値を使わない・保存値の往復・余白ペインでなくなったら終える） |
| `ui/NoteImageMeasurementsTest.kt` | 7 | 画像の表示寸法算出（原寸・上限・アスペクト保持） |
| `ui/NoteImageTextTest.kt` | 19 | 画像の失敗理由ごとの文言と代替テキスト |
| `ui/ReaderLayoutTest.kt` | 15 | **ノート画面の並べ方**（低い横長の画面だけ左右2列・480dpの境界・左列の幅、縦の折り目で割る・折り目を本文領域の座標へ直す・本文領域の中で割れなければ縦積み・レールの中の折り目は中央で割る・幅を持つ折り目・折り目の無い広い窓・卓上の形・ペインの設定が閉じているとき・下限の幅の前後） |
| `ui/ReadingProgressGeometryTest.kt` | 13 | 最終可視ブロックの可視割合（完全/一部/画面外/高さ未確定）、5%刻みの量子化 |
| `ui/ReadingTraceCleanupTextTest.kt` | 6 | 孤児整理画面の文言（件数・削除の取り返しのつかなさ） |
| `ui/ReadingTraceHeadlineTest.kt` | 9 | 経過日・セクション・到達率・訪問回数のカード文面。**「止まった」と言わないこと** |
| `ui/ReunionLeadTest.kt` | 2 | **種別ごとの前置き**（問い・古い前提。前後の要約とノートの要約には付けない） |
| `ui/VigilithOpeningMotionTest.kt` | 8 | ハロー・全身・名称の登場順、保持区間、退場、終端・範囲外入力 |
| `ui/theme/AppColorContrastTest.kt` | 26 | 明暗の役割トークンのコントラスト比。文字は4.5:1・塗りと記号は3:1を**強制**する |
| `ui/theme/NoteFieldPaletteTest.kt` | 5 | 分野の6色を値ではなく規則で固定（明度が揃い色相が等間隔）と、面としての合否1件 |
| `ui/theme/VibrantTextUsageTest.kt` | 2 | 画面からの `onVibrant` 直接使用と、文字色への任意の `copy(alpha)` をソース走査で禁じる |
| **合計。148クラス** | **1,740** | |
