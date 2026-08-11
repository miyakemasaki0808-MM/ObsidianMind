# 開発知識

**このフォルダが答える問い: いま何が有効な判断で、何を繰り返してはいけないか。**

**索引の正本は [document_map.md](document_map.md)** — 文書の地図と運用ルール、
そして**パッケージから引く逆引き表**（§5）を持つ。コードを触る前はそこを引く。
本書はフォルダを開いたときの道標にすぎない。

| | 中身 |
|---|---|
| [features/](features/) | **ユーザーから見える機能**の仕様と実現方法。様式は [`_template.md`](features/_template.md) |
| [system/](system/) | **横断的な基盤**（責務・保証・不変条件・利用者） |
| [decisions/](decisions/) | **ADR。覆りにくい重大判断だけ**（文脈・決定・帰結、**30行以内＝`AdrShapeTest` が固定**） |
| [lessons.md](lessons.md) ＋ [lessons/](lessons/) | 同じ失敗を繰り返さないための索引とカード。**IDは永久の住所** |
| [change_history.md](change_history.md) | PR単位の変更履歴（新しい順・1文100字以内） |

> **種別が混ざると役割が曖昧になる。** 2026-08-11 まで全部が `design/` 1つにあり、
> **24本中11本が `## 判断N` 形式＝ADRの形**で機能仕様と同居していた。
> **ADRに設計の写しを置かない** — `decisions/` は「なぜ」の索引で、詳細の正本は `features/` か `system/`。
