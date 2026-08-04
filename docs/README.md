# ドキュメントの入口

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）

**分類の軸は「読み手」ではなく「文書の役割」。** 担当するAIが変わっても意味が変わらないようにするため。

| フォルダ | 役割 | 代表 |
|---|---|---|
| [owner/](owner/) | **プロダクトの説明** — 何ができるか・どこから来たか | [README](owner/README.md) |
| [dev/](dev/) | **作るための知識** — 設計判断・現況解析・教訓・変更履歴 | [document_map](dev/document_map.md)（文書の地図と運用ルール） |
| [review/](review/) | **評価とその追跡** — 外部レビュー・様式・指摘の受付簿 | [README](review/README.md) |
| [_wip/](_wip/) | **未確定** — 課題台帳・ロードマップ・機能アイデア | [current_issues](_wip/current_issues.md) |

**`_wip/` だけは役割ではなく寿命で切ってある。** リリース時に3ファイルまとめて捨てる置き場なので、
中身がどの役割であってもここに集める。恒久文書（`owner/` `dev/` `review/`）から `_wip/` は参照しない。
