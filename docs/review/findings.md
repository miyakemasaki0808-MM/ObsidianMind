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
| `2026-11-03-fold-desk-device/P3-1` | カバー画面を横にすると、見出しの霞がレールの右端から離れる | `起票` UI-7 |
| `2026-11-03-explore-antigravity-review/P1-1` | Nano が空や候補外を返すと、キーワード一致の結果が注記なしで出て正本と食い違う | `起票` SEARCH-2 |
| `2026-11-03-explore-antigravity-review/P2-1` | AI のモードの照合が拡張子まで照らし、`md` や `m` で無関係なノートが出る | `起票` SEARCH-3 |
| `2026-11-03-explore-antigravity-review/P3-1` | タイトル0件の1行が範囲を戻さず、前に選んだフォルダで探す | `起票` SEARCH-4 |
| `2026-11-03-explore-antigravity-review/P4-1` | タブを離れても範囲と結果が残り、走行中の検索も止めない | `統合` SEARCH-4 |
| `2026-11-03-explore-antigravity-review/P5-1` | AI のモードのプロンプトと結果で、同じ名前のノートを見分けられない | `起票` SEARCH-5 |
| `2026-11-03-explore-antigravity-review/P6-1` | クエリの bigram を候補ごとに作り直す | `起票` SEARCH-8 |
| `2026-11-03-explore-antigravity-review/P6-2` | AI のモードの入力中もタイトルを照らす | `統合` SEARCH-8 |
| `2026-11-03-explore-antigravity-review/P7-1` | 範囲に選んだフォルダが外で消えると、チップの選択が消えて0件になる | `起票` SEARCH-6 |
| `2026-11-03-explore-antigravity-review/P7-2` | 範囲のチップの選択状態を TalkBack が読み上げない | `起票` SEARCH-7 |
| `2026-11-03-explore-antigravity-review/P7-3` | 空の Vault は一覧を引くたびに走査する | `統合` SEARCH-8 |
| `2026-07-31-code-quality/P2-5` | releaseは組み立てられるが公開可能な成果物ではない | `起票` REL-1 |
| `2026-10-12-observation-device/P2-1` | ランダム・さがす・関連で開いたノートが前のノートの読んでいた位置から始まる | `統合` UI-2 |
