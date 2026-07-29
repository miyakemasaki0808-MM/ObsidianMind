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
