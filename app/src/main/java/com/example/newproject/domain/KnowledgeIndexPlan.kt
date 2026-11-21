package com.example.newproject.domain

import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteScanKind

// ---------------------------------------------------------------------------
// 走査の結果と知識索引を突き合わせ、読み直すノートと消すノートを決める（→ docs/dev/system/knowledge_index.md §3）。
// ---------------------------------------------------------------------------

/** 索引にあるノートの、突き合わせに要る分。[lastModified] は読んだときの更新日時で、空でありうる。 */
internal data class IndexedNoteState(val lastModified: Long?, val fingerprint: String)

/** [forgetMarks] は、読み切れた走査に無い参照の控え（消えたノート・前の Vault の参照）で、もう読むことが無いので外す。 */
internal data class ReconcilePlan(
    val toRead: List<NoteFile>,
    val toDelete: List<String>,
    val forgetMarks: List<String> = emptyList()
) {
    companion object {
        val NONE = ReconcilePlan(emptyList(), emptyList())
    }
}

/**
 * 読み直すノートと、索引から消すノートを決める。
 *
 * - **読めずに空の走査では何もしない。** 準備完了と見なさない
 * - 読み直すのは、索引に無い・更新日時が違う・**更新日時が走査でも索引でも空**・**アプリが書き換えたと控えてある**ノート。
 *   日時が同じことを「変わっていない」と読んでよいのは、両方の日時が空でないときだけ
 * - 消すのは**読み切れた走査**に無いノートだけ。一部を読めない走査で消すと、一時的に読めないノートの索引を失う
 * - 相対パスを持たないノートは鍵が無いので扱わない
 *
 * [rewritten] はアプリが本文を書き換えたと控えてある**参照**。控えは参照で持つ — 索引に無いノートでも控えられ、
 * 初めて読んでいる途中の書き換えを番号の照合で止められる（→ knowledge_index.md §3 の手順4）。
 */
internal fun planReconcile(
    notes: List<NoteFile>,
    kind: NoteScanKind,
    indexed: Map<String, IndexedNoteState>,
    rewritten: Set<String>
): ReconcilePlan {
    if (kind == NoteScanKind.UNREADABLE_EMPTY) return ReconcilePlan.NONE
    val scanned = notes.filter { it.vaultRelativePath.isNotEmpty() }
    val toRead = scanned.filter { note ->
        val path = note.vaultRelativePath
        val previous = indexed[path]
        when {
            previous == null -> true
            note.ref.value in rewritten -> true
            note.lastModified == null && previous.lastModified == null -> true
            else -> note.lastModified != previous.lastModified
        }
    }
    if (kind != NoteScanKind.COMPLETE) return ReconcilePlan(toRead, emptyList())
    val present = scanned.mapTo(HashSet()) { it.vaultRelativePath }
    val presentRefs = notes.mapTo(HashSet()) { it.ref.value }
    return ReconcilePlan(
        toRead = toRead,
        toDelete = indexed.keys.filter { it !in present }.sorted(),
        forgetMarks = rewritten.filter { it !in presentRefs }.sorted()
    )
}

/**
 * 読んだ結果を索引へ書いてよいか。**読み始めたときの書き換えの番号が、書くときもそのパスの最新であるときだけ**（→ knowledge_index.md §3）。
 *
 * 番号は控えが無ければ null。読み始めたときに無く書くときにあれば、読んでいる途中にアプリが書き換えたので書かない。
 * 番号が進んでいても書かない。**書かなかったら控えも外さない** — 次の回で新しい本文を読む。
 */
internal fun mayApplyIndexedRead(writeNumberAtRead: Long?, writeNumberNow: Long?): Boolean =
    writeNumberAtRead == writeNumberNow
