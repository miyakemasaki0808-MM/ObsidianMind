# 未解決のレビュー指摘

**この文書が答える問い:** **レビューで見つかった問題のうち、いまも未解決なのはどれか。**

レビュー本文は転記まで最大1本を置き、削除後も未解決の受付行は残す。進行中の対応内容は
[_wip/current_issues.md](../_wip/current_issues.md) が持つ。この受付簿は両者をつなぎ、
レビュー指摘が課題台帳へ移されないまま消えることだけを防ぐ。

過去の受付行・レビュー一覧の要点はgit履歴、完了した変更は
[change_history.md](../dev/change_history.md)、現在有効な判断は `dev/features/`・`dev/system/` が持つ。
日付つきのレビュー原文は未追跡なので、git履歴には残らない。
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
| `2026-10-12-observation-device/P2-1` | ランダム・さがす・関連で開いたノートが前のノートの読んでいた位置から始まる | `統合` UI-2 |
| `2026-10-21-fold-entry-design-review/P2-2` | ふだんの画面で読み進めてから見開きへ入ると、復元待ちの見開きの位置が一覧の位置より優先される | `起票` SPREAD-7 |
| `2026-10-22-fold-entry-design-rereview/P2-1` | 縦の流しで適用済みの送りが、その後に指で読んだ位置より優先される | `統合` SPREAD-7 |
| `2026-10-22-fold-entry-design-rereview/P2-2` | 見開きの留守中に解析し直すと、一覧の新しい読書位置を照らさず古い保存ブロックを採る | `統合` SPREAD-7 |
| `2026-10-23-fold-entry-implementation-review/P2-2` | SPREAD-7 の instrumentation 3件が、画面外の段落0で一覧を探して止まる | `統合` SPREAD-7 |
