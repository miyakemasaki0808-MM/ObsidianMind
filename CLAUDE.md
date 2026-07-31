# Vigilith AI 開発規約

Android / Kotlin / Jetpack Compose。AIはオンデバイスの Gemini Nano（ML Kit GenAI Prompt API）。
ネットワーク権限は持たない。ユーザーのVault（Obsidian の `.md` 群）を SAF 経由で読み書きする。

> **この文書の位置づけ = 憲法。** 常時効かせる原則・参照先・禁止事項・完了条件だけを置く。
> 背景と判断理由は `docs/design/`（法律）、作業手順は Skill（作業標準書）が持つ。**ここに詳細を書き足さない。**

## 最優先文書

@docs/design/architecture.md

上記は全変更に効く横断規約なので常時読み込む。**機能固有の変更では、着手前に対応する設計書を必ず読むこと。**
どれを読むかは [docs/README.md](docs/README.md) §5 の逆引き表で引く。主要なものだけ再掲する。

| 触るところ | 先に読む |
|---|---|
| `NoteViewModel.kt` / `controller/` | [architecture](docs/design/architecture.md) → 該当機能の設計書 |
| `ai/` ・AI生成の通知/待ち時間 | [background_ai_ux](docs/design/background_ai_ux.md) |
| `ui/theme/` ・色/テーマ | [theme_and_ui_refactor](docs/design/theme_and_ui_refactor.md) → [dark_mode](docs/design/dark_mode.md) |
| `data/`（SAF・サイドカー・書き戻し） | [reflect_reading_trace](docs/design/reflect_reading_trace.md) / [reflect_distill](docs/design/reflect_distill.md) |
| `ui/vigilith/` | [character_vigilith](docs/design/character_vigilith.md) → [vigilith_in_app](docs/design/vigilith_in_app.md) |

**繰り返し現れた構造的な教訓は [docs/lessons.md](docs/lessons.md)。** 新しい仕組みを入れる前、
テストを足す前、横展開する前に目を通す（過去に同じ形で失敗している型が集めてある）。

`_wip/` は3本で役割が分かれている。**混ぜない。**

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

- **類似コードを見つけても、設計文書の判断を無視して安易に共通化しない。** Controllerの相似形は 2026-07-24 と 07-25 の2度の検討を経て「**共通化しない**」で決着済み（→ [architecture.md](docs/design/architecture.md) の追記2節）。再提案するなら、そこに書かれた再検討条件を満たすことを先に示す
- **実装と設計文書が食い違う場合、勝手にどちらかへ寄せない。差分を報告して判断を仰ぐ**
- 外から渡されたラムダを `pointerInput` / `LaunchedEffect` など長寿命ブロック内から呼ぶときは `rememberUpdatedState` を通す（stale closure で「押しても無反応」になる既知の型 → [bugfix_reports.md](docs/bugfix_reports.md)）
- SDKの制約は公式ドキュメントではなくバイナリで確認する（`maxOutputTokens` の 1〜256 制限で全AI生成が落ちた前例あり）
- 依存ライブラリを一括更新しない。ML Kit GenAI を含め機能単位で上げ、実機確認を伴う

## 検証と完了条件

`java` / `javac` はスタブでJDK未インストール。**Android Studio 同梱のJBRを使う**（`/Applications` 直下ではなく `AIセット` フォルダ配下にあるため `/usr/libexec/java_home` では見つからない）。

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest lintDebug --offline
```

- **コード変更後は必ず上記を通してからコミットする。** 静的レビューだけで通したコードにコンパイルエラーが混入した前例がある
- **実機確認はユーザーがAndroid Studioで実施する。** Claudeは実行できない。**実機確認が済むまでPR本文に「確認完了」と書かない**
- Lint は現在 Error 0 / Warning 28。**警告を増やさない**

**変更を終える前に:**

1. [docs/change_history.md](docs/change_history.md) へPR単位で1行追記する
2. 設計判断や試行錯誤があった変更だけ、対応する `docs/design/*.md` に追記する（自明な変更は履歴1行のみ）
3. 解析書・総評で「問題」と書いたものは [docs/_wip/current_issues.md](docs/_wip/current_issues.md) に起票する。**解消したら即座に削除する**（完了の記録は 1. が持つ。台帳に残すと未対応課題が埋もれる）
4. **同じ形の失敗を2度した、または1度でも構造上また起きる**と判断したら [docs/lessons.md](docs/lessons.md) へ追記する（番号は振り直さず末尾へ足す）

## 文書の扱い

- **`docs/source_code_quality_review.md` は書き換えない。** Codexが評価者として書く外部レビューで、当時のスナップショットとして保存する
- **恒久文書（`docs/` 直下・`design/`）から `_wip/` を参照しない。** 参照は `_wip/` → 恒久文書の一方通行。恒久側で課題に触れるときはリンクや項目番号ではなく**内容そのもの**を書く（`_wip/` はリリース時に廃棄するため）
- `design/` の各文書には `**状態:**` 行を置く

## 作業の進め方

- **修正1件＝1コミット。** コミットメッセージは日本語で「何を・なぜ」
- PR本文も日本語で、修正表＋「見た目・挙動の変更点」＋「実機確認ポイント」を載せる
- ブランチはユーザーがGitHub側で作成する。「フェッチして」と言われたら、main を ff-only で pull → マージ済み旧ブランチをローカル削除 → 新ブランチを checkout
- **修正が効いていることの主張は、修正コードを別の目で読むまで確定させない。** 方針が正しいと、実装が効いていなくても文書とコミットメッセージだけ通ってしまう
