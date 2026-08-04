# ソースコード品質レビュー再評価

**評価日:** 2026-07-31

**対象ブランチ:** `feature/Improvement_Function_No.5`

**対象コミット:** `efc4ebd`

**対象範囲:** `app/src/main`、`app/src/test`、`app/src/androidTest`、Gradle設定、AndroidManifest、GitHub Actions

**検証コマンド:**

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest --offline --rerun-tasks
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest --offline --rerun-tasks
```

> **位置づけ:** 2026-07-28の評価は対象コミット`ac53749`のスナップショットとして
> [別ファイル](2026-07-28-code-quality.md)に残る。本書は`efc4ebd`時点の独立した再評価である。

---

## 1. 結論

現状は、**JVM上で検証できる内部ロジックと静的品質ゲートは強い一方、長文表示のUI性能、SAF境界、instrumentation実行環境、公開リリース構成に未完部分がある**状態です。

D案・E案によって、ライト配色の大半、色トークンの面対応、グラデーション停止色の二重管理、Lint／Kotlin警告、正式`applicationId`、release build type、androidTestのコンパイル基盤は改善しました。一方、前回のP1である表示用MarkdownのMain上での二重解析と、復旧・補記保存・ReadingTraceの境界課題は残っています。さらに今回、androidTestを初めて実行した結果、Android 16エミュレータではテスト環境の初期化自体が失敗することを確認しました。

- 本番Kotlin: **93ファイル・14,400行**
- JVMテスト: **53ファイル・8,553行・459件**
- JVMテスト結果: **459 success / 0 failure / 0 error / 0 skipped**
- Android instrumentation test: **1ファイル・2件**
- instrumentation実行結果: **0 success / 2 failure**（Android 16エミュレータ）
- Android Lint: **0 errors / 0 warnings**
- Kotlinコンパイル警告: **0件**（`allWarningsAsErrors`で固定）
- リリース組み立て: **成功**（`app-release-unsigned.apk`、約10MB）
- androidTest APK組み立て: **成功**（約952KB）
- CI: JVMテスト、Lint、androidTest APKのコンパイル／組み立てを実行
- 作業ツリー: 再評価開始時点でクリーン

総合評価は **8.1 / 10** です。前回の8.0からの改善幅を0.1に留めたのは、アクセシビリティと警告ゲートの改善が実装・テストの両方で確認できた一方、最優先の性能問題が未解決で、instrumentation基盤も「動作する」とはまだ言えないためです。

| 評価軸 | 評価 | 前回比 | 要点 |
|---|---:|---:|---|
| 可読性 | 8.0 | ±0.0 | 責務・命名・KDocは良好。大きい画面／Controllerは残る |
| 保守性 | 8.0 | ±0.0 | 依存方向と状態所有を固定。配色は単一ソース化が進んだ |
| 拡張性 | 7.5 | ±0.0 | Controller追加の型は明確。ジョブ登録と一部レイアウト契約は手動 |
| 信頼性 | 8.5 | ±0.0 | 蒸留保存は強い。補記保存と復旧チェックには境界上の隙がある |
| テスト容易性 | 8.0 | ±0.0 | JVMテストは強いが、androidTestは実行時に初期化失敗する |
| 並行処理 | 8.0 | ±0.0 | 主要要求はJob＋世代IDで保護。復旧チェックだけ管理外 |
| 性能 | 7.0 | ±0.0 | I/O上限とAI抜粋の別dispatcherは良好。表示Markdown解析はMain上 |
| セキュリティ・プライバシー | 8.5 | ±0.0 | INTERNET権限なし、オンデバイスAI、機微設定をバックアップ除外 |
| アクセシビリティ | 8.0 | +1.5 | 文字と主要部品はAAへ改善。ナビ帯上のバッジ塗りが既知未達 |
| 開発・リリース運用 | 7.5 | +0.5 | 警告ゲート・正式ID・release構成を追加。署名・R8・実行テストは未完 |
| ドキュメント | 8.0 | ±0.0 | 判断理由は豊富。一部コメントと実態に小さなずれがある |

**P0相当の問題、確認済みの既存ノート消失経路、ネットワークへの本文送信は見つかりませんでした。**

---

## 2. 優先度の高い指摘

### P1-1. 表示用Markdownは現在もMain上で2回解析される

前回指摘は未解決です。

- 通常表示は [`NoteReaderTab`](../../app/src/main/java/com/example/newproject/ui/screen/NoteReaderTab.kt#L103) の`remember`内で`buildNoteSectionModel()`を同期実行する
- 全画面表示は [`FullscreenNoteScreen`](../../app/src/main/java/com/example/newproject/ui/screen/FullscreenNoteScreen.kt#L125) で同じ本文を再解析する
- 解析結果はComposableをまたいで共有されない
- 表示用本文は最大1MBまで許可される

AI入力用の重い抜粋生成はMain外へ移っていますが、表示経路には適用されていません。大きなノートを開く時と全画面へ入る時に、UI停止が起こり得ます。

**推奨:** ノート読込後に`Dispatchers.Default`で`NoteSectionModel`を1回だけ生成し、通常表示と全画面表示で共有する。200KB・1MBの解析時間とキャンセルを計測し、Composableからの同期呼び出しをソース走査テストで禁止する。

---

## 3. 中優先度の指摘

### P2-1. instrumentationスモークテストはAndroid 16で2件とも起動前に失敗する

[`InstrumentationSetupTest`](../../app/src/androidTest/java/com/example/newproject/InstrumentationSetupTest.kt#L26) にはContext取得とCompose描画の2件があり、[`assembleDebugAndroidTest`](../../.github/workflows/ci.yml#L40) もCIへ追加されています。しかし、`connectedDebugAndroidTest`をAndroid 16エミュレータで実行すると、2件とも次の例外で失敗しました。

```text
NoSuchMethodException: android.hardware.input.InputManager.getInstance []
```

失敗はテスト本体へ入る前のCompose／Espressoアイドル待機初期化で発生します。Contextだけを見るテストにもクラス共通の`createComposeRule()`が適用されるため、2件とも同じ理由で止まります。[`build.gradle.kts`](../../app/build.gradle.kts#L104) の依存とRunnerはコンパイル可能ですが、「Runnerが起動しComposeを描画できる土台」は実証できていません。

また、CIはandroidTest APKを組み立てるだけで、エミュレータ上の実行は行いません。したがって、この失敗は現行CIでは検出されません。

**推奨:** 対象APIで動くAndroidX Test／Espressoの組み合わせを確定する。ContextだけのテストとComposeルールを使うテストを別クラスへ分け、少なくとも1つの管理対象エミュレータで`connectedDebugAndroidTest`を継続実行する。

### P2-2. 蒸留の復旧チェックだけJob・requestId管理の外にある

前回指摘は未解決です。[`DistillController.checkRecovery()`](../../app/src/main/java/com/example/newproject/controller/DistillController.kt#L334) は、`scope.launch`したJobを保持せず、要求世代も採番しません。通常の分析・保存が`activeRequestId`で保護される一方、復旧確認の後着は`RecoveryRequired`を直接書き込みます。

保存直前には未解決復旧レコードを再確認するため、危険な書き込みは止まります。ただし、進行中の分析と復旧表示が互いに上書きし、不要なAI実行や状態遷移の混乱を起こし得ます。

**推奨:** 復旧確認を追跡Jobと世代管理へ載せ、「遅い復旧確認」と「進行中分析」を交差させるテストを追加する。

### P2-3. AI補記は書込失敗時に空・部分ファイルを残し得る

前回指摘は未解決です。[`NoteRepository.createAnnotationFile()`](../../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L197) は`createDocument()`後に作成済みURIへ直接書き込み、失敗時の削除と保存後の再読込検証を行いません。

そのため、画面には生成失敗と表示されても、`_AI補記`には空または途中までのファイルが残る可能性があります。

**推奨:** 作成後の書込失敗時に作成済みURIをベストエフォートで削除し、保存後に最低限のサイズ／本文検証を行う。Android APIを小さいgatewayへ分け、失敗注入をJVMテスト可能にする。

### P2-4. ReadingTraceの外部同期・改名・孤児管理は未解決

[`SafReadingTraceDocumentGateway`](../../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L135) は、[`folderIndexOf()`](../../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L222) で作ったVault内索引をプロセス中無期限に再利用します。自身が作成したファイルは索引へ追加しますが、外部同期で後から増えたファイルは再起動まで認識しません。

相対パスのハッシュをキーにするため、ノートのrename／move／delete後には旧痕跡が孤児になります。

**推奨:** 索引ミス時の1回再走査、短いTTL、または明示無効化を導入する。孤児は同期途中の誤削除を避け、猶予期間付き清掃か手動整理とする。

### P2-5. releaseは組み立てられるが公開可能な成果物ではない

[`build.gradle.kts`](../../app/build.gradle.kts#L41) にrelease build typeが追加され、`applicationId`も`com.vigilith.ai`へ確定しました。一方で次は未完です。

- 生成物は`app-release-unsigned.apk`
- `isMinifyEnabled = false`でR8未適用
- release署名設定なし
- ML Kit GenAIを含む縮小後の実機検証なし

**推奨:** 署名情報をリポジトリ外から注入し、release署名、R8／resource shrinking、オンデバイスAIを含むrelease実機スモーク確認を一組で整備する。

### P2-6. 関連ノートの抜粋予算は注意書きの固定費が大きい

[`NoteExcerptLimits.RELATED`](../../app/src/main/java/com/example/newproject/model/NoteExcerptLimits.kt#L12) は600文字のままです。長文時は共通注意書きもこの予算から支払うため、現ノート本文へ使える領域が小さくなります。

**推奨:** 完成プロンプトの実トークン数を測り、関連ノート専用の短い注意書きか上限変更を判断する。候補ブロックとの合算を見ずに単独で増やさない。

---

## 4. D案・E案の再評価

### D案: 配色とアクセシビリティは大幅に改善した

今回の改善は単なる色値の変更に留まりません。

- [`AppColorContrastTest`](../../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L181) が、文字トークンを実際に載る面との組み合わせで検証する
- 半透明面は合成後の実効背景で検証する
- グラデーション停止色は`AppColorScheme`のリストを実装とテストで共有し、全停止色を総当たりする
- [`VibrantTextUsageTest`](../../app/src/test/java/com/example/newproject/ui/theme/VibrantTextUsageTest.kt#L41) が、背景を持たない画面からの`OnVibrant`直接使用と文字色への任意alpha適用を禁止する
- [`GradientHeader`](../../app/src/main/java/com/example/newproject/ui/component/GradientHeader.kt#L50) はライトで白いヘイズ＋濃色文字、ダークで透明面＋明色文字を使い分ける

前回の「既知未達7件を実測値として固定するだけ」という状態からは明確に前進しています。文字と主要な部品の組み合わせは基準を強制するテストへ移りました。

ただし、[`AppColorContrastTest`](../../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L287) は、ライトのナビ帯上でSuccess／Errorバッジの塗りが3:1を満たさないことを、意図的に「未達の記録」として残しています。中の記号は読めますが、塗り自体の判別基準は未達です。したがって、**「ライト配色は例外なくAA準拠」とはまだ言えません。**

また、`GradientHeader`の全幅化は呼び出し側の水平余白と`horizontalBleed = 20.dp`が一致する前提です。現画面では成立していますが、画面余白を変える際の手動契約であり、レイアウトテストはありません。

### E案: 品質ゲートは成立したが、実行・公開工程は未完

[`build.gradle.kts`](../../app/build.gradle.kts#L8) ではKotlinの`allWarningsAsErrors = true`、Lintの`warningsAsErrors = true`と`abortOnError = true`が有効です。今回の再実行でもLint 0件、Kotlin警告0件を確認しました。baselineで既存警告を隠さず、増加をビルド失敗にする方針は適切です。

一方、依存更新系の3チェックは方針未定のまま無効化されています。今回のinstrumentation実行失敗により、テスト依存と対象APIの互換性が実害になっているため、依存更新方針は単なる保守課題ではなくなりました。

なお、依存定義付近の「テスト本体はまだ無い」というコメントは、現在の2件のスモークテストと一致していません。機能には影響しませんが、次回修正時に更新すべき小さなドキュメントずれです。

---

## 5. テスト・静的解析・CIが保証する範囲

### 現在保証できること

- JVMテスト459件は全件成功し、失敗・エラー・スキップはない
- Markdown、AI応答パース、候補採点、Controller、ReadingTrace JSON、蒸留保存、配色などの純粋ロジックを広く覆う
- パッケージ依存方向、AI抜粋のdispatcher、配色面対応、危険な文字色使用をソース走査テストでも固定する
- Lint警告とKotlin警告は新しく増やせない
- release APKとandroidTest APKはオフラインで組み立てられる
- CIはJVMテスト、Lint、androidTestソースのコンパイル崩れを検出する

### 現在保証できないこと

- instrumentationテストは対象エミュレータで0/2のため、Runner／Compose描画の土台
- 実ContentResolver／SAF、Compose Navigation、Activity／プロセス再生成
- TalkBack、キーボード、複数画面幅、ライト／ダーク全状態の自動UI回帰
- Gemini Nanoの実モデル準備・生成・release縮小後の動作
- 行／分岐カバレッジ率
- Main上の表示用Markdown解析時間とフレーム停止

シミュレータ上で最新ライト配色の見た目は確認されていますが、これは自動回帰テストではなく、表示状態と端末幅を限定した目視確認です。

---

## 6. 前回レビューからの更新

| 前回時点 | 現在 |
|---|---|
| JVMテスト450件 | **459件、全件成功** |
| Lint 0 errors / 21 warnings | **0 errors / 0 warnings、増加時失敗** |
| Kotlin警告14件、成功扱い | **0件、増加時失敗** |
| instrumentationソース／依存／Runnerなし | **2件と基盤を追加。組み立て成功、Android 16で実行0/2** |
| ライト配色に既知未達7件 | **文字・主要部品は基準強制へ。ナビ帯上のバッジ塗り2種が既知未達** |
| グラデーション停止色を実装とテストで二重管理 | **`AppColorScheme`の停止色リストへ単一ソース化** |
| `applicationId = com.example.newproject` | **`com.vigilith.ai`へ確定** |
| build type／警告方針なし | **release build typeとLint／Kotlin警告ゲートを追加** |
| releaseは未署名・R8未適用 | **未解決** |
| 表示MarkdownのMain上二重解析 | **未解決** |
| 蒸留復旧チェックの世代管理外 | **未解決** |
| AI補記の失敗時後始末なし | **未解決** |
| ReadingTrace外部同期・孤児 | **未解決** |

---

## 7. 推奨する実施順序

1. 通常／全画面の`NoteSectionModel`をMain外で1回だけ生成・共有する
2. Android 16で動くinstrumentation依存構成へ直し、2件を実行可能にする
3. 蒸留の復旧確認を追跡Jobと世代管理へ載せる
4. AI補記の書込失敗時削除と保存後検証を追加する
5. SAF・Navigation・Activity再生成を通す少数のinstrumentationテストを追加し、実行ゲートへ載せる
6. ReadingTrace索引の再走査契機と孤児整理方針を決める
7. release署名、R8、resource shrinking、ML Kit GenAIのrelease実機確認を一組で整備する
8. ライトのナビ帯上にあるバッジ塗りの判別方法を決める
9. 依存更新の周期・対象・検証方法を決め、無効化した3つのLintチェックを見直す
10. 完成プロンプトのトークン計測後に関連ノートの抜粋予算を調整する

最初の4件は、現在のController／Repository構成を全面的に変えず局所的に直せます。特にinstrumentationは、基盤の有無を議論する段階から、**実行して具体的な互換性障害を再現できる段階**へ進みました。ここを緑に戻して初めて、「androidTestの土台が動く」と評価できます。

---

## 8. 対応判断の追記（2026-07-31）

P2-1のinstrumentation互換性修正は、**今回のD案・E案の活動には含めず、次回の独立した作業で対応する**。

今回の活動で確定した状態は次のとおり。

- androidTestの依存、Runner、スモークテスト2件、CI上のコンパイル確認までは追加済み
- `connectedDebugAndroidTest`をAndroid 16エミュレータで実行済み
- 2件とも`createComposeRule()`の初期化段階で失敗し、結果は **0 success / 2 failure**
- 例外は`NoSuchMethodException: android.hardware.input.InputManager.getInstance []`
- したがって、現時点では「土台を組み立てられる」とは言えるが、**「土台が動く」「スモークテストが通る」とは言わない**

次回は、現在の`espresso-core:3.6.1`に対し、AndroidX Test公式リリースノートで同じ反射呼び出しを`getSystemService`へ置き換えたと明記されている
[`espresso-core:3.7.0`](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)への限定更新から着手する。必要に応じて`ext:junit`も対応する1.3.0へ揃えるが、Compose BOMや本番依存を含む一括更新には広げない。

再開時の確認順序は次のとおり。

1. Context確認テストをComposeルールのないクラスへ分離し、RunnerとComposeの故障を別々に観測できるようにする
2. AndroidX Test／Espressoだけを限定更新する
3. Android 16エミュレータで`connectedDebugAndroidTest`を再実行する
4. 2件が通れば、SAF・Navigation・Activity再生成の実テスト追加へ進む
5. 同じ範囲で直らなければ、結果を記録して依存互換性の独立課題として調査する

併せて、[`build.gradle.kts`](../../app/build.gradle.kts#L101) の「テスト本体はまだ無い」というコメントは現在の実装と一致しないため、次回のコード変更時に修正する。[`GradientHeader`](../../app/src/main/java/com/example/newproject/ui/component/GradientHeader.kt#L50) の`horizontalBleed = 20.dp`と呼び出し側余白の手動契約は既知の軽微な保守課題とし、今回の完了を妨げる問題にはしない。

---
