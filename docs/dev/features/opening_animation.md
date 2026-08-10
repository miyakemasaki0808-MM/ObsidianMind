# 設計思想 — 起動OPアニメーション

**状態:** Implemented — 実装済み。新規Activity起動時のみ再生（回転・Fold開閉・プロセス復元では再生しない）
**最終検証:** 2026-08-11 / `a99e524`（**ヘッダのみ確認。本文は未検証**）
**関連コード:** `ui/screen/OpeningScreen.kt / ui/vigilith/`
**関連テスト:** VigilithOpeningMotionTest
**正本:** この文書

**対象領域:** アプリ起動時のブランド演出（システムスプラッシュ＋Compose OP）
**初版:** 2026-07-20（PR #26）
**改稿:** 2026-07-26（アプリ内Idle WebPへ統一）
**関連:** [character_vigilith](character_vigilith.md)・[vigilith_in_app](vigilith_in_app.md)

---

## なぜOPを入れるのか

コールド起動で `setContent` からいきなり本体（Noteタブ）が出る従来の体験に、ブランド提示の「間」を一枚挟む。狙いは (1) 起動の白フラッシュを消して連続感を出す、(2) アプリの世界観（濃紺＋ブランドグラデーション）を最初に印象づけること。

## 「軽量C」方針 — 2層構成

起動演出には性質の異なる2つの層がある。両方を薄く使い、継ぎ目を消すことを優先した（凝ったOSアニメには踏み込まない）。

| 層 | 役割 | 実装 |
|----|------|------|
| ① システムスプラッシュ | コールド起動の一瞬をブランド色で即座に埋める | `core-splashscreen`。月光スレート(`vigilith_slate`)背景＋アイコン。`installSplashScreen()` を `super.onCreate()` 前に呼ぶ |
| ② Compose OP | Vigilith・製品名のアニメーション本体 | `OpeningScreen.kt`。`setContent` 直後に本体の代わりに表示 |

システムスプラッシュ側のシステムバー色も月光スレートに揃え、①→②で色が跳ねないようにしている。

## 設計判断

| 論点 | 決定 | 理由 |
|------|------|------|
| 背景の受け渡し | OP終端を **`ReadingGradient`** で解決し、`VigilithSlate` をα制御で剥がす | 起動着地は `startDestination="note"` ＝ Noteタブで、その背景は `ReadingGradient`。OPを着地と同色で終えれば、真のクロスフェードなしの**ハードカットでも継ぎ目が見えない** |
| 黒曜石と背景の分離 | 外周を月光スレート **`#314158`** にし、中央に **Aqua 16% → Indigo 14% → Purple 10%** の低強度ハロー | 背景自体の明度差でLogoNavyの頭部を読みやすくし、ハローは補助に留める。Adaptive Icon背景とCompose OPで同じ色・実効アルファを使う |
| 本体の配置 | OP中は本体をコンポーズせず、完了時に入れ替え（`return@setContent`） | OP背面の誤タップ・TalkBack読み上げ・Snackbar表示を構造的に遮断。`NoteViewModel.init` の `restoreVault()` はcomposition非依存で走るため取りこぼしなし |
| 進行の駆動 | 単一 `Animatable` を `tween` で 0→1 に進め、全要素をそこから導出 | 固定 `delay` を使わず、端末の Animator duration scale（0倍含む）にCompose標準挙動で追従。倍率0ならほぼ即時に本体へ |
| Vigilithの登場 | **ハロー → 完成WebP全身 → 名称** | 目だけの別描画は完成イラストとの二重表現になり違和感を生むため削除。回転・バウンド・常時点滅も使わない |
| 再生判定 | `savedInstanceState == null` のときだけ再生 | 新規起動＝null、回転/Fold開閉/バックグラウンド復帰/プロセス復元＝非null。`rememberSaveable` ではなく `remember` で保持し、config変更で再評価される点を利用 |
| スキップ | 画面全体タップで即終了 | `finishOnce()` で完了コールバックの1回実行を保証（自然終了とスキップの競合対策） |

## 実装上の判断

- **外部ラムダは `rememberUpdatedState` 経由で呼ぶ**。`OpeningScreen` の `onFinished` は `LaunchedEffect`（長寿命ブロック）から呼ぶため、PR #25で文書化した「stale closure」の教訓（[architecture](../system/architecture.md) 参照）に従う。
- **タイムライン計算は純関数 `vigilithOpeningMotion` に集約**し、区間ごとの背景・本体・ハロー・名称の
  αとスケールを1フレーム分の値へ変換する。ComposeやAndroid型を含めないためJVMテストで演出順と終端を検証できる。
- **目専用のCanvasレイヤーは置かない。** 完成WebPに描かれた目だけを使い、起動中の二重描画と
  退場時に目だけ残って見える現象を構造的に防ぐ。
- **ハローの色側へ最終アルファを持たせ、モーション側は登退場だけを制御する。** Adaptive Icon背景と数値を比較しやすくし、二重のα乗算で意図より暗くなることを防ぐ。

## Vigilithへの移行（2026-07-25）

旧OPは開発元のM.M AI Solutionsロゴを表示しており、Vigilithへ差し替えたランチャーアイコンと不整合だった。
Compose OPは2026-07-25に `ic_vigilith.xml` へ移行し、2026-07-26にアプリ内と同じ
`vigilith_idle_rich.webp`へ統一した。演出は次の2秒タイムラインを維持する。

1. 月光スレートの薄闇にAqua→Indigo→Purpleのハローが現れる
2. 黒曜石の全身がわずかに浮上しながら現れる
3. 完成WebPの目・嘴・コアを含む全身が一体として整う
4. 「Vigilith AI」の名称が現れる
5. キャラクターと月光スレートが消え、背面のReadingGradientへ着地する

途中タップによるスキップ、2秒という総時間、Animator duration scaleへの追従は維持する。

## コードレビューで直した点（PR #26 レビュー）

Codexの初版に対し、動作を変えない範囲で2件を修正した。

1. **TalkBackの二重読み上げ回避** — 外側Boxの `contentDescription = "Obsidian Mind"` と可視 `Text("Obsidian Mind")` が重複源になっていた。外側は `semantics(mergeDescendants = true) {}` に留め、名称は可視Textが1回だけ供給する形にした。**マージノードに `contentDescription` を重ねると子の text と二重に読まれ得る**、が教訓。
2. **発光色の定義位置** — OP背面発光の `LogoPurple` をローカル定義から `AppColors` へ集約し、Aqua/Indigo/LogoNavy/VigilithSlate と定義元を揃えた。

## 見送り（別タスク）

- **OP時間（2秒）**：繰り返し起動では長く感じ得るが、短縮はプロダクト判断（`OpeningDurationMillis` 1行で調整可）。

旧ランチャー前景PNGの軽量化課題は、VigilithをVectorDrawableとして実装したことで解消した。旧PNGは
現行コードから参照されず、移行確認後に削除可能。
