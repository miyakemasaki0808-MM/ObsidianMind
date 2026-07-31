# 設計思想 — SAF境界を gateway の裏へ入れる（`Uri` の不透明化）

**対象領域:** `model` の共有データ型が持つ `android.net.Uri`・`domain` の Android 依存・SAF操作の呼び出し境界
**初版:** 2026-08-01
**状態:** **段階1〜6 実装済み**（2026-08-01）。`model` / `domain` / `ui` から `android.net.Uri` が消え、`PackageDependencyTest` で固定済み。**残るのは段階7（Vaultルートを controller の引数から外す）のみ。**
**実装で段取りの誤りが1つ見つかり、下記§段取りに追記した。**

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

## 段取り — 実装して分かったこと（**当初案は誤りだった**）

当初は「葉から1段階ずつ、2段目に `HistoryEntry` 単独」という7段階を組んだ。
**これは実行できない。** 依存規約と噛み合わないことが着手して初めて分かった。

| # | 当初の想定 | 実際 |
|---|---|---|
| 1 | `DocumentRef` を追加、`data` に変換関数 | **そのとおり**（`2956735`） |
| 2 | `HistoryEntry` だけを置き換える | **不可能。** 3型を同時に移すしかない |
| 3〜5 | `RelatedNote` → `NoteFile` → `AnnotationState` と順に | **2と同時に実施**（`e7d61ee`） |
| 6 | `PackageDependencyTest` を拡張 | そのとおり（同コミット） |
| 7 | Vaultルートを controller から外す | **未着手** |

**なぜ部分移行が不可能だったか。** 構築箇所が層をまたいで連鎖している。

```
data/NoteRepository ──生成──> NoteFile
                                 │
        domain/RelatedNotesUseCase ┴─組み立て──> RelatedNote
        domain/SearchPickerUseCase ─組み立て──> RelatedNote
        controller/SearchController ─組み立て──> RelatedNote
        ui/SearchScreen ─── HistoryEntry から組み立て ──> RelatedNote
```

`HistoryEntry` だけを `DocumentRef` にすると、`ui/SearchScreen` が
`RelatedNote(uri = entry.uri)` を組み立てられなくなり、`entry.ref` → `Uri` の変換が要る。
`RelatedNote` だけを移せば、今度は `domain/RelatedNotesUseCase` が `NoteFile.uri` から
組み立てられなくなり、同じく変換が要る。**ところが `ui` と `domain` は依存規約上
`data` を import できず、変換関数に手が届かない。**

つまり **「変換を `data` に閉じる」という判断1そのものが、部分移行を禁じていた。**
3型を同時に移せば、どの構築箇所も `ref` をそのまま写すだけになり、変換は1つも要らない。

**教訓: 段取りを「型の大きさ」で切ったのが誤りだった。** `HistoryEntry` は2フィールドで
最小だから安全だろう、と考えたが、**移行の単位を決めるのはサイズではなく「その型が
どの層で組み立てられるか」**である。同じ変換関数に手が届く範囲が1単位になる。

## 実装で得られた副次的な効果

**往復変換が2つ消えた。** どちらも「不透明な文字列を一度 `Uri` にしてから使う」形で、
`DocumentRef` を入れたことで無駄が可視化された。

- `SavedAnnotation` は `AnnotationDocumentGateway` が返す不透明文字列を
  `toUri()` してから保持していた。`DocumentRef` を直接持たせて往復をやめた。
- `reloadNoteBody` は `Uri.toString()` 由来の `targetUri`（`String`）を
  `toUri()` して渡していた。そのまま `DocumentRef` へ包むようにした。

**テスト側のハックが1つ消えた。** `NoteSessionCoordinatorTest` には
`listOf("旧Vaultの履歴") as List<HistoryEntry>` という強制キャストがあり、
「`HistoryEntry` は `Uri` を要るので作れない」というコメントが添えられていた。
**この refactor が狙っていた痛みが、そのままコメントとして残っていた**ことになる。
実物を組み立てる形に直した。

## 完了の固定

`PackageDependencyTest` に「`model` と `domain` は Android に依存しない」を足した。
従来の依存テストは `com.example.newproject.*` の import しか見ておらず、
**`android.*` を数えていなかったのが穴の正体**である。

`ui` を対象にしないのは Compose 自体が `androidx.*` だから。`data` とルートは
SAF・`ContentResolver` を実際に扱う境界なので依存してよい。
`model` と `domain` のそれぞれへ import を1行注入し、テストが落ちることを確認済み。

## 実測（2026-08-01・移行後）

| 層 | 移行前 | 移行後 |
|---|---:|---:|
| `model` | 4 | **0** |
| `domain` | 1 | **0** |
| `ui` | 1 | **0** |
| `controller` | 3 | 3（Vaultルートのみ。段階7の対象） |
| `data` | 7 | 6（`NoteHistoryStore` が `Uri` から解放された） |
| ルート | 1 | 1（Android境界の窓口。**正しい依存**） |
| 合計 | 17 | **10** |

## この設計が引き受けないこと

- **`Uri` を完全に消すことは目的ではない。** `data` とルートの `NoteViewModel` は SAF と
  `ContentResolver` を実際に扱う境界なので、そこに `Uri` があるのは正しい。
  **消すのではなく、境界の外側へ漏れないようにする**のが目的である。
- **SAF操作そのものの再設計はしない。** 既存の `NoteRepository` の走査・読み書きの実装には触れない。
  変えるのは「何を引数に取るか」だけに留める。
