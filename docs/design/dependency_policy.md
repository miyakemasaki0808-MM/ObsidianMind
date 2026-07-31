# 設計思想 — 依存更新の方針（Lintの更新系チェックをどう扱うか）

**対象領域:** `app/build.gradle.kts` の依存宣言・`gradle/wrapper`・Lintの更新系3チェック
**初版:** 2026-08-01（同日中に「`disable` 維持」→「`informational` へ降格」へ改稿）
**状態:** 方針確定・実装済み（Lint設定1箇所）。**更新の実行そのものは未着手**（初回棚卸しの結果は §4）。

---

## 背景

Lint警告を0にする活動で、`NewerVersionAvailable`・`GradleDependency`・`AndroidGradlePluginVersion` の3チェックを
`lint { disable }` へ入れた。理由は「いつ・どこまで上げるかの方針が無いまま毎ビルド出しても行動につながらない」で、
これ自体は正しい。ただし**黙らせた瞬間から更新の判断を誰も催促しなくなった**（→ [lessons.md](../lessons.md) L15）。

そこへ期限が付いた。instrumentationスモークテストが Android 16 で起動前に失敗した原因が
`espresso-core` の反射呼び出し（`InputManager.getInstance`）で、**テスト依存の互換性がそのまま実害になった**。
依存更新はもう「いつか決める保守課題」ではない。

## 判断1: `disable` でも `error` でもなく、`informational` へ降格する

当初は「方針が決まったら `disable` から外す」つもりだった。だが3チェックを素のまま有効化すると、
**12件すべてが Error になり `lintDebug` タスクが失敗する**。

```text
Lint found 12 errors. First failure:
gradle/wrapper/gradle-wrapper.properties:5: Error: A newer version of Gradle than 9.4.1 is available: 9.6.1
> Lint found errors in the project; aborting build.
```

このモジュールが `lint { warningsAsErrors = true; abortOnError = true }` を敷いているためで、
素のまま戻すことは「全依存を常に最新へ追随させ続ける」と同義になる。

**この3チェックは、他のLint警告と種類が違う。** 他は「自分のコードに欠陥がある」という指摘なので、
直せば消えて、直さない限り出続けるのが正しい。更新系は**外界（上流のリリース）の変化**を報告するもので、
**こちらが1行も触っていないのに、ある日突然赤くなる**。ゲートに載せると、

- CIの赤が「直すべきもの」と「上流が新版を出しただけ」の2種類に分かれ、赤の意味が薄まる
- 追随を強制されるので、CLAUDE.md の「依存ライブラリを一括更新しない。機能単位で上げ、実機確認を伴う」と正面から衝突する

**ただし選択肢は「ゲートに載せる」と「消す」の二択ではなかった。** Lint の severity は
`error` / `warning` / `informational` / `ignore` に分かれており、`informational`（hint）は
`warningsAsErrors` の昇格対象にならない。実測すると次のようになる。

| 設定 | `lintDebug` | 集計 | 12件は見えるか |
|---|---|---|---|
| 素のまま有効化 | **FAILED** | 12 errors | 見える（ただしCIが赤） |
| `disable`（当初） | SUCCESSFUL | 0 errors, 0 warnings | **見えない** |
| **`informational`（採用）** | **SUCCESSFUL** | **0 errors, 0 warnings, 12 hints** | **見える** |

`--offline` でも同じく12 hints が出る。**「警告0」の主張も保ったまま、催促だけを残せる**ので、
`disable` より厳密に良い。当初案（`disable` を維持し、棚卸しのたびに一時有効化して戻す）は
手順が増えるうえ、戻し忘れればCIが赤になる。**採らない。**

> **表現の注意:** ここで失敗するのは `lintDebug` タスクと、それを実行するCIジョブである。
> **APKのコンパイルが必ず失敗するという意味ではない。** また `disable` を外すこと自体は
> 技術的に可能で、「不可能」ではない — 現在の警告0運用と噛み合わないだけである。

## 判断2: 棚卸しは「常時」ではなく「区切りごと」に行う

hint は毎ビルド出るが、**hint は止めないので放っておける**。止めない以上、見る契機を決めておく。

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew lintDebug
grep "Warning:\|Information:" app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
# HTMLレポート: app/build/reports/lint-results-debug.html（CIも成果物として保存している）
```

**契機はカレンダーではなくマイルストーン**（大きめのPRの区切り、あるいはリリース準備）とする。
「四半期に1回」のような日付基準にしないのは、**この課題が既に一度「いつか決める」で放置された**ため。
日付は守られなかったときに気づく仕組みが無いが、区切りは必ず来る上に「今やるか」を必ず問える。

## 判断3: 更新の単位 — グループごとに1PR、混ぜない

一括更新を禁じているのは、壊れたときに**どれが原因か分からなくなる**ためである。
実際 `espresso-core` の限定更新では、クラス分離と依存更新を1コミットに入れたせいで
「どちらが効いたか」が実行するまで分からない状態になった。単位は次のとおり。

| グループ | 対象 | 上げ方 | 確認 |
|---|---|---|---|
| **ML Kit GenAI** | `genai-prompt` | **必ず単独**。最もリスクが高い | AI 7経路（要約・補記・クイズ・関連ノート・セクションチャット・蒸留・読書痕跡）を実機で一巡。**上げる前にAARを展開してAPIの制約を確認する**（`maxOutputTokens` の 1〜256 で全AI生成が落ちた前例） |
| **Compose BOM** | `compose-bom` とその管理下 | **BOMごと1PR**。個別に上げない（BOMの意味が消える） | 全画面の描画・テーマ切替・OPアニメーション |
| **AndroidX Test** | `espresso-core`・`ext:junit` | 単独。**本番依存に影響しない唯一の枠** | `connectedDebugAndroidTest` が緑になること |
| **AndroidX 本体** | `core-ktx`・`activity-compose`・`lifecycle-*`・`navigation-compose`・`window` | 機能ごとに分ける（navigationは遷移、windowはサイズクラス） | 該当する導線の実機確認 |
| **Kotlin / Coroutines** | `kotlinx-coroutines-android`・`-test` | 単独。本体とtestは同時に揃える | JVMテスト全件＋ノート切替の中断挙動を実機で |
| **ビルドツール** | Gradle wrapper・AGP | 単独 | ローカルとCIの両方が通ること。**実機確認は不要**（生成物が変わらなければ） |
| **テスト専用** | `org.json` | 単独。最も安全 | JVMテストのみ |

**上から順に上げない。** リスクの高いものほど「上げる理由があるとき」だけ動かす。
バグ修正や必要なAPIが入ったときが理由であって、新版が出たことは理由にならない。

## 判断4: 「上げない」も記録する

棚卸しで見送ったものは、次の棚卸しで同じ検討を最初からやり直すことになる。
見送った理由（例: ML Kit GenAI beta4 は変更点を確認できていない）は、その時点の
[change_history.md](../change_history.md) か本書§4へ1行残す。

---

## §4. 棚卸しスナップショット — 2026-08-01

初回の実測結果。**上げたものは1件も無い。** この時点の距離感を残しておくための記録である。

| グループ | 依存 | 現在 | 最新 | 距離 |
|---|---|---|---|---|
| ビルドツール | Gradle wrapper | 9.4.1 | 9.6.1 | 小 |
| ML Kit GenAI | `com.google.mlkit:genai-prompt` | 1.0.0-beta2 | 1.0.0-beta4 | **beta2つぶん。要調査** |
| AndroidX 本体 | `androidx.navigation:navigation-compose` | 2.7.7 | 2.9.8 | 大 |
| AndroidX 本体 | `androidx.activity:activity-compose` | 1.9.3 | 1.13.0 | 大 |
| AndroidX 本体 | `androidx.core:core-ktx` | 1.13.1 | 1.19.0 | 大 |
| AndroidX 本体 | `androidx.core:core-splashscreen` | 1.0.1 | 1.2.0 | 小 |
| AndroidX 本体 | `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | 2.11.0 | 中 |
| AndroidX 本体 | `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.7 | 2.11.0 | 中 |
| AndroidX 本体 | `androidx.window:window` | 1.3.0 | 1.5.1 | 中 |
| Kotlin/Coroutines | `kotlinx-coroutines-android` | 1.9.0 | 1.11.0 | 中 |
| Kotlin/Coroutines | `kotlinx-coroutines-test` | 1.9.0 | 1.11.0 | 中 |
| テスト専用 | `org.json:json` | 20240303 | 20260719 | 小 |

内訳は Gradle 本体1件＋ライブラリ11件。

**Compose BOM（2024.09.03）は指摘に出てこない。** `platform()` 宣言をLintが更新対象として見ていないためで、
「最新である」という意味ではない。**BOMだけは棚卸しの自動検出から漏れる**ので、手で確認する必要がある。

**AndroidX Test 枠は既に1回動かした。** `espresso-core` 3.6.1 → 3.7.0、`ext:junit` 1.2.1 → 1.3.0。
この枠が「本番に影響せず、テスト実行だけで検証が閉じる」ことを示した先例になっている。

**`NewerVersionAvailable` は毎回ネットワークへ問い合わせる**ぶん Lint が遅くなる
（実測で概ね2倍）。それでも hint を残す価値のほうが大きいと判断した。
