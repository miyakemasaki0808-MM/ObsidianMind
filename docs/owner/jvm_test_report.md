# JVMテスト俯瞰 — 何を、どういう観点で確かめているか

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-07 / ブランチ `feature/New_Function_No.Y6` 時点で計測
**位置づけ:** **テストスイートを読むための地図**。`owner/` の他文書と同じく、
指示があったときに通しで見直す台帳ではない読み物である（→ [README](README.md)）。
**正本ではない** — 個々の判断理由は各テストのKDocと `dev/` が持つ。ここはその入口。

---

## 0. 3行でいうと

- JVMテストは **109クラス・1,340件**あり、**2秒**で終わる。端末もエミュレータも要らない
- 中身は「機能が正しいか」だけではない。**半分近くが「構造・規約・文書が壊れていないか」を見ている**
- 見ている観点は5種類に分けられる。**A 純関数 / B 非同期と状態 / C 組み合わせ / D 見た目の数値化 / E 構造と文書**

---

## 1. 数字で見る全体像

| 置き場 | ファイル | テスト件数 | 何を見ているか |
|---|---:|---:|---|
| `test/.../`（直下） | 40 | 690 | Controller・保存/読込・パーサ。**アプリの動きの本体** |
| `test/.../domain/` | 26 | 372 | 純関数（採点・抜粋・スコアリング・解析） |
| `test/.../ui/` | 19 | 181 | 表示ロジックを**数値として**固定（色・幾何・派生状態） |
| `test/.../architecture/` | 20 | 68 | **コードではなく規約と文書**を守る検査 |
| `test/.../ai/` | 6 | 33 | プロンプト組み立て（端末AIは呼ばない） |
| `test/.../fakes/`, `FakeVault.kt` | 3 | — | テスト用の差し替え（本体ではなく道具） |
| **合計** | **112** | **1,340** | 実行 **約2秒**（クラス実行時間の合算） |

> 参考: 実機で走らせる instrumentation テストは別に **15ファイル・96件**ある。
> JVMは「端末に触らずに分かること」だけを引き受け、**残りを実機へ渡す**という分担になっている。

**依存ライブラリは3つだけ。**

```
junit:junit:4.13.2
org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0
org.json:json:20240303          ← Android の org.json をJVM側で代替
```

**Robolectric も mockk も使っていない。** 代わりに `fakes/FakeAiClient.kt` と `FakeVault.kt` を手で書いている。
これは横着ではなく設計と対になっている — `model` / `domain` / `controller` の3層は
**Android非依存であることをCIで固定してある**ので（→ [architecture](../dev/system/architecture.md) 判断5）、
そもそもAndroidを模す仕掛けが要らない。**「テストが素直に書けること」を層の設計で買っている。**

---

## 2. そもそもJVMテストとは（instrumentation との境界）

| | JVMテスト（本書の対象） | instrumentation テスト |
|---|---|---|
| 走る場所 | PCのJVM上（`app/src/test/`） | 実機・エミュレータ（`app/src/androidTest/`） |
| 起動時間 | 秒 | 分（＋端末の準備） |
| 見られるもの | 値・状態遷移・文字列・構造 | 実際の描画・タップ・SAF・端末AI |
| コマンド | `./gradlew testDebugUnitTest` | `./gradlew connectedDebugAndroidTest` |

**この境界は「できる／できない」ではなく「効いていると言えるか」で引いている。**
このリポジトリでは、テストが効いていることの根拠を**変異させて落ちるか**に置いている
（→ [L11](../dev/lessons.md)）。実機テストは手元で変異を回せないので、
**同じ主張を守るならJVM側へ観測点を引き出す**という判断を何度かしている（→ [L53](../dev/lessons/L53.md)）。

例: 蒸留の「確定範囲は太字＋下線で示す（色だけの手がかりにしない）」は、
もともと `androidTest` が親文の表示だけを見ていて、**強調を丸ごと外しても緑のまま通った。**
いまは `DistillRangeHighlightTest` / `DistillRangeNoticeTest` がJVM側で持っている。

---

## 3. 5つの観点

### 観点A — 純関数: 入力を入れて出力を見る

いちばん教科書的な形。**壊れやすい文字列処理・パース・採点をAndroidから切り離して置いてある**
のは、そのまま素のJVMでテストするためである（→ CLAUDE.md 必須原則）。

代表:

| テスト | 守っている性質 |
|---|---|
| `QuizResponseParserTest` | AIの揺れた応答を救済する（`多択の回答は末尾記号や括弧や余分な語があっても救済する`） |
| `MarkdownParserTest` / `InlineMarkdownTest` | 見出し・引用・リンクの解釈（`配列表記の角括弧はリンクにならない`） |
| `domain/DistillCandidateScoringTest` | 候補の選び方（`unique final conclusion survives first stage`） |
| `domain/NoteExcerptBuilderTest` | AIへ渡す抜粋の作り方（`目標長が大きければノート全体まで広がる`） |
| `domain/BoundedInputStreamTest` | 上限を超える入力で落ちないこと |

**観点は「正常系が通ること」ではなく、ほぼ全部が異常系と境界である。**
`本文が空でも落ちない` `異常な寸法でも倍率の上限で止まる` `プラス記号は空白へ変換しない` のように、
**"起きたら困ること"を名前にして並べてある**。

### 観点B — 非同期と状態: 「遅れて届いた結果」を作りにいく

このアプリの事故はほぼここで起きる。AI生成もSAF読み書きも非同期で、
**その最中にユーザーはノートを切り替えたりVaultを選び直したりする。**

守るのは3つ。

1. **切替時に走行中のジョブが止まる**（`cancelNoteScopedJobs()`）
2. **止まりきらずに戻ってきた結果を、状態へ書かない**（世代ID＋`isCurrent()`）
3. **どの経路を通っても「走っていないのに走行中フラグが残る」状態にならない**

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

`cancel()` では止められない経路（**読み出しはもう戻ってきている**）を再現するための穴で、
ここがなければ世代照合が効いているかを確かめられない。**「テストを書けるようにするための設計」の実例。**

時間は `StandardTestDispatcher` + `advanceUntilIdle()` で手で進める（実時間を待たない）。
**22ファイルが `runTest` を使っている。**

### 観点C — 組み合わせ: 片方ずつのテストでは永久に空く面

`SectionChatCombinationTest` が専用クラスとして独立している理由がそのまま観点になっている。

> `SectionChatController` は独立した2つのJob（要約・回答）を持ち、それぞれに
> 「実行中／失敗／端末AIが使えない」の3状態がある。機能ごとのテストは各Jobを単独で通すので、
> **片方が走っている最中にもう片方を操作する経路が丸ごと空く。**

実際にそこで2件の欠陥が出た（要約の再試行が走行中の回答を巻き添えにして「生成中…」が永久に残る、など）。
**共存しうる2つの処理があるなら、掛け合わせを1クラスとして立てる** — これはこのリポジトリの型になっている。

### 観点D — 見た目を数値へ引き出す

Composeの描画そのものはJVMで見られない。そこで**判断の部分だけを純関数へ出して、値として固定する。**

| テスト | 観点 |
|---|---|
| `ui/theme/AppColorContrastTest` | WCAG のコントラスト比を計算し、**AA基準を割らない**ことを28件で固定 |
| `ui/BookletTurnGeometryTest` / `BookletPeelGeometryTest` | 紙の傾き・遠近・影の**符号と範囲** |
| `ui/ReadingProgressGeometryTest` | `block scrolled above the viewport counts from its top` |
| `ui/VigilithStatusDerivationTest` | 派生状態（`エラーやダウンロード待ちは動作中として演じない`） |
| `ui/ReunionLeadTest` / `ReadingTraceHeadlineTest` | 画面に出す文言の選び方 |

**見え方そのもの（重さ・速さ・気持ちよさ）は、ここでは見ないと明記してある。**
それは時間の中にしかないので実機検証のケース表が持つ（→ [bearing_channels](../dev/system/bearing_channels.md)）。
**「見ないもの」を書いておくのが、この層のテストの作法になっている。**

### 観点E — 構造と文書: コードの正しさではなく、規約が守られていることを見る

**ここが他所ではあまり見ない部分で、20ファイル・68件ある。** 発想は一貫している。

> **このリポジトリで規則が守られたのは検査に載せたときだけ、というのが実績である。**
> 文書に書くだけの規則は増やさない。 — `AdrShapeTest` のKDocより

見ている対象は3つに分かれる。

**(1) コードの構造**

| テスト | 守るもの |
|---|---|
| `PackageDependencyTest` | 層の依存が許可した向きだけを向く（`model` は葉） |
| `NoteExcerptThreadingTest` / `NoteSectionThreadingTest` / `ReadingTraceBackupThreadingTest` | 入力サイズに比例する処理をMainスレッドから呼ばない |
| `AiAvailabilityUsageTest` | `AiAvailability` を等値比較しない（**網羅 `when` を強制** → 状態が増えたらコンパイルエラーで気づける） |
| `AiClientDoubleTest` | テストダブルを `fakes/` の外に作らせない |
| `DistillProtectedScanTest` | 入力サイズの二乗になる書き方を新しく足させない |

**(2) コードと文書の同期**

| テスト | 守るもの |
|---|---|
| `SourceDocSyncTest` | 状態型に足した欄が、設計書の一覧にも載っている／KDocの相対リンクが実在する |
| `DesignDocStateNameTest` | **消した型・値の名前を、文書が現役のように書いていない** |
| `SchemaVersionDocsTest` | 文書が名指しする「現行スキーマ版」がコードの定数と一致する |
| `WipIssueReferenceTest` | 課題台帳から消したIDを、他の文書が参照し続けていない |

**(3) 開発プロセスそのもの**

| テスト | 守るもの |
|---|---|
| `ReviewFindingsLedgerTest` | 外部レビューの指摘が1件も取りこぼされずに受付簿へ載っている |
| `AdrShapeTest` | ADRが30行以内（＝ADRに設計の写しを置かせない） |
| `PromptGenerationCoverageTest` | プロンプトを足したら「実生成で通す」か「未保証と明示する」かを必ず選ばせる |
| `InstrumentationTestShapeTest` | 実機テストが**起動すらしない**書き方（`runBlocking` の戻り値型）を禁じる |
| `DeviceValidationDocsTest` | 実機検証手順が必須項目を持っている／**簡易版が実在しないケースを指していない** |
| `DeviceProbeResidueTest` | **実機検証の使い捨て一時テストが作業ツリーに残っていない** |

`ReviewFindingsLedgerTest` のKDocに、なぜスクリプトではなくテストなのかが書いてある。

> スクリプトだと「走らせる」という手動契約が新しく生まれ、**同じ罠を1段ずらすだけになる。**
> テストなら `./gradlew testDebugUnitTest` と CI に自動で載る。

**25ファイルがソースや文書をファイルとして読んでいる**（`walkTopDown` / `readText`）。
テストが**リポジトリ自身を入力にしている**わけで、これがこのスイートのいちばんの特色である。

---

## 4. 読むときに効く作法（学習の要点）

**1. テスト名が仕様書になっている。** 日本語の平叙文で不変条件を書く。

```
`ノート切替後に旧ノートの再会カードが後着しない`
`要約が成功した後にクイズだけ一時的に使えないなら理由と再試行が出る`
`痕跡が1件も無いなら書き出さない`
```

`shouldReturnTrue` 系の名前は1つも無い。**名前を読めば、何が壊れたのかがそのまま分かる。**

**2. KDocに「なぜ要るか / 見ているもの / **見ていないもの** 」を書く。**
特に3つ目が重要で、テストの主張範囲を自分から狭めている。
主張だけが検査の顔をしている状態（実際に起きた）を防ぐため。

**3. 効いていることは、変異させて確かめる。**
ガードを外してテストが落ちなければ、そのガードかそのテストのどちらかが要らない。
実際に3回この判定をして3回とも**ガードを削除**している（→ [L11](../dev/lessons.md)）。

**4. 走査テストは呼び出しの存在しか見ない。**
`navigate("note")` と書いてあることと、戻ったとき同じ状態が復元されることは別。
**変異させた場所が「呼び出しの有無」だけなら、確かめたのは呼び出しの有無でしかない**（→ [L55](../dev/lessons/L55.md)）。
だから `BookletRouteContractTest` は、振る舞いを見ている別テストを表にして自分のKDocへ書いてある。

---

## 5. このスイートが見ていないもの（正直に）

- **実際の描画・タップ・スクロールの手触り** → `androidTest` 15ファイル96件と、Codexの実機検証
- **端末AI（Gemini Nano）の実生成** → `OnDeviceGenerationTest`（Nano非対応端末では `Assume` で skip）
- **SAFの実挙動**（権限・Uri・実ファイル） → 実機検証
- **文言の自然さ・配色の好み** → レビューとオーナー判断。テストは「読めるか」まで
- **意味の正しさ**。`SourceDocSyncTest` は欄が一覧に載っているかは見るが、説明が正しいかは見ない

---

## 6. 動かし方

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest lintDebug --offline
```

- テスト実行そのものは2秒程度。ビルドから通しても17秒（`--rerun-tasks` で全24タスクを再実行した実測）なので、
  **コード変更のたびに通す**のが前提の速さになっている
- 失敗したテストの詳細は `app/build/reports/tests/testDebugUnitTest/index.html`
- `androidTest` を触ったときは `assembleDebugAndroidTest` も通す（上のコマンドはコンパイルしない）
