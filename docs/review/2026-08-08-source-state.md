# ソースコード総評 — 2026-08-08

**基準HEAD:** `579c16a`（`feature/New_Function_No.2`）

**対象範囲:** `origin/main...579c16a` の本番差分、`src/debug` の偽SAFプロバイダ、
`src/androidTest` 34件、テスト形状の機械検査、`.github/workflows/ci.yml`、関連設計・課題文書

**検証コマンド:**

```bash
env JAVA_HOME='/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest --rerun-tasks
git diff --check origin/main...HEAD
```

**実測:** JVMテスト **748件成功・0 failure・0 error・0 skipped** / Lint **0 error・0 warning・12 Hint** /
androidTest APK組み立て成功 / `@Test` 宣言 **34件** / 差分検査成功。
instrumentation の実行は本レビューでは再実施していない。
リポジトリに記録された直近実績は Pixel 10 Pro Fold（Android 17）の **34/34成功・0 skipped** である。

> **位置づけ:** 過去のレビューは各対象コミット時点のスナップショットとして別ファイルに残る。
> 本書は `579c16a` 時点の独立した評価である。現行テンプレートに従い数値採点は行わない。

## 結論

今回変更された本番コードでは、`BoundedInputStream` の exact / overflow 判定、
画像Gatewayでの `TooLarge` / `Broken` 分類、ReadingTraceの三値再走査と遮断器包含が、
元の再現順序を閉じる形で修正されている。差分から新しい本番不具合は確認できなかった。

一方、追加された instrumentation には、**実際に試していることより保証の主張が広い箇所が3件**ある。
さらに偽SAFの同一性保持に1件、文書運用の状態同期に1件のずれがある。
新規指摘は **P2 3件・P3 2件**である。

CIへのエミュレータ追加は、現時点では**見送りの判断でよい**。
25件の反復実行は自動化できるが、下記P2-1〜P2-3の保証範囲はジョブを足しても広がらず、
Nano依存9件の実機確認も残る。まずテストの主張と観測を一致させる方が先である。
再検討条件を「件数」ではなく、実行忘れの実害・複数人開発・PR頻度・リリースゲートへ置いた判断は妥当である。

ただしCIの説明には注意が要る。現在は**1つのbuild job内で3つのGradle step**を直列実行し、
トリガーは `pull_request` と **`main` へのpushだけ**である。
「任意ブランチへpushするたび」は正しくなく、featureブランチではPRが開いている場合の更新時に走る。

## 1. 前回指摘の判定

| # | 前回の指摘 | 判定 | 根拠・残り |
|---|---|---|---|
| 2026-08-03 P1-1 | 未計測画像の仮高から後続ブロックの進捗が永続化される | **未解消** | `NoteImage.kt` と進捗報告経路は対象差分で変わっておらず、IMG-1の成立順序は残る |
| 2026-08-03 P2-1 | 索引ヒット時に `contentVersion` が更新されない | **未解消** | `VaultImageIndexStore.resolve()` のヒット時早期returnは残り、IMG-2の外部上書き順序は閉じていない |
| 2026-08-03 P2-2 | 上限ちょうどの入力を切断扱いにする | **解消** | 上限到達時に1バイトだけ先読みして超過を確定し、単一read・配列read・skip・混在・zero-lengthを13件で固定。実物Gatewayの7件も追加された |
| 2026-08-01 P1-1 | 削除直前の再走査が三値になっていない | **解消** | `NotePresence` が PRESENT / MISSING / INDETERMINATE を保持し、読取不能時に削除へ進まない |
| 2026-08-01 P2-1 | ルート直下の遮断器が別サブツリーの遮断器を消す | **解消** | 包含判定を `breakerGroupPathsOfFolder()` と同じグループ定義へ統一し、ルートと別サブツリーの回帰テストがある |
| 2026-08-01 P2-2 | 連続削除が同じJobを奪い合う | **未解消** | `ReadingTraceCleanupController` のJob所有は対象差分で変わっておらず、TRACE-3が継続している |
| 2026-08-01 P2-3 | 完成プロンプトにハード上限が無い | **未解消** | 端末計測テストは追加されたが、本番入力を切り詰める上限ではない。AI-3が継続している |
| 2026-08-01 P2-4 | ReadingTrace索引が外部追加を自動認識しない | **解消** | 不在時に索引を再生成する経路と回帰テストが既に入り、今回の差分でも維持されている |

判定は実装やテストの追加件数ではなく、前回記載された再現順序が閉じたかで行った。

## 2. 指摘

### P2-1. 「連打」テストが操作間で同期し、バックスタック退行も検出しない

- **該当箇所:** [`TabNavigationTest.kt`](../../app/src/androidTest/java/com/example/newproject/ui/TabNavigationTest.kt#L65) の連打テスト、
  [`AppScaffold.kt`](../../app/src/main/java/com/example/newproject/ui/AppScaffold.kt#L208) の `navigateToTab()`
- **成立する順序:** ① `onNodeWithText(...).performClick()` を続けて呼ぶ
  ② Compose UI Test 1.7.3 の `performClick()` は `performTouchInput()` を経由する
  ③各呼び出しは `fetchSemanticsNode()` でUIと同期してから次のノードを取り直す
  ④明示的な `waitForIdle()` が無くても、クリック列は「遷移が重なった状態」にならない
  ⑤さらに `launchSingleTop` または `popUpTo` を外してバックスタックを積ませても、最後にNoteを選べば
  `NOTE_MARKER` は表示されるため、現在の最終assertは成功する
- **影響:** テストのKDocが主張する「連続操作時の競合」「バックスタックの積み上がり」は保護されていない。
  エミュレータで毎回実行しても、戻る回数が増える退行や重複エントリを緑のまま通す。
- **修正方針:** 実クリックの競合を試すなら、下部ナビの座標を1回の入力ブロックで連続tapし、
  操作ごとのSemantics再取得を挟まない。バックスタック契約は競合と分け、
  最後の画面だけでなくsystem back後の画面またはテスト用 `NavController` のエントリを観測する。
- **受理条件:**
  - `launchSingleTop` を外す変異でテストが失敗する
  - `popUpTo` を外す変異でテストが失敗する
  - 「連打」ケースはクリックごとにUI同期するAPI列ではなく、idle前に複数入力を投入する
  - 正常時はsystem back 1回で設計どおりの画面へ戻り、重複したタブ履歴を踏まない
- **規模感:** 小〜中

### P2-2. `ActivityScenario.recreate()` をプロセス再生成の検証として記録している

- **該当箇所:** [`ProcessRecreationTest.kt`](../../app/src/androidTest/java/com/example/newproject/ui/ProcessRecreationTest.kt#L12)、
  [`instrumentation_testing.md`](../dev/design/instrumentation_testing.md#L69)、
  [`change_history.md`](../dev/change_history.md#L16)
- **成立する順序:** ① `scenario.recreate()` を呼ぶ
  ② AndroidX Test Core 1.6.1 は現在のActivityを `onSaveInstanceState()` 後に破棄し、
  保存Bundleで新しいActivityを**同一プロセス内に**生成する
  ③プロセス・Application・静的状態・プロセス内キャッシュは生存する
  ④テストは成功し、文書上は「プロセス復元も確認済み」と読める
  ⑤プロセス死亡時だけ初期化される状態や、永続層からの復元に退行があっても検出されない
- **影響:** Activity再生成とプロセス死亡後の復元が同じ保証として扱われる。
  34/34成功から、プロセス死亡に耐えることまでは結論できない。
- **修正方針:** 現状のテストはActivity再生成テストとして残し、クラス名・KDoc・設計・変更履歴の主張を
  「Activity再生成（回転/Fold等）」へ狭める。プロセス死亡を保証したい場合だけ、
  対象プロセスを終了して永続状態から再起動する独立シナリオを追加する。
- **受理条件:**
  - `ActivityScenario.recreate()` に結び付いた「プロセス再生成／プロセス復元」の表記が残らない
  - 現テストの保証範囲が同一プロセス内のActivity再生成だと明記される
  - プロセス復元を保証対象に残す場合は、Application/静的状態が初期化されたことを前提条件として検査する別テストが修正前に失敗する
- **規模感:** 小（主張を狭める場合）／中〜大（実プロセス死亡を追加する場合）

### P2-3. 端末AIは10種類中4種類の生成だけを通すが「各プロンプト」と主張している

- **該当箇所:** [`OnDeviceGenerationTest.kt`](../../app/src/androidTest/java/com/example/newproject/ai/OnDeviceGenerationTest.kt#L19) の保証説明と4テスト、
  [`PromptBuilder.kt`](../../app/src/main/java/com/example/newproject/ai/PromptBuilder.kt#L37) の10個のbuilder
- **成立する順序:** ① 要約・クイズ・関連ノート・セクション要約の4経路は成功する
  ②未実行の読書痕跡要約・蒸留・検索picker・補記・セクション候補・セクションchatのいずれかに、
  プロンプト長・引数・SDK呼出しの退行を入れる
  ③4件の生成テストと5件のトークン計測/診断は成功する
  ④KDocの「本番の各プロンプトで `generate()`」と、Nano依存9件という総数から全経路が守られたように読める
- **影響:** 9件のNano依存テストのうち、実生成は4経路だけである。
  共通のAICore故障は検出できるが、プロンプト固有の6経路は34/34成功でも未保証である。
- **修正方針:** 今回の目的が共通SDK経路の疎通なら、KDocと設計を「代表4経路」に狭め、
  非対象6経路を明記する。全プロンプトを保証したいなら、10個のbuilderと本番 `generate()` 呼出しの対応表を作り、
  未実行6経路をパラメータ化して追加する。
- **受理条件:**
  - 本番の10 builderと、実生成テストの対応が機械的または表で追える
  - 「各プロンプト」と主張する場合は各builder固有の入力で `generate()` が呼ばれる
  - 代表テストに留める場合は、未保証6経路がテストKDocと設計書の両方に列挙される
  - いずれかの保証対象builderをテスト用の不正入力へ変える変異で対応テストが失敗する
- **規模感:** 小（主張を狭める場合）／中（10経路へ広げる場合）

### P3-1. 偽Vaultが異なるdocument IDを同じファイルへ潰し、同一パスの再投入で子を重複させる

- **該当箇所:** [`FakeVaultDocumentsProvider.kt`](../../app/src/debug/java/com/example/newproject/testing/FakeVaultDocumentsProvider.kt#L218) の `putBinaryFile()` と、
  同ファイルの [`fileOf()`](../../app/src/debug/java/com/example/newproject/testing/FakeVaultDocumentsProvider.kt#L281)
- **成立する順序:** ① `a_b.md` と `a/b.md` を置く
  ②document IDは `root/a_b.md` と `root/a/b.md` で別になる
  ③ `id.replace('/', '_')` により、両方の実体は `root_a_b.md` になる
  ④後から置いた内容が先の内容を上書きし、両documentのサイズと内容が同じになる。
  別経路として同一パスを2回 `putFile()` すると、`nodes` は置換されるが親の `childIds` には同じIDが2件積まれる
- **影響:** 現行テストのデータ名では顕在化していないが、今後のパス分離・外部上書きテストが
  実プロバイダには無い衝突や重複行を観測し、誤った成功／失敗を返し得る。
- **修正方針:** backing file名はdocument IDの可逆エンコードまたは衝突しないhashにする。
  同一IDの再投入は親の子集合を増やさず、内容・更新日時・サイズ申告だけを更新するAPIとして扱う。
- **受理条件:**
  - `a_b.md` と `a/b.md` が異なる内容・サイズを保持する
  - 同一パスを2回投入しても親の列挙結果は1行で、内容と更新日時だけが新しくなる
  - 上記2ケースを実物の `ContentResolver.query/openInputStream` 経由で確認するinstrumentationテストが修正前に失敗し、修正後に成功する
- **規模感:** 小

### P3-2. TEST-2を「手動実行の担保」へ転用したが、見出しと状態文書は「テストの中身が無い」のままである

- **該当箇所:** [`current_issues.md`](../_wip/current_issues.md#L36) と同文書のTEST-2、
  [`roadmap.md`](../_wip/roadmap.md#L103)、[`document_map.md`](../dev/document_map.md#L71)、
  [`instrumentation_testing.md`](../dev/design/instrumentation_testing.md#L5)、[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml#L6)
- **成立する順序:** ①全34件を実装し、実機34/34を確認し、CIエミュレータ見送りも確定する
  ② `current_issues` は本文末尾だけを「PR前の手動実行が残る」へ更新する
  ③一覧・見出し・roadmapは「統合テストの中身が無い」、document mapは「段階1完了」、
  設計書の状態欄は「CI実行の要否が残る」のまま残る
  ④次の読者は、未実装・全実装・判断待ち・見送り確定の4状態を同時に読むことになる
- **影響:** `current_issues` の「未対応だけを置く」、roadmapの「順序だけを持つ」、designの「決定を持つ」という
  分担が崩れ、完了済みの中身を再度計画するか、手動運用のリスクを見落とす。
- **修正方針:** TEST-2を残すなら「instrumentation実行がCIで担保されない」へIDの表示名・影響・本文を揃える。
  見送りを受容済みリスクとして閉じるなら `current_issues` から削除し、判断と再検討条件だけを設計書に残す。
  どちらでもroadmap・document map・設計書冒頭を現状へ同期し、CIトリガーは「PRとmain push」と記す。
- **受理条件:**
  - `current_issues` の一覧・見出し・本文が同じ未対応内容を指す
  - roadmapが実装済み34件を「中身が無い」と扱わない
  - document mapが全段階完了、設計書冒頭がCI見送り確定を示す
  - CI説明がfeatureブランチを含む任意pushではなく、workflowの `pull_request` / `push.branches: [main]` と一致する
  - 文書整合テストまたは同等の検査で、上記の旧状態へ戻す変異を検出する
- **規模感:** 小

## 3. 保証していない範囲

- 本レビューでは実機instrumentationを再実行していない。34/34は既存の実行記録を確認した値である。
- `ActivityScenario.recreate()` は同一プロセス内のActivity再生成を覆うが、プロセス死亡後の復元を覆わない。
- タブ連打テストは操作を重ねず、`launchSingleTop` / `popUpTo` の退行を検出しない。
- Nano依存9件の内訳は生成4件・計測/診断5件で、10種類の本番プロンプト全てを生成してはいない。
- エミュレータを追加してもNano依存9件はskipされ、実機確認は置き換わらない。
- CIはfeatureブランチへの単独pushでは走らない。PRを開く前のpushには自動検査が無い。
- 既存課題のIMG-1・IMG-2・TRACE-3・AI-3は未解消であり、748件成功から解消とは判断できない。

## 4. 同型の再発

| 過去に扱った型 | 今回の対応関係 | 再発防止の観点 |
|---|---|---|
| 「網羅テストがある」と「対象フィールドを網羅している」は別 | 34件という総数が、連打・プロセス死亡・全AI経路の保証へ読み替わっている | 件数ではなく、状態遷移と変異がどのテストで落ちるかを対応付ける |
| 規則を書いただけでは検査にならない | 「PR前に実行する」は明記されたが、CIではコンパイルだけで実行記録も機械判定しない | 手動を選ぶなら、実害ベースの再検討条件と実行記録を維持する |
| Fakeと本番の状態遷移が違うと、実配線テストでも結論がずれる | document IDは別なのにbacking fileが衝突し、上書きで子が重複する | Fake追加時に同一性・更新・削除・列挙件数を本番契約と突き合わせる |
| 記録先を増やすと片方が古くなる | TEST-2の実装状態が課題台帳・roadmap・document map・設計書で分岐した | 未対応・順序・決定・索引の所有者を守り、同じ状態文を複製しない |

## 5. 課題起票

本書の新規5件は、受付簿でそれぞれ独立に処遇を決める。

- P2-1はタブ連打とバックスタック契約のテスト不備として起票対象にする。
- P2-2は、まず保証表記をActivity再生成へ狭めるか、実プロセス死亡テストを追加するかを選んで起票する。
- P2-3は、代表4経路の疎通へ主張を狭めるか、全10経路へ実生成を広げるかを選んで起票する。
- P3-1は偽Vaultのdocument同一性・列挙件数の修正として起票対象にする。
- P3-2は文書運用の整合として起票対象にし、TEST-2を改題して残すか、受容済みリスクとして閉じるかを決める。

既存のIMG-1・IMG-2・TRACE-3・AI-3は再起票せず、現在の課題IDで追跡を継続する。
本書は `579c16a` 時点のスナップショットとして後から書き換えない。
