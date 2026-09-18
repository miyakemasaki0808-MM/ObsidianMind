# 文書一覧

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-08 / **更新:** 2026-09-18（基準 `03cc839`）

**位置づけ:** このリポジトリにどんな文書があり、それぞれ何を答えるかを一望する1枚。
`owner/` の他文書と同じく、指示があったときに通しで見直す → [README](README.md)。
検査には載せない。文書を1本足すたびに本書が古くなるが、それは CI で止める種類のずれではない。

**正本の所在はここではない。** 運用ルールの正本は [dev/document_map.md](../dev/document_map.md)、
憲法は [CLAUDE.md](../../CLAUDE.md) が持つ。本書は目録である。

---

## 1. 全体

**追跡対象の Markdown は 125本・約21,400行。** `CLAUDE.md`・`README.md` と `docs/` 配下の123本を数えた。
作業ツリーにはこの他に追跡しないものが8本ある。レビュー本文1本、Fable 5.1 の報告書5本、実機検証の証跡フォルダの引き継ぎメモ2本。

```
CLAUDE.md                  開発規約（憲法）
README.md                  リポジトリの入口
docs/
├── README.md              文書の入口
├── owner/     (16本)      オーナーが読む俯瞰。検査に載せない
│   ├── journal/  (4本)    開発日誌。README＋月別3本
│   └── Fable5.1_report/   評価報告書。git 管理外の特別枠
├── dev/       (83本)      判断の正本。ここが古くなると実害が出る
│   ├── features/  (24本)  ユーザーから見える機能。README・様式＋仕様22本
│   ├── system/    (13本)  横断的な基盤。README＋12本
│   ├── decisions/  (6本)  ADR。README＋5本
│   └── lessons/   (36本)  教訓65件のうち、カードを持つ36本
├── _wip/       (4本)      進行中。リリース時に廃棄する
└── review/    (19本)      レビューと実機検証。ほかに追跡しない本文1本
    └── device_validation/ (16本)  共通手順・簡易版・機能別ケース14本
```

2026-09-14 の前回目録から3本増えた。実機ケース2本（要約の保存・起動の再生成）と、
`owner/` の実装設計の下書き1本（反証の一文）である。

## 2. 場所ごとの役割

| 場所 | 答える問い | 誰が書くか | 検査 |
|---|---|---|---|
| `CLAUDE.md` | 常時効く原則・禁止事項・完了条件 | 全員 | 入口の存在のみ。`DeviceValidationDocsTest` |
| `docs/owner/` | 何ができるアプリか。いまどうなっているか | **オーナーの依頼時だけ** | **無し。意図的** |
| `docs/dev/features/` | ユーザーから見える機能の仕様と実現方法 | 実装者 | `DesignDocStateNameTest`・`SourceDocSyncTest`・`SchemaVersionDocsTest`・`AdrShapeTest`（12節の形） |
| `docs/dev/system/` | 横断的な基盤の責務・保証・不変条件 | 実装者 | 同上 |
| `docs/dev/decisions/` | なぜその判断にしたか。索引 | 実装者 | `AdrShapeTest`。30行以内 |
| `docs/dev/lessons/` | 次に同じ形の失敗をしないための判断材料 | 実装者 | 一部の教訓のみ → §6 |
| `docs/_wip/` | いま何が壊れているか。何をどの順でやるか | 実装者 | `WipIssueReferenceTest` |
| `docs/review/` | 未解決の指摘と、実機検証の手順 | レビュアーとこちら | `ReviewFindingsLedgerTest`・`DeviceValidationDocsTest` |

**線引きは「コードを読めば再現できるか」。** 現況の解析は再現できるので `owner/`、
「なぜその判断にしたか」「何を試して駄目だったか」はコードに書かれていないので `dev/`。

---

## 3. `docs/dev/features/` — 機能仕様。22本＋README＋様式

| 文書 | 機能 | 状態 |
|---|---|---|
| [rediscover](../dev/features/rediscover.md) | Rediscover | 稼働中。アプリの入口 |
| [reflect_reading_trace](../dev/features/reflect_reading_trace.md) | 読書痕跡 | 稼働中。サイドカーは schema v6 |
| [reunion_card](../dev/features/reunion_card.md) | 再会カードに何を出すか | 実装済み・実機検証済み |
| [note_summary](../dev/features/note_summary.md) | ノート要約 | 稼働中。主軸のAI。**同じ入力の要約は端末に保存する** |
| [related_notes_ai](../dev/features/related_notes_ai.md) | 関連ノートAI推薦 | 稼働中 |
| [ai_picker](../dev/features/ai_picker.md) | さがす | 稼働中 |
| [section_ai_chat](../dev/features/section_ai_chat.md) | セクションAI | 稼働中 |
| [quiz](../dev/features/quiz.md) | クイズ | 稼働中。未確認管理を持つ唯一の機能 |
| [reflect_remark](../dev/features/reflect_remark.md) | ノートへのひとこと | 稼働中 |
| [reflect_distill](../dev/features/reflect_distill.md) | 蒸留 | v1 Phase 1〜6＋句分割＋括弧内語句 |
| [distill_range_adjust](../dev/features/distill_range_adjust.md) | 蒸留の太字範囲の調整 | 段階1のプリセットまで実装・実機済み |
| [booklet_mode](../dev/features/booklet_mode.md) | 冊子モード | **完了。** 佇まい・めくり・編む冊子まで実機で受理。920行で最大の文書 |
| [note_field_color](../dev/features/note_field_color.md) | 冊子の分野色 | 実装済み。主要経路を実機確認。**2026-09-09〜12 に新設** |
| [note_image_rendering](../dev/features/note_image_rendering.md) | ノート内画像の表示 | 稼働中・実機確認済み |
| [note_fullscreen](../dev/features/note_fullscreen.md) | 全画面ノート | 稼働中 |
| [note_age_paper](../dev/features/note_age_paper.md) | 年代の紙色 | 稼働中・実機確認済み。既定オフ |
| [reading_trace_backup](../dev/features/reading_trace_backup.md) | 読書痕跡の退避と復元 | 実装済み・稼働中 |
| [character_vigilith](../dev/features/character_vigilith.md) | キャラクターシート | Adopted。12節に従わない参照シート |
| [vigilith_in_app](../dev/features/vigilith_in_app.md) | アプリ内 Vigilith | 稼働中。実機の目視だけ端末の認証ロックで未了 |
| [opening_animation](../dev/features/opening_animation.md) | 起動OP | 稼働中。ランチャー重複起動のガードは判断7 |
| [dark_mode](../dev/features/dark_mode.md) | ダークモード | 稼働中・実機確認済み |
| [sealed_reply](../dev/features/sealed_reply.md) | 封をした返事 | **Draft。未実装。** Fable のアイデア帳から深掘りした下書き |
| [README](../dev/features/README.md) ／ [_template](../dev/features/_template.md) | 索引と様式 | — |

## 4. `docs/dev/system/` — 基盤設計。12本＋README

| 文書 | 対象領域 |
|---|---|
| [architecture](../dev/system/architecture.md) | ViewModel分割・状態管理・並行処理。全変更に効く。常時読み込む |
| [ui_design_principles](../dev/system/ui_design_principles.md) | UIデザインの指針。UIを触る前に読む |
| [bearing_channels](../dev/system/bearing_channels.md) | 佇まいのチャネル割り当て。形・色・動きの役割 |
| [theme_and_ui_refactor](../dev/system/theme_and_ui_refactor.md) | テーマ基盤と配色コントラスト |
| [background_ai_ux](../dev/system/background_ai_ux.md) | AI生成のバックグラウンドUX。AICore の短期回数制限もここ |
| [ai_input_excerpt](../dev/system/ai_input_excerpt.md) | AI入力の抜粋と予算 |
| [ai_quality_measurement](../dev/system/ai_quality_measurement.md) | AI出力の採点。要約が原文のどこを落としたか。**2026-09-13 に新設** |
| [saf_boundary_gateway](../dev/system/saf_boundary_gateway.md) | SAF境界の不透明化。`model` を葉に保つ |
| [markdown_rendering](../dev/system/markdown_rendering.md) | Markdown解析の準拠先 |
| [tab_navigation](../dev/system/tab_navigation.md) | タブ・ナビゲーション |
| [instrumentation_testing](../dev/system/instrumentation_testing.md) | instrumentation テストの方針 |
| [dependency_policy](../dev/system/dependency_policy.md) | 依存更新の方針。成果物の権限を数える判断もここ |

2026-09-12 に6本を実装と全文突合し、最終検証の日付を9月へ揃えた。1ヶ月「本文未突合」だった状態は解消した。

## 5. `docs/dev/decisions/` — ADR。5本

**30行以内を `AdrShapeTest` が固定する。** 詳細の正本は `features/`・`system/` 側にあり、ADRはそこへリンクする。

| ADR | 判断 |
|---|---|
| [0001](../dev/decisions/ADR-0001-single-viewmodel-controllers.md) | 単一ViewModel＋機能Controller方式を採る |
| [0002](../dev/decisions/ADR-0002-on-device-ai-only.md) | AIはオンデバイスのみ。ネットワーク権限を持たない。推移依存が持ち込む権限は成果物で数える |
| [0003](../dev/decisions/ADR-0003-opaque-saf-references.md) | SAF参照を不透明化し `model` を依存グラフの葉に保つ |
| [0004](../dev/decisions/ADR-0004-do-not-rewrite-vault-body.md) | Vault本文を書き換えない。例外は蒸留の太字化のみ |
| [0005](../dev/decisions/ADR-0005-bearing-channel-allocation.md) | 佇まいのチャネルは1つの意味だけに割り当てる |

## 6. `docs/dev/lessons/` — 教訓。65件

**[索引](../dev/lessons.md) が本体で、カードは詳細。** 索引の「いつ当てるか」列を引き、該当したカードだけを読む。
中身の棚卸しは [lessons_summary](lessons_summary.md) が持つ。

| 数え方 | 件数 |
|---|---|
| 教訓の総数 | **65。** `L1`〜`L66` で、欠番は `L62` の1つ。取り下げ |
| 独立カードがあるもの | 36 |
| 索引の中に本文があるもの | 29。`L1`〜`L18`・`L21`・`L27`・`L33`・`L37`・`L43`・`L54`・`L57`・`L63`〜`L66` |
| 検査を名指ししているもの＝規約 | **26** |
| 検査を持たないもの＝参考 | **39** |
| うち索引の「検査」列が埋まっている行 | 23。`L7`・`L9`・`L13` は本文に検査があるのに列が空 |
| 役目を終えたもの。転送だけを持つ | 2。`L12`・`L15` |
| 索引の行数 | 52。`L1`〜`L16` を1行に畳んでいるので件数とは一致しない |

**教訓の追加条件は 2026-09-12 に「同じ形の失敗を2度したとき」へ戻した。** 1度目はどこにも書かない。
例外は検査を同時に置けるときだけ。9月前半だけで9件増えて翌日取り下げも出たためである。

> **検査列が空の教訓は、規約ではなく参考である。** このリポジトリで規則が守られたのは
> 検査に載せたときだけ、というのが実績 → `L29`。両者を同じ強さで扱わない。

## 7. `docs/_wip/` — 進行中。4本・リリース時に廃棄

| 文書 | 答える問い |
|---|---|
| [current_issues](../_wip/current_issues.md) | いま何が壊れている／足りないのか。現在10件。順序は書かない |
| [roadmap](../_wip/roadmap.md) | 何をどの順でやるか。Now／Next／Later |
| [feature_ideas](../_wip/feature_ideas.md) | まだ作っていない機能の候補。758行・使い捨て |
| [fable51_triage](../_wip/fable51_triage.md) | Fable 5.1 の課題候補29件の処遇。今回限りの特別枠。残21件 |

**恒久文書から `_wip/` の項目IDを参照しない。** 廃棄した瞬間に意味が消えるため。
外から読んだ分析は [wip_analysis](wip_analysis.md) が持つ。

## 8. `docs/review/` — レビューと実機検証。19本＋追跡しない本文1本

| 文書 | 役割 | 追跡 |
|---|---|---|
| [README](../review/README.md) | レビューの入口と運用。一覧は 96行。7月2・8月47・9月47 | ✅ |
| [findings](../review/findings.md) | 未解決指摘の受付簿。現在3件 | ✅ |
| [review_template](../review/review_template.md) | レビュー本文の様式 | ✅ |
| `2026-*.md` | 最新レビュー本文1本だけ。書き換えない | ❌ 未追跡 |
| [device_validation/README](../review/device_validation/README.md) | Codex実機検証の共通手順 | ✅ |
| [device_validation/quick_check](../review/device_validation/quick_check.md) | 実機検証の簡易版。選抜規則とスモークセット | ✅ |
| device_validation の機能別ケース14本 | 冊子・蒸留・退避・画像・再会・AI状態UX・AI予算・起動・起動の再生成・分野色・返事の保存・ネットワーク権限・要約の基準線・要約の保存。結果は持たない | ✅ |
| `device_validation/evidence/` | スクリーンショット・UIダンプ・引き継ぎメモ | ❌ 未追跡 |

## 9. `docs/owner/` — オーナーが読む俯瞰。16本

| 文書 | 答える問い |
|---|---|
| [README](README.md) | 何ができるアプリか |
| [source_code_analysis](source_code_analysis.md) | いまコードがどうなっているか。最大の文書 |
| [jvm_test_report](jvm_test_report.md) | どういう観点でテストしているか |
| [lessons_summary](lessons_summary.md) | 教訓65件に何が書かれているか |
| [readme_map](readme_map.md) | 16本ある README がそれぞれ何をしているか |
| [wip_analysis](wip_analysis.md) | `_wip/` の4本は何を抱えているか。観察と提案 |
| [idea_catalog](idea_catalog.md) | Fable は何を足せると考えるか。4象限×10件 |
| [rebuttal_sentence_design](rebuttal_sentence_design.md) | 反証の一文をどう実装するか。**引き渡しの粒度の下書き。実装後も更新しない** |
| [comments_and_history_practices](comments_and_history_practices.md) | コメント・設計書・経緯をどこに置くか |
| [project_chronology](project_chronology.md) | どう歩んできたか。年表 |
| **本書** | どんな文書があるか |
| [project_origin](project_origin.md) | どこから始まったか。2026-04-30。更新しない |
| [journal/](journal/) | どうやってここまで来たか。2026-07・08・09 |

`Fable5.1_report/` は git 管理外。入口 README と章3本・課題一覧、`完了/` に計測値がある。本書の数には入れない。

---

## 10. 検査で守られている範囲

**文書のどこが CI で守られ、どこが人任せか**の対応表。見直しの起点になる。

| 検査 | 何を落とすか | 対象 |
|---|---|---|
| `DesignDocStateNameTest` | 消した型・値の名前を現役のように書いている | `dev/features/` `dev/system/` |
| `SourceDocSyncTest` | 状態欄が正本の一覧に無い。KDocの相対リンクが切れている。文書がソースを行番号で指している | `dev/` とソース |
| `SchemaVersionDocsTest` | 文書の「現行スキーマ版」がコードの定数と違う | 痕跡系3本 |
| `AdrShapeTest` | ADRが30行を超えた。機能仕様の12節が欠けている。最終検証が実在しないコミットを指す | `dev/decisions/` `dev/features/` |
| `WipIssueReferenceTest` | 台帳から消したIDを他が参照している。コードが課題IDを参照している | `_wip/` と `app/src/` |
| `ReviewFindingsLedgerTest` | レビュー指摘が受付簿に無い。処遇が空。本文が2本ある | `review/` |
| `DeviceValidationDocsTest` | 実機ケースの形が不完全。件数が実数と違う。簡易版が実在しないケースを指す | `review/device_validation/` |
| `DeviceProbeResidueTest` | 使い捨ての一時テストが残っている | `app/src/androidTest/` |
| `SourceCommentShapeTest` | KDocが2つ続いて宙に浮いている。本番コメントに日付やレビューの指摘番号がある | `app/src/` 3ソースセット |
| `BackupExclusionTest` | 端末に残す置き場がバックアップ除外に載っていない | `res/xml/` とソース |
| `verify<Variant>ManifestPermissions`（Gradle） | マージ後マニフェストの権限が期待集合と違う | ビルド出力。CI は release も名指しで呼ぶ |

**守られていないもの:**

- **`docs/owner/` 全体。** 意図的。古びてよいと決めた文書が CI で無関係な変更を止めないため
- **教訓39件。** 検査を持たない。規約ではなく参考として扱う
- **文書の意味の正しさ全般。** 欄が載っているかは数えられるが、説明が正しいかは読み手が見る
