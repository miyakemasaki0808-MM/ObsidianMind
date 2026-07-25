package com.example.newproject

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

/** 走査で見つかった .md 1件。[vaultRelativePath] はVaultルートからの相対パス。 */
internal data class MarkdownEntry(
    val documentId: String,
    val name: String,
    val vaultRelativePath: String,
    val lastModified: Long?
)

/** ルート直下は名前のみ、それ以下は "親/名前" で繋ぐ。 */
internal fun joinVaultPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/**
 * [startId] 配下をBFSで走査して .md を集め、それぞれにVaultルートからの相対パスを付ける。
 * [excludeFolderNames] に一致するフォルダは潜らない。
 *
 * [startPath] は [startId] 自身までの相対パス。Vaultルートから始めるなら空文字、
 * サブフォルダを起点にするならそのフォルダ名を渡す。
 *
 * documentId の訪問済み集合を持つのは、プロバイダが循環を返した場合に
 * 相対パスとメモリが無限に伸びるのを防ぐため（パスを持たない従来の走査では
 * 問題にならなかった）。
 */
internal fun traverseMarkdownPaths(
    startId: String,
    startPath: String = "",
    excludeFolderNames: Set<String> = emptySet(),
    listChildren: (String) -> List<ChildDoc>
): List<MarkdownEntry> {
    val result = mutableListOf<MarkdownEntry>()
    val visited = mutableSetOf(startId)
    val queue = ArrayDeque<Pair<String, String>>()
    queue.add(startId to startPath)
    while (queue.isNotEmpty()) {
        val (documentId, path) = queue.removeFirst()
        for (child in listChildren(documentId)) {
            when {
                child.isDirectory ->
                    if (child.name !in excludeFolderNames && visited.add(child.documentId)) {
                        queue.add(child.documentId to joinVaultPath(path, child.name))
                    }
                isMarkdownFile(child.name) ->
                    result.add(
                        MarkdownEntry(
                            documentId = child.documentId,
                            name = child.name,
                            vaultRelativePath = joinVaultPath(path, child.name),
                            lastModified = child.lastModified
                        )
                    )
            }
        }
    }
    return result
}
