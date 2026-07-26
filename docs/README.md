# ドキュメント地図

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**最終更新:** 2026-07-26

このフォルダは**23文書**を4つの役割で分けている。**分類の軸はソースコードのパッケージではなく「文書の役割と寿命」**。
同じ機能の話が複数の文書に分かれるのは意図的で、「確定した判断」と「まだ決まっていないこと」を物理的に隔離するため。

---

## 0. 4つの役割（この軸だけ覚えれば迷わない）

| 問い | 置き場 | 寿命 |
|---|---|---|
| **何をいつ変えたか** | [change_history.md](change_history.md) | 累積（消さない） |
| **今どうなっているか** | [source_code_analysis.md](source_code_analysis.md)／[source_code_quality_review.md](source_code_quality_review.md) | スナップショット（日付で更新） |
| **なぜそうしたか** | [design/](design/)（14本） | 判断ごとに1本。判断が覆っても改稿して残す |
| **まだ決まっていない** | [_wip/](_wip/)（3本） | **解消したら削除する**（残すと未対応課題が埋もれる） |

補助として、失敗の知見を横断的に集めた [bugfix_reports.md](bugfix_reports.md) と、出発点の記録 [project_origin.md](project_origin.md) がある。

---

## 1. 恒久記録（`docs/` 直下・5本）

| 文書 | 役割 | 更新契機 |
|---|---|---|
| [change_history.md](change_history.md) | PR単位の変更履歴（新しい順） | **PRごとに1行追記** |
| [source_code_analysis.md](source_code_analysis.md) | 現況の全体解析（914行・16章）。§7 SAF層・§8 AI層・§9 Markdown層はパッケージ単位で読める | 大きな実装の区切りで通しで書き直す |
| [source_code_quality_review.md](source_code_quality_review.md) | 品質の採点結果（11軸・総合7.2/10・2026-07-26） | **更新せず、次回レビューを追記**（推移を残す） |
| [bugfix_reports.md](bugfix_reports.md) | AIが生成しがちで通常フローでは表面化しない潜在バグの記録 | 該当する型のバグを踏んだとき |
| [project_origin.md](project_origin.md) | 2026-04-30 の第一歩の報告書 | 更新しない（起点の記録） |

> **解析書と総評の違い:** 解析書は「事実の網羅」、総評は「ある時点の採点」。

---

## 2. 設計判断（`design/`・14本）

「なぜその設計にしたか」を機能単位で残す。**フォルダ分けはしていない**（14本なら一覧で足りるため。20本を超えたら下の4分類でサブフォルダ化する）。

> 状態列は**実装が入ったかどうか**だけを示す。未着手のPhaseや設計書と実装の乖離といった個別の話は、各文書の冒頭（`**状態:**` / `**未解決:**` 行）と [_wip/current_issues.md](_wip/current_issues.md) が持つ。ここには書かない。

### 基盤・横断

| 文書 | 対象 | 状態 |
|---|---|---|
| [architecture.md](design/architecture.md) | ViewModel分割・状態管理・並行処理の規約 | 実装済み（PR #16〜#20） |
| [theme_and_ui_refactor.md](design/theme_and_ui_refactor.md) | テーマ基盤とUI構造のリファクタ（R-1〜R-4） | 実装済み |
| [dark_mode.md](design/dark_mode.md) | ダークモード | 実装済み |

### 読む導線

| 文書 | 対象 | 状態 |
|---|---|---|
| [tab_navigation.md](design/tab_navigation.md) | 画面構成・ナビゲーション（Plan C） | 実装済み（PR #12） |
| [note_fullscreen.md](design/note_fullscreen.md) | 全画面ノート（独立ルート化） | 実装済み（PR #30） |
| [ai_picker.md](design/ai_picker.md) | さがすタブ（検索・ランダム・履歴） | 実装済み（PR #15） |

### AIと考える（Reflect）

| 文書 | 対象 | 状態 |
|---|---|---|
| [reflect_distill.md](design/reflect_distill.md) | 蒸留（Distill） | v1 Phase 1〜6 実装済み・実機確認待ち |
| [reflect_reading_trace.md](design/reflect_reading_trace.md) | ReadingTrace（読書痕跡・サイドカー） | v1 実装済み・実機確認待ち |
| [section_ai_chat.md](design/section_ai_chat.md) | セクション単位AIチャット | 実装済み（PR #14） |
| [related_notes_ai.md](design/related_notes_ai.md) | 関連ノートAI推薦 | 実装済み（一部Phase未着手） |
| [background_ai_ux.md](design/background_ai_ux.md) | AI生成の待ち時間と結果通知 | 実装済み（PR #22, #23） |

### Vigilith（人格と演出）

| 文書 | 対象 | 状態 |
|---|---|---|
| [character_vigilith.md](design/character_vigilith.md) | キャラクターシート（黒曜の梟オートマトン） | コンセプト確定 |
| [vigilith_in_app.md](design/vigilith_in_app.md) | アプリ内Vigilith（読書相手の身体化） | Phase 3 実装済み・実機確認待ち |
| [opening_animation.md](design/opening_animation.md) | 起動OPアニメーション | 実装済み（PR #26・2026-07-26改稿） |

---

## 3. 未確定（`_wip/`・3本）

**ここにある内容は「まだ終わっていない」ことを意味する。** 解消した項目はこのフォルダから削除する（記録は §1 の恒久記録に残る）。

> **`_wip/` はリリース時点で3ファイルとも廃棄する一時置き場。** そのため**恒久文書（§1・§2）から `_wip/` を参照しない**。
> 参照は一方通行で、`_wip/` → 恒久文書のみ。恒久側で課題に触れるときは、リンクや項目番号ではなく**内容そのものを書く**
> （例: `**未解決:** current_issues 2-9` ではなく `**未解決:** 依存の循環が3組（model ⇄ data / …）`）。
> この向きを守っている限り、`_wip/` はいつ捨てても、中の番号をいつ振り直しても、恒久文書は壊れない。

| 文書 | 役割 |
|---|---|
| [current_issues.md](_wip/current_issues.md) | 課題台帳。未対応のものだけを残し、解消したら削除する |
| [roadmap.md](_wip/roadmap.md) | Now / Next / Later。日付を切らず優先度と成熟度で3段。**使い捨て**（完了項目は取り消し線を残さず削除する） |
| [feature_ideas.md](_wip/feature_ideas.md) | 未実装の採用候補（使い捨て。実装済み・却下は残さない） |

---

## 4. 運用ルール

1. **PRごとに** [change_history.md](change_history.md) へ1行追記する。設計判断や試行錯誤があった変更だけ `design/` に対応文書を作成／追記し、履歴表からリンクする。
2. **解析書・総評で「問題」と書いたものは、必ず [_wip/current_issues.md](_wip/current_issues.md) に起票する。** 書いただけでは追跡されない。
   ただし**起票先へのリンクや番号は恒久文書側に残さない**（§3 の一方通行ルール）。恒久文書には問題の内容だけを書く。
3. **`_wip/` の項目が解消したら削除する。** 残すと未対応の課題が埋もれる。
4. **修正の主張は、修正コードを別の目で読むまで確定させない。** 方針が正しいと文書とコミットメッセージだけ通ってしまう（→ [bugfix_reports.md](bugfix_reports.md) #4）。
5. **`design/` の各文書には `**状態:**` 行を置く。** 実装済みか構想段階かが本文を読まずに分かるようにする。

> 各文書の内部ルール（課題番号の扱いなど）はその文書の冒頭が持つ。ここには集約しない。

---

## 5. 目的別の入口

| したいこと | 読む順 |
|---|---|
| プロジェクトを初めて把握する | [source_code_analysis.md](source_code_analysis.md) §1〜§4 → [design/architecture.md](design/architecture.md) |
| 次に何を作るか決める | [_wip/roadmap.md](_wip/roadmap.md) → [_wip/current_issues.md](_wip/current_issues.md) → [_wip/feature_ideas.md](_wip/feature_ideas.md) |
| 品質改善に着手する | [source_code_quality_review.md](source_code_quality_review.md) → [_wip/current_issues.md](_wip/current_issues.md) |
| 既存コードを触る前に背景を知る | 下の逆引き表 → 該当する `design/` |
| バグを踏んだ | [bugfix_reports.md](bugfix_reports.md)（同じ型か確認） |

### パッケージ → 先に読む設計書（逆引き）

`design/` はパッケージ単位に分かれていないので、コードから引くときはこの表を使う。

| 触るパッケージ | 先に読む |
|---|---|
| `NoteViewModel.kt` / `controller/` | [architecture](design/architecture.md) → 該当機能の設計書 |
| `ai/` | [background_ai_ux](design/background_ai_ux.md) → [reflect_distill](design/reflect_distill.md) / [related_notes_ai](design/related_notes_ai.md) |
| `domain/` | [related_notes_ai](design/related_notes_ai.md) / [reflect_distill](design/reflect_distill.md) |
| `data/` | [reflect_reading_trace](design/reflect_reading_trace.md)（サイドカー）/ [reflect_distill](design/reflect_distill.md)（原子性・復旧） |
| `model/NoteUiState.kt` | [architecture](design/architecture.md) / [tab_navigation](design/tab_navigation.md) |
| `ui/theme/` | [theme_and_ui_refactor](design/theme_and_ui_refactor.md) → [dark_mode](design/dark_mode.md) |
| `ui/vigilith/` | [character_vigilith](design/character_vigilith.md) → [vigilith_in_app](design/vigilith_in_app.md) → [opening_animation](design/opening_animation.md) |
| `ui/screen/` | [tab_navigation](design/tab_navigation.md) / [note_fullscreen](design/note_fullscreen.md) / [section_ai_chat](design/section_ai_chat.md) |
