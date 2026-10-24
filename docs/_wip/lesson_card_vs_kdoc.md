# 検査を持つ教訓 — カードとKDocの仕分け・Claudeへの引き継ぎ

**基準:** 2026-10-12、`684b6162`。Codexによる文書・検査の静的な照合。
**状態:** オーナーが §3 の仕分けと §4 の扱いを案どおり採用した（2026-10-24）。カード・索引・KDoc の整理は実施済みで、Codex の差分レビュー待ち。検査本体（assert・fixture・閾値）は変えていない。
**関連:** [課題台帳のPROC-1](current_issues.md#proc-1-過剰な検証の実体は検査ではなく文書の量である)。
**寿命:** 使い捨て。判断結果を実施依頼へ、採用した説明を正本へ移し、必要な引き継ぎが済んでから削除する。

## 1. 提案の要点

対象23件について、**教訓側を正本にする16件、KDoc側へ詳細を集約する7件**を提案する。
教訓側16件には、独立カードを持たないL14（索引内本文）を含む。
KDoc側の7件は **L30・L40・L44・L46・L48・L50・L61**。

**一律にカードを消す案ではない。** 横断的な判断は教訓へ、検査固有の観測方法・入力の理由・保証の限界はKDocへ残す。
同じ結論を1行で示すことは許し、長い事件の説明や同じ手順を2箇所で維持する部分を減らす。
この表の「正本」は**重複する教訓の詳しい説明の置き場所**を指す。機能仕様・値・責務の正本である既存の `features/`・`system/` は動かさない。

KDoc側へ集約するカードも、**IDと既存ファイルの入口を保ち、1〜2文の要約＋当てる場面＋リンクへ短縮**する。
`owner/` や過去の記録からも参照されているため、物理削除して参照元を全部書き換える方法は採らない案とする。
教訓側のカードは原則20行程度を目安にする既存方針を使い、複数事例の長文を再掲しない。新しい行数検査は追加しない。

## 2. 対象と比較方法

- [lessonsの索引](../dev/lessons.md)の「検査」列に検査名がある**23件**を抽出。独立カード22本708行と、索引内L14本文33行を読んだ（空行込み、末尾の節間空行を除く）。
- 索引で名指しされた検査27ファイルに、カードが補足する4ファイルを加えた**31ファイル**の関係するKDoc・コメントを照合。対応するテスト名や観測も必要な範囲で確認した。全テストの品質監査や新しい変異実験ではない。
- L29は索引の3本と本文の7本の和集合9本を確認。「ほか6本」だけで追跡を終えない。L51の `DistillSourceModelTest`、L52の `DistillProtectedScanTest` も含めた。
- 参照元は `docs/`・`app/src/`・`CLAUDE.md`・`.github/` を検索。ファイルリンクに加え、索引へのリンクと裸のIDも確認した。カードを短縮する案ではURLを維持する。
- **重複度は意味による定性評価。** 高＝原因と対策の説明をほぼ同じ形で再掲、中＝事例や理由の一部が重なる、低＝教訓と検査の射程が違う。短い標語の一致だけで削除を決めない。

重複度は高10件・中11件・低2件。L52は主な検査の通常コメントと、補助検査のKDocを合わせて中とした。
行数は対象の大きさを示す値で、削減可能行数やトークン削減率ではない。採用後の実際の差分で削減量を報告する。

## 3. 23件の仕分け案

| 教訓 | 重複度 | 詳細の正本案 | 比較した検査・箇所 | 理由・独自に残る内容 | 変更するなら |
|---|---|---|---|---|---|
| [L29](../dev/lessons/L29.md) | 中 | 教訓 | ReviewFindingsLedgerTest／AiAvailabilityContractTest／SourceDocSyncTestほか | 規則を検査へ載せる理由と追跡漏れの経緯が重なるが、教訓は規則の置き場所・検査の空振りまで横断する。 | 教訓には3つの当て方を残し事例を圧縮。KDocには対象・対象外・実行方法・受付IDの作り方を残す。 |
| [L14](../dev/lessons.md#l14-横展開は最後の1本を取り残す) | 中 | 教訓（索引内） | CrystalSaveInterleavingTest／ThreadingScanTest／SchemaVersionDocsTest | 版更新で6ファイル9箇所を残した経緯は重複。一方、横展開と交錯の原則は複数の検査にまたがる。 | 索引内本文を要点と検査への参照へ短縮。KDocには各検査の範囲、実スレッドの必要性、数えていない経路を残す。 |
| [L25](../dev/lessons/L25.md) | 中 | 教訓 | WipIssueReferenceTest（入力の話はNoteSessionCoordinatorTestも照合） | 全34件確認後に一覧が古く残った経緯は重複するが、入力・系統図・再掲表という別の面もある。 | コピー元と更新先を数える一般則を残す。リセット入力の詳細はL46とそのKDocへ参照し、同じ説明を二重に持たない。 |
| [L30](../dev/lessons/L30.md) | 高 | KDoc | InstrumentationTestShapeTest | JUnitの戻り値、runBlocking末尾の型、クラス全件が起動しない原因・対策がほぼ同じ。 | KDocに検査の条件と対象外を集約。カードは「実行件数の減少も見る」の要約と参照にする。 |
| [L38](../dev/lessons/L38.md) | 中 | 教訓 | AdrShapeTest：最終検証コミットの実在確認 | 存在しないコミットを12文書へ書いた経緯と危険性が重複する。検査は実在だけを確認する。 | 教訓には未確認を明示する判断と、実在と検証済みの違いを残す。KDocはGit照合の契約と限界へ短縮する。 |
| [L39](../dev/lessons/L39.md) | 低 | 教訓 | AiAvailabilityUsageTest | 教訓はSDK契約を推測したFakeの危険性、検査は等値比較・メンバーimportの禁止で、守る範囲が異なる。 | 教訓はFakeを作る前の根拠確認を残す。SDKの機能仕様は既存のbackground_ai_uxを正本のままにし、KDocは構文上の保証を残す。 |
| [L40](../dev/lessons/L40.md) | 高 | KDoc | DesignDocStateNameTest | 改名が文書に残った経緯、裸の旧名を見落とす理由、歴史的言及の扱いが重複し、現行の範囲もずれている。 | KDocに対象・対象外、RETIRED_NAMESと旧名の許容条件を集約。カードは改名時の注意と参照にし、owner文書を同期対象へ戻さない。 |
| [L41](../dev/lessons/L41.md) | 中 | 教訓 | DistillCandidateUnitCopyTest | 単位変更で画面の文言が残った事例は重複するが、教訓の入力上限・文脈の親子単位は検査が見ない。 | 教訓に読み手ごとの親・子の選択を残す。KDocは表示文字列だけを検査することと「本文」「文字」の除外理由を残す。 |
| [L45](../dev/lessons/L45.md) | 低 | 教訓 | PromptBudgetTest | 用途別予算と共通上限という原則は共通。教訓が現在形で指す旧俯瞰要約の最新訪問行の検査は現ファイルに無い。 | 教訓は「意味の単位を保って先に閉じる」へ短縮し、撤去された事例と現在の保証を分ける。KDocの現行最大構成・実トークン未保証は残す。 |
| [L44](../dev/lessons/L44.md) | 高 | KDoc | PromptIndentationTest | 複数行補間でtrimIndentが効かない原因と、単一行の検査が通した経緯が重なる。 | KDocへ集約し「誤った出力を期待値に写しても通る」を短く補う。カードは入力の形を振る原則と参照にする。 |
| [L46](../dev/lessons/L46.md) | 高 | KDoc | NoteSessionCoordinatorTest：リセット検査の入力は全フィールドが初期値と異なる | リフレクションで全欄を見てもfullyPopulatedStateの登録漏れが素通りする説明が直接重複する。 | 当該メソッドのKDocに入力検査の理由を集約。カードは「全部の出どころをたどる」の要約と参照にする。 |
| [L47](../dev/lessons/L47.md) | 中 | 教訓 | ReadingTraceBackupControllerTest：読取不能を新規扱いしない | 読取不能を不在へ畳むと上書きする説明が重複するが、一覧・点読込・他の書き込みにも効く教訓である。 | 教訓は全境界を列挙する判断を残す。KDocはfixtureの失敗点・保留・書込0件の観測を残し、返事を現行機能と読ませない。 |
| [L48](../dev/lessons/L48.md) | 高 | KDoc | ReadingTraceBackupControllerTest：下見後の端末側変更 | 最新値で処理するだけでは承認内容を守れず、変更時は1件も書かず再提示する説明が重複する。 | 当該KDocへ件数でなく内容を照合する理由を集約。カードは承認の原則と参照にし、旧返事の損失説明を現行メモの仕様へ混ぜない。 |
| [L49](../dev/lessons/L49.md) | 高 | 教訓 | ReadingTraceBackupControllerTest／ThreadingScanTest | cancel・join・集計の順序と、停止待ちの再入が重複する。順序走査だけでは再入を観測できない点が要である。 | 教訓は要求→停止→確定と再入の原則へ短縮。KDocは走査と動的検査の分担・別スレッドとラッチの必要性を残す。 |
| [L50](../dev/lessons/L50.md) | 高 | KDoc | ReadingTraceBackupJsonTest | documentIdのキーを外へ出さないことと、拒否文言を同じにする理由が同じ検査へ揃っている。 | 該当2メソッドのKDocに外部形式を観測する理由を集約。カードは「復元後の意味だけでは足りない」の要約と参照にする。 |
| [L51](../dev/lessons/L51.md) | 中 | 教訓 | InlineSyntaxTest／InlineMarkdownTest／DistillSourceModelTest | 表示と蒸留の解釈を共有する理由が重複するが、検査は解釈・表示・保存へ分かれる。 | 教訓は複数の読み手が同じ解釈器を使う判断を残す。機能仕様の正本はreflect_distillのまま。各KDocは自分が観測する境界だけを残す。 |
| [L52](../dev/lessons/L52.md) | 中 | 教訓 | DistillSourceModelTest／DistillProtectedScanTest | 主な検査では通常コメントに、補助検査ではKDocに重複がある。候補数×記法数を増やす理由と、時間で捕まらない経路を構造で守る理由が重なる。 | 教訓に掛け合わさる量を最大化する原則を残す。入力の作り方、時間閾値の根拠、時間で捕まらない構造の説明は検査の隣に残す。 |
| [L53](../dev/lessons/L53.md) | 中 | 教訓 | DistillRangeHighlightTest／DistillRangeNoticeTest | 親文の表示だけでは太字・下線を保証できない説明が重なる。値と描画の境界という判断は両検査より広い。 | 教訓には値をJVMへ出す判断と実測に残す範囲を残す。「実機では変異確認できない」の一般化は要判断。KDocは各観測点を残す。 |
| [L56](../dev/lessons/L56.md) | 中 | 教訓 | BookletNavigationTest | 冊子から戻るとページ位置だけが消えた経緯が重複する。複数状態の寿命を揃える原則は他機能にも使われる。 | 教訓は条件を構成する状態と寿命の照合へ短縮。KDocには最小NavHostを往復する理由と本番そのものではない範囲を残す。 |
| [L55](../dev/lessons/L55.md) | 高 | 教訓 | BookletRouteContractTest／BearingChannelTest | 名前・import・別の面が残る変異、引数境界を緩めた失敗が重複する。複数検査の設計原則なので1テストへ閉じない。 | 教訓は観測対象・最小の変異・保証を緩めた範囲の明示に絞る。KDocには具体的な代入箇所・引数境界・振る舞い検査への対応表を残す。 |
| [L58](../dev/lessons/L58.md) | 高 | 教訓 | BookletRestackTest | 同期完了でLoadingが届かない原因と事例が直接重複するが、ページ位置や余白面にも同じ判断が使われる。 | 教訓は最終状態で識別する原則へ短縮。KDocには同期／遅延とopen／drawAgainの組、最終状態だけ観測する理由を残す。 |
| [L60](../dev/lessons/L60.md) | 中 | 教訓 | BookletSheetPerspectiveTest：カメラ距離の単位 | Viewの別入口を読んで単位を誤認した経緯が重複。SDKの入口・変換・実出力を突き合わせる原則は広い。 | 教訓の「検査なし」と索引の食い違いを判断後に整理。測定値の断定を現行全環境の保証にせず、KDocに逆算方法と測定条件を残す。 |
| [L61](../dev/lessons/L61.md) | 高 | KDoc | BookletSheetPerspectiveTest：傾いた紙の下端幅 | 負なら手前という共通の思い込みを27件が通した事例と、画素で向きを確かめる理由が重なる。 | KDocへ値の一貫性と画素による意味の観測の違いを集約。カードは共通前提を外から確かめる要約と参照にする。 |

## 4. 重複以外に、整理時に判断が必要な箇所

以下も既存のPROC-1へ統合して追跡する。**今回、事実を新仕様に書き換えたり、テストを追加・削除したりはしていない。**

| 対象 | 確認できた差分 | 引き継ぐ判断 |
|---|---|---|
| L60 | 索引は `BookletSheetPerspectiveTest` を挙げ、本文冒頭は「検査なし（参考）」、本文後半は同検査が持つと説明。見出しは実装、索引は出力で確かめるとなる | 「一般則すべてを検査している」とせず、画素測定で固定する具体的な部分と、他SDKでは個別確認が要る部分を明示する |
| L45 | 「最新行が完全な1行として残る／古い側から落ちる」を `PromptBudgetTest` の現行検査として記すが、旧俯瞰要約のその検査は現ファイルに無い | 撤去済み事例を現在の保証として残さない。現行の候補・指示文・最大構成の検査へ案内し、旧機能の検査を復活させない |
| L40 | カードは型名を付けない歴史的言及を説明するが、現KDocは直前の「旧 」を条件とする。過去のowner対象外を失敗として書く一方、現検査はownerを意図的に除外する | 過去の経緯と現行の検査範囲を切り分ける。ownerの更新権限を文書整理で変更しない |
| L47・L48 | 旧返事の消失・下見を事例にしている。現検査は `memos` を使い、下見後の変更もメモで確認する。一部KDocには旧返事の語が残る | 不在と不能、承認内容の照合という原則を残し、旧機能の現役説明を取り除く。メモの合流と旧返事の置換を同じ仕様として扱わない |
| L53 | 「実機では変異確認できない」と一般化しているが、`BookletSheetPerspectiveTest` のKDocには実機の画素検査で変異を検出した記録がある | 当時の実行環境の制約と原理的な不能を区別する。値をJVMへ出す判断は残し、実機でしか観測できないものも明示する。今回その変異は再実行していない |
| L29・L14・L51・L52 | 索引の検査列と本文の列挙が同じ集合ではない。代表例なのか全件なのかが読み取りにくい | 1つの索引に案内を寄せるか、本文側を「代表例」と明示して二重の完全一覧を作らない。検査数を新たな恒久値として固定しない |

L25の入力網羅の説明とL46も重なる。**今回はIDの統合や番号の付け直しを提案しない。**
L25を横断的な「更新先とコピーを数える」へ絞り、入力の詳細はL46が案内するKDocへ寄せれば役割を分けられる。

## 5. 参照を壊さない短縮方法

KDoc側を正本にする7件は、すべて索引から参照されている。加えて次のような入口がある。

| 対象 | 索引以外の参照元の例（ID参照も含む） | 推奨する残し方 |
|---|---|---|
| L30 | `system/instrumentation_testing.md`、L29、ownerの8月記録 | 同じカードURLで件数減少の注意と検査リンクを残す |
| L40 | ownerの教訓集 | 同じカードURLで改名の注意と検査リンクを残す |
| L44 | `system/ai_input_excerpt.md`、`system/bearing_channels.md`、L45、`BearingChannelTest` | 単一行／複数行など入力の形を振る要約を残す |
| L46 | `NoteSessionCoordinatorTest`、ownerの教訓集 | 検査→教訓→同じ検査だけの循環にせず、KDoc自身に完結した理由を残す |
| L48 | `features/reading_trace_backup.md`、ownerの8月記録 | 承認の原則を1文で読み取れる入口を残す |
| L50 | ownerの8月記録・教訓集 | 外へ出る形を観測する原則と2検査への案内を残す |
| L61 | `features/booklet_mode.md`、実機ケース、L60、ownerの検査報告 | 共通前提を外の観測で確かめる原則と画素検査への案内を残す |

参照元の全文一覧は成果物へ複製しない。改修時に対象ID・ファイル名を検索し直し、**既存のカードURL／索引アンカーを維持**する。
索引アンカーは見出しの変更でも壊れるので、L14を短くするときも見出しとIDは動かさない。
既に機能仕様の正本を指すL39・L40・L51のリンクは保ち、KDocへ機能仕様の長文を移植しない。

## 6. Claudeへの実施依頼案（オーナーが仕分けを採用した後）

1. §3の正本案と§4の差分の扱いを確定する。本書は分析依頼の成果であり、カード・KDocの改修を開始した記録ではない。
2. KDocへ寄せる7件から整理する。テスト名・assert・fixture・閾値・実行条件は変えず、説明とリンクだけを編集する。新規の長い事件記録をKDocへ移すのでなく、検査を変更するときに必要な理由へ圧縮する。
3. 教訓側16件は、一般則・当てる場面・検査の限界を残す。KDoc側は観測点・入力条件・非自明な実装理由だけにし、同じ事故の長文を参照へ替える。L14は索引内本文を編集し、カードを新設しない。
4. §4の差分を採用した判断に沿って直す。規約・ADR・owner文書の判断変更や、テスト保証の拡大は別の依頼として扱う。既存のテストを消して文書を合わせない。
5. 相対リンク・ID・参照先のテスト名が実在することを確認し、説明とテストのassertが対応するか読む。短縮した先が同じ説明への循環リンクだけになっていないことも見る。
6. 文書検査を含む `testDebugUnitTest lintDebug --offline --rerun-tasks` を通す。`androidTest` のコメントを編集する場合は `assembleDebugAndroidTest --offline` も通す。挙動が変わらない説明整理だけのために、実機全件の再実行を追加しない。
7. Codexへ差分レビューを渡す。23件すべての採用先、短縮前後の文書・コメント量、失われていない説明とリンクを報告し、オーナーの受理でPROC-1のこの残項を閉じる。

**受理条件:** 23件の重複する詳細の正本が決まり、もう片方は要約と参照で辿れ、検査の射程を広く見せないこと。
分析資料を作っただけでは課題を解消扱いにしない。削減量の数値目標やテスト削除を追加の完了条件にしない。

## 7. 比較した検査への入口

以下は今回の照合用。仕分けの正本は§3であり、この検査一覧を新しい恒久索引として維持しない。

- [ReviewFindingsLedgerTest](../../app/src/test/java/com/example/newproject/architecture/ReviewFindingsLedgerTest.kt)
- [AiAvailabilityContractTest](../../app/src/test/java/com/example/newproject/AiAvailabilityContractTest.kt)
- [SourceDocSyncTest](../../app/src/test/java/com/example/newproject/architecture/SourceDocSyncTest.kt)
- [CrystalSaveInterleavingTest](../../app/src/test/java/com/example/newproject/CrystalSaveInterleavingTest.kt)
- [ThreadingScanTest](../../app/src/test/java/com/example/newproject/architecture/ThreadingScanTest.kt)
- [SchemaVersionDocsTest](../../app/src/test/java/com/example/newproject/architecture/SchemaVersionDocsTest.kt)
- [WipIssueReferenceTest](../../app/src/test/java/com/example/newproject/architecture/WipIssueReferenceTest.kt)
- [InstrumentationTestShapeTest](../../app/src/test/java/com/example/newproject/architecture/InstrumentationTestShapeTest.kt)
- [AdrShapeTest](../../app/src/test/java/com/example/newproject/architecture/AdrShapeTest.kt)
- [AiAvailabilityUsageTest](../../app/src/test/java/com/example/newproject/architecture/AiAvailabilityUsageTest.kt)
- [DesignDocStateNameTest](../../app/src/test/java/com/example/newproject/architecture/DesignDocStateNameTest.kt)
- [DistillCandidateUnitCopyTest](../../app/src/test/java/com/example/newproject/architecture/DistillCandidateUnitCopyTest.kt)
- [PromptBudgetTest](../../app/src/test/java/com/example/newproject/ai/PromptBudgetTest.kt)
- [PromptIndentationTest](../../app/src/test/java/com/example/newproject/ai/PromptIndentationTest.kt)
- [NoteSessionCoordinatorTest](../../app/src/test/java/com/example/newproject/NoteSessionCoordinatorTest.kt)
- [ReadingTraceBackupControllerTest](../../app/src/test/java/com/example/newproject/ReadingTraceBackupControllerTest.kt)
- [ReadingTraceBackupJsonTest](../../app/src/test/java/com/example/newproject/ReadingTraceBackupJsonTest.kt)
- [InlineSyntaxTest](../../app/src/test/java/com/example/newproject/domain/markdown/InlineSyntaxTest.kt)
- [InlineMarkdownTest](../../app/src/test/java/com/example/newproject/InlineMarkdownTest.kt)
- [DistillSourceModelTest](../../app/src/test/java/com/example/newproject/domain/DistillSourceModelTest.kt)
- [DistillRangeHighlightTest](../../app/src/test/java/com/example/newproject/ui/DistillRangeHighlightTest.kt)
- [DistillRangeNoticeTest](../../app/src/test/java/com/example/newproject/ui/DistillRangeNoticeTest.kt)
- [BookletNavigationTest](../../app/src/androidTest/java/com/example/newproject/ui/BookletNavigationTest.kt)
- [BookletRouteContractTest](../../app/src/test/java/com/example/newproject/architecture/BookletRouteContractTest.kt)
- [BearingChannelTest](../../app/src/test/java/com/example/newproject/architecture/BearingChannelTest.kt)
- [BookletRestackTest](../../app/src/test/java/com/example/newproject/BookletRestackTest.kt)
- [BookletSheetPerspectiveTest](../../app/src/androidTest/java/com/example/newproject/ui/BookletSheetPerspectiveTest.kt)
- [PackageDependencyTest](../../app/src/test/java/com/example/newproject/architecture/PackageDependencyTest.kt)
- [AppColorContrastTest](../../app/src/test/java/com/example/newproject/ui/theme/AppColorContrastTest.kt)
- [PromptGenerationCoverageTest](../../app/src/test/java/com/example/newproject/architecture/PromptGenerationCoverageTest.kt)
- [DistillProtectedScanTest](../../app/src/test/java/com/example/newproject/architecture/DistillProtectedScanTest.kt)
