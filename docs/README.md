# ドキュメントの入口

**プロジェクト:** Vigilith AI（旧 Obsidian Mind）

**分類の軸は「その文書が答える問い」。** 読み手で分けると、同じ内容が複数の場所へ増えてしまう。

| フォルダ | 答える問い | 代表 |
|---|---|---|
| [owner/](owner/) | **このアプリは何で、いまどうなっていて、どうやってここまで来たか** | [README](owner/README.md)・[技術俯瞰](owner/source_code_analysis.md)・[開発日誌](owner/journal/) |
| [dev/](dev/) | **いま何が有効な判断で、何を繰り返してはいけないか** | [document_map](dev/document_map.md)（文書の地図と運用ルール） |
| [review/](review/) | **外から見てどう評価されたか、その指摘はどうなったか** | [README](review/README.md) |
| [_wip/](_wip/) | **まだ決まっていないこと** | [current_issues](_wip/current_issues.md) |

**`_wip/` だけは問いではなく寿命で切ってある。** リリース時に中身をまとめて捨てる置き場なので、
どの役割の文書であってもここに集める（**ファイル数は増減する**ので、数を固定して書かない）。

---

## どこから読むか

| したいこと | 読む順 |
|---|---|
| アプリの全体像を知る | [owner/README.md](owner/README.md) |
| コード構成・技術を知る | [owner/source_code_analysis.md](owner/source_code_analysis.md) |
| 開発の経緯を読み物として追う | [owner/journal/](owner/journal/) |
| コードを触る前に背景を知る | [dev/document_map.md](dev/document_map.md) §5 の逆引き表 → 該当する `dev/design/` |
| 次に何を作るか決める | [_wip/roadmap.md](_wip/roadmap.md) → [_wip/current_issues.md](_wip/current_issues.md) |

## owner/ と dev/ の線引き

**「コードを読めば再現できるか」で引く。**

- **再現できる**（現況の解析・規模・データフロー）→ `owner/`。
  Claudeはコードを直接読むので、作業知識としては参照しない
- **再現できない**（なぜその判断にしたか・何を試して駄目だったか・繰り返してはいけない失敗）→ `dev/`。
  コードには「採らなかった案」も「その理由」も書かれていない
