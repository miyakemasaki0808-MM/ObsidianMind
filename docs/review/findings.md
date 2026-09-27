# 未解決のレビュー指摘

**この文書が答える問い:** **レビューで見つかった問題のうち、いまも未解決なのはどれか。**

レビュー本文は最新の1本だけ、進行中の対応内容は
[_wip/current_issues.md](../_wip/current_issues.md) が持つ。この受付簿は両者をつなぎ、
レビュー指摘が課題台帳へ移されないまま消えることだけを防ぐ。

過去の原文と処遇はgit履歴、完了した変更は
[change_history.md](../dev/change_history.md)、現在有効な判断は `dev/features/`・`dev/system/` が持つ。
**解消済みの指摘はここへ残さない。** 修正確認が済んだら課題台帳の項目と同時に受付行も削除する。

検査は [`ReviewFindingsLedgerTest`](../../app/src/test/java/com/example/newproject/architecture/ReviewFindingsLedgerTest.kt) が行う。
`./gradlew testDebugUnitTest` とCIで毎回走り、次を固定する。

- 最新レビューの `### P1-1.` 等の指摘がすべて受付済みである
- 受付行が実在する未解決課題を参照する
- 解消済みの処遇や、課題台帳から消えた古い行を残さない

## 書き方

- **1指摘＝1行。** 受付IDは `<レビューのファイル名から .md を除いた全体>/<P番号>`。
- 処遇は、新しい課題なら **`起票`**、既存課題と同じなら **`統合`**。実在する課題IDを必ず併記する。
- 内容は識別できる一文だけにする。対応内容は `current_issues.md` が持つ。
- 修正確認まで済んだら行を削除する。`解消` 行や完了の経緯は残さない。

## 受付簿

| ID | 指摘 | 処遇 |
|---|---|---|
| `2026-09-27-reunion-card-device-rerun-review/P3-1` | 低い横画面の計測テストが本文とカードの同じ語で2か所に当たって落ちる | `統合` APP-2 |
| `2026-09-27-reunion-card-followup-review/P2-1` | 別ノートの訪問保存を全件待つため保存済み再会カードまで表示が止まる | `起票` REUN-9 |
| `2026-09-27-reunion-card-device-review/P3-2` | 横画面の4ボタンで本文が隠れ「読んだ」もマスコットと重なる | `統合` APP-2 |
| `2026-09-26-reunion-card-implementation-fix-review/P2-1` | 飛び越した画像の測定待ちで全画面へ移ると測定が途切れ進捗を記録できない | `統合` REUN-4 |
| `2026-09-26-reunion-card-implementation-review/P2-1` | 続きから読むで未測定画像を飛び越すと後方の進捗を記録できない | `起票` REUN-4 |
| `2026-09-26-reunion-card-implementation-review/P2-3` | 長大ブロックの未読末尾を既読側へ渡しブロック内の進捗も失う | `起票` REUN-6 |
| `2026-07-31-code-quality/P2-5` | releaseは組み立てられるが公開可能な成果物ではない | `起票` REL-1 |
| `2026-09-17-app-launch-device-review/P2-1` | 横画面で再会カードがノート本文の表示領域を占有する | `起票` APP-2 |
