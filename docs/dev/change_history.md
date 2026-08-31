# 変更履歴表

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）

このファイルはPR単位の変更履歴を新しい順に記録する累積文書である。

- **何を**いつ変えたかはこの表、**今どうなって**いるかは [owner/source_code_analysis.md](../owner/source_code_analysis.md)、**なぜそう**したかは [features/](features/)・[system/](system/) を参照する。
- 運用ルール: PRごとに1行追記する。設計判断・試行錯誤があった変更のみ `features/` か `system/` に対応ファイルを作成または追記し、この表からリンクする。
- **「変更内容」は1文・100字以内。** 経緯・代償・変異確認の結果・教訓は**ここに書かない**（行き先は `features/`・`system/` と lessons.md）。
  2026-08-10 に中央値1,846字まで肥大していたのを圧縮した。**「1行」に長さの上限が無かったことが原因**なので、
  上限を明記する。圧縮前の全文は git 履歴に残る（`git log -p docs/dev/change_history.md`）。

---

## 履歴（新しい順）

| 日付 | PR | 変更内容 | 設計メモ |
|---|---|---|---|
| 2026-09-01 | — | 冊子の往復・描画・読み上げ文言・回転／FoldをPixel実機で確認し、N-12を完了した | **[booklet_mode](features/booklet_mode.md)** §10・§11・[review](../review/README.md) |
| 2026-08-31 | — | 冊子のページ復帰をPixel実機で確認し、NavHostテストの同名ノード選択を残件化した | **[booklet_mode](features/booklet_mode.md)** §10・§11・[review](../review/README.md) |
| 2026-08-31 | — | 冊子をPixel実機で検証し、通常ノートから戻るとページ位置を失う欠陥を残件化した | **[booklet_mode](features/booklet_mode.md)** §6・§10・§11・[review](../review/README.md) |
| 2026-08-31 | — | 冊子の読書ボタンにノート名を名乗らせ、読み上げと往復テストで表示中の1件を選べるようにした | **[booklet_mode](features/booklet_mode.md)** §9・§10 |
| 2026-08-31 | — | 冊子のページ位置を束と同じ場所へ移し、ノートから戻ると同じページが開くようにした | **[booklet_mode](features/booklet_mode.md)** §6・§10・[lessons](lessons.md) L56 |
| 2026-08-30 | — | 冊子の再修正確認4件を直し、停止中に始まるセッションと取消の観測点を閉じた | **[booklet_mode](features/booklet_mode.md)** §10・[lessons](lessons.md) L55 |
| 2026-08-30 | — | 冊子の修正確認4件を直し、読書時間の停止を理由ごとに数える形へ変えた | **[booklet_mode](features/booklet_mode.md)** §10・[lessons](lessons.md) L55 |
| 2026-08-30 | — | 冊子の実装レビュー9件を直し、冊子滞在中の読書時間と読込中Backの後着を止めた | **[booklet_mode](features/booklet_mode.md)** §10・[architecture](system/architecture.md) |
| 2026-08-30 | — | 冊子モード（N-12）を実装した。10枚を引いてめくり、扉から通常表示へ渡す（実機検証待ち） | **[booklet_mode](features/booklet_mode.md)**・[architecture](system/architecture.md) |
| 2026-08-30 | — | 冊子モードの設計レビュー7件を反映し、解析の共有をやめてナビと扉の受け入れ条件を決めた | **[booklet_mode](features/booklet_mode.md)** §8 判断7・判断8・§10 |
| 2026-08-30 | — | 文書のソース参照31本から行番号を落として名前で指す形へ揃え、実在を検査に載せた | **[CLAUDE.md](../../CLAUDE.md)**「文書の扱い」・[lessons](lessons.md) L29 |
| 2026-08-30 | — | 冊子モードの位置づけをオーナー判断で確定し、束の寿命・めくる向き・使い切り後を決めた | **[booklet_mode](features/booklet_mode.md)** §8 判断6 |
| 2026-08-30 | — | 実機検証の開始・終了を常設検証用Vaultへ固定し、元Vaultへ戻さない手順にした | **[device_validation](../review/device_validation/README.md)**・[lessons](lessons.md) L54 |
| 2026-08-30 | — | 太字範囲調整をPixel実機のDIST-21〜24とinstrumentation 6件で確認し、段階1を完了した | **[distill_range_adjust](features/distill_range_adjust.md)** §10・[review](../review/README.md) |
| 2026-08-29 | — | X-11コードレビューの4件を直し、告知の寿命と主語、強調の観測点を契約にした | **[distill_range_adjust](features/distill_range_adjust.md)** §5・§10・[lessons](lessons.md) L53 |
| 2026-08-29 | — | 蒸留の太字範囲を`語句`/`意味節`/`文全体`から選べるようにした（段階1・実機検証待ち） | **[distill_range_adjust](features/distill_range_adjust.md)** |
| 2026-08-29 | — | 蒸留の装飾保護をPixel実機の DIST-19・DIST-20 で確認し、BOLD-1を完了した | **[reflect_distill](features/reflect_distill.md)** §10・[review](../review/README.md) |
| 2026-08-29 | — | バッククォートを連なり単位で索引し、未閉じrunのOOMと部分一致をなくした | **[reflect_distill](features/reflect_distill.md)** §5・[lessons](lessons.md) L52 |
| 2026-08-29 | — | 未閉じ記号の再探索と候補ごとの保護範囲走査をやめ、上限テストへ3系統目を足した | **[reflect_distill](features/reflect_distill.md)** §5・[lessons](lessons.md) L52 |
| 2026-08-29 | — | インライン記法の入れ子を保持して描き、走査を入力サイズに比例させた | **[reflect_distill](features/reflect_distill.md)** §5・[lessons](lessons.md) L51・L52 |
| 2026-08-29 | — | インライン記法の解釈器を表示と蒸留で共有し、規則の食い違いで残っていた3件を閉じた | **[reflect_distill](features/reflect_distill.md)** §5・§11・[lessons](lessons.md) L51 |
| 2026-08-29 | — | 蒸留の保護範囲へ斜体・太字斜体・打ち消し線を入れ、装飾をまたぐ候補を採らないようにした | **[reflect_distill](features/reflect_distill.md)** §5・§11・[lessons](lessons.md) L51 |
| 2026-08-29 | — | N-14設計レビューの追加5件を反映し、調整範囲が保存へ届くことと告知の寿命を契約にした | **[distill_range_adjust](features/distill_range_adjust.md)** §5・§7・§10 |
| 2026-08-29 | — | N-14設計レビューの5件を反映し、v1が斜体・打ち消し線をまたぐ欠陥を起票した | **[distill_range_adjust](features/distill_range_adjust.md)**・[reflect_distill](features/reflect_distill.md) §11・[lessons](lessons.md) L51 |
| 2026-08-28 | — | 蒸留の太字範囲調整を設計し、確定範囲を親文の内側へ閉じてプリセット先行に切った | **[distill_range_adjust](features/distill_range_adjust.md)**・[roadmap](../_wip/roadmap.md) |
| 2026-08-28 | — | X-9の禁止キー・拒否文言・単発中止・再タップをPixel実機で確認し完了した | **[reading_trace_backup](features/reading_trace_backup.md)**・[review](../review/README.md) |
| 2026-08-28 | — | 停止待ち中の再タップが停止前の件数を確定できる穴を塞いだ | **[reading_trace_backup](features/reading_trace_backup.md)** §10・§11・[lessons](lessons.md) L49 |
| 2026-08-27 | — | 中止後の過剰適用を断ち、退避JSONのキーと拒否文言を正本の外部契約へ揃えた | **[reading_trace_backup](features/reading_trace_backup.md)** §5・§10・§11・[lessons](lessons.md) L49・L50 |
| 2026-08-27 | — | X-9をPixel実機で検証し、応答性を確認、中止後の過剰適用と形式差異を残件化した | [review](../review/README.md)・[reading_trace_backup](features/reading_trace_backup.md) |
| 2026-08-26 | — | X-9レビューのP1 2件・P2 2件を直し、読取不能・下見との照合・損失の方向・Main占有を閉じた | **[reading_trace_backup](features/reading_trace_backup.md)** §5・§8・[lessons](lessons.md) L47・L48 |
| 2026-08-23 | — | 読書痕跡の書き出し・読み戻しを入れ、片付け系を「データ管理」1画面へ統合した | **[reading_trace_backup](features/reading_trace_backup.md)**・[architecture](system/architecture.md) |
| 2026-08-22 | — | 解析書へ目次（63見出し・2階層）を足した | [source_code_analysis](../owner/source_code_analysis.md) §16 |
| 2026-08-22 | — | 技術俯瞰と開発日誌を X-5・X-2 まで同期し、テスト内訳89クラスを機械的に数え直した | [source_code_analysis](../owner/source_code_analysis.md)・[journal](../owner/journal/2026-08.md) |
| 2026-08-22 | — | X-2の再会カードをPixel実機の全ケースと描画テストで確認し、X-9をNowへ繰り上げた | **[reunion_card](features/reunion_card.md)**・[review](../review/README.md) |
| 2026-08-22 | — | 印の要求世代をノート単位にし、正本から実機ケース範囲の重複を外した | **[reunion_card](features/reunion_card.md)** |
| 2026-08-22 | — | X-2のレビュー指摘6件を直し、生成結果の3分割・印の要求世代・描画instrumentation・版番号検査を入れた | **[reunion_card](features/reunion_card.md)**・[lessons](lessons.md) L14 |
| 2026-08-22 | — | 再会カードに種別と「まだ考えたい」を入れ、X-2・A-6・A-7 を1つの実装で閉じた | **[reunion_card](features/reunion_card.md)**・[reflect_reading_trace](features/reflect_reading_trace.md) §6 |
| 2026-08-22 | — | 痕跡の退避（X-9）とN-14の設計（X-10）をNextへ昇格し、X-2より先に置けない理由を書いた | [roadmap](../_wip/roadmap.md) §0.5 |
| 2026-08-22 | — | KDocリンク切れ検査をtest・androidTestへ広げ、走査外だった既存の壊れたリンク1件を直した | [lessons](lessons.md) L14 |
| 2026-08-22 | — | AI入力上限と字下げ修正をPixel実機のBUDGET-01〜09で確認し、X-5を完了した | **[ai_input_excerpt](system/ai_input_excerpt.md)** §11・§13・[review](../review/README.md) |
| 2026-08-22 | — | AI入力予算の実機ケースを実機検証の入口と存在検査へ登録した | [review](../review/README.md)・[device_validation](../review/device_validation/ai_input_budget.md) |
| 2026-08-22 | — | ピッカーが予算で提示から落とした候補を応答で受理しないよう、照合表を提示済みタイトルから作るようにした | **[ai_input_excerpt](system/ai_input_excerpt.md)** §13・[lessons](lessons.md) L14 |
| 2026-08-22 | — | 読書痕跡の履歴に予算を置き、最新の訪問が共通クランプで途中から切れないようにした | **[ai_input_excerpt](system/ai_input_excerpt.md)** §13・[lessons](lessons.md) L45 |
| 2026-08-22 | — | 完成プロンプトの入力上限を1箇所で強制し、会話履歴・候補・ラベルを予算内へ収めるようにした | **[ai_input_excerpt](system/ai_input_excerpt.md)** §13 |
| 2026-08-22 | — | 複数行の値を埋めるとテンプレートの字下げが全行に残る問題を9つのプロンプトで直した | **[ai_input_excerpt](system/ai_input_excerpt.md)** §13・[lessons](lessons.md) L44 |
| 2026-08-21 | — | 再会カードの枠を排他1枠＋種別欄と決め、まだ考えたい＞問い＞古い前提＞俯瞰要約の順で確定した | **[reunion_card](features/reunion_card.md)（新規）**・[reflect_reading_trace](features/reflect_reading_trace.md) |
| 2026-08-21 | — | X-4を閉じ、X-2の設計判断を待たずに動けるX-5（AI-6→AI-3）をNowへ繰り上げた | [roadmap](../_wip/roadmap.md) |
| 2026-08-21 | — | 実機ではconnectedタスクを使わず、adb installとam instrumentで検証する手順へ改めた | **[device_validation](../review/device_validation/README.md)**・[lessons](lessons.md) L43 |
| 2026-08-21 | — | X-4の実機確認を完了した（IMAGE-11/12/14/15、画像関連instrumentation 22/22成功） | **[note_image_rendering](features/note_image_rendering.md)** §10 |
| 2026-08-21 | — | 寸法キャッシュをsingle-flightの入れ物へ移し、確かめ終えるまで復号を始めないようにした | **[note_image_rendering](features/note_image_rendering.md)** §8 |
| 2026-08-21 | — | 寸法のキャッシュにも世代を入れ、鮮度確認の結果を参照ごとに控えて解決のたびの外部I/Oを止めた | **[note_image_rendering](features/note_image_rendering.md)** §8 |
| 2026-08-21 | — | 索引がヒットし続けても、TTL超過後は当たった1件の更新日時を引き直して復号キャッシュの鍵へ載せるようにした | **[note_image_rendering](features/note_image_rendering.md)** §8 |
| 2026-08-20 | — | 蒸留v1完了後の見直しで、Nowへ画像の世代更新を置き、プロンプト上限をAI-6→AI-3の順でNextへ入れた | [roadmap](../_wip/roadmap.md) §0.5 |
| 2026-08-20 | — | 蒸留v1の表示・候補・保存・競合・復旧・最大サイズをPixel実機で全件確認した | **[reflect_distill](features/reflect_distill.md)** §10・[review](../review/README.md) |
| 2026-08-20 | — | 読書痕跡の退避と復元の設計を確定し、突き合わせ規則・書き出し先・契機・範囲を決めた | [reading_trace_backup](features/reading_trace_backup.md) |
| 2026-08-19 | — | 痕跡の退避が余白メモ待ちでないことと、再会カードの枠を取り合うのが4件であることを資料とロードマップで揃えた | [lessons](lessons.md) L37 |
| 2026-08-19 | — | 二度書き検出の粒度を段落・単位をノート対と決め、ノート全体のDice絞り込みが成立しないことを実測で確かめた | [lessons](lessons.md) L42 |
| 2026-08-17 | — | 句の境界を鉤括弧の内側へ置かないようにし、括弧内読点で語句候補が消える欠陥を直した | **[reflect_distill](features/reflect_distill.md)** §5 |
| 2026-08-17 | — | 蒸留の画面文言と状態名から候補の単位を外し、「文」固定が残らないことを走査検査に載せた | **[reflect_distill](features/reflect_distill.md)** §5・[lessons](lessons.md) L41 |
| 2026-08-17 | — | 蒸留段階2の位置・語句種別・「箇所」表示・語句保存をPixel実機で再確認し、短いノート例外に残る「文」固定を残件化した | **[reflect_distill](features/reflect_distill.md)** §5・[review](../review/README.md) |
| 2026-08-17 | — | 蒸留段階2の括弧内語句と非重複保存をPixel実機で確認し、表示不整合を起票した | **[reflect_distill](features/reflect_distill.md)** §5・[review](../review/README.md) |
| 2026-08-16 | — | 蒸留段階1の直前文脈修正をPixel実機3条件で確認し、段階1を完了した | **[reflect_distill](features/reflect_distill.md)** §5・[review](../review/README.md) |
| 2026-08-16 | — | 蒸留段階1の親文上限をPixel実機9境界で確認し、直前文脈の回帰を起票した | **[reflect_distill](features/reflect_distill.md)** §5・[review](../review/README.md) |
| 2026-08-16 | — | Codexの実機検証を共通手順と機能別ケースへ標準化し、個別承認と後処理漏れを防いだ | **[device_validation](../review/device_validation/)** |
| 2026-08-16 | — | 蒸留段階0のリンク候補除外をPixel実機4ケースで確認し、未解決台帳から閉じた | **[reflect_distill](features/reflect_distill.md)** §5・[review](../review/README.md) |
| 2026-08-16 | — | レビュー受付簿を未解決指摘だけに絞り、解消済みの履歴はgitへ任せる運用にした | **[review/README](../review/README.md)**・[document_map](document_map.md) |
| 2026-08-15 | — | 蒸留候補からリンクだけの文を外し、リンクしか無いセクションが代表を出さないようにした | **[reflect_distill](features/reflect_distill.md)** §5 |
| 2026-08-15 | — | AI状態UXの実機7項目とクイズ追加2状態を完了し、起動契機の一般則へ昇格した | **[background_ai_ux](system/background_ai_ux.md)** §6・[lessons](lessons.md) L4 |
| 2026-08-15 | — | 実機レビューのP2 1件を直し、クイズが使えない理由をシートのクイズ欄自身に表示した | **[background_ai_ux](system/background_ai_ux.md)** §6 |
| 2026-08-15 | — | 再修正確認レビューのP2 1件・P3 1件を直し、無音経路のキャンセルと状態一覧の観測点を絞った | **[background_ai_ux](system/background_ai_ux.md)** §6 |
| 2026-08-14 | — | 修正確認レビューのP2 2件・P3 1件を直し、契約テストを本番10呼び出しと1対1にした | **[background_ai_ux](system/background_ai_ux.md)** §6 |
| 2026-08-14 | — | 机上再レビューのP2 3件・P3 1件を直し、状態確認例外の契約テストと旧主張の検査を足した | **[background_ai_ux](system/background_ai_ux.md)** §6 |
| 2026-08-13 | — | 構造変更の影響面監査を完了条件へ入れ、共存する処理の両方向テストと受付簿の運用を検査に載せた | **[lessons](lessons.md)** L14 |
| 2026-08-12 | — | `AiAvailability` を5値へ割り、キャンセルを再throwし、AI状態の見せ方を純関数1本へ統一した | **[background_ai_ux](system/background_ai_ux.md)** §6 |
| 2026-08-12 | — | 機能仕様6本の数値・データ契約を §5〜§7 へ一本化し、§8 を判断の理由だけに絞った | **[related_notes_ai](features/related_notes_ai.md)**・**[reflect_distill](features/reflect_distill.md)**・**[reflect_reading_trace](features/reflect_reading_trace.md)**・[note_age_paper](features/note_age_paper.md)・[note_image_rendering](features/note_image_rendering.md)・[dark_mode](features/dark_mode.md) |
| 2026-08-12 | — | 技術俯瞰の §14.2 を実装から作り直し、§15 を廃止して行き先の表へ置き換えた | [source_code_analysis](../owner/source_code_analysis.md) §14.2 |
| 2026-08-12 | — | 壊れた節参照25件を内容参照へ直し、番号依存を AdrShapeTest で止めた | [lessons](lessons.md) L29 |
| 2026-08-12 | — | 最終検証の捏造コミット12件を実在する値へ直し、実在と12節の存在を検査へ載せた | **[lessons](lessons.md) L38**・[_template](features/_template.md) |
| 2026-08-11 | — | features の全16本を12節の新様式へ揃え、空欄の代わりに理由を書く規約を検査へ載せた | **[_template](features/_template.md)**・[lessons](lessons.md) L29 |
| 2026-08-11 | — | さがす・セクションAIをテンプレートへ移行し、クイズの入口の誤りを直した | **[ai_picker](features/ai_picker.md)**・**[section_ai_chat](features/section_ai_chat.md)**・[quiz](features/quiz.md) |
| 2026-08-11 | — | Rediscover・ノート要約・クイズの機能仕様を実装から起こして新設した | **[rediscover](features/rediscover.md)（新規）**・**[note_summary](features/note_summary.md)（新規）** |
| 2026-08-11 | — | 恒久文書の _wip 項目ID 13件を内容の記述へ直し、検査を features / system へ広げた | [decisions/README](decisions/README.md)・[lessons](lessons.md) L29 |
| 2026-08-11 | — | ADRの行数規則を `wc -l` 基準の30行へ直し、`AdrShapeTest` で検査に載せた | [decisions/README](decisions/README.md)・[lessons](lessons.md) L29 |
| 2026-08-11 | — | 「結果がAIタブへ直接出る」という誤りを文書2本とKDoc2箇所から潰した | **[background_ai_ux](system/background_ai_ux.md) §4**・[lessons](lessons.md) L37 |
| 2026-08-11 | — | ひとことの復元・生成・通知の記述を実装から起こし直した | **[reflect_remark](features/reflect_remark.md) §4・§5** |
| 2026-08-11 | — | リポジトリ直下と dev / features / system に索引を新設し、README の規約を揃えた | [README](../../README.md)（新設）・[dev/README](README.md)（新設） |
| 2026-08-11 | — | 設計書を features / system / decisions へ種別で分け、機能仕様テンプレートを標準化した | **[decisions/README](decisions/README.md)**（新設）・[document_map](document_map.md) §2 |
| 2026-08-10 | — | feature_ideas を圧縮し、冊子モードを設計書へ切り出してローカルDB案を起票した | **[booklet_mode](features/booklet_mode.md)**（新設） |
| 2026-08-10 | — | 外部レビュー本文を最新1本だけ残す運用へ変え、過去4本を削除した | [review/README](../review/README.md) |
| 2026-08-09 | — | リンク付き文の問い判定から `でしょう` `だろう` を外した（誤拒否の修正） | **[reflect_remark](features/reflect_remark.md)** §11.8 |
| 2026-08-09 | — | ひとことの「問いか接続かどちらか一方」を指示から検査へ移した | **[reflect_remark](features/reflect_remark.md)** §11.8 |
| 2026-08-09 | — | 退避のP1 2件を塞ぎ、映し返しの本文の出所を直した | **[reflect_remark](features/reflect_remark.md)** §11.8 |
| 2026-08-09 | — | 返事の退避を作り直した（同日中の再設計）＋システムBack対応 | **[reflect_remark](features/reflect_remark.md)** §11.7 |
| 2026-08-09 | — | 「保存ボタンを押した文章は必ず永続化する」を契約として固めた（5件） | **[reflect_remark](features/reflect_remark.md)** §11.7 |
| 2026-08-09 | — | ひとこと・映し返しの冒頭から「あなた」を外した | **[reflect_remark](features/reflect_remark.md)** §11.6 |
| 2026-08-09 | — | ひとことの実機確認4巡目を反映した（長文の扱い） | **[reflect_remark](features/reflect_remark.md)** §11 |
| 2026-08-09 | — | ひとことの実機確認3巡目を反映した（返事の保全＋1往復で閉じる＋Rediscover連携） | **[reflect_remark](features/reflect_remark.md)** §10 |
| 2026-08-09 | — | ひとことの実機確認2巡目を反映した（言語バグ＋返事欄） | **[reflect_remark](features/reflect_remark.md)** §9 |
| 2026-08-09 | — | ひとことの実機確認1巡目を反映した（4件） | **[reflect_remark](features/reflect_remark.md)** §8 |
| 2026-08-09 | — | 「AI補記メモ」を「ノートへのひとこと」へ作り直した | **[reflect_remark](features/reflect_remark.md)**（新規） |
| 2026-08-08 | — | CIでのエミュレータ実行を再検討し、見送りで確定した。あわせて「件数をトリガーにしない」を教訓へ足した（L31） | **[instrumentation_testing](system/instrumentation_testing.md) 判断4**・[lessons](lessons.md) L31 |
| 2026-08-08 | — | TEST-2 段階4a〜4c — 端末AIの生成・再生成・タブ遷移の instrumentation を10件足した | **[instrumentation_testing](system/instrumentation_testing.md)** |
| 2026-08-08 | — | instrumentation 段階1〜3の実機確認が完了した（24/24 成功・0 skipped）。あわせて「CIにエミュレータジョブを足さない」判断を撤回した | **[instrumentation_testing](system/instrumentation_testing.md)** |
| 2026-08-08 | — | TEST-2 段階3 — `NoteImageGateway` を実物のSAF・実物の `BitmapFactory` で通す instrumentation を7件足した | **[instrumentation_testing](system/instrumentation_testing.md)** |
| 2026-08-08 | — | TEST-2 段階2 — テスト用 `DocumentsProvider` を `src/debug/` へ置き、実物のSAF経路を通す instrumentation を6件足した | **[instrumentation_testing](system/instrumentation_testing.md)** |
| 2026-08-08 | — | TEST-2 に着手し、読書画面の instrumentation を4件足した（段階1）。あわせて規模感を「大」から「中」へ改めた | **[instrumentation_testing](system/instrumentation_testing.md)** |
| 2026-08-05 | — | 受付漏れ検査の抜け道・遮断器の包含判定・上限つきストリームの境界を直し、外部レビュー後に3件をクローズした | [lessons](lessons.md) L11・L14 |
| 2026-08-05 | — | TRACE-1 の実機確認をオーナー判断で省略し、課題台帳からクローズした | — |
| 2026-08-05 | — | TRACE-1 を修正した — 削除直前の再確認を三値化し、生きた読書痕跡を消し得る経路を塞いだ | **[reflect_reading_trace](features/reflect_reading_trace.md)**・[lessons](lessons.md) L28 |
| 2026-08-05 | — | §0 の1回目の判定を記録し、L29 を追記した | **[lessons](lessons.md) §0・L29** |
| 2026-08-05 | — | 文書を owner / dev / review へ再編し、レビュー指摘の受付簿と受付漏れ検査を入れた | **[review/README](../review/README.md)**・[document_map](document_map.md) |
| 2026-08-02 | — | N-3 の外部レビュー指摘7件を修正した（P1が3件） | **[note_image_rendering](features/note_image_rendering.md) §6・§7・§8・§9** |
| 2026-08-02 | — | N-3 段階5「描画」を実装し、画像が画面に出るようになった（実機確認待ち） | **[note_image_rendering](features/note_image_rendering.md) §6・§9**・**[ui_design_principles](system/ui_design_principles.md) §1** |
| 2026-08-02 | — | N-3 段階4「復号とキャッシュ」を実装した（見た目は変えない・実機確認不要） | **[note_image_rendering](features/note_image_rendering.md) §8・§9** |
| 2026-08-02 | — | N-3 段階3「画像索引」を実装した（見た目は変えない・実機確認不要） | **[note_image_rendering](features/note_image_rendering.md) §2・§7** |
| 2026-08-02 | — | N-3 段階2「パス正規化と照合規則」を純関数として実装した（見た目は変えない・実機確認不要） | **[note_image_rendering](features/note_image_rendering.md) §3・§4** |
| 2026-08-02 | — | N-3 段階1「単独行の画像をブロックとして解析する」を実装した（見た目は据え置き・実機確認不要） | **[note_image_rendering](features/note_image_rendering.md) §5・§10・§11** |
| 2026-08-02 | — | N-3（ノート内画像の表示）の設計を確定した（実装は未着手） | **[note_image_rendering](features/note_image_rendering.md)（新規）**・[markdown_rendering](system/markdown_rendering.md) |
| 2026-08-02 | — | ノートの放置期間に応じて本文の紙を生成り色へ寄せる演出を追加した（既定オフ） | **[note_age_paper](features/note_age_paper.md)（新規）**・[lessons](lessons.md) L25 |
| 2026-08-02 | — | `lessons.md` の運用を「着手前の通読」から「diff完成後の5問」へ置き換えた（コード変更なし） | [lessons](lessons.md) §0・L28 |
| 2026-08-02 | — | SYNC-2（`_ReadingTraces` 索引が外部同期の追加を認識しない）を解消した | **[reflect_reading_trace](features/reflect_reading_trace.md)（索引の鮮度）**・[lessons](lessons.md) L28 |
| 2026-08-02 | — | バッジの記号に当てる基準を 4.5:1 から 3:1 へ改め、✓ を白へ戻した。あわせて UIデザインの指針を新設した | **[ui_design_principles](system/ui_design_principles.md)（新規）**・[theme_and_ui_refactor](system/theme_and_ui_refactor.md) 判断8 |
| 2026-08-02 | — | FEAT-1 のMarkdown部分（リスト構造）を解消した | **[markdown_rendering](system/markdown_rendering.md)（新規）**・[ai_input_excerpt](system/ai_input_excerpt.md) §6 |
| 2026-08-01 | — | N-1（読書痕跡の孤児削除）の完了を受け、SYNC-1 を課題台帳から削除し roadmap の Now を空けた | [roadmap](../_wip/roadmap.md)・[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除） |
| 2026-08-01 | — | 外部レビューの指摘9件（高2・中3・軽微4）をすべて修正した | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除）**・[lessons](lessons.md) L11・L13・L14・L24 |
| 2026-08-01 | — | N-1 完了に伴い恒久文書を追随させた（コード変更なし） | [architecture](system/architecture.md)・[lessons](lessons.md) L25・L26 |
| 2026-08-01 | — | N-1 段階4「手動削除」を実装し、段階3 の実機確認を完了した（段階4 は実機確認待ち） | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の段階4）**・[roadmap](../_wip/roadmap.md) N-1 |
| 2026-08-01 | — | N-1 段階3「一覧画面（削除ボタンなし＝シャドーモード）」を実装した（実機確認待ち） | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の段階3）**・[roadmap](../_wip/roadmap.md) N-1 |
| 2026-08-01 | — | N-1 段階2「孤児判定の純関数」を実装した | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の段階2）**・[roadmap](../_wip/roadmap.md) N-1 |
| 2026-08-01 | — | N-1 段階1「痕跡の列挙API」を実装し、段階0 の実機確認完了で SCAN-1 を台帳から削除した | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の段階1）**・[roadmap](../_wip/roadmap.md) N-1 |
| 2026-08-01 | — | N-1 段階0「走査を正直にする」を実装した（SCAN-1・実機確認待ち） | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の段階0）**・[roadmap](../_wip/roadmap.md) N-1 |
| 2026-08-01 | — | SYNC-1 の着手対象を「孤児のみ削除」に確定し（オーナー判断）、段階0〜4 の計画を roadmap N-1 として起こした | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の決着）**・[roadmap](../_wip/roadmap.md) N-1 |
| 2026-08-01 | — | SYNC-1 の決着を同日中に更新した — 「手動で固定」ではなく「観測してから自動化へ昇格する段階案」を採る | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除）**・[lessons](lessons.md) L17・L24 |
| 2026-08-01 | — | SYNC-1（読書痕跡の孤児掃除）を「手動・自動を問わず採らない」で決着させ、性能懸念を PERF-1 として分離した | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の決着）**・[lessons](lessons.md) L24 |
| 2026-08-01 | — | AI-1 を実機確認まで終えてクローズし、MAINT-1 を優先度「超低」へ落とした | **[ai_input_excerpt](system/ai_input_excerpt.md)（予算調整）**・[lessons](lessons.md) L23 |
| 2026-08-01 | — | 関連ノートの抜粋予算を 600 → 800 へ上げた（AI-1 の1歩目）。予算値の変更はこれ1件のみ | **[ai_input_excerpt](system/ai_input_excerpt.md)（予算調整）**・[dependency_policy](system/dependency_policy.md) §5 |
| 2026-08-01 | — | 計測器を実機で緑にし、AI-1 の前提が誤りだったことを数字で確定した（実機確認完了） | **[ai_input_excerpt](system/ai_input_excerpt.md)（トークン計測器）**・[lessons](lessons.md) L20〜L22 |
| 2026-08-01 | — | プロンプトの実トークン余裕を測る計測器を入れ、`genai-prompt` beta4 をAARで調査した（AI-1 の前提を解消・MAINT-1 の未確認を解消） | **[ai_input_excerpt](system/ai_input_excerpt.md)（トークン計測器）**・**[dependency_policy](system/dependency_policy.md) §5** |
| 2026-08-01 | — | VERIFY-2（C案の実機確認2件）を検証不要と判断し、課題台帳から削除した | — |
| 2026-08-01 | — | `VaultBrowser` 移行（N-7 段階7）の実機確認が完了した（VERIFY-4クローズ） | **[saf_boundary_gateway](system/saf_boundary_gateway.md)** |
| 2026-08-01 | — | さがす／補記の Vault と `ContentResolver` を `VaultBrowser` へ束ね、世代照合をJVMテストで固定した（N-7 段階7） | **[saf_boundary_gateway](system/saf_boundary_gateway.md)**・[architecture](system/architecture.md)・[lessons](lessons.md) L19 |
| 2026-08-01 | — | ドキュメント参照を `DocumentRef` へ移し、`model` / `domain` / `ui` から Android を追い出した（N-7 段階1〜6） | **[saf_boundary_gateway](system/saf_boundary_gateway.md)**・[lessons](lessons.md) L18 |
| 2026-08-01 | — | N-7 の段階7（Vaultルートを controller の引数から外す）には意図的に進まない | **[saf_boundary_gateway](system/saf_boundary_gateway.md)** |
| 2026-08-01 | — | instrumentation の土台が動くことを Android 16 で実証した（TEST-1 クローズ） | — |
| 2026-08-01 | — | SAF境界を gateway の裏へ入れる設計を確定した（実装は未着手） | **[saf_boundary_gateway](system/saf_boundary_gateway.md)**・[architecture](system/architecture.md) |
| 2026-08-01 | — | 依存更新の方針を確定し、Lint更新系3チェックを `disable` から `informational`（hint）へ変えた | **[dependency_policy](system/dependency_policy.md)**・[lessons](lessons.md) L17 |
| 2026-07-31 | — | ReadingTrace v1 を完了とし、残っていた実機確認4件をクローズした | **[reflect_reading_trace](features/reflect_reading_trace.md)**・[lessons](lessons.md) |
| 2026-07-31 | — | AI補記の書込失敗時に後始末し、消せなければ残骸を伝える（F-2の2件目） | — |
| 2026-07-31 | — | 蒸留の復旧チェックを追跡Jobへ載せる（F-2の1件目） | [architecture](system/architecture.md) |
| 2026-07-31 | — | 表示用Markdownの解析をMain外へ出し、通常表示と全画面で1回だけにする（F-1） | **[architecture](system/architecture.md)** |
| 2026-07-29〜30 | — | ライト配色のAA是正とリリース構成の整備（D案・E案）。レビューで2度差し戻し、3度目で通した | **[theme_and_ui_refactor](system/theme_and_ui_refactor.md)**・[dark_mode](features/dark_mode.md) |
| 2026-07-28 | — | 現行コードの品質を再評価 | — |
| 2026-07-27 | — | AI入力の先頭固定長切り出しを、見出し骨格＋冒頭＋末尾の抜粋へ置換 | **[ai_input_excerpt](system/ai_input_excerpt.md)**（新規）・[architecture](system/architecture.md)・[related_notes_ai](features/related_notes_ai.md) |
| 2026-07-27 | — | 依存と状態の境界を型とCIで守る（B案・3段階） | [architecture](system/architecture.md) |
| 2026-07-26 | — | 入出力に用途別の予算と結果の扱いを持たせる（読込予算・累計回数の分離・保存スコープの3件） | [reflect_reading_trace](features/reflect_reading_trace.md) |
| 2026-07-26 | — | 非同期の境界を世代IDで揃える（後着する4経路とエラーの握りつぶし） | [architecture](system/architecture.md) |
| 2026-07-26 | — | 検索の世代管理 | [ai_picker](features/ai_picker.md) |
| 2026-07-26 | — | 軽量課題5件の一括消化 | — |
| 2026-07-26 | — | ダークモード（オプション画面での明示切替） | [dark_mode](features/dark_mode.md)・[theme_and_ui_refactor](system/theme_and_ui_refactor.md) |
| 2026-07-26 | — | テーマ基盤とUI構造のリファクタ（R-1〜R-4／ダークモードの土台） | [theme_and_ui_refactor](system/theme_and_ui_refactor.md)・[dark_mode](features/dark_mode.md) |
| 2026-07-25 | #35 | ReadingTrace レビュー指摘4件の修正 | [reflect_reading_trace](features/reflect_reading_trace.md) |
| 2026-07-22 | #32 | 蒸留（Distill）v1 | [reflect_distill](features/reflect_distill.md)・[reflect_reading_trace](features/reflect_reading_trace.md)・[section_ai_chat](features/section_ai_chat.md)・[lessons](lessons.md) L34 |
| 2026-07-21 | #31 | 全画面ノート（#30）の潜在不具合修正（レビュー対応） | [note_fullscreen](features/note_fullscreen.md)・[lessons](lessons.md) L34 |
| 2026-07-21 | #30 | UIUX改善 | [note_fullscreen](features/note_fullscreen.md) |
| 2026-07-21 | #29 | 関連ノートAI推薦の Phase 3 | [related_notes_ai](features/related_notes_ai.md) |
| 2026-07-20 | #28 | 関連ノートAI推薦の Phase 2 | [related_notes_ai](features/related_notes_ai.md) |
| 2026-07-20 | #27 | 関連ノートAI推薦の Phase 1 | [related_notes_ai](features/related_notes_ai.md) |
| 2026-07-20 | #26 | アプリ起動時のブランドOPアニメーションを追加 | [opening_animation](features/opening_animation.md) |
| 2026-07-20 | #25 | AI補記の途切れ対策（finishReason検知・出力要求の絞り込み）／クイズを「フォーカスセクション周辺から2問」へ再設計し入口を吹き出しシートへ移動（AIタブのQ&Aボタン廃止・バッジ補記のみ）… | [section_ai_chat](features/section_ai_chat.md)・[background_ai_ux](system/background_ai_ux.md)・[architecture](system/architecture.md) |
| 2026-07-20 | #24 | PR #23のレビュー対応3件（デッドコード削除・Snackbarイベントキーのテスト可能化・画面回転での通知再表示抑止）／Vault選択をオプションへ移動／全画面✕ボタンの視認性修正／当日分の閲覧履… | [background_ai_ux](system/background_ai_ux.md)・[tab_navigation](system/tab_navigation.md)・[ai_picker](features/ai_picker.md) |
| 2026-07-20 | #23 | Q&A・AI補記のバックグラウンド生成UX | [background_ai_ux](system/background_ai_ux.md) |
| 2026-07-19 | #22 | AI要約の生成中もノート閲覧を継続可能に（要約待ちのブロッキング解消） | [background_ai_ux](system/background_ai_ux.md) |
| 2026-07-19 | #21 | 壁打ちUI改善: 読む画面の低彩度グラデーション（ReadingGradient）・ボタン配色3役ルールの明文化・ノート出現アニメーション・検索結果への更新日表示 | — |
| 2026-07-19 | #16〜#20 | コード品質改善活動 | [architecture](system/architecture.md) |
| 2026-07-18 | #15 | AIピッカー「さがす🔎」タブ | [ai_picker](features/ai_picker.md) |
| 2026-07-17 | #14 | セクション単位AIチャット | [section_ai_chat](features/section_ai_chat.md) |
| 2026-07-16 | #13 | AI補記メモの削除機能（オプション画面を新設） | — |
| 2026-07-16 | #12 | UIをタブ・ナビゲーション構成に再設計（Plan C） | [tab_navigation](system/tab_navigation.md) |
| 2026-06-19 | #11 | AI補記プロンプト改善・UI名称変更・表示整理 | — |
| 2026-06-19 | #10 | Android 17対応・ステータスバー非表示・UI調整 | — |
| 2026-05-31 | #9 | AI補記メモ機能の追加・AI推薦の改善 | — |
| 2026-05-31 | #8 | Q&Aフラッシュカード（オンデバイス4択生成）・グラフビュー・関連ノート改善 | — |
| 2026-05-30 | #7 | リファクタリング: Activity分割・ノート走査のスタック安全化 | — |
| 2026-05-30 | #6 | Fold展開時に関連ノートTop-5を左ペイン表示・タップで本文切替 | — |
| 2026-05-30 | #5 | Gemini Nano 4によるオンデバイスノート要約（ML Kit GenAI Prompt API） | — |
| 2026-05-30 | #4 | Markdownレンダリング強化（見出し・リスト・コード・引用・テーブル等） | — |
| 2026-05-11 | #2, #3 | アプリ名を「Obsidian Mind」へ変更、旧View系リソース整理、ソースコード解析書の整備 | — |
| 2026-05-10 | #1 | Jetpack Compose移行 | — |
| 2026-04-30〜05-10 | — | プロジェクト開始 | [project_origin.md](../owner/project_origin.md) |
