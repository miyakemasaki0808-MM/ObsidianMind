package com.example.newproject.model

/**
 * Vault内の1ドキュメントを指す**不透明な参照**。
 *
 * ## なぜ `android.net.Uri` を直接持たないか
 *
 * `model` は依存グラフの葉で、プロジェクト内の何も import しない決まりだった。
 * ところが `android.net.Uri` だけは例外的に入り込んでおり、**`model` の型を
 * 素のJVMテストで組み立てられない**状態が続いていた（`Uri` はユニットテストでは
 * スタブで、触ると例外を投げる）。その結果、検索実行や補記保存の世代照合は
 * 実機確認だけが担保になっていた。
 *
 * ここでは**中身を解釈しない**。等価性・重複排除・キャッシュキーとしてしか使わず、
 * SAFの実体（`Uri`）への変換は `data` パッケージだけが行う。先行する2つの gateway
 * （`AnnotationDocumentGateway` / `ReadingTraceDocumentGateway`）が参照を生の
 * `String` で扱っているのと同じ考え方だが、こちらは `model` の型のフィールドに
 * なるため、`title` や `vaultRelativePath` と取り違えないよう型を付けてある。
 *
 * [value] は `Uri.toString()` の結果である。**アプリ内部に既にある文字列表現へ
 * 型を付け直しただけ**なので、`NoteHistoryStore` の保存済みJSONとも互換が保たれる。
 * 中身の形式に依存した処理をここへ足さないこと（足した瞬間に不透明でなくなる）。
 */
@JvmInline
value class DocumentRef(val value: String)
