# クイズ

**状態:** Implemented（**ただし本文書は正本として未整備**）
**最終検証:** 2026-08-11 / `ffe0995`（**存在確認のみ。仕様は未記述**）
**関連コード:** `controller/QuizController.kt` / `model/state/QuizState.kt` / `domain/QuizResponseParser.kt` / `domain/QuizInputProfile.kt` / `ui/screen/QuizScreen.kt`
**関連テスト:** `QuizResponseParser` のJVMテスト（`domain` 配下）
**正本:** **未確定。** 現在は下記のとおり判断が他文書へ散っている。

---

> **この文書は空の器である。** 2026-08-11 の文書分割で `features/` を機能単位に並べたところ、
> **クイズだけ専用の設計文書が存在しない**ことが分かった。実装は稼働しているのに、
> 「何ができて、どう実現しているか」を1本で読める場所が無い。
>
> **穴を可視化するために、あえて空で置く。** 削ると次の分割まで誰も気づかない。

## 現在、判断が散っている先

| 内容 | 現在の在処 |
|---|---|
| バックグラウンド生成の待ち時間・3層通知・`markViewed()` による未確認管理 | [background_ai_ux](../system/background_ai_ux.md) |
| Controller を共通化しない判断（クイズが比較軸の1列） | [architecture](../system/architecture.md)「Controller共通化はしない」 |
| プロンプトへ渡す本文の抜粋方式と文字数上限 | [ai_input_excerpt](../system/ai_input_excerpt.md) |
| 応答パースの構造契約（境界付きID・`validIds` 照合） | 文書化されていない（`QuizResponseParser` のみ） |

## 埋めるときの手順

1. `QuizController` / `QuizState` / `QuizScreen` を読み、[`_template.md`](_template.md) の §1〜§7 を実装から起こす
2. 上表の判断を**移動ではなく参照**にする — 横断的な内容（通知の型・抜粋・Controller方針）の正本は
   `system/` 側のままにし、ここからリンクする（**正本を2つにしない**）
3. §10「保証していないこと」を必ず書く — Nano非対応端末での挙動、ノート切替時のキャンセルが対象
