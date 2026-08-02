# アプリ俯瞰とドキュメント地図

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**最終更新:** 2026-08-02

**位置づけ:** **俯瞰用のスナップショット**。「このアプリは何ができるか」と「どの文書を読めばよいか」の2つを、
一望できる粒度で持つ。**継続的に同期する台帳ではない**ので、
[source_code_analysis.md](source_code_analysis.md) と同じく**ユーザーの指示があったときに通しで見直す**。
細部が実装より古いことは仕様の範囲で、正確な現況は解析書、未対応課題は [_wip/](_wip/) を正とする。

> **例外は §5 の逆引き表だけ。** [CLAUDE.md](../CLAUDE.md) が「コードを触る前にここで設計書を引く」導線として
> 参照しているため、**設計書を追加・削除したらその場で直す**。古いと違う設計書へ送ってしまう。

---

## アプリの機能（俯瞰）

> **Vigilith AI は「過去の自分の思考と再会し、もう一段深める、オンデバイスAIの読書相手」。**
> Obsidian の Vault（`.md` 群）を SAF 経由で読み書きし、**AIは全て端末内の Gemini Nano**（ネットワーク権限なし）。

画面は**5つのタブ**（ノート／さがす／関連／AI／オプション）と、全画面ルート4つ（全画面ノート・クイズ・補記結果・補記管理）。

| 機能 | できること |
|---|---|
| **Rediscover（ランダム表示）** | 過去ノートを偶然引く。アプリの入口であり心臓 |
| **ReadingTrace（読書痕跡）** | 読書位置を自動記録し、同じノートを引いた時だけ「前回の読み方」を再会カードで出す |
| **ノート要約** | 開いたノートの要約を自動生成する |
| **関連ノート** | 現ノートに近い候補をAIが選び、タップで切り替える |
| **さがす（AIピッカー）** | キーワード検索・フォルダ絞り込み・当日の閲覧履歴・AIによる候補提示 |
| **セクションAI** | 見出し単位で要約・質問候補・Q&Aを出す |
| **適応出題クイズ** | 入力量に応じて ○× / 3択 / 4択 を自動選択して出題する |
| **AI補記メモ** | ノートへの補足をAIが書き、`_AI補記` フォルダへ別ファイルで保存する |
| **蒸留（Distill）** | AIが重要文を**選び**、ユーザーが確認した文だけを元ノートで `**太字**` にする |
| **Vigilith** | 画面に常駐する梟のマスコット。AIの状態を身体で示し、タップで操作へ入る |
| **ダークモード** | オプションのトグルで切替（OS設定には追従しない） |

**ノート本文へ書き込むのは蒸留の太字化だけ。** 補記も読書痕跡もサイドカー（別ファイル）へ逃がしてある。
機能ごとのデータフローは [source_code_analysis.md](source_code_analysis.md) §5〜§6、
なぜそう作ったかは [design/](design/) が持つ。

---

## 文書の地図

このフォルダは**28文書**を4つの役割で分けている。**分類の軸はソースコードのパッケージではなく「文書の役割と寿命」**。
同じ機能の話が複数の文書に分かれるのは意図的で、「確定した判断」と「まだ決まっていないこと」を物理的に隔離するため。

---

## 0. 4つの役割（この軸だけ覚えれば迷わない）

| 問い | 置き場 | 寿命 |
|---|---|---|
| **何をいつ変えたか** | [change_history.md](change_history.md) | 累積（消さない） |
| **今どうなっているか** | [source_code_analysis.md](source_code_analysis.md)／[source_code_quality_review.md](source_code_quality_review.md) | スナップショット（日付で更新） |
| **なぜそうしたか** | [design/](design/)（19本） | 判断ごとに1本。判断が覆っても改稿して残す |
| **まだ決まっていない** | [_wip/](_wip/)（3本） | **実機検証まで終わったら削除する**（残すと未対応課題が埋もれる） |
| **同じ失敗を繰り返さないために** | [lessons.md](lessons.md) | 累積（番号を振り直さず末尾へ追加） |

補助として、単発の潜在バグを記録する [bugfix_reports.md](bugfix_reports.md) と、出発点の記録 [project_origin.md](project_origin.md) がある。

> **lessons.md と bugfix_reports.md の違い:** 前者は「構造的にまた起きる型」、後者は「再現手順のある個別のバグ」。
> 前者は 2026-07-31 に `_wip/current_issues.md` の横断テーマ節から移した（`_wip/` は廃棄されるため）。

---

## 1. 恒久記録（`docs/` 直下・6本）

| 文書 | 役割 | 更新契機 |
|---|---|---|
| [change_history.md](change_history.md) | PR単位の変更履歴（新しい順） | **PRごとに1行追記** |
| [source_code_analysis.md](source_code_analysis.md) | 現況の全体解析（914行・16章）。§7 SAF層・§8 AI層・§9 Markdown層はパッケージ単位で読める | 大きな実装の区切りで通しで書き直す |
| [source_code_quality_review.md](source_code_quality_review.md) | 品質の採点結果（11軸・総合7.2/10・2026-07-26）。**Codexが評価者として作成する外部レビュー** | **書き換えない。** 次回レビュー時に新しい日付の総評を追記する（推移を残す） |
| [bugfix_reports.md](bugfix_reports.md) | AIが生成しがちで通常フローでは表面化しない潜在バグの記録 | 該当する型のバグを踏んだとき |
| [lessons.md](lessons.md) | 機能にもバグにも属さない、繰り返し現れた構造的な教訓（27件） | 同じ形の失敗を2度した／構造上また起きると判断したとき |
| [project_origin.md](project_origin.md) | 2026-04-30 の第一歩の報告書。Android開発の最初の記録としてのみ存在し、現状とは一致しない | 更新しない（起点の記録） |

> **解析書と総評の違い:** 解析書は「事実の網羅」、総評は「ある時点の採点」。

---

## 2. 設計判断（`design/`・19本）

「なぜその設計にしたか」を機能単位で残す。**フォルダ分けはしていない**（19本なら一覧で足りるため。20本を超えたら下の4分類でサブフォルダ化する）。

> 状態列は**俯瞰のための要約**で、実装が入ったか・実機確認まで済んだかを1行で示す。
> **正は各文書の冒頭（`**状態:**` 行）** で、こちらはスナップショットなので遅れることがある。
> 未着手のPhaseや設計書と実装の乖離といった個別の話は各文書と [_wip/current_issues.md](_wip/current_issues.md) が持ち、ここには書かない。

### 基盤・横断

| 文書 | 対象 | 状態 |
|---|---|---|
| [architecture.md](design/architecture.md) | ViewModel分割・状態管理・並行処理の規約 | 実装済み（PR #16〜#20） |
| [ui_design_principles.md](design/ui_design_principles.md) | **UIデザインの指針（国際規約＋好み）。見た目に触る前に読む** | 運用中 |
| [theme_and_ui_refactor.md](design/theme_and_ui_refactor.md) | テーマ基盤とUI構造のリファクタ（R-1〜R-4）と判断1〜8 | 実装済み |
| [dark_mode.md](design/dark_mode.md) | ダークモード | 実装済み・実機確認済み |
| [dependency_policy.md](design/dependency_policy.md) | 依存更新の方針とLint更新系チェックの扱い | 方針確定・更新の実行は未着手 |
| [saf_boundary_gateway.md](design/saf_boundary_gateway.md) | SAF境界の gateway 化（`Uri` の不透明化） | 全段階 実装済み・実機確認済み |

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
| [reflect_reading_trace.md](design/reflect_reading_trace.md) | ReadingTrace（読書痕跡・サイドカー） | **v1完了**（実機確認は2026-07-31にクローズ） |
| [section_ai_chat.md](design/section_ai_chat.md) | セクション単位AIチャット | 実装済み（PR #14） |
| [related_notes_ai.md](design/related_notes_ai.md) | 関連ノートAI推薦 | 実装済み（一部Phase未着手） |
| [background_ai_ux.md](design/background_ai_ux.md) | AI生成の待ち時間と結果通知 | 実装済み（PR #22, #23） |
| [ai_input_excerpt.md](design/ai_input_excerpt.md) | AI入力の抜粋（7プロンプトへ渡す本文の作り方） | 実装済み・実機確認済み |
| [markdown_rendering.md](design/markdown_rendering.md) | Markdown解析の準拠先とリスト構造 | リストは実装済み・実機確認済み |

### Vigilith（人格と演出）

| 文書 | 対象 | 状態 |
|---|---|---|
| [character_vigilith.md](design/character_vigilith.md) | キャラクターシート（黒曜の梟オートマトン） | コンセプト確定 |
| [vigilith_in_app.md](design/vigilith_in_app.md) | アプリ内Vigilith（読書相手の身体化） | Phase 3 実装済み・実機確認待ち（認証ロックで保留） |
| [opening_animation.md](design/opening_animation.md) | 起動OPアニメーション | 実装済み（PR #26・2026-07-26改稿） |

---

## 3. 未確定（`_wip/`・3本）

**ここにある内容は「まだ終わっていない」ことを意味する。** 解消した項目はこのフォルダから削除する（記録は §1 の恒久記録に残る）。

> **`_wip/` はリリース時点で3ファイルとも廃棄する一時置き場。** そのため**恒久文書（§1・§2）から `_wip/` を参照しない**。
> 参照は一方通行で、`_wip/` → 恒久文書のみ。恒久側で課題に触れるときは、リンクや項目番号ではなく**内容そのものを書く**
> （例: `**未解決:** current_issues 3-14` ではなく `**未解決:** instrumentation テストの構成が未定義`）。
> この向きを守っている限り、`_wip/` はいつ捨てても、中の番号をいつ振り直しても、恒久文書は壊れない。
>
> **例外は [source_code_quality_review.md](source_code_quality_review.md) のみ。** Codexが書く外部レビューなので
> こちらから手を入れない。`_wip/` 廃棄時にリンク切れが残るが、本書は当時のスナップショットなので許容する。

| 文書 | 役割 |
|---|---|
| [current_issues.md](_wip/current_issues.md) | 課題台帳。**「いま何が壊れているか」だけ**を持ち、順序は書かない。未対応のものだけを残し、**実機検証まで終わったら削除する**（実装完了では消さない）。IDはカテゴリ記号（TEST-1・AI-1 など） |
| [roadmap.md](_wip/roadmap.md) | Now / Next / Later。日付を切らず優先度と成熟度で3段。**使い捨て**（完了項目は取り消し線を残さず削除する） |
| [feature_ideas.md](_wip/feature_ideas.md) | 未実装の採用候補（使い捨て。実装済み・却下は残さない） |

---

## 4. 運用ルール

1. **PRごとに** [change_history.md](change_history.md) へ1行追記する。設計判断や試行錯誤があった変更だけ `design/` に対応文書を作成／追記し、履歴表からリンクする。
2. **解析書・総評で「問題」と書いたものは、必ず [_wip/current_issues.md](_wip/current_issues.md) に起票する。** 書いただけでは追跡されない。
   ただし**起票先へのリンクや番号は恒久文書側に残さない**（§3 の一方通行ルール）。恒久文書には問題の内容だけを書く。
3. **`_wip/` の項目は実機検証まで終わったら削除する。** 実装完了では消さない（検証待ちが消えると誰も確認しなくなる）。残すと未対応の課題が埋もれる。
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
| `NoteViewModel.kt` / `controller/NoteSessionCoordinator.kt` / `model/NoteUiStateStore.kt` | [architecture](design/architecture.md) → 該当機能の設計書 |
| `controller/` | [architecture](design/architecture.md) → 該当機能の設計書 |
| `ai/PromptBuilder.kt` | [ai_input_excerpt](design/ai_input_excerpt.md) → 該当機能の設計書 |
| `ai/` | [background_ai_ux](design/background_ai_ux.md) → [reflect_distill](design/reflect_distill.md) / [related_notes_ai](design/related_notes_ai.md) |
| `domain/NoteExcerptBuilder.kt` / `model/NoteExcerptLimits.kt` | [ai_input_excerpt](design/ai_input_excerpt.md) |
| `domain/markdown/` / `ui/markdown/` | [markdown_rendering](design/markdown_rendering.md) → [ai_input_excerpt](design/ai_input_excerpt.md)（同じパーサがAI入力にも効くため） |
| `domain/` | [related_notes_ai](design/related_notes_ai.md) / [reflect_distill](design/reflect_distill.md) |
| `data/` | [reflect_reading_trace](design/reflect_reading_trace.md)（サイドカー）/ [reflect_distill](design/reflect_distill.md)（原子性・復旧） |
| `model/NoteUiState.kt` / `model/state/` | [architecture](design/architecture.md) / [tab_navigation](design/tab_navigation.md) |
| `model/` の共有データ型 | [architecture](design/architecture.md) → 該当機能の設計書 |
| `ui/theme/`・見た目に触る変更全般 | **[ui_design_principles](design/ui_design_principles.md)（先に読む）** → [theme_and_ui_refactor](design/theme_and_ui_refactor.md) → [dark_mode](design/dark_mode.md) |
| `ui/vigilith/` | [character_vigilith](design/character_vigilith.md) → [vigilith_in_app](design/vigilith_in_app.md) → [opening_animation](design/opening_animation.md) |
| `ui/screen/` | [tab_navigation](design/tab_navigation.md) / [note_fullscreen](design/note_fullscreen.md) / [section_ai_chat](design/section_ai_chat.md) |
| `app/build.gradle.kts` の依存宣言・`gradle/wrapper` | [dependency_policy](design/dependency_policy.md) |
| `data/SafDocuments.kt` / `data/VaultBrowser.kt` / `model` の参照型 | [saf_boundary_gateway](design/saf_boundary_gateway.md) → [architecture](design/architecture.md) |
