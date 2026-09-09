package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.NoteFile

/**
 * 索引A（ノート → 分類）へ、走査で作ったヒントを流し込む。
 *
 * **走査は初回だけではない。** `collectAllNotesCached` はTTLを過ぎると走査し直すので、
 * この関数は**AIが確定を書いた後にも呼ばれる**。規則が無いと、
 * 再走査のたびに色がヒント側へ戻る（→ `docs/dev/features/note_field_color.md` 判断16）。
 *
 * ## 規則
 *
 * - **確定は暫定で上書きしない。** 暫定を置けるのは、有効な確定が無いノートだけ。
 * - **走査に居なくなったノートは落とす。** 索引がVaultから消えたノートを抱え続けると、
 *   相対パスを付け替えた別のノートへ古い分類が当たる余地が残る。
 * - **ヒントが作れないノートは載せない。** `Unknown` を明示的に置く必要は無く、
 *   引けなかったものが未判定である（表示側は既定で無彩色）。
 *
 * **確定同士の優先は、ここでは起きない。** この関数が書くのは暫定だけなので、
 * 更新元の優先規則（一括復元は補完だけ／個別の確定は置き換える）は
 * 確定を書く側が持つ（→ 判断16 の表）。
 *
 * @param current いまの索引A。
 * @param notes 走査で得たノート。**相対パスを持たないものはヒントを作れない。**
 * @return 走査に居るノートだけを持つ、新しい索引A。
 */
fun indexNoteFieldHints(
    current: Map<DocumentRef, NoteFieldClassification>,
    notes: List<NoteFile>
): Map<DocumentRef, NoteFieldClassification> {
    val next = LinkedHashMap<DocumentRef, NoteFieldClassification>(notes.size)
    for (note in notes) {
        val existing = current[note.ref]
        if (existing is NoteFieldClassification.Confirmed) {
            // 確定は残す。**入力版が有効かどうかはここでは見ない** —
            // 本文ハッシュを持たないので判定できず、判定できないものを捨てると
            // 「走査のたびにAIをやり直す」になる。失効はAIを呼ぶ側が入力版で見る。
            next[note.ref] = existing
            continue
        }
        val hint = noteFieldHint(note.vaultRelativePath) ?: continue
        next[note.ref] = NoteFieldClassification.Provisional(hint)
    }
    return next
}
