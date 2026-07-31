# 設計思想 — SAF境界を gateway の裏へ入れる（`Uri` の不透明化）

**対象領域:** `model` の共有データ型が持つ `android.net.Uri`・`domain` の Android 依存・SAF操作の呼び出し境界
**初版:** 2026-08-01
**状態:** **設計のみ。未実装。** 影響範囲の実測と段取りまで確定し、着手の判断待ち。

---

## 背景

2026-07-27 に依存方向を `model` が葉になる形で確定し、`PackageDependencyTest` でCIに固定した。
ただし固定したのは**プロジェクト内パッケージ間の向き**だけで、**Androidフレームワークへの依存は対象外**だった。
結果として `model` は「プロジェクト内の何も import しない」が「`android.net.Uri` は import する」状態で残っている。

実害は3つ。

1. **検索実行・補記保存の世代照合がJVMテストに落とせない。** 素のJVMでは `Uri` がスタブで例外を投げるため、
   `Uri` を持つ型を組み立てられない。担保が実機確認だけになっている。
2. **`domain` が Android に依存している。** `RelatedNotesUseCase` が `Uri` をキャッシュキーと
   読み込みトークンに使っており、層の位置づけ（純粋ロジック）と実態がずれている。
3. **同じ形の gateway が既に2つあるのに、中核の型だけ取り残されている**
   （`AnnotationDocumentGateway`・`ReadingTraceDocumentGateway`）。

## 判断0: `Uri` は2種類あり、直し方が違う

**着手前にこれを分けないと、片方に効かない対策を全体へ広げることになる。**

| 種類 | 実体 | 今の持ち主 | 使われ方 | 直し方 |
|---|---|---|---|---|
| **Vault ルート** | tree Uri | `SearchController` / `AnnotationController` の `vaultUri: () -> Uri?` | **自分では解釈せず `repository` へ素通しするだけ** | **不透明化しない。controller から消す** |
| **ドキュメント** | 1ノートの document Uri | `NoteFile.uri`・`RelatedNote.uri`・`HistoryEntry.uri`・`AnnotationState.savedUri` | **キーとして比較・重複排除に使う**（`distinctBy`・`!=`・`in`） | **不透明な参照型にする** |

**Vault ルートを不透明化しても何も良くならない。** controller は依然として
「自分で解釈しないトークンを引き回す」ままで、テスト容易性は1ミリも上がらず、
変換の手間だけが増える。ここで必要なのは型を変えることではなく、
**Vault の解決を repository / gateway 側へ束ねて、controller の引数から消す**ことである。

逆にドキュメント側は、controller と domain が**中身を見ずキーとしてだけ**使っている。
つまり既に不透明に扱われており、型がそれに追いついていないだけである。

## 判断1: 参照は `model` に置く `DocumentRef`（value class）

```kotlin
// model/DocumentRef.kt — 葉なので何も import しない
@JvmInline
value class DocumentRef(val value: String)
```

先行する2つの gateway は参照を**生の `String`** で扱っている。そこでは局所的な受け渡しなので
それで足りていたが、今回は `model` の4型の**フィールド**になるため、`String` のままだと
`title` や `vaultRelativePath` と取り違えられる。`@JvmInline value class` なら実行時コストは無い。

- **変換（`Uri` ↔ `DocumentRef`）は `data` だけが行う。** 置き場は `data/SafDocuments.kt`。
- **等価性は文字列比較になる。** 現在の `it.uri != uri` / `distinctBy { it.uri }` /
  `it.uri in relatedUris` は `Uri.equals`（実質は文字列比較）なので、**挙動は変わらない**。
  ただしこれは「変わらないはず」であって、**入れ替えの前後で重複排除の結果が同じことを
  テストで確かめてから進む**（→ [lessons.md](../lessons.md) L2 の型）。
- **永続化のスキーマ変更は不要。** `NoteHistoryStore` は既に `uri.toString()` でJSONへ書いており、
  蒸留も `targetUri = uri.toString()` と `String` で持っている。**アプリ内部に既にある文字列表現へ
  型を付け直すだけ**で、保存済みデータとの互換は保たれる。

## 判断2: 完了条件を `PackageDependencyTest` の拡張に置く

現在このテストは `com.example.newproject.*` の import しか見ていない。
**`model` と `domain` は `android.*` を import してはいけない**という規則を足す。

これを入れるまで N-7 は完了と呼ばない。**「Uri を追い出した」は主張だが、
「Uri が戻ってこない」はテストにしかできない**（→ [lessons.md](../lessons.md) L2）。
`data`・`controller`・`ui`・ルートは Android に依存してよいので対象外。

## 影響範囲（2026-08-01 実測）

**`import android.net.Uri` を実際に持つのは17ファイル**（`grep "^import android.net.Uri"` で計測）。

| 層 | ファイル数 | 内訳 | 備考 |
|---|---:|---|---|
| `model` | **4** | `NoteTypes`・`RelatedNote`・`HistoryEntry`・`state/AnnotationState` | **全廃が目標** |
| `domain` | **1** | `RelatedNotesUseCase` | **全廃が目標** |
| `controller` | 3 | `SearchController`・`AnnotationController`・`NoteSessionCoordinator` | 残るのは判断0のVaultルートのみ |
| `data` | 7 | `NoteRepository`・`SafDocuments`・`VaultLocation`・`NoteSnapshot`・`NoteHistoryStore`・`ReadingTraceStore`・`DistillWriteRepository` | **正しい依存。残す**（SAF境界そのもの） |
| `ui` | 1 | `AnnotationManagerScreen` | 削除コールバックの引数 |
| ルート | 1 | `NoteViewModel` | **正しい依存。残す**（Android境界の窓口） |

> **`KeyedMemoCache` は数えない。** `android.net.Uri` の文字列は出てくるが、
> **KDocの本文で「実キーに依存しない総称実装にした」と説明しているだけ**で import は無い。
> 初版はここを `grep -l` で数えて `domain` を2ファイルと書いていた。
> **`grep` でファイルを数えるときは、コメントと import を区別する**（`^import` で固定する）。

`.uri` の消費は約34箇所で、**`NoteViewModel` に17・`RelatedNotesUseCase` に8**と偏っている。
残りは1〜2箇所ずつなので、**重いのは実質2ファイル**。

## 段取り — 葉から順に、1段階ずつ

**一度に全部やらない。** 中核の型に触るので、途中で壊れたときに原因を絞れる形にする。

| # | 段階 | 効果が見える形 |
|---|---|---|
| 1 | `DocumentRef` を `model` へ追加、`data` に変換関数を置く | 既存型は変えない。**何も壊れない** |
| 2 | `HistoryEntry` を置き換える | `NoteHistoryStore` のJSON入出力が**JVMテストで書けるようになる**。最小で効果が目に見える |
| 3 | `RelatedNote` を置き換える | `SearchController` の世代照合がJVMテストへ落ちる |
| 4 | `NoteFile` を置き換える | **本体。** `NoteRepository`・`RelatedNotesUseCase`・`NoteViewModel` |
| 5 | `AnnotationState.savedUri` を置き換える | `model/state` から Android が消える |
| 6 | `PackageDependencyTest` に `android.*` 禁止を足す | **ここで初めて完了が固定される** |
| 7 | Vault ルートを controller の引数から外す（判断0の1行目） | **別PR。** ここまでとは独立した変更 |

段階2を先頭に置くのは、**最小の型で「この形が実際に効く」ことを確かめてから中核へ入る**ため。
`HistoryEntry` は2フィールドしか無く、消費側も `SearchScreen` と `NoteViewModel` の2箇所しかない。
ここで想定外（等価性・永続化・テストの書きにくさ）が出れば、`NoteFile` に触る前に方針を戻せる。

## この設計が引き受けないこと

- **`Uri` を完全に消すことは目的ではない。** `data` とルートの `NoteViewModel` は SAF と
  `ContentResolver` を実際に扱う境界なので、そこに `Uri` があるのは正しい。
  **消すのではなく、境界の外側へ漏れないようにする**のが目的である。
- **SAF操作そのものの再設計はしない。** 既存の `NoteRepository` の走査・読み書きの実装には触れない。
  変えるのは「何を引数に取るか」だけに留める。
