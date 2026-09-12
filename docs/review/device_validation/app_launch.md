# アプリ起動の実機検証ケース

## 正本

- OPの再生条件と、重複起動を畳む判断: [opening_animation](../../dev/features/opening_animation.md)
- 共通の準備と後処理: [Codex実機検証手順](README.md)

> **ここは入口である。** 落ちたら他の全ケースが無意味になるので、
> 簡易版でも最初に通す（→ [quick_check](quick_check.md) の選抜規則「入口」）。

## 適用条件

`MainActivity` の `onCreate`・マニフェストの `intent-filter` / `launchMode`・
OPの再生条件を触ったときに使う。**起動経路そのものを触るので、机上では代えが利かない** —
`ActivityRecreationTest` が覆うのは同一プロセス内の再生成までで、
**タスクの基点Intentが食い違う経路は覆っていない。**

## 検証前

- **Vaultの状態に依存しない。** 常設検証用Vaultのままでよく、一時Vaultも fixture も要らない。
- 開始前に `MainActivity` が1枚だけであることを数えておく。

  ```text
  adb -s <serial> shell dumpsys activity activities | grep -cE 'ActivityRecord\{[^}]*MainActivity'
  ```

- **`LAUNCH-01` の前に、アプリを完全に終了させる**（タスクを一度捨てる）。
  積み残しがあると開始時点の枚数が1にならない。

  ```text
  adb -s <serial> shell am force-stop com.vigilith.ai
  ```

## ケース

| ID | 入力・操作 | 期待 |
|---|---|---|
| `LAUNCH-01` | 素のコンポーネント指定（`am start -n`）で起動したあと、ランチャーのアイコンを3回タップする | `MainActivity` が**1枚のまま**。タップのたびにOPが走らない |
| `LAUNCH-02` | `LAUNCH-01` の状態から戻るボタンを押す | **1回でアプリを抜ける**。同じ画面が1枚ずつ剥がれない |
| `LAUNCH-03` | アプリを終了させ、**ランチャーのアイコン**から起動する | OPが1回再生され、ノートタブへ着地する。`MainActivity` は1枚 |
| `LAUNCH-04` | `LAUNCH-03` の状態で別アプリへ移り、ランチャーのアイコンをもう一度タップする | **既存タスクへ復帰し、OPは出ない**。表示中の画面がそのまま戻る |
| `LAUNCH-05` | ノートを表示した状態で端末を回転させ、Foldがあれば開閉する | OPが出ない。`ActivityRecreationTest` と同じ結論になる |

**`LAUNCH-01` と `LAUNCH-03` は起動のしかたが違う。** 素のコンポーネント指定はガードを実際に通し、
ランチャー起動はガードを通らない経路を見る。**どちらか一方では、畳みすぎと畳み漏れのどちらかが残る。**

## 後処理

**共通手順の「実機検証後」をそのまま行う。** ケース別の一時物を作らないので、削除する対象は無い。
`am force-stop` を使ったので、**終了地点は常設検証用Vaultを表示した状態まで戻してから**報告する。

## 記録

| 項目 | 内容 |
|---|---|
| 通したID | `LAUNCH-01`〜`LAUNCH-05` のどれを通したか |
| 枚数 | `LAUNCH-01` の**タップ3回それぞれの後**の `MainActivity` の枚数（3回とも書く） |
| 判定 | 合否。**`LAUNCH-01` か `LAUNCH-02` が不合格なら、残りのケースを通さずに打ち切る** |
