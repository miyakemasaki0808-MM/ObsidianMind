# レビュー

**このフォルダが持つもの:** 転記までの外部レビュー本文（最大1本）、レビュー由来の**未解決指摘**、
**次に実機で確かめてほしいことの待ち行列**、Codexが行う実機検証の恒久手順。

**誰が何をし、何をしないかの正本は [CLAUDE.md](../../CLAUDE.md) の「作業の進め方」にある。**
ここが持つのは**Codexの手順**であって、分担そのものではない（2箇所に書くと必ず片方が古くなる）。

`2026-*.md` は対象コミット時点のスナップショットなので、残している間は書き換えない。
検証範囲・結果・未確認範囲の転記と指摘の受付を終えた本文は削除する（オーナーの受理は待たない）。
新しいレビューを受け付ける場合も、前の本文は転記を済ませてから削除し、最大1本に保つ。
**本文はコミットしない**（`.gitignore`）— 端末の識別子や検証中のローカルパスが入るため。
**本文が消えた後も、同じ作業のあいだは下の一覧の行が要点を持つ**ので、要点は一覧へ書き切る。
**一覧行には本文のファイル名（stem）を併記する** — 同じ日に2本目を置いたとき、
日付だけでは1本目の行が身代わりになって「転記済み」に見えるため。転記は
[`ReviewFindingsLedgerTest`](../../app/src/test/java/com/example/newproject/architecture/ReviewFindingsLedgerTest.kt) が検査する。
**一覧は今の作業のレビューだけを持つ。** 別の作業のレビューを受け付けたら、前の作業の行を消す。
レビューがあったことは [change_history](../dev/change_history.md) の1行が残し、消した行の全文は git 履歴にある。
レビュー由来の未解決指摘は [findings.md](findings.md)、対応内容は
[_wip/current_issues.md](../_wip/current_issues.md) を正とする。解消済みの受付行は残さない。

---

## レビュー一覧（新しい順）

| 日付 | 対象 | 基準 | 結果 |
|---|---|---|---|
| 2026-10-24 区切り4と帯の幅の実装の再レビュー<br>`2026-10-24-fold-turn-implementation-rereview` | `242ff5f5..5af47a85` の2コミット・10ファイル（レビューの受付 `36940df0`・アニメーション無効時の寄せの修正 `5af47a85`）。`SpreadSettleFling` とページャへの配線、`SpreadSettleTest`・`SpreadReducedMotionTest`、旧走査テストの撤去、設計・課題・転記を照合。Compose Foundation 1.7.3 の `PagerDefaults.flingBehavior`・`PagerSnapLayoutInfoProvider`・`SnapFlingBehavior` も確認 | `5af47a85`、`feature/New_Function_Fold_No.02`、追跡ファイルの差分なし | **新規指摘なし。前回の P2-1（SPREAD-8）は前回の再現順序と今回の検証範囲で解消**（物理端末の検証待ちとして台帳に残す）。前回と同じ 800×600dp・見開き1950px・同じ払い方（離す前565px）で、離した後の6フレームがすべて1950px（修正前は 565→651→793→…→1668px）。指を動かす間は倍率1・離す直前に0へ替えても6フレームとも1950px、逆に離す直前に1へ替えると 1385→1243→…→609px と途中を通って0px。即時の寄せは `snap()` を渡すだけで、既定の `PagerSnapDistance.atMost(1)` では近づける段が走らないことをソースで確認。JVM **2,048成功・失敗0**（`--rerun-tasks`）、Lint error／warning 0、Debug・androidTest 組み立て成功。Pixel 10 Pro Fold の AVD（API 36.1）で instrumentation **28成功・失敗0・skip 0**（`SpreadReducedMotionTest` 5件・`NoteReadingFlowTest` の見開き21件・一時2件）。FOLD-11 の前回判定は維持（今回の差分は帯の幅を変えず、見開き1.6倍の回帰は成功。机2倍・対照1倍は再実施していない）。**未保証:** 物理 Fold の開閉・横持ち・分割窓、端末の設定画面からのアニメーション無効と通知の経路（倍率は実行コンテキストへの注入）、TalkBack の実操作・発話、物理端末での遠近・影・手触り、instrumentation 全件と TEST-9、寄せの途中での設定変更、別の Compose 版や複数ページの寄せ、非同期の交錯の全順序。製品コード・恒久テストは未変更、一時テストは削除済み |
| 2026-10-24 区切り4（綴じ目を軸にしためくり・読み上げの送り）と帯の幅の実装レビュー<br>`2026-10-24-fold-turn-implementation-review` | `81a83529..242ff5f5` の4コミット・14ファイル（帯のレールの実幅・綴じ目を軸にしためくり・読み上げの送り・関連テスト・設計・受理条件）。中心は `MainActivity`・`AppScaffold`・`DeskControls`・`NoteReaderTab`・`NoteSpread`・`SpreadTurnGeometry`。`BodyPosition`・`ReaderLayout` と、Compose Foundation 1.7.3 のページャ・寄せ・配置のソースも照合 | `242ff5f5`、`feature/New_Function_Fold_No.02`、追跡ファイルの差分なし | **新規P2を1件（SPREAD-8 へ起票）。FOLD-11 は合意済みの instrumentation の受理条件の範囲で解消**（物理端末は未確認なので台帳に残す）。P2-1: OS のアニメーション無効（倍率0）でも、指を離した後の寄せだけが回転を続ける — Foundation 1.7.3 の `snapFlingBehavior` が寄せを倍率1に固定した中で動かす。実際の `NoteSpread`（800×600dp）に倍率0を注入し、幅の約30%を引いて離すと、離した後の10フレームが 565・651・793・…・1668px（見開きの幅1950px）と途中の位置を出した。同じ条件の「次の見開きへ」は5フレームとも1950pxで途中なし、「前の見開きへ」で0px、末尾では「前の見開きへ」だけ。アプリの2ファイルに `MotionDurationScale` が無いことを見る検査では捉えられない。JVM **2,047成功・失敗0**（`--rerun-tasks`）、Lint error／warning 0、Debug・androidTest 組み立て成功。Pixel 10 Pro Fold の AVD（API 36.1）で `NoteReadingFlowTest` **89成功・失敗0・skip 0**（今回の5件 — 帯の机2倍・見開き1.6倍・対照1倍、読み上げの送りと書き戻し、手前への持ち上がりと左ページの紙の色 — を含む）。一時JVM検証で、倍率0の通常の `animateTo` は1フレーム、ページャ既定の寄せは25フレーム。**未保証:** 物理 Fold の開閉・横持ち・分割窓・Insets の届く順、端末設定からの倍率・アニメーション設定の切替（倍率0は Compose の実行コンテキストへの注入で確かめた）、TalkBack の実操作・発話・フォーカス順、めくりの手触り・遠近と影、全角度・両方向の画素、再会カードや画像との組合せ、instrumentation 全件と TEST-9、§10 の非同期の交錯の全順序。製品コードは未変更、一時テストは削除済み |

**同型への照合:** P2-1 は [lessons L60](../dev/lessons/L60.md)（使っている SDK の実際の経路を確かめる — アプリが倍率を上書きしないことと、SDK を通った後も倍率が保たれることは別）と
[L55](../dev/lessons/L55.md)（文字列の不在を見る走査は、無効の設定で動かないことを保証しない。受理条件は結果で書く）。

---

## ファイルの役割

| ファイル | 役割 | 誰が書くか |
|---|---|---|
| `2026-*.md` | 転記までのレビュー本文（最大1本） | **レビュアー（Codex）。こちらは書き換えない。未追跡** |
| [review_template.md](review_template.md) | レビューに書く内容の様式 | こちらが持ち、レビュアーへ渡す |
| [findings.md](findings.md) | **未解決指摘の受付簿** — レビューと課題台帳の対応 | こちらが書く |
| `pending_device_validation.md` | **実機検証の依頼待ち** — 机上は通ったが実機で未確認のもの（着手順）。依頼が済めば消す | こちらが書く。**未追跡・使い捨て** |
| [device_validation/](device_validation/) | **Codex実機検証の共通手順＋機能別ケース**。結果は持たない | こちらが持ち、機能契約と同時に更新する |
| [device_validation/quick_check.md](device_validation/quick_check.md) | **実機検証（簡易版）** — 通すケースの選抜規則とスモークセット | こちらが持つ。**回帰確認のときだけ使う** |

**手順とケースは追跡し、端末を特定するものだけ履歴へ残さない**（`.gitignore`）。
残さないのは日付つきレビュー本文・`device_validation/local_config.md`（実シリアルやVault URI）・
`device_validation/evidence/`（スクリーンショット・UIダンプ・pullしたファイル）・使い捨ての依頼書。
**再利用できる手順とケースはリポジトリに置く** — 実機依頼のたびに導出し直さないため。

実機検証を依頼されたCodexは、まず [共通手順](device_validation/README.md) を読み、
対象機能のケースIDを選ぶ。**機能別ケースの一覧は [簡易版](device_validation/quick_check.md) のスモークセットが持つ。**
全ケースが載っていることを `DeviceValidationDocsTest` が検査するので、ここには写さない。

機能別ケースには**再現手順と期待だけ**を書く。端末・日付・成否はレビュー本文から一覧へ転記し、
現在も残る問題は [findings.md](findings.md) が持つ。

**受付簿が要る理由。** 「総評で問題と書いたものは課題台帳へ起票する」という規則は
[CLAUDE.md](../../CLAUDE.md) と [document_map](../dev/document_map.md) の2箇所に以前からあり、
**それでも 2026-08-01 の総評の指摘4件が2日間どこにも追跡されなかった**。
規則を増やしても直らないので、**未解決指摘と課題台帳の対応を機械検査する**形にした。

> **受付簿の内容は薄く保つ。** 受付簿は「未解決であること」と課題ID、進行中の対応内容は
> [_wip/current_issues.md](../_wip/current_issues.md) が持つ。修正確認が済んだら両方から削除し、
> 完了した変更は change_history、現在有効な判断は設計書、削除した受付行・一覧行の要点はgit履歴へ任せる（未追跡のレビュー原文は残らない）。
