# ソースコード品質総評

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）

**評価日:** 2026-07-26

**評価者:** Codex（外部レビュー）／同日 Claude が全指摘を実コードで突合・検証済み

**対象ブランチ:** `feature/Improvement_Function`（`439d23b` 時点）

**対象範囲:** `app/src/main`、`app/src/test`、Gradle設定、AndroidManifest

> **この文書の位置づけ:** ある時点の**採点結果のスナップショット**。事実の網羅は [source_code_analysis.md](source_code_analysis.md)、
> ここから起票した課題の追跡は [_wip/current_issues.md](_wip/current_issues.md)（1-1・2-9〜2-11・3-10〜3-13）が持つ。
> **本書は更新せず、次回レビュー時に新しい日付の総評を追記する**（点数の推移を残すため）。
>
> **突合結果（2026-07-26）:** 記載の数値・箇所はすべて実コードで確認でき、**誤りは無かった**。
> 未使用importのみ実測6件（本文は4件）で、`model/NoteUiState.kt` の `NoteHistoryStore` と
> `projectedBoldRatio` が追加。詳細は current_issues 3-10。

---

## 総評

現状は「機能品質と純粋ロジックのテストは強いが、アーキテクチャ境界と状態管理が次の成長限界」という状態です。全面的な作り直しは不要です。

- 本番コード: 70ファイル・約12,500行
- JVMテスト: 43ファイル・378件すべて成功
- Android Lint: 0 errors / 28 warnings
- 作業ツリー: クリーン

総合評価は **7.2 / 10** です。

| 評価軸 | 評価 | 要点 |
|---|---:|---|
| 可読性 | 7.5 | 命名・コメント・パッケージ整理は良好。大きなファイルが残る |
| 保守性 | 6.5 | パッケージ間循環と巨大な共有Stateが弱点 |
| 拡張性 | 6.5 | Controller追加は容易だが、機能追加時の変更範囲が広い |
| 信頼性 | 8.0 | 蒸留の復旧・競合検知・境界検証が非常に強い |
| テスト容易性 | 7.5 | 純粋ロジックは強いが、ViewModel・SAF・Navigationが未検証 |
| 並行処理 | 6.5 | 主要機能は保護済み。検索などに競合余地あり |
| 性能 | 7.0 | キャッシュ・上限・並列数制御あり。大規模Vaultでは走査が課題 |
| セキュリティ・プライバシー | 8.0 | SAF・オンデバイスAI・サイズ制限は良好。バックアップ設定は要確認 |
| アクセシビリティ | 6.5 | TalkBack対応は良いが、ライト配色に既知のAA未達あり |
| 開発・リリース運用 | 5.5 | CI・統合テスト・依存更新方針が不足 |
| ドキュメント | 8.5 | 判断理由が非常によく残されている |

## 重要な課題

### 1. パッケージは整理されたが、依存はまだレイヤー化されていない

現在は次の循環があります。

- `model → data`  
  [NoteUiState.kt](../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L3) が `NoteFile`、`NoteFolder`、`HistoryEntry` を参照
- `data → model`  
  [ReadingTraceJson.kt](../app/src/main/java/com/example/newproject/data/ReadingTraceJson.kt#L3) などが `ReadingTrace` を参照
- `domain → ai/data` と `ai → domain`  
  `RelatedNotesUseCase` がAI・データ型へ直接依存し、`PromptBuilder` はdomain型へ依存

さらに、コメントのためだけに不要なimportが残っています。

- [MarkdownBlocks.kt](../app/src/main/java/com/example/newproject/domain/markdown/MarkdownBlocks.kt#L3) → `NoteRepository`
- [NoteSections.kt](../app/src/main/java/com/example/newproject/domain/markdown/NoteSections.kt#L3) → UI
- [AnnotationComposer.kt](../app/src/main/java/com/example/newproject/domain/AnnotationComposer.kt#L3) → Controller
- [ReadingTrace.kt](../app/src/main/java/com/example/newproject/model/ReadingTrace.kt#L3) → JSON実装

したがって「ビジネスロジックからUIへの依存が完全に消えた」は、まだ厳密には成立していません。ただしコメント用importの除去だけなら、ロジック変更なしで直せます。

### 2. `NoteViewModel` が依然として統合の集中点

[NoteViewModel.kt](../app/src/main/java/com/example/newproject/NoteViewModel.kt#L58) は631行あり、以下を担当しています。

- 全依存オブジェクトの生成
- Vault・テーマ・キャッシュ
- 6 Controller間の調停
- ノート読み込みと再読込
- 要約・関連ノート・モデルDL
- 状態リセットとLifecycle連携

Controllerへの分離は効いていますが、依存を内部で直接生成しているため、`NoteViewModel` 自体をJVMテストしにくい構造です。

### 3. 状態管理の結合が大きい

[NoteUiState.kt](../app/src/main/java/com/example/newproject/model/NoteUiState.kt#L212) は17種類の状態型をまとめ、すべてのControllerが同じ `MutableStateFlow<NoteUiState>` を更新します。

問題はファイルサイズよりも次の2点です。

- Controllerが担当外フィールドも変更できる
- 小さな状態更新でも、ルートでUiState全体を購読する [MainActivity.kt](../app/src/main/java/com/example/newproject/MainActivity.kt#L83) から再評価される

当面は型を機能別ファイルへ分け、次段階で状態スライスや個別Flowを検討するのが妥当です。

### 4. 検索の並行処理に実害の余地がある

[SearchController.kt](../app/src/main/java/com/example/newproject/controller/SearchController.kt#L60) はJobやrequestIdを保持していません。

また、[例外処理](../app/src/main/java/com/example/newproject/controller/SearchController.kt#L77) が `CancellationException` も通常エラーとして捕捉します。

そのため連続検索時に、

- 古い検索結果が新しい検索結果を上書きする
- キャンセルがエラー表示へ変換される

可能性があります。ここは現在の構造上、最優先の実装改善候補です。

## 強い部分

特に評価できるのはデータ保護です。

[DistillWriteRepository.kt](../app/src/main/java/com/example/newproject/data/DistillWriteRepository.kt#L113) は、サイズ・UTF-8・空き容量・外部変更・復旧レコード・書込後ハッシュまで確認しています。障害注入テストもあり、この規模のアプリとしてかなり堅牢です。

AI部分も良好です。

- 呼び出しのMutex直列化
- ロック取得後からのタイムアウト
- トークン上限終了の検出
- ID選択方式による幻覚対策
- 候補数・入力文字数・並列読み込み数の上限

また、インターネット権限がなく、ノート処理がオンデバイスで完結する点も強いです。

## その他の評価

### テスト

378件は強力ですが、対象はJVMテストのみです。

未検証なのは、

- `ContentResolver`／実SAFプロバイダ
- Compose Navigation
- 画面回転・プロセス再生成
- 連続タップ時の競合
- 実際のTalkBack semantics
- Gemini Nano実機挙動

です。CI設定も存在しないため、PRごとの自動検証がありません。

### アクセシビリティ

カスタム操作の `contentDescription` やVigilithのTalkBack対応は丁寧です。一方、[コントラストテスト](../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt#L127) はライト配色の既知未達を「失敗させず記録する」テストです。

つまりテスト成功＝全配色がAA準拠、ではありません。ライト側の文字6色と緑ボタンは未解決です。

### プライバシー

[AndroidManifest.xml](../app/src/main/AndroidManifest.xml#L3) の `allowBackup="true"` により、Vault URIや当日履歴のタイトルなどを含むSharedPreferencesがバックアップ対象になり得ます。

ノート本文は保存していませんが、「完全に端末内だけ」を重視するならバックアップ除外ルールを検討すべきです。

### リリース運用

[app/build.gradle.kts](../app/build.gradle.kts#L7) の `applicationId` がまだ `com.example.newproject` です。未公開なら正式ID決定を後回しにしない方が安全です。

Lintの28警告は主に以下です。

- 依存・Gradle更新: 11件
- 不要リソース等: 4件
- KTX推奨: 9件
- 空き容量API: 2件
- `SimpleDateFormat`／Locale固定: 2件

## 推奨順序

1. コメント用の不要importを除去し、許可するパッケージ依存方向を決める
2. `SearchController` のJob/requestIdとキャンセル伝播を修正
3. `NoteUiState.kt` を機能別ファイルへ分割
4. `NoteViewModel` の依存生成をFactoryまたはコンストラクタ境界へ出す
5. ViewModel・Navigation・SAFの統合テストを追加
6. CIでテストとLintを必須化
7. ライト配色のAA未達を解消
8. 依存更新・正式applicationId・バックアップ方針を整理

今すぐ大規模なfeature-first化やマルチモジュール化をする必要はありません。まず「依存方向」「状態の所有者」「非同期要求の世代管理」の3点を固めるのが、最も費用対効果が高いです。
