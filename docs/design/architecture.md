# 設計思想 — アーキテクチャ（ViewModel分割・状態管理）

**対象領域:** 横断的なコード構造・状態管理・並行処理の規約
**初版:** 2026-07-19（品質改善活動 PR #16〜#20）

---

## 背景

機能追加を重ねた結果、`NoteViewModel` が906行まで肥大化し、状態リセット処理の重複（3箇所）による「リセット漏れ→旧状態の残留」バグが複数発生していた。静的分析で27項目を指摘し、同日中に全件解消した活動の中核が本構造である。

## 判断1: 単一ViewModel＋機能Controller方式

マルチモジュール化や機能別ViewModel化ではなく、「`NoteViewModel` は窓口として残し、実装を機能Controllerへ委譲する」方式を採った。

```
NoteViewModel（348行・窓口と横断調停）
 ├── SectionChatController
 ├── QuizController
 ├── AnnotationController
 └── SearchController
```

- 各Controllerは `viewModelScope` と `MutableStateFlow<NoteUiState>` を注入され、**担当フィールドだけ**を `copy()` で更新する
- 公開APIと `uiState` の形を維持したため、UI層の変更ゼロで移行できた
- 状態の単一ソース（1つの `NoteUiState`）は維持。画面ごとの状態追跡が分散しない利点を捨てない

## 判断2: 結合点を「明示契約」に変換する

ノート切替・Vault切替時の後始末は、各所に散らばせず2つの契約に集約した。

| 契約 | 役割 |
|------|------|
| `cancelNoteScopedJobs()`＋各Controllerの `cancelAndClear()` | 実行中AIジョブの停止（旧ノートの結果混入と、Mutexロックの占有継続を防ぐ） |
| `resetNoteScopedStates()` | ノート単位状態の一括リセット（リセット漏れを構造的に防ぐ） |

**機能追加の定型**: Controller 1ファイル＋状態1フィールド＋この契約2箇所への登録。純粋ロジックは最初から別ファイルに切り、テストを同時に書く。

## 判断3: 壊れやすいロジックは純関数に切り出す

`QuizResponseParser`・`AnnotationComposer`・`MarkdownParser`・タイトル正規化などの文字列処理はAndroid I/Oから分離し、素のJVMユニットテストで回帰を防ぐ。テスト設計の過程で実バグ（補記Markdownの字下げ混入）も発見された。**テストは検証だけでなく発見の道具になる。**

## 教訓: 重複が品質問題の温床だった

27項目の多くは「同じ形のコードを複数箇所に書いた」ことに起因していた（状態リセット3重複・SAFカーソルループ5重複・タブ遷移2重複・AIタイトル整形2重複）。**同じ形を2度書いたら共通化を検討する**を目安とする。

ただし共通化は早すぎても失敗する。QuizとAnnotationのController相似形は2件の段階では意図的に共通化せず、3件目が現れてパターンが確定してから抽出する方針（[background_ai_ux](background_ai_ux.md) 参照）。

## 並行処理の規約

- AI生成は `AiClient` 側のMutexで直列化し、60秒タイムアウトを設ける
- ノート・Vault単位のジョブは追跡してキャンセルする
- `CancellationException` は再throwし、一般エラーへ変換しない
- 完了通知がキャンセルをすり抜ける経路には requestId＋`isCurrent()` ガードを併用する
