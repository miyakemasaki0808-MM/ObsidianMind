# 文書一覧

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-08

**位置づけ:** **このリポジトリにどんな文書があり、それぞれ何を答えるか**を一望する1枚。
`owner/` の他文書と同じく、**指示があったときに通しで見直す**（→ [README](README.md)）。
**検査には載せない** — 文書を1本足すたびに本書が古くなるが、それはCIで止める種類のずれではない。

**正本の所在はここではない。** 運用ルールの正本は [dev/document_map.md](../dev/document_map.md)、
憲法は [CLAUDE.md](../../CLAUDE.md) が持つ。本書は**目録**である。

---

## 1. 全体

**Markdown 110本・約17,150行**（`.claude/` 配下と `evidence/` を除く）。

```
CLAUDE.md                  開発規約（憲法）
README.md                  リポジトリの入口
docs/
├── README.md              文書の入口
├── owner/     (11本)       オーナーが読む俯瞰。検査に載せない
│   └── journal/  (3本)
├── dev/       (77本)      判断の正本。ここが古くなると実害が出る
│   ├── features/  (22本)  ユーザーから見える機能
│   ├── system/    (12本)  横断的な基盤
│   ├── decisions/  (6本)  ADR（覆りにくい重大判断だけ）
│   └── lessons/   (36本)  教訓62件のうち、カードを持つ36本
├── _wip/       (3本)      進行中。リリース時に廃棄する
└── review/    (13本)      レビューと実機検証
    └── device_validation/ (9本)
```

## 2. 場所ごとの役割

| 場所 | 答える問い | 誰が書くか | 検査 |
|---|---|---|---|
| `CLAUDE.md` | **常時効く原則・禁止事項・完了条件** | 全員 | 入口の存在のみ（`DeviceValidationDocsTest`） |
| `docs/owner/` | 何ができるアプリか／いまどうなっているか | **オーナーの依頼時だけ** | **無し（意図的）** |
| `docs/dev/features/` | ユーザーから見える機能の仕様と実現方法 | 実装者 | `DesignDocStateNameTest`・`SourceDocSyncTest`・`SchemaVersionDocsTest` |
| `docs/dev/system/` | 横断的な基盤の責務・保証・不変条件 | 実装者 | 同上 |
| `docs/dev/decisions/` | なぜその判断にしたか（索引） | 実装者 | `AdrShapeTest`（30行以内） |
| `docs/dev/lessons/` | 次に同じ形の失敗をしないための判断材料 | 実装者 | 一部の教訓のみ（下記 §6） |
| `docs/_wip/` | いま何が壊れているか／何をどの順でやるか | 実装者 | `WipIssueReferenceTest` |
| `docs/review/` | 未解決の指摘と、実機検証の手順 | レビュアーとこちら | `ReviewFindingsLedgerTest`・`DeviceValidationDocsTest` |

**線引きは「コードを読めば再現できるか」。** 現況の解析は再現できるので `owner/`、
「なぜその判断にしたか」「何を試して駄目だったか」はコードに書かれていないので `dev/`。

---

## 3. `docs/dev/features/` — 機能仕様（20本＋README＋様式）

| 文書 | 機能 | 状態 |
|---|---|---|
| [rediscover](../dev/features/rediscover.md) | Rediscover（ランダム表示） | 稼働中。アプリの入口 |
| [reflect_reading_trace](../dev/features/reflect_reading_trace.md) | 読書痕跡（ReadingTrace） | 稼働中。サイドカー |
| [reunion_card](../dev/features/reunion_card.md) | 再会カードに何を出すか | 実装済み・実機検証済み |
| [note_summary](../dev/features/note_summary.md) | ノート要約 | 稼働中 |
| [related_notes_ai](../dev/features/related_notes_ai.md) | 関連ノートAI推薦 | 稼働中 |
| [ai_picker](../dev/features/ai_picker.md) | さがす（AIピッカー・閲覧履歴） | 稼働中 |
| [section_ai_chat](../dev/features/section_ai_chat.md) | セクションAI | 稼働中 |
| [quiz](../dev/features/quiz.md) | クイズ（Q&A） | 稼働中 |
| [reflect_remark](../dev/features/reflect_remark.md) | ノートへのひとこと | 稼働中 |
| [reflect_distill](../dev/features/reflect_distill.md) | 蒸留（Distill） | v1 Phase 1〜6 |
| [distill_range_adjust](../dev/features/distill_range_adjust.md) | 蒸留の太字範囲の調整 | 段階1（プリセット）実装 |
| [booklet_mode](../dev/features/booklet_mode.md) | 冊子モード（**最大の文書・1,038行**） | 実装済み。**編む束は実機検証待ち** |
| [note_image_rendering](../dev/features/note_image_rendering.md) | ノート内画像の表示 | 稼働中・実機確認 |
| [note_fullscreen](../dev/features/note_fullscreen.md) | 全画面ノート | 稼働中 |
| [note_age_paper](../dev/features/note_age_paper.md) | ノートの年代を紙の地色で伝える | 稼働中・実機確認 |
| [reading_trace_backup](../dev/features/reading_trace_backup.md) | 読書痕跡の退避と復元 | 実装済み・稼働中 |
| [character_vigilith](../dev/features/character_vigilith.md) | キャラクターシート（造形・配色） | Adopted |
| [vigilith_in_app](../dev/features/vigilith_in_app.md) | アプリ内Vigilith（身体化） | 稼働中 |
| [opening_animation](../dev/features/opening_animation.md) | 起動OPアニメーション | 稼働中 |
| [dark_mode](../dev/features/dark_mode.md) | ダークモード | 稼働中・実機確認 |
| [README](../dev/features/README.md) ／ [_template](../dev/features/_template.md) | 索引と様式 | — |

## 4. `docs/dev/system/` — 基盤設計（11本＋README）

| 文書 | 対象領域 |
|---|---|
| [architecture](../dev/system/architecture.md) | **ViewModel分割・状態管理・並行処理**（全変更に効く。常時読み込む） |
| [ui_design_principles](../dev/system/ui_design_principles.md) | UIデザインの指針（**UIを触る前に読む**） |
| [bearing_channels](../dev/system/bearing_channels.md) | 佇まいのチャネル割り当て（形・色・動きの役割） |
| [theme_and_ui_refactor](../dev/system/theme_and_ui_refactor.md) | テーマ基盤と配色コントラスト |
| [background_ai_ux](../dev/system/background_ai_ux.md) | AI生成のバックグラウンドUX（通知と失敗の見せ方） |
| [ai_input_excerpt](../dev/system/ai_input_excerpt.md) | AI入力の抜粋と予算 |
| [saf_boundary_gateway](../dev/system/saf_boundary_gateway.md) | SAF境界の不透明化（`model` を葉に保つ） |
| [markdown_rendering](../dev/system/markdown_rendering.md) | Markdown解析の準拠先 |
| [tab_navigation](../dev/system/tab_navigation.md) | タブ・ナビゲーション |
| [instrumentation_testing](../dev/system/instrumentation_testing.md) | instrumentation テストの方針 |
| [dependency_policy](../dev/system/dependency_policy.md) | 依存更新の方針 |

## 5. `docs/dev/decisions/` — ADR（5本）

**30行以内を `AdrShapeTest` が固定する。** 詳細の正本は `features/`・`system/` 側にあり、ADRはそこへリンクする。

| ADR | 判断 |
|---|---|
| [0001](../dev/decisions/ADR-0001-single-viewmodel-controllers.md) | 単一ViewModel＋機能Controller方式を採る |
| [0002](../dev/decisions/ADR-0002-on-device-ai-only.md) | AIはオンデバイスのみ。ネットワーク権限を持たない |
| [0003](../dev/decisions/ADR-0003-opaque-saf-references.md) | SAF参照を不透明化し `model` を依存グラフの葉に保つ |
| [0004](../dev/decisions/ADR-0004-do-not-rewrite-vault-body.md) | Vault本文を書き換えない（例外は蒸留の太字化のみ） |
| [0005](../dev/decisions/ADR-0005-bearing-channel-allocation.md) | 佇まいのチャネルは1つの意味だけに割り当てる |

## 6. `docs/dev/lessons/` — 教訓（62件）

**[索引](../dev/lessons.md) が本体で、カードは詳細。** 索引の「いつ当てるか」列を引き、**該当したカードだけ**を読む。
**中身の棚卸しは [lessons_summary](lessons_summary.md) が持つ。**

| 数え方 | 件数 |
|---|---|
| **教訓の総数** | **62**（`L1`〜`L63`。欠番は `L62` の1つだけ＝取り下げ） |
| 独立カードがあるもの | 36 |
| **索引の中に本文があるもの** | 26（`L1`〜`L18` と `L21` `L27` `L33` `L37` `L43` `L54` `L57` `L63`） |
| **検査を名指ししているもの＝規約** | **26** |
| **検査を持たないもの＝参考** | **36** |
| （うち索引の「検査」列が埋まっている行） | 23（`L7` `L9` `L13` は**本文に検査があるのに列が空**） |
| 役目を終えたもの（転送だけを持つ） | 2（`L12` `L15`） |
| 索引の行数 | 49（`L1`〜`L16` を1行に畳んでいるので、**件数とは一致しない**） |

> **検査列が空の教訓は、規約ではなく参考である。** このリポジトリで規則が守られたのは
> 検査に載せたときだけ、というのが実績（→ `L29`）。**両者を同じ強さで扱わない。**

## 7. `docs/_wip/` — 進行中（3本・リリース時に廃棄）

| 文書 | 答える問い |
|---|---|
| [current_issues](../_wip/current_issues.md) | **いま何が壊れている／足りないのか**（現在11件。順序は書かない） |
| [roadmap](../_wip/roadmap.md) | 何をどの順でやるか（Now / Next / Later） |
| [feature_ideas](../_wip/feature_ideas.md) | まだ作っていない機能の候補（686行・使い捨て） |

**恒久文書から `_wip/` の項目IDを参照しない。** 廃棄した瞬間に意味が消えるため。

## 8. `docs/review/` — レビューと実機検証（13本）

| 文書 | 役割 | 追跡 |
|---|---|---|
| [README](../review/README.md) | レビューの入口と運用 | ✅ |
| [findings](../review/findings.md) | **未解決指摘の受付簿**（現在2件） | ✅ |
| [review_template](../review/review_template.md) | レビュー本文の様式 | ✅ |
| `2026-*.md` | **最新レビュー本文1本だけ。書き換えない** | ❌ 未追跡 |
| [device_validation/README](../review/device_validation/README.md) | Codex実機検証の共通手順 | ✅ |
| [device_validation/quick_check](../review/device_validation/quick_check.md) | **実機検証（簡易版）** — 選抜規則とスモークセット | ✅ |
| device_validation の機能別ケース7本 | 冊子・蒸留・退避・画像・再会・AI状態UX・AI予算。**結果は持たない** | ✅ |
| `device_validation/evidence/` | スクリーンショット・UIダンプ・引き継ぎメモ | ❌ 未追跡 |

## 9. `docs/owner/` — オーナーが読む俯瞰（11本）

| 文書 | 答える問い |
|---|---|
| [README](README.md) | **何ができるアプリか**（機能の俯瞰） |
| [source_code_analysis](source_code_analysis.md) | いまコードがどうなっているか（**1,712行・最大の文書**） |
| [jvm_test_report](jvm_test_report.md) | どういう観点でテストしているか |
| [lessons_summary](lessons_summary.md) | 教訓62件に何が書かれているか（棚卸し用） |
| [readme_map](readme_map.md) | 11本ある README がそれぞれ何をしているか |
| **本書** | **どんな文書があるか** |
| [project_origin](project_origin.md) | どこから始まったか（2026-04-30。更新しない） |
| [journal/](journal/) | どうやってここまで来たか（2026-07・2026-08） |

---

## 10. 検査で守られている範囲

**文書のどこがCIで守られ、どこが人任せか**の対応表。見直しの起点になる。

| 検査 | 何を落とすか | 対象 |
|---|---|---|
| `DesignDocStateNameTest` | 消した型・値の名前を現役のように書いている | `dev/features/` `dev/system/` |
| `SourceDocSyncTest` | 状態欄が正本の一覧に無い／KDocの相対リンクが切れている | `dev/` とソース |
| `SchemaVersionDocsTest` | 文書の「現行スキーマ版」がコードの定数と違う | 痕跡系3本 |
| `AdrShapeTest` | ADRが30行を超えた | `dev/decisions/` |
| `WipIssueReferenceTest` | 台帳から消したIDを他が参照している／コードが課題IDを参照している | `_wip/` と `app/src/` |
| `ReviewFindingsLedgerTest` | レビュー指摘が受付簿に無い／処遇が空／本文が2本ある | `review/` |
| `DeviceValidationDocsTest` | 実機ケースの形が不完全／件数が実数と違う／簡易版が実在しないケースを指す | `review/device_validation/` |
| `DeviceProbeResidueTest` | 使い捨ての一時テストが残っている | `app/src/androidTest/` |

**守られていないもの:**

- **`docs/owner/` 全体**（意図的。古びてよいと決めた文書がCIで無関係な変更を止めないため）
- **教訓36件**（検査を持たない。規約ではなく参考として扱う）
- **文書の意味の正しさ全般** — 欄が載っているかは数えられるが、説明が正しいかは読み手が見る
