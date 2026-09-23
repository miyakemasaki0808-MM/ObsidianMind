# README の地図

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-08 / **更新:** 2026-09-20

**位置づけ:** 16本ある `README.md` がそれぞれ何をしているかを一望する1枚。
「どこに何が書いてあるか」は [document_inventory](03_document_inventory.md) が持ち、
本書は入口そのものの構造だけを扱う。`owner/` の他文書と同じく検査に載せない。

---

## 1. なぜ16本もあるのか

**フォルダを1つ作ったら、その入口を1本置く**という運用になっているためである。
`README.md` は多くのツールと GitHub がフォルダを開いたとき最初に見せるので、
「このフォルダは何か」を置く場所として決め打ちしてある。

ただし16本すべてが同じ役割ではない。4種類に分かれる。

| 種類 | 本数 | 何をしているか |
|---|---:|---|
| **A. 入口・索引** | 8 | そのフォルダの中身へ案内する。内容は持たない |
| **B. それ自体が正本** | 2 | 入口ではなく運用の規則そのもの。検査が中身を見ている |
| **C. テスト入力の説明** | 3 | 固定コーパスと実機の観測値が何であるかを、隣に置いて読ませる。**2026-09-13 に増えた種類** |
| **D. 追跡しない作業ファイル** | 3 | 一時的な引き継ぎメモと、git 管理外の報告書の入口 |

前回の目録は11本だったので5本増えた。C の3本と、D の2本である。

## 2. 全16本

**凡例:** 追跡 ✅＝git 管理下、❌＝`.gitignore`。検査＝JVMテストが中身を見ているか。

### A. 入口・索引。8本

| 場所 | 見出し | 行数 | 何を案内するか | 追跡 | 検査 |
|---|---|---:|---|:--:|---|
| [`README.md`](../../README.md) | Vigilith AI | 48 | リポジトリの入口。何ができるアプリか＋`docs/` の4フォルダ | ✅ | — |
| [`docs/README.md`](../README.md) | ドキュメントの入口 | 37 | 分類の軸は「その文書が答える問い」。4フォルダへ振り分ける | ✅ | — |
| [`docs/dev/README.md`](../dev/README.md) | 開発知識 | 23 | features・system・decisions・lessons への道標 | ✅ | — |
| [`docs/dev/features/README.md`](../dev/features/README.md) | 機能仕様 | 15 | 様式とヘッダ5行の決まり | ✅ | — |
| [`docs/dev/system/README.md`](../dev/system/README.md) | 基盤設計 | 13 | 「全機能に効くもの」だけを置く基準 | ✅ | — |
| [`docs/dev/decisions/README.md`](../dev/decisions/README.md) | 重大判断 | 51 | ADRの様式の正本。30行以内・設計の写しを置かない | ✅ | 間接 |
| [`docs/owner/README.md`](README.md) | アプリ俯瞰 | 130 | `owner/` の索引（番号＝読む順）＋アプリの機能一覧そのもの | ✅ | — |
| [`docs/owner/journal/README.md`](journal/README.md) | 開発日誌 | 52 | 月別索引＋「現在状態の正本ではない」警告 | ✅ | — |

`dev/README.md` は自分で「道標にすぎない」と書いている。索引の正本は
[document_map.md](../dev/document_map.md) で、パッケージから引く逆引き表はそちらが持つ。
同じフォルダに入口が2つある形だが、片方が「正本はあちら」と明示しているので迷わない。

`decisions/README.md` の「間接」は、`AdrShapeTest` がエラーメッセージで「様式は `decisions/README.md`」と案内し、
README 自身は ADR の形の検査から除外している関係を指す。中身は検査していない。

### B. それ自体が正本。2本

| 場所 | 見出し | 行数 | 何を持つか | 追跡 | 検査 |
|---|---|---:|---|:--:|---|
| [`docs/review/README.md`](../review/README.md) | レビュー | 207 | レビュー運用の正本＋レビュー一覧136行。本文が消えても結果が追える | ✅ | **`ReviewFindingsLedgerTest`** |
| [`docs/review/device_validation/README.md`](../review/device_validation/README.md) | Codex実機検証手順 | 282 | 権限範囲・準備・検証中・後処理・記録・**誰が行うか**。実機作業の唯一の手順書 | ✅ | **`DeviceValidationDocsTest`** |

この2本だけは「開いたら別の場所へ行く」文書ではない。読んでそのとおりに動くための規則である。
だから検査も中身を見ている。

- `ReviewFindingsLedgerTest` — レビュー本文が一覧に載っていること。載せずに本文を消すと結果が失われる
- `DeviceValidationDocsTest` — 権限範囲・実機検証前・実機検証中・実機検証後・記録の5節が存在すること。
  `CLAUDE.md` からこの README へ到達できることも見ている

分担そのものは `CLAUDE.md` の「作業の進め方」が正本で、`review/README.md` は Codex の手順だけを持つ。
2026-09-12 にそう分けた。2箇所に書くと必ず片方が古くなる。
**2026-09-19 に、実機検証の区間そのものを割り直した** — 組み立てと片付けはこちら、ケースの判定は Codex。
`device_validation/README.md` が185行から282行へ伸びたのは、その割りを「誰が行うか」の節として書いたためである。

> スクリーンショットの扱いもここにある。実機検証で何をどの道具で見るかは「実機検証中」節が持ち、
> 「画面上の文字はUI階層、見た目はスクリーンショット、永続化は端末上の実ファイル。どれか1つで他を代用しない」
> が中心の1行である。簡易版の例外は [quick_check.md](../review/device_validation/quick_check.md) 側。

### C. テスト入力の説明。3本

| 場所 | 見出し | 行数 | 何を説明するか | 追跡 |
|---|---|---:|---|:--:|
| `app/src/androidTest/assets/ai_corpus/README.md` | 固定コーパス | 37 | 要約の品質を測る架空Vault9本が、それぞれどの壊れ方を再現するか。ファイル名をASCIIにする理由 | ✅ |
| `…/ai_corpus/references/nano/README.md` | 実機の Nano が返した要約 | 96 | 人が書いた参照と混ぜない理由。生成の条件。文ごとのラベルの付け方 | ✅ |
| `…/references/nano/observations/2026-09-13/README.md` | 分割実行の観測値 | 11 | 27組の応答が未ラベルの観測資料であって較正用データではないこと | ✅ |

**入口ではなく注意書きである。** 隣にあるファイルをどう読むか、何と混ぜてはいけないかを、
そのフォルダを開いた人が最初に読むように置いてある。正本は
[ai_quality_measurement](../dev/system/ai_quality_measurement.md) で、3本ともそこへリンクする。
README という名前を使ったのは、フォルダを開いたとき最初に見える位置に置くためで、A の8本と動機は同じである。

### D. 追跡しない作業ファイル。3本

| 場所 | 見出し | 行数 | 何か |
|---|---|---:|---|
| `docs/owner/Fable5.1_report/README.md` | Fable 5.1 評価報告書 | 81 | 2026-09-11 の総評の入口。8軸の点数と最重要5点。報告書は更新しないと決めてある |
| `docs/review/device_validation/evidence/book4-weave-20260907/README.md` | 編む冊子の実機検証 | 156 | 利用枠切れで中断した回の引き継ぎメモ |
| `docs/review/device_validation/evidence/scale-review-20260910/README.md` | 全体レビューのJVM再現証拠 | 18 | レビューの再現手順 |

`evidence/` と `Fable5.1_report/` は `.gitignore` 済み。端末を特定する値や検証中のローカルパスが入るため、
あるいはオーナー判断で特別枠としたためである。フォルダを開いたとき最初に読まれるよう README という名前にしてあるが、
リポジトリには入らない。**この種類は今後も一時的に増減する。**

## 3. 入口の連なり

```
README.md（リポジトリ）
└── docs/README.md（分類の軸＝「答える問い」）
    ├── owner/README.md ──── journal/README.md ・ Fable5.1_report/README.md（管理外）
    ├── dev/README.md ────── features/ ・ system/ ・ decisions/ の各README
    │   └── （索引の正本は document_map.md）
    ├── review/README.md ─── device_validation/README.md ─── evidence/…/README.md（管理外）
    └── _wip/（READMEを持たない）

app/src/androidTest/assets/ai_corpus/README.md ─── references/nano/README.md ─── observations/…/README.md
```

**`_wip/` だけ README が無い。** 4本とも役割が明確で、`CLAUDE.md` に「どれを見るか」の表が直接置いてあるため。
入口を1本増やす価値が無いと判断されている。

**コーパスの3本は `docs/` の連なりから外れている。** テスト入力の隣に置く注意書きなので、
`docs/` から辿る入口ではなく、正本の設計書からリンクで届く。

## 4. 気づいた点

### `owner/README.md` だけ、名前と中身がずれている

他の入口が案内に徹しているのに対し、これは索引でありながらアプリの機能一覧そのもの、64行を持つ。
見出しも「アプリ俯瞰」で、README という名前と一致していない。

実害はまだ出ていないが、`owner/` は 2026-09-08 に6本から9本へ、09-14 に14本へ、09-17 に16本へ増えた。
入口としての案内が、機能一覧の上に30行ほど乗っている状態である。
分けるなら「`README.md`（入口）」と「`app_overview.md`（アプリ俯瞰）」になるが、
分けると今度は「README を開いても機能が分からない」ので、どちらが良いかは読み方次第。
**保留のまま。** 判断はオーナーに預ける。

### 検査があるのは `review/` 配下の2本だけ

`owner/` は方針として検査に載せない。`dev/` 側の4本は索引であって規則ではないので、載せる対象が無い。
C の3本は正本の設計書側が検査を持つ。**規則を持つ README だけが検査を持っている**という形になっていて、これは意図と一致している。

### README が「入口」以外の意味で増え始めた

C の3本は入口ではなく注意書きである。数が増えれば「README＝フォルダの入口」という前提が崩れるが、
いまは3本とも1つの機能に閉じているので問題にしない。同じ形が別の機能で2件目に現れたら、
名前を `NOTES.md` のように分けるかを考える。
