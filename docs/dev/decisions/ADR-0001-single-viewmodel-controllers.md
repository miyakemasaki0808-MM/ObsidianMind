# ADR-0001: 単一ViewModel＋機能Controller方式を採る

**状態:** Accepted
**決定日:** 2026-07（→ [owner/journal/2026-07.md](../../owner/journal/2026-07.md)）
**詳細の正本:** [system/architecture.md](../system/architecture.md) 判断1・判断2

## 文脈

機能追加を重ねて `NoteViewModel` が906行まで肥大し、状態リセット処理の3重複による
「リセット漏れ→旧状態の残留」バグが複数発生していた。

## 決定

**マルチモジュール化も機能別ViewModel化も採らず、`NoteViewModel` は窓口として残し、実装を機能Controllerへ委譲する。**
横断調停と状態所有は `NoteSessionCoordinator` が持ち、各Controllerには機能別の `*StateWriter` だけを注入する。

- **マルチモジュール化を採らない理由:** 差し替え対象が依存グラフ1つだけで、ビルド構成の複雑さに見合わない
- **機能別ViewModel化を採らない理由:** ノート切替時の後始末が各ViewModelへ散り、リセット漏れの原因をそのまま残す

## 帰結

- **ノート単位の状態を足したら、契約2箇所へ必ず登録する** — `cancelNoteScopedJobs()` と `withNoteScopedReset()`。
  登録漏れ＝旧ノートの状態残留バグ
- **Vault単位のControllerはノート単位の契約に登録しない**（登録すると、ノートを開き直しただけで一覧が消える）
- **Controllerの相似形を共通化しない。** 4度の判定を経て決着済みで、再提案には
  [system/architecture.md](../system/architecture.md) の再検討条件を満たすことを先に示す必要がある
