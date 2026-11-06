---
name: fetch-branch
description: オーナーが GitHub 側で作ったブランチへ移る手順。「フェッチして」「新しいブランチに切り替えて」と言われたときに使う。main を ff-only で更新し、マージ済みの旧ブランチをローカルから消して、新しいブランチを checkout する。
---

# ブランチの取り込み

ブランチはオーナーが GitHub 側で作り、PR のマージもオーナーが行う。Claude はブランチを作らない
（分担の正本は CLAUDE.md の「作業の進め方」）。

1. `git status --short` で未コミットの変更が無いことを確かめる。あれば止めて、オーナーへ何が残っているかを伝える
2. `git fetch --prune origin`
3. `git switch main` のあと `git pull --ff-only`。早送りできずに失敗したら止める — main に手元だけのコミットがある
4. マージ済みの旧ブランチを消す。`git branch --merged main` に出たブランチだけを `git branch -d` で消す。
   `-D` は使わない（未マージのコミットを黙って捨てる）
5. `git switch <新しいブランチ名>`。origin にあれば追跡ブランチができる。
   **名前はオーナーが作ったとおりに使う。** 綴りの誤りに見えても直さない
6. `git log --oneline -3` と `git status --short` の結果をオーナーへ返す
