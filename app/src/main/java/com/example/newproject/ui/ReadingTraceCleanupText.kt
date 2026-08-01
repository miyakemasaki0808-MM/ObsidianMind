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
        "Vault のフォルダをうまく読み取れませんでした。" +
            "この状態ではすべてのノートが「無い」ように見えてしまうため、判定していません。" +
            "同期の完了を待ってから開き直してください。"

    OrphanBlockReason.TOO_MANY_CANDIDATES ->
        "見つからないノートが${state.candidateCount}件と多すぎます。" +
            "Vault の同期が終わっていないか、別のフォルダが選ばれている可能性があります。" +
            "念のため何も削除対象にしていません。"
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
        "同じフォルダのノートがまとめて見つかりませんでした。" +
            "フォルダごと削除した場合と、フォルダを読み取れなかった場合を区別できないため残します。"

    OrphanWithholdReason.UNREADABLE_FOLDER ->
        "このフォルダの中身を読み取れませんでした。ノートが本当に無いのかを確認できません。"

    OrphanWithholdReason.UNRESOLVABLE ->
        "痕跡そのものを読み取れませんでした。何を削除することになるのか確認できないため残します。"
}
