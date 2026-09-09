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
| `2026-08-01-no9/P2-2` | 連続削除が同じJobを奪い合い、物理削除と画面状態がずれ得る | `起票` TRACE-3 |
| `2026-07-31-code-quality/P2-5` | releaseは組み立てられるが公開可能な成果物ではない | `起票` REL-1 |
| `2026-09-09-note-field-color-design-review/P2-1` | 縮退結果が永久確定し、一時的なAI障害から回復できない | `起票` FIELD-1 |
| `2026-09-09-note-field-color-design-review/P2-3` | 本文なしで引く索引と永続結果の寿命が未定義 | `起票` FIELD-1 |
| `2026-09-09-note-field-color-design-review/P3-1` | システム図と検証条件に判断6以前の分岐が残る | `起票` FIELD-1 |
| `2026-09-10-note-field-color-design-rereview/P2-1` | 一時的な連続失敗の停止に解除条件がなく、非対応判定の根拠も既存契約と異なる | `統合` FIELD-1 |
| `2026-09-10-note-field-color-design-rereview/P2-2` | 走査による暫定結果とAI確定結果の索引Aへの合成規則が未定義 | `統合` FIELD-1 |
| `2026-09-10-note-field-color-design-rereview/P2-3` | 正常な該当分野なしを確定結果として保存する契約がない | `統合` FIELD-1 |
| `2026-09-10-note-field-color-design-rereview/P2-4` | 索引未準備から冊子を開くと走査時の保存ストアI/Oが性能契約に反する | `統合` FIELD-1 |
| `2026-09-10-note-field-color-design-rereview/P3-1` | 概要と図が暫定非永続・キャンセル破棄・入力版照合と矛盾する | `統合` FIELD-1 |
