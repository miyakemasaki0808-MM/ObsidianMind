package com.example.newproject.domain.image

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteImageFailure

// ---------------------------------------------------------------------------
// Vault画像索引の組み立てと照合。走査（SAF）は data 側が行い、ここは結果を
// 受け取って並べ替えるだけなので Android 型を持たない。
// ---------------------------------------------------------------------------

/** 走査で見つかった画像1件。[vaultRelativePath] はVaultルートからの相対パス。 */
data class NoteImageEntry(val vaultRelativePath: String, val ref: DocumentRef)

/**
 * Vault内の画像索引。
 *
 * **[isComplete] を持つのが要点。** 走査中に読めなかったフォルダがあると、
 * 「索引に無い」は「Vaultに無い」を意味しない。畳むと、一度読み損ねただけで
 * 「画像がありません」と**断定して**しまう（→ N-1 段階0 と同じ型の誤り）。
 */
internal class NoteImageIndex private constructor(
    private val byPath: Map<String, DocumentRef>,
    private val byFileName: Map<String, List<DocumentRef>>,
    val isComplete: Boolean
) {
    internal fun pathMatch(vaultPath: String): DocumentRef? = byPath[vaultPath]

    internal fun fileNameMatches(fileName: String): List<DocumentRef> =
        byFileName[fileName.lowercase()].orEmpty()

    internal companion object {
        internal val EMPTY_INCOMPLETE: NoteImageIndex =
            NoteImageIndex(emptyMap(), emptyMap(), isComplete = false)

        /**
         * 走査結果から索引を作る。
         *
         * **パスは要求側と同じ [normalizeVaultImagePath] を通す。** 片方だけ通すと
         * `a//b.png` のような揺れで同じファイルが別のキーになり、永久に一致しない。
         * ファイル名は小文字へ畳む（完全パスは区別し、ファイル名は区別しない仕様）。
         *
         * 同じ正規化パスが2件現れた場合は**先勝ち**にする。SAFの列挙順は
         * プロバイダ依存だが、正規化後に衝突するのは実質同一ファイルなのでどちらでもよい。
         */
        internal fun of(entries: List<NoteImageEntry>, isComplete: Boolean): NoteImageIndex {
            val byPath = LinkedHashMap<String, DocumentRef>(entries.size)
            val byFileName = LinkedHashMap<String, MutableList<DocumentRef>>()
            for (entry in entries) {
                val path = normalizeVaultImagePath(entry.vaultRelativePath)
                if (path.isEmpty()) continue
                byPath.putIfAbsent(path, entry.ref)
                byFileName.getOrPut(path.substringAfterLast('/').lowercase()) { mutableListOf() }
                    .add(entry.ref)
            }
            return NoteImageIndex(byPath, byFileName, isComplete)
        }
    }
}

/**
 * 画像1つの解決結果。
 *
 * 失敗の語彙は [NoteImageFailure]（`model`）を使う。**復号の失敗と同じ型にまとめる**のは、
 * 表示側が2つの型を突き合わせずに済むようにするため（→ note_image_rendering §9）。
 */
internal sealed interface ImageResolution {
    data class Resolved(val ref: DocumentRef) : ImageResolution

    /**
     * 解決できなかった。
     *
     * **曖昧なときに1つ選んで返さない。** それらしい答えを黙って出すと、
     * 誤りが誤りとして見えず報告もされない（→ note_image_rendering §4）。
     */
    data class Failed(val reason: NoteImageFailure) : ImageResolution
}

/**
 * 要求と索引を突き合わせる。
 *
 * **照合順は「正規化した完全パス一致 → ファイル名一致」の2段。**
 * 完全パスが当たったときはファイル名が曖昧でも確定させる（より強い根拠が先）。
 * ノート相対の解決は v1 では行わない（→ note_image_rendering §3）。
 */
internal fun resolveImage(request: ImageRequest, index: NoteImageIndex): ImageResolution =
    when (request) {
        is ImageRequest.External -> ImageResolution.Failed(NoteImageFailure.External(request.url))
        is ImageRequest.Empty -> ImageResolution.Failed(NoteImageFailure.Empty)
        is ImageRequest.Lookup -> {
            val exact = index.pathMatch(request.vaultPath)
            if (exact != null) {
                ImageResolution.Resolved(exact)
            } else {
                val candidates = index.fileNameMatches(request.fileName)
                when {
                    candidates.size == 1 -> ImageResolution.Resolved(candidates.single())
                    candidates.size > 1 ->
                        ImageResolution.Failed(NoteImageFailure.Ambiguous(candidates.size))
                    // 見つからないときだけ完全性を見る。見つかったなら不完全でも正しい。
                    !index.isComplete -> ImageResolution.Failed(NoteImageFailure.Unverifiable)
                    else -> ImageResolution.Failed(NoteImageFailure.NotFound)
                }
            }
        }
    }
