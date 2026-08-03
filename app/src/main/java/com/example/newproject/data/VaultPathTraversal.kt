package com.example.newproject.data

// ---------------------------------------------------------------------------
// Vault走査の純粋部分。Android型を一切持たないため、素のJVMユニットテストで
// 「ネストしたノートの相対パスが正しく組まれるか」を検証できる。
// SAFのカーソル読み出し（queryChildren）とUri構築だけを NoteRepository に残す。
// ---------------------------------------------------------------------------

/** SAFの子要素1件。カーソル列の詰め替え先。 */
internal data class ChildDoc(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long?
)

/**
 * SAFの子一覧と、その列挙が成功したかどうか。
 *
 * **[isComplete] が false のとき [items] が空でも「子が無い」を意味しない。**
 * 素の `List` で返すと、この2つが同じ値へ潰れる（`query()` が null を返した場合と
 * 本当に空フォルダだった場合が区別できない）。潰すと、不在を根拠にする処理
 * — 孤児判定・索引作成 — が誤った結論を出す。
 *
 * 表示系は [items] だけ見て構わない（読めた分で動き続けるのが仕様）。
 * [isComplete] を見る義務があるのは、**不在から何かを結論する側**だけ。
 */
internal data class SafChildren(
    val items: List<ChildDoc>,
    val isComplete: Boolean
) {
    internal companion object {
        fun complete(items: List<ChildDoc>): SafChildren = SafChildren(items, isComplete = true)

        /** 列挙そのものが失敗した。空フォルダとは異なる。 */
        val UNREADABLE: SafChildren = SafChildren(emptyList(), isComplete = false)
    }
}

/**
 * 走査結果。[unreadableFolderPaths] は**列挙に失敗したフォルダ**のVault相対パス
 * （走査の起点そのものが読めなければ起点のパス。ルート起点なら空文字）。
 *
 * 失敗を全体の1フラグに畳まずパス集合で持つのは、孤児判定が
 * 「読めなかったサブツリー配下だけ候補から外す」と精密に書けるようにするため。
 * 1フラグにすると、どこか1フォルダを読み損ねただけで掃除が全面停止する。
 */
internal data class VaultFileScan(
    val entries: List<VaultFileEntry>,
    val unreadableFolderPaths: Set<String>
)

/** 走査で見つかったファイル1件。[vaultRelativePath] はVaultルートからの相対パス。 */
internal data class VaultFileEntry(
    val documentId: String,
    val name: String,
    val vaultRelativePath: String,
    val lastModified: Long?
)

/** ルート直下は名前のみ、それ以下は "親/名前" で繋ぐ。 */
internal fun joinVaultPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/**
 * [startId] 配下をBFSで走査して .md を集める。詳細は [traverseVaultFiles]。
 *
 * **受理条件だけを固定した [traverseVaultFiles] の別名**である。画像索引が
 * 同じ走査を別の条件で使うため、歩き方と受理条件を分けた。
 */
internal fun traverseMarkdownPaths(
    startId: String,
    startPath: String = "",
    excludeFolderNames: Set<String> = emptySet(),
    listChildren: (String) -> SafChildren
): VaultFileScan = traverseVaultFiles(
    startId = startId,
    startPath = startPath,
    excludeFolderNames = excludeFolderNames,
    accept = ::isMarkdownFile,
    listChildren = listChildren
)

/**
 * [startId] 配下をBFSで走査し、[accept] が真を返したファイルを集めて
 * それぞれにVaultルートからの相対パスを付ける。
 * [excludeFolderNames] に一致するフォルダは潜らない。
 *
 * [startPath] は [startId] 自身までの相対パス。Vaultルートから始めるなら空文字、
 * サブフォルダを起点にするならそのフォルダ名を渡す。
 *
 * documentId の訪問済み集合を持つのは、プロバイダが循環を返した場合に
 * 相対パスとメモリが無限に伸びるのを防ぐため（パスを持たない従来の走査では
 * 問題にならなかった）。
 *
 * **[accept] を引数にしたのは画像索引のため。** 歩き方（BFS・除外・循環対策・
 * 完全性の記録）はノートと画像で完全に同じで、違うのは受理条件だけだった。
 * 走査を2つ書くと、片方だけ直す形の失敗（→ lessons L14）が確実に起きる。
 */
internal fun traverseVaultFiles(
    startId: String,
    startPath: String = "",
    excludeFolderNames: Set<String> = emptySet(),
    accept: (String) -> Boolean,
    listChildren: (String) -> SafChildren
): VaultFileScan {
    val result = mutableListOf<VaultFileEntry>()
    val unreadable = mutableSetOf<String>()
    val visited = mutableSetOf(startId)
    val queue = ArrayDeque<Pair<String, String>>()
    queue.add(startId to startPath)
    while (queue.isNotEmpty()) {
        val (documentId, path) = queue.removeFirst()
        val children = listChildren(documentId)
        // 読めなかったフォルダは、その時点のパスで記録して走査は続ける。
        // 中断しないのは、他の枝で見つかったノートは正しいため（表示系はそれで動く）。
        if (!children.isComplete) unreadable.add(path)
        for (child in children.items) {
            when {
                child.isDirectory ->
                    if (child.name !in excludeFolderNames && visited.add(child.documentId)) {
                        queue.add(child.documentId to joinVaultPath(path, child.name))
                    }
                accept(child.name) ->
                    result.add(
                        VaultFileEntry(
                            documentId = child.documentId,
                            name = child.name,
                            vaultRelativePath = joinVaultPath(path, child.name),
                            lastModified = child.lastModified
                        )
                    )
            }
        }
    }
    return VaultFileScan(entries = result, unreadableFolderPaths = unreadable)
}
