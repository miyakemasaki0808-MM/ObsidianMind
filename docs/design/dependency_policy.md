# 設計思想 — 依存更新の方針（Lintの更新系チェックをどう扱うか）

**対象領域:** `app/build.gradle.kts` の依存宣言・`gradle/wrapper`・Lintの更新系3チェック
**初版:** 2026-08-01
**状態:** 方針確定。**更新の実行そのものは未着手**（棚卸しの結果は下記§4に2026-08-01時点のスナップショットとして置く）。

---

## 背景

Lint警告を0にする活動で、`NewerVersionAvailable`・`GradleDependency`・`AndroidGradlePluginVersion` の3チェックを
`lint { disable }` へ入れた。理由は「いつ・どこまで上げるかの方針が無いまま毎ビルド出しても行動につながらない」で、
これ自体は正しい。ただし**黙らせた瞬間から更新の判断を誰も催促しなくなった**（→ [lessons.md](../lessons.md) L15）。

そこへ期限が付いた。instrumentationスモークテストが Android 16 で起動前に失敗した原因が
`espresso-core` の反射呼び出し（`InputManager.getInstance`）で、**テスト依存の互換性がそのまま実害になった**。
依存更新はもう「いつか決める保守課題」ではない。

## 判断1: `disable` は維持する。ただし理由を差し替える

当初は「方針が決まったら `disable` から外す」つもりだったが、**これは実行できない**ことが実測で分かった。

一時的に3チェックを有効化して `./gradlew lintDebug` を回すと、**12件すべてが Error になりビルドが落ちる**。
このモジュールは `lint { warningsAsErrors = true; abortOnError = true }` を敷いているためで、
つまり「更新チェックを戻す」は「全依存を常に最新に追随させ続ける」と同義になる。

```text
Lint found 12 errors. First failure:
gradle/wrapper/gradle-wrapper.properties:5: Error: A newer version of Gradle than 9.4.1 is available: 9.6.1
> Lint found errors in the project; aborting build.
```

**この3チェックは、他のLint警告と種類が違う。** 他は「自分のコードに欠陥がある」という指摘なので、
直せば消えて、直さない限り出続けるのが正しい。更新系は**外界（上流のリリース）の変化**を報告するもので、
**こちらが1行も触っていないのに、ある日突然赤くなる**。常時ゲートに載せると、

- CIの赤が「直すべきもの」と「上流が新版を出しただけ」の2種類に分かれ、赤の意味が薄まる
- 追随を強制されるので、CLAUDE.md の「依存ライブラリを一括更新しない。機能単位で上げ、実機確認を伴う」と正面から衝突する

したがって `disable` は残す。**変えるのはコード側ではなくコメント側で、「方針未定だから」ではなく
「常時ゲートに載せられない種類の指摘だから」と書き直す。** 前者は放置に読めるが、後者は判断である。

## 判断2: 棚卸しは「常時」ではなく「区切りごと」に、手動で行う

黙らせたまま忘れないために、催促をビルドから**運用の手順**へ移す。

```bash
# 1. app/build.gradle.kts の `disable += setOf("NewerVersionAvailable", ...)` を一時的にコメントアウト
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew lintDebug
# 2. 一覧を読む（--offline を付けない。上流の版を引きに行く必要がある）
grep "Error:" app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
# 3. コメントアウトを戻す。git diff が空であることを確認する
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

**Compose BOM（2024.09.03）は指摘に出てこない。** `platform()` 宣言をLintが更新対象として見ていないためで、
「最新である」という意味ではない。**BOMだけは棚卸しの自動検出から漏れる**ので、手で確認する必要がある。

**AndroidX Test 枠は既に1回動かした。** `espresso-core` 3.6.1 → 3.7.0、`ext:junit` 1.2.1 → 1.3.0。
この枠が「本番に影響せず、テスト実行だけで検証が閉じる」ことを示した先例になっている。
