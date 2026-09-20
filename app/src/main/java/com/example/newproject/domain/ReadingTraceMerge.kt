package com.example.newproject.domain

import com.example.newproject.model.READING_TRACE_SCHEMA_VERSION
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.mergeMarginMemos

// ---------------------------------------------------------------------------
// 読み戻しの突き合わせ規則。Android型を持たない純粋部分なので素のJVMテストで固定する。
// 規則の正本は features/reading_trace_backup.md §5。
//
// **この機能で最も壊れやすいのはここ**である。列挙・読み書き・版管理・checksum は
// 既存の部品をそのまま使うので、新しく間違えられる余地は突き合わせにしか無い。
//
// 貫いている原則は1つ。**再生成できないものを最優先で守る。**
// 訪問履歴とユーザーの余白メモ、そして「まだ考えたい」の印は作り直せない。
// AI俯瞰要約は訪問履歴から作り直せるので、噛み合わなければ捨ててよい。
//
// **メモは配列なので「どちらかを選ぶ」必要が無い。** 1ノート1組だった旧ひとことは
// 構造上マージできず、読み戻しのたびに必ず片方の言葉が消えていた。
// 代わりに、合流が保持上限を超えるという新しい境界ができる（→ [ReadingTraceMergeResult]）。
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
 * 突き合わせの結果。
 *
 * **[HeldOverCapacity] を「失敗」と呼ばない。** 端末側も退避側も無傷のまま残るので、
 * 失われたものは無い。合流できなかったという**報告**である。
 */
internal sealed interface ReadingTraceMergeResult {
    data class Merged(val trace: ReadingTrace) : ReadingTraceMergeResult

    /**
     * 合流すると余白メモが保持上限を超える。**そのノートは無変更のまま保留する。**
     *
     * 切れば「古いものから捨てない」に反し、全部持てば検証と容量に反する。
     * **どちらも選ばない**（→ features/reflect_margin_memo.md 判断5）。
     */
    data object HeldOverCapacity : ReadingTraceMergeResult
}

/**
 * 同じノートの痕跡が端末側と退避側の両方にあるとき、**欄ごとに**マージする。
 *
 * 丸ごと上書きしないのは、訪問は結合でき、累計は規則で決まり、AI要約は作り直せるため。
 * **余白メモも結合できる** — 配列なので、どちらの言葉も残せる。
 *
 * @param local 端末側。**[ReadingTrace.documentId] はこちらを保つ**（退避側の値は無効）。
 */
internal fun mergeReadingTraces(
    local: ReadingTrace,
    imported: ReadingTrace
): ReadingTraceMergeResult {
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

    // **どちらの言葉も残す。** 訪問と違い、ここで古い方を捨てると二度と戻らない。
    val memos = mergeMarginMemos(local.memos, imported.memos)
    // **入りきらないなら、切らずに保留する。** アプリが捨てないのであって、
    // 全部入るという意味ではない（退避ファイルは手元に残り続ける）。
    if (memos.size > ReadingTraceLimits.MAX_MEMOS) return ReadingTraceMergeResult.HeldOverCapacity

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
    // 性質としてはメモと同じ「守る側」なので、持っている側を優先する。
    val markSource = when {
        local.hasMark && imported.hasMark ->
            if (markedAt(imported) > markedAt(local)) imported else local
        local.hasMark -> local
        imported.hasMark -> imported
        else -> null
    }

    return ReadingTraceMergeResult.Merged(
        ReadingTrace(
            vaultRelativePath = local.vaultRelativePath,
            // **表示名は端末側を保つ。** 旧ひとことは採用した対話の側へ揃えていたが、
            // メモは両方残るので「採用した側」が存在しない。
            noteTitle = local.noteTitle,
            documentId = local.documentId,
            visits = visits,
            aiSummary = summarySource?.aiSummary,
            aiSummaryVisitCount = summarySource?.aiSummaryVisitCount,
            aiSummaryKind = summarySource?.aiSummaryKind,
            totalVisitCount = totalVisitCount,
            memos = memos,
            markedAtEpochMillis = markSource?.markedAtEpochMillis,
            markedSummary = markSource?.markedSummary,
            markedKind = markSource?.markedKind,
            schemaVersion = READING_TRACE_SCHEMA_VERSION
        )
    )
}

private fun markedAt(trace: ReadingTrace): Long = trace.markedAtEpochMillis ?: 0L
