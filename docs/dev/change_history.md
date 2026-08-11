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
| 2026-08-10 | — | 外部レビュー本文を最新1本だけ残す運用へ変え、過去4本を削除した | [review/README](../review/README.md)・[findings](../review/findings.md) |
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
| 2026-08-01 | — | SYNC-1 の着手対象を「孤児のみ削除」に確定し（オーナー判断）、段階0〜4 の計画を roadmap N-1 として起こした | **[reflect_reading_trace](features/reflect_reading_trace.md)（孤児掃除の決着）〜§14**・[roadmap](../_wip/roadmap.md) N-1 |
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
