# 設計思想 — アプリ内Vigilith

**対象:** N-1「マスコット＝読書相手の身体化」
**初版:** 2026-07-25
**関連:** [character_vigilith](character_vigilith.md)・[feature_ideas](../_wip/feature_ideas.md)
**状態:** Phase 3実装済み（実機画面の目視確認のみ認証ロックで保留）

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
- 全画面読書: 従来の最小AIインジケータを維持
- 通常5タブ: `AppScaffold`直下の共通Hostで同一個体を描画

## 3. 4表示状態（3つの基本姿勢）

| 状態 | 既存状態からの導出 | 表示・モーション |
|---|---|---|
| Idle | 下記以外 | 正面で翼を畳み、レンズとコアだけが4.8秒周期で弱く呼吸する |
| Summarizing | 全体要約またはセクション要約の生成中 | 胴体を三分の二横向きにし、外側の片翼を開いて結果を案内する |
| Distilling | `Analyzing` / `Saving`、AIタブ上の`Candidates` | 正面で両翼を胸元へ寄せ、断片の収集・保持・下線確定を工程別に描く |
| Messenger | Noteタブで未dismissの`ReadingTraceCard`がある | 下から静かに着地し、痕跡カプセルが登場中に一度だけ明滅する |

4状態はそれぞれ専用の透過WebP完成ポーズを持つ。Distillingは横向きで本文を指す
旧案を廃止し、添付キャラクター資料の正面・両翼ポーズを正とする。
優先順位は **Distilling > 明示的なセクション要約 > Messenger > バックグラウンド要約 > Idle**。
エラー、モデル取得待ち、復旧要求を
Vigilith自身の感情として演じないため、これらはIdleへ戻して既存UIの説明に任せる。

表示判断は `resolveVigilithPresentation` の純関数へ集約する。ViewModelへキャラクター専用状態を
追加せず、既存状態を唯一の真実とする。

## 4. Phase計画

### Phase 1 — 静的プロトタイプと吹き出し置換（実装済み）

- 3ポーズを共通108×132 viewportのVectorDrawableで作成（後のWebP移行前プロトタイプ）
- `VigilithMode` / `VigilithPresentation` と純粋な状態判定を追加
- Noteタブの既存吹き出しをVigilithへ置換
- タップ、ドラッグ、状態ラベル、AI状態表示を維持
- TalkBackのボタン名とクリックアクションを明示
- 状態優先順位・表示条件をJVMテスト7件で固定

### Phase 2 — アプリ外殻への常駐と個別モーション（実装済み）

- `AppScaffold`直下へ`VigilithHost`を置き、通常5タブで一体だけ描画
- Noteタブの現在セクション操作をHostへ受け渡す
- Idle: 原画の丸みと黒曜石の質感を保つ透過WebPを使用し、4〜6秒周期でレンズとコアだけが呼吸する
- Summarizing: 胴体と足を三分の二横向きにし、頭はこちらへ向けた専用WebPで、外側の片翼から要約を差し出す
- Distilling: 両翼を胸元へ寄せた専用WebPで、`Analyzing`は3つの断片を中央へ集め、`Candidates`は一節を保持し、`Saving`は下線を一度だけ引く
- Messenger: 痕跡カプセルを抱えた専用WebPが低い位置から登場し、カプセルを一度だけAquaに明滅
- 状態遷移は短いCrossfade＋小さな移動に留め、回転・バウンドは使わない
- `VigilithMascotMotion`を純粋計算にし、要約と蒸留3工程の分離・出力範囲・一度だけの発光・着地をJVMテスト9件で固定
- アプリ内4状態と起動OPは専用の透過ロスレスWebP、ランチャーはAdaptive Icon用VectorDrawableを使用
- 目は全表示で、明るい機械枠／Aqua虹彩／濃色瞳孔／左上キャッチライトへ統一

### Phase 3 — 衝突回避と実機品質（実装済み）

- ドラッグ位置をpxではなく配置可能領域内の相対座標（0〜1）で保持し、四辺へclamp
- Fold開閉・回転・状態ラベルの寸法変更後も、同じ相対位置から安全な座標を再計算
- CompactではNavigationBar、展開時はNavigationRailを予約領域に含め、システムバーも
  `WindowInsets.safeDrawing`から実測
- Snackbar表示中は72dp上方へ退避し、IME表示中はキーボード上端＋16dpを下限として優先
- ReadingTraceカードは本文上部、Vigilithの既定位置はナビゲーション上の右下となるため、
  Compact / Foldとも初期配置では競合しない。ユーザーが移動した位置も画面変更後にclampされる
- 76×93dpのタッチ領域を維持。Noteタブでは1つのButton semanticsへ状態・操作・対象節を集約し、
  可視ラベルは読み上げ対象から外してTalkBackの二重フォーカスを防止
- Animator duration scale 0倍ではComposeの遷移が即時完了しても最終ポーズが成立する構成を維持
- 画面内clamp、Fold再配置、ラベル寸法、Snackbar / IME退避を純粋ロジック6件、
  TalkBack文言を2件のJVMテストで固定

## 5. Phase 3終了時点の既知の境界

- 5タブ共通Host化、4表示状態（3基本姿勢）のモーション、衝突回避を実装済み。
- 画面外へのドラッグ、Compact / Fold再配置、Snackbar / IME / Navigation UIの予約領域は
  純粋計算とJVMテストで検証済み。
- 提示資料と承認済みIdleを造形参照にして4状態の透過WebPを新規生成した。資料自体の切り抜きではなく、
  76×93dp表示に合わせたIdle／Summary／Distilling／Messengerの専用素材である。
- 4素材はいずれも透過WebP（高さ936px、幅749〜802px）で、76×93dpの表示枠に対して十分な解像度を持つ。
- Phase 3のDebug APKはPixel 10 Pro Foldへインストール済み。ただし端末の認証ロックにより、
  アプリ画面でのドラッグ・Snackbar・IME・Animator 0倍・TalkBackの最終目視／操作確認は未完了。

## 6. ガードレール

1. 本文、ReadingTraceカード、操作ボタンを覆わない。
2. 自動で文章を発話せず、既存データを身体表現するだけにする。
3. ストリーク、空腹、報酬要求などの催促表現を持たせない。
4. 反復アニメーションは低強度の発光・候補探索・翼に限定し、非表示中は動かさない。
5. 可視テキストとVigilithの説明を重複して読み上げない。
