package com.example.newproject.ui

import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanWithholdReason
import com.example.newproject.model.WithheldOrphans
import com.example.newproject.model.state.ReadingTraceCleanupState

// ---------------------------------------------------------------------------
// 整理画面の文面。Composeを起動せずJVMテストできるよう純関数に切り出す。
//
// **文面の設計方針:** 内部語（孤児・遮断器・列挙）をそのまま出さない。
// ユーザーにとっての事実は「ノートが見つからない」「読み取れなかった」であって、
// こちらの実装都合ではない。一方で**「候補ゼロ」と「判定できなかった」は
// 必ず別の言葉にする** — 同じ「何も無い」に見せると、遮断器が働いている状態を
// 「掃除するものが無い」と読み違える。
// ---------------------------------------------------------------------------

/** 判定ごと見送ったときの説明。**「孤児は無かった」と読めない言い方にする。** */
fun blockedExplanation(state: ReadingTraceCleanupState.Blocked): String = when (state.reason) {
    OrphanBlockReason.VAULT_ROOT_UNREADABLE ->
        "Vault のフォルダを読み取れなかったので、判定していません。同期が終わったら開き直せます。"

    OrphanBlockReason.TOO_MANY_CANDIDATES ->
        "見つからないノートが${state.candidateCount}件と多いので、削除の対象にしていません。" +
            "同期の途中か、別のフォルダかもしれません。"
}

/** 保留した一群の見出し。ルート直下と読み取り失敗は「フォルダ名」で語れない。 */
fun withheldLocation(group: WithheldOrphans): String = when {
    group.reason == OrphanWithholdReason.UNRESOLVABLE -> "内容を確認できない痕跡 ${group.count}件"
    group.folderPath.isEmpty() -> "Vault 直下 ${group.count}件"
    else -> "${group.folderPath} ${group.count}件"
}

/** 保留した理由。**シャドーモードではここが観測対象そのもの。** */
fun withheldReasonText(group: WithheldOrphans): String = when (group.reason) {
    OrphanWithholdReason.FOLDER_WIDE_ABSENCE ->
        "フォルダごと見つかりません。読み取れなかっただけかもしれないので残します。"

    OrphanWithholdReason.UNREADABLE_FOLDER ->
        "このフォルダを読み取れませんでした。ノートがあるか確かめられません。"

    OrphanWithholdReason.UNRESOLVABLE ->
        "痕跡を読み取れないので残します。"
}
