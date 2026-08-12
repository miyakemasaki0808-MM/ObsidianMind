# 実装計画 — AI状態UXの統一

**プロジェクト:** Vigilith AI
**作成:** 2026-08-10（同日改訂 — 設計レビューを受けて段階1の判断を2つ変更）
**対象:** [current_issues.md](current_issues.md) AI-2
**状態:** **実装済み・実機確認待ち（2026-08-12）。** 残っているのは実機確認だけ。

> **⚠ この本文はもう正本ではない。** 実装は2回の独立レビューで差し戻され、
> **下記の設計判断は覆っている。** 現在の設計は
> [system/background_ai_ux.md](../dev/system/background_ai_ux.md) §6 を読むこと。
>
> | 本文の記述 | 実際にどうなったか |
> |---|---|
> | `Downloading` は走行中のDLへ合流する | **合流しない。** `downloadModel()` を呼んでよいのは `DOWNLOADABLE` のときだけ（AARに状態の門番が無い） |
> | `CheckFailed(cause)` | `TemporarilyUnavailable(cause)` へ改名。`UNAVAILABLE` は恒久と限らないため、`GenAiUtils.isAiCoreCompatible` で恒久判定する |
> | さがすは `AiStatusNotice` を通す | 通さない。`isAiAssisted` の Boolean 1本 |
> | 関連ノートは状態を持って注記する | 状態を持たない（自動起動なので黙る） |
> | `AiStatusNotice(message, action)` | `canTryAgainLater` を加えた3項。CTAの有無と入口を閉じてよいかは別 |
>
> **この文書は使い捨て。** 実機確認が終わったら削除する
> （設計判断は上記 §6、記録は [change_history.md](../dev/change_history.md) が持つ）。
> **`_wip/` の3本（課題・順序・アイデア）とは役割が違うので混ぜない** — これは特定項目の実装計画。

---

## 1. 何が問題か

`AICoreClient.checkAvailability()`（[AICoreClient.kt:75-86](../../app/src/main/java/com/example/newproject/ai/AICoreClient.kt#L75)）が
**4つの別々のこと**を `AiAvailability.Unavailable` へ畳んでいる。

| 実際に起きたこと | 再試行の意味 | 現在の戻り値 |
|---|---|---|
| `FeatureStatus.UNAVAILABLE` — この端末でNanoが動かない | **無い**（永久） | `Unavailable` |
| 未知の `FeatureStatus`（SDK更新で増えた値） | 不明 | `Unavailable` |
| `checkStatus()` が例外を投げた（AICore未バインド等） | **ある**（一時的） | `Unavailable` |
| **ノート切替でキャンセルされた** | 失敗ですらない | `Unavailable` |

**4行目は当初この計画に無かった。** `checkStatus()` は suspend 関数なので
（`checkStatus(Continuation<? super Integer>)` — AARの逆アセンブルで確認）**キャンセルが届く**。
`CancellationException` は `Exception` の子なので、`catch (e: Exception)` がそれを飲んで
`Unavailable` を返す。**症状は「ノートを切り替えた瞬間に『この端末では利用できません』が一瞬出る」。**
CLAUDE.md 並行処理§2（`CancellationException` は握りつぶさず再throw）に真正面から反しており、
**同じ12行の中にある独立した2件目の欠陥**なので同時に直す。

**下流の機能はどう頑張っても区別できない。** そのうえで各機能が独自に見せ方を作った結果、
同じ原因に対して**5通りの状態モデル**（蒸留＝専用状態／要約＝一部専用／さがす・関連＝enum側チャネル／
クイズ・ひとこと＝`Error(String)` へ畳む／セクションチャット＝nullable `String`）と、
互いに矛盾する文言が並んでいる。実害が3つ出ている。

1. **要約は非対応端末でパネルごと黙って消える。**
   [AiTab.kt:458-459](../../app/src/main/java/com/example/newproject/ui/screen/AiTab.kt#L458) の早期 `return` により、
   同ファイル `:519-521` の `AiUnavailable` 分岐（「この端末はGemini Nanoに対応していません。」）は**到達しない死コード**。
2. **クイズ・ひとことは「非対応」を `Error(String)` へ畳む**ので、画面には
   `エラー: Q&Aはこの端末では利用できません。` と出て、**再試行しても直らないものに再試行導線が付く**。
3. **セクションチャットの文言が古い** —
   [SectionChatController.kt:59](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L59) は
   「先にAI要約や**補記メモ**を実行して」と言うが、補記メモは2026-08-09に「ノートへのひとこと」へ置き換わって存在しない。
   さらに質問経路（`:106`）は `!= Available` で **NeedsDownload を Unavailable と同じ文言へ畳んでいる**。

---

## 2. 決めたこと

### 方針1: 説明はユーザーの操作に従う（オーナー判断・2026-08-10）

非対応端末で全機能が「使えません」と言い続けるのは騒がしく、全部黙ると押した機能が無反応になる。
**ユーザーが押した機能はその場で理由を説明し、自動起動の機能は黙って劣化する。**

これは ReadingTrace の [L4](../dev/lessons.md)「意識させない機能は作法が違う」を**特例から一般則へ昇格**させる形になる
（「意識させない」の判定基準が、機能名ではなく**起動契機**で引けるようになる）。

| 機能 | 起動契機 | 非対応時 |
|---|---|---|
| 蒸留・クイズ・ひとこと・セクションチャット・さがす | **ユーザーのタップ** | その場でインライン説明 |
| 要約・関連ノート・読書痕跡 | 自動 | **黙る／劣化のまま** |

### 方針2: 対象は「名前の一覧」ではなく `checkAvailability()` を呼ぶ全箇所で数える

roadmap X-6 の列挙（要約・検索・クイズ・補記）は**ひとこと以前に書かれていて、
セクションチャットと関連ノートが漏れている**。名前で引くと L14（横展開は最後の1本を取り残す）を再演するので、
**性質（`checkAvailability()` を呼ぶ）で grep して10箇所**を対象にする。読書痕跡だけ L4 により意図的に除外。

### 方針3: Controllerは共通化しない。共通化するのは「見せ方」だけ

[architecture.md](../dev/system/architecture.md) が4度（07-24 / 07-25 / 07-26 / 08-09）決着させたとおり、
Controller基底クラスは作らない。一方で同文書が確立した判定軸は
「**共通性は生成処理ではなくユーザーへの見せ方に宿る**」であり、
本件はまさに見せ方の統一なので、**純関数1本の共通化は同じ判断に沿う**（対立しない）。

---

## 3. 実装

### 段階1 — `AiAvailability` を5値にし、**両側**を改名する

`app/src/main/java/com/example/newproject/ai/AICoreClient.kt`

```kotlin
sealed class AiAvailability {
    /** 生成できる。 */
    object Ready : AiAvailability()
    /** モデル未取得。DLを提案する（自動DL方式なら黙って開始する）。 */
    object NeedsDownload : AiAvailability()
    /** DL実行中。走行中のDLへ合流して待つ。**新しいCTAは出さない。** */
    object Downloading : AiAvailability()
    /** この端末では動かない。**再試行を出さない**（何度押しても同じ答えが返る）。 */
    object Unsupported : AiAvailability()
    /** 状態を取得できなかった。**再試行を出す**（次は取れるかもしれない）。 */
    data class CheckFailed(val cause: Throwable) : AiAvailability()
}
```

各値の説明が「呼び出し側が次に何をするか」で書けている点が L28 の要件で、
これが変種を割る基準そのものになっている。`cause` は**画面へ出さない** — SDKの `message` は
英語か null でユーザーの次の行動を1文字も助けないので、診断のためだけに運ぶ。

#### 改訂1: `Available` → `Ready` も改名する（当初は `Unavailable` だけの予定だった）

**片側だけの改名では、コンパイラ駆動が7割で止まる。** 呼び出し10箇所のうち3箇所は
`!= AiAvailability.Available` という比較で、**変種を足しても改名しても素通りする**。
`Available` も改名して初めて10箇所すべてが未解決参照になり、
「全呼び出し箇所を必ず読み直させる」という当初の狙いが実際に達成される。

L29「規則は検査へ変えるまで守られない」をコンパイラで実現する最も安い形であり、
**改名の対象を「畳んでいた側」で選んだのが誤りだった** — 選ぶ基準は
**「その識別子に触れているか」**であって、意味が変わったかどうかではない。

#### 改訂2: `FeatureStatus.DOWNLOADING` を分離する（当初は「やらない」と書いていた）

**撤回の理由は L28 そのもの。** 判断基準は「呼び出し側の次の行動が違うか」であって、
関心事が同じかどうかではなかった。**蒸留では実際に違う** — 未DLなら「確認してダウンロード」を出し、
DL中なら走行中の進捗へ合流する。畳んだままだと**DL実行中に「通信量を確認してから開始してください」と出る**。

コストの見積もりも誤っていた。自動DLの3機能（要約・クイズ・ひとこと）では
`NeedsDownload` と `Downloading` が同じ枝へ合流するので、
**実際に分岐が増えるのは蒸留1箇所だけ**で、「7機能ぶんの `when` を増やす」は成り立たない。

#### 分類ロジックは `AICoreClient` の外へ出す

**新規 `ai/AiAvailabilityMapping.kt`**

```kotlin
internal suspend fun readAvailability(readStatus: suspend () -> Int): AiAvailability =
    try {
        featureStatusToAvailability(readStatus())
    } catch (e: CancellationException) {
        throw e          // ノート切替のたびに偽の「非対応」を出さない
    } catch (e: Exception) {
        AiAvailability.CheckFailed(e)
    }

internal fun featureStatusToAvailability(status: Int): AiAvailability = when (status) {
    FeatureStatus.AVAILABLE    -> AiAvailability.Ready
    FeatureStatus.DOWNLOADABLE -> AiAvailability.NeedsDownload
    FeatureStatus.DOWNLOADING  -> AiAvailability.Downloading
    FeatureStatus.UNAVAILABLE  -> AiAvailability.Unsupported
    // 未知の値を Unsupported にしない。SDKが定数を増やしただけで全端末が「非対応」に化け、
    // しかも再試行が出ないので異常だと誰も気づけない。再試行可能側へ寄せる。
    else -> AiAvailability.CheckFailed(IllegalStateException("未知の FeatureStatus: $status"))
}
```

**`AICoreClient` の中に書いたままだとテストできない** — あのクラスは `Generation.getClient()` を
抱えていて素のJVMでは組み立てられないので、「非対応／一時失敗／未知の値」の割り振りを
**一度も検証できない**。それがこの誤りが長く残った理由でもある。ラムダで受ければ
投げる側も返す側もJVMテストから作れる（`FeatureStatus` は4つとも定数なので素のJVMで参照できる）。

### 段階2 — 見せ方の純関数を1本置く

`ui` は `ai` を import できない（`PackageDependencyTest` が固定）。したがって
**表示用の型は `model`、変換関数は `domain`** に置く。

**新規 `model/state/AiStatusNotice.kt`**

```kotlin
/** AI状態をユーザーへ説明する1件ぶんの内容。UIはこれだけを見て描く。 */
data class AiStatusNotice(val message: String, val action: AiNoticeAction)

/** 説明に添える導線。**Boolean 2つにしない** — 次の行動が3つあるので sealed にする（L28）。 */
sealed class AiNoticeAction {
    object None : AiNoticeAction()      // 閉じるだけ。再試行しても変わらない
    object Retry : AiNoticeAction()     // 再試行に意味がある
    object Download : AiNoticeAction()  // モデルDLを促す
}
```

**新規 `domain/AiStatusNotices.kt`** — `AiAvailability` ＋ 機能名 → `AiStatusNotice` の**全域関数**。

```kotlin
fun aiStatusNotice(availability: AiAvailability, featureLabel: String): AiStatusNotice?
```

- `Ready` → `null`
- `NeedsDownload` → 「${featureLabel}にはGemini Nanoのダウンロードが必要です。通信量を確認してから開始してください。」＋ `Download`
- `Downloading` → 「Gemini Nanoをダウンロード中です。完了すると${featureLabel}を使えます。」＋ **`None`**（走行中のDLへCTAを重ねない。押しても新しく始まるものが無い）
- `Unsupported` → 「この端末では${featureLabel}を利用できません。」＋ **`None`**
- `CheckFailed` → 「${featureLabel}をいま開始できませんでした。時間をおいて試してください。」＋ **`Retry`**

**`CheckFailed` の `cause` を文言へ混ぜない。** SDKの例外文言は英語か null で、
ユーザーの次の行動を助けない。原因は診断のために型が運ぶだけにする。

**「黙るかどうか」はこの関数が決めない。** 決めるのは呼び出し側（方針1の表）。
関数は「言うとしたら何を言うか」だけを答える全域関数に保ち、沈黙の判断を混ぜない。

> **設計レビューで出た `AiStatusTone`（Info/Caution/Error）と
> `offersRetry` / `offersDownload` の派生Booleanは採らない。** 前者は7機能×3トーンを
> 今決める根拠が無く先回りになる（色の出し分けは必要になってから足す）。
> 後者は `AiNoticeAction` を sealed にした意味を薄める — 4通りのうち
> 「両方true」が意味を持たない値になるので、まさに L28 が避けよと言っている形。

### 段階3 — 10箇所の呼び出し側を直す

**改訂1（`Available` も改名）により、10箇所すべてがコンパイルエラーになる。**
下表の「今の形」は、**`Unavailable` だけを改名していたら何が起きたか**の記録として残す —
`!=` の3箇所は素通りし、そのうち `SectionChatController:106` は**本来直すべき側**だった。

| 呼び出し側 | 今の形 | 対応 |
|---|---|---|
| [DistillController.kt:106](../../app/src/main/java/com/example/newproject/controller/DistillController.kt#L106) | `when` | `CheckFailed` → `Error(canRetry = true)`／**`Downloading` → 進捗表示へ直行**（改訂2の実効箇所）。**参照実装** |
| [QuizController.kt:60](../../app/src/main/java/com/example/newproject/controller/QuizController.kt#L60) | `when` | `Error(String)` への畳み込みをやめ、`QuizState` に非対応を表す値を足して**再試行導線を消す**。`NeedsDownload` と `Downloading` は同じDL枝へ合流 |
| [RemarkController.kt:106](../../app/src/main/java/com/example/newproject/controller/RemarkController.kt#L106) | `when` | 同上（`RemarkState`） |
| [SectionChatController.kt:55](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L55) | `when` | **古い「補記メモ」文言を差し替え**、`AiStatusNotice` 経由へ |
| [SummarizeUseCase.kt:25](../../app/src/main/java/com/example/newproject/domain/SummarizeUseCase.kt#L25) | `when` | `CheckFailed` も自動機能なので黙る側へ |
| [RelatedNotesUseCase.kt:67](../../app/src/main/java/com/example/newproject/domain/RelatedNotesUseCase.kt#L67) | `when` | 同上 |
| [SearchPickerUseCase.kt:40](../../app/src/main/java/com/example/newproject/domain/SearchPickerUseCase.kt#L40) | `when` | `CheckFailed` → 一時失敗の説明（**現在到達不能だった枝が初めて到達する**） |
| [SectionChatController.kt:106](../../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L106) | `!= Available` ⚠️ | `when` へ直す。NeedsDownload を Unsupported と同じ文言へ畳むのをやめる |
| [RemarkController.kt:257](../../app/src/main/java/com/example/newproject/controller/RemarkController.kt#L257) | `!= Available` ⚠️ | 映し返しは黙る仕様なので**振る舞いは現状維持**（分類だけ網羅 `when` へ）。意図であることをKDocに書く |
| [ReadingTraceController.kt:747](../../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L747) | `!= Available` ⚠️ | L4により**見せ方の統一は対象外**。網羅 `when` にはするが `Ready` 以外はすべて `null`（黙る）。既にKDocに理由がある |

**`==` / `!=` での比較を今後させない検査を置く。** `src/main` に
`== AiAvailability.` / `!= AiAvailability.` が現れたら落ちるソース走査テストを足す
（`PackageDependencyTest` と同じ「規則ではなく検査」の形）。**許容リストは空にできる** —
読書痕跡も網羅 `when` へ移すため、例外を持たない検査になる。

### 段階4 — UIを方針1に合わせる

| 画面 | 変更 |
|---|---|
| [AiTab.kt:519-521](../../app/src/main/java/com/example/newproject/ui/screen/AiTab.kt#L519) `SummaryPanel` | **死コードを削除する**（復活させない）。自動機能なので黙るのが方針1どおり。`:458` の早期returnがそのまま正しい |
| [AiTab.kt:189-426](../../app/src/main/java/com/example/newproject/ui/screen/AiTab.kt#L189) `DistillPanel` | 参照実装。`AiStatusNotice` を描く共通composableへ寄せる（見た目は変えない） |
| [RelatedTab.kt:194-211](../../app/src/main/java/com/example/newproject/ui/screen/RelatedTab.kt#L194) | 非対応時は **AI推薦セクションごと出さない**（`showAiSection` の条件を変える）。あわせて「AI推薦に必要なモデルを準備中です。」の嘘を直す（この機能自身はDLしない） |
| [SearchScreen.kt:283-288](../../app/src/main/java/com/example/newproject/ui/screen/SearchScreen.kt#L283) | 文言は現状維持で正しい。一時失敗の枝が初めて到達するようになる |
| `QuizScreen.kt` / `RemarkScreen.kt` / `SectionChatSheet.kt` | 非対応時に再試行ボタンを出さない。`AiNoticeAction` で分岐 |

**`AiRecommendationStatus`（[RelatedNote.kt](../../app/src/main/java/com/example/newproject/model/RelatedNote.kt)）は削除する。**
さがすと関連ノートだけが使っている4値enumで、`AiStatusNotice` に完全に包含される。
放置すると**同じことを表す型が2つ**になり、統一のために作った型が3通り目の方言になる。
削除すると、調査で見つかった**到達不能な variant が2件まとめて消える**
（`SearchPickerUseCase` は生成例外を `PickerResult.Error` へ回すので `Status.Error` に到達せず、
`RelatedNotesUseCase` は `Result.Error` を一度も構築しない）。
`RelatedNotesState.Success.aiErrorMessage` も一緒に落ちる。

**共通composable** `ui/component/AiStatusNoticeRow.kt`（新規）が `AiStatusNotice` を受けて
「メッセージ＋`action` に応じたボタン」を描く。蒸留の現在の見た目をそのまま持ち上げる。

> **`QuizState` / `RemarkState` に値を足すと `toEventKey()` も対象になる。**
> Snackbar の発火判定キーなので、足した値を落とすと**非対応端末で通知が出ないか、出っぱなしになる**。
> 新しい状態フィールドを足すわけではないので「契約2箇所」（`cancelNoteScopedJobs` / `withNoteScopedReset`）は
> 対象外だが、**`toEventKey()` は各 sealed class のローカル契約として同じ性質を持つ。**

### 段階5 — テスト

**AI-2が今まで捕まらなかった理由がテストの穴そのもの:**
`checkAvailability()` から**例外を投げるテストダブルが1つも無い**（9ファイルの private double を確認済み）。
さらに `downloadModel()` が本物のチャンネルを返すダブルも `SummaryControllerTest` の1つだけで、
**DL経路は1機能でしか動いていない。**

- **テストダブルを1本へ統合する** — `app/src/test/.../fakes/FakeAiClient.kt` を作り、
  9ファイルに散らばった10個の private double を置き換える。`availability` の差し替え・
  **`checkAvailability()` から投げる口**・DLチャンネルを最初から持たせる。
  「`src/test` に `override suspend fun checkAvailability` が `fakes/` の外に現れたら落ちる」
  走査テストで、また散らばるのを防ぐ
- **「`CheckFailed` を返す」と「例外を投げる」は別のテスト** — 前者は修正後の本番経路そのもの、
  後者は**`AiClient` 契約の違反**（修正後 `AICoreClient` は投げないが、実装は他にもある）。
  あわせて `CancellationException` を投げる場合に**エラー状態へ変換されず素通りする**ことを固定する
- `AiStatusNotices` は純関数なので5値×機能ぶんを直接テストする
- **`AiAvailabilityMapping` を単体でテストする** — 4定数＋未知の値＋例外＋キャンセルの7経路。
  分類を `AICoreClient` の中に置いたままだと**この7つを1つも書けない**（段階1参照）
- **L11（§0問2）を全ガードへ当てる:** 足した分岐を1つ消したら落ちるテストを、分岐ごとに名指しで用意する。
  落ちるテストを書けない分岐は削る（2026-07-26 のDL進捗ガード・07-31 の `NoteSectionController` と同じ判断）
- **既存の振る舞いを固定しているテストは1件把握済み** —
  [RemarkControllerTest.kt:113,120](../../app/src/test/java/com/example/newproject/RemarkControllerTest.kt#L113)
  が「ひとことはこの端末では利用できません。」を文字列一致で固定している。
  §0問5に従い、着手前に改めて全体を grep して数える

---

## 4. やらないこと（明示）

- **モデルDLポリシーの統一。** 要約だけがノートを開くたび無断で自動DLし、蒸留は通信量を告知して明示タップを求める。
  この不統一は [architecture.md](../dev/system/architecture.md) 2026-07-24 が**意図的な設計判断として記録している**ので、勝手に寄せない
  （CLAUDE.md「実装と設計文書が食い違う場合、勝手にどちらかへ寄せない」）。触るなら別PR＋設計判断から。
- **AIタブ／設定への「この端末は非対応」の一括表示。** 方針1で採らないと決めた。
- **`AiStatusTone` と派生Boolean**（段階2の注記）。
- **生成失敗・DL失敗の文言統一。** 「モデルのダウンロードに失敗しました: …」が4つのControllerに
  重複しており、同じ理屈で1箇所へ寄せられる。ただし**本件は「状態の取得」の話**で、
  こちらは「生成の失敗」— 混ぜると1コミットの範囲を超える。
  **段階1〜4が通ってから、同じ `AiStatusNotice` の上へ足す**（型はそのまま使える）。

---

## 5. 検証

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest lintDebug --offline
```

- Lint は Error 0 / Warning 0 を維持する（hint 12 はゲート外）
- **`androidTest` に文言修正が要る** — `OnDeviceGenerationTest.kt:67` と `PromptTokenBudgetTest.kt:79` の
  KDocが「`checkAvailability()` は例外まで畳む」と書いており、修正後は事実でなくなる。
  触るので **`assembleDebugAndroidTest` も通す**
- **変異確認**（L11・§0問2）: 少なくとも次を潰して、落ちるテストがあることを実際に確認する
  - `catch (CancellationException) { throw e }` を消す → キャンセルが `CheckFailed` に化ける
  - 未知の `FeatureStatus` を `Unsupported` へ寄せる
  - `DOWNLOADING` を `NeedsDownload` へ戻す → 蒸留でCTAが出る（改訂2の症状そのもの）
  - `Unsupported` の `AiNoticeAction` を `Retry` にする
  - `CheckFailed` を `Unsupported` の枝へ畳む → **全機能ぶんまとめて落ちるはず**（落ちないなら統一が効いていない）

**実機確認（ユーザーがAndroid Studioで実施）** — 非対応状態を再現できないので、`StubAiClient`
（[AICoreClient.kt:211](../../app/src/main/java/com/example/newproject/ai/AICoreClient.kt#L211)）を
`Unsupported` / `CheckFailed` / `Downloading` を返す形へ一時的に差し替えて次を見る。

1. 押した機能（蒸留・クイズ・ひとこと・セクションチャット・さがす）が**その場で理由を説明する**
2. 非対応時に**再試行ボタンが出ない**／一時エラー時には**出る**
3. 自動機能（要約・関連ノート）が**黙る** — パネルが騒がしくない
4. **`Downloading` のとき蒸留が「確認してダウンロード」ではなく進捗を出す**（改訂2の受理条件）
5. **ノートを高速に切り替えても「利用できません」が一瞬も出ない**（キャンセル再throwの受理条件。
   これは `StubAiClient` では再現しないので、**実機のNano経路で連続切替**を試す）
6. 正常な端末で8つのAI経路が従来どおり動く（デグレが無い）

---

## 6. 完了条件（CLAUDE.md）

1. コミット前に**別のモデル／エージェントがdiffをレビューする**（恒久工程。自分の2回目のパスは数えない）
2. [change_history.md](../dev/change_history.md) へ1行
3. [background_ai_ux.md](../dev/system/background_ai_ux.md) を更新 — **冒頭の「未解決:」行が本件をそのまま指している**ので、
   そこを解いた記録に差し替える。方針1（説明は操作に従う）は新しい設計判断なので追記する
4. [current_issues.md](current_issues.md) の **AI-2 は実機確認が済むまで消さない**
5. [roadmap.md](roadmap.md) の X-6 を削除（取り消し線を残さない）
6. 報告に **`§0適用:` の1行**を残す。現時点で問1（新しい戻り値）・問2（ガード追加）・
   問4（横展開＝`!=` の3箇所）・問5（既存振る舞いの変更）が該当見込み。
   **問1と問4は、この計画自体が2度当て直されて初めて効いた** — 改訂1（`Available` の改名）は問4、
   改訂2（`Downloading` の分離）は問1（「次の行動で変種を割れているか」）にそのまま当たる。
   **1回目の自分のパスでは両方とも当てたつもりで当たっていなかった**（2026-08-05 の §0 1回目判定と同じ形）
7. L4 を「起動契機で引く一般則」へ昇格させた件は、**方針が実機確認を通ってから** [lessons.md](../dev/lessons.md) へ追記する
8. **この計画書自体を削除する。**
