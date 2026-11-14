package com.example.newproject.ui

import com.example.newproject.model.RelatedReason

/**
 * 関連ノートの行に添える根拠の言い方（→ docs/dev/features/related_notes_ai.md §5）。
 * **数字を出さない** — スコアが見えると、順位への納得と同じだけ不満も生む。
 */
internal fun relatedReasonLabel(reason: RelatedReason): String = when (reason) {
    is RelatedReason.SharedTag -> "タグ #${reason.tag} が共通"
    RelatedReason.SimilarOpening -> "書き出しが近い"
    RelatedReason.SimilarTitle -> "題名が近い"
}
