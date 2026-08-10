# 基盤設計（system/）

**答える問い: 全機能に効く責務・保証・不変条件・利用者は何か。**
**書かないこと: ユーザーフロー**（→ [../features/](../features/)）。

ここに置くのは、**特定の機能ではなくコードベース全体へ効くもの**。
1つ変えると複数の機能が同時に壊れうるので、**触る前に必ず読む**。

- 全変更に効く横断規約は [architecture.md](architecture.md)（CLAUDE.md が常時読み込む）
- 見た目に触る前は [ui_design_principles.md](ui_design_principles.md)
- パッケージから引くときは [document_map.md](../document_map.md) §5 の逆引き表

一覧は [document_map.md](../document_map.md) §2 が持つ（**二重管理にしないため、ここには並べない**）。
