# AI状態UX・再修正確認レビュー — 2026-08-14

**基準HEAD:** `46b9804`（`feature/Refactoring_Function_No.XX`）

**対象範囲:** 前回基準 `9837ea9` から `46b9804` までの3コミット。
質問候補の派生状態、`checkAvailability()` 10呼び出しの契約テスト、
状態型と正本の同期検査、KDocリンク検査、受付簿とAI-2の処遇更新

**検証コマンド:**

```bash
env JAVA_HOME='/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest --offline --rerun-tasks
git diff --check 9837ea9...HEAD
```

**実測:** JVMテスト **932件成功・0 failure・0 skipped** / Lint **0 error・0 warning** /
androidTest APK組み立て成功 / `@Test` 宣言 **40件** / 差分検査成功。
instrumentation と実機のNano経路は実行していない。

**追加の変異確認:**

- `RemarkController.generateMirror()` のキャンセル再throwを握りつぶしても、
  `RemarkControllerTest` と `AiAvailabilityContractTest` は**全件成功した**
- `ReadingTraceController.generateSummary()` のキャンセル再throwを `null` 返却へ変えても、
  `ReadingTraceControllerTest` と `AiAvailabilityContractTest` は**全件成功した**
- 正本の `SectionChatState` 一覧から `isSuggestionsLoading` だけを削除し、直後の説明には残すと、
  `SourceDocSyncTest` は**全件成功した**

> **位置づけ:** 本書は `46b9804` 時点の独立した机上評価である。過去レビューの要約と処遇は
> README・findings、原文はgit履歴に残る。

## 結論

前回P2-1の表示不整合は解消した。候補生成中はシートとVigilith/FABがともに `Working`、
完了後は `Ready`、候補Jobを始めないAI状態では `Idle` になるテストが揃っている。
P2-2とP3-1も、本番コードと現行文書そのものは正しい。

一方、追加した検査の保証範囲が再び説明より狭い。10呼び出しの対応表で
キャンセル確認済みとする映し返し・読書痕跡は、専用catchを握りつぶす変異を緑で通す。
状態同期検査も「状態一覧」を見ず文書全体の単語有無を見るため、一覧から欄だけを落としても緑になる。

新規指摘は **P2 1件・P3 1件**。P1は確認しなかった。
実機確認へ移る前に、検査の主張と実測を一致させる必要がある。

## 1. 前回指摘の判定

| # | 前回の指摘 | 判定 | 根拠・残り |
|---|---|---|---|
| 2026-08-14-fix/P2-1 | 候補生成中に派生表示が `Ready` | **解消** | `sectionChatStatus()` が3走行フラグを読み、Deferred中 `Working`・完了後 `Ready`・Job無し `Idle` を直接テストしている |
| 2026-08-14-fix/P2-2 | 契約テストが全経路とキャンセル再throwを保証しない | **部分解消** | 本番10呼び出しの対応表、非キャンセル例外、主要8経路のキャンセル観測は入った。映し返しと読書痕跡の再throw変異は緑のまま（新規P2-1） |
| 2026-08-14-fix/P3-1 | 状態欄の正本漏れとKDocリンク切れ | **部分解消** | 現行の一覧とリンクは正しい。リンク検査も効くが、状態同期検査は一覧外の言及で満たせる（新規P3-1） |

## 2. 指摘

### P2-1. 対応表のうち2経路はキャンセル再throwを検査していない

- **該当箇所:** [`AiAvailabilityContractTest.kt:63`](../../app/src/test/java/com/example/newproject/AiAvailabilityContractTest.kt#L63)、
  [`RemarkControllerTest.kt:472`](../../app/src/test/java/com/example/newproject/RemarkControllerTest.kt#L472)、
  [`ReadingTraceControllerTest.kt:960`](../../app/src/test/java/com/example/newproject/ReadingTraceControllerTest.kt#L960)
- **成立する順序:** ①対応表は映し返しと読書痕跡のキャンセル列を既存テストで確認済みとする
  ②映し返しのテストは `mirrored == null` だけを見るため、再throwでも握りつぶしでも同じ結果になる
  ③読書痕跡には状態確認の非キャンセル例外テストしかなく、キャンセルを投入しない
  ④両方の専用catchを握りつぶす変異を入れる ⑤対応表が指すテストと横断契約テストが全件成功する
- **影響:** 本番実装は現時点で正しく再throwしているが、必須原則の退行を2経路で検出できない。
  とくに読書痕跡は、握りつぶすとキャンセル後に `null` の正常劣化として後続処理へ進む
- **修正方針:** 2経路とも、キャンセルを「結果が空」ではなく**伝播そのもの**で観測できる境界を作る。
  内部suspend関数を直接テスト可能な単位へ切り出すか、起動Jobの完了原因を観測し、
  投入した `CancellationException` が握りつぶされていないことを固定する。
  あわせてクラスKDoc冒頭の「投げても走行状態を残さない」と、キャンセル時は状態を触らない説明を分ける
- **受理条件:**
  - 映し返しの専用catchを握りつぶす変異で、名指しのキャンセルテストが失敗する
  - 読書痕跡の専用catchを `null` 返却へ変える変異で、名指しのキャンセルテストが失敗する
  - 対応表の10行すべてで、例外とキャンセルの観測先が変異を殺せる
  - 非キャンセル例外は従来どおり、映し返しは無音、読書痕跡は生カードへ劣化する
- **規模感:** 小〜中

### P3-1. 状態同期検査が「一覧」ではなく文書全体の単語有無を見ている

- **該当箇所:** [`SourceDocSyncTest.kt:42`](../../app/src/test/java/com/example/newproject/architecture/SourceDocSyncTest.kt#L42)、
  [`section_ai_chat.md:72`](../dev/features/section_ai_chat.md#L72)
- **成立する順序:** ①正本の状態一覧から `isSuggestionsLoading` だけを削除する
  ②直後の設計説明には同名が残る ③検査は `documented.contains("`$it`")` で文書全体を検索する
  ④一覧が欠けても説明中の1語で満たされ、`SourceDocSyncTest` が成功する
- **影響:** 現行文書は正しいが、受理条件の「実装の全フィールドが状態一覧にある」を検査できない。
  初版の抽出漏れを直した自己検査はコード側の母数を守るだけで、文書側の観測範囲は守らない
- **修正方針:** `SectionChatState` の一覧部分を見出し・行範囲・機械可読な表などで限定し、
  その範囲に全フィールドがあることを照合する。単なる文書全体の言及は一覧登録として数えない
- **受理条件:**
  - `isSuggestionsLoading` を一覧からだけ外し、説明には残す変異で失敗する
  - 説明からだけ外し、一覧に残す場合は状態一覧の検査として成功する
  - コード側のフィールド抽出0件は従来どおり自己検査で失敗する
  - KDoc相対リンク検査は現状どおり実在しないリンクを検出する
- **規模感:** 小

## 3. 保証していない範囲

- 本レビューは机上確認であり、AI状態UXの実機確認7項目は未実施である。
- `assembleDebugAndroidTest` は40件のコンパイルとAPK組み立てまでで、instrumentationは実行していない。
- 実端末のNano状態遷移・AICore能力判定・高速ノート切替は覆っていない。
- P2-1の本番コードは現時点でキャンセルを再throwしている。指摘は、その退行を検査できない点である。
- P3-1の現行正本には全フィールドが記載されている。指摘は、一覧からの退行を検査できない点である。
- AI状態UX以外の既存課題は再評価していない。

## 4. 同型の再発

| 過去に扱った型 | 今回の対応関係 | 再発防止の観点 |
|---|---|---|
| 表を作ったが、行が指すテストの観測が目的と違う | 10経路を列挙したが、2行は「空の結果」を見て再throwを見ていない（P2-1） | 対応表は存在確認ではなく、各行の名指し変異が落ちることを完了証拠にする |
| 検査が文書のどこかにある単語で満たされる | 状態一覧を守る検査が、直後の説明文だけで成功する（P3-1） | 守る構造の範囲を機械的に区切り、その外側の言及を数えない |

## 5. 課題起票

新規2件は、実機確認待ちのAI状態UXと同じ変更単位なので既存の **AI-2へ統合**する。
P2-1とP3-1を机上で閉じた後も、既存の実機確認7項目は省略しない。

本書は `46b9804` 時点のスナップショットとして後から書き換えない。
