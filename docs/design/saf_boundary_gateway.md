# 設計思想 — SAF境界を gateway の裏へ入れる（`Uri` の不透明化）

**対象領域:** `model` の共有データ型が持つ `android.net.Uri`・`domain` の Android 依存・SAF操作の呼び出し境界
**初版:** 2026-08-01
**状態:** **全段階 実装済み**（いずれも 2026-08-01。段階1〜6は実機確認済み、**段階7は実機確認待ち**）。
`model` / `domain` / `controller` / `ui` から `android.net.Uri` が消え、`PackageDependencyTest` で固定済み。
**実装で当初案の誤りが2つ見つかった** — 段取り（下記§段取り）と、段階7の意義そのもの（下記§段階7）。

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

> **この節の後半は 2026-08-01 に訂正した。** 「Vaultルートを不透明化しても
> **テスト容易性は1ミリも上がらない**」と書いていたが、これは誤りだった。
> 正しくは「**不透明化するだけでは上がらない**」で、Vault・`ContentResolver`・
> `NoteRepository` の3つを同時に外せば上がる。詳細は §段階7。
> 結論（不透明化ではなく束ねる）は変わらないが、**理由が違うと次の判断を誤る。**

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
| 7 | Vaultルートを controller から外す | **実施した**（2026-08-01。ただし当初の想定とは中身が違う → §段階7） |

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

`PackageDependencyTest` に「**`model`・`domain`・`controller` は Android に依存しない**」を足した。
従来の依存テストは `com.example.newproject.*` の import しか見ておらず、
**`android.*` を数えていなかったのが穴の正体**である。

`controller` は段階7で加えた。`VaultBrowser` が最後の `Uri` を消したことで、
`NoteSessionCoordinator` のKDocにあった「Android API を呼ばない」という約束が
**import の不在として機械的に確かめられる**ようになったため。**約束のままにしない。**

`ui` を対象にしないのは Compose 自体が `androidx.*` だから。`data` とルート
（`NoteViewModel` / `MainActivity`）は SAF・`ContentResolver` を実際に扱う境界なので依存してよい。
`model`・`domain`・`controller` のそれぞれへ import を1行注入し、テストが落ちることを確認済み。

## 実測（`import android.net.Uri` を持つファイル数）

| 層 | 着手前 | 段階1〜6後 | 段階7後 |
|---|---:|---:|---:|
| `model` | 4 | **0** | **0** |
| `domain` | 1 | **0** | **0** |
| `ui` | 1 | **0** | **0** |
| `controller` | 3 | 3 | **0** |
| `data` | 7 | 6 | 7（`VaultBrowser` が増え、`NoteHistoryStore` が減った） |
| ルート | 1 | 1 | 1（Android境界の窓口。**正しい依存**） |
| 合計 | 17 | 10 | **8** |

**残る8はすべて意図した依存**で、`data`（SAF境界そのもの）とルート（Android境界の窓口）だけである。

## 段階7 — 「引数を1つ消す」ではなく「3つ同時に外す」だった（2026-08-01）

当初の処方は「Vault の解決を repository / gateway 側へ束ねて、controller の引数から消す」で、
**効果は依存の見た目が整うことだと考えていた**。着手前に測ったら、それでは何も起きないと分かった。

**Vault スコープの経路をJVMで検証できない壁は3つある。**

| # | 壁 | 着手前の実態 |
|---|---|---|
| 1 | `vaultUri(): Uri?` が非nullで要る | 全テストが `vaultUri = { null }` を渡していた |
| 2 | `NoteRepository` が具象クラスで差し替え口が無い | 全テストが実物の `NoteRepository()` を渡していた |
| 3 | 全公開メソッドが `contentResolver: ContentResolver` を取る | **どのテストも渡していない**（JVMで作れない） |

**1つだけ外しても、2と3で詰まって happy path には到達しない。** 当初案は1しか外さないので、
新しく書けるテストは0件だった。証拠はテスト側のコメントに残っていた —
`SearchControllerTest` は「検索の実行と `loadFolders` の世代照合は検証できない…実機確認で担保する」、
`NoteSessionCoordinatorTest` は `ContentResolver` を作れないため
**private フィールドへリフレクションで番兵を積んでいた**。

**3つを同時に外す形が [VaultBrowser] / [VaultHandle]。** `ContentResolver` と Vault ルートを
実装側で束ね、controller は「未選択なら null」だけを見る。

**これは新しい発明ではない。** 蒸留（`DistillPersistence` ＋ `SafDistillDocumentGateway`）と
読書痕跡（`ReadingTracePersistence` ＋ `SafReadingTraceDocumentGateway`）は既にこの形で、
どちらも `ContentResolver` を構築時に束ねているから Fake を書ける。
**7機能のうち2つだけが解けていて、残りが取り残されていた**（テーマ8: 横展開は最後の1本を取り残す）。

### 「Vault未選択」のポリシーは controller に残す

[VaultBrowser.current] が null を返すところまでが `data` の責任で、そのとき何を出すかは
呼び出し側が決める。**実際に挙動が違う。**

| 経路 | Vault未選択のとき |
|---|---|
| `SearchController`（列挙・検索・ランダム） | 黙って返る |
| `AnnotationController.create` | `AnnotationState.Error("Vault が選択されていません。")` |
| `AnnotationController.loadList` | `AnnotationListState.Error("Vault が選択されていません。")` |

下へ押し込むとこの差が消える。**共通化できるのは「未選択かどうか」までで、その先ではない。**

### ハンドルは1回だけ取る

処理の開始時に [VaultBrowser.current] を1回呼び、その1つを最後まで使う。
途中で引き直すと「照合は旧Vault・書き込みは新Vault」という食い違いが起こり得る。
これは `ReadingTraceStore` が既にKDocへ明記している規約（「`vaultUri()` を読むのは1回だけ。
読み直してはいけない」）と同じで、走行中の切替は `vaultGeneration` が弾く。

**例外はモデルDL完了後の再開だけ。** 完了は数分後になり得るので、その時点で引き直す（従来と同じ）。

### 得られたもの

- **JVMテスト +21件**（`SearchControllerTest` +11・`AnnotationControllerTest` +9・`NoteSessionCoordinatorTest` +1）（487→508）。
  世代照合3種・検索とランダムの実行・走査キャッシュのヒットと破棄・削除失敗の件数。
  **5つのガードを1つずつ削る変異で、対応するテストが落ちることを確認済み。**
- **リフレクション番兵が半分消えた。** 走査キャッシュの破棄は実際に走査させて回数で見る形になった。
  Jobの停止だけは番兵が残る（走行中のJobを外から観測する手段が無いため）。
- **`controller` から `android.*` が完全に消えた。** 使われなくなった `vaultUri` パラメータと
  `ContentResolver` の import も落ちた。`NoteSessionCoordinator` の「Android API を呼ばない」という
  KDocの約束が、**import の不在として確かめられる**ようになった。

### 教訓

**「処方」だけを設計書に書くと、効果の見積もりが検証されないまま残る。**
判断0は結論（不透明化ではなく束ねる）は正しかったが、理由（テスト容易性は上がらない）が誤りで、
処方の範囲（Vaultだけ外す）が不足していた。**着手前に「これで何が書けるようになるか」を
1件でも具体的に挙げれば、3つの壁のうち1つしか崩していないことに気づけた。**

## この設計が引き受けないこと

- **`Uri` を完全に消すことは目的ではない。** `data` とルートの `NoteViewModel` は SAF と
  `ContentResolver` を実際に扱う境界なので、そこに `Uri` があるのは正しい。
  **消すのではなく、境界の外側へ漏れないようにする**のが目的である。
- **SAF操作そのものの再設計はしない。** 既存の `NoteRepository` の走査・読み書きの実装には触れない。
  変えるのは「何を引数に取るか」だけに留める。
