# Vigilith AI

**過去の自分の思考と再会し、もう一段深める、オンデバイスAIの読書相手。**

Obsidian の Vault（`.md` 群）を SAF 経由で読み書きする Android アプリ。
ランダムに引いた過去ノートを読み、AIと考え、痕跡を残す。**AIはすべて端末内の Gemini Nano で動き、
ネットワーク権限を持たない**（→ [ADR-0002](docs/dev/decisions/ADR-0002-on-device-ai-only.md)）。

## 何ができるか

| | |
|---|---|
| **Rediscover** | 過去ノートを偶然引く。アプリの入口であり心臓 |
| **ReadingTrace** | 読書位置を自動記録し、同じノートを引いた時だけ「前回の読み方」を再会カードで出す |
| **要約・関連ノート** | 開いたノートを要約し、近い候補をAIが選ぶ |
| **蒸留 / クイズ / ひとこと** | 読んだ内容を、選ぶ・試す・書き留めるの3方向で深める |

機能の全体像は [docs/owner/README.md](docs/owner/README.md) にある。

## 技術

Kotlin / Jetpack Compose / ML Kit GenAI Prompt API（Gemini Nano）。minSdk 26。
Vaultへの書き込みは原則サイドカーに逃がし、**本文は書き換えない**
（例外は蒸留の太字化のみ → [ADR-0004](docs/dev/decisions/ADR-0004-do-not-rewrite-vault-body.md)）。

コード構成・規模の推移は [docs/owner/source_code_analysis.md](docs/owner/source_code_analysis.md)。

## ビルドと検証

```bash
./gradlew testDebugUnitTest lintDebug
```

`androidTest` を触った場合は `assembleDebugAndroidTest` も通す。
実機を通すテストの運用は [docs/dev/system/instrumentation_testing.md](docs/dev/system/instrumentation_testing.md)。

## ドキュメント

**入口は [docs/README.md](docs/README.md)。** 分類の軸は「その文書が答える問い」。

| | 答える問い |
|---|---|
| [docs/owner/](docs/owner/) | このアプリは何で、いまどうなっていて、どうやってここまで来たか |
| [docs/dev/](docs/dev/) | いま何が有効な判断で、何を繰り返してはいけないか |
| [docs/review/](docs/review/) | 外から見てどう評価されたか、その指摘はどうなったか |
| [docs/_wip/](docs/_wip/) | まだ決まっていないこと（リリース時に捨てる） |

開発規約は [CLAUDE.md](CLAUDE.md)。
