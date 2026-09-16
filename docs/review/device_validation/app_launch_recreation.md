# 回転・Fold開閉の実機検証ケース（`LAUNCH-05`）

## 正本

- OPの再生条件と、重複起動を畳む判断: [opening_animation](../../dev/features/opening_animation.md) §5・判断7
- 起動ケースの全体と `LAUNCH-01`〜`04`: [app_launch](app_launch.md)
- 共通の準備と後処理: [Codex実機検証手順](README.md)

> **このファイルは [app_launch](app_launch.md) の `LAUNCH-05` を、Codexがそのまま実行できる粒度へ分けたもの。**
> `LAUNCH-05a`〜`LAUNCH-05e` の**全件が合格したときだけ** `LAUNCH-05` を合格と記録する。

## 適用条件

- [app_launch](app_launch.md) の適用条件（`MainActivity` の `onCreate`・マニフェストの `intent-filter` / `launchMode`・
  OPの再生条件を触った）に当たる**通し版**で通す。
- **課題台帳の APP-1 を閉じるとき**は、変更の有無に関わらず通す。

### 何を見るか

回転とFold開閉では `MainActivity` が作り直され、`onCreate` に保存Bundle（非null）が渡る。
このとき次の3つが揃っていることを実機で確かめる。**どれか1つでも崩れたら不合格である。**

1. **OPが再生されない**（OPは `savedInstanceState == null` のときだけ出る）
2. **重複起動のガードが誤って畳まない**（`MainActivity` が1枚のまま残り、終了していない）
3. **本当に作り直された**（作り直されていなければ、1と2は何も確かめていない）

`ActivityRecreationTest` が覆うのは `scenario.recreate()` による同一プロセス内の再生成で、
**実端末の回転・Fold開閉の経路は通っていない**。ここで見るのはその差分である。

### 観測の要点 — OPは2秒で終わるので、そのままでは見逃す

`uiautomator dump` は1回に数秒かかるので、**2秒のOPが出ても終わった後の画面しか取れない**。
「OPが写っていなかった」は、そのままでは合格の証拠にならない。

そこで**アニメーションの再生倍率を一時的に10倍へ上げ、OPを約20秒に伸ばす**。
OPは再生倍率に追従する（→ [opening_animation](../../dev/features/opening_animation.md) §5「進行の駆動」）。
さらに `LAUNCH-05a` で、**この観測方法でOPを実際に捉えられることを先に確かめる**（陽性対照）。
陽性対照が不合格なら、後続のケースは判定しない。

| 画面 | UI階層に出る文字 |
|---|---|
| OP再生中 | `Vigilith AI`（OP画面にしか無い）。`Rediscover` は**出ない**（OP中は本体を組み立てない） |
| 本体（ノートタブ） | `Rediscover`（Vaultの選択に依らず出る）。`Vigilith AI` は**出ない** |

目印は `ActivityRecreationTest` と同じものを使う。

## 検証前

### 権限範囲（このケースに限る）

共通手順は端末設定の変更を実機検証の依頼に含めていない。**このケースに限り、次の一時変更を依頼に含める**
（[共通手順](README.md) の権限範囲にも同じ行がある）。**変更前の値を記録し、後処理で必ず戻す。**

| 変更 | コマンド | 戻し方 |
|---|---|---|
| アニメーションの再生倍率 | `settings put global animator_duration_scale 10` | 記録した値を `put`。記録が `null` なら `settings delete global animator_duration_scale` |
| 自動回転の停止 | `settings put system accelerometer_rotation 0` | 記録した値を `put` |
| 画面の向きの固定 | `settings put system user_rotation <0/1>` | 記録した値を `put` |
| 折りたたみ状態の一時上書き | `cmd device_state state <id>` | `cmd device_state state reset` |

**上の表以外の端末設定は変えない。** 特に「カバー画面でアプリを続行」の設定は変えない
（`LAUNCH-05d` で続行されなかったら、設定を変えずに**未確認**と記録する）。

### 準備

以下、`adb` にはすべて `-s <serial>` を付ける（シリアルは共通手順の準備4で取得し、文書へ残さない）。

1. **共通手順の「実機検証前」1〜6を行う。** 一時Vaultもfixtureも要らない。常設検証用Vaultのままでよい。
2. **端末の前提を確かめる。**
   - 画面が点灯し、ロックが解除されている。**ロック画面ならユーザーへ解除を頼む**（PINは入力しない）。
   - 端末が**物理的に開いている**。`cmd device_state print-state` の現在状態と、
     `cmd device_state print-states` の一覧を記録する。一覧から**閉じた状態**（名前が `CLOSED` のもの）と
     **開いた状態**（名前が `OPENED` のもの）のIDを控える。現在状態が開いた状態でなければ、ユーザーへ開くよう頼む。
   - Foldでない端末では `LAUNCH-05d`・`05e` を**対象外**と記録し、回転の3件だけを通す。
3. **変更前の値を記録する。**

   ```text
   adb -s <serial> shell settings get global animator_duration_scale
   adb -s <serial> shell settings get system accelerometer_rotation
   adb -s <serial> shell settings get system user_rotation
   ```

4. **観測に使うコマンドを確かめる。** 以降のケースは、次の3つだけで判定する。

   ```text
   # 画面の文字（Vigilith AI / Rediscover の有無と、画面の向き）
   adb -s <serial> shell uiautomator dump /sdcard/ui.xml >/dev/null && \
     adb -s <serial> shell cat /sdcard/ui.xml | grep -oE 'rotation="[0-3]"|text="(Vigilith AI|Rediscover)"' | sort -u

   # MainActivity の枚数
   adb -s <serial> shell dumpsys activity activities | grep -cE 'ActivityRecord\{[^}]*MainActivity'

   # 作り直されたか（Local Activity の値が作り直しのたびに変わる）と、終了していないか
   adb -s <serial> shell dumpsys activity top | grep -A2 'ACTIVITY com.vigilith.ai/'
   ```

   `dumpsys activity top` の `Local Activity <値>` は**Activityの実体ごとに変わる値**で、
   `ActivityRecord` の値は作り直しても変わらない。**`Local Activity` の行が出ない場合は、作り直しを
   確かめる手段が無いので、`LAUNCH-05b` 以降を未確認として打ち切る。**

## ケース

| ID | 入力・操作 | 期待 |
|---|---|---|
| `LAUNCH-05a` | **陽性対照。** 再生倍率を10にしてから `am force-stop`、素のコンポーネント指定で起動し、**起動から5秒以内**に画面の文字を取る。その後5秒おきに取り直し、`Rediscover` が出るまで待つ（上限60秒） | 起動直後の取得で `Vigilith AI` が出て `Rediscover` が出ない。その後 `Rediscover` が出て `Vigilith AI` が消える。**消えるまでに10秒以上かかる**（倍率が効いている） |
| `LAUNCH-05b` | `LAUNCH-05a` の本体表示の状態で、作り直し前の `Local Activity` と枚数を記録する。自動回転を止め、`user_rotation` を**現在と違う向き**（0なら1、それ以外なら0）にする。**変えた直後から15秒のあいだ**、5秒おきに画面の文字を3回取る | 画面の `rotation` が変わる。3回とも `Vigilith AI` が出ず、**少なくとも1回は `Rediscover` が出る**。`Local Activity` が変わり、`MainActivity` は1枚、`mFinished=false` |
| `LAUNCH-05c` | `user_rotation` を記録した元の値へ戻し、`LAUNCH-05b` と同じ取り方をする | `LAUNCH-05b` と同じ |
| `LAUNCH-05d` | 折りたたみ状態を**閉じた状態**へ上書きし、`LAUNCH-05b` と同じ取り方をする | `LAUNCH-05b` と同じ（`rotation` の変化は問わない）。加えて `dumpsys activity activities` の `topResumedActivity` が `com.vigilith.ai` |
| `LAUNCH-05e` | 折りたたみ状態を**開いた状態**へ上書きし、`LAUNCH-05b` と同じ取り方をする | `LAUNCH-05d` と同じ |

### 判定の注意

- **「15秒のあいだに少なくとも1回は `Rediscover`」が要点である。** OPが再生されていれば、倍率10では
  約20秒のあいだ本体が組み立てられないので、15秒以内に `Rediscover` は出ない。
  作り直しの途中を取って両方とも出ないことはあるので、**1回目だけで判定しない。**
- **`Local Activity` が変わらなかったら、合格ではなく未確認と記録する。** 作り直されていない
  （向きの変化を Activity が受け流した）なら、OPが出ないのは当然で何も確かめていない。
- **`LAUNCH-05d` でアプリがカバー画面へ続行されなかったら**（`topResumedActivity` が別のアプリ・ランチャー）、
  設定を変えずに `LAUNCH-05d`・`05e` を**未確認**と記録する。不合格とは書かない。
- **不合格を見つけたら**、画像ではなく、その時点の画面の文字・枚数・`Local Activity` の3つを記録する。
  OPが写った瞬間の説明に画像が要るときだけ、代表1枚を撮る（→ [観測とスクリーンショット](README.md#観測とスクリーンショット)）。

## 後処理

**戻し忘れると、次の検証で全アニメーションが10倍遅くなり、画面の向きが固定されたままになる。**
共通手順の後処理より**先に**、次を行う。

1. **折りたたみ状態の上書きを外す。** `cmd device_state state reset` を実行し、`print-state` が
   準備2で記録した状態に戻ったことを確かめる。
2. **設定を戻す。** 準備3で記録した値へ戻す（`animator_duration_scale` の記録が `null` なら `settings delete`）。
3. **戻ったことを確かめる。** 準備3の3コマンドを再実行し、記録と一致することを確かめる。
4. **`/sdcard/ui.xml` を削除する。**
5. **共通手順の「実機検証後」を行う。** `am force-stop` を使ったので、終了地点は常設検証用Vaultを
   表示した状態まで戻してから報告する。一時Vaultは作っていないので、削除する一時領域は無い。

## 記録

共通手順の記録表に加えて、次を残す。

| 項目 | 内容 |
|---|---|
| 端末の前提 | Foldか。準備2の現在状態と、使った閉じた状態・開いた状態のID |
| 変更前の値 | `animator_duration_scale`・`accelerometer_rotation`・`user_rotation` の3つ |
| 陽性対照 | `LAUNCH-05a` の起動直後の文字と、`Vigilith AI` が消えるまでの秒数 |
| 各ケース | `LAUNCH-05b`〜`05e` ごとに、`rotation`（または折りたたみ状態）の前後、3回の取得それぞれの文字、`Local Activity` の前後、`MainActivity` の枚数、`mFinished` |
| 判定 | 合格・不合格・未確認のどれか。**未確認の理由**（作り直されなかった・カバー画面へ続行されなかった・Foldでない） |
| `LAUNCH-05` | **`LAUNCH-05a`〜`05e` がすべて合格のときだけ**合格。Foldでない端末は05d・05eを対象外として、その旨を併記する |
| 戻し | 後処理3で確かめた3つの値と、`print-state` の結果 |
