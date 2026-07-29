# R8 / ProGuard ルール。
#
# 現在 release は `isMinifyEnabled = false` なのでこのファイルは適用されない。
# 有効化する前に、少なくとも次の2つを実機の release ビルドで確認すること。
#
#   1. ML Kit GenAI（Gemini Nano）— リフレクションで解決される部分が縮小で消えると、
#      要約・補記・クイズ・関連ノート・蒸留がすべて実機でだけ落ちる。
#   2. Compose — Composable のメタデータと、`remember` 周辺のラムダ。
#
# どちらもJVMユニットテストでは検出できない（テストは縮小前のクラスを見る）。
