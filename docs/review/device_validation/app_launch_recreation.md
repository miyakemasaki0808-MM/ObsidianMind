# 回転・Fold開閉の実機検証ケース（`LAUNCH-05`）

## 正本

- OPの再生条件と、重複起動を畳む判断: [opening_animation](../../dev/features/opening_animation.md) §5・判断7
- 起動ケースの全体と `LAUNCH-01`〜`04`: [app_launch](app_launch.md)
- 共通の準備と後処理: [Codex実機検証手順](README.md)

> **このファイルは [app_launch](app_launch.md) の `LAUNCH-05` を、手順として実行できる粒度へ分けたもの。**
> `LAUNCH-05a`〜`LAUNCH-05e` の**全件が合格したときだけ** `LAUNCH-05` を合格と記録する。

## 適用条件

- [app_launch](app_launch.md) の適用条件（`MainActivity` の `onCreate`・マニフェストの `intent-filter` / `launchMode`・
  OPの再生条件を触った）に当たる**通し版**で通す。
- **課題台帳の APP-1 を閉じるとき**は、変更の有無に関わらず通す。
- **ユーザーが端末の前にいるときだけ通す。** 回転と開閉はユーザーの手で行う（下記）。

### 何を見るか

回転とFold開閉では `MainActivity` が作り直され、`onCreate` に保存Bundle（非null）が渡る。
このとき次の3つが揃っていることを実機で確かめる。**どれか1つでも崩れたら不合格である。**

1. **OPが再生されない**（OPは `savedInstanceState == null` のときだけ出る）
2. **重複起動のガードが誤って畳まない**（`MainActivity` が1枚のまま残り、ノートの表示が保たれる）
3. **本当に作り直された**（作り直されていなければ、1と2は何も確かめていない）

`ActivityRecreationTest` が覆うのは `scenario.recreate()` による同一プロセス内の再生成で、
**実端末の回転・Fold開閉の経路は通っていない**。ここで見るのはその差分である。

### 役割 — 操作はユーザー、観測はCodex

| 担い手 | やること |
|---|---|
| **ユーザー** | 端末を横にする・縦へ戻す・開く・閉じる。**Codexの合図を受けてから**行う |
| **Codex** | 監視を始めて合図を出し、変化を記録して判定する。**端末設定は変えない** |

- **開閉と回転は遠隔では行わない。** `cmd device_state` による折りたたみ状態の上書きや、
  `settings` による画面の向きの固定は使わない（端末設定の変更にあたり、共通手順の権限範囲の外）。
- **自動回転がオフでも設定は変えない。** オフのまま端末を横にすると、ナビゲーションバーに
  **回転ボタン**が出るので、ユーザーにそれを押してもらう。
  それでも回転できないときだけ、共通手順どおり**自動回転の一時変更をユーザーへ確認**する
  （許可されたら、変更前の値を記録して後処理で戻す）。
- **カバー画面でアプリが続行されない**ときも設定は変えない。「上にスワイプして続行」の表示が出たら、
  ユーザーにスワイプしてもらう。

### 観測の要点 — OPは2秒で終わるので、画面の取得では見逃す

`uiautomator dump` は1回に数秒かかり、しかも**ユーザーがいつ操作するかCodexには分からない**。
2秒のOPが出ても、終わった後の画面しか取れない。**「OPが写っていなかった」は合格の証拠にならない。**

そこで**アプリと同じプロセスで動く一時テスト**（監視テスト）を書き、操作のあいだ画面の文字を
**100msおき**に採取する。OPが出れば20回以上の採取に写る。

| 画面 | 採取される文字 |
|---|---|
| OP再生中 | `Vigilith AI`（OP画面にしか無い）。本体は組み立てないので、ノートの文字は出ない |
| 本体（ノートタブ） | `Rediscover` と、表示中のノートの名前。`Vigilith AI` は出ない |

目印は `ActivityRecreationTest` と同じものを使う。

## 検証前

1. **共通手順の「実機検証前」1〜6を行う。** 一時Vaultもfixtureも要らない。常設検証用Vaultのままでよい。
2. **端末の前提を記録する。** 画面の点灯とロック解除（ロック画面ならユーザーへ解除を頼む。PINは入力しない）、
   自動回転のオン／オフ、いま開いているか閉じているか。**設定は読むだけで変えない。**

   ```text
   adb -s <serial> shell settings get system accelerometer_rotation
   adb -s <serial> shell dumpsys device_state | grep -E 'mCommittedState='
   ```

   Foldでない端末では `LAUNCH-05d`・`05e` を**対象外**と記録する。
3. **監視テストを書く。** 共通手順の[一時テスト](quick_check.md#一時テスト)の規則に従い、
   `app/src/androidTest/java/com/example/newproject/ui/LaunchRecreationDeviceProbeTest.kt` とする。
   **後処理で必ず削除する。** 次をすべて満たすこと。

   | 要件 | なぜ |
   |---|---|
   | **採取を起動より先に始める。** 採取用のスレッドを先に回してから `MainActivity` を起動し、起動時のOPの `Vigilith AI` を**1回以上**採取できたことを記録する | **陽性対照**（`LAUNCH-05a`）。捉えられない採取では、後で「出なかった」と言えない |
   | 画面の文字は `uiAutomation.rootInActiveWindow` から、`com.vigilith.ai` のノードだけを100msおきに採取する | 他アプリ・システムUIの文字を混ぜない。2秒のOPを見逃さない間隔 |
   | `Application.ActivityLifecycleCallbacks` で、`MainActivity` の `onActivityCreated` ごとに**保存Bundleが非nullか**と実体の識別値を記録する | 「本当に作り直された」と「保存Bundle付きで作り直された」を直接見る |
   | 構成（向き・画面幅）が変わるたびと、`dumpsys device_state` の `mCommittedState` が変わるたびにログへ出す | どの操作がどの作り直しに対応したかを後から突き合わせる |
   | 起動時のOPが終わりノートが表示されたら、ログへ `READY` を出す。**それ以降に `Vigilith AI` を1回でも採取したら、その場で失敗させる** | 合図の前に起きたOP（起動時の正常なOP）を不合格に数えない |
   | 待つのは最大5分。時間切れは失敗にし、そこまでに見えた変化をログへ出す | ユーザーを待たせ続けない。途中までの結果を未確認として残せる |

   ログのタグは `Launch05Probe` とする。

4. **机上ゲートとAPKの組み立てを行う**（共通手順の準備2・3。監視テストを含めて組み立てる）。
   両APKを `adb install -r` する。

## ケース

**監視テストの実行から `LAUNCH-05e` までは1回の実行で通す。** 監視を始めてから、ユーザーへ合図を出す。

```text
adb -s <serial> logcat -c
adb -s <serial> shell am force-stop com.vigilith.ai
adb -s <serial> shell am instrument -w -r \
  -e class com.example.newproject.ui.LaunchRecreationDeviceProbeTest \
  com.vigilith.ai.test/androidx.test.runner.AndroidJUnitRunner
# 別の端末で、合図を出す時機と結果を見る
adb -s <serial> logcat -s Launch05Probe
```

| ID | 入力・操作 | 期待 |
|---|---|---|
| `LAUNCH-05a` | **陽性対照。** 監視テストを起動し、`READY` が出るまで待つ（ユーザーは何もしない） | 起動時のOPの `Vigilith AI` を**1回以上**採取している。その後ノートの表示へ移り、`READY` が出る |
| `LAUNCH-05b` | `READY` を見てから、ユーザーへ「**横にしてください**」と合図する | 向きが横へ変わった記録がある。その変化に対応して `MainActivity` が**保存Bundle付きで**作り直されている。`Vigilith AI` を採取していない。作り直しの後にノートの表示が採取されている |
| `LAUNCH-05c` | 横になったのを確認してから、「**縦へ戻してください**」と合図する | `LAUNCH-05b` と同じ（向きは縦へ） |
| `LAUNCH-05d` | 「**開いてください**」と合図する（**いま開いているなら「閉じてください」**を先にする） | 折りたたみ状態が変わった記録がある。ほかは `LAUNCH-05b` と同じ |
| `LAUNCH-05e` | `LAUNCH-05d` と逆の操作を合図する | `LAUNCH-05d` と同じ |

**監視テストが終わったら**、次の2つを取って `LAUNCH-05b`〜`05e` の期待へ加える。

```text
# MainActivity の枚数（1であること）
adb -s <serial> shell dumpsys activity activities | grep -cE 'ActivityRecord\{[^}]*MainActivity'
# 前面のActivity（com.vigilith.ai であること）
adb -s <serial> shell dumpsys activity activities | grep -E 'topResumedActivity'
```

### 判定の注意

- **`LAUNCH-05a` が不合格なら、後続は判定しない。** OPを捉えられない採取で「出なかった」は言えない。
- **作り直しの記録が無い操作は、合格ではなく未確認と記録する。** 回転ボタンを押していない・
  向きの変化をActivityが受け流した、のどちらでも、OPが出ないのは当然で何も確かめていない。
- **閉じたときにアプリがカバー画面へ続行されなかったら**（ノートの表示が採取されない）、
  スワイプでの続行をユーザーへ頼む。それでも続行されなければ、設定を変えずに未確認と記録する。不合格とは書かない。
- **合図は1つずつ出し、前の変化がログに出てから次を出す。** 2つの操作が1回の作り直しに畳まれると、
  どちらの操作の結果か分からなくなる。
- **不合格を見つけたら**、画像ではなく、そのときのログ（採取した文字・作り直しの記録・構成と折りたたみ状態）を残す。
  説明に画像が要るときだけ代表1枚を撮る（→ [観測とスクリーンショット](README.md#観測とスクリーンショット)）。

## 後処理

1. **ユーザーへ、端末を検証前の開閉状態・向きへ戻すよう頼む**（検証前2の記録と照らす）。
2. **自動回転を一時的に変えた場合だけ**、記録した値へ戻し、`settings get` で一致を確かめる。
3. **監視テスト `LaunchRecreationDeviceProbeTest.kt` を削除する。** 残すと `DeviceProbeResidueTest` が落ちる。
4. **共通手順の「実機検証後」を行う。** `am force-stop` を使ったので、終了地点は常設検証用Vaultを
   表示した状態まで戻してから報告する。一時Vaultは作っていないので、削除する一時領域は無い。

## 記録

共通手順の記録表に加えて、次を残す。

| 項目 | 内容 |
|---|---|
| 端末の前提 | Foldか。検証前の自動回転のオン／オフと開閉状態。**設定を変えたか**（変えたなら、許可を得た記録と戻した値） |
| 陽性対照 | `LAUNCH-05a` で `Vigilith AI` を採取した回数 |
| 各ケース | `LAUNCH-05b`〜`05e` ごとに、合図の時刻、構成または折りたたみ状態の前後、作り直しの回数と保存Bundleの有無、`Vigilith AI` の採取回数（0であること）、作り直し後にノートを採取したか |
| 監視後 | `MainActivity` の枚数と `topResumedActivity` |
| 判定 | 合格・不合格・未確認のどれか。**未確認の理由**（作り直されなかった・カバー画面へ続行されなかった・Foldでない） |
| `LAUNCH-05` | **`LAUNCH-05a`〜`05e` がすべて合格のときだけ**合格。Foldでない端末は05d・05eを対象外として、その旨を併記する |
| 後処理 | 監視テストを削除したこと、端末を検証前の状態へ戻したこと |
