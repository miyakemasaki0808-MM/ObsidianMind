# ソースコード品質レビュー

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）

**評価日:** 2026-07-26

**対象ブランチ:** `feature/Improvement_Issue_No.2`

**対象コミット:** `c62fb75`

**対象範囲:** `app/src/main`、`app/src/test`、Gradle設定、AndroidManifest、GitHub Actions

**検証コマンド:** `./gradlew testDebugUnitTest --rerun-tasks`、`./gradlew lintDebug`

> **この文書の位置づけ:** 現在の実装に対する品質評価のスナップショット。
> 機能・構成の網羅的な説明は [source_code_analysis.md](source_code_analysis.md)、
> 課題の進行管理は [_wip/current_issues.md](_wip/current_issues.md) が担う。

---

## 1. 結論

現状は、**純粋ロジックとVault書き戻しの安全性は強い一方、Vault単位の非同期状態管理とアーキテクチャ境界が弱い**状態です。
全面的な作り直しは不要ですが、次の機能追加より先に、旧Vaultの状態が後着する経路とReadingTraceの累計回数を修正する価値があります。

- 本番Kotlin: **70ファイル・12,535行**
- JVMテスト: **43ファイル・6,007行・379件**
- テスト結果: **379 success / 0 failure / 0 skipped**
- Android Lint: **0 errors / 28 warnings**
- Android instrumentation test: **0件**
- CI: PRと`main`へのpushでJVMテストとLintを実行
- 作業ツリー: レビュー開始時点でクリーン

総合評価の目安は **7.2 / 10** です。379件のテスト、蒸留の復旧設計、AI呼び出しの直列化、CI導入は高く評価できます。一方、テスト件数だけでは捕捉できていない高優先度の状態競合が残っています。

| 評価軸 | 評価 | 要点 |
|---|---:|---|
| 可読性 | 7.5 | 命名・KDoc・責務コメントは良好。大きな統合クラスが残る |
| 保守性 | 6.5 | パッケージ間循環、共有UiState、依存の内部生成が変更範囲を広げる |
| 拡張性 | 6.5 | Controller追加は容易だが、状態と依存の境界が型で守られていない |
| 信頼性 | 7.0 | 蒸留は非常に堅牢。Vault切替とReadingTraceに実害のある境界不備が残る |
| テスト容易性 | 7.5 | 純粋ロジックは強いが、ViewModel・検索Controller・SAF・Navigationが未検証 |
| 並行処理 | 7.0 | 主要AI処理は保護済み。Vault単位の一覧取得・削除は未追跡 |
| 性能 | 6.5 | キャッシュ・候補上限はあるが、Markdown本文の無制限読込が残る |
| セキュリティ・プライバシー | 8.5 | オンデバイスAI、INTERNET権限なし、SharedPreferencesのバックアップ除外 |
| アクセシビリティ | 6.5 | semanticsは丁寧だが、ライト配色に既知のコントラスト未達がある |
| 開発・リリース運用 | 7.0 | CI導入済み。Lint警告の増加防止、実機テスト、正式IDは未整備 |
| ドキュメント | 8.5 | 設計判断と制約が詳しく、実装への参照も多い |

---

## 2. 優先度の高い指摘

### P1-1. Vault切替時に旧Vaultの一覧処理が残り、補記の誤削除へ進める

生成処理のキャンセルは整っていますが、Vaultに属する「一覧」のJobは追跡されていません。

- [`AnnotationController.cancelAndClear()`](../app/src/main/java/com/example/newproject/controller/AnnotationController.kt#L122) が止めるのは生成・モデルDLだけで、補記一覧の読込・削除Jobは対象外
- [`loadList()`](../app/src/main/java/com/example/newproject/controller/AnnotationController.kt#L133) は取得開始時のVault URIを保持したまま、無条件で共有UiStateへ結果を書き戻す
- [`NoteViewModel.saveVault()`](../app/src/main/java/com/example/newproject/NoteViewModel.kt#L154) は `annotationListState` を初期化しない
- [`deleteAll()`](../app/src/main/java/com/example/newproject/controller/AnnotationController.kt#L163) は現在UiStateにあるURIをそのまま削除する
- [`deleteDocument()`](../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L206) の成否も呼び出し側で確認していない

したがって、Vault Aの一覧取得中にVault Bへ切り替えると、Aの一覧がBの状態へ後着できます。その状態で「すべて削除」を実行すると、Aの永続URI権限が残っている端末では、表示上の現在VaultがBでもA側を削除し得ます。

**推奨:** 一覧・削除にもJobとVault世代IDを持たせ、`saveVault()`でキャンセル、世代更新、`annotationListState = Idle`を同時に行う。削除はBoolean結果を集計し、失敗件数を表示する。この競合を遅延可能なFakeで固定するテストを追加する。

### P1-2. 検索の要求世代管理は入ったが、フォルダ変更と一覧取得は保護されていない

キーワード検索とランダム取得には、Job・request ID・`CancellationException`の再送出が追加され、連続検索による古い結果の上書きは解消しています。

ただし、次の2経路は保護の外です。

1. [`loadFolders()`](../app/src/main/java/com/example/newproject/controller/SearchController.kt#L60) のJobを保持していないため、Vault Aのフォルダ一覧がVault Bへの切替後に後着できる
2. [`selectFolder()`](../app/src/main/java/com/example/newproject/controller/SearchController.kt#L72) は進行中検索をキャンセルせず、`searchState`も初期化しない

画面側も、検索中は検索・ランダムボタンを無効化しますが、[`FolderChip`](../app/src/main/java/com/example/newproject/ui/screen/SearchScreen.kt#L92) は操作可能です。そのため、フォルダAの検索中にフォルダBへ切り替えると、選択表示はBのまま結果だけAになる可能性があります。`SearchController`を直接検証するテストもありません。

**推奨:** フォルダ一覧Jobと検索Jobを同じVault世代で検証する。`selectFolder()`では検索を無効化して結果を`Idle`へ戻すか、検索要求へスコープIDを含める。検索→フォルダ変更、Vault切替中の一覧後着をテストする。

### P1-3. ReadingTraceは31回目以降、累計回数とAI要約が更新されない

[`ReadingTrace.withVisit()`](../app/src/main/java/com/example/newproject/model/ReadingTrace.kt#L69) は訪問履歴を直近30件へ切り詰めます。一方、次の処理は保持件数 `visits.size` を累計回数として使っています。

- 要約再生成判定: [`needsAiSummary`](../app/src/main/java/com/example/newproject/model/ReadingTrace.kt#L78)
- カードの訪問回数と要約の鮮度判定: [`ReadingTraceController.cardOf()`](../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L357)
- 保存する要約時点: [`persistSummary()`](../app/src/main/java/com/example/newproject/controller/ReadingTraceController.kt#L393)
- AIプロンプトの「開いた回数」: [`PromptBuilder`](../app/src/main/java/com/example/newproject/ai/PromptBuilder.kt#L60)

30件到達後は `visits.size` が増えないため、表示は常に30回となり、30件時点の要約が「最新」と判定され続けます。既存テストは履歴の切り詰めと30件未満での要約更新を個別に確認していますが、上限到達後の再訪を組み合わせていません。

**推奨:** `totalVisitCount`を履歴保持数と分離し、要約の鮮度も累計値で判定する。schema v2へ上げ、v1は少なくとも`visits.size`を初期累計値として移行する。31回目で要約が再生成される回帰テストを追加する。

### P1-4. Markdown本文の無制限読込が、大きなVaultでメモリ上限を無効化する

蒸留用の読込には256KB基準と境界検査がありますが、汎用の [`readNoteContent()`](../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L133) は `readText()`でEOFまで読みます。

この無制限経路は、

- 大きすぎるノートや不正UTF-8からの表示フォールバック（[`loadNoteForDistill()`](../app/src/main/java/com/example/newproject/NoteViewModel.kt#L448)）
- 関連ノート候補の本文読込（[`fetchRelatedNotes()`](../app/src/main/java/com/example/newproject/NoteViewModel.kt#L518)）

で使われます。関連ノート処理は候補40件、最大8並列まで制御していますが、[`RelatedNotesUseCase`](../app/src/main/java/com/example/newproject/domain/RelatedNotesUseCase.kt#L93) が各本文を丸ごと読んだ後で短いスニペットへ縮めます。巨大ファイルが混ざると、入力文字数の上限より前にメモリとI/Oを消費します。

**推奨:** 表示用、メタデータ・スニペット用、蒸留用で読込APIと上限を分ける。候補抽出は必要な先頭バイトだけをストリーム処理し、表示用にも明示的な最大サイズまたは段階読込を設ける。

---

## 3. 構造上の課題

### P2-1. レイヤー名のパッケージ間に4組の双方向依存がある

現在のimportを突合すると、少なくとも次の双方向依存があります。

| 循環 | 代表例 |
|---|---|
| `model ⇄ data` | [`NoteUiState`](../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L3) がdata型を参照し、[`ReadingTraceJson`](../app/src/main/java/com/example/newproject/data/ReadingTraceJson.kt#L3) がmodel型を参照 |
| `model ⇄ domain` | `NoteUiState`がdomain型を参照し、[`QuizResponseParser`](../app/src/main/java/com/example/newproject/domain/QuizResponseParser.kt#L3) がmodel型を参照 |
| `data ⇄ domain` | [`NoteRepository`](../app/src/main/java/com/example/newproject/data/NoteRepository.kt#L3) がdomain定数・関数を参照し、[`RelatedNotesUseCase`](../app/src/main/java/com/example/newproject/domain/RelatedNotesUseCase.kt#L4) がdata型を参照 |
| `domain ⇄ ai` | `RelatedNotesUseCase`などがAI実装側の型を参照し、[`PromptBuilder`](../app/src/main/java/com/example/newproject/ai/PromptBuilder.kt#L3) がdomain型を参照 |

パッケージ整理自体は可読性を上げていますが、依存方向はまだ境界として機能していません。コメント説明用の不要importは除去済みなので、上表は実使用による循環です。

**推奨:** 先に許可する依存方向を決める。`NoteFile`等の共有データ型、`AiClient`等のポート、`DistillLimits`等の境界定数の所属を整理し、ビルド時に依存規則を検査する。現段階でマルチモジュール化する必要はない。

### P2-2. `NoteViewModel`が構築・調停・状態所有を一手に担い、統合テストできない

[`NoteViewModel`](../app/src/main/java/com/example/newproject/NoteViewModel.kt#L58) は631行あり、Repository、AI client、UseCase、6 Controller、永続化実装を内部で直接生成しています。JVMテスト379件のうち、`NoteViewModel`を通るものはありません。

さらに、[`NoteUiState`](../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L210) は17フィールドを持ち、6 Controllerへ同じ `MutableStateFlow<NoteUiState>`を渡しています。各Controllerが担当外フィールドを書けないことは型では保証されません。[`MainActivity`](../app/src/main/java/com/example/newproject/MainActivity.kt#L81) もテーマ判定のためルートでUiState全体を購読します。

**推奨:** DIライブラリ導入より先に、Factoryまたはテスト用コンストラクタで依存を渡せるようにする。Controllerには機能別の状態更新インターフェースまたは状態スライスだけを渡す。`NoteUiState.kt`の機械的なファイル分割だけでは、所有権と再評価範囲は改善しない。

### P2-3. ReadingTraceの索引は外部同期による追加をプロセス中に認識しない

[`SafReadingTraceDocumentGateway`](../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L140) はVaultごとのファイル索引を一度構築し、[`folderIndexOf()`](../app/src/main/java/com/example/newproject/data/ReadingTraceStore.kt#L222) で無期限に再利用します。自身が作成したファイルは索引へ追加しますが、外部同期で後から増えた痕跡には無効化契機がありません。

**推奨:** 未ヒット時だけ再走査する、短いTTLを設ける、または監視で無効化する。同名キーが複数あった場合の採用規則も決める。

---

## 4. テスト・CI・静的解析

### 良い点

- JVMテスト379件はすべて実行成功し、失敗・スキップはありません。
- Markdown解析、AI応答パース、候補採点、キャッシュ、各主要Controller、ReadingTrace JSON、テーマコントラストなど、壊れやすい純粋ロジックを広く覆っています。
- 蒸留は障害注入を含むRepositoryテストがあり、保存前後のハッシュ、外部変更、復旧レコード、UTF-8、容量不足を検証しています。
- [CI](../.github/workflows/ci.yml#L16) がPRと`main`へのpushでテストとLintを実行し、失敗時もレポートを保存します。

### 保証していない範囲

- `app/src/androidTest`は存在せず、ContentResolver／実SAFプロバイダ、Compose Navigation、Activity再生成、実TalkBack、Gemini Nano実機挙動は未検証です。
- 検索の世代管理を直接検証する`SearchControllerTest`はありません。
- `NoteViewModel`の依存生成とController間調停を通るテストはありません。
- CIのLintは警告を失敗扱いにしないため、現在の28件から増えてもビルドは成功します。

Lint警告の内訳は次のとおりです。

| 種別 | 件数 |
|---|---:|
| `UseKtx` | 9 |
| `GradleDependency` | 7 |
| `UnusedResources` | 3 |
| `NewerVersionAvailable` | 3 |
| `UsableSpace` | 2 |
| `ConstantLocale` | 2 |
| `ObsoleteSdkInt` | 1 |
| `AndroidGradlePluginVersion` | 1 |

依存更新系11件は一括更新せず、ML Kit GenAIを含めて機能単位で更新・実機確認するのが安全です。警告数の増加防止にはLint baseline、レポート差分、または段階的な`warningsAsErrors`を検討できます。

---

## 5. 強い部分

### データ保護

[`DistillWriteRepository`](../app/src/main/java/com/example/newproject/data/DistillWriteRepository.kt#L103) は、入力検証、保存直前の競合再確認、ステージファイル、復旧レコード、書込後ハッシュ検証まで実装しています。SAFがrenameの原子性を保証しない前提に正面から対応しており、本プロジェクトで最も品質の高い部分です。

### AI境界

[`AICoreClient`](../app/src/main/java/com/example/newproject/ai/AICoreClient.kt#L47) は生成をMutexで直列化し、ロック取得後から60秒を計測し、トークン上限終了を明示エラーへ変換します。主要UseCaseとControllerは`CancellationException`を再送出し、キャンセルを通常エラーへ畳まない実装へ概ね統一されています。

### プライバシー

AndroidManifestにINTERNET権限はなく、AI処理はオンデバイスです。Vault URI、テーマ、当日履歴を含むSharedPreferencesは、[`backup_rules.xml`](../app/src/main/res/xml/backup_rules.xml#L12) と [`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml#L9) によりクラウドバックアップと端末移行から除外されています。旧レビュー時のバックアップ懸念は解消済みです。

### ドキュメント

制約、採用理由、劣化時の振る舞いがKDocと設計文書に残っています。単なる処理説明ではなく、「なぜその堅牢性レベルか」が記録されている点は、長期保守に有効です。

---

## 6. その他の継続課題

### アクセシビリティ

[`AppColorContrastTest`](../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L127) はライト配色の既知未達7件を、失敗させるのではなく現在値として固定しています。したがってテスト全件成功は、全配色のWCAG AA準拠を意味しません。ライト側の文字6色、緑ボタンの輪郭、成功バッジを是正する必要があります。

### エラー表現

[`SearchController.loadFolders()`](../app/src/main/java/com/example/newproject/controller/SearchController.kt#L60) は失敗を黙殺し、補記削除はBoolean結果を捨てています。また、[`AICoreClient.checkAvailability()`](../app/src/main/java/com/example/newproject/ai/AICoreClient.kt#L59) は状態取得例外も`Unavailable`へまとめるため、非対応端末と一時エラーを区別できません。

### リリース設定

[`app/build.gradle.kts`](../app/build.gradle.kts#L6) の `applicationId` と `namespace` は `com.example.newproject`、`versionCode`は1のままです。公開後に変更できない`applicationId`は、リリース準備の初期段階で正式値を決める必要があります。

---

## 7. 前回レビューからの更新

前回の基準コミット`439d23b`以降、次の改善を確認しました。

- 検索・ランダム取得にJobとrequest IDを導入し、古い要求の上書きを防止
- `CancellationException`を検索UseCaseとControllerで再送出
- コメント説明用だった未使用import 6件を削除
- PRごとのJVMテスト・Lint CIを追加
- SharedPreferencesをバックアップ・端末移行から除外
- JVMテストを378件から379件へ増加

一方、「検索の並行処理は解消済み」という評価は範囲を限定する必要があります。**検索要求同士**の追い越しは解消していますが、フォルダ選択変更とフォルダ一覧取得は未保護です。また、旧レビューの「バックアップ設定は要確認」「CIが存在しない」は現在は該当しません。

---

## 8. 推奨する実施順序

1. Vault切替時の補記一覧・削除・フォルダ一覧Jobを世代管理し、Vault単位の状態を必ず初期化する
2. 検索中のフォルダ変更をキャンセルまたはスコープIDで無効化し、競合テストを追加する
3. ReadingTraceへ`totalVisitCount`を追加し、31回目以降も表示・AI要約を更新する
4. Markdown本文読込へ用途別の上限を設け、関連候補は必要部分だけ読む
5. `NoteViewModel`の依存生成を外へ出し、Vault切替とController調停の統合テストを追加する
6. 許可するパッケージ依存方向を決め、4組の循環を段階的に解消する
7. SAF・Navigation・Activity再生成のinstrumentationテストを追加する
8. ライト配色のAA未達、Lint警告、正式`applicationId`をリリース前に整理する

最も費用対効果が高いのは、**「Vault世代をすべてのVault依存Jobへ通す」「履歴保持数と累計数を分ける」「読込量を入口で制限する」**の3点です。これらを先に直せば、既存のController分割や豊富な純粋ロジックテストを活かしたまま、実害のある境界不備を減らせます。
