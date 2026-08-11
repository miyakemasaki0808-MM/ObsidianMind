# SAF境界を gateway の裏へ入れる（`Uri` の不透明化）

**状態:** 実装済み・実機確認済み・稼働中
**最終検証:** 2026-08-11 / `9af63ee`（層ごとの `import android.net.Uri` を再計測し、`PackageDependencyTest` の対象と突合）
**関連コード:** `model/DocumentRef.kt` / `data/SafDocuments.kt`（唯一の変換点）/ `data/VaultBrowser.kt`・`VaultLocation.kt`
**関連テスト:** `PackageDependencyTest`（CIで固定）/ `SearchControllerTest` / `AnnotationControllerTest` / `NoteSessionCoordinatorTest`
**正本:** この文書

**対象領域:** SAF操作の呼び出し境界と、`model` / `domain` / `controller` を Android 非依存に保つ規約

---

## 1. 現在の状態

**`import android.net.Uri` を持つのは `data`（7ファイル）とルートの `NoteViewModel` だけ。**
`model` / `domain` / `controller` / `ui` はゼロで、`PackageDependencyTest` がCIで固定している。

| 層 | `Uri` | 位置づけ |
|---|:--:|---|
| `model` `domain` `controller` | **0** | **テストで禁止**。素のJVMで扱えなければならない |
| `ui` | 0 | 禁止対象外（Compose 自体が `androidx.*`）だが実際に0 |
| `data` | 7 | **正しい依存。** SAF境界そのもの |
| ルート（`NoteViewModel`） | 1 | **正しい依存。** Android境界の窓口 |

## 2. 判断1: 変換は `data` だけが行う

`Uri` ↔ `DocumentRef` の変換点は `data/SafDocuments.kt` の1箇所だけ。

- **等価性は文字列比較になる。** 従来の `Uri.equals` も実質は文字列比較なので挙動は変わらない。
  ただしこれは「変わらないはず」であって、**重複排除の結果が同じことをテストで確かめる**（→ [lessons.md](../lessons.md) L2 の型）
- **永続化のスキーマ変更は不要。** `NoteHistoryStore` は既に `uri.toString()` でJSONへ書き、蒸留も `String` で持っていた。
  **アプリ内部に既にある文字列表現へ型を付け直すだけ**

> **この判断が、部分移行を構造的に禁じている。** `NoteFile` / `RelatedNote` / `HistoryEntry` は
> 構築箇所が層をまたいで連鎖しており、1型だけ移すと `ui` や `domain` が変換を必要とする。
> ところが**その2層は依存規約上 `data` を import できず、変換関数に手が届かない。**
> 3型を同時に移せば、どの構築箇所も `ref` をそのまま写すだけになり変換は1つも要らない。
>
> **移行の単位を決めるのは型のサイズではなく「その型がどの層で組み立てられるか」である。**
> 同じ変換関数に手が届く範囲が1単位になる。

## 3. 判断2: 「葉である」は素のJVMで扱えるかで定義する

従来の依存テストは `com.example.newproject.*` の import しか見ておらず、**`android.*` を数えていなかった。**
そのため `model` は「プロジェクト内の何も import しない」を満たしながら `android.net.Uri` だけは import する状態で残り、
**`model` の型を素のJVMテストで組み立てられなかった**（`Uri` はユニットテストではスタブで、触ると例外を投げる）。

**テスト容易性の観点では、プロジェクト内の依存も外部フレームワークへの依存も等しく
「その層を素のJVMで扱えなくする」ので、同じ規則で数える。**

**「Uri を追い出した」は主張だが、「Uri が戻ってこない」はテストにしかできない。**
`PackageDependencyTest` の検査は2点で厳しくしてある。

- **import だけを見ない。** `android.net.Uri.parse(...)` のような完全修飾名は import を伴わず素通りするので、
  `android.` / `androidx.` の出現を全て拾う
- **コメントを落としてから拾う。** `DocumentRef` と `KeyedMemoCache` のKDocが
  **「`android.net.Uri` を持たない理由」の説明として同じ文字列を含む**ため

`model`・`domain`・`controller` のそれぞれへ import を1行注入し、テストが落ちることを確認済み。

## 4. 判断3: 引数の素通しをやめ、`VaultBrowser` の裏へ束ねる

**Vaultスコープの経路をJVMで検証できない壁は3つあり、1つだけ外しても happy path に到達しない。**

| # | 壁 |
|---|---|
| 1 | `vaultUri(): Uri?` が非nullで要る（全テストが `null` を渡していた） |
| 2 | `NoteRepository` が具象クラスで差し替え口が無い |
| 3 | 全公開メソッドが `ContentResolver` を取る（**どのテストも渡していない** — JVMで作れない） |

**3つを同時に外す形が `VaultBrowser` / `VaultHandle`。** `ContentResolver` と Vault ルートを実装側で束ね、
controller は「未選択なら null」だけを見る。

**これは新しい発明ではない。** 蒸留（`DistillPersistence` ＋ `SafDistillDocumentGateway`）と
読書痕跡（`ReadingTracePersistence` ＋ `SafReadingTraceDocumentGateway`）は既にこの形だった。
**7機能のうち2つだけが解けていて、残りが取り残されていた。**

> **「引数として受け取って素通しする」は、依存を消したことにならない。**
> 型として残っている限りテストはその型を作らねばならず、作れなければその経路は検証できない。

### 「Vault未選択」のポリシーは controller に残す

`VaultBrowser.current` が null を返すところまでが `data` の責任で、**そのとき何を出すかは呼び出し側が決める。**

| 経路 | Vault未選択のとき |
|---|---|
| `SearchController`（列挙・検索・ランダム） | 黙って返る |
| `AnnotationController.create` / `loadList` | `Error("Vault が選択されていません。")` |

下へ押し込むとこの差が消える。**共通化できるのは「未選択かどうか」までで、その先ではない。**

### ハンドルは1回だけ取る

処理の開始時に `VaultBrowser.current` を**1回**呼び、その1つを最後まで使う。
途中で引き直すと**「照合は旧Vault・書き込みは新Vault」**という食い違いが起こり得る。
走行中の切替は `vaultGeneration` が弾く。

**例外はモデルDL完了後の再開だけ** — 完了は数分後になり得るので、その時点で引き直す。

## 5. この設計が引き受けないこと

- **`Uri` を完全に消すことは目的ではない。** `data` とルートの `NoteViewModel` は SAF と `ContentResolver` を
  実際に扱う境界なので、そこに `Uri` があるのは正しい。**消すのではなく、境界の外側へ漏れないようにする**
- **SAF操作そのものの再設計はしない。** 走査・読み書きの実装には触れず、変えるのは「何を引数に取るか」だけ

## 6. 残した教訓

**「処方」だけを設計書に書くと、効果の見積もりが検証されないまま残る。**
当初の判断は結論（不透明化ではなく束ねる）は正しかったが、**理由が誤りで処方の範囲が不足していた** —
「Vaultだけ外す」では上記3つの壁のうち1つしか崩せず、新しく書けるテストは0件だった。

**着手前に「これで何が書けるようになるか」を1件でも具体的に挙げる。** それだけで範囲不足に気づける。

## 7. 開発経緯

[開発日誌 2026-08](../../owner/journal/2026-08.md)
