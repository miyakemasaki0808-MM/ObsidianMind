package com.example.newproject.domain

import com.example.newproject.model.OrphanAssessment
import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanCandidate
import com.example.newproject.model.OrphanLimits
import com.example.newproject.model.OrphanTraceInfo
import com.example.newproject.model.OrphanWithholdReason
import com.example.newproject.model.WithheldOrphans

// ---------------------------------------------------------------------------
// 読書痕跡の孤児判定。Android型を持たない純粋部分なので素のJVMテストで固定する。
// 結果の型は `controller` と `ui` の両方が触るため葉の `model` にあり、
// ここには判定そのものだけを置く（NoteExcerpt / buildNoteExcerpt と同じ切り分け）。
//
// **この判定が守るべき一点は「不在は証明ではない」こと。**
// 痕跡は `_ReadingTraces/<sha256(相対パス)>.json` に置かれ、対応するノートが
// Vault走査に現れなければ孤児に見える。しかし SAF の列挙は**フォルダ単位で失敗する**
// （プロバイダが子を返さない）ため、「見つからない」は「削除された」と
// 「そのフォルダが読めなかった」の両方を意味しうる。
//
// したがって遮断器も**フォルダ単位**に置く。故障の粒度と判定の粒度を合わせるのが要点で、
// 観測回数を重ねるだけでは持続的な列挙失敗を排除できない（毎回同じフォルダが失敗すれば
// 何回数えても欠け続ける）。
//
// **粒度は「直接の親」ではなく「全祖先」で見る。** 故障は任意の階層で起こり、しかも
// 上位フォルダが**成功したまま空を返す**場合は unreadableFolderPaths にも現れない
// （BFSがその配下へ潜らないので、配下のノートが丸ごと走査から消える）。
// 直接の親で数えると、配下の各フォルダは1件ずつになって遮断器をすり抜ける。
//
// **ルートは祖先として数えない。** 数えると全候補が共通の祖先を持つので
// 「Vault全体で常に1件まで」となり、正当な孤児が2件溜まった時点で永久に掃除できなくなる。
// ルート直下が静かに欠ける場合は候補が広く分散するため、総数の上限で受ける。
//
// **代償を明記する。** フォルダごと削除された場合も「同じフォルダから複数件が欠けた」に
// 見えるので、**まとめて消したノートの痕跡は掃除されない**。これは意図した割り切りで、
// この機能が狙うのは日常的に発生する単発の孤児のほう。安全側に倒している。
// ---------------------------------------------------------------------------

/**
 * 保存済み痕跡のキー集合と、Vault走査の結果から孤児候補を割り出す。
 *
 * [noteKeys] は現存ノートの相対パスをキー化したもの。**集合差だけならファイルを
 * 1つも読まずに済む**（キーは相対パスのハッシュなので突き合わせは文字列比較で足りる）。
 * [resolve] を呼ぶのは差集合に残った候補だけで、しかも [OrphanLimits.maxTotalCandidates]
 * で件数が縛られている。
 *
 * [unreadableFolderPaths] は走査で列挙に失敗したフォルダの相対パス（ルートなら空文字）。
 *
 * [resolve] が null を返した候補は「中身を確認できなかった」として保留する。**消さない。**
 */
fun assessReadingTraceOrphans(
    traceKeys: Set<String>,
    noteKeys: Set<String>,
    unreadableFolderPaths: Set<String>,
    limits: OrphanLimits = OrphanLimits(),
    resolve: (key: String) -> OrphanTraceInfo?
): OrphanAssessment {
    // ルートが読めていないなら全ノートが不在に見える。候補を数える意味がない。
    if (ROOT_PATH in unreadableFolderPaths) {
        return OrphanAssessment.Blocked(OrphanBlockReason.VAULT_ROOT_UNREADABLE, candidateCount = 0)
    }

    val candidateKeys = traceKeys - noteKeys
    // 件数の判定は resolve の前に行う。後ろに置くと、異常時ほど大量に読むことになる。
    if (candidateKeys.size > limits.maxTotalCandidates) {
        return OrphanAssessment.Blocked(
            OrphanBlockReason.TOO_MANY_CANDIDATES,
            candidateCount = candidateKeys.size
        )
    }

    val resolved = mutableListOf<OrphanCandidate>()
    var unresolvable = 0
    // 並びを決定的にしておく（画面の順序と、テストの安定のため）。
    for (key in candidateKeys.sorted()) {
        val info = resolve(key)
        if (info == null) {
            unresolvable++
            continue
        }
        resolved += OrphanCandidate(
            key = key,
            vaultRelativePath = info.vaultRelativePath,
            noteTitle = info.noteTitle,
            totalVisitCount = info.totalVisitCount,
            lastVisitAtEpochMillis = info.lastVisitAtEpochMillis
        )
    }

    val orphans = mutableListOf<OrphanCandidate>()
    val withheld = mutableListOf<WithheldOrphans>()

    // 読めなかったサブツリーの分を先に落とす。
    val (unreadableItems, readableItems) = resolved.partition {
        isUnderUnreadableFolder(parentVaultPath(it.vaultRelativePath), unreadableFolderPaths)
    }
    unreadableItems.groupBy { parentVaultPath(it.vaultRelativePath) }
        .forEach { (folderPath, items) ->
            withheld += WithheldOrphans(folderPath, items.size, OrphanWithholdReason.UNREADABLE_FOLDER)
        }

    // 上限を超えたフォルダを洗い出し、その配下をまとめて保留する。
    val blocked = readableItems
        .flatMap { breakerGroupPaths(it.vaultRelativePath) }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > limits.maxCandidatesPerFolder }
        .keys
    // 報告は**最も浅いフォルダ**へ寄せる（`ideas` と `ideas/a` が両方該当しても1件）。
    val shallowest = blocked.filterNot { path ->
        blocked.any { other -> other != path && isDescendantFolder(path, other) }
    }
    readableItems
        .groupBy { item -> shallowest.firstOrNull { it in breakerGroupPaths(item.vaultRelativePath) } }
        .forEach { (blockedBy, items) ->
            if (blockedBy == null) {
                orphans += items
            } else {
                withheld += WithheldOrphans(blockedBy, items.size, OrphanWithholdReason.FOLDER_WIDE_ABSENCE)
            }
        }
    if (unresolvable > 0) {
        withheld += WithheldOrphans(ROOT_PATH, unresolvable, OrphanWithholdReason.UNRESOLVABLE)
    }

    return OrphanAssessment.Assessed(
        orphans = orphans.sortedBy { it.vaultRelativePath },
        withheld = withheld.sortedWith(compareBy({ it.reason.ordinal }, { it.folderPath }))
    )
}

/** Vaultルートを表す相対パス。ルート直下のノートの親はこれになる。 */
private const val ROOT_PATH = ""

/**
 * 遮断器が候補を数える単位。**直接の親と、ルートを除く全祖先。**
 *
 * - `ideas/2026/habit.md` → `["ideas", "ideas/2026"]`
 * - `habit.md` → `[""]`（ルート直下。ルートは**直接の親のときだけ**数える）
 *
 * 全祖先を見るのは、故障が任意の階層で起こるため。直接の親だけだと、上位フォルダが
 * 静かに空を返したときに配下の各フォルダが1件ずつになってすり抜ける。
 *
 * **ルートを祖先としては数えない。** 数えると全候補が共通の祖先を持つので
 * 「Vault全体で常に1件まで」となり、正当な孤児が2件溜まった時点で永久に掃除できなくなる。
 * ただし**ルート直下のノートどうしは直接の親が同じ**なので、そこは従来どおり括られる。
 */
internal fun breakerGroupPaths(vaultRelativePath: String): List<String> {
    val folder = parentVaultPath(vaultRelativePath)
    if (folder.isEmpty()) return listOf(ROOT_PATH)
    val segments = folder.split('/')
    return segments.indices.map { segments.take(it + 1).joinToString("/") }
}

/** [candidate] が [ancestor] の配下か（同一は含めない）。 */
internal fun isDescendantFolder(candidate: String, ancestor: String): Boolean =
    ancestor.isEmpty() && candidate.isNotEmpty() || candidate.startsWith("$ancestor/")

/** `ideas/2026/habit.md` → `ideas/2026`、`habit.md` → `""`（ルート）。 */
internal fun parentVaultPath(vaultRelativePath: String): String =
    vaultRelativePath.substringBeforeLast('/', ROOT_PATH)

/**
 * [folderPath] が、列挙に失敗したフォルダ自身かその配下にあるか。
 *
 * 区切りを足して比較するのは前方一致の取り違えを避けるため
 * （`ideas2` が `ideas` の配下と見なされないように）。
 *
 * **ルート（空文字）は全パスの祖先として特別扱いする。** 区切りを足す比較では
 * `"ideas".startsWith("/")` になって偽を返すため、**ルートが読めなかったのに
 * ネストしたパスが「読めている」と判定されていた**。走査がルートで止まっていれば
 * 配下は1件も見えていないので、そこから不在を結論してはいけない。
 */
internal fun isUnderUnreadableFolder(folderPath: String, unreadableFolderPaths: Set<String>): Boolean =
    unreadableFolderPaths.any { unreadable ->
        unreadable.isEmpty() || folderPath == unreadable || folderPath.startsWith("$unreadable/")
    }

/**
 * 再走査でノートを確かめた結果。
 *
 * **「無い」と「確かめられなかった」を畳まない。** 畳むと、
 * 走査が失敗しただけの状態を「削除してよい」と読んでしまう
 * （→ 生きた痕跡を消す）。逆に「確認できなかった」を「生き返った」へ
 * 畳むと、候補が一覧から消えて再試行できなくなる。
 */
internal enum class NotePresence { PRESENT, MISSING, INDETERMINATE }

/**
 * 削除の直前に、対象のノートが本当に不在かを確かめる。
 *
 * **洗い出しと削除で同じ判定を使うためにここへ置く。** 同じ意味の判定が
 * 2箇所にあると、片方だけ強い状態が生まれる（実際そうなっていた —
 * 洗い出しはルート読取失敗を止められたのに、削除直前だけ素通りしていた）。
 *
 * @param keyOf 相対パスから痕跡キーを作る関数。`data` 層の実装を注入して、この関数を純粋に保つ。
 */
internal fun notePresenceAfterRescan(
    targetKey: String,
    targetVaultRelativePath: String,
    notes: List<String>,
    unreadableFolderPaths: Set<String>,
    keyOf: (String) -> String
): NotePresence = when {
    notes.any { keyOf(it) == targetKey } -> NotePresence.PRESENT
    // **不在を結論する前に、読めなかった枝を先に見る。**
    isUnderUnreadableFolder(parentVaultPath(targetVaultRelativePath), unreadableFolderPaths) ->
        NotePresence.INDETERMINATE
    else -> NotePresence.MISSING
}
