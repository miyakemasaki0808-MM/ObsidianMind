# Vigilith AI 開発規約

Android / Kotlin / Jetpack Compose。AIはオンデバイスの Gemini Nano（ML Kit GenAI Prompt API）。
ネットワーク権限は持たない。ユーザーのVault（Obsidian の `.md` 群）を SAF 経由で読み書きする。

> **この文書の位置づけ = 憲法。** 常時効かせる原則・参照先・禁止事項・完了条件だけを置く。
> 背景と判断理由は `docs/dev/design/`（法律）、作業手順は Skill（作業標準書）が持つ。**ここに詳細を書き足さない。**

## 最優先文書

@docs/dev/design/architecture.md

上記は全変更に効く横断規約なので常時読み込む。**機能固有の変更では、着手前に対応する設計書を必ず読むこと。**
どれを読むかは [docs/dev/document_map.md](docs/dev/document_map.md) §5 の逆引き表で引く。主要なものだけ再掲する。

| 触るところ | 先に読む |
|---|---|
| `NoteViewModel.kt` / `controller/` | [architecture](docs/dev/design/architecture.md) → 該当機能の設計書 |
| `ai/` ・AI生成の通知/待ち時間 | [background_ai_ux](docs/dev/design/background_ai_ux.md) |
| `ui/theme/` ・色/テーマ | **[ui_design_principles](docs/dev/design/ui_design_principles.md)（先に読む）** → [theme_and_ui_refactor](docs/dev/design/theme_and_ui_refactor.md) → [dark_mode](docs/dev/design/dark_mode.md) |
| `data/`（SAF・サイドカー・書き戻し） | [reflect_reading_trace](docs/dev/design/reflect_reading_trace.md) / [reflect_distill](docs/dev/design/reflect_distill.md) |
| `ui/vigilith/` | [character_vigilith](docs/dev/design/character_vigilith.md) → [vigilith_in_app](docs/dev/design/vigilith_in_app.md) |
| `androidTest/` | [instrumentation_testing](docs/dev/design/instrumentation_testing.md) |

**繰り返し現れた構造的な教訓は [docs/dev/lessons.md](docs/dev/lessons.md)（索引）と [docs/dev/lessons/](docs/dev/lessons/)（カード）。**
**着手前に全文を読まない。** 索引の「いつ当てるか」列を引き、**該当したカードだけ**を読む。

**索引の「検査」列が空の教訓は、規約ではなく参考である。**
このリポジトリで規則が守られたのは検査に載せたときだけ、というのが実績
（→ [L29](docs/dev/lessons/L29.md)）。**両者を同じ強さで扱わない。**

（2026-08-10、旧 §0「diff後に当てる5問」を廃止した。自ら「3回で評価する」と宣言しながら
17件の変更が走るあいだ一度も判定されず、**評価する規則自体が守られなかった**
→ [L32](docs/dev/lessons/L32.md)。対策は文書ではなく検査側へ寄せる。）

`_wip/` は役割で分かれている。**混ぜない。**（個別の実装計画など、下表以外のファイルが一時的に増えることはある）

| 知りたいこと | 見る文書 |
|---|---|
| いま何が壊れている／足りないのか | [_wip/current_issues.md](docs/_wip/current_issues.md)（課題だけ。順序は書かない） |
| 何をどの順でやるか | [_wip/roadmap.md](docs/_wip/roadmap.md)（Now / Next / Later） |
| まだ作っていない機能の候補 | [_wip/feature_ideas.md](docs/_wip/feature_ideas.md) |

## 必須原則

**構造**

- UIへ業務ロジックを書かない。UI分岐は純関数に切り出す（切り出せばそのままテストになる）
- 機能責務は既存Controllerへ置く。`NoteViewModel` はAndroid境界の窓口、横断調停は `NoteSessionCoordinator` に留める
- 壊れやすいロジック（文字列処理・パース・採点）はAndroid依存から分離し、JVMテストを同時に書く
- **新しいノート単位の状態を足したら、契約2箇所へ必ず登録する** — `NoteSessionCoordinator.cancelNoteScopedJobs()`（実行中Jobの停止）と `NoteUiStateStore` の `withNoteScopedReset()`（状態の一括リセット）。登録漏れ＝旧ノートの状態残留バグ

**並行処理**

- ノート単位・Vault単位のジョブは Job を保持して切替時にキャンセルする
- `CancellationException` は握りつぶさず再throwする。広い `catch (e: Exception)` に畳むと切替のたびに偽エラーが出る
- キャンセルがすり抜ける経路（モデルDLコールバック等）には requestId ＋ `isCurrent()` ガードを併用する。**`cancel()` だけでは足りない**
- 非同期の結果を `uiState.update` する直前に、必ず「まだ最新の要求か」を確認する

**禁止事項**

- **類似コードを見つけても、設計文書の判断を無視して安易に共通化しない。** Controllerの相似形は 2026-07-24 と 07-25 の2度の検討を経て「**共通化しない**」で決着済み（→ [architecture.md](docs/dev/design/architecture.md) の追記2節）。再提案するなら、そこに書かれた再検討条件を満たすことを先に示す
- **実装と設計文書が食い違う場合、勝手にどちらかへ寄せない。差分を報告して判断を仰ぐ**
- 外から渡されたラムダを `pointerInput` / `LaunchedEffect` など長寿命ブロック内から呼ぶときは `rememberUpdatedState` を通す（stale closure で「押しても無反応」になる既知の型 → [L34](docs/dev/lessons/L34.md)）
- SDKの制約は公式ドキュメントではなくバイナリで確認する（`maxOutputTokens` の 1〜256 制限で全AI生成が落ちた前例あり）
- 依存ライブラリを一括更新しない。ML Kit GenAI を含め機能単位で上げ、実機確認を伴う

## 検証と完了条件

`java` / `javac` はスタブでJDK未インストール。**Android Studio 同梱のJBRを使う**（`/Applications` 直下ではなく `AIセット` フォルダ配下にあるため `/usr/libexec/java_home` では見つからない）。

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest lintDebug --offline
```

- **コード変更後は必ず上記を通してからコミットする。** 静的レビューだけで通したコードにコンパイルエラーが混入した前例がある
- `androidTest` を触ったら `assembleDebugAndroidTest`（CIと同じ組み立てタスク）も通す。上記のコマンドはこれをコンパイルしない
- **実機確認はユーザーがAndroid Studioで実施する。** Claudeは実行できない。**実機確認が済むまでPR本文に「確認完了」と書かない**
- Lint は現在 Error 0 / Warning 0 / hint 12（hint は依存更新系の催促で、ゲートに載せない）。**警告を増やさない**
- 端末AIを呼ぶ instrumentation テストは、Nano が使えない端末では `Assume` で skip する。**skip 判定は既知の `FeatureStatus` だけで行い、計測・生成呼び出しが投げた例外は skip せず失敗させる**（`checkAvailability()` は例外も畳むので判定に使わない）→ [ai_input_excerpt](docs/dev/design/ai_input_excerpt.md)

**変更を終える前に:**

1. [docs/dev/change_history.md](docs/dev/change_history.md) へPR単位で1行追記する。**「変更内容」は1文・100字以内** — 経緯・代償・変異確認の結果・教訓はここに書かない（行き先は 2. と 4.）
2. 設計判断や試行錯誤があった変更だけ、対応する `docs/dev/design/*.md` に追記する（自明な変更は履歴1行のみ）
3. 解析書・総評で「問題」と書いたものは [docs/_wip/current_issues.md](docs/_wip/current_issues.md) に起票する。**実機検証まで終わったら即座に削除する**（実装完了では消さない。検証待ちが台帳から消えると誰も確認しなくなる）。完了の経緯は残さない — 記録は 1. が持ち、教訓は 4. が持つ
4. **同じ形の失敗を2度した、または1度でも構造上また起きる**と判断したら [docs/dev/lessons.md](docs/dev/lessons.md) の索引へ1行足し、長ければ [lessons/](docs/dev/lessons/) にカードを作る。**番号は振り直さず末尾へ足す**（既存IDの意味を変えない — 外部参照が壊れる）。カードは20行を目安にし、超えたら詳細の正本を `design/` に決めて要約＋リンクにする
5. **同じ事件を design・lessons・change_history へ3回とも長文で書かない。** 正本を1つ決め、他は要約＋リンクにする

## 文書の扱い

- **`docs/review/` 配下の `2026-*.md` は最新の1本だけを置き、書き換えない。** 新しいレビューを受け付けたら前の本文は削除する（原文はgit履歴に残る）。様式（`review_template.md`）と全指摘の受付簿（`findings.md`）はこちらが持つ
- **恒久文書から `_wip/` の項目IDへ依存しない。** `_wip/` はリリース時に廃棄するので、`SYNC-2` のような項目番号を設計書や記録から参照すると、廃棄した瞬間に意味が消える。**課題に触れるときは番号ではなく内容そのものを書く。** ただし**入口・索引（`docs/README.md`・`dev/document_map.md`・`review/README.md`）はフォルダとして案内してよい** — 廃棄時に索引ごと直せばよいため
- `design/` の各文書には `**状態:**` 行を置く

## 作業の進め方

- **修正1件＝1コミット。** コミットメッセージは日本語で「何を・なぜ」
- PR本文も日本語で、修正表＋「見た目・挙動の変更点」＋「実機確認ポイント」を載せる
- ブランチはユーザーがGitHub側で作成する。「フェッチして」と言われたら、main を ff-only で pull → マージ済み旧ブランチをローカル削除 → 新ブランチを checkout
- **修正が効いていることの主張は、修正コードを別の目で読むまで確定させない。** 方針が正しいと、実装が効いていなくても文書とコミットメッセージだけ通ってしまう
- **「別の目」は、実装したのとは別のモデル／エージェントがdiffをレビューすること。自分の2回目のパスは数えない。** コミット前に通す恒久の工程であって、余裕があるときの安全網ではない — 実績上、見落としを最も確実に捕まえているのはこの工程である（外部レビューで9件・4件・2件。自己パスの検出はゼロではないが同水準ではない）
