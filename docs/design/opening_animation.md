# 設計思想 — 起動OPアニメーション

**対象領域:** アプリ起動時のブランド演出（システムスプラッシュ＋Compose OP）
**初版:** 2026-07-20（PR #26）

---

## なぜOPを入れるのか

コールド起動で `setContent` からいきなり本体（Noteタブ）が出る従来の体験に、ブランド提示の「間」を一枚挟む。狙いは (1) 起動の白フラッシュを消して連続感を出す、(2) アプリの世界観（濃紺＋ブランドグラデーション）を最初に印象づけること。

## 「軽量C」方針 — 2層構成

起動演出には性質の異なる2つの層がある。両方を薄く使い、継ぎ目を消すことを優先した（凝ったOSアニメには踏み込まない）。

| 層 | 役割 | 実装 |
|----|------|------|
| ① システムスプラッシュ | コールド起動の一瞬をブランド色で即座に埋める | `core-splashscreen`。濃紺(`logo_navy`)背景＋アイコン。`installSplashScreen()` を `super.onCreate()` 前に呼ぶ |
| ② Compose OP | ロゴ・製品名のアニメーション本体 | `OpeningScreen.kt`。`setContent` 直後に本体の代わりに表示 |

システムスプラッシュ側のシステムバー色も濃紺に揃え、①→②で色が跳ねないようにしている。

## 設計判断

| 論点 | 決定 | 理由 |
|------|------|------|
| 背景の受け渡し | OP終端を **`ReadingGradient`** で解決し、`LogoNavy` をα制御で剥がす | 起動着地は `startDestination="note"` ＝ Noteタブで、その背景は `ReadingGradient`。OPを着地と同色で終えれば、真のクロスフェードなしの**ハードカットでも継ぎ目が見えない** |
| 本体の配置 | OP中は本体をコンポーズせず、完了時に入れ替え（`return@setContent`） | OP背面の誤タップ・TalkBack読み上げ・Snackbar表示を構造的に遮断。`NoteViewModel.init` の `restoreVault()` はcomposition非依存で走るため取りこぼしなし |
| 進行の駆動 | 単一 `Animatable` を `tween` で 0→1 に進め、全要素をそこから導出 | 固定 `delay` を使わず、端末の Animator duration scale（0倍含む）にCompose標準挙動で追従。倍率0ならほぼ即時に本体へ |
| 再生判定 | `savedInstanceState == null` のときだけ再生 | 新規起動＝null、回転/Fold開閉/バックグラウンド復帰/プロセス復元＝非null。`rememberSaveable` ではなく `remember` で保持し、config変更で再評価される点を利用 |
| スキップ | 画面全体タップで即終了 | `finishOnce()` で完了コールバックの1回実行を保証（自然終了とスキップの競合対策） |

## 実装上の判断

- **外部ラムダは `rememberUpdatedState` 経由で呼ぶ**。`OpeningScreen` の `onFinished` は `LaunchedEffect`（長寿命ブロック）から呼ぶため、PR #25で文書化した「stale closure」の教訓（[architecture](architecture.md) 参照）に従う。
- **タイムライン計算は純関数 `fractionBetween` に切り出し**、区間ごとのα・スケールをそこから合成する。区間境界（0.325 / 0.75）での連続性を保つよう組んでいる。

## コードレビューで直した点（PR #26 レビュー）

Codexの初版に対し、動作を変えない範囲で2件を修正した。

1. **TalkBackの二重読み上げ回避** — 外側Boxの `contentDescription = "Obsidian Mind"` と可視 `Text("Obsidian Mind")` が重複源になっていた。外側は `semantics(mergeDescendants = true) {}` に留め、名称は可視Textが1回だけ供給する形にした。**マージノードに `contentDescription` を重ねると子の text と二重に読まれ得る**、が教訓。
2. **発光色の定義位置** — OP背面発光の `LogoPurple` をローカル定義から `AppColors` へ集約し、Aqua/Indigo/LogoNavy と定義元を揃えた。

## 見送り（別タスク）

- **ランチャーアイコン前景PNG（671KB）の軽量化**：`drawable-nodpi` のフル解像度PNGをアダプティブ前景にも共用しており、APK肥大と密度非対応の負債。密度別PNGまたはベクター化が理想だが、画像の作り直しが必要なため分離した。
- **OP時間（2秒）**：繰り返し起動では長く感じ得るが、短縮はプロダクト判断（`OpeningDurationMillis` 1行で調整可）。
