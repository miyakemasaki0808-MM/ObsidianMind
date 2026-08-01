# ソースコード品質レビュー

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）

**評価日:** 2026-07-28

**対象ブランチ:** `feature/Improvement_Function_No.5`

**対象コミット:** `ac53749`

**対象範囲:** `app/src/main`、`app/src/test`、Gradle設定、AndroidManifest、GitHub Actions

**検証コマンド:**

```bash
./gradlew testDebugUnitTest lintDebug --offline --rerun-tasks
./gradlew assembleRelease --offline
```

> **この文書の位置づけ:** 対象コミット時点の実装に対する品質評価のスナップショット。
> 機能・構成の説明は [source_code_analysis.md](source_code_analysis.md)、
> 未解決課題の進行管理は [_wip/current_issues.md](_wip/current_issues.md) が担う。
>
> **最新評価:** 2026-08-01・`feature/Improvement_Function_No.9`・基準HEAD `4c269df`、
> **8.5 / 10**。過去評価は履歴として残し、最新の独立評価を本書末尾へ追記している。

---

## 1. 結論

現状は、**機能追加を継続できる堅実な内部品質に到達している一方、長文表示のUI性能と実端末境界、公開リリース構成には未完部分がある**状態です。

前回の主要指摘だったVault世代管理、検索スコープ競合、ReadingTraceの累計回数、無制限本文読込、パッケージ循環、状態所有の曖昧さは、実装と回帰テストの両方で解消されています。全面的な再設計は不要です。次の品質改善は、既存のController構成を保ったまま、UI側の重い解析をMainから外し、復旧・SAF境界のテスト可能性を補うのが最も費用対効果に優れます。

- 本番Kotlin: **92ファイル・14,084行**
- JVMテスト: **52ファイル・8,222行・450件**
- テスト結果: **450 success / 0 failure / 0 error / 0 skipped**
- Android Lint: **0 errors / 21 warnings**
- Kotlinテストコンパイル警告: **14件**（`SummaryControllerTest`のExperimental Coroutines API）
- Android instrumentation test: **0件**
- リリース組み立て: **成功**（`app-release-unsigned.apk`、約11MB）
- CI: PRと`main`へのpushでJVMテストとLintを実行
- 作業ツリー: レビュー開始時点でクリーン

総合評価は **8.0 / 10** です。前回の7.2から明確に改善しました。特に、状態競合を世代IDと集中調停で防ぐ設計、パッケージ依存をテストで固定した点、用途別の読込上限、蒸留の復旧可能な書き戻しは高く評価できます。一方、テスト全件成功は実SAF、Compose、Activity、Gemini Nanoの統合動作や、既知のライト配色コントラストを保証しません。

| 評価軸 | 評価 | 要点 |
|---|---:|---|
| 可読性 | 8.0 | 責務・命名・KDocは良好。大きい画面／調停ファイルは残る |
| 保守性 | 8.0 | 依存方向と状態所有を型・テストで固定。Android型が下位モデルへ残る |
| 拡張性 | 7.5 | Controller追加の型は明確。状態・ジョブの登録契約は手動 |
| 信頼性 | 8.5 | 蒸留と非同期世代管理は強い。補記保存と復旧チェックに境界上の隙がある |
| テスト容易性 | 8.0 | 純粋ロジックと調停は強い。非null `Uri` を使う実行経路とUIは未検証 |
| 並行処理 | 8.0 | 主要要求はJob＋世代IDで保護。復旧チェックだけ要求管理の外にある |
| 性能 | 7.0 | I/O上限・キャッシュ・並列数制限は良好。Markdown解析がMainに残る |
| セキュリティ・プライバシー | 8.5 | INTERNET権限なし、オンデバイスAI、機微設定のバックアップ除外 |
| アクセシビリティ | 6.5 | semanticsは丁寧だが、ライト配色に既知のAA未達が残る |
| 開発・リリース運用 | 7.0 | CIとrelease assembleは成立。署名・R8・正式ID・警告予算が未整備 |
| ドキュメント | 8.0 | 判断理由は非常に豊富。一部の件数・性能説明が現実装とずれている |

**P0相当の問題、確認済みのユーザーノート消失経路、ネットワークへの本文送信は見つかりませんでした。**

---

## 2. 優先度の高い指摘

### P1-1. 最大1MBのMarkdown解析がComposeのMain上で走り、全画面進入時に同じ本文を再解析する

AI入力用の抜粋生成は `Dispatchers.Default` へ移されましたが、**表示用の同じMarkdownパーサはMain上に残っています**。

- 通常表示は [`NoteReaderTab`](../app/src/main/java/com/example/newproject/ui/screen/NoteReaderTab.kt#L92) の `remember` 内で `buildNoteSectionModel(content)` を同期実行する
- 全画面表示は [`FullscreenNoteScreen`](../app/src/main/java/com/example/newproject/ui/screen/FullscreenNoteScreen.kt#L125) で同じ本文をもう一度同期解析する
- [`buildNoteSectionModel`](../app/src/main/java/com/example/newproject/domain/markdown/NoteSections.kt#L87) は全本文をブロック解析し、各セクション本文も再構成する
- 表示フォールバックは最大1MBまで許可されている
- 同じパーサを使う抜粋処理は、既存の実測で1MB約460ms、200KB約20msと記録されている

したがって、大きなノートを開いた瞬間と全画面へ入る瞬間に、端末のUIスレッドが目に見えて停止し得ます。`source_code_analysis.md`は通常表示と全画面表示が「同じパース済みブロック」を使うと説明していますが、実装上は各Composableが独立した`remember`を持つため共有されません。

**推奨:** ノート読込後に `Dispatchers.Default` で `NoteSectionModel` を1回だけ作り、通常／全画面で共有する。少なくともComposable内の同期解析を検出するテストと、200KB・1MB入力の時間／キャンセル計測を追加する。実端末では長文を開く操作と全画面進入のフレーム停止を確認する。

---

## 3. 中優先度の指摘

### P2-1. 蒸留の復旧チェックだけJob・requestId管理の外にある

[`DistillController.checkRecovery()`](../app/src/main/java/com/example/newproject/controller/DistillController.kt#L334) は、`scope.launch`したJobを保持せず、要求世代も採番しません。通常の分析・保存は`activeRequestId`で保護されていますが、復旧チェックの後着はその世代を無効化せず、`RecoveryRequired`を直接書き込みます。

起動OP中は表面化しにくいものの、Vault切替直後に復旧確認が遅延し、その間にユーザーが蒸留を始めると、次の順序が成立します。

1. 復旧確認を開始
2. 新しい蒸留分析を開始
3. 復旧確認が後着して`RecoveryRequired`を表示
4. 進行中の分析が同じrequestIdのまま候補を返し、復旧表示を上書き

保存層の [`DistillWriteRepository.write()`](../app/src/main/java/com/example/newproject/data/DistillWriteRepository.kt#L114) が未解決レコードを再確認するため、危険な書き込みは止まります。現時点の主な影響は、復旧表示の一時的な消失、不要なAI実行、状態遷移の混乱です。

**推奨:** 復旧確認を追跡Jobへ載せ、開始中は新規蒸留を許可しないか、復旧結果を反映する時点で分析世代を無効化する。「遅い復旧確認」と「進行中分析」を交差させるテストを追加する。

### P2-2. AI補記ファイルは作成後の書込失敗で空・部分ファイルを残し得る

[`NoteRepository.createAnnotationFile()`](../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L197) は、`createDocument()`で保存先を作った後、そのURIへ直接書き込みます。ストリームを開けない、途中書込で例外になる、プロセスが終了する、といった場合の削除・再読込検証はありません。

蒸留ほど重い復旧設計は不要ですが、生成失敗を表示した一方で`_AI補記`に空または途中までのファイルが残る可能性があります。また、ファイル名は分単位のため、同じノートで同一分に再生成するとSAFプロバイダが名称を変える場合があり、Controllerが予測した表示名と実名が一致しないことがあります。

**推奨:** 作成後の書込失敗時に作成済みURIをベストエフォートで削除する。書込後に最低限のサイズ／本文検証を行い、表示名は保存後のメタデータから取得する。Fake gatewayを介した失敗注入テストを追加する。

### P2-3. 重要なAndroid境界は、設計上テストできないまま残る

JVMテスト450件は強力ですが、[`SearchControllerTest`](../app/src/test/java/com/example/newproject/SearchControllerTest.kt#L23) 自身が明記するように、検索実行とVault世代照合は非nullの`android.net.Uri`が必要なため検証していません。

- `model`の [`NoteFile`](../app/src/main/java/com/example/newproject/model/NoteTypes.kt#L12) と [`RelatedNote`](../app/src/main/java/com/example/newproject/model/RelatedNote.kt#L5) がAndroidの`Uri`を直接保持する
- `NoteViewModel`はAndroidスタブの制約からJVMで直接生成していない
- `app/src/androidTest`と`androidTestImplementation`、`testInstrumentationRunner`が存在しない
- 実ContentResolver／SAFプロバイダ、Navigation、Activity再生成、TalkBack、Gemini Nanoは自動検証外

`NoteSessionCoordinator`の分離で調停の大半は検証可能になりましたが、「世代ガードが実SAFの完了順で効くか」は依然として実機確認だけが担保です。

**推奨:** まずRepositoryの必要操作を小さいgateway/interfaceに分け、Controllerテストで`Uri`を解釈せず不透明な参照として扱えるようにする。その後、少数のinstrumentationテストでSAF・Navigation・Activity再生成を通す。

### P2-4. ReadingTraceの外部同期・改名・孤児管理は未解決

[`SafReadingTraceDocumentGateway`](../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L222) はVaultごとのファイル索引をプロセス中無期限に再利用します。自身が作ったファイルは追加しますが、外部同期で後から増えた痕跡は再起動まで認識しません。

また、相対パスのSHA-256をキーにするため、ノートのrename／move／deleteで旧痕跡が孤児になります。これはノート本文を壊す問題ではありませんが、長期利用と複数端末同期では再会カードの見逃し、重複ファイル、孤児数の増加につながります。

**推奨:** 索引ミス時の1回だけ再走査、短いTTL、または明示無効化を追加する。孤児は同期途中の誤削除を避けるため、自動即時削除ではなく手動整理か猶予期間付き清掃が安全です。

### P2-5. 関連ノートの抜粋予算は注意書きの比率が高い

[`NoteExcerptLimits.RELATED`](../app/src/main/java/com/example/newproject/model/NoteExcerptLimits.kt#L12) は600文字ですが、長文時は共通注意書き226文字もこの予算内から支払います。固定ラベルも含めると、現ノート本文の実質領域は約228文字です。

型で上限を守り、冒頭だけでなく骨格・末尾も渡す設計自体は良好です。ただし関連ノート経路だけは、構造化の固定費が本文より大きくなりやすく、AI推薦品質のボトルネックになっています。

**推奨:** 完成プロンプトの実トークン数を計測してから、関連ノート専用の短い注意書き、または600文字上限の段階的引上げを判断する。候補ブロック3,500文字との合算を見ずに単独で増やさない。

---

## 4. テスト・静的解析・CI

### 良い点

- 450件がすべて成功し、失敗・エラー・スキップはありません。
- Markdown、AI応答パース、関連候補採点、キャッシュ、Controller、ReadingTrace JSON、蒸留保存、色コントラストなど、壊れやすい純粋ロジックを広く覆っています。
- `NoteSessionCoordinatorTest`は、全UiStateフィールドのリセット漏れをリフレクションで検査し、7 Controllerの一斉停止・初期化を実物で確認しています。
- `PackageDependencyTest`は、以前残っていた4組のパッケージ循環を再導入できないよう依存方向を固定しています。
- `NoteExcerptThreadingTest`は、AI入力用の重い抜粋生成がMainへ戻らないよう全呼び出し箇所を固定しています。
- CIはPRと`main`へのpushでテストとLintを実行し、レポートを保存します。

### 保証していない範囲

- instrumentationテストが無いため、実SAF、Compose Navigation、Activity／プロセス再生成、TalkBack、端末AIは保証外です。
- UI側の`buildNoteSectionModel()`は、`NoteExcerptThreadingTest`の対象外です。
- `NoteViewModel`本体を通すテストはなく、Android境界とCoordinatorの結線はコンパイルと実機確認に依存します。
- テストカバレッジ率は計測されていません。件数は広さの参考にはなりますが、行／分岐網羅率ではありません。

### 警告

今回の`--offline --rerun-tasks`実行では、Lintは **0 errors / 21 warnings** でした。

| 種別 | 件数 |
|---|---:|
| `UseKtx` | 9 |
| `NewerVersionAvailable` | 3 |
| `UnusedResources` | 3 |
| `UsableSpace` | 2 |
| `ConstantLocale` | 2 |
| `ObsoleteSdkInt` | 1 |
| `AndroidGradlePluginVersion` | 1 |

さらに [`SummaryControllerTest`](../app/src/test/java/com/example/newproject/SummaryControllerTest.kt#L30) は`ExperimentalCoroutinesApi`をopt-inせず`advanceUntilIdle()`等を使うため、Kotlinコンパイラ警告を14件出します。テスト結果には影響しませんが、本当に新しい警告を見つけにくくします。

CIはLint警告数とKotlinコンパイラ警告数の増加を失敗扱いにしません。現行件数を固定するbaseline／差分検査、または対象を整理した後の`warningsAsErrors`が必要です。

---

## 5. 強い部分

### 非同期状態の境界

`NoteSessionCoordinator`がノート／Vault切替を一手に引き受け、`NoteUiStateStore`が機能別WriterだけをControllerへ渡します。Job停止と状態リセットが同じ合流点にあり、Vault単位の処理はURI比較ではなく世代番号で照合します。A→B→Aの再選択でも古い要求を識別できる設計です。

### データ保護

[`DistillWriteRepository`](../app/src/main/java/com/example/newproject/data/DistillWriteRepository.kt#L103) は、入力検証、保存直前の競合再確認、ステージファイル、復旧レコード、フェーズ更新、書込後ハッシュ検証まで実装しています。SAFがrenameの原子性を保証しない前提で、ユーザーノートを書き換える機能に必要な堅牢性を確保しています。

### 入力量の境界

表示1MB、候補スニペット8KB、蒸留256KBという用途別入口に分かれ、呼び出し側が任意の巨大上限を渡せません。AI入力も`NoteExcerpt`型を経由し、長文では見出し骨格・冒頭・末尾を予算内に収めます。前回の無制限読込は解消済みです。

### パッケージと状態所有

`model`をプロジェクト内依存の葉にし、`ai → model`、`domain → model, ai`、`data → model, domain`、`controller → model, data, domain, ai`、`ui → model, domain`の方向をテストで固定しています。単なるフォルダ整理から、破るとCIが落ちる境界へ進んだ点は大きな改善です。

### プライバシー

AndroidManifestにINTERNET権限はなく、AI処理はオンデバイスです。Vault URI、テーマ、当日履歴を含むSharedPreferencesはクラウドバックアップと端末移行から除外されています。蒸留復旧元は`noBackupFilesDir`に置かれます。

---

## 6. その他の継続課題

### アクセシビリティ

`contentDescription`、Role、pointer inputのsemantics、stale closure対策は丁寧です。一方、[`AppColorContrastTest`](../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L127) はライト配色の既知未達7件を「現在値」として固定しており、失敗させません。ライト側の弱い文字6色、緑ボタン輪郭、成功バッジはWCAG AA未達のままです。

### リリース設定

`assembleRelease`は成功しますが、生成物は`app-release-unsigned.apk`です。[`app/build.gradle.kts`](../app/build.gradle.kts#L6) には`buildTypes`、署名、R8／resource shrinking、instrumentation runner、Lint方針がありません。`applicationId`と`namespace`は`com.example.newproject`、`versionCode`は1、Java互換性は8のままです。

### AI状態とモデル準備

[`AICoreClient.checkAvailability()`](../app/src/main/java/com/example/newproject/ai/AICoreClient.kt#L59) は状態取得例外も`Unavailable`へまとめるため、非対応端末と一時エラーを区別できません。また、要約・クイズ・補記・蒸留が個別に`downloadModel()`を収集し、アプリ全体のsingle-flightはありません。SDK側の重複処理挙動を実機で確認し、必要ならモデル準備状態を共有する余地があります。

### Markdown仕様

番号付きリストは箇条書きへ正規化され、リンクは見た目だけでタップ不可です。画像、埋め込み、脚注、HTML、数式は専用対応していません。これは限定実装として明文化されていますが、AI入力用抜粋も同じパーサを使うため、手順番号は長文ノートのAI文脈からも失われます。

---

## 7. 前回レビューからの更新

前回の基準コミット`c62fb75`から、次を確認しました。

| 前回指摘 | 現在 |
|---|---|
| 補記一覧・削除がVault切替をすり抜ける | `vaultGeneration`、`listJob`、削除失敗件数で解消 |
| 検索中のフォルダ変更・フォルダ一覧後着 | 検索requestId、`foldersJob`、スコープ初期化で解消 |
| ReadingTraceが30回で止まる | `totalVisitCount`とschema v2で解消 |
| Markdown本文の無制限読込 | 表示1MB／候補8KB／蒸留256KBへ分離して解消 |
| パッケージ間4循環 | 共有型移動と`PackageDependencyTest`で解消 |
| ViewModelの依存内部生成・共有状態の無制限更新 | `NoteViewModelDependencies`、Coordinator、機能別Writerで改善 |
| SearchController／ViewModel調停テストなし | 状態と調停テストは追加。非null Uriを使う検索実行は未検証 |
| ReadingTrace索引の外部同期 | 未解決 |
| instrumentation testなし | 未解決 |
| ライト配色AA未達 | 未解決 |
| 正式ID・Lint警告・リリース構成 | 未解決 |

AI入力の先頭固定長切り出しは、型付きの見出し骨格＋冒頭＋末尾の抜粋へ改善されました。呼び出し側での`Dispatchers.Default`切替もテストで固定されています。ただし、同じMarkdown解析のUI表示経路はMainに残っており、今回の新しい最優先指摘です。

---

## 8. 推奨する実施順序

1. 通常／全画面の`NoteSectionModel`をMain外で1回だけ生成・共有し、長文表示の停止を解消する
2. 蒸留の復旧チェックを追跡Jobと世代管理へ載せ、分析との交差テストを追加する
3. AI補記ファイルの作成失敗時に後始末と保存後検証を行う
4. RepositoryのAndroid境界を小さいgatewayへ分け、検索・補記の世代照合をJVMテスト可能にする
5. SAF・Navigation・Activity再生成を通す最小のinstrumentation基盤を作る
6. ReadingTrace索引の再走査契機と孤児整理方針を決める
7. ライト配色のAA未達をグレー階調整理と合わせて解消する
8. 正式`applicationId`、署名、release build type、R8、Lint／Kotlin警告予算を整備する
9. 完成プロンプトのトークン計測後に関連ノートの抜粋予算を調整する

最初の3件は既存設計を崩さず局所的に直せます。特に、**「表示用MarkdownをMain外で1回だけ解析する」**対応は、既にAI入力側で確立したdispatcher注入とソース走査テストの考え方を再利用でき、効果が明確です。

---

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

> **追記方針:** 2026-07-28の評価は対象コミット`ac53749`のスナップショットとして上に保存し、
> ここから下を`efc4ebd`時点の独立した再評価とする。

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

- 通常表示は [`NoteReaderTab`](../app/src/main/java/com/example/newproject/ui/screen/NoteReaderTab.kt#L103) の`remember`内で`buildNoteSectionModel()`を同期実行する
- 全画面表示は [`FullscreenNoteScreen`](../app/src/main/java/com/example/newproject/ui/screen/FullscreenNoteScreen.kt#L125) で同じ本文を再解析する
- 解析結果はComposableをまたいで共有されない
- 表示用本文は最大1MBまで許可される

AI入力用の重い抜粋生成はMain外へ移っていますが、表示経路には適用されていません。大きなノートを開く時と全画面へ入る時に、UI停止が起こり得ます。

**推奨:** ノート読込後に`Dispatchers.Default`で`NoteSectionModel`を1回だけ生成し、通常表示と全画面表示で共有する。200KB・1MBの解析時間とキャンセルを計測し、Composableからの同期呼び出しをソース走査テストで禁止する。

---

## 3. 中優先度の指摘

### P2-1. instrumentationスモークテストはAndroid 16で2件とも起動前に失敗する

[`InstrumentationSetupTest`](../app/src/androidTest/java/com/example/newproject/InstrumentationSetupTest.kt#L26) にはContext取得とCompose描画の2件があり、[`assembleDebugAndroidTest`](../.github/workflows/ci.yml#L40) もCIへ追加されています。しかし、`connectedDebugAndroidTest`をAndroid 16エミュレータで実行すると、2件とも次の例外で失敗しました。

```text
NoSuchMethodException: android.hardware.input.InputManager.getInstance []
```

失敗はテスト本体へ入る前のCompose／Espressoアイドル待機初期化で発生します。Contextだけを見るテストにもクラス共通の`createComposeRule()`が適用されるため、2件とも同じ理由で止まります。[`build.gradle.kts`](../app/build.gradle.kts#L104) の依存とRunnerはコンパイル可能ですが、「Runnerが起動しComposeを描画できる土台」は実証できていません。

また、CIはandroidTest APKを組み立てるだけで、エミュレータ上の実行は行いません。したがって、この失敗は現行CIでは検出されません。

**推奨:** 対象APIで動くAndroidX Test／Espressoの組み合わせを確定する。ContextだけのテストとComposeルールを使うテストを別クラスへ分け、少なくとも1つの管理対象エミュレータで`connectedDebugAndroidTest`を継続実行する。

### P2-2. 蒸留の復旧チェックだけJob・requestId管理の外にある

前回指摘は未解決です。[`DistillController.checkRecovery()`](../app/src/main/java/com/example/newproject/controller/DistillController.kt#L334) は、`scope.launch`したJobを保持せず、要求世代も採番しません。通常の分析・保存が`activeRequestId`で保護される一方、復旧確認の後着は`RecoveryRequired`を直接書き込みます。

保存直前には未解決復旧レコードを再確認するため、危険な書き込みは止まります。ただし、進行中の分析と復旧表示が互いに上書きし、不要なAI実行や状態遷移の混乱を起こし得ます。

**推奨:** 復旧確認を追跡Jobと世代管理へ載せ、「遅い復旧確認」と「進行中分析」を交差させるテストを追加する。

### P2-3. AI補記は書込失敗時に空・部分ファイルを残し得る

前回指摘は未解決です。[`NoteRepository.createAnnotationFile()`](../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L197) は`createDocument()`後に作成済みURIへ直接書き込み、失敗時の削除と保存後の再読込検証を行いません。

そのため、画面には生成失敗と表示されても、`_AI補記`には空または途中までのファイルが残る可能性があります。

**推奨:** 作成後の書込失敗時に作成済みURIをベストエフォートで削除し、保存後に最低限のサイズ／本文検証を行う。Android APIを小さいgatewayへ分け、失敗注入をJVMテスト可能にする。

### P2-4. ReadingTraceの外部同期・改名・孤児管理は未解決

[`SafReadingTraceDocumentGateway`](../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L135) は、[`folderIndexOf()`](../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L222) で作ったVault内索引をプロセス中無期限に再利用します。自身が作成したファイルは索引へ追加しますが、外部同期で後から増えたファイルは再起動まで認識しません。

相対パスのハッシュをキーにするため、ノートのrename／move／delete後には旧痕跡が孤児になります。

**推奨:** 索引ミス時の1回再走査、短いTTL、または明示無効化を導入する。孤児は同期途中の誤削除を避け、猶予期間付き清掃か手動整理とする。

### P2-5. releaseは組み立てられるが公開可能な成果物ではない

[`build.gradle.kts`](../app/build.gradle.kts#L41) にrelease build typeが追加され、`applicationId`も`com.vigilith.ai`へ確定しました。一方で次は未完です。

- 生成物は`app-release-unsigned.apk`
- `isMinifyEnabled = false`でR8未適用
- release署名設定なし
- ML Kit GenAIを含む縮小後の実機検証なし

**推奨:** 署名情報をリポジトリ外から注入し、release署名、R8／resource shrinking、オンデバイスAIを含むrelease実機スモーク確認を一組で整備する。

### P2-6. 関連ノートの抜粋予算は注意書きの固定費が大きい

[`NoteExcerptLimits.RELATED`](../app/src/main/java/com/example/newproject/model/NoteExcerptLimits.kt#L12) は600文字のままです。長文時は共通注意書きもこの予算から支払うため、現ノート本文へ使える領域が小さくなります。

**推奨:** 完成プロンプトの実トークン数を測り、関連ノート専用の短い注意書きか上限変更を判断する。候補ブロックとの合算を見ずに単独で増やさない。

---

## 4. D案・E案の再評価

### D案: 配色とアクセシビリティは大幅に改善した

今回の改善は単なる色値の変更に留まりません。

- [`AppColorContrastTest`](../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L181) が、文字トークンを実際に載る面との組み合わせで検証する
- 半透明面は合成後の実効背景で検証する
- グラデーション停止色は`AppColorScheme`のリストを実装とテストで共有し、全停止色を総当たりする
- [`VibrantTextUsageTest`](../app/src/test/java/com/example/newproject/ui/theme/VibrantTextUsageTest.kt#L41) が、背景を持たない画面からの`OnVibrant`直接使用と文字色への任意alpha適用を禁止する
- [`GradientHeader`](../app/src/main/java/com/example/newproject/ui/component/GradientHeader.kt#L50) はライトで白いヘイズ＋濃色文字、ダークで透明面＋明色文字を使い分ける

前回の「既知未達7件を実測値として固定するだけ」という状態からは明確に前進しています。文字と主要な部品の組み合わせは基準を強制するテストへ移りました。

ただし、[`AppColorContrastTest`](../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L287) は、ライトのナビ帯上でSuccess／Errorバッジの塗りが3:1を満たさないことを、意図的に「未達の記録」として残しています。中の記号は読めますが、塗り自体の判別基準は未達です。したがって、**「ライト配色は例外なくAA準拠」とはまだ言えません。**

また、`GradientHeader`の全幅化は呼び出し側の水平余白と`horizontalBleed = 20.dp`が一致する前提です。現画面では成立していますが、画面余白を変える際の手動契約であり、レイアウトテストはありません。

### E案: 品質ゲートは成立したが、実行・公開工程は未完

[`build.gradle.kts`](../app/build.gradle.kts#L8) ではKotlinの`allWarningsAsErrors = true`、Lintの`warningsAsErrors = true`と`abortOnError = true`が有効です。今回の再実行でもLint 0件、Kotlin警告0件を確認しました。baselineで既存警告を隠さず、増加をビルド失敗にする方針は適切です。

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

併せて、[`build.gradle.kts`](../app/build.gradle.kts#L101) の「テスト本体はまだ無い」というコメントは現在の実装と一致しないため、次回のコード変更時に修正する。[`GradientHeader`](../app/src/main/java/com/example/newproject/ui/component/GradientHeader.kt#L50) の`horizontalBleed = 20.dp`と呼び出し側余白の手動契約は既知の軽微な保守課題とし、今回の完了を妨げる問題にはしない。

---

# ソースコード品質レビュー再評価（2026-08-01・No.9）

**評価日:** 2026-08-01

**対象ブランチ:** `feature/Improvement_Function_No.9`

**基準HEAD:** `4c269df`（本レビュー文書の未コミット更新を除く）

**対象範囲:** `app/src/main`、`app/src/test`、`app/src/androidTest`、Gradle設定、AndroidManifest、GitHub Actions、設計文書・課題台帳との整合

**検証コマンド:**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest assembleRelease --rerun-tasks
```

> **追記方針:** 2026-07-28・2026-07-31の評価は各対象コミットのスナップショットとして保存し、
> ここから下を`4c269df`時点の独立した再評価とする。過去節に残る「未解決」は、当時の評価として
> 読むこと。現在の状態は本節を正とする。

---

## 1. 結論

現状は、**単一ViewModelのAndroidアプリとしては責務分割、状態所有、非同期要求の世代管理、純粋ロジックのテストがかなり強く、全面的な再設計を要しない**状態です。2026-07-31時点の最優先課題だった表示用MarkdownのMain上二重解析、instrumentation基盤の不動作、蒸留復旧の要求管理外、AI補記の失敗時後始末は、実装・回帰テスト・実機確認まで進みました。`DocumentRef`と`VaultBrowser`の導入により、`model`・`domain`・`controller`からAndroid依存も排除されています。

一方、新しい読書痕跡の手動整理には、**削除直前の走査が読めなかった場合を三値で扱えておらず、Vaultルート読取失敗時に生きた痕跡を削除し得る問題**が1件あります。Markdownノート本体を削除する経路ではなく、対象は`_ReadingTraces`の補助JSONだけですが、機能が掲げる「不在は証明ではない」という安全原則には反します。また、遮断器のルート混在ケースと連続削除のJob競合に、中優先度の穴が残ります。

- 本番Kotlin: **103ファイル・16,266行**
- JVMテスト: **60ファイル・10,692行・564件**
- JVMテスト結果: **564 success / 0 failure / 0 error / 0 skipped**
- Android instrumentation: **3ファイル・7テスト宣言**
  - 内訳は土台のスモーク2件と、Nano対応端末で使うプロンプト計測・診断5件
  - 今回はAPK組み立てまで。土台のスモーク2件は2026-08-01の既存記録でAndroid 16上 **2/2成功**
- Android Lint: **0 errors / 0 warnings / 12 hints**（依存更新系のみinformational）
- Kotlinコンパイル警告: **0件**（`allWarningsAsErrors`で固定）
- リリース組み立て: **成功**（`app-release-unsigned.apk`、約10MB）
- androidTest APK組み立て: **成功**（約976KB）
- CI: JVMテスト、Lint、androidTest APK組み立てを実行
- 作業ツリー: レビュー開始時点でクリーン

総合評価は **8.5 / 10** です。前回の8.1から上げたのは、P1だったUI性能問題と三つの信頼性／テスト境界が解消し、Android境界を型とアーキテクチャテストで固定できたためです。9点台にしないのは、手動整理のP1、実機統合テストの不足、入力予算の未閉包、外部同期索引、署名・R8が残るためです。

| 評価軸 | 評価 | 前回比 | 要点 |
|---|---:|---:|---|
| 可読性 | 8.5 | +0.5 | 責務・命名・KDocは良好。500行級のController／画面は残る |
| 保守性 | 8.8 | +0.8 | Android境界と依存方向を型・テストで固定。設計判断も追跡可能 |
| 拡張性 | 8.3 | +0.8 | Controller追加の型は明確。Coordinatorの委譲口と寿命登録は手動 |
| 信頼性 | 8.3 | -0.2 | 既存の保存・世代管理は強いが、孤児削除の再検証にP1が残る |
| テスト容易性 | 9.0 | +1.0 | 564 JVMテスト、dispatcher／gateway注入、依存制約テストが機能している |
| 並行処理 | 8.3 | +0.3 | 主要要求はJob＋世代で保護。痕跡の連続削除だけ同一Job共有が危険 |
| 性能 | 8.4 | +1.4 | 表示MarkdownをMain外で1回化。痕跡索引・整理走査は件数比例 |
| セキュリティ・プライバシー | 9.0 | +0.5 | INTERNET権限なし、端末内AI、機微設定をバックアップ／移行から除外 |
| アクセシビリティ | 8.0 | ±0.0 | 文字と主要部品は強制済み。ナビ帯上のバッジ塗りは既知未達 |
| 開発・リリース運用 | 8.1 | +0.6 | 警告ゲートとandroidTestコンパイルがCI化。署名・R8・実行ゲートは未完 |
| ドキュメント | 8.3 | +0.3 | 判断理由は非常に豊富。件数・対象ブランチのスナップショットずれがある |

**P0相当の問題、確認済みのMarkdownノート消失経路、ネットワークへの本文送信は見つかりませんでした。** 現在のP1は補助的な読書痕跡JSONに限定されます。

---

## 2. 優先度の高い指摘

### P1-1. 削除直前の再走査が「存在・不在・判定不能」の三値になっていない

[`ReadingTraceCleanupController.runDelete()`](../app/src/main/java/com/example/newproject/controller/ReadingTraceCleanupController.kt#L190) は削除直前にVaultを再走査する点自体は正しいものの、判定を`stillMissing: Boolean`へ潰しています。

特に、`scan.unreadableFolderPaths`がルートを表す`""`を含み、対象が`ideas/note.md`のようなネストパスの場合、[`isUnderUnreadableFolder()`](../app/src/main/java/com/example/newproject/domain/ReadingTraceOrphans.kt#L170) は`"ideas".startsWith("/")`を満たさず`false`になります。その結果、ノートが1件も見えないルート読取失敗でも`stillMissing = true`となり、生きている可能性のある痕跡JSONを削除します。洗い出し時にはルート読取失敗を`Blocked`へできているため、削除直前だけ安全規則が弱くなっています。

また、通常のサブフォルダ読取失敗は削除こそ止まるものの、`NOT_ORPHAN_ANYMORE`へ畳まれ、候補が画面から消えます。「ノートが再出現した」と「確認できなかった」が同じ状態になっているため、ユーザーは再試行できません。

**推奨:** 再確認を`PRESENT / MISSING / INDETERMINATE`の三値にする。ルート読取失敗は全パスを`INDETERMINATE`とし、対象の祖先が読めない場合も同じ扱いにする。`INDETERMINATE`では削除せず候補を残し、失敗または保留を表示する。次の回帰テストを追加する。

- ネスト候補＋`unreadableFolderPaths = setOf("")`で削除されない
- 対象フォルダが読めない場合、候補が一覧に残る
- ノートが実際に再出現した場合だけ候補から外れる

---

## 3. 中優先度の指摘

### P2-1. ルート直下の遮断器が、別サブツリーの遮断器を消してしまう

[`assessReadingTraceOrphans()`](../app/src/main/java/com/example/newproject/domain/ReadingTraceOrphans.kt#L103) は、同じフォルダ／祖先から複数候補が出た場合に安全側へ保留する設計です。しかし、`blocked`にルート`""`と`"ideas"`が同時に入ると、[`shallowest`](../app/src/main/java/com/example/newproject/domain/ReadingTraceOrphans.kt#L110)の選別で`""`が`"ideas"`の祖先と扱われ、`"ideas"`が落ちます。

一方、ネスト候補の`breakerGroupPaths()`はルートを含めません。たとえばルート直下で2件、`ideas/`配下で2件が同時に欠けると、ルート直下だけが保留され、`ideas/`の2件は孤児候補として出ます。ルートは「直接の親としてのグループ」であり、ネスト候補の共通祖先ではないという設計コメントと実装が、この混在時だけ一致していません。

**推奨:** ルートブロックは非ルートブロックを包含しない特別値として扱う。上記4件の混在ケースを回帰テストへ追加する。

### P2-2. 連続削除が同じJobを奪い合い、物理削除と画面状態がずれ得る

[`ReadingTraceCleanupController`](../app/src/main/java/com/example/newproject/controller/ReadingTraceCleanupController.kt#L54) は洗い出しと削除に同じ`job`を使い、[`delete()`](../app/src/main/java/com/example/newproject/controller/ReadingTraceCleanupController.kt#L158)のたびに前のJobをキャンセルします。SAFの`deleteDocument()`は同期的な外部I/Oなので、キャンセル時点ですでに物理削除だけ完了し、その後の状態更新が落ちる可能性があります。

さらに各削除は開始時の`current`を捕捉します。AとBを素早く押すと、AのJSONは消えたのに画面に残り、B完了時には古い一覧を基にBだけを外す、というずれが起こり得ます。再洗い出しで自己修復しますが、「削除に失敗したように見える」状態になります。

**推奨:** `assessmentJob`と削除処理を分け、削除は`Mutex`または単一キューで直列化する。実行中は同じ候補のボタンを無効化し、完了時は捕捉した`current`ではなく最新状態から対象を除く。連続2件、洗い出し中の削除、Vault切替中の削除をテストする。

### P2-3. AI入力の「用途別抜粋」は強いが、完成プロンプトの上限にはなっていない

本文の抜粋上限と実機トークン計測はよく設計されています。しかし、[`SectionChatController.sendMessage()`](../app/src/main/java/com/example/newproject/controller/SectionChatController.kt#L90) は会話履歴を全件渡し、ユーザー質問にも長さ上限がありません。また、[`renderCandidatesWithinBudget()`](../app/src/main/java/com/example/newproject/domain/RelatedCandidateContext.kt#L63)の最終フォールバックはIDとタイトルを全件残し、`fits()`を再確認しません。長いタイトル群だけで候補予算を超える場合があります。

これは[`PromptTokenBudgetTest`](../app/src/androidTest/java/com/example/newproject/ai/PromptTokenBudgetTest.kt#L40)自身も「定義済み計測プロファイルの回帰であり、上限保証ではない」と正しく明記しています。つまり認識はされていますが、本番側の閉じた制約にはまだなっていません。

**推奨:** 完成プロンプト単位の入力予算を1か所で強制し、履歴は直近N件または文字予算で切る。関連候補はタイトルのみでも超える場合に候補数またはタイトル表示長を減らし、最終返却前の`fits()`を必須にする。

### P2-4. ReadingTrace索引は外部同期による追加をプロセス中に自動認識しない

[`SafReadingTraceDocumentGateway`](../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L210) は完全に読めたkey→実体一覧をキャッシュし、自身の作成・削除は反映します。しかしTTLや外部変更通知がないため、別端末から同期された痕跡は再起動または手動整理の再列挙まで見えません。重複実体をリストで保持し一括して消す修正は妥当ですが、外部追加の最終的な認識は未解決です。

**推奨:** 索引ミス時の一度だけの再走査、短いTTL、またはプロバイダ変更通知を検討する。これは現行台帳のSYNC-2と一致する。

---

## 4. 前回指摘の解消確認

| 2026-07-31時点の指摘 | 現在の状態 |
|---|---|
| 表示用MarkdownをMain上で2回解析 | **解消。** `NoteSectionController`が`Dispatchers.Default`で1回生成し、通常／全画面で共有。ソース走査テストあり |
| instrumentationがAndroid 16で0/2 | **解消。** ContextとComposeを別クラスに分離し、依存を限定更新。既存実機記録で2/2成功 |
| 蒸留の復旧確認がJob・世代管理外 | **解消。** 追跡Jobと要求照合へ統合し、交差テストを追加 |
| AI補記が失敗時に空・部分ファイルを残す | **解消。** 専用gateway、失敗時後始末、失敗注入テストを追加 |
| ReadingTraceの孤児管理なし | **部分解消。** 明示的な1件削除、直前再走査、遮断器、壊れた候補の保留を追加。ただし本レビューのP1／P2が残る |
| 関連ノート抜粋600文字 | **判断済み。** 実機でトークン余裕を計測し800文字へ限定的に増加 |
| Android型が下位層へ侵入 | **解消。** `DocumentRef`／`VaultBrowser`へ集約し、`model`・`domain`・`controller`のAndroid依存をテストで禁止 |
| Lint更新系チェックを無効化 | **改善。** `informational`へ変更し、12 hintsを可視化しながらゲートから分離 |

前回の主要な実装課題はほぼ解消し、ReadingTraceは機能追加まで進んだうえで新しい境界ケースが残った、という評価です。

---

## 5. コード全体の健全性

### 強い部分

- **依存方向:** `model`を葉とし、`domain`・`controller`をAndroid非依存にした境界が明確です。完全修飾名による抜け道まで[`PackageDependencyTest`](../app/src/test/java/com/example/newproject/architecture/PackageDependencyTest.kt#L81)で検出します。
- **状態所有:** `NoteUiStateStore`だけが状態を持ち、Controllerには機能別`*StateWriter`を渡します。担当外状態の更新を型で防いでいます。
- **非同期要求:** ノート単位はJob＋requestId、Vault単位は`vaultGeneration`、痕跡はVault識別子まで保持します。キャンセル例外を再送出する規律も主要Controllerで揃っています。
- **データ保護:** 本文書き換えを行う蒸留は原バイト、二重ハッシュ照合、復旧レコードを持ちます。補記は作成後の書込失敗を後始末し、ReadingTraceの失敗はMarkdown本体へ波及しません。
- **I/O境界:** 本文読込は上限付き、SAF走査は不完全と空を型で区別し、遠いプロバイダの同期I/OをMainから外しています。
- **テスト設計:** gateway・dispatcher・時計・状態writerを注入でき、純粋ロジックだけでなく競合、世代、失敗注入、依存方向、スレッド境界をJVMで検証しています。件数の多さより、壊し方を狙ったテストがある点を評価します。
- **プライバシー:** Manifestに`INTERNET`権限がなく、AIは端末内です。Vault URI・閲覧タイトルを持つSharedPreferencesはクラウドバックアップと端末移行の両方から除外しています。

### 継続する構造的な負債

- 本番最大ファイルは`AiTab.kt` 521行、`DistillController.kt` 516行、`NoteViewModel.kt` 502行です。直ちに分割すべき閾値ではありませんが、機能追加時は既存責務へ足すより、表示部品・純粋変換・境界処理へ切り出す方針を維持すべきです。
- `NoteSessionCoordinator`は9 Controllerの委譲口として安定していますが、新規Controller追加時のジョブ寿命・Vault切替・ノート切替への登録は手動契約です。今後10本を大きく超えるなら、ライフサイクルインターフェースの導入を検討できます。
- YAML／Markdownは意図的な限定実装で、番号付きリスト、ネスト、リンク、画像、数式などを完全には保持しません。特に番号付き手順の番号はAI抜粋でも失われます。
- 走査キャッシュの60秒TTL、ReadingTrace索引の件数比例、`GradientHeader`の20dp bleed契約は、現時点では低優先度の既知制約です。

---

## 6. テスト・静的解析・CIが保証する範囲

### 現在保証できること

- JVMテスト564件が全件成功し、失敗・エラー・スキップはない
- Markdown、AI応答パース、候補採点、Controller、蒸留、ReadingTrace JSON／孤児判定、配色などの純粋・調停ロジックを広く覆う
- パッケージ依存、Android非依存層、表示Markdownのdispatcher、配色面対応をソース走査テストで固定する
- Kotlin警告と、依存更新系以外のLint警告は新しく増やせない
- debug unit test、release APK、androidTest APKは同じHEADから組み立てられる
- CIはJVMロジック、Lint、androidTestソースのコンパイル崩れを検出する

### 現在保証できないこと

- CI上でのinstrumentation実行。現在はAPKを組み立てるだけ
- SAFプロバイダの部分列挙、外部同期、削除のブロッキングとCoroutineキャンセルが交差する実挙動
- Compose Navigation、画面回転、Activity／プロセス再生成を含む機能統合
- TalkBack、キーボード、複数画面幅、全状態のライト／ダークUI回帰
- Nano非対応・ダウンロード中・一時失敗、release＋R8後のGemini Nano動作
- 行／分岐カバレッジ率と、実Vault規模での走査時間

テスト数564件は強い材料ですが、P1-1のような「洗い出し時にはあるテストが、削除直前の同型処理には無い」という重複境界を自動的には保証しません。安全判定を純関数へ一元化することが、単純なテスト追加より再発防止に効きます。

---

## 7. その他の継続課題

| 領域 | 状態 | 評価への反映 |
|---|---|---|
| 実機統合 | 土台2件は成功済みだが、SAF・Navigation・再生成を通すテストがない | テスト容易性／運用 |
| AI状態 | `checkAvailability()`の例外を非対応と同じ`Unavailable`へ畳む | 信頼性／UX |
| Markdown／YAML | 限定構文。外部編集の反映は最大60秒遅れる | 拡張性 |
| A11Y | ライトのナビ帯上でSuccess／Errorバッジ塗りが3:1未達 | アクセシビリティ |
| release | 未署名、`isMinifyEnabled = false`、縮小後の実機確認なし | 開発・リリース運用 |
| 依存 | 12件が旧版。方針・調査は済み、更新系Lintはhintとして可視化 | 保守性 |
| 性能 | `_ReadingTraces`の索引作成と整理走査がファイル数に比例 | 性能 |
| 文書整合 | `source_code_analysis.md`と`current_issues.md`のテスト件数・対象ブランチが現行564件／No.9とずれる | ドキュメント |

---

## 8. コミット判断と推奨順序

**現状をコミットすること自体を止めるP0はありません。** 既知のP1は読書痕跡の手動整理を実行したときだけ到達し、Markdownノート本体ではなく補助JSONに限定されます。ただし、整理機能を安全策として信用する前にP1-1を直すべきです。少なくとも本レビューを同じコミットに含め、既知問題を記録した状態にします。

推奨順序は次のとおりです。

1. 削除直前判定を三値化し、ルート読取失敗とサブツリー読取失敗を`INDETERMINATE`として候補に残す
2. ルート直下／ネストの遮断器混在テストを追加し、グループの包含規則を修正する
3. 痕跡削除を専用Jobまたはキューで直列化し、連続操作の状態更新を最新値基準にする
4. 会話履歴・質問・候補タイトルを含む完成AI入力にハード上限を置く
5. SYNC-2の索引再走査契機を設計し、外部同期プロバイダで確認する
6. SAF・Navigation・Activity再生成を通す少数のinstrumentationテストを追加する
7. 配布時に署名・R8・resource shrinking・release実機確認を一組で行う

最初の3件はいずれも読書痕跡整理の局所修正です。既存アーキテクチャを崩さず、純関数化とControllerテストの追加で閉じられます。
