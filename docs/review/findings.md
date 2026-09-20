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
| `2026-07-31-code-quality/P2-5` | releaseは組み立てられるが公開可能な成果物ではない | `起票` REL-1 |
| `2026-09-17-app-launch-device-review/P2-1` | 横画面で再会カードがノート本文の表示領域を占有する | `起票` APP-2 |
| `2026-09-19-remark-regenerate-device-review/P2-1` | 同じひとことの再生成で保存済みの返事だけが失われることを実機で確認 | `統合` AI-8 |
| `2026-09-20-margin-memo-implementation-review/P1-1` | 保存完了状態が残り、同じシートで次に入力したメモが消える | `起票` MEMO-5 |
| `2026-09-20-margin-memo-implementation-review/P1-2` | 訪問保存の合流超過で保存済みメモを切り落とし、離脱後に失う | `起票` MEMO-6 |
| `2026-09-20-margin-memo-implementation-review/P1-3` | バックアップ内の重複合流超過を捨て、一部だけ復元して成功扱いする | `起票` MEMO-7 |
| `2026-09-20-margin-memo-implementation-review/P2-1` | 削除前に起動した訪問保存が削除済みメモを復活させる | `起票` MEMO-8 |
| `2026-09-20-margin-memo-implementation-review/P2-2` | 保存と削除が互いを取り消し、Saving残留と楽観削除の不整合を起こす | `起票` MEMO-9 |
| `2026-09-20-margin-memo-implementation-review/P2-3` | 再会カードへhasMemosを渡さず、メモの入口が常に隠れる | `起票` MEMO-10 |
| `2026-09-20-margin-memo-implementation-review/P2-4` | 長い見出しを含むメモが永続不能なHeldになり、後続保存も妨げる | `起票` MEMO-11 |
| `2026-09-20-margin-memo-implementation-review/P2-5` | 既存痕跡の読み取り不能を削除成功として返す | `起票` MEMO-12 |
