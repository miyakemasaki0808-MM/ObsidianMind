# 設計思想 — instrumentation テスト

**対象領域:** 実端末を通すテストの選定基準・実物SAFの作り方・実行の運用
**状態:** 実装済み・稼働中（40件）。CIでは実行せず、PR前にAndroid Studioで回す運用。
**関連:** [architecture](architecture.md)・[note_image_rendering](../features/note_image_rendering.md)・[ai_input_excerpt](ai_input_excerpt.md)
**経緯:** [開発日誌 2026-08](../../owner/journal/2026-08.md#2026-08-08--instrumentation-を34件そろえて実機で回す)

---

## 判断1: 置くのは「実測が要るもの」だけ

判定は機能名ではなく**「JVMで書けないか」**で行う。純関数の値の伝播・分岐・境界値はJVM側が覆っているので持ち込まない。
`androidTest` に残すのは **Compose のレイアウト実測・可視判定・再コンポーズ・Activityのライフサイクル・
実物のSAF/`BitmapFactory`** に依存するもの。

**この基準は書き始めると「ついでにここも」で崩れやすい。**
各テストのKDocに**なぜJVMでは書けないかを1行残す**ことで、後から読んで判断できるようにする。

## 判断2: ViewModel を組み立てない。先回りで seam を作らない

`NoteReaderTab` / `FullscreenNoteScreen` は `NoteUiState` と `LazyListState` を**素の引数として受け取る**。
UIに業務ロジックを置かない規約の副産物として、**ViewModelもFakeも無しに画面を描ける。**

**`MainActivity` 経由では差し替えられない**（`by viewModels()` でデフォルトファクトリを直に使うため）。
画面コンポーザブルを直接 `setContent` することで回避する。
**先回りで ViewModelFactory の seam を作らない** — 使い道が確定していない抽象を本番へ足すことになる。

> 実際、Activity再生成のテストでも seam は要らなかった。確かめたい振る舞い
> （再生成でOPを再生し直さない）は **Vault 未選択のままで観測できる**ため、実依存のまま起動してよい。

**`src/test` の Fake は `androidTest` から見えない**（別ソースセット）。共有が必要になったら
共有ディレクトリを両ソースセットへ足す。

## 判断3: ブロック番号を本文へ埋めて可視位置を特定する

`ReadingProgressReporter` は `visibleItemsInfo.last().index` を**そのままブロック番号として**報告する。
したがってテスト本文を「段落0」「段落1」…と並べておくと**可視位置を文言で特定できる。**
`LazyListState` の内部値ではなく画面に出ている文字で判定するので、レイアウトの細部が変わっても壊れにくい。

**全画面の引き継ぎ判定には先頭可視ブロックを使う。** 全画面はシステムバーを隠して表示域が変わるため
末尾可視ブロックは1つずれ得るが、引き継がれるのは `firstVisibleItemIndex` なのでそこを見る。

## 判断4: 実物SAFのプロバイダは `src/debug/` へ置く

`androidTest` 側に置くと**別APK・別UIDになり、URI権限の付与が要る**。
debug ソースセットならアプリ本体と同じUIDになるので `DocumentsContract` の tree URI をそのまま扱える。
**release には入らない。**

**読取失敗は「例外」ではなく「null カーソル」で作る。** 本番が守っているのは
`query()` が `null` を返す経路（→ [reflect_reading_trace](../features/reflect_reading_trace.md) 判断14）なので、
例外を投げるプロバイダでは**別の経路を試していることになる。**

## 判断5: CIでは実行せず、実行を運用で担保する

**現在のCIは `assembleDebugAndroidTest`（コンパイルのみ）で、instrumentation を1件も実行していない。**
このまま増やすと「コンパイルは通るが誰も走らせないテスト」になり、
[lessons L29](../lessons/L29.md) と同じ形（規則を書いただけで検査になっていない）になる。

**それでもCIにエミュレータジョブを足さない。**

1. **端末AI依存のテストは、エミュレータでは `Assume` で skip される。**
   しかも**最も実機固有な部分がここ**なので、エミュレータが緑でも実機確認は残る
2. CI時間の増加が大きく、現在の1人開発の回転数に見合わない

代わりに**「PR前にAndroid Studioで実行する」を運用として明記**する。
**運用は検査に劣ることを承知の上での選択。**

> **再検討の契機に件数を使わない。** かつて「20件を超えたら再検討」を置いたところ、
> 同じ日に34件へ到達して発火し、**「入れる価値が出た」という誤った結論を一度出させた**。
> 実際に踏んだ失敗はどちらも基盤の初期設定ミスで、**実行忘れで流出した実績は1件も無い。**
> 契機は**実害か状況の変化**（実行忘れで実害が出た／開発者が複数人になった／配布のゲートが要る）
> → [lessons L31](../lessons/L31.md)

## 判断6: 端末AIは「生成が返ること」までを assert し、書式は観測に留める

Nano の出力は揺らぐので、書式を assert すると**モデルの気分でCIが赤くなる**。
生成が返ること・例外が出ないこと・トークン予算に収まることまでを固定し、書式はログへ出して観測する。

**skip 判定は既知の `FeatureStatus` だけで行う。**
`checkAvailability()` は例外も `Unavailable` へ畳むので、判定に使うと
**SDKの回帰が「非対応端末」に化けて見逃される。**
**計測・生成呼び出しが投げた例外は skip せず失敗させる。**

**端末AIのテストには `ActivityScenarioRule` が要る。** AICore はバックグラウンドからの利用を拒否し、
instrumentation は既定でフォアグラウンドのActivityを持たない。
**Activity の有無は環境ではなく前提条件である** → [lessons L20](../lessons/L20.md)。

## 判断7: 画面の目印は「重ならない文言」かつ「端末の実データに依存しないもの」から選ぶ

- タブのラベルと重なる文言を目印にすると、**タブとコンテンツのどちらを掴んだか分からない**
- 端末の実データ（実際のVaultの中身）に依存する目印は、**別の端末で落ちる**

## 判断8: バックスタック契約は「組み合わせ」で守る

**単体を隔離してテストしない。** `popUpTo` の設定は「タブAへ行き、Bへ行き、戻る」のような
**組み合わせでしか壊れ方が現れない。** 変異（`popUpTo` を外す）で落ちることを確認済み。

**開始タブでの「戻る」は Activity を終了させる**（バックスタックが空になるため）。
これは仕様なので、そう assert する。

## 判断9: `@Test` の戻り値は検査で縛る（規約にできない）

JUnit4 の `@Test` は `void` を要求する。Kotlin の `fun x() = runBlocking { ... }` は
**ブロック末尾の式の型がそのまま戻り値になる**ので、末尾に `Log.i()`（`Int` を返す）を置くと
`InvalidTestClassError` で**クラス全件が起動しない。**

**しかも失敗が「赤」ではなく「件数の減少」として出るので気づけない**
（「1 failed, 30 passed」に見え、消えた3件はどこにも数えられない）。

「末尾に値を返す式を置かない」は規約にできない（1行足すだけで破れ、破れたことが見えない）ので、
**書き方のほうを縛る** — 式本体の `runBlocking` は `runBlocking<Unit>` にし、
`InstrumentationTestShapeTest` がソース走査で固定する。
**コンパイルが通ることは、テストが起動することを意味しない** → [lessons L30](../lessons/L30.md)。

## 判断10: 撤回した主張 — 覆えていない範囲を正直に書く

| かつての主張 | 現在 |
|---|---|
| 「プロセス再生成を検証している」 | **`ActivityScenario.recreate()` は同一プロセス内で Activity を作り直すだけ。** Application・静的状態・プロセス内キャッシュは生き残るので、**全件成功からプロセス死亡耐性は結論できない**。クラス名を `ActivityRecreationTest` へ改め、主張を狭めた |
| 「連続入力の競合をテストする」 | **Android 17 では作れないので撤回した。** 作れない競合を「テストした」と書かない |

**上限の境界はBMPで通す。** PNG は圧縮が効くので「入力バイト数の上限ちょうど」を狙って作れない。
BMP は非圧縮なので寸法から正確にバイト数を決められる。

---

## ソースセットは4つ

| | 用途 | release に入るか |
|---|---|---|
| `src/main` | 本番 | ✓ |
| `src/test` | JVMユニットテスト | — |
| `src/androidTest` | instrumentation | — |
| `src/debug` | テスト用 `DocumentsProvider` | **入らない** |

## 検証

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebugAndroidTest --offline
```

**実行は Android Studio から行う**（CIはコンパイルまで）。
**テストを足したら、足した数だけ増えたかを件数で確かめる**（判断9）。
