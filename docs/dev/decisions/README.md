# 重大判断（ADR）

**ここに置くのは、覆りにくく、後から「なぜ？」となる横断判断だけ。**
機能追加のたびにADRを作らない。**判断の総数が増えていくのは `features/` `system/` 側であって、ここではない。**

## ADRに設計の写しを置かない

各ADRは**文脈・決定・帰結だけ**を書き、**詳細の正本は必ず `features/` か `system/` 側**へリンクする。

**上限は30行**（`wc -l` の実ファイル行数）で、`AdrShapeTest` がCIで固定する。
上限の意図は短さそのものではなく**ADRに設計の写しを置かせないこと**で、
詳細を書き始めると必ず溢れるので、溢れた時点で「正本は別にあるはず」と気づける。

> **旧規則は「20行以内」で、それを書いた同じコミットで4本とも破っていた。**
> 空行込みか本文だけかを決めていなかったのが直接の原因（本文なら18〜20行、
> ファイル行数なら26〜28行）。**数え方を `wc -l` に固定し、検査へ載せた** —
> このリポジトリで規則が守られたのは検査に載せたときだけである（→ [lessons](../lessons.md) L29）。

**理由: 正本が2つに割れると、どちらかが必ず古くなる。** このリポジトリでは実際に、
`document_map` が持っていた「実装済み／未着手」の列が各設計書の `**状態:**` 行と二重管理になり、
画像表示が実装・実機確認まで終わっているのに索引側だけ「実装未着手」で残っていた。
**ADRは「なぜ」の索引であって、設計の複製ではない。**

## 一覧

| ID | 決定 | 詳細の正本 |
|---|---|---|
| [ADR-0001](ADR-0001-single-viewmodel-controllers.md) | 単一ViewModel＋機能Controller方式を採る | [system/architecture.md](../system/architecture.md) 判断1 |
| [ADR-0002](ADR-0002-on-device-ai-only.md) | AIはオンデバイスのみ。ネットワーク権限を持たない | **本ADRが正本**（他に記録が無い） |
| [ADR-0003](ADR-0003-opaque-saf-references.md) | SAF参照を不透明化し `model` を葉に保つ | [system/saf_boundary_gateway.md](../system/saf_boundary_gateway.md) |
| [ADR-0004](ADR-0004-do-not-rewrite-vault-body.md) | Vault本文を書き換えない（例外は蒸留の太字化のみ） | [features/reflect_distill.md](../features/reflect_distill.md) |
| [ADR-0005](ADR-0005-bearing-channel-allocation.md) | 佇まいのチャネルは1つの意味だけに割り当てる | [system/bearing_channels.md](../system/bearing_channels.md) |

## 様式

```
# ADR-XXXX: 決定の一文

**状態:** Accepted / Superseded by ADR-YYYY
**決定日:** YYYY-MM-DD
**詳細の正本:** <features/ か system/ の文書へのリンク。無ければ「本ADR」>

## 文脈
何が問題で、何を選ばねばならなかったか。

## 決定
何を選んだか。**採らなかった案と、その理由。**

## 帰結
この決定が今も課している制約。**破ろうとしたときに何が壊れるか。**
```
