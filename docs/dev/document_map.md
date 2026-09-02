# 文書の地図と運用ルール

**この文書は「作るための知識」の索引。** プロダクトの俯瞰・技術俯瞰・開発日誌は [owner/](../owner/)、
フォルダ全体の入口は [docs/README.md](../README.md) が持つ。

`docs/` は4つのフォルダへ分けている。**分類の軸は「その文書が答える問い」。**

| フォルダ | 答える問い |
|---|---|
| `owner/` | このアプリは何で、いまどうなっていて、どうやってここまで来たか。**更新はオーナーが指示したときだけ・検査に載せない**（→ [owner/README](../owner/README.md)） |
| `dev/` | いま何が有効な判断で、何を繰り返してはいけないか |
| `review/` | 外から見てどう評価されたか、その指摘はどうなったか、Codexが実機でどう確かめるか |
| `_wip/` | まだ決まっていないこと（**寿命で切ってある**。リリース時にまとめて捨てる） |

> **`owner/` と `dev/` は「コードを読めば再現できるか」で分ける。**
> 現況の解析・規模・データフローは再現できるので `owner/`。
> 「なぜその判断にしたか」「何を試して駄目だったか」はコードに書かれていないので `dev/`。
> **難易度で分けていない** — `owner/` にも技術的な内容が入る。

---

## 0. どの問いをどこが持つか

| 問い | 置き場 | 寿命 |
|---|---|---|
| **何をいつ変えたか** | [change_history.md](change_history.md)（**1文100字以内の索引**） | 累積（消さない） |
| **なぜそうしたか（現在有効な判断）** | [features/](features/)（機能）・[system/](system/)（基盤） | 判断ごとに1本。**現在形で書く** |
| **なぜその重大判断をしたか** | [decisions/](decisions/)（ADR。**30行以内＝`AdrShapeTest` が固定**） | 覆りにくいものだけ |
| **どうやってそこへ至ったか** | [owner/journal/](../owner/journal/) | 当時の記録。古くなってよい |
| **いまコードがどうなっているか** | [owner/source_code_analysis.md](../owner/source_code_analysis.md) | 測定日つきスナップショット |
| **同じ失敗を繰り返さないために** | [lessons.md](lessons.md)（索引）＋ [lessons/](lessons/)（カード） | 累積（**IDは永久の住所**。振り直さない） |
| **外部からの評価と指摘の追跡** | [review/](../review/README.md) | 最新1本＋未解決の受付簿 |
| **Codexが実機でどう検証するか** | [review/device_validation/](../review/device_validation/) | 共通手順＋機能別ケース（結果は持たない） |
| **まだ決まっていないこと** | [_wip/](../_wip/) | **実機検証まで終わったら削除する** |

補助として、出発点の記録 [project_origin.md](../owner/project_origin.md) がある。

> **設計書と日誌の線引き（2026-08-10 に整理）。**
> 設計書には**現在有効な判断とその理由・契約・受理条件・意図的にやらないこと**だけを置く。
> 「実機確認1巡目・2巡目…」「段階1完了」「当時の未実装状況」は日誌へ移した。
> **同じ事件を設計書・日誌・change_history へ3回とも長文で書かない。**

---

## 1. 恒久記録（`owner/` `dev/` `review/`）

| 文書 | 役割 | 更新契機 |
|---|---|---|
| [change_history.md](change_history.md) | PR単位の変更履歴（新しい順） | **PRごとに1行追記** |
| [review/](../review/) | 最新の外部レビュー1本（未追跡）、未解決指摘の受付簿、Codex実機検証の恒久手順 | レビュー更新時。機能契約・実機ケースを変更したときは `device_validation/` も同時更新 |
| [lessons.md](lessons.md) | **教訓の索引**（ID／一文／いつ当てるか／**検査の有無**）。長い教訓は [lessons/](lessons/) にカードとして1件1ファイル。**最大番号は書かない**（L1以降） | 同じ形の失敗を2度した／構造上また起きると判断したとき |
| [project_origin.md](../owner/project_origin.md) | 2026-04-30 の第一歩の報告書 | 更新しない（起点の記録） |

> **解析書と総評の違い:** 解析書は「事実の網羅」、総評は「ある時点の採点」。

---

## 2. 設計文書（`dev/features/` `dev/system/` `dev/decisions/`）

**種別で3つに分けている（2026-08-11）。** かつては `dev/design/` 1つに全部を置いていたが、
**24本中11本が `## 判断N` 形式＝ADRの形**で、機能仕様・基盤設計・重大判断が同居していた。
同じ場所に混ぜると役割が曖昧になるので、**答える問いで割った。**

| フォルダ | 答える問い | 書かないこと |
|---|---|---|
| [features/](features/) | この機能で**何ができて、どう実現しているか** | 横断的な基盤の話 |
| [system/](system/) | 全機能に効く**責務・保証・不変条件・利用者** | ユーザーフロー |
| [decisions/](decisions/) | **なぜその重大判断をしたか**（文脈・決定・帰結） | 設計の詳細（正本は上2つ） |

> **ADRに設計の写しを置かない。** `decisions/` は「なぜ」の索引で、**詳細の正本は必ず
> `features/` か `system/` 側**。正本が2つに割れると、どちらかが必ず古くなる。
> **機能追加のたびにADRを作らない** — 覆りにくく、後から「なぜ？」となる判断だけ。

> **状態はここに書かない。** かつて「実装済み／未着手」の列を持っていたが、
> **正本（各文書の `**状態:**` 行）と二重管理になり、実際に古くなった**
> （画像表示は実装・実機確認まで終わっているのに、ここだけ「実装未着手」が残っていた）。
> **本書は索引に徹し、状態は各文書の冒頭が持つ。**

### 機能（`features/`）

**内訳は機能仕様16本＋参照シート1本。**

- **機能仕様16本**は [`_template.md`](features/_template.md) の12節に揃っている。
  **節の存在と、空でないことの両方を `AdrShapeTest` が固定する** —
  埋まっていない節は空欄ではなく `> **未確認:**` か `> **該当なし:**` で理由を書く
- **参照シート1本**（[character_vigilith](features/character_vigilith.md)）は12節に従わない。
  造形・世界観・作画基準であってフローも状態も持たないため、**検査の例外として明示的に列挙**してある。
  同種の資料（ブランドガイド・文体規定など）が2〜3本に増えたら `reference/` へ独立させる合図


| 文書 | 対象 |
|---|---|
| [rediscover.md](features/rediscover.md) | **Rediscover（ランダム表示）。アプリの入口であり心臓** |
| [note_summary.md](features/note_summary.md) | ノート要約（主軸のAI機能・自動起動） |
| [ai_picker.md](features/ai_picker.md) | さがすタブ（検索・ランダム・履歴） |
| [related_notes_ai.md](features/related_notes_ai.md) | 関連ノートAI推薦 |
| [section_ai_chat.md](features/section_ai_chat.md) | セクションAI（浮遊吹き出し。**クイズの入口でもある**） |
| [reflect_distill.md](features/reflect_distill.md) | 蒸留（Distill） |
| [distill_range_adjust.md](features/distill_range_adjust.md) | 蒸留の太字範囲をユーザーが調整する。**段階1（プリセット）実装済み・段階2（自由範囲）未着手**（畳むのは段階2完了後） |
| [reflect_reading_trace.md](features/reflect_reading_trace.md) | ReadingTrace（読書痕跡・サイドカー） |
| [reflect_remark.md](features/reflect_remark.md) | ノートへのひとこと（旧「AI補記メモ」） |
| [quiz.md](features/quiz.md) | クイズ（Q&A。**未確認管理を持つ唯一の機能**） |
| [note_fullscreen.md](features/note_fullscreen.md) | 全画面ノート（独立ルート化） |
| [note_image_rendering.md](features/note_image_rendering.md) | ノート内画像の表示（パス解決・復号・描画） |
| [dark_mode.md](features/dark_mode.md) | ダークモード |
| [note_age_paper.md](features/note_age_paper.md) | ノートの年代を紙の地色で伝える |
| [opening_animation.md](features/opening_animation.md) | 起動OPアニメーション |
| [character_vigilith.md](features/character_vigilith.md) | **参照シート（12節の例外）。** キャラクターの造形・世界観・作画基準 |
| [vigilith_in_app.md](features/vigilith_in_app.md) | アプリ内Vigilith（読書相手の身体化） |
| [booklet_mode.md](features/booklet_mode.md) | 冊子モード（10枚束ねて捲る）。**実装済み・実機検証完了** |
| [reading_trace_backup.md](features/reading_trace_backup.md) | 読書痕跡の退避と復元（エクスポート／インポート） |
| [reunion_card.md](features/reunion_card.md) | 再会カードに何を出すか（枠の排他・種別・優先順位）。**未実装・設計確定** |

### 基盤（`system/`）

| 文書 | 対象 |
|---|---|
| [architecture.md](system/architecture.md) | ViewModel分割・状態管理・並行処理の規約 |
| [saf_boundary_gateway.md](system/saf_boundary_gateway.md) | SAF境界の gateway 化（`Uri` の不透明化） |
| [ai_input_excerpt.md](system/ai_input_excerpt.md) | AI入力（**抜粋経路8本**へ渡す本文の作り方＋**完成プロンプト11本**を閉じる上限） |
| [background_ai_ux.md](system/background_ai_ux.md) | AI生成の待ち時間と結果通知 |
| [markdown_rendering.md](system/markdown_rendering.md) | Markdown解析の準拠先とリスト構造 |
| [tab_navigation.md](system/tab_navigation.md) | 画面構成・ナビゲーション（Plan C） |
| [ui_design_principles.md](system/ui_design_principles.md) | **UIデザインの指針（国際規約＋好み）。見た目に触る前に読む** |
| [bearing_channels.md](system/bearing_channels.md) | **佇まいのチャネル割り当て**（色＝年代／形＝面の役割／位置＝分類…）。装飾を足す前に読む |
| [theme_and_ui_refactor.md](system/theme_and_ui_refactor.md) | テーマ基盤とUI構造のリファクタ（R-1〜R-4）と判断1〜8 |
| [instrumentation_testing.md](system/instrumentation_testing.md) | 実端末を通すテストの段階分け・実物SAFの作り方・実行の運用 |
| [dependency_policy.md](system/dependency_policy.md) | 依存更新の方針とLint更新系チェックの扱い |

### 重大判断（`decisions/`）

[decisions/README.md](decisions/README.md) が一覧を持つ。

---

---

## 3. 未確定（`_wip/`）

**ここにある内容は「まだ終わっていない」ことを意味する。** 解消した項目はこのフォルダから削除する（記録は §1 の恒久記録に残る）。

> **`_wip/` はリリース時点で中身をまとめて廃棄する一時置き場**（ファイル数は増減する）。そのため
> **恒久文書から `_wip/` の項目IDへ依存しない** — `SYNC-2` のような番号を設計書や記録から参照すると、
> 廃棄した瞬間に意味が消える。課題に触れるときは、リンクや項目番号ではなく**内容そのものを書く**
> （例: `**未解決:** current_issues 3-14` ではなく `**未解決:** instrumentation テストの構成が未定義`）。
> この向きを守っている限り、`_wip/` はいつ捨てても、中の番号をいつ振り直しても、恒久文書は壊れない。
>
> **入口・索引はフォルダとして案内してよい**（`docs/README.md`・本書・`review/README.md`）。
> 廃棄時に索引ごと直せばよく、項目IDに依存していないため壊れ方が局所で済む。
> **禁じているのは「番号への依存」であって「存在への言及」ではない。**
>
> **例外は [`review/2026-*.md`](../review/README.md) の最新レビューのみ。**
> レビュアーが書くスナップショットなので、残している間はこちらから手を入れない。
> 新しいレビューを受け付けたら前の本文は削除する。**本文はコミットしない**ので履歴にも残らない。
> 指摘の存在と処遇は [`review/findings.md`](../review/findings.md) が引き受ける。
> `_wip/` 廃棄時にリンク切れが残るが、当時の記録なので許容する。
> **`review/` の他のファイル（`README.md`・`review_template.md`・`findings.md`）は
> こちらが持つので、通常どおり更新する。**

| 文書 | 役割 |
|---|---|
| [current_issues.md](../_wip/current_issues.md) | 課題台帳。**「いま何が壊れているか」だけ**を持ち、順序は書かない。未対応のものだけを残し、**実機検証まで終わったら削除する**（実装完了では消さない）。IDはカテゴリ記号（TEST-1・AI-1 など） |
| [roadmap.md](../_wip/roadmap.md) | Now / Next / Later。日付を切らず優先度と成熟度で3段。**使い捨て**（完了項目は取り消し線を残さず削除する） |
| [feature_ideas.md](../_wip/feature_ideas.md) | 未実装の採用候補（使い捨て。実装済み・却下は残さない） |
| `plan_*.md` | **特定作業の実装計画**（使い捨て。着手して終わったら削除する）。現在2本 |

---

## 4. 運用ルール

1. **PRごとに** [change_history.md](change_history.md) へ1行追記する。設計判断や試行錯誤があった変更だけ `features/` か `system/` に対応文書を作成／追記し、履歴表からリンクする。
2. **解析書・総評で「問題」と書いたものは、必ず [_wip/current_issues.md](../_wip/current_issues.md) に起票する。** 書いただけでは追跡されない。
   ただし**起票先へのリンクや番号は恒久文書側に残さない**（§3 の一方通行ルール）。恒久文書には問題の内容だけを書く。
3. **`_wip/` の項目は実機検証まで終わったら削除する。** 実装完了では消さない（検証待ちが消えると誰も確認しなくなる）。残すと未対応の課題が埋もれる。
4. **修正の主張は、修正コードを別の目で読むまで確定させない。** 方針が正しいと文書とコミットメッセージだけ通ってしまう（→ [L26](lessons/L26.md)・[L34](lessons/L34.md)）。
5. **`features/` `system/` の各文書には `**状態:**` 行を置く。** 実装済みか構想段階かが本文を読まずに分かるようにする。

> 各文書の内部ルール（課題番号の扱いなど）はその文書の冒頭が持つ。ここには集約しない。

---

## 5. 目的別の入口

| したいこと | 読む順 |
|---|---|
| プロジェクトを初めて把握する | [owner/source_code_analysis.md](../owner/source_code_analysis.md) §1〜§4 → [system/architecture.md](system/architecture.md) |
| 次に何を作るか決める | [_wip/roadmap.md](../_wip/roadmap.md) → [_wip/current_issues.md](../_wip/current_issues.md) → [_wip/feature_ideas.md](../_wip/feature_ideas.md) |
| 品質改善に着手する | [レビュー一覧](../review/README.md) → [_wip/current_issues.md](../_wip/current_issues.md) |
| Codexが実機検証する | [共通手順](../review/device_validation/README.md) → 対象機能のケース → 該当する `features/` / `system/` の正本 |
| 既存コードを触る前に背景を知る | 下の逆引き表 → 該当する `features/` か `system/` |
| バグを踏んだ | [lessons.md](lessons.md) の索引 →（AI混入バグなら）[L34](lessons/L34.md) で型を判定 |

### パッケージ → 先に読む設計書（逆引き）

`features/` `system/` はパッケージ単位に分かれていないので、コードから引くときはこの表を使う。

| 触るパッケージ | 先に読む |
|---|---|
| `NoteViewModel.kt` / `controller/NoteSessionCoordinator.kt` / `model/NoteUiStateStore.kt` | [architecture](system/architecture.md) → 該当機能の設計書 |
| `controller/` | [architecture](system/architecture.md) → 該当機能の設計書 |
| `ai/PromptBuilder.kt` | [ai_input_excerpt](system/ai_input_excerpt.md) → 該当機能の設計書 |
| `ai/` | [background_ai_ux](system/background_ai_ux.md) → [reflect_distill](features/reflect_distill.md) / [related_notes_ai](features/related_notes_ai.md) |
| `domain/NoteExcerptBuilder.kt` / `model/NoteExcerptLimits.kt` / `model/PromptLimits.kt` / `ai/PromptBudget.kt` | [ai_input_excerpt](system/ai_input_excerpt.md) |
| `domain/markdown/` / `ui/markdown/` | [markdown_rendering](system/markdown_rendering.md) → [ai_input_excerpt](system/ai_input_excerpt.md)（同じパーサがAI入力にも効くため） |
| `ui/markdown/` の画像・画像索引・復号 | [note_image_rendering](features/note_image_rendering.md) → [markdown_rendering](system/markdown_rendering.md) |
| `domain/` | [related_notes_ai](features/related_notes_ai.md) / [reflect_distill](features/reflect_distill.md) |
| `data/` | [reflect_reading_trace](features/reflect_reading_trace.md)（サイドカー）/ [reflect_distill](features/reflect_distill.md)（原子性・復旧） |
| `androidTest/` | [instrumentation_testing](system/instrumentation_testing.md)（何をここへ置くかの基準） |
| `model/NoteUiState.kt` / `model/state/` | [architecture](system/architecture.md) / [tab_navigation](system/tab_navigation.md) |
| `model/` の共有データ型 | [architecture](system/architecture.md) → 該当機能の設計書 |
| `ui/theme/`・見た目に触る変更全般 | **[ui_design_principles](system/ui_design_principles.md)（先に読む）** → [theme_and_ui_refactor](system/theme_and_ui_refactor.md) → [dark_mode](features/dark_mode.md) |
| **情報を色・形・動きで伝える変更**（装飾を足す・分類を見せる・演出を足す） | **[bearing_channels](system/bearing_channels.md)（どのチャネルが何を意味するか）** → [ui_design_principles](system/ui_design_principles.md) |
| `ui/theme/` の `panel` 系トークン・読書画面の地色 | [ui_design_principles](system/ui_design_principles.md) → [note_age_paper](features/note_age_paper.md) |
| `ui/vigilith/` | [character_vigilith](features/character_vigilith.md) → [vigilith_in_app](features/vigilith_in_app.md) → [opening_animation](features/opening_animation.md) |
| `ui/component/ReadingTraceCard.kt`・再会カードのAI枠 | **[reunion_card](features/reunion_card.md)（枠の排他・種別・優先順位）** → [reflect_reading_trace](features/reflect_reading_trace.md) |
| `ui/screen/` | [tab_navigation](system/tab_navigation.md) / [note_fullscreen](features/note_fullscreen.md) / [section_ai_chat](features/section_ai_chat.md) |
| `app/build.gradle.kts` の依存宣言・`gradle/wrapper` | [dependency_policy](system/dependency_policy.md) |
| `data/SafDocuments.kt` / `data/VaultBrowser.kt` / `model` の参照型 | [saf_boundary_gateway](system/saf_boundary_gateway.md) → [architecture](system/architecture.md) |
