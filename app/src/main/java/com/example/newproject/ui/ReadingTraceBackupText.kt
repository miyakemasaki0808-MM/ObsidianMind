package com.example.newproject.ui

import com.example.newproject.model.ReadingTraceBackupStep
import com.example.newproject.model.ReadingTraceImportPlan
import com.example.newproject.model.ReadingTraceImportWithholdReason
import com.example.newproject.model.WithheldImport
import com.example.newproject.model.state.ReadingTraceBackupState

// ---------------------------------------------------------------------------
// 退避画面の文面。Composeを起動せずJVMテストできるよう純関数に切り出す
// （整理画面の `ReadingTraceCleanupText` と同じ切り分け）。
//
// **文面の設計方針:** この機能でユーザーが本当に知りたいのは1つだけ —
// **自分が書いた言葉が失われるのか。** 件数を並べるより先にそこへ答える。
// 「上書き」「マージ」という語は内部の処理を指しているので、
// ユーザーから見た言葉で言い直す。
//
// **余白メモは両方が残る。** 配列なので合流でき、どちらかを選ぶ必要が無い
// （1ノート1組だった旧ひとことは、両方に返事があれば必ず片方が消えていた）。
// 残るのは「入りきらなかった」という境界だけで、それは損失ではなく**保留**である。
// ---------------------------------------------------------------------------

/** 実行中の見出し。**適用だけは「途中で止めると戻せない」段階**なので言い方を変える。 */
fun backupStepLabel(step: ReadingTraceBackupStep): String = when (step) {
    ReadingTraceBackupStep.EXPORT_READ -> "読書痕跡を集めています…"
    ReadingTraceBackupStep.IMPORT_SCAN -> "退避ファイルを調べています…"
    ReadingTraceBackupStep.IMPORT_APPLY -> "読み戻しています…"
}

/** 進捗。件数が未確定のあいだは数を出さない（0/0 は「終わった」に見える）。 */
fun backupProgressText(state: ReadingTraceBackupState.Working): String =
    if (state.total <= 0) "" else "${state.done} / ${state.total}"

/** 書き出しの結果。**読めなかった分を隠さない。** */
fun exportSummary(state: ReadingTraceBackupState.Exported): String {
    val head = "${state.written}件の読書痕跡を書き出しました。"
    if (state.unreadableKeys.isEmpty()) return head
    return head + "\n${state.unreadableKeys.size}件は壊れていて読み取れなかったため、含まれていません。"
}

/**
 * 読めなかった痕跡の在り処。
 *
 * **ノート名も相対パスも出せない** — 中身を読めておらず、ファイル名は相対パスの
 * ハッシュなので逆に辿れない。**ファイル名なら現物へ辿り着ける**ので、それを出す。
 */
fun unreadableTraceLocation(key: String): String = "_ReadingTraces/$key.json"

/** 下見の結果。**先に「失われるもの」を言う。** */
fun importPlanSummary(plan: ReadingTraceImportPlan): String {
    val counts = "新しく増えるのが${plan.added}件、既にある痕跡と合わせるのが${plan.merged}件です。"
    val held = plan.withheld.count {
        it.reason == ReadingTraceImportWithholdReason.MEMOS_OVER_CAPACITY
    }
    if (held == 0) return "失われるメモはありません。\n" + counts
    // **保留は損失ではない。** 端末側も退避ファイル側も無傷で残ると明示する。
    return "${held}件のノートは、合わせるとメモが上限を超えるのでそのままにします。" +
        "どちらのメモも消えません。\n" + counts
}

/**
 * 下見を作り直したときの但し書き。**なぜ同じ画面をもう一度見せられたのかを言う。**
 *
 * ここで一番大事なのは「まだ書いていない」こと。これが伝わらないと、
 * 押した操作がどこまで進んだのか分からないまま二度目を押すことになる。
 */
fun revisedPlanNotice(): String =
    "確定しようとしたときには、この端末の読書痕跡が下見のときから変わっていました。" +
        "まだ1件も書き戻していません。下の内容で改めて確認してください。"

/** 読み戻しの結果。**中断したことを結果と別に言わない**（件数と同じ場所で言う）。 */
fun importResultSummary(state: ReadingTraceBackupState.Imported): String {
    val head = if (state.interrupted) {
        "途中で中止しました。ここまでに"
    } else {
        "読み戻しました。"
    }
    val counts = "${state.added}件を追加し、${state.merged}件を既存の痕跡と合わせました。"
    val withheld = if (state.withheld.isEmpty()) {
        ""
    } else {
        "\n${state.withheld.size}件は適用できませんでした。"
    }
    return head + counts + withheld
}

/** 適用できなかった1件の説明。**理由ごとに次の行動が違うので、同じ言葉にしない。** */
fun withheldImportText(item: WithheldImport): String {
    val note = item.vaultRelativePath ?: "ノート不明"
    return when (item.reason) {
        ReadingTraceImportWithholdReason.UNREADABLE_ENTRY ->
            "退避ファイルの中の1件を読み取れませんでした（どのノートのものかも分かりません）。"

        // **「無かった」と書かない。** 読めなかっただけで、そこには痕跡がある。
        // 「無い」と読ませると、上書きされなかったことが不具合に見える。
        ReadingTraceImportWithholdReason.LOCAL_UNREADABLE ->
            "$note は、この端末側の痕跡を読み取れませんでした。" +
                "上書きしてよいか確かめられないので書き戻していません。"

        ReadingTraceImportWithholdReason.LOCAL_CHANGED ->
            "$note は、書き戻す直前にこの端末側が変わったため見送りました。もう一度お試しください。"

        ReadingTraceImportWithholdReason.SAVE_FAILED ->
            "$note の痕跡を書き込めませんでした。"

        // **失敗として書かない。** どちらのメモも無傷で残っているので、
        // ユーザーが次にするのは「1件消してもう一度」であって再試行ではない。
        ReadingTraceImportWithholdReason.MEMOS_OVER_CAPACITY ->
            "$note は、合わせるとメモが上限を超えるのでそのままにしました。" +
                "どちらのメモも消えていません。"
    }
}
