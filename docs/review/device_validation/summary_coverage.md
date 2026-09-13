# 要約カバレッジの基準線を採る実機検証ケース

## 正本

- 測り方と限界: [ai_quality_measurement](../../dev/system/ai_quality_measurement.md)
- 測られる側: [note_summary](../../dev/features/note_summary.md)
- 共通の準備と後処理: [Codex実機検証手順](README.md)

## 適用条件

**要約のプロンプト・抜粋の作り方・抜粋予算を変えたときに使う。** 変える前と後で同じ表を採り、並べる。
初回は「変える前」が無いので、**基準線を作ることそのものが目的**になる。

測る対象（抜粋の作り方の変種）は `SUMMARY_EXCERPT_VARIANTS` が持つ。
**形はJVMの `SummaryExcerptVariantsTest` が先に固定している** —
予算超過・プロンプトの切り詰め・「変種が実は同じ」は、ここへ来る前に落ちる。

**何を生成し何を使い回すかも、JVMの `SummaryBaselinePlanTest` が固定している。**
生成は決定的なので、同じプロンプトの組は同じ実行の中で先に生成した応答を使い回す（全27組のうち生成は15回）。
引数と出力行の形は `SummaryCoverageBaselineTest` の KDoc が持つ。

物差し（`SummaryCoverage`）と閾値はJVM側で決まっており、ここでは動かさない。
ここが答えるのは **「Nano が実際に返す要約は、その物差しでどう出るのか」** だけである。

`SummaryCoverageBaselineTest` は端末AIを呼ぶので、Nano非対応端末では `Assume` で skip される。
**skip を「通った」と読まない。**

## 検証前

- **常設の検証用Vaultで行う。** このテストは固定コーパス（test APK の assets）だけを読み、
  Vaultのノートには触れないが、`ActivityScenarioRule` がアプリを起動するので
  **最後に開いていたノートの読書痕跡が増えうる。** 元Vaultでは行わない。
- 生成は `AiClient` の Mutex で直列化される。**他のAI機能を同時に走らせない**
  （ノートを開いたままにすると要約・分野判定が割り込む）。
- 直前に採った表があるなら、**それがどのプロンプト版で採られたか**を確認する。
  プロンプトを変えた後の値は、変える前の値と直接は比べられない。

## ケース

| ID | 入力・操作 | 期待 |
|---|---|---|
| `COVER-01` | `SummaryCoverageBaselineTest` の `固定コーパスを実機から読める` を実行する | 9本すべてを読めて中身が空でない。**Nanoは要らないので、ここが赤ならassetsの同梱を疑う** |
| `COVER-02` | `要約の基準線を採る` を引数なしで実行する | 冒頭の `PLAN` 行が**27組・生成15回・使い回し12組**を示す。**生成が12回を超えるので途中で BUSY になりうる** — そのときは `COVER-09` で続きを採る。最終的に全組の `RESULT` 行が揃い、生成した応答がすべて空でない |
| `COVER-03` | `RESULT` 行と `RESPONSE` 行を採る | 組ごとの「文数・裏付けの最小と平均・被覆・生成ms（または使い回し元）」と応答の全文が読める。**数値ではなく行をそのままレビュー本文へ載せる** |
| `COVER-04` | `[裏付けが弱い]` と出た文を数える | 数を残すだけで、**合否を判定しない**。実機の出力では忠実な文もこの印を受ける（→ 正本 判断7）。新しく取れた応答は `references/nano/` へ取り込み、ラベルを付ける材料にする |
| `COVER-05` | `RESULT` 行の生成msを並べる | 1回あたりの時間と合計を残す。**要約キャッシュを入れるかの判断材料**になる（→ [ai_input_budget](ai_input_budget.md) の計測と同じ扱い） |
| `COVER-06` | `[未反映]` として出た見出しを実際の要約と読み比べる | **長いノートで未反映が出ること自体は異常ではない**。物差しが的外れな箇所を指していないかを人が確かめる |
| `COVER-07` | 末尾の `COMPARE` 行（長文3本 × 変種）を並べる | **ノートごとに**本番と旧方式を読み比べる。**単回の探索の記録であり、優劣を確定しない**（入力が変わるのは3本だけで、生成は決定的なので繰り返しても揺らぎは測れない）。差を書くときは文数の違いも併記する |
| `COVER-08` | 同じ `COMPARE` 行で本番と予算2倍を並べる | 同じく探索の記録に留める。**差が小さくても、予算を増やす案を捨てる根拠にしない**（判定保留を結論として書いてよい） |
| `COVER-09` | 途中で `FAILED` が出たら、**時間を空けて**から `RESUME` 行の値をそのまま `-e keys` に渡して続きを採る | `RESUME` は失敗した組を含む。成功した組を採り直さずに続きが採れる。**各実行の `RESULT` のキーを突き合わせ、全27組に重複も欠落も無い**ことを記録する。空けた時間も記録する（BUSY の時間窓の長さは特定しない） |
## 引数の例

共通手順（[README](README.md)）と同じく、実行前に `am force-stop` する。

```text
adb -s <serial> shell am force-stop com.vigilith.ai
adb -s <serial> shell am instrument -w -r \
  -e class 'com.example.newproject.ai.SummaryCoverageBaselineTest#要約の基準線を採る' \
  -e keys budget_x2/0200_long_nested,budget_x2/0300_bullet_list \
  com.vigilith.ai.test/androidx.test.runner.AndroidJUnitRunner
```

`-e keys` の代わりに `-e variants`（`production` / `head_only` / `budget_x2`）と `-e notes`（固定コーパスのファイル名から `.md` を除いたもの）でも絞れる。**`keys` とは混ぜない。**
**知らない名前を渡すとテストが失敗する**（0組を採って成功したように見せない）。

## 後処理

- 常設検証用Vaultのまま終える。**元Vaultへは戻さない。**
- test APK を削除する。一時Vaultは作っていないので削除対象は無い。
- **採った表は正本（[ai_quality_measurement](../../dev/system/ai_quality_measurement.md)）へ書き写す。**
  この手順書へ日付つきの結果を書き戻さない。

## 記録

- 合否はケースIDで返す。skip したものは「対象外」と理由を書く。
- `COVER-03` の表は**数値をそのまま**レビュー本文へ載せる。要約すると比較できなくなる。
- **緑であることを「要約が良い」と書かない。** このテストは値を assert していない。
