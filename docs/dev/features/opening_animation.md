# 起動OPアニメーション

**状態:** Implemented — 稼働中。**新規Activity起動時のみ再生**（回転・Fold開閉・プロセス復元では再生しない）
**最終検証:** 2026-08-11 / `9e4a2b7`（**`OpeningDurationMillis = 2_000` のみ実装で確認。演出の各区間は未突合**）
**関連コード:** `ui/screen/OpeningScreen.kt` / `ui/vigilith/`（`vigilithOpeningMotion`）
**関連テスト:** `VigilithOpeningMotionTest`
**正本:** この文書

**対象領域:** アプリ起動時のブランド演出（システムスプラッシュ＋Compose OP）

---

## 1. 概要

コールド起動で `setContent` からいきなり本体（ノートタブ）が出る代わりに、
ブランド提示の「間」を一枚挟む。

狙いは2つ — **起動の白フラッシュを消して連続感を出す**ことと、
**世界観（濃紺＋ブランドグラデーション）を最初に印象づける**こと。

## 2. ゴールと非ゴール

### ゴール
- 起動から本体まで**色の跳ねが無い**（継ぎ目を消す）
- Vigilith を最初に見せる

### 非ゴール
- **凝ったOSアニメには踏み込まない**（「軽量C」方針）
- **繰り返し起動で邪魔にならない**（タップで即スキップ）
- **回転やFold開閉で再生しない**

## 3. 詳細機能一覧

| 詳細機能 | ユーザーから見える挙動 | 起動条件 |
|---|---|---|
| システムスプラッシュ | 起動の一瞬を月光スレートで埋める | コールド起動 |
| Compose OP | ハロー → Vigilith 全身 → 名称 の2秒 | `savedInstanceState == null` のときだけ |
| スキップ | 画面全体タップで即終了 | OP再生中 |

## 4. 現在のユーザーフロー

1. コールド起動 → **システムスプラッシュ**（月光スレート `vigilith_slate` 背景＋アイコン）
   - `installSplashScreen()` は `super.onCreate()` **前**に呼ぶ
2. `setContent` 直後、本体の代わりに `OpeningScreen` を表示する（**本体はコンポーズしない**）
3. 2秒のタイムライン（→ §5）が進む
4. 終端で `ReadingGradient` へ着地し、本体（ノートタブ）と入れ替わる

**途中で画面をタップすると即終了する。** `finishOnce()` が完了コールバックの1回実行を保証する
（自然終了とスキップの競合対策）。

## 5. 機能仕様

- **再生判定:** `savedInstanceState == null` のときだけ。
  **新規起動＝null、回転／Fold開閉／バックグラウンド復帰／プロセス復元＝非null。**
  `rememberSaveable` ではなく `remember` で保持し、config変更で再評価される点を利用する
- **総時間:** **2,000ms**（`OpeningDurationMillis`）
- **進行の駆動:** 単一 `Animatable` を `tween` で 0→1 に進め、**全要素をそこから導出**する。
  固定 `delay` を使わないので、端末の **Animator duration scale（0倍を含む）に Compose 標準挙動で追従**する
  （倍率0ならほぼ即時に本体へ）
- **タイムライン:**
  1. 月光スレートの薄闇に Aqua → Indigo → Purple のハローが現れる
  2. 黒曜石の全身がわずかに浮上しながら現れる
  3. 完成WebPの目・嘴・コアを含む全身が一体として整う
  4. 「Vigilith AI」の名称が現れる
  5. キャラクターと月光スレートが消え、背面の `ReadingGradient` へ着地する
- **色:**

  | 対象 | 値 |
  |---|---|
  | 外周 | 月光スレート `#314158` |
  | 中央のハロー | Aqua 16% → Indigo 14% → Purple 10%（低強度） |

- **エラー／キャンセル時:**

  > **該当なし:** OPはI/OもAI生成も行わない。失敗しうる処理を持たない。

## 6. 状態とデータ

**永続化しない。** OP完了の判定は Activity のライフサイクル（`savedInstanceState`）だけで行う。

> **該当なし:** `NoteUiState` にも contribute しない。OPは本体をコンポーズする前に完結する。

**`NoteViewModel.init` の `restoreVault()` は composition 非依存で走る**ので、
OP中に本体をコンポーズしなくても取りこぼしは起きない。

## 7. システム設計

```
MainActivity
 ├─ installSplashScreen()      ← super.onCreate() より前
 └─ setContent
      └─ savedInstanceState == null なら OpeningScreen（本体は return@setContent で抑止）
           └─ Animatable 0→1
                └─ vigilithOpeningMotion(progress)   ← 純関数・JVMテスト
                     └─ 背景 / 本体 / ハロー / 名称 の α とスケール
```

## 8. 設計判断と代替案

### 判断1: 2層構成にして、継ぎ目を消すことを優先する

| 層 | 役割 |
|---|---|
| ① システムスプラッシュ | コールド起動の一瞬をブランド色で即座に埋める |
| ② Compose OP | Vigilith・製品名のアニメーション本体 |

**システムスプラッシュ側のシステムバー色も月光スレートに揃え、①→② で色が跳ねないようにする。**

### 判断2: OP終端を着地先と同色にして、クロスフェードを不要にする

起動着地は `startDestination="note"` ＝ ノートタブで、その背景は `ReadingGradient`。
**OPを着地と同色で終えれば、真のクロスフェードなしのハードカットでも継ぎ目が見えない。**

### 判断3: OP中は本体をコンポーズしない

完了時に入れ替える（`return@setContent`）。
**OP背面の誤タップ・TalkBack読み上げ・Snackbar表示を構造的に遮断する。**

### 判断4: 固定 `delay` を使わない

単一 `Animatable` から全要素を導出することで、**端末の Animator duration scale に追従**する。
アニメーションを切っている端末では、待たされずに本体へ着く。

### 判断5: 目専用の描画レイヤーを置かない

完成WebPに描かれた目だけを使う。目だけの別描画は完成イラストとの**二重表現**になり違和感を生む。
**退場時に目だけ残って見える現象も構造的に防げる。**

回転・バウンド・常時点滅も使わない。

### 判断6: ハローの最終アルファは色側に持たせる

モーション側は登退場だけを制御する。
**Adaptive Icon 背景と数値を比較しやすくなり、二重のα乗算で意図より暗くなることを防ぐ。**

### 実装上の注意

- **外部ラムダは `rememberUpdatedState` 経由で呼ぶ。** `onFinished` は `LaunchedEffect`（長寿命ブロック）から
  呼ぶため、stale closure の型に当たる（→ [lessons/L34](../lessons/L34.md)）
- **タイムライン計算は純関数 `vigilithOpeningMotion` に集約する。** Compose や Android 型を含めないので
  JVMテストで演出順と終端を検証できる
- **マージノードに `contentDescription` を重ねない。** 外側 Box の `contentDescription` と可視 `Text` が
  重複源になり、TalkBack が二重に読む。外側は `semantics(mergeDescendants = true) {}` に留める

## 9. 品質要件

- **アクセシビリティ:** 名称は可視 `Text` が1回だけ供給する（二重読み上げを避ける）
- **性能:** Animator duration scale に追従するので、アニメーションを切った端末では待たされない
- **プライバシー:**

  > **該当なし:** ユーザーデータに触れない。

## 10. 検証と受け入れ条件

- **JVMテスト:** `VigilithOpeningMotionTest`（演出順と終端の値）
- **instrumentation:**

  > **該当なし:** 起動演出は実端末の目視が主で、自動化していない。

- **保証していないこと:**
  - **見た目の印象は自動検証していない。** 純関数が返す数値の順序だけを固定している
  - **2秒という長さの妥当性は測っていない**（繰り返し起動では長く感じ得る）

## 11. 既知の制約・未解決事項

| | |
|---|---|
| OP時間（2秒）が繰り返し起動では長く感じ得る | 短縮は**プロダクト判断**。`OpeningDurationMillis` の1行で調整できる |
| 旧ランチャー前景PNG | Vigilith を VectorDrawable として実装したことで参照されなくなった。削除可能 |

## 12. 開発経緯

[開発日誌 2026-07](../../owner/journal/2026-07.md)
