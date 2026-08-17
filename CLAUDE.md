# Vigilith AI 開発規約

Android / Kotlin / Jetpack Compose。AIはオンデバイスの Gemini Nano（ML Kit GenAI Prompt API）。
ネットワーク権限は持たない。ユーザーのVault（Obsidian の `.md` 群）を SAF 経由で読み書きする。

> **この文書の位置づけ = 憲法。** 常時効かせる原則・参照先・禁止事項・完了条件だけを置く。
> 背景と判断理由は `docs/dev/features/`・`docs/dev/system/`・`docs/dev/decisions/`（法律）、作業手順は原則Skill（作業標準書）が持つ。
> **例外として、Codexが端末とリポジトリを一体で扱う実機検証は [device_validation](docs/review/device_validation/README.md) が持つ。**
> ここに詳細を書き足さない。

## 最優先文書

@docs/dev/system/architecture.md

上記は全変更に効く横断規約なので常時読み込む。**機能固有の変更では、着手前に対応する設計書を必ず読むこと。**
どれを読むかは [docs/dev/document_map.md](docs/dev/document_map.md) §5 の逆引き表で引く。主要なものだけ再掲する。

| 触るところ | 先に読む |
|---|---|
| `NoteViewModel.kt` / `controller/` | [architecture](docs/dev/system/architecture.md) → 該当機能の設計書 |
| `ai/` ・AI生成の通知/待ち時間 | [background_ai_ux](docs/dev/system/background_ai_ux.md) |
| `ui/theme/` ・色/テーマ | **[ui_design_principles](docs/dev/system/ui_design_principles.md)（先に読む）** → [theme_and_ui_refactor](docs/dev/system/theme_and_ui_refactor.md) → [dark_mode](docs/dev/features/dark_mode.md) |
| `data/`（SAF・サイドカー・書き戻し） | [reflect_reading_trace](docs/dev/features/reflect_reading_trace.md) / [reflect_distill](docs/dev/features/reflect_distill.md) |
| `ui/vigilith/` | [character_vigilith](docs/dev/features/character_vigilith.md) → [vigilith_in_app](docs/dev/features/vigilith_in_app.md) |
| `androidTest/` | [instrumentation_testing](docs/dev/system/instrumentation_testing.md) |

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

- **類似コードを見つけても、設計文書の判断を無視して安易に共通化しない。** Controllerの相似形は 2026-07-24 と 07-25 の2度の検討を経て「**共通化しない**」で決着済み（→ [architecture.md](docs/dev/system/architecture.md) の追記2節）。再提案するなら、そこに書かれた再検討条件を満たすことを先に示す
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
- **実機確認はCodexが行う。** 着手前に [共通手順](docs/review/device_validation/README.md) と対象機能のケースを読み、
  一時領域だけで検証して元Vaultへ戻す。共通手順の権限範囲は実機検証依頼に含まれるため、操作ごとに承認を取り直さない。
  **端末を特定する値（シリアル・Vault URI・端末内パス）は文書へ残さない** — 検証開始時に `adb devices -l` で取得する。
  **実機確認が済むまでPR本文に「確認完了」と書かない**
- Lint は現在 Error 0 / Warning 0 / hint 12（hint は依存更新系の催促で、ゲートに載せない）。**警告を増やさない**
- 端末AIを呼ぶ instrumentation テストは、Nano が使えない端末では `Assume` で skip する。**skip 判定は既知の `FeatureStatus` だけで行い、計測・生成呼び出しが投げた例外は skip せず失敗させる**（`checkAvailability()` は例外も畳むので判定に使わない）→ [ai_input_excerpt](docs/dev/system/ai_input_excerpt.md)

**構造を変えたら、変更箇所だけで終わらせない（影響面監査）。**

下の引き金を踏んだら、**その行の「証拠」を出すまで完了報告しない。「確認した」は証拠にならない**
（→ [L14](docs/dev/lessons.md#l14-横展開は最後の1本を取り残す)）。

| 引き金 | 出す証拠 |
|---|---|
| 状態型に欄を足した／意味を変えた | その欄を**読む全箇所**（UI・派生状態・`toEventKey`）を性質で grep した結果 |
| 非同期Jobやキャンセル・再試行を変えた | **両方向**のテスト（片方の実行中・失敗中にもう片方を操作する）＋走行フラグが残らないテスト |
| 型・値・欄を改名／削除した | ソース走査テスト（`AiAvailabilityUsageTest` / `DesignDocStateNameTest`） |
| 上記のいずれか | **正本文書**（`features/` `system/` `owner/`）の該当記述を直したこと |

**共存しうる2つの処理は、片方ずつのテストでは面が永久に空く。** 実例と型は
`SectionChatCombinationTest` が持つ（4巡のレビューで出た欠陥のうち2件がこの面にあった）。

**レビューは受け取った経路を問わず、着手前に `docs/review/` へ本文を置く。** チャットで受けても同じ。
置いた時点で `ReviewFindingsLedgerTest` が全指摘の受付を検査するので、
**行番号まで名指しされた指摘の取りこぼしが落ちる。** 置かなければ検査そのものが起動しない
（2026-08-12〜13 の5巡は、これを怠って16件が受付簿の外にあった）。
受付簿は未解決事項だけを持つ。修正確認まで済んだ指摘は課題台帳と同時に削除し、完了履歴を残さない。

**変更を終える前に:**

1. [docs/dev/change_history.md](docs/dev/change_history.md) へPR単位で1行追記する。**「変更内容」は1文・100字以内** — 経緯・代償・変異確認の結果・教訓はここに書かない（行き先は 2. と 4.）
2. 設計判断や試行錯誤があった変更だけ、対応する `docs/dev/features/*.md` か `docs/dev/system/*.md` に追記する（自明な変更は履歴1行のみ）
3. 解析書・総評で「問題」と書いたものは [docs/_wip/current_issues.md](docs/_wip/current_issues.md) に起票する。**実機検証まで終わったら即座に削除する**（実装完了では消さない。検証待ちが台帳から消えると誰も確認しなくなる）。完了の経緯は残さない — 記録は 1. が持ち、教訓は 4. が持つ
4. **同じ形の失敗を2度した、または1度でも構造上また起きる**と判断したら [docs/dev/lessons.md](docs/dev/lessons.md) の索引へ1行足し、長ければ [lessons/](docs/dev/lessons/) にカードを作る。**番号は振り直さず末尾へ足す**（既存IDの意味を変えない — 外部参照が壊れる）。カードは20行を目安にし、超えたら詳細の正本を `features/` か `system/` に決めて要約＋リンクにする
5. **同じ事件を design・lessons・change_history へ3回とも長文で書かない。** 正本を1つ決め、他は要約＋リンクにする

## 文書の扱い

**設計文書は種別で分ける（2026-08-11）。旧 `dev/design/` は3つへ割った。** 種別が同じ場所に混ざると役割が曖昧になり、実際に24本中11本がADRの形（`## 判断N`）で機能仕様と同居していた。

| 置き場 | 何を書くか | 判断の軸 |
|---|---|---|
| `dev/features/` | **ユーザーから見える機能**の仕様と実現方法。様式は [`_template.md`](docs/dev/features/_template.md) | 「何ができるか」をオーナーが読んで分かるか |
| `dev/system/` | **横断的な基盤**（責務・保証・不変条件・利用者）。ユーザーフローは書かない | ユーザー機能ではなく、全機能に効くか |
| `dev/decisions/` | **ADR。覆りにくい重大判断だけ**（文脈・決定・帰結、**30行以内＝`AdrShapeTest` が固定**） | 後から「なぜ？」となるか。機能追加ごとには作らない |

- **ADRに設計の写しを置かない。** `decisions/` は「なぜ」の索引であって正本ではない。**詳細の正本は必ず `features/` か `system/` 側**で、ADRはそこへリンクする（正本が2つに割れると、どちらかが必ず古くなる）
- **`docs/review/` 配下の日付つきレビュー本文は最新の1本だけを置き、書き換えない。**
  **本文はコミットしない**（`.gitignore`）— 端末の識別子や検証中のローカルパスが入るため。
  新しいレビューを受け付けたら前の本文は削除する。**存在と処遇は `findings.md` が引き受ける**ので、本文が消えても追跡は切れない。
  様式（`review_template.md`）・未解決指摘の受付簿（`findings.md`）・Codexの実機手順（`device_validation/`）はこちらが持ち、
  解消済みの受付行は削除する。実機ケースは手順だけを持ち、日付つき結果を蓄積しない
- **恒久文書から `_wip/` の項目IDへ依存しない。** `_wip/` はリリース時に廃棄するので、`SYNC-2` のような項目番号を設計書や記録から参照すると、廃棄した瞬間に意味が消える。**課題に触れるときは番号ではなく内容そのものを書く。** ただし**入口・索引（`docs/README.md`・`dev/document_map.md`・`review/README.md`）はフォルダとして案内してよい** — 廃棄時に索引ごと直せばよいため
- `features/` `system/` の各文書には `**状態:**` 行を置く

## 作業の進め方

- **修正1件＝1コミット。** コミットメッセージは日本語で「何を・なぜ」
- PR本文も日本語で、修正表＋「見た目・挙動の変更点」＋「実機確認ポイント」を載せる
- ブランチはユーザーがGitHub側で作成する。「フェッチして」と言われたら、main を ff-only で pull → マージ済み旧ブランチをローカル削除 → 新ブランチを checkout
- **修正が効いていることの主張は、修正コードを別の目で読むまで確定させない。** 方針が正しいと、実装が効いていなくても文書とコミットメッセージだけ通ってしまう
- **「別の目」は、実装したのとは別のモデル／エージェントがdiffをレビューすること。自分の2回目のパスは数えない。** コミット前に通す恒久の工程であって、余裕があるときの安全網ではない — 実績上、見落としを最も確実に捕まえているのはこの工程である（外部レビューで9件・4件・2件。自己パスの検出はゼロではないが同水準ではない）
