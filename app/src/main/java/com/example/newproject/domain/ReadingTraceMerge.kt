package com.example.newproject.domain

import com.example.newproject.model.READING_TRACE_SCHEMA_VERSION
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.Reflection

// ---------------------------------------------------------------------------
// 読み戻しの突き合わせ規則。Android型を持たない純粋部分なので素のJVMテストで固定する。
// 規則の正本は features/reading_trace_backup.md §5。
//
// **この機能で最も壊れやすいのはここ**である。列挙・読み書き・版管理・checksum は
// 既存の部品をそのまま使うので、新しく間違えられる余地は突き合わせにしか無い。
//
// 貫いている原則は1つ。**再生成できないものを最優先で守る。**
// 訪問履歴とユーザーの返事、そして「まだ考えたい」の印は作り直せない。
// AI俯瞰要約は訪問履歴から作り直せるので、噛み合わなければ捨ててよい。
// ---------------------------------------------------------------------------

/**
 * 端末側に無かった痕跡をそのまま受け入れる。
 *
 * **[ReadingTrace.documentId] は必ず落とす。** これは端末／権限グラントごとに変わる
 * 高速引き当てキャッシュで、別端末はもちろん**同じ端末の再インストール後でも無効**。
 * 残すと、次に開いたときに存在しない参照を先に引きにいくことになる。
 */
internal fun adoptImportedTrace(imported: ReadingTrace): ReadingTrace =
    imported.copy(documentId = null, schemaVersion = READING_TRACE_SCHEMA_VERSION)

/**
 * 同じノートの痕跡が端末側と退避側の両方にあるとき、**欄ごとに**マージする。
 *
 * 丸ごと上書きしないのは、失う可能性のある欄を [ReadingTrace.reflection] だけに
 * 絞れるため。訪問は結合でき、累計は規則で決まり、AI要約は作り直せる。
 *
 * @param local 端末側。**[ReadingTrace.documentId] はこちらを保つ**（退避側の値は無効）。
 */
internal fun mergeReadingTraces(local: ReadingTrace, imported: ReadingTrace): ReadingTrace {
    // 時刻で重複排除して結合する。**同じ時刻は端末側を残す** — 同一の閲覧が
    // 両方に書かれている場合で、どちらも同じ読書を指しているため差が出ない。
    val visits = (local.visits + imported.visits)
        .distinctBy { it.atEpochMillis }
        .sortedBy { it.atEpochMillis }
        .takeLast(ReadingTraceLimits.MAX_VISITS)

    // 累計は**大きい方**を採る。保持件数（最大30）と別に数えているので、
    // マージ後の件数からは復元できない。足すと同じ閲覧を二重に数える。
    // 結合後の保持件数を下回らせないのは、検証（累計 >= 保持件数）を満たすため。
    val totalVisitCount = maxOf(local.totalVisitCount, imported.totalVisitCount, visits.size)

    // **返事を持つ側が勝つ。** 「新しい方が残る」と期待した利用者には意外に映るが、
    // ひとことは本文から作り直せるのに対し、返事は二度と作れない。
    val reflectionFromImported = adoptsImportedReflection(local.reflection, imported.reflection)
    val reflection = if (reflectionFromImported) imported.reflection else local.reflection

    // AI俯瞰要約は**採用後の累計と噛み合う側だけ**を残し、どちらも噛み合わなければ
    // 3つまとめて捨てる。訪問履歴から作り直せるので、捨てても失うものが無い。
    // **種別だけ残さない** — 内容の無い前置きが出る。
    val summarySource = when {
        local.aiSummaryVisitCount == totalVisitCount -> local
        imported.aiSummaryVisitCount == totalVisitCount -> imported
        else -> null
    }

    // **印は `aiSummary` の一族に見えるが、捨ててはいけない側。** 印は*その内容*への
    // 意図なので、生成し直すと別の文が出て意図とずれる（→ reunion_card §6）。
    // 性質としては返事と同じ「守る側」なので、持っている側を優先する。
    val markSource = when {
        local.hasMark && imported.hasMark ->
            if (markedAt(imported) > markedAt(local)) imported else local
        local.hasMark -> local
        imported.hasMark -> imported
        else -> null
    }

    return ReadingTrace(
        vaultRelativePath = local.vaultRelativePath,
        // 表示名は採用した対話の側に揃える。対話と食い違う名前を出さないため。
        noteTitle = if (reflectionFromImported) imported.noteTitle else local.noteTitle,
        documentId = local.documentId,
        visits = visits,
        aiSummary = summarySource?.aiSummary,
        aiSummaryVisitCount = summarySource?.aiSummaryVisitCount,
        aiSummaryKind = summarySource?.aiSummaryKind,
        totalVisitCount = totalVisitCount,
        reflection = reflection,
        markedAtEpochMillis = markSource?.markedAtEpochMillis,
        markedSummary = markSource?.markedSummary,
        markedKind = markSource?.markedKind,
        schemaVersion = READING_TRACE_SCHEMA_VERSION
    )
}

/** マージで失われる返事がどちら側のものか。 */
internal enum class DroppedReplySide { LOCAL, IMPORTED }

/**
 * このマージで**どちらの返事が失われるか**。両方に異なる返事があるときだけ答えが出る。
 *
 * 1ノート1組の [Reflection] は構造上マージできないので、両方に返事があれば必ず片方が消える。
 * 規則で決まるのは「どちらを残すか」までで、失われること自体は避けられない。
 * **だから確定前に件数で見せる**（→ reading_trace_backup §9）。
 *
 * **方向を数えるのが要点。** 規則は「返事を持つ側／新しい側が残る」なので、
 * 端末側が新しければ失われるのは**退避側**である。方向を見ずに1つの件数へまとめると、
 * 通常の往復（書き出した後に返事を書き足す）で**実際と逆の告知**になる。
 *
 * **判定は [mergeReadingTraces] の結果から引く。** 同じ規則を2度書くと必ず片方が古くなる。
 */
internal fun droppedReplySide(local: ReadingTrace, imported: ReadingTrace): DroppedReplySide? {
    val localReply = local.reflection?.reply ?: return null
    val importedReply = imported.reflection?.reply ?: return null
    // 中身が同じなら、どちらが残っても失うものは無い。
    if (localReply == importedReply) return null
    return if (mergeReadingTraces(local, imported).reflection?.reply == localReply) {
        DroppedReplySide.IMPORTED
    } else {
        DroppedReplySide.LOCAL
    }
}

/**
 * 退避側の対話を採るか。**同点は端末側**（いま使っている側を動かさない）。
 *
 * 返事の有無を日時より先に見るのが要点で、これが「再生成できないものを守る」の実体。
 */
private fun adoptsImportedReflection(local: Reflection?, imported: Reflection?): Boolean = when {
    imported == null -> false
    local == null -> true
    local.hasReply != imported.hasReply -> imported.hasReply
    else -> reflectionAt(imported) > reflectionAt(local)
}

/** 対話の新しさ。返事があればその日時、無ければひとことの日時。 */
private fun reflectionAt(reflection: Reflection): Long =
    reflection.repliedAtEpochMillis ?: reflection.remarkedAtEpochMillis

private fun markedAt(trace: ReadingTrace): Long = trace.markedAtEpochMillis ?: 0L
