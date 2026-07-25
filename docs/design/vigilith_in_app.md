# 設計思想 — アプリ内Vigilith

**対象:** N-1「マスコット＝読書相手の身体化」
**初版:** 2026-07-25
**関連:** [character_vigilith](character_vigilith.md)・[feature_ideas](../_wip/feature_ideas.md)
**状態:** Phase 1実装済み、Phase 2以降は未実装

---

## 1. 目的

Vigilithをポイントを要求するペットではなく、既存のAI操作とReadingTraceへ身体を与える
**寡黙な読書相手**として常駐させる。主役は常にノートであり、新しい会話人格やキャラクター用の
永続状態は作らない。

旧キャラクターシートの名称「Otus」は使用せず、実装・読み上げ・リソース名をすべて
**Vigilith**へ統一する。

## 2. 既存UIとの統合

ノート画面右下には、セクション要約と質問シートを開くドラッグ可能な吹き出しが既にある。
Vigilithを別要素として追加すると操作対象と表示領域が競合するため、**吹き出しの操作責務を
Vigilithへ移す**。

- タップ: 現在のセクションを対象に要約・質問シートを開く
- ドラッグ: 従来どおり画面内を移動
- AI生成状態: 本体右下の小さな進捗・完了・エラーバッジで通知
- ボトムシート表示中: 本文やシートを覆わないよう非表示
- 全画面読書: 従来の最小AIインジケータを維持（Phase 1）

## 3. 3状態

| 状態 | 既存状態からの導出 | Phase 1の表示 |
|---|---|---|
| Idle | 下記以外 | 正面で翼を畳む |
| Distilling | `Analyzing` / `Downloading` / `Saving`、AIタブ上の`Candidates` | 片翼で本文側を指す |
| Messenger | Noteタブで未dismissの`ReadingTraceCard`がある | 痕跡カプセルを抱える |

優先順位は **Distilling > Messenger > Idle**。エラー、モデル取得待ち、復旧要求を
Vigilith自身の感情として演じないため、これらはIdleへ戻して既存UIの説明に任せる。

表示判断は `resolveVigilithPresentation` の純関数へ集約する。ViewModelへキャラクター専用状態を
追加せず、既存状態を唯一の真実とする。

## 4. Phase計画

### Phase 1 — 静的プロトタイプと吹き出し置換（実装済み）

- 3ポーズを共通108×132 viewportのVectorDrawableで作成
- `VigilithMode` / `VigilithPresentation` と純粋な状態判定を追加
- Noteタブの既存吹き出しをVigilithへ置換
- タップ、ドラッグ、状態ラベル、AI状態表示を維持
- TalkBackのボタン名とクリックアクションを明示
- 状態優先順位・表示条件をJVMテスト7件で固定

### Phase 2 — アプリ外殻への常駐と個別モーション

- `AppScaffold`直下へ`VigilithHost`を置き、通常5タブで一体だけ描画
- Noteタブの現在セクション操作をHostへ受け渡す
- Idle: 4〜6秒周期のレンズ呼吸光（体は静止）
- Distilling: 目の収束、胸部コア、蒸留線、指し翼の短い動き
- Messenger: 低い位置から登場し、カプセルを一度だけAquaに明滅
- 状態遷移は短いCrossfade＋小さな移動に留め、回転・バウンドは使わない

### Phase 3 — 衝突回避と実機品質

- Compact / Fold展開時のサイズと余白を調整
- Snackbar表示中の上方退避
- IME、NavigationBar / Rail、ReadingTraceカードとの重なり検証
- Animator duration scale 0倍、TalkBack、タッチターゲットを検証
- ドラッグ位置を画面内へclampし、画面サイズ変更時に再計算
- 必要ならIdleラベルの表示頻度を下げ、本文優先を再確認

## 5. Phase 1の既知の境界

- 常駐範囲はまだNoteタブのみ。5タブ共通のHost化はPhase 2で行う。
- 3ポーズは静的ベクター。Phase 1では状態間Crossfadeと低強度のコア光だけを使う。
- 提示された800×450のキャラクターシートはラフ資料であり、切り抜き画像としては使用していない。
- Debug APKの実機インストールは成功。端末の認証ロックによりアプリ画面の目視確認は未完了。

## 6. ガードレール

1. 本文、ReadingTraceカード、操作ボタンを覆わない。
2. 自動で文章を発話せず、既存データを身体表現するだけにする。
3. ストリーク、空腹、報酬要求などの催促表現を持たせない。
4. 無限アニメーションはIdleの低強度発光だけとし、非表示中は動かさない。
5. 可視テキストとVigilithの説明を重複して読み上げない。
