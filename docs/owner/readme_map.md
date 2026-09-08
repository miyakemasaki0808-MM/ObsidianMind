# README の地図

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）
**作成:** 2026-09-08

**位置づけ:** **11本ある `README.md` がそれぞれ何をしているか**を一望する1枚。
「どこに何が書いてあるか」は [document_inventory](document_inventory.md) が持ち、
**本書は入口そのものの構造だけ**を扱う。`owner/` の他文書と同じく検査に載せない。

---

## 1. なぜ11本もあるのか

**フォルダを1つ作ったら、その入口を1本置く**という運用になっているためである。
`README.md` は多くのツールとGitHubがフォルダを開いたとき最初に見せるので、
**「このフォルダは何か」を置く場所として決め打ちしてある。**

ただし**11本すべてが同じ役割ではない。** 3種類に分かれる。

| 種類 | 本数 | 何をしているか |
|---|---:|---|
| **A. 入口・索引** | 8 | そのフォルダの中身へ案内する。内容は持たない |
| **B. それ自体が正本** | 2 | 入口ではなく**運用の規則そのもの**。検査が中身を見ている |
| **C. 未追跡の作業ファイル** | 1 | 一時的な引き継ぎメモ。リポジトリには入らない |

## 2. 全11本

**凡例:** 追跡 ✅=git管理下／❌=`.gitignore` ／ 検査 = JVMテストが中身を見ているか

### A. 入口・索引（8本）

| 場所 | 見出し | 行数 | 何を案内するか | 追跡 | 検査 |
|---|---|---:|---|:--:|---|
| [`README.md`](../../README.md) | Vigilith AI | 48 | リポジトリの入口。何ができるアプリか＋`docs/` の4フォルダ | ✅ | — |
| [`docs/README.md`](../README.md) | ドキュメントの入口 | 37 | **分類の軸は「その文書が答える問い」**。4フォルダへ振り分ける | ✅ | — |
| [`docs/dev/README.md`](../dev/README.md) | 開発知識 | 19 | features / system / decisions / lessons への道標 | ✅ | — |
| [`docs/dev/features/README.md`](../dev/features/README.md) | 機能仕様 | 15 | 様式（`_template.md`）とヘッダ5行の決まり | ✅ | — |
| [`docs/dev/system/README.md`](../dev/system/README.md) | 基盤設計 | 13 | 「全機能に効くもの」だけを置く基準 | ✅ | — |
| [`docs/dev/decisions/README.md`](../dev/decisions/README.md) | 重大判断（ADR） | 51 | **ADRの様式の正本**（30行以内・設計の写しを置かない） | ✅ | 間接 |
| [`docs/owner/README.md`](README.md) | アプリ俯瞰 | 81 | `owner/` の索引＋**アプリの機能一覧そのもの** | ✅ | — |
| [`docs/owner/journal/README.md`](journal/README.md) | 開発日誌 | 52 | 月別索引＋「現在状態の正本ではない」警告 | ✅ | — |

**`dev/README.md` は自分で「道標にすぎない」と書いている。** 索引の正本は
[document_map.md](../dev/document_map.md) で、パッケージから引く逆引き表（§5）はそちらが持つ。
**同じフォルダに入口が2つある形**だが、片方が「正本はあちら」と明示しているので迷わない。

`decisions/README.md` の「間接」は、`AdrShapeTest` が
**エラーメッセージで「様式は `decisions/README.md`」と案内し、README 自身は ADR の形の検査から除外している**
という関係を指す。中身は検査していない。

### B. それ自体が正本（2本）

| 場所 | 見出し | 行数 | 何を持つか | 追跡 | 検査 |
|---|---|---:|---|:--:|---|
| [`docs/review/README.md`](../review/README.md) | レビュー | 140 | レビュー運用の正本＋**レビュー一覧**（本文が消えても結果が追える） | ✅ | **`ReviewFindingsLedgerTest`** |
| [`docs/review/device_validation/README.md`](../review/device_validation/README.md) | Codex実機検証手順 | 133 | **権限範囲・準備・検証中・後処理・記録**。実機作業の唯一の手順書 | ✅ | **`DeviceValidationDocsTest`** |

**この2本だけは「開いたら別の場所へ行く」文書ではない。** 読んでそのとおりに動くための規則である。

だから検査も中身を見ている。

- `ReviewFindingsLedgerTest` — レビュー本文が**一覧に載っている**こと（載せずに本文を消すと結果が失われる）
- `DeviceValidationDocsTest` — `## 権限範囲`・`## 実機検証前`・`## 実機検証中`・`## 実機検証後`・`## 記録` の
  **5つの節が存在する**こと。さらに `CLAUDE.md` からこの README へ到達できることも見ている

> **スクリーンショットの扱いもここにある。** 実機検証で何をどの道具で見るかは
> `device_validation/README.md` の「実機検証中」節が持ち、
> **「画面上の文字はUI階層、見た目はスクリーンショット、永続化は端末上の実ファイル。どれか1つで他を代用しない」**
> が中心の1行である。簡易版の例外は [quick_check.md](../review/device_validation/quick_check.md) 側。

### C. 未追跡の作業ファイル（1本）

| 場所 | 見出し | 行数 | 追跡 |
|---|---|---:|:--:|
| `docs/review/device_validation/evidence/book4-weave-20260907/README.md` | BOOK-4 実機検証 — 中断からの引き継ぎ | 156 | ❌ |

`evidence/` は `.gitignore` 済み（端末を特定する値や検証中のローカルパスが入るため）。
**フォルダを開いたとき最初に読まれるよう README という名前にしてある**が、リポジトリには入らない。
**この種類は今後も一時的に増減する。**

## 3. 入口の連なり

```
README.md（リポジトリ）
└── docs/README.md（分類の軸＝「答える問い」）
    ├── owner/README.md ──── journal/README.md
    ├── dev/README.md ────── features/ ・ system/ ・ decisions/ の各README
    │   └── （索引の正本は document_map.md）
    ├── review/README.md ─── device_validation/README.md ─── evidence/…/README.md
    └── _wip/（**READMEを持たない**）
```

**`_wip/` だけ README が無い。** 3本（`current_issues` `roadmap` `feature_ideas`）とも役割が明確で、
`CLAUDE.md` に「どれを見るか」の表が直接置いてあるため。**入口を1本増やす価値が無いと判断されている。**

## 4. 気づいた点

### `owner/README.md` だけ、名前と中身がずれている

他の入口が「案内」に徹しているのに対し、**これは索引でありながらアプリの機能一覧そのもの**（81行）を持つ。
見出しも「アプリ俯瞰」で、README という名前と一致していない。

**実害はまだ出ていない**が、`owner/` は 2026-09-08 に 6本 → 9本へ増えた。
入口としての案内が、機能一覧の**上に 20行ほど**乗っている状態である。
分けるなら「`README.md`（入口）」と「`app_overview.md`（アプリ俯瞰）」になるが、
**分けると今度は「READMEを開いても機能が分からない」**ので、どちらが良いかは読み方次第。

### 検査があるのは `review/` 配下の2本だけ

`owner/` は方針として検査に載せない（古びてよい文書がCIで無関係な変更を止めるため）。
`dev/` 側の4本は**索引であって規則ではない**ので、載せる対象が無い。
**規則を持つ README だけが検査を持っている**という形になっていて、これは意図と一致している。
